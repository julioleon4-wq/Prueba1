package mx.edu.uanl.papeleriaauto;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private Uri selectedPdf;
    private File selectedRemoteFile;
    private String selectedName = "documento.pdf";
    private int selectedPages = 0;
    private LocalUploadServer uploadServer;
    private final ExecutorService previewPool = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSaveFormData(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        startUploadServer();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void startUploadServer() {
        try {
            uploadServer = new LocalUploadServer(getCacheDir(), 8989,
                    (file, originalName) -> runOnUiThread(() -> acceptRemotePdf(file, originalName)));
            uploadServer.start();
        } catch(Exception e) {
            Toast.makeText(this, "No se pudo iniciar recepción por QR: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public class Bridge {
        @JavascriptInterface public void openSecure(String url, String title) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, SecureBrowserActivity.class);
                i.putExtra("url", url);
                i.putExtra("title", title);
                startActivity(i);
            });
        }

        @JavascriptInterface public void printSelected(String mode, int copies) {
            runOnUiThread(() -> startPrint(mode, copies));
        }

        @JavascriptInterface public String newUploadSession() {
            try {
                clearCurrentJob();
                if (uploadServer == null) return new JSONObject().put("ok", false).put("error", "Recepción QR no disponible").toString();
                String ip = getLocalIpv4();
                if (ip == null) return new JSONObject().put("ok", false).put("error", "Conecta la tablet a una red Wi‑Fi y vuelve a intentar").toString();
                String token = uploadServer.newSession();
                String url = "http://" + ip + ":" + uploadServer.getPort() + "/upload/" + token;
                return new JSONObject().put("ok", true).put("url", url).put("qr", qrDataUrl(url)).put("ip", ip).toString();
            } catch(Exception e) {
                try { return new JSONObject().put("ok", false).put("error", e.getMessage()).toString(); }
                catch(Exception ignored) { return "{\"ok\":false}"; }
            }
        }

        @JavascriptInterface public void cancelCurrentJob() {
            runOnUiThread(() -> {
                clearCurrentJob();
                web.evaluateJavascript("window.onJobCleared && window.onJobCleared()", null);
            });
        }

        @JavascriptInterface public void requestPreview(int pageIndex, int targetWidth) {
            previewPool.execute(() -> renderPreview(pageIndex, targetWidth));
        }

        @JavascriptInterface public String deviceMode() { return "kiosk-v4"; }
    }

    private void acceptRemotePdf(File file, String originalName) {
        cleanupRemote();
        selectedRemoteFile = file;
        selectedPdf = Uri.fromFile(file);
        selectedName = originalName;
        selectedPages = countPages(selectedPdf);
        String safe = jsEscape(selectedName);
        web.evaluateJavascript("window.onRemotePdfReceived && window.onRemotePdfReceived('" + safe + "'," + selectedPages + ")", null);
    }

    private void startPrint(String mode, int copies) {
        if (selectedPdf == null) {
            Toast.makeText(this, "No hay un PDF activo", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
            PrintAttributes.Builder ab = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .setColorMode("color".equals(mode) ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME);
            pm.print("Papeleria - " + selectedName,
                    new PdfAdapter(selectedPdf, selectedName, selectedPages, Math.max(1, copies)), ab.build());
            web.evaluateJavascript("window.onPrintOpened && window.onPrintOpened(" + Math.max(1,copies) + ")", null);
        } catch(Exception e) {
            Toast.makeText(this, "No se pudo abrir impresión: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void renderPreview(int pageIndex, int targetWidth) {
        Uri uri = selectedPdf;
        int pageCount = selectedPages;
        if (uri == null || pageIndex < 0 || pageIndex >= pageCount) return;
        try (ParcelFileDescriptor pfd = openPfd(uri); PdfRenderer renderer = new PdfRenderer(pfd)) {
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                int width = Math.max(360, Math.min(1100, targetWidth));
                float ratio = page.getHeight() / (float)Math.max(1, page.getWidth());
                int height = Math.max(480, Math.min(1500, Math.round(width * ratio)));
                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
                bmp.recycle();
                String data = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                String js = "window.onPreviewReady && window.onPreviewReady(" + pageIndex + ",'" + data + "')";
                mainHandler.post(() -> web.evaluateJavascript(js, null));
            }
        } catch(Exception e) {
            mainHandler.post(() -> web.evaluateJavascript("window.onPreviewError && window.onPreviewError()", null));
        }
    }

    private int countPages(Uri uri) {
        try (ParcelFileDescriptor pfd = openPfd(uri); PdfRenderer r = new PdfRenderer(pfd)) {
            return Math.max(1, r.getPageCount());
        } catch(Exception e) { return 1; }
    }

    private ParcelFileDescriptor openPfd(Uri uri) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme()))
            return ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor p = getContentResolver().openFileDescriptor(uri, "r");
        if (p == null) throw new FileNotFoundException();
        return p;
    }

    private InputStream openInput(Uri uri) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new FileNotFoundException();
        return in;
    }

    private String getLocalIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        String n = ni.getName().toLowerCase(Locale.ROOT);
                        if (n.contains("wlan") || n.contains("wifi")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch(Exception ignored) {}
        return fallback;
    }

    private String qrDataUrl(String text) throws Exception {
        BitMatrix m = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 560, 560);
        Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
        for (int y=0; y<m.getHeight(); y++)
            for (int x=0; x<m.getWidth(); x++)
                bmp.setPixel(x, y, m.get(x,y) ? 0xff111111 : 0xffffffff);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private String jsEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", " ").replace("\n", " ");
    }

    private void clearCurrentJob() {
        cleanupRemote();
        selectedPdf = null;
        selectedName = "documento.pdf";
        selectedPages = 0;
    }

    private void cleanupRemote() {
        try { if (selectedRemoteFile != null && selectedRemoteFile.exists()) selectedRemoteFile.delete(); }
        catch(Exception ignored) {}
        selectedRemoteFile = null;
    }

    private class PdfAdapter extends PrintDocumentAdapter {
        private final Uri uri;
        private final String name;
        private final int pages;
        private final int copies;
        PdfAdapter(Uri u, String n, int p, int c) { uri=u; name=n; pages=p; copies=c; }

        @Override public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cs,
                                       LayoutResultCallback cb, Bundle extras) {
            if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(Math.max(1,pages)).build();
            cb.onLayoutFinished(info, true);
        }

        @Override public void onWrite(android.print.PageRange[] ranges, ParcelFileDescriptor dest,
                                      CancellationSignal cs, WriteResultCallback cb) {
            try (InputStream in = openInput(uri); OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                byte[] buf = new byte[65536]; int n;
                while ((n=in.read(buf))>0) {
                    if(cs.isCanceled()){ cb.onWriteCancelled(); return; }
                    out.write(buf,0,n);
                }
                out.flush();
                cb.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
            } catch(Exception e) { cb.onWriteFailed(e.getMessage()); }
        }

        @Override public void onFinish() {
            super.onFinish();
            mainHandler.postDelayed(() -> {
                clearCurrentJob();
                web.evaluateJavascript("window.onPrintFlowFinished && window.onPrintFlowFinished(" + copies + ")", null);
            }, 1500);
        }
    }

    @Override protected void onDestroy() {
        if (uploadServer != null) uploadServer.stop();
        previewPool.shutdownNow();
        clearCurrentJob();
        if(web != null) { web.removeJavascriptInterface("Android"); web.destroy(); }
        super.onDestroy();
    }
}
