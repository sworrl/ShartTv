package com.sharttv.launcher.ui;

import android.graphics.Color;

/**
 * Semantic coloring: every weather value gets a color that MEANS something (hot=red, cold=blue,
 * AQI by EPA band, UV by risk, wind by force, etc.). Colors are bright/saturated to read on the
 * dark glass. Pure math.
 */
public class Semantic {

    private static int lerp(int a, int b, float t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return Color.rgb(
            (int)(Color.red(a)+(Color.red(b)-Color.red(a))*t),
            (int)(Color.green(a)+(Color.green(b)-Color.green(a))*t),
            (int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));
    }
    /** map value across stops [v0..vn] to colors. */
    private static int ramp(double v, double[] stops, int[] cols) {
        if (v <= stops[0]) return cols[0];
        for (int i = 1; i < stops.length; i++) {
            if (v <= stops[i]) return lerp(cols[i-1], cols[i], (float)((v-stops[i-1])/(stops[i]-stops[i-1])));
        }
        return cols[cols.length-1];
    }

    // Temperature °F: deep blue -> cyan -> green -> yellow -> orange -> red
    public static int temp(double f) {
        return ramp(f,
            new double[]{10, 32, 50, 68, 85, 100},
            new int[]{0xFF4A6CF0, 0xFF46C7FF, 0xFF57D98A, 0xFFF2D857, 0xFFFF9E3D, 0xFFFF4D4D});
    }
    // AQI (US): EPA bands
    public static int aqi(int a) {
        return ramp(a, new double[]{0,50,100,150,200,300,500},
            new int[]{0xFF3FD17A,0xFF3FD17A,0xFFF2D857,0xFFFF9E3D,0xFFFF4D4D,0xFFB36BE0,0xFF9B4A5A});
    }
    public static int pm25(double v) { return ramp(v, new double[]{0,12,35,55,150,250},
            new int[]{0xFF3FD17A,0xFF3FD17A,0xFFF2D857,0xFFFF9E3D,0xFFFF4D4D,0xFFB36BE0}); }
    // UV index
    public static int uv(double u) { return ramp(u, new double[]{0,2,5,7,10,14},
            new int[]{0xFF3FD17A,0xFF3FD17A,0xFFF2D857,0xFFFF9E3D,0xFFFF4D4D,0xFFB36BE0}); }
    // Wind / gust mph: calm -> gale
    public static int wind(double mph) { return ramp(mph, new double[]{0,8,18,32,50},
            new int[]{0xFF8FB7C9,0xFF57D98A,0xFFF2D857,0xFFFF9E3D,0xFFFF4D4D}); }
    // Humidity %: dry(amber) - comfy(green) - humid(blue)
    public static int humidity(double h) { return ramp(h, new double[]{0,25,45,60,100},
            new int[]{0xFFFFB24D,0xFFF2D857,0xFF57D98A,0xFF46C7FF,0xFF4A6CF0}); }
    // Dew point °F comfort
    public static int dew(double f) { return ramp(f, new double[]{30,50,60,65,75},
            new int[]{0xFF8FB7C9,0xFF57D98A,0xFFF2D857,0xFFFF9E3D,0xFFFF4D4D}); }
    // Precip probability %
    public static int precip(double p) { return ramp(p, new double[]{0,20,50,80,100},
            new int[]{0xFF9AA4B2,0xFF57D98A,0xFF46C7FF,0xFF3D7BFF,0xFF4A6CF0}); }
    // Pressure hPa: low(stormy red) - 1013 neutral - high(fair blue)
    public static int pressure(double h) { return ramp(h, new double[]{985,1000,1013,1025,1040},
            new int[]{0xFFFF6E6E,0xFFFF9E3D,0xFFCBD3DE,0xFF57D98A,0xFF46C7FF}); }
    // Visibility miles: low(red) -> high(green)
    public static int visibility(double mi) { return ramp(mi, new double[]{0,2,5,10,20},
            new int[]{0xFFFF4D4D,0xFFFF9E3D,0xFFF2D857,0xFF57D98A,0xFF8FE3B0}); }
    // CAPE J/kg: 0 calm green -> high red (instability)
    public static int cape(double j) { return ramp(j, new double[]{0,500,1500,2500,4000},
            new int[]{0xFF57D98A,0xFFF2D857,0xFFFF9E3D,0xFFFF4D4D,0xFFB36BE0}); }
    // Cloud cover %: clear gold -> overcast grey
    public static int cloud(double pct) { return ramp(pct, new double[]{0,30,70,100},
            new int[]{0xFFF2D857,0xFF9AA4B2,0xFF8893A3,0xFF6B7585}); }
    public static int sun() { return 0xFFFFC56B; }
    public static int neutral() { return 0xFFE8EDF4; }
}
