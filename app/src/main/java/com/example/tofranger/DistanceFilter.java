package com.example.tofranger;

import java.util.Arrays;

/**
 * Multi-stage signal processing for dToF distance data.
 * Pipeline: raw → spike reject → median → adaptive EMA → output
 *
 * Improvements over v1:
 * - Adaptive EMA: alpha increases when target is moving fast, decreases when stable
 * - Spike handling: soft reject (blend toward boundary) instead of hard cap
 * - Movement detection: tracks recent variance to adjust filter aggressiveness
 */
public class DistanceFilter {

    private final float[] medianBuffer;
    private final float[] sortBuffer;
    private final int windowSize;
    private int medianPos = 0;
    private boolean medianFilled = false;

    // EMA state
    private float ema = 0;
    private boolean emaInitialized = false;

    // Adaptive parameters
    private final float baseAlpha;    // base EMA factor (stable state)
    private final float fastAlpha;    // EMA factor when moving fast
    private float currentAlpha;

    private float lastOutput = -1;
    private float lastRaw = -1;
    private final float maxJumpMm;
    private final float maxRangeMm;

    // Movement detection
    private float recentDelta = 0;
    private static final float DELTA_DECAY = 0.9f;  // how fast movement memory fades
    private static final float MOVEMENT_THRESHOLD = 15f; // mm/s — below this = stable
    private long lastFilterTime = 0;

    // Outlier counter — consecutive spikes suggest real movement, not noise
    private int consecutiveSpikes = 0;
    private static final int SPIKE_TOLERANCE = 3; // allow N consecutive spikes before adapting

    /**
     * @param windowSize   median window
     * @param alpha        base EMA smoothing factor (higher=more responsive)
     * @param maxJumpMm    max allowed jump per sample in mm (0=disabled)
     * @param maxRangeMm   sensor max range (0=disable range checks)
     */
    public DistanceFilter(int windowSize, float alpha, float maxJumpMm, float maxRangeMm) {
        this.windowSize = windowSize;
        this.medianBuffer = new float[windowSize];
        this.sortBuffer = new float[windowSize];
        this.baseAlpha = alpha;
        this.fastAlpha = Math.min(0.7f, alpha * 3f); // 3x faster when moving
        this.currentAlpha = alpha;
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
        long now = System.nanoTime();
        float dt = (lastFilterTime > 0) ? (now - lastFilterTime) / 1_000_000_000f : 0.05f;
        lastFilterTime = now;

        if (rawMm < 0) {
            consecutiveSpikes = 0;
            return emaInitialized ? ema : -1;
        }

        // Range checks
        if (maxRangeMm > 0) {
            if (rawMm >= maxRangeMm) {
                consecutiveSpikes = 0;
                return emaInitialized ? ema : -1;
            }
        }

        // Adaptive spike handling
        if (lastRaw > 0 && lastRaw < 8190f && maxJumpMm > 0) {
            float jump = rawMm - lastRaw;
            float absJump = Math.abs(jump);

            if (absJump > maxJumpMm) {
                consecutiveSpikes++;
                if (consecutiveSpikes > SPIKE_TOLERANCE) {
                    // Multiple consecutive spikes → likely real movement, increase tolerance
                    float adaptiveMax = maxJumpMm * 2f;
                    if (absJump > adaptiveMax) {
                        rawMm = lastRaw + Math.signum(jump) * adaptiveMax;
                    }
                    // else: accept it
                } else {
                    // Single spike → soft blend toward boundary
                    rawMm = lastRaw + Math.signum(jump) * maxJumpMm * 0.7f;
                }
            } else {
                consecutiveSpikes = 0;
            }
        }

        // Track movement speed (rate of change)
        if (lastOutput >= 0 && dt > 0 && dt < 0.2f) {
            float instantDelta = Math.abs(rawMm - lastOutput) / dt; // mm/s
            recentDelta = recentDelta * DELTA_DECAY + instantDelta * (1f - DELTA_DECAY);
        }
        lastRaw = rawMm;

        // Adaptive alpha: fast when moving, smooth when stable
        if (recentDelta > MOVEMENT_THRESHOLD * 10) {
            currentAlpha = fastAlpha; // very fast movement
        } else if (recentDelta > MOVEMENT_THRESHOLD) {
            float t = (recentDelta - MOVEMENT_THRESHOLD) / (MOVEMENT_THRESHOLD * 9f);
            currentAlpha = baseAlpha + (fastAlpha - baseAlpha) * t;
        } else {
            currentAlpha = baseAlpha;
        }

        // Fill median buffer
        medianBuffer[medianPos] = rawMm;
        medianPos = (medianPos + 1) % windowSize;
        if (medianPos == 0) medianFilled = true;

        int count = medianFilled ? windowSize : medianPos;
        if (count < 2) {
            return applyEma(rawMm);
        }

        // Median filter
        System.arraycopy(medianBuffer, 0, sortBuffer, 0, count);
        Arrays.sort(sortBuffer, 0, count);
        float median = sortBuffer[count / 2];

        return applyEma(median);
    }

    private float applyEma(float value) {
        if (!emaInitialized) {
            ema = value;
            emaInitialized = true;
        } else {
            ema = currentAlpha * value + (1f - currentAlpha) * ema;
        }
        lastOutput = ema;
        return ema;
    }

    public void reset() {
        medianPos = 0;
        medianFilled = false;
        emaInitialized = false;
        lastRaw = -1;
        lastOutput = -1;
        ema = 0;
        recentDelta = 0;
        consecutiveSpikes = 0;
        lastFilterTime = 0;
        currentAlpha = baseAlpha;
        Arrays.fill(medianBuffer, -1);
    }

    public float getCurrentValue() {
        return ema;
    }

    public boolean isWarmedUp() {
        return medianFilled || medianPos >= 2;
    }

    /** Current estimated movement speed in mm/s. */
    public float getMovementSpeed() {
        return recentDelta;
    }
}
