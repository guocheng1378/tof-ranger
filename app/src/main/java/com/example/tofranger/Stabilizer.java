package com.example.tofranger;

import java.util.Arrays;

/**
 * Shake-aware distance stabilizer.
 *
 * When device is shaking, buffers readings. On shake end, outputs the
 * median of buffered samples as the stabilized value. Falls back to
 * gentle EMA filtering when not shaking.
 *
 * Improvements over v1:
 *  - Normal-mode EMA reduced from α=0.5 to α=0.15 (matches DistanceFilter smoothness)
 *  - Buffer filters out invalid values (-1) before median calculation
 *  - Warmup period to avoid false shake detection at startup
 */
public class Stabilizer {

    private static final int BUFFER_SIZE = 20;
    private final float[] buffer = new float[BUFFER_SIZE];
    private int bufferPos = 0;
    private boolean bufferFull = false;

    private float stabilizedValue = -1;
    private boolean wasShaking = false;

    // Sort buffer (reusable)
    private final float[] sortBuf = new float[BUFFER_SIZE];

    private int invalidCount = 0;
    private static final int MAX_INVALID_BEFORE_SHOW_DASH = 8; // ~400ms at 50ms UI throttle

    // Normal-mode smoothing (low = smoother, matches DistanceFilter output)
    private static final float NORMAL_ALPHA = 0.15f;

    /**
     * Feed a new filtered distance reading.
     * @param filteredMm value from DistanceFilter
     * @param isShaking current shake state
     * @return stabilized distance (or filteredMm if not shaking and no buffer)
     */
    public float update(float filteredMm, boolean isShaking) {
        if (filteredMm < 0) {
            invalidCount++;
            if (invalidCount >= MAX_INVALID_BEFORE_SHOW_DASH) {
                return -1; // force display "---"
            }
            return stabilizedValue >= 0 ? stabilizedValue : -1;
        }
        invalidCount = 0;

        // Shake just started → begin buffering
        if (isShaking && !wasShaking) {
            bufferPos = 0;
            bufferFull = false;
        }

        if (isShaking) {
            // Buffer readings during shake (skip invalid)
            buffer[bufferPos] = filteredMm;
            bufferPos = (bufferPos + 1) % BUFFER_SIZE;
            if (bufferPos == 0) bufferFull = true;
            wasShaking = true;
            // Return last stable value during shake, or current if none yet
            return stabilizedValue >= 0 ? stabilizedValue : filteredMm;
        }

        // Shake just ended → compute median from valid buffer entries
        if (wasShaking && !isShaking) {
            wasShaking = false;
            invalidCount = 0;
            int totalCount = bufferFull ? BUFFER_SIZE : bufferPos;
            // Filter out invalid values
            int validIdx = 0;
            for (int i = 0; i < totalCount; i++) {
                if (buffer[i] >= 0) {
                    sortBuf[validIdx++] = buffer[i];
                }
            }
            if (validIdx >= 3) {
                Arrays.sort(sortBuf, 0, validIdx);
                stabilizedValue = sortBuf[validIdx / 2];
            } else if (validIdx > 0) {
                stabilizedValue = sortBuf[0];
            } else {
                stabilizedValue = filteredMm;
            }
            return stabilizedValue;
        }

        // Normal (no shake) → gentle EMA toward current reading
        if (stabilizedValue < 0) {
            stabilizedValue = filteredMm;
        } else {
            stabilizedValue = stabilizedValue * (1f - NORMAL_ALPHA) + filteredMm * NORMAL_ALPHA;
        }
        return stabilizedValue;
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
    }
}
