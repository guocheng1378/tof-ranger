package com.example.tofranger;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import java.io.File;
import java.util.Locale;

/**
 * Main Activity — UI shell that delegates to SensorController and DataRecorder.
 *
 * Refactored from 897-line God class:
 * - Sensor logic → SensorController
 * - CSV recording → DataRecorder
 * - All view references + UI update logic stays here (it's the view layer)
 */
public class MainActivity extends ComponentActivity implements SensorController.ToFListener {

    // ── Delegated concerns ──
    private SensorController sensorCtrl;
    private DataRecorder recorder;

    // ── State ──
    private volatile float currentDistance = -1;
    private volatile float filteredDistance = -1;
    private volatile float lastRawSensorValue = -1;
    private float smoothDisplay = -1;
    private static final float DISPLAY_ALPHA = 0.15f;
    private int consecutiveInvalidCount = 0;
    private static final int MAX_INVALID_BEFORE_CLEAR = 10;
    private boolean isLocked = false;
    private boolean isPaused = false;
    private int unitMode = 0;
    private boolean moreExpanded = false;
    private boolean debugVisible = false;
    private boolean isLightTheme = true;

    // UI update throttle
    private static final long UI_UPDATE_INTERVAL_MS = 50;
    private long lastUiUpdateMs = 0;

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

    // Pre-allocated StringBuilder for debug text (avoid String.format in hot path)
    private final StringBuilder debugSb = new StringBuilder(256);

    // ── Unit labels ──
    private static final String[] UNIT_LABELS = {"cm", "mm", "in"};

    // ─────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // Cache density globally for all views
        ThemeColors.DENSITY = getResources().getDisplayMetrics().density;

        // Restore state
        if (savedInstanceState != null) {
            isLightTheme = savedInstanceState.getBoolean("isLight", true);
            unitMode = savedInstanceState.getInt("unitMode", 0);
            isLocked = savedInstanceState.getBoolean("isLocked", false);
            isPaused = savedInstanceState.getBoolean("isPaused", false);
            debugVisible = savedInstanceState.getBoolean("debugVisible", false);
        }
        ThemeColors.apply(isLightTheme);

        // Initialize delegated controllers
        sensorCtrl = new SensorController(this);
        sensorCtrl.setListener(this);
        recorder = new DataRecorder();

