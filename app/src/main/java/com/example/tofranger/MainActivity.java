package com.example.tofranger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
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

    // ===== 小米 ToF 传感器 =====
    private static final int[] KNOWN_TOF_TYPES = {33171040, 33171041, 65570, 65572};
    private static final int UNIT_MM = 0, UNIT_CM = 1, UNIT_M = 2, UNIT_INCH = 3;
    private static final String[] UNIT_LABELS = {"mm", "cm", "m", "in"};
    private int currentUnit = UNIT_CM;
    private boolean isHolding = false;
    private DistanceFilter primaryFilter = new DistanceFilter(5, 0.5f, 0, 0);
    private DistanceStats stats = new DistanceStats(100);
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private boolean isProximityFallback = false;
    private int detectedTofType = 0;
    private float unitScale = 1f;
    private boolean isCalibrated = false;
    private int warmUpCount = 0;
    private static final int WARM_UP_SAMPLES = 3;
    private float lockedDistanceMm = -1;
    private boolean isLocked = false;
    private boolean continuousMode = false;
    private final ArrayList<MeasurementRecord> continuousRecords = new ArrayList<>();
    private float lastStableValue = -1;
    private long lastStableTime = 0;
    private static final long STABLE_THRESHOLD_MS = 1500;
    private static final float STABLE_RANGE_MM = 15;
    private float calRawSum = 0;
    private int calRawCount = 0;
    private boolean isCollectingCal = false;
    private Vibrator vibrator;
    private ShakeDetector shakeDetector;
    private TiltCompensator tiltCompensator;
    private boolean lastShakeState = false;
    private long lastUiUpdate = 0;
    private int eventCount = 0;
    private float lastFilteredMm = -1;
    private float lastRawSensorValue = -1;

    // ===== MiUIX 配色 =====
    private static final int C_BG          = 0xFF000000;       // 纯黑背景
    private static final int C_SURFACE     = 0xFF1C1C1E;       // 表面色
    private static final int C_GLASS       = 0x22FFFFFF;       // 玻璃半透明
    private static final int C_GLASS_EDGE  = 0x44FFFFFF;       // 玻璃边缘高光
    private static final int C_GLASS_SHINE = 0x66FFFFFF;       // 玻璃顶部反射
    private static final int C_ACCENT      = 0xFF5AC8FA;       // MiUIX 蓝
    private static final int C_ACCENT2     = 0xFF34C759;       // MiUIX 绿
    private static final int C_ACCENT3     = 0xFFFF9F0A;       // MiUIX 橙
    private static final int C_TEXT        = 0xFFFFFFFF;       // 纯白文字
    private static final int C_TEXT_SEC    = 0x99FFFFFF;       // 次要文字
    private static final int C_TEXT_DIM    = 0x4DFFFFFF;       // 弱化文字
    private static final int C_LOCKED      = 0xFFFF9F0A;       // 锁定色
    private static final int C_ERR         = 0xFFFF453A;       // 错误色

    // ===== UI 引用 =====
    private TextView tvDistance, tvUnit, tvStatus, tvSensorLabel;
    private GlassButton btnLock, btnUnit, btnPause, btnReset;
    private GlassButton btnMore;
    private View mainCard;
    private boolean morePanelVisible = false;
    private LinearLayout morePanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(C_BG);
        getWindow().setStatusBarColor(C_BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        // 振动器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        shakeDetector = new ShakeDetector();
        tiltCompensator = new TiltCompensator();

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(C_BG);

        // ===== 主内容区 =====
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(C_BG);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(60), dp(28), dp(140));

        // — 顶部状态栏 —
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("测距仪");
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusBar.addView(tvTitle);

        tvSensorLabel = new TextView(this);
        tvSensorLabel.setTextSize(11);
        tvSensorLabel.setTextColor(C_TEXT_DIM);
        tvSensorLabel.setGravity(Gravity.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        statusBar.addView(tvSensorLabel, slp);

        content.addView(statusBar);
        content.addView(gap(dp(40)));

        // ===== 主测距卡片（毛玻璃效果） =====
        mainCard = new GlassCard(this);
        mainCard.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        LinearLayout cardInner = new LinearLayout(this);
        cardInner.setOrientation(LinearLayout.VERTICAL);
        cardInner.setGravity(Gravity.CENTER);
        cardInner.setPadding(dp(24), dp(36), dp(24), dp(36));

        // 状态指示
        TextView tvHoldLabel = new TextView(this);
        tvHoldLabel.setTextSize(12);
        tvHoldLabel.setTextColor(C_ACCENT);
        tvHoldLabel.setGravity(Gravity.CENTER);
        cardInner.addView(tvHoldLabel);
        // 保存引用
        this.tvStatus = tvHoldLabel;

        cardInner.addView(gap(dp(24)));

        // 距离数字
        tvDistance = new TextView(this);
        tvDistance.setText("--");
        tvDistance.setTextSize(86);
        tvDistance.setTextColor(C_TEXT);
        tvDistance.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/MiSans-Bold.ttf"),
                Typeface.BOLD);
        // 如果没有 MiSans 字体，用系统等宽字体替代
        try {
            tvDistance.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/MiSans-Bold.ttf"), Typeface.BOLD);
        } catch (Exception e) {
            tvDistance.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }
        tvDistance.setGravity(Gravity.CENTER);
        tvDistance.setLetterSpacing(0.02f);
        cardInner.addView(tvDistance);

        // 单位
        tvUnit = new TextView(this);
        tvUnit.setText("cm");
        tvUnit.setTextSize(20);
        tvUnit.setTextColor(C_TEXT_SEC);
        tvUnit.setGravity(Gravity.CENTER);
        tvUnit.setPadding(0, dp(2), 0, 0);
        cardInner.addView(tvUnit);

        // 精度条
        cardInner.addView(gap(dp(28)));
        View qualityBar = new QualityBarView(this);
        qualityBar.setLayoutParams(new LinearLayout.LayoutParams(dp(180), dp(4)));
        cardInner.addView(qualityBar);

        // 传感器调试（隐藏，默认不显示）
        TextView tvDebug = new TextView(this);
        tvDebug.setTextSize(9);
        tvDebug.setTextColor(C_TEXT_DIM);
        tvDebug.setGravity(Gravity.CENTER);
        tvDebug.setLineSpacing(dp(1), 1.0f);
        tvDebug.setVisibility(View.GONE);
        tvDebug.setPadding(0, dp(12), 0, 0);
        cardInner.addView(tvDebug);

        ((LinearLayout) mainCard).addView(cardInner);
        content.addView(mainCard);

        // 保存引用
        this.findViewById(android.R.id.content);

        // 更多面板
        morePanel = new LinearLayout(this);
        morePanel.setOrientation(LinearLayout.VERTICAL);
        morePanel.setVisibility(View.GONE);
        morePanel.setPadding(0, dp(16), 0, 0);

        // 统计信息行
        TextView tvStats = new TextView(this);
        tvStats.setTextSize(12);
        tvStats.setTextColor(C_TEXT_SEC);
        tvStats.setGravity(Gravity.CENTER);
        morePanel.addView(tvStats);

        morePanel.addView(gap(dp(8)));

        // 调试开关行
        LinearLayout debugRow = new LinearLayout(this);
        debugRow.setOrientation(LinearLayout.HORIZONTAL);
        debugRow.setGravity(Gravity.CENTER);
        GlassButton btnDebug = new GlassButton(this, "🔍 调试信息", C_ACCENT, dp(100), dp(38));
        GlassButton btnCalibrate = new GlassButton(this, "🎯 校准", C_ACCENT2, dp(100), dp(38));
        GlassButton btnExport = new GlassButton(this, "📄 导出", C_ACCENT3, dp(100), dp(38));
        debugRow.addView(btnDebug);
        debugRow.addView(gap(dp(8)));
        debugRow.addView(btnCalibrate);
        debugRow.addView(gap(dp(8)));
        debugRow.addView(btnExport);
        morePanel.addView(debugRow);

        morePanel.addView(gap(dp(8)));

        // 连测按钮
        LinearLayout contRow = new LinearLayout(this);
        contRow.setOrientation(LinearLayout.HORIZONTAL);
        contRow.setGravity(Gravity.CENTER);
        GlassButton btnContinuous = new GlassButton(this, "⏺ 连测", 0xFFAF52DE, dp(160), dp(38));
        contRow.addView(btnContinuous);
        morePanel.addView(contRow);

        content.addView(morePanel);

        scrollView.addView(content);

        // ===== 底部悬浮玻璃按钮栏 =====
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(20), 0, dp(20), dp(32));

        int btnSize = dp(62);

        // 锁定按钮
        btnLock = new GlassButton(this, "🔒", null, btnSize, btnSize);
        btnLock.setOnClickListener(v -> captureMeasurement());

        // 单位按钮
        btnUnit = new GlassButton(this, "cm", null, btnSize, btnSize);
        btnUnit.setOnClickListener(v -> cycleUnit());

        // 暂停按钮
        btnPause = new GlassButton(this, "⏸", null, btnSize, btnSize);
        btnPause.setOnClickListener(v -> toggleHold());

        // 重置按钮
        btnReset = new GlassButton(this, "↺", null, btnSize, btnSize);
        btnReset.setOnClickListener(v -> resetAll());

        // 更多按钮
        btnMore = new GlassButton(this, "⋯", null, dp(48), dp(48));
        btnMore.setOnClickListener(v -> toggleMorePanel());

        int btnGap = dp(14);
        bottomBar.addView(btnLock, new LinearLayout.LayoutParams(btnSize, btnSize));
        bottomBar.addView(gap(btnGap));
        bottomBar.addView(btnPause, new LinearLayout.LayoutParams(btnSize, btnSize));
        bottomBar.addView(gap(btnGap));
        bottomBar.addView(btnUnit, new LinearLayout.LayoutParams(btnSize, btnSize));
        bottomBar.addView(gap(btnGap));
        bottomBar.addView(btnReset, new LinearLayout.LayoutParams(btnSize, btnSize));
        bottomBar.addView(gap(btnGap));
        bottomBar.addView(btnMore, new LinearLayout.LayoutParams(dp(48), dp(48)));

        rootFrame.addView(scrollView);
        rootFrame.addView(bottomBar);

        setContentView(rootFrame);

        // 事件绑定
        btnDebug.setOnClickListener(v -> {
            boolean vis = tvDebug.getVisibility() == View.VISIBLE;
            tvDebug.setVisibility(vis ? View.GONE : View.VISIBLE);
        });

        btnCalibrate.setOnClickListener(v -> startCalibration());

        btnContinuous.setOnClickListener(v -> toggleContinuousMode());

        btnExport.setOnClickListener(v -> exportCSV());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
        findAdditionalSensors();
    }

    // ===== 传感器发现（保持原有逻辑） =====

    private void findTofSensor() {
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        tofSensor = null;
        String name = "未找到";

        for (int tofType : KNOWN_TOF_TYPES) {
            for (Sensor s : all) {
                if (s.getType() == tofType) {
                    tofSensor = s;
                    detectedTofType = tofType;
                    name = s.getName() + " (" + tofType + ")";
                    break;
                }
            }
            if (tofSensor != null) break;
        }

        if (tofSensor == null) {
            for (Sensor s : all) {
                String n = s.getName().toLowerCase(Locale.ROOT);
                int type = s.getType();
                if (type < 65536) continue;
                if (n.contains("tof") || n.contains("vl53") || n.contains("d-tof")
                        || n.contains("dtof") || n.contains("range")) {
                    tofSensor = s;
                    detectedTofType = type;
                    name = s.getName() + " (匹配)";
                    break;
                }
            }
        }

        if (tofSensor == null) {
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) {
                name = tofSensor.getName() + " (Proximity)";
                isProximityFallback = true;
            }
        }

        tvSensorLabel.setText(name);
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

    // ===== 传感器回调 =====

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        if (type == Sensor.TYPE_ACCELEROMETER) {
            shakeDetector.update(event);
            tiltCompensator.updateAccelerometer(event);
            if (lastShakeState != shakeDetector.isShaking()) {
                lastShakeState = shakeDetector.isShaking();
                runOnUiThread(this::updateShakeStatus);
            }
            return;
        }

        if (type == Sensor.TYPE_GYROSCOPE) {
            tiltCompensator.updateGyroscope(event);
            return;
        }

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

        if (warmUpCount < WARM_UP_SAMPLES) {
            warmUpCount++;
            return;
        }

        float overflowThresholdMm;
        if (tofSensor != null && tofSensor.getMaximumRange() > 100) {
            overflowThresholdMm = tofSensor.getMaximumRange();
        } else {
            if (tofSensor != null) {
                String sensorName = tofSensor.getName().toLowerCase(Locale.ROOT);
                if (sensorName.contains("vl53l0")) overflowThresholdMm = 1200;
                else if (sensorName.contains("vl53l1") || sensorName.contains("vl53lx")) overflowThresholdMm = 4000;
                else overflowThresholdMm = 4000;
            } else {
                overflowThresholdMm = 4000;
            }
        }

        float mm = raw * unitScale;

        if (mm >= overflowThresholdMm) {
            if (lastFilteredMm > 0) {
                updateDisplay(raw, lastFilteredMm, false);
            } else {
                updateDisplay(raw, -1, true);
            }
            return;
        }

        if (mm <= 0) return;

        if (isCollectingCal) {
            calRawSum += raw;
            calRawCount++;
            return;
        }

        float filtered = primaryFilter.filter(mm);
        if (isLocked) filtered = lockedDistanceMm;
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
            tvStatus.setText("检测到抖动");
            tvStatus.setTextColor(C_ERR);
        } else {
            tvStatus.setTextColor(C_ACCENT);
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
                    long now = System.currentTimeMillis();
                    continuousRecords.add(new MeasurementRecord(filtered, now));
                    vibrate(50);
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
            tvStatus.setText(isProximityFallback ? "降级模式" : "等待信号");
            tvStatus.setTextColor(isProximityFallback ? C_ACCENT3 : C_ACCENT);
            mainCard.invalidate();
            return;
        }

        float rounded = Math.round(filteredMm);
        float displayVal = convertUnit(rounded, currentUnit);
        tvDistance.setText(fmt(displayVal, currentUnit));
        tvUnit.setText(UNIT_LABELS[currentUnit]);

        if (isLocked) {
            tvDistance.setTextColor(C_LOCKED);
            tvStatus.setText("已锁定");
            tvStatus.setTextColor(C_LOCKED);
        } else if (isCollectingCal) {
            tvDistance.setTextColor(C_ACCENT2);
            tvStatus.setText("校准采集中…");
            tvStatus.setTextColor(C_ACCENT2);
        } else {
            tvDistance.setTextColor(C_TEXT);
            tvStatus.setText("测量中");
            tvStatus.setTextColor(C_ACCENT);
        }

        mainCard.invalidate();
    }

    // ===== 操作 =====

    private void toggleHold() {
        isHolding = !isHolding;
        if (isHolding) {
            btnPause.setText("▶");
            btnPause.setAccentColor(C_ACCENT2);
        } else {
            btnPause.setText("⏸");
            btnPause.setAccentColor(C_ACCENT);
        }
        vibrate(30);
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
        lastShakeState = false;
        btnLock.setText("🔒");
        btnPause.setText("⏸");
        btnPause.setAccentColor(C_ACCENT);
        morePanelVisible = false;
        morePanel.setVisibility(View.GONE);
        tvDistance.setText("--");
        tvDistance.setTextColor(C_TEXT);
        tvStatus.setText("已重置");
        vibrate(50);
    }

    private void cycleUnit() {
        currentUnit = (currentUnit + 1) % 4;
        btnUnit.setText(UNIT_LABELS[currentUnit]);
        vibrate(20);
    }

    private void captureMeasurement() {
        if (isLocked) {
            isLocked = false;
            lockedDistanceMm = -1;
            btnLock.setText("🔒");
            return;
        }
        lockedDistanceMm = lastFilteredMm;
        if (lockedDistanceMm < 0) return;
        isLocked = true;
        btnLock.setText("🔓");
        vibrate(80);
    }

    private void toggleMorePanel() {
        morePanelVisible = !morePanelVisible;
        morePanel.setVisibility(morePanelVisible ? View.VISIBLE : View.GONE);
    }

    // ===== 校准 =====

    private void startCalibration() {
        if (isCollectingCal) {
            finishCalibration();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("校准");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(16), dp(24), dp(8));

        TextView desc = new TextView(this);
        desc.setText("将手机对准已知距离的目标\n输入真实距离（cm）后开始采集");
        desc.setTextSize(13);
        desc.setTextColor(C_TEXT);
        layout.addView(desc);

        EditText input = new EditText(this);
        input.setHint("例如: 100");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_TEXT_DIM);
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

                tvStatus.postDelayed(() -> {
                    if (isCollectingCal) finishCalibrationWith(knownMm);
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
        if (calRawCount < 5) return;
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
        vibrate(100);
    }

    // ===== 导出 =====

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

    private void toggleContinuousMode() {
        continuousMode = !continuousMode;
        if (continuousMode) {
            continuousRecords.clear();
            lastStableValue = -1;
        }
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

    // ===== 音量键 =====

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            captureMeasurement();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===== 工具方法 =====

    private String fmt(float val, int unit) {
        switch (unit) {
            case UNIT_MM: return String.format(Locale.getDefault(), "%.0f", val);
            case UNIT_CM: return String.format(Locale.getDefault(), "%.1f", val);
            case UNIT_M: return String.format(Locale.getDefault(), "%.3f", val);
            case UNIT_INCH: return String.format(Locale.getDefault(), "%.2f", val);
            default: return String.format(Locale.getDefault(), "%.1f", val);
        }
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

    private View gap(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, height));
        return v;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static int dpStatic(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private static float dpStaticF(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    // ===== 内部类 =====

    static class MeasurementRecord {
        final float distanceMm;
        final long timestamp;
        MeasurementRecord(float distanceMm, long timestamp) {
            this.distanceMm = distanceMm;
            this.timestamp = timestamp;
        }
    }

    /**
     * 毛玻璃卡片 — 模拟 MiUIX 的 frosted glass 风格
     */
    private static class GlassCard extends LinearLayout {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        private final float radius;

        public GlassCard(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            radius = dpStatic(ctx, 28);
            setPadding(0, 0, 0, 0);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            rectF.set(0, 0, getWidth(), getHeight());

            // 半透明底色
            bgPaint.setColor(C_GLASS);
            bgPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rectF, radius, radius, bgPaint);

            // 顶部高光（模拟玻璃反射）
            LinearGradient shine = new LinearGradient(
                    0, 0, 0, getHeight() * 0.4f,
                    C_GLASS_SHINE, 0x00FFFFFF, Shader.TileMode.CLAMP);
            shinePaint.setShader(shine);
            canvas.drawRoundRect(rectF, radius, radius, shinePaint);

            // 边缘光
            edgePaint.setColor(C_GLASS_EDGE);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(dpStatic(getContext(), 1));
            canvas.drawRoundRect(rectF, radius, radius, edgePaint);

            // 底部阴影（浮动感）
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(0x11000000);
            shadowPaint.setMaskFilter(new BlurMaskFilter(dpStatic(getContext(), 16), BlurMaskFilter.Blur.OUTER));
            shadowPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rectF, radius, radius, shadowPaint);
        }
    }

    /**
     * 液态玻璃悬浮按钮 — MiUIX 风格
     * 模拟半透明玻璃材质 + 光泽反射 + 浮动阴影
     */
    static class GlassButton extends View {
        private String text;
        private int accentColor;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        private float radius;
        private float pressedScale = 1f;
        private OnClickListener clickListener;

        public GlassButton(Context ctx, String text, Integer accentColor, int widthDp, int heightDp) {
            super(ctx);
            this.text = text;
            this.accentColor = accentColor != null ? accentColor : C_GLASS_EDGE;

            float density = ctx.getResources().getDisplayMetrics().density;
            int w = (int) (widthDp * density);
            int h = (int) (heightDp * density);
            radius = h / 2f; // 全圆角（胶囊形）

            setLayoutParams(new ViewGroup.LayoutParams(w, h));

            // 文字画笔
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(h * 0.35f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setAntiAlias(true);

            setClickable(true);
            setFocusable(true);

            // 触摸动画
            setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        animate().scaleX(0.92f).scaleY(0.92f)
                                .setDuration(120)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        animate().scaleX(1f).scaleY(1f)
                                .setDuration(250)
                                .setInterpolator(new OvershootInterpolator(2f))
                                .start();
                        if (event.getAction() == MotionEvent.ACTION_UP && clickListener != null) {
                            clickListener.onClick(v);
                        }
                        break;
                }
                return true;
            });
        }

        public void setText(String text) {
            this.text = text;
            invalidate();
        }

        public void setAccentColor(int color) {
            this.accentColor = color;
            invalidate();
        }

        @Override
        public void setOnClickListener(OnClickListener l) {
            this.clickListener = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            rectF.set(0, 0, w, h);

            // === 浮动阴影 ===
            shadowPaint.setColor(0x22000000);
            shadowPaint.setMaskFilter(new BlurMaskFilter(dpStatic(getContext(), 12), BlurMaskFilter.Blur.OUTER));
            shadowPaint.setStyle(Paint.Style.FILL);
            RectF shadowRect = new RectF(2, 4, w - 2, h - 2);
            canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint);

            // === 玻璃底色（带accent色调）===
            int glassAlpha = 0x28;
            int r = Color.red(accentColor);
            int g = Color.green(accentColor);
            int b = Color.blue(accentColor);
            bgPaint.setColor(Color.argb(glassAlpha, r, g, b));
            bgPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rectF, radius, radius, bgPaint);

            // === 顶部光泽 ===
            float shineH = h * 0.45f;
            LinearGradient shine = new LinearGradient(
                    0, 0, 0, shineH,
                    Color.argb(0x40, 255, 255, 255),
                    Color.argb(0x00, 255, 255, 255),
                    Shader.TileMode.CLAMP);
            bgPaint.setShader(shine);
            RectF shineRect = new RectF(0, 0, w, shineH);
            canvas.drawRoundRect(shineRect, radius, radius, bgPaint);
            bgPaint.setShader(null);

            // === 边缘光 ===
            edgePaint.setColor(Color.argb(0x55, r, g, b));
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(dpStaticF(getContext(), 1.2f));
            canvas.drawRoundRect(rectF, radius, radius, edgePaint);

            // === 发光边缘（底部accent线） ===
            glowPaint.setColor(Color.argb(0x30, r, g, b));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(dpStatic(getContext(), 2));
            glowPaint.setMaskFilter(new BlurMaskFilter(dpStatic(getContext(), 4), BlurMaskFilter.Blur.NORMAL));
            RectF glowRect = new RectF(3, 3, w - 3, h - 3);
            canvas.drawRoundRect(glowRect, radius - 1, radius - 1, glowPaint);

            // === 文字 ===
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, w / 2f, textY, textPaint);
        }
    }

    /**
     * 精度条 — 简约动画条
     */
    private class QualityBarView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public QualityBarView(Context ctx) {
            super(ctx);
            bgPaint.setColor(0x1AFFFFFF);
            fillPaint.setColor(C_ACCENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float h = getHeight();
            float w = getWidth();
            float r = h / 2f;

            // 背景条
            canvas.drawRoundRect(0, 0, w, h, r, r, bgPaint);

            // 填充条（基于标准差）
            float stdDev = stats.getStdDev();
            float fillRatio = Math.max(0, Math.min(1f, 1f - stdDev / 25f));

            if (stdDev < 5) fillPaint.setColor(C_ACCENT2);
            else if (stdDev < 15) fillPaint.setColor(C_ACCENT3);
            else fillPaint.setColor(C_ERR);

            if (fillRatio > 0) {
                float fillW = Math.max(h, w * fillRatio);
                canvas.drawRoundRect(0, 0, fillW, h, r, r, fillPaint);
            }
        }
    }
}
