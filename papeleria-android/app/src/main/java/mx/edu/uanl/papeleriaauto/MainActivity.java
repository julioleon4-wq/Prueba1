package mx.edu.uanl.papeleriaauto;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.print.pdf.PrintedPdfDocument;
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

    private File reprintFile;
    private String reprintOperation = "";
    private String reprintMode = "bw";
    private String reprintPages = "1";
    private String reprintPaper = "letter";
    private boolean reprintDuplex = false;
    private int reprintCopies = 1;
    private long reprintExpiresAt = 0L;
    private static final long REPRINT_RETENTION_MS = 3L * 60L * 1000L;

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

        @JavascriptInterface public void printSelected(String mode, int copies, String pagesCsv,
                                                       boolean duplex, String paper, String operationId) {
            runOnUiThread(() -> startPrint(selectedPdf, selectedName, mode, copies, pagesCsv,
                    duplex, paper, operationId, false));
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
                if (uploadServer != null) uploadServer.newSession();
                clearCurrentJob();
                web.evaluateJavascript("window.onJobCleared && window.onJobCleared()", null);
            });
        }

        @JavascriptInterface public void requestPreview(int pageIndex, int targetWidth) {
            previewPool.execute(() -> renderPreview(pageIndex, targetWidth));
        }

        @JavascriptInterface public String systemStatus() {
            try {
                PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
                cleanupExpiredReprint();
                return new JSONObject()
                        .put("printService", pm != null)
                        .put("reprintAvailable", reprintFile != null && reprintFile.exists() && System.currentTimeMillis() < reprintExpiresAt)
                        .put("reprintOperation", reprintOperation)
                        .put("reprintSeconds", Math.max(0, (reprintExpiresAt - System.currentTimeMillis()) / 1000L))
                        .toString();
            } catch(Exception e) { return "{\"printService\":false}"; }
        }

        @JavascriptInterface public void reprintLast() {
            runOnUiThread(() -> {
                cleanupExpiredReprint();
                if (reprintFile == null || !reprintFile.exists()) {
                    Toast.makeText(MainActivity.this, "La copia temporal ya expiró", Toast.LENGTH_SHORT).show();
                    return;
                }
                startPrint(Uri.fromFile(reprintFile), "Reimpresion_" + reprintOperation + ".pdf",
                        reprintMode, reprintCopies, reprintPages, reprintDuplex, reprintPaper,
                        reprintOperation, true);
            });
        }

        @JavascriptInterface public String deviceMode() { return "kiosk-v5"; }
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

    private void startPrint(Uri uri, String name, String mode, int copies, String pagesCsv,
                            boolean duplex, String paper, String operationId, boolean isReprint) {
        if (uri == null) {
            Toast.makeText(this, "No hay un PDF activo", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] pageIndexes = parsePages(pagesCsv, countPages(uri));
        if (pageIndexes.length == 0) {
            Toast.makeText(this, "Selecciona al menos una página", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
            if (pm == null) throw new IllegalStateException("Servicio de impresión no disponible");

            PrintAttributes attrs = buildAttributes(mode, duplex, paper);
            if (!isReprint) prepareReprintCache(uri, operationId, mode, copies, pagesCsv, duplex, paper);

            SubsetPdfAdapter adapter = new SubsetPdfAdapter(uri, name, pageIndexes, Math.max(1, copies), attrs, operationId);
            PrintJob job = pm.print((isReprint ? "Reimpresion " : "Papeleria ") + operationId, adapter, attrs);
            monitorPrintJob(job, operationId);
            web.evaluateJavascript("window.onPrintOpened && window.onPrintOpened('" + jsEscape(operationId) + "'," + Math.max(1,copies) + ")", null);
        } catch(Exception e) {
            Toast.makeText(this, "No se pudo abrir impresión: " + e.getMessage(), Toast.LENGTH_LONG).show();
            notifyPrintStatus(operationId, "error");
        }
    }

    private PrintAttributes buildAttributes(String mode, boolean duplex, String paper) {
        PrintAttributes.MediaSize media;
        if ("a4".equalsIgnoreCase(paper)) media = PrintAttributes.MediaSize.ISO_A4;
        else if ("legal".equalsIgnoreCase(paper)) media = PrintAttributes.MediaSize.NA_LEGAL;
        else media = PrintAttributes.MediaSize.NA_LETTER;

        return new PrintAttributes.Builder()
                .setMediaSize(media)
                .setResolution(new PrintAttributes.Resolution("kiosk", "Kiosk", 300, 300))
                .setMinMargins(new PrintAttributes.Margins(180,180,180,180))
                .setColorMode("color".equals(mode) ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME)
                .setDuplexMode(duplex ? PrintAttributes.DUPLEX_MODE_LONG_EDGE : PrintAttributes.DUPLEX_MODE_NONE)
                .build();
    }

    private int[] parsePages(String csv, int total) {
        if (csv == null || csv.trim().isEmpty()) {
            int[] all = new int[total]; for (int i=0;i<total;i++) all[i]=i; return all;
        }
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            try {
                int oneBased = Integer.parseInt(part.trim());
                if (oneBased >= 1 && oneBased <= total) set.add(oneBased - 1);
            } catch(Exception ignored) {}
        }
        int[] out = new int[set.size()]; int i=0; for (Integer v : set) out[i++]=v; return out;
    }

    private void prepareReprintCache(Uri source, String op, String mode, int copies, String pages,
                                     boolean duplex, String paper) {
        cleanupReprint();
        try {
            File dir = new File(getCacheDir(), "reprint_cache"); dir.mkdirs();
            File target = new File(dir, "reprint_" + op.replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf");
            try (InputStream in = openInput(source); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf,0,n);
            }
            reprintFile = target;
            reprintOperation = op;
            reprintMode = mode;
            reprintCopies = Math.max(1,copies);
            reprintPages = pages;
            reprintDuplex = duplex;
            reprintPaper = paper;
            reprintExpiresAt = System.currentTimeMillis() + REPRINT_RETENTION_MS;
            mainHandler.postDelayed(this::cleanupExpiredReprint, REPRINT_RETENTION_MS + 2000L);
        } catch(Exception ignored) { cleanupReprint(); }
    }

    private void monitorPrintJob(PrintJob job, String operationId) {
        final long started = System.currentTimeMillis();
        Runnable poll = new Runnable() {
            @Override public void run() {
                try {
                    String status = "queued";
                    if (job.isCompleted()) status = "completed";
                    else if (job.isFailed()) status = "failed";
                    else if (job.isCancelled()) status = "cancelled";
                    else if (job.isBlocked()) status = "blocked";
                    else if (job.isStarted()) status = "printing";
                    else if (job.isQueued()) status = "queued";
                    notifyPrintStatus(operationId, status);
                    if (job.isCompleted() || job.isFailed() || job.isCancelled()) return;
                    if (System.currentTimeMillis() - started < 5L*60L*1000L) mainHandler.postDelayed(this, 1200L);
                } catch(Exception ignored) {}
            }
        };
        mainHandler.postDelayed(poll, 500L);
    }

    private void notifyPrintStatus(String operationId, String status) {
        String js = "window.onPrintStatus && window.onPrintStatus('" + jsEscape(operationId) + "','" + jsEscape(status) + "')";
        mainHandler.post(() -> web.evaluateJavascript(js, null));
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
                bmp.compress(Bitmap.CompressFormat.JPEG, 84, out);
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
        if (s == null) return "";
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

    private void cleanupExpiredReprint() {
        if (reprintFile != null && System.currentTimeMillis() >= reprintExpiresAt) cleanupReprint();
    }

    private void cleanupReprint() {
        try { if (reprintFile != null && reprintFile.exists()) reprintFile.delete(); } catch(Exception ignored) {}
        reprintFile = null;
        reprintOperation = "";
        reprintExpiresAt = 0L;
    }

    private class SubsetPdfAdapter extends PrintDocumentAdapter {
        private final Uri uri;
        private final String name;
        private final int[] sourcePages;
        private final int copies;
        private final PrintAttributes attrs;
        private final String operationId;

        SubsetPdfAdapter(Uri uri, String name, int[] sourcePages, int copies,
                         PrintAttributes attrs, String operationId) {
            this.uri = uri; this.name = name; this.sourcePages = sourcePages;
            this.copies = copies; this.attrs = attrs; this.operationId = operationId;
        }

        @Override public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cs,
                                       LayoutResultCallback cb, Bundle extras) {
            if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
            int outputPages = Math.max(1, sourcePages.length * copies);
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(outputPages).build();
            cb.onLayoutFinished(info, true);
        }

        @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest,
                                      CancellationSignal cs, WriteResultCallback cb) {
            PrintedPdfDocument outDoc = null;
            try (ParcelFileDescriptor sourcePfd = openPfd(uri);
                 PdfRenderer renderer = new PdfRenderer(sourcePfd)) {
                outDoc = new PrintedPdfDocument(MainActivity.this, attrs);
                int outputIndex = 0;
                for (int copy=0; copy<copies; copy++) {
                    for (int srcIndex : sourcePages) {
                        if (cs.isCanceled()) { cb.onWriteCancelled(); return; }
                        PdfDocument.Page outPage = outDoc.startPage(outputIndex);
                        Rect content = outPage.getInfo().getContentRect();
                        try (PdfRenderer.Page src = renderer.openPage(srcIndex)) {
                            int bw = Math.min(1800, Math.max(600, content.width()));
                            int bh = Math.max(1, Math.round(bw * (src.getHeight() / (float)Math.max(1, src.getWidth()))));
                            if (bh > 2400) { float scale = 2400f / bh; bh = 2400; bw = Math.max(1, Math.round(bw * scale)); }
                            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            float scale = Math.min(content.width()/(float)bmp.getWidth(), content.height()/(float)bmp.getHeight());
                            float dw = bmp.getWidth()*scale, dh = bmp.getHeight()*scale;
                            float left = content.left + (content.width()-dw)/2f;
                            float top = content.top + (content.height()-dh)/2f;
                            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                            outPage.getCanvas().drawBitmap(bmp, null, new RectF(left, top, left+dw, top+dh), paint);
                            bmp.recycle();
                        }
                        outDoc.finishPage(outPage);
                        outputIndex++;
                    }
                }
                try (FileOutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                    outDoc.writeTo(out);
                }
                cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch(Exception e) {
                cb.onWriteFailed(e.getMessage());
                notifyPrintStatus(operationId, "failed");
            } finally {
                if (outDoc != null) {
                    try { outDoc.close(); } catch(Exception ignored) {}
                }
            }
        }

        @Override public void onFinish() {
            super.onFinish();
            mainHandler.postDelayed(() -> {
                clearCurrentJob();
                web.evaluateJavascript("window.onPrintFlowFinished && window.onPrintFlowFinished('" + jsEscape(operationId) + "')", null);
            }, 900L);
        }
    }

    @Override protected void onDestroy() {
        if (uploadServer != null) uploadServer.stop();
        previewPool.shutdownNow();
        clearCurrentJob();
        cleanupReprint();
        if(web != null) { web.removeJavascriptInterface("Android"); web.destroy(); }
        super.onDestroy();
    }
}
