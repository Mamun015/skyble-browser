package com.skyble.browser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * ফোনে ইনস্টল থাকা অন্য ডাউনলোড ম্যানেজার (ADM ইত্যাদি) খুঁজে বের করে।
 * ওরা http/https লিংক ACTION_VIEW দিয়ে নেয়, তাই ওই ইনটেন্টই পাঠানো হয়।
 */
final class DownloadApps {

    private DownloadApps() { }

    static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** ব্রাউজার আর নিজের অ্যাপ বাদ দিয়ে বাকি ডাউনলোডারগুলো */
    static List<App> list(Context c, String url, String mime) {
        List<App> out = new ArrayList<>();
        add(c, out, url, mime == null || mime.length() == 0 ? "*/*" : mime);
        add(c, out, url, "*/*");
        return out;
    }

    private static void add(Context c, List<App> out, String url, String mime) {
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW);
            probe.setDataAndType(Uri.parse(url), mime);
            List<ResolveInfo> found = c.getPackageManager()
                    .queryIntentActivities(probe, 0);
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(c.getPackageName())) continue;
                if (isBrowser(pkg)) continue;
                boolean dup = false;
                for (App a : out) if (a.pkg.equals(pkg)) dup = true;
                if (dup) continue;
                CharSequence label = ri.loadLabel(c.getPackageManager());
                out.add(new App(pkg, label == null ? pkg : label.toString()));
            }
        } catch (Exception ignored) { }
    }

    /** ইনস্টল করা অ্যাপের নাম, না পেলে প্যাকেজের নাম */
    static String label(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(
                    c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** ওই অ্যাপে লিংকটা পাঠায়; অ্যাপ না থাকলে false */
    static boolean open(Context c, String pkg, String url, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url), mime == null || mime.length() == 0 ? "*/*" : mime);
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** লিংক খোলার অ্যাপগুলোর মধ্যে ব্রাউজারগুলো বাদ, নইলে তালিকা জমে যায় */
    private static boolean isBrowser(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.contains("browser") || p.contains("chrome") || p.contains("firefox")
                || p.contains("opera") || p.contains("brave") || p.contains("vivaldi")
                || p.contains("duckduckgo") || p.contains("microsoft.emmx")
                || p.contains("yandex") || p.contains("ucmobile") || p.contains("quark")
                || p.contains("webview");
    }
}
