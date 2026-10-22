package com.sharttv.launcher.weather;

/**
 * Current weather snapshot + WMO classification. Model and palettes ported from the FOSS
 * arcobocconotto weather engine (Open-Meteo WMO codes). Kept lightweight: plain fields, no deps.
 */
public class Wx {
    public double tempC, feelsC, precipMm, windKmh, windGust, cloudPct, windDirDeg;
    public double tempMaxC = Double.NaN, tempMinC = Double.NaN;
    public double dewC = Double.NaN, pressureHpa = Double.NaN, surfacePressureHpa = Double.NaN;
    public double visibilityM = Double.NaN, uvIndex = Double.NaN, cape = Double.NaN;
    public int cloudLow = -1, cloudMid = -1, cloudHigh = -1;
    public double precipProb = Double.NaN, uvMax = Double.NaN;
    public int sunriseSec = -1, sunsetSec = -1, aqi = -1; public double pm25 = Double.NaN;
    public int code = -1, humidity;
    public boolean isDay = true;
    public long fetchedAt;
    public double lat, lon;
    public String place = "";

    // Derived
    public String category = "unknown";   // clear, clear_night, cloudy, overcast, fog, drizzle,
                                           // rain, heavy_rain, snow, blizzard, storm
    public String name = "";
    public String icon = "";
    public float intensity = 0f;          // 0..1 effect strength (precip/wind driven)

    public boolean valid() { return code >= 0 && (System.currentTimeMillis() - fetchedAt) < 2L*3600*1000; }

    /** Port of ClassifyWMO: code -> category/name/icon. */
    public void classify() {
        int c = code;
        if (c == 0 || c == 1) { set(isDay ? "clear" : "clear_night", isDay ? "Clear & Sunny" : "Clear Night", isDay ? "☀" : "★"); }
        else if (c == 2) { set("cloudy", "Partly Cloudy", "⛅"); }
        else if (c == 3) { set("overcast", "Overcast", "☁"); }
        else if (c == 45 || c == 48) { set("fog", "Fog / Mist", "🌫"); }
        else if (c >= 51 && c <= 57) { set("drizzle", "Light Drizzle", "🌦"); }
        else if (c == 61 || c == 63 || c == 80 || c == 81) { set("rain", "Steady Rain", "🌧"); }
        else if (c == 65 || c == 82 || c == 66 || c == 67) { set("heavy_rain", "Torrential Downpour", "⛈"); }
        else if (c == 71 || c == 73 || c == 77 || c == 85) { set("snow", "Snow Flurries", "🌨"); }
        else if (c == 75 || c == 86) { set("blizzard", "Heavy Snow / Blizzard", "❄"); }
        else if (c == 95 || c == 96 || c == 99) { set("storm", "Thunderstorm", "⚡"); }
        else { set(isDay ? "clear" : "clear_night", "Atmospheric", "🌤"); }

        // intensity from precip + wind, clamped
        double p = Math.min(1.0, precipMm / 8.0);
        double w = Math.min(1.0, windKmh / 70.0);
        intensity = (float) Math.max(p, w * 0.7);
        if (category.equals("heavy_rain") || category.equals("storm") || category.equals("blizzard"))
            intensity = Math.max(intensity, 0.8f);
        if (category.equals("drizzle")) intensity = Math.max(0.25f, Math.min(intensity, 0.4f));
        if (category.equals("rain")) intensity = Math.max(0.45f, intensity);
    }

    private void set(String cat, String nm, String ic) { category = cat; name = nm; icon = ic; }

    /** True when it is cold enough that surfaces should read frosty regardless of precip. */
    public boolean isFreezing() { return tempC <= 1.0; }

    // Compact multi-day forecast (filled by the engine; may be null before first live fetch).
    public String[] fcDay;   // "Fri","Sat",...
    public int[] fcCode;     // WMO code per day
    public double[] fcMax, fcMin;

    /** WMO code -> single glyph for the forecast strip. */
    public static String glyph(int c) {
        if (c == 0 || c == 1) return "\u2600";
        if (c == 2) return "\u26C5";
        if (c == 3) return "\u2601";
        if (c == 45 || c == 48) return "\uD83C\uDF2B";
        if (c >= 51 && c <= 57) return "\uD83C\uDF26";
        if ((c >= 61 && c <= 65) || (c >= 80 && c <= 82) || c == 66 || c == 67) return "\uD83C\uDF27";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "\uD83C\uDF28";
        if (c >= 95) return "\u26C8";
        return "\uD83C\uDF24";
    }
}
