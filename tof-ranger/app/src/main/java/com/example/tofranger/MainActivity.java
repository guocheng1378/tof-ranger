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
import java.util.ArrayList;
import java.util.Date;
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
    private DistanceFilter primaryFilter = new DistanceFilter(7, 0.3f, 500f, 4000f);

    // Statistics (sliding window only)
    private DistanceStats stats = new DistanceStats(100);

    // Sensor
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelSensor;
    private boolean isProximityFallback = false;

    // Shake detection
    private ShakeDetector shakeDetector = new ShakeDetector();
    private boolean displayFrozen = false;

    // Tilt compensation
    private TiltCompensator tilt = new TiltCompensator();

    // Warm-up (discard first few noisy readings)
    private int warmUpCount = 0;
    private static final int WARM_UP_SAMPLES = 5;

    // Photo measurement
    private float lockedDistance = -1;
    private boolean isLocked = false;

    // Continuous measurement
    private boolean continuousMode = false;
    private final ArrayList<MeasurementRecord> continuousRecords = new ArrayList<>();
    private float lastStableValue = -1;
    private long lastStableTime = 0;
    private static final long STABLE_THRESHOLD_MS = 1500;
    private static final float STABLE_RANGE_MM = 10;

    // TTS
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsEnabled = false;

    // Vibration
    private Vibrator vibrator;

    // Chart
    private DistanceChartView chartView;

    // UI
    private TextView tvDistance, tvUnit, tvRawInfo, tvStatus;
    private TextView tvStats, tvHz, tvHoldLabel, tvSensorInfo;
    private TextView tvQuality, tvConfidenceBar;
    private TextView tvLockedInfo;
    private TextView tvContinuousInfo;

    // Buttons
    private View btnHold, btnReset, btnUnit;
    private View btnCapture, btnContinuous, btnExportCSV;

    // Cards
    private LinearLayout cardContinuous;

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
    private static final int C_GREEN = 0xFF059669;
    private static final int C_ORANGE = 0xFFF97316;

    private long lastUiUpdate = 0;
    private int eventCount = 0;
    private float lastFiltered = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(C_BG);

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
        root.setPadding(dp(20), dp(48), dp(20), dp(24));
        root.setBackgroundColor(C_BG);

        // === Header ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("📐 ToF 测距仪");
        tvTitle.setTextSize(18);
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
        root.addView(makeGap(dp(16)));

        // === Main Distance Card (hero) ===
        LinearLayout distCard = makeCard();
        distCard.setGravity(Gravity.CENTER_HORIZONTAL);
        distCard.setPadding(dp(24), dp(32), dp(24), dp(20));

        tvHoldLabel = new TextView(this);
        tvHoldLabel.setText("● 测量中");
        tvHoldLabel.setTextSize(11);
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
        tvUnit.setPadding(dp(4), 0, 0, 0);
        distRow.addView(tvUnit);

        distCard.addView(distRow);

        // Quality + confidence
        tvQuality = new TextView(this);
        tvQuality.setTextSize(11);
        tvQuality.setGravity(Gravity.CENTER);
        tvQuality.setPadding(0, dp(8), 0, 0);
        distCard.addView(tvQuality);

        tvConfidenceBar = new TextView(this);
        tvConfidenceBar.setTextSize(10);
        tvConfidenceBar.setTypeface(Typeface.MONOSPACE);
        tvConfidenceBar.setTextColor(C_DIM);
        tvConfidenceBar.setGravity(Gravity.CENTER);
        tvConfidenceBar.setPadding(0, dp(2), 0, 0);
        distCard.addView(tvConfidenceBar);

        // Raw info (small, dim)
        tvRawInfo = new TextView(this);
        tvRawInfo.setTextSize(10);
        tvRawInfo.setTextColor(C_DIM);
        tvRawInfo.setGravity(Gravity.CENTER);
        tvRawInfo.setPadding(0, dp(4), 0, 0);
        distCard.addView(tvRawInfo);

        tvLockedInfo = new TextView(this);
        tvLockedInfo.setTextSize(11);
        tvLockedInfo.setTextColor(C_BLUE);
        tvLockedInfo.setGravity(Gravity.CENTER);
        tvLockedInfo.setPadding(0, dp(4), 0, 0);
        tvLockedInfo.setVisibility(View.GONE);
        distCard.addView(tvLockedInfo);

        root.addView(distCard);
        root.addView(makeGap(dp(12)));

        // === Action buttons row ===
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        btnCapture = makeSmallButton("📸 锁定", C_BLUE);
        btnContinuous = makeSmallButton("⏺ 连测", C_GREEN);
        btnExportCSV = makeSmallButton("📄 导出", C_ORANGE);
        actionRow.addView(btnCapture, lp(0, dp(38), 1));
        actionRow.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        actionRow.addView(btnContinuous, lp(0, dp(38), 1));
        actionRow.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        actionRow.addView(btnExportCSV, lp(0, dp(38), 1));
        root.addView(actionRow);
        root.addView(makeGap(dp(12)));

        // === Continuous Mode Card (hidden by default) ===
        cardContinuous = makeCard();
        cardContinuous.setPadding(dp(14), dp(12), dp(14), dp(12));
        cardContinuous.setVisibility(View.GONE);
        cardContinuous.addView(makeLabel("⏺ 连续测量"));
        tvContinuousInfo = makeBodyText();
        cardContinuous.addView(tvContinuousInfo);
        root.addView(cardContinuous);
        root.addView(makeGap(dp(12)));

        // === Stats Card (compact) ===
        LinearLayout statsCard = makeCard();
        statsCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        tvStats = makeBodyText();
        tvStats.setTextSize(11);
        statsCard.addView(tvStats);
        root.addView(statsCard);
        root.addView(makeGap(dp(12)));

        // === Chart (compact) ===
        LinearLayout chartCard = makeCard();
        chartCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        chartCard.addView(makeLabel("📈 曲线"));
        chartView = new DistanceChartView(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
        chartLp.topMargin = dp(6);
        chartCard.addView(chartView, chartLp);
        root.addView(chartCard);
        root.addView(makeGap(dp(12)));

        // === Control buttons (bottom) ===
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnHold = makeButton("⏸ 暂停", C_WARN);
        btnReset = makeButton("🔄 重置", C_BLUE);
        btnUnit = makeButton("📏 cm", 0xFF8B5CF6);
        btnRow.addView(btnHold, lp(0, dp(40), 1));
        btnRow.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow.addView(btnReset, lp(0, dp(40), 1));
        btnRow.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow.addView(btnUnit, lp(0, dp(40), 1));
        root.addView(btnRow);

        root.addView(makeGap(dp(12)));

        // === Info bar (minimal) ===
        tvHz = makeDimText();
        tvHz.setTextSize(10);
        root.addView(tvHz);
        tvStatus = makeDimText();
        tvStatus.setTextSize(10);
        tvStatus.setPadding(0, dp(2), 0, 0);
        root.addView(tvStatus);

        scrollView.addView(root);
        setContentView(scrollView);

        // Listeners
        btnHold.setOnClickListener(v -> toggleHold());
        btnReset.setOnClickListener(v -> resetAll());
        btnUnit.setOnClickListener(v -> cycleUnit());
        btnCapture.setOnClickListener(v -> captureMeasurement());
        btnContinuous.setOnClickListener(v -> toggleContinuousMode());
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tofSensor != null)
            sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_GAME);
        if (accelSensor != null)
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
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

        // Shake detection
        if (type == Sensor.TYPE_ACCELEROMETER) {
            tilt.updateAccelerometer(event);
            boolean wasShaking = shakeDetector.isShaking();
            boolean nowShaking = shakeDetector.update(event);
            if (wasShaking != nowShaking) {
                displayFrozen = nowShaking;
            }
            return;
        }

        if (type != SENSOR_TYPE_TOF && type != Sensor.TYPE_PROXIMITY) return;
        if (isHolding) return;

        eventCount++;
        stats.tickHz();

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdate < 50) return; // 20fps cap
        lastUiUpdate = now;

        float rawMm = event.values[0];

        // Warm-up: discard first few noisy readings
        if (warmUpCount < WARM_UP_SAMPLES) {
            warmUpCount++;
            return;
        }

        // Filter (no offset subtraction — ToF gives absolute distance)
        float filtered = primaryFilter.filter(rawMm);

        // Shake freeze
        if (displayFrozen) {
            filtered = lastFiltered > 0 ? lastFiltered : filtered;
        }

        // Lock mode
        if (isLocked) {
            filtered = lockedDistance;
        }

        if (filtered > 0 && !displayFrozen && !isLocked) {
            stats.add(filtered);
            chartView.addPoint(filtered);
            checkContinuous(filtered);
        }

        lastFiltered = filtered;
        updateDisplay(rawMm, filtered);
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
            if (System.currentTimeMillis() - lastStableTime > STABLE_THRESHOLD_MS) {
                if (continuousRecords.isEmpty() ||
                        Math.abs(continuousRecords.get(continuousRecords.size() - 1).distanceMm - filtered) > 20) {
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
                lastStableValue = filtered;
                lastStableTime = System.currentTimeMillis();
            }
        } else {
            lastStableValue = filtered;
            lastStableTime = System.currentTimeMillis();
        }
    }

    // ========== Display ==========

    private void updateDisplay(float rawMm, float filtered) {
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

        // Round to 1mm for display (not 5mm)
        float rounded = Math.round(filtered);
        float displayVal = convertUnit(rounded, currentUnit);
        tvDistance.setText(fmt(displayVal, currentUnit));
        tvUnit.setText(" " + UNIT_LABELS[currentUnit]);

        // Raw info
        float diff = Math.abs(rawMm - filtered);
        tvRawInfo.setText(String.format(Locale.getDefault(),
                "%.0f → %.0f mm", rawMm, filtered));

        // Quality based on stddev
        float stdDev = stats.getStdDev();
        String qualityLabel;
        int qualityColor;
        if (stdDev < 3) {
            qualityLabel = "🟢 高精度";
            qualityColor = C_GOOD;
        } else if (stdDev < 10) {
            qualityLabel = "🟡 良好";
            qualityColor = C_FAIR;
        } else {
            qualityLabel = "🔴 波动";
            qualityColor = C_POOR;
        }
        tvQuality.setText(qualityLabel);
        tvQuality.setTextColor(qualityColor);

        // Confidence bar
        int barLen = Math.max(0, Math.min(20, (int) (20 - stdDev * 0.5f)));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 20; i++) bar.append(i < barLen ? "█" : "░");
        bar.append("]");
        tvConfidenceBar.setText(bar.toString());
        tvConfidenceBar.setTextColor(qualityColor);

        // Locked info
        if (isLocked) {
            tvLockedInfo.setVisibility(View.VISIBLE);
            tvLockedInfo.setText("📸 点「解锁」恢复");
        } else {
            tvLockedInfo.setVisibility(View.GONE);
        }

        // Stats
        if (stats.getSampleCount() > 0) {
            String u = UNIT_LABELS[currentUnit];
            tvStats.setText(String.format(Locale.getDefault(),
                    "范围: %s ~ %s  均值: %s  σ: %s",
                    fmt(convertUnit(stats.getMin(), currentUnit), currentUnit),
                    fmt(convertUnit(stats.getMax(), currentUnit), currentUnit),
                    fmt(convertUnit(stats.getAvg(), currentUnit), currentUnit),
                    fmtDec(stdDev, 1)));
        } else {
            tvStats.setText("等待数据...");
            tvStats.setTextColor(C_DIM);
        }

        // Info bar
        tvHz.setText(String.format(Locale.getDefault(),
                "%d Hz · %d samples", stats.getActualHz(), eventCount));

        if (isProximityFallback) {
            tvStatus.setText("⚠ 降级 Proximity 传感器");
            tvStatus.setTextColor(C_WARN);
        } else {
            tvStatus.setText(String.format(Locale.getDefault(),
                    "%.0f–%.0f mm 量程", Math.max(0, stats.getMin()), stats.getMax()));
            tvStatus.setTextColor(C_DIM);
        }
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
        tilt.reset();
        shakeDetector.reset();
        chartView.clear();
        eventCount = 0;
        lastFiltered = -1;
        isLocked = false;
        lockedDistance = -1;
        continuousMode = false;
        continuousRecords.clear();
        lastStableValue = -1;
        warmUpCount = 0;
        displayFrozen = false;
        cardContinuous.setVisibility(View.GONE);
        updateDisplay(0, -1);
    }

    private void cycleUnit() {
        currentUnit = (currentUnit + 1) % 4;
        ((TextView) btnUnit).setText("📏 " + UNIT_LABELS[currentUnit]);
    }

    private void captureMeasurement() {
        if (isLocked) {
            isLocked = false;
            lockedDistance = -1;
            ((TextView) btnCapture).setText("📸 锁定");
            tvLockedInfo.setVisibility(View.GONE);
            return;
        }

        lockedDistance = lastFiltered;
        if (lockedDistance < 0) return;

        isLocked = true;
        ((TextView) btnCapture).setText("🔓 解锁");
        tvLockedInfo.setVisibility(View.VISIBLE);
        vibrate(100);

        // Screenshot
        View rootView = getWindow().getDecorView().getRootView();
        rootView.post(() -> {
            try {
                Bitmap bitmap = Bitmap.createBitmap(rootView.getWidth(), rootView.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                rootView.draw(canvas);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String u = UNIT_LABELS[currentUnit];
                String filename = String.format("tof_%s_%s_%s.png",
                        fmt(convertUnit(lockedDistance, currentUnit), currentUnit), u, timestamp);

                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = getFilesDir();
                FileOutputStream fos = new FileOutputStream(new File(dir, filename));
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();

                String valStr = fmt(convertUnit(lockedDistance, currentUnit), currentUnit);
                speak("锁定 " + valStr + " " + u);
            } catch (Exception ignored) {}
        });
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
            speak("共记录" + continuousRecords.size() + "个点");
        }
    }

    private void exportCSV() {
        if (stats.getSampleCount() == 0) {
            speak("没有数据可导出");
            return;
        }

        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, "tof_data_" + timestamp + ".csv");

            FileWriter fw = new FileWriter(file);
            fw.write("timestamp,distance_mm\n");

            if (!continuousRecords.isEmpty()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                for (MeasurementRecord rec : continuousRecords) {
                    fw.write(String.format(Locale.getDefault(), "%s,%.1f\n",
                            sdf.format(new Date(rec.timestamp)), rec.distanceMm));
                }
            }
            fw.close();

            vibrate(100);
            speak("已导出");
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
        tv.setTextSize(12);
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
