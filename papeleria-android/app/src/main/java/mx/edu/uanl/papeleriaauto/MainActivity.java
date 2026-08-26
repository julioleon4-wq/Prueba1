package mx.edu.uanl.papeleriaauto;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.*;
import android.print.*;
import android.print.pdf.PrintedPdfDocument;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
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
    private static final int REQ_BROWSER = 901;
    private static final int REQ_NEARBY_WIFI = 902;
    private static final long REPRINT_RETENTION_MS = 3L * 60L * 1000L;

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
    private boolean reprintDuplex = false;
    private int reprintCopies = 1;
    private long reprintExpiresAt = 0L;

    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private String hotspotSsid = "";
    private String hotspotPassword = "";
    private boolean pendingHotspotPermission = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersive();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSaveFormData(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
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
                startActivityForResult(i, REQ_BROWSER);
            });
        }

        @JavascriptInterface public void printSelected(String mode, int copies, String pagesCsv,
                                                       boolean duplex, String paper, String operationId) {
            runOnUiThread(() -> startPrint(selectedPdf, selectedName, mode, copies, pagesCsv,
                    duplex, "letter", operationId, false));
        }

        @JavascriptInterface public String newUploadSession() {
            try {
                clearCurrentJob();
                if (uploadServer == null) return new JSONObject().put("ok", false).put("error", "Recepción QR no disponible").toString();
                String ip = hotspotReservation != null ? getHotspotIpv4() : getLocalIpv4();
                if (ip == null) return new JSONObject().put("ok", false).put("error", "La tablet no tiene una red local disponible. Usa Conexión directa.").toString();
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
                ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
                boolean locked = am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
                return new JSONObject()
                        .put("printService", pm != null)
                        .put("reprintAvailable", reprintFile != null && reprintFile.exists() && System.currentTimeMillis() < reprintExpiresAt)
                        .put("reprintOperation", reprintOperation)
                        .put("reprintSeconds", Math.max(0, (reprintExpiresAt - System.currentTimeMillis()) / 1000L))
                        .put("lockTask", locked)
                        .put("hotspot", hotspotReservation != null)
                        .put("version", "7.0.0")
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
                        reprintMode, reprintCopies, reprintPages, reprintDuplex, "letter",
                        reprintOperation, true);
            });
        }

        @JavascriptInterface public void startDirectHotspot() {
            runOnUiThread(MainActivity.this::startDirectHotspotInternal);
        }

        @JavascriptInterface public void stopDirectHotspot() {
            runOnUiThread(MainActivity.this::stopDirectHotspotInternal);
        }

        @JavascriptInterface public void feedback(String kind) {
            runOnUiThread(() -> feedbackInternal(kind));
        }

        @JavascriptInterface public void setKioskLock(boolean enabled) {
            runOnUiThread(() -> setKioskLockInternal(enabled));
        }

        @JavascriptInterface public String deviceMode() { return "kiosk-v7"; }
    }

    private void acceptRemotePdf(File file, String originalName) {
        cleanupRemote();
        selectedRemoteFile = file;
        selectedPdf = Uri.fromFile(file);
        selectedName = originalName == null ? "documento.pdf" : originalName;
        try {
            selectedPages = countPagesStrict(selectedPdf);
            String safe = jsEscape(selectedName);
            web.evaluateJavascript("window.onRemotePdfReceived && window.onRemotePdfReceived('" + safe + "'," + selectedPages + ")", null);
        } catch(Exception e) {
            cleanupRemote();
            selectedPdf = null;
            selectedPages = 0;
            web.evaluateJavascript("window.onPdfRejected && window.onPdfRejected('No pudimos abrir ese PDF. Puede estar dañado o protegido con contraseña.')", null);
        }
    }

    private void acceptBrowserPdf(File file, String originalName) {
        acceptRemotePdf(file, originalName);
        if (selectedPdf != null) {
            String safe = jsEscape(selectedName);
            web.evaluateJavascript("window.onBrowserPdfReceived && window.onBrowserPdfReceived('" + safe + "'," + selectedPages + ")", null);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BROWSER && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra("pdf_path");
            String name = data.getStringExtra("pdf_name");
            if (path != null) {
                File f = new File(path);
                if (f.exists()) acceptBrowserPdf(f, name == null ? f.getName() : name);
            }
        }
        applyImmersive();
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
            PrintAttributes attrs = buildAttributes(mode, duplex);
            if (!isReprint) prepareReprintCache(uri, operationId, mode, copies, pagesCsv, duplex);
            SubsetPdfAdapter adapter = new SubsetPdfAdapter(uri, name, pageIndexes, Math.max(1, copies), attrs, operationId);
            PrintJob job = pm.print((isReprint ? "Reimpresion " : "Papeleria ") + operationId, adapter, attrs);
            monitorPrintJob(job, operationId);
            web.evaluateJavascript("window.onPrintOpened && window.onPrintOpened('" + jsEscape(operationId) + "'," + Math.max(1,copies) + ")", null);
        } catch(Exception e) {
            Toast.makeText(this, "No se pudo abrir impresión: " + e.getMessage(), Toast.LENGTH_LONG).show();
            notifyPrintStatus(operationId, "error");
        }
    }

    private PrintAttributes buildAttributes(String mode, boolean duplex) {
        return new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
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

    private void prepareReprintCache(Uri source, String op, String mode, int copies, String pages, boolean duplex) {
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

    private int countPagesStrict(Uri uri) throws IOException {
        try (ParcelFileDescriptor pfd = openPfd(uri); PdfRenderer r = new PdfRenderer(pfd)) {
            return Math.max(1, r.getPageCount());
        }
    }

    private int countPages(Uri uri) {
        try { return countPagesStrict(uri); } catch(Exception e) { return 1; }
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

    private String getHotspotIpv4() {
        String endingOne = null, fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (n.contains("ap") || n.contains("softap") || n.contains("swlan")) return ip;
                    if (ip.endsWith(".1")) endingOne = ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch(Exception ignored) {}
        return endingOne != null ? endingOne : fallback;
    }

    private void startDirectHotspotInternal() {
        if (uploadServer == null) { emitHotspotError("Recepción local no disponible."); return; }
        if (hotspotReservation != null) { emitHotspotReady(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            pendingHotspotPermission = true;
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
            return;
        }
        if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingHotspotPermission = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_NEARBY_WIFI);
            return;
        }
        try {
            WifiManager wm = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) { emitHotspotError("Wi‑Fi no disponible en esta tablet."); return; }
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            SoftApConfiguration c = reservation.getSoftApConfiguration();
                            hotspotSsid = c.getSsid();
                            hotspotPassword = c.getPassphrase();
                        } else {
                            WifiConfiguration c = reservation.getWifiConfiguration();
                            hotspotSsid = c == null ? "COBRA-KIOSCO" : stripQuotes(c.SSID);
                            hotspotPassword = c == null ? "" : stripQuotes(c.preSharedKey);
                        }
                    } catch(Exception ignored) {}
                    if (hotspotSsid == null || hotspotSsid.isEmpty()) hotspotSsid = "COBRA-KIOSCO";
                    if (hotspotPassword == null) hotspotPassword = "";
                    mainHandler.postDelayed(MainActivity.this::emitHotspotReady, 1200L);
                }
                @Override public void onStopped() {
                    hotspotReservation = null; hotspotSsid = ""; hotspotPassword = "";
                }
                @Override public void onFailed(int reason) {
                    hotspotReservation = null;
                    emitHotspotError("No se pudo crear la conexión directa. Usa la misma Wi‑Fi del kiosco.");
                }
            }, mainHandler);
        } catch(Exception e) { emitHotspotError("Conexión directa no disponible: " + e.getMessage()); }
    }

    private void emitHotspotReady() {
        try {
            String ip = getHotspotIpv4();
            if (ip == null) { emitHotspotError("La red directa inició, pero no pudimos obtener su dirección local."); return; }
            String token = uploadServer.newSession();
            String url = "http://" + ip + ":" + uploadServer.getPort() + "/upload/" + token;
            String wifiPayload = "WIFI:T:WPA;S:" + wifiEscape(hotspotSsid) + ";P:" + wifiEscape(hotspotPassword) + ";;";
            String js = "window.onDirectHotspotReady && window.onDirectHotspotReady('" + jsEscape(hotspotSsid) + "','" + jsEscape(hotspotPassword) + "','" + qrDataUrl(wifiPayload) + "','" + qrDataUrl(url) + "')";
            web.evaluateJavascript(js, null);
        } catch(Exception e) { emitHotspotError("No se pudo preparar el QR de conexión directa."); }
    }

    private void emitHotspotError(String msg) {
        if (web != null) web.evaluateJavascript("window.onDirectHotspotError && window.onDirectHotspotError('" + jsEscape(msg) + "')", null);
    }

    private void stopDirectHotspotInternal() {
        try { if (hotspotReservation != null) hotspotReservation.close(); } catch(Exception ignored) {}
        hotspotReservation = null; hotspotSsid = ""; hotspotPassword = "";
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NEARBY_WIFI && pendingHotspotPermission) {
            pendingHotspotPermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startDirectHotspotInternal();
            else emitHotspotError("Permiso de Wi‑Fi denegado. Puedes seguir usando la misma red del kiosco.");
        }
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length()-1);
        return s;
    }

    private static String wifiEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:");
    }

    private void feedbackInternal(String kind) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 38);
            int tone = "done".equals(kind) ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP;
            tg.startTone(tone, "done".equals(kind) ? 110 : 65);
            mainHandler.postDelayed(tg::release, 220L);
        } catch(Exception ignored) {}
        try {
            Vibrator v = (Vibrator)getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long ms = "done".equals(kind) ? 70 : 35;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            }
        } catch(Exception ignored) {}
    }

    private void setKioskLockInternal(boolean enabled) {
        applyImmersive();
        try {
            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            boolean active = am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
            if (enabled && !active) startLockTask();
            else if (!enabled && active) stopLockTask();
        } catch(Exception e) {
            Toast.makeText(this, "El bloqueo completo requiere que la tablet permita fijar esta app", Toast.LENGTH_LONG).show();
        }
        applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override public void onBackPressed() {
        // Kiosco: evita salir accidentalmente. La navegación se hace desde la interfaz.
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

        SubsetPdfAdapter(Uri uri, String name, int[] sourcePages, int copies, PrintAttributes attrs, String operationId) {
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
            try (ParcelFileDescriptor sourcePfd = openPfd(uri); PdfRenderer renderer = new PdfRenderer(sourcePfd)) {
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
                if (outDoc != null) try { outDoc.close(); } catch(Exception ignored) {}
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
        stopDirectHotspotInternal();
        if (uploadServer != null) uploadServer.stop();
        previewPool.shutdownNow();
        clearCurrentJob();
        cleanupReprint();
        if(web != null) { web.removeJavascriptInterface("Android"); web.destroy(); }
        super.onDestroy();
    }
}
