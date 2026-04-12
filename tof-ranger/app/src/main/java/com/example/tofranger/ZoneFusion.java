package com.example.tofranger;

/**
 * Multi-zone weighted fusion for 8x8 or 4x4 dToF sensor data.
 * Center zones get higher weight since the camera typically focuses there.
 */
public class ZoneFusion {

    /**
     * Fuse multiple zone distances into a single estimate.
     * Weights center zones higher than edges.
     *
     * @param zoneDists  array of zone distances in mm (-1 = invalid)
     * @param gridW      grid width (4 or 8)
     * @param gridH      grid height (4 or 8)
     * @return fused distance in mm, or -1 if no valid zones
     */
    public static float fuse(float[] zoneDists, int gridW, int gridH) {
        if (zoneDists == null || zoneDists.length == 0) return -1;

        int total = gridW * gridH;
        if (zoneDists.length < total) total = zoneDists.length;

        // Gaussian-ish weights: center = max, edges = min
        float[] weights = new float[total];
        float cx = (gridW - 1) / 2f;
        float cy = (gridH - 1) / 2f;
        float sigma = Math.max(gridW, gridH) * 0.35f;

        for (int i = 0; i < total; i++) {
            int x = i % gridW;
            int y = i / gridW;
            float dx = x - cx;
            float dy = y - cy;
            weights[i] = (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
        }

        float sum = 0, wSum = 0;
        for (int i = 0; i < total; i++) {
            if (zoneDists[i] > 0) {
                sum += zoneDists[i] * weights[i];
                wSum += weights[i];
            }
        }

        return wSum > 0 ? sum / wSum : -1;
    }

    /**
     * Convenience: fuse 8 zones in a single array.
     * Assumes 4x2 layout: [0][1][2][3] / [4][5][6][7]
     */
    public static float fuse8(float[] zoneDists) {
        return fuse(zoneDists, 4, 2);
    }

    /**
     * Find the closest valid zone distance.
     */
    public static float closest(float[] zoneDists) {
        float min = Float.MAX_VALUE;
        boolean found = false;
        for (float d : zoneDists) {
            if (d > 0 && d < min) {
                min = d;
                found = true;
            }
        }
        return found ? min : -1;
    }

    /**
     * Count valid zones (distance > 0).
     */
    public static int validCount(float[] zoneDists) {
        int count = 0;
        for (float d : zoneDists) {
            if (d > 0) count++;
        }
        return count;
    }
}
