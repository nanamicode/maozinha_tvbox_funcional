package com.tridi.maozinha;

public final class HandResult {
    public final float[] xy; // 21 x/y pairs in source-image pixels
    public final float[] z;
    public final float score;
    public final boolean rightHand;
    public final String gesture;
    public final long latencyMs;
    public final boolean palmDetectorRan;

    public HandResult(float[] xy, float[] z, float score, boolean rightHand,
                      String gesture, long latencyMs, boolean palmDetectorRan) {
        this.xy = xy;
        this.z = z;
        this.score = score;
        this.rightHand = rightHand;
        this.gesture = gesture;
        this.latencyMs = latencyMs;
        this.palmDetectorRan = palmDetectorRan;
    }
}
