package com.example.tofranger;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Scroller;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int SENSOR_TYPE_TOF = 33171040;

    // Unit modes
    private static final int UNIT_MM = 0;
    private static final int UNIT_CM = 1;
    private static final int UNIT_M = 2;
    private static final int UNIT_INCH = 3;
    private static final String[] UNIT_LABELS = {"mm", "cm", "m", "in"};
    private int currentUnit = UNIT_CM;

    // State
    private boolean isHolding = false;
    private float heldDistance = -1;

    // Stats
    private float minValue = Float.MAX_VALUE;
    private float maxValue = 0;
    private float sumValues = 0;
    private int sampleCount = 0;
    private final ArrayList<Float> history = new ArrayList<>();
    private static final int MAX_HISTORY = 500;

    // Sensor
    private SensorManager sensorManager;
    private Sensor tofSensor;

    // UI
    private TextView tvDistance;
    private TextView tvUnit;
    private TextView tvStatus;
    private TextView tvStats;
    private TextView tvHz;
    private TextView tvHoldLabel;
    private TextView tvHistoryCount;
    private TextView tvSensorInfo;

    // Buttons
    private View btnHold;
    private View btnReset;
    private View btnUnit;
    private View btnClearStats;

    // Colors
    private static final int C_BG = 0xFF0A0A1A;
    private static final int C_ACCENT = 0xFF00E5A0;
    private static final int C_ACCENT_DIM = 0xFF00E5A0;
    private static final int C_WARN = 0xFFFFB800;
    private static final int C_ERR = 0xFFFF4466;
    private static final int C_TEXT = 0xFFE0E8F0;
    private static final int C_DIM = 0xFF4A5568;
    private static final int C_CARD = 0xFF1A1F2E;
    private static final int C_CARD_BORDER = 0xFF2D3548;

    private long lastUiUpdate = 0;
    private long lastEventTime = 0;
    private int eventCount = 0;
    private float hz = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(C_BG);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(C_BG);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(48), dp(20), dp(24));
        root.setBackgroundColor(C_BG);

        // === Header ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📐 ToF 测距仪");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle);

        // Sensor info in header
        tvSensorInfo = new TextView(this);
        tvSensorInfo.setTextSize(11);
        tvSensorInfo.setTextColor(C_DIM);
        tvSensorInfo.setGravity(Gravity.END);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.setMarginStart(dp(12));
        header.addView(tvSensorInfo, infoLp);

        root.addView(header);

        // Separator
        root.addView(makeSeparator());

        // === Main Distance Display (big card) ===
        LinearLayout distCard = makeCard();
        distCard.setGravity(Gravity.CENTER_HORIZONTAL);
        distCard.setPadding(dp(24), dp(32), dp(24), dp(20));

        tvHoldLabel = new TextView(this);
        tvHoldLabel.setText("● 测量中");
        tvHoldLabel.setTextSize(12);
        tvHoldLabel.setTextColor(C_ACCENT);
        tvHoldLabel.setGravity(Gravity.CENTER);
        distCard.addView(tvHoldLabel);

        LinearLayout distRow = new LinearLayout(this);
        distRow.setOrientation(LinearLayout.HORIZONTAL);
        distRow.setGravity(Gravity.CENTER);
        distRow.setPadding(0, dp(12), 0, 0);

        tvDistance = new TextView(this);
        tvDistance.setText("--");
        tvDistance.setTextSize(72);
        tvDistance.setTextColor(C_ACCENT);
        tvDistance.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        distRow.addView(tvDistance);

        tvUnit = new TextView(this);
        tvUnit.setText(" cm");
        tvUnit.setTextSize(28);
        tvUnit.setTextColor(C_ACCENT_DIM);
        tvUnit.setGravity(Gravity.CENTER_VERTICAL);
        tvUnit.setPadding(dp(6), 0, 0, 0);
        distRow.addView(tvUnit);

        distCard.addView(distRow);

        // Raw mm display
        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(C_DIM);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(8), 0, 0);
        distCard.addView(tvStatus);

        root.addView(distCard);
        root.addView(makeGap(dp(12)));

        // === Stats Card ===
        LinearLayout statsCard = makeCard();
        statsCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView tvStatsTitle = makeLabel("📊 统计");
        statsCard.addView(tvStatsTitle);

        tvStats = new TextView(this);
        tvStats.setTextSize(13);
        tvStats.setTextColor(C_TEXT);
        tvStats.setLineSpacing(dp(4), 1);
        tvStats.setPadding(0, dp(6), 0, 0);
        updateStatsText();
        statsCard.addView(tvStats);

        root.addView(statsCard);
        root.addView(makeGap(dp(12)));

        // === Buttons Row ===
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        btnHold = makeButton("⏸ 暂停", C_WARN);
        btnReset = makeButton("🔄 重置", 0xFF3B82F6);
        btnUnit = makeButton("📏 单位", 0xFF8B5CF6);
        btnClearStats = makeButton("🗑 清除", 0xFF64748B);

        btnRow.addView(btnHold, new LinearLayout.LayoutParams(0, dp(42), 1));
        btnRow.addView(makeGap(dp(8)), new LinearLayout.LayoutParams(dp(8), 0));
        btnRow.addView(btnReset, new LinearLayout.LayoutParams(0, dp(42), 1));
        btnRow.addView(makeGap(dp(8)), new LinearLayout.LayoutParams(dp(8), 0));
        btnRow.addView(btnUnit, new LinearLayout.LayoutParams(0, dp(42), 1));

        root.addView(btnRow);
        root.addView(makeGap(dp(8)));

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setGravity(Gravity.CENTER);
        btnRow2.addView(btnClearStats, new LinearLayout.LayoutParams(0, dp(42), 1));
        root.addView(btnRow2);

        root.addView(makeGap(dp(12)));

        // === History Card ===
        LinearLayout historyCard = makeCard();
        historyCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setOrientation(LinearLayout.HORIZONTAL);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvHistoryTitle = new TextView(this);
        tvHistoryTitle.setText("📝 历史记录");
        tvHistoryTitle.setTextSize(14);
        tvHistoryTitle.setTextColor(C_TEXT);
        tvHistoryTitle.setTypeface(Typeface.DEFAULT_BOLD);
        historyHeader.addView(tvHistoryTitle);

        tvHistoryCount = new TextView(this);
        tvHistoryCount.setText(" (0)");
        tvHistoryCount.setTextSize(12);
        tvHistoryCount.setTextColor(C_DIM);
        historyHeader.addView(tvHistoryCount);

        historyCard.addView(historyHeader);

        tvHz = new TextView(this);
        tvHz.setTextSize(11);
        tvHz.setTextColor(C_DIM);
        tvHz.setPadding(0, dp(4), 0, 0);
        historyCard.addView(tvHz);

        root.addView(historyCard);

        scrollView.addView(root);
        setContentView(scrollView);

        // Button listeners
        btnHold.setOnClickListener(v -> toggleHold());
        btnReset.setOnClickListener(v -> resetStats());
        btnUnit.setOnClickListener(v -> cycleUnit());
        btnClearStats.setOnClickListener(v -> clearHistory());

        // Init sensor
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
    }

    private void findTofSensor() {
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        tofSensor = null;
        String sensorName = "未找到";

        for (Sensor s : all) {
            String name = s.getName().toLowerCase();
            boolean isTof = s.getType() == SENSOR_TYPE_TOF
                    || name.contains("tof")
                    || name.contains("vl53");

            if (isTof && tofSensor == null) {
                tofSensor = s;
                sensorName = s.getName();
            }
        }

        if (tofSensor == null) {
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) sensorName = tofSensor.getName() + " (降级)";
        }

        tvSensorInfo.setText(sensorName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tofSensor != null) {
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
                && event.sensor.getType() != Sensor.TYPE_PROXIMITY) return;

        if (isHolding) return;

        eventCount++;
        long now = SystemClock.elapsedRealtime();

        if (lastEventTime > 0) {
            long dt = now - lastEventTime;
            if (dt > 0) hz = hz * 0.9f + (1000f / dt) * 0.1f;
        }
        lastEventTime = now;

        if (now - lastUiUpdate < 33) return;
        lastUiUpdate = now;

        float rawMm = event.values[0];

        // Add to history
        if (rawMm > 0) {
            history.add(rawMm);
            if (history.size() > MAX_HISTORY) history.remove(0);

            sampleCount++;
            sumValues += rawMm;
            if (rawMm < minValue) minValue = rawMm;
            if (rawMm > maxValue) maxValue = rawMm;
        }

        updateDisplay(rawMm);
    }

    private void updateDisplay(float rawMm) {
        if (rawMm < 0) {
            tvDistance.setText("ERR");
            tvDistance.setTextColor(C_ERR);
            tvStatus.setText("超出范围 / 无效");
            tvHoldLabel.setText("⚠ 无信号");
            tvHoldLabel.setTextColor(C_ERR);
            return;
        }

        tvDistance.setTextColor(C_ACCENT);
        tvHoldLabel.setTextColor(isHolding ? C_WARN : C_ACCENT);
        tvHoldLabel.setText(isHolding ? "● 已暂停" : "● 测量中");

        // Convert and display
        String valStr;
        String unitStr = UNIT_LABELS[currentUnit];
        float displayVal = convertUnit(rawMm, currentUnit);

        if (currentUnit == UNIT_MM) {
            valStr = String.format(Locale.getDefault(), "%.0f", displayVal);
        } else if (currentUnit == UNIT_CM) {
            valStr = String.format(Locale.getDefault(), "%.1f", displayVal);
        } else if (currentUnit == UNIT_M) {
            valStr = String.format(Locale.getDefault(), "%.3f", displayVal);
        } else {
            valStr = String.format(Locale.getDefault(), "%.2f", displayVal);
        }

        tvDistance.setText(valStr);
        tvUnit.setText(" " + unitStr);

        // Raw info
        tvStatus.setText(String.format(Locale.getDefault(),
                "%.0f mm", rawMm));

        // Stats
        updateStatsText();

        // Hz
        tvHz.setText(String.format(Locale.getDefault(),
                "%.0f Hz | 共 %d 次采样 | %.0f-%.0f mm 量程",
                hz, eventCount,
                Math.max(0, minValue),
                Math.min(maxValue, 4000)));
    }

    private void updateStatsText() {
        if (sampleCount == 0) {
            tvStats.setText("等待数据...");
            tvStats.setTextColor(C_DIM);
            return;
        }
        tvStats.setTextColor(C_TEXT);
        float avg = sumValues / sampleCount;
        String u = UNIT_LABELS[currentUnit];
        tvStats.setText(String.format(Locale.getDefault(),
                "最小: %s %s    最大: %s %s\n平均: %s %s    样本: %d",
                fmt(convertUnit(minValue, currentUnit), currentUnit), u,
                fmt(convertUnit(maxValue, currentUnit), currentUnit), u,
                fmt(convertUnit(avg, currentUnit), currentUnit), u,
                sampleCount));
    }

    private String fmt(float val, int unit) {
        if (unit == UNIT_MM) return String.format(Locale.getDefault(), "%.0f", val);
        if (unit == UNIT_CM) return String.format(Locale.getDefault(), "%.1f", val);
        if (unit == UNIT_M) return String.format(Locale.getDefault(), "%.3f", val);
        return String.format(Locale.getDefault(), "%.2f", val);
    }

    private float convertUnit(float mm, int unit) {
        switch (unit) {
            case UNIT_MM: return mm;
            case UNIT_CM: return mm / 10f;
            case UNIT_M: return mm / 1000f;
            case UNIT_INCH: return mm / 25.4f;
            default: return mm / 10f;
        }
    }

    private void toggleHold() {
        isHolding = !isHolding;
        TextView tv = (TextView) btnHold;
        if (isHolding) {
            tv.setText("▶ 继续");
            tv.setTextColor(C_ACCENT);
            setBackgroundTint(btnHold, C_ACCENT);
        } else {
            tv.setText("⏸ 暂停");
            tv.setTextColor(C_WARN);
            setBackgroundTint(btnHold, C_WARN);
        }
    }

    private void resetStats() {
        minValue = Float.MAX_VALUE;
        maxValue = 0;
        sumValues = 0;
        sampleCount = 0;
        updateStatsText();
    }

    private void clearHistory() {
        history.clear();
        resetStats();
        eventCount = 0;
        tvHistoryCount.setText(" (0)");
    }

    private void cycleUnit() {
        currentUnit = (currentUnit + 1) % 4;
        ((TextView) btnUnit).setText("📏 " + UNIT_LABELS[currentUnit]);
        updateStatsText();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ========== UI Helpers ==========

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(1, C_CARD_BORDER);
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        return card;
    }

    private View makeSeparator() {
        View sep = new View(this);
        sep.setBackgroundColor(C_CARD_BORDER);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return sep;
    }

    private View makeGap(int height) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, height));
        return gap;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(C_TEXT);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);

        // Touch feedback
        btn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.7f);
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
            }
            return false;
        });

        return btn;
    }

    private void setBackgroundTint(View view, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        view.setBackground(bg);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
