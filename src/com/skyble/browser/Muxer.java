package com.skyble.browser;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * YouTube keeps sound and picture in separate high quality files, so both are
 * fetched and joined into one normal mp4 with MediaMuxer.
 *
 * <p>Three things make that harder than it looks:
 * a plain request gets 403, so every piece asks for a bounded byte range;
 * a slow phone link stalls in the middle, so the piece is re-asked from the
 * same byte instead of starting over;
 * and YouTube may cut a high quality file short, so the next lower quality from
 * the list is tried when a quality never finishes.
 */
final class Muxer {

    private Muxer() { }

    /** googlevideo serves up to 8 MB per request; 4 MB keeps progress smooth */
    private static final long CHUNK = 4L * 1024 * 1024;

    private static final String UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    private static final String VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12) gzip";
    private static final String IOS_UA =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";

    /** সারির তালিকা আর শুরুর জায়গা: কোয়ালিটি না নামলে পরেরটায় নামে */
    static void start(final MainActivity act, final String id, final List<String[]> rows,
                      final int from, final String name) {
        Lang.init(act.getSharedPreferences("skyble_browser", 0));
        if (rows == null || from < 0 || from >= rows.size()) return;
        if (!Downloader.hasStorage(act)) return;

        final Downloader.Task task = new Downloader.Task(name, "video/mp4");
        task.total = sizeOf(rows.get(from)[2]);
        Downloader.addTask(task);
        Downloader.askNotify(act);
        Notify.start(act, task);
        Toast.makeText(act, Lang.t("ডাউনলোড শুরু হয়েছে: ") + name, Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            public void run() {
                Muxer.run(act, id, rows, from, name, task);
            }
        }).start();
    }

    private static void run(MainActivity act, String id, List<String[]> rows, int from,
                            String name, Downloader.Task task) {
        // পেছনে: টোকেনওয়ালা পেজ আর কোন ক্লায়েন্টের লিংক পুরো চলে তার পরীক্ষা
        Pot.warm(act, id);
        Tube.scanClients(id, rows);
        StringBuilder notes = new StringBuilder();
        String lastFail = null;
        for (int i = from; i < rows.size(); i++) {
            if (task.stop) break;
            String[] row = rows.get(i);
            task.total = sizeOf(row[2]);
            task.done = 0;
            task.speed = 0;
            try {
                Trace.w("dl", "quality " + row[1] + " start, size text " + row[2]);
                Uri saved = save(act, id, row, name, task);
                Downloader.removeTask(task);
                Notify.done(act, task, saved, task.mime);
                act.downloadDone(task, saved);
                return;
            } catch (Exception e) {
                if (task.stop) break;
                lastFail = reason(e);
                Trace.w("dl", "quality " + row[1] + " failed: " + lastFail);
                if (notes.length() > 0) notes.append(", ");
                notes.append(row[1]).append(": ").append(lastFail);
                // ব্যর্থ ধাপটা কিছুক্ষণ স্ক্রিনে থাকে, যাতে বোঝা যায় কী হয়েছে
                task.phase = row[1] + " · " + Lang.t("হল না") + " · " + lastFail;
                Notify.tick(act, task);
            }
        }
        Downloader.removeTask(task);
        if (task.stop) {
            Notify.cancel(act);
            toast(act, Lang.t("ডাউনলোড বাতিল হয়েছে"));
            return;
        }
        Notify.fail(act, name, lastFail == null ? "" : lastFail);
        toast(act, Lang.t("ডাউনলোড হয়নি")
                + (notes.length() == 0 ? "" : " (" + notes + ")"));
    }

    /** একটা কোয়ালিটি নামিয়ে, দরকার হলে জোড়া লাগিয়ে Downloads-এ রাখে */
    private static Uri save(MainActivity act, String id, String[] row, String name,
                            Downloader.Task task) throws Exception {
        File vf = null, af = null, mf = null;
        try {
            String audio = row[3] == null ? "" : row[3];
            vf = File.createTempFile("skyble-v", ".mp4", act.getCacheDir());
            task.phase = Lang.t("ভিডিও নামছে");
            String[] good = Tube.goodLink(id, itagOf(row[0]));
            fetch(id, row[1], true, good == null ? row[0] : good[0],
                    good == null ? uaFor(row[0]) : good[1], vf, task, act);

            if (audio.length() > 0) {
                af = File.createTempFile("skyble-a", ".m4a", act.getCacheDir());
                task.phase = Lang.t("আওয়াজ নামছে");
                String[] goodAudio = Tube.goodLink(id, itagOf(audio));
                fetch(id, row[1], false, goodAudio == null ? audio : goodAudio[0],
                        goodAudio == null ? uaFor(audio) : goodAudio[1], af, task, act);
            }
            if (task.stop) throw new Exception("stop");

            mf = File.createTempFile("skyble-m", ".mp4", act.getCacheDir());
            if (af != null) {
                task.phase = Lang.t("ছবি আর আওয়াজ জোড়া লাগছে");
                Notify.tick(act, task);
                join(vf, af, mf);
            } else {
                mf.delete();
                mf = vf;
                vf = null;
            }

            Uri out = Downloader.publishFile(act, mf, name, "video/mp4");
            MainActivity.addDownloadRecord(act, name, out == null ? "" : out.toString(),
                    "video/mp4", mf.length());
            task.done = mf.length();
            return out;
        } finally {
            delete(vf);
            delete(af);
            delete(mf);
        }
    }

    private static void delete(File f) {
        try { if (f != null) f.delete(); } catch (Exception ignored) { }
    }

    /**
     * একটা ট্র্যাক ৪টি কানেকশনে ভাগ করে নামায়। প্রতিটি কানেকশন আলাদা করে
     * টুকরো টুকরো চায়, তাই একটা আটকে গেলেও বাকিগুলো চলতে থাকে, আর YouTube
     * এক কানেকশনকে ধীর করে দিলে বাকিগুলো সেই ঘাটতি টেনে তোলে।
     */
    private static void fetch(final String id, final String quality, final boolean video,
                              final String startUrl, final String startUa, final File out,
                              final Downloader.Task task, final MainActivity act)
            throws Exception {
        final long base = task.done;
        long size = probeSize(startUrl);
        if (size <= 0) throw new Exception("HTTP size");
        final long total = size;

        final AtomicLong done = new AtomicLong();
        final AtomicReference<String> urlRef = new AtomicReference<>(startUrl);
        final AtomicReference<String> uaRef = new AtomicReference<>(startUa);
        final AtomicBoolean pageTried = new AtomicBoolean(false);
        final AtomicReference<Exception> fatal = new AtomicReference<>();
        final Object linkLock = new Object();
        final AtomicLong attempt = new AtomicLong();

        final int workers = 4;
        final CountDownLatch latch = new CountDownLatch(workers);
        long part = (total + workers - 1) / workers;

        for (int w = 0; w < workers; w++) {
            final long from = w * part;
            final long to = Math.min(total - 1, from + part - 1);
            if (from > to) {
                latch.countDown();
                continue;
            }
            new Thread(new Runnable() {
                public void run() {
                    try {
                        work(out, from, to, id, video, urlRef, uaRef, pageTried, fatal,
                                linkLock, attempt, done, task, act);
                    } catch (Exception e) {
                        if (fatal.get() == null) fatal.set(e);
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }

        long lastDone = 0;
        long lastTime = System.currentTimeMillis();
        while (true) {
            boolean finished = latch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS);
            long now = System.currentTimeMillis();
            long d = done.get();
            task.done = base + d;
            if (now - lastTime >= 600) {
                task.speed = (d - lastDone) * 1000L / Math.max(1, now - lastTime);
                lastDone = d;
                lastTime = now;
                String head = video ? Lang.t("ভিডিও নামছে") : Lang.t("আওয়াজ নামছে");
                task.phase = head + " " + (d * 100 / Math.max(1, total)) + "%";
                Notify.tick(act, task);
            }
            if (task.stop || finished || fatal.get() != null) break;
        }
        task.done = base + done.get();

        if (task.stop) throw new Exception("stop");
        Exception e = fatal.get();
        if (e != null) throw e;
        if (done.get() < total) throw new Exception("stopped " + done.get() + "/" + total);
    }

    /** এক কানেকশনের কাজ: নিজের অংশটুকু টুকরো টুকরো নামিয়ে ফাইলে বসায় */
    private static void work(File out, long from, long to, String id, boolean video,
                             AtomicReference<String> urlRef, AtomicReference<String> uaRef,
                             AtomicBoolean pageTried, AtomicReference<Exception> fatal,
                             Object linkLock, AtomicLong attempt, AtomicLong done,
                             Downloader.Task task, MainActivity act) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(out, "rw");
        long pos = from;
        int stalls = 0;
        int refusals = 0;
        int noProgress = 0;
        try {
            while (pos <= to) {
                if (task.stop) throw new Exception("stop");
                if (fatal.get() != null) return;
                long end = Math.min(to, pos + CHUNK - 1);
                String url = urlRef.get();
                String ua = uaRef.get();
                long reqStart = System.currentTimeMillis();
                int reqCode = 0;
                HttpURLConnection cn = null;
                long got = 0;
                String failure = null;
                try {
                    cn = (HttpURLConnection) new URL(url).openConnection();
                    cn.setInstanceFollowRedirects(true);
                    cn.setConnectTimeout(20000);
                    cn.setReadTimeout(45000);
                    cn.setRequestProperty("User-Agent", ua);
                    cn.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                    int code = cn.getResponseCode();
                    reqCode = code;
                    if (code == 401 || code == 403 || code == 410 || code >= 500) {
                        failure = "HTTP " + code;
                    } else if (code < 200 || code >= 400) {
                        throw new Exception("HTTP " + code);
                    } else {
                        InputStream in = cn.getInputStream();
                        byte[] buf = new byte[65536];
                        try {
                            int read;
                            while (!task.stop && (read = in.read(buf)) > 0) {
                                raf.seek(pos + got);
                                raf.write(buf, 0, read);
                                got += read;
                                done.addAndGet(read);
                            }
                        } finally {
                            try { in.close(); } catch (Exception ignored) { }
                        }
                    }
                } catch (java.io.IOException e) {
                    failure = Lang.t("নেট থেমে গেছে");
                } finally {
                    if (cn != null) cn.disconnect();
                }
                if (task.stop) throw new Exception("stop");
                Trace.w("dl", (video ? "video" : "audio") + " itag=" + itagOf(url)
                        + " " + pos + "-" + end + " code=" + reqCode + " got=" + got
                        + " " + (System.currentTimeMillis() - reqStart) + "ms"
                        + (failure == null ? "" : " " + failure));

                if (got > 0) {
                    // যা এসেছে তা ফাইলে বসে গেছে, তাই এখান থেকেই পরে চালানো হবে
                    pos += got;
                    stalls = 0;
                    refusals = 0;
                    noProgress = 0;
                    if (failure == null) continue;
                }
                if (failure != null && failure.startsWith("HTTP")) {
                    noProgress++;
                    if (noProgress >= 10) throw new Exception(failure);
                    if (refusals >= 6) throw new Exception(failure);
                    refusals++;
                    switchLink(id, video, urlRef, uaRef, pageTried, linkLock,
                            attempt, act, task);
                    sleep(1200L * refusals);
                } else {
                    noProgress++;
                    if (noProgress >= 14) throw new Exception(failure == null
                            ? Lang.t("নেট থেমে গেছে") : failure);
                    if (stalls >= 12) throw new Exception(failure == null
                            ? Lang.t("নেট থেমে গেছে") : failure);
                    stalls++;
                    if (stalls == 3) {
                        switchLink(id, video, urlRef, uaRef, pageTried, linkLock,
                                attempt, act, task);
                    }
                    sleep(1500);
                }
            }
        } finally {
            try { raf.close(); } catch (Exception ignored) { }
        }
    }

    /** নতুন লিংক: আগে পেজের টোকেনওয়ালা (একই itag), নাহলে অন্য ক্লায়েন্ট */
    private static void switchLink(String id, boolean video, AtomicReference<String> urlRef,
                                   AtomicReference<String> uaRef, AtomicBoolean pageTried,
                                   Object lock, AtomicLong attempt, MainActivity act,
                                   Downloader.Task task) {
        synchronized (lock) {
            String cur = urlRef.get();
            int itag = itagOf(cur);
            String[] good = Tube.goodLink(id, itag);
            if (good != null && !good[0].equals(cur)) {
                Trace.w("dl", "scanned link for itag " + itag + ": " + host(good[0]));
                urlRef.set(good[0]);
                uaRef.set(good[1]);
                return;
            }
            if (itag > 0 && !pageTried.getAndSet(true)) {
                // ডাউনলোড থেমে থাকলেও টোকেনের জন্য একবার অপেক্ষা করা হয়
                task.phase = Lang.t("টোকেন আনা হচ্ছে");
                Notify.tick(act, task);
                String alt = Pot.waitLink(act, id, itag, 30000);
                Trace.w("dl", "token link for itag " + itag + ": "
                        + (alt == null ? "none" : host(alt)));
                if (alt != null) {
                    urlRef.set(alt);
                    uaRef.set(Pot.ua());
                    return;
                }
            }
            String next = linkFor(id, itag, video, (int) attempt.getAndIncrement());
            Trace.w("dl", "switch itag " + itag + " -> "
                    + (next == null ? "none" : host(next)));
            if (next != null && next.length() > 0) {
                urlRef.set(next);
                uaRef.set(uaFor(next));
            }
        }
    }

    /** একই itag-এর লিংক অন্য ক্লায়েন্ট থেকে; না পেলে null */
    private static String linkFor(String id, int itag, boolean video, int attempt) {
        if (id == null || id.length() != 11 || itag <= 0) return null;
        try {
            for (String[] r : Tube.resolveClient(id, Math.abs(attempt) % 4)) {
                String u = video ? r[0] : (r[3] == null ? "" : r[3]);
                if (u != null && u.length() > 0 && itagOf(u) == itag) return u;
            }
            for (String[] r : Tube.resolve(id)) {
                String u = video ? r[0] : (r[3] == null ? "" : r[3]);
                if (u != null && u.length() > 0 && itagOf(u) == itag) return u;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** লগে ছোট নাম */
    static String host(String url) {
        if (url == null) return "-";
        try {
            String h = new URL(url).getHost();
            return h == null ? "-" : h;
        } catch (Exception e) {
            return "-";
        }
    }

    /** ১ বাইট চেয়ে ফাইলের মোট সাইজ জেনে নেয়; লিংক কাটা থাকলে এখানেই ধরা পড়ে */
    private static long probeSize(String url) {
        for (int i = 0; i < 3; i++) {
            HttpURLConnection cn = null;
            try {
                cn = (HttpURLConnection) new URL(url).openConnection();
                cn.setInstanceFollowRedirects(true);
                cn.setConnectTimeout(15000);
                cn.setReadTimeout(25000);
                cn.setRequestProperty("User-Agent", uaFor(url));
                cn.setRequestProperty("Range", "bytes=0-0");
                int code = cn.getResponseCode();
                if (code == 401 || code == 403 || code == 410) return 0;
                Trace.w("dl", "probe " + host(url) + " itag=" + itagOf(url)
                        + " code=" + code + " cr=" + cn.getHeaderField("Content-Range"));
                long n = totalOf(cn.getHeaderField("Content-Range"));
                if (n <= 0) {
                    String len = cn.getHeaderField("Content-Length");
                    if (len != null) {
                        try { n = Long.parseLong(len.trim()); } catch (Exception ignored) { }
                    }
                }
                return n;
            } catch (Exception e) {
                sleep(1000L * (i + 1));
            } finally {
                if (cn != null) cn.disconnect();
            }
        }
        return 0;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) { }
    }

    private static String reason(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.length() == 0 ? Lang.t("কারণ পাওয়া যায়নি") : m;
    }

    /** লিংকের ভেতরের itag; না পেলে ০ */
    private static int itagOf(String url) {
        if (url == null) return 0;
        try {
            int i = url.indexOf("itag=");
            if (i < 0) return 0;
            i += 5;
            int j = i;
            while (j < url.length() && Character.isDigit(url.charAt(j))) j++;
            return j > i ? Integer.parseInt(url.substring(i, j)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** কোন ক্লায়েন্টের লিংক, তার User-Agent */
    private static String uaFor(String url) {
        if (url == null) return UA;
        if (url.contains("c=ANDROID_VR")) return VR_UA;
        if (url.contains("c=IOS")) return IOS_UA;
        return UA;
    }

    /** "bytes 0-99/1000" থেকে মোট সাইজ */
    private static long totalOf(String contentRange) {
        if (contentRange == null) return 0;
        int slash = contentRange.indexOf('/');
        if (slash < 0) return 0;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** "8.9 MB" লেখা থেকে বাইট */
    static long sizeOf(String text) {
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

    /** ছবির ফাইল আর আওয়াজের ফাইল একটাই mp4-তে বসায় */
    private static void join(File video, File audio, File out) throws Exception {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vTrack = track(ve, "video/");
            int aTrack = track(ae, "audio/");
            if (vTrack < 0) throw new Exception("video track");
            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = mux.addTrack(ve.getTrackFormat(vTrack));
            int aOut = aTrack >= 0 ? mux.addTrack(ae.getTrackFormat(aTrack)) : -1;
            mux.start();
            copy(ve, vTrack, mux, vOut);
            if (aOut >= 0) copy(ae, aTrack, mux, aOut);
            mux.stop();
        } finally {
            try { if (mux != null) mux.release(); } catch (Exception ignored) { }
            try { ve.release(); } catch (Exception ignored) { }
            try { ae.release(); } catch (Exception ignored) { }
        }
    }

    private static int track(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copy(MediaExtractor ex, int track, MediaMuxer mux, int outTrack) {
        ex.selectTrack(track);
        ByteBuffer buf = ByteBuffer.allocateDirect(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    private static void toast(final MainActivity act, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) { }
            }
        });
    }
}
