package com.ally.tvtube;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Trinh phat dung ExoPlayer. Moi thu lien quan ExoPlayer nam trong ExoEngine;
 * Activity chi giu kieu Engine (khong phai kieu thu vien) nen loi nap lop
 * khong lam chet Activity.
 */
public class ExoPlayerActivity extends Activity {

    private Engine engine;
    private String videoId, title;
    private TextView txtTime, txtLoading, txtTitle;
    private SeekBar seekBar;
    private ImageButton btnPlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean fallingBack = false;
    private int resumeMs = 0;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (engine != null) {
                int dur = engine.duration();
                int pos = engine.position();
                if (dur > 0) {
                    if (seekBar.getMax() != dur) seekBar.setMax(dur);
                    if (!seekBar.isPressed()) seekBar.setProgress(pos);
                    if (txtTime != null) txtTime.setText(fmt(pos) + " / " + fmt(dur));
                }
                if (btnPlay != null) {
                    btnPlay.setImageResource(engine.isPlaying()
                            ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
                }
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
            load();
        } catch (Throwable t) {
            fallback("Không mở được trình phát");
        }
    }

    private void buildUi() {
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        videoId = getIntent().getStringExtra("id");
        title = getIntent().getStringExtra("title");
        resumeMs = getIntent().getIntExtra("posMs", 0);

        txtTitle = (TextView) findViewById(R.id.txtTitle);
        txtTime = (TextView) findViewById(R.id.txtTime);
        txtLoading = (TextView) findViewById(R.id.txtLoading);
        seekBar = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btnPlay);
        if (txtTitle != null && title != null) txtTitle.setText(title);
        if (txtLoading != null) txtLoading.setText("Đang kết nối…");
        if (seekBar != null) seekBar.setFocusable(false);
        if (btnPlay != null) btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
    }

    private void load() {
        new Thread(new Runnable() {
            public void run() {
                Api.Pair p = null;
                String err = null;
                try {
                    p = Api.streams(getApplicationContext(), videoId);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final Api.Pair pair = p;
                final String error = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (pair == null) {
                            fallback(error != null ? error : "Máy chủ chưa hỗ trợ hai luồng");
                            return;
                        }
                        startEngine(pair);
                    }
                });
            }
        }).start();
    }

    private void startEngine(final Api.Pair pair) {
        try {
            // Nạp động: Activity không tham chiếu tĩnh tới lớp ExoPlayer nào
            EngineListener listener = new EngineListener() {
                public void onReady() {
                    if (txtLoading != null) txtLoading.setVisibility(View.GONE);
                }
                public void onEnded() { finish(); }
                public void onError(final String message) {
                    runOnUiThread(new Runnable() {
                        public void run() { fallback("ExoPlayer: " + message); }
                    });
                }
            };
            Class<?> cls = Class.forName("com.ally.tvtube.ExoEngine");
            engine = (Engine) cls.getConstructor(android.content.Context.class, EngineListener.class)
                    .newInstance(this, listener);
            SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
            engine.setVideoSurfaceView(sv);
            engine.start(pair.videoUrl, pair.audioUrl, resumeMs);
            handler.post(tick);
        } catch (Throwable t) {
            fallback("Máy không chạy được ExoPlayer");
        }
    }

    private void fallback(String why) {
        if (fallingBack || isFinishing()) return;
        fallingBack = true;
        int pos = resumeMs;
        try { if (engine != null && engine.position() > 3000) pos = engine.position(); } catch (Throwable ignored) {}
        if (engine != null) {
            try { engine.release(); } catch (Throwable ignored) {}
            engine = null;
        }
        Toast.makeText(this, "Chuyển trình phát dự phòng", Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("id", videoId);
        i.putExtra("title", title);
        i.putExtra("posMs", pos);
        startActivity(i);
        finish();
    }

    private void toggle() {
        if (engine == null) return;
        if (engine.isPlaying()) engine.pause(); else engine.play();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (engine != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                toggle(); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                engine.seekTo(Math.max(0, engine.position() - 10000)); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                engine.seekTo(engine.position() + 10000); return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private String fmt(int ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) engine.pause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (engine != null) {
            try { engine.release(); } catch (Throwable ignored) {}
            engine = null;
        }
        super.onDestroy();
    }
}
