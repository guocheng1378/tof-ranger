package com.example.tofranger.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Simple rounded progress bar showing measurement quality (0-100%).
 */
public class QualityBarView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();
    private final RectF fillRect = new RectF();
    private float progress = 0;
    private int fillColor = ThemeColors.ACCENT2;

    public QualityBarView(Context ctx) {
        super(ctx);
        bgPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);
        refreshTheme();
    }

    public void setProgress(float p) {
        this.progress = Math.max(0, Math.min(1, p));
        invalidate();
    }

    public void setFillColor(int color) {
        this.fillColor = color;
        invalidate();
    }

    public void onThemeChanged() {
        refreshTheme();
        invalidate();
    }

    private void refreshTheme() {
        bgPaint.setColor(ThemeColors.isLight ? 0x33000000 : 0x1AFFFFFF);
        fillColor = ThemeColors.ACCENT2;
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
