package org.ruyisdk.imager;

import android.hardware.usb.*;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Base64;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

final class PageSession {
    final ImagerActivity activity;
    final JavaScriptReplyProxy frame;
    final Streams streams = new Streams();
    private final Documents files;
    private final UsbHost usb;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    volatile boolean closed, flashing, writing;
    private String root;

    PageSession(ImagerActivity activity, JavaScriptReplyProxy frame) {
        this.activity = activity;
        this.frame = frame;
        files = new Documents(activity.getContentResolver(), activity.getCacheDir());
        usb = new UsbHost(activity.getSystemService(UsbManager.class), activity.access(this));
    }

    private void check() throws IOException { if (closed) throw new IOException("Page session ended"); }

    void message(JSONObject request) throws JSONException {
        int id = request.getInt("id");
        String method = request.getString("method");
        JSONObject args = request.getJSONObject("args");
        io.execute(() -> {
            JSONObject reply = new JSONObject();
            try {
                check();
                reply.put("value", dispatch(method, args));
            } catch (Exception failure) {
                Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
                try {
                    reply.put("error", cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    reply.put("name", cause instanceof CancellationException ? "AbortError" :
                            cause instanceof FileNotFoundException ? "NotFoundError" : "Error");
                } catch (JSONException ignored) {}
            }
            try { reply.put("id", id); } catch (JSONException ignored) {}
            activity.runOnUiThread(() -> { if (!closed) frame.postMessage(reply.toString()); });
        });
    }

    Object dispatch(String method, JSONObject args) throws Exception {
        if (method.startsWith("file") && (root == null || !root.equals(args.getString("root")))) throw new IOException("Directory authorization expired");
        switch (method) {
            case "hello": return streams.key;
            case "directory": {
                if (flashing) throw new IOException("Flashing is in progress");
                Uri selected = activity.pickDirectory(this);
                check();
                files.select(selected);
                root = UsbHost.token();
                return new JSONObject().put("root", root).put("name", files.name);
            }
            case "fileLookup": files.resolve(Documents.parts(args.getJSONArray("parts")), args.getBoolean("directory"), args.getBoolean("create")); return null;
            case "fileRead": {
                Uri uri = files.resolve(Documents.parts(args.getJSONArray("parts")), false, false);
                return streams.issue(() -> files.read(uri));
            }
            case "fileOpen": return files.open(Documents.parts(args.getJSONArray("parts")));
            case "fileAppend": files.append(args.getString("writer"), Base64.decode(args.getString("data"), Base64.DEFAULT)); return null;
            case "fileFinish": files.finish(args.getString("writer"), args.getBoolean("commit")); return null;
            case "mirror": {
                URI url = Policy.mirror(args.getString("url"));
                return "ruyi-image://download/" + streams.issue(() -> Streams.mirror(url));
            }
            case "usbSelect": return usb.chooseBoard();
            case "usbDevices": return usb.authorized();
            case "usbOpen": usb.open(args.getString("id")); return null;
            case "usbClose": usb.closeBoard(args.getString("id")); return null;
            case "usbIn": case "usbOut": return usb.transfer(method, args);
            case "listDisks": if (!flashing) return usb.listDisks(); break;
            case "chooseDisk": if (!flashing) return usb.selectDisk(args.getString("id")); break;
            case "flashing": {
                boolean active = args.getBoolean("active");
                if (active && !usb.hasAuthorizedTarget()) throw new IOException("Select and authorize a USB target first");
                activity.onMain(() -> { check(); activity.setFlashing(this, active); return null; });
                return null;
            }
            case "write": {
                if (!flashing) throw new IOException("Disk write was not confirmed");
                activity.onMain(() -> { check(); writing = true; activity.updateProtection(); return null; });
                try { write(args); } finally {
                    activity.onMain(() -> { writing = false; activity.updateProtection(); return null; });
                }
                return null;
            }
            default: throw new IOException("Unknown host operation");
        }
        throw new IOException("Flashing is in progress");
    }

    private void write(JSONObject args) throws Exception {
        JSONObject image = args.getJSONObject("image");
        List<String> parts = Documents.parts(image.getJSONArray("parts"));
        long size = image.getLong("size");
        String hex = image.getString("sha256");
        if (root == null || !root.equals(args.getString("root")) || !image.getString("directory").equals(files.name)
                || parts.size() < 5 || !parts.get(3).equals("images") || size < 1 || !hex.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid disk image");
        File snapshot = File.createTempFile("ruyi-source-", ".part", files.cache);
        try {
            byte[] digest;
            CancellationSignal cancel = new CancellationSignal();
            AtomicLong progress = new AtomicLong(SystemClock.elapsedRealtime());
            AtomicReference<InputStream> reading = new AtomicReference<>();
            ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
            watchdog.scheduleWithFixedDelay(() -> {
                if (!closed && SystemClock.elapsedRealtime() - progress.get() < 300000) return;
                cancel.cancel();
                InputStream stream = reading.getAndSet(null);
                if (stream != null) try { stream.close(); } catch (IOException ignored) {}
            }, 5, 5, TimeUnit.SECONDS);
            try (InputStream source = files.image(parts, cancel); OutputStream staged = new FileOutputStream(snapshot)) {
                reading.set(source);
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[262144];
                long total = 0;
                int bytes;
                while ((bytes = source.read(buffer)) != -1) {
                    check();
                    total += bytes;
                    if (total > size) throw new IOException("Image grew during preparation");
                    staged.write(buffer, 0, bytes);
                    hash.update(buffer, 0, bytes);
                    progress.set(SystemClock.elapsedRealtime());
                }
                cancel.throwIfCanceled();
                digest = hash.digest();
                byte[] expected = new byte[32];
                for (int index = 0; index < 32; index++) expected[index] = (byte)Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
                if (total != size || !MessageDigest.isEqual(expected, digest)) throw new IOException("Source size or SHA-256 changed");
            } finally { watchdog.shutdownNow(); }
            check();
            try (InputStream verified = new FileInputStream(snapshot)) { usb.write(args.getString("id"), verified, size, digest); }
        } finally { snapshot.delete(); }
    }

    void detached(String device) {
        if (closed) return;
        usb.invalidateAttachment(device);
        io.execute(() -> {
            for (String id : usb.detached(device)) activity.runOnUiThread(() -> {
                if (!closed) frame.postMessage("{\"disconnect\":" + JSONObject.quote(id) + "}");
            });
        });
    }

    void close() {
        if (closed) return;
        closed = true;
        io.execute(() -> {
            usb.close(); files.close(); streams.close();
            activity.runOnUiThread(() -> { flashing = false; activity.retired(this); });
        });
        io.shutdown();
    }
}
