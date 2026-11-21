package org.ruyisdk.imager;

import android.hardware.usb.*;
import android.util.Base64;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

final class UsbHost implements Closeable {
    interface Access {
        void authorize(UsbDevice device) throws Exception;
        int pick(String[] names) throws Exception;
    }

    private final UsbManager manager;
    private final Access access;
    private final Map<String, UsbDevice> boards = new LinkedHashMap<>();
    private final Map<String, Disk> choices = new LinkedHashMap<>(), targets = new LinkedHashMap<>();
    private Link fastboot;
    private String opened;
    private final Map<String, Long> detachCounts = new ConcurrentHashMap<>();

    UsbHost(UsbManager manager, Access access) { this.manager = manager; this.access = access; }
    static String token() { return UUID.randomUUID().toString(); }
    static String label(UsbDevice device) {
        String name = device.getProductName();
        return String.format(Locale.ROOT, "%s · %04x:%04x", name == null ? "USB" : name, device.getVendorId(), device.getProductId());
    }

    private static boolean matches(UsbInterface iface, boolean disk) {
        return iface.getInterfaceClass() == (disk ? 8 : 255) && iface.getInterfaceSubclass() == (disk ? 6 : 66)
                && iface.getInterfaceProtocol() == (disk ? 80 : 3);
    }

    private record Port(UsbDevice device, UsbConfiguration config, UsbInterface iface, UsbEndpoint input, UsbEndpoint output) {}

