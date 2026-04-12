package com.example.tofranger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener, TextToSpeech.OnInitListener {

    private static final int SENSOR_TYPE_TOF = 33171040;

    // Unit modes
    private static final int UNIT_MM = 0, UNIT_CM = 1, UNIT_M = 2, UNIT_INCH = 3;
    private static final String[] UNIT_LABELS = {"mm", "cm", "m", "in"};
    private int currentUnit = UNIT_CM;

    // State
    private boolean isHolding = false;

    // Signal processing
    private DistanceFilter primaryFilter = new DistanceFilter(11, 0.08f, 300f, 2f, 4000f);
    private DistanceFilter[] targetFilters;
    private static final int MAX_TARGETS = 4;

    // Statistics
    private DistanceStats stats = new DistanceStats(200);
    private final Deque<Float> rawHistory = new ArrayDeque<>();
    private static final int MAX_HISTORY = 500;

    // Sensor
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private boolean isProximityFallback = false;

    // Tilt compensation
    private TiltCompensator tilt = new TiltCompensator();

    // Shake detection
    private ShakeDetector shakeDetector = new ShakeDetector();
    private boolean displayFrozen = false;

    // Auto-calibration
    private boolean autoCalDone = false;
    private long startTime = 0;
    private static final long AUTO_CAL_DURATION_MS = 3000;
    private float calSum = 0;
    private int calCount = 0;
    private float sensorOffset = 0;

    // Photo measurement
    private float lockedDistance = -1;
    private boolean isLocked = false;

    // Area measurement
    private boolean areaMode = false;
    private float areaDist1 = -1, areaDist2 = -1;

    // Continuous measurement
    private boolean continuousMode = false;
    private final ArrayList<MeasurementRecord> continuousRecords = new ArrayList<>();
    private float lastStableValue = -1;
    private long lastStableTime = 0;
    private static final long STABLE_THRESHOLD_MS = 1500; // must be stable for 1.5s
    private static final float STABLE_RANGE_MM = 15; // within 15mm = stable

    // TTS
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsEnabled = false;

    // Vibration
    private Vibrator vibrator;

    // History chart
    private DistanceChartView chartView;

    // UI
    private TextView tvDistance, tvUnit, tvRawInfo, tvStatus;
    private TextView tvStats, tvHz, tvHoldLabel, tvHistoryCount, tvSensorInfo;
    private TextView tvQuality, tvTrend, tvMultiTarget;
    private TextView tvConfidenceBar;
    private TextView tvPitch, tvHorizontal, tvVertical, tvTiltInfo;
    private TextView tvShakeStatus, tvAutoCal, tvLockedInfo;
    private TextView tvAreaResult, tvContinuousInfo;

    // Buttons
    private View btnHold, btnReset, btnUnit, btnClearStats, btnCalibrate;
    private View btnCapture, btnArea, btnContinuous, btnTTS, btnExportCSV;

    // Card refs for show/hide
    private LinearLayout cardMultiTarget, cardTilt, cardArea, cardContinuous;

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
    private static final int C_BLUE = 0xFF3B82F6;
    private static final int C_PURPLE = 0xFF8B5CF6;
    private static final int C_GREEN = 0xFF059669;
    private static final int C_ORANGE = 0xFFF97316;

    private long lastUiUpdate = 0;
    private int eventCount = 0;
    private float lastFiltered = -1;

    // Multi-target
    private float[] lastTargetDists = new float[MAX_TARGETS];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(C_BG);

        startTime = System.currentTimeMillis();

        // Init filters
        targetFilters = new DistanceFilter[MAX_TARGETS];
        for (int i = 0; i < MAX_TARGETS; i++) {
            targetFilters[i] = new DistanceFilter(5, 0.30f, 1000f, 2f, 4000f);
        }

        // Init vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        // Init TTS
        tts = new TextToSpeech(this, this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(C_BG);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(40), dp(16), dp(24));
        root.setBackgroundColor(C_BG);

        // === Header ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📐 ToF 测距仪");
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle);

        tvSensorInfo = new TextView(this);
        tvSensorInfo.setTextSize(10);
        tvSensorInfo.setTextColor(C_DIM);
        tvSensorInfo.setGravity(Gravity.END);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.setMarginStart(dp(8));
        header.addView(tvSensorInfo, infoLp);

        root.addView(header);
        root.addView(makeSeparator());

        // === Shake / Auto-cal status bar ===
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(0, dp(4), 0, dp(4));

        tvAutoCal = new TextView(this);
        tvAutoCal.setTextSize(11);
        tvAutoCal.setTextColor(C_WARN);
        statusBar.addView(tvAutoCal);

        tvShakeStatus = new TextView(this);
        tvShakeStatus.setTextSize(11);
        tvShakeStatus.setTextColor(C_ERR);
        tvShakeStatus.setGravity(Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusBar.addView(tvShakeStatus, slp);

        root.addView(statusBar);

        // === Main Distance Card ===
        LinearLayout distCard = makeCard();
        distCard.setGravity(Gravity.CENTER_HORIZONTAL);
        distCard.setPadding(dp(20), dp(28), dp(20), dp(16));

        tvHoldLabel = new TextView(this);
        tvHoldLabel.setText("● 测量中");
        tvHoldLabel.setTextSize(12);
        tvHoldLabel.setTextColor(C_ACCENT);
        tvHoldLabel.setGravity(Gravity.CENTER);
        distCard.addView(tvHoldLabel);

        LinearLayout distRow = new LinearLayout(this);
        distRow.setOrientation(LinearLayout.HORIZONTAL);
        distRow.setGravity(Gravity.CENTER);
        distRow.setPadding(0, dp(8), 0, 0);

        tvDistance = new TextView(this);
        tvDistance.setText("--");
        tvDistance.setTextSize(64);
        tvDistance.setTextColor(C_ACCENT);
        tvDistance.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        distRow.addView(tvDistance);

        tvUnit = new TextView(this);
        tvUnit.setText(" cm");
        tvUnit.setTextSize(24);
        tvUnit.setTextColor(C_ACCENT);
        tvUnit.setGravity(Gravity.CENTER_VERTICAL);
        tvUnit.setPadding(dp(4), 0, 0, 0);
        distRow.addView(tvUnit);

        distCard.addView(distRow);

        tvRawInfo = new TextView(this);
        tvRawInfo.setTextSize(12);
        tvRawInfo.setTextColor(C_DIM);
        tvRawInfo.setGravity(Gravity.CENTER);
        tvRawInfo.setPadding(0, dp(6), 0, 0);
        distCard.addView(tvRawInfo);

        tvQuality = new TextView(this);
        tvQuality.setTextSize(11);
        tvQuality.setGravity(Gravity.CENTER);
        tvQuality.setPadding(0, dp(2), 0, 0);
        distCard.addView(tvQuality);

        tvConfidenceBar = new TextView(this);
        tvConfidenceBar.setTextSize(10);
        tvConfidenceBar.setTypeface(Typeface.MONOSPACE);
        tvConfidenceBar.setTextColor(C_DIM);
        tvConfidenceBar.setGravity(Gravity.CENTER);
        tvConfidenceBar.setPadding(0, dp(2), 0, 0);
        distCard.addView(tvConfidenceBar);

        tvLockedInfo = new TextView(this);
        tvLockedInfo.setTextSize(11);
        tvLockedInfo.setTextColor(C_BLUE);
        tvLockedInfo.setGravity(Gravity.CENTER);
        tvLockedInfo.setPadding(0, dp(4), 0, 0);
        tvLockedInfo.setVisibility(View.GONE);
        distCard.addView(tvLockedInfo);

        root.addView(distCard);
        root.addView(makeGap(dp(8)));

        // === Quick Action Buttons (small) ===
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER);
        btnCapture = makeSmallButton("📸 锁定", C_BLUE);
        btnTTS = makeSmallButton("🔊 语音", C_DIM);
        btnContinuous = makeSmallButton("⏺ 连测", C_GREEN);
        btnExportCSV = makeSmallButton("📄 导出", C_ORANGE);
        quickRow.addView(btnCapture, lp(0, dp(36), 1));
        quickRow.addView(makeGap(dp(4)), lp(dp(4), 0, 0));
        quickRow.addView(btnTTS, lp(0, dp(36), 1));
        quickRow.addView(makeGap(dp(4)), lp(dp(4), 0, 0));
        quickRow.addView(btnContinuous, lp(0, dp(36), 1));
        quickRow.addView(makeGap(dp(4)), lp(dp(4), 0, 0));
        quickRow.addView(btnExportCSV, lp(0, dp(36), 1));
        root.addView(quickRow);
        root.addView(makeGap(dp(8)));

        // === Multi-Target Card ===
        cardMultiTarget = makeCard();
        cardMultiTarget.setPadding(dp(14), dp(12), dp(14), dp(12));
        cardMultiTarget.setVisibility(View.GONE);
        cardMultiTarget.addView(makeLabel("🎯 多目标检测"));
        tvMultiTarget = makeBodyText();
        cardMultiTarget.addView(tvMultiTarget);
        root.addView(cardMultiTarget);
        root.addView(makeGap(dp(8)));

        // === Tilt Card ===
        cardTilt = makeCard();
        cardTilt.setPadding(dp(14), dp(12), dp(14), dp(12));
        cardTilt.addView(makeLabel("🧭 倾斜补偿"));
        tvPitch = makeBodyText();
        cardTilt.addView(tvPitch);
        tvHorizontal = makeBodyText();
        tvHorizontal.setTextColor(C_ACCENT);
        cardTilt.addView(tvHorizontal);
        tvVertical = makeBodyText();
        tvVertical.setTextColor(C_WARN);
        cardTilt.addView(tvVertical);
        tvTiltInfo = makeDimText();
        cardTilt.addView(tvTiltInfo);
        root.addView(cardTilt);
        root.addView(makeGap(dp(8)));

        // === Area Card ===
        cardArea = makeCard();
        cardArea.setPadding(dp(14), dp(12), dp(14), dp(12));
        cardArea.setVisibility(View.GONE);
        cardArea.addView(makeLabel("📐 面积测量"));
        tvAreaResult = makeBodyText();
        cardArea.addView(tvAreaResult);
        root.addView(cardArea);
        root.addView(makeGap(dp(8)));

        // === Continuous Mode Card ===
        cardContinuous = makeCard();
        cardContinuous.setPadding(dp(14), dp(12), dp(14), dp(12));
        cardContinuous.setVisibility(View.GONE);
        cardContinuous.addView(makeLabel("⏺ 连续测量"));
        tvContinuousInfo = makeBodyText();
        cardContinuous.addView(tvContinuousInfo);
        root.addView(cardContinuous);
        root.addView(makeGap(dp(8)));

        // === Stats Card ===
        LinearLayout statsCard = makeCard();
        statsCard.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout statsHeader = new LinearLayout(this);
        statsHeader.setOrientation(LinearLayout.HORIZONTAL);
        statsHeader.setGravity(Gravity.CENTER_VERTICAL);
        statsHeader.addView(makeLabel("📊 统计"));
        tvTrend = new TextView(this);
        tvTrend.setTextSize(11);
        tvTrend.setPadding(dp(8), 0, 0, 0);
        statsHeader.addView(tvTrend);
        statsCard.addView(statsHeader);

        tvStats = makeBodyText();
        statsCard.addView(tvStats);
        root.addView(statsCard);
        root.addView(makeGap(dp(8)));

        // === Chart Card ===
        LinearLayout chartCard = makeCard();
        chartCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        chartCard.addView(makeLabel("📈 实时曲线"));
        chartView = new DistanceChartView(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        chartLp.topMargin = dp(6);
        chartCard.addView(chartView, chartLp);
        root.addView(chartCard);
        root.addView(makeGap(dp(8)));

        // === Control Buttons ===
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.setGravity(Gravity.CENTER);
        btnHold = makeButton("⏸ 暂停", C_WARN);
        btnReset = makeButton("🔄 重置", C_BLUE);
        btnUnit = makeButton("📏 单位", C_PURPLE);
        btnArea = makeButton("📐 面积", C_GREEN);
        btnRow1.addView(btnHold, lp(0, dp(40), 1));
        btnRow1.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow1.addView(btnReset, lp(0, dp(40), 1));
        btnRow1.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow1.addView(btnUnit, lp(0, dp(40), 1));
        btnRow1.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow1.addView(btnArea, lp(0, dp(40), 1));
        root.addView(btnRow1);
        root.addView(makeGap(dp(6)));

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setGravity(Gravity.CENTER);
        btnCalibrate = makeButton("🧭 校准", C_GREEN);
        btnClearStats = makeButton("🗑 清除", 0xFF64748B);
        btnRow2.addView(btnCalibrate, lp(0, dp(40), 1));
        btnRow2.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow2.addView(btnClearStats, lp(0, dp(40), 1));
        root.addView(btnRow2);

        root.addView(makeGap(dp(8)));

        // === Info Card ===
        LinearLayout infoCard = makeCard();
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvHz = makeDimText();
        infoCard.addView(tvHz);
        tvStatus = makeDimText();
        tvStatus.setPadding(0, dp(2), 0, 0);
        infoCard.addView(tvStatus);
        tvHistoryCount = makeDimText();
        tvHistoryCount.setPadding(0, dp(2), 0, 0);
        infoCard.addView(tvHistoryCount);
        root.addView(infoCard);

        scrollView.addView(root);
        setContentView(scrollView);

        // Listeners
        btnHold.setOnClickListener(v -> toggleHold());
        btnReset.setOnClickListener(v -> resetAll());
        btnUnit.setOnClickListener(v -> cycleUnit());
        btnClearStats.setOnClickListener(v -> clearHistory());
        btnCalibrate.setOnClickListener(v -> calibrateTilt());
        btnCapture.setOnClickListener(v -> captureMeasurement());
        btnArea.setOnClickListener(v -> toggleAreaMode());
        btnContinuous.setOnClickListener(v -> toggleContinuousMode());
        btnTTS.setOnClickListener(v -> toggleTTS());
        btnExportCSV.setOnClickListener(v -> exportCSV());

        // Sensor init
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
        findImuSensors();
    }

    // ========== Sensor Setup ==========

    private void findTofSensor() {
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        tofSensor = null;
        String sensorName = "未找到";

        for (Sensor s : all) {
            String name = s.getName().toLowerCase();
            boolean isTof = s.getType() == SENSOR_TYPE_TOF
                    || name.contains("tof") || name.contains("vl53");
            if (isTof && tofSensor == null) {
                tofSensor = s;
                sensorName = s.getName();
            }
        }

        if (tofSensor == null) {
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) {
                sensorName = tofSensor.getName() + " (降级)";
                isProximityFallback = true;
            }
        }
        tvSensorInfo.setText(sensorName);
    }

    private void findImuSensors() {
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tofSensor != null)
            sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_GAME);
        if (accelSensor != null)
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gyroSensor != null)
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    // ========== TTS ==========

    @Override
    public void onInit(int status) {
        ttsReady = (status == TextToSpeech.SUCCESS);
        if (ttsReady) {
            tts.setLanguage(Locale.CHINESE);
        }
    }

    private void speak(String text) {
        if (ttsReady && ttsEnabled) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tof_speak");
        }
    }

    // ========== Sensor Events ==========

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        // Shake detection (always process accel)
        if (type == Sensor.TYPE_ACCELEROMETER) {
            tilt.updateAccelerometer(event);
            boolean wasShaking = shakeDetector.isShaking();
            boolean nowShaking = shakeDetector.update(event);

            // Update shake UI on state change
            if (wasShaking != nowShaking) {
                tvShakeStatus.post(() -> {
                    if (shakeDetector.isShaking()) {
                        tvShakeStatus.setText("📳 晃动中 — 已冻结");
                        displayFrozen = true;
                    } else {
                        tvShakeStatus.setText("");
                        displayFrozen = false;
                    }
                });
            }
            return;
        }
        if (type == Sensor.TYPE_GYROSCOPE) {
            tilt.updateGyroscope(event);
            return;
        }

        if (type != SENSOR_TYPE_TOF && type != Sensor.TYPE_PROXIMITY) return;
        if (isHolding) return;

        eventCount++;
        stats.tickHz();

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdate < 50) return; // 20fps cap for less jitter
        lastUiUpdate = now;

        float rawMm = event.values[0];

        // Auto-calibration phase (first 3 seconds)
        if (!autoCalDone) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed < AUTO_CAL_DURATION_MS && rawMm > 0 && rawMm < 4000) {
                calSum += rawMm;
                calCount++;
                tvAutoCal.post(() -> tvAutoCal.setText(
                        String.format(Locale.getDefault(), "⏳ 自我校准中... (%.0f%%)", elapsed * 100f / AUTO_CAL_DURATION_MS)));
                return;
            } else {
                autoCalDone = true;
                if (calCount > 0) {
                    sensorOffset = calSum / calCount;
                }
                tvAutoCal.post(() -> tvAutoCal.setText("✓ 已自动校准"));
                tvAutoCal.postDelayed(() -> tvAutoCal.post(() -> tvAutoCal.setText("")), 2000);
            }
        }

        // Apply offset
        rawMm -= sensorOffset;
        if (rawMm < 0) rawMm = 0;

        // Multi-target
        boolean hasMultiTarget = !isProximityFallback && event.values.length > 1;
        float[] targetDistances = new float[MAX_TARGETS];
        int validTargets = 0;
        if (hasMultiTarget) {
            for (int t = 0; t < MAX_TARGETS && t < event.values.length; t++) {
                float tDist = event.values[t] - sensorOffset;
                if (tDist > 0 && tDist < 4000) {
                    targetDistances[t] = targetFilters[t].filter(tDist);
                    if (targetFilters[t].isWarmedUp()) validTargets++;
                } else {
                    targetDistances[t] = -1;
                }
            }
            lastTargetDists = targetDistances;
        }

        // Filter
        float filtered = primaryFilter.filter(rawMm);

        // Shake freeze: keep last filtered value
        if (displayFrozen) {
            filtered = lastFiltered > 0 ? lastFiltered : filtered;
        }

        // Lock mode
        if (isLocked) {
            filtered = lockedDistance;
        }

        if (filtered > 0 && !displayFrozen && !isLocked) {
            stats.add(filtered);
            rawHistory.addLast(rawMm);
            if (rawHistory.size() > MAX_HISTORY) rawHistory.removeFirst();

            // Chart
            chartView.addPoint(filtered);

            // Continuous measurement check
            checkContinuous(filtered);
        }

        lastFiltered = filtered;
        updateDisplay(rawMm, filtered, hasMultiTarget, validTargets, targetDistances);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ========== Continuous Measurement ==========

    private void checkContinuous(float filtered) {
        if (!continuousMode) return;

        if (lastStableValue < 0) {
            lastStableValue = filtered;
            lastStableTime = System.currentTimeMillis();
            return;
        }

        if (Math.abs(filtered - lastStableValue) < STABLE_RANGE_MM) {
            // Still stable
            if (System.currentTimeMillis() - lastStableTime > STABLE_THRESHOLD_MS) {
                // Stable long enough — record it
                if (continuousRecords.isEmpty() ||
                        Math.abs(continuousRecords.get(continuousRecords.size() - 1).distanceMm - filtered) > 30) {
                    MeasurementRecord rec = new MeasurementRecord(filtered, System.currentTimeMillis());
                    continuousRecords.add(rec);

                    String u = UNIT_LABELS[currentUnit];
                    String valStr = fmt(convertUnit(filtered, currentUnit), currentUnit);
                    speak("记录 " + valStr + " " + u);

                    vibrate(50);

                    tvContinuousInfo.post(() -> tvContinuousInfo.setText(
                            String.format(Locale.getDefault(),
                                    "已记录 %d 个点\n最近: %s %s",
                                    continuousRecords.size(), valStr, u)));
                }
                // Reset for next measurement
                lastStableValue = filtered;
                lastStableTime = System.currentTimeMillis();
            }
        } else {
            // Not stable, reset
            lastStableValue = filtered;
            lastStableTime = System.currentTimeMillis();
        }
    }

    // ========== Display ==========

    private void updateDisplay(float rawMm, float filtered,
                                boolean hasMultiTarget, int validTargets,
                                float[] targetDists) {
        if (filtered < 0 || rawMm < 0) {
            tvDistance.setText("ERR");
            tvDistance.setTextColor(C_ERR);
            tvRawInfo.setText("超出范围");
            tvHoldLabel.setText("⚠ 无信号");
            tvHoldLabel.setTextColor(C_ERR);
            tvQuality.setText("");
            tvConfidenceBar.setText("");
            return;
        }

        tvDistance.setTextColor(isLocked ? C_BLUE : C_ACCENT);
        tvHoldLabel.setTextColor(isHolding ? C_WARN : C_ACCENT);

        String stateText = "● 测量中";
        if (isLocked) stateText = "🔒 已锁定";
        else if (isHolding) stateText = "● 已暂停";
        else if (displayFrozen) stateText = "📳 晃动冻结";
        tvHoldLabel.setText(stateText);

        // Round to 5mm for display
        float rounded = Math.round(filtered / 5f) * 5f;
        float displayVal = convertUnit(rounded, currentUnit);
        tvDistance.setText(fmt(displayVal, currentUnit));
        tvUnit.setText(" " + UNIT_LABELS[currentUnit]);

        // Raw info
        float diff = Math.abs(rawMm - filtered);
        tvRawInfo.setText(String.format(Locale.getDefault(),
                "%.0f→%.0f mm (Δ%.0f) %s",
                rawMm, filtered, diff,
                shakeDetector.isShaking() ? "📳" : ""));

        // Quality
        float stdDev = stats.getStdDev();
        String qualityLabel;
        int qualityColor;
        if (stdDev < 5) {
            qualityLabel = "🟢 高精度 (σ=" + fmtDec(stdDev, 0) + ")";
            qualityColor = C_GOOD;
        } else if (stdDev < 20) {
            qualityLabel = "🟡 良好 (σ=" + fmtDec(stdDev, 0) + ")";
            qualityColor = C_FAIR;
        } else {
            qualityLabel = "🔴 波动 (σ=" + fmtDec(stdDev, 0) + ")";
            qualityColor = C_POOR;
        }
        tvQuality.setText(qualityLabel);
        tvQuality.setTextColor(qualityColor);

        // Confidence bar
        int barLen = Math.max(0, Math.min(20, (int) (20 - stdDev)));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 20; i++) bar.append(i < barLen ? "█" : "░");
        bar.append("]");
        tvConfidenceBar.setText(bar.toString());
        tvConfidenceBar.setTextColor(qualityColor);

        // Locked info
        if (isLocked) {
            tvLockedInfo.setVisibility(View.VISIBLE);
            tvLockedInfo.setText("📸 按「解锁」恢复实时测量");
        } else {
            tvLockedInfo.setVisibility(View.GONE);
        }

        // Trend
        float trend = stats.getTrend();
        String trendStr;
        if (Math.abs(trend) < 1) trendStr = "→ 稳定";
        else if (trend > 0) trendStr = String.format(Locale.getDefault(), "↑ +%.0f", trend);
        else trendStr = String.format(Locale.getDefault(), "↓ %.0f", trend);
        tvTrend.setText(trendStr);
        tvTrend.setTextColor(Math.abs(trend) < 1 ? C_ACCENT : C_WARN);

        // Multi-target
        if (hasMultiTarget && validTargets > 1) {
            cardMultiTarget.setVisibility(View.VISIBLE);
            StringBuilder mt = new StringBuilder();
            String u = UNIT_LABELS[currentUnit];
            for (int t = 0; t < MAX_TARGETS; t++) {
                if (targetDists[t] > 0 && targetFilters[t].isWarmedUp()) {
                    float conv = convertUnit(targetDists[t], currentUnit);
                    mt.append(String.format(Locale.getDefault(),
                            "%s %s %s\n", t == 0 ? "🎯" : "→", fmt(conv, currentUnit), u));
                }
            }
            tvMultiTarget.setText(mt.toString().trim());
        } else {
            cardMultiTarget.setVisibility(View.GONE);
        }

        // Tilt
        updateTiltDisplay(filtered);

        // Area mode
        updateAreaDisplay(filtered);

        // Stats
        updateStatsText();

        // Info
        tvHz.setText(String.format(Locale.getDefault(),
                "%d Hz | %d samples | Median+EMA²",
                stats.getActualHz(), eventCount));
        tvHistoryCount.setText(String.format(Locale.getDefault(),
                "历史: %d | 曲线: %d", rawHistory.size(), chartView.getPointCount()));

        if (isProximityFallback) {
            tvStatus.setText("⚠ 降级 Proximity");
            tvStatus.setTextColor(C_WARN);
        } else {
            tvStatus.setText(String.format(Locale.getDefault(),
                    "%.0f-%.0f mm 量程", Math.max(0, stats.getMin()), stats.getMax()));
            tvStatus.setTextColor(C_DIM);
        }
    }

    private void updateTiltDisplay(float slantDist) {
        float pitchDeg = tilt.getPitchDegrees();
        String u = UNIT_LABELS[currentUnit];

        tvPitch.setText(String.format(Locale.getDefault(),
                "倾斜: %.1f° (%s)%s", pitchDeg, tilt.getTiltQuality(),
                tilt.isCalibrated() ? " ✓" : ""));

        if (slantDist > 0) {
            float hDist = tilt.getHorizontalDistance(slantDist);
            float vDist = tilt.getVerticalHeight(slantDist);
            tvHorizontal.setText("↔ " + fmt(convertUnit(hDist, currentUnit), currentUnit) + " " + u);
            tvVertical.setText("↕ " + fmt(convertUnit(Math.abs(vDist), currentUnit), currentUnit) + " " + u);
        } else {
            tvHorizontal.setText("↔ --");
            tvVertical.setText("↕ --");
        }

        if (!tilt.isCalibrated())
            tvTiltInfo.setText("💡 持平后点「校准」");
        else if (Math.abs(pitchDeg) < 5)
            tvTiltInfo.setText("📏 近乎水平");
        else
            tvTiltInfo.setText("📐 倾斜中 — 已自动换算");
    }

    private void updateAreaDisplay(float filtered) {
        if (!areaMode) {
            cardArea.setVisibility(View.GONE);
            return;
        }
        cardArea.setVisibility(View.VISIBLE);

        if (areaDist1 < 0) {
            tvAreaResult.setText("第1次：对准一面墙，按「确认」");
        } else if (areaDist2 < 0) {
            tvAreaResult.setText(String.format(Locale.getDefault(),
                    "第1次: %.1f cm ✓\n第2次：转向垂直面，按「确认」",
                    areaDist1 / 10f));
        } else {
            float area = (areaDist1 * areaDist2) / 10000f; // cm² → m²
            tvAreaResult.setText(String.format(Locale.getDefault(),
                    "宽: %.1f cm × 高: %.1f cm\n面积: %.2f m²",
                    areaDist1 / 10f, areaDist2 / 10f, area));
            tvAreaResult.setTextColor(C_ACCENT);
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
                "最小:%s  最大:%s  平均:%s\n中位:%s  σ:%s  样本:%d",
                fmt(convertUnit(stats.getMin(), currentUnit), currentUnit),
                fmt(convertUnit(stats.getMax(), currentUnit), currentUnit),
                fmt(convertUnit(stats.getAvg(), currentUnit), currentUnit),
                fmt(convertUnit(median, currentUnit), currentUnit),
                fmtDec(stats.getStdDev(), 1),
                stats.getSampleCount()));
    }

    // ========== Actions ==========

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
        tilt.reset();
        shakeDetector.reset();
        chartView.clear();
        eventCount = 0;
        lastFiltered = -1;
        rawHistory.clear();
        isLocked = false;
        lockedDistance = -1;
        areaMode = false;
        areaDist1 = -1;
        areaDist2 = -1;
        continuousMode = false;
        continuousRecords.clear();
        lastStableValue = -1;
        autoCalDone = false;
        sensorOffset = 0;
        calSum = 0;
        calCount = 0;
        startTime = System.currentTimeMillis();
        cardArea.setVisibility(View.GONE);
        cardContinuous.setVisibility(View.GONE);
        updateStatsText();
    }

    private void clearHistory() {
        rawHistory.clear();
        chartView.clear();
        continuousRecords.clear();
        stats.reset();
        eventCount = 0;
        updateStatsText();
    }

    private void cycleUnit() {
        currentUnit = (currentUnit + 1) % 4;
        ((TextView) btnUnit).setText("📏 " + UNIT_LABELS[currentUnit]);
        updateStatsText();
    }

    private void calibrateTilt() {
        tilt.calibrate();
        TextView tv = (TextView) btnCalibrate;
        tv.setText("✓ 已校准");
        tv.setTextColor(C_GOOD);
        setBackgroundTint(btnCalibrate, C_GOOD);
        tv.postDelayed(() -> {
            tv.setText("🧭 校准");
            tv.setTextColor(Color.WHITE);
            setBackgroundTint(btnCalibrate, C_GREEN);
        }, 1500);
    }

    private void captureMeasurement() {
        if (isLocked) {
            // Unlock
            isLocked = false;
            lockedDistance = -1;
            ((TextView) btnCapture).setText("📸 锁定");
            tvLockedInfo.setVisibility(View.GONE);
            return;
        }

        // Lock current distance
        lockedDistance = lastFiltered;
        if (lockedDistance < 0) return;

        isLocked = true;
        ((TextView) btnCapture).setText("🔓 解锁");
        tvLockedInfo.setVisibility(View.VISIBLE);

        vibrate(100);

        // Take screenshot
        View rootView = getWindow().getDecorView().getRootView();
        rootView.post(() -> {
            try {
                Bitmap bitmap = Bitmap.createBitmap(rootView.getWidth(), rootView.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                rootView.draw(canvas);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String u = UNIT_LABELS[currentUnit];
                String filename = String.format("tof_%s_%s%s.png",
                        fmt(convertUnit(lockedDistance, currentUnit), currentUnit),
                        u, timestamp);

                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = getFilesDir();
                File file = new File(dir, filename);

                FileOutputStream fos = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();

                String valStr = fmt(convertUnit(lockedDistance, currentUnit), currentUnit);
                speak("锁定 " + valStr + " " + u + "，已截图");
            } catch (Exception e) {
                // screenshot failed, but lock still works
            }
        });
    }

    private void toggleAreaMode() {
        if (!areaMode) {
            // Start area mode
            areaMode = true;
            areaDist1 = -1;
            areaDist2 = -1;
            cardArea.setVisibility(View.VISIBLE);
            ((TextView) btnArea).setText("📐 确认");
            tvAreaResult.setTextColor(C_TEXT);
            return;
        }

        // areaMode is true — btnArea acts as confirm
        if (areaDist1 < 0 && lastFiltered > 0) {
            areaDist1 = lastFiltered;
            vibrate(80);
            speak("第1次已记录");
        } else if (areaDist1 > 0 && areaDist2 < 0 && lastFiltered > 0) {
            areaDist2 = lastFiltered;
            vibrate(80);
            speak("面积已计算");
            ((TextView) btnArea).setText("📐 重来");
        } else {
            // Reset
            areaDist1 = -1;
            areaDist2 = -1;
            cardArea.setVisibility(View.GONE);
            areaMode = false;
            ((TextView) btnArea).setText("📐 面积");
            tvAreaResult.setTextColor(C_TEXT);
        }
    }

    private void toggleContinuousMode() {
        continuousMode = !continuousMode;
        if (continuousMode) {
            continuousRecords.clear();
            lastStableValue = -1;
            cardContinuous.setVisibility(View.VISIBLE);
            ((TextView) btnContinuous).setText("⏹ 停止");
            setBackgroundTint(btnContinuous, C_ERR);
            speak("连续测量已开始");
        } else {
            cardContinuous.setVisibility(View.GONE);
            ((TextView) btnContinuous).setText("⏺ 连测");
            setBackgroundTint(btnContinuous, C_GREEN);
            speak("连续测量已停止，共记录" + continuousRecords.size() + "个点");
        }
    }

    private void toggleTTS() {
        ttsEnabled = !ttsEnabled;
        TextView tv = (TextView) btnTTS;
        if (ttsEnabled) {
            tv.setText("🔊 开");
            tv.setTextColor(C_ACCENT);
            speak("语音播报已开启");
        } else {
            tv.setText("🔇 关");
            tv.setTextColor(C_DIM);
        }
    }

    private void exportCSV() {
        if (rawHistory.isEmpty()) {
            speak("没有数据可导出");
            return;
        }

        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, "tof_data_" + timestamp + ".csv");

            FileWriter fw = new FileWriter(file);
            fw.write("index,distance_mm\n");
            int idx = 0;
            for (float val : rawHistory) {
                fw.write(String.format(Locale.getDefault(), "%d,%.0f\n", idx++, val));
            }

            // Also export continuous records if any
            if (!continuousRecords.isEmpty()) {
                fw.write("\n--- Continuous Measurements ---\n");
                fw.write("timestamp,distance_mm\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                for (MeasurementRecord rec : continuousRecords) {
                    fw.write(String.format(Locale.getDefault(), "%s,%.0f\n",
                            sdf.format(new Date(rec.timestamp)), rec.distanceMm));
                }
            }

            fw.close();

            vibrate(100);
            speak("已导出 " + rawHistory.size() + " 条数据");
        } catch (IOException e) {
            speak("导出失败");
        }
    }

    private void vibrate(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(ms);
        }
    }

    // ========== Helpers ==========

    private String fmt(float val, int unit) {
        switch (unit) {
            case UNIT_MM: return String.format(Locale.getDefault(), "%.0f", val);
            case UNIT_CM: return String.format(Locale.getDefault(), "%.1f", val);
            case UNIT_M: return String.format(Locale.getDefault(), "%.3f", val);
            case UNIT_INCH: return String.format(Locale.getDefault(), "%.2f", val);
            default: return String.format(Locale.getDefault(), "%.1f", val);
        }
    }

    private String fmtDec(float val, int decimals) {
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

    // ========== UI Builders ==========

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(1, C_CARD_BORDER);
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
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
        tv.setTextSize(13);
        tv.setTextColor(C_TEXT);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeBodyText() {
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(C_TEXT);
        tv.setLineSpacing(dp(2), 1);
        tv.setPadding(0, dp(4), 0, 0);
        return tv;
    }

    private TextView makeDimText() {
        TextView tv = makeBodyText();
        tv.setTextSize(10);
        tv.setTextColor(C_DIM);
        return tv;
    }

    private TextView makeButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        btn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.7f);
            else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        return btn;
    }

    private TextView makeSmallButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(6));
        btn.setBackground(bg);
        btn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.7f);
            else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        return btn;
    }

    private void setBackgroundTint(View view, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        view.setBackground(bg);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ========== Data Classes ==========

    static class MeasurementRecord {
        final float distanceMm;
        final long timestamp;

        MeasurementRecord(float distanceMm, long timestamp) {
            this.distanceMm = distanceMm;
            this.timestamp = timestamp;
        }
    }
}
