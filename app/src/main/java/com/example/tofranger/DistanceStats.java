package com.example.tofranger;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Rolling statistics tracker for distance measurements.
 * Maintains min/max/avg/stddev over a sliding window.
 * Uses ArrayDeque for O(1) add/remove at both ends.
 */
public class DistanceStats {

    private final ArrayDeque<Float> window;
    private final int maxSize;

    // All-time stats
    private float allTimeMin = Float.MAX_VALUE;
    private float allTimeMax = 0;
    private float allTimeSum = 0;
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

    /** Add a valid distance sample */
    public void add(float mm) {
        if (mm < 0) return;

        window.addLast(mm);
        if (window.size() > maxSize) window.removeFirst();

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
        return allTimeCount > 0 ? allTimeSum / allTimeCount : 0;
    }

    /** Standard deviation over sliding window */
    public float getStdDev() {
        int size = window.size();
        if (size < 2) return 0;
        float mean = 0;
        for (float v : window) mean += v;
        mean /= size;
        float sumSq = 0;
        for (float v : window) {
            float d = v - mean;
            sumSq += d * d;
        }
        return (float) Math.sqrt(sumSq / size);
    }

    /** Median over sliding window */
    public float getMedian() {
        int size = window.size();
        if (size == 0) return 0;
        float[] sorted = new float[size];
        int i = 0;
        for (float v : window) sorted[i++] = v;
        Arrays.sort(sorted);
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
        int i = 0;
        for (float v : window) {
            if (i < half) firstHalf += v;
            else secondHalf += v;
            i++;
        }
        return (secondHalf / (size - half)) - (firstHalf / half);
    }

    public void reset() {
        window.clear();
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
    }
}
