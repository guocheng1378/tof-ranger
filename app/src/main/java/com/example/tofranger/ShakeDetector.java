package com.example.tofranger;

import android.hardware.SensorEvent;

/**
 * Detects device shaking via accelerometer.
 * When shaking is detected, display should be frozen to prevent jitter.
 *
 * FIX: Initialize gravity to Earth gravity (0, 0, 9.8) instead of (0, 0, 0)
 * to prevent false shake detection on startup.
 */
public class ShakeDetector {

    private static final float SHAKE_THRESHOLD = 25f; // m/s²
    private static final long SETTLE_TIME_MS = 300;

    private long lastShakeTime = 0;
    private boolean shaking = false;

    // Low-pass filter for baseline gravity
    // FIX: Initialize to Earth gravity to avoid false positives at startup
    private float gravityX = 0f, gravityY = 0f, gravityZ = 9.8f;
    private static final float LP_ALPHA = 0.1f;

    // Track whether we've received at least one sample
    private boolean initialized = false;

    /**
     * Process accelerometer data.
     */
    public boolean update(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // First sample: seed gravity directly
        if (!initialized) {
            gravityX = x;
            gravityY = y;
            gravityZ = z;
            initialized = true;
            return false;
        }

        // Low-pass filter to get gravity baseline
        gravityX = LP_ALPHA * x + (1 - LP_ALPHA) * gravityX;
        gravityY = LP_ALPHA * y + (1 - LP_ALPHA) * gravityY;
        gravityZ = LP_ALPHA * z + (1 - LP_ALPHA) * gravityZ;

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

    public void reset() {
        shaking = false;
        lastShakeTime = 0;
        gravityX = 0f;
        gravityY = 0f;
        gravityZ = 9.8f;
        initialized = false;
    }
}
