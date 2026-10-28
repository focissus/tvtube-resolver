package com.ally.tvtube;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class PlayerActivity extends Activity implements SurfaceHolder.Callback,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnBufferingUpdateListener,
        MediaPlayer.OnVideoSizeChangedListener, MediaPlayer.OnInfoListener {

    private static final int SEEK_STEP_MS = 10000;
    private static final int PROGRESS_INTERVAL_MS = 500;
    private static final int OVERLAY_HIDE_DELAY_MS = 4000;
    private static final int OVERLAY_FADE_MS = 220;
    private static final String PREF_NAME = "tv";
    private static final String KEY_POS_PREFIX = "pos_";

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private MediaPlayer mediaPlayer;

    private View overlayContainer;
    private TextView txtTitle;
    private TextView txtTime;
    private TextView txtLoading;
    private SeekBar seekBar;
    private ImageButton btnPlay;

    private String videoId;
    private String videoTitle;
    private String streamUrl;

    private boolean isPrepared = false;
    private boolean isSurfaceCreated = false;
    private boolean isPreparing = false;
    private boolean startWhenSurfaceReady = false;
    private boolean isPaused = false;
    private int startPosMs = 0;
    private int pendingSeekMs = 0;
    private volatile boolean resolveCancelled = false;
    private volatile String fullUrl = null;
    private volatile boolean switched = false;
    private volatile boolean switching = false;
    private volatile int fastDurationMs = 0;
    private String playUrl = null;
    private boolean isSeekTracking = false;
    private boolean overlayVisible = false;
    private boolean isDestroyed = false;

    private int videoWidth = 0;
    private int videoHeight = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable updateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPrepared && !isSeekTracking) {
                try {
                    int current = mediaPlayer.getCurrentPosition();
                    int duration = mediaPlayer.getDuration();
                    if (duration > 0) {
                        if (seekBar != null) {
                            if (seekBar.getMax() != duration) {
                                seekBar.setMax(duration);
                            }
                            seekBar.setProgress(current);
                        }
                        updateTimeDisplay(current, duration);
                        maybeSwitchToFull();
                    }
                } catch (Exception ignored) {
                }
            }
            if (!isDestroyed) {
                handler.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        }
    };

    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            hideOverlay();
        }
    };

    private final Runnable overlayGoneRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayContainer != null && !overlayVisible) {
                overlayContainer.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.install(this);
        setContentView(R.layout.activity_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        hideSystemUI();

        Intent intent = getIntent();
        if (intent != null) {
            videoId = intent.getStringExtra("id");
        startPosMs = getIntent().getIntExtra("posMs", 0);
            videoTitle = intent.getStringExtra("title");
        }

        txtTitle = (TextView) findViewById(R.id.txtTitle);
        txtTime = (TextView) findViewById(R.id.txtTime);
        seekBar = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btnPlay);

        overlayContainer = findViewById(R.id.overlay);
        if (overlayContainer == null && txtTitle != null) {
            if (txtTitle.getParent() instanceof View) {
                overlayContainer = (View) txtTitle.getParent();
            }
        }

        txtLoading = (TextView) findViewById(R.id.txtLoading);
        if (txtLoading == null) {
            txtLoading = txtTitle;
        }

        if (txtTitle != null && videoTitle != null) {
            txtTitle.setText(videoTitle);
        }

        if (txtTime != null) {
            txtTime.setText("00:00 / 00:00");
        }

        if (txtLoading != null && txtLoading != txtTitle) {
            txtLoading.setText("Đang tải...");
            txtLoading.setVisibility(View.VISIBLE);
        }

        FrameLayout container = null;
        View containerView = findViewById(R.id.surface_container);
        if (containerView instanceof FrameLayout) {
            container = (FrameLayout) containerView;
        } else {
            View root = findViewById(android.R.id.content);
            if (root instanceof FrameLayout) {
                container = (FrameLayout) root;
            }
        }

        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        if (surfaceView == null && container != null) {
            surfaceView = new SurfaceView(this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            container.addView(surfaceView, 0, params);
        }

        if (surfaceView == null) {
            showErrorAndFinish("Không tìm thấy vùng hiển thị video.");
            return;
        }

        surfaceView.setBackgroundColor(Color.TRANSPARENT);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setFormat(PixelFormat.RGB_565);
        surfaceHolder.addCallback(this);

        if (seekBar != null) {
            seekBar.setFocusable(false);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && mediaPlayer != null && isPrepared) {
                        try {
                            updateTimeDisplay(progress, mediaPlayer.getDuration());
                        } catch (Exception ignored) {
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                    isSeekTracking = true;
                    showOverlay();
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    isSeekTracking = false;
                    if (mediaPlayer != null && isPrepared) {
                        try {
                            mediaPlayer.seekTo(sb.getProgress());
                            updateTimeDisplay(sb.getProgress(), mediaPlayer.getDuration());
                        } catch (Exception ignored) {
                        }
                    }
                    showOverlay();
                }
            });
        }

        if (btnPlay != null) {
            btnPlay.setFocusable(false);
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
            btnPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePlayPause();
                }
            });
        }

        showOverlay();
        resolveStream();
    }

    private void resolveStream() {
        if (videoId == null || videoId.length() == 0) {
            showErrorAndFinish("Không tìm thấy mã video.");
            return;
        }

        final String id = videoId;
        final Context appContext = getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Api.Stream stream = null;
                String error = null;

                try {
                    stream = Api.resolve(appContext, id, new Api.Progress() {
                        public boolean isCancelled() { return resolveCancelled; }
                        public void onProgress(final int seconds) {
                            handler.post(new Runnable() {
                                public void run() {
                                    if (isDestroyed || isFinishing() || txtLoading == null) return;
                                    if (txtLoading != txtTitle) {
                                        txtLoading.setText(seconds > 0
                                                ? "Đang chuẩn bị video… " + seconds + " giây"
                                                : "Đang chuẩn bị video…");
                                        txtLoading.setVisibility(View.VISIBLE);
                                    }
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    error = e.getMessage();
                }

                if (stream != null && stream.url != null && stream.url.indexOf(".fast.mp4") >= 0 && !resolveCancelled) {
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                String full = Api.waitFull(appContext, id, new Api.Progress() {
                                    public boolean isCancelled() { return resolveCancelled; }
                                    public void onProgress(int seconds) { }
                                });
                                if (full != null && !resolveCancelled) fullUrl = full;
                            } catch (Exception ignored) {}
                        }
                    }).start();
                }
                final Api.Stream result = stream;
                final String err = error;

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed || isFinishing()) {
                            return;
                        }

                        if (result != null && result.url != null && result.url.length() > 0) {
                            streamUrl = result.url;
                            playUrl = result.url;      // bat buoc: onCompletion dua vao playUrl de biet dang phat doan ngan
                            prepareMediaPlayer();
                        } else {
                            String msg = err;
                            if (msg == null || msg.length() == 0) {
                                msg = "Không lấy được luồng xem video.";
                            }
                            showErrorAndFinish(msg);
                        }
                    }
                });
            }
        }).start();
    }

    private void prepareMediaPlayer() {
        if (streamUrl == null || streamUrl.length() == 0) {
            return;
        }
        if (!isSurfaceCreated || isFinishing() || isDestroyed || isPreparing) {
            return;
        }

        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.setDisplay(surfaceHolder);
            } catch (Exception ignored) {
            }
            return;
        }

        try {
            isPreparing = true;
            isPrepared = false;

            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            } else {
                mediaPlayer.reset();
            }

            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setScreenOnWhilePlaying(true);
            mediaPlayer.setDataSource(streamUrl);
            mediaPlayer.setDisplay(surfaceHolder);

            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnCompletionListener(this);
            mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setOnBufferingUpdateListener(this);
            mediaPlayer.setOnVideoSizeChangedListener(this);
            mediaPlayer.setOnInfoListener(this);

            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            isPreparing = false;
            isPrepared = false;
            String msg = e.getMessage();
            if (msg == null || msg.length() == 0) {
                msg = "Không thể chuẩn bị trình phát video.";
            }
            showErrorAndFinish(msg);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isSurfaceCreated = true;

        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.setDisplay(surfaceHolder);
                if (startWhenSurfaceReady) {
                    startWhenSurfaceReady = false;
                    mediaPlayer.start();
                }
            } catch (Exception ignored) {
            }

            updatePlayButtonIcon();
            startProgressTracker();
            showOverlay();
        } else {
            prepareMediaPlayer();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (videoWidth > 0 && videoHeight > 0) {
            fitAspect(videoWidth, videoHeight);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isSurfaceCreated = false;

        if (mediaPlayer != null) {
            try {
                mediaPlayer.setDisplay(null);
            } catch (Exception ignored) {
            }

            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    updatePlayButtonIcon();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (isFinishing() || isDestroyed) {
            return;
        }

        isPreparing = false;
        isPrepared = true;

        if (txtLoading != null && txtLoading != txtTitle) {
            txtLoading.setVisibility(View.GONE);
        }

        try {
            int duration = mp.getDuration();
            if (duration > 0 && seekBar != null) {
                seekBar.setMax(duration);
            }

            int saved = 0;
            if (videoId != null) {
                saved = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .getInt(KEY_POS_PREFIX + videoId, 0);
            }

            if (saved > 5000 && duration > 0 && saved < duration - 8000) {
                mp.seekTo(saved);
                if (seekBar != null) {
                    seekBar.setProgress(saved);
                }
                updateTimeDisplay(saved, duration);
            } else {
                updateTimeDisplay(0, duration);
            }

            if (isSurfaceCreated && !isPaused) {
                mp.start();
                startWhenSurfaceReady = false;
            } else {
                startWhenSurfaceReady = !isPaused;
            }
        } catch (Exception ignored) {
        }

        updatePlayButtonIcon();
        startProgressTracker();
        showOverlay();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        // het doan ngan dau tien: cho tep day du roi chuyen tiep, khong thoat
        String cur = playUrl != null ? playUrl : streamUrl;
        if (!switched && cur != null && cur.indexOf(".fast.mp4") >= 0) {
            waitFullThenSwitch();
            return;
        }
        if (videoId != null) {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_POS_PREFIX + videoId)
                    .commit();
        }

        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        isPreparing = false;
        isPrepared = false;
        showErrorAndFinish(mapMediaError(what, extra));
        return true;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (txtLoading != null && txtLoading != txtTitle) {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                txtLoading.setText("Đang đệm video…");
                txtLoading.setVisibility(View.VISIBLE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                txtLoading.setVisibility(View.GONE);
            }
        }
        return false;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        if (seekBar != null && mp != null && isPrepared) {
            try {
                int duration = mp.getDuration();
                if (duration > 0) {
                    seekBar.setSecondaryProgress((duration * percent) / 100);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        if (width > 0 && height > 0) {
            videoWidth = width;
            videoHeight = height;
            fitAspect(width, height);
        }
    }

    private void fitAspect(int videoW, int videoH) {
        if (videoW <= 0 || videoH <= 0 || surfaceView == null) {
            return;
        }

        View parent = null;
        if (surfaceView.getParent() instanceof View) {
            parent = (View) surfaceView.getParent();
        }
        if (parent == null) {
            return;
        }

        int parentW = parent.getWidth();
        int parentH = parent.getHeight();
        if (parentW <= 0 || parentH <= 0) {
            return;
        }

        double videoAspect = (double) videoW / (double) videoH;
        double parentAspect = (double) parentW / (double) parentH;

        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
        }

        if (videoAspect > parentAspect) {
            lp.width = parentW;
            lp.height = (int) (parentW / videoAspect);
        } else {
            lp.width = (int) (parentH * videoAspect);
            lp.height = parentH;
        }

        surfaceView.setLayoutParams(lp);
        surfaceView.requestLayout();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        showOverlay();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                togglePlayPause();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekRelative(-SEEK_STEP_MS);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekRelative(SEEK_STEP_MS);
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private void togglePlayPause() {
        if (mediaPlayer == null || !isPrepared) {
            Toast.makeText(this, "Video chưa sẵn sàng.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
            updatePlayButtonIcon();
        } catch (Exception e) {
            Toast.makeText(this, "Không thể điều khiển video.", Toast.LENGTH_SHORT).show();
        }

        showOverlay();
    }

    private void seekRelative(int deltaMs) {
        if (mediaPlayer == null || !isPrepared) {
            return;
        }

        try {
            int current = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            if (duration <= 0) {
                return;
            }

            int target = current + deltaMs;
            if (target < 0) {
                target = 0;
            }
            if (target > duration) {
                target = duration;
            }

            mediaPlayer.seekTo(target);
            if (seekBar != null) {
                seekBar.setProgress(target);
            }
            updateTimeDisplay(target, duration);
            showOverlay();
        } catch (Exception ignored) {
        }
    }

    private void showOverlay() {
        if (overlayContainer == null) {
            return;
        }

        handler.removeCallbacks(hideOverlayRunnable);
        handler.removeCallbacks(overlayGoneRunnable);

        overlayContainer.animate().cancel();

        if (overlayContainer.getVisibility() != View.VISIBLE) {
            overlayContainer.setVisibility(View.VISIBLE);
            overlayContainer.setAlpha(0f);
        }

        overlayVisible = true;
        overlayContainer.animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_MS)
                .start();

        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS);
    }

    private void hideOverlay() {
        if (overlayContainer == null) {
            return;
        }

        overlayVisible = false;
        overlayContainer.animate().cancel();
        overlayContainer.animate()
                .alpha(0f)
                .setDuration(OVERLAY_FADE_MS)
                .start();

        handler.removeCallbacks(overlayGoneRunnable);
        handler.postDelayed(overlayGoneRunnable, OVERLAY_FADE_MS + 20);
    }

    private void startProgressTracker() {
        handler.removeCallbacks(updateProgressRunnable);
        if (!isDestroyed) {
            handler.post(updateProgressRunnable);
        }
    }

    private void updateTimeDisplay(int currentMs, int durationMs) {
        if (txtTime != null) {
            txtTime.setText(formatTime(currentMs) + " / " + formatTime(durationMs));
        }
    }

    /** Doan ngan da het: cho tep day du roi phat tiep tu cho dang xem. */
    private void waitFullThenSwitch() {
        if (switching || switched) return;
        switching = true;
        final int resume = Math.max(0, fastDurationMs - 500);
        if (txtLoading != null && txtLoading != txtTitle) {
            txtLoading.setText("Đang chuẩn bị phần tiếp theo…");
            txtLoading.setVisibility(View.VISIBLE);
        }
        new Thread(new Runnable() {
            public void run() {
                String full = fullUrl;
                long deadline = System.currentTimeMillis() + 300000L;  // doi tep day du toi 5 phut, khong thoat som
                while (full == null && System.currentTimeMillis() < deadline && !resolveCancelled) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    full = fullUrl;
                }
                final String target = full;
                handler.post(new Runnable() {
                    public void run() {
                        if (isDestroyed || isFinishing() || switched) return;
                        switching = false;
                        if (target == null) {
                            showErrorAndFinish("Không tải được phần tiếp theo.");
                            return;
                        }
                        switched = true;
                        pendingSeekMs = resume;
                        streamUrl = target;
                        playUrl = target;
                        fullUrl = null;
                        isPrepared = false;
                        releaseMediaPlayer();
                        prepareMediaPlayer();
                    }
                });
            }
        }).start();
    }

    private void maybeSwitchToFull() {
        if (switched || switching || fullUrl == null || mediaPlayer == null || !isPrepared) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            int dur = mediaPlayer.getDuration();
            if (dur > 0 && dur - pos > 4000) return;      // con xa cuoi doan ngan
            switching = true;
            switched = true;
            pendingSeekMs = pos;
            String target = fullUrl;
            fullUrl = null;
            playUrl = target;
            streamUrl = target;      // prepareMediaPlayer doc bien nay
            releaseMediaPlayer();
            prepareMediaPlayer();
        } catch (Exception ignored) {}
    }

    private String formatTime(int ms) {
        if (ms < 0) {
            ms = 0;
        }

        long totalSec = ms / 1000L;
        long sec = totalSec % 60L;
        long min = (totalSec / 60L) % 60L;
        long hr = totalSec / 3600L;

        if (hr > 0) {
            return String.format("%d:%02d:%02d", hr, min, sec);
        }
        return String.format("%02d:%02d", min, sec);
    }

    private void updatePlayButtonIcon() {
        if (btnPlay == null) {
            return;
        }

        boolean playing = false;
        if (mediaPlayer != null) {
            try {
                playing = mediaPlayer.isPlaying();
            } catch (Exception ignored) {
            }
        }

        if (playing) {
            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private String mapMediaError(int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                return "Máy chủ media bị lỗi. Vui lòng thử lại.";
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                return "Định dạng video không được hỗ trợ.";
            case MediaPlayer.MEDIA_ERROR_IO:
                return "Lỗi mạng hoặc không đọc được video.";
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                return "Dữ liệu video bị lỗi.";
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                return "Quá thời gian kết nối video.";
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
            default:
                return "Lỗi phát video (mã " + what + ", " + extra + ").";
        }
    }

    private void showErrorAndFinish(String message) {
        if (isDestroyed || isFinishing()) {
            return;
        }

        if (message == null || message.length() == 0) {
            message = "Lỗi phát video.";
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= 19) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void savePosition() {
        if (mediaPlayer == null || !isPrepared || videoId == null) {
            return;
        }

        try {
            int pos = mediaPlayer.getCurrentPosition();
            int dur = mediaPlayer.getDuration();
            SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

            if (dur > 0 && pos > 3000 && pos < dur - 5000) {
                sp.edit().putInt(KEY_POS_PREFIX + videoId, pos).commit();
            } else {
                sp.edit().remove(KEY_POS_PREFIX + videoId).commit();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        isPaused = false;
        super.onResume();
        hideSystemUI();

        if (isPrepared) {
            startProgressTracker();
        }
    }

    @Override
    protected void onPause() {
        savePosition();
        handler.removeCallbacks(updateProgressRunnable);

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    updatePlayButtonIcon();
                }
            } catch (Exception ignored) {
            }
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        resolveCancelled = true;
        savePosition();
        isDestroyed = true;

        handler.removeCallbacksAndMessages(null);
        releaseMediaPlayer();

        super.onDestroy();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {
            }

            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }

            mediaPlayer = null;
        }

        isPrepared = false;
        isPreparing = false;
        startWhenSurfaceReady = false;
    }
}
