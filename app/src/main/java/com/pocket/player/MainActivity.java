package com.pocket.player;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

public class MainActivity extends AppCompatActivity {
    private static final int PINK = 0xFFFB7299;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private AudioManager audio;
    private ActivityResultLauncher<String[]> picker;

    private FrameLayout root;
    private PlayerView playerView;
    private View overlay;
    private LinearLayout top, bottom, empty;
    private TextView titleTv, timeTv, toastTv;
    private SeekBar seek;
    private Button playBtn, speedBtn, zoomBtn, lockBtn;

    private boolean locked = false, dragging = false;
    private float speed = 1f;
    private int zoomIdx = 0;
    private static final int[] ZOOM_MODES = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL};
    private static final String[] ZOOM_NAMES = {"适应", "裁切填满", "拉伸"};

    // gesture state
    private int mode = 0; // 0 none, 1 seek, 2 brightness, 3 volume, 4 long-press 2x
    private float downX, downY, startBright;
    private long startPos, seekTarget, lastTap = 0;
    private int startVol;

    private final Runnable hideUi = () -> setUi(false);
    private final Runnable hideToast = () -> toastTv.setVisibility(View.GONE);
    private final Runnable singleTap = () -> setUi(top.getVisibility() != View.VISIBLE && !(locked && lockBtn.getVisibility() == View.VISIBLE));
    private final Runnable longPress = () -> {
        if (mode == 0 && player.isPlaying()) {
            mode = 4;
            player.setPlaybackSpeed(2f);
            showToast("2x 倍速播放中", true);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long d = player.getDuration();
            if (!dragging && d > 0) {
                long p = player.getCurrentPosition();
                seek.setProgress((int) (p * 1000 / d));
                timeTv.setText(fmt(p) + " / " + fmt(d));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 28) {
            // 让画面延伸到刘海/挖孔区域，避免横屏两侧出现黑边
            WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wl);
        }
        buildUi();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                playBtn.setText(playing ? "⏸" : "▶");
                root.setKeepScreenOn(playing);
                if (playing) setUi(true);
            }
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    // 横屏视频自动转横屏，竖屏视频自动转竖屏，减少黑边
                    setRequestedOrientation(vs.width >= vs.height
                            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            }
            @Override public void onPlayerError(PlaybackException e) {
                showToast("无法播放：" + e.getErrorCodeName(), false);
            }
        });
        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) play(uri);
        });
        handler.post(ticker);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) play(i.getData());
    }

    private void play(Uri uri) {
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.setPlaybackSpeed(speed);
        player.play();
        titleTv.setText(nameOf(uri));
        empty.setVisibility(View.GONE);
        setUi(true);
    }

    private String nameOf(Uri uri) {
        String n = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) n = c.getString(i);
            }
        } catch (Exception ignored) { }
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "视频" : n;
    }

    // ---------- UI ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        return t;
    }

    private void buildUi() {
        ViewGroup.LayoutParams match = new ViewGroup.LayoutParams(-1, -1);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(ZOOM_MODES[0]);
        root.addView(playerView, match);

        overlay = new View(this);
        overlay.setOnTouchListener(this::onGesture);
        root.addView(overlay, match);

        // top bar
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        top.setPadding(dp(8), dp(4), dp(8), dp(4));
        top.addView(btn("📂", v -> picker.launch(new String[]{"video/*"})));
        titleTv = text("未选择视频", 15);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom bar
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(dp(10), dp(6), dp(10), dp(6));

        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        timeTv = text("0:00 / 0:00", 13);
        r1.addView(timeTv);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(PINK));
        seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player.getDuration() > 0)
                    timeTv.setText(fmt(p * player.getDuration() / 1000) + " / " + fmt(player.getDuration()));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; handler.removeCallbacks(hideUi); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (player.getDuration() > 0) player.seekTo(s.getProgress() * player.getDuration() / 1000);
                dragging = false;
                setUi(true);
            }
        });
        r1.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        bottom.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = btn("▶", v -> togglePlay());
        r2.addView(playBtn);
        r2.addView(btn("⏪10", v -> { player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); setUi(true); }));
        r2.addView(btn("10⏩", v -> { player.seekTo(player.getCurrentPosition() + 10000); setUi(true); }));
        r2.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        zoomBtn = btn("画面:适应", v -> {
            zoomIdx = (zoomIdx + 1) % ZOOM_MODES.length;
            playerView.setResizeMode(ZOOM_MODES[zoomIdx]);
            zoomBtn.setText("画面:" + ZOOM_NAMES[zoomIdx]);
            showToast("画面：" + ZOOM_NAMES[zoomIdx], false);
            setUi(true);
        });
        r2.addView(zoomBtn);
        speedBtn = btn("倍速1x", v -> showSpeedMenu());
        r2.addView(speedBtn);
        r2.addView(btn("⛶", v -> toggleFullscreen()));
        bottom.addView(r2);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // lock
        lockBtn = btn("🔓", v -> {
            locked = !locked;
            lockBtn.setText(locked ? "🔒" : "🔓");
            showToast(locked ? "已锁定" : "已解锁", false);
            setUi(true);
        });
        lockBtn.setTextSize(20);
        GradientDrawable lb = new GradientDrawable();
        lb.setShape(GradientDrawable.OVAL);
        lb.setColor(0x99000000);
        lockBtn.setBackground(lb);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL);
        llp.leftMargin = dp(16);
        root.addView(lockBtn, llp);

        // toast
        toastTv = text("", 15);
        toastTv.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(20));
        tb.setColor(0xB3000000);
        toastTv.setBackground(tb);
        toastTv.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = dp(90);
        root.addView(toastTv, tlp);

        // empty state
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setBackgroundColor(0xFF0B0B10);
        TextView h = text("口袋播放器", 24);
        h.setGravity(Gravity.CENTER);
        TextView tip = text("双击暂停 · 左右滑动快进\n左侧上下滑调亮度 · 右侧上下滑调音量\n长按 2 倍速", 14);
        tip.setGravity(Gravity.CENTER);
        tip.setAlpha(.7f);
        tip.setPadding(0, dp(12), 0, dp(20));
        Button pick = btn("选择视频", v -> picker.launch(new String[]{"video/*"}));
        pick.setTextSize(16);
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(dp(24));
        pb.setColor(PINK);
        pick.setBackground(pb);
        pick.setPadding(dp(32), dp(12), dp(32), dp(12));
        empty.addView(h);
        empty.addView(tip);
        empty.addView(pick);
        root.addView(empty, match);

        setContentView(root);
        setUi(true);
    }

    private void setUi(boolean show) {
        handler.removeCallbacks(hideUi);
        top.setVisibility(show && !locked ? View.VISIBLE : View.GONE);
        bottom.setVisibility(show && !locked ? View.VISIBLE : View.GONE);
        lockBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && player != null && player.isPlaying()) handler.postDelayed(hideUi, 4000);
    }

    private void showToast(String s, boolean sticky) {
        toastTv.setText(s);
        toastTv.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToast);
        if (!sticky) handler.postDelayed(hideToast, 900);
    }

    private void togglePlay() {
        if (player.getMediaItemCount() == 0) return;
        if (player.isPlaying()) { player.pause(); showToast("⏸ 暂停", false); }
        else {
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
            player.play();
            showToast("▶ 播放", false);
        }
    }

    private void showSpeedMenu() {
        PopupMenu pm = new PopupMenu(this, speedBtn);
        final float[] r = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};
        for (int i = 0; i < r.length; i++) pm.getMenu().add(0, i, i, r[i] + "x");
        pm.setOnMenuItemClickListener(it -> {
            speed = r[it.getItemId()];
            player.setPlaybackSpeed(speed);
            speedBtn.setText("倍速" + speed + "x");
            return true;
        });
        pm.show();
    }

    private void toggleFullscreen() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        getWindow().getDecorView().setSystemUiVisibility(land
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
        // 横屏时控制栏左右留出刘海空间，避免按钮被挡住
        int pad = land ? dp(44) : dp(10);
        if (top != null) top.setPadding(pad, dp(4), pad, dp(4));
        if (bottom != null) bottom.setPadding(pad, dp(6), pad, dp(6));
    }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); applyImmersive(); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) applyImmersive(); }

    // ---------- gestures ----------
    private boolean onGesture(View v, MotionEvent e) {
        int w = v.getWidth(), h = v.getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (locked) { handler.removeCallbacks(singleTap); setUi(lockBtn.getVisibility() != View.VISIBLE); return true; }
                downX = e.getX(); downY = e.getY(); mode = 0;
                startPos = player.getCurrentPosition();
                seekTarget = startPos;
                float cur = getWindow().getAttributes().screenBrightness;
                startBright = cur < 0 ? 0.5f : cur;
                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                handler.postDelayed(longPress, 500);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (locked || mode == 4) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (mode == 0 && (Math.abs(dx) > dp(12) || Math.abs(dy) > dp(12))) {
                    handler.removeCallbacks(longPress);
                    mode = Math.abs(dx) > Math.abs(dy) ? 1 : (downX < w / 2f ? 2 : 3);
                }
                if (mode == 1 && player.getDuration() > 0) {
                    long d = player.getDuration();
                    long span = Math.min(120000L, d);
                    seekTarget = Math.max(0, Math.min(d, startPos + (long) (dx / w * span)));
                    long diff = (seekTarget - startPos) / 1000;
                    showToast((diff >= 0 ? "⏩ " : "⏪ ") + fmt(seekTarget) + " / " + fmt(d)
                            + "  (" + (diff >= 0 ? "+" : "") + diff + "s)", true);
                } else if (mode == 2) {
                    float bnew = Math.max(0.05f, Math.min(1f, startBright - dy / h * 1.3f));
                    android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = bnew;
                    getWindow().setAttributes(lp);
                    showToast("☀ 亮度 " + Math.round(bnew * 100) + "%", true);
                } else if (mode == 3) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    float vf = Math.max(0, Math.min(max, startVol - dy / h * 1.3f * max));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(vf), 0);
                    showToast("🔊 音量 " + Math.round(vf * 100f / max) + "%", true);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (locked) return true;
                handler.removeCallbacks(longPress);
                int m = mode;
                if (m == 1) player.seekTo(seekTarget);
                if (m == 4) player.setPlaybackSpeed(speed);
                if (m != 0) { handler.removeCallbacks(hideToast); toastTv.setVisibility(View.GONE); }
                else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 280) {
                        handler.removeCallbacks(singleTap);
                        lastTap = 0;
                        togglePlay();
                    } else {
                        lastTap = now;
                        handler.postDelayed(singleTap, 280);
                    }
                }
                mode = 0;
                return true;
        }
        return true;
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = s % 3600 / 60, c = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, c) : String.format("%d:%02d", m, c);
    }

    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }
}