        buildUI();
        setContentView(rootLayout);

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            contentLayout.setPadding(dp(20), top + dp(16), dp(20), dp(120));
            return insets;
        });
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
        sensorCtrl.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorCtrl.pause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Only intercept volume keys when activity is in foreground
        if (isPaused && !isFinishing()) {
            return super.dispatchKeyEvent(event);
        }
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
    //  SensorController.ToFListener callback
    // ─────────────────────────────────────────────

    @Override
    public void onDistance(float distanceMm, float rawMm) {
        lastRawSensorValue = rawMm;
        currentDistance = distanceMm;

        if (isPaused) return;

        filteredDistance = (rawMm >= 0) ? sensorCtrl.getFilter().getCurrentValue() : -1;

        // Continuous CSV recording
        if (recorder.addContinuous(
                filteredDistance >= 0 ? filteredDistance : distanceMm,
                sensorCtrl.getTiltCompensator().getPitchDegrees())) {
            final int count = recorder.getCount();
            final long elapsed = recorder.getElapsedSeconds();
            mainHandler.post(() -> updateRecordUI(count, elapsed, null));
        }

        // Throttled UI update — pass the stabilized value directly
        long now = System.currentTimeMillis();
        if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdateMs = now;
            final float display = distanceMm;
            mainHandler.post(() -> updateDisplay(display));
        }
    }

    @Override
    public void onSensorStatus(String status) {
        mainHandler.post(() -> statusText.setText(status));
    }

    // ─────────────────────────────────────────────
    //  Data Recording
    // ─────────────────────────────────────────────

    private void recordSingleDataPoint() {
        float dist = (filteredDistance >= 0) ? filteredDistance : currentDistance;
        if (dist >= 0 && dist <= sensorCtrl.getMaxRange()) {
            recorder.addPoint(dist, sensorCtrl.getTiltCompensator().getPitchDegrees());
            final int count = recorder.getCount();
            final String distStr = formatDistance(dist);
            mainHandler.post(() -> updateRecordUI(count, -1, distStr));
            vibrate(50);
        }
    }

    private void updateRecordUI(int count, long elapsedSec, String lastDist) {
        if (recordCountText != null) recordCountText.setText(count + " 条数据");
        if (elapsedSec >= 0 && recordTimeText != null && recorder.isRecording()) {
            recordTimeText.setText(String.format(Locale.US, "已记录 %d:%02d", elapsedSec / 60, elapsedSec % 60));
        }
        if (lastDist != null) {
            if (recordStatusText != null) {
                recordStatusText.setText("● 已记录");
                recordStatusText.setTextColor(ThemeColors.ACCENT);
            }
            if (recordDistText != null) recordDistText.setText("最近: " + lastDist);
        }
    }

    private String formatDistance(float distMm) {
        switch (unitMode) {
            case 1: return String.format(Locale.US, "%.1f mm", distMm);
            case 2: return String.format(Locale.US, "%.2f in", distMm / 25.4f);
            default: return String.format(Locale.US, "%.1f cm", distMm / 10f);
        }
    }

    private void exportCsv() {
        recorder.exportCsv(this, new DataRecorder.ExportCallback() {
            @Override
            public void onSuccess(File file, String filename) {
                mainHandler.post(() -> {
                    statusText.setText("已导出: " + filename);
                    vibrate(80);
                    try {
                        Uri contentUri = FileProvider.getUriForFile(MainActivity.this,
                                getApplicationContext().getPackageName() + ".fileprovider", file);
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/csv");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "分享CSV"));
                    } catch (ActivityNotFoundException | IllegalArgumentException ignored) {}
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> statusText.setText(message));
            }
        });
    }

    // ─────────────────────────────────────────────
    //  UI Building
    // ─────────────────────────────────────────────

    private int dp(float v) { return ThemeColors.dp(v); }

    /** Create LinearLayout.LayoutParams with top margin. */
    private LinearLayout.LayoutParams lpWithTopMargin(int w, int h, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = topMargin;
        return lp;
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
        cardInner.addView(unitText, lpWithTopMargin(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(4)));

        qualityBar = new QualityBarView(this);
        LinearLayout.LayoutParams qbLp = new LinearLayout.LayoutParams(dp(200), dp(6));
        qbLp.topMargin = dp(16);
        cardInner.addView(qualityBar, qbLp);

        statusText = new TextView(this);
        statusText.setText("寻找传感器…");
        statusText.setTextColor(ThemeColors.TEXT_DIM);
        statusText.setTextSize(13);
        statusText.setGravity(Gravity.CENTER);
        cardInner.addView(statusText, lpWithTopMargin(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(12)));

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
        statsRow.setVisibility(View.VISIBLE);
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
        infoCol.addView(recordCountText, lpWithTopMargin(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(4)));

        recordTimeText = new TextView(this);
        recordTimeText.setText("0:00");
        recordTimeText.setTextColor(ThemeColors.TEXT_DIM);
        recordTimeText.setTextSize(12);
        infoCol.addView(recordTimeText, lpWithTopMargin(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(2)));

        recordDistText = new TextView(this);
        recordDistText.setText("");
        recordDistText.setTextColor(ThemeColors.ACCENT);
        recordDistText.setTextSize(14);
        recordDistText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        infoCol.addView(recordDistText, lpWithTopMargin(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(4)));

        inner.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        recordBtn = new GlassButtonView(this);
        recordBtn.setIconType(GlassButtonView.ICON_RECORD);
        recordBtn.setLabel(recorder.isRecording() ? "停止" : "开始");
        recordBtn.setAccentColor(0xFFFF3B30);
        recordBtn.setActive(recorder.isRecording());
        recordBtn.setOnPress(() -> {
            if (!recorder.isRecording()) {
                recorder.startRecording();
                recorder.setContinuousMode(true);
                recordBtn.setLabel("停止");
                recordBtn.setActive(true);
                recordStatusText.setText("● 记录中");
                recordStatusText.setTextColor(0xFFFF3B30);
                recordSingleDataPoint();
            } else {
                recorder.stopRecording();
                recordBtn.setLabel("开始");
                recordBtn.setActive(false);
                recordStatusText.setText("数据记录");
                recordStatusText.setTextColor(ThemeColors.TEXT);
                exportCsv();
            }
            vibrate(50);
        });
        inner.addView(recordBtn, new LinearLayout.LayoutParams(dp(56), dp(52)));

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

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(56), dp(52));
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

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
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
            sensorCtrl.getTiltCompensator().calibrate(); vibrate(80); statusText.setText("已校准 ✓");
        });
        continuousBtn = makeFlatBtn("连续模式", 0xFFFF375F, () -> {
            boolean c = !recorder.isContinuousMode();
            recorder.setContinuousMode(c);
            continuousBtn.setLabel(c ? "连续模式 ✓" : "连续模式");
            if (c) recorder.setRecording(true);
            vibrate(30);
        });
        themeBtn = makeFlatBtn(ThemeColors.isLight ? "🌙 深色模式" : "☀️ 浅色模式", ThemeColors.TEXT_DIM, () -> {
            isLightTheme = !isLightTheme;
            ThemeColors.apply(isLightTheme);
            refreshThemeColors();
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

    /** Refresh theme colors in-place without rebuilding the entire UI tree. */
    private void refreshThemeColors() {
        rootLayout.setBackgroundColor(ThemeColors.BG);

        // Update distance card
        distanceCard.setAccentTint(isLocked ? 0xFFFF453A : ThemeColors.ACCENT);
        valueText.setTextColor(ThemeColors.TEXT);
        unitText.setTextColor(ThemeColors.ACCENT);
        statusText.setTextColor(ThemeColors.TEXT_DIM);

        // Update record card text colors
        recordStatusText.setTextColor(ThemeColors.TEXT);
        recordCountText.setTextColor(ThemeColors.TEXT_DIM);
        recordTimeText.setTextColor(ThemeColors.TEXT_DIM);
        recordDistText.setTextColor(ThemeColors.ACCENT);

        // Update debug text
        debugText.setTextColor(ThemeColors.DEBUG_DIM);

        // Update bottom bar
        lockBtn.setAccentColor(isLocked ? 0xFFFF453A : ThemeColors.ACCENT);
        pauseBtn.setAccentColor(ThemeColors.ACCENT2);
        unitBtn.setAccentColor(ThemeColors.ACCENT3);
        moreBtn.setAccentColor(ThemeColors.TEXT_DIM);

        // Update more panel background
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(ThemeColors.isLight ? 0xEEFFFFFF : 0xE62C2C2E);
        panelBg.setCornerRadius(dp(20));
        morePanel.setBackground(panelBg);

        // Update more panel button label
        themeBtn.setLabel(ThemeColors.isLight ? "🌙 深色模式" : "☀️ 浅色模式");

        // Rebuild bottom bar glass background
        if (bottomBarFloat != null) {
            // The GlassBarView reads ThemeColors on draw, so invalidate triggers repaint
            bottomBarFloat.invalidate();
        }

        // Invalidate custom views
        qualityBar.onThemeChanged();
        distanceCard.invalidate();
    }

    private void updateDisplay(float distMm) {
        if (isLocked) return;

        // Sensor dead? Clear stale display
        if (distMm < 0) {
            consecutiveInvalidCount++;
            if (consecutiveInvalidCount >= MAX_INVALID_BEFORE_CLEAR) {
                smoothDisplay = -1;
                valueText.setText("—");
                statusText.setText("传感器无信号 · 检查光线/表面角度");
                qualityBar.setProgress(0);
                return;
            }
        } else {
            consecutiveInvalidCount = 0;
        }

        // Smooth EMA toward target
        if (smoothDisplay < 0) {
            smoothDisplay = distMm;
        } else {
            smoothDisplay = smoothDisplay * (1f - DISPLAY_ALPHA) + distMm * DISPLAY_ALPHA;
        }

        float src = smoothDisplay;
        float displayDist;
        String unit;
        switch (unitMode) {
            case 1: displayDist = src; unit = "mm"; break;
            case 2: displayDist = src / 25.4f; unit = "in"; break;
            default: displayDist = src / 10f; unit = "cm"; break;
        }

        if (displayDist < 0) {
            valueText.setText("—");
        } else {
            valueText.setText(String.format(Locale.US, "%.1f", displayDist));
        }
        unitText.setText(unit);

        // Quality bar
        DistanceStats stats = sensorCtrl.getStats();
        float stddev = stats.getStdDev();
        float quality = stddev < 1 ? 1f : stddev < 5 ? 0.8f : stddev < 15 ? 0.5f : 0.2f;
        qualityBar.setProgress(quality);
        qualityBar.setFillColor(quality > 0.7f ? ThemeColors.ACCENT2 : quality > 0.4f ? ThemeColors.ACCENT3 : 0xFFFF453A);

        // Status line
        TiltCompensator tc = sensorCtrl.getTiltCompensator();
        ShakeDetector sd = sensorCtrl.getShakeDetector();
        Stabilizer st = sensorCtrl.getStabilizer();
        String tiltInfo = tc.getTiltQuality();
        String rangeInfo = (currentDistance < 0) ? " 超量程" : "";
        String shakeInfo = sd.isShaking() ? " 防抖中(" + st.getBufferedCount() + ")" : "";
        String lockInfo = isLocked ? " 锁定" : "";
        statusText.setText(tiltInfo + rangeInfo + shakeInfo + lockInfo);

        // Debug text (StringBuilder to avoid String.format GC)
        if (debugVisible) {
            debugSb.setLength(0);
            debugSb.append("传感器原始: ").append(formatFloat(lastRawSensorValue))
                    .append(" mm [阈值:").append((int) sensorCtrl.getMaxRange()).append("]\n")
                    .append("范围过滤后: ").append(formatFloat(currentDistance)).append(" mm\n")
                    .append("滤波: ").append(formatFloat(filteredDistance)).append(" mm\n")
                    .append("倾斜: ").append(formatFloat(tc.getPitchDegrees())).append("°\n")
                    .append("水平: ").append(formatFloat(tc.getHorizontalDistance(filteredDistance))).append(" mm\n")
                    .append("采样: ").append(stats.getSampleCount()).append(" | Hz: ").append(stats.getActualHz()).append("\n")
                    .append("σ: ").append(formatFloat(stats.getStdDev())).append(" mm");
            debugText.setText(debugSb.toString());
        }
    }

    /** Format float to 1 decimal without String.format (GC-free in hot path). */
    private String formatFloat(float v) {
        if (v < 0) return "-1";
        return String.format(Locale.US, "%.1f", v);
    }

    // ─────────────────────────────────────────────
    //  Actions
    // ─────────────────────────────────────────────

    private void resetMeasurement() {
        sensorCtrl.reset();
        recorder.clear();
        recorder.stopRecording();
        if (recordBtn != null) { recordBtn.setLabel("开始"); recordBtn.setActive(false); }
        if (recordStatusText != null) { recordStatusText.setText("数据记录"); recordStatusText.setTextColor(ThemeColors.TEXT); }
        if (recordCountText != null) recordCountText.setText("0 条数据");
        if (recordTimeText != null) recordTimeText.setText("0:00");
        if (recordDistText != null) recordDistText.setText("");
        if (continuousBtn != null) continuousBtn.setLabel("连续模式");
        currentDistance = -1;
        filteredDistance = -1;
        smoothDisplay = -1;
        valueText.setText("—");
        qualityBar.setProgress(0);
        statusText.setText("已重置");
        debugText.setText("");
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
