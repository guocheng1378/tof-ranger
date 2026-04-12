package com.example.tofranger;

import java.util.Arrays;

/**
 * Multi-stage signal processing for dToF distance data.
 *
 * Pipeline:
 *   raw → spike reject → trimmed median → dead zone → EMA(x2) → output
 *
 * - Spike rejection: throws away jumps larger than maxJump
 * - Trimmed median: sorts window, averages middle 50% (more stable than plain median)
 * - Dead zone: ignores changes smaller than deadZoneMm (reduces micro-jitter)
 * - Double EMA: two passes of exponential smoothing for extra smoothness
 */
public class DistanceFilter {

    private final int[] medianBuffer;
    private final int windowSize;
    private int medianPos = 0;
    private boolean medianFilled = false;

    // Double EMA
    private float ema1 = 0, ema2 = 0;
    private boolean emaInitialized = false;
    private float alpha;

    private float lastFiltered = -1;
    private float maxJumpMm;
    private float deadZoneMm;
    private float maxRangeMm; // sensor max range, readings at this value are invalid

    /**
     * @param windowSize   median window (9-15 recommended)
     * @param alpha        EMA smoothing factor (0.05-0.12 recommended, lower=smoother)
     * @param maxJumpMm    max allowed jump per sample in mm
     * @param deadZoneMm   ignore changes smaller than this (mm), 0 = disabled
     * @param maxRangeMm   sensor max range, readings at/above this are invalid
     */
    public DistanceFilter(int windowSize, float alpha, float maxJumpMm, float deadZoneMm, float maxRangeMm) {
        this.windowSize = windowSize;
        this.medianBuffer = new int[windowSize];
        this.alpha = alpha;
        this.maxJumpMm = maxJumpMm;
        this.deadZoneMm = deadZoneMm;
        this.maxRangeMm = maxRangeMm;
        Arrays.fill(medianBuffer, -1);
    }

    /** Convenience: default maxRange=4000mm */
    public DistanceFilter(int windowSize, float alpha, float maxJumpMm, float deadZoneMm) {
        this(windowSize, alpha, maxJumpMm, deadZoneMm, 4000f);
    }

    /** Convenience: no dead zone */
    public DistanceFilter(int windowSize, float alpha, float maxJumpMm) {
        this(windowSize, alpha, maxJumpMm, 2f, 4000f);
    }

    /** Default: window=11, alpha=0.08, maxJump=150mm, deadZone=2mm, maxRange=4000mm */
    public DistanceFilter() {
        this(11, 0.08f, 150f, 2f, 4000f);
    }

    /**
     * Feed a raw distance reading (mm). Returns filtered distance, or -1 if rejected.
     */
    public float filter(float rawMm) {
        if (rawMm < 0) return -1;

        // Reject max-range readings — sensor lost signal, returned boundary value
        if (maxRangeMm > 0 && rawMm >= maxRangeMm) {
            return emaInitialized ? ema2 : -1;
        }

        // Reject readings very close to max range (>95%) — likely unreliable
        if (maxRangeMm > 0 && rawMm > maxRangeMm * 0.95f) {
            return emaInitialized ? ema2 : -1;
        }

        int rawInt = Math.round(rawMm);

        // Stage 1: Spike rejection — if raw jumps too much, reject it
        if (lastFiltered > 0 && maxJumpMm > 0) {
            if (Math.abs(rawMm - lastFiltered) > maxJumpMm) {
                return emaInitialized ? ema2 : lastFiltered;
            }
        }

        // Stage 2: Fill median buffer (only with non-spike values)
        medianBuffer[medianPos] = rawInt;
        medianPos = (medianPos + 1) % windowSize;
        if (medianPos == 0) medianFilled = true;

        int count = medianFilled ? windowSize : medianPos;
        if (count < 3) {
            lastFiltered = rawMm;
            return initEma(rawMm);
        }

        // Stage 3: Trimmed median — sort, take middle 50%, average them
        int[] sorted = new int[count];
        System.arraycopy(medianBuffer, 0, sorted, 0, count);
        Arrays.sort(sorted);

        int q1 = count / 4;
        int q3 = (count * 3) / 4;
        if (q3 <= q1) q3 = q1 + 1;

        float sum = 0;
        int trimCount = 0;
        for (int i = q1; i < q3; i++) {
            sum += sorted[i];
            trimCount++;
        }
        float trimmed = sum / trimCount;

        // Stage 4: Dead zone — ignore tiny changes from current output
        if (deadZoneMm > 0 && emaInitialized) {
            if (Math.abs(trimmed - ema2) < deadZoneMm) {
                return ema2; // within dead zone, don't update
            }
        }

        lastFiltered = trimmed;

        // Stage 5: Double EMA
        return applyDoubleEma(trimmed);
    }

    private float initEma(float value) {
        if (!emaInitialized) {
            ema1 = value;
            ema2 = value;
            emaInitialized = true;
        }
        return ema2;
    }

    private float applyDoubleEma(float value) {
        if (!emaInitialized) {
            ema1 = value;
            ema2 = value;
            emaInitialized = true;
        } else {
            ema1 = alpha * value + (1 - alpha) * ema1;
            ema2 = alpha * ema1 + (1 - alpha) * ema2;
        }
        return ema2;
    }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0.02f, Math.min(0.95f, alpha));
    }

    public void setDeadZone(float deadZoneMm) {
        this.deadZoneMm = deadZoneMm;
    }

    public void reset() {
        medianPos = 0;
        medianFilled = false;
        emaInitialized = false;
        lastFiltered = -1;
        ema1 = 0;
        ema2 = 0;
        Arrays.fill(medianBuffer, -1);
    }

    public float getCurrentValue() {
        return ema2;
    }

    public boolean isWarmedUp() {
        return medianFilled || medianPos >= 3;
    }
}
