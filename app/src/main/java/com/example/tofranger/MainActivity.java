package com.example.tofranger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
    private boolean isLightTheme = true;
    private int C_BG, C_ACCENT, C_ACCENT2, C_ACCENT3;
    private int C_TEXT, C_TEXT_DIM, C_GLASS_BG, C_GLASS_EDGE, C_GLASS_SHINE;
    private int C_BAR_BG, C_MORE_BG, C_DEBUG_DIM, C_SEP_DIM;
    // ── Sensor ──
    // 小米自定义 ToF 传感器类型（非 AOSP 标准，由 MIUI/HyperOS 定义）
    private static final int SENSOR_TYPE_MIUI_TOF = 33171040;
    private boolean isProximityFallback = false;
    private SensorManager sensorManager;
    private Sensor tofSensor;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private boolean sensorRegistered = false;

    // ── State ──
    private volatile float currentDistance = -1;
    private volatile float smoothDisplay = -1; // smoothed display value (needs volatile for sensor/UI thread)
    private volatile float filteredDistance = -1;
    private boolean isLocked = false;
    private boolean isPaused = false;
    private int unitMode = 0; // 0=cm, 1=mm, 2=inch
    private boolean moreExpanded = false;
    private boolean debugVisible = false;
    private boolean continuousMode = false;
    private long lastContinuousCsv = 0;
    private static final long CONTINUOUS_CSV_INTERVAL_MS = 200;

    // UI update throttle
    private static final long UI_UPDATE_INTERVAL_MS = 50; // max 20 fps
    private long lastUiUpdateMs = 0;

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
    private LinearLayout morePanel;

    // Distance display
    private GlassCard distanceCard;
    private TextView valueText;
    private TextView unitText;
    private TextView statusText;
    private QualityBarView qualityBar;
    private TextView debugText;
    private TextView statMinText, statMaxText, statAvgText, statStdText;

    // Bottom bar floating container
    private FrameLayout bottomBarFloat;

    // Bottom buttons
    private GlassButton lockBtn;
    private GlassButton pauseBtn;
    private GlassButton recordBtn;
    private GlassButton unitBtn;
    private GlassButton moreBtn;
    private GlassButton resetBtn;

    // More panel buttons
    private GlassButton debugBtn;
    private GlassButton calibrateBtn;
    private GlassButton continuousBtn;
    private GlassButton themeBtn;

    // Record section
    private TextView recordStatusText;
    private TextView recordCountText;
    private TextView recordTimeText;
    private TextView recordDistText;
    private long recordStartTime = 0;

    // ─────────────────────────────────────────────
    //  Inner classes: GlassCard, GlassButton, QualityBarView
    // ─────────────────────────────────────────────

    /**
     * Apple Liquid Glass card — multi-layer frosted glass with
     * specular highlight, rim light, inner shadow, and accent tint.
     */
    class GlassCard extends FrameLayout {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF bottomEdge = new RectF();
        private float cornerRadius;
        private int accentTint = 0;
        // Cached gradient bounds to detect size changes
        private float lastSpecH = -1, lastRimH = -1, lastW = -1, lastH = -1;
        private boolean themeDirty = true;

        public GlassCard(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setClipChildren(false);
            cornerRadius = 28f;
            bgPaint.setColor(C_GLASS_BG);
            bgPaint.setStyle(Paint.Style.FILL);
            edgePaint.setColor(C_GLASS_EDGE);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(1.2f);
            // Remove BlurMaskFilter + software layer: use elevation shadow instead
            tintPaint.setStyle(Paint.Style.FILL);
        }

        public void setAccentTint(int color) {
            this.accentTint = color;
            invalidate();
        }

        /** Call after theme change to refresh gradient colors */
        public void onThemeChanged() {
            themeDirty = true;
            bgPaint.setColor(C_GLASS_BG);
            edgePaint.setColor(C_GLASS_EDGE);
            invalidate();
        }

        private void rebuildGradientsIfNeeded() {
            float w = getWidth();
            float h = getHeight();
            float specH = h * 0.35f;
            float rimH = h * 0.25f;
            if (!themeDirty && w == lastW && h == lastH && specH == lastSpecH && rimH == lastRimH) return;
            lastW = w; lastH = h; lastSpecH = specH; lastRimH = rimH; themeDirty = false;
            float inset = 5f;
            // Shine gradient (top specular)
            int specTopC = isLightTheme ? 0x1A000000 : 0x22FFFFFF;
            shinePaint.setShader(new LinearGradient(
                    inset, inset, inset, inset + specH,
                    new int[]{specTopC, 0x00000000},
                    null, Shader.TileMode.CLAMP));
            // Rim gradient (bottom reflection)
            int rimBotC = isLightTheme ? 0x0A000000 : 0x0DFFFFFF;
            rimPaint.setShader(new LinearGradient(
                    inset, h - inset - rimH, inset, h - inset,
                    new int[]{0x00000000, rimBotC},
                    null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float r = cornerRadius;
            float inset = 5f;
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

            // Background fill
            canvas.drawRoundRect(rect, r, r, bgPaint);

            rebuildGradientsIfNeeded();

            // Layer 1: Top specular highlight
            canvas.drawRoundRect(rect, r, r, shinePaint);

            // Layer 2: Bottom rim glow
            canvas.drawRoundRect(rect, r, r, rimPaint);

            // Layer 3: Edge highlight
            canvas.drawRoundRect(rect, r, r, edgePaint);

            // Layer 4: Inner shadow (top edge)
            float innerShadowH = 6f;
            int innerShadowC = isLightTheme ? 0x0D000000 : 0x15000000;
            innerShadowPaint.setShader(new LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + innerShadowH,
                    new int[]{innerShadowC, 0x00000000},
                    null, Shader.TileMode.CLAMP));
            innerShadowPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(
                    new RectF(rect.left, rect.top, rect.right, rect.top + innerShadowH),
                    r, r, innerShadowPaint);

            // Accent tint bottom edge
            if (accentTint != 0) {
                float edgeH = 3.5f;
                bottomEdge.set(rect.left, rect.bottom - edgeH, rect.right, rect.bottom);
                tintPaint.setColor(accentTint & 0x30FFFFFF);
                canvas.drawRoundRect(bottomEdge, r, r, tintPaint);
            }
        }
    }

    /**
     * Liquid glass floating button with accent tint, glass reflection, glow edge, press animation.
     */
    class GlassButton extends View {

        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int iconType = 0; // 0=lock, 1=pause/play, 2=ruler, 3=sun/moon
        private String label = "";
        private int accentColor = C_ACCENT;
        private boolean active = false;
        private boolean pressed = false;
        private float pressScale = 1f;
        private Runnable onPress;

        // Icon types
        static final int ICON_LOCK = 0;
        static final int ICON_PAUSE = 1;
        static final int ICON_RULER = 2;
        static final int ICON_THEME = 3;
        static final int ICON_RECORD = 4;
        static final int ICON_MORE = 5;

        public GlassButton(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);

            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            iconPaint.setTextAlign(Paint.Align.CENTER);

            bgPaint.setStyle(Paint.Style.FILL);

            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setAntiAlias(true);
            labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        public void setAccentColor(int color) {
            this.accentColor = color;
            invalidate();
        }

        public void setLabel(String text) {
            this.label = text;
            invalidate();
        }

        public void setIconType(int type) {
            this.iconType = type;
            invalidate();
        }

        public void setActive(boolean a) {
            this.active = a;
            invalidate();
        }

        public void setRound(boolean r) {
            // kept for compatibility; icon buttons ignore this
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

            canvas.save();
            canvas.scale(pressScale, pressScale, w / 2f, h / 2f);

            float cx = w / 2f;
            float iconSize = Math.min(w, h) * 0.38f;
            float strokeW = dp(1.8f);
            iconPaint.setStrokeWidth(strokeW);

            // Active/inactive color
            int iconColor = active ? accentColor : (isLightTheme ? 0xFF8E8E93 : 0xFF8E8E93);
            iconPaint.setColor(iconColor);

            // Icon center Y (shifted up to make room for label)
            float iconCy = h * 0.38f;

            // Draw the appropriate icon
            switch (iconType) {
                case ICON_LOCK: drawLockIcon(canvas, cx, iconCy, iconSize); break;
                case ICON_PAUSE: drawPauseIcon(canvas, cx, iconCy, iconSize); break;
                case ICON_RULER: drawRulerIcon(canvas, cx, iconCy, iconSize); break;
                case ICON_THEME: drawThemeIcon(canvas, cx, iconCy, iconSize); break;
                case ICON_RECORD: drawRecordIcon(canvas, cx, iconCy, iconSize); break;
                case ICON_MORE: drawMoreIcon(canvas, cx, iconCy, iconSize); break;
                default:
                    // Text-only button (for more panel)
                    if (!label.isEmpty()) {
                        iconPaint.setStyle(Paint.Style.STROKE);
                        RectF bgR = new RectF(4, 4, w - 4, h - 4);
                        int bgColor = Color.argb(0x15, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
                        iconPaint.setColor(bgColor);
                        iconPaint.setStyle(Paint.Style.FILL);
                        canvas.drawRoundRect(bgR, dp(12), dp(12), iconPaint);
                        iconPaint.setStyle(Paint.Style.STROKE);

                        // Accent edge
                        iconPaint.setColor(Color.argb(0x30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
                        RectF edge = new RectF(bgR.left, bgR.bottom - dp(2), bgR.right, bgR.bottom);
                        canvas.drawRoundRect(edge, dp(12), dp(12), iconPaint);

                        labelPaint.setColor(isLightTheme ? C_TEXT_LIGHT : C_TEXT_DARK);
                        labelPaint.setTextSize(dp(13));
                        Paint.FontMetrics fm = labelPaint.getFontMetrics();
                        float ty = h / 2f - (fm.ascent + fm.descent) / 2f;
                        canvas.drawText(label, cx, ty, labelPaint);
                    }
                    break;
            }

            // Label below icon
            if (!label.isEmpty()) {
                labelPaint.setColor(iconColor);
                labelPaint.setTextSize(dp(9));
                Paint.FontMetrics fm = labelPaint.getFontMetrics();
                float labelY = h * 0.82f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(label, cx, labelY, labelPaint);
            }

            canvas.restore();
        }

        // Lock icon: open or closed padlock
        private void drawLockIcon(Canvas c, float cx, float cy, float s) {
            float hw = s * 0.35f; // half width of body
            float bh = s * 0.3f;  // body height
            float bodyTop = cy - bh * 0.1f;
            float bodyBot = bodyTop + bh;

            // Shackle (U-shape on top)
            float shackW = hw * 0.65f;
            float shackH = s * 0.25f;
            float shackTop = bodyTop - shackH;

            if (active) {
                // Locked: shackle attached
                c.drawArc(cx - shackW, shackTop, cx + shackW, bodyTop, 180, 180, false, iconPaint);
            } else {
                // Unlocked: shackle tilted
                c.drawArc(cx - shackW, shackTop - s * 0.05f, cx + shackW + s * 0.08f, bodyTop, 200, 160, false, iconPaint);
            }

            // Body (rounded rect)
            RectF body = new RectF(cx - hw, bodyTop, cx + hw, bodyBot);
            c.drawRoundRect(body, dp(3), dp(3), iconPaint);

            // Keyhole
            float kcx = cx;
            float kcy = bodyTop + bh * 0.5f;
            c.drawCircle(kcx, kcy, s * 0.06f, iconPaint);
            c.drawLine(kcx, kcy + s * 0.06f, kcx, kcy + s * 0.15f, iconPaint);
        }

        // Pause icon: two vertical bars, or play triangle when active
        private void drawPauseIcon(Canvas c, float cx, float cy, float s) {
            if (active) {
                // Play triangle
                float sz = s * 0.35f;
                Path tri = new Path();
                tri.moveTo(cx - sz * 0.4f, cy - sz);
                tri.lineTo(cx - sz * 0.4f, cy + sz);
                tri.lineTo(cx + sz * 0.7f, cy);
                tri.close();
                iconPaint.setStyle(Paint.Style.FILL);
                c.drawPath(tri, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
            } else {
                // Pause bars
                float bw = s * 0.12f;
                float bh = s * 0.6f;
                float gap = s * 0.18f;
                RectF bar1 = new RectF(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f);
                RectF bar2 = new RectF(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f);
                iconPaint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(bar1, dp(2), dp(2), iconPaint);
                c.drawRoundRect(bar2, dp(2), dp(2), iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
            }
        }

        // Ruler icon: horizontal line with tick marks
        private void drawRulerIcon(Canvas c, float cx, float cy, float s) {
            float hw = s * 0.45f;
            float hh = s * 0.22f;

            // Main outline
            RectF body = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
            c.drawRoundRect(body, dp(2), dp(2), iconPaint);

            // Tick marks
            float tickH = hh * 0.6f;
            for (int i = -2; i <= 2; i++) {
                float x = cx + i * (hw * 0.4f);
                float tickLen = (i == 0) ? hh : tickH * 0.6f;
                c.drawLine(x, cy - tickLen, x, cy + tickLen, iconPaint);
            }
        }

        // Sun/Moon icon
        private void drawThemeIcon(Canvas c, float cx, float cy, float s) {
            float r = s * 0.22f;
            if (active) {
                // Moon (dark mode active → show moon)
                Path moon = new Path();
                float mr = r * 1.1f;
                moon.addCircle(cx - mr * 0.3f, cy, mr, Path.Direction.CW);
                Path cutout = new Path();
                cutout.addCircle(cx + mr * 0.4f, cy - mr * 0.15f, mr * 0.75f, Path.Direction.CW);
                moon.op(cutout, Path.Op.DIFFERENCE);
                iconPaint.setStyle(Paint.Style.FILL);
                c.drawPath(moon, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
            } else {
                // Sun (light mode active → show sun)
                c.drawCircle(cx, cy, r, iconPaint);
                // Rays
                float rayLen = r * 0.5f;
                float rayStart = r * 1.25f;
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * 2 * i / 8;
                    float x1 = cx + (float) Math.cos(angle) * rayStart;
                    float y1 = cy + (float) Math.sin(angle) * rayStart;
                    float x2 = cx + (float) Math.cos(angle) * (rayStart + rayLen);
                    float y2 = cy + (float) Math.sin(angle) * (rayStart + rayLen);
                    c.drawLine(x1, y1, x2, y2, iconPaint);
                }
            }
        }

        // Record icon: solid circle (recording) or circle outline (idle)
        private void drawRecordIcon(Canvas c, float cx, float cy, float s) {
            float r = s * 0.3f;
            if (active) {
                // Recording: pulsing red dot (solid fill)
                iconPaint.setStyle(Paint.Style.FILL);
                iconPaint.setColor(0xFFFF3B30); // iOS red
                c.drawCircle(cx, cy, r, iconPaint);
                iconPaint.setStyle(Paint.Style.STROKE);
            } else {
                // Idle: circle outline
                iconPaint.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, r, iconPaint);
            }
        }

        // More icon: three horizontal dots
        private void drawMoreIcon(Canvas c, float cx, float cy, float s) {
            float dotR = s * 0.07f;
            float spacing = s * 0.22f;
            iconPaint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - spacing, cy, dotR, iconPaint);
            c.drawCircle(cx, cy, dotR, iconPaint);
            c.drawCircle(cx + spacing, cy, dotR, iconPaint);
            iconPaint.setStyle(Paint.Style.STROKE);
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
                    .scaleX(down ? 0.88f : 1f)
                    .scaleY(down ? 0.88f : 1f)
                    .setDuration(down ? 80 : 300)
                    .setInterpolator(down ? new AccelerateDecelerateInterpolator() : new OvershootInterpolator(2.5f))
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
        applyTheme(true); // 默认白色主题

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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // ACTION_DOWN 时就拦截，阻止系统音量条弹出
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
                event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // 音量上 → 锁定/解锁
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                isLocked = !isLocked;
                updateLockButton();
                vibrate(30);
                return true;
            }
            // 音量下 → 记录一条距离数据
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                recordSingleDataPoint();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 记录一条距离数据点（音量下键 和 界面按钮 共用） */
    private void recordSingleDataPoint() {
        float dist = (filteredDistance >= 0) ? filteredDistance : currentDistance;
        if (dist >= 0) {
            csvData.add(new float[]{dist, tiltCompensator.getPitchDegrees()});
            final int count = csvData.size();
            final String distStr = formatDistance(dist);
            runOnUiThread(() -> {
                if (recordCountText != null) recordCountText.setText(count + " 条数据");
                if (recordStatusText != null) {
                    recordStatusText.setText("● 已记录");
                    recordStatusText.setTextColor(C_ACCENT);
                }
                if (recordDistText != null) recordDistText.setText("最近: " + distStr);
            });
            vibrate(50);
        }
    }

    /** 格式化距离显示 */
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
        buildRecordSection();
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

        // More panel (initially hidden) — positioned above floating bar
        morePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        moreLp.bottomMargin = dp(88);
        rootLayout.addView(morePanel, moreLp);

        // Floating bottom bar
        rootLayout.addView(bottomBarFloat, new FrameLayout.LayoutParams(
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
        valueText.setTextSize(72);
        valueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valueText.setGravity(Gravity.CENTER);
        cardInner.addView(valueText);

        // Unit label
        unitText = new TextView(this);
        unitText.setText("cm");
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
        TextView[] refs = new TextView[4];
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
            refs[i] = tv;
            statsRow.addView(tv);
        }
        statMinText = refs[0];
        statMaxText = refs[1];
        statAvgText = refs[2];
        statStdText = refs[3];
        statsRow.setVisibility(View.GONE); // 隐藏统计行
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

    private void buildRecordSection() {
        GlassCard recordCard = new GlassCard(this);
        recordCard.setAccentTint(0xFFFF3B30);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(20), dp(16), dp(20), dp(16));

        // Left: status text + counter
        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        recordStatusText = new TextView(this);
        recordStatusText.setText("数据记录");
        recordStatusText.setTextColor(C_TEXT);
        recordStatusText.setTextSize(16);
        recordStatusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        infoCol.addView(recordStatusText);

        recordCountText = new TextView(this);
        recordCountText.setText("0 条数据");
        recordCountText.setTextColor(C_TEXT_DIM);
        recordCountText.setTextSize(13);
        LinearLayout.LayoutParams cntLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cntLp.topMargin = dp(4);
        infoCol.addView(recordCountText, cntLp);

        recordTimeText = new TextView(this);
        recordTimeText.setText("0:00");
        recordTimeText.setTextColor(C_TEXT_DIM);
        recordTimeText.setTextSize(12);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dp(2);
        infoCol.addView(recordTimeText, timeLp);

        // 距离值预览行
        recordDistText = new TextView(this);
        recordDistText.setText("");
        recordDistText.setTextColor(C_ACCENT);
        recordDistText.setTextSize(14);
        recordDistText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams distLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        distLp.topMargin = dp(4);
        infoCol.addView(recordDistText, distLp);

        inner.addView(infoCol, infoLp);

        // Right: record button
        recordBtn = new GlassButton(this);
        recordBtn.setIconType(GlassButton.ICON_RECORD);
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
                // 立即记录当前距离
                recordSingleDataPoint();
            } else {
                continuousMode = false;
                recordBtn.setLabel("开始");
                recordBtn.setActive(false);
                recordStatusText.setText("数据记录");
                recordStatusText.setTextColor(C_TEXT);
                if (!csvData.isEmpty()) {
                    exportCsv();
                }
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

    /**
     * Apple-style liquid glass bar background view.
     * Multi-layer: frosted base → top specular → bottom glow → rim light
     */
    class GlassBarView extends View {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barRect = new RectF();
        private float cr; // corner radius

        public GlassBarView(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            cr = dp(28);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(1f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            barRect.set(0, 0, w, h);

            // Layer 1: Frosted base
            if (isLightTheme) {
                basePaint.setColor(0xE8FFFFFF);
            } else {
                basePaint.setColor(0xE82C2C2E);
            }
            canvas.drawRoundRect(barRect, cr, cr, basePaint);

            // Layer 2: Top specular highlight (liquid glass shine)
            float specH = h * 0.45f;
            int specTop = isLightTheme ? 0x30FFFFFF : 0x20FFFFFF;
            specPaint.setShader(new LinearGradient(
                    0, 0, 0, specH,
                    new int[]{specTop, 0x00000000},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(barRect, cr, cr, specPaint);

            // Layer 3: Bottom subtle glow
            float rimH = h * 0.3f;
            int rimBot = isLightTheme ? 0x15000000 : 0x10FFFFFF;
            rimPaint.setShader(new LinearGradient(
                    0, h - rimH, 0, h,
                    new int[]{0x00000000, rimBot},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(barRect, cr, cr, rimPaint);

            // Layer 4: Edge rim light
            edgePaint.setColor(isLightTheme ? 0x40000000 : 0x30FFFFFF);
            canvas.drawRoundRect(barRect, cr, cr, edgePaint);

            // Layer 5: Inner top glow (subtle warm light from above)
            float innerH = 2f;
            int innerC = isLightTheme ? 0x20FFFFFF : 0x15FFFFFF;
            innerGlowPaint.setShader(new LinearGradient(
                    0, 0, 0, innerH,
                    new int[]{innerC, 0x00000000},
                    null, Shader.TileMode.CLAMP));
            innerGlowPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(1, 1, w - 1, innerH), cr, cr, innerGlowPaint);
        }
    }

    private void buildBottomBar() {
        // ── Floating container with outer padding (the "float" effect) ──
        FrameLayout floatContainer = new FrameLayout(this);
        floatContainer.setPadding(dp(16), 0, dp(16), dp(16));

        // Drop shadow for glass float effect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            floatContainer.setOutlineAmbientShadowColor(0x20000000);
            floatContainer.setOutlineSpotShadowColor(0x30000000);
            floatContainer.setElevation(dp(16));
        }

        // Use a FrameLayout to layer the glass background behind the buttons
        FrameLayout barFrame = new FrameLayout(this);

        // Glass background layer
        GlassBarView glassBg = new GlassBarView(this);
        barFrame.addView(glassBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Button row on top of glass
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(dp(12), dp(10), dp(12), dp(10));

        // ── Buttons ──
        int btnSize = dp(56);

        lockBtn = new GlassButton(this);
        lockBtn.setIconType(GlassButton.ICON_LOCK);
        lockBtn.setLabel("锁定");
        lockBtn.setAccentColor(C_ACCENT);
        lockBtn.setActive(isLocked);
        lockBtn.setOnPress(() -> {
            isLocked = !isLocked;
            updateLockButton();
            vibrate(30);
        });

        pauseBtn = new GlassButton(this);
        pauseBtn.setIconType(GlassButton.ICON_PAUSE);
        pauseBtn.setLabel("暂停");
        pauseBtn.setAccentColor(C_ACCENT2);
        pauseBtn.setActive(isPaused);
        pauseBtn.setOnPress(() -> {
            isPaused = !isPaused;
            updatePauseButton();
            vibrate(30);
        });

        unitBtn = new GlassButton(this);
        unitBtn.setIconType(GlassButton.ICON_RULER);
        unitBtn.setLabel("cm");
        unitBtn.setAccentColor(C_ACCENT3);
        unitBtn.setActive(false);
        unitBtn.setOnPress(() -> {
            unitMode = (unitMode + 1) % 3;
            updateUnitDisplay();
            vibrate(30);
        });

        moreBtn = new GlassButton(this);
        moreBtn.setIconType(GlassButton.ICON_MORE);
        moreBtn.setLabel("更多");
        moreBtn.setAccentColor(C_TEXT_DIM);
        moreBtn.setActive(false);
        moreBtn.setOnPress(() -> {
            moreExpanded = !moreExpanded;
            moreBtn.setActive(moreExpanded);
            updateMorePanel();
            vibrate(30);
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSize, dp(52));
        btnLp.weight = 1;

        buttonRow.addView(lockBtn, btnLp);
        buttonRow.addView(pauseBtn, btnLp);
        buttonRow.addView(unitBtn, btnLp);
        buttonRow.addView(moreBtn, btnLp);

        barFrame.addView(buttonRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        floatContainer.addView(barFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottomBarFloat = floatContainer;
    }

    private void buildMorePanel() {
        morePanel = new LinearLayout(this);
        morePanel.setOrientation(LinearLayout.VERTICAL);

        // Apple liquid glass style for panel (white theme)
        GradientDrawable panelBg = new GradientDrawable();
        if (isLightTheme) {
            panelBg.setColor(0xEEFFFFFF);
        } else {
            panelBg.setColor(0xE62C2C2E);
        }
        panelBg.setCornerRadius(dp(20));
        morePanel.setBackground(panelBg);
        morePanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        morePanel.setVisibility(View.GONE);
        morePanel.setClipChildren(false);

        int rowHeight = dp(44);

        resetBtn = makeFlatButton("↺ 重置数据", 0xFFFF453A);
        resetBtn.setOnPress(() -> {
            resetMeasurement();
            moreExpanded = false;
            updateMorePanel();
            vibrate(50);
        });

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

        continuousBtn = makeFlatButton("连续模式", 0xFFFF375F);
        continuousBtn.setOnPress(() -> {
            continuousMode = !continuousMode;
            continuousBtn.setLabel(continuousMode ? "连续模式 ✓" : "连续模式");
            if (continuousMode) isRecording = true;
            vibrate(30);
        });

        themeBtn = makeFlatButton(isLightTheme ? "🌙 深色模式" : "☀️ 浅色模式", C_TEXT_DIM);
        themeBtn.setOnPress(() -> {
            isLightTheme = !isLightTheme;
            applyTheme(isLightTheme);
            rebuildUI();
            vibrate(30);
        });

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        rowLp.setMargins(0, dp(4), 0, dp(4));
        morePanel.addView(resetBtn, rowLp);
        morePanel.addView(debugBtn, rowLp);
        morePanel.addView(calibrateBtn, rowLp);
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

    private static final String[] UNIT_LABELS = {"cm", "mm", "in"};

    private void updateLockButton() {
        lockBtn.setActive(isLocked);
        lockBtn.setLabel("锁定");
        lockBtn.setAccentColor(isLocked ? 0xFFFF453A : C_ACCENT);
        distanceCard.setAccentTint(isLocked ? 0xFFFF453A : C_ACCENT);
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
        // Restore state display
        if (currentDistance >= 0) updateDisplay(currentDistance);
        updateLockButton();
        updatePauseButton();
        updateUnitDisplay();
    }

    private void updateDisplay(float distMm) {
        if (isLocked) return;

        // Exponential smoothing to reduce display jitter
        if (smoothDisplay < 0) {
            smoothDisplay = distMm;
        } else {
            smoothDisplay = smoothDisplay * 0.6f + distMm * 0.4f;
        }

        float src = smoothDisplay;
        float displayDist;
        String unit;

        switch (unitMode) {
            case 0: // cm
                displayDist = src / 10f;
                unit = "cm";
                break;
            case 1: // mm
                displayDist = src;
                unit = "mm";
                break;
            case 2: // inch
                displayDist = src / 25.4f;
                unit = "in";
                break;
            default:
                displayDist = src / 10f;
                unit = "cm";
        }

        // Format value
        String valueStr;
        if (displayDist < 0) {
            valueStr = "—";
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

        // Stats row — skip if hidden
        if (statMinText != null && statMinText.getParent() != null
                && ((View) statMinText.getParent()).getVisibility() == View.VISIBLE) {
            updateStatsRow();
        }

        // Status line
        String tiltInfo = tiltCompensator.getTiltQuality();
        String shakeInfo = shakeDetector.isShaking() ? " 手抖" : "";
        String lockInfo = isLocked ? " 锁定" : "";
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
        float scale;
        switch (unitMode) {
            case 0: scale = 0.1f; break;
            case 1: scale = 1f; break;
            case 2: scale = 1f / 25.4f; break;
            default: scale = 0.1f;
        }
        if (statMinText != null) statMinText.setText(String.format(Locale.US, "%s: %.1f", "min", stats.getMin() * scale));
        if (statMaxText != null) statMaxText.setText(String.format(Locale.US, "%s: %.1f", "max", stats.getMax() * scale));
        if (statAvgText != null) statAvgText.setText(String.format(Locale.US, "%s: %.1f", "avg", stats.getAvg() * scale));
        if (statStdText != null) statStdText.setText(String.format(Locale.US, "%s: %.1f", "\u03c3", stats.getStdDev() * scale));
    }

    // ─────────────────────────────────────────────
    //  Sensor Logic
    // ─────────────────────────────────────────────

    private void findTofSensor() {
        if (sensorManager == null) return;

        // 遍历所有传感器，匹配小米自定义 ToF 类型或名称
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

        // 降级到 Proximity（二值传感器，精度有限）
        if (tofSensor == null) {
            tofSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (tofSensor != null) {
                isProximityFallback = true;
            }
        }

        if (tofSensor == null) {
            statusText.setText("未找到距离传感器");
            return;
        }

        float maxRange = tofSensor.getMaximumRange();
        String label = isProximityFallback ? " (降级 Proximity)" : "";
        statusText.setText("传感器: " + tofSensor.getName() + label + " | 量程: " + maxRange);

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
        int sType = event.sensor.getType();
        if (sType == SENSOR_TYPE_MIUI_TOF || sType == Sensor.TYPE_PROXIMITY) {
            handleTofReading(event.values[0]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            boolean wasShaking = shakeDetector.isShaking();
            shakeDetector.update(event);
            tiltCompensator.updateAccelerometer(event);

            // Auto-unfreeze when shake stops
            if (wasShaking && !shakeDetector.isShaking() && isLocked) {
                isLocked = false;
                runOnUiThread(() -> updateLockButton());
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

        // Apply filter for stats/debug
        filteredDistance = filter.filter(rawMm);
        stats.add(filteredDistance >= 0 ? filteredDistance : rawMm);

        long now = System.currentTimeMillis();

        // Continuous CSV recording (background thread is fine for data)
        if (continuousMode && isRecording) {
            if (now - lastContinuousCsv >= CONTINUOUS_CSV_INTERVAL_MS) {
                if (filteredDistance >= 0) {
                    csvData.add(new float[]{filteredDistance, tiltCompensator.getPitchDegrees()});
                }
                lastContinuousCsv = now;
                // Update UI on main thread
                final int count = csvData.size();
                final long elapsed = (now - recordStartTime) / 1000;
                runOnUiThread(() -> {
                    if (recordCountText != null) recordCountText.setText(count + " 条数据");
                    if (recordTimeText != null && recordStartTime > 0) {
                        recordTimeText.setText(String.format(Locale.US, "已记录 %d:%02d", elapsed / 60, elapsed % 60));
                    }
                });
            }
        }

        // UI update on main thread
        if (now - lastUiUpdateMs >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdateMs = now;
            final float displayDist = currentDistance;
            runOnUiThread(() -> updateDisplay(displayDist));
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
        if (recordBtn != null) {
            recordBtn.setLabel("开始");
            recordBtn.setActive(false);
        }
        if (recordStatusText != null) {
            recordStatusText.setText("数据记录");
            recordStatusText.setTextColor(C_TEXT);
        }
        if (recordCountText != null) {
            recordCountText.setText("0 条数据");
        }
        if (recordTimeText != null) {
            recordTimeText.setText("0:00");
        }
        if (recordDistText != null) {
            recordDistText.setText("");
        }
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

            // Try to share via FileProvider (compatible with Android 7+)
            Uri contentUri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
