package com.skyble.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;

/**
 * অ্যাপের রং এক জায়গায়।
 *
 * অ্যান্ড্রয়েড ১২+ এ প্ল্যাটফর্মের ডায়নামিক প্যালেট (system_accent1_*,
 * system_neutral1_*, system_neutral2_*) থেকে ম্যাটেরিয়াল ৩ রোল নেওয়া হয়, তাই
 * ফোনের ওয়ালপেপার বা সিস্টেম থিম বদলালে অ্যাপও সেটাই ব্যবহার করে।
 *
 * দয়া করে মনে রাখুন: অ্যান্ড্রয়েডের টোন নাম উল্টো। `_10` মানে টোন ৯৯ (প্রায় সাদা),
 * `_1000` মানে টোন ০ (কালো)। তাই লাইট থিমে `_10`/`_900`, ডার্ক থিমে `_900`/`_100`।
 */
public class Theme {

    public final boolean dark;
    public final int bg, surface, surface2, text, muted, accent, accentSoft, line;
    /** অ্যাকসেন্টের ওপরে বসা লেখা/আইকনের রং — কখনো সাদা ধরে নেওয়া যাবে না */
    public final int onAccent, onAccentSoft;
    /** দ্বিতীয় অ্যাকসেন্ট (প্রাইভেট ট্যাবের মতো আলাদা জিনিসের জন্য) */
    public final int accent2;

    public Theme(Context c, boolean dark) {
        this.dark = dark;
        int bg, surface, surface2, text, muted, accent, accentSoft, line;
        int onAccent, onAccentSoft, accent2;
        if (dark) {
            bg = Color.parseColor("#080B0A");
            surface = Color.parseColor("#121715");
            surface2 = Color.parseColor("#1A211E");
            text = Color.parseColor("#EAF1EE");
            muted = Color.parseColor("#8D9A95");
            accent = Color.parseColor("#34D399");
            accentSoft = Color.parseColor("#16332A");
            line = Color.parseColor("#1F2724");
            onAccent = Color.parseColor("#04241B");
            onAccentSoft = Color.parseColor("#6EE7B7");
            accent2 = Color.parseColor("#B39DDB");
        } else {
            bg = Color.parseColor("#FFFFFF");
            surface = Color.parseColor("#F3F7F5");
            surface2 = Color.parseColor("#E9F0EC");
            text = Color.parseColor("#0D1412");
            muted = Color.parseColor("#5A6763");   // ছোট লেখাও AA পাস করে
            accent = Color.parseColor("#007A5A");
            accentSoft = Color.parseColor("#E3F4ED");
            line = Color.parseColor("#E4EBE7");
            onAccent = Color.WHITE;
            onAccentSoft = Color.parseColor("#00543E");
            accent2 = Color.parseColor("#5E35B1");
        }

        if (enabled(c)) {
            if (dark) {
                bg = sys(c, "system_neutral1_900", bg);          // টোন ১০
                surface = sys(c, "system_neutral1_800", surface); // টোন ২০
                surface2 = sys(c, "system_neutral1_700", surface2); // টোন ৩০
                text = sys(c, "system_neutral1_100", text);       // টোন ৯০
                muted = sys(c, "system_neutral2_200", muted);     // টোন ৮০
                line = sys(c, "system_neutral2_700", line);       // টোন ৩০
                accent = sys(c, "system_accent1_200", accent);    // টোন ৮০
                accentSoft = sys(c, "system_accent1_700", accentSoft); // টোন ৩০
                onAccent = sys(c, "system_accent1_800", onAccent); // টোন ২০
                onAccentSoft = sys(c, "system_accent1_100", onAccentSoft); // টোন ৯০
                accent2 = sys(c, "system_accent3_200", accent2);
            } else {
                bg = sys(c, "system_neutral1_10", bg);            // টোন ৯৯
                surface = sys(c, "system_neutral1_50", surface);  // টোন ৯৫
                surface2 = sys(c, "system_neutral2_100", surface2); // টোন ৯০
                text = sys(c, "system_neutral1_900", text);       // টোন ১০
                muted = sys(c, "system_neutral2_700", muted);     // টোন ৩০
                line = sys(c, "system_neutral2_200", line);       // টোন ৮০
                accent = sys(c, "system_accent1_600", accent);    // টোন ৪০
                accentSoft = sys(c, "system_accent1_100", accentSoft); // টোন ৯০
                onAccent = sys(c, "system_accent1_0", onAccent);  // টোন ১০০
                onAccentSoft = sys(c, "system_accent1_900", onAccentSoft); // টোন ১০
                accent2 = sys(c, "system_accent3_600", accent2);
            }
        }

        this.bg = bg;
        this.surface = surface;
        this.surface2 = surface2;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.accentSoft = accentSoft;
        this.line = line;
        this.onAccent = onAccent;
        this.onAccentSoft = onAccentSoft;
        this.accent2 = accent2;
    }

    /** এই ফোনে ডায়নামিক রং পাওয়া যায় কি না (অ্যান্ড্রয়েড ১২+) */
    static boolean available() {
        return Build.VERSION.SDK_INT >= 31;
    }

    /** সেটিংসে চালু আছে কি না; ডিফল্টে চালু */
    static boolean enabled(Context c) {
        if (!available() || c == null) return false;
        SharedPreferences p = c.getSharedPreferences("skyble_browser", Context.MODE_PRIVATE);
        return p.getBoolean("materialyou", true);
    }

    /** প্যালেট বদলেছে কি না বোঝার ছোট ছাপ — সব রং একসাথে ধরে */
    long stamp() {
        long h = 17;
        int[] all = {bg, surface, surface2, text, muted, accent, accentSoft, line,
                onAccent, onAccentSoft, accent2};
        for (int c : all) h = h * 31 + c;
        return h;
    }

    /** একই রঙ, আলাদা স্বচ্ছতা — রিপল, স্ক্রিম, বর্ডারের জন্য */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int sys(Context c, String name, int fallback) {
        try {
            int id = c.getResources().getIdentifier(name, "color", "android");
            if (id != 0) {
                if (Build.VERSION.SDK_INT >= 23) return c.getColor(id);
                return c.getResources().getColor(id);
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
