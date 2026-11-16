package com.skyble.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** GitHub-এ নতুন রিলিজ এসেছে কি না দেখে। */
final class Updater {

    static final String RELEASES =
            "https://github.com/Mamun015/skyble-browser/releases/latest";
    private static final String API =
            "https://api.github.com/repos/Mamun015/skyble-browser/releases/latest";
    /** অ্যাপ খোলা থাকলে ঘণ্টায় একবার নিজে থেকেই দেখে */
    private static final long EVERY = 60L * 60 * 1000;

    interface Result {
        /**
         * @param version নতুন ভার্সন, নতুন কিছু না থাকলে null
         * @param apkUrl  নতুন APK-র সরাসরি লিংক, না পেলে null
         * @param failed  ইন্টারনেট বা সার্ভার সমস্যা হলে true
         */
        void done(String version, String apkUrl, boolean failed);
    }

    private Updater() { }

    /** শেষ চেকের এক ঘণ্টা পেরোলে true। */
    static boolean due(Context c) {
        SharedPreferences p = c.getSharedPreferences("skyble_browser", Context.MODE_PRIVATE);
        return System.currentTimeMillis() - p.getLong("update.checked", 0) > EVERY;
    }

    /** ইনস্টল করা অ্যাপের আসল versionName — কোডে লেখা সংখ্যা ভুল হলেও তুলনা ঠিক থাকে। */
    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে GitHub দেখে, ফল আসলে মেইন থ্রেডে cb ডাকে। */
    static void check(final Context ctx, final String current, final Result cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                String base = baseVersion(current);
                String version = null;
                String apk = null;
                boolean failed = true;
                HttpURLConnection cn = null;
                InputStream in = null;
                try {
                    cn = (HttpURLConnection) new URL(API).openConnection();
                    cn.setConnectTimeout(10000);
                    cn.setReadTimeout(10000);
                    cn.setRequestProperty("Accept", "application/vnd.github+json");
                    cn.setRequestProperty("User-Agent", "BanglaBrowser/" + current);
                    if (cn.getResponseCode() == 200) {
                        in = cn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        JSONObject json = new JSONObject(bos.toString("UTF-8"));
                        failed = false;
                        String tag = json.optString("tag_name", "");
                        tag = tag.replace("v", "").trim();
                        // "1.2.5 test 1" টেস্ট বিল্ডে বসে থাকলেও 1.2.5 বা তার পরেরটা ধরা পড়ে
                        if (newer(tag, base) || sameAsTestBase(tag, current)) {
                            version = tag;
                            JSONArray assets = json.optJSONArray("assets");
                            if (assets != null) {
                                for (int i = 0; i < assets.length(); i++) {
                                    JSONObject a = assets.optJSONObject(i);
                                    if (a == null) continue;
                                    String name = a.optString("name", "");
                                    if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                                        apk = a.optString("browser_download_url", "");
                                        if (apk.length() == 0) apk = null;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) { }
                    if (cn != null) cn.disconnect();
                }

                if (!failed) {
                    ctx.getSharedPreferences("skyble_browser", Context.MODE_PRIVATE)
                            .edit()
                            .putLong("update.checked", System.currentTimeMillis())
                            .apply();
                }

                final String v = version;
                final String a = apk;
                final boolean f = failed;
                ui.post(new Runnable() {
                    public void run() {
                        try { cb.done(v, a, f); } catch (Exception ignored) { }
                    }
                });
            }
        }).start();
    }

    /** "1.2.5 test 1" থেকে "1.2.5" — টেস্ট বিল্ডের মূল নম্বর */
    static String baseVersion(String v) {
        if (v == null) return "0";
        int t = v.toLowerCase(Locale.US).indexOf("test");
        String base = (t > 0 ? v.substring(0, t) : v).trim();
        return base.length() == 0 ? v : base;
    }

    /** টেস্ট বিল্ডে বসে থাকলে একই নম্বরের আসল রিলিজকেও নতুন ধরতে হয় */
    static boolean sameAsTestBase(String release, String current) {
        if (current == null || release == null) return false;
        if (!current.toLowerCase(Locale.US).contains("test")) return false;
        return baseVersion(current).equalsIgnoreCase(release.trim());
    }

    /** "2.1.0" ধরনের ভার্সন তুলনা: a, b-এর চেয়ে নতুন হলে true। */
    static boolean newer(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        String clean = v.trim().replaceAll("[^0-9.]", "");
        if (clean.length() == 0) return new int[0];
        String[] bits = clean.split("\\.");
        int[] out = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            try { out[i] = Integer.parseInt(bits[i]); } catch (Exception e) { out[i] = 0; }
        }
        return out;
    }
}
