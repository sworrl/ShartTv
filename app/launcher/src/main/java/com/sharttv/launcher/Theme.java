package com.sharttv.launcher;

import android.content.Context;
import android.graphics.Color;
import org.json.JSONObject;
import java.io.File;

/**
 * Shart TV appearance config. User-editable at runtime; persisted as theme.json in the app's
 * files dir. Everything the user asked to customize lives here: colors, backgrounds, tile shape,
 * focus animation, and list style.
 */
public class Theme {
    public int bgColor = Color.parseColor("#0B0E14");
    public int surfaceColor = Color.parseColor("#161B26");
    public int accentColor = Color.parseColor("#4FC3F7");
    public int textColor = Color.parseColor("#F2F5FA");
    public int textDimColor = Color.parseColor("#9AA4B2");

    public String bgImagePath = "";      // optional full-screen wallpaper (file path or content uri)
    public int cornerRadiusDp = 18;      // tile corner rounding
    public boolean focusScale = true;    // scale-up animation on focus
    public float focusScaleAmount = 1.12f;
    public int focusAnimMs = 140;
    public String listStyle = "rows";    // "rows" (horizontal shelves) or "grid"
    public boolean weather = true;       // weather + time-of-day backdrop
    public boolean autoPromote = true;   // sort rows by learned usage (most-used first)
    public boolean showMostUsed = true;  // show a "Most used" row at top
    public boolean showForecast = true;  // multi-day forecast strip in the hero
    public boolean radar = true;         // Windy radar inlay in the hero
    public String iconStyle = "banner";  // "banner" (wide app banner) or "icon" (square app icon)
    public int tileWidthDp = 168;        // banner tile width
    public int tileHeightDp = 94;

    private static final String FILE = "theme.json";

    public static Theme load(Context ctx) {
        Theme t = new Theme();
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists()) return t;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line; while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            t.bgColor = parse(o, "bgColor", t.bgColor);
            t.surfaceColor = parse(o, "surfaceColor", t.surfaceColor);
            t.accentColor = parse(o, "accentColor", t.accentColor);
            t.textColor = parse(o, "textColor", t.textColor);
            t.textDimColor = parse(o, "textDimColor", t.textDimColor);
            t.bgImagePath = o.optString("bgImagePath", t.bgImagePath);
            t.cornerRadiusDp = o.optInt("cornerRadiusDp", t.cornerRadiusDp);
            t.focusScale = o.optBoolean("focusScale", t.focusScale);
            t.focusScaleAmount = (float) o.optDouble("focusScaleAmount", t.focusScaleAmount);
            t.focusAnimMs = o.optInt("focusAnimMs", t.focusAnimMs);
            t.listStyle = o.optString("listStyle", t.listStyle);
            t.weather = o.optBoolean("weather", t.weather);
            t.autoPromote = o.optBoolean("autoPromote", t.autoPromote);
            t.showMostUsed = o.optBoolean("showMostUsed", t.showMostUsed);
            t.showForecast = o.optBoolean("showForecast", t.showForecast);
            t.radar = o.optBoolean("radar", t.radar);
            t.iconStyle = o.optString("iconStyle", t.iconStyle);
            t.tileWidthDp = o.optInt("tileWidthDp", t.tileWidthDp);
            t.tileHeightDp = o.optInt("tileHeightDp", t.tileHeightDp);
        } catch (Exception ignored) {}
        return t;
    }

    public void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            o.put("bgColor", hex(bgColor));
            o.put("surfaceColor", hex(surfaceColor));
            o.put("accentColor", hex(accentColor));
            o.put("textColor", hex(textColor));
            o.put("textDimColor", hex(textDimColor));
            o.put("bgImagePath", bgImagePath);
            o.put("cornerRadiusDp", cornerRadiusDp);
            o.put("focusScale", focusScale);
            o.put("focusScaleAmount", focusScaleAmount);
            o.put("focusAnimMs", focusAnimMs);
            o.put("listStyle", listStyle);
            o.put("weather", weather);
            o.put("autoPromote", autoPromote);
            o.put("showMostUsed", showMostUsed);
            o.put("showForecast", showForecast);
            o.put("radar", radar);
            o.put("iconStyle", iconStyle);
            o.put("tileWidthDp", tileWidthDp);
            o.put("tileHeightDp", tileHeightDp);
            File f = new File(ctx.getFilesDir(), FILE);
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(o.toString(2));
            w.close();
        } catch (Exception ignored) {}
    }

    private static int parse(JSONObject o, String k, int def) {
        try { String s = o.optString(k, null); return s == null ? def : Color.parseColor(s); }
        catch (Exception e) { return def; }
    }
    private static String hex(int c) {
        return String.format("#%08X", c);
    }
}
