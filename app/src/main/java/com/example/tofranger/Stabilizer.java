package com.example.tofranger;

import java.util.Arrays;

/**
 * Shake-aware distance stabilizer with weighted median output.
 *
 * Improvements over v1:
 * - Weighted samples: recent readings have higher weight in shake buffer
 * - Gradual fade-out: stabilizer blends buffer median with latest reading
 *   over a short transition instead of hard switch
 * - Adaptive convergence: faster EMA when not shaking, slower when transitioning
 */
public class Stabilizer {

    private static final int BUFFER_SIZE = 20;
    private final float[] buffer = new float[BUFFER_SIZE];
    private final float[] weights = new float[BUFFER_SIZE]; // temporal weights
    private int bufferPos = 0;
    private boolean bufferFull = false;

    private float stabilizedValue = -1;
    private boolean wasShaking = false;

    // Sort buffer (reusable)
    private final float[] sortBuf = new float[BUFFER_SIZE];
    private final float[] weightBuf = new float[BUFFER_SIZE];

    private int invalidCount = 0;
    private static final int MAX_INVALID_BEFORE_SHOW_DASH = 8;

    // Transition blending after shake ends
    private float transitionAlpha = 0f;
    private static final float TRANSITION_RATE = 0.15f; // blend speed per call

    public float update(float filteredMm, boolean isShaking) {
        if (filteredMm < 0) {
            invalidCount++;
            if (invalidCount >= MAX_INVALID_BEFORE_SHOW_DASH) {
                return -1;
            }
            return stabilizedValue >= 0 ? stabilizedValue : -1;
        }
        invalidCount = 0;

        // Shake just started → begin buffering
        if (isShaking && !wasShaking) {
            bufferPos = 0;
            bufferFull = false;
            transitionAlpha = 0f;
        }

        if (isShaking) {
            buffer[bufferPos] = filteredMm;
            weights[bufferPos] = bufferPos + 1f; // newer = higher weight
            bufferPos = (bufferPos + 1) % BUFFER_SIZE;
            if (bufferPos == 0) bufferFull = true;
            wasShaking = true;
            return stabilizedValue >= 0 ? stabilizedValue : filteredMm;
        }

        // Shake just ended → compute weighted median from buffer
        if (wasShaking && !isShaking) {
            wasShaking = false;
            invalidCount = 0;
            int count = bufferFull ? BUFFER_SIZE : bufferPos;
            if (count >= 3) {
                stabilizedValue = weightedMedian(count);
            } else {
                stabilizedValue = filteredMm;
            }
            transitionAlpha = 0f;
            return stabilizedValue;
        }

        // Post-shake transition: blend stabilized value with live reading
        if (transitionAlpha < 1f) {
            transitionAlpha = Math.min(1f, transitionAlpha + TRANSITION_RATE);
            if (stabilizedValue < 0) {
                stabilizedValue = filteredMm;
            } else {
                stabilizedValue = stabilizedValue * (1f - transitionAlpha) + filteredMm * transitionAlpha;
            }
            return stabilizedValue;
        }

        // Normal (no shake) → responsive EMA
        if (stabilizedValue < 0) {
            stabilizedValue = filteredMm;
        } else {
            stabilizedValue = stabilizedValue * 0.4f + filteredMm * 0.6f;
        }
        return stabilizedValue;
    }

    /**
     * Weighted median: sort by value, find where cumulative weight crosses 50%.
     */
    private float weightedMedian(int count) {
        System.arraycopy(buffer, 0, sortBuf, 0, count);
        System.arraycopy(weights, 0, weightBuf, 0, count);

        // Simple insertion sort by value (small N, avoids allocation)
        for (int i = 1; i < count; i++) {
            float key = sortBuf[i];
            float wKey = weightBuf[i];
            int j = i - 1;
            while (j >= 0 && sortBuf[j] > key) {
                sortBuf[j + 1] = sortBuf[j];
                weightBuf[j + 1] = weightBuf[j];
                j--;
            }
            sortBuf[j + 1] = key;
            weightBuf[j + 1] = wKey;
        }

        float totalWeight = 0;
        for (int i = 0; i < count; i++) totalWeight += weightBuf[i];

        float cumWeight = 0;
        float halfWeight = totalWeight / 2f;
        for (int i = 0; i < count; i++) {
            cumWeight += weightBuf[i];
            if (cumWeight >= halfWeight) {
                return sortBuf[i];
            }
        }
        return sortBuf[count - 1];
    }

    public boolean hasBufferedData() {
        return bufferFull || bufferPos > 0;
    }

    public int getBufferedCount() {
        return bufferFull ? BUFFER_SIZE : bufferPos;
    }

    public void reset() {
        bufferPos = 0;
        bufferFull = false;
        stabilizedValue = -1;
        wasShaking = false;
        invalidCount = 0;
        transitionAlpha = 0f;
    }
}
