package com.example.tofranger;

import android.hardware.SensorEvent;

/**
 * Detects device shaking via accelerometer.
 * When shaking is detected, display should be frozen to prevent jitter.
 *
 * Improvements over v1:
 *  - Warmup period (first ~50 samples) to let gravity baseline settle
 *  - Prevents false shake detection at app startup
 */
public class ShakeDetector {

    private static final float SHAKE_THRESHOLD = 25f; // m/s² (~2.5g)
    private static final long SETTLE_TIME_MS = 300;

    private long lastShakeTime = 0;
    private long lastAccelTime = 0;
    private boolean shaking = false;

    // Low-pass filter for baseline gravity
    private float gravityX = 0, gravityY = 0, gravityZ = 0;
    private static final float LP_ALPHA = 0.1f;

    // Warmup: ignore shake detection until baseline settles
    private int warmupCount = 0;
    private static final int WARMUP_SAMPLES = 50;

    /**
     * Process accelerometer data. Call in onSensorChanged for TYPE_ACCELEROMETER.
     * @return true if device is currently shaking
     */
    public boolean update(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // Low-pass filter to get gravity baseline
        gravityX = LP_ALPHA * x + (1 - LP_ALPHA) * gravityX;
        gravityY = LP_ALPHA * y + (1 - LP_ALPHA) * gravityY;
        gravityZ = LP_ALPHA * z + (1 - LP_ALPHA) * gravityZ;

        // Warmup: let gravity baseline settle before checking for shakes
        if (warmupCount < WARMUP_SAMPLES) {
            warmupCount++;
            lastAccelTime = System.currentTimeMillis();
            return false;
        }

        // Linear acceleration (remove gravity)
        float lx = x - gravityX;
        float ly = y - gravityY;
        float lz = z - gravityZ;

        float magnitude = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);

        long now = System.currentTimeMillis();

        if (magnitude > SHAKE_THRESHOLD) {
            lastShakeTime = now;
            shaking = true;
        } else if (shaking && (now - lastShakeTime > SETTLE_TIME_MS)) {
            shaking = false;
        }

        lastAccelTime = now;
        return shaking;
    }

    public boolean isShaking() {
        return shaking;
    }

    public long getSettleRemainingMs() {
        if (!shaking) return 0;
        long elapsed = System.currentTimeMillis() - lastShakeTime;
        return Math.max(0, SETTLE_TIME_MS - elapsed);
    }

    /** Whether warmup has completed */
    public boolean isWarmedUp() {
        return warmupCount >= WARMUP_SAMPLES;
    }

    public void reset() {
        shaking = false;
        lastShakeTime = 0;
        gravityX = gravityY = gravityZ = 0;
        warmupCount = 0;
    }
}
