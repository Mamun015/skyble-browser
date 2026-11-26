package com.skyble.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub-এ নতুন রিলিজ এসেছে কি না দেখে।
 *
 * api.github.com ব্যবহার করা হয় না: শেয়ার্ড ইন্টারনেটে (মোবাইল অপারেটরের NAT) ওই
 * এন্ডপয়েন্ট প্রায়ই 403 দেয়, কারণ একই IP থেকে অনেকে চালায় আর লগইন ছাড়া সীমা
 * প্রতি ঘণ্টায় ৬০টি। তাই সোজা ওয়েবসাইটের রিডাইরেক্ট দেখি — ওতে কোনো সীমা নেই।
 */
final class Updater {

    private static final String REPO =
            "https://github.com/Mamun015/skyble-browser";
    static final String RELEASES = REPO + "/releases/latest";
    /** সীমা ছাড়া, লগইন ছাড়া — রিডাইরেক্ট ব্যর্থ হলে এটা */
    private static final String ATOM = REPO + "/releases.atom";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) SkybleBrowser";
    /** অ্যাপ খোলা থাকলে ঘণ্টায় একবার নিজে থেকেই দেখে */
    private static final long EVERY = 60L * 60 * 1000;

    private static final Pattern TAG =
            Pattern.compile("releases/tag/([A-Za-z0-9._-]+)");
    private static final Pattern APK_LINK =
            Pattern.compile("href=\"([^\"]+\\.apk)\"", Pattern.CASE_INSENSITIVE);

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
                try {
                    String tag = tagFromRedirect();
                    if (tag == null) tag = tagFromAtom();
                    if (tag != null) {
                        failed = false;
                        String clean = tag.replace("v", "").trim();
                        // "1.2.5 test 1" টেস্ট বিল্ডে বসে থাকলেও 1.2.5 বা তার পরেরটা ধরা পড়ে
                        if (newer(clean, base) || sameAsTestBase(clean, current)) {
                            version = clean;
                            apk = apkFor(tag);
                        }
                    }
                } catch (Exception ignored) {
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

    /** releases/latest → releases/tag/v1.3.0 রিডাইরেক্টের ঠিকানা থেকেই ট্যাগ পাওয়া যায়। */
    private static String tagFromRedirect() {
        HttpURLConnection cn = null;
        try {
            String url = RELEASES;
            for (int i = 0; i < 6; i++) {
                cn = (HttpURLConnection) new URL(url).openConnection();
                cn.setConnectTimeout(10000);
                cn.setReadTimeout(10000);
                cn.setInstanceFollowRedirects(false);
                cn.setRequestProperty("User-Agent", UA);
                int code = cn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = cn.getHeaderField("Location");
                    cn.disconnect();
                    cn = null;
                    if (loc == null) return null;
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code == 200) return firstTag(url);
                return null;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }

    /** রিডাইরেক্ট আটকালে রিলিজের ফিড — ওতেও সীমা নেই। */
    private static String tagFromAtom() {
        String body = get(ATOM);
        return body == null ? null : firstTag(body);
    }

    private static String firstTag(String text) {
        Matcher m = TAG.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** রিলিজ পাতার অ্যাসেট তালিকা থেকে ব্রাউজারের APK-র লিংক। */
    private static String apkFor(String tag) {
        String body = get(REPO + "/releases/expanded_assets/" + tag);
        if (body != null) {
            Matcher m = APK_LINK.matcher(body);
            while (m.find()) {
                String href = m.group(1);
                // হেল্পার অ্যাপের APK নয়
                if (href.toLowerCase(Locale.US).contains("tube")) continue;
                if (href.startsWith("/")) href = "https://github.com" + href;
                return href;
            }
        }
        return null;
    }

    /** GET করে পুরো লেখা ফেরায়, রিডাইরেক্ট নিজে ফলো করে; সমস্যা হলে null। */
    private static String get(String url) {
        HttpURLConnection cn = null;
        InputStream in = null;
        try {
            String u = url;
            for (int i = 0; i < 6; i++) {
                cn = (HttpURLConnection) new URL(u).openConnection();
                cn.setConnectTimeout(10000);
                cn.setReadTimeout(15000);
                cn.setInstanceFollowRedirects(false);
                cn.setRequestProperty("User-Agent", UA);
                int code = cn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = cn.getHeaderField("Location");
                    cn.disconnect();
                    cn = null;
                    if (loc == null) return null;
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                in = cn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (cn != null) cn.disconnect();
        }
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
