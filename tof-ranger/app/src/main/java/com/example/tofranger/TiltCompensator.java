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

    /**
     * Process accelerometer data (gravity vector).
     */
    public void updateAccelerometer(SensorEvent event) {
        latestAccelX = event.values[0];
        latestAccelY = event.values[1];
        latestAccelZ = event.values[2];

        // Gravity-based pitch estimate (robust but noisy)
        // Normalize gravity vector
        float norm = (float) Math.sqrt(
                latestAccelX * latestAccelX +
                latestAccelY * latestAccelY +
                latestAccelZ * latestAccelZ);
        if (norm < 0.001f) return;

        float ay = latestAccelY / norm; // Y-axis component
        float az = latestAccelZ / norm; // Z-axis component

        // Pitch from accelerometer
        accelPitchRad = (float) Math.atan2(ay, az);

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
            return;
        }

        float dt = (now - lastGyroTime) / 1_000_000_000f;
        lastGyroTime = now;

        if (dt > 0.1f) return; // too large gap, skip

        // Gyro integration (Y-axis rotation affects pitch)
        // Positive gyroY = rotating phone to point upward
        float gyroPitchRate = event.values[1];

        // Complementary filter: fuse gyro (fast) + accel (stable)
        pitchRad = ALPHA * (pitchRad + gyroPitchRate * dt) + (1 - ALPHA) * accelPitchRad;
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
     * Useful for height estimation when pointing at top of wall/window.
     * @param slantDist raw ToF distance (mm)
     * @return vertical height in mm, or -1 if invalid
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

    /**
     * Get pitch in radians, with calibration offset applied.
     */
    public float getPitchRad() {
        return pitchRad - pitchOffsetRad;
    }

    /**
     * Calibrate: set current pitch as "zero" reference.
     * Call when phone is held in the desired reference position.
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
    }

    /**
     * Get tilt quality indicator.
     * Near vertical (±10°) or horizontal (80-100°) = most reliable.
     * Around 45° = least reliable for single-axis measurement.
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
