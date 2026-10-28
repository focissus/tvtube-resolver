package com.ally.tvtube;

/** Giao diện trung lập: Activity chỉ nói chuyện qua đây, không giữ kiểu của ExoPlayer. */
public interface Engine {
    void start(String videoUrl, String audioUrl, int resumeMs);
    void play();
    void pause();
    void seekTo(int ms);
    int position();
    int duration();
    boolean isPlaying();
    void release();
    void setVideoSurfaceView(android.view.SurfaceView view);
}
