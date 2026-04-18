package com.example.tofranger;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/**
 * IMU + ToF sensor fusion for tilt compensation and height/distance estimation.
 *
 * FIX: Added gyro drift compensation via static-period detection.
 * When device is stationary (low accel variance), periodically re-derive
 * pitch from accelerometer to prevent long-term gyro drift.
 *
 * Convention: pitch = 0° when phone is vertical (screen facing user),
 *             pitch = 90° when pointing at horizon.
 *             Positive pitch = pointing upward.
 */
public class TiltCompensator {

    // Complementary filter: high-pass gyro + low-pass accel
    private static final float ALPHA = 0.98f; // gyro weight
    private float pitchRad = 0; // radians, 0 = vertical, +π/2 = up
    private long lastGyroTime = 0;

    // Calibration
    private float pitchOffsetRad = 0;
    private boolean calibrated = false;

    // Latest accelerometer values
    private float latestAccelX, latestAccelY, latestAccelZ;

    // Accelerometer-based pitch (used when gyro is unavailable)
    private float accelPitchRad = 0;
    private boolean hasGyro = false;

    // ── Gyro drift compensation ──
    // Track accel variance to detect static periods
    private float prevAccelPitchRad = 0;
    private float accelDeltaEMA = 0;
    private static final float ACCEL_DELTA_ALPHA = 0.05f; // slow EMA for variance tracking
    private static final float STATIC_THRESHOLD = 0.005f; // rad — below this = device is still
    private static final float DRIFT_CORRECTION_ALPHA = 0.002f; // gentle pull toward accel when static
    private long lastDriftCheckNanos = 0;
    private static final long DRIFT_CHECK_INTERVAL_NS = 500_000_000L; // every 500ms

    /**
     * Process accelerometer data (gravity vector).
     */
    public void updateAccelerometer(SensorEvent event) {
        latestAccelX = event.values[0];
        latestAccelY = event.values[1];
        latestAccelZ = event.values[2];

        // Gravity-based pitch estimate (robust but noisy)
        float norm = (float) Math.sqrt(
                latestAccelX * latestAccelX +
                latestAccelY * latestAccelY +
                latestAccelZ * latestAccelZ);
        if (norm < 0.001f) return;

        float ay = latestAccelY / norm;
        float az = latestAccelZ / norm;

        float newAccelPitch = (float) Math.atan2(ay, az);

        // Track accel pitch stability (for drift detection)
        float accelDelta = Math.abs(newAccelPitch - prevAccelPitchRad);
        accelDeltaEMA = ACCEL_DELTA_ALPHA * accelDelta + (1 - ACCEL_DELTA_ALPHA) * accelDeltaEMA;
        prevAccelPitchRad = newAccelPitch;

        accelPitchRad = newAccelPitch;

        // If no gyro available, use accel directly (with smoothing)
        if (!hasGyro) {
            pitchRad = ALPHA * pitchRad + (1 - ALPHA) * accelPitchRad;
        }
    }

    /**
     * Process gyroscope data (angular velocity).
     * Call this at SENSOR_DELAY_GAME rate.
     */
    public void updateGyroscope(SensorEvent event) {
        hasGyro = true;

        long now = System.nanoTime();
        if (lastGyroTime == 0) {
            lastGyroTime = now;
            lastDriftCheckNanos = now;
            return;
        }

        float dt = (now - lastGyroTime) / 1_000_000_000f;
        lastGyroTime = now;

        if (dt > 0.1f) return; // too large gap, skip

        // Gyro integration (Y-axis rotation affects pitch)
        float gyroPitchRate = event.values[1];

        // Complementary filter: fuse gyro (fast) + accel (stable)
        pitchRad = ALPHA * (pitchRad + gyroPitchRate * dt) + (1 - ALPHA) * accelPitchRad;

        // Gyro drift correction: when device is static, gently pull toward accel
        if (now - lastDriftCheckNanos >= DRIFT_CHECK_INTERVAL_NS) {
            lastDriftCheckNanos = now;
            if (accelDeltaEMA < STATIC_THRESHOLD) {
                // Device is stationary — slowly correct drift toward accel baseline
                float driftError = accelPitchRad - pitchRad;
                pitchRad += driftError * DRIFT_CORRECTION_ALPHA;
            }
        }
    }

    /**
     * Get tilt-compensated horizontal distance.
     */
    public float getHorizontalDistance(float slantDist) {
        if (slantDist < 0) return -1;
        float pitch = getPitchRad();
        return (float) (slantDist * Math.cos(pitch));
    }

    /**
     * Get vertical component of the ToF measurement.
     */
    public float getVerticalHeight(float slantDist) {
        if (slantDist < 0) return -1;
        float pitch = getPitchRad();
        return (float) (slantDist * Math.sin(pitch));
    }

    /**
     * Get pitch angle in degrees.
     * 0° = pointing at horizon, 90° = pointing up, -90° = pointing down.
     */
    public float getPitchDegrees() {
        return (float) Math.toDegrees(getPitchRad());
    }

    public float getPitchRad() {
        return pitchRad - pitchOffsetRad;
    }

    /**
     * Calibrate: set current pitch as "zero" reference.
     */
    public void calibrate() {
        pitchOffsetRad = pitchRad;
        calibrated = true;
    }

    public void resetCalibration() {
        pitchOffsetRad = 0;
        calibrated = false;
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    public void reset() {
        pitchRad = 0;
        lastGyroTime = 0;
        pitchOffsetRad = 0;
        calibrated = false;
        prevAccelPitchRad = 0;
        accelDeltaEMA = 0;
        lastDriftCheckNanos = 0;
    }

    /**
     * Get tilt quality indicator.
     */
    public String getTiltQuality() {
        float deg = Math.abs(getPitchDegrees());
        if (deg < 5) return "水平";
        if (deg < 25) return "微倾";
        if (deg < 65) return "斜向";
        if (deg < 85) return "上仰";
        return "垂直";
    }
}
