package org.ruyisdk.imager;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;

public class ScsiDiskTest {
    private static final class MemoryDrive implements ScsiDisk.Transport {
        final byte[] disk = new byte[1024 * 1024];
        final List<Integer> commands = new ArrayList<>();
        int opcode, tag, remaining, offset;
        boolean flushed, corrupt, badTag, readOnly;

        public void send(byte[] data, int size) {
            if (remaining > 0) {
                assertEquals(0x2a, opcode);
                assertEquals(remaining, size);
                System.arraycopy(data, 0, disk, offset, size);
                remaining = 0;
                return;
            }
            assertEquals(31, size);
            ByteBuffer cbw = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x43425355, cbw.getInt());
            tag = cbw.getInt(); remaining = cbw.getInt();
            int direction = cbw.get() & 255;
            assertEquals(3, cbw.get());
            int length = cbw.get() & 255;
            opcode = cbw.get() & 255; commands.add(opcode);
            if (opcode == 0x28 || opcode == 0x2a) {
                ByteBuffer command = ByteBuffer.wrap(data);
                assertEquals(10, length);
                assertEquals(opcode == 0x28 ? 128 : 0, direction);
                offset = command.getInt(17) * 512;
                assertEquals(remaining, command.getShort(22) * 512);
            } else if (opcode == 0x35) flushed = true;
        }

        public void receive(byte[] data, int size) {
            if (remaining == 0) {
                assertEquals(13, size);
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(0x53425355).putInt(badTag ? tag + 1 : tag).putInt(0).put((byte)0);
                return;
            }
            assertEquals(remaining, size); remaining = 0;
            if (opcode == 0x12) data[0] = 0;
            else if (opcode == 0x1a) data[2] = (byte)(readOnly ? 128 : 0);
            else if (opcode == 0x25) ByteBuffer.wrap(data).putInt(disk.length / 512 - 1).putInt(512);
            else {
                assertEquals(0x28, opcode);
                System.arraycopy(disk, offset, data, 0, size);
                if (corrupt && flushed) data[0] ^= 1;
            }
        }
    }

    @Test public void writesAcrossTransfersAndPreservesLastSectorTail() throws Exception {
        MemoryDrive drive = new MemoryDrive();
        Arrays.fill(drive.disk, (byte)0x5a);
        byte[] image = new byte[300001];
        new Random(419).nextBytes(image);
        ScsiDisk disk = new ScsiDisk(drive, 3);
        disk.inspect(true);
        disk.writeAndVerify(new ByteArrayInputStream(image), image.length, MessageDigest.getInstance("SHA-256").digest(image));
        assertArrayEquals(image, Arrays.copyOf(drive.disk, image.length));
        for (int index = image.length; index < drive.disk.length; index++) assertEquals(0x5a, drive.disk[index]);
        assertEquals(List.of(0, 0x12, 0x1a, 0x25, 0x2a, 0x28, 0x2a, 0x35, 0x28, 0x28), drive.commands);
    }

    @Test public void rejectsChangedReadbackAndStaleStatus() throws Exception {
        MemoryDrive drive = new MemoryDrive();
        ScsiDisk disk = new ScsiDisk(drive, 3);
        disk.inspect(true);
        byte[] image = new byte[512]; drive.corrupt = true;
        assertThrows(IOException.class, () -> disk.writeAndVerify(new ByteArrayInputStream(image), image.length, MessageDigest.getInstance("SHA-256").digest(image)));
        drive.badTag = true;
        assertThrows(IOException.class, () -> disk.inspect(false));
    }

    @Test public void oversizedImageSendsNoWrite() throws Exception {
        MemoryDrive drive = new MemoryDrive();
        ScsiDisk disk = new ScsiDisk(drive, 3);
        disk.inspect(true);
        assertThrows(IOException.class, () -> disk.writeAndVerify(new ByteArrayInputStream(new byte[0]), disk.capacity + 1, new byte[32]));
        assertEquals(List.of(0, 0x12, 0x1a, 0x25), drive.commands);
    }

    @Test public void rejectsWriteProtectedMediumBeforeListing() {
        MemoryDrive drive = new MemoryDrive();
        drive.readOnly = true;
        assertThrows(IOException.class, () -> new ScsiDisk(drive, 3).inspect(true));
        assertEquals(List.of(0, 0x12, 0x1a), drive.commands);
    }
}
