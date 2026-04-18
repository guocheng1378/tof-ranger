package com.example.tofranger;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe CSV data recorder for distance measurements.
 *
 * FIX: Uses proper data class (static inner class) instead of float[]
 * to avoid float→long precision loss for timestamps.
 * FIX: All access to the data list is synchronized consistently.
 */
public class DataRecorder {

    /** Immutable data point with proper types. */
    private static class DataPoint {
        final float distanceMm;
        final float tiltDeg;
        final long timestampMs;

        DataPoint(float distanceMm, float tiltDeg, long timestampMs) {
            this.distanceMm = distanceMm;
            this.tiltDeg = tiltDeg;
            this.timestampMs = timestampMs;
        }
    }

    private final List<DataPoint> data = new ArrayList<>();
    private final Object lock = new Object();
    private volatile boolean recording = false;
    private volatile boolean continuousMode = false;
    private long recordStartTime = 0;
    private long lastContinuousMs = 0;
    private static final long CONTINUOUS_INTERVAL_MS = 200;

    public boolean isRecording() { return recording; }
    public boolean isContinuousMode() { return continuousMode; }

    public void setRecording(boolean r) { this.recording = r; }
    public void setContinuousMode(boolean c) { this.continuousMode = c; }

    public void startRecording() {
        synchronized (lock) { data.clear(); }
        recordStartTime = System.currentTimeMillis();
        recording = true;
        lastContinuousMs = 0;
    }

    public void stopRecording() {
        recording = false;
        continuousMode = false;
    }

    /** Add a single data point (manual record). */
    public void addPoint(float distanceMm, float tiltDeg) {
        if (!recording || distanceMm < 0) return;
        synchronized (lock) {
            data.add(new DataPoint(distanceMm, tiltDeg, System.currentTimeMillis()));
        }
    }

    /** Add continuous data point if interval has elapsed. Returns true if added. */
    public boolean addContinuous(float distanceMm, float tiltDeg) {
        if (!recording || !continuousMode || distanceMm < 0) return false;
        long now = System.currentTimeMillis();
        if (now - lastContinuousMs < CONTINUOUS_INTERVAL_MS) return false;
        lastContinuousMs = now;
        synchronized (lock) {
            data.add(new DataPoint(distanceMm, tiltDeg, now));
        }
        return true;
    }

    public int getCount() {
        synchronized (lock) { return data.size(); }
    }

    public long getElapsedSeconds() {
        if (recordStartTime <= 0) return 0;
        return (System.currentTimeMillis() - recordStartTime) / 1000;
    }

    public void clear() {
        synchronized (lock) { data.clear(); }
        recordStartTime = 0;
    }

    /**
     * Export data to CSV file on background thread.
     */
    public void exportCsv(Context context, ExportCallback callback) {
        final List<DataPoint> snapshot;
        synchronized (lock) { snapshot = new ArrayList<>(data); }
        if (snapshot.isEmpty()) {
            callback.onError("无数据");
            return;
        }
        new Thread(() -> {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                String filename = "tof_" + sdf.format(new Date()) + ".csv";
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (dir == null) dir = context.getFilesDir();
                File file = new File(dir, filename);

                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write("timestamp_ms,distance_mm,tilt_deg");
                writer.newLine();
                for (DataPoint dp : snapshot) {
                    writer.write(String.format(Locale.US, "%d,%.1f,%.1f",
                            dp.timestampMs, dp.distanceMm, dp.tiltDeg));
                    writer.newLine();
                }
                writer.close();
                callback.onSuccess(file, filename);
            } catch (IOException e) {
                callback.onError("导出失败: " + e.getMessage());
            }
        }).start();
    }

    public interface ExportCallback {
        void onSuccess(File file, String filename);
        void onError(String message);
    }
}
