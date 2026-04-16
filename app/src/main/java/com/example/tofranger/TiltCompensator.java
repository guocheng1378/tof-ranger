package com.example.tofranger;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;

/**
 * IMU + ToF sensor fusion for tilt compensation and height/distance estimation.
 *
 * Uses accelerometer (gravity) + gyroscope via complementary filter to track
 * device tilt angle, then applies trigonometry to compute:
 *   - Horizontal distance = slantDist × cos(pitch)
 *   - Vertical component  = slantDist × sin(pitch)
 *
 * Improvements over v1:
 *  - Gyroscope zero-bias calibration on startup (samples N frames, subtracts mean offset)
 *  - Adaptive complementary filter weight: more accel trust when device is still
 *  - Pitch reliability warning at extreme angles (>75°)
 */
public class TiltCompensator {

    // Complementary filter: high-pass gyro + low-pass accel
    private static final float ALPHA_GYRO = 0.98f; // gyro weight (fast motion)
    private static final float ALPHA_STILL = 0.90f; // gyro weight when still (more accel trust)
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

    // Gyro bias estimation (auto-calibration on startup)
    private float gyroBiasY = 0;
    private int gyroCalibCount = 0;
    private static final int GYRO_CALIB_SAMPLES = 80; // ~800ms at SENSOR_DELAY_GAME
    private boolean gyroCalibrated = false;

    // Stillness detection for adaptive filter
    private float prevGyroY = 0;
    private boolean isStill = false;
    private static final float STILL_THRESHOLD = 0.05f; // rad/s — below this = still

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

        accelPitchRad = (float) Math.atan2(ay, az);

        // If no gyro available, use accel directly (with smoothing)
        if (!hasGyro) {
            pitchRad = ALPHA_GYRO * pitchRad + (1 - ALPHA_GYRO) * accelPitchRad;
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
            return;
        }

        float dt = (now - lastGyroTime) / 1_000_000_000f;
        lastGyroTime = now;

        if (dt > 0.1f) return; // too large gap, skip

        float rawGyroY = event.values[1];

        // Gyro bias calibration (first ~800ms)
        if (!gyroCalibrated) {
            gyroBiasY = (gyroBiasY * gyroCalibCount + rawGyroY) / (gyroCalibCount + 1);
            gyroCalibCount++;
            if (gyroCalibCount >= GYRO_CALIB_SAMPLES) {
                gyroCalibrated = true;
            }
            // During calibration, use accel-only estimate
            pitchRad = 0.9f * pitchRad + 0.1f * accelPitchRad;
            return;
        }

        // Apply bias correction
        float gyroY = rawGyroY - gyroBiasY;

        // Stillness detection: if gyro rate is low, device is stationary
        isStill = Math.abs(gyroY) < STILL_THRESHOLD;

        // Adaptive complementary filter: more accel trust when still (reduces drift)
        float alpha = isStill ? ALPHA_STILL : ALPHA_GYRO;

        // Complementary filter: fuse gyro (fast) + accel (stable)
        pitchRad = alpha * (pitchRad + gyroY * dt) + (1 - alpha) * accelPitchRad;

        prevGyroY = gyroY;
    }

    /**
     * Get tilt-compensated horizontal distance.
     * @param slantDist raw ToF distance (mm)
     * @return horizontal distance in mm, or -1 if invalid
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
     */
    public float getPitchDegrees() {
        return (float) Math.toDegrees(getPitchRad());
    }

    /**
     * Get pitch in radians, with calibration offset applied.
     */
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

    /**
     * Reset calibration.
     */
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
        gyroBiasY = 0;
        gyroCalibCount = 0;
        gyroCalibrated = false;
        isStill = false;
        prevGyroY = 0;
    }

    /**
     * Get tilt quality indicator with reliability warning at extreme angles.
     */
    public String getTiltQuality() {
        float deg = Math.abs(getPitchDegrees());
        if (deg > 75) return "⚠ 极端角度";  // unreliable for single-axis measurement
        if (deg < 5) return "水平";
        if (deg < 25) return "微倾";
        if (deg < 65) return "斜向";
        return "上仰";
    }

    /** Whether gyro bias calibration has completed */
    public boolean isGyroCalibrated() {
        return gyroCalibrated;
    }

    /** Whether device is currently stationary */
    public boolean isStill() {
        return isStill;
    }

    /** Get current gyro bias offset (for debug) */
    public float getGyroBias() {
        return gyroBiasY;
    }
}
