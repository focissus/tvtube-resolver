package com.ally.tvtube;

/** Lắng nghe sự kiện của engine, dùng kiểu của app để Activity không phải chạm ExoPlayer. */
public interface EngineListener {
    void onReady();
    void onEnded();
    void onError(String message);
}
