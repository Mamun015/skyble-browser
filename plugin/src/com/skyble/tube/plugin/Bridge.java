package com.skyble.tube.plugin;

import android.content.Context;
import android.content.Intent;

/**
 * ডাউনলোড শেষ হলে ব্রাউজারকে খবর দেয়, যাতে ফাইলটা ব্রাউজারের ডাউনলোড
 * তালিকাতেও দেখা যায়। ব্রাউজার না থাকলে চুপচাপ কিছুই হয় না।
 */
final class Bridge {

    static final String ACTION = "com.skyble.browser.DOWNLOAD_DONE";
    private static final String BROWSER = "com.skyble.browser";

    private Bridge() { }

    static void post(Context ctx, String name, Object uri, String mime, long size) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ACTION);
            i.setPackage(BROWSER);
            i.putExtra("name", name == null ? "video.mp4" : name);
            i.putExtra("uri", uri == null ? "" : uri.toString());
            i.putExtra("mime", mime == null || mime.length() == 0 ? "video/mp4" : mime);
            i.putExtra("size", size);
            ctx.sendBroadcast(i);
        } catch (Exception ignored) { }
    }
}
