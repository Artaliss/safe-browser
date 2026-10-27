package com.safebrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String HOME = "https://www.google.com/";
    private WebView web;
    private EditText bar;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);
        web = findViewById(R.id.web);
        bar = findViewById(R.id.bar);
        progress = findViewById(R.id.progress);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // window.open / target=_blank go through onCreateWindow, which refuses them all
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // No DownloadListener on purpose: WebView then ignores every download (.apk drops etc.)

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri to = req.getUrl();
                String scheme = to.getScheme() == null ? "" : to.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    blocked("ลิงก์เปิดแอปอื่น (" + scheme + ":)");
                    return true;
                }
                String from = Uri.parse(view.getUrl() == null ? "" : view.getUrl()).getHost();
                if (sameSite(from, to.getHost()) || req.isRedirect()) return false;
                if (!req.hasGesture()) {
                    // page script tried to send us elsewhere without a tap
                    blocked("redirect ไป " + to.getHost());
                    return true;
                }
                if (isGoogle(from)) return false;
                confirmLeave(to.toString());
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (!bar.hasFocus()) bar.setText(url);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message msg) {
                blocked("popup / หน้าต่างใหม่");
                return false;
            }

            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? ProgressBar.VISIBLE : ProgressBar.GONE);
            }
        });

        bar.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                web.loadUrl(toUrl(bar.getText().toString()));
                bar.clearFocus();
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(bar.getWindowToken(), 0);
                web.requestFocus();
                return true;
            }
            return false;
        });

        if (saved != null) web.restoreState(saved);
        else web.loadUrl(HOME);
    }

    private void confirmLeave(String url) {
        new AlertDialog.Builder(this)
                .setTitle("กำลังจะออกไปเว็บอื่น")
                .setMessage(Uri.parse(url).getHost() + "\n\nถ้าไม่ได้ตั้งใจกด ให้กดยกเลิก")
                .setPositiveButton("ไปต่อ", (d, w) -> web.loadUrl(url))
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void blocked(String what) {
        Toast.makeText(this, "บล็อกแล้ว: " + what, Toast.LENGTH_SHORT).show();
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

    static String toUrl(String input) {
        String t = input.trim();
        if (t.matches("(?i)https?://.*")) return t;
        if (!t.contains(" ") && t.matches("[^/]+\\.[a-zA-Z]{2,}(/.*)?")) return "https://" + t;
        return "https://www.google.com/search?q=" + Uri.encode(t);
    }

    static boolean isGoogle(String host) {
        return host != null && baseDomain(host).startsWith("google.");
    }

    static boolean sameSite(String a, String b) {
        return a != null && b != null && baseDomain(a).equals(baseDomain(b));
    }

    // shortcut: guesses the registrable domain (handles co.th / com.au style), swap for a public-suffix list if it misfires
    static String baseDomain(String host) {
        String[] p = host.toLowerCase().split("\\.");
        int n = p.length;
        if (n < 2) return host.toLowerCase();
        int keep = (n >= 3 && p[n - 1].length() == 2 && p[n - 2].length() <= 3) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = n - keep; i < n; i++) sb.append(i > n - keep ? "." : "").append(p[i]);
        return sb.toString();
    }
}
