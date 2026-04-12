package com.example.tofranger;

import android.hardware.SensorEvent;

/**
 * Detects device shaking via accelerometer.
 * When shaking is detected, display should be frozen to prevent jitter.
 */
public class ShakeDetector {

    private static final float SHAKE_THRESHOLD = 15f; // m/s² (normal gravity ≈ 9.8)
    private static final long SHAKE_COOLDOWN_MS = 500; // min time between shake events
    private static final long SETTLE_TIME_MS = 800; // how long to stay frozen after shake stops

    private long lastShakeTime = 0;
    private long lastAccelTime = 0;
    private boolean shaking = false;

    // Low-pass filter for baseline gravity
    private float gravityX = 0, gravityY = 0, gravityZ = 0;
    private static final float LP_ALPHA = 0.1f;

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

    /**
     * Get how long until settle (ms). 0 = already settled.
     */
    public long getSettleRemainingMs() {
        if (!shaking) return 0;
        long elapsed = System.currentTimeMillis() - lastShakeTime;
        return Math.max(0, SETTLE_TIME_MS - elapsed);
    }

    public void reset() {
        shaking = false;
        lastShakeTime = 0;
        gravityX = gravityY = gravityZ = 0;
    }
}
