package com.example.tofranger;

import java.util.Arrays;

/**
 * Two-stage signal processing for dToF distance data.
 *   raw → MovingMedian (outlier rejection) → EMA (smoothing) → output
 */
public class DistanceFilter {

    private final int[] medianBuffer;
    private int medianPos = 0;
    private boolean medianFilled = false;

    private float emaValue = 0;
    private boolean emaInitialized = false;
    private float alpha;

    private float lastValid = -1;
    private float maxJumpMm;

    /**
     * @param windowSize  median window (5-9 recommended, must be odd)
     * @param alpha       EMA smoothing factor (0.15-0.35 recommended)
     * @param maxJumpMm   max allowed single-sample jump in mm
     */
    public DistanceFilter(int windowSize, float alpha, float maxJumpMm) {
        this.medianBuffer = new int[windowSize];
        this.alpha = alpha;
        this.maxJumpMm = maxJumpMm;
        Arrays.fill(medianBuffer, -1);
    }

    /** Default: window=5, alpha=0.25, maxJump=800mm */
    public DistanceFilter() {
        this(5, 0.25f, 800f);
    }

    /**
     * Feed a raw distance reading (mm). Returns filtered distance, or -1 if rejected.
     */
    public float filter(float rawMm) {
        if (rawMm < 0) return -1;

        int rawInt = Math.round(rawMm);

        // Stage 1: Moving Median
        medianBuffer[medianPos] = rawInt;
        medianPos = (medianPos + 1) % medianBuffer.length;
        if (medianPos == 0) medianFilled = true;

        int count = medianFilled ? medianBuffer.length : medianPos;
        if (count < 3) {
            lastValid = rawMm;
            return initEma(rawMm);
        }

        int[] sorted = new int[count];
        System.arraycopy(medianBuffer, 0, sorted, 0, count);
        Arrays.sort(sorted);
        float median = sorted[count / 2];

        // Stage 2: Spike detection
        if (lastValid > 0 && maxJumpMm > 0) {
            if (Math.abs(median - lastValid) > maxJumpMm) {
                return emaInitialized ? emaValue : lastValid;
            }
        }
        lastValid = median;

        return applyEma(median);
    }

    private float initEma(float value) {
        if (!emaInitialized) {
            emaValue = value;
            emaInitialized = true;
        }
        return emaValue;
    }

    private float applyEma(float value) {
        if (!emaInitialized) {
            emaValue = value;
            emaInitialized = true;
        } else {
            emaValue = alpha * value + (1 - alpha) * emaValue;
        }
        return emaValue;
    }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0.05f, Math.min(0.95f, alpha));
    }

    public void reset() {
        medianPos = 0;
        medianFilled = false;
        emaInitialized = false;
        lastValid = -1;
        Arrays.fill(medianBuffer, -1);
    }

    public float getCurrentValue() {
        return emaValue;
    }

    public boolean isWarmedUp() {
        return medianFilled || medianPos >= 3;
    }
}
