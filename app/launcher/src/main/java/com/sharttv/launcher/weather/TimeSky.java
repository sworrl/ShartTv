package com.sharttv.launcher.weather;

import android.graphics.Color;
import java.util.Calendar;

/**
 * Time-of-day color. Returns a base sky tint + accent derived from the EXACT local time, so the
 * scheme drifts continuously and is never identical second to second (a tiny sine wander on top of
 * the hour-based gradient). Uses BeaconFix sun times when available for true local dawn/dusk.
 * Pure math, a few ops per call — safe to call once a second.
 */
public class TimeSky {

    // Keyframe colors across the day (hue-rich, TV-friendly).
    private static final int NIGHT   = Color.parseColor("#070A14");
    private static final int DAWN    = Color.parseColor("#2A2140");
    private static final int SUNRISE = Color.parseColor("#6E4A5A");
    private static final int MORNING = Color.parseColor("#16314A");
    private static final int NOON    = Color.parseColor("#0E2236");
    private static final int AFTERN  = Color.parseColor("#163048");
    private static final int SUNSET  = Color.parseColor("#5A3A52");
    private static final int DUSK    = Color.parseColor("#201A38");

    public static class Sky { public int tint; public int accent; }

    public static Sky now(Loc loc) {
        int s = secondsOfDay();
        int sunrise = loc != null && loc.sunriseSec >= 0 ? loc.sunriseSec : 6 * 3600;
        int sunset  = loc != null && loc.sunsetSec  >= 0 ? loc.sunsetSec  : 19 * 3600;
        int dawn    = loc != null && loc.civilDawnSec >= 0 ? loc.civilDawnSec : sunrise - 1800;
        int dusk    = loc != null && loc.civilDuskSec >= 0 ? loc.civilDuskSec : sunset + 1800;
        int noon    = loc != null && loc.solarNoonSec >= 0 ? loc.solarNoonSec : (sunrise + sunset) / 2;

        // piecewise interpolation between the day's stops
        int base;
        if (s < dawn)            base = NIGHT;
        else if (s < sunrise)    base = lerp(DAWN, SUNRISE, frac(s, dawn, sunrise));
        else if (s < noon)       base = lerp(lerp(SUNRISE, MORNING, 0.5f), NOON, frac(s, sunrise, noon));
        else if (s < sunset)     base = lerp(NOON, AFTERN, frac(s, noon, sunset));
        else if (s < dusk)       base = lerp(SUNSET, DUSK, frac(s, sunset, dusk));
        else                     base = lerp(DUSK, NIGHT, frac(s, dusk, 24 * 3600));

        // continuous non-repeating wander: hue +/- ~4 deg, value +/- ~3% on long sine periods
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        double t = System.currentTimeMillis() / 1000.0;
        hsv[0] = (hsv[0] + (float) (4.0 * Math.sin(t / 47.0)) + 360f) % 360f;
        hsv[2] = clamp01(hsv[2] + (float) (0.03 * Math.sin(t / 29.0)));
        hsv[1] = clamp01(hsv[1] + (float) (0.03 * Math.sin(t / 61.0)));

        Sky sky = new Sky();
        sky.tint = Color.HSVToColor(hsv);
        // accent: complementary-ish, brighter, also drifting
        float[] a = new float[]{(hsv[0] + 150f + (float)(6.0*Math.sin(t/37.0))) % 360f,
                                clamp01(0.55f + 0.15f*(float)Math.sin(t/53.0)),
                                0.85f};
        sky.accent = Color.HSVToColor(a);
        return sky;
    }

    /** Blend the time tint with weather mood (overcast desaturates, storm darkens, clear lifts). */
    public static int weatherAdjust(int tint, Wx wx) {
        if (wx == null) return tint;
        float[] h = new float[3]; Color.colorToHSV(tint, h);
        switch (wx.category) {
            case "overcast": case "fog": h[1] *= 0.5f; h[2] = clamp01(h[2] + 0.04f); break;
            case "storm": h[2] *= 0.6f; h[1] = clamp01(h[1] + 0.1f); break;
            case "heavy_rain": case "rain": h[2] *= 0.8f; h[0] = 215; h[1] = clamp01(h[1]+0.1f); break;
            case "snow": case "blizzard": h[1] *= 0.4f; h[2] = clamp01(h[2]+0.06f); break;
            case "clear": h[2] = clamp01(h[2] + 0.05f); break;
        }
        if (wx.isFreezing()) { h[0] = 195; h[1] = clamp01(h[1] + 0.12f); }
        return Color.HSVToColor(h);
    }

    private static int secondsOfDay() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND);
    }
    private static float frac(int s, int a, int b) { return b <= a ? 0 : clamp01((float) (s - a) / (b - a)); }
    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static int lerp(int c1, int c2, float t) {
        return Color.argb(255,
            (int) (Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * t),
            (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t),
            (int) (Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * t));
    }
}
