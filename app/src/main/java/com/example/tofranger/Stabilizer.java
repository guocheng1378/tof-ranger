package com.example.tofranger;

import java.util.Arrays;

/**
 * Shake-aware distance stabilizer.
 *
 * When device is shaking, buffers readings. On shake end, outputs the
 * median of buffered samples as the stabilized value. Falls back to
 * normal EMA filtering when not shaking.
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

    /**
     * Feed a new filtered distance reading.
     * @param filteredMm value from DistanceFilter
     * @param isShaking current shake state
     * @return stabilized distance (or filteredMm if not shaking and no buffer)
     */
    public float update(float filteredMm, boolean isShaking) {
        if (filteredMm < 0) return stabilizedValue >= 0 ? stabilizedValue : -1;

        // Shake just started → begin buffering
        if (isShaking && !wasShaking) {
            bufferPos = 0;
            bufferFull = false;
        }

        if (isShaking) {
            // Buffer readings during shake
            buffer[bufferPos] = filteredMm;
            bufferPos = (bufferPos + 1) % BUFFER_SIZE;
            if (bufferPos == 0) bufferFull = true;
            wasShaking = true;
            // Return last stable value during shake, or current if none yet
            return stabilizedValue >= 0 ? stabilizedValue : filteredMm;
        }

        // Shake just ended → compute median from buffer
        if (wasShaking && !isShaking) {
            wasShaking = false;
            int count = bufferFull ? BUFFER_SIZE : bufferPos;
            if (count >= 3) {
                System.arraycopy(buffer, 0, sortBuf, 0, count);
                Arrays.sort(sortBuf, 0, count);
                stabilizedValue = sortBuf[count / 2];
            } else {
                stabilizedValue = filteredMm;
            }
            return stabilizedValue;
        }

        // Normal (no shake) → gentle EMA toward current reading
        if (stabilizedValue < 0) {
            stabilizedValue = filteredMm;
        } else {
            // Smooth convergence to filtered value
            stabilizedValue = stabilizedValue * 0.7f + filteredMm * 0.3f;
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
    }
}
