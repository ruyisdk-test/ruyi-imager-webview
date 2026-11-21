package org.ruyisdk.imager;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.content.res.AssetFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.*;
import java.util.*;
import org.json.*;

final class Documents implements Closeable {
    private final ContentResolver resolver;
    final File cache;
    private static File staging;
    private final Map<String, Pending> writers = new HashMap<>();
    private Uri tree;
    String name;

    Documents(ContentResolver resolver, File parent) { this.resolver = resolver; this.cache = staging(parent); }

    private static synchronized File staging(File parent) {
        if (staging != null) return staging;
        File dir = new File(parent, "imager-staging");
        if (!dir.isDirectory() && !dir.mkdir()) throw new IllegalStateException("Cannot create staging directory");
        File[] abandoned = dir.listFiles((folder, name) -> name.startsWith("ruyi-") && name.endsWith(".part"));
        if (abandoned != null) for (File part : abandoned) part.delete();
        return staging = dir;
    }

    void select(Uri selected) throws IOException {
        close();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(selected, DocumentsContract.getTreeDocumentId(selected));
        try (Cursor row = resolver.query(root, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (row == null || !row.moveToFirst()) throw new IOException("Cannot read selected directory");
            name = row.getString(0);
        }
        tree = selected;
    }

    static List<String> parts(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) result.add(array.getString(index));
        Policy.path(result);
        return result;
    }

    Uri resolve(List<String> parts, boolean directory, boolean create) throws IOException {
        return resolve(parts, directory, create, null);
    }

    Uri resolve(List<String> parts, boolean directory, boolean create, CancellationSignal cancel) throws IOException {
        Policy.path(parts);
        if (tree == null) throw new IOException("Select an image directory first");
        String id = DocumentsContract.getTreeDocumentId(tree);
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index);
            boolean folder = index + 1 < parts.size() || directory;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            String child = null;
            try (Cursor rows = resolver.query(children, new String[]{Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, null, null, null, cancel)) {
                if (rows == null) throw new IOException("Directory listing failed");
                while (rows.moveToNext()) if (part.equals(rows.getString(1))) {
                    if (child != null || folder != Document.MIME_TYPE_DIR.equals(rows.getString(2)))
                        throw new IOException("Ambiguous file or directory: " + part);
                    child = rows.getString(0);
                }
            }
            if (child == null) {
                if (!create) throw new FileNotFoundException(part);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                Uri created = DocumentsContract.createDocument(resolver, parent,
                        folder ? Document.MIME_TYPE_DIR : "application/octet-stream", part);
                if (created == null) throw new IOException("Cannot create " + part);
                child = DocumentsContract.getDocumentId(created);
            }
            id = child;
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    InputStream read(Uri uri) throws IOException {
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) throw new IOException("Cannot read document");
        return stream;
    }

    InputStream image(List<String> parts, CancellationSignal cancel) throws IOException {
        Uri uri = resolve(parts, false, false, cancel);
        AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", cancel);
        if (descriptor == null) throw new IOException("Cannot open disk image");
        try { return descriptor.createInputStream(); }
        catch (IOException failure) { descriptor.close(); throw failure; }
    }

    private static class Pending implements Closeable {
        final Uri target;
        final File file;
        final OutputStream out;
        Pending(Uri target, File cache) throws IOException {
            this.target = target;
            file = File.createTempFile("ruyi-write-", ".part", cache);
            out = new BufferedOutputStream(new FileOutputStream(file));
        }
        public void close() throws IOException { try { out.close(); } finally { file.delete(); } }
    }

    String open(List<String> parts) throws IOException {
        if (writers.size() >= 8) throw new IOException("Too many open files");
        String id = UUID.randomUUID().toString();
        writers.put(id, new Pending(resolve(parts, false, true), cache));
        return id;
    }

    void append(String id, byte[] data) throws IOException {
        Pending writer = writers.get(id);
        if (writer == null) throw new IOException("File writer expired");
        writer.out.write(data);
    }

    void finish(String id, boolean commit) throws IOException {
        Pending writer = writers.remove(id);
        if (writer == null) throw new IOException("File writer expired");
        try (writer) {
            writer.out.close();
            if (commit) try (InputStream source = new FileInputStream(writer.file);
                    OutputStream target = resolver.openOutputStream(writer.target, "rwt")) {
                if (target == null) throw new IOException("Cannot write document");
                byte[] buffer = new byte[131072];
                int count;
                while ((count = source.read(buffer)) != -1) target.write(buffer, 0, count);
            }
        }
    }

    public void close() {
        for (Pending writer : writers.values()) try { writer.close(); } catch (IOException ignored) {}
        writers.clear();
        tree = null;
    }
}
