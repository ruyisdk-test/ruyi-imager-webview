package org.ruyisdk.imager;

import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.Arrays;

final class ScsiDisk {
    interface Transport {
        void send(byte[] data, int size) throws IOException;
        void receive(byte[] data, int size) throws IOException;
    }

    private final Transport transport;
    private final int lun;
    private int tag;
    int blockSize;
    long capacity;

    ScsiDisk(Transport transport, int lun) { this.transport = transport; this.lun = lun; }

    private static final class CheckCondition extends IOException {
        CheckCondition(int opcode) { super(String.format("SCSI command %02x rejected by medium", opcode)); }
    }

    static final class NoMedium extends IOException {}

    private void command(byte[] cdb, byte[] data, int size, boolean input) throws IOException {
        ByteBuffer cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);
        cbw.putInt(0x43425355).putInt(++tag).putInt(size).put((byte)(input ? 0x80 : 0))
                .put((byte)lun).put((byte)cdb.length).put(cdb);
        transport.send(cbw.array(), 31);
        if (size > 0) {
            if (input) transport.receive(data, size);
            else transport.send(data, size);
        }
        byte[] bytes = new byte[13];
        transport.receive(bytes, bytes.length);
        ByteBuffer csw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (csw.getInt() != 0x53425355 || csw.getInt() != tag) throw new IOException("Invalid USB storage status");
        int residue = csw.getInt(), status = csw.get();
        if (status == 1) throw new CheckCondition(cdb[0] & 255);
        if (status != 0 || residue != 0) throw new IOException("Incomplete USB storage command");
    }

    void inspect(boolean initial) throws IOException {
        byte[] ready = new byte[6];
        try { command(ready, null, 0, false); }
        catch (CheckCondition changed) {
            if (!initial) throw changed;
            byte[] sense = {3, 0, 0, 0, 18, 0};
            byte[] details = new byte[18];
            command(sense, details, 18, true);
            if ((details[2] & 15) == 2 && details[12] == 0x3a) throw new NoMedium();
            command(ready, null, 0, false);
        }
        byte[] inquiry = new byte[36];
        command(new byte[]{0x12, 0, 0, 0, 36, 0}, inquiry, 36, true);
        if (inquiry[0] != 0 && inquiry[0] != 0x0e) throw new IOException("USB LUN is not a direct-access disk");
        byte[] mode = new byte[4];
        command(new byte[]{0x1a, 8, 0x3f, 0, 4, 0}, mode, 4, true);
        if ((mode[2] & 0x80) != 0) throw new IOException("Storage medium is write-protected");
        byte[] cdb = new byte[10];
        cdb[0] = 0x25;
        ByteBuffer geometry = ByteBuffer.allocate(8);
        command(cdb, geometry.array(), 8, true);
        long last = Integer.toUnsignedLong(geometry.getInt());
        int sector = geometry.getInt();
        if (last == 0xffffffffL) {
            ByteBuffer extended = ByteBuffer.allocate(16);
            extended.put((byte)0x9e).put((byte)0x10).putLong(0).putInt(32);
            geometry = ByteBuffer.allocate(32);
            command(extended.array(), geometry.array(), 32, true);
            last = geometry.getLong();
            sector = geometry.getInt();
        }
        if (last < 0 || sector < 512 || sector > 65536 || Integer.bitCount(sector) != 1
                || last >= Long.MAX_VALUE / sector) throw new IOException("Invalid disk capacity");
        blockSize = sector;
        capacity = (last + 1) * sector;
    }

    void blocks(long lba, byte[] data, int size, boolean read) throws IOException {
        if (size <= 0 || size % blockSize != 0 || lba < 0 || lba > (capacity - size) / blockSize || size > capacity)
            throw new IOException("Transfer outside target disk");
        int count = size / blockSize;
        ByteBuffer cdb;
        if (lba + count - 1 <= 0xffffffffL && count <= 65535) {
            cdb = ByteBuffer.allocate(10);
            cdb.put((byte)(read ? 0x28 : 0x2a)).put((byte)0).putInt((int)lba).put((byte)0).putShort((short)count);
        } else {
            cdb = ByteBuffer.allocate(16);
            cdb.put((byte)(read ? 0x88 : 0x8a)).put((byte)0).putLong(lba).putInt(count);
        }
        command(cdb.array(), data, size, read);
    }

    byte[] fingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sector = new byte[blockSize];
        for (long lba : new long[]{0, capacity / blockSize - 1}) {
            blocks(lba, sector, sector.length, true);
            digest.update(sector);
        }
        return digest.digest();
    }

    void writeAndVerify(InputStream source, long size, byte[] expected) throws Exception {
        if (size <= 0 || size > capacity) throw new IOException("Image does not fit on disk");
        byte[] buffer = new byte[256 * 1024];
        DataInputStream stream = new DataInputStream(source);
        for (long offset = 0; offset < size;) {
            int bytes = (int)Math.min(buffer.length, size - offset);
            int aligned = (bytes + blockSize - 1) / blockSize * blockSize;
            if (aligned != bytes) {
                byte[] tail = new byte[blockSize];
                blocks((offset + bytes) / blockSize, tail, blockSize, true);
                System.arraycopy(tail, 0, buffer, aligned - blockSize, blockSize);
            }
            stream.readFully(buffer, 0, bytes);
            blocks(offset / blockSize, buffer, aligned, false);
            offset += bytes;
        }
        byte[] sync = new byte[10];
        sync[0] = 0x35;
        command(sync, null, 0, false);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (long offset = 0; offset < size;) {
            int bytes = (int)Math.min(buffer.length, size - offset);
            blocks(offset / blockSize, buffer, (bytes + blockSize - 1) / blockSize * blockSize, true);
            digest.update(buffer, 0, bytes);
            offset += bytes;
        }
        if (!Arrays.equals(expected, digest.digest())) throw new IOException("Disk read-back SHA-256 mismatch");
    }
}