    private static List<Port> ports(UsbDevice device, boolean disk) {
        List<Port> ports = new ArrayList<>();
        for (int configIndex = 0; configIndex < device.getConfigurationCount(); configIndex++) {
            UsbConfiguration config = device.getConfiguration(configIndex);
            for (int index = 0; index < config.getInterfaceCount(); index++) {
                UsbInterface iface = config.getInterface(index);
                if (!matches(iface, disk)) continue;
                UsbEndpoint input = null, output = null;
                for (int number = 0; number < iface.getEndpointCount(); number++) {
                    UsbEndpoint endpoint = iface.getEndpoint(number);
                    if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) input = endpoint;
                    else output = endpoint;
                }
                if (input != null && output != null) ports.add(new Port(device, config, iface, input, output));
            }
        }
        return ports;
    }

    private void present(UsbDevice device) throws IOException {
        UsbDevice current = manager.getDeviceList().get(device.getDeviceName());
        if (current == null || current.getDeviceId() != device.getDeviceId() || !manager.hasPermission(current))
            throw new IOException("USB device disconnected or authorization expired");
    }

    private static class Link implements AutoCloseable, ScsiDisk.Transport {
        final Port port;
        final UsbDeviceConnection connection;
        Link(Port port, UsbDeviceConnection connection) { this.port = port; this.connection = connection; }
        public void close() { connection.releaseInterface(port.iface); connection.close(); }
        public void send(byte[] data, int size) throws IOException {
            int offset = 0;
            while (offset < size) {
                int count = connection.bulkTransfer(port.output, data, offset, size - offset, 30000);
                if (count <= 0) throw new IOException("USB bulk write failed or timed out");
                offset += count;
            }
        }
        public void receive(byte[] data, int size) throws IOException {
            if (connection.bulkTransfer(port.input, data, size, 30000) != size) throw new IOException("Incomplete USB storage response");
        }
    }

    private Link connect(Port port) throws IOException {
        present(port.device);
        UsbDeviceConnection connection = manager.openDevice(port.device);
        if (connection == null) throw new IOException("Cannot open USB device");
        try {
            byte[] active = new byte[1];
            if (connection.controlTransfer(0x80, 8, 0, 0, active, 1, 3000) != 1)
                throw new IOException("Cannot determine active USB configuration");
            if ((active[0] & 255) != port.config.getId()) {
                if (matches(port.iface, true) || !connection.setConfiguration(port.config))
                    throw new IOException("USB configuration unavailable");
            }
            if (!connection.claimInterface(port.iface, false))
                throw new IOException("USB interface is in use. Eject the drive in Android and try again.");
            if (port.iface.getAlternateSetting() != 0 && !connection.setInterface(port.iface))
                throw new IOException("Cannot select USB alternate setting");
            return new Link(port, connection);
        } catch (Exception failure) { connection.close(); throw failure; }
    }

    private JSONObject endpoint(UsbEndpoint endpoint) throws JSONException {
        return new JSONObject().put("type", "bulk").put("direction", endpoint.getDirection() == 128 ? "in" : "out")
                .put("endpointNumber", endpoint.getEndpointNumber()).put("packetSize", endpoint.getMaxPacketSize());
    }

    private JSONObject board(String id, UsbDevice device) throws JSONException {
        Port port = ports(device, false).get(0);
        JSONObject alt = new JSONObject().put("alternateSetting", port.iface.getAlternateSetting()).put("interfaceClass", 255)
                .put("interfaceSubclass", 66).put("interfaceProtocol", 3)
                .put("endpoints", new JSONArray().put(endpoint(port.input)).put(endpoint(port.output)));
        JSONObject iface = new JSONObject().put("interfaceNumber", port.iface.getId()).put("alternate", alt).put("alternates", new JSONArray().put(alt));
        JSONObject config = new JSONObject().put("configurationValue", port.config.getId()).put("interfaces", new JSONArray().put(iface));
        return new JSONObject().put("id", id).put("productName", device.getProductName()).put("vendorId", device.getVendorId())
                .put("productId", device.getProductId()).put("serialNumber", device.getSerialNumber()).put("configuration", config);
    }

    JSONObject chooseBoard() throws Exception {
        List<UsbDevice> available = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) if (!ports(device, false).isEmpty()) available.add(device);
        if (available.isEmpty()) throw new IOException("No fastboot device connected");
        String[] labels = available.stream().map(UsbHost::label).toArray(String[]::new);
        UsbDevice selected = available.get(access.pick(labels));
        access.authorize(selected);
        present(selected);
        for (var entry : boards.entrySet()) if (entry.getValue().equals(selected)) return board(entry.getKey(), selected);
        String id = token();
        boards.put(id, selected);
        return board(id, selected);
    }

    JSONArray authorized() throws JSONException {
        JSONArray result = new JSONArray();
        for (var entry : boards.entrySet()) {
            try { present(entry.getValue()); } catch (IOException gone) { continue; }
            result.put(board(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    void open(String id) throws IOException {
        UsbDevice device = boards.get(id);
        if (device == null) throw new IOException("Unselected USB device");
        closeBoard(opened);
        fastboot = connect(ports(device, false).get(0));
        opened = id;
    }

    void closeBoard(String id) {
        if (fastboot != null && Objects.equals(id, opened)) { fastboot.close(); fastboot = null; opened = null; }
    }

    Object transfer(String method, JSONObject args) throws Exception {
        if (fastboot == null || !args.getString("id").equals(opened)) throw new IOException("USB device is closed");
        present(fastboot.port.device);
        if (method.equals("usbOut")) {
            byte[] bytes = Base64.decode(args.getString("data"), Base64.DEFAULT);
            if (bytes.length > 262144 || args.getInt("endpoint") != fastboot.port.output.getEndpointNumber()) throw new IOException("Invalid USB write");
            fastboot.send(bytes, bytes.length);
            return bytes.length;
        }
        int size = args.getInt("size");
        if (size < 1 || size > 65536 || args.getInt("endpoint") != fastboot.port.input.getEndpointNumber()) throw new IOException("Invalid USB read");
        byte[] data = new byte[size];
        int count = fastboot.connection.bulkTransfer(fastboot.port.input, data, size, 300000);
        if (count <= 0) throw new IOException("USB read failed or timed out");
        return Base64.encodeToString(data, 0, count, Base64.NO_WRAP);
    }

    private record Disk(Port port, long generation, int lun, int sector, long capacity, byte[] fingerprint, String label) {}

    boolean hasAuthorizedTarget() {
        return fastboot != null && manager.hasPermission(fastboot.port.device) ||
                targets.values().stream().anyMatch(disk -> manager.hasPermission(disk.port.device));
    }

    JSONArray listDisks() throws Exception {
        choices.clear();
        JSONArray result = new JSONArray();
        IOException failure = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            List<Port> ports = ports(device, true);
            if (ports.isEmpty()) continue;
            try { access.authorize(device); } catch (IOException denied) { failure = denied; continue; }
            for (Port port : ports) try (Link link = connect(port)) {
                byte[] response = new byte[1];
                int count = link.connection.controlTransfer(0xa1, 0xfe, 0, port.iface.getId(), response, 1, 3000);
                int last = count == 1 ? response[0] & 255 : 0;
                if (last > 15) throw new IOException("Invalid USB storage LUN count");
                for (int lun = 0; lun <= last; lun++) {
                    ScsiDisk scsi = new ScsiDisk(link, lun);
                    try { scsi.inspect(true); } catch (ScsiDisk.NoMedium empty) { continue; }
                    String label = label(device) + " · LUN " + lun + " · " + scsi.capacity + " B";
                    String id = token();
                    choices.put(id, new Disk(port, generation(device), lun, scsi.blockSize, scsi.capacity, scsi.fingerprint(), label));
                    result.put(new JSONObject().put("id", id).put("label", label));
                }
            } catch (IOException error) { failure = error; }
        }
        if (result.length() == 0 && failure != null) throw failure;
        return result;
    }

    private ScsiDisk revalidate(Disk disk, Link link) throws Exception {
        if (generation(disk.port.device) != disk.generation) throw new IOException("Selected USB reader was disconnected");
        ScsiDisk scsi = new ScsiDisk(link, disk.lun);
        scsi.inspect(false);
        if (scsi.capacity != disk.capacity || scsi.blockSize != disk.sector || !Arrays.equals(disk.fingerprint, scsi.fingerprint()))
            throw new IOException("Selected storage medium changed");
        return scsi;
    }

    JSONObject selectDisk(String id) throws Exception {
        Disk disk = choices.remove(id);
        if (disk == null) throw new IOException("Expired disk choice");
        try (Link link = connect(disk.port)) { revalidate(disk, link); }
        if (targets.size() >= 128) targets.clear();
        String target = token();
        targets.put(target, disk);
        return new JSONObject().put("id", target).put("label", disk.label);
    }

    void write(String id, InputStream image, long size, byte[] digest) throws Exception {
        Disk disk = targets.remove(id);
        if (disk == null) throw new IOException("Expired target disk");
        try (Link link = connect(disk.port)) { revalidate(disk, link).writeAndVerify(image, size, digest); }
    }

    List<String> detached(String name) {
        List<String> gone = new ArrayList<>();
        boards.entrySet().removeIf(entry -> {
            if (!entry.getValue().getDeviceName().equals(name)) return false;
            closeBoard(entry.getKey()); gone.add(entry.getKey()); return true;
        });
        targets.values().removeIf(disk -> disk.port.device.getDeviceName().equals(name));
        choices.values().removeIf(disk -> disk.port.device.getDeviceName().equals(name));
        return gone;
    }

    void invalidateAttachment(String name) { detachCounts.merge(name, 1L, Long::sum); }
    private long generation(UsbDevice device) { return detachCounts.getOrDefault(device.getDeviceName(), 0L); }

    public void close() { closeBoard(opened); boards.clear(); choices.clear(); targets.clear(); }
}
