package com.example.tofranger;

import java.util.Arrays;

/**
 * Multi-stage signal processing for dToF distance data.
 *
 * Pipeline:
 *   raw → range check → spike reject → median → EMA → output
 *
 * - Range check: reject out-of-range and near-limit readings
 * - Spike rejection: throws away jumps larger than maxJump from last raw
 * - Median: standard median filter for noise rejection
 * - Single EMA: lightweight smoothing for output
 */
public class DistanceFilter {

    private final float[] medianBuffer;
    private final int windowSize;
    private int medianPos = 0;
    private boolean medianFilled = false;

    // Single EMA
    private float ema = 0;
    private boolean emaInitialized = false;
    private float alpha;

    private float lastRaw = -1;
    private float maxJumpMm;
    private float maxRangeMm;

    /**
     * @param windowSize   median window (5-9 recommended)
     * @param alpha        EMA smoothing factor (0.2-0.4 recommended)
     * @param maxJumpMm    max allowed jump per sample in mm
     * @param maxRangeMm   sensor max range, readings at/above this are invalid
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
        this(windowSize, alpha, maxJumpMm, 4000f);
    }

    /** Default: window=7, alpha=0.3, maxJump=500mm, maxRange=4000mm */
    public DistanceFilter() {
        this(7, 0.3f, 500f, 4000f);
    }

    /**
     * Feed a raw distance reading (mm). Returns filtered distance, or -1 if rejected.
     */
    public float filter(float rawMm) {
        if (rawMm < 0) return emaInitialized ? ema : -1;

        // Reject max-range readings — sensor lost signal
        if (maxRangeMm > 0 && rawMm >= maxRangeMm) {
            return emaInitialized ? ema : -1;
        }

        // Reject readings very close to max range (>95%) — likely unreliable
        if (maxRangeMm > 0 && rawMm > maxRangeMm * 0.95f) {
            return emaInitialized ? ema : -1;
        }

        // Spike rejection — compare against last RAW value, not filtered
        if (lastRaw > 0 && maxJumpMm > 0) {
            if (Math.abs(rawMm - lastRaw) > maxJumpMm) {
                return emaInitialized ? ema : lastRaw;
            }
        }
        lastRaw = rawMm;

        // Fill median buffer (float precision, no int rounding)
        medianBuffer[medianPos] = rawMm;
        medianPos = (medianPos + 1) % windowSize;
        if (medianPos == 0) medianFilled = true;

        int count = medianFilled ? windowSize : medianPos;
        if (count < 3) {
            return applyEma(rawMm);
        }

        // Standard median — simple, no trimmed mean complexity
        float[] sorted = new float[count];
        System.arraycopy(medianBuffer, 0, sorted, 0, count);
        Arrays.sort(sorted);
        float median = sorted[count / 2];

        // Single EMA
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

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0.05f, Math.min(0.95f, alpha));
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
        return medianFilled || medianPos >= 3;
    }
}
