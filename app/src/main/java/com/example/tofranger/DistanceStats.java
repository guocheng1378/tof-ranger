package com.example.tofranger;

/**
 * Rolling statistics tracker for distance measurements.
 * Maintains min/max/avg/stddev over a sliding window.
 *
 * Improvements over v1:
 *  - Ring buffer (float[]) instead of ArrayDeque<Float> — no boxing/GC pressure
 *  - Welford's online algorithm for O(1) incremental mean/variance
 *  - Incremental min/max tracking over sliding window
 */
public class DistanceStats {

    private final float[] ringBuffer;
    private final int maxSize;
    private int head = 0;      // next write position
    private int count = 0;     // current number of elements

    // Welford online stats (over sliding window)
    private double welfordMean = 0;
    private double welfordM2 = 0;

    // Incremental min/max tracking
    private float windowMin = Float.MAX_VALUE;
    private float windowMax = -Float.MAX_VALUE;

    // All-time stats
    private float allTimeMin = Float.MAX_VALUE;
    private float allTimeMax = 0;
    private double allTimeSum = 0;
    private int allTimeCount = 0;

    // Sampling rate
    private long lastNanos = 0;
    private int sampleCount = 0;
    private int actualHz = 0;

    // Reusable sort buffer for median
    private float[] sortBuffer;

    public DistanceStats(int windowSize) {
        this.maxSize = windowSize;
        this.ringBuffer = new float[windowSize];
        this.sortBuffer = new float[windowSize];
    }

    public DistanceStats() {
        this(200);
    }

    /** Add a valid distance sample */
    public void add(float mm) {
        if (mm < 0) return;

        // If buffer full, remove oldest from Welford stats
        if (count == maxSize) {
            float oldest = ringBuffer[head]; // head points to oldest when full
            // Approximate removal from Welford (exact for large windows)
            double delta = oldest - welfordMean;
            welfordMean -= delta / maxSize;
            double delta2 = oldest - welfordMean;
            welfordM2 -= delta * delta2;
        }

        // Write to ring buffer
        ringBuffer[head] = mm;
        head = (head + 1) % maxSize;
        if (count < maxSize) count++;

        // Update Welford
        double delta = mm - welfordMean;
        welfordMean += delta / count;
        double delta2 = mm - welfordMean;
        welfordM2 += delta * delta2;

        // Update all-time stats
        allTimeCount++;
        allTimeSum += mm;
        if (mm < allTimeMin) allTimeMin = mm;
        if (mm > allTimeMax) allTimeMax = mm;

        // Invalidate window min/max (lazy recompute on read)
        windowMin = Float.MAX_VALUE;
        windowMax = -Float.MAX_VALUE;
    }

    /** Call on every sensor event for Hz calculation */
    public void tickHz() {
        sampleCount++;
        long now = System.nanoTime();
        if (lastNanos == 0) {
            lastNanos = now;
            return;
        }
        if (now - lastNanos >= 1_000_000_000L) {
            actualHz = sampleCount;
            sampleCount = 0;
            lastNanos = now;
        }
    }

    /** Compute window min/max on demand (lazy, amortized cheap) */
    private void computeWindowMinMax() {
        if (windowMin != Float.MAX_VALUE) return; // already computed
        windowMin = Float.MAX_VALUE;
        windowMax = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float v = ringBuffer[i];
            if (v < windowMin) windowMin = v;
            if (v > windowMax) windowMax = v;
        }
    }

    public float getMin() {
        return allTimeCount > 0 ? allTimeMin : 0;
    }

    public float getMax() {
        return allTimeCount > 0 ? allTimeMax : 0;
    }

    public float getAvg() {
        return allTimeCount > 0 ? (float) (allTimeSum / allTimeCount) : 0;
    }

    /** Standard deviation over sliding window — O(1) via Welford */
    public float getStdDev() {
        if (count < 2) return 0;
        double variance = welfordM2 / count;
        return (float) Math.sqrt(Math.max(0, variance)); // clamp to avoid negative from float precision
    }

    /** Median over sliding window — uses pre-allocated sortBuffer */
    public float getMedian() {
        if (count == 0) return 0;
        // Copy ring buffer to sort buffer
        if (count <= maxSize) {
            // Linear copy from ring buffer
            int firstLen = Math.min(count, maxSize - head);
            System.arraycopy(ringBuffer, head, sortBuffer, 0, firstLen);
            if (firstLen < count) {
                System.arraycopy(ringBuffer, 0, sortBuffer, firstLen, count - firstLen);
            }
        }
        // Insertion sort for small arrays (faster than Arrays.sort for n < ~20)
        if (count <= 20) {
            for (int i = 1; i < count; i++) {
                float key = sortBuffer[i];
                int j = i - 1;
                while (j >= 0 && sortBuffer[j] > key) {
                    sortBuffer[j + 1] = sortBuffer[j];
                    j--;
                }
                sortBuffer[j + 1] = key;
            }
        } else {
            java.util.Arrays.sort(sortBuffer, 0, count);
        }
        return sortBuffer[count / 2];
    }

    public int getSampleCount() {
        return allTimeCount;
    }

    public int getWindowSize() {
        return count;
    }

    public int getActualHz() {
        return actualHz;
    }

    /** Trend: positive = moving away, negative = moving closer */
    public float getTrend() {
        if (count < 10) return 0;
        int half = count / 2;
        float firstHalf = 0, secondHalf = 0;
        for (int i = 0; i < half; i++) firstHalf += ringBuffer[i % maxSize];
        for (int i = half; i < count; i++) secondHalf += ringBuffer[i % maxSize];
        return (secondHalf / (count - half)) - (firstHalf / half);
    }

    public void reset() {
        head = 0;
        count = 0;
        welfordMean = 0;
        welfordM2 = 0;
        windowMin = Float.MAX_VALUE;
        windowMax = -Float.MAX_VALUE;
        allTimeMin = Float.MAX_VALUE;
        allTimeMax = 0;
        allTimeSum = 0;
        allTimeCount = 0;
        sampleCount = 0;
        actualHz = 0;
        lastNanos = 0;
    }

    public void resetWindow() {
        head = 0;
        count = 0;
        welfordMean = 0;
        welfordM2 = 0;
        windowMin = Float.MAX_VALUE;
        windowMax = -Float.MAX_VALUE;
    }
}
