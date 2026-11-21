package org.ruyisdk.imager;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.*;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class ImagerActivity extends Activity {
    private static final Set<PageSession> sessions = new HashSet<>();
    private static boolean serviceActive;
    private static final String PERMISSION = "org.ruyisdk.imager.USB_PERMISSION";
    private WebView web;
    private volatile PageSession page;
    private CompletableFuture<?> pending;
    private CompletableFuture<Boolean> permission;
    private String permissionToken;
    private CompletableFuture<Uri> directory;
    private ValueCallback<Uri[]> file;
    private AlertDialog chooser;
    private int requestSequence = 10, directoryRequest, fileRequest;
    private boolean rendererLost;

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PERMISSION.equals(intent.getAction()) && permission != null && Objects.equals(permissionToken, intent.getStringExtra("token"))) {
                permission.complete(true);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && page != null) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) page.detached(device.getDeviceName());
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        IntentFilter filter = new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, events, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        createWebView();
    }

    private void createWebView() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            TextView notice = new TextView(this);
            notice.setText(R.string.webview_update); setContentView(notice); return;
        }
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        Set<String> origins = Collections.singleton(Policy.SITE);
        WebViewCompat.addWebMessageListener(web, "ruyiAndroid", origins, (view, message, origin, main, frame) -> {
            if (!main || !Policy.SITE.equals(origin.toString()) || !Policy.page(view.getUrl())) return;
            String raw = message.getData();
            if (raw == null || raw.length() > 400000) return;
            try {
                JSONObject request = new JSONObject(raw);
                if (page == null || page.closed) {
                    if (!"hello".equals(request.getString("method"))) return;
                    page = new PageSession(this, frame);
                    sessions.add(page);
                }
                if (page.frame != frame) return;
                if (!"hello".equals(request.getString("method")) && !page.streams.key.equals(request.optString("key"))) return;
                for (PageSession other : sessions) if (other != page && (other.flashing || other.writing)) {
                    frame.postMessage(new JSONObject().put("id", request.getInt("id")).put("error", "Another flash is still in progress").toString()); return;
                }
                page.message(request);
            } catch (JSONException invalid) { android.util.Log.w("RuyiImager", "Invalid bridge request"); }
        });
        try (InputStream script = getAssets().open("host.js"); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = script.read(chunk)) != -1) buffer.write(chunk, 0, count);
            WebViewCompat.addDocumentStartJavaScript(web, buffer.toString(StandardCharsets.UTF_8.name()), origins);
        } catch (IOException missing) { throw new IllegalStateException(missing); }
        String initial = Policy.SITE + "/tryruyi/imager";
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (busy()) return true;
                if (Policy.page(url)) return false;
                if (request.hasGesture() && (url.startsWith("https://") || url.startsWith("http://"))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()).addCategory(Intent.CATEGORY_BROWSABLE)); }
                    catch (ActivityNotFoundException ignored) {}
                }
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.isForMainFrame() && !Policy.page(url)) return Streams.failure(403);
                if (!url.startsWith(Policy.STREAM)) return null;
                PageSession current = page;
                return current == null || current.closed ? Streams.failure(403) : current.streams.serve(request);
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                invalidatePage();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (!Policy.page(url)) invalidatePage();
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                rendererLost = true;
                invalidatePage();
                setContentView(new TextView(ImagerActivity.this));
                web = null; view.destroy();
                recoverRenderer(); return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!Policy.page(view.getUrl()) || busy() || file != null) { callback.onReceiveValue(null); return true; }
                file = callback; fileRequest = ++requestSequence;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(pick, fileRequest); }
                catch (ActivityNotFoundException unavailable) { file = null; callback.onReceiveValue(null); }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(initial);
    }

    private void recoverRenderer() {
        if (rendererLost && !busy() && !isDestroyed() && !isFinishing()) { rendererLost = false; createWebView(); }
    }

    private void invalidatePage() {
        PageSession previous = page;
        page = null;
        if (previous != null) previous.close();
        if (pending != null) pending.cancel(false);
        if (chooser != null) chooser.dismiss();
        if (file != null) { file.onReceiveValue(null); file = null; }
    }

    <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action);
        runOnUiThread(result);
        return result.get();
    }

    private interface Prompt<T> { void open(CompletableFuture<T> result); }
    private <T> T prompt(PageSession owner, Prompt<T> prompt) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            onMain(() -> {
                if (owner != page || pending != null && !pending.isDone()) throw new IOException("Another picker is active or page closed");
                pending = result;
                prompt.open(result);
                return null;
            });
            return result.get();
        } finally {
            onMain(() -> { if (pending == result) { pending = null; permission = null; directory = null; chooser = null; } return null; });
        }
    }

    UsbHost.Access access(PageSession owner) {
        return new UsbHost.Access() {
            public void authorize(UsbDevice device) throws Exception {
                UsbManager manager = getSystemService(UsbManager.class);
                if (manager.hasPermission(device)) return;
                ImagerActivity.this.<Boolean>prompt(owner, result -> {
                    permission = result; permissionToken = UsbHost.token();
                    Intent reply = new Intent(PERMISSION).setPackage(getPackageName()).putExtra("token", permissionToken);
                    manager.requestPermission(device, PendingIntent.getBroadcast(ImagerActivity.this, ++requestSequence, reply,
                            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE));
                });
                if (!manager.hasPermission(device)) throw new IOException("USB permission denied");
            }
            public int pick(String[] labels) throws Exception {
                return prompt(owner, result -> chooser = new AlertDialog.Builder(ImagerActivity.this).setTitle("Fastboot")
                        .setItems(labels, (dialog, index) -> result.complete(index))
                        .setOnCancelListener(dialog -> result.cancel(false)).show());
            }
        };
    }

    Uri pickDirectory(PageSession owner) throws Exception {
        return prompt(owner, result -> {
            directory = result; directoryRequest = ++requestSequence;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), directoryRequest);
        });
    }

    @Override public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null && !"content".equals(uri.getScheme())) uri = null;
        if (code == directoryRequest && directory != null) {
            if (uri == null) directory.cancel(false); else directory.complete(uri);
        } else if (code == fileRequest && file != null) { file.onReceiveValue(uri == null ? null : new Uri[]{uri}); file = null; }
    }

    private boolean busy() { return sessions.stream().anyMatch(session -> session.flashing || session.writing); }
    void setFlashing(PageSession session, boolean active) throws IOException {
        if (active && sessions.stream().anyMatch(other -> other != session && (other.flashing || other.writing)))
            throw new IOException("Another flash is still in progress");
        boolean previous = session.flashing;
        session.flashing = active;
        try { updateProtection(); }
        catch (RuntimeException failure) { session.flashing = previous; throw failure; }
    }
    void updateProtection() {
        boolean active = busy();
        if (active != serviceActive) {
            Intent service = new Intent(this, FlashService.class);
            if (active) startForegroundService(service); else stopService(service);
            serviceActive = active;
        }
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        invalidateOptionsMenu();
    }
    void retired(PageSession session) { sessions.remove(session); updateProtection(); recoverRenderer(); }
    @Override public boolean onCreateOptionsMenu(Menu menu) { menu.add(0, 1, 0, "Ruyi Imager"); menu.add(0, 2, 1, "ruyi device provision"); return true; }
    @Override public boolean onPrepareOptionsMenu(Menu menu) { menu.setGroupEnabled(0, !busy()); return true; }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() != 1 && item.getItemId() != 2) return super.onOptionsItemSelected(item);
        if (web != null && !busy()) {
            String url = web.getUrl();
            String prefix = Policy.page(url) ? Uri.parse(url).getPath().split("/tryruyi/", -1)[0] : "";
            web.loadUrl(Policy.SITE + prefix + "/tryruyi/" + (item.getItemId() == 1 ? "imager" : "device"));
        }
        return true;
    }
    @Override public void onBackPressed() {
        if (busy()) Toast.makeText(this, R.string.flashing, Toast.LENGTH_SHORT).show();
        else if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override public void onDestroy() {
        rendererLost = false; invalidatePage(); unregisterReceiver(events);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
}
