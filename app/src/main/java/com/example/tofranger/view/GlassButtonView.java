package com.example.tofranger.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Liquid glass floating button with accent tint, glass reflection, glow edge, press animation.
 */
public class GlassButtonView extends View {

    public static final int ICON_LOCK = 0;
    public static final int ICON_PAUSE = 1;
    public static final int ICON_RULER = 2;
    public static final int ICON_THEME = 3;
    public static final int ICON_RECORD = 4;
    public static final int ICON_MORE = 5;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int iconType = 0;
    private String label = "";
    private int accentColor = ThemeColors.ACCENT;
    private boolean active = false;
    private boolean pressed = false;
    private Runnable onPress;

    public GlassButtonView(Context ctx) {
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

    public void setAccentColor(int color) { this.accentColor = color; invalidate(); }
    public void setLabel(String text) { this.label = text; invalidate(); }
    public void setIconType(int type) { this.iconType = type; invalidate(); }
    public void setActive(boolean a) { this.active = a; invalidate(); }
    public void setRound(boolean r) { invalidate(); }
    public void setOnPress(Runnable r) { this.onPress = r; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        canvas.save();
        canvas.scale(getScaleX(), getScaleY(), w / 2f, h / 2f);

        float cx = w / 2f;
        float iconSize = Math.min(w, h) * 0.38f;
        float strokeW = dp(1.8f);
        iconPaint.setStrokeWidth(strokeW);

        boolean light = ThemeColors.isLight;
        int inactiveColor = light ? 0xFF636366 : 0xFF8E8E93;
        int iconColor = active ? accentColor : inactiveColor;
        iconPaint.setColor(iconColor);
        float iconCy = h * 0.38f;

        switch (iconType) {
            case ICON_LOCK: drawLockIcon(canvas, cx, iconCy, iconSize); break;
            case ICON_PAUSE: drawPauseIcon(canvas, cx, iconCy, iconSize); break;
            case ICON_RULER: drawRulerIcon(canvas, cx, iconCy, iconSize); break;
            case ICON_THEME: drawThemeIcon(canvas, cx, iconCy, iconSize); break;
            case ICON_RECORD: drawRecordIcon(canvas, cx, iconCy, iconSize); break;
            case ICON_MORE: drawMoreIcon(canvas, cx, iconCy, iconSize); break;
            default:
                if (!label.isEmpty()) {
                    iconPaint.setStyle(Paint.Style.STROKE);
                    RectF bgR = new RectF(4, 4, w - 4, h - 4);
                    int bgColor = Color.argb(0x15, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
                    iconPaint.setColor(bgColor);
                    iconPaint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(bgR, dp(12), dp(12), iconPaint);
                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setColor(Color.argb(0x30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
                    RectF edge = new RectF(bgR.left, bgR.bottom - dp(2), bgR.right, bgR.bottom);
                    canvas.drawRoundRect(edge, dp(12), dp(12), iconPaint);
                    labelPaint.setColor(light ? ThemeColors.TEXT : ThemeColors.TEXT);
                    labelPaint.setTextSize(dp(13));
                    Paint.FontMetrics fm = labelPaint.getFontMetrics();
                    float ty = h / 2f - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(label, cx, ty, labelPaint);
                }
                break;
        }

        if (!label.isEmpty()) {
            labelPaint.setColor(iconColor);
            labelPaint.setTextSize(dp(9));
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            float labelY = h * 0.82f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, cx, labelY, labelPaint);
        }
        canvas.restore();
    }

    private void drawLockIcon(Canvas c, float cx, float cy, float s) {
        float hw = s * 0.35f, bh = s * 0.3f;
        float bodyTop = cy - bh * 0.1f, bodyBot = bodyTop + bh;
        float shackW = hw * 0.65f, shackH = s * 0.25f;
        float shackTop = bodyTop - shackH;
        if (active) c.drawArc(cx - shackW, shackTop, cx + shackW, bodyTop, 180, 180, false, iconPaint);
        else c.drawArc(cx - shackW, shackTop - s * 0.05f, cx + shackW + s * 0.08f, bodyTop, 200, 160, false, iconPaint);
        RectF body = new RectF(cx - hw, bodyTop, cx + hw, bodyBot);
        c.drawRoundRect(body, dp(3), dp(3), iconPaint);
        float kcx = cx, kcy = bodyTop + bh * 0.5f;
        c.drawCircle(kcx, kcy, s * 0.06f, iconPaint);
        c.drawLine(kcx, kcy + s * 0.06f, kcx, kcy + s * 0.15f, iconPaint);
    }

    private void drawPauseIcon(Canvas c, float cx, float cy, float s) {
        if (active) {
            float sz = s * 0.35f;
            Path tri = new Path();
            tri.moveTo(cx - sz * 0.4f, cy - sz); tri.lineTo(cx - sz * 0.4f, cy + sz);
            tri.lineTo(cx + sz * 0.7f, cy); tri.close();
            iconPaint.setStyle(Paint.Style.FILL); c.drawPath(tri, iconPaint); iconPaint.setStyle(Paint.Style.STROKE);
        } else {
            float bw = s * 0.12f, bh = s * 0.6f, gap = s * 0.18f;
            RectF b1 = new RectF(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f);
            RectF b2 = new RectF(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f);
            iconPaint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(b1, dp(2), dp(2), iconPaint);
            c.drawRoundRect(b2, dp(2), dp(2), iconPaint);
            iconPaint.setStyle(Paint.Style.STROKE);
        }
    }

    private void drawRulerIcon(Canvas c, float cx, float cy, float s) {
        float hw = s * 0.45f, hh = s * 0.22f;
        RectF body = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
        c.drawRoundRect(body, dp(2), dp(2), iconPaint);
        float tickH = hh * 0.6f;
        for (int i = -2; i <= 2; i++) {
            float x = cx + i * (hw * 0.4f);
            float tickLen = (i == 0) ? hh : tickH * 0.6f;
            c.drawLine(x, cy - tickLen, x, cy + tickLen, iconPaint);
        }
    }

    private void drawThemeIcon(Canvas c, float cx, float cy, float s) {
        float r = s * 0.22f;
        if (active) {
            Path moon = new Path(); float mr = r * 1.1f;
            moon.addCircle(cx - mr * 0.3f, cy, mr, Path.Direction.CW);
            Path cutout = new Path();
            cutout.addCircle(cx + mr * 0.4f, cy - mr * 0.15f, mr * 0.75f, Path.Direction.CW);
            moon.op(cutout, Path.Op.DIFFERENCE);
            iconPaint.setStyle(Paint.Style.FILL); c.drawPath(moon, iconPaint); iconPaint.setStyle(Paint.Style.STROKE);
        } else {
            c.drawCircle(cx, cy, r, iconPaint);
            float rayLen = r * 0.5f, rayStart = r * 1.25f;
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2 * i / 8;
                c.drawLine(cx + (float) Math.cos(angle) * rayStart, cy + (float) Math.sin(angle) * rayStart,
                        cx + (float) Math.cos(angle) * (rayStart + rayLen), cy + (float) Math.sin(angle) * (rayStart + rayLen), iconPaint);
            }
        }
    }

    private void drawRecordIcon(Canvas c, float cx, float cy, float s) {
        float r = s * 0.3f;
        if (active) { iconPaint.setStyle(Paint.Style.FILL); iconPaint.setColor(0xFFFF3B30); c.drawCircle(cx, cy, r, iconPaint); iconPaint.setStyle(Paint.Style.STROKE); }
        else { iconPaint.setStyle(Paint.Style.STROKE); c.drawCircle(cx, cy, r, iconPaint); }
    }

    private void drawMoreIcon(Canvas c, float cx, float cy, float s) {
        float dotR = s * 0.07f, spacing = s * 0.22f;
        iconPaint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - spacing, cy, dotR, iconPaint);
        c.drawCircle(cx, cy, dotR, iconPaint);
        c.drawCircle(cx + spacing, cy, dotR, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: pressed = true; animatePress(true); return true;
            case MotionEvent.ACTION_UP:
                if (pressed) { animatePress(false); if (onPress != null) onPress.run(); performClick(); }
                pressed = false; return true;
            case MotionEvent.ACTION_CANCEL: pressed = false; animatePress(false); return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void animatePress(boolean down) {
        animate().scaleX(down ? 0.88f : 1f).scaleY(down ? 0.88f : 1f)
                .setDuration(down ? 80 : 300)
                .setInterpolator(down ? new AccelerateDecelerateInterpolator() : new OvershootInterpolator(2.5f))
                .start();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
