package com.example.tofranger;

import java.util.Arrays;

/**
 * Multi-stage signal processing for dToF distance data.
 * Pipeline: raw → spike reject → median → EMA → output
 */
public class DistanceFilter {

    private final float[] medianBuffer;
    private final int windowSize;
    private int medianPos = 0;
    private boolean medianFilled = false;

    private float ema = 0;
    private boolean emaInitialized = false;
    private float alpha;

    private float lastRaw = -1;
    private float maxJumpMm;
    private float maxRangeMm;

    /**
     * @param windowSize   median window
     * @param alpha        EMA smoothing factor (higher=more responsive)
     * @param maxJumpMm    max allowed jump per sample in mm (0=disabled)
     * @param maxRangeMm   sensor max range (0=disable range checks)
     */
    public DistanceFilter(int windowSize, float alpha, float maxJumpMm, float maxRangeMm) {
        this.windowSize = windowSize;
        this.medianBuffer = new float[windowSize];
        this.alpha = alpha;
        this.maxJumpMm = maxJumpMm;
        this.maxRangeMm = maxRangeMm;
        Arrays.fill(medianBuffer, -1);
    }

    public DistanceFilter(int windowSize, float alpha, float maxJumpMm) {
        this(windowSize, alpha, maxJumpMm, 0);
    }

    public DistanceFilter() {
        this(5, 0.4f, 0, 0);
    }

    /**
     * Feed a raw distance reading (mm). Returns filtered distance, or -1 if rejected.
     */
    public float filter(float rawMm) {
        if (rawMm < 0) return emaInitialized ? ema : -1;

        // Range checks — only if maxRange is valid (>0)
        if (maxRangeMm > 0) {
            if (rawMm >= maxRangeMm) return emaInitialized ? ema : -1;
            // 不再做95%截断，太激进了
        }

        // Spike rejection — only if enabled (>0)
        if (lastRaw > 0 && maxJumpMm > 0) {
            if (Math.abs(rawMm - lastRaw) > maxJumpMm) {
                return emaInitialized ? ema : lastRaw;
            }
        }
        lastRaw = rawMm;

        // Fill median buffer
        medianBuffer[medianPos] = rawMm;
        medianPos = (medianPos + 1) % windowSize;
        if (medianPos == 0) medianFilled = true;

        int count = medianFilled ? windowSize : medianPos;
        if (count < 2) {
            return applyEma(rawMm);
        }

        // Median
        float[] sorted = new float[count];
        System.arraycopy(medianBuffer, 0, sorted, 0, count);
        Arrays.sort(sorted);
        float median = sorted[count / 2];

        return applyEma(median);
    }

    private float applyEma(float value) {
        if (!emaInitialized) {
            ema = value;
            emaInitialized = true;
        } else {
            ema = alpha * value + (1 - alpha) * ema;
        }
        return ema;
    }

    public void reset() {
        medianPos = 0;
        medianFilled = false;
        emaInitialized = false;
        lastRaw = -1;
        ema = 0;
        Arrays.fill(medianBuffer, -1);
    }

    public float getCurrentValue() {
        return ema;
    }

    public boolean isWarmedUp() {
        return medianFilled || medianPos >= 2;
    }
}
