package com.skyble.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * অ্যাপের নিজের ভিডিও প্লেয়ার।
 *
 * ইঞ্জিন ExoPlayer (Media3); না চললে ফোনের MediaPlayer-এ ফিরে যায়।
 * বাঁ অর্ধেকে উপরে-নিচে সোয়াইপ = উজ্জ্বলতা, ডান অর্ধেকে = ভলিউম,
 * আড়াআড়ি সোয়াইপ = সামনে/পেছনে। নিচে সিকবার, গতি, অনুপাত, অডিও ট্র্যাক,
 * মিউট আর হার্ডওয়্যার/সফটওয়্যার ডিকোডার বদলানোর বোতাম।
 */
public class PlayerActivity extends Activity implements View.OnTouchListener {

    private static final int ASPECT_FIT = 0;
    private static final int ASPECT_FILL = 1;
    private static final int ASPECT_STRETCH = 2;

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private static final int MODE_NONE = 0;
    private static final int MODE_BRIGHT = 1;
    private static final int MODE_VOLUME = 2;
    private static final int MODE_SEEK = 3;

    private ExoPlayer player;
    private TextureView video;
    private VideoView legacy;
    private MediaPlayer legacyMp;

    private FrameLayout rootView;
    private ProgressBar spinner;
    private TextView hud;
    private TextView titleView;
    private TextView curTime;
    private TextView durTime;
    private TextView speedChip;
    private TextView decodeChip;
    private TextView aspectChip;
    private ImageView playPause;
    private ImageView muteBtn;
    private ImageView audioBtn;
    private SeekBar bar;
    private View topBar;
    private View bottomBar;
    private View centerRow;

    private AudioManager audio;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String mediaUrl;
    private String mediaTitle = "";
    private int videoW;
    private int videoH;
    private int aspectMode = ASPECT_FIT;
    private boolean softwareDecode = false;
    private int speedIndex = 2;
    private boolean muted = false;
    private boolean controlsOn = true;
    private boolean seeking = false;
    private boolean triedLegacy = false;
    private boolean ended = false;
    private long holdUntil = 0L;

