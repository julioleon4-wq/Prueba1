package mx.edu.uanl.papeleriaauto;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;

public class SecureBrowserActivity extends Activity {
    private WebView web;
    private Handler handler = new Handler(Looper.getMainLooper());
    private long deadline;
    private static final int IDLE_SECONDS = 150;
    private final Runnable timeout = new Runnable(){ @Override public void run(){
        if(System.currentTimeMillis() >= deadline){ finishSecure(); return; }
        handler.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xff07111f);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(20,12,20,12);
        TextView title = new TextView(this); title.setText(getIntent().getStringExtra("title")); title.setTextColor(0xfff5f9ff); title.setTextSize(20); title.setLayoutParams(new LinearLayout.LayoutParams(0,56,1));
        Button done = new Button(this); done.setText("Terminar sesión"); done.setOnClickListener(v->finishSecure());
        bar.addView(title); bar.addView(done); root.addView(bar);
        web = new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);

        CookieManager cm=CookieManager.getInstance(); cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(web,true);
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setSaveFormData(false); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){ return !allowed(r.getUrl()); }
        });
        web.setDownloadListener((url,ua,cd,mime,len)->{
            try{
                DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
                String cookie=CookieManager.getInstance().getCookie(url); if(cookie!=null) req.addRequestHeader("Cookie",cookie); if(ua!=null) req.addRequestHeader("User-Agent",ua);
                String name=URLUtil.guessFileName(url,cd,mime); if(!name.toLowerCase().endsWith(".pdf")) name += ".pdf";
                req.setTitle(name); req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
                ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this,"PDF descargado en Descargas",Toast.LENGTH_LONG).show();
            }catch(Exception e){Toast.makeText(this,"No se pudo descargar",Toast.LENGTH_LONG).show();}
        });
        resetTimer(); web.loadUrl(getIntent().getStringExtra("url"));
    }

    private boolean allowed(Uri u){
        String h=u.getHost()==null?"":u.getHost().toLowerCase();
        if(!"https".equalsIgnoreCase(u.getScheme())) return false;
        return h.endsWith(".uanl.mx")||h.equals("uanl.mx")||h.endsWith(".microsoft.com")||h.endsWith(".microsoftonline.com")||h.endsWith(".office.com")||h.endsWith(".office365.com")||h.endsWith(".live.com")||h.endsWith(".sharepoint.com")||h.endsWith(".windows.net")||h.equals("www.microsoft365.com");
    }

    private void resetTimer(){ deadline=System.currentTimeMillis()+IDLE_SECONDS*1000L; handler.removeCallbacks(timeout); handler.post(timeout); }
    @Override public void onUserInteraction(){ super.onUserInteraction(); resetTimer(); }

    private void finishSecure(){
        handler.removeCallbacksAndMessages(null);
        try{ web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); web.clearCache(true); web.clearFormData(); WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); }catch(Exception ignored){}
        finish();
    }
    @Override public void onBackPressed(){ if(web!=null&&web.canGoBack())web.goBack();else finishSecure(); }
    @Override protected void onDestroy(){ handler.removeCallbacksAndMessages(null); if(web!=null)web.destroy(); super.onDestroy(); }
}
