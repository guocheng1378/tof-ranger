package com.example.tofranger.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.widget.FrameLayout;

/**
 * Apple Liquid Glass card — multi-layer frosted glass with
 * specular highlight, rim light, inner shadow, and accent tint.
 * Gradients are cached and only rebuilt on size/theme change.
 */
public class GlassCardView extends FrameLayout {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF bottomEdge = new RectF();
    private float cornerRadius;
    private int accentTint = 0;
    private float lastW = -1, lastH = -1;
    private boolean dirty = true;

    public GlassCardView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        setClipChildren(false);
        cornerRadius = 28f;
        bgPaint.setStyle(Paint.Style.FILL);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.2f);
        tintPaint.setStyle(Paint.Style.FILL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setElevation(dp(6));
        }
        refreshThemeColors();
    }

    public void setAccentTint(int color) {
        this.accentTint = color;
        invalidate();
    }

    public void onThemeChanged() {
        dirty = true;
        refreshThemeColors();
        invalidate();
    }

    private void refreshThemeColors() {
        bgPaint.setColor(ThemeColors.GLASS_BG);
        edgePaint.setColor(ThemeColors.GLASS_EDGE);
    }

    private void rebuildGradientsIfNeeded() {
        float w = getWidth();
        float h = getHeight();
        if (!dirty && w == lastW && h == lastH) return;
        lastW = w; lastH = h; dirty = false;
        float specH = h * 0.35f;
        float rimH = h * 0.25f;
        float inset = 5f;
        boolean light = ThemeColors.isLight;
        int specTopC = light ? 0x1A000000 : 0x22FFFFFF;
        shinePaint.setShader(new LinearGradient(
                inset, inset, inset, inset + specH,
                new int[]{specTopC, 0x00000000}, null, Shader.TileMode.CLAMP));
        int rimBotC = light ? 0x0A000000 : 0x0DFFFFFF;
        rimPaint.setShader(new LinearGradient(
                inset, h - inset - rimH, inset, h - inset,
                new int[]{0x00000000, rimBotC}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        rebuildGradientsIfNeeded();
        float r = cornerRadius;
        float inset = 5f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        canvas.drawRoundRect(rect, r, r, bgPaint);
        canvas.drawRoundRect(rect, r, r, shinePaint);
        canvas.drawRoundRect(rect, r, r, rimPaint);
        canvas.drawRoundRect(rect, r, r, edgePaint);

        float innerShadowH = 6f;
        boolean light = ThemeColors.isLight;
        int innerShadowC = light ? 0x0D000000 : 0x15000000;
        innerShadowPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.top + innerShadowH,
                new int[]{innerShadowC, 0x00000000}, null, Shader.TileMode.CLAMP));
        innerShadowPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(
                new RectF(rect.left, rect.top, rect.right, rect.top + innerShadowH),
                r, r, innerShadowPaint);

        if (accentTint != 0) {
            float edgeH = 3.5f;
            bottomEdge.set(rect.left, rect.bottom - edgeH, rect.right, rect.bottom);
            tintPaint.setColor(accentTint & 0x80FFFFFF);
            canvas.drawRoundRect(bottomEdge, r, r, tintPaint);
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
