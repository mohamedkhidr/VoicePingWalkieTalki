package com.smartwalkie.voicepingsdk;

public interface FrameCallback {
    void onFrameCaptured(byte[] pcmData);
    void onError(Exception e);
    void onCompleted();   // <-- new
}