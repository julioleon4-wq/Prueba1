package mx.edu.uanl.papeleriaauto;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecureBrowserActivity extends Activity {
    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadPool = Executors.newSingleThreadExecutor();
    private long deadline;
    private static final int IDLE_SECONDS = 150;
    private static final long MAX_BYTES = 25L * 1024L * 1024L;
    private final Runnable timeout = new Runnable(){ @Override public void run(){
        if(System.currentTimeMillis() >= deadline){ finishSecure(); return; }
        handler.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xfff3efe6);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(20,12,20,12); bar.setBackgroundColor(0xff171717);
        TextView title = new TextView(this); title.setText(getIntent().getStringExtra("title")); title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(null,1); title.setLayoutParams(new LinearLayout.LayoutParams(0,56,1)); title.setGravity(Gravity.CENTER_VERTICAL);
        Button done = new Button(this); done.setText("Terminar"); done.setOnClickListener(v->finishSecure());
        bar.addView(title); bar.addView(done); root.addView(bar);
        TextView tip = new TextView(this); tip.setText("Cuando descargues un PDF, volverá solo al kiosco para que lo revises antes de imprimir."); tip.setTextColor(0xff5f5a53); tip.setTextSize(12); tip.setPadding(20,12,20,12); tip.setBackgroundColor(0xfffff8e8); root.addView(tip);
        web = new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);

        CookieManager cm=CookieManager.getInstance(); cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(web,true);
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setSaveFormData(false); s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){ return !allowed(r.getUrl()); }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setDownloadListener((url,ua,cd,mime,len)->downloadPdf(url,ua,cd,mime,len));
        resetTimer(); web.loadUrl(getIntent().getStringExtra("url"));
    }

    private void downloadPdf(String url, String ua, String cd, String mime, long len){
        if(url==null || !url.toLowerCase(Locale.ROOT).startsWith("https://")){
            Toast.makeText(this,"Solo se permiten descargas seguras",Toast.LENGTH_LONG).show(); return;
        }
        String guess=URLUtil.guessFileName(url,cd,mime);
        if(guess==null||guess.trim().isEmpty()) guess="documento.pdf";
        if(!guess.toLowerCase(Locale.ROOT).endsWith(".pdf")) guess += ".pdf";
        final String fileName=sanitize(guess);
        Toast.makeText(this,"Descargando PDF…",Toast.LENGTH_SHORT).show();
        downloadPool.execute(()->{
            File outFile=null;
            HttpURLConnection conn=null;
            try{
                URL u=new URL(url);
                conn=(HttpURLConnection)u.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
                if(ua!=null&&!ua.isEmpty()) conn.setRequestProperty("User-Agent",ua);
                String cookie=CookieManager.getInstance().getCookie(url); if(cookie!=null) conn.setRequestProperty("Cookie",cookie);
                conn.setRequestProperty("Accept","application/pdf,*/*;q=0.8");
                conn.connect();
                int code=conn.getResponseCode();
                if(code<200||code>=400) throw new IOException("HTTP "+code);
                long size=conn.getContentLengthLong(); if(size>MAX_BYTES) throw new IOException("El PDF supera 25 MB");
                File dir=new File(getCacheDir(),"browser_downloads"); dir.mkdirs(); cleanupDir(dir);
                outFile=new File(dir,System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+".pdf");
                try(InputStream in=new BufferedInputStream(conn.getInputStream()); OutputStream out=new BufferedOutputStream(new FileOutputStream(outFile))){
                    byte[] buf=new byte[65536]; int n; long total=0;
                    while((n=in.read(buf))>0){ total+=n; if(total>MAX_BYTES) throw new IOException("El PDF supera 25 MB"); out.write(buf,0,n); }
                }
                if(!isPdf(outFile)) throw new IOException("La descarga no es un PDF válido");
                File finalFile=outFile;
                runOnUiThread(()->finishWithPdf(finalFile,fileName));
            }catch(Exception e){
                if(outFile!=null) try{outFile.delete();}catch(Exception ignored){}
                String msg=e.getMessage()==null?"No se pudo descargar el PDF":e.getMessage();
                runOnUiThread(()->Toast.makeText(this,"No se pudo traer el PDF: "+msg,Toast.LENGTH_LONG).show());
            }finally{if(conn!=null)conn.disconnect();}
        });
    }

    private boolean isPdf(File f){
        byte[] b=new byte[5];
        try(InputStream in=new FileInputStream(f)){return in.read(b)==5 && "%PDF-".equals(new String(b,java.nio.charset.StandardCharsets.US_ASCII));}
        catch(Exception e){return false;}
    }

    private void cleanupDir(File dir){
        File[] fs=dir.listFiles(); if(fs==null)return;
        long cutoff=System.currentTimeMillis()-60L*60L*1000L;
        for(File f:fs) if(f.isFile()&&f.lastModified()<cutoff) try{f.delete();}catch(Exception ignored){}
    }

    private String sanitize(String s){
        s=s.replaceAll("[\\r\\n\\\\/:*?\"<>|]","_").trim();
        if(s.length()>100)s=s.substring(0,100);
        return s.isEmpty()?"documento.pdf":s;
    }

    private void finishWithPdf(File f,String name){
        Intent data=new Intent(); data.putExtra("pdf_path",f.getAbsolutePath()); data.putExtra("pdf_name",name); setResult(RESULT_OK,data);
        clearWebSession(); finish();
    }

    private boolean allowed(Uri u){
        String h=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equalsIgnoreCase(u.getScheme())) return false;
        return h.endsWith(".uanl.mx")||h.equals("uanl.mx")||h.endsWith(".microsoft.com")||h.endsWith(".microsoftonline.com")||h.endsWith(".office.com")||h.endsWith(".office365.com")||h.endsWith(".live.com")||h.endsWith(".sharepoint.com")||h.endsWith(".windows.net")||h.equals("www.microsoft365.com")||h.endsWith(".onedrive.com");
    }

    private void resetTimer(){ deadline=System.currentTimeMillis()+IDLE_SECONDS*1000L; handler.removeCallbacks(timeout); handler.post(timeout); }
    @Override public void onUserInteraction(){ super.onUserInteraction(); resetTimer(); }

    private void clearWebSession(){
        handler.removeCallbacksAndMessages(null);
        try{ web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); web.clearCache(true); web.clearFormData(); WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); }catch(Exception ignored){}
    }
    private void finishSecure(){ clearWebSession(); setResult(RESULT_CANCELED); finish(); }
    @Override public void onBackPressed(){ if(web!=null&&web.canGoBack())web.goBack();else finishSecure(); }
    @Override protected void onDestroy(){ handler.removeCallbacksAndMessages(null); downloadPool.shutdownNow(); if(web!=null)web.destroy(); super.onDestroy(); }
}
