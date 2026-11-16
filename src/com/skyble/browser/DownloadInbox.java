package com.skyble.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * হেল্পার অ্যাপ (Skyble Tube) ডাউনলোড শেষ হলে এখানে খবর দেয়, যাতে ফাইলটা
 * ব্রাউজারের ডাউনলোড তালিকাতেও দেখা যায়। রিসিভারটি signature পারমিশন দিয়ে
 * সুরক্ষিত — একই কী দিয়ে সাইন করা অ্যাপ ছাড়া কেউ খবর পাঠাতে পারে না।
 */
public class DownloadInbox extends BroadcastReceiver {

    static final String ACTION = "com.skyble.browser.DOWNLOAD_DONE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || ctx == null) return;
        String name = intent.getStringExtra("name");
        String uri = intent.getStringExtra("uri");
        String mime = intent.getStringExtra("mime");
        long size = intent.getLongExtra("size", 0);
        if (name == null || name.length() == 0) name = "video.mp4";
        if (mime == null || mime.length() == 0) mime = "video/mp4";
        MainActivity.addDownloadRecord(ctx, name, uri == null ? "" : uri, mime, size);
    }
}
