package com.skyble.tube.plugin;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skyble Tube: takes a YouTube link from the browser (or any app), asks a public
 * front-end for ready to download files and hands the chosen one to DownloadManager.
 */
public class MainActivity extends Activity {

    private static final String ACTION_DOWNLOAD = "com.skyble.tube.plugin.DOWNLOAD";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int REQ_WRITE = 11;
    private static final Pattern LINK = Pattern.compile("https?://\\S+");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Long> ids = new ArrayList<>();
    // নাম আগেই থাকলে DownloadManager ব্যর্থ হয়; তখন নম্বর জুড়ে আবার চেষ্টা করা হয়
    private final java.util.HashMap<Long, String[]> queueInfo = new java.util.HashMap<>();
    private final java.util.HashMap<Long, Integer> attempts = new java.util.HashMap<>();
    private final List<String[]> found = new ArrayList<>();
    // ব্রাউজারকে একবারই খবর দেওয়া হয়, প্রতি পোলে নয়
    private final java.util.HashSet<Long> told = new java.util.HashSet<>();

    private EditText linkBox;
    private Button searchButton;
    private TextView statusView;
    private LinearLayout resultBox;
    private LinearLayout downloadBox;
    private TextView downloadHeader;

    private int searchToken = 0;
    private String videoTitle = "";
    /** শেষ খোঁজায় পাওয়া ভিডিওর আইডি, নতুন লিংকের জন্য দরকার হয় */
    private String curVideoId = "";

