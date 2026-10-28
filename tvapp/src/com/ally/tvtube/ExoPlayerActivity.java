package com.ally.tvtube;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

/** Trinh phat dung ExoPlayer: phat hai luong roi (hinh + tieng), khong can ghep. */
public class ExoPlayerActivity extends Activity {

    private ExoPlayer player;
    private SurfaceView surfaceView;
    private TextView txtTitle, txtTime, txtLoading;
    private SeekBar seekBar;
    private ImageButton btnPlay;
    private View overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String videoId, title;
    private boolean fallingBack = false;
    private int ioRetries = 0;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (player != null) {
                try {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    if (dur > 0) {
                        if (seekBar.getMax() != (int) dur) seekBar.setMax((int) dur);
                        seekBar.setProgress((int) pos);
                        txtTime.setText(fmt(pos) + " / " + fmt(dur));
                    }
                    btnPlay.setImageResource(player.isPlaying()
                            ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.install(this);
        try {
            buildUi();
        } catch (Throwable t) {
            // khong de chet app vi ExoPlayer
            Toast.makeText(this, "Không mở được trình phát, chuyển bản nhẹ", Toast.LENGTH_LONG).show();
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra("id", getIntent().getStringExtra("id"));
            i.putExtra("title", getIntent().getStringExtra("title"));
            i.putExtra("posMs", getIntent().getIntExtra("posMs", 0));
            startActivity(i);
            finish();
        }
    }

    private void buildUi() {
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        videoId = getIntent().getStringExtra("id");
        title = getIntent().getStringExtra("title");
        int resume = getIntent().getIntExtra("posMs", 0);

        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        txtTitle = (TextView) findViewById(R.id.txtTitle);
        txtTime = (TextView) findViewById(R.id.txtTime);
        txtLoading = (TextView) findViewById(R.id.txtLoading);
        seekBar = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btnPlay);
        overlay = findViewById(R.id.content);

        if (txtTitle != null && title != null) txtTitle.setText(title);
        if (txtLoading != null) txtLoading.setText("Đang kết nối…");
        seekBar.setFocusable(false);

        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });

        if (resume > 3000) {
            seekBar.setProgress(resume);
        }
        new Thread(new Runnable() {
            public void run() { load(); }
        }).start();
        handler.post(tick);
    }

    private void load() {
        final Api.Pair p;
        try {
            p = Api.streams(this, videoId);
        } catch (Exception e) {
            fallback(e.getMessage());
            return;
        }
        if (p == null) {
            fallback("Máy chủ chưa hỗ trợ chế độ hai luồng");
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed() || player != null) return;
                startExo(p);
            }
        });
    }

    private void startExo(Api.Pair p) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            int memClass = am != null ? am.getMemoryClass() : 64;
            if (memClass < 48) { fallback("RAM quá thấp"); return; }

            int targetBytes = memClass <= 64 ? 6 * 1024 * 1024 : 8 * 1024 * 1024;
            int maxMs = memClass <= 64 ? 10000 : 15000;

            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5000, maxMs, 1000, 2000)
                    .setTargetBufferBytes(targetBytes)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .setBackBuffer(0, false)
                    .build();

            DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    .setEnableDecoderFallback(true)
                    .setEnableAudioFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false);

            com.google.android.exoplayer2.trackselection.DefaultTrackSelector ts =
                    new com.google.android.exoplayer2.trackselection.DefaultTrackSelector(this);
            ts.setParameters(ts.buildUponParameters()
                    .setMaxVideoSize(1280, 720)
                    .setMaxVideoFrameRate(30)
                    .setMaxVideoBitrate(4000000)
                    .setForceHighestSupportedBitrate(false)
                    .setExceedRendererCapabilitiesIfNecessary(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(false)
                    .setAllowVideoNonSeamlessAdaptiveness(false)
                    .setAllowAudioMixedMimeTypeAdaptiveness(false)
                    .setAllowAudioMixedSampleRateAdaptiveness(false)
                    .setAllowAudioMixedChannelCountAdaptiveness(false)
                    .build());

            try {
                player = new ExoPlayer.Builder(this, rf)
                        .setLoadControl(lc)
                        .setTrackSelector(ts)
                        .build();
            } catch (OutOfMemoryError oom) {
                fallback("Thiếu bộ nhớ khi tạo ExoPlayer");
                return;
            }

            try { player.setVideoChangeFrameRateStrategy(com.google.android.exoplayer2.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF); } catch (Throwable ignored) {}
            player.setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NONE);
            player.setForegroundMode(false);
            player.setHandleAudioBecomingNoisy(false);
            player.setVideoSurfaceView(surfaceView);
            player.setAudioAttributes(new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                    .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                    .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), false);

            java.util.Map<String, String> req = new java.util.HashMap<String, String>();
            req.put("Accept-Encoding", "identity");
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("TVTube/1.0")
                    .setConnectTimeoutMs(10000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(false)
                    .setDefaultRequestProperties(req);

            MediaSource vs = new ProgressiveMediaSource.Factory(http)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(p.videoUrl)));
            MediaSource as = new ProgressiveMediaSource.Factory(http)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(p.audioUrl)));

            player.setMediaSource(new MergingMediaSource(vs, as), false);
            player.addListener(new Player.Listener() {
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && txtLoading != null) {
                        txtLoading.setVisibility(View.GONE);
                    }
                    if (state == Player.STATE_ENDED) finish();
                }
                public void onPlayerError(PlaybackException error) {
                    int code = error.errorCode;
                    boolean net = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            || code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE;
                    if (net && ioRetries < 1) {
                        ioRetries++;
                        try {
                            long pos = player.getCurrentPosition();
                            player.prepare();
                            player.seekTo(pos);
                            player.setPlayWhenReady(true);
                            return;
                        } catch (Exception ignored) {}
                    }
                    fallback("ExoPlayer lỗi: " + error.getErrorCodeName());
                }
            });
            player.prepare();
            int resume = getIntent().getIntExtra("posMs", 0);
            if (resume > 3000) player.seekTo(resume);
            player.setPlayWhenReady(true);
        } catch (Throwable t) {
            releasePlayer();
            fallback("Không khởi động được ExoPlayer");
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && player != null) {
            fallback("RAM thấp");
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (player != null) fallback("Hết RAM");
    }

    private void fallback(final String why) {
        if (isFinishing() || fallingBack) return;
        fallingBack = true;
        runOnUiThread(new Runnable() {
            public void run() {
                int resume = getIntent().getIntExtra("posMs", 0);
                try {
                    if (player != null) {
                        long pos = player.getCurrentPosition();
                        if (pos > 3000) resume = (int) pos;
                    }
                } catch (Throwable ignored) {}
                releasePlayer();
                Toast.makeText(ExoPlayerActivity.this, "Chuyển trình phát dự phòng", Toast.LENGTH_SHORT).show();
                Intent i = new Intent(ExoPlayerActivity.this, PlayerActivity.class);
                i.putExtra("id", videoId);
                i.putExtra("title", title);
                i.putExtra("posMs", resume);
                startActivity(i);
                finish();
            }
        });
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void toggle() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                toggle(); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                player.seekTo(player.getCurrentPosition() + 10000); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        releasePlayer();
        super.onDestroy();
    }
}
