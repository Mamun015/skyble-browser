package com.skyble.browser;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * YouTube high quality links stop after a few megabytes unless the request
 * carries a proof of origin token. The token is made by YouTube's own BotGuard
 * script inside a page, which is far too much code to rewrite here.
 *
 * <p>So a page is opened in a hidden WebView and YouTube's player is left to do
 * its own thing. The player response the page receives already carries a token,
 * so the file links inside it download at full speed. Nothing is drawn: the
 * page is one pixel, hidden, and removed as soon as the links are read.
 *
 * <p>Reading happens in the background. If the page never loads, or YouTube
 * sends no links, every call simply returns null and the normal resolver is
 * used instead.
 */
final class Pot {

    private Pot() { }

    /** desktop page, because it hands out plain links more often than the phone one */
    static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final long WAIT_MS = 45000;

    /** id -> rows of {url, itag, audio} ; null marks "tried, nothing" */
    private static final java.util.HashMap<String, List<String[]>> cache =
            new java.util.HashMap<>();
    private static final java.util.HashSet<String> busy = new java.util.HashSet<>();

    private static volatile String pageUa = UA;

    static String ua() {
        return pageUa;
    }

    /** পেজটা আগেই খুলে রাখে, যাতে ডাউনলোড আটকালে লিংক তৈরি থাকে */
    static void warm(final Activity act, final String id) {
        if (act == null || id == null || id.length() != 11) return;
        synchronized (cache) {
            if (cache.containsKey(id) || busy.contains(id)) return;
            busy.add(id);
        }
        act.runOnUiThread(new Runnable() {
            public void run() {
                load(act, id);
            }
        });
    }

    /** তৈরি থাকলে সাথে সাথে লিংক, নাহলে null — ডাউনলোড থামিয়ে রাখা হয় না */
    static String peek(final Activity act, final String id, int itag) {
        if (id == null || id.length() != 11 || itag <= 0) return null;
        List<String[]> rows;
        synchronized (cache) {
            if (!cache.containsKey(id)) {
                warm(act, id);
                return null;
            }
            rows = cache.get(id);
        }
        if (rows == null) return null;
        for (String[] r : rows) {
            if (Integer.parseInt(r[1]) != itag) continue;
            String u = r[0];
            if (u != null && u.length() > 0) return u;
        }
        return null;
    }

    /** টোকেনের জন্য কিছুক্ষণ অপেক্ষা; সময় পার হলে null */
    static String waitLink(final Activity act, final String id, int itag, long millis) {
        if (id == null || id.length() != 11 || itag <= 0) return null;
        warm(act, id);
        long end = System.currentTimeMillis() + millis;
        List<String[]> rows;
        synchronized (cache) {
            while (!cache.containsKey(id) && System.currentTimeMillis() < end) {
                try {
                    cache.wait(Math.max(200, Math.min(2000, end - System.currentTimeMillis())));
                } catch (InterruptedException e) {
                    return null;
                }
            }
            rows = cache.get(id);
        }
        if (rows == null) return null;
        for (String[] r : rows) {
            if (Integer.parseInt(r[1]) != itag) continue;
            String u = r[0];
            if (u != null && u.length() > 0) return u;
        }
        return null;
    }

    private static void put(String id, List<String[]> rows) {
        synchronized (cache) {
            cache.put(id, rows);
            busy.remove(id);
            cache.notifyAll();
        }
    }

