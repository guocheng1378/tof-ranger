package com.example.tofranger;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Manages ToF sensor discovery, registration, and data flow.
 * Extracted from MainActivity to separate sensor concerns.
 *
 * v3.2: Passes filter.getMovementSpeed() to Stabilizer for adaptive EMA.
 */
public class SensorController implements SensorEventListener {

    public interface ToFListener {
        void onDistance(float distanceMm, float rawMm);
        void onSensorStatus(String status);
    }

    private static final int SENSOR_TYPE_MIUI_TOF = 33171040;
    private static final float MAX_VALID_RANGE_MM = 4000f;
    private static final float VL53_OVERFLOW = 8190f;

    private final SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private boolean registered = false;
    private boolean isProximityFallback = false;

    private ToFListener listener;
    private final DistanceFilter filter;
    private final DistanceStats stats;
    private final ShakeDetector shakeDetector;
    private final Stabilizer stabilizer;
    private final TiltCompensator tiltCompensator;

    public SensorController(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        filter = new DistanceFilter(7, 0.25f, 200, MAX_VALID_RANGE_MM);
        stats = new DistanceStats(200);
        shakeDetector = new ShakeDetector();
        stabilizer = new Stabilizer();
        tiltCompensator = new TiltCompensator();
        discoverSensors();
    }

    public void setListener(ToFListener l) { this.listener = l; }

    public DistanceFilter getFilter() { return filter; }
    public DistanceStats getStats() { return stats; }
    public ShakeDetector getShakeDetector() { return shakeDetector; }
    public Stabilizer getStabilizer() { return stabilizer; }
    public TiltCompensator getTiltCompensator() { return tiltCompensator; }
    public boolean isProximityFallback() { return isProximityFallback; }
    public boolean hasSensor() { return tofSensor != null; }

    private void discoverSensors() {
        if (sensorManager == null) return;
        tofSensor = null;
        isProximityFallback = false;
        for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            String n = s.getName().toLowerCase();
            int type = s.getType();
            if ((type == SENSOR_TYPE_MIUI_TOF || n.contains("tof") || n.contains("vl53")) && tofSensor == null) {
                tofSensor = s;
                break;
            }
        }
        if (tofSensor == null) {
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) isProximityFallback = true;
        }
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (listener != null) {
            if (tofSensor == null) {
                listener.onSensorStatus("未找到距离传感器");
            } else {
                String label = isProximityFallback ? " (降级 Proximity)" : "";
                listener.onSensorStatus("传感器: " + tofSensor.getName() + label
                        + " | 量程: " + tofSensor.getMaximumRange());
            }
        }
    }

    public void resume() {
        if (sensorManager == null || registered) return;
        if (tofSensor != null) sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_FASTEST);
        if (accelSensor != null) sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gyroSensor != null) sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        registered = true;
    }

    public void pause() {
        if (sensorManager != null && registered) {
            sensorManager.unregisterListener(this);
            registered = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sType = event.sensor.getType();
        if (sType == SENSOR_TYPE_MIUI_TOF || sType == Sensor.TYPE_PROXIMITY) {
            handleTofReading(event.values[0]);
        } else if (sType == Sensor.TYPE_ACCELEROMETER) {
            shakeDetector.update(event);
            tiltCompensator.updateAccelerometer(event);
        } else if (sType == Sensor.TYPE_GYROSCOPE) {
            tiltCompensator.updateGyroscope(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Process raw ToF reading through the filter pipeline.
     */
    public float[] processReading(float rawMm) {
        if (!registered) return new float[]{-1, rawMm, -1};

        // FIX: Check raw BEFORE filtering — filter.filter(-1) returns stale EMA
        boolean sensorDead = (rawMm < 0 || rawMm >= VL53_OVERFLOW || rawMm > MAX_VALID_RANGE_MM);

        float raw = rawMm;
        if (raw >= VL53_OVERFLOW) raw = -1;
        else if (raw > MAX_VALID_RANGE_MM) raw = -1;

        stats.tickHz();

        float filtered;
        float stabInput;
        if (sensorDead) {
            // Sensor has no signal — bypass filter, tell stabilizer to clear
            filtered = -1;
            stabInput = -1;
        } else {
            filtered = filter.filter(raw);
            stats.add(filtered >= 0 ? filtered : raw);
            stabInput = (filtered >= 0) ? filtered : raw;
        }

        float movementSpeed = filter.getMovementSpeed();
        float stabilized = stabilizer.update(stabInput, shakeDetector.isShaking(), movementSpeed);

        return new float[]{filtered, raw, stabilized};
    }

    private void handleTofReading(float rawMm) {
        if (listener != null) {
            float[] result = processReading(rawMm);
            listener.onDistance(result[2], result[1]); // stabilized, raw
        } else {
            processReading(rawMm);
        }
    }

    public void reset() {
        filter.reset();
        stats.reset();
        tiltCompensator.resetCalibration();
        shakeDetector.reset();
        stabilizer.reset();
    }

    public float getMaxRange() {
        return MAX_VALID_RANGE_MM;
    }
}
