package com.example.tofranger.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Apple-style liquid glass bar background view.
 * Multi-layer: frosted base → top specular → bottom glow → rim light.
 * Gradients are cached in onSizeChanged to avoid per-frame allocation.
 */
public class GlassBarView extends View {

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF innerGlowRect = new RectF();
    private float cr;
    private int cachedW = -1, cachedH = -1;

    public GlassBarView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        cr = dp(ctx, 28);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        rebuildShaders(w, h);
    }

    private void rebuildShaders(int w, int h) {
        boolean light = ThemeColors.isLight;
        float specH = h * 0.45f;
        int specTop = light ? 0x50FFFFFF : 0x20FFFFFF;
        specPaint.setShader(new LinearGradient(
                0, 0, 0, specH,
                new int[]{specTop, 0x00000000},
                null, Shader.TileMode.CLAMP));

        float rimH = h * 0.3f;
        int rimBot = light ? 0x15000000 : 0x10FFFFFF;
        rimPaint.setShader(new LinearGradient(
                0, h - rimH, 0, h,
                new int[]{0x00000000, rimBot},
                null, Shader.TileMode.CLAMP));

        float innerH = 2f;
        int innerC = light ? 0x30FFFFFF : 0x15FFFFFF;
        innerGlowPaint.setShader(new LinearGradient(
                0, 0, 0, innerH,
                new int[]{innerC, 0x00000000},
                null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean light = ThemeColors.isLight;
        float w = getWidth();
        float h = getHeight();
        barRect.set(0, 0, w, h);

        // Layer 1: Frosted base
        basePaint.setColor(light ? 0xF5FFFFFF : 0xE82C2C2E);
        canvas.drawRoundRect(barRect, cr, cr, basePaint);

        // Layer 2: Top specular
        canvas.drawRoundRect(barRect, cr, cr, specPaint);

        // Layer 3: Bottom glow
        canvas.drawRoundRect(barRect, cr, cr, rimPaint);

        // Layer 4: Edge rim light
        edgePaint.setColor(light ? 0x60000000 : 0x30FFFFFF);
        canvas.drawRoundRect(barRect, cr, cr, edgePaint);

        // Layer 5: Inner top glow
        innerGlowPaint.setStyle(Paint.Style.FILL);
        innerGlowRect.set(1, 1, w - 1, 2f);
        canvas.drawRoundRect(innerGlowRect, cr, cr, innerGlowPaint);
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
