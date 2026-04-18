package com.example.tofranger;

import java.util.ArrayDeque;

/**
 * Rolling statistics tracker for distance measurements.
 * Maintains min/max/avg/stddev over a sliding window.
 *
 * FIX: Uses Welford's online algorithm for O(1) stddev updates
 * instead of O(n) recomputation on every call.
 */
public class DistanceStats {

    private final ArrayDeque<Float> window;
    private final int maxSize;

    // Welford's online algorithm for running mean/variance
    private double runningMean = 0;
    private double runningM2 = 0;   // sum of squared differences from mean
    private int windowCount = 0;

    // All-time stats
    private float allTimeMin = Float.MAX_VALUE;
    private float allTimeMax = 0;
    private double allTimeSum = 0;
    private int allTimeCount = 0;

    // Sampling rate
    private long lastNanos = 0;
    private int sampleCount = 0;
    private int actualHz = 0;

    public DistanceStats(int windowSize) {
        this.maxSize = windowSize;
        this.window = new ArrayDeque<>(windowSize);
    }

    public DistanceStats() {
        this(200);
    }

    /** Add a valid distance sample — O(1) with Welford update. */
    public void add(float mm) {
        if (mm < 0) return;

        // Evict oldest if full
        if (window.size() >= maxSize) {
            float removed = window.removeFirst();
            // Welford remove
            double delta = removed - runningMean;
            runningMean -= delta / (windowCount - 1);
            double delta2 = removed - runningMean;
            runningM2 -= delta * delta2;
            windowCount--;
        }

        window.addLast(mm);

        // Welford add
        windowCount++;
        double delta = mm - runningMean;
        runningMean += delta / windowCount;
        double delta2 = mm - runningMean;
        runningM2 += delta * delta2;

        // All-time stats
        allTimeCount++;
        allTimeSum += mm;
        if (mm < allTimeMin) allTimeMin = mm;
        if (mm > allTimeMax) allTimeMax = mm;
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

    public float getMin() {
        return allTimeCount > 0 ? allTimeMin : 0;
    }

    public float getMax() {
        return allTimeCount > 0 ? allTimeMax : 0;
    }

    public float getAvg() {
        return allTimeCount > 0 ? (float)(allTimeSum / allTimeCount) : 0;
    }

    /** Standard deviation over sliding window — O(1) via Welford. */
    public float getStdDev() {
        if (windowCount < 2) return 0;
        return (float) Math.sqrt(runningM2 / windowCount);
    }

    /** Median over sliding window — O(n log n) sort, only called on demand. */
    public float getMedian() {
        int size = window.size();
        if (size == 0) return 0;
        float[] sorted = new float[size];
        int i = 0;
        for (float v : window) sorted[i++] = v;
        java.util.Arrays.sort(sorted);
        return sorted[size / 2];
    }

    public int getSampleCount() {
        return allTimeCount;
    }

    public int getWindowSize() {
        return window.size();
    }

    public int getActualHz() {
        return actualHz;
    }

    /** Trend: positive = moving away, negative = moving closer */
    public float getTrend() {
        int size = window.size();
        if (size < 10) return 0;
        int half = size / 2;
        float firstHalf = 0, secondHalf = 0;
        int idx = 0;
        for (float v : window) {
            if (idx < half) firstHalf += v;
            else secondHalf += v;
            idx++;
        }
        return (secondHalf / (size - half)) - (firstHalf / half);
    }

    public void reset() {
        window.clear();
        runningMean = 0;
        runningM2 = 0;
        windowCount = 0;
        allTimeMin = Float.MAX_VALUE;
        allTimeMax = 0;
        allTimeSum = 0;
        allTimeCount = 0;
        sampleCount = 0;
        actualHz = 0;
        lastNanos = 0;
    }

    public void resetWindow() {
        window.clear();
        runningMean = 0;
        runningM2 = 0;
        windowCount = 0;
    }
}
