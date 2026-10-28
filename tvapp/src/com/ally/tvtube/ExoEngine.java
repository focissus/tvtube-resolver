package com.ally.tvtube;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Toàn bộ phần dùng ExoPlayer nằm ở đây. Lớp này chỉ được nạp khi Activity
 * thực sự tạo nó (bên trong try/catch), nên nếu máy không chạy nổi ExoPlayer
 * thì lỗi nạp lớp không làm chết Activity.
 */
public class ExoEngine implements Engine {

    public interface Callback {
        void onReady();
        void onEnded();
        void onError(String message);
    }

    private ExoPlayer player;
    private SurfaceView surface;
    private final Callback cb;

    public ExoEngine(Context ctx, Callback cb) {
        this.cb = cb;
        android.app.ActivityManager am =
                (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        int memClass = am != null ? am.getMemoryClass() : 64;
        int targetBytes = memClass <= 64 ? 6 * 1024 * 1024 : 8 * 1024 * 1024;
        int maxMs = memClass <= 64 ? 10000 : 15000;

        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, maxMs, 1000, 2000)
                .setTargetBufferBytes(targetBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(0, false)
                .build();

        DefaultRenderersFactory rf = new DefaultRenderersFactory(ctx)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)
                .setEnableAudioFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false);

        DefaultTrackSelector ts = new DefaultTrackSelector(ctx);
        ts.setParameters(ts.buildUponParameters()
                .setMaxVideoSize(1280, 720)
                .setMaxVideoFrameRate(30)
                .setMaxVideoBitrate(4000000)
                .setForceHighestSupportedBitrate(false)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .build());

        player = new ExoPlayer.Builder(ctx, rf).setLoadControl(lc).setTrackSelector(ts).build();
        try {
            player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);
        } catch (Throwable ignored) {}
        player.setWakeMode(C.WAKE_MODE_NONE);
        player.setHandleAudioBecomingNoisy(false);
        player.setAudioAttributes(
                new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), false);

        player.addListener(new Player.Listener() {
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && cb != null) cb.onReady();
                if (state == Player.STATE_ENDED && cb != null) cb.onEnded();
            }
            public void onPlayerError(PlaybackException error) {
                if (cb != null) cb.onError(error.getErrorCodeName());
            }
        });
    }

    public void setVideoSurfaceView(SurfaceView view) {
        surface = view;
        if (player != null && view != null) player.setVideoSurfaceView(view);
    }

    public void start(String videoUrl, String audioUrl, int resumeMs) {
        Map<String, String> req = new HashMap<String, String>();
        req.put("Accept-Encoding", "identity");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("TVTube/1.0")
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(req);

        MediaSource vs = new ProgressiveMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)));
        MediaSource as = new ProgressiveMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)));

        player.setMediaSource(new MergingMediaSource(vs, as), false);
        player.prepare();
        if (resumeMs > 3000) player.seekTo(resumeMs);
        player.setPlayWhenReady(true);
    }

    public void play() { if (player != null) player.play(); }
    public void pause() { if (player != null) player.pause(); }
    public void seekTo(int ms) { if (player != null) player.seekTo(ms); }

    public int position() {
        try { return player == null ? 0 : (int) player.getCurrentPosition(); } catch (Exception e) { return 0; }
    }

    public int duration() {
        try { return player == null ? 0 : (int) player.getDuration(); } catch (Exception e) { return 0; }
    }

    public boolean isPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    public void release() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }
}
