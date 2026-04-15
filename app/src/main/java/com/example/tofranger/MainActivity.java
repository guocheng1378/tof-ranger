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

    // ── Colors — Dark theme ──
    private static final int C_BG_DARK        = Color.BLACK;
    private static final int C_ACCENT_DARK    = 0xFF5AC8FA; // blue
    private static final int C_ACCENT2_DARK   = 0xFF34C759; // green
    private static final int C_ACCENT3_DARK   = 0xFFFF9F0A; // orange
    private static final int C_TEXT_DARK      = 0xFFFFFFFF;
    private static final int C_TEXT_DIM_DARK  = 0x99FFFFFF;
    private static final int C_GLASS_BG_DARK  = 0x1AFFFFFF;
    private static final int C_GLASS_EDGE_DARK= 0x33FFFFFF;
    private static final int C_GLASS_SHINE_DARK= 0x0CFFFFFF;
    private static final int C_BAR_BG_DARK    = 0xCC000000;
    private static final int C_MORE_BG_DARK   = 0xEE111111;
    private static final int C_DEBUG_DIM_DARK = 0x66FFFFFF;
    private static final int C_SEP_DIM_DARK   = 0x33FFFFFF;

    // ── Colors — Light theme ──
    private static final int C_BG_LIGHT        = 0xFFF2F2F7;
    private static final int C_ACCENT_LIGHT    = 0xFF007AFF; // blue
    private static final int C_ACCENT2_LIGHT   = 0xFF34C759; // green
    private static final int C_ACCENT3_LIGHT   = 0xFFFF9500; // orange
    private static final int C_TEXT_LIGHT      = 0xFF1C1C1E;
    private static final int C_TEXT_DIM_LIGHT  = 0x991C1C1E;
    private static final int C_GLASS_BG_LIGHT  = 0xCCFFFFFF;
    private static final int C_GLASS_EDGE_LIGHT= 0x44000000;
    private static final int C_GLASS_SHINE_LIGHT= 0x15FFFFFF;
    private static final int C_BAR_BG_LIGHT    = 0xCCF2F2F7;
    private static final int C_MORE_BG_LIGHT   = 0xEEFFFFFF;
    private static final int C_DEBUG_DIM_LIGHT = 0x661C1C1E;
    private static final int C_SEP_DIM_LIGHT   = 0x331C1C1E;

    // ── Active colors (set by theme) ──
    private boolean isLightTheme = false;
    private int C_BG, C_ACCENT, C_ACCENT2, C_ACCENT3;
    private int C_TEXT, C_TEXT_DIM, C_GLASS_BG, C_GLASS_EDGE, C_GLASS_SHINE;
    private int C_BAR_BG, C_MORE_BG, C_DEBUG_DIM, C_SEP_DIM;
    // ── Sensor ──
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private boolean sensorRegistered = false;

    // ── State ──
    private float currentDistance = -1;
    private float filteredDistance = -1;
    private boolean isLocked = false;
    private boolean isPaused = false;
    private boolean isMm = true; // true=mm, false=inch
    private boolean moreExpanded = false;
    private boolean debugVisible = false;
    private boolean continuousMode = false;
    private long lastContinuousCsv = 0;
    private static final long CONTINUOUS_CSV_INTERVAL_MS = 200;

    // ── Filter & Stats ──
    private DistanceFilter filter;
    private DistanceStats stats;
    private ShakeDetector shakeDetector;
    private TiltCompensator tiltCompensator;

    // ── CSV ──
    private List<float[]> csvData = new ArrayList<>();
    private boolean isRecording = false;

    // ── UI ──
    private FrameLayout rootLayout;
    private ScrollView scrollView;
    private LinearLayout contentLayout;
    private LinearLayout bottomBar;
    private LinearLayout morePanel;

    // Distance display
    private GlassCard distanceCard;
    private TextView valueText;
    private TextView unitText;
    private TextView statusText;
    private QualityBarView qualityBar;
    private TextView debugText;

    // Bottom buttons
    private GlassButton lockBtn;
    private GlassButton pauseBtn;
    private GlassButton unitBtn;
    private GlassButton resetBtn;
    private GlassButton moreBtn;

    // More panel buttons
    private GlassButton debugBtn;
    private GlassButton calibrateBtn;
    private GlassButton csvBtn;
    private GlassButton continuousBtn;
    private GlassButton themeBtn;

    // ─────────────────────────────────────────────
    //  Inner classes: GlassCard, GlassButton, QualityBarView
    // ─────────────────────────────────────────────

    /**
     * Frosted glass card with semi-transparent bg, shine gradient, edge highlight, shadow.
     */
    class GlassCard extends FrameLayout {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float cornerRadius;
        private int accentTint = 0;

        public GlassCard(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setClipChildren(false);
            cornerRadius = 24f;
            bgPaint.setColor(C_GLASS_BG);
            bgPaint.setStyle(Paint.Style.FILL);
            edgePaint.setColor(C_GLASS_EDGE);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(1.5f);
            shadowPaint.setColor(0x22000000);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setMaskFilter(new BlurMaskFilter(16f, BlurMaskFilter.Blur.OUTER));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        public void setAccentTint(int color) {
            this.accentTint = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float r = cornerRadius;
            rect.set(4, 4, getWidth() - 4, getHeight() - 4);

            // Shadow
            canvas.drawRoundRect(rect, r, r, shadowPaint);

            // Background
            canvas.drawRoundRect(rect, r, r, bgPaint);

            // Accent tint bottom edge
            if (accentTint != 0) {
                Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                tintPaint.setStyle(Paint.Style.FILL);
                float edgeH = 3f;
                RectF bottomEdge = new RectF(rect.left, rect.bottom - edgeH, rect.right, rect.bottom);
                tintPaint.setColor(accentTint & 0x30FFFFFF);
                canvas.drawRoundRect(bottomEdge, r, r, tintPaint);
            }

            // Shine gradient (top half, fading down)
            shinePaint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + rect.height() * 0.5f,
                    new int[]{0x15FFFFFF, 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, shinePaint);

            // Edge highlight
            canvas.drawRoundRect(rect, r, r, edgePaint);
        }
    }

    /**
     * Liquid glass floating button with accent tint, glass reflection, glow edge, press animation.
     */
    class GlassButton extends View {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint reflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String label = "";
        private int accentColor = C_ACCENT;
        private boolean round = false;
        private boolean pressed = false;
        private float pressScale = 1f;
        private Runnable onPress;

        public GlassButton(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            bgPaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(2f);
            glowPaint.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.SOLID));
            textPaint.setColor(C_TEXT);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setAntiAlias(true);
            iconPaint.setColor(C_TEXT);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(2.5f);
            iconPaint.setAntiAlias(true);
            reflectionPaint.setStyle(Paint.Style.FILL);
        }

        public void setAccentColor(int color) {
            this.accentColor = color;
            invalidate();
        }

        public void setLabel(String text) {
            this.label = text;
            invalidate();
        }

        public void setRound(boolean r) {
            this.round = r;
            invalidate();
        }

        public void setOnPress(Runnable r) {
            this.onPress = r;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = round ? Math.min(w, h) / 2f : 16f;

            canvas.save();
            canvas.scale(pressScale, pressScale, w / 2f, h / 2f);

            rect.set(2, 2, w - 2, h - 2);

            // BG with accent tint
            bgPaint.setColor(Color.argb(0x22, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            canvas.drawRoundRect(rect, r, r, bgPaint);

            // Glass reflection (top half)
            reflectionPaint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + rect.height() * 0.45f,
                    0x18FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, reflectionPaint);

            // Glow edge
            glowPaint.setColor(Color.argb(0x40, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            canvas.drawRoundRect(rect, r, r, glowPaint);

            // Text
            if (!label.isEmpty()) {
                textPaint.setTextSize(Math.min(w, h) * 0.32f);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = h / 2f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(label, w / 2f, textY, textPaint);
            }

            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    animatePress(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pressed) {
                        animatePress(false);
                        if (onPress != null) onPress.run();
                        performClick();
                    }
                    pressed = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    animatePress(false);
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void animatePress(boolean down) {
            animate()
                    .scaleX(down ? 0.92f : 1f)
                    .scaleY(down ? 0.92f : 1f)
                    .setDuration(down ? 100 : 350)
                    .setInterpolator(down ? new AccelerateDecelerateInterpolator() : new OvershootInterpolator(3f))
                    .start();
        }
    }

    /**
     * Simple progress bar showing measurement quality (0-100%).
     */
    class QualityBarView extends View {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bgRect = new RectF();
        private final RectF fillRect = new RectF();
        private float progress = 0; // 0..1
        private int fillColor = C_ACCENT2;

        public QualityBarView(Context ctx) {
            super(ctx);
            bgPaint.setColor(isLightTheme ? 0x1A000000 : 0x1AFFFFFF);
            bgPaint.setStyle(Paint.Style.FILL);
            fillPaint.setStyle(Paint.Style.FILL);
            fillColor = C_ACCENT2;
        }

        public void setProgress(float p) {
            this.progress = Math.max(0, Math.min(1, p));
            invalidate();
        }

        public void setFillColor(int color) {
            this.fillColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float h = getHeight();
            float r = h / 2f;
            bgRect.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(bgRect, r, r, bgPaint);

            float fillW = getWidth() * progress;
            if (fillW > r * 2) {
                fillRect.set(0, 0, fillW, h);
                fillPaint.setColor(fillColor);
                canvas.drawRoundRect(fillRect, r, r, fillPaint);
            }
        }
    }

    // ─────────────────────────────────────────────
    //  Activity lifecycle
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Init helpers
        filter = new DistanceFilter(5, 0.4f, 0, 0);
        stats = new DistanceStats(200);
        shakeDetector = new ShakeDetector();
        tiltCompensator = new TiltCompensator();

        // Init colors
        applyTheme(false);

        // Build UI
        buildUI();
        setContentView(rootLayout);

        // Init sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        findTofSensor();
    }

    private void applyTheme(boolean light) {
        isLightTheme = light;
        if (light) {
            C_BG = C_BG_LIGHT;  C_ACCENT = C_ACCENT_LIGHT;  C_ACCENT2 = C_ACCENT2_LIGHT;
            C_ACCENT3 = C_ACCENT3_LIGHT;  C_TEXT = C_TEXT_LIGHT;  C_TEXT_DIM = C_TEXT_DIM_LIGHT;
            C_GLASS_BG = C_GLASS_BG_LIGHT;  C_GLASS_EDGE = C_GLASS_EDGE_LIGHT;
            C_GLASS_SHINE = C_GLASS_SHINE_LIGHT;  C_BAR_BG = C_BAR_BG_LIGHT;
            C_MORE_BG = C_MORE_BG_LIGHT;  C_DEBUG_DIM = C_DEBUG_DIM_LIGHT;
            C_SEP_DIM = C_SEP_DIM_LIGHT;
        } else {
            C_BG = C_BG_DARK;  C_ACCENT = C_ACCENT_DARK;  C_ACCENT2 = C_ACCENT2_DARK;
            C_ACCENT3 = C_ACCENT3_DARK;  C_TEXT = C_TEXT_DARK;  C_TEXT_DIM = C_TEXT_DIM_DARK;
            C_GLASS_BG = C_GLASS_BG_DARK;  C_GLASS_EDGE = C_GLASS_EDGE_DARK;
            C_GLASS_SHINE = C_GLASS_SHINE_DARK;  C_BAR_BG = C_BAR_BG_DARK;
            C_MORE_BG = C_MORE_BG_DARK;  C_DEBUG_DIM = C_DEBUG_DIM_DARK;
            C_SEP_DIM = C_SEP_DIM_DARK;
        }
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            isLocked = !isLocked;
            updateLockButton();
            vibrate(50);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ─────────────────────────────────────────────
    //  UI Building
    // ─────────────────────────────────────────────

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUI() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(C_BG);

        // ScrollView + content
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        contentLayout.setPadding(dp(20), dp(48), dp(20), dp(120));

        buildDistanceCard();
        buildStatusSection();
        buildDebugSection();

        scrollView.addView(contentLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Bottom bar
        buildBottomBar();
        buildMorePanel();

        // Compose: ScrollView + bottom bar overlay
        rootLayout.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // More panel (initially hidden)
        rootLayout.addView(morePanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // Bottom bar sits above more panel
        rootLayout.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    private void buildDistanceCard() {
        distanceCard = new GlassCard(this);
        distanceCard.setAccentTint(C_ACCENT);

        LinearLayout cardInner = new LinearLayout(this);
        cardInner.setOrientation(LinearLayout.VERTICAL);
        cardInner.setGravity(Gravity.CENTER);
        cardInner.setPadding(dp(24), dp(32), dp(24), dp(24));

        // Main distance value
        valueText = new TextView(this);
        valueText.setText("—");
        valueText.setTextColor(C_TEXT);
        valueText.setTextSize(64);
        valueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valueText.setGravity(Gravity.CENTER);
        cardInner.addView(valueText);

        // Unit label
        unitText = new TextView(this);
        unitText.setText("mm");
        unitText.setTextColor(C_ACCENT);
        unitText.setTextSize(20);
        unitText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        unitText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unitLp.topMargin = dp(4);
        cardInner.addView(unitText, unitLp);

        // Quality bar
        qualityBar = new QualityBarView(this);
        LinearLayout.LayoutParams qbLp = new LinearLayout.LayoutParams(dp(200), dp(6));
        qbLp.topMargin = dp(16);
        cardInner.addView(qualityBar, qbLp);

        // Tilt info
        statusText = new TextView(this);
        statusText.setText("寻找传感器…");
        statusText.setTextColor(C_TEXT_DIM);
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
        // Stats summary row
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER);
        statsRow.setPadding(0, dp(8), 0, dp(8));

        String[] labels = {"min", "max", "avg", "σ"};
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("  ·  ");
                sep.setTextColor(C_SEP_DIM);
                sep.setTextSize(12);
                statsRow.addView(sep);
            }
            TextView tv = new TextView(this);
            tv.setText(labels[i] + ": —");
            tv.setTextColor(C_TEXT_DIM);
            tv.setTextSize(12);
            tv.setTag("stat_" + labels[i]);
            statsRow.addView(tv);
        }
        contentLayout.addView(statsRow);
    }

    private void buildDebugSection() {
        debugText = new TextView(this);
        debugText.setTextColor(C_DEBUG_DIM);
        debugText.setTextSize(11);
        debugText.setVisibility(View.GONE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(12);
        contentLayout.addView(debugText, dlp);
    }

    private void buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(C_BAR_BG);
        bottomBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        int btnSize = dp(52);
        int smallBtnSize = dp(40);

        lockBtn = makeRoundButton("🔒", C_ACCENT);
        lockBtn.setOnPress(() -> {
            isLocked = !isLocked;
            updateLockButton();
            vibrate(30);
        });

        pauseBtn = makeRoundButton("⏸", C_ACCENT2);
        pauseBtn.setOnPress(() -> {
            isPaused = !isPaused;
            updatePauseButton();
            vibrate(30);
        });

        unitBtn = makeRoundButton("mm", C_ACCENT3);
        unitBtn.setOnPress(() -> {
            isMm = !isMm;
            updateUnitDisplay();
            vibrate(30);
        });

        resetBtn = makeRoundButton("↺", 0xFFFF453A);
        resetBtn.setOnPress(() -> {
            resetMeasurement();
            vibrate(50);
        });

        moreBtn = makeRoundButton("⋯", C_TEXT_DIM);
        moreBtn.setRound(true);
        moreBtn.setOnPress(() -> {
            moreExpanded = !moreExpanded;
            updateMorePanel();
            vibrate(30);
        });

        LinearLayout.LayoutParams roundLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        roundLp.setMargins(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams smallLp = new LinearLayout.LayoutParams(smallBtnSize, smallBtnSize);
        smallLp.setMargins(dp(8), 0, dp(8), 0);

        bottomBar.addView(lockBtn, roundLp);
        bottomBar.addView(pauseBtn, roundLp);
        bottomBar.addView(unitBtn, roundLp);
        bottomBar.addView(resetBtn, roundLp);
        bottomBar.addView(moreBtn, smallLp);
    }

    private GlassButton makeRoundButton(String label, int accent) {
        GlassButton btn = new GlassButton(this);
        btn.setLabel(label);
        btn.setAccentColor(accent);
        btn.setRound(true);
        return btn;
    }

    private void buildMorePanel() {
        morePanel = new LinearLayout(this);
        morePanel.setOrientation(LinearLayout.VERTICAL);
        morePanel.setBackgroundColor(C_MORE_BG);
        morePanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        morePanel.setVisibility(View.GONE);

        // Add bottom bar height offset
        FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) morePanel.getLayoutParams();
        if (mlp == null) {
            mlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
        }
        mlp.bottomMargin = dp(72); // above bottom bar

        int rowHeight = dp(44);

        debugBtn = makeFlatButton("调试信息", C_ACCENT);
        debugBtn.setOnPress(() -> {
            debugVisible = !debugVisible;
            debugText.setVisibility(debugVisible ? View.VISIBLE : View.GONE);
            debugBtn.setLabel(debugVisible ? "调试信息 ✓" : "调试信息");
            vibrate(30);
        });

        calibrateBtn = makeFlatButton("校准倾斜", C_ACCENT2);
        calibrateBtn.setOnPress(() -> {
            tiltCompensator.calibrate();
            vibrate(80);
            statusText.setText("已校准 ✓");
        });

        csvBtn = makeFlatButton("导出 CSV", C_ACCENT3);
        csvBtn.setOnPress(() -> {
            if (csvData.isEmpty()) {
                statusText.setText("无数据可导出");
            } else {
                exportCsv();
            }
            vibrate(50);
        });

        continuousBtn = makeFlatButton("连续测量", 0xFFFF375F);
        continuousBtn.setOnPress(() -> {
            continuousMode = !continuousMode;
            continuousBtn.setLabel(continuousMode ? "连续测量 ✓" : "连续测量");
            if (continuousMode) isRecording = true;
            vibrate(30);
        });

        themeBtn = makeFlatButton(isLightTheme ? "🌙 深色主题" : "☀️ 浅色主题", C_TEXT_DIM);
        themeBtn.setOnPress(() -> {
            isLightTheme = !isLightTheme;
            applyTheme(isLightTheme);
            rebuildUI();
            vibrate(30);
        });

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        rowLp.setMargins(0, dp(4), 0, dp(4));
        morePanel.addView(debugBtn, rowLp);
        morePanel.addView(calibrateBtn, rowLp);
        morePanel.addView(csvBtn, rowLp);
        morePanel.addView(continuousBtn, rowLp);
        morePanel.addView(themeBtn, rowLp);
    }

    private GlassButton makeFlatButton(String label, int accent) {
        GlassButton btn = new GlassButton(this);
        btn.setLabel(label);
        btn.setAccentColor(accent);
        btn.setRound(false);
        return btn;
    }

    // ─────────────────────────────────────────────
    //  UI Updates
    // ─────────────────────────────────────────────

    private void updateLockButton() {
        lockBtn.setLabel(isLocked ? "🔓" : "🔒");
        lockBtn.setAccentColor(isLocked ? 0xFFFF453A : C_ACCENT);
        distanceCard.setAccentTint(isLocked ? 0xFFFF453A : C_ACCENT);
    }

    private void updatePauseButton() {
        pauseBtn.setLabel(isPaused ? "▶" : "⏸");
        pauseBtn.setAccentColor(isPaused ? C_ACCENT2 : C_ACCENT2);
    }

    private void updateUnitDisplay() {
        unitBtn.setLabel(isMm ? "mm" : "in");
    }

    private void updateMorePanel() {
        morePanel.setVisibility(moreExpanded ? View.VISIBLE : View.GONE);
        if (moreExpanded) {
            morePanel.animate().translationY(0).setDuration(250).start();
        }
    }

    private void rebuildUI() {
        rootLayout.removeAllViews();
        buildUI();
        setContentView(rootLayout);
        themeBtn.setLabel(isLightTheme ? "🌙 深色主题" : "☀️ 浅色主题");
        themeBtn.setAccentColor(C_TEXT_DIM);
        // Restore state display
        if (filteredDistance >= 0) updateDisplay(filteredDistance);
        updateLockButton();
        updatePauseButton();
        updateUnitDisplay();
    }

    private void updateDisplay(float distMm) {
        if (isLocked) return;

        float displayDist = distMm;
        String unit = "mm";

        if (!isMm) {
            displayDist = distMm / 25.4f;
            unit = "in";
        }

        // Format value
        String valueStr;
        if (displayDist < 0) {
            valueStr = "—";
        } else if (displayDist >= 1000 || (!isMm && displayDist >= 39.37f)) {
            valueStr = String.format(Locale.US, "%.2f", displayDist);
        } else {
            valueStr = String.format(Locale.US, "%.1f", displayDist);
        }

        valueText.setText(valueStr);
        unitText.setText(unit);

        // Quality bar based on stddev
        float stddev = stats.getStdDev();
        float quality;
        if (stddev < 1) quality = 1f;
        else if (stddev < 5) quality = 0.8f;
        else if (stddev < 15) quality = 0.5f;
        else quality = 0.2f;
        qualityBar.setProgress(quality);

        int qColor = quality > 0.7f ? C_ACCENT2 : (quality > 0.4f ? C_ACCENT3 : 0xFFFF453A);
        qualityBar.setFillColor(qColor);

        // Stats row
        updateStatsRow();

        // Status line
        String tiltInfo = tiltCompensator.getTiltQuality();
        String shakeInfo = shakeDetector.isShaking() ? " | 手抖" : "";
        String lockInfo = isLocked ? " | 🔒" : "";
        statusText.setText(tiltInfo + shakeInfo + lockInfo);

        // Debug info
        if (debugVisible) {
            String dbg = String.format(Locale.US,
                    "原始: %.1f mm\n滤波: %.1f mm\n倾斜: %.1f°\n水平: %.1f mm\n" +
                    "采样: %d | Hz: %d\n偏移: %.2f mm",
                    currentDistance, filteredDistance,
                    tiltCompensator.getPitchDegrees(),
                    tiltCompensator.getHorizontalDistance(filteredDistance),
                    stats.getSampleCount(), stats.getActualHz(),
                    stats.getStdDev());
            debugText.setText(dbg);
        }
    }

    private void updateStatsRow() {
        for (int i = 0; i < contentLayout.getChildCount(); i++) {
            View child = contentLayout.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View v = row.getChildAt(j);
                    if (v instanceof TextView && v.getTag() != null) {
                        String tag = (String) v.getTag();
                        float val;
                        if (tag.equals("stat_min")) val = stats.getMin();
                        else if (tag.equals("stat_max")) val = stats.getMax();
                        else if (tag.equals("stat_avg")) val = stats.getAvg();
                        else if (tag.equals("stat_σ")) val = stats.getStdDev();
                        else continue;

                        String label = tag.replace("stat_", "");
                        if (val > 0 && !isMm) val /= 25.4f;
                        ((TextView) v).setText(String.format(Locale.US, "%s: %.1f", label, val));
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    //  Sensor Logic
    // ─────────────────────────────────────────────

    private void findTofSensor() {
        if (sensorManager == null) return;

        // Try dToF sensor types
        tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        if (tofSensor == null) {
            statusText.setText("未找到距离传感器");
            return;
        }

        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    private void registerSensors() {
        if (sensorManager == null || sensorRegistered) return;

        if (tofSensor != null) {
            sensorManager.registerListener(this, tofSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }
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
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            handleTofReading(event.values[0]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            boolean wasShaking = shakeDetector.isShaking();
            shakeDetector.update(event);
            tiltCompensator.updateAccelerometer(event);

            // Auto-unfreeze when shake stops
            if (wasShaking && !shakeDetector.isShaking() && isLocked) {
                runOnUiThread(() -> {
                    isLocked = false;
                    updateLockButton();
                });
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            tiltCompensator.updateGyroscope(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }

    private void handleTofReading(float rawMm) {
        currentDistance = rawMm;
        stats.tickHz();

        if (isPaused) return;

        // Apply filter
        filteredDistance = filter.filter(rawMm);
        if (filteredDistance < 0) return;

        stats.add(filteredDistance);

        final float dist = filteredDistance;
        runOnUiThread(() -> updateDisplay(dist));

        // Continuous CSV recording
        if (continuousMode && isRecording) {
            long now = System.currentTimeMillis();
            if (now - lastContinuousCsv >= CONTINUOUS_CSV_INTERVAL_MS) {
                csvData.add(new float[]{filteredDistance, tiltCompensator.getPitchDegrees()});
                lastContinuousCsv = now;
            }
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
        csvData.clear();
        isRecording = false;
        continuousMode = false;
        continuousBtn.setLabel("连续测量");
        currentDistance = -1;
        filteredDistance = -1;
        valueText.setText("—");
        qualityBar.setProgress(0);
        statusText.setText("已重置");
        debugText.setText("");
    }

    private void exportCsv() {
        if (csvData.isEmpty()) {
            statusText.setText("无数据");
            return;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = "tof_" + sdf.format(new Date()) + ".csv";
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, filename);

            BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            writer.write("timestamp_ms,distance_mm,tilt_deg");
            writer.newLine();

            long baseTime = System.currentTimeMillis();
            for (int i = 0; i < csvData.size(); i++) {
                float[] row = csvData.get(i);
                writer.write(String.format(Locale.US, "%d,%.1f,%.1f",
                        baseTime + i * CONTINUOUS_CSV_INTERVAL_MS, row[0], row[1]));
                writer.newLine();
            }
            writer.close();

            statusText.setText("已导出: " + filename);
            vibrate(80);

            // Try to share
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            try {
                startActivity(Intent.createChooser(shareIntent, "分享CSV"));
            } catch (ActivityNotFoundException e) {
                // no chooser available
            }

        } catch (IOException e) {
            statusText.setText("导出失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────

    private void vibrate(long ms) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm.getDefaultVibrator();
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