    private float downX;
    private float downY;
    private float startY;
    private int mode = MODE_NONE;
    private float startBrightness = -1f;
    private int startVolume;
    private int maxVolume = 15;
    private long seekStartPos;
    private long seekTarget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.init(getSharedPreferences("skyble_browser", 0));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        mediaUrl = getIntent().getStringExtra("url");
        if (mediaUrl == null || mediaUrl.length() == 0) {
            finish();
            return;
        }
        String t = getIntent().getStringExtra("title");
        if (t != null) mediaTitle = t.trim();

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            maxVolume = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }

        buildUi();
        startEngine();
        ui.post(tick);
        showControls();
    }

    // ------------------------------------------------------------------ ui

    private void buildUi() {
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(0xFF000000);
        rootView.setClipChildren(true);
        rootView.setOnTouchListener(this);

        video = new TextureView(this);
        rootView.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // উপরে-নিচে অন্ধকার গ্রেডিয়েন্ট, কন্ট্রোল পড়তে সুবিধা
        View topScrim = new View(this);
        topScrim.setBackground(scrim(0xCC000000, 0x00000000));
        FrameLayout.LayoutParams tsp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
        tsp.gravity = Gravity.TOP;
        rootView.addView(topScrim, tsp);

        View bottomScrim = new View(this);
        bottomScrim.setBackground(scrim(0x00000000, 0xE6000000));
        FrameLayout.LayoutParams bsp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        bsp.gravity = Gravity.BOTTOM;
        rootView.addView(bottomScrim, bsp);

        topBar = buildTopBar();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        tlp.topMargin = dp(6);
        rootView.addView(topBar, tlp);

        centerRow = buildCenter();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        rootView.addView(centerRow, clp);

        bottomBar = buildBottom();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.bottomMargin = dp(10);
        rootView.addView(bottomBar, blp);

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(40), dp(40));
        sp.gravity = Gravity.CENTER;
        rootView.addView(spinner, sp);

        hud = new TextView(this);
        hud.setTextColor(0xFFFFFFFF);
        hud.setTextSize(14);
        hud.setTypeface(Typeface.DEFAULT_BOLD);
        hud.setGravity(Gravity.CENTER);
        hud.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xCC1A1A1A);
        hb.setCornerRadius(dp(18));
        hud.setBackground(hb);
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.CENTER;
        rootView.addView(hud, hp);

        setContentView(rootView);

        // লেআউট তৈরি/ঘোরানোর পরে ভিডিওর অনুপাত আবার বসে
        rootView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) applyAspect();
            }
        });
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));

        ImageView back = iconButton(R.drawable.ic_back, 40);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        titleView = new TextView(this);
        titleView.setText(mediaTitle.length() > 0 ? mediaTitle : Lang.t("ভিডিও"));
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleView.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        decodeChip = chip(softwareDecode ? "SW" : "HW");
        decodeChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                softwareDecode = !softwareDecode;
                decodeChip.setText(softwareDecode ? "SW" : "HW");
                rebuildEngine();
                toast(softwareDecode
                        ? Lang.t("সফটওয়্যার ডিকোডার")
                        : Lang.t("হার্ডওয়্যার ডিকোডার"));
            }
        });
        bar.addView(decodeChip);

        return bar;
    }

    private View buildCenter() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        ImageView rew = iconButton(R.drawable.ic_rew, 46);
        rew.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { bump(-10000); }
        });
        row.addView(rew);

        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(dp(66), dp(66));
        mid.leftMargin = dp(26);
        mid.rightMargin = dp(26);
        playPause = iconButton(R.drawable.ic_pause, 66);
        playPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        row.addView(playPause, mid);

        ImageView fwd = iconButton(R.drawable.ic_fwd, 46);
        fwd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { bump(10000); }
        });
        row.addView(fwd);

        return row;
    }

    private View buildBottom() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(14), 0);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);

        curTime = new TextView(this);
        curTime.setTextColor(0xFFFFFFFF);
        curTime.setTextSize(12);
        curTime.setText("0:00");
        times.addView(curTime);

        bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF34D399));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x59FFFFFF));
        bar.setThumbTintList(ColorStateList.valueOf(0xFF34D399));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(10);
        slp.rightMargin = dp(10);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                long d = duration();
                if (d > 0) curTime.setText(fmt((long) progress * 10L));
            }

            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
                ui.removeCallbacks(hideControls);
            }

            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                seekTo((long) sb.getProgress() * 10L);
                showControls();
            }
        });
        times.addView(bar, slp);

        durTime = new TextView(this);
        durTime.setTextColor(0xCCFFFFFF);
        durTime.setTextSize(12);
        durTime.setText("0:00");
        times.addView(durTime);
        col.addView(times);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);

        muteBtn = iconButton(R.drawable.ic_volume, 40);
        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        row.addView(muteBtn);

        audioBtn = iconButton(R.drawable.ic_audio, 40);
        audioBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAudioTracks(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(40), dp(40));
        alp.leftMargin = dp(6);
        row.addView(audioBtn, alp);

        aspectChip = chip(Lang.t("ফিট"));
        aspectChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleAspect(); }
        });
        LinearLayout.LayoutParams asp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        asp.leftMargin = dp(10);
        row.addView(aspectChip, asp);

        speedChip = chip("1.0x");
        speedChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleSpeed(); }
        });
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        scp.leftMargin = dp(8);
        row.addView(speedChip, scp);

        View filler = new View(this);
        row.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));

        col.addView(row);
        return col;
    }

    private ImageView iconButton(int drawableId, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawableId);
        v.setColorFilter(0xFFFFFFFF);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(sizeDp >= 60 ? 18 : 10);
        v.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(sizeDp >= 60 ? 0x40FFFFFF : 0x26FFFFFF);
        v.setBackground(bg);
        v.setClickable(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(17));
        t.setBackground(bg);
        t.setClickable(true);
        t.setMinWidth(dp(46));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        return t;
    }

    private GradientDrawable scrim(int from, int to) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{from, to});
        return g;
    }

    // ------------------------------------------------------------------ engines

    private void startEngine() {
        boolean ok = false;
        try {
            startExo();
            ok = true;
        } catch (Throwable t) {
            releasePlayer();
            ok = false;
        }
        if (!ok) startLegacy();
    }

    private void startExo() {
        HashMap<String, String> hdr = headers();
        String agent = hdr.get("User-Agent");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(hdr);
        if (agent != null && agent.length() > 0) http.setUserAgent(agent);
        DataSource.Factory ds = new DefaultDataSource.Factory(this, http);

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(decoderSelector());

        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
                .build();
        player.setVideoTextureView(video);
        player.addListener(new Player.Listener() {
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    spinner.setVisibility(View.GONE);
                    ended = false;
                    updateTime();
                } else if (state == Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    ended = true;
                    showControls();
                }
            }

            public void onIsPlayingChanged(boolean playing) {
                if (playPause != null) {
                    playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                }
                if (playing) {
                    spinner.setVisibility(View.GONE);
                    ui.removeCallbacks(tick);
                    ui.post(tick);
                }
            }

            public void onVideoSizeChanged(VideoSize size) {
                videoW = size.width;
                videoH = size.height;
                applyAspect();
            }

            public void onTracksChanged(Tracks tracks) {
                if (audioBtn != null) {
                    boolean hasAudio = false;
                    for (Tracks.Group g : tracks.getGroups()) {
                        if (g.getType() == C.TRACK_TYPE_AUDIO && g.length > 0) hasAudio = true;
                    }
                    audioBtn.setAlpha(hasAudio ? 1f : 0.35f);
                }
            }

            public void onPlayerError(PlaybackException e) {
                spinner.setVisibility(View.GONE);
                if (fallbackToLegacy()) return;
                String code = "";
                try {
                    code = " (" + e.getErrorCodeName() + ")";
                } catch (Throwable ignored) { }
                Toast.makeText(PlayerActivity.this,
                        Lang.t("এই ভিডিওটা চালানো যাচ্ছে না") + code,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        });
        player.setMediaItem(MediaItem.fromUri(mediaUrl));
        player.setVolume(muted ? 0f : 1f);
        player.setPlaybackSpeed(SPEEDS[speedIndex]);
        player.setPlayWhenReady(true);
        player.prepare();
        spinner.setVisibility(View.VISIBLE);
    }

    private MediaCodecSelector decoderSelector() {
        if (!softwareDecode) return MediaCodecSelector.DEFAULT;
        return new MediaCodecSelector() {
            public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean secure,
                                                        boolean tunneling)
                    throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all =
                        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling);
                List<MediaCodecInfo> soft = new ArrayList<>();
                for (MediaCodecInfo i : all) {
                    if (!i.hardwareAccelerated) soft.add(i);
                }
                return soft.isEmpty() ? all : soft;
            }
        };
    }

    private void startLegacy() {
        if (video != null) video.setVisibility(View.GONE);
        legacy = new VideoView(this);
        rootView.addView(legacy, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        legacy.setOnTouchListener(this);
        legacy.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                legacyMp = mp;
                spinner.setVisibility(View.GONE);
                try { mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Exception ignored) { }
                legacy.start();
                updateTime();
                ui.removeCallbacks(tick);
                ui.post(tick);
            }
        });
        legacy.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                ended = true;
                showControls();
            }
        });
        legacy.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                spinner.setVisibility(View.GONE);
                Toast.makeText(PlayerActivity.this,
                        Lang.t("এই ভিডিওটা চালানো যাচ্ছে না") + " (" + what + "/" + extra + ")",
                        Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
        });
        boolean withHeaders = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                legacy.setVideoURI(Uri.parse(mediaUrl), headers());
                withHeaders = true;
            } catch (Throwable ignored) { }
        }
        if (!withHeaders) legacy.setVideoURI(Uri.parse(mediaUrl));
        legacy.requestFocus();
        spinner.setVisibility(View.VISIBLE);
    }

    /** ExoPlayer-এ চলল না — ফোনের নিজের MediaPlayer-এ একবার চেষ্টা */
    private boolean fallbackToLegacy() {
        if (triedLegacy || rootView == null || mediaUrl == null) return false;
        triedLegacy = true;
        long pos = position();
        releasePlayer();
        try {
            startLegacy();
            if (pos > 0 && legacy != null) legacy.seekTo((int) pos);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void releasePlayer() {
        try {
            if (player != null) player.release();
        } catch (Throwable ignored) { }
        player = null;
        try {
            if (legacy != null) legacy.stopPlayback();
        } catch (Throwable ignored) { }
        if (legacy != null && rootView != null) rootView.removeView(legacy);
        legacy = null;
        legacyMp = null;
    }

    /** HW/SW বদলালে একই জায়গা থেকে নতুন করে চালু হয় */
    private void rebuildEngine() {
        long pos = position();
        boolean wasPlaying = playing() || !ended;
        releasePlayer();
        if (video != null) video.setVisibility(View.VISIBLE);
        ui.removeCallbacks(tick);
        try {
            startExo();
            if (player != null && pos > 0) player.seekTo(pos);
        } catch (Throwable t) {
            releasePlayer();
            startLegacy();
            return;
        }
        if (player != null) player.setPlayWhenReady(wasPlaying);
    }

    private HashMap<String, String> headers() {
        HashMap<String, String> h = new HashMap<>();
        String cookie = getIntent().getStringExtra("cookie");
        String referer = getIntent().getStringExtra("referer");
        String agent = getIntent().getStringExtra("agent");
        if (cookie != null && cookie.length() > 0) h.put("Cookie", cookie);
        if (referer != null && referer.length() > 0) h.put("Referer", referer);
        if (agent != null && agent.length() > 0) h.put("User-Agent", agent);
        return h;
    }

    // ------------------------------------------------------------------ playback

    private boolean playing() {
        try {
            if (player != null) return player.isPlaying();
            return legacy != null && legacy.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private long duration() {
        try {
            long d = player != null ? player.getDuration() : (legacy == null ? 0 : legacy.getDuration());
            return d < 0 ? 0 : d;
        } catch (Exception e) {
            return 0;
        }
    }

    private long position() {
        try {
            if (player != null) return player.getCurrentPosition();
            return legacy == null ? 0 : legacy.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private long buffered() {
        try {
            if (player != null) return player.getBufferedPosition();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void seekTo(long ms) {
        try {
            if (player != null) player.seekTo(ms);
            else if (legacy != null) legacy.seekTo((int) ms);
        } catch (Exception ignored) { }
        // সিকবার সাথে সাথে নতুন জায়গায় বসে, তারপর ঘড়ি কিছুক্ষণ ওই মানেই থাকে
        holdUntil = System.currentTimeMillis() + 700;
        if (bar != null) {
            long d = duration();
            if (d > 0) {
                int max = (int) Math.min(2000000000L, Math.max(1, d / 10));
                if (bar.getMax() != max) bar.setMax(max);
                bar.setProgress((int) Math.min(max, ms / 10));
            }
        }
        if (curTime != null) curTime.setText(fmt(ms));
    }

    private void bump(long delta) {
        long d = duration();
        long p = Math.max(0, position() + delta);
        if (d > 0 && p > d) p = d;
        seekTo(p);
        showControls();
        showHud((delta > 0 ? "⏩ +" : "⏪ -") + Math.abs(delta) / 1000 + "s");
    }

    private void toggle() {
        try {
            if (player != null) {
                if (ended) {
                    player.seekTo(0);
                    player.play();
                    ended = false;
                } else if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
            } else if (legacy != null) {
                if (legacy.isPlaying()) legacy.pause();
                else legacy.start();
            }
        } catch (Exception ignored) { }
        showControls();
    }

    private void toggleMute() {
        muted = !muted;
        try {
            if (player != null) player.setVolume(muted ? 0f : 1f);
            else if (legacyMp != null) legacyMp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        } catch (Exception ignored) { }
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        showHud(muted ? Lang.t("মিউট") : Lang.t("আওয়াজ চালু"));
        showControls();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float s = SPEEDS[speedIndex];
        try {
            if (player != null) player.setPlaybackSpeed(s);
            else if (legacyMp != null && Build.VERSION.SDK_INT >= 23) {
                android.media.PlaybackParams pp = legacyMp.getPlaybackParams();
                pp.setSpeed(s);
                legacyMp.setPlaybackParams(pp);
            }
        } catch (Exception ignored) { }
        speedChip.setText(s == Math.round(s)
                ? String.valueOf((int) s) + "x"
                : String.valueOf(s) + "x");
        showHud(Lang.t("গতি") + " " + speedChip.getText());
        showControls();
    }

    private void cycleAspect() {
        aspectMode = (aspectMode + 1) % 3;
        String name;
        if (aspectMode == ASPECT_FIT) name = Lang.t("ফিট");
        else if (aspectMode == ASPECT_FILL) name = Lang.t("ভরাট");
        else name = Lang.t("টান");
        aspectChip.setText(name);
        applyAspect();
        showHud(name);
        showControls();
    }

    private void applyAspect() {
        if (video == null || rootView == null) return;
        int cw = rootView.getWidth();
        int ch = rootView.getHeight();
        if (cw <= 0 || ch <= 0) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        lp.gravity = Gravity.CENTER;

        if (videoW <= 0 || videoH <= 0 || aspectMode == ASPECT_STRETCH) {
            lp.width = cw;
            lp.height = ch;
            video.setLayoutParams(lp);
            return;
        }

        float vr = (float) videoW / (float) videoH;
        float cr = (float) cw / (float) ch;
        int w;
        int h;
        if (aspectMode == ASPECT_FILL) {
            // পুরো স্ক্রিন ঢাকতে বড় করে, বাড়তি অংশ বাবা-ভিউ কেটে দেয়
            if (vr >= cr) {
                h = ch;
                w = Math.max(cw, Math.round(ch * vr));
            } else {
                w = cw;
                h = Math.max(ch, Math.round(cw / vr));
            }
        } else {
            if (vr >= cr) {
                w = cw;
                h = Math.max(1, Math.round(cw / vr));
            } else {
                h = ch;
                w = Math.max(1, Math.round(ch * vr));
            }
        }
        lp.width = w;
        lp.height = h;
        video.setLayoutParams(lp);
    }

    private void showAudioTracks() {
        if (player == null) {
            toast(Lang.t("অডিও ট্র্যাক নেই"));
            return;
        }
        Tracks tracks = player.getCurrentTracks();
        final List<Object[]> refs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add(Lang.t("অটো"));
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                String label = f.label;
                String lang = f.language;
                String name;
                if (label != null && label.length() > 0) name = label;
                else if (lang != null && lang.length() > 0) name = lang;
                else name = Lang.t("অডিও");
                if (!g.isTrackSupported(i)) name = name + " (" + Lang.t("চলবে না") + ")";
                names.add(name);
                refs.add(new Object[]{g, i});
            }
        }
        if (refs.isEmpty()) {
            toast(Lang.t("অডিও ট্র্যাক নেই"));
            return;
        }
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(Lang.t("অডিও ট্র্যাক"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (player == null) return;
                        if (which == 0) {
                            player.setTrackSelectionParameters(
                                    player.getTrackSelectionParameters().buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                            .build());
                        } else {
                            Object[] r = refs.get(which - 1);
                            Tracks.Group g = (Tracks.Group) r[0];
                            int idx = (Integer) r[1];
                            player.setTrackSelectionParameters(
                                    player.getTrackSelectionParameters().buildUpon()
                                            .setOverrideForType(new TrackSelectionOverride(
                                                    g.getMediaTrackGroup(), idx))
                                            .build());
                        }
                        showControls();
                    }
                })
                .setNegativeButton(Lang.t("বাতিল"), null)
                .show();
    }

    // ------------------------------------------------------------------ clock

    private final Runnable tick = new Runnable() {
        public void run() {
            updateTime();
            if (playing()) ui.postDelayed(this, 300);
            else if (controlsOn) ui.postDelayed(this, 600);
        }
    };

    private void updateTime() {
        if (bar == null || curTime == null || durTime == null) return;
        long d = duration();
        long p = position();
        if (!seeking && System.currentTimeMillis() >= holdUntil) {
            if (d > 0) {
                int max = (int) Math.min(2000000000L, Math.max(1, d / 10));
                if (bar.getMax() != max) bar.setMax(max);
                int prog = (int) Math.min(max, Math.max(0, p / 10));
                bar.setProgress(prog);
                int sec = (int) Math.min(max, Math.max(prog, buffered() / 10));
                bar.setSecondaryProgress(sec);
                bar.setEnabled(true);
                curTime.setText(fmt(p));
            } else {
                bar.setEnabled(false);
            }
        }
        durTime.setText(d > 0 ? fmt(d) : "--:--");
        if (playPause != null) {
            playPause.setImageResource(playing() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long m = s / 60;
        long h = m / 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    // ------------------------------------------------------------------ controls

    private void showControls() {
        if (bottomBar == null) return;
        controlsOn = true;
        fade(topBar, 1f);
        fade(bottomBar, 1f);
        fade(centerRow, 1f);
        ui.removeCallbacks(hideControls);
        ui.removeCallbacks(tick);
        ui.post(tick);
        if (playing()) ui.postDelayed(hideControls, 3500);
    }

    private void fade(View v, float a) {
        if (v == null) return;
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(a).setDuration(160).start();
    }

    private final Runnable hideControls = new Runnable() {
        public void run() {
            if (!playing() || seeking) return;
            controlsOn = false;
            fade(topBar, 0f);
            fade(bottomBar, 0f);
            fade(centerRow, 0f);
            topBar.postDelayed(new Runnable() {
                public void run() {
                    if (!controlsOn) {
                        topBar.setVisibility(View.GONE);
                        bottomBar.setVisibility(View.GONE);
                        centerRow.setVisibility(View.GONE);
                    }
                }
            }, 180);
        }
    };

    private final Runnable hideHud = new Runnable() {
        public void run() {
            if (hud != null) hud.setVisibility(View.GONE);
        }
    };

    private void showHud(String text) {
        if (hud == null) return;
        hud.removeCallbacks(hideHud);
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        hud.postDelayed(hideHud, 900);
    }

    // ------------------------------------------------------------------ gestures

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                startY = e.getY();
                mode = MODE_NONE;
                startBrightness = currentBrightness();
                startVolume = audio == null ? 0
                        : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                seekStartPos = position();
                seekTarget = seekStartPos;
                ui.removeCallbacks(hideControls);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (mode == MODE_NONE) {
                    if (Math.abs(dx) > dp(18) && Math.abs(dx) > Math.abs(dy)) mode = MODE_SEEK;
                    else if (Math.abs(dy) > dp(18)) {
                        mode = e.getX() < rootView.getWidth() / 2f ? MODE_BRIGHT : MODE_VOLUME;
                    }
                }
                if (mode == MODE_SEEK) {
                    long d = duration();
                    if (d <= 0) return true;
                    float span = Math.max(1f, rootView.getWidth() * 0.75f);
                    long delta = (long) (dx / span * d);
                    seekTarget = Math.max(0, Math.min(d, seekStartPos + delta));
                    showHud(fmt(seekTarget) + " / " + fmt(d));
                } else if (mode == MODE_BRIGHT) {
                    float span = Math.max(1f, rootView.getHeight() * 0.6f);
                    float frac = (startY - e.getY()) / span;
                    float b = Math.max(0.02f, Math.min(1f, startBrightness + frac));
                    setBrightness(b);
                    showHud("☀ " + Math.round(b * 100) + "%");
                } else if (mode == MODE_VOLUME) {
                    float span = Math.max(1f, rootView.getHeight() * 0.6f);
                    float frac = (startY - e.getY()) / span;
                    int vol = Math.round(startVolume + frac * maxVolume);
                    vol = Math.max(0, Math.min(maxVolume, vol));
                    if (audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                    showHud("🔊 " + Math.round(vol * 100f / maxVolume) + "%");
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == MODE_SEEK) {
                    seekTo(seekTarget);
                    showControls();
                } else if (mode == MODE_NONE) {
                    // ট্যাপ: কন্ট্রোল দেখানো/লুকানো
                    if (controlsOn) {
                        ui.removeCallbacks(hideControls);
                        hideControls.run();
                    } else {
                        showControls();
                    }
                }
                if (mode != MODE_NONE) showControls();
                mode = MODE_NONE;
                return true;
        }
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void immersive() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b < 0) {
            try {
                b = Settings.System.getInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Exception e) {
                b = 0.5f;
            }
        }
        return Math.max(0.02f, Math.min(1f, b));
    }

    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    // ------------------------------------------------------------------ life

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (rootView != null) {
            rootView.post(new Runnable() {
                public void run() { applyAspect(); }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (player != null) player.pause();
            if (legacy != null && legacy.isPlaying()) legacy.pause();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        applyAspect();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (controlsOn) {
            ui.removeCallbacks(hideControls);
            hideControls.run();
            return;
        }
        super.onBackPressed();
    }
}