    /** হিডেন WebView-এ পেজ খুলে প্লেয়ার-রেসপন্স পড়ে */
    private static void load(final Activity act, final String id) {
        final WebView web = new WebView(act);
        final Handler ui = new Handler(Looper.getMainLooper());
        final long started = System.currentTimeMillis();
        final boolean[] done = {false};
        // তিন ধরনের পেজ পালা করে চেষ্টা করা হয়, যেটা আগে লিংক দেয় সেটাই যথেষ্ট
        final String[] urls = {
                "https://m.youtube.com/watch?v=" + id,
                "https://www.youtube.com/embed/" + id + "?autoplay=1",
                "https://www.youtube.com/watch?v=" + id
        };
        final long[] windows = {15000, 15000, 15000};
        final int[] which = {0};
        final long[] attemptStart = {started};

        final Runnable finish = new Runnable() {
            public void run() {
                try { web.stopLoading(); } catch (Exception ignored) { }
                try { web.loadUrl("about:blank"); } catch (Exception ignored) { }
                try { ((ViewGroup) web.getParent()).removeView(web); } catch (Exception ignored) { }
                try { web.destroy(); } catch (Exception ignored) { }
            }
        };
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            public void run() {
                if (done[0]) return;
                long now = System.currentTimeMillis();
                if (now - started > 42000) {
                    Trace.w("pot", "gave up, no links");
                    done[0] = true;
                    finish.run();
                    put(id, null);
                    return;
                }
                if (now - attemptStart[0] > windows[which[0]] && which[0] < urls.length - 1) {
                    which[0]++;
                    Trace.w("pot", "no links yet, trying " + urls[which[0]]);
                    attemptStart[0] = now;
                    try { web.loadUrl(urls[which[0]]); } catch (Exception ignored) { }
                    ui.postDelayed(poll[0], 6000);
                    return;
                }
                String js = "(function(){try{"
                        + "var p=window.ytInitialPlayerResponse;"
                        + "var m=document.getElementById('movie_player');"
                        + "if((!p||!p.streamingData)&&m&&m.getPlayerResponse){try{p=m.getPlayerResponse();}catch(e){}}"
                        + "if(!p||!p.streamingData)return '';"
                        + "var o=[],af=p.streamingData.adaptiveFormats||[],fm=p.streamingData.formats||[],i,f;"
                        + "var all=af.concat(fm);"
                        + "for(i=0;i<all.length;i++){f=all[i];"
                        + "if(!f||!f.url)continue;"
                        + "o.push([f.url.replace(/&amp;/g,'&'),String(f.itag||''),"
                        + "(f.mimeType||'').indexOf('audio/')==0?'1':'0']);}"
                        + "if(!o.length)return '';"
                        + "return JSON.stringify(o);"
                        + "}catch(e){return '';}})()";
                web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String value) {
                        if (done[0]) return;
                        List<String[]> rows = parse(value);
                        if (rows == null || rows.isEmpty()) {
                            if (value != null && value.length() > 2) {
                                Trace.w("pot", "js said " + value.substring(0,
                                        Math.min(80, value.length())));
                            }
                            ui.postDelayed(poll[0], 900);
                            return;
                        }
                        Trace.w("pot", "links " + rows.size() + " from "
                                + web.getUrl());
                        done[0] = true;
                        String uaNow = null;
                        try { uaNow = web.getSettings().getUserAgentString(); } catch (Exception ignored) { }
                        if (uaNow != null && uaNow.length() > 0) pageUa = uaNow;
                        put(id, rows);
                        finish.run();
                    }
                });
            }
        };

        Trace.w("pot", "loading " + urls[0]);
        try {
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setLoadsImagesAutomatically(false);
            s.setBlockNetworkImage(true);
            s.setUserAgentString(UA);
            s.setMediaPlaybackRequiresUserGesture(false);
            web.setVisibility(View.INVISIBLE);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    Trace.w("pot", "page finished " + url);
                    ui.postDelayed(poll[0], 800);
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description,
                                            String failingUrl) {
                    Trace.w("pot", "page error " + errorCode + " " + description
                            + " " + failingUrl);
                }
            });
            web.layout(0, 0, 1, 1);
            web.measure(View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY));
            act.addContentView(web, new ViewGroup.LayoutParams(1, 1));
            web.loadUrl(urls[0]);
            ui.postDelayed(poll[0], 5000);
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    finish.run();
                    put(id, null);
                }
            }, 44000);
        } catch (Exception e) {
            put(id, null);
        }
    }

    /** JS থেকে আসা JSON-লেখা: [["url","itag","audio"], …] */
    private static List<String[]> parse(String value) {
        if (value == null) return null;
        try {
            Object o = new JSONTokener(value).nextValue();
            if (!(o instanceof String)) return null;
            String s = (String) o;
            if (s.length() < 3) return null;
            JSONArray arr = new JSONArray(s);
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray f = arr.optJSONArray(i);
                if (f == null || f.length() < 2) continue;
                String url = f.optString(0, "");
                if (url.length() == 0) continue;
                rows.add(new String[]{url, f.optString(1, "0"), f.optString(2, "0")});
            }
            return rows;
        } catch (Exception e) {
            return null;
        }
    }
}
