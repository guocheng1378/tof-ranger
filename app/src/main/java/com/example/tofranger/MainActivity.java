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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int SENSOR_TYPE_TOF = 33171040;

    // Unit modes
    private static final int UNIT_MM = 0, UNIT_CM = 1, UNIT_M = 2, UNIT_INCH = 3;
    private static final String[] UNIT_LABELS = {"mm", "cm", "m", "in"};
    private int currentUnit = UNIT_CM;

    // State
    private boolean isHolding = false;

    // Signal processing
    private DistanceFilter primaryFilter = new DistanceFilter(7, 0.20f, 600f);
    private DistanceFilter[] targetFilters;
    private static final int MAX_TARGETS = 4;

    // Statistics
    private DistanceStats stats = new DistanceStats(200);
    private final ArrayList<Float> rawHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 500;

    // Sensor
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private boolean isProximityFallback = false;

    // UI
    private TextView tvDistance, tvUnit, tvRawInfo, tvStatus;
    private TextView tvStats, tvHz, tvHoldLabel, tvHistoryCount, tvSensorInfo;
    private TextView tvQuality, tvTrend, tvMultiTarget;
    private TextView tvConfidenceBar;

    // Buttons
    private View btnHold, btnReset, btnUnit, btnClearStats;

    // Colors
    private static final int C_BG = 0xFF0A0A1A;
    private static final int C_ACCENT = 0xFF00E5A0;
    private static final int C_WARN = 0xFFFFB800;
    private static final int C_ERR = 0xFFFF4466;
    private static final int C_TEXT = 0xFFE0E8F0;
    private static final int C_DIM = 0xFF4A5568;
    private static final int C_CARD = 0xFF1A1F2E;
    private static final int C_CARD_BORDER = 0xFF2D3548;
    private static final int C_GOOD = 0xFF00E5A0;
    private static final int C_FAIR = 0xFFFFB800;
    private static final int C_POOR = 0xFFFF4466;

    private long lastUiUpdate = 0;
    private int eventCount = 0;
    private float lastFiltered = -1;

    // Multi-target tracking
    private float[] lastTargetDists = new float[MAX_TARGETS];
    private boolean multiTargetVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(C_BG);

        // Init filters for multi-target
        targetFilters = new DistanceFilter[MAX_TARGETS];
        for (int i = 0; i < MAX_TARGETS; i++) {
            targetFilters[i] = new DistanceFilter(5, 0.30f, 1000f);
        }

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

        tvSensorInfo = new TextView(this);
        tvSensorInfo.setTextSize(11);
        tvSensorInfo.setTextColor(C_DIM);
        tvSensorInfo.setGravity(Gravity.END);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.setMarginStart(dp(12));
        header.addView(tvSensorInfo, infoLp);

        root.addView(header);
        root.addView(makeSeparator());

        // === Main Distance Card ===
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
        tvUnit.setTextColor(C_ACCENT);
        tvUnit.setGravity(Gravity.CENTER_VERTICAL);
        tvUnit.setPadding(dp(6), 0, 0, 0);
        distRow.addView(tvUnit);

        distCard.addView(distRow);

        // Raw info line
        tvRawInfo = new TextView(this);
        tvRawInfo.setTextSize(13);
        tvRawInfo.setTextColor(C_DIM);
        tvRawInfo.setGravity(Gravity.CENTER);
        tvRawInfo.setPadding(0, dp(8), 0, 0);
        distCard.addView(tvRawInfo);

        // Quality indicator
        tvQuality = new TextView(this);
        tvQuality.setTextSize(12);
        tvQuality.setGravity(Gravity.CENTER);
        tvQuality.setPadding(0, dp(4), 0, 0);
        distCard.addView(tvQuality);

        // Confidence bar
        tvConfidenceBar = new TextView(this);
        tvConfidenceBar.setTextSize(11);
        tvConfidenceBar.setTypeface(Typeface.MONOSPACE);
        tvConfidenceBar.setTextColor(C_DIM);
        tvConfidenceBar.setGravity(Gravity.CENTER);
        tvConfidenceBar.setPadding(0, dp(2), 0, 0);
        distCard.addView(tvConfidenceBar);

        root.addView(distCard);
        root.addView(makeGap(dp(12)));

        // === Multi-Target Card (hidden by default) ===
        LinearLayout mtCard = makeCard();
        mtCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        mtCard.setVisibility(View.GONE);
        mtCard.setTag("multi_target_card");

        TextView mtTitle = makeLabel("🎯 多目标检测");
        mtCard.addView(mtTitle);

        tvMultiTarget = new TextView(this);
        tvMultiTarget.setTextSize(13);
        tvMultiTarget.setTextColor(C_TEXT);
        tvMultiTarget.setLineSpacing(dp(4), 1);
        tvMultiTarget.setPadding(0, dp(6), 0, 0);
        mtCard.addView(tvMultiTarget);

        root.addView(mtCard);
        root.addView(makeGap(dp(12)));

        // === Stats Card ===
        LinearLayout statsCard = makeCard();
        statsCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout statsHeader = new LinearLayout(this);
        statsHeader.setOrientation(LinearLayout.HORIZONTAL);
        statsHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvStatsTitle = new TextView(this);
        tvStatsTitle.setText("📊 统计");
        tvStatsTitle.setTextSize(14);
        tvStatsTitle.setTextColor(C_TEXT);
        tvStatsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statsHeader.addView(tvStatsTitle);

        tvTrend = new TextView(this);
        tvTrend.setTextSize(12);
        tvTrend.setPadding(dp(8), 0, 0, 0);
        statsHeader.addView(tvTrend);

        statsCard.addView(statsHeader);

        tvStats = new TextView(this);
        tvStats.setTextSize(13);
        tvStats.setTextColor(C_TEXT);
        tvStats.setLineSpacing(dp(4), 1);
        tvStats.setPadding(0, dp(6), 0, 0);
        statsCard.addView(tvStats);

        root.addView(statsCard);
        root.addView(makeGap(dp(12)));

        // === Buttons Row 1 ===
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        btnHold = makeButton("⏸ 暂停", C_WARN);
        btnReset = makeButton("🔄 重置", 0xFF3B82F6);
        btnUnit = makeButton("📏 单位", 0xFF8B5CF6);
        btnClearStats = makeButton("🗑 清除", 0xFF64748B);

        btnRow.addView(btnHold, lp(0, dp(42), 1));
        btnRow.addView(makeGap(dp(8)), lp(dp(8), 0, 0));
        btnRow.addView(btnReset, lp(0, dp(42), 1));
        btnRow.addView(makeGap(dp(8)), lp(dp(8), 0, 0));
        btnRow.addView(btnUnit, lp(0, dp(42), 1));

        root.addView(btnRow);
        root.addView(makeGap(dp(8)));

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setGravity(Gravity.CENTER);
        btnRow2.addView(btnClearStats, lp(0, dp(42), 1));
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

        // Status line
        tvStatus = new TextView(this);
        tvStatus.setTextSize(11);
        tvStatus.setTextColor(C_DIM);
        tvStatus.setPadding(0, dp(4), 0, 0);
        historyCard.addView(tvStatus);

        root.addView(historyCard);

        scrollView.addView(root);
        setContentView(scrollView);

        // Listeners
        btnHold.setOnClickListener(v -> toggleHold());
        btnReset.setOnClickListener(v -> resetAll());
        btnUnit.setOnClickListener(v -> cycleUnit());
        btnClearStats.setOnClickListener(v -> clearHistory());

        // Sensor init
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
            if (tofSensor != null) {
                sensorName = tofSensor.getName() + " (降级-仅Proximity)";
                isProximityFallback = true;
            }
        }

        tvSensorInfo.setText(sensorName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tofSensor != null) {
            sensorManager.registerListener(this, tofSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type != SENSOR_TYPE_TOF && type != Sensor.TYPE_PROXIMITY) return;
        if (isHolding) return;

        eventCount++;
        stats.tickHz();

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdate < 33) return; // ~30fps UI cap
        lastUiUpdate = now;

        // === Primary distance (target 0) ===
        float rawMm = event.values[0];

        // === Multi-target detection (VL53L5CX returns up to 4 targets per zone) ===
        boolean hasMultiTarget = !isProximityFallback && event.values.length > 1;
        float[] targetDistances = new float[MAX_TARGETS];
        int validTargets = 0;

        if (hasMultiTarget) {
            for (int t = 0; t < MAX_TARGETS && t < event.values.length; t++) {
                float tDist = event.values[t];
                if (tDist > 0 && tDist < 4000) {
                    targetDistances[t] = targetFilters[t].filter(tDist);
                    if (targetFilters[t].isWarmedUp()) {
                        validTargets++;
                    }
                } else {
                    targetDistances[t] = -1;
                }
            }
            lastTargetDists = targetDistances;
        }

        // === Signal processing pipeline ===
        // raw → outlier rejection → median → EMA → display
        float filtered = primaryFilter.filter(rawMm);

        // Record stats
        if (filtered > 0) {
            stats.add(filtered);
            rawHistory.add(rawMm);
            if (rawHistory.size() > MAX_HISTORY) rawHistory.remove(0);
        }

        lastFiltered = filtered;
        updateDisplay(rawMm, filtered, hasMultiTarget, validTargets, targetDistances);
    }

    private void updateDisplay(float rawMm, float filtered,
                                boolean hasMultiTarget, int validTargets,
                                float[] targetDists) {
        // --- Main distance ---
        if (filtered < 0 || rawMm < 0) {
            tvDistance.setText("ERR");
            tvDistance.setTextColor(C_ERR);
            tvRawInfo.setText("超出范围 / 无效");
            tvHoldLabel.setText("⚠ 无信号");
            tvHoldLabel.setTextColor(C_ERR);
            tvQuality.setText("");
            tvConfidenceBar.setText("");
            return;
        }

        tvDistance.setTextColor(C_ACCENT);
        tvHoldLabel.setTextColor(isHolding ? C_WARN : C_ACCENT);
        tvHoldLabel.setText(isHolding ? "● 已暂停" : "● 测量中");

        // Display filtered value
        float displayVal = convertUnit(filtered, currentUnit);
        String valStr = fmt(displayVal, currentUnit);
        tvDistance.setText(valStr);
        tvUnit.setText(" " + UNIT_LABELS[currentUnit]);

        // Raw vs filtered info
        float diff = Math.abs(rawMm - filtered);
        tvRawInfo.setText(String.format(Locale.getDefault(),
                "原始: %.0f mm → 滤波: %.0f mm (Δ%.0f)",
                rawMm, filtered, diff));

        // --- Quality indicator (based on std deviation) ---
        float stdDev = stats.getStdDev();
        String qualityLabel;
        int qualityColor;
        if (stdDev < 5) {
            qualityLabel = "🟢 高精度 (σ=" + fmt(stdDev, 0) + "mm)";
            qualityColor = C_GOOD;
        } else if (stdDev < 20) {
            qualityLabel = "🟡 良好 (σ=" + fmt(stdDev, 0) + "mm)";
            qualityColor = C_FAIR;
        } else {
            qualityLabel = "🔴 波动大 (σ=" + fmt(stdDev, 0) + "mm)";
            qualityColor = C_POOR;
        }
        tvQuality.setText(qualityLabel);
        tvQuality.setTextColor(qualityColor);

        // Confidence bar
        int barLen = Math.max(0, Math.min(20, (int) (20 - stdDev)));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            bar.append(i < barLen ? "█" : "░");
        }
        bar.append("]");
        tvConfidenceBar.setText(bar.toString());
        tvConfidenceBar.setTextColor(qualityColor);

        // --- Trend indicator ---
        float trend = stats.getTrend();
        String trendStr;
        if (Math.abs(trend) < 1) trendStr = "→ 稳定";
        else if (trend > 0) trendStr = String.format(Locale.getDefault(), "↑ 远离 +%.0fmm/s", trend);
        else trendStr = String.format(Locale.getDefault(), "↓ 靠近 %.0fmm/s", trend);
        tvTrend.setText(trendStr);
        tvTrend.setTextColor(Math.abs(trend) < 1 ? C_ACCENT : C_WARN);

        // --- Multi-target display ---
        View mtCard = findViewByTag("multi_target_card");
        if (hasMultiTarget && validTargets > 1) {
            if (mtCard != null) mtCard.setVisibility(View.VISIBLE);
            multiTargetVisible = true;
            StringBuilder mt = new StringBuilder();
            String u = UNIT_LABELS[currentUnit];
            for (int t = 0; t < MAX_TARGETS; t++) {
                if (targetDists[t] > 0 && targetFilters[t].isWarmedUp()) {
                    float conv = convertUnit(targetDists[t], currentUnit);
                    String label = t == 0 ? "🎯 主目标" : "🎯 目标 " + (t + 1);
                    mt.append(String.format(Locale.getDefault(),
                            "%s: %s %s\n", label, fmt(conv, currentUnit), u));
                }
            }
            if (mt.length() > 0) mt.setLength(mt.length() - 1); // trim trailing \n
            tvMultiTarget.setText(mt.toString());
        } else {
            if (mtCard != null) mtCard.setVisibility(View.GONE);
            multiTargetVisible = false;
        }

        // --- Stats ---
        updateStatsText();

        // --- Hz + info ---
        tvHz.setText(String.format(Locale.getDefault(),
                "采样: %d Hz | 共 %d 次 | 窗口: %d | 滤波器: Median+EMA",
                stats.getActualHz(), eventCount, stats.getWindowSize()));

        tvHistoryCount.setText(String.format(Locale.getDefault(),
                " (%d)", rawHistory.size()));

        // Sensor mode info
        if (isProximityFallback) {
            tvStatus.setText("⚠ 降级模式 — 仅 Proximity 传感器");
            tvStatus.setTextColor(C_WARN);
        } else {
            String mode;
            if (filtered < 200) mode = "近距离模式 (高精度)";
            else if (filtered < 1000) mode = "标准模式";
            else mode = "远距离模式";
            tvStatus.setText(String.format(Locale.getDefault(),
                    "模式: %s | 量程: %.0f-%.0f mm", mode,
                    Math.max(0, stats.getMin()), stats.getMax()));
            tvStatus.setTextColor(C_DIM);
        }
    }

    private void updateStatsText() {
        if (stats.getSampleCount() == 0) {
            tvStats.setText("等待数据...");
            tvStats.setTextColor(C_DIM);
            return;
        }
        tvStats.setTextColor(C_TEXT);
        String u = UNIT_LABELS[currentUnit];
        float median = stats.getMedian();
        tvStats.setText(String.format(Locale.getDefault(),
                "最小: %s %s    最大: %s %s\n" +
                "平均: %s %s    中位: %s %s\n" +
                "标准差: %s    样本: %d",
                fmt(convertUnit(stats.getMin(), currentUnit), currentUnit), u,
                fmt(convertUnit(stats.getMax(), currentUnit), currentUnit), u,
                fmt(convertUnit(stats.getAvg(), currentUnit), currentUnit), u,
                fmt(convertUnit(median, currentUnit), currentUnit), u,
                fmt(stats.getStdDev(), 1) + " mm",
                stats.getSampleCount()));
    }

    private String fmt(float val, int unit) {
        switch (unit) {
            case UNIT_MM: return String.format(Locale.getDefault(), "%.0f", val);
            case UNIT_CM: return String.format(Locale.getDefault(), "%.1f", val);
            case UNIT_M: return String.format(Locale.getDefault(), "%.3f", val);
            case UNIT_INCH: return String.format(Locale.getDefault(), "%.2f", val);
            default: return String.format(Locale.getDefault(), "%.1f", val);
        }
    }

    // Overload for raw mm values that need decimal formatting
    private String fmt(float val, int decimals) {
        return String.format(Locale.getDefault(), "%." + decimals + "f", val);
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

    private void resetAll() {
        stats.reset();
        primaryFilter.reset();
        for (DistanceFilter f : targetFilters) f.reset();
        eventCount = 0;
        lastFiltered = -1;
        rawHistory.clear();
        updateStatsText();
    }

    private void clearHistory() {
        rawHistory.clear();
        stats.reset();
        eventCount = 0;
        tvHistoryCount.setText(" (0)");
        updateStatsText();
    }

    private void cycleUnit() {
        currentUnit = (currentUnit + 1) % 4;
        ((TextView) btnUnit).setText("📏 " + UNIT_LABELS[currentUnit]);
        updateStatsText();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ========== UI Helpers ==========

    private View findViewByTag(String tag) {
        // Simple recursive search
        return findViewWithTag(tag);
    }

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

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
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