    // held while the storage permission dialog is open
    private String pendingUrl, pendingQuality, pendingTitle, pendingAudio;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshDownloads();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        handleIntent(getIntent(), true);
        askWritePermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ids.isEmpty()) ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    // ---------- incoming link ----------

    private void handleIntent(Intent intent, boolean autoSearch) {
        if (intent == null) return;
        String raw = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            raw = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (raw == null) raw = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) raw = intent.getData().toString();
        } else if (ACTION_DOWNLOAD.equals(action)) {
            raw = intent.getStringExtra("url");
            if (raw == null) raw = intent.getStringExtra("link");
        }
        String text = firstLink(raw);
        if (text == null || text.length() == 0) return;
        linkBox.setText(text);
        if (autoSearch) search();
    }

    /** the link inside shared text, or the text itself when it is already a link */
    private static String firstLink(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m = LINK.matcher(s);
        if (m.find()) return m.group();
        return s;
    }

    // ---------- search ----------

    private void search() {
        final String raw = linkBox.getText().toString().trim();
        final String id = Tube.videoId(firstLink(raw));
        if (id == null) {
            status("No YouTube link found", true);
            return;
        }
        status("Looking up…", false);
        resultBox.removeAllViews();
        found.clear();
        searchButton.setEnabled(false);
        final int token = ++searchToken;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // NewPipe first (real YouTube API); the old resolver is the fallback
                List<String[]> resolved = null;
                String resolvedTitle = "";
                try {
                    resolved = Npe.resolve(id);
                    if (resolved != null && !resolved.isEmpty()) resolvedTitle = Npe.lastTitle;
                } catch (Throwable ignored) {
                    resolved = null;
                }
                if (resolved == null || resolved.isEmpty()) {
                    resolved = Tube.resolve(id);
                    resolvedTitle = Tube.lastTitle;
                }
                final List<String[]> list = resolved;
                final String title = resolvedTitle;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != searchToken || isFinishing()) return;
                        searchButton.setEnabled(true);
                        videoTitle = title == null ? "" : title;
                        curVideoId = id;
                        if (list.isEmpty()) {
                            String why = Tube.friendlyError();
                            status("This video cannot be downloaded right now"
                                    + (why.length() == 0 ? "" : " · " + why), true);
                            return;
                        }
                        found.addAll(list);
                        if (videoTitle.length() > 0) status(videoTitle, false);
                        else status("Pick a quality", false);
                        for (String[] row : found) addResultRow(row);
                    }
                });
            }
        }).start();
    }

    private void addResultRow(final String[] row) {
        String label = row[1];
        if (row[2] != null && row[2].length() > 0) label = label + "  ·  " + row[2];
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(Color.parseColor("#1A1A1A"));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(rowBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload(row[0], row[1], videoTitle,
                        row.length > 3 ? row[3] : "");
            }
        });
        resultBox.addView(tv);
    }

    // ---------- download ----------

    private void startDownload(String url, String quality, String title, String audioUrl) {
        if (!hasWritePermission()) {
            pendingUrl = url;
            pendingQuality = quality;
            pendingTitle = title;
            pendingAudio = audioUrl;
            status("Allow storage access, then tap again", true);
            requestWritePermission();
            return;
        }
        String base = fileName((title == null || title.length() == 0) ? "skyble-video" : title);
        if (audioUrl != null && audioUrl.length() > 0) {
            startMerged(url, audioUrl, base + ".mp4", quality, title);
            return;
        }
        long id = enqueue(url, quality, title, base + ".mp4", 0);
        if (id <= 0) return;
        ids.add(id);
        downloadHeader.setVisibility(View.VISIBLE);
        status("Download started", false);
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        refreshDownloads();
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1500);
    }

    /** ৭২০p/১০৮০p: ছবি আর আওয়াজ আলাদা নামিয়ে এক mp4-তে জোড়া লাগানো হয় */
    private void startMerged(String videoUrl, String audioUrl, String name,
                            final String quality, final String title) {
        final TextView row = downloadRow((title == null || title.length() == 0
                ? "Skyble Tube" : title) + " · " + quality);
        downloadHeader.setVisibility(View.VISIBLE);
        status("Download started", false);
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        final Handler main = ui;
        Muxer.start(this, curVideoId, quality, videoUrl, audioUrl, name, 0, new Muxer.Listener() {
            @Override
            public void progress(final String text) {
                main.post(new Runnable() {
                    @Override
                    public void run() { row.setText(rowLabel(quality, title) + "\n" + text); }
                });
            }

            @Override
            public void finished(final String text, final boolean failed) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        row.setText(rowLabel(quality, title) + "\n" + text);
                        status(failed ? "Could not join the video and audio tracks" : "Download finished", failed);
                    }
                });
            }
        });
    }

    private String rowLabel(String quality, String title) {
        return (title == null || title.length() == 0 ? "Skyble Tube" : title) + " · " + quality;
    }

    /** ডাউনলোড তালিকায় একটা সারি যোগ করে */
    private TextView downloadRow(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.parseColor("#333333"));
        tv.setPadding(dp(2), dp(6), dp(2), dp(6));
        downloadBox.addView(tv);
        return tv;
    }

    /** DownloadManager-এ পাঠায়; নাম আগেই থাকলে নম্বর জুড়ে নতুন নামে আবার চেষ্টা হয় */
    private long enqueue(String url, String quality, String title, String name, int attempt) {
        String shown = (title == null || title.length() == 0 ? "Skyble Tube" : title);
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                status("Downloader not available", true);
                return -1;
            }
            DownloadManager.Request req = new DownloadManager.Request(android.net.Uri.parse(url));
            req.setTitle(shown + " · " + quality);
            req.setDescription("Skyble Tube · " + quality);
            req.setMimeType("video/mp4");
            req.addRequestHeader("User-Agent", UA);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.allowScanningByMediaScanner();
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            long id = dm.enqueue(req);
            queueInfo.put(id, new String[]{url, quality, title, name});
            attempts.put(id, attempt);
            return id;
        } catch (Exception e) {
            status("Could not start the download: " + shortError(e), true);
            return -1;
        }
    }

    private void refreshDownloads() {
        if (ids.isEmpty()) return;
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        downloadBox.removeAllViews();
        for (Long id : ids) {
            String text = "…";
            String title = "Video";
            try {
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                android.database.Cursor c = dm.query(q);
                if (c != null) {
                    if (c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long done = c.getLong(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(
                                c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        String t = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                        if (t != null && t.length() > 0) title = t;
                        if (st == DownloadManager.STATUS_SUCCESSFUL) {
                            text = "Done · saved in Downloads";
                            if (told.add(id)) {
                                String[] info = queueInfo.get(id);
                                String fname = info != null ? info[3] : title + ".mp4";
                                android.net.Uri u = null;
                                try { u = dm.getUriForDownloadedFile(id); } catch (Exception ignored) { }
                                Bridge.post(MainActivity.this, fname, u, "video/mp4", total);
                            }
                        } else if (st == DownloadManager.STATUS_FAILED) {
                            int reason = c.getInt(
                                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                            text = "Failed";
                            if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) {
                                String[] info = queueInfo.get(id);
                                Integer tried = attempts.get(id);
                                int n = tried == null ? 0 : tried;
                                if (info != null && n < 5) {
                                    String base = info[3].endsWith(".mp4")
                                            ? info[3].substring(0, info[3].length() - 4)
                                            : info[3];
                                    String next = base + " (" + (n + 2) + ").mp4";
                                    long again = enqueue(info[0], info[1], info[2], next, n + 1);
                                    if (again > 0) {
                                        ids.set(ids.indexOf(id), again);
                                        text = "Name existed, restarted with a new name";
                                    }
                                }
                            }
                        } else if (st == DownloadManager.STATUS_PAUSED) {
                            text = "Paused";
                        } else if (total > 0) {
                            text = String.format(Locale.US, "Running %d%% · %s",
                                    (int) (done * 100 / total), Tube.sizeText(done));
                        } else {
                            text = "Running · " + Tube.sizeText(done);
                        }
                    }
                    c.close();
                }
            } catch (Exception e) {
                text = "Status unavailable";
            }
            TextView tv = new TextView(this);
            tv.setText(title + "\n" + text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(Color.parseColor("#333333"));
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            tv.setLayoutParams(lp);
            downloadBox.addView(tv);
        }
    }

    /** only letters, digits, dash, underscore, space in a file name */
    private static String fileName(String title) {
        String s = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        if (s.length() == 0) s = "skyble-video";
        return s;
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) return e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    // ---------- storage permission ----------

    private boolean wantsWritePermission() {
        return Build.VERSION.SDK_INT <= 28;
    }

    private boolean hasWritePermission() {
        if (!wantsWritePermission()) return true;
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askWritePermission() {
        if (wantsWritePermission() && !hasWritePermission()) requestWritePermission();
    }

    private void requestWritePermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_WRITE) return;
        if (!hasWritePermission()) {
            status("Without storage access the file cannot be saved in Downloads", true);
            return;
        }
        if (pendingUrl != null) {
            String u = pendingUrl, q = pendingQuality, t = pendingTitle;
            String a = pendingAudio;
            pendingUrl = null;
            pendingAudio = null;
            startDownload(u, q, t, a);
        }
    }

    // ---------- ui ----------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("Skyble Tube");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(Color.parseColor("#0140CA"));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("YouTube video download");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(14);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        linkBox = new EditText(this);
        linkBox.setHint("YouTube link");
        linkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        linkBox.setSingleLine(true);
        linkBox.setBackground(rounded());
        linkBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLp.bottomMargin = dp(10);
        linkBox.setLayoutParams(linkLp);
        root.addView(linkBox);

        searchButton = new Button(this);
        searchButton.setText("Search");
        searchButton.setAllCaps(false);
        searchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    search();
                } catch (Exception e) {
                    status("Something went wrong", true);
                }
            }
        });
        root.addView(searchButton);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(Color.parseColor("#666666"));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(12);
        stLp.bottomMargin = dp(6);
        statusView.setLayoutParams(stLp);
        statusView.setText("Paste a link, then tap Search");
        root.addView(statusView);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultBox);

        downloadHeader = new TextView(this);
        downloadHeader.setText("Download");
        downloadHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        downloadHeader.setTextColor(Color.parseColor("#666666"));
        downloadHeader.setVisibility(View.GONE);
        LinearLayout.LayoutParams dhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dhLp.topMargin = dp(14);
        dhLp.bottomMargin = dp(6);
        downloadHeader.setLayoutParams(dhLp);
        root.addView(downloadHeader);

        downloadBox = new LinearLayout(this);
        downloadBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(downloadBox);

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(root);
        return sc;
    }

    private void status(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(Color.parseColor(error ? "#B00020" : "#1B5E20"));
    }

    private GradientDrawable rounded() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F4F6FA"));
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), Color.parseColor("#D0D7E4"));
        return g;
    }

    private GradientDrawable rowBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F4F6FA"));
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), Color.parseColor("#D0D7E4"));
        return g;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
