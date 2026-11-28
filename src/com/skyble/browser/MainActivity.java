package com.skyble.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String PREFS = "skyble_browser";
    /** ইনস্টল করা APK-র versionName — onCreate-এ ভরে নেওয়া হয়, হাতে লেখা সংখ্যা নয় */
    private String VERSION = "0";
    private boolean autoTranslate = true;
    /** কোন ভাষায় অনুবাদ হবে (Google কোড, ডিফল্ট বাংলা) */
    private String transLang = "en";
    private final java.util.HashMap<String, String> assetCache = new java.util.HashMap<String, String>();
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int MAX_TABS = 50;

    private WebView current;
    private EditText urlBar;
    private ProgressBar progress;
    private View btnBack, btnForward;
    private ImageView reloadIcon;
    private boolean pageLoading;
    private TextView pageTitle, tabBadge, navTabLabel, brandView, lockView, tubePill;
    private LinearLayout rootView, bottomBar, navBar, suggestBox, topBarView;
    private ScrollView suggestScroll;
    private TextView updatePill;
    private String knownUpdate, knownApk;
    /** এই অ্যাপ চালু থাকার সময় কোন ভার্সনের পপআপ দেখানো হয়েছে */
    private String updateShown;
    /** সর্বশেষ আপডেট চেক কখন শুরু হয়েছিল (মিলিসেকেন্ড) */
    private long updateCheckedAt;
    private Handler updateTimer;
    /** ইনস্টলের অনুমতি না থাকলে APK এখানে জমা থাকে */
    private Uri pendingInstall;
    /** ডাউনলোড শেষের ছোট বার */
    private View doneBar;
    private Handler doneHandler;
    private FrameLayout.LayoutParams suggestLp;
    private boolean suggestTapping;
    private int suggestScreenH, suggestKeyboardH;
    private final java.util.HashSet<String> sslAllowedHosts = new java.util.HashSet<String>();
    private String sslDialogHost;
    private final android.os.Handler suggestHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable suggestPending;
    private int suggestSeq;
    private String suggestQuery = "";
    private List<String> suggestRemote = new ArrayList<String>();
    private String suggestRemoteQuery = "";
    private FrameLayout screen, holder;
    private SharedPreferences prefs;
    private View overlay;

    private final List<Tab> tabs = new ArrayList<>();

    /** প্রতি ট্যাবে পেজের ভেতরে যে মিডিয়া লিংকগুলো রিকোয়েস্ট হয়েছে (নতুন সবার শেষে) */
    private final java.util.WeakHashMap<WebView, java.util.LinkedHashMap<String, Long>> sniffed =
            new java.util.WeakHashMap<>();

    private static final String[] MEDIA_EXT = {
            ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".3gp",
            ".m3u8", ".mpd", ".mp3", ".m4a", ".aac", ".ogg", ".wav"
    };

    private final Handler pillTick = new Handler(Looper.getMainLooper());
    private String pillUrl;
    /** ইউটিউব নিজের ভেতরে পেজ বদলায়, তখন onPageFinished আসে না — তাই ঠিকানা দেখতে হয় */
    private final Runnable pillWatch = new Runnable() {
        public void run() {
            String u = currentUrl();
            String v = u == null ? "" : u;
            if (!v.equals(pillUrl)) {
                pillUrl = v;
                updateTubePill(u);
            }
            pillTick.postDelayed(this, 700);
        }
    };
    private final java.util.HashSet<String> httpTried = new java.util.HashSet<>();
    private int activeIndex = 0;

    private boolean darkMode, desktopMode, forceDark, darkPages, langEn;
    private String uaMobile;
    private int cBg, cSurface, cSurface2, cText, cMuted, cAccent, cAccentSoft, cLine;
    private int cOnAccent, cOnAccentSoft, cAccent2;
    private long cStamp;
    private long lastBackPress = 0;
    private boolean pendingPalette;

    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE = 4201;
    private static final int REQ_ROLE = 4202;

    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    private static class Tab {
        WebView web;
        String title = "";
        String url = "";
        String lastGood = "";
        boolean loading;
        boolean incognito;
        /** ট্যাব সুইচারে দেখানোর ছোট ছবি — ট্যাব থেকে সরে যাওয়ার সময় তোলা হয় */
        Bitmap thumb;
    }

    // ---------------------------------------------------------------- lifecycle

    // থিমের তিনটে রকম: ফোনের সেটিং মানা / সবসময় রাত / জোর করে সব কালো
    private static final String T_SYSTEM = "system", T_LIGHT = "light",
            T_DARK = "dark", T_FORCE = "force";

    /** পুরনো ভার্সনের দুই সুইচ (dark / forcedark) থেকে থিম সেটিংয়ে উত্তরণ */
    private static String themeKey(SharedPreferences p) {
        String t = p.getString("theme", null);
        if (t == null) {
            t = p.getBoolean("forcedark", false) ? T_FORCE
                    : (p.getBoolean("dark", false) ? T_DARK : T_SYSTEM);
            p.edit().putString("theme", t).apply();
        }
        return t;
    }

    private String themeKey() {
        return themeKey(prefs);
    }

    private boolean themeIsDark() {
        String t = themeKey();
        return T_FORCE.equals(t) || T_DARK.equals(t) || (T_SYSTEM.equals(t) && systemDark);
    }

    private String themeName() {
        String t = themeKey();
        if (T_FORCE.equals(t)) return Lang.t("জোর করে কালো");
        if (T_DARK.equals(t)) return Lang.t("রাতের মোড");
        if (T_LIGHT.equals(t)) return Lang.t("হালকা");
        return Lang.t("সিস্টেম সেটিং");
    }

    private void pickTheme() {
        final String[] names = {Lang.t("সিস্টেম সেটিং অনুযায়ী"), Lang.t("হালকা"),
                Lang.t("রাতের মোড"), Lang.t("জোর করে কালো")};
        final String[] keys = {T_SYSTEM, T_LIGHT, T_DARK, T_FORCE};
        int checked = 0;
        String cur = themeKey();
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(cur)) checked = i;
        dialog().setTitle(Lang.t("থিম"))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs.edit().putString("theme", keys[which]).apply();
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    /** ফোনের সিস্টেম ডার্ক মোড চালু কি না — আসল কনফিগ থেকেই নেওয়া হয় */
    private boolean systemDark;

    @Override
    protected void attachBaseContext(Context base) {
        SharedPreferences p = base.getSharedPreferences(PREFS, MODE_PRIVATE);
        String t = themeKey(p);
        int baseMode = base.getResources().getConfiguration().uiMode;
        boolean sys = (baseMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = T_FORCE.equals(t) || T_DARK.equals(t) || (T_SYSTEM.equals(t) && sys);
        boolean force = T_FORCE.equals(t);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int mode = (dark && !force) ? Configuration.UI_MODE_NIGHT_YES
                                    : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Trace.init(this);
        VERSION = Updater.currentVersion(this);
        Trace.w("app", "start " + VERSION + " sdk " + android.os.Build.VERSION.SDK_INT);
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Lang.init(prefs);
        AdBlock.init(this);
        langEn = Lang.isEn();
        systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        darkMode = themeIsDark();
        desktopMode = prefs.getBoolean("desktop", false);
        forceDark = T_FORCE.equals(themeKey());
        darkPages = prefs.getBoolean("darkpages", true);
        autoTranslate = prefs.getBoolean("autotranslate", true);
        transLang = prefs.getString("translang", "en");
        pickColors();
        syncYouTubeTheme();

        if (Build.VERSION.SDK_INT >= 21) {
            Window w = getWindow();
            w.setStatusBarColor(cBg);
            w.setNavigationBarColor(cBg);
            int flags = 0;
            if (Build.VERSION.SDK_INT >= 23 && !darkMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26 && !darkMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(cBg));

        buildShell();
        restoreSession();

        // অন্য অ্যাপ থেকে http/https লিংক চেপে ব্রাউজার খুললে সেই লিংকটাই খুলবে
        Intent launch = getIntent();
        if (launch != null && launch.getData() != null) {
            String link = launch.getData().toString();
            if (link.startsWith("http://") || link.startsWith("https://")) {
                newTab(link, true);
                setIntent(new Intent()); // recreate() হলে যেন আবার না খোলে
            }
        }

        // অ্যাপ খুললেই নতুন ভার্সন দেখে নেয়; নতুন কিছু থাকলে পপআপ আসে
        checkUpdates(false);
        // খোলা থাকলে (ব্যাকগ্রাউন্ডেও) ঘণ্টায় একবার নিজে থেকেই দেখে
        updateTimer = new Handler(Looper.getMainLooper());
        updateTimer.postDelayed(new Runnable() {
            public void run() {
                if (Updater.due(MainActivity.this)) checkUpdates(false);
                if (updateTimer != null) updateTimer.postDelayed(this, 60L * 60 * 1000);
            }
        }, 60L * 60 * 1000);
    }

    @Override
    protected void onPause() {
        Tab act = active();
        if (act != null) captureThumb(act);
        saveSession();
        super.onPause();
    }

    private void saveSession() {
        JSONArray arr = new JSONArray();
        for (Tab t : tabs) {
            if (t.incognito) continue;   // প্রাইভেট ট্যাব পরের বার ফিরে আসে না
            try {
                JSONObject o = new JSONObject();
                String u = t.url;
                if (u == null || u.length() == 0 || u.equals("about:blank") || u.startsWith("data:")) {
                    u = HOME_URL;
                }
                if (u.startsWith(HOME_URL)) u = HOME_URL;
                o.put("u", u);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString("session", arr.toString())
                .putInt("session_index", activeIndex).apply();
    }

    private void restoreSession() {
        if (!prefs.getBoolean("restore", true)) {
            newTab(homeUrl(), true);
            return;
        }
        try {
            JSONArray arr = new JSONArray(prefs.getString("session", "[]"));
            if (arr.length() == 0) {
                newTab(homeUrl(), true);
                return;
            }
            int count = Math.min(arr.length(), MAX_TABS);
            for (int i = 0; i < count; i++) {
                String u = arr.getJSONObject(i).getString("u");
                if (u.length() == 0 || u.equals("about:blank") || u.startsWith("data:")) u = HOME_URL;
                if (u.startsWith(HOME_URL)) u = homeUrl();
                Tab t = new Tab();
                t.url = u;
                tabs.add(t);
            }
            int idx = prefs.getInt("session_index", 0);
            selectTab(Math.max(0, Math.min(idx, tabs.size() - 1)));
        } catch (Exception e) {
            newTab(homeUrl(), true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingPalette && applyPendingPalette()) return;
        Lang.init(prefs);
        if (Lang.isEn() != langEn) {
            recreate();
            return;
        }
        boolean dark = themeIsDark();
        boolean force = T_FORCE.equals(themeKey());
        boolean desk = prefs.getBoolean("desktop", false);
        if (dark != darkMode || force != forceDark) {
            recreate();
            return;
        }
        applySettings();
        if (desk != desktopMode) {
            desktopMode = desk;
            if (current != null) current.reload();
        }
        boolean tr = prefs.getBoolean("autotranslate", true);
        String tl = prefs.getString("translang", "en");
        if (tr != autoTranslate || !tl.equals(transLang)) {
            autoTranslate = tr;
            transLang = tl;
            if (current != null) current.reload();
        }
        boolean dp = prefs.getBoolean("darkpages", true);
        if (dp != darkPages) {
            darkPages = dp;
            if (current != null) current.reload();
        }
        // অ্যাপ বন্ধ করে আবার খুললে (বা অনেকক্ষণ পর ফিরলে) আবার চেক
        if (System.currentTimeMillis() - updateCheckedAt > 60L * 1000) checkUpdates(false);
        // ইনস্টলের অনুমতি দেওয়া হয়ে থাকলে এখনই ইনস্টলার খুলে দেয়
        if (pendingInstall != null) {
            Uri wait = pendingInstall;
            pendingInstall = null;
            installApk(wait);
        }
        // ওয়ালপেপার/সিস্টেম থিম বদলালে প্যালেটও বদলায় — তখন নতুন রঙে আঁকি
        Theme now = new Theme(this, darkMode);
        if (cStamp != 0) {
            if (now.stamp() != cStamp) {
                // ফুলস্ক্রিন ভিডিও বা প্রাইভেট ট্যাব থাকলে এখনই নতুন করে আঁকি না,
                // নইলে ট্যাবের ব্যাক-হিস্টরি বা প্রাইভেট ট্যাব হারিয়ে যায়
                if (fullView != null || hasPrivate() || !prefs.getBoolean("restore", true)) {
                    pendingPalette = true;
                } else {
                    saveSession();  // recreate() নতুন করে খুললেও ট্যাবগুলো ফিরে আসবে
                    recreate();
                }
            } else {
                pendingPalette = false;   // আবার আগের প্যালেটে ফিরেছে
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (updateTimer != null) updateTimer.removeCallbacksAndMessages(null);
        if (doneHandler != null) doneHandler.removeCallbacksAndMessages(null);
        pillTick.removeCallbacksAndMessages(null);
        if (suggestHandler != null) suggestHandler.removeCallbacksAndMessages(null);
        exitFullscreen();
        for (Tab t : tabs) {
            if (t.web != null) {
                t.web.stopLoading();
                t.web.destroy();
            }
        }
        tabs.clear();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) newTab(intent.getData().toString(), true);
    }

    @Override
    public void onBackPressed() {
        if (suggestScroll != null && suggestScroll.getVisibility() == View.VISIBLE) {
            hideSuggestions();
            hideKeyboard();
            return;
        }
        if (fullView != null) {
            exitFullscreen();
            return;
        }
        if (overlay != null) {
            closeOverlay();
            return;
        }
        if (current != null && current.canGoBack()) {
            current.goBack();
            return;
        }
        if (tabs.size() > 1) {
            closeTab(activeIndex);
            toast(Lang.t("ট্যাব বন্ধ হয়েছে"));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            toast(Lang.t("বের হতে আবার চাপুন"));
        }
    }

    // ---------------------------------------------------------------- colors

    private boolean hasPrivate() {
        for (Tab t : tabs) if (t.incognito) return true;
        return false;
    }

    /** প্যালেট বদলেছে, আর এখন নিরাপদে নতুন করে আঁকা যায় কি না */
    private boolean applyPendingPalette() {
        if (!pendingPalette || fullView != null || hasPrivate()) return false;
        // "শেষ ট্যাব ফিরিয়ে আনুন" বন্ধ থাকলে recreate() সব ট্যাব মুছে ফেলবে
        if (!prefs.getBoolean("restore", true)) return false;
        pendingPalette = false;
        saveSession();
        recreate();
        return true;
    }

    private void pickColors() {
        Theme t = new Theme(this, darkMode);
        cBg = t.bg;
        cSurface = t.surface;
        cSurface2 = t.surface2;
        cText = t.text;
        cMuted = t.muted;
        cAccent = t.accent;
        cAccentSoft = t.accentSoft;
        cLine = t.line;
        cOnAccent = t.onAccent;
        cOnAccentSoft = t.onAccentSoft;
        cAccent2 = t.accent2;
        cStamp = t.stamp();
    }

    /** #RRGGBB লেখা — HTML/JS-এ রং বসাতে */
    static String hex(int color) {
        return "#" + hex6(color);
    }

    /** ঘরের নম্বর ছাড়া RRGGBB — URL-এর hash-এ */
    static String hex6(int color) {
        return String.format(java.util.Locale.US, "%06X", 0xFFFFFF & color);
    }

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable roundedBorder(int color, float radius) {
        GradientDrawable d = rounded(color, radius);
        d.setStroke(dp(1), cLine);
        return d;
    }

    private GradientDrawable sheetBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(cBg);
        float r = dp(20);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    private void ripple(View v) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) v.setBackgroundResource(tv.resourceId);
    }

    /** গোল কোণে রিপল — আধুনিক বাটন/সারির জন্য */
    private void rippleRound(View v, float radius) {
        if (Build.VERSION.SDK_INT < 21) {
            ripple(v);
            return;
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        android.content.res.ColorStateList tint = ColorStateList.valueOf(
                Theme.withAlpha(cAccent, darkMode ? 0x38 : 0x24));
        v.setBackground(new android.graphics.drawable.RippleDrawable(tint, null, mask));
    }

    /** আগে থেকে থাকা পটভূমির ওপরে রিপল */
    private void rippleOn(View v, float radius, android.graphics.drawable.Drawable base) {
        if (Build.VERSION.SDK_INT < 21) return;
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radius));
        android.content.res.ColorStateList tint = ColorStateList.valueOf(
                Theme.withAlpha(cAccent, darkMode ? 0x38 : 0x24));
        v.setBackground(new android.graphics.drawable.RippleDrawable(tint, base, mask));
    }

    /** গোল কোণা ভরাট পটভূমি — বোতাম, চিপ, আইকন টাইলের জন্য */
    private GradientDrawable pill(int color, float radius) {
        return rounded(color, radius);
    }

    private TextView glyphButton(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        ripple(t);
        return t;
    }

    /** res/drawable-এর ভেক্টর আইকন, রঙ বদলানো যায় */
    private ImageView icon(String name, int sizeDp, int color) {
        ImageView v = new ImageView(this);
        int id = getResources().getIdentifier("ic_" + name, "drawable", getPackageName());
        if (id != 0) {
            v.setImageResource(id);
            v.setColorFilter(color);
        }
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // ---------------------------------------------------------------- shell

    private void buildShell() {
        screen = new FrameLayout(this);
        screen.setBackgroundColor(cBg);

        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(cBg);

        LinearLayout topBar = new LinearLayout(this);
        topBarView = topBar;
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(cBg);
        topBar.setPadding(dp(12), dp(8), dp(6), dp(8));

        // title row: brand | page title | tab badge | menu
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brandView = brand;
        brand.setText(Lang.t("Skyble Browser"));
        brand.setTextSize(13);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextColor(cAccent);
        titleRow.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageTitle = new TextView(this);
        pageTitle.setTextSize(11);
        pageTitle.setTextColor(cMuted);
        pageTitle.setSingleLine(true);
        pageTitle.setGravity(Gravity.END);
        pageTitle.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(pageTitle, plp);

        tabBadge = new TextView(this);
        tabBadge.setText("1");
        tabBadge.setTextSize(12);
        tabBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tabBadge.setTextColor(cText);
        tabBadge.setGravity(Gravity.CENTER);
        tabBadge.setBackground(roundedBorder(cSurface, 8));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(28), dp(28));
        tlp.rightMargin = dp(6);
        titleRow.addView(tabBadge, tlp);
        tabBadge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTabSwitcher(); }
        });

        // উপরের নাম-সারি বাদ: শুধু ঠিকানার বার আর নিচের বার

        // address row
        LinearLayout addrPill = new LinearLayout(this);
        addrPill.setOrientation(LinearLayout.HORIZONTAL);
        addrPill.setGravity(Gravity.CENTER_VERTICAL);
        addrPill.setBackground(rounded(cSurface2, 26));
        if (Build.VERSION.SDK_INT >= 21) addrPill.setElevation(dp(1));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView lock = new TextView(this);
        lockView = lock;
        lock.setText("🔒");
        lock.setTextSize(12);
        lock.setPadding(dp(15), 0, dp(7), 0);
        addrPill.addView(lock);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(14);
        urlBar.setTextColor(cText);
        urlBar.setHintTextColor(cMuted);
        urlBar.setHint(Lang.t("সার্চ করুন বা ঠিকানা লিখুন"));
        urlBar.setBackground(null);
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setPadding(0, dp(9), 0, dp(9));
        urlBar.setSelectAllOnFocus(true);
        urlBar.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (urlBar.hasFocus()) showSuggestions(s == null ? "" : s.toString());
            }
            public void afterTextChanged(android.text.Editable s) { }
        });
        urlBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) return;
                // আঙুল তালিকার সারিতে থাকলে সাথে সাথে সরাব না, নইলে ক্লিক হারিয়ে যায়
                suggestHandler.postDelayed(new Runnable() {
                    public void run() {
                        if (suggestTapping || urlBar.hasFocus()) return;
                        hideSuggestions();
                    }
                }, 260);
            }
        });
        addrPill.addView(urlBar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // নতুন ভার্সন এলে ঠিকানা বারে ছোট চিহ্ন — চাপলে আপডেটের কথা বলে
        updatePill = new TextView(this);
        updatePill.setTextSize(11);
        updatePill.setTypeface(Typeface.DEFAULT_BOLD);
        updatePill.setTextColor(cOnAccentSoft);
        updatePill.setGravity(Gravity.CENTER);
        updatePill.setPadding(dp(8), dp(4), dp(8), dp(4));
        updatePill.setBackground(rounded(cAccentSoft, 20));
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (knownUpdate != null) showUpdate(knownUpdate, knownApk, true);
            }
        });
        LinearLayout.LayoutParams uplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uplp.rightMargin = dp(4);
        addrPill.addView(updatePill, uplp);

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    go(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });

        final ImageView reload = icon("refresh", 20, cMuted);
        reloadIcon = reload;
        LinearLayout reloadBox = new LinearLayout(this);
        reloadBox.setGravity(Gravity.CENTER);
        reloadBox.setPadding(dp(10), dp(7), dp(12), dp(7));
        rippleRound(reloadBox, 20);
        reloadBox.addView(reload, new LinearLayout.LayoutParams(dp(20), dp(20)));
        reloadBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pageLoading) {
                    if (current != null) current.stopLoading();
                    setReloadState(false);
                    return;
                }
                reload.animate().rotationBy(360f).setDuration(480)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                if (current != null) current.reload();
            }
        });
        addrPill.addView(reloadBox);

        topBar.addView(addrPill, pl);

        // ইউটিউব পেজে দেখা যায়: এক চাপে ভিডিও ডাউনলোড
        tubePill = new TextView(this);
        tubePill.setTextSize(11);
        tubePill.setTypeface(Typeface.DEFAULT_BOLD);
        tubePill.setTextColor(cOnAccentSoft);
        tubePill.setGravity(Gravity.CENTER);
        tubePill.setPadding(dp(12), dp(5), dp(12), dp(5));
        tubePill.setBackground(rounded(cAccentSoft, 20));
        tubePill.setVisibility(View.GONE);
        pillTick.postDelayed(pillWatch, 700);
        tubePill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = currentUrl();
                if (Tube.videoId(u) != null) {
                    youtubeDownload(u);
                } else {
                    toast(Lang.t("যেকোনো ভিডিও খুলে আবার চাপো"));
                }
            }
        });
        LinearLayout.LayoutParams tplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tplp.topMargin = dp(7);
        tplp.leftMargin = dp(2);
        topBar.addView(tubePill, tplp);

        rootView.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        suggestBox = new LinearLayout(this);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        suggestBox.setBackground(rounded(cSurface2, 16));

        // কীবোর্ডের সাথে তালিকা স্ক্রিনের বাইরে চলে যাবে না — বেশি সারি হলে ভেতরে স্ক্রল
        suggestScroll = new ScrollView(this);
        suggestScroll.setVerticalScrollBarEnabled(false);
        suggestScroll.setFillViewport(false);
        suggestScroll.addView(suggestBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        suggestScroll.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 21) suggestScroll.setElevation(dp(8));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        if (Build.VERSION.SDK_INT >= 21) {
            progress.setProgressTintList(ColorStateList.valueOf(cAccent));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.withAlpha(cAccent, 0x33)));
        }
        rootView.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        holder = new FrameLayout(this);
        rootView.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View barLine = new View(this);
        barLine.setBackgroundColor(cLine);
        rootView.addView(barLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(cBg);
        bottomBar.setPadding(dp(4), dp(2), dp(4), dp(3));

        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);
        navBar.setPadding(0, 0, 0, 0);
        bottomBar.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnBack = navItem("back", Lang.t("পিছনে"), new Runnable() {
            public void run() { if (current != null && current.canGoBack()) current.goBack(); }
        });
        btnForward = navItem("forward", Lang.t("সামনে"), new Runnable() {
            public void run() { if (current != null && current.canGoForward()) current.goForward(); }
        });
        navItem("home", Lang.t("হোম"), new Runnable() {
            public void run() {
                if (current == null) return;
                urlBar.setText("");
                pageTitle.setText("");
                current.loadUrl(homeUrl());
            }
        });
        LinearLayout tabBox = navItemBox("tab", Lang.t("ট্যাব"), new Runnable() {
            public void run() { showTabSwitcher(); }
        });
        navTabLabel = (TextView) tabBox.getChildAt(1);
        navMenuButton();

        rootView.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        screen.addView(rootView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // সাজানোর তালিকা পাতার ওপরে ভাসে — দেখালে নিচের কনটেন্ট নড়ে না
        suggestLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        suggestLp.gravity = Gravity.TOP;
        suggestLp.leftMargin = dp(12);
        suggestLp.rightMargin = dp(12);
        screen.addView(suggestScroll, suggestLp);

        watchKeyboard();
        setContentView(screen);
        updateNavButtons();
    }

    /** কীবোর্ড নামলে ভাসমান তালিকাও সরবে */
    private void watchKeyboard() {
        screen.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        int h = screen.getHeight();
                        if (h <= 0) return;
                        if (h > suggestScreenH) suggestScreenH = h;
                        boolean shrunk = h < suggestScreenH - dp(90);
                        if (shrunk) {
                            suggestKeyboardH = h;
                        } else if (suggestKeyboardH > 0) {
                            suggestKeyboardH = 0;
                            if (suggestScroll != null
                                    && suggestScroll.getVisibility() == View.VISIBLE) {
                                suggestHandler.post(new Runnable() {
                                    public void run() { hideSuggestions(); }
                                });
                            }
                        }
                    }
                });
    }

    private View navItem(String iconName, String label, final Runnable action) {
        return navItemBox(iconName, label, action).getChildAt(0);
    }

    private LinearLayout navItemBox(String iconName, String label, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(3), 0, dp(1));
        rippleRound(box, 14);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });

        ImageView g = icon(iconName, 25, cText);
        box.addView(g);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(8.5f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(cMuted);
        t.setPadding(0, dp(1), 0, 0);
        t.setIncludeFontPadding(false);
        box.addView(t);

        navBar.addView(box, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    /** নিচের বারের ডানদিকের গোল মেনু বোতাম */
    private void navMenuButton() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(2), 0, dp(1));
        rippleRound(box, 14);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(cAccent);
        LinearLayout menuCircle = new LinearLayout(this);
        menuCircle.setGravity(Gravity.CENTER);
        menuCircle.setBackground(circle);
        ImageView g = icon("menu", 20, cOnAccent);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        menuCircle.addView(g);
        box.addView(menuCircle, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView t = new TextView(this);
        t.setText(Lang.t("মেনু"));
        t.setTextSize(8.5f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(cMuted);
        t.setPadding(0, dp(1), 0, 0);
        t.setIncludeFontPadding(false);
        box.addView(t);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(); }
        });
        navBar.addView(box, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void updateNavButtons() {
        if (current == null) return;
        btnBack.setAlpha(current.canGoBack() ? 1f : 0.32f);
        btnForward.setAlpha(current.canGoForward() ? 1f : 0.32f);
        updateTabBadge();
    }

    private void updateTabBadge() {
        tabBadge.setText(String.valueOf(tabs.size()));
        if (navTabLabel != null) {
            navTabLabel.setText(tabs.size() > 1 ? Lang.t("ট্যাব ") + tabs.size() : Lang.t("ট্যাব"));
            navTabLabel.setTextColor(tabs.size() > 1 ? cAccent : cMuted);
        }
    }

    // ---------------------------------------------------------------- tabs

    private Tab tabOf(WebView view) {
        for (Tab t : tabs) if (t.web == view) return t;
        return null;
    }

    private Tab active() {
        if (tabs.isEmpty()) return null;
        if (activeIndex >= tabs.size()) activeIndex = tabs.size() - 1;
        return tabs.get(activeIndex);
    }

    private void newTab(String url, boolean activate) {
        newTab(url, activate, false);
    }

    private void newTab(String url, boolean activate, boolean incognito) {
        if (tabs.size() >= MAX_TABS) {
            toast(Lang.t("সর্বোচ্চ ") + MAX_TABS + Lang.t(" ট্যাব খোলা যায়"));
            return;
        }
        Tab t = new Tab();
        t.url = url;
        t.incognito = incognito;
        t.web = buildWebView(incognito);
        holder.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.web.setVisibility(View.GONE);
        tabs.add(t);
        if (activate) {
            selectTab(tabs.size() - 1);
        } else {
            t.web.loadUrl(url);
        }
        updateTabBadge();
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (index != activeIndex) {
            // পুরনো ট্যাবের ছবি তুলে রাখি, সুইচারে পরে দেখাবে
            Tab old = active();
            if (old != null && old.web != null && old.web.getVisibility() == View.VISIBLE) {
                captureThumb(old);
            }
        }
        activeIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).web != null) {
                tabs.get(i).web.setVisibility(i == index ? View.VISIBLE : View.GONE);
            }
        }
        Tab t = tabs.get(index);
        if (t.web == null) {
            t.web = buildWebView(t.incognito);
            holder.addView(t.web, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.web.setVisibility(View.VISIBLE);
            if (t.url != null && t.url.length() > 0) t.web.loadUrl(t.url);
        }
        current = t.web;
        if (t.web.getUrl() == null && t.url != null && t.url.length() > 0) {
            t.web.loadUrl(t.url);
        }
        if (t.url != null && t.url.startsWith("file:")) {
            urlBar.setText("");
            urlBar.setHint(Lang.t("সার্চ করুন বা ঠিকানা লিখুন"));
            pageTitle.setText("");
        } else {
            urlBar.setText(prettyUrl(t.url));
            pageTitle.setText(t.title);
        }
        // এই ট্যাব নিজে লোড করছে কি না, সেটাই আইকনে দেখাবে
        setReloadState(t.web != null && t.loading);
        progress.setVisibility(t.web != null && t.loading ? View.VISIBLE : View.GONE);
        updateNavButtons();
        showPrivateMark(t.incognito);
        updateTubePill(t.url);
    }

    /** প্রাইভেট ট্যাবে উপরের নাম আর তালার চিহ্ন বদলে যায়, যাতে ভুল না হয় */
    private void showPrivateMark(boolean on) {
        if (brandView == null) return;
        if (on) {
            brandView.setText(Lang.t("প্রাইভেট"));
            brandView.setTextColor(cAccent2);
            if (lockView != null) lockView.setText("🕶");
        } else {
            brandView.setText(Lang.t("Skyble Browser"));
            brandView.setTextColor(cAccent);
            if (lockView != null) lockView.setText("🔒");
        }
    }

    /** শেষ প্রাইভেট ট্যাব বন্ধ হলে ওই সেশনের কুকি আর ক্যাশে মুছে যায় */
    private void clearPrivateLeftovers() {
        for (Tab t : tabs) if (t.incognito) return;
        try {
            CookieManager.getInstance().removeSessionCookies(null);
        } catch (Exception ignored) { }
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) { }
        try {
            if (current != null) current.clearCache(true);
        } catch (Exception ignored) { }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        if (t.web != null) {
            holder.removeView(t.web);
            t.web.stopLoading();
            t.web.destroy();
        }
        boolean wasPrivate = t.incognito;
        if (t.thumb != null) {
            t.thumb.recycle();
            t.thumb = null;
        }
        tabs.remove(index);
        if (tabs.isEmpty()) {
            newTab(homeUrl(), true);
            if (wasPrivate) clearPrivateLeftovers();
            return;
        }
        if (activeIndex >= index) activeIndex = Math.max(0, activeIndex - 1);
        selectTab(activeIndex);
        updateTabBadge();
        if (wasPrivate) clearPrivateLeftovers();
        applyPendingPalette();
    }

    private FrameLayout tabSheet;
    private LinearLayout tabGrid;
    private TextView tabCount;

    private void showTabSwitcher() {
        hideSuggestions();
        Tab act = active();
        if (act != null) captureThumb(act);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Theme.withAlpha(cBg, 0xF7));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tabCount = new TextView(this);
        tabCount.setText(Lang.t("ট্যাব (") + tabs.size() + ")");
        tabCount.setTextSize(18);
        tabCount.setTypeface(Typeface.DEFAULT_BOLD);
        tabCount.setTextColor(cText);
        header.addView(tabCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView plus = new TextView(this);
        plus.setText("＋");
        plus.setTextSize(20);
        plus.setTextColor(cAccent);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(dp(10), dp(4), dp(10), dp(4));
        rippleRound(plus, 18);
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                newTab(homeUrl(), true);
                fillTabGrid();
            }
        });
        header.addView(plus);

        TextView done = new TextView(this);
        done.setText(Lang.t("সম্পন্ন"));
        done.setTextSize(13);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setTextColor(cAccent);
        done.setPadding(dp(12), dp(8), dp(10), dp(8));
        rippleRound(done, 18);
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeOverlay(); }
        });
        header.addView(done);

        panel.addView(header);

        ScrollView scroll = new ScrollView(this);
        tabGrid = new LinearLayout(this);
        tabGrid.setOrientation(LinearLayout.VERTICAL);
        tabGrid.setPadding(0, dp(12), 0, dp(24));
        scroll.addView(tabGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setClipToPadding(false);

        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        box.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabSheet = box;
        fillTabGrid();
        openOverlay(box);
    }

    /** সুইচারের বড় বর্গাকার ট্যাব-কার্ডগুলো আবার সাজায় (ট্যাব বন্ধ/খোলার পরে) */
    private void fillTabGrid() {
        if (tabGrid == null) return;
        if (tabCount != null) tabCount.setText(Lang.t("ট্যাব (") + tabs.size() + ")");
        tabGrid.removeAllViews();
        int gap = dp(10);
        for (int i = 0; i < tabs.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = gap;

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = gap / 2;
            row.addView(tabCard(i), clp);

            if (i + 1 < tabs.size()) {
                LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                rlp2.leftMargin = gap / 2;
                row.addView(tabCard(i + 1), rlp2);
            } else {
                View pad = new View(this);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, 1, 1f);
                plp.leftMargin = gap / 2;
                row.addView(pad, plp);
            }
            tabGrid.addView(row, rlp);
        }
    }

    /** ক্রোমের মতো বর্গাকার ট্যাব: উপরে পেজের ছবি, নিচে নাম আর বন্ধের বোতাম */
    private View tabCard(final int idx) {
        final Tab t = tabs.get(idx);

        SquareFrame card = new SquareFrame(this);
        GradientDrawable bg = rounded(idx == activeIndex ? cSurface : cSurface2, 14);
        card.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) card.setClipToOutline(true);
        // লাইনটা ছবির ওপরে বসে (ফোরগ্রাউন্ড), তাই সাদা পেজেও স্পষ্ট দেখা যায়
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(Color.TRANSPARENT);
        edge.setCornerRadius(dp(14));
        if (idx == activeIndex) edge.setStroke(dp(3), cAccent);
        else edge.setStroke(dp(1), Theme.withAlpha(cMuted, 0x70));
        card.setForeground(edge);
        card.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout shotBox = new FrameLayout(this);
        shotBox.setBackgroundColor(cSurface2);
        if (t.thumb != null) {
            ImageView shot = new ImageView(this);
            shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
            shot.setImageBitmap(t.thumb);
            shotBox.addView(shot, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            TextView empty = new TextView(this);
            empty.setText(t.incognito ? "🕶" : "🌐");
            empty.setTextSize(30);
            empty.setGravity(Gravity.CENTER);
            shotBox.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (t.incognito) {
            TextView badge = new TextView(this);
            badge.setText("🕶");
            badge.setTextSize(11);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            badge.setBackground(pill(0x99000000, 8));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.TOP | Gravity.LEFT;
            blp.leftMargin = dp(6);
            blp.topMargin = dp(6);
            shotBox.addView(badge, blp);
        }
        col.addView(shotBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(Theme.withAlpha(cMuted, 0x38));
        col.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(8), dp(6), dp(2), dp(6));

        if (isRealPage(t.url)) {
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Favicon.bind(this, t.url, icon, cAccent, cOnAccent);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(16), dp(16));
            ilp.rightMargin = dp(6);
            strip.addView(icon, ilp);
        }

        TextView name = new TextView(this);
        String label = t.title;
        if (label == null || label.length() == 0) {
            label = t.url == null || t.url.length() == 0 ? Lang.t("নতুন ট্যাব") : prettyUrl(t.url);
            if (label.startsWith("file:")) label = Lang.t("হোম");
        }
        name.setText(label);
        name.setTextSize(11);
        name.setTextColor(cText);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        strip.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(12);
        close.setTextColor(cMuted);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(7), dp(4), dp(7), dp(4));
        rippleRound(close, 14);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // সুইচার খোলা থাকে, শুধু কার্ডটা সরে যায়
                closeTab(idx);
                fillTabGrid();
            }
        });
        strip.addView(close, new LinearLayout.LayoutParams(dp(28), dp(28)));

        col.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeOverlay();
                selectTab(idx);
            }
        });
        return card;
    }

    /** ট্যাবের পেজটা ছোট ছবি হিসেবে তুলে রাখে (স্ক্রিনে দেখানো অবস্থায়) */
    private void captureThumb(Tab t) {
        if (t == null || t.web == null) return;
        try {
            int w = t.web.getWidth();
            int h = t.web.getHeight();
            if (w <= 0 || h <= 0) return;
            float scale = Math.min(1f, 320f / w);
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            Bitmap full = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(full);
            c.scale(scale, scale);
            t.web.draw(c);
            // কিছু ফোনে WebView নিজের ছবি দেয় না, পুরো সাদা/কালো আসে — тогда পুরোনোটা রাখি
            if (isFlat(full)) {
                full.recycle();
                return;
            }
            // কার্ড বর্গাকার, তাই পেজের ওপরের বর্গাকার অংশটাই রাখি
            int side = Math.min(tw, th);
            Bitmap shot = full;
            if (side < tw || side < th) {
                shot = Bitmap.createBitmap(full, 0, 0, side, side);
                full.recycle();
            }
            if (t.thumb != null && t.thumb != shot) t.thumb.recycle();
            t.thumb = shot;
        } catch (Throwable ignored) { }
    }

    /** ছবিটা কি এক রঙের (অর্থাৎ কিছুই আঁকা হয়নি)? */
    private static boolean isFlat(Bitmap bm) {
        try {
            int w = bm.getWidth();
            int h = bm.getHeight();
            if (w < 4 || h < 4) return true;
            int first = bm.getPixel(0, 0);
            int sx = Math.max(1, w / 8);
            int sy = Math.max(1, h / 8);
            for (int y = 0; y < h; y += sy) {
                for (int x = 0; x < w; x += sx) {
                    if (bm.getPixel(x, y) != first) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** সবসময় বর্গাকার থাকে, ভেতরের সব কিছু ওই বর্গে বসে */
    private static class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
            int side = getMeasuredWidth();
            setMeasuredDimension(side, side);
        }
    }

    /** সব ট্যাব মুছে ফেলার আগে একবার জিজ্ঞাসা */
    private void confirmCloseAll() {
        dialog().setTitle(Lang.t("সব ট্যাব বন্ধ করবেন?"))
                .setMessage(Lang.t("সব ট্যাব মুছে নতুন করে শুরু হবে।"))
                .setPositiveButton(Lang.t("সব বন্ধ"), (d, w) -> {
                    closeOverlay();
                    closeAllTabs();
                })
                .setNegativeButton(Lang.t("না"), null)
                .show();
    }

    // ---------------------------------------------------------------- modern menu sheet

    private void showMenu() {
        hideSuggestions();
        final FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0x52000000);   // ম্যাটেরিয়াল স্ক্রিম
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeOverlay(); }
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cSurface);
        float r = dp(26);
        bg.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
        sheet.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) sheet.setElevation(dp(20));
        sheet.setPadding(dp(10), dp(6), dp(10), dp(16));
        sheet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { }
        });

        View grip = new View(this);
        grip.setBackground(rounded(Theme.withAlpha(cMuted, 0x59), 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(38), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(8);
        glp.bottomMargin = dp(6);
        sheet.addView(grip, glp);

        sheet.addView(sheetHeader());
        sheet.addView(quickActions(current != null && isBookmarked(current.getUrl())));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        boolean marked = current != null && isBookmarked(current.getUrl());
        list.addView(menuRow("star",
                marked ? Lang.t("বুকমার্ক সরান") : Lang.t("এই পেজ বুকমার্ক করুন"),
                null, null, new Runnable() {
                    public void run() { toggleBookmark(); }
                }));
        list.addView(menuRow("bookmarks", Lang.t("বুকমার্ক"),
                String.valueOf(loadList("bookmarks").size()), new Runnable() {
                    public void run() { showList(Lang.t("বুকমার্ক"), "bookmarks", true); }
                }));
        list.addView(menuRow("history", Lang.t("ইতিহাস"),
                String.valueOf(loadList("history").size()), new Runnable() {
                    public void run() { showHistory(); }
                }));
        list.addView(menuRow("apps", Lang.t("প্রিয় সাইট"),
                String.valueOf(loadSites().size()), new Runnable() {
                    public void run() { showSiteEditor(); }
                }));
        list.addView(menuRow("home", Lang.t("হোমে যোগ করুন"), null, new Runnable() {
            public void run() { addToHome(); }
        }));
        int running = Downloader.activeCount();
        list.addView(menuRow("download", Lang.t("ডাউনলোড"),
                running > 0 ? running + Lang.t(" চলছে")
                        : String.valueOf(downloadRecords().length()), new Runnable() {
                    public void run() { showDownloads(); }
                }));
        list.addView(menuRow("share", Lang.t("শেয়ার"), null, new Runnable() {
            public void run() {
                if (current == null) return;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, current.getUrl());
                startActivity(Intent.createChooser(i, Lang.t("শেয়ার করুন")));
            }
        }));
        list.addView(menuRow("link", Lang.t("লিংক কপি"), null, new Runnable() {
            public void run() { copyLink(); }
        }));

        list.addView(sectionLabel(Lang.t("পেজ")));

        list.addView(menuRow("search", Lang.t("পেজে খুঁজুন"), null, new Runnable() {
            public void run() { findInPage(); }
        }));
        list.addView(menuRow("download", Lang.t("মিডিয়া ডাউনলোড"), null, new Runnable() {
            public void run() { downloadMedia(); }
        }));
        list.addView(menuRow("play", Lang.t("প্লেয়ারে চালান"), null, new Runnable() {
            public void run() { playInPlayer(); }
        }));
        list.addView(menuRow("public", Lang.t("অনুবাদ"),
                Langs.name(transLang, Lang.isEn()), new Runnable() {
                    public void run() { showTranslateDialog(); }
                }));
        list.addView(menuRow("refresh", Lang.t("রিলোড"), null, new Runnable() {
            public void run() { if (current != null) current.reload(); }
        }));
        list.addView(menuRow("print", Lang.t("প্রিন্ট"), null, new Runnable() {
            public void run() { printPage(); }
        }));
        list.addView(toggleRow("desktop", Lang.t("ডেস্কটপ সাইট"),
                Lang.t("কম্পিউটারের মতো পেজ দেখায়"), desktopMode, new OnToggle() {
                    public void onChange(boolean on) {
                        desktopMode = on;
                        prefs.edit().putBoolean("desktop", desktopMode).apply();
                        applySettings();
                        if (current != null) current.reload();
                    }
                }));
        list.addView(themePicker());

        list.addView(sectionLabel(Lang.t("ট্যাব")));

        list.addView(menuRow("tab", Lang.t("নতুন প্রাইভেট ট্যাব"),
                Lang.t("ইতিহাস সেভ হয় না"), new Runnable() {
                    public void run() { newTab(homeUrl(), true, true); }
                }));
        list.addView(menuRow("delete", Lang.t("সব ট্যাব বন্ধ"),
                String.valueOf(tabs.size()), new Runnable() {
                    public void run() { confirmCloseAll(); }
                }));

        list.addView(sectionLabel(Lang.t("অ্যাপ")));

        list.addView(menuRow("public", Lang.t("ডিফল্ট ব্রাউজার"),
                isDefaultBrowser() ? Lang.t("সেট আছে") : Lang.t("সেট করুন"), new Runnable() {
                    public void run() { askDefaultBrowser(); }
                }));
        list.addView(menuRow("public", Lang.t("নেটওয়ার্ক পরীক্ষা"), null, new Runnable() {
            public void run() { networkCheck(); }
        }));
        list.addView(menuRow("settings", Lang.t("সেটিংস"), null, new Runnable() {
            public void run() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));
        list.addView(menuRow("refresh", Lang.t("আপডেট চেক"), null, new Runnable() {
            public void run() { checkUpdates(true); }
        }));
        list.addView(menuRow("info", Lang.t("অ্যাপ সম্পর্কে"), VERSION, new Runnable() {
            public void run() { about(); }
        }));

        addDividers(list);
        scroll.addView(list);
        sheet.addView(scroll);

        // keep the sheet from growing past ~72% of the screen so it always fits
        final ScrollView menuScroll = scroll;
        final LinearLayout menuList = list;
        scroll.post(new Runnable() {
            public void run() {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
                if (menuList.getHeight() > max) {
                    ViewGroup.LayoutParams lp = menuScroll.getLayoutParams();
                    lp.height = max;
                    menuScroll.setLayoutParams(lp);
                }
            }
        });

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM;
        slp.setMargins(dp(8), dp(8), dp(8), dp(8));
        box.addView(sheet, slp);

        openOverlay(box);
        sheet.setTranslationY(dp(48));
        sheet.setAlpha(0f);
        sheet.animate().translationY(0).alpha(1f).setDuration(190).start();
    }

    /** মেনুর ওপরে চারটি দ্রুত কাজ */
    private View quickActions(boolean marked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        row.addView(quickAction("add", Lang.t("নতুন ট্যাব"), new Runnable() {
            public void run() { newTab(homeUrl(), true); }
        }));
        row.addView(quickAction("refresh", Lang.t("রিলোড"), new Runnable() {
            public void run() { if (current != null) current.reload(); }
        }));
        row.addView(quickAction("share", Lang.t("শেয়ার"), new Runnable() {
            public void run() {
                if (current == null) return;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, current.getUrl());
                startActivity(Intent.createChooser(i, Lang.t("শেয়ার করুন")));
            }
        }));
        row.addView(quickAction("star", Lang.t("বুকমার্ক"), new Runnable() {
            public void run() { toggleBookmark(); }
        }));
        return row;
    }

    private View quickAction(String iconName, String label, final Runnable action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(2), 0, dp(8));
        rippleRound(b, 18);

        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(cAccentSoft);
        circle.setBackground(circleBg);
        ImageView g = icon(iconName, 24, cOnAccentSoft);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        circle.addView(g);
        b.addView(circle, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(10.5f);
        t.setTextColor(cMuted);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(2), dp(6), dp(2), 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.addView(t);

        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeOverlay();
                action.run();
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** দুই সারির মাঝে সরু রেখা, আইকনের ডান থেকে শুরু */
    private View menuDivider() {
        View line = new View(this);
        line.setBackgroundColor(cLine);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(58);
        lp.rightMargin = dp(12);
        line.setLayoutParams(lp);
        return line;
    }

    /** পরপর দুটি সারির মাঝে রেখা বসায়; সেকশন লেখার পাশে বসায় না */
    private void addDividers(LinearLayout list) {
        for (int i = list.getChildCount() - 1; i > 0; i--) {
            View above = list.getChildAt(i - 1);
            View below = list.getChildAt(i);
            if (ROW_TAG.equals(above.getTag()) && ROW_TAG.equals(below.getTag())) {
                list.addView(menuDivider(), i);
            }
        }
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(cAccent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(12), dp(18), dp(12), dp(6));
        return t;
    }

    private View menuRow(String icon, String label, String value, final Runnable action) {
        return menuRow(icon, label, null, value, action);
    }

    private View menuRow(String icon, String label, String sub, String value,
                         final Runnable action) {
        LinearLayout row = rowShell(icon, label, sub, value, true);
        row.setTag(ROW_TAG);
        if (action != null) {
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closeOverlay();
                    action.run();
                }
            });
        }
        return row;
    }

    private static final String ROW_TAG = "bb_row";

    private LinearLayout rowShell(String icon, String label, String sub,
                                  String value, boolean chevron) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(7), dp(8), dp(7));
        rippleRound(row, 15);

        LinearLayout tile = new LinearLayout(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(rounded(cAccentSoft, 13));
        tile.addView(icon(icon, 23, cOnAccentSoft));
        row.addView(tile, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(13), 0, dp(6), 0);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(15);
        name.setTextColor(cText);
        texts.addView(name);

        if (sub != null) {
            TextView subView = new TextView(this);
            subView.setText(sub);
            subView.setTextSize(11);
            subView.setTextColor(cMuted);
            subView.setPadding(0, dp(1), 0, 0);
            texts.addView(subView);
        }

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            TextView val = new TextView(this);
            val.setText(value);
            val.setTextSize(11.5f);
            val.setTextColor(cMuted);
            row.addView(val);
        }

        if (chevron) {
            TextView chev = new TextView(this);
            chev.setText("›");
            chev.setTextSize(17);
            chev.setTextColor(cMuted);
            chev.setPadding(dp(6), 0, dp(2), 0);
            row.addView(chev);
        }

        return row;
    }

    private interface OnToggle {
        void onChange(boolean checked);
    }

    /** সেটিংসের মতো আসল সুইচ, পাশে চালু/বন্ধ লেখা, নিচে ছোট করে লেখা কী করে। */
    private View toggleRow(String icon, String label, String sub, boolean on,
                           final OnToggle action) {
        final LinearLayout row = rowShell(icon, label, sub, null, false);
        row.setTag(ROW_TAG);

        final TextView state = new TextView(this);
        state.setText(Lang.onOff(on));
        state.setTextSize(10.5f);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setTextColor(on ? cAccent : cMuted);
        state.setPadding(0, 0, dp(8), 0);
        row.addView(state);

        final Switch sw = new Switch(this);
        sw.setTag("bb_switch");
        sw.setChecked(on);
        SwitchStyle.apply(sw, cAccent, cOnAccent, cSurface2, cMuted);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                state.setText(Lang.onOff(checked));
                state.setTextColor(checked ? cAccent : cMuted);
                action.onChange(checked);
            }
        });
        row.addView(sw);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sw.toggle(); }
        });
        return row;
    }

    /** থিমের তিনটি রকম এক লাইনে, কোনটা চালু আছে সেটা স্পষ্ট দেখা যায়। */
    private View themePicker() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(2), 0, dp(6));
        wrap.setTag(ROW_TAG);

        wrap.addView(rowShell("theme", Lang.t("থিম"), 
                Lang.t("ওয়েবপেজও কালো হয়ে যায়"), null, false));

        final String[] keys = {T_SYSTEM, T_LIGHT, T_DARK, T_FORCE};
        final String[] names = {Lang.t("সিস্টেম"), Lang.t("হালকা"),
                Lang.t("রাতের"), Lang.t("কালো")};
        String cur = themeKey();

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(cSurface2, 15));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));

        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            boolean sel = keys[i].equals(cur);
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(12.5f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), dp(9), dp(4), dp(9));
            chip.setTextColor(sel ? cOnAccent : cMuted);
            chip.setBackground(sel ? rounded(cAccent, 12) : null);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!key.equals(themeKey())) {
                        prefs.edit().putString("theme", key).apply();
                        recreate();
                    }
                }
            });
            seg.addView(chip, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(6);
        slp.rightMargin = dp(6);
        slp.topMargin = dp(2);
        wrap.addView(seg, slp);
        return wrap;
    }

    private View sheetHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(2), dp(4), dp(10));

        LinearLayout tile = new LinearLayout(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(rounded(cAccentSoft, 14));
        ImageView logo = new ImageView(this);
        try {
            logo.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        } catch (Exception ignored) { }
        tile.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        head.addView(tile, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(Lang.t("Skyble Browser"));
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(cText);
        texts.addView(name);

        TextView sub = new TextView(this);
        sub.setText(Lang.t("সংস্করণ") + " " + VERSION);
        sub.setTextSize(11);
        sub.setTextColor(cMuted);
        texts.addView(sub);

        head.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = glyphButton("✕", 15, cMuted);
        close.setPadding(dp(10), dp(8), dp(10), dp(8));
        rippleRound(close, 16);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeOverlay(); }
        });
        head.addView(close);
        return head;
    }

    // ---------------------------------------------------------------- downloads

    private JSONArray downloadRecords() {
        try {
            return new JSONArray(prefs.getString("downloads", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** হেল্পার অ্যাপ থেকেও ডাকা হয়, তাই Activity ছাড়াই কাজ করে */
    static void addDownloadRecord(Context ctx, String name, String uri, String mime, long size) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old;
            try {
                old = new JSONArray(p.getString("downloads", "[]"));
            } catch (Exception e) {
                old = new JSONArray();
            }
            JSONObject o = new JSONObject();
            o.put("n", name);
            o.put("u", uri);
            o.put("m", mime);
            o.put("s", size);
            o.put("t", System.currentTimeMillis());
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < old.length() && i < 99; i++) out.put(old.get(i));
            p.edit().putString("downloads", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    String refererFor(String url) {
        try {
            if (current != null && isRealPage(current.getUrl())) return current.getUrl();
        } catch (Exception ignored) { }
        return null;
    }

    private static class ActiveRow {
        Downloader.Task task;
        ProgressBar bar;
        TextView detail;
    }

    private final java.util.List<ActiveRow> activeRows = new java.util.ArrayList<>();
    private final android.os.Handler dlHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private LinearLayout dlHost;

    private void showDownloads() {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(cBg);

        LinearLayout panel = fullPanel(Lang.t("ডাউনলোড"), new View.OnClickListener() {
            public void onClick(View v) {
                dialog().setTitle(Lang.t("তালিকা মুছবেন?"))
                        .setMessage(Lang.t("শুধু তালিকা থেকে মুছে যাবে, ফোনে নামানো ফাইল থাকবে।"))
                        .setPositiveButton(Lang.t("মুছুন"), (d, w) -> {
                            prefs.edit().putString("downloads", "[]").apply();
                            closeOverlay();
                            showDownloads();
                        })
                        .setNegativeButton(Lang.t("না"), null).show();
            }
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(6), 0, dp(6), dp(20));
        scroll.addView(rows);

        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        box.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        openOverlay(box);

        renderDownloads(rows);
        startDownloadTicker(rows);
    }

    private void renderDownloads(LinearLayout host) {
        host.removeAllViews();
        activeRows.clear();

        java.util.List<Downloader.Task> active = Downloader.active();
        if (!active.isEmpty()) {
            host.addView(sectionLabel(Lang.t("চলছে (") + active.size() + ")"));
            for (Downloader.Task t : active) host.addView(activeRow(t));
        }

        JSONArray arr = downloadRecords();
        if (arr.length() > 0) {
            host.addView(sectionLabel(Lang.t("সম্পন্ন হয়েছে")));
            for (int i = 0; i < arr.length() && i < 100; i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    host.addView(recordRow(o.optString("n", Lang.t("ফাইল")),
                            o.optString("u", ""), o.optString("m", ""),
                            o.optLong("s", 0)));
                } catch (Exception ignored) { }
            }
        }

        if (active.isEmpty() && arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(Lang.t("এখনো কোনো ডাউনলোড নেই।\nকোনো পেজ থেকে ফাইল নামালে সেটা এখানে দেখা যাবে।"));
            empty.setTextSize(13);
            empty.setTextColor(cMuted);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(60), dp(16), dp(20));
            host.addView(empty);
        }
    }

    private View activeRow(final Downloader.Task task) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(task.name);
        name.setTextSize(14);
        name.setTextColor(cText);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = glyphButton("✕", 14, cMuted);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Downloader.cancel(task);
                toast(Lang.t("ডাউনলোড বাতিল হচ্ছে"));
            }
        });
        top.addView(cancel);
        box.addView(top);

        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setProgressTintList(ColorStateList.valueOf(cAccent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.withAlpha(cAccent, 0x33)));
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(9);
        box.addView(bar, blp);

        TextView detail = new TextView(this);
        detail.setTextSize(11.5f);
        detail.setTextColor(cMuted);
        detail.setPadding(0, dp(7), 0, 0);
        detail.setText(downloadLine(task));
        box.addView(detail);

        ActiveRow holder = new ActiveRow();
        holder.task = task;
        holder.bar = bar;
        holder.detail = detail;
        activeRows.add(holder);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(dp(6), dp(2), dp(6), dp(6));
        wrap.setBackground(roundedBorder(cSurface, 13));
        wrap.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setLayoutParams(wlp);
        return wrap;
    }

    private String downloadLine(Downloader.Task t) {
        String phase = t.phase == null || t.phase.length() == 0 ? "" : t.phase + " · ";
        if (t.total > 0) {
            long left = Math.max(0, t.total - t.done);
            return phase + Downloader.sizeText(t.done) + " / " + Downloader.sizeText(t.total)
                    + " · " + t.percent() + "% · " + Downloader.sizeText(t.speed)
                    + Lang.t("/s · বাকি ") + Downloader.sizeText(left);
        }
        return phase + Downloader.sizeText(t.done) + " · "
                + Downloader.sizeText(t.speed) + "/s";
    }

    private void startDownloadTicker(final LinearLayout host) {
        dlHost = host;
        dlHandler.removeCallbacksAndMessages(null);
        dlHandler.postDelayed(new Runnable() {
            public void run() {
                if (overlay == null || dlHost != host) return;
                java.util.List<Downloader.Task> active = Downloader.active();
                boolean same = active.size() == activeRows.size();
                if (same) {
                    for (int i = 0; i < active.size(); i++) {
                        if (activeRows.get(i).task != active.get(i)) {
                            same = false;
                            break;
                        }
                    }
                }
                if (!same) {
                    renderDownloads(host);
                } else {
                    for (ActiveRow row : activeRows) {
                        row.bar.setProgress((int) (row.task.percent() * 10));
                        row.detail.setText(downloadLine(row.task));
                    }
                }
                if (!Downloader.active().isEmpty()) {
                    dlHandler.postDelayed(this, 500);
                }
            }
        }, 400);
    }

    private View recordRow(final String name, final String uri, final String mime, long size) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        ripple(row);

        LinearLayout tile = new LinearLayout(this);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(rounded(cSurface, 11));
        TextView ico = new TextView(this);
        ico.setText(iconFor(mime, name));
        ico.setTextSize(18);
        ico.setGravity(Gravity.CENTER);
        tile.addView(ico, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(tile, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(14);
        nameView.setTextColor(cText);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(nameView);

        TextView sub = new TextView(this);
        sub.setText(Lang.t("সম্পন্ন হয়েছে") + (size > 0 ? " · " + Downloader.sizeText(size) : ""));
        sub.setTextSize(11);
        sub.setTextColor(cMuted);
        texts.addView(sub);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (uri.length() == 0) {
                    // হেল্পার অ্যাপের ফাইল: Downloads ফোল্ডারটা খুলে দেখানো হয়
                    try {
                        startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
                    } catch (Exception e) {
                        toast(Lang.t("ফাইলটা পাওয়া যায়নি"));
                    }
                    return;
                }
                // ভিডিও/অডিও হলে অ্যাপের নিজের প্লেয়ারে, নইলে অন্য অ্যাপে
                if ((mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")))
                        || looksLikeMedia(name) || looksLikeMedia(uri)) {
                    openPlayer(uri);
                    return;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    String type = (mime == null || mime.length() == 0) ? "*/*" : mime;
                    i.setDataAndType(Uri.parse(uri), type);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) {
                    toast(Lang.t("ফাইল খোলার মতো অ্যাপ নেই"));
                }
            }
        });
        return row;
    }

    // ---------------------------------------------------------------- history

    private String shortTitle(String title, String url) {
        String t = title == null ? "" : title.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        if (t.length() == 0 || t.startsWith("http://") || t.startsWith("https://")
                || t.startsWith("data:") || t.startsWith("file:") || t.equals("about:blank")) {
            String h = Favicon.hostOf(url);
            return h.length() == 0 ? prettyUrl(url) : h;
        }
        if (t.length() > 70) t = t.substring(0, 70).trim() + "…";
        return t;
    }

    private View entryRow(String title, final String url,
                          final View.OnClickListener onTap,
                          final View.OnLongClickListener onLong) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(9), dp(14), dp(9));
        ripple(row);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Favicon.bind(this, url, icon, cAccent, cOnAccent);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
        ilp.rightMargin = dp(14);
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(shortTitle(title, url));
        t.setTextSize(13.5f);
        t.setTextColor(cText);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);

        TextView u = new TextView(this);
        u.setText(prettyUrl(url));
        u.setTextSize(11);
        u.setTextColor(cMuted);
        u.setSingleLine(true);
        u.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        u.setPadding(0, dp(2), 0, 0);
        texts.addView(u);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (onTap != null) row.setOnClickListener(onTap);
        if (onLong != null) row.setOnLongClickListener(onLong);
        return row;
    }

    private LinearLayout fullPanel(String titleText, View.OnClickListener extra) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(cBg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(cText);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (extra != null) {
            TextView more = glyphButton("⋮", 18, cMuted);
            more.setPadding(dp(12), dp(6), dp(12), dp(6));
            more.setOnClickListener(extra);
            head.addView(more);
        }

        TextView close = glyphButton("✕", 16, cMuted);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeOverlay(); }
        });
        head.addView(close);
        panel.addView(head);
        return panel;
    }

    private void showHistory() {
        final List<String[]> items = loadList("history");

        LinearLayout panel = fullPanel(Lang.t("ইতিহাস (") + items.size() + ")", new View.OnClickListener() {
            public void onClick(View v) {
                dialog().setTitle(Lang.t("ইতিহাস মুছবেন?"))
                        .setPositiveButton(Lang.t("মুছুন"), (d, w) -> {
                            saveList("history", new ArrayList<>());
                            closeOverlay();
                            showHistory();
                        })
                        .setNegativeButton(Lang.t("না"), null).show();
            }
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, 0, 0, dp(20));

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(Lang.t("ইতিহাস খালি।\nকোনো সাইটে গেলে সেটা এখানে জমা হবে।"));
            empty.setTextSize(13);
            empty.setTextColor(cMuted);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(60), dp(16), dp(20));
            rows.addView(empty);
        }

        for (int i = 0; i < items.size(); i++) {
            final String title = items.get(i)[0];
            final String url = items.get(i)[1];
            rows.addView(entryRow(title, url, new View.OnClickListener() {
                public void onClick(View v) {
                    closeOverlay();
                    if (current != null) current.loadUrl(url);
                }
            }, new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    dialog().setTitle(shortTitle(title, url))
                            .setItems(new String[]{Lang.t("নতুন ট্যাবে খুলুন"), Lang.t("লিংক কপি"), Lang.t("ইতিহাস থেকে মুছুন")},
                                    (d, which) -> {
                                        if (which == 0) {
                                            newTab(url, true);
                                        } else if (which == 1) {
                                            android.content.ClipboardManager cb =
                                                    (android.content.ClipboardManager)
                                                            getSystemService(CLIPBOARD_SERVICE);
                                            cb.setPrimaryClip(android.content.ClipData
                                                    .newPlainText(Lang.t("লিংক"), url));
                                            toast(Lang.t("লিংক কপি হয়েছে"));
                                        } else {
                                            removeHistory(url);
                                            closeOverlay();
                                            showHistory();
                                        }
                                    })
                            .show();
                    return true;
                }
            }));
        }

        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(cBg);
        box.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        openOverlay(box);
    }

    private void removeHistory(String url) {
        List<String[]> h = loadList("history");
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i)[1].equals(url)) h.remove(i);
        }
        saveList("history", h);
    }

    // ---------------------------------------------------------------- favourite sites

    /** এই সংস্করণের ডিফল্ট ৮টি সাইট; পুরোনো তালিকা একবারেই এতে বদলে যায় */
    private static final int SITES_REV = 2;
    private static final String[] OLD_SITE_URLS = {
            "https://www.prothomalo.com", "https://www.kalerkantho.com",
            "https://bangla.bdnews24.com", "https://bn.wikipedia.org",
            "https://m.youtube.com", "https://m.facebook.com",
            "https://mail.google.com", "https://www.banglanews24.com"
    };

    private List<String[]> defaultSites() {
        List<String[]> s = new ArrayList<>();
        s.add(new String[]{"Google", "https://www.google.com"});
        s.add(new String[]{"Facebook", "https://m.facebook.com"});
        s.add(new String[]{"Instagram", "https://www.instagram.com"});
        s.add(new String[]{"Gmail", "https://mail.google.com"});
        s.add(new String[]{"GitHub", "https://github.com"});
        s.add(new String[]{"YouTube", "https://m.youtube.com"});
        s.add(new String[]{"Stack Overflow", "https://stackoverflow.com"});
        s.add(new String[]{"MDN", "https://developer.mozilla.org"});
        return s;
    }

    private static boolean isOldSite(String url) {
        for (String u : OLD_SITE_URLS) if (u.equals(url)) return true;
        return false;
    }

    private List<String[]> loadSites() {
        // আগের ভার্সনের ডিফল্ট তালিকা থাকলে নতুন ৮টি ডিফল্ট বসে,
        // কিন্তু ইউজার নিজে যোগ করা সাইটগুলো হারায় না
        if (prefs.getInt("sites_rev", 1) < SITES_REV) {
            List<String[]> old = loadList("sites");
            // নতুন ইনস্টলে ডিফল্ট আসবে; ইউজার সব মুছে ফেললে খালিই থাকবে
            List<String[]> merged = (prefs.contains("sites") && old.isEmpty())
                    ? new ArrayList<String[]>() : defaultSites();
            for (String[] row : old) {
                if (isOldSite(row[1])) continue;
                boolean dup = false;
                for (String[] have : merged) if (have[1].equals(row[1])) dup = true;
                if (!dup) merged.add(row);
            }
            saveList("sites", merged);
            prefs.edit().putInt("sites_rev", SITES_REV).apply();
            return merged;
        }
        // [] মানে ইউজার নিজে সব সরিয়েছে — তখন ডিফল্ট ফিরিয়ে আনা যাবে না
        return loadList("sites");
    }

    /** হোম পাতার রং অ্যাপের থিমের সাথে মেলে — ম্যাটেরিয়াল ইউ প্যালেটও */
    private void injectTheme(WebView view) {
        if (view == null) return;
        String js = "window.BBTheme&&window.BBTheme('" + hex(cBg) + "','" + hex(cSurface)
                + "','" + hex(cSurface2) + "','" + hex(cText) + "','" + hex(cMuted)
                + "','" + hex(cAccent) + "','" + hex(cOnAccent) + "','" + hex(cLine)
                + "'," + (darkMode ? "true" : "false") + ");";
        view.evaluateJavascript(js, null);
    }

    /** ফুটারের ভার্সনটা প্যাকেজ থেকেই পাঠায়, যাতে হোম পাতায় পুরোনো নম্বর না থাকে */
    private void injectVersion(WebView view) {
        if (view == null) return;
        view.evaluateJavascript("window.setVersion && window.setVersion('"
                + VERSION.replace("'", "") + "');", null);
    }

    private void injectSites(WebView view) {
        if (view == null) return;
        JSONArray arr = new JSONArray();
        for (String[] s : loadSites()) {
            try {
                JSONObject o = new JSONObject();
                o.put("n", Lang.t(s[0]));
                o.put("u", s[1]);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        view.evaluateJavascript("window.setSites && window.setSites(" + arr.toString() + ");", null);
    }

    /** এখনকার পেজটা হোমের গ্রিডে যোগ করে */
    private void addToHome() {
        if (current == null) return;
        String u = current.getUrl();
        Tab t = tabOf(current);
        if (!isRealPage(u)) {
            toast(Lang.t("এই পেজটা হোমে যোগ করা যায় না"));
            return;
        }
        List<String[]> sites = loadSites();
        for (String[] s : sites) {
            if (s[1].equals(u)) {
                toast(Lang.t("সাইটটা আগেই হোমে আছে"));
                return;
            }
        }
        String name = t != null && t.title != null && t.title.trim().length() > 0
                ? t.title.trim() : prettyUrl(u);
        if (name.length() > 24) name = name.substring(0, 24).trim() + "…";
        sites.add(new String[]{name, u});
        saveList("sites", sites);
        toast(Lang.t("হোমে যোগ হয়েছে"));
        refreshHomePages();
    }

    /** খোলা থাকা সব হোম পেজে নতুন তালিকা পাঠায় */
    private void refreshHomePages() {
        for (Tab t : tabs) {
            if (t.web != null && t.url != null && t.url.startsWith(HOME_URL)) injectSites(t.web);
        }
    }

    private void removeFromHome(String url) {
        if (url == null) return;
        List<String[]> sites = loadSites();
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i)[1].equals(url)) {
                sites.remove(i);
                saveList("sites", sites);
                toast(Lang.t("হোম থেকে সরানো হয়েছে"));
                refreshHomePages();
                return;
            }
        }
    }

    /** হোম পেজের JS থেকে ডাকা হয় — যোগ, এডিট, সরানো আর টাইল সাজানো */
    private class HomeBridge {
        private final WebView page;

        HomeBridge(WebView page) {
            this.page = page;
        }

        /** এই ব্রিজটা যে WebView-এর, সেটাই হোম পাতা কি না — অন্য ট্যাবের পেজ নয় */
        private boolean onHome() {
            String u = page == null ? null : page.getUrl();
            return u != null && u.startsWith(HOME_URL);
        }

        @JavascriptInterface
        public void add() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (!onHome()) return;
                    addSiteDialog(true);
                }
            });
        }

        @JavascriptInterface
        public void edit(final String url) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (!onHome()) return;
                    editSiteDialog(url);
                }
            });
        }

        @JavascriptInterface
        public void remove(final String url) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (!onHome()) return;
                    removeFromHome(url);
                }
            });
        }

        /** টানার পরে নতুন ক্রম — URL-এর তালিকা */
        @JavascriptInterface
        public void order(final String json) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (!onHome()) return;
                    saveSiteOrder(json);
                }
            });
        }
    }

    private void saveSiteOrder(String json) {
        List<String[]> have = loadSites();
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                for (int j = 0; j < have.size(); j++) {
                    if (have.get(j)[1].equals(u)) {
                        out.add(have.remove(j));
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }
        out.addAll(have);   // তালিকায় না আসা সারি শেষে থাকবে
        saveList("sites", out);
        refreshHomePages();
    }

    // ---------------------------------------------------------------- media

    private static final String DL_ASK = "ask";

    /**
     * ডাউনলোড শুরু করার আগে জিজ্ঞাসা: ব্রাউজারের নিজের ডাউনলোডার, নাকি ফোনে
     * ইনস্টল থাকা অন্য ডাউনলোড ম্যানেজার (ADM ইত্যাদি)। পছন্দ মনে রাখা যায়।
     */
    private void askDownload(final String url, final String ua, final String cd,
                             final String mime, final long len) {
        String saved = prefs.getString("dlmgr", DL_ASK);
        if ("builtin".equals(saved)) {
            Downloader.start(this, url, ua, cd, mime, len);
            return;
        }
        if (!DL_ASK.equals(saved) && saved.length() > 0) {
            if (DownloadApps.open(this, saved, url, mime)) return;
            // অ্যাপটা আর নেই — আবার জিজ্ঞাসা করব
            prefs.edit().putString("dlmgr", DL_ASK).apply();
        }
        List<DownloadApps.App> apps = DownloadApps.list(this, url, mime);
        if (apps.isEmpty()) {
            Downloader.start(this, url, ua, cd, mime, len);
            return;
        }
        showDownloadPicker(url, ua, cd, mime, len, apps);
    }

    private void showDownloadPicker(final String url, final String ua, final String cd,
                                    final String mime, final long len,
                                    final List<DownloadApps.App> apps) {
        String name = Downloader.fileName(url, cd, mime);
        String host = "";
        try {
            host = String.valueOf(Uri.parse(url).getHost());
        } catch (Exception ignored) { }
        if ("null".equals(host)) host = "";

        final String[] labels = new String[apps.size() + 1];
        labels[0] = Lang.t("Skyble ডাউনলোডার (ভেতরে)");
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i).label;

        final boolean[] remember = {false};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(2), dp(22), 0);

        TextView info = new TextView(this);
        String line = name + "\n";
        if (len > 0) line += Downloader.sizeText(len) + " · ";
        line += host;
        info.setText(line);
        info.setTextSize(12);
        info.setTextColor(cMuted);
        box.addView(info);

        // বাছাইটা ডায়ালগের ভেতরেই রেডিও তালিকা হিসেবে, আলাদা লিস্ট নয়
        final RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        final int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextSize(14);
            rb.setTextColor(cText);
            rb.setPadding(dp(4), dp(9), dp(4), dp(9));
            rb.setId(View.generateViewId());
            ids[i] = rb.getId();
            if (i == 0) rb.setChecked(true);
            group.addView(rb);
        }
        box.addView(group);

        Switch keep = new Switch(this);
        keep.setText(Lang.t("সবসময় এই পছন্দ"));
        keep.setTextSize(13);
        keep.setTextColor(cText);
        keep.setPadding(0, dp(12), 0, 0);
        keep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { remember[0] = on; }
        });
        box.addView(keep);

        dialog().setTitle(Lang.t("ডাউনলোড করবেন কী দিয়ে?"))
                .setView(box)
                .setPositiveButton(Lang.t("ডাউনলোড"), (d, w) -> {
                    int pick = 0;
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i] == group.getCheckedRadioButtonId()) pick = i;
                    }
                    if (remember[0]) {
                        prefs.edit().putString("dlmgr",
                                pick == 0 ? "builtin" : apps.get(pick - 1).pkg).apply();
                    }
                    if (pick == 0) {
                        Downloader.start(MainActivity.this, url, ua, cd, mime, len);
                    } else {
                        String pkg = apps.get(pick - 1).pkg;
                        if (!DownloadApps.open(MainActivity.this, pkg, url, mime)) {
                            toast(Lang.t("ওই অ্যাপে পাঠানো যায়নি, ব্রাউজারেই নামানো হচ্ছে"));
                            Downloader.start(MainActivity.this, url, ua, cd, mime, len);
                        }
                    }
                })
                .setNegativeButton(Lang.t("বাতিল"), null)
                .show();
    }

    /** পেজে থাকা ভিডিও/অডিও/ফাইল খুঁজে বের করে ডাউনলোড করতে দেয় */
    private void downloadMedia() {
        if (current == null) return;
        final String pageUrl = current.getUrl();
        if (Tube.videoId(pageUrl) != null) {
            youtubeDownload(pageUrl);
            return;
        }
        current.evaluateJavascript(MediaFinder.JS, new ValueCallback<String>() {
            public void onReceiveValue(String value) {
                // স্ট্রিম প্লেলিস্ট (m3u8/mpd) নামালে পুরো ভিডিও আসে না, তাই বাদ
                final List<String[]> found = new ArrayList<>();
                for (String[] m : MediaFinder.parse(value)) {
                    if (!"stream".equals(m[1])) found.add(m);
                }
                if (found.isEmpty()) {
                    toast(Lang.t("এই পেজে ডাউনলোড করার মতো ভিডিও বা অডিও নেই"));
                    return;
                }
                String[] rows = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    String url = found.get(i)[0];
                    String kind = found.get(i)[1];
                    String label = found.get(i)[2];
                    String size = found.get(i)[3];
                    String host = "";
                    try {
                        host = String.valueOf(Uri.parse(url).getHost());
                    } catch (Exception ignored) { }
                    String name = MediaFinder.titleFor(kind, label);
                    if (name.length() > 46) name = name.substring(0, 46) + "…";
                    rows[i] = name + " · " + host + (size.length() > 0 ? " · " + size : "");
                }
                dialog().setTitle(Lang.t("মিডিয়া ডাউনলোড"))
                        .setItems(rows, (d, which) -> {
                            if (current == null) return;
                            String url = found.get(which)[0];
                            String kind = found.get(which)[1];
                            askDownload(url,
                                    current.getSettings().getUserAgentString(),
                                    null, mediaMime(url, kind), 0);
                        })
                        .setNegativeButton(Lang.t("বাতিল"), null)
                        .show();
            }
        });
    }

    // ---------------------------------------------------------------- tube plugin

    /** নতুন Tube অ্যাপ আগে; পুরনো প্লাগিন প্যাকেজও এখনো কাজ করে */
    private static final String[] TUBE_PKGS = {
            "com.skyble.tube", "com.skyble.tube.plugin"};
    private static final String[] TUBE_ACTIONS = {
            "com.skyble.tube.DOWNLOAD", "com.skyble.tube.plugin.DOWNLOAD"};
    /** Tube এখন আলাদা অ্যাপ, নিজের রিপো থেকেই নামে */
    private static final String PLUGIN_APK_URL =
            "https://github.com/Mamun015/skyble-tube/releases/latest/download/SkybleTube.apk";

    /** ইনস্টল থাকা Tube অ্যাপের ইনডেক্স, কিছু না থাকলে -1 */
    private int tubeApp() {
        for (int i = 0; i < TUBE_PKGS.length; i++) {
            try {
                getPackageManager().getPackageInfo(TUBE_PKGS[i], 0);
                return i;
            } catch (Exception ignored) { }
        }
        return -1;
    }

    private boolean tubePluginInstalled() {
        return tubeApp() >= 0;
    }

    /** ইউটিউব পেজে ডাউনলোড চাপলে: ব্রাউজারের ভেতরেই পপআপ, অ্যাপ বদলাতে হয় না */
    private void youtubeDownload(final String url) {
        if (url == null) return;
        final String id = Tube.videoId(url);
        if (id == null) return;
        toast(Lang.t("ভিডিওর লিংক খোঁজা হচ্ছে…"));
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> found = Tube.resolve(id);
                runOnUiThread(new Runnable() {
                    public void run() { showTubeResult(found, url); }
                });
            }
        }).start();
    }

    /** পপআপের তালিকা: কোয়ালিটি, সাইজ, আর হেল্পার অ্যাপের সুযোগ */
    private void showTubeResult(final List<String[]> found, final String pageUrl) {
        String[] rows = new String[found.size()];
        for (int i = 0; i < found.size(); i++) {
            rows[i] = found.get(i)[1] + (found.get(i)[2].length() > 0 ? " · " + found.get(i)[2] : "");
        }
        final String fileName = tubeFileName();
        AlertDialog.Builder b = dialog();
        if (found.isEmpty()) {
            String why = Tube.friendlyError();
            b.setTitle(Lang.t("ভিডিও ডাউনলোড"))
                    .setMessage(Lang.t("এই ভিডিওটা এখন নামানো যাচ্ছে না। " +
                            "ভিডিওটা সরানো হলে বা সার্ভার ব্লক করলে এমন হয়।")
                            + (why.length() == 0 ? "" : "\n\n" + why));
            if (tubePluginInstalled()) {
                b.setPositiveButton(Lang.t("Tube অ্যাপে খোলো"), (d, w) -> launchTubePlugin(pageUrl));
            } else {
                b.setPositiveButton(Lang.t("হেল্পার অ্যাপ"), (d, w) -> downloadTubePlugin());
            }
            b.setNeutralButton(Lang.t("লিংক শেয়ার"), (d, w) -> shareLink(pageUrl));
        } else {
            b.setTitle(Lang.t("ভিডিও ডাউনলোড"))
                    .setItems(rows, (d, w) -> {
                        // বেছে নেওয়া কোয়ালিটি না নামলে Muxer নিজেই পরেরটায় নামে
                        Muxer.start(MainActivity.this, Tube.videoId(pageUrl),
                                found, w, fileName);
                    })
                    .setNeutralButton(tubePluginInstalled()
                            ? Lang.t("Tube অ্যাপে খোলো") : Lang.t("হেল্পার অ্যাপ"),
                            (d, w) -> {
                                if (tubePluginInstalled()) {
                                    launchTubePlugin(pageUrl);
                                } else {
                                    downloadTubePlugin();
                                }
                            });
        }
        b.setNegativeButton(Lang.t("বাতিল"), null).show();
    }

    /** "8.9 MB" টেক্সট থেকে বাইট, শুধু অগ্রগতির হিসাবের জন্য */
    private long parseSize(String text) {
        if (text == null) return 0;
        try {
            String s = text.trim().toUpperCase(java.util.Locale.US);
            double mul = 1;
            if (s.endsWith("KB")) { mul = 1024; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("MB")) { mul = 1048576; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("GB")) { mul = 1073741824; s = s.substring(0, s.length() - 2); }
            else if (s.endsWith("B")) { s = s.substring(0, s.length() - 1); }
            return (long) (Double.parseDouble(s.trim()) * mul);
        } catch (Exception e) {
            return 0;
        }
    }

    private String tubeFileName() {
        Tab t = active();
        String title = t != null && t.title != null && t.title.trim().length() > 0
                ? t.title.trim() : "video";
        title = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (title.length() > 60) title = title.substring(0, 60);
        return title + ".mp4";
    }

    private void shareLink(String url) {
        if (url == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(i, Lang.t("শেয়ার করুন")));
        } catch (Exception e) {
            toast(Lang.t("শেয়ার করা যায়নি"));
        }
    }

    private String currentUrl() {
        return current == null ? null : current.getUrl();
    }

    /** হেল্পার অ্যাপের APK নামায়; ইনস্টল করতে ইউজারকে একবার ট্যাপ করতে হয় */
    private void downloadTubePlugin() {
        if (current == null) return;
        Downloader.start(this, PLUGIN_APK_URL, current.getSettings().getUserAgentString(),
                null, "application/vnd.android.package-archive", 0);
        toast(Lang.t("ডাউনলোড শেষ হলে ফাইলটা খুলে ইনস্টল করো"));
    }

    private void launchTubePlugin(String url) {
        if (url == null) return;
        int which = tubeApp();
        if (which < 0) which = 0;
        Intent i = new Intent(TUBE_ACTIONS[which]);
        i.setPackage(TUBE_PKGS[which]);
        i.putExtra("url", url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(Lang.t("Tube অ্যাপ খোলা যায়নি"));
        }
    }

    /** ইউটিউব পেজে টুলবারের ছোট বোতাম, চাপলে ডাউনলোডের পথ */
    private void updateTubePill(String url) {
        if (tubePill == null) return;
        boolean site = Tube.isYouTube(url);
        boolean video = site && Tube.videoId(url) != null;
        tubePill.setVisibility(site ? View.VISIBLE : View.GONE);
        if (site) tubePill.setText(video ? Lang.t("⬇ ভিডিও") : Lang.t("⬇ ডাউনলোড"));
    }

    private String mediaMime(String url, String kind) {
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        int cut = u.indexOf('?');
        if (cut > 0) u = u.substring(0, cut);
        if (u.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (u.endsWith(".mpd")) return "application/dash+xml";
        if (u.endsWith(".mp4") || u.endsWith(".m4v")) return "video/mp4";
        if (u.endsWith(".webm")) return "video/webm";
        if (u.endsWith(".mkv")) return "video/x-matroska";
        if (u.endsWith(".mp3")) return "audio/mpeg";
        if (u.endsWith(".m4a")) return "audio/mp4";
        if (u.endsWith(".aac")) return "audio/aac";
        if (u.endsWith(".ogg")) return "audio/ogg";
        if (u.endsWith(".wav")) return "audio/wav";
        if (u.endsWith(".pdf")) return "application/pdf";
        if (u.endsWith(".zip")) return "application/zip";
        if ("audio".equals(kind)) return "audio/mpeg";
        if ("file".equals(kind)) return "application/octet-stream";
        return "video/mp4";
    }

    private void showSiteEditor() {
        final List<String[]> sites = loadSites();

        LinearLayout panel = fullPanel(Lang.t("প্রিয় সাইট"), new View.OnClickListener() {
            public void onClick(View v) {
                dialog().setTitle(Lang.t("ডিফল্ট তালিকায় ফিরবেন?"))
                        .setPositiveButton(Lang.t("ফিরিয়ে আনো"), (d, w) -> {
                            saveList("sites", defaultSites());
                            closeOverlay();
                            showSiteEditor();
                            if (current != null) injectSites(current);
                        })
                        .setNegativeButton(Lang.t("না"), null).show();
            }
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < sites.size(); i++) {
            final int idx = i;
            final String name = sites.get(i)[0];
            final String url = sites.get(i)[1];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(7), dp(8), dp(7));

            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Favicon.bind(this, url, icon, cAccent, cOnAccent);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
            ilp.rightMargin = dp(12);
            row.addView(icon, ilp);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView t = new TextView(this);
            t.setText(Lang.t(name));
            t.setTextSize(14);
            t.setTextColor(cText);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(t);

            TextView u = new TextView(this);
            u.setText(prettyUrl(url));
            u.setTextSize(11);
            u.setTextColor(cMuted);
            u.setSingleLine(true);
            u.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            texts.addView(u);

            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(smallButton(i == 0 ? "" : "▲", new Runnable() {
                public void run() { moveSite(idx, -1); }
            }));
            row.addView(smallButton(i == sites.size() - 1 ? "" : "▼", new Runnable() {
                public void run() { moveSite(idx, 1); }
            }));
            row.addView(smallButton("✕", new Runnable() {
                public void run() {
                    List<String[]> now = loadSites();
                    if (idx < now.size()) now.remove(idx);
                    saveList("sites", now);
                    closeOverlay();
                    showSiteEditor();
                    if (current != null) injectSites(current);
                }
            }));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closeOverlay();
                    if (current != null) current.loadUrl(url);
                }
            });
            rows.addView(row);
        }

        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(16), dp(10), dp(16), dp(22));
        add.setBackground(rounded(cSurface, 16));
        TextView plus = new TextView(this);
        plus.setText(Lang.t("+ নতুন সাইট যোগ করুন"));
        plus.setTextSize(14);
        plus.setTypeface(Typeface.DEFAULT_BOLD);
        plus.setTextColor(cAccent);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(0, dp(12), 0, dp(12));
        add.addView(plus);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addSiteDialog(false); }
        });
        panel.addView(add);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(cBg);
        box.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        openOverlay(box);
    }

    private TextView smallButton(String glyph, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(13);
        t.setTextColor(glyph.length() == 0 ? cBg : cMuted);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        ripple(t);
        if (glyph.length() > 0) {
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { action.run(); }
            });
        }
        return t;
    }

    private void moveSite(int index, int dir) {
        List<String[]> sites = loadSites();
        int target = index + dir;
        if (index < 0 || index >= sites.size() || target < 0 || target >= sites.size()) return;
        String[] a = sites.get(index);
        sites.set(index, sites.get(target));
        sites.set(target, a);
        saveList("sites", sites);
        closeOverlay();
        showSiteEditor();
        if (current != null) injectSites(current);
    }

    private void addSiteDialog(final boolean fromHome) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        final EditText name = new EditText(this);
        name.setHint(Lang.t("নাম (যেমন প্রথম আলো)"));
        name.setSingleLine(true);
        box.addView(name);

        final EditText url = new EditText(this);
        url.setHint(Lang.t("ঠিকানা (prothomalo.com)"));
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        dialog().setTitle(Lang.t("নতুন সাইট"))
                .setView(box)
                .setPositiveButton(Lang.t("যোগ করুন"), (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (u.length() == 0) {
                        toast(Lang.t("সাইটের ঠিকানা লিখো"));
                        return;
                    }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "https://" + u;
                    }
                    if (n.length() == 0) n = Favicon.hostOf(u);
                    List<String[]> sites = loadSites();
                    for (String[] have : sites) {
                        if (have[1].equals(u)) {
                            toast(Lang.t("সাইটটা আগেই হোমে আছে"));
                            return;
                        }
                    }
                    sites.add(new String[]{n, u});
                    saveList("sites", sites);
                    if (fromHome) {
                        toast(Lang.t("হোমে যোগ হয়েছে"));
                        refreshHomePages();
                    } else {
                        closeOverlay();
                        showSiteEditor();
                        if (current != null) injectSites(current);
                    }
                })
                .setNegativeButton(Lang.t("বাতিল"), null)
                .show();
    }

    /** হোমের টাইলের নাম/ঠিকানা বদলানোর ডায়ালগ */
    private void editSiteDialog(final String url) {
        final List<String[]> sites = loadSites();
        int index = -1;
        for (int i = 0; i < sites.size(); i++) if (sites.get(i)[1].equals(url)) index = i;
        if (index < 0) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), 0);

        final EditText name = new EditText(this);
        name.setText(sites.get(index)[0]);
        name.setHint(Lang.t("নাম (যেমন প্রথম আলো)"));
        name.setSingleLine(true);
        box.addView(name);

        final EditText addr = new EditText(this);
        addr.setText(sites.get(index)[1]);
        addr.setHint(Lang.t("ঠিকানা (prothomalo.com)"));
        addr.setSingleLine(true);
        addr.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(addr);

        dialog().setTitle(Lang.t("সাইট এডিট"))
                .setView(box)
                .setPositiveButton(Lang.t("সেভ"), (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = addr.getText().toString().trim();
                    if (u.length() == 0) {
                        toast(Lang.t("সাইটের ঠিকানা লিখো"));
                        return;
                    }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "https://" + u;
                    }
                    if (n.length() == 0) n = Favicon.hostOf(u);
                    List<String[]> now = loadSites();
                    int at = -1;
                    for (int i = 0; i < now.size(); i++) {
                        if (now.get(i)[1].equals(u) && !u.equals(url)) {
                            toast(Lang.t("সাইটটা আগেই হোমে আছে"));
                            return;
                        }
                        if (now.get(i)[1].equals(url)) at = i;
                    }
                    if (at < 0) return;   // এর মধ্যে সরিয়ে ফেলা হয়েছে
                    now.set(at, new String[]{n, u});
                    saveList("sites", now);
                    toast(Lang.t("সেভ হয়েছে"));
                    refreshHomePages();
                })
                .setNegativeButton(Lang.t("বাতিল"), null)
                .show();
    }

    private String iconFor(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.US);
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (m.startsWith("image/") || n.endsWith(".jpg") || n.endsWith(".png")
                || n.endsWith(".webp")) return "🖼️";
        if (m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".mkv")) return "🎬";
        if (m.startsWith("audio/") || n.endsWith(".mp3") || n.endsWith(".m4a")) return "🎵";
        if (m.contains("pdf") || n.endsWith(".pdf")) return "📕";
        if (m.contains("zip") || m.contains("compressed") || n.endsWith(".zip")
                || n.endsWith(".apk")) return "🗜️";
        return "📄";
    }

    // ---------------------------------------------------------------- default browser

    private boolean isDefaultBrowser() {
        return BrowserRole.isDefault(this);
    }

    private void askDefaultBrowser() {
        if (isDefaultBrowser()) {
            toast(Lang.t("Skyble Browser এখনই ডিফল্ট ব্রাউজার"));
            return;
        }
        if (!BrowserRole.request(this, REQ_ROLE)) {
            toast(Lang.t("এই ফোনে সেটিংস খোলা যায়নি"));
        }
    }

    // ---------------------------------------------------------------- page tools

    private void copyLink() {
        if (current == null) return;
        String u = current.getUrl();
        if (!isRealPage(u)) {
            toast(Lang.t("এই পেজের লিংক নেই"));
            return;
        }
        try {
            android.content.ClipboardManager cb =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText(Lang.t("লিংক"), u));
            toast(Lang.t("লিংক কপি হয়েছে"));
        } catch (Exception e) {
            toast(Lang.t("কপি করা যায়নি"));
        }
    }

    private void findInPage() {
        if (current == null) return;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setHint(Lang.t("পেজে লেখা খুঁজুন"));
        input.setTextColor(cText);
        input.setHintTextColor(cMuted);
        input.setPadding(dp(20), dp(10), dp(20), dp(10));

        dialog().setTitle(Lang.t("পেজে খুঁজুন"))
                .setView(input)
                .setPositiveButton(Lang.t("খুঁজুন"), (d, w) -> {
                    String q = input.getText().toString();
                    if (q.length() > 0) current.findAllAsync(q);
                })
                .setNegativeButton(Lang.t("বন্ধ"), (d, w) -> {
                    if (current != null) current.clearMatches();
                })
                .show();
    }

    private void printPage() {
        if (current == null) return;
        try {
            android.print.PrintManager pm =
                    (android.print.PrintManager) getSystemService(PRINT_SERVICE);
            String name = pageTitle.getText().toString();
            if (name.length() == 0) name = Lang.t("Skyble Browser");
            pm.print(name, current.createPrintDocumentAdapter(name), null);
        } catch (Exception e) {
            toast(Lang.t("এই ফোনে প্রিন্ট চালু নেই"));
        }
    }

    private void closeAllTabs() {
        for (Tab t : new ArrayList<>(tabs)) {
            try {
                if (t.web != null) {
                    holder.removeView(t.web);
                    t.web.stopLoading();
                    t.web.destroy();
                }
                if (t.thumb != null) {
                    t.thumb.recycle();
                    t.thumb = null;
                }
            } catch (Exception ignored) { }
        }
        tabs.clear();
        activeIndex = 0;
        current = null;
        newTab(homeUrl(), true);
        toast(Lang.t("সব ট্যাব বন্ধ হয়েছে"));
    }

    // ---------------------------------------------------------------- long press menu

    /** লিংক/ছবি/ভিডিওতে চেপে ধরলে নিজের মেনু — কপি, শেয়ার, নতুন ট্যাব, প্লেয়ার, ডাউনলোড */
    private boolean pageLongPress(final WebView view, float x, float y) {
        WebView.HitTestResult r = view.getHitTestResult();
        final String hit = r == null ? null : r.getExtra();
        int type = r == null ? 0 : r.getType();
        boolean linkish = type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                || type == WebView.HitTestResult.ANCHOR_TYPE
                || type == WebView.HitTestResult.IMAGE_TYPE
                || (hit != null && hit.startsWith("http"));
        if (!linkish) return false;   // সাধারণ লেখা: আগের মতোই টেক্সট সিলেকশন

        float scale = view.getScale();
        if (scale <= 0) scale = 1f;
        String js = "(function(){try{"
                + "var el=document.elementFromPoint(" + (x / scale) + "," + (y / scale) + ");"
                + "if(!el)return '{\"h\":\"\",\"t\":\"\",\"v\":\"\"}';"
                + "var a=el.closest?el.closest('a'):null;"
                + "var m=el.closest?el.closest('video,audio'):null;"
                + "return JSON.stringify({h:a?(a.href||''):'',"
                + "t:a?String(a.innerText||a.title||'').replace(/\\s+/g,' ').trim().slice(0,120):'',"
                + "v:m?(m.currentSrc||m.src||''):''});"
                + "}catch(e){return '{\"h\":\"\",\"t\":\"\",\"v\":\"\"}'}})()";
        view.evaluateJavascript(js, new ValueCallback<String>() {
            public void onReceiveValue(String value) {
                String href = "", text = "", vid = "";
                try {
                    String s = value;
                    if (s != null && s.length() > 1 && s.charAt(0) == '"') {
                        s = String.valueOf(new org.json.JSONTokener(s).nextValue());
                    }
                    JSONObject o = new JSONObject(s);
                    href = o.optString("h", "");
                    text = o.optString("t", "");
                    vid = o.optString("v", "");
                } catch (Exception ignored) { }
                if (href.length() == 0 && hit != null && hit.startsWith("http")) href = hit;
                if (vid.length() == 0 && hit != null && looksLikeMedia(hit)) vid = hit;
                if (href.length() == 0 && vid.length() == 0) return;
                showLinkSheet(href, text, vid);
            }
        });
        return true;
    }

    private void showLinkSheet(final String link, final String text, final String video) {
        hideSuggestions();
        final FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0x52000000);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeOverlay(); }
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cSurface);
        float r = dp(26);
        bg.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
        sheet.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) sheet.setElevation(dp(20));
        sheet.setPadding(dp(10), dp(6), dp(10), dp(14));
        sheet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { }
        });

        View grip = new View(this);
        grip.setBackground(rounded(Theme.withAlpha(cMuted, 0x59), 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(38), dp(4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(8);
        glp.bottomMargin = dp(6);
        sheet.addView(grip, glp);

        TextView head = new TextView(this);
        head.setText(link != null && link.length() > 0 ? link : video);
        head.setTextSize(12);
        head.setTextColor(cMuted);
        head.setMaxLines(2);
        head.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        head.setPadding(dp(14), dp(2), dp(14), dp(8));
        sheet.addView(head);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        if (video != null && video.length() > 0) {
            list.addView(menuRow("play", Lang.t("প্লেয়ারে চালান"), null, new Runnable() {
                public void run() { openPlayer(video); }
            }));
        }
        if (link != null && link.length() > 0) {
            list.addView(menuRow("link", Lang.t("লিংক কপি"), null, new Runnable() {
                public void run() {
                    copyText(Lang.t("লিংক"), link, Lang.t("লিংক কপি হয়েছে"));
                }
            }));
        }
        if (text != null && text.trim().length() > 0) {
            final String t = text.trim();
            list.addView(menuRow("code", Lang.t("লিংকের লেখা কপি"), null, new Runnable() {
                public void run() {
                    copyText(Lang.t("লিংকের লেখা"), t, Lang.t("লিংকের লেখা কপি হয়েছে"));
                }
            }));
        }
        if (link != null && link.length() > 0) {
            list.addView(menuRow("share", Lang.t("শেয়ার"), null, new Runnable() {
                public void run() { shareUrl(link); }
            }));
            list.addView(menuRow("tab", Lang.t("নতুন ট্যাবে খুলুন"), null, new Runnable() {
                public void run() { newTab(link, true); }
            }));
            if (looksLikeMedia(link) || (video != null && video.length() > 0)) {
                list.addView(menuRow("download", Lang.t("ডাউনলোড"), null, new Runnable() {
                    public void run() {
                        Downloader.start(MainActivity.this, link,
                                uaMobile == null ? "" : uaMobile, null,
                                mediaMime(link, "video"), 0);
                    }
                }));
            }
            if (link.startsWith("http")) {
                list.addView(menuRow("home", Lang.t("হোমে যোগ করুন"), null, new Runnable() {
                    public void run() { addSiteToHome(link, text); }
                }));
            }
        }

        ScrollView scroll = new ScrollView(this);
        addDividers(list);
        scroll.addView(list);
        sheet.addView(scroll);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM;
        slp.setMargins(dp(8), dp(8), dp(8), dp(8));
        box.addView(sheet, slp);
        openOverlay(box);
        sheet.setTranslationY(dp(48));
        sheet.setAlpha(0f);
        sheet.animate().translationY(0).alpha(1f).setDuration(190).start();
    }

    private void copyText(String label, String text, String done) {
        try {
            android.content.ClipboardManager cb =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            toast(done);
        } catch (Exception e) {
            toast(Lang.t("কপি করা যায়নি"));
        }
    }

    private void shareUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, url);
            startActivity(Intent.createChooser(i, Lang.t("শেয়ার করুন")));
        } catch (Exception e) {
            toast(Lang.t("শেয়ার করা যায়নি"));
        }
    }

    private void addSiteToHome(String url, String label) {
        if (url == null || !url.startsWith("http")) return;
        List<String[]> sites = loadSites();
        for (String[] s : sites) {
            if (s[1].equals(url)) {
                toast(Lang.t("সাইটটা আগেই হোমে আছে"));
                return;
            }
        }
        String name = label == null ? "" : label.trim();
        if (name.length() == 0) name = prettyUrl(url);
        if (name.length() > 24) name = name.substring(0, 24).trim() + "…";
        sites.add(new String[]{name, url});
        saveList("sites", sites);
        toast(Lang.t("হোমে যোগ হয়েছে"));
        refreshHomePages();
    }

    // ---------------------------------------------------------------- fullscreen video

    private void exitFullscreen() {
        if (fullView == null) return;
        try {
            screen.removeView(fullView);
        } catch (Exception ignored) { }
        fullView = null;
        hideSystemBars(false);
        WebChromeClient.CustomViewCallback cb = fullCallback;
        fullCallback = null;
        if (cb != null) cb.onCustomViewHidden();
    }

    private void hideSystemBars(boolean hide) {
        try {
            Window w = getWindow();
            View d = w.getDecorView();
            if (hide) {
                w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                d.setSystemUiVisibility(darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Exception ignored) { }
    }


    // ---------------------------------------------------------------- overlays

    private void openOverlay(View v) {
        hideSuggestions();
        closeOverlay();
        overlay = v;
        screen.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeOverlay() {
        dlHandler.removeCallbacksAndMessages(null);
        dlHost = null;
        if (overlay != null) {
            screen.removeView(overlay);
            overlay = null;
        }
    }

    // ---------------------------------------------------------------- webview

    private WebView buildWebView() {
        return buildWebView(false);
    }

    private WebView buildWebView(boolean incognito) {
        WebView web = new WebView(this);
        web.setBackgroundColor(cBg);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(prefs.getBoolean("js", true));
        web.addJavascriptInterface(new TranslateBridge(), "BBTranslate");
        web.addJavascriptInterface(new HomeBridge(web), "BBHome");
        web.addJavascriptInterface(new PlayerBridge(), "BBPlayer");
        s.setDomStorageEnabled(!incognito);
        s.setDatabaseEnabled(!incognito);
        // প্রাইভেট ট্যাব: কিছুই ফোনে জমা হয় না
        if (incognito) s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setLoadsImagesAutomatically(prefs.getBoolean("images", true));
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(prefs.getInt("textzoom", 100));
        uaMobile = s.getUserAgentString().replace("; wv", "");
        s.setUserAgentString(desktopMode ? UA_DESKTOP : uaMobile);

        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                   android.webkit.WebResourceRequest request) {
                return handleUrl(view, request.getUrl().toString());
            }

            /** পেজের ভেতরের বিজ্ঞাপন/ট্র্যাকার রিকোয়েস্ট আটকায়, মূল পেজ নয় */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                if (request == null) return null;
                if (!request.isForMainFrame()) {
                    Uri m = request.getUrl();
                    if (m != null) noteMedia(view, m.toString());
                }
                if (request.isForMainFrame()) return null;
                Uri u = request.getUrl();
                if (u == null) return null;
                String scheme = u.getScheme();
                if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return null;
                if (!adBlockOn() || !AdBlock.isBlocked(u.getHost())) return null;
                try {
                    return new android.webkit.WebResourceResponse("text/plain", "utf-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap fav) {
                Tab t = tabOf(view);
                if (t != null) {
                    t.url = url;
                    t.title = "";       // পুরোনো পেজের নাম নতুন ঠিকানায় জমা হবে না
                    t.loading = true;
                }
                if (view == current) {
                    progress.setVisibility(View.VISIBLE);
                    setReloadState(true);
                }
                if (url != null && url.startsWith(HOME_URL)) {
                    urlBar.setText("");
                    pageTitle.setText("");
                } else if (view == current && isRealPage(url)) {
                    urlBar.setText(prettyUrl(url));
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Tab t = tabOf(view);
                if (t != null) {
                    t.url = url;
                    t.loading = false;
                    if (t.title.length() == 0) t.title = prettyUrl(url);
                }
                if (view == current) {
                    progress.setVisibility(View.GONE);
                    setReloadState(false);
                    updateNavButtons();
                }
                if (t != null && !isAdUrl(url)) t.lastGood = url;
                if (isRealPage(url) && !isAdUrl(url) && (t == null || !t.incognito)) {
                    addHistory(t == null ? null : t.title, url);
                }
                if (view == current) updateTubePill(url);
                if (url != null && url.startsWith(HOME_URL)) {
                    injectTheme(view);
                    injectVersion(view);
                    injectSites(view);
                    injectRecent(view);
                }
                if (isRealPage(url)) {
                    // প্রাইভেট ট্যাবে নিজে থেকে অনুবাদ নয় — পেজের লেখা বাইরে যায় না
                    injectTranslate(view, autoTranslate && (t == null || !t.incognito));
                    injectPlayer(view);
                    if (darkMode) injectDark(view);
                }
                // লোড শেষে ট্যাব-কার্ডের ছবিটা নতুন করে তুলি, যাতে ঝাপসা/খালি না থাকে
                if (t != null && view == current) {
                    final Tab shot = t;
                    view.postDelayed(new Runnable() {
                        public void run() { captureThumb(shot); }
                    }, 400);
                }
            }

            @Override
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                if (Build.VERSION.SDK_INT >= 23) return;
                if (!isRealPage(failingUrl) || view != current) return;
                progress.setVisibility(View.GONE);
                setReloadState(false);
                if (recoverFromAd(view, failingUrl)) return;
                showErrorPage(view, failingUrl, code, desc);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                String failingUrl = request.getUrl() == null ? null : request.getUrl().toString();
                if (!isRealPage(failingUrl) || view != current) return;
                progress.setVisibility(View.GONE);
                setReloadState(false);
                if (recoverFromAd(view, failingUrl)) return;
                if (tryPlainHttp(view, failingUrl)) return;
                int code = error == null ? 0 : error.getErrorCode();
                String desc = error == null || error.getDescription() == null
                        ? "" : error.getDescription().toString();
                showErrorPage(view, failingUrl, code, desc);
            }

            @Override
            public void onReceivedSslError(WebView view, final android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                progress.setVisibility(View.GONE);
                setReloadState(false);
                String errUrl = error == null ? null : error.getUrl();
                String pageUrl = view.getUrl();
                final String errHost = errUrl == null ? "" : String.valueOf(Uri.parse(errUrl).getHost());
                String pageHost = pageUrl == null ? "" : String.valueOf(Uri.parse(pageUrl).getHost());
                // একই সাইটে আগে "তবুও খুলুন" দেওয়া হলে আর জিজ্ঞাসা নয়
                if (sslAllowedHosts.contains(errHost)) {
                    handler.proceed();
                    return;
                }
                // শুধু মূল পেজের সার্টিফিকেট সমস্যাতেই জিজ্ঞাসা, বিজ্ঞাপন/ছবির জন্য নয়
                if (view != current || !errHost.equalsIgnoreCase(pageHost)
                        || errHost.equals(sslDialogHost)) {
                    handler.cancel();
                    return;
                }
                sslDialogHost = errHost;
                dialog()
                        .setTitle(Lang.t("সার্টিফিকেট সমস্যা"))
                        .setMessage(Lang.t("এই সাইটের নিরাপত্তা সার্টিফিকেট ঠিক নেই। "
                                + "তবুও খুললে তথ্য ঝুঁকিতে থাকতে পারে।"))
                        .setPositiveButton(Lang.t("তবুও খুলুন"), (d, w) -> {
                            sslAllowedHosts.add(errHost);
                            sslDialogHost = null;
                            handler.proceed();
                        })
                        .setNegativeButton(Lang.t("বাতিল"), (d, w) -> {
                            sslDialogHost = null;
                            handler.cancel();
                        })
                        .setOnCancelListener(d -> {
                            sslDialogHost = null;
                            handler.cancel();
                        })
                        .show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view != current) return;
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                if (newProgress >= 100) setReloadState(false);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                Tab t = tabOf(view);
                if (t != null) t.title = title == null ? "" : title;
                if (view == current) pageTitle.setText(t == null ? "" : t.title);
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final android.webkit.JsResult result) {
                dialog()
                        .setMessage(message)
                        .setPositiveButton(Lang.t("ঠিক আছে"), (d, w) -> result.confirm())
                        .setNegativeButton(Lang.t("বাতিল"), (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, Lang.t("ফাইল বাছুন")), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                // window.open() (বিজ্ঞাপনের ফাঁদ) বর্তমান ট্যাব দখল করতে পারবে না
                if (!isUserGesture) return false;
                final WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openPopup(url);
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v,
                                                            android.webkit.WebResourceRequest req) {
                        openPopup(req.getUrl() == null ? null : req.getUrl().toString());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullView = view;
                fullCallback = callback;
                screen.addView(fullView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                closeOverlay();
                hideSystemBars(true);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                askDownload(url, ua, cd, mime, len);
            }
        });

        // লিংকে চেপে ধরলে নিজের মেনু: কপি, শেয়ার, নতুন ট্যাব, প্লেয়ার, ডাউনলোড
        final float[] press = new float[2];
        web.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    press[0] = e.getX();
                    press[1] = e.getY();
                }
                return false;
            }
        });
        web.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                return pageLongPress(web, press[0], press[1]);
            }
        });

        return web;
    }

    /** পপআপের ঠিকানা: বিজ্ঞাপন বাদ দিয়ে নতুন ট্যাবে */
    private void openPopup(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        String host = Uri.parse(url).getHost();
        host = host == null ? "" : host.toLowerCase(java.util.Locale.US);
        if (host.contains("googleadservices") || host.contains("googlesyndication")
                || host.contains("doubleclick")) return;
        newTab(url, true);
    }

    private void applySettings() {
        String engine = prefs.getString("engine", "google");
        int zoom = prefs.getInt("textzoom", 100);
        boolean js = prefs.getBoolean("js", true);
        boolean images = prefs.getBoolean("images", true);
        for (Tab t : tabs) {
            if (t.web == null) continue;
            WebSettings s = t.web.getSettings();
            s.setJavaScriptEnabled(js);
            s.setLoadsImagesAutomatically(images);
            s.setTextZoom(zoom);
            s.setUserAgentString(desktopMode ? UA_DESKTOP : uaMobile);
        }
        this.engine = engine;
    }

    private String engine = "google";

    private String searchUrl(String q) {
        String e = engine;
        if ("ddg".equals(e)) return "https://duckduckgo.com/?q=" + enc(q);
        if ("bing".equals(e)) return "https://www.bing.com/search?q=" + enc(q);
        return "https://www.google.com/search?q=" + enc(q);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ROLE) {
            toast(isDefaultBrowser() ? Lang.t("ডিফল্ট ব্রাউজার সেট হয়েছে") : Lang.t("ডিফল্ট ব্রাউজার সেট হয়নি"));
            return;
        }
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- navigation

    private boolean isRealPage(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private String prettyUrl(String url) {
        if (url == null) return "";
        String u = url;
        if (u.startsWith("https://")) u = u.substring(8);
        else if (u.startsWith("http://")) u = u.substring(7);
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String homeUrl() {
        // রংগুলো হোম পাতার URL-এ দেওয়া হয়, তাই পাতাটা প্রথম আঁকা থেকেই ঠিক রঙে বসে
        String hash = "lang=" + (Lang.isEn() ? "en" : "bn");
        if (darkMode) hash = hash + "&dark";
        return HOME_URL + "#" + hash
                + "&bg=" + hex6(cBg) + "&sf=" + hex6(cSurface) + "&sf2=" + hex6(cSurface2)
                + "&tx=" + hex6(cText) + "&mu=" + hex6(cMuted) + "&ac=" + hex6(cAccent)
                + "&oa=" + hex6(cOnAccent) + "&ln=" + hex6(cLine);
    }

    private boolean adBlockOn() {
        return prefs == null || prefs.getBoolean("adblock", true);
    }

    private static final String[] AD_HOSTS = {
            "googleadservices", "googlesyndication", "doubleclick", "adservice.google",
            "pagead2", "adnxs", "taboola", "outbrain", "popads", "propellerads",
            "onclickads", "adsterra", "revcontent", "zedo", "adcash", "trafficjunky"
    };

    /** বিজ্ঞাপন/ট্র্যাকিং ঠিকানা — এসব পেজ হিসেবে খোলা হয় না */
    private boolean isAdUrl(String url) {
        if (url == null || url.length() == 0) return false;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.US);
        if (adBlockOn() && AdBlock.isBlocked(host)) return true;
        for (String bad : AD_HOSTS) {
            if (host.contains(bad)) return true;
        }
        return false;
    }

    private boolean handleUrl(WebView view, String url) {
        if (url == null) return true;
        if (isAdUrl(url)) return true;   // বিজ্ঞাপন যেন পেজ দখল না করে
        if (url.startsWith("skyble://")) {
            internal(view, url);
            return true;
        }
        if ((url.startsWith("http://") || url.startsWith("https://"))
                && prefs.getBoolean("newtab", true)) {
            // হোম পাতা/ফাঁকা ট্যাব থেকে খুললে ওই ট্যাবেই, নইলে নতুন ট্যাবে
            if (!isRealPage(view == null ? null : view.getUrl())) return false;
            newTab(url, true);
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
        return true;
    }

    private void internal(WebView view, String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost();
        if (host.equals("search")) {
            String q = u.getQueryParameter("q");
            if (q != null && q.trim().length() > 0) {
                String text = q.trim();
                boolean site = looksLikeAddress(text);
                String target;
                if (site) {
                    target = (text.startsWith("http://") || text.startsWith("https://"))
                            ? text : "https://" + text;
                } else {
                    target = searchUrl(text);
                }
                view.loadUrl(target);
                addHistory(text, target);
            }
        } else if (host.equals("reload")) {
            view.reload();
        } else if (host.equals("open")) {
            String s = u.getQueryParameter("u");
            if (s != null) {
                String target = s;
                if (!target.startsWith("http")) target = "https://" + target;
                view.loadUrl(target);
            }
        }
    }

    /** বিজ্ঞাপনের ঠিকানায় আটকে গেলে আগের ভালো পেজে ফিরে আসে */
    private boolean recoverFromAd(WebView view, String failingUrl) {
        if (!isAdUrl(failingUrl)) return false;
        Tab t = tabOf(view);
        String back = (t != null && t.lastGood != null && t.lastGood.length() > 0)
                ? t.lastGood : homeUrl();
        view.loadUrl(back);
        return true;
    }

    /**
     * সাইট যদি শুধু http-তে চলে (সার্টিফিকেট নেই বা https বন্ধ), একবার সাদা http দিয়ে
     * আবার চেষ্টা করে। একই ঠিকানায় দুবার চেষ্টা করে না, তাই লুপ হয় না।
     */
    private boolean tryPlainHttp(WebView view, String url) {
        if (url == null || !url.startsWith("https://") || view != current) return false;
        String plain = "http://" + url.substring(8);
        if (!httpTried.add(plain)) return false;
        if (httpTried.size() > 100) httpTried.clear();
        progress.setVisibility(View.VISIBLE);
        view.loadUrl(plain);
        return true;
    }

    /** লেখাটা ঠিকানা না সার্চের কথা — হোম পাতার বাক্স থেকেও সাইট খোলার জন্য */
    private boolean looksLikeAddress(String text) {
        if (text == null) return false;
        String x = text.trim();
        if (x.length() == 0 || x.indexOf(' ') >= 0) return false;
        if (x.startsWith("http://") || x.startsWith("https://")) return true;
        return x.matches("[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+\\.[a-zA-Z]{2,}(/.*)?");
    }

    private void go(String input) {
        hideSuggestions();
        String t = input == null ? "" : input.trim();
        if (t.length() == 0 || current == null) return;
        hideKeyboard();
        if (t.startsWith("skyble://")) {
            internal(current, t);
            return;
        }
        if (t.contains(" ") || !t.contains(".")) {
            String search = searchUrl(t);
            current.loadUrl(search);
            addHistory(t, search);
            return;
        }
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://" + t;
        current.loadUrl(t);
    }

    private String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** লোড চলার সময় ✕ (থামানোর জন্য), শেষ হলে ↻ (রিফ্রেশ) */
    private void setReloadState(boolean loading) {
        pageLoading = loading;
        if (reloadIcon == null) return;
        int id = getResources().getIdentifier(loading ? "ic_close" : "ic_refresh",
                "drawable", getPackageName());
        if (id != 0) reloadIcon.setImageResource(id);
        reloadIcon.animate().cancel();
        reloadIcon.setRotation(0f);
    }

    private void hideSuggestions() {
        if (suggestBox == null) return;
        suggestSeq++;
        suggestTapping = false;
        cancelSuggestFetch();
        suggestQuery = "";
        suggestRemoteQuery = "";
        suggestRemote = new ArrayList<String>();
        suggestBox.removeAllViews();
        if (suggestScroll != null) suggestScroll.setVisibility(View.GONE);
        suggestBox.setVisibility(View.GONE);
    }

    /** তালিকা দেখায়, ঠিকানা বারের নিচে ভেসে; উচ্চতা ধরে স্ক্রিনে সীমিত রাখে */
    private void showSuggestPanel() {
        if (suggestScroll == null || suggestLp == null) return;
        int w = screen != null ? screen.getWidth() : 0;
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        int maxW = Math.max(dp(120), w - dp(24));
        // আসল কনটেন্ট মেপে উচ্চতা নেওয়া হয়, তাই নিচে ফাঁকা জায়গা থাকে না
        suggestBox.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int want = suggestBox.getMeasuredHeight() + dp(2);
        int top = (topBarView != null ? topBarView.getBottom() : dp(56)) + dp(2);
        int room = screen != null ? screen.getHeight() : 0;
        if (room <= 0) room = getResources().getDisplayMetrics().heightPixels;
        int h = Math.min(want, Math.min(dp(320), Math.max(dp(120), room - top - dp(10))));
        suggestLp.topMargin = top;
        suggestLp.height = h;
        suggestScroll.setLayoutParams(suggestLp);
        suggestScroll.scrollTo(0, 0);
        suggestScroll.setVisibility(View.VISIBLE);
        suggestBox.setVisibility(View.VISIBLE);
        suggestScroll.bringToFront();
    }

    /** লেখার সময় নিচে সাজেশন: ঠিকানা, সার্চ, গুগলের সাজেশন, আর আগে দেখা সাইট */
    private void showSuggestions(String text) {
        if (suggestBox == null) return;
        String q = text == null ? "" : text.trim();
        suggestQuery = q;
        // লেখা বদলালে আগের শব্দের সাজেশন আর দেখাব না
        if (!q.equalsIgnoreCase(suggestRemoteQuery)) suggestRemote = new ArrayList<String>();

        if (q.length() == 0) {
            suggestSeq++;               // আগের রিকোয়েস্টের ফল বাতিল
            cancelSuggestFetch();
            suggestRemote = new ArrayList<String>();
            renderSuggestions();
            return;
        }

        renderSuggestions();
        scheduleFetch(q);
    }

    /** লেখা থামলে ২২০ মিলিসেকেন্ড পরে গুগলের কাছে সাজেশন চাই */
    private void scheduleFetch(final String q) {
        cancelSuggestFetch();
        final int seq = ++suggestSeq;
        suggestPending = new Runnable() {
            public void run() {
                suggestPending = null;
                fetchSuggestions(q, seq);
            }
        };
        suggestHandler.postDelayed(suggestPending, 220);
    }

    private void cancelSuggestFetch() {
        if (suggestPending != null) {
            suggestHandler.removeCallbacks(suggestPending);
            suggestPending = null;
        }
    }

    /** গুগলের suggest API — শুধু লেখা, কোনো ব্যক্তিগত তথ্য পাঠায় না */
    private void fetchSuggestions(final String q, final int seq) {
        new Thread(new Runnable() {
            public void run() {
                final List<String> out = new ArrayList<String>();
                java.net.HttpURLConnection c = null;
                try {
                    String url = "https://suggestqueries.google.com/complete/search?client=firefox&q="
                            + enc(q);
                    c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("User-Agent",
                            uaMobile == null ? "Mozilla/5.0" : uaMobile);
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        if (bo.size() > 200000) break;
                    }
                    in.close();
                    JSONArray arr = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
                    if (arr.length() > 1) {
                        JSONArray list = arr.optJSONArray(1);
                        if (list != null) {
                            for (int i = 0; i < list.length() && i < 8; i++) {
                                String term = list.optString(i, "");
                                if (term.length() > 0) out.add(term);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (seq != suggestSeq) return;
                        if (!q.equalsIgnoreCase(suggestQuery)) return;
                        suggestRemote = out;
                        suggestRemoteQuery = q;
                        renderSuggestions();
                    }
                });
            }
        }).start();
    }

    /** তালিকাটা নতুন করে সাজায়; ইন্টারনেটের ফল আসলে আবার ডাকা হয় */
    private void renderSuggestions() {
        if (suggestBox == null) return;
        suggestBox.removeAllViews();
        String q = suggestQuery == null ? "" : suggestQuery;

        if (q.length() == 0) {
            // খালি বারে চাপ দিলে সাম্প্রতিক সাইট
            int recent = 0;
            for (String[] item : loadList("history")) {
                if (recent >= 5) break;
                if (item == null || item.length < 2 || item[1] == null) continue;
                String title = item[0] == null ? "" : item[0];
                addSuggestRow(recent == 0 ? "history" : "history",
                        title.length() == 0 ? prettyUrl(item[1]) : title, item[1], item[1]);
                recent++;
            }
            if (recent == 0) {
                suggestScroll.setVisibility(View.GONE);
                suggestBox.setVisibility(View.GONE);
                return;
            }
            trimLastDivider();
            showSuggestPanel();
            return;
        }

        int rows = 0;
        if (looksLikeAddress(q)) {
            addSuggestRow("public", q, null, q);
            rows++;
        }
        addSuggestRow("search", Lang.t("সার্চ") + ": " + q, null, q);
        rows++;

        // গুগলের আসল সাজেশন আগে
        int googleRows = 0;
        for (String term : suggestRemote) {
            if (rows >= 9 || googleRows >= 6) break;
            if (term == null || term.length() == 0) continue;
            if (term.equalsIgnoreCase(q)) continue;
            addSuggestRow("search", term, null, term);
            rows++;
            googleRows++;
        }

        // তার নিচে আগে দেখা সাইট
        for (String[] item : loadList("history")) {
            if (rows >= 9) break;
            if (item == null || item.length < 2 || item[1] == null) continue;
            String title = item[0] == null ? "" : item[0];
            String url = item[1];
            if (url.equals(q)) continue;
            if (!contains(title, q) && !contains(url, q) && !contains(prettyUrl(url), q)) continue;
            addSuggestRow("history", title.length() == 0 ? prettyUrl(url) : title, url, url);
            rows++;
        }

        // বুকমার্ক
        for (String[] item : loadList("bookmarks")) {
            if (rows >= 9) break;
            if (item == null || item.length < 2 || item[1] == null) continue;
            String title = item[0] == null ? "" : item[0];
            String url = item[1];
            if (url.equals(q)) continue;
            if (!contains(title, q) && !contains(url, q) && !contains(prettyUrl(url), q)) continue;
            addSuggestRow("star", title.length() == 0 ? prettyUrl(url) : title, url, url);
            rows++;
        }

        if (rows == 0) {
            suggestScroll.setVisibility(View.GONE);
            suggestBox.setVisibility(View.GONE);
            return;
        }
        trimLastDivider();
        showSuggestPanel();
    }

    /** শেষ সারির নিচে বাড়তি রেখা থাকবে না */
    private void trimLastDivider() {
        int n = suggestBox.getChildCount();
        if (n > 0) suggestBox.removeViewAt(n - 1);
    }

    private boolean contains(String hay, String needle) {
        return hay != null && needle != null
                && hay.toLowerCase(java.util.Locale.US).contains(needle.toLowerCase(java.util.Locale.US));
    }

    private void addSuggestRow(String iconName, String title, String sub, final String target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(9), dp(16), dp(9));

        row.addView(icon(iconName, 18, cMuted), new LinearLayout.LayoutParams(dp(18), dp(18)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(13), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(14);
        name.setTextColor(cText);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);

        if (sub != null) {
            TextView url = new TextView(this);
            url.setText(prettyUrl(sub));
            url.setTextSize(11);
            url.setTextColor(cMuted);
            url.setSingleLine(true);
            url.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(url);
        }

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // আঙুল সারিতে রাখা পর্যন্ত তালিকা লুকাবে না
        row.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    suggestTapping = true;
                } else if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
                    suggestHandler.post(new Runnable() {
                        public void run() { suggestTapping = false; }
                    });
                }
                return false;
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideSuggestions();
                hideKeyboard();
                urlBar.clearFocus();
                go(target);
            }
        });
        suggestBox.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(cLine);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.leftMargin = dp(46);
        suggestBox.addView(line, lineLp);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
            urlBar.clearFocus();
        } catch (Exception ignored) { }
    }

    private void showErrorPage(WebView view, String url) {
        showErrorPage(view, url, 0, "");
    }

    private void showErrorPage(WebView view, String url, int code, String desc) {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;background:" + hex(cBg)
                + ";color:" + hex(cText) + ";padding:40px 24px;text-align:center}"
                + "h2{font-size:18px;margin:0 0 8px}p{font-size:13px;color:" + hex(cMuted)
                + ";word-break:break-all}"
                + "a{display:inline-block;margin-top:20px;background:" + hex(cAccent)
                + ";color:" + hex(cOnAccent)
                + ";text-decoration:none;padding:12px 22px;border-radius:16px;font-size:14px}</style>"
                + Lang.t("<body><div style='font-size:40px'>📡</div><h2>পেজ লোড হয়নি</h2>")
                + Lang.t("<p>ইন্টারনেট সংযোগ দেখুন, তারপর আবার চেষ্টা করুন।</p>")
                + "<p style='font-size:11px'>" + url + "</p>"
                + (code == 0 ? "" : "<p style='font-size:11px;color:" + hex(cMuted) + "'>"
                        + Lang.t("ত্রুটি") + " " + code + (desc.length() == 0 ? "" : " · " + desc) + "</p>")
                + Lang.t("<a href='skyble://reload'>আবার চেষ্টা করুন</a></body></html>");
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ---------------------------------------------------------------- bookmarks / history

    private List<String[]> loadList(String key) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{o.getString("t"), o.getString("u")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void saveList(String key, List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (String[] row : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", row[0]);
                o.put("u", row[1]);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString(key, arr.toString()).apply();
    }

    private boolean isBookmarked(String url) {
        if (url == null) return false;
        for (String[] row : loadList("bookmarks")) if (row[1].equals(url)) return true;
        return false;
    }

    private void addHistory(String title, String url) {
        List<String[]> h = loadList("history");
        for (int i = h.size() - 1; i >= 0; i--) if (h.get(i)[1].equals(url)) h.remove(i);
        h.add(0, new String[]{shortTitle(title, url), url});
        while (h.size() > 200) h.remove(h.size() - 1);
        saveList("history", h);
    }

    private void toggleBookmark() {
        if (current == null) return;
        String url = current.getUrl();
        if (url == null || url.startsWith("file://") || url.startsWith("data:")) {
            toast(Lang.t("এই পেজ বুকমার্ক করা যায় না"));
            return;
        }
        List<String[]> b = loadList("bookmarks");
        for (int i = 0; i < b.size(); i++) {
            if (b.get(i)[1].equals(url)) {
                b.remove(i);
                saveList("bookmarks", b);
                updateNavButtons();
                toast(Lang.t("বুকমার্ক সরানো হয়েছে"));
                return;
            }
        }
        String t = pageTitle.getText().toString();
        if (t.length() == 0) t = prettyUrl(url);
        b.add(0, new String[]{t, url});
        saveList("bookmarks", b);
        updateNavButtons();
        toast(Lang.t("বুকমার্কে রাখা হয়েছে"));
    }

    private void injectRecent(WebView view) {
        if (view == null) return;
        JSONArray arr = new JSONArray();
        List<String[]> h = loadList("history");
        for (int i = 0; i < h.size() && i < 4; i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", h.get(i)[0]);
                o.put("u", h.get(i)[1]);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        view.evaluateJavascript("window.setRecent && window.setRecent(" + arr.toString() + ");", null);
    }

    // ---------------------------------------------------------------- dialogs

    private AlertDialog.Builder dialog() {
        // ওয়ালপেপারের রঙ বন্ধ থাকলে ডায়ালগেও পুরোনো ব্র্যান্ড রং
        boolean dyn = Theme.enabled(this);
        int style;
        if (dyn) {
            style = darkMode ? R.style.SkybleDialogDynamicDark : R.style.SkybleDialogDynamic;
        } else {
            style = darkMode ? R.style.SkybleDialogDark : R.style.SkybleDialog;
        }
        return new AlertDialog.Builder(this, style);
    }

    private void showList(String title, String key, final boolean clearable) {
        final List<String[]> items = loadList(key);
        if (items.isEmpty()) {
            toast(Lang.t("এখনো কিছু নেই"));
            return;
        }
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) names[i] = items.get(i)[0];
        AlertDialog.Builder b = dialog().setTitle(title)
                .setItems(names, (d, which) -> {
                    if (current != null) current.loadUrl(items.get(which)[1]);
                });
        if (clearable) {
            b.setNegativeButton(Lang.t("সব মুছুন"), (d, w) -> {
                saveList(key, new ArrayList<>());
                toast(Lang.t("মুছে ফেলা হয়েছে"));
            });
        }
        b.setPositiveButton(Lang.t("বন্ধ"), null).show();
    }

    private String asset(String name) {
        String cached = assetCache.get(name);
        if (cached != null) return cached;
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            String js = new String(out.toByteArray(), "UTF-8");
            assetCache.put(name, js);
            return js;
        } catch (Exception e) {
            return null;
        }
    }

    /** অনুবাদের ভাষা আর অটো/অফার মোড পেজে পাঠায় */
    private void injectTranslate(WebView view, boolean auto) {
        if (!prefs.getBoolean("js", true)) return;
        String js = asset("translate.js");
        if (js == null) return;
        view.evaluateJavascript(trConfig(auto) + js, null);
    }

    private String trConfig(boolean auto) {
        return "window.__bbTrCfg={tl:" + JSONObject.quote(transLang)
                + ",name:" + JSONObject.quote(Langs.name(transLang, Lang.isEn()))
                + ",auto:" + (auto ? "1" : "0") + "};";
    }

    /** মেনু থেকে ডাকলে এই পেজটা এখনই অনুবাদ হয় */
    private void translateNow() {
        if (current == null || !isRealPage(current.getUrl())) {
            toast(Lang.t("আগে কোনো পেজ খুলুন"));
            return;
        }
        if (!prefs.getBoolean("js", true)) {
            toast(Lang.t("অনুবাদ করতে জাভাস্ক্রিপ্ট চালু করো"));
            return;
        }
        String js = asset("translate.js");
        if (js == null) return;
        // আগের অনুবাদ থাকলে আগে মূল লেখা ফিরিয়ে আনি, নইলে নতুন ভাষায় হয় না
        current.evaluateJavascript("if(window.__bbOriginal)window.__bbOriginal();", null);
        current.evaluateJavascript(trConfig(false) + js
                + ";if(window.__bbTranslateNow)window.__bbTranslateNow();", null);
        toast(Lang.t("অনুবাদ হচ্ছে: ") + Langs.name(transLang, Lang.isEn()));
    }

    private void showOriginal() {
        if (current == null) return;
        current.evaluateJavascript("if(window.__bbOriginal)window.__bbOriginal();", null);
        toast(Lang.t("মূল পেজ ফিরে এসেছে"));
    }

    /** ভাষা বেছে নিলে সেভ হয়, চাইলে সেই ভাষাতেই পেজ অনুবাদ হয় */
    private void pickTransLang(final boolean translateAfter) {
        Langs.pick(this, dialog(), Lang.t("অনুবাদের ভাষা"), Lang.isEn(), transLang,
                new Langs.Pick() {
                    public void onPick(String code) {
                        transLang = code;
                        prefs.edit().putString("translang", code).apply();
                        if (translateAfter) translateNow();
                    }
                });
    }

    private void showTranslateDialog() {
        final String nm = Langs.name(transLang, Lang.isEn());
        String[] items = {
                Lang.t("এই পেজ অনুবাদ করুন") + " (" + nm + ")",
                Lang.t("অন্য ভাষায় অনুবাদ…"),
                Lang.t("মূল পেজ দেখাও")
        };
        dialog().setTitle(Lang.t("অনুবাদ"))
                .setItems(items, (d, which) -> {
                    if (which == 0) translateNow();
                    else if (which == 1) pickTransLang(true);
                    else showOriginal();
                })
                .show();
    }

    /** ইউটিউবের নিজের ডার্ক/লাইট থিম ঠিক হয় তার PREF কুকি থেকে।
        রাতের মোডে কুকিতে ডার্ক বসিয়ে দিই, তাই ইউটিউব নিজের আসল কালো থিমে আসে।
        জোর করে কালোতে বরং লাইট করে দিই, কারণ পুরো পেজ আমরা উল্টে দিই। */
    private void syncYouTubeTheme() {
        try {
            String f6 = null;
            if (T_LIGHT.equals(themeKey())) f6 = "8"; // ইউটিউব লাইট
            else if (darkMode && !forceDark) f6 = "400"; // ইউটিউব ডার্ক
            else if (forceDark) f6 = "8";             // ইউটিউব লাইট, তারপর আমরা উল্টাব
            if (f6 == null) return;

            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String cur = cm.getCookie("https://www.youtube.com");
            String val = "f6=" + f6;
            if (cur != null && cur.contains("PREF=")) {
                String pref = null;
                for (String p : cur.split(";\\s*")) {
                    if (p.startsWith("PREF=")) pref = p.substring(5);
                }
                if (pref != null && pref.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    boolean has = false;
                    for (String kv : pref.split("&")) {
                        if (kv.length() == 0) continue;
                        if (kv.startsWith("f6=")) { kv = "f6=" + f6; has = true; }
                        if (sb.length() > 0) sb.append('&');
                        sb.append(kv);
                    }
                    if (!has) { if (sb.length() > 0) sb.append('&'); sb.append("f6=").append(f6); }
                    val = sb.toString();
                }
            }
            String cookie = "PREF=" + val + "; Domain=.youtube.com; Path=/; Secure";
            cm.setCookie("https://www.youtube.com", cookie);
            cm.setCookie("https://m.youtube.com", cookie);
            cm.flush();
        } catch (Exception e) { /* কুকি না বসালেও বাকি কাজ চলবে */ }
    }

    /** পেজের ভিডিও অ্যাপের নিজের প্লেয়ারে খোলার স্ক্রিপ্ট */
    private void injectPlayer(WebView view) {
        if (!prefs.getBoolean("js", true)) return;
        String js = asset("player.js");
        if (js == null) return;
        view.evaluateJavascript(js, null);
    }

    private void injectDark(WebView view) {        if (!prefs.getBoolean("js", true)) return;
        // "পেজও কালো" বন্ধ থাকলে সাদা পেজ জোর করে কালো করা হবে না
        if (!forceDark && !prefs.getBoolean("darkpages", true)) return;
        String js = asset("dark.js");
        if (js == null) return;
        // জোর করে কালো: সাইটের নিজের ডার্ক থিম বাদ দিয়ে সব পেজ উল্টে দেওয়া হবে
        view.evaluateJavascript(
                "window.__bbForceDark=" + (forceDark ? "1" : "0") + ";" + js, null);
    }

    /** পেজের ভিডিও অ্যাপের নিজের প্লেয়ারে খোলে। প্লেয়ারে সোয়াইপে উজ্জ্বলতা/ভলিউম। */
    private class PlayerBridge {
        @JavascriptInterface
        public boolean open(String url, String title) {
            if (url == null) return false;
            String u = url;
            if (u.startsWith("blob:") || u.startsWith("mediasource:")) {
                // blob: এর আসল ঠিকানা লুকানো; নেটওয়ার্ক স্নিফার যা ধরেছে সেটাই ব্যবহার করি
                String best = sniffedBest(current);
                if (best == null) return false;
                u = best;
            }
            if (!u.startsWith("https://") && !u.startsWith("http://")) return false;
            final String target = u;
            final String name = title;
            runOnUiThread(new Runnable() {
                public void run() { openPlayer(target, name); }
            });
            return true;
        }
    }

    /** ভিডিও অ্যাপের প্লেয়ারে খোলে; পেজের কুকি/রেফারার/UA সাথে যায় */
    private void openPlayer(String url) {
        openPlayer(url, null);
    }

    private void openPlayer(String url, String title) {
        if (url == null) return;
        boolean remote = url.startsWith("http://") || url.startsWith("https://");
        boolean local = url.startsWith("content://") || url.startsWith("file://");
        if (!remote && !local) return;
        String ck = "";
        String ref = "";
        try {
            if (remote) {
                ck = CookieManager.getInstance().getCookie(url);
                ref = current != null && current.getUrl() != null ? current.getUrl() : url;
            }
        } catch (Exception ignored) { }
        String name = title;
        if (name == null || name.trim().length() == 0) {
            Tab t = current == null ? null : tabOf(current);
            name = t == null ? "" : t.title;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", url);
        i.putExtra("title", name == null ? "" : name);
        i.putExtra("cookie", ck == null ? "" : ck);
        i.putExtra("referer", ref);
        i.putExtra("agent", uaMobile == null ? "" : uaMobile);
        if (local) i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /** পেজের ভেতরে যে মিডিয়া রিকোয়েস্ট হয় মনে রাখে; blob: ভিডিওর আসল ঠিকানা এখানেই ধরা পড়ে */
    private void noteMedia(WebView view, String url) {
        if (view == null || url == null) return;
        String u = url;
        int h = u.indexOf('#');
        if (h > 0) u = u.substring(0, h);
        String low = u.toLowerCase(java.util.Locale.US);
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        boolean ok = false;
        for (String e : MEDIA_EXT) {
            if (path.endsWith(e)) {
                ok = true;
                break;
            }
        }
        if (!ok) return;
        synchronized (sniffed) {
            java.util.LinkedHashMap<String, Long> map = sniffed.get(view);
            if (map == null) {
                map = new java.util.LinkedHashMap<>();
                sniffed.put(view, map);
            }
            map.remove(u);
            map.put(u, System.currentTimeMillis());
            while (map.size() > 40) {
                java.util.Iterator<String> it = map.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
    }

    /** ওই ট্যাবে ধরা পড়া মিডিয়া, নতুন আগে */
    private java.util.List<String> sniffedList(WebView view) {
        java.util.List<String> out = new ArrayList<>();
        if (view == null) return out;
        synchronized (sniffed) {
            java.util.LinkedHashMap<String, Long> map = sniffed.get(view);
            if (map == null) return out;
            java.util.ArrayList<String> keys = new java.util.ArrayList<>(map.keySet());
            for (int i = keys.size() - 1; i >= 0; i--) out.add(keys.get(i));
        }
        return out;
    }

    /** blob: ভিডিওর পেছনে যে স্ট্রিম চলছে সেটাই আগে; নাহলে নতুনতম মিডিয়া */
    private String sniffedBest(WebView view) {
        java.util.List<String> all = sniffedList(view);
        for (String u : all) {
            String p = u.toLowerCase(java.util.Locale.US);
            int q = p.indexOf('?');
            if (q > 0) p = p.substring(0, q);
            if (p.endsWith(".m3u8") || p.endsWith(".mpd")) return u;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /** মিডিয়া ফাইল বা স্ট্রিমের লিংক কি না */
    private static boolean looksLikeMedia(String u) {
        if (u == null) return false;
        String p = u.toLowerCase(java.util.Locale.US);
        int q = p.indexOf('?');
        if (q > 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h > 0) p = p.substring(0, h);
        String[] exts = {".mp4", ".m4v", ".webm", ".mkv", ".mov", ".3gp", ".ts",
                ".m3u8", ".mpd", ".mp3", ".m4a", ".aac", ".ogg", ".wav"};
        for (String e : exts) {
            if (p.endsWith(e)) return true;
        }
        return false;
    }

    /** পেজের ভিডিও/স্ট্রিম বেছে অ্যাপের প্লেয়ারে চালায় */
    private void playInPlayer() {
        if (current == null) return;
        final WebView page = current;
        current.evaluateJavascript(MediaFinder.JS, new ValueCallback<String>() {
            public void onReceiveValue(String value) {
                final List<String[]> vids = new ArrayList<>();
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                // নেটওয়ার্কে ধরা পড়া মিডিয়া আগে (blob: ভিডিওর আসল স্ট্রিম এখানেই)
                for (String u : sniffedList(page)) {
                    if (seen.add(u)) vids.add(new String[]{u, "video", ""});
                }
                for (String[] m : MediaFinder.parse(value)) {
                    if (!"video".equals(m[1]) && !"stream".equals(m[1])) continue;
                    if (seen.add(m[0])) vids.add(m);
                }
                if (vids.isEmpty()) {
                    toast(Lang.t("এই পেজে ভিডিও পাওয়া যায়নি"));
                    return;
                }
                if (vids.size() == 1) {
                    openPlayer(vids.get(0)[0]);
                    return;
                }
                String[] rows = new String[vids.size()];
                for (int i = 0; i < vids.size(); i++) {
                    String url = vids.get(i)[0];
                    String host = "";
                    try {
                        host = String.valueOf(Uri.parse(url).getHost());
                    } catch (Exception ignored) { }
                    String name = MediaFinder.titleFor(vids.get(i)[1], vids.get(i)[2]);
                    if (name.length() > 46) name = name.substring(0, 46) + "…";
                    rows[i] = name + " · " + host;
                }
                dialog().setTitle(Lang.t("ভিডিও বাছুন"))
                        .setItems(rows, (d, which) -> openPlayer(vids.get(which)[0]))
                        .setNegativeButton(Lang.t("বাতিল"), null)
                        .show();
            }
        });
    }

    /** সাইটের CSP-তে আটকে গেলে অনুবাদ এই ব্রিজ দিয়ে আনা হয়। */
    private static class TranslateBridge {
        @JavascriptInterface
        public String get(String url) {
            if (url == null || !url.startsWith("https://translate.googleapis.com/")) return "";
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
                if (c.getResponseCode() != 200) return "";
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return new String(out.toByteArray(), "UTF-8");
            } catch (Exception e) {
                return "";
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    /** ইন্টারনেট, সার্টিফিকেট আর WebView-এর অবস্থা এক পাতায় দেখায় */
    // ---------------------------------------------------------------- আপডেট

    /** manual হলে চেক শুরুর সময়ই জানায়; ব্যাকগ্রাউন্ড চেক চুপচাপ চলে। */
    private void checkUpdates(final boolean manual) {
        updateCheckedAt = System.currentTimeMillis();
        if (manual) toast(Lang.t("আপডেট খোঁজা হচ্ছে…"));
        Updater.check(this, Updater.currentVersion(this), (version, apkUrl, failed) -> {
            if (failed) {
                if (manual) toast(Lang.t("আপডেট চেক করা যায়নি"));
                return;
            }
            if (version == null) {
                knownUpdate = null;
                knownApk = null;
                updateShown = null;
                if (updatePill != null) updatePill.setVisibility(View.GONE);
                if (manual) toast(Lang.t("আপনি সর্বশেষ সংস্করণ ব্যবহার করছেন"));
                return;
            }
            showUpdate(version, apkUrl, manual);
        });
    }

    private void showUpdate(final String version, final String apkUrl, boolean force) {
        knownUpdate = version;
        knownApk = apkUrl;
        if (isFinishing() || isDestroyed()) return;
        if (updatePill != null) {
            updatePill.setText("↑ " + version);
            updatePill.setVisibility(View.VISIBLE);
        }
        // একই ভার্সনের পপআপ এই অ্যাপ চালুর সময়ে একবারই; আবার খুললে আবার আসবে
        if (!force && version.equals(updateShown)) return;
        updateShown = version;

        String msg = Lang.isEn()
                ? "Version " + version + " is available.\nYou are on "
                        + Updater.currentVersion(this) + "."
                : "সংস্করণ " + version + " এসেছে।\nএখন আপনি "
                        + Updater.currentVersion(this) + " ব্যবহার করছেন।";
        dialog().setTitle(Lang.t("নতুন সংস্করণ পাওয়া গেছে"))
                .setMessage(msg)
                .setPositiveButton(Lang.t("ডাউনলোড করুন"), (d, w) -> {
                    if (apkUrl != null) {
                        Downloader.start(MainActivity.this, apkUrl, uaMobile, null,
                                "application/vnd.android.package-archive", 0);
                    } else {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(Updater.RELEASES)));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton(Lang.t("পরে"), null)
                .show();
    }

    // ---------------------------------------------------------------- দ্রুত পরীক্ষা

    // ---------------------------------------------------------------- দ্রুত পরীক্ষা

    private void networkCheck() {
        String wv = "";
        try {
            wv = getPackageManager().getPackageInfo("com.google.android.webview", 0).versionName;
        } catch (Exception ignored) { }
        final StringBuilder head = new StringBuilder();
        head.append("Android ").append(Build.VERSION.RELEASE)
                .append(" · SDK ").append(Build.VERSION.SDK_INT)
                .append("\nWebView ").append(wv.length() == 0 ? "?" : wv)
                .append("\n\n");

        final AlertDialog dlg = dialog()
                .setTitle(Lang.t("নেটওয়ার্ক পরীক্ষা"))
                .setMessage(head.toString() + Lang.t("দেখা হচ্ছে…"))
                .setPositiveButton(Lang.t("ঠিক আছে"), null)
                .show();

        final String[] urls = {"https://www.google.com/", "https://www.prothomalo.com/",
                "http://example.com/"};
        new Thread(new Runnable() {
            public void run() {
                final StringBuilder res = new StringBuilder(head);
                for (int i = 0; i < urls.length; i++) {
                    res.append(check(urls[i])).append("\n");
                    final String text = res.toString();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            try { dlg.setMessage(text); } catch (Exception ignored) { }
                        }
                    });
                }
            }
        }).start();
    }

    private String check(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", uaMobile == null ? "BanglaBrowser" : uaMobile);
            int code = c.getResponseCode();
            return url + "  →  " + code;
        } catch (Exception e) {
            String m = e.getMessage();
            if (m != null && m.length() > 70) m = m.substring(0, 70) + "…";
            return url + "  →  " + e.getClass().getSimpleName()
                    + (m == null ? "" : ": " + m);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void about() {
        String msg = Lang.isEn()
                ? "Version " + VERSION + "\n\n"
                + "Skyble Browser is a light web browser for Android. Pages are\n"
                + "drawn by the system WebView. Tabs, bookmarks, history, dark\n"
                + "mode, English and Bengali.\n\n"
                + "Media download\n"
                + "Save video, audio and files from any site you open, and\n"
                + "YouTube video up to 1080p as MP4.\n\n"
                + "Video player\n"
                + "Videos from pages and from your downloads play in the app:\n"
                + "MP4, WebM, MKV, MOV, HLS and DASH.\n\n"
                + "Download manager\n"
                + "Use the built-in downloader, or a download app already on the\n"
                + "phone such as ADM. The choice can be remembered.\n\n"
                + "Ad blocking\n"
                + "Known ad and tracker hosts are stopped before the request\n"
                + "leaves the phone. No analytics, no ad SDK.\n\n"
                + "Mamun (@Mamun015)  ·  github.com/Mamun015\n"
                + "Source: github.com/Mamun015/skyble-browser\n"
                + "License: MPL-2.0\n\n"
                + "Skyble and the Skyble logo are trademarks of the author.\n"
                + "© 2026 Mamun. All rights reserved.\n\n"
                + "Made in Bangladesh"
                : "সংস্করণ " + VERSION + "\n\n"
                + "স্কাইবল ব্রাউজার অ্যান্ড্রয়েডের জন্য হালকা ব্রাউজার। পেজ আঁকে\n"
                + "ফোনের WebView। ট্যাব, বুকমার্ক, ইতিহাস, রাতের মোড, বাংলা ও\n"
                + "ইংরেজি ইন্টারফেস।\n\n"
                + "মিডিয়া ডাউনলোড\n"
                + "যে সাইটই খুলুন, সেখান থেকে ভিডিও, অডিও বা ফাইল নামানো\n"
                + "যায়। ইউটিউবের ভিডিও ১০৮০p পর্যন্ত MP4।\n\n"
                + "ভিডিও প্লেয়ার\n"
                + "সাইটের ভিডিও আর ডাউনলোড করা ভিডিও অ্যাপেই চলে: MP4,\n"
                + "WebM, MKV, MOV, HLS, DASH।\n\n"
                + "ডাউনলোড ম্যানেজার\n"
                + "নিজের ডাউনলোডার, অথবা ফোনে থাকা অন্য অ্যাপ (ADM ইত্যাদি)\n"
                + "দিয়ে নামানো যায়। পছন্দ মনে রাখা যায়।\n\n"
                + "বিজ্ঞাপন ব্লক\n"
                + "জানা বিজ্ঞাপন ও ট্র্যাকার সার্ভার ফোন থেকে বেরোনোর আগেই\n"
                + "আটকে যায়। কোনো অ্যানালিটিক্স নেই, অ্যাড SDK নেই।\n\n"
                + "মামুন (@Mamun015)  ·  github.com/Mamun015\n"
                + "সোর্স: github.com/Mamun015/skyble-browser\n"
                + "লাইসেন্স: MPL-2.0\n\n"
                + "Skyble ও Skyble লোগো নির্মাতার ট্রেডমার্ক।\n"
                + "© ২০২৬ মামুন। সর্বস্বত্ব সংরক্ষিত।\n\n"
                + "Made in Bangladesh";
        dialog().setTitle(Lang.t("অ্যাপ সম্পর্কে"))
                .setMessage(msg)
                .setPositiveButton(Lang.t("ঠিক আছে"), null)
                .show();
    }

    // ---------------------------------------------------------------- ডাউনলোড শেষ

    /** ডাউনলোডার ফাইল নামানোর পরে এখানে খবর দেয় (পেছনের থ্রেড থেকে আসে) */
    void downloadDone(final Downloader.Task task, final Uri uri) {
        if (task == null) return;
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                // ব্রাউজারের নিজের আপডেট হলে সোজা ইনস্টল স্ক্রিন
                if (task.selfUpdate && uri != null) {
                    installApk(uri);
                    return;
                }
                showDoneBar(task.name, uri, task.mime);
            }
        });
    }

    /** নিচে ভেসে ওঠা ছোট বার — "ওপেন" চাপলে ফাইলটা খোলে */
    private void showDoneBar(final String name, final Uri uri, final String mime) {
        final ViewGroup root = findViewById(android.R.id.content);
        if (root == null || uri == null) {
            toast(Lang.t("ডাউনলোড সম্পন্ন: ") + name);
            return;
        }
        if (doneBar != null && doneBar.getParent() != null) {
            ((ViewGroup) doneBar.getParent()).removeView(doneBar);
        }
        if (doneHandler == null) doneHandler = new Handler(Looper.getMainLooper());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(roundedBorder(cSurface2, 16));
        bar.setElevation(dp(8));
        bar.setPadding(dp(14), dp(10), dp(6), dp(10));

        TextView msg = new TextView(this);
        msg.setText(Lang.t("ডাউনলোড সম্পন্ন: ") + name);
        msg.setTextSize(12.5f);
        msg.setTextColor(cText);
        msg.setMaxLines(2);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(msg, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView open = new TextView(this);
        open.setText(Lang.t("ওপেন"));
        open.setTextSize(13);
        open.setTypeface(Typeface.DEFAULT_BOLD);
        open.setTextColor(cAccent);
        open.setPadding(dp(12), dp(8), dp(12), dp(8));
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideDoneBar();
                openFile(uri, mime);
            }
        });
        bar.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(13);
        close.setTextColor(cMuted);
        close.setPadding(dp(10), dp(8), dp(10), dp(8));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hideDoneBar(); }
        });
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.bottomMargin = dp(80);
        root.addView(bar, lp);
        doneBar = bar;

        doneHandler.removeCallbacksAndMessages(null);
        doneHandler.postDelayed(new Runnable() {
            public void run() { hideDoneBar(); }
        }, 8000);
    }

    private void hideDoneBar() {
        if (doneBar == null) return;
        if (doneBar.getParent() != null) ((ViewGroup) doneBar.getParent()).removeView(doneBar);
        doneBar = null;
    }

    private void openFile(Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null || mime.length() == 0 ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
            } catch (Exception ignored) {
                toast(Lang.t("ফাইলটা খোলা যায়নি"));
            }
        }
    }

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return true;
        }
    }

    /** APK-টা ক্যাশে কপি করে ইনস্টলার স্ক্রিন খোলে */
    private void installApk(Uri uri) {
        if (uri == null) return;
        if (!canInstall()) {
            pendingInstall = uri;
            dialog().setTitle(Lang.t("ইনস্টলের অনুমতি"))
                    .setMessage(Lang.t("আপডেট ইনস্টল করতে এই ব্রাউজারকে অজানা অ্যাপ ইনস্টল করার অনুমতি দিতে হবে। অনুমতি দিয়ে ফিরে এলে ইনস্টল স্ক্রিন নিজেই আসবে।"))
                    .setPositiveButton(Lang.t("সেটিংসে যাও"), (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) { }
                    })
                    .setNegativeButton(Lang.t("পরে"), null)
                    .show();
            return;
        }
        try {
            java.io.File tmp = new java.io.File(getCacheDir(), "update.apk");
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new java.io.IOException("no stream");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
            }
            ApkProvider.shared = tmp;
            Uri apk = Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(apk, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast(Lang.t("ইনস্টলার খোলা যায়নি, Downloads ফোল্ডার থেকে APK ফাইলটা চেপে ইনস্টল করো"));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
