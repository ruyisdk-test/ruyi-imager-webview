package org.ruyisdk.imager;

import android.os.SystemClock;
import android.webkit.*;
import java.io.*;
import java.net.*;
import java.util.*;

final class Streams {
    interface Source { InputStream open() throws IOException; }
    private record Ticket(Source source, long expires) {}
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final Set<InputStream> active = new HashSet<>();
    final String key = UsbHost.token();
    private boolean closed;

    synchronized String issue(Source source) throws IOException {
        if (closed) throw new IOException("Session closed");
        if (tickets.size() >= 16) tickets.remove(tickets.keySet().iterator().next());
        String id = UsbHost.token();
        tickets.put(id, new Ticket(source, SystemClock.elapsedRealtime() + 120000));
        return id;
    }

    static InputStream mirror(URI url) throws IOException {
        for (int redirects = 0; redirects <= 10; redirects++) {
            HttpURLConnection connection = (HttpURLConnection)url.toURL().openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Mirror redirect lacks a location");
                    url = Policy.mirror(url.resolve(location).toString());
                    connection.disconnect();
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("Mirror returned HTTP " + status);
                return new FilterInputStream(connection.getInputStream()) {
                    public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
                };
            } catch (Exception error) { connection.disconnect(); throw error; }
        }
        throw new IOException("Too many mirror redirects");
    }

    WebResourceResponse serve(WebResourceRequest request) {
        if (request.isForMainFrame() || !request.getMethod().equals("GET")
                || !key.equals(request.getRequestHeaders().entrySet().stream()
                        .filter(entry -> "X-Ruyi-Session".equalsIgnoreCase(entry.getKey()))
                        .map(Map.Entry::getValue).findFirst().orElse(null))) return failure(403);
        String url = request.getUrl().toString();
        Ticket ticket;
        synchronized (this) { ticket = tickets.remove(url.substring(Policy.STREAM.length())); }
        if (ticket == null || ticket.expires < SystemClock.elapsedRealtime()) return failure(403);
        try {
            InputStream source = ticket.source.open();
            synchronized (this) {
                if (closed) { source.close(); return failure(403); }
                active.add(source);
            }
            InputStream body = new FilterInputStream(source) {
                public void close() throws IOException {
                    synchronized (Streams.this) { active.remove(source); }
                    super.close();
                }
            };
            return new WebResourceResponse("application/octet-stream", null, 200, "OK",
                    Map.of("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff"), body);
        } catch (Exception failure) { return failure(502); }
    }

    static WebResourceResponse failure(int status) {
        return new WebResourceResponse("text/plain", "UTF-8", status, "Unavailable", Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    synchronized void close() {
        closed = true;
        tickets.clear();
        for (InputStream source : active) try { source.close(); } catch (IOException ignored) {}
        active.clear();
    }
}
