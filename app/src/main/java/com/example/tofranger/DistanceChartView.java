package com.example.tofranger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;

/**
 * Custom View that draws a real-time distance history line chart.
 */
public class DistanceChartView extends View {

    private final ArrayList<Float> data = new ArrayList<>();
    private static final int MAX_POINTS = 200;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int lineColor = 0xFF00E5A0;
    private int fillColor = 0x1500E5A0;
    private int gridColor = 0xFF1E2536;
    private int labelColor = 0xFF4A5568;

    public DistanceChartView(Context context) {
        super(context);

        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setColor(fillColor);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(24f);

        dotPaint.setColor(lineColor);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void addPoint(float value) {
        data.add(value);
        if (data.size() > MAX_POINTS) data.remove(0);
        postInvalidate();
    }

    public void clear() {
        data.clear();
        postInvalidate();
    }

    public int getPointCount() {
        return data.size();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        int padL = (int) (labelPaint.getTextSize() * 2.5f);
        int padR = dp(8);
        int padT = dp(8);
        int padB = dp(4);

        int chartW = w - padL - padR;
        int chartH = h - padT - padB;

        // Background
        canvas.drawColor(Color.TRANSPARENT);

        if (data.size() < 2) {
            String msg = "等待数据...";
            float tw = labelPaint.measureText(msg);
            canvas.drawText(msg, (w - tw) / 2f, h / 2f, labelPaint);
            return;
        }

        // Find min/max
        float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
        for (float v : data) {
            if (v < min) min = v;
            if (v > max) max = v;
        }

        // Add padding to range
        float range = max - min;
        if (range < 10) range = 10;
        float rangePad = range * 0.1f;
        min -= rangePad;
        max += rangePad;
        range = max - min;

        // Draw horizontal grid lines (3 lines)
        float textSize = labelPaint.getTextSize();
        for (int i = 0; i <= 3; i++) {
            float y = padT + (chartH * i / 3f);
            canvas.drawLine(padL, y, w - padR, y, gridPaint);

            float val = max - (range * i / 3f);
            String label = String.format("%.0f", val);
            float tw = labelPaint.measureText(label);
            canvas.drawText(label, padL - tw - dp(4), y + textSize / 3, labelPaint);
        }

        // Map data to screen coordinates
        float xStep = chartW / (float) (data.size() - 1);

        // Draw filled area
        Path fillPath = new Path();
        fillPath.moveTo(padL, padT + chartH); // bottom-left

        float lastX = 0, lastY = 0;
        for (int i = 0; i < data.size(); i++) {
            float x = padL + i * xStep;
            float normalized = (data.get(i) - min) / range;
            float y = padT + chartH * (1 - normalized);

            if (i == 0) fillPath.moveTo(x, y);
            else fillPath.lineTo(x, y);

            lastX = x;
            lastY = y;
        }

        // Close fill path
        fillPath.lineTo(lastX, padT + chartH);
        fillPath.lineTo(padL, padT + chartH);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // Draw line
        Path linePath = new Path();
        for (int i = 0; i < data.size(); i++) {
            float x = padL + i * xStep;
            float normalized = (data.get(i) - min) / range;
            float y = padT + chartH * (1 - normalized);

            if (i == 0) linePath.moveTo(x, y);
            else linePath.lineTo(x, y);
        }
        canvas.drawPath(linePath, linePaint);

        // Draw last point dot
        if (data.size() > 0) {
            float lastVal = data.get(data.size() - 1);
            float x = padL + (data.size() - 1) * xStep;
            float normalized = (lastVal - min) / range;
            float y = padT + chartH * (1 - normalized);
            canvas.drawCircle(x, y, dp(4), dotPaint);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
