package com.skyble.browser;

import android.content.Context;

import java.io.File;

/**
 * একটা ছোট লগ ফাইল, যাতে ফোনে সমস্যা হলে কী হয়েছে বোঝা যায়।
 * বেশি বড় হলে পুরোনোটা সরিয়ে নতুন করে লেখা শুরু হয়।
 */
final class Trace {

    private Trace() { }

    private static final Object LOCK = new Object();
    private static final long MAX = 400 * 1024;
    private static File file;

    static void init(Context c) {
        if (file != null || c == null) return;
        try {
            file = new File(c.getCacheDir(), "skyble-debug.txt");
        } catch (Exception ignored) { }
    }

    static File file(Context c) {
        init(c);
        return file;
    }

    static void w(String tag, String msg) {
        synchronized (LOCK) {
            if (file == null) return;
            try {
                if (file.length() > MAX) {
                    File old = new File(file.getParentFile(), "skyble-debug-old.txt");
                    if (old.exists()) old.delete();
                    file.renameTo(old);
                }
                String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US).format(new java.util.Date())
                        + " [" + Thread.currentThread().getName() + "] "
                        + tag + ": " + msg + "\n";
                java.io.FileOutputStream f = new java.io.FileOutputStream(file, true);
                try {
                    f.write(line.getBytes("UTF-8"));
                } finally {
                    f.close();
                }
            } catch (Exception ignored) { }
        }
    }

    static void clear() {
        synchronized (LOCK) {
            try { if (file != null) file.delete(); } catch (Exception ignored) { }
        }
    }
}
