package com.example.tofranger.view;

import android.graphics.Color;

/**
 * Shared theme state for all custom views.
 * Call ThemeColors.apply(light) when theme changes; views read these fields on draw.
 */
public final class ThemeColors {

    public static boolean isLight = true;

    // Dark
    private static final int C_BG_DARK        = Color.BLACK;
    private static final int C_ACCENT_DARK    = 0xFF5AC8FA;
    private static final int C_ACCENT2_DARK   = 0xFF34C759;
    private static final int C_ACCENT3_DARK   = 0xFFFF9F0A;
    private static final int C_TEXT_DARK      = 0xFFFFFFFF;
    private static final int C_TEXT_DIM_DARK  = 0x99FFFFFF;
    private static final int C_GLASS_BG_DARK  = 0x1AFFFFFF;
    private static final int C_GLASS_EDGE_DARK= 0x33FFFFFF;
    private static final int C_GLASS_SHINE_DARK= 0x0CFFFFFF;
    private static final int C_BAR_BG_DARK    = 0xCC000000;
    private static final int C_MORE_BG_DARK   = 0xEE111111;
    private static final int C_DEBUG_DIM_DARK = 0x66FFFFFF;
    private static final int C_SEP_DIM_DARK   = 0x33FFFFFF;

    // Light
    private static final int C_BG_LIGHT        = 0xFFF2F2F7;
    private static final int C_ACCENT_LIGHT    = 0xFF007AFF;
    private static final int C_ACCENT2_LIGHT   = 0xFF34C759;
    private static final int C_ACCENT3_LIGHT   = 0xFFFF9500;
    private static final int C_TEXT_LIGHT      = 0xFF000000;
    private static final int C_TEXT_DIM_LIGHT  = 0xFF636366;
    private static final int C_GLASS_BG_LIGHT  = 0xF0FFFFFF;
    private static final int C_GLASS_EDGE_LIGHT= 0x66000000;
    private static final int C_GLASS_SHINE_LIGHT= 0x15FFFFFF;
    private static final int C_BAR_BG_LIGHT    = 0xCCF2F2F7;
    private static final int C_MORE_BG_LIGHT   = 0xEEFFFFFF;
    private static final int C_DEBUG_DIM_LIGHT = 0x661C1C1E;
    private static final int C_SEP_DIM_LIGHT   = 0x331C1C1E;

    // Cached density for dp() — set in Activity.onCreate via initDensity()
    public static float DENSITY = 1f;

    /** Convert dp to px using cached density. */
    public static int dp(float v) {
        return (int) (v * DENSITY + 0.5f);
    }

    // Active
    public static int BG, ACCENT, ACCENT2, ACCENT3;
    public static int TEXT, TEXT_DIM, GLASS_BG, GLASS_EDGE, GLASS_SHINE;
    public static int BAR_BG, MORE_BG, DEBUG_DIM, SEP_DIM;

    static { apply(true); }

    public static void apply(boolean light) {
        isLight = light;
        if (light) {
            BG = C_BG_LIGHT;  ACCENT = C_ACCENT_LIGHT;  ACCENT2 = C_ACCENT2_LIGHT;
            ACCENT3 = C_ACCENT3_LIGHT;  TEXT = C_TEXT_LIGHT;  TEXT_DIM = C_TEXT_DIM_LIGHT;
            GLASS_BG = C_GLASS_BG_LIGHT;  GLASS_EDGE = C_GLASS_EDGE_LIGHT;
            GLASS_SHINE = C_GLASS_SHINE_LIGHT;  BAR_BG = C_BAR_BG_LIGHT;
            MORE_BG = C_MORE_BG_LIGHT;  DEBUG_DIM = C_DEBUG_DIM_LIGHT;
            SEP_DIM = C_SEP_DIM_LIGHT;
        } else {
            BG = C_BG_DARK;  ACCENT = C_ACCENT_DARK;  ACCENT2 = C_ACCENT2_DARK;
            ACCENT3 = C_ACCENT3_DARK;  TEXT = C_TEXT_DARK;  TEXT_DIM = C_TEXT_DIM_DARK;
            GLASS_BG = C_GLASS_BG_DARK;  GLASS_EDGE = C_GLASS_EDGE_DARK;
            GLASS_SHINE = C_GLASS_SHINE_DARK;  BAR_BG = C_BAR_BG_DARK;
            MORE_BG = C_MORE_BG_DARK;  DEBUG_DIM = C_DEBUG_DIM_DARK;
            SEP_DIM = C_SEP_DIM_DARK;
        }
    }
}
