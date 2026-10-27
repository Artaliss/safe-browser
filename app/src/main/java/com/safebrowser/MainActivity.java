package com.safebrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String HOME = "https://www.google.com/";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final int REQ_FILE = 1;

    private final List<WebView> tabs = new ArrayList<>();
    private WebView web; // current tab
    private FrameLayout frame, fullscreen;
    private EditText bar, findText;
    private TextView findCount;
    private View findBar;
    private Button tabCount;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private boolean dark;
    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle saved) {
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        int mode = prefs.getInt("dark", 0); // 0 = follow system, 1 = on, 2 = off
        dark = mode == 1 || (mode == 0 && (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? R.style.Dark : R.style.Light);
        super.onCreate(saved);
        setContentView(R.layout.main);
        frame = findViewById(R.id.frame);
        fullscreen = findViewById(R.id.fullscreen);
        bar = findViewById(R.id.bar);
        progress = findViewById(R.id.progress);
        tabCount = findViewById(R.id.tabs);
        findBar = findViewById(R.id.findbar);
        findText = findViewById(R.id.findtext);
        findCount = findViewById(R.id.findcount);

        bar.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                web.loadUrl(toUrl(bar.getText().toString()));
                hideKeyboard(bar);
                web.requestFocus();
                return true;
            }
            return false;
        });
        tabCount.setOnClickListener(v -> showTabs());
        findViewById(R.id.menu).setOnClickListener(this::showMenu);
        findText.setOnEditorActionListener((v, id, ev) -> {
            web.findAllAsync(findText.getText().toString());
            hideKeyboard(findText);
            return true;
        });
        findViewById(R.id.findprev).setOnClickListener(v -> web.findNext(false));
        findViewById(R.id.findnext).setOnClickListener(v -> web.findNext(true));
        findViewById(R.id.findclose).setOnClickListener(v -> closeFind());

        JSONArray open = load("tabs");
        for (int i = 0; i < open.length(); i++) newTab(open.optString(i));
        if (tabs.isEmpty()) newTab(HOME);
        else select(tabs.get(Math.min(prefs.getInt("tab", 0), tabs.size() - 1)));
        if (saved == null) handleIntent(getIntent()); // recreate() would reopen the same link
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    // links opened from other apps (when chosen as the default browser)
    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) newTab(intent.getData().toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        JSONArray urls = new JSONArray();
        for (WebView w : tabs) if (w.getUrl() != null) urls.put(w.getUrl());
        prefs.edit().putString("tabs", urls.toString()).putInt("tab", tabs.indexOf(web)).apply();
    }

    // ---- tabs ----

    private WebView newTab(String url) {
        WebView w = createWebView();
        tabs.add(w);
        select(w);
        w.loadUrl(url);
        return w;
    }

    private void select(WebView w) {
        web = w;
        frame.removeAllViews();
        frame.addView(w);
        bar.clearFocus();
        bar.setText(w.getUrl());
        tabCount.setText(String.valueOf(tabs.size()));
        progress.setVisibility(View.GONE);
        closeFind();
    }

    private void closeTab(WebView w) {
        tabs.remove(w);
        frame.removeView(w);
        w.destroy();
        if (tabs.isEmpty()) newTab(HOME);
        else if (w == web) select(tabs.get(tabs.size() - 1));
        else tabCount.setText(String.valueOf(tabs.size()));
    }

    private void showTabs() {
        String[] names = new String[tabs.size()];
        for (int i = 0; i < names.length; i++) {
            WebView w = tabs.get(i);
            String t = w.getTitle() == null || w.getTitle().isEmpty() ? String.valueOf(w.getUrl()) : w.getTitle();
            names[i] = (w == web ? "▶ " : "") + t;
        }
        new AlertDialog.Builder(this)
                .setTitle("แท็บ (" + tabs.size() + ")")
                .setItems(names, (d, i) -> select(tabs.get(i)))
                .setPositiveButton("แท็บใหม่", (d, i) -> newTab(HOME))
                .setNeutralButton("ปิดแท็บนี้", (d, i) -> closeTab(web))
                .setNegativeButton("ปิด", null)
                .show();
    }

    // ---- WebView setup ----

    private WebView createWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // window.open / target=_blank go through onCreateWindow, which checks them
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // darkens sites that have no dark style of their own
        if (dark && Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkening(true);
        else if (dark) s.setForceDark(WebSettings.FORCE_DARK_ON);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri to = req.getUrl();
                if (!isWeb(to)) {
                    blocked("ลิงก์เปิดแอปอื่น (" + to.getScheme() + ":)");
                    return true;
                }
                String from = Uri.parse(String.valueOf(view.getUrl())).getHost();
                if (sameSite(from, to.getHost()) || req.isRedirect()) return false;
                if (!req.hasGesture()) {
                    // page script tried to send us elsewhere without a tap
                    blocked("redirect ไป " + to.getHost());
                    return true;
                }
                if (isGoogle(from)) return false;
                confirmLeave(to.toString(), () -> view.loadUrl(to.toString()));
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (view == web && !bar.hasFocus()) bar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == web && !bar.hasFocus()) bar.setText(url);
                addHistory(view.getTitle(), url);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message msg) {
                if (!gesture) {
                    blocked("popup ที่เด้งเอง");
                    return false;
                }
                return openPopup(view, msg);
            }

            @Override
            public void onProgressChanged(WebView view, int p) {
                if (view != web) return;
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                customView = v;
                customCallback = cb;
                fullscreen.addView(v);
                fullscreen.setVisibility(View.VISIBLE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        w.setDownloadListener((url, ua, disposition, mime, length) -> confirmDownload(url, ua, disposition, mime));
        w.setFindListener((i, count, done) -> {
            if (done && w == web) findCount.setText(count == 0 ? "0/0" : (i + 1) + "/" + count);
        });
        registerForContextMenu(w);
        return w;
    }

    // Tapped popups load into a hidden probe WebView first, so we see the target URL before anything shows.
    private boolean openPopup(WebView opener, Message msg) {
        String from = Uri.parse(String.valueOf(opener.getUrl())).getHost();
        WebView probe = new WebView(this);
        probe.setWebViewClient(new WebViewClient() {
            boolean done;

            void take(String url) {
                if (done || url == null || url.startsWith("about:")) return;
                done = true;
                probe.stopLoading();
                probe.post(probe::destroy);
                Uri u = Uri.parse(url);
                if (!isWeb(u)) blocked("ลิงก์เปิดแอปอื่น (" + u.getScheme() + ":)");
                else if (sameSite(from, u.getHost()) || isGoogle(from)) newTab(url);
                else confirmLeave(url, () -> newTab(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                take(req.getUrl().toString());
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap icon) {
                take(url);
            }
        });
        ((WebView.WebViewTransport) msg.obj).setWebView(probe);
        msg.sendToTarget();
        return true;
    }

    private void confirmLeave(String url, Runnable go) {
        new AlertDialog.Builder(this)
                .setTitle("กำลังจะออกไปเว็บอื่น")
                .setMessage(Uri.parse(url).getHost() + "\n\nถ้าไม่ได้ตั้งใจกด ให้กดยกเลิก")
                .setPositiveButton("ไปต่อ", (d, w) -> go.run())
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void confirmDownload(String url, String ua, String disposition, String mime) {
        Uri u = Uri.parse(url);
        if (!isWeb(u)) {
            toast("ดาวน์โหลดไฟล์แบบนี้ไม่ได้");
            return;
        }
        String name = URLUtil.guessFileName(url, disposition, mime);
        boolean apk = name.toLowerCase().endsWith(".apk") || "application/vnd.android.package-archive".equals(mime);
        new AlertDialog.Builder(this)
                .setTitle(apk ? "⚠ ไฟล์ติดตั้งแอป" : "ดาวน์โหลดไฟล์?")
                .setMessage(name + "\nจาก " + u.getHost()
                        + (apk ? "\n\nไฟล์ .apk ใช้ติดตั้งแอป ถ้าไม่ได้ตั้งใจโหลดเอง อย่าโหลด อาจเป็นไวรัส" : ""))
                .setPositiveButton("ดาวน์โหลด", (d, w) -> {
                    DownloadManager.Request r = new DownloadManager.Request(u);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) r.addRequestHeader("Cookie", cookie);
                    r.addRequestHeader("User-Agent", ua);
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    toast("กำลังดาวน์โหลด " + name);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
        WebView.HitTestResult h = ((WebView) v).getHitTestResult();
        String link = h.getExtra();
        if (link == null || !isWeb(Uri.parse(link))) return;
        boolean image = h.getType() == WebView.HitTestResult.IMAGE_TYPE || h.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        if (!image && h.getType() != WebView.HitTestResult.SRC_ANCHOR_TYPE) return;
        menu.setHeaderTitle(link);
        menu.add(image ? "เปิดรูปในแท็บใหม่" : "เปิดในแท็บใหม่").setOnMenuItemClickListener(i -> { newTab(link); return true; });
        menu.add(image ? "คัดลอกที่อยู่รูป" : "คัดลอกลิงก์").setOnMenuItemClickListener(i -> { copy(link); return true; });
        if (image) menu.add("ดาวน์โหลดรูป").setOnMenuItemClickListener(i -> {
            confirmDownload(link, web.getSettings().getUserAgentString(), null, null);
            return true;
        });
    }

    // ---- menu ----

    private void showMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        Menu m = p.getMenu();
        String url = web.getUrl();
        boolean marked = url != null && indexOf(load("bookmarks"), url) >= 0;
        boolean desktop = DESKTOP_UA.equals(web.getSettings().getUserAgentString());
        if (web.canGoForward()) add(m, "ไปข้างหน้า", web::goForward);
        add(m, "โหลดใหม่", web::reload);
        add(m, "แท็บใหม่", () -> newTab(HOME));
        add(m, "ปิดแท็บนี้", () -> closeTab(web));
        add(m, marked ? "★ ลบบุ๊กมาร์ก" : "☆ เพิ่มบุ๊กมาร์ก", this::toggleBookmark);
        add(m, "บุ๊กมาร์ก", this::showBookmarks);
        add(m, "ประวัติ", this::showHistory);
        add(m, "ดาวน์โหลด", () -> startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)));
        add(m, "ค้นหาในหน้า", this::openFind);
        add(m, "แชร์", this::share);
        add(m, desktop ? "✓ เวอร์ชันเดสก์ท็อป" : "เวอร์ชันเดสก์ท็อป", this::toggleDesktop);
        add(m, "โหมดมืด", this::chooseDark);
        add(m, "หน้าแรก", () -> web.loadUrl(HOME));
        p.show();
    }

    private void chooseDark() {
        new AlertDialog.Builder(this)
                .setTitle("โหมดมืด")
                .setSingleChoiceItems(new String[]{"ตามระบบ", "เปิด", "ปิด"}, prefs.getInt("dark", 0), (d, i) -> {
                    prefs.edit().putInt("dark", i).apply();
                    d.dismiss();
                    recreate(); // onPause saves the open tabs first
                })
                .show();
    }

    private static void add(Menu m, String title, Runnable action) {
        m.add(title).setOnMenuItemClickListener(i -> { action.run(); return true; });
    }

    private void toggleDesktop() {
        WebSettings s = web.getSettings();
        boolean desktop = DESKTOP_UA.equals(s.getUserAgentString());
        s.setUserAgentString(desktop ? null : DESKTOP_UA);
        s.setUseWideViewPort(!desktop);
        s.setLoadWithOverviewMode(!desktop);
        web.reload();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, web.getUrl());
        startActivity(Intent.createChooser(i, "แชร์ลิงก์"));
    }

    private void openFind() {
        findBar.setVisibility(View.VISIBLE);
        findText.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(findText, 0);
    }

    private void closeFind() {
        if (findBar.getVisibility() != View.VISIBLE) return;
        findBar.setVisibility(View.GONE);
        findText.setText("");
        findCount.setText("");
        web.clearMatches();
        hideKeyboard(findText);
    }

    // ---- bookmarks & history (stored as JSON arrays of [title, url]) ----

    private void toggleBookmark() {
        String url = web.getUrl();
        if (url == null) return;
        JSONArray b = load("bookmarks");
        int i = indexOf(b, url);
        if (i >= 0) b.remove(i);
        else b.put(new JSONArray().put(String.valueOf(web.getTitle())).put(url));
        save("bookmarks", b);
        toast(i >= 0 ? "ลบบุ๊กมาร์กแล้ว" : "เพิ่มบุ๊กมาร์กแล้ว");
    }

    private void showBookmarks() {
        showList("บุ๊กมาร์ก", load("bookmarks"), false, null, null);
    }

    private void showHistory() {
        showList("ประวัติ", load("history"), true, "ล้างข้อมูลทั้งหมด", () -> {
            prefs.edit().remove("history").apply();
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            toast("ล้างประวัติ คุกกี้ และแคชแล้ว");
        });
    }

    private void showList(String title, JSONArray items, boolean newestFirst, String extra, Runnable extraAction) {
        int n = items.length();
        String[] names = new String[n];
        String[] urls = new String[n];
        for (int i = 0; i < n; i++) {
            JSONArray e = items.optJSONArray(newestFirst ? n - 1 - i : i);
            urls[i] = e.optString(1);
            names[i] = e.optString(0).isEmpty() ? urls[i] : e.optString(0);
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle(title).setNegativeButton("ปิด", null);
        if (n == 0) d.setMessage("ยังไม่มีรายการ");
        else d.setItems(names, (dlg, i) -> web.loadUrl(urls[i]));
        if (extra != null) d.setNeutralButton(extra, (dlg, i) -> extraAction.run());
        d.show();
    }

    private void addHistory(String title, String url) {
        if (url == null || !isWeb(Uri.parse(url))) return;
        JSONArray h = load("history");
        if (h.length() > 0 && url.equals(h.optJSONArray(h.length() - 1).optString(1))) return;
        h.put(new JSONArray().put(String.valueOf(title)).put(url));
        while (h.length() > 300) h.remove(0);
        save("history", h);
    }

    private static int indexOf(JSONArray list, String url) {
        for (int i = 0; i < list.length(); i++) if (url.equals(list.optJSONArray(i).optString(1))) return i;
        return -1;
    }

    private JSONArray load(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void save(String key, JSONArray a) {
        prefs.edit().putString(key, a.toString()).apply();
    }

    // ---- misc ----

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        customCallback.onCustomViewHidden();
        customView = null;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) exitFullscreen();
        else if (findBar.getVisibility() == View.VISIBLE) closeFind();
        else if (web.canGoBack()) web.goBack();
        else if (tabs.size() > 1) closeTab(web);
        else super.onBackPressed();
    }

    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link", text));
        toast("คัดลอกแล้ว");
    }

    private void hideKeyboard(View v) {
        v.clearFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void blocked(String what) {
        toast("บล็อกแล้ว: " + what);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static boolean isWeb(Uri u) {
        return "http".equals(u.getScheme()) || "https".equals(u.getScheme());
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
