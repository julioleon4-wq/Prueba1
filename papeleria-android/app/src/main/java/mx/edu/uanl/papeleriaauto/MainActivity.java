package mx.edu.uanl.papeleriaauto;

import android.app.*;
import android.content.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;

public class MainActivity extends Activity {
    private WebView web;
    private Uri selectedPdf;
    private String selectedName = "documento.pdf";
    private int selectedPages = 0;
    private static final int PICK_PDF = 7001;

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
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @JavascriptInterface public void choosePdf() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/pdf");
                startActivityForResult(i, PICK_PDF);
            });
        }

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

        @JavascriptInterface public String deviceMode() { return "standalone"; }
    }

    private void startPrint(String mode, int copies) {
        if (selectedPdf == null) {
            Toast.makeText(this, "Primero selecciona un PDF", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
            PrintAttributes.Builder ab = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .setColorMode("color".equals(mode) ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME);
            pm.print("Papeleria - " + selectedName, new PdfAdapter(selectedPdf, selectedName, selectedPages), ab.build());
            web.evaluateJavascript("window.onPrintOpened && window.onPrintOpened(" + Math.max(1,copies) + ")", null);
        } catch(Exception e) {
            Toast.makeText(this, "No se pudo abrir impresión: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedPdf = data.getData();
            try { getContentResolver().takePersistableUriPermission(selectedPdf, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(Exception ignored) {}
            selectedName = queryName(selectedPdf);
            selectedPages = countPages(selectedPdf);
            final String safe = selectedName.replace("\\","\\\\").replace("'","\\'");
            web.evaluateJavascript("window.onPdfSelected('" + safe + "'," + selectedPages + ")", null);
        }
    }

    private int countPages(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r"); PdfRenderer r = new PdfRenderer(pfd)) {
            return r.getPageCount();
        } catch(Exception e) { return 1; }
    }

    private String queryName(Uri uri) {
        String name = "documento.pdf";
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch(Exception ignored) {}
        return name;
    }

    private class PdfAdapter extends PrintDocumentAdapter {
        private final Uri uri; private final String name; private final int pages;
        PdfAdapter(Uri u, String n, int p) { uri=u; name=n; pages=p; }
        @Override public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cs, LayoutResultCallback cb, Bundle extras) {
            if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(Math.max(1,pages)).build();
            cb.onLayoutFinished(info, true);
        }
        @Override public void onWrite(android.print.PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cs, WriteResultCallback cb) {
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                byte[] buf = new byte[65536]; int n;
                while ((n=in.read(buf))>0) { if(cs.isCanceled()){cb.onWriteCancelled();return;} out.write(buf,0,n); }
                out.flush();
                cb.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
            } catch(Exception e) { cb.onWriteFailed(e.getMessage()); }
        }
    }

    @Override protected void onDestroy() {
        if(web != null) { web.removeJavascriptInterface("Android"); web.destroy(); }
        super.onDestroy();
    }
}
