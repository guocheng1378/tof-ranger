package com.example.tofranger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    // 小米自定义 ToF 传感器类型（非 AOSP 标准，由 MIUI/HyperOS 定义）
    // 已知值：33171040（MIUI 12-14）、以及可能的新值
    // 策略：优先用硬编码值，找不到时遍历全部传感器按名称匹配
    private static final int[] KNOWN_TOF_TYPES = {33171040, 33171041, 65570, 65572};

    // Unit modes
    private static final int UNIT_MM = 0, UNIT_CM = 1, UNIT_M = 2, UNIT_INCH = 3;
    private static final String[] UNIT_LABELS = {"mm", "cm", "m", "in"};
    private int currentUnit = UNIT_CM;

    // State
    private boolean isHolding = false;

    // Signal processing
    private DistanceFilter primaryFilter = new DistanceFilter(5, 0.5f, 0, 0);

    // Statistics
    private DistanceStats stats = new DistanceStats(100);

    // Sensor
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private boolean isProximityFallback = false;
    private int detectedTofType = 0; // 实际探测到的传感器 type

    // 单位换算：传感器原始值 × unitScale = mm
    // 启动后自动根据前几个样本检测
    private float unitScale = 1f; // 默认 1（先假设 mm），自动校准
    private boolean isCalibrated = false;
    private boolean unitAutoDetected = false;

    // Warm-up + 自动单位检测
    private int warmUpCount = 0;
    private static final int WARM_UP_SAMPLES = 5;
    private float warmUpMaxRaw = 0;
    private float warmUpMinRaw = Float.MAX_VALUE;

    // Lock
    private float lockedDistanceMm = -1;
    private boolean isLocked = false;

    // Continuous
    private boolean continuousMode = false;
    private final ArrayList<MeasurementRecord> continuousRecords = new ArrayList<>();
    private float lastStableValue = -1;
    private long lastStableTime = 0;
    private static final long STABLE_THRESHOLD_MS = 1500;
    private static final float STABLE_RANGE_MM = 15;

    // Calibration
    private float calRawSum = 0;
    private int calRawCount = 0;
    private boolean isCollectingCal = false;

    // Vibration
    private Vibrator vibrator;

    // Shake detection + Tilt compensation
    private ShakeDetector shakeDetector;
    private TiltCompensator tiltCompensator;
    private boolean lastShakeState = false;

    // UI
    private TextView tvDistance, tvUnit, tvRawInfo, tvStatus;
    private TextView tvStats, tvHz, tvHoldLabel, tvSensorInfo;
    private TextView tvQuality, tvConfidenceBar;
    private TextView tvLockedInfo;
    private TextView tvContinuousInfo;
    private TextView tvSensorDebug;
    private TextView tvHorizontalInfo;

    // Buttons
    private View btnHold, btnReset, btnUnit;
    private View btnCapture, btnContinuous, btnExportCSV, btnCalibrate;

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
    private float lastFilteredMm = -1;
    private float lastRawSensorValue = -1;

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

        // Shake + Tilt
        shakeDetector = new ShakeDetector();
        tiltCompensator = new TiltCompensator();

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
        header.addView(tvSensorInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(header);
        root.addView(makeGap(dp(16)));

        // === Main Distance Card ===
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

        tvRawInfo = new TextView(this);
        tvRawInfo.setTextSize(10);
        tvRawInfo.setTextColor(C_DIM);
        tvRawInfo.setGravity(Gravity.CENTER);
        tvRawInfo.setPadding(0, dp(4), 0, 0);
        distCard.addView(tvRawInfo);

        tvHorizontalInfo = new TextView(this);
        tvHorizontalInfo.setTextSize(11);
        tvHorizontalInfo.setTextColor(C_BLUE);
        tvHorizontalInfo.setGravity(Gravity.CENTER);
        tvHorizontalInfo.setPadding(0, dp(4), 0, 0);
        tvHorizontalInfo.setVisibility(View.GONE);
        distCard.addView(tvHorizontalInfo);

        tvLockedInfo = new TextView(this);
        tvLockedInfo.setTextSize(11);
        tvLockedInfo.setTextColor(C_BLUE);
        tvLockedInfo.setGravity(Gravity.CENTER);
        tvLockedInfo.setPadding(0, dp(4), 0, 0);
        tvLockedInfo.setVisibility(View.GONE);
        distCard.addView(tvLockedInfo);

        root.addView(distCard);
        root.addView(makeGap(dp(12)));

        // === Sensor Debug ===
        LinearLayout debugCard = makeCard();
        debugCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        tvSensorDebug = new TextView(this);
        tvSensorDebug.setTextSize(10);
        tvSensorDebug.setTextColor(C_DIM);
        tvSensorDebug.setTypeface(Typeface.MONOSPACE);
        tvSensorDebug.setLineSpacing(dp(2), 1);
        debugCard.addView(tvSensorDebug);
        root.addView(debugCard);
        root.addView(makeGap(dp(12)));

        // === Action buttons ===
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        btnCapture = makeSmallButton("📸 锁定", C_BLUE);
        btnCapture.setContentDescription("锁定当前距离");
        btnCalibrate = makeSmallButton("🎯 校准", C_GREEN);
        btnCalibrate.setContentDescription("校准传感器");
        btnContinuous = makeSmallButton("⏺ 连测", 0xFF8B5CF6);
        btnContinuous.setContentDescription("连续测量");
        btnExportCSV = makeSmallButton("📄 导出", C_ORANGE);
        btnExportCSV.setContentDescription("导出CSV文件");
        actionRow.addView(btnCapture, lp(0, dp(38), 1));
        actionRow.addView(makeGap(dp(4)), lp(dp(4), 0, 0));
        actionRow.addView(btnCalibrate, lp(0, dp(38), 1));
        actionRow.addView(makeGap(dp(4)), lp(dp(4), 0, 0));
        actionRow.addView(btnContinuous, lp(0, dp(38), 1));
        actionRow.addView(makeGap(dp(4)), lp(dp(4), 0, 0));
        actionRow.addView(btnExportCSV, lp(0, dp(38), 1));
        root.addView(actionRow);
        root.addView(makeGap(dp(12)));

        // === Continuous Card ===
        cardContinuous = makeCard();
        cardContinuous.setPadding(dp(14), dp(12), dp(14), dp(12));
        cardContinuous.setVisibility(View.GONE);
        cardContinuous.addView(makeLabel("⏺ 连续测量"));
        tvContinuousInfo = makeBodyText();
        cardContinuous.addView(tvContinuousInfo);
        root.addView(cardContinuous);
        root.addView(makeGap(dp(12)));

        // === Stats ===
        LinearLayout statsCard = makeCard();
        statsCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        tvStats = makeBodyText();
        tvStats.setTextSize(11);
        statsCard.addView(tvStats);
        root.addView(statsCard);
        root.addView(makeGap(dp(12)));

        // === Controls ===
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnHold = makeButton("⏸ 暂停", C_WARN);
        btnHold.setContentDescription("暂停测量");
        btnReset = makeButton("🔄 重置", C_BLUE);
        btnReset.setContentDescription("重置所有数据");
        btnUnit = makeButton("📏 cm", 0xFF8B5CF6);
        btnUnit.setContentDescription("切换单位");
        btnRow.addView(btnHold, lp(0, dp(40), 1));
        btnRow.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow.addView(btnReset, lp(0, dp(40), 1));
        btnRow.addView(makeGap(dp(6)), lp(dp(6), 0, 0));
        btnRow.addView(btnUnit, lp(0, dp(40), 1));
        root.addView(btnRow);
        root.addView(makeGap(dp(12)));

        tvHz = makeDimText();
        tvHz.setTextSize(10);
        root.addView(tvHz);
        tvStatus = makeDimText();
        tvStatus.setTextSize(10);
        tvStatus.setPadding(0, dp(2), 0, 0);
        root.addView(tvStatus);

        scrollView.addView(root);
        setContentView(scrollView);

        btnHold.setOnClickListener(v -> toggleHold());
        btnReset.setOnClickListener(v -> resetAll());
        btnUnit.setOnClickListener(v -> cycleUnit());
        btnCapture.setOnClickListener(v -> captureMeasurement());
        btnCalibrate.setOnClickListener(v -> startCalibration());
        btnContinuous.setOnClickListener(v -> toggleContinuousMode());
        btnExportCSV.setOnClickListener(v -> exportCSV());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
        findAdditionalSensors();
    }

    private void findTofSensor() {
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        tofSensor = null;
        String name = "未找到";

        // 打印全部传感器信息到调试区（方便定位新的 type 值）
        StringBuilder allSensorsDump = new StringBuilder();
        allSensorsDump.append("=== 全部传感器 ===\n");
        for (Sensor s : all) {
            allSensorsDump.append(String.format(Locale.getDefault(),
                    "type=%d name=%s vendor=%s range=%.1f\n",
                    s.getType(), s.getName(), s.getVendor(), s.getMaximumRange()));
        }

        // 第一轮：按已知 type 值匹配
        for (int tofType : KNOWN_TOF_TYPES) {
            for (Sensor s : all) {
                if (s.getType() == tofType) {
                    tofSensor = s;
                    detectedTofType = tofType;
                    name = s.getName() + " (type=" + tofType + ")";
                    break;
                }
            }
            if (tofSensor != null) break;
        }

        // 第二轮：按名称模糊匹配（兜底，应对 type 值完全变掉的情况）
        if (tofSensor == null) {
            for (Sensor s : all) {
                String n = s.getName().toLowerCase(Locale.ROOT);
                int type = s.getType();
                // 跳过标准传感器类型（加速度、陀螺仪等）
                if (type < 65536) continue;
                if (n.contains("tof") || n.contains("vl53") || n.contains("d-tof")
                        || n.contains("dtof") || n.contains("range")) {
                    tofSensor = s;
                    detectedTofType = type;
                    name = s.getName() + " (type=" + type + " 匹配)";
                    break;
                }
            }
        }

        // 第三轮：列出所有非标准 type（>65536）的传感器供参考
        if (tofSensor == null) {
            allSensorsDump.append("\n--- 非标准传感器(type>65536) ---\n");
            for (Sensor s : all) {
                if (s.getType() > 65536) {
                    allSensorsDump.append(String.format(Locale.getDefault(),
                            "★ type=%d name=%s range=%.1f\n",
                            s.getType(), s.getName(), s.getMaximumRange()));
                }
            }
        }

        // 最终降级：Proximity
        if (tofSensor == null) {
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) {
                name = tofSensor.getName() + " (降级Proximity)";
                isProximityFallback = true;
            }
        }

        tvSensorInfo.setText(name);
        // 将传感器列表写入调试区，方便用户反馈
        final String dump = allSensorsDump.toString();
        tvSensorDebug.post(() -> tvSensorDebug.setText(dump));
    }

    private void findAdditionalSensors() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tofSensor != null)
            sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_GAME);
        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        if (gyroscope != null)
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        // Route accelerometer → ShakeDetector + TiltCompensator
        if (type == Sensor.TYPE_ACCELEROMETER) {
            shakeDetector.update(event);
            tiltCompensator.updateAccelerometer(event);
            if (lastShakeState != shakeDetector.isShaking()) {
                lastShakeState = shakeDetector.isShaking();
                runOnUiThread(() -> updateShakeStatus());
            }
            return;
        }

        // Route gyroscope → TiltCompensator
        if (type == Sensor.TYPE_GYROSCOPE) {
            tiltCompensator.updateGyroscope(event);
            return;
        }

        // Distance sensor — 用 detectedTofType 而非硬编码值
        boolean isTofEvent = (detectedTofType > 0 && type == detectedTofType)
                || type == Sensor.TYPE_PROXIMITY;
        if (!isTofEvent) return;
        if (isHolding) return;
        if (shakeDetector.isShaking()) return;

        eventCount++;
        stats.tickHz();

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdate < 50) return;
        lastUiUpdate = now;

        float raw = event.values[0];
        lastRawSensorValue = raw;

        // ====== 自动单位检测（warm-up 阶段）======
        if (warmUpCount < WARM_UP_SAMPLES) {
            warmUpCount++;
            // 跳过溢出标记值（8191 是 VL53L1X 的 out-of-range 标记）
            if (raw < 8190) {
                if (raw > warmUpMaxRaw) warmUpMaxRaw = raw;
                if (raw > 0 && raw < warmUpMinRaw) warmUpMinRaw = raw;
            }
            // warm-up 结束时做单位判断
            if (warmUpCount == WARM_UP_SAMPLES && !unitAutoDetected) {
                autoDetectUnitScale();
            }
            return;
        }

        // ====== 溢出/无效值判断 ======
        // 注意：部分设备 getMaximumRange() 返回值不可靠（如小米17PM返回1mm）
        // 策略：用传感器已知量程兜底，而非盲目信任 API
        float overflowThresholdMm;
        if (tofSensor != null && tofSensor.getMaximumRange() > 100) {
            // API 返回合理量程（>100mm），直接使用
            overflowThresholdMm = tofSensor.getMaximumRange();
        } else {
            // API 返回不合理值，按传感器类型推测量程
            if (tofSensor != null) {
                String sensorName = tofSensor.getName().toLowerCase(Locale.ROOT);
                if (sensorName.contains("vl53l0")) {
                    overflowThresholdMm = 1200; // VL53L0X 量程 1.2m
                } else if (sensorName.contains("vl53l1") || sensorName.contains("vl53lx")) {
                    overflowThresholdMm = 4000; // VL53L1X 量程 4m
                } else {
                    overflowThresholdMm = 4000; // 其他 ToF 默认 4m
                }
            } else {
                overflowThresholdMm = 4000;
            }
        }

        // 换算: 传感器原始值 × unitScale = mm
        float mm = raw * unitScale;

        // 判断是否溢出（超过传感器量程）
        if (mm >= overflowThresholdMm) {
            if (lastFilteredMm > 0) {
                updateDisplay(raw, lastFilteredMm, false);
            } else {
                updateDisplay(raw, -1, true);
            }
            return;
        }

        // 丢弃负值和零
        if (mm <= 0) {
            return;
        }

        // 校准采集中
        if (isCollectingCal) {
            calRawSum += raw;
            calRawCount++;
            return;
        }

        // 滤波
        float filtered = primaryFilter.filter(mm);

        if (isLocked) {
            filtered = lockedDistanceMm;
        }

        if (filtered > 0 && !isLocked) {
            stats.add(filtered);
            checkContinuous(filtered);
        }

        lastFilteredMm = filtered;
        updateDisplay(raw, filtered, false);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateShakeStatus() {
        if (shakeDetector.isShaking()) {
            tvStatus.setText("📵 检测到抖动，暂停更新");
            tvStatus.setTextColor(C_WARN);
        } else {
            tvStatus.setTextColor(C_DIM);
        }
    }

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
                    continuousRecords.add(new MeasurementRecord(filtered, System.currentTimeMillis()));
                    vibrate(50);
                    String u = UNIT_LABELS[currentUnit];
                    String valStr = fmt(convertUnit(filtered, currentUnit), currentUnit);
                    tvContinuousInfo.post(() -> tvContinuousInfo.setText(
                            String.format(Locale.getDefault(), "已记录 %d 个点\n最近: %s %s",
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

    private void updateDisplay(float rawSensorValue, float filteredMm, boolean noSignal) {
        if (noSignal || filteredMm <= 0) {
            tvDistance.setText("--");
            tvDistance.setTextColor(C_ERR);
            tvRawInfo.setText(noSignal ? "无信号" : "等待数据");
            tvHoldLabel.setText(noSignal ? "⚠ 无信号" : "● 测量中");
            tvHoldLabel.setTextColor(noSignal ? C_ERR : C_ACCENT);
            tvQuality.setText("");
            tvConfidenceBar.setText("");
            tvHorizontalInfo.setVisibility(View.GONE);
            updateDebug(rawSensorValue);
            return;
        }

        tvDistance.setTextColor(isLocked ? C_BLUE : C_ACCENT);
        tvHoldLabel.setTextColor(isHolding ? C_WARN : C_ACCENT);

        String state = "● 测量中";
        if (isLocked) state = "🔒 已锁定";
        else if (isHolding) state = "● 已暂停";
        else if (isCollectingCal) state = "🎯 校准采集中...";
        tvHoldLabel.setText(state);

        float rounded = Math.round(filteredMm);
        float displayVal = convertUnit(rounded, currentUnit);
        tvDistance.setText(fmt(displayVal, currentUnit));
        tvUnit.setText(" " + UNIT_LABELS[currentUnit]);

        // 显示原始值和换算后的值
        tvRawInfo.setText(String.format(Locale.getDefault(),
                "传感器: %.1f × %.2f = %.0f mm", rawSensorValue, unitScale, filteredMm));

        // 倾斜补偿：仅对 ToF 传感器有效（Proximity 降级模式下不显示）
        if (!isProximityFallback && tiltCompensator != null) {
            float pitchDeg = Math.abs(tiltCompensator.getPitchDegrees());
            if (pitchDeg > 10) {
                float hDist = tiltCompensator.getHorizontalDistance(filteredMm);
                float hDisplay = convertUnit(hDist, currentUnit);
                tvHorizontalInfo.setText(String.format(Locale.getDefault(),
                        "📐 %s · 水平: %s %s",
                        tiltCompensator.getTiltQuality(),
                        fmt(hDisplay, currentUnit),
                        UNIT_LABELS[currentUnit]));
                tvHorizontalInfo.setVisibility(View.VISIBLE);
            } else {
                tvHorizontalInfo.setVisibility(View.GONE);
            }
        }

        // Quality
        float stdDev = stats.getStdDev();
        String qLabel;
        int qColor;
        if (stdDev < 5) { qLabel = "🟢 高精度"; qColor = C_GOOD; }
        else if (stdDev < 15) { qLabel = "🟡 良好"; qColor = C_FAIR; }
        else { qLabel = "🔴 波动"; qColor = C_POOR; }
        tvQuality.setText(qLabel);
        tvQuality.setTextColor(qColor);

        int barLen = Math.max(0, Math.min(20, (int) (20 - stdDev * 0.4f)));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 20; i++) bar.append(i < barLen ? "█" : "░");
        bar.append("]");
        tvConfidenceBar.setText(bar.toString());
        tvConfidenceBar.setTextColor(qColor);

        tvLockedInfo.setVisibility(isLocked ? View.VISIBLE : View.GONE);
        if (isLocked) tvLockedInfo.setText("📸 点「解锁」恢复");

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
        }

        updateDebug(rawSensorValue);

        tvHz.setText(String.format(Locale.getDefault(),
                "%d Hz · %d samples", stats.getActualHz(), eventCount));

        if (isProximityFallback) {
            tvStatus.setText("⚠ 降级 Proximity");
            tvStatus.setTextColor(C_WARN);
        } else if (!shakeDetector.isShaking()) {
            tvStatus.setTextColor(C_DIM);
        }
    }

    private void updateDebug(float rawSensorValue) {
        float apiRange = tofSensor != null ? tofSensor.getMaximumRange() : 0;
        float apiRes = tofSensor != null ? tofSensor.getResolution() : 0;

        tvSensorDebug.setText(String.format(Locale.getDefault(),
                "传感器: %s (type=%d)\n" +
                "API量程: %.0f mm  API分辨率: %.4f\n" +
                "换算系数: %.4f %s  单位自动检测: %s\n" +
                "warm-up maxRaw: %.2f  minRaw: %.2f\n" +
                "最近原始值: %.2f",
                tofSensor != null ? tofSensor.getName() : "null",
                detectedTofType,
                apiRange, apiRes,
                unitScale, isCalibrated ? "(已校准)" : "(自动)",
                unitAutoDetected ? "已完成" : "未完成",
                warmUpMaxRaw, warmUpMinRaw,
                rawSensorValue));
    }

    // ========== 自动单位检测 ==========

    /**
     * 根据 warm-up 阶段采集的样本自动判断传感器输出单位。
     *
     * 逻辑：
     * - VL53L1X 量程 4000mm，正常读数 50~4000，传感器直接输出 mm
     * - VL53L0X 量程 1200mm，正常读数 50~1200，传感器直接输出 mm
     * - 如果传感器输出 cm，同样距离值会小 10 倍（如 1m → 100）
     * - 如果 maxRaw < 500 → 很可能是 cm 单位 → unitScale = 10
     * - 如果 maxRaw >= 500 → 大概率是 mm 单位 → unitScale = 1
     */
    private void autoDetectUnitScale() {
        // 如果 warm-up 全是溢出值，maxRaw 可能还是 0
        if (warmUpMaxRaw <= 0) {
            // 保守默认：VL53L1X 输出 mm
            unitScale = 1f;
            unitAutoDetected = true;
            return;
        }

        // 用 API 返回的量程做辅助判断（仅当量程合理时）
        float apiRange = tofSensor != null ? tofSensor.getMaximumRange() : 0;

        if (warmUpMaxRaw < 500 && apiRange > 500) {
            // API 说量程 >500mm，但实际读数 <500 → 传感器输出 cm
            unitScale = 10f;
        } else if (warmUpMaxRaw >= 500) {
            // 读数已经 >=500，传感器输出 mm
            unitScale = 1f;
        } else if (apiRange > 0 && apiRange <= 100) {
            // API 返回的量程很小（cm 级）→ 传感器输出 cm
            unitScale = 10f;
        } else if (warmUpMaxRaw > 100) {
            // 读数 > 100，大概率是 mm
            unitScale = 1f;
        } else {
            // 保守默认：mm
            unitScale = 1f;
        }

        unitAutoDetected = true;
    }

    // ========== 校准 ==========

    private void startCalibration() {
        if (isCollectingCal) {
            finishCalibration();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🎯 校准单位换算");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(8));

        TextView desc = new TextView(this);
        desc.setText("1. 将手机对准一个已知距离的目标（如尺子）\n2. 保持不动\n3. 在下方输入真实距离（cm）\n4. 点「开始采集」后保持 2 秒");
        desc.setTextSize(12);
        desc.setTextColor(C_TEXT);
        layout.addView(desc);

        EditText input = new EditText(this);
        input.setHint("例如: 100");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_DIM);
        layout.addView(input);

        builder.setView(layout);

        builder.setPositiveButton("开始采集", (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            try {
                float knownCm = Float.parseFloat(text);
                float knownMm = knownCm * 10f;

                calRawSum = 0;
                calRawCount = 0;
                isCollectingCal = true;

                ((TextView) btnCalibrate).setText("⏳ 采集中");
                setBackgroundTint(btnCalibrate, C_WARN);

                tvHoldLabel.postDelayed(() -> {
                    if (isCollectingCal) {
                        finishCalibrationWith(knownMm);
                    }
                }, 2000);

            } catch (NumberFormatException ignored) {}
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void finishCalibration() {
        if (calRawCount > 0 && lastFilteredMm > 0) {
            finishCalibrationWith(lastFilteredMm);
        }
    }

    private void finishCalibrationWith(float knownMm) {
        isCollectingCal = false;

        if (calRawCount < 5) {
            ((TextView) btnCalibrate).setText("🎯 校准");
            setBackgroundTint(btnCalibrate, C_GREEN);
            return;
        }

        float avgRaw = calRawSum / calRawCount;

        if (avgRaw > 0) {
            unitScale = knownMm / avgRaw;
            isCalibrated = true;
        }

        primaryFilter.reset();
        stats.reset();
        warmUpCount = 0;
        eventCount = 0;

        calRawSum = 0;
        calRawCount = 0;

        ((TextView) btnCalibrate).setText("✅ 已校准");
        setBackgroundTint(btnCalibrate, C_GOOD);
        vibrate(100);

        btnCalibrate.postDelayed(() -> {
            ((TextView) btnCalibrate).setText("🎯 校准");
            setBackgroundTint(btnCalibrate, C_GREEN);
        }, 2000);
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
        shakeDetector.reset();
        tiltCompensator.reset();
        eventCount = 0;
        lastFilteredMm = -1;
        lastRawSensorValue = -1;
        isLocked = false;
        lockedDistanceMm = -1;
        continuousMode = false;
        continuousRecords.clear();
        lastStableValue = -1;
        warmUpCount = 0;
        isCollectingCal = false;
        calRawSum = 0;
        calRawCount = 0;
        unitScale = 1f;
        isCalibrated = false;
        unitAutoDetected = false;
        warmUpMaxRaw = 0;
        warmUpMinRaw = Float.MAX_VALUE;
        lastShakeState = false;
        cardContinuous.setVisibility(View.GONE);
        ((TextView) btnCalibrate).setText("🎯 校准");
        setBackgroundTint(btnCalibrate, C_GREEN);
        updateDisplay(0, -1, false);
    }

    private void cycleUnit() {
        currentUnit = (currentUnit + 1) % 4;
        ((TextView) btnUnit).setText("📏 " + UNIT_LABELS[currentUnit]);
    }

    private void captureMeasurement() {
        if (isLocked) {
            isLocked = false;
            lockedDistanceMm = -1;
            ((TextView) btnCapture).setText("📸 锁定");
            tvLockedInfo.setVisibility(View.GONE);
            return;
        }
        lockedDistanceMm = lastFilteredMm;
        if (lockedDistanceMm < 0) return;
        isLocked = true;
        ((TextView) btnCapture).setText("🔓 解锁");
        tvLockedInfo.setVisibility(View.VISIBLE);
        vibrate(100);

        View rootView = getWindow().getDecorView().getRootView();
        rootView.post(() -> {
            try {
                Bitmap bitmap = Bitmap.createBitmap(rootView.getWidth(), rootView.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                rootView.draw(canvas);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String u = UNIT_LABELS[currentUnit];
                String fn = String.format("tof_%s_%s_%s.png",
                        fmt(convertUnit(lockedDistanceMm, currentUnit), currentUnit), u, ts);
                File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) dir = getFilesDir();
                FileOutputStream fos = new FileOutputStream(new File(dir, fn));
                // API 31+ 使用 Bitmap.CompressFormat.PNG (非废弃版本)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                } else {
                    @SuppressWarnings("deprecation")
                    Bitmap.CompressFormat fmt = Bitmap.CompressFormat.PNG;
                    bitmap.compress(fmt, 100, fos);
                }
                fos.close();
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
        } else {
            cardContinuous.setVisibility(View.GONE);
            ((TextView) btnContinuous).setText("⏺ 连测");
            setBackgroundTint(btnContinuous, C_GREEN);
        }
    }

    private static final int REQUEST_CREATE_CSV = 1001;

    private void exportCSV() {
        if (stats.getSampleCount() == 0) return;

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "tof_data_" + ts + ".csv";

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {
            startActivityForResult(intent, REQUEST_CREATE_CSV);
        } catch (ActivityNotFoundException e) {
            // 无文件管理器可用，fallback 到 app 私有目录
            exportCSVToAppDir();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREATE_CSV && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) writeCSVToUri(uri);
        }
    }

    private void writeCSVToUri(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) return;
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os));
            bw.write("timestamp,distance_mm\n");
            if (!continuousRecords.isEmpty()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                for (MeasurementRecord rec : continuousRecords) {
                    bw.write(String.format(Locale.getDefault(), "%s,%.1f\n",
                            sdf.format(new Date(rec.timestamp)), rec.distanceMm));
                }
            }
            bw.close();
            vibrate(100);
        } catch (IOException ignored) {}
    }

    private void exportCSVToAppDir() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();
            FileWriter fw = new FileWriter(new File(dir, "tof_data_" + ts + ".csv"));
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
        } catch (IOException ignored) {}
    }

    private void vibrate(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            @SuppressWarnings("deprecation")
            Vibrator v = vibrator;
            v.vibrate(ms);
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

    // ========== UI ==========

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

    static class MeasurementRecord {
        final float distanceMm;
        final long timestamp;
        MeasurementRecord(float distanceMm, long timestamp) {
            this.distanceMm = distanceMm;
            this.timestamp = timestamp;
        }
    }
}
