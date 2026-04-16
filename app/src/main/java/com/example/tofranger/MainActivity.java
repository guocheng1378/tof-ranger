package com.example.tofranger;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.tofranger.view.GlassBarView;
import com.example.tofranger.view.GlassButtonView;
import com.example.tofranger.view.GlassCardView;
import com.example.tofranger.view.QualityBarView;
import com.example.tofranger.view.ThemeColors;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends ComponentActivity implements SensorEventListener {

    // ── Sensor ──
    private static final int SENSOR_TYPE_MIUI_TOF = 33171040;
    private static final float MAX_VALID_RANGE_MM = 4000f;
    private boolean isProximityFallback = false;
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private boolean sensorRegistered = false;

    // ── State ──
    private volatile float currentDistance = -1;
    private volatile float smoothDisplay = -1;
    private volatile float filteredDistance = -1;
    private volatile float lastRawSensorValue = -1;
    private boolean isLocked = false;
    private boolean isPaused = false;
    private int unitMode = 0;
    private boolean moreExpanded = false;
    private boolean debugVisible = false;
    private boolean continuousMode = false;
    private long lastContinuousCsv = 0;
    private static final long CONTINUOUS_CSV_INTERVAL_MS = 200;
    private static final long UI_UPDATE_INTERVAL_MS = 50;
    private long lastUiUpdateMs = 0;

    // ── Filter & Stats ──
    private DistanceFilter filter;
    private DistanceStats stats;
    private ShakeDetector shakeDetector;
    private Stabilizer stabilizer;
    private TiltCompensator tiltCompensator;

    // ── CSV (thread-safe) ──
    private final CopyOnWriteArrayList<float[]> csvData = new CopyOnWriteArrayList<>();
    private boolean isRecording = false;
    private long recordStartTime = 0;

    // ── UI ──
    private FrameLayout rootLayout;
    private ScrollView scrollView;
    private LinearLayout contentLayout;
    private LinearLayout morePanel;

    private GlassCardView distanceCard;
    private TextView valueText;
    private TextView unitText;
    private TextView statusText;
    private QualityBarView qualityBar;
    private TextView debugText;
    private TextView statMinText, statMaxText, statAvgText, statStdText;

    private FrameLayout bottomBarFloat;
    private GlassButtonView lockBtn, pauseBtn, recordBtn, unitBtn, moreBtn;
    private GlassButtonView resetBtn, debugBtn, calibrateBtn, continuousBtn, themeBtn;

    private TextView recordStatusText, recordCountText, recordTimeText, recordDistText;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge
        EdgeToEdge.enable(this);

        // Restore state
        if (savedInstanceState != null) {
            isLightTheme = savedInstanceState.getBoolean("isLight", true);
            unitMode = savedInstanceState.getInt("unitMode", 0);
            isLocked = savedInstanceState.getBoolean("isLocked", false);
            isPaused = savedInstanceState.getBoolean("isPaused", false);
            debugVisible = savedInstanceState.getBoolean("debugVisible", false);
        }
        ThemeColors.apply(isLightTheme);

        // Init helpers — tuned filter: window=7, alpha=0.25, maxJump=150mm, maxRange=4000mm
        filter = new DistanceFilter(7, 0.25f, 150, MAX_VALID_RANGE_MM);
        stats = new DistanceStats(200);
        shakeDetector = new ShakeDetector();
        stabilizer = new Stabilizer();
        tiltCompensator = new TiltCompensator();

        buildUI();
        setContentView(rootLayout);

        // Handle system insets
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            contentLayout.setPadding(dp(20), top + dp(16), dp(20), dp(120));
            return insets;
        });

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isLight", ThemeColors.isLight);
        outState.putInt("unitMode", unitMode);
        outState.putBoolean("isLocked", isLocked);
        outState.putBoolean("isPaused", isPaused);
        outState.putBoolean("debugVisible", debugVisible);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterSensors();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
                event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                isLocked = !isLocked;
                updateLockButton();
                vibrate(30);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                recordSingleDataPoint();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ─────────────────────────────────────────────
    //  Theme (static so extracted Views can read)
    // ─────────────────────────────────────────────

    private boolean isLightTheme = true;

    // ─────────────────────────────────────────────
    //  Data recording
    // ─────────────────────────────────────────────

    private void recordSingleDataPoint() {
        float dist = (filteredDistance >= 0) ? filteredDistance : currentDistance;
        if (dist >= 0 && dist <= MAX_VALID_RANGE_MM) {
            csvData.add(new float[]{dist, tiltCompensator.getPitchDegrees(), System.currentTimeMillis()});
            final int count = csvData.size();
            final String distStr = formatDistance(dist);
            mainHandler.post(() -> {
                if (recordCountText != null) recordCountText.setText(count + " 条数据");
                if (recordStatusText != null) {
                    recordStatusText.setText("● 已记录");
                    recordStatusText.setTextColor(ThemeColors.ACCENT);
                }
                if (recordDistText != null) recordDistText.setText("最近: " + distStr);
            });
            vibrate(50);
        }
    }

    private String formatDistance(float distMm) {
        switch (unitMode) {
            case 1: return String.format(Locale.US, "%.1f mm", distMm);
            case 2: return String.format(Locale.US, "%.2f in", distMm / 25.4f);
            default: return String.format(Locale.US, "%.1f cm", distMm / 10f);
        }
    }

    // ─────────────────────────────────────────────
    //  UI Building
    // ─────────────────────────────────────────────

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUI() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(ThemeColors.BG);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        contentLayout.setPadding(dp(20), dp(48), dp(20), dp(120));

        buildDistanceCard();
        buildRecordSection();
        buildStatusSection();
        buildDebugSection();

        scrollView.addView(contentLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildBottomBar();
        buildMorePanel();

        rootLayout.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        morePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        moreLp.bottomMargin = dp(88);
        rootLayout.addView(morePanel, moreLp);

        // Fixed height bar
        int barHeight = dp(16) + dp(10) + dp(52) + dp(10);
        rootLayout.addView(bottomBarFloat, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM));
    }

    private void buildDistanceCard() {
        distanceCard = new GlassCardView(this);
        distanceCard.setAccentTint(ThemeColors.ACCENT);

        LinearLayout cardInner = new LinearLayout(this);
        cardInner.setOrientation(LinearLayout.VERTICAL);
        cardInner.setGravity(Gravity.CENTER);
        cardInner.setPadding(dp(24), dp(32), dp(24), dp(24));

        valueText = new TextView(this);
        valueText.setText("—");
        valueText.setTextColor(ThemeColors.TEXT);
        valueText.setTextSize(72);
        valueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valueText.setGravity(Gravity.CENTER);
        cardInner.addView(valueText);

        unitText = new TextView(this);
        unitText.setText("cm");
        unitText.setTextColor(ThemeColors.ACCENT);
        unitText.setTextSize(20);
        unitText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        unitText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unitLp.topMargin = dp(4);
        cardInner.addView(unitText, unitLp);

        qualityBar = new QualityBarView(this);
        LinearLayout.LayoutParams qbLp = new LinearLayout.LayoutParams(dp(200), dp(6));
        qbLp.topMargin = dp(16);
        cardInner.addView(qualityBar, qbLp);

        statusText = new TextView(this);
        statusText.setText("寻找传感器…");
        statusText.setTextColor(ThemeColors.TEXT_DIM);
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        cardInner.addView(statusText, slp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(24));
        distanceCard.addView(cardInner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentLayout.addView(distanceCard, cardLp);
    }

    private void buildStatusSection() {
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER);
        statsRow.setPadding(0, dp(8), 0, dp(8));

        String[] labels = {"min", "max", "avg", "σ"};
        TextView[] refs = new TextView[4];
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("  ·  ");
                sep.setTextColor(ThemeColors.SEP_DIM);
                sep.setTextSize(12);
                statsRow.addView(sep);
            }
            TextView tv = new TextView(this);
            tv.setText(labels[i] + ": —");
            tv.setTextColor(ThemeColors.TEXT_DIM);
            tv.setTextSize(12);
            refs[i] = tv;
            statsRow.addView(tv);
        }
        statMinText = refs[0]; statMaxText = refs[1]; statAvgText = refs[2]; statStdText = refs[3];
        statsRow.setVisibility(View.GONE);
        contentLayout.addView(statsRow);
    }

    private void buildDebugSection() {
        debugText = new TextView(this);
        debugText.setTextColor(ThemeColors.DEBUG_DIM);
        debugText.setTextSize(11);
        debugText.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(12);
        contentLayout.addView(debugText, dlp);
    }

    private void buildRecordSection() {
        GlassCardView recordCard = new GlassCardView(this);
        recordCard.setAccentTint(0xFFFF3B30);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(20), dp(16), dp(20), dp(16));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        recordStatusText = new TextView(this);
        recordStatusText.setText("数据记录");
        recordStatusText.setTextColor(ThemeColors.TEXT);
        recordStatusText.setTextSize(16);
        recordStatusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        infoCol.addView(recordStatusText);

        recordCountText = new TextView(this);
        recordCountText.setText("0 条数据");
        recordCountText.setTextColor(ThemeColors.TEXT_DIM);
        recordCountText.setTextSize(13);
        LinearLayout.LayoutParams cntLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cntLp.topMargin = dp(4);
        infoCol.addView(recordCountText, cntLp);

        recordTimeText = new TextView(this);
        recordTimeText.setText("0:00");
        recordTimeText.setTextColor(ThemeColors.TEXT_DIM);
        recordTimeText.setTextSize(12);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dp(2);
        infoCol.addView(recordTimeText, timeLp);

        recordDistText = new TextView(this);
        recordDistText.setText("");
        recordDistText.setTextColor(ThemeColors.ACCENT);
        recordDistText.setTextSize(14);
        recordDistText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams distLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        distLp.topMargin = dp(4);
        infoCol.addView(recordDistText, distLp);

        inner.addView(infoCol, infoLp);

        recordBtn = new GlassButtonView(this);
        recordBtn.setIconType(GlassButtonView.ICON_RECORD);
        recordBtn.setLabel(isRecording ? "停止" : "开始");
        recordBtn.setAccentColor(0xFFFF3B30);
        recordBtn.setActive(isRecording);
        recordBtn.setOnPress(() -> {
            isRecording = !isRecording;
            if (isRecording) {
                csvData.clear();
                recordStartTime = System.currentTimeMillis();
                recordBtn.setLabel("停止");
                recordBtn.setActive(true);
                recordStatusText.setText("● 记录中");
                recordStatusText.setTextColor(0xFFFF3B30);
                continuousMode = true;
                recordSingleDataPoint();
            } else {
                continuousMode = false;
                recordBtn.setLabel("开始");
                recordBtn.setActive(false);
                recordStatusText.setText("数据记录");
                recordStatusText.setTextColor(ThemeColors.TEXT);
                if (!csvData.isEmpty()) exportCsv();
            }
            vibrate(50);
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(56), dp(52));
        inner.addView(recordBtn, btnLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(16));
        recordCard.addView(inner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentLayout.addView(recordCard, cardLp);
    }

    private void buildBottomBar() {
        FrameLayout floatContainer = new FrameLayout(this);
        floatContainer.setPadding(dp(16), 0, dp(16), dp(16));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            floatContainer.setOutlineAmbientShadowColor(0x20000000);
            floatContainer.setOutlineSpotShadowColor(0x30000000);
            floatContainer.setElevation(dp(16));
        }

        GlassBarView glassBg = new GlassBarView(this);
        floatContainer.addView(glassBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(dp(12), dp(10), dp(12), dp(10));

        int btnSize = dp(56);

        lockBtn = makeIconBtn(GlassButtonView.ICON_LOCK, "锁定", ThemeColors.ACCENT, () -> {
            isLocked = !isLocked; updateLockButton(); vibrate(30);
        });
        pauseBtn = makeIconBtn(GlassButtonView.ICON_PAUSE, "暂停", ThemeColors.ACCENT2, () -> {
            isPaused = !isPaused; updatePauseButton(); vibrate(30);
        });
        unitBtn = makeIconBtn(GlassButtonView.ICON_RULER, "cm", ThemeColors.ACCENT3, () -> {
            unitMode = (unitMode + 1) % 3; updateUnitDisplay(); vibrate(30);
        });
        moreBtn = makeIconBtn(GlassButtonView.ICON_MORE, "更多", ThemeColors.TEXT_DIM, () -> {
            moreExpanded = !moreExpanded; moreBtn.setActive(moreExpanded); updateMorePanel(); vibrate(30);
        });

        lockBtn.setActive(isLocked);
        pauseBtn.setActive(isPaused);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSize, dp(52));
        btnLp.weight = 1;
        buttonRow.addView(lockBtn, btnLp);
        buttonRow.addView(pauseBtn, btnLp);
        buttonRow.addView(unitBtn, btnLp);
        buttonRow.addView(moreBtn, btnLp);

        floatContainer.addView(buttonRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomBarFloat = floatContainer;
    }

    private GlassButtonView makeIconBtn(int iconType, String label, int accent, Runnable onPress) {
        GlassButtonView btn = new GlassButtonView(this);
        btn.setIconType(iconType);
        btn.setLabel(label);
        btn.setAccentColor(accent);
        btn.setOnPress(onPress);
        return btn;
    }

    private void buildMorePanel() {
        morePanel = new LinearLayout(this);
        morePanel.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(ThemeColors.isLight ? 0xEEFFFFFF : 0xE62C2C2E);
        panelBg.setCornerRadius(dp(20));
        morePanel.setBackground(panelBg);
        morePanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        morePanel.setVisibility(View.GONE);

        int rowHeight = dp(44);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        rowLp.setMargins(0, dp(4), 0, dp(4));

        resetBtn = makeFlatBtn("↺ 重置数据", 0xFFFF453A, () -> {
            resetMeasurement(); moreExpanded = false; updateMorePanel(); vibrate(50);
        });
        debugBtn = makeFlatBtn("调试信息", ThemeColors.ACCENT, () -> {
            debugVisible = !debugVisible;
            debugText.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
            debugBtn.setLabel(debugVisible ? "调试信息 ✓" : "调试信息");
            vibrate(30);
        });
        calibrateBtn = makeFlatBtn("校准倾斜", ThemeColors.ACCENT2, () -> {
            tiltCompensator.calibrate(); vibrate(80); statusText.setText("已校准 ✓");
        });
        continuousBtn = makeFlatBtn("连续模式", 0xFFFF375F, () -> {
            continuousMode = !continuousMode;
            continuousBtn.setLabel(continuousMode ? "连续模式 ✓" : "连续模式");
            if (continuousMode) isRecording = true;
            vibrate(30);
        });
        themeBtn = makeFlatBtn(ThemeColors.isLight ? "🌙 深色模式" : "☀️ 浅色模式", ThemeColors.TEXT_DIM, () -> {
            isLightTheme = !isLightTheme;
            ThemeColors.apply(isLightTheme);
            rebuildUI();
            vibrate(30);
        });

        morePanel.addView(resetBtn, rowLp);
        morePanel.addView(debugBtn, rowLp);
        morePanel.addView(calibrateBtn, rowLp);
        morePanel.addView(continuousBtn, rowLp);
        morePanel.addView(themeBtn, rowLp);
    }

    private GlassButtonView makeFlatBtn(String label, int accent, Runnable onPress) {
        GlassButtonView btn = new GlassButtonView(this);
        btn.setLabel(label);
        btn.setAccentColor(accent);
        btn.setOnPress(onPress);
        return btn;
    }

    // ─────────────────────────────────────────────
    //  UI Updates
    // ─────────────────────────────────────────────

    private static final String[] UNIT_LABELS = {"cm", "mm", "in"};

    private void updateLockButton() {
        lockBtn.setActive(isLocked);
        lockBtn.setLabel("锁定");
        lockBtn.setAccentColor(isLocked ? 0xFFFF453A : ThemeColors.ACCENT);
        distanceCard.setAccentTint(isLocked ? 0xFFFF453A : ThemeColors.ACCENT);
    }

    private void updatePauseButton() {
        pauseBtn.setActive(isPaused);
        pauseBtn.setLabel(isPaused ? "继续" : "暂停");
    }

    private void updateUnitDisplay() {
        unitBtn.setLabel(UNIT_LABELS[unitMode]);
    }

    private void updateMorePanel() {
        if (moreExpanded) {
            morePanel.setVisibility(View.VISIBLE);
            morePanel.setAlpha(0f);
            morePanel.animate().alpha(1f).translationY(0).setDuration(250).start();
        } else {
            morePanel.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> morePanel.setVisibility(View.GONE)).start();
        }
    }

    private void rebuildUI() {
        rootLayout.removeAllViews();
        buildUI();
        setContentView(rootLayout);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            contentLayout.setPadding(dp(20), top + dp(16), dp(20), dp(120));
            return insets;
        });
        if (currentDistance >= 0) updateDisplay(currentDistance);
        updateLockButton();
        updatePauseButton();
        updateUnitDisplay();
    }

    private void updateDisplay(float distMm) {
        if (isLocked) return;

        if (smoothDisplay < 0) smoothDisplay = distMm;
        else smoothDisplay = smoothDisplay * 0.6f + distMm * 0.4f;

        float src = smoothDisplay;
        float displayDist;
        String unit;
        switch (unitMode) {
            case 1: displayDist = src; unit = "mm"; break;
            case 2: displayDist = src / 25.4f; unit = "in"; break;
            default: displayDist = src / 10f; unit = "cm"; break;
        }

        valueText.setText(displayDist < 0 ? "—" : String.format(Locale.US, "%.1f", displayDist));
        unitText.setText(unit);

        float stddev = stats.getStdDev();
        float quality = stddev < 1 ? 1f : stddev < 5 ? 0.8f : stddev < 15 ? 0.5f : 0.2f;
        qualityBar.setProgress(quality);
        qualityBar.setFillColor(quality > 0.7f ? ThemeColors.ACCENT2 : quality > 0.4f ? ThemeColors.ACCENT3 : 0xFFFF453A);

        if (statMinText != null && statMinText.getParent() != null
                && ((View) statMinText.getParent()).getVisibility() == View.VISIBLE) {
            updateStatsRow();
        }

        String tiltInfo = tiltCompensator.getTiltQuality();
        String rangeInfo = (currentDistance < 0) ? " 超量程" : "";
        String shakeInfo = shakeDetector.isShaking() ? " 防抖中(" + stabilizer.getBufferedCount() + ")" : "";
        String lockInfo = isLocked ? " 锁定" : "";
        statusText.setText(tiltInfo + rangeInfo + shakeInfo + lockInfo);

        if (debugVisible) {
            debugText.setText(String.format(Locale.US,
                    "传感器原始: %.1f mm [阈值:%.0f]\n范围过滤后: %.1f mm\n滤波: %.1f mm\n倾斜: %.1f°\n水平: %.1f mm\n采样: %d | Hz: %d\nσ: %.2f mm",
                    lastRawSensorValue, MAX_VALID_RANGE_MM, currentDistance, filteredDistance,
                    tiltCompensator.getPitchDegrees(),
                    tiltCompensator.getHorizontalDistance(filteredDistance),
                    stats.getSampleCount(), stats.getActualHz(),
                    stats.getStdDev()));
        }
    }

    private void updateStatsRow() {
        float scale = unitMode == 0 ? 0.1f : unitMode == 1 ? 1f : 1f / 25.4f;
        if (statMinText != null) statMinText.setText(String.format(Locale.US, "min: %.1f", stats.getMin() * scale));
        if (statMaxText != null) statMaxText.setText(String.format(Locale.US, "max: %.1f", stats.getMax() * scale));
        if (statAvgText != null) statAvgText.setText(String.format(Locale.US, "avg: %.1f", stats.getAvg() * scale));
        if (statStdText != null) statStdText.setText(String.format(Locale.US, "σ: %.1f", stats.getStdDev() * scale));
    }

    // ─────────────────────────────────────────────
    //  Sensor Logic
    // ─────────────────────────────────────────────

    private void findTofSensor() {
        if (sensorManager == null) return;
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        tofSensor = null;
        isProximityFallback = false;
        for (Sensor s : all) {
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
        if (tofSensor == null) {
            statusText.setText("未找到距离传感器");
            return;
        }
        String label = isProximityFallback ? " (降级 Proximity)" : "";
        statusText.setText("传感器: " + tofSensor.getName() + label + " | 量程: " + tofSensor.getMaximumRange());
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    private void registerSensors() {
        if (sensorManager == null || sensorRegistered) return;
        if (tofSensor != null) sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_FASTEST);
        if (accelSensor != null) sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gyroSensor != null) sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        sensorRegistered = true;
    }

    private void unregisterSensors() {
        if (sensorManager != null && sensorRegistered) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sType = event.sensor.getType();
        if (sType == SENSOR_TYPE_MIUI_TOF || sType == Sensor.TYPE_PROXIMITY) {
            handleTofReading(event.values[0]);
        } else if (sType == Sensor.TYPE_ACCELEROMETER) {
            boolean wasShaking = shakeDetector.isShaking();
            shakeDetector.update(event);
            tiltCompensator.updateAccelerometer(event);
            if (wasShaking && !shakeDetector.isShaking() && isLocked) {
                isLocked = false;
                mainHandler.post(() -> updateLockButton());
            }
        } else if (sType == Sensor.TYPE_GYROSCOPE) {
            tiltCompensator.updateGyroscope(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void handleTofReading(float rawMm) {
        lastRawSensorValue = rawMm; // debug: keep original sensor value
        // Discard sensor overflow sentinel (VL53L1X returns 8191 = 2^13-1 when no target)
        if (rawMm >= 8190f) {
            rawMm = -1;
        }
        // Discard out-of-range readings
        else if (rawMm > MAX_VALID_RANGE_MM) {
            rawMm = -1;
        }
        currentDistance = rawMm;
        stats.tickHz();

        if (isPaused) return;

        filteredDistance = filter.filter(rawMm);
        stats.add(filteredDistance >= 0 ? filteredDistance : rawMm);

        long now = System.currentTimeMillis();

        // Continuous CSV recording with real timestamps
        if (continuousMode && isRecording) {
            if (now - lastContinuousCsv >= CONTINUOUS_CSV_INTERVAL_MS) {
                if (filteredDistance >= 0 && filteredDistance <= MAX_VALID_RANGE_MM) {
                    csvData.add(new float[]{filteredDistance, tiltCompensator.getPitchDegrees(), (float) now});
                }
                lastContinuousCsv = now;
                final int count = csvData.size();
                final long elapsed = (now - recordStartTime) / 1000;
                mainHandler.post(() -> {
                    if (recordCountText != null) recordCountText.setText(count + " 条数据");
                    if (recordTimeText != null && recordStartTime > 0)
                        recordTimeText.setText(String.format(Locale.US, "已记录 %d:%02d", elapsed / 60, elapsed % 60));
                });
            }
        }

        // Throttled UI update
        if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdateMs = now;
            float stabInput = (rawMm < 0) ? -1 : (filteredDistance >= 0 ? filteredDistance : currentDistance);
            final float displayDist = stabilizer.update(stabInput, shakeDetector.isShaking());
            mainHandler.post(() -> updateDisplay(displayDist));
        }
    }

    // ─────────────────────────────────────────────
    //  Actions
    // ─────────────────────────────────────────────

    private void resetMeasurement() {
        filter.reset();
        stats.reset();
        tiltCompensator.resetCalibration();
        shakeDetector.reset();
        stabilizer.reset();
        csvData.clear();
        isRecording = false;
        continuousMode = false;
        if (recordBtn != null) { recordBtn.setLabel("开始"); recordBtn.setActive(false); }
        if (recordStatusText != null) { recordStatusText.setText("数据记录"); recordStatusText.setTextColor(ThemeColors.TEXT); }
        if (recordCountText != null) recordCountText.setText("0 条数据");
        if (recordTimeText != null) recordTimeText.setText("0:00");
        if (recordDistText != null) recordDistText.setText("");
        recordStartTime = 0;
        if (continuousBtn != null) continuousBtn.setLabel("连续模式");
        currentDistance = -1;
        filteredDistance = -1;
        smoothDisplay = -1;
        valueText.setText("—");
        qualityBar.setProgress(0);
        statusText.setText("已重置");
        debugText.setText("");
    }

    /**
     * CSV export on background thread with real timestamps.
     */
    private void exportCsv() {
        final List<float[]> snapshot = new ArrayList<>(csvData);
        if (snapshot.isEmpty()) {
            statusText.setText("无数据");
            return;
        }
        new Thread(() -> {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                String filename = "tof_" + sdf.format(new Date()) + ".csv";
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (dir == null) dir = getFilesDir();
                File file = new File(dir, filename);

                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write("timestamp_ms,distance_mm,tilt_deg");
                writer.newLine();
                for (float[] row : snapshot) {
                    long ts = (row.length > 2) ? (long) row[2] : System.currentTimeMillis();
                    writer.write(String.format(Locale.US, "%d,%.1f,%.1f", ts, row[0], row[1]));
                    writer.newLine();
                }
                writer.close();

                mainHandler.post(() -> {
                    statusText.setText("已导出: " + filename);
                    vibrate(80);
                    try {
                        Uri contentUri = FileProvider.getUriForFile(this,
                                getApplicationContext().getPackageName() + ".fileprovider", file);
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/csv");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "分享CSV"));
                    } catch (ActivityNotFoundException | IllegalArgumentException ignored) {}
                });
            } catch (IOException e) {
                mainHandler.post(() -> statusText.setText("导出失败: " + e.getMessage()));
            }
        }).start();
    }

    // ─────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────

    private void vibrate(long ms) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrator = ((VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(ms);
                }
            }
        } catch (Exception ignored) {}
    }
}
