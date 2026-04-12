package com.example.tofranger;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int SENSOR_TYPE_TOF = 33171040;

    private SensorManager sensorManager;
    private Sensor tofSensor;

    private TextView tvDistance;
    private TextView tvUnit;
    private TextView tvStatus;
    private TextView tvHz;
    private TextView tvInfo;
    private TextView tvLog;

    private long lastUiUpdate = 0;
    private long lastEventTime = 0;
    private int eventCount = 0;
    private float hz = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Root layout
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F0F23);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // Title
        addText(root, "📐 ToF 测距仪", 24, 0xFFFFFFFF, Gravity.CENTER, dp(0), dp(4));

        // Sensor info box
        tvInfo = new TextView(this);
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(0xFF667788);
        tvInfo.setGravity(Gravity.CENTER);
        root.addView(tvInfo);

        // Distance display
        LinearLayout distRow = new LinearLayout(this);
        distRow.setOrientation(LinearLayout.HORIZONTAL);
        distRow.setGravity(Gravity.CENTER);
        distRow.setPadding(0, dp(48), 0, 0);

        tvDistance = new TextView(this);
        tvDistance.setText("--");
        tvDistance.setTextSize(100);
        tvDistance.setTextColor(0xFF00FF88);
        tvDistance.setTypeface(Typeface.MONOSPACE);
        distRow.addView(tvDistance);

        tvUnit = new TextView(this);
        tvUnit.setText("");
        tvUnit.setTextSize(36);
        tvUnit.setTextColor(0xFF00FF88);
        tvUnit.setGravity(Gravity.CENTER_VERTICAL);
        distRow.addView(tvUnit);

        root.addView(distRow);

        // Status
        tvStatus = new TextView(this);
        tvStatus.setTextSize(16);
        tvStatus.setTextColor(0xFFFFAA00);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(16), 0, 0);
        root.addView(tvStatus);

        // Hz
        tvHz = new TextView(this);
        tvHz.setTextSize(12);
        tvHz.setTextColor(0xFF556677);
        tvHz.setGravity(Gravity.CENTER);
        tvHz.setPadding(0, dp(8), 0, 0);
        root.addView(tvHz);

        // Log
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(0xFF445566);
        tvLog.setPadding(0, dp(24), 0, 0);
        root.addView(tvLog);

        setContentView(root);

        // Init sensor
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
    }

    private void findTofSensor() {
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        List<String> relevant = new ArrayList<>();
        tofSensor = null;

        for (Sensor s : all) {
            String name = s.getName().toLowerCase();
            boolean isTof = s.getType() == SENSOR_TYPE_TOF
                    || name.contains("tof")
                    || name.contains("vl53");
            boolean isProx = s.getType() == Sensor.TYPE_PROXIMITY;

            if (isTof || isProx) {
                String line = String.format("%s type=%d %s",
                        isTof ? "⭐" : "📍", s.getType(), s.getName());
                relevant.add(line);

                if (isTof && tofSensor == null) {
                    tofSensor = s;
                }
            }
        }

        if (tofSensor == null) {
            // Fallback to proximity
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) {
                relevant.add("⚠️ 降级使用 Proximity");
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String r : relevant) sb.append(r).append("\n");
        tvInfo.setText(sb.toString());

        if (tofSensor != null) {
            tvStatus.setText("✅ 就绪 — 对准目标物体");
            tvStatus.setTextColor(0xFF00FF88);
            log("传感器: " + tofSensor.getName() + " type=" + tofSensor.getType());
        } else {
            tvStatus.setText("❌ 未找到 ToF 传感器");
            tvStatus.setTextColor(0xFFFF4444);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tofSensor != null) {
            // SENSOR_DELAY_GAME ≈ 20ms (50Hz), fast enough
            sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != SENSOR_TYPE_TOF
                && event.sensor.getType() != Sensor.TYPE_PROXIMITY) {
            return;
        }

        eventCount++;
        long now = SystemClock.elapsedRealtime();

        // Calc Hz
        if (lastEventTime > 0) {
            long dt = now - lastEventTime;
            if (dt > 0) {
                float instantHz = 1000f / dt;
                hz = hz * 0.9f + instantHz * 0.1f; // smoothing
            }
        }
        lastEventTime = now;

        // Throttle UI updates to ~30fps
        if (now - lastUiUpdate < 33) return;
        lastUiUpdate = now;

        float distance = event.values[0];
        int valCount = event.values.length;

        // Debug: multi-zone sensor
        if (valCount > 1) {
            log(String.format(Locale.getDefault(),
                    "多区域: %d 值, [0]=%.1f [1]=%.1f [2]=%.1f [3]=%.1f",
                    valCount, event.values[0],
                    valCount > 1 ? event.values[1] : 0,
                    valCount > 2 ? event.values[2] : 0,
                    valCount > 3 ? event.values[3] : 0));
        }

        if (distance < 0) {
            tvDistance.setText("ERR");
            tvUnit.setText("");
            tvStatus.setText("超出范围");
            tvStatus.setTextColor(0xFFFF4444);
            tvDistance.setTextColor(0xFFFF4444);
        } else {
            tvDistance.setTextColor(0xFF00FF88);
            tvStatus.setTextColor(0xFFFFAA00);

            // Determine unit based on magnitude
            // VL53Lx typically returns mm
            if (distance > 1000) {
                // mm -> meters
                float meters = distance / 1000f;
                tvDistance.setText(String.format(Locale.getDefault(), "%.2f", meters));
                tvUnit.setText(" m");
                tvStatus.setText(String.format(Locale.getDefault(),
                        "%d mm", (int) distance));
            } else if (distance > 10) {
                // mm -> cm
                float cm = distance / 10f;
                tvDistance.setText(String.format(Locale.getDefault(), "%.1f", cm));
                tvUnit.setText(" cm");
                tvStatus.setText(String.format(Locale.getDefault(),
                        "%d mm", (int) distance));
            } else {
                // Very close or already in cm
                tvDistance.setText(String.format(Locale.getDefault(), "%.1f", distance));
                tvUnit.setText(" cm");
                tvStatus.setText("⚠️ 极近距离");
            }
        }

        tvHz.setText(String.format(Locale.getDefault(),
                "%.0f Hz | events: %d", hz, eventCount));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        String[] labels = {"不可靠", "低", "中", "高"};
        if (accuracy >= 0 && accuracy < labels.length) {
            log("精度: " + labels[accuracy]);
        }
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            String current = tvLog.getText().toString();
            String[] lines = current.split("\n");
            StringBuilder sb = new StringBuilder();
            sb.append(msg).append("\n");
            int start = Math.max(0, lines.length - 8);
            for (int i = start; i < lines.length; i++) {
                if (!lines[i].isEmpty()) sb.append(lines[i]).append("\n");
            }
            tvLog.setText(sb.toString());
        });
    }

    private void addText(LinearLayout parent, String text, float size,
                         int color, int gravity, int topPad, int bottomPad) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setGravity(gravity);
        tv.setPadding(0, topPad,0, bottomPad);
        parent.addView(tv);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
