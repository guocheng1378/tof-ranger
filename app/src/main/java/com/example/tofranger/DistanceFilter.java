package com.example.tofranger;

import java.util.Arrays;

/**
 * Multi-stage signal processing for dToF distance data.
 * Pipeline: raw → spike reject → median → adaptive EMA → output
 *
 * Improvements over v1:
 *  - Spike rejection uses reject-then-recover instead of hard clamping
 *  - EMA alpha adapts to local variance (static → smooth, dynamic → responsive)
 *  - Median sort uses insertion sort for small windows (O(n) vs O(n log n))
 */
public class DistanceFilter {

    private final float[] medianBuffer;
    private final float[] sortBuffer; // reusable sort buffer to avoid allocation per call
    private final int windowSize;
    private int medianPos = 0;
    private boolean medianFilled = false;

    private float ema = 0;
    private boolean emaInitialized = false;
    private final float baseAlpha;   // minimum alpha (static scene)
    private final float maxAlpha;    // maximum alpha (dynamic scene)

    private float lastValid = -1;    // last accepted value (for spike recovery)
    private float lastRaw = -1;      // last raw value (for spike detection)
    private int rejectedCount = 0;   // consecutive rejected samples
    private float maxJumpMm;
    private float maxRangeMm;

    // Welford stats for adaptive alpha (rolling over recent accepted values)
    private float welfordMean = 0;
    private float welfordM2 = 0;
    private int welfordCount = 0;
    private static final int WELFORD_WINDOW = 20;

    /**
     * @param windowSize   median window
     * @param baseAlpha    EMA smoothing factor for static scenes (lower=smoother)
     * @param maxAlpha     EMA smoothing factor for dynamic scenes (higher=responsive)
     * @param maxJumpMm    max allowed jump per sample in mm (0=disabled)
     * @param maxRangeMm   sensor max range (0=disable range checks)
     */
    public DistanceFilter(int windowSize, float baseAlpha, float maxAlpha, float maxJumpMm, float maxRangeMm) {
        this.windowSize = windowSize;
        this.medianBuffer = new float[windowSize];
        this.sortBuffer = new float[windowSize];
        this.baseAlpha = baseAlpha;
        this.maxAlpha = maxAlpha;
        this.maxJumpMm = maxJumpMm;
        this.maxRangeMm = maxRangeMm;
        Arrays.fill(medianBuffer, -1);
    }

    public DistanceFilter(int windowSize, float alpha, float maxJumpMm, float maxRangeMm) {
        this(windowSize, 0.15f, 0.40f, maxJumpMm, maxRangeMm);
    }

    public DistanceFilter(int windowSize, float alpha, float maxJumpMm) {
        this(windowSize, 0.15f, 0.40f, maxJumpMm, 0);
    }

    public DistanceFilter() {
        this(5, 0.15f, 0.40f, 0, 0);
    }

    /**
     * Feed a raw distance reading (mm). Returns filtered distance, or -1 if rejected.
     */
    public float filter(float rawMm) {
        if (rawMm < 0) {
            rejectedCount++;
            if (rejectedCount >= 8) {
                // Too long without valid data — reset EMA so next valid value initializes fresh
                emaInitialized = false;
            }
            return emaInitialized ? ema : -1;
        }
        rejectedCount = 0;

        // Range checks — only if maxRange is valid (>0)
        if (maxRangeMm > 0) {
            if (rawMm >= maxRangeMm) return emaInitialized ? ema : -1;
        }

        // Spike rejection — reject-then-recover strategy
        if (lastValid > 0 && lastRaw > 0 && lastRaw < 8190f && maxJumpMm > 0) {
            float jumpFromValid = Math.abs(rawMm - lastValid);
            float jumpFromLast = Math.abs(rawMm - lastRaw);
            // Reject if jump from last valid is too large AND this isn't a recovery from a previous spike
            if (jumpFromValid > maxJumpMm && jumpFromLast > maxJumpMm) {
                // Reject this sample, reuse last valid
                rawMm = lastValid;
            }
            // If jump from raw is large but from valid is small → accept (recovered from spike)
        }
        lastRaw = rawMm;
        lastValid = rawMm;

        // Fill median buffer
        medianBuffer[medianPos] = rawMm;
        medianPos = (medianPos + 1) % windowSize;
        if (medianPos == 0) medianFilled = true;

        int count = medianFilled ? windowSize : medianPos;
        if (count < 2) {
            return applyAdaptiveEma(rawMm);
        }

        // Median — insertion sort for small windows (faster than Arrays.sort for n<=7)
        float median;
        if (count <= 7) {
            median = insertionSortMedian(medianBuffer, count);
        } else {
            System.arraycopy(medianBuffer, 0, sortBuffer, 0, count);
            Arrays.sort(sortBuffer, 0, count);
            median = sortBuffer[count / 2];
        }

        return applyAdaptiveEma(median);
    }

    /**
     * Insertion sort then pick median — O(n) for small arrays, avoids Arrays.sort overhead.
     */
    private float insertionSortMedian(float[] buf, int count) {
        // Copy to sortBuffer and insertion-sort
        for (int i = 0; i < count; i++) sortBuffer[i] = buf[i];
        for (int i = 1; i < count; i++) {
            float key = sortBuffer[i];
            int j = i - 1;
            while (j >= 0 && sortBuffer[j] > key) {
                sortBuffer[j + 1] = sortBuffer[j];
                j--;
            }
            sortBuffer[j + 1] = key;
        }
        return sortBuffer[count / 2];
    }

    private float applyAdaptiveEma(float value) {
        if (!emaInitialized) {
            ema = value;
            emaInitialized = true;
            resetWelford();
            return ema;
        }

        // Update Welford stats
        welfordCount++;
        if (welfordCount > WELFORD_WINDOW) {
            // Reset periodically to track recent variance
            resetWelford();
            welfordCount = 1;
        }
        float delta = value - welfordMean;
        welfordMean += delta / welfordCount;
        float delta2 = value - welfordMean;
        welfordM2 += delta * delta2;

        // Adaptive alpha: high stddev → more responsive, low stddev → more smooth
        float stddev = welfordCount > 1 ? (float) Math.sqrt(welfordM2 / welfordCount) : 0;
        float alpha = baseAlpha + (maxAlpha - baseAlpha) * Math.min(1f, stddev / 15f);

        ema = alpha * value + (1 - alpha) * ema;
        return ema;
    }

    private void resetWelford() {
        welfordMean = 0;
        welfordM2 = 0;
        welfordCount = 0;
    }

    public void reset() {
        medianPos = 0;
        medianFilled = false;
        emaInitialized = false;
        lastValid = -1;
        lastRaw = -1;
        rejectedCount = 0;
        ema = 0;
        resetWelford();
        Arrays.fill(medianBuffer, -1);
    }

    public float getCurrentValue() {
        return ema;
    }

    public boolean isWarmedUp() {
        return medianFilled || medianPos >= 2;
    }

    /** Expose current adaptive alpha for debug display */
    public float getCurrentAlpha() {
        float stddev = welfordCount > 1 ? (float) Math.sqrt(welfordM2 / welfordCount) : 0;
        return baseAlpha + (maxAlpha - baseAlpha) * Math.min(1f, stddev / 15f);
    }
}
