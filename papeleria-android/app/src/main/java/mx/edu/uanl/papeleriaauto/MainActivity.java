package mx.edu.uanl.papeleriaauto;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK=7001;
    private String serverUrl(){return getPreferences(MODE_PRIVATE).getString("url","http://192.168.1.50:8787/");}

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web=new WebView(this);
        setContentView(web);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setSaveFormData(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(true); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){
                Uri u=r.getUrl(); String h=u.getHost()==null?"":u.getHost().toLowerCase();
                String base=Uri.parse(serverUrl()).getHost();
                if(base!=null && base.equalsIgnoreCase(h)) return false;
                if("https".equalsIgnoreCase(u.getScheme()) && (h.endsWith(".uanl.mx")||h.equals("uanl.mx")||h.contains("microsoft")||h.endsWith(".office.com")||h.endsWith(".live.com"))){
                    startActivity(new Intent(Intent.ACTION_VIEW,u)); return true;
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p){
                if(fileCallback!=null) fileCallback.onReceiveValue(null);
                fileCallback=cb;
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/pdf");
                startActivityForResult(i,PICK); return true;
            }
        });
        web.setOnLongClickListener(v->{showSettings(); return true;});
        immersive(); web.loadUrl(serverUrl());
    }

    private void showSettings(){
        final EditText e=new EditText(this); e.setText(serverUrl()); e.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Servidor de Papelería").setMessage("Escribe la IP/URL de la PC, por ejemplo http://192.168.1.73:8787/").setView(e)
        .setNegativeButton("Cancelar",null).setPositiveButton("Guardar",(d,w)->{
            String u=e.getText().toString().trim(); if(!u.endsWith("/"))u+="/";
            getPreferences(MODE_PRIVATE).edit().putString("url",u).apply(); web.loadUrl(u);
        }).show();
    }

    private void immersive(){
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK&&fileCallback!=null){Uri[] r=null;if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null)r=new Uri[]{data.getData()};fileCallback.onReceiveValue(r);fileCallback=null;}
    }
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else web.loadUrl(serverUrl());}
    @Override protected void onResume(){super.onResume();immersive();}
}
