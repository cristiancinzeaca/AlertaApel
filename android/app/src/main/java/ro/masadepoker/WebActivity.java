package ro.masadepoker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Jocul cu boți, Jarvis pe tot ecranul sau jocul online, într-o pagină pe tot ecranul. */
public class WebActivity extends Activity {
    public static final String EXTRA_URL = "url";
    private WebView web;

    public static void open(Context c, String url) {
        c.startActivity(new Intent(c, WebActivity.class).putExtra(EXTRA_URL, url));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#0c1512"));
        getWindow().setNavigationBarColor(Color.parseColor("#0c1512"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0c1512"));
        setup(web);
        setContentView(web);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (b != null) web.restoreState(b);
        else web.loadUrl(url != null ? url : LocalServer.get(this).base() + "/");
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void setup(WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost();
                if ("127.0.0.1".equals(host) || (host != null && host.endsWith(".up.railway.app"))) return false;
                try { v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignored) { }
                return true;
            }
        });
        if ((web.getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
