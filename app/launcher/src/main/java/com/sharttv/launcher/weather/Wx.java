package com.sharttv.launcher.weather;

/**
 * Current weather snapshot + WMO classification. Model and palettes ported from the FOSS
 * arcobocconotto weather engine (Open-Meteo WMO codes). Kept lightweight: plain fields, no deps.
 */
public class Wx {
    public double tempC, feelsC, precipMm, windKmh, windGust, cloudPct;
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
}
