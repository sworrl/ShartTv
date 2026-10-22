package com.sharttv.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Appearance editor. Everything the user asked to customize: accent, background, icon style,
 * list style, tile shape, and focus animation. Writes theme.json; the launcher re-reads on resume.
 */
public class SettingsActivity extends Activity {

    private Theme t;
    private LinearLayout col;

    private static final int[] ACCENTS = {
            Color.parseColor("#4FC3F7"), Color.parseColor("#FF6E6E"), Color.parseColor("#9CCC65"),
            Color.parseColor("#FFB74D"), Color.parseColor("#BA68C8"), Color.parseColor("#F5F5F5")
    };
    private static final int[] BGS = {
            Color.parseColor("#0B0E14"), Color.parseColor("#000000"), Color.parseColor("#12161C"),
            Color.parseColor("#1A1026"), Color.parseColor("#04140E")
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        t = Theme.load(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgColor);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), dp(40));
        sv.addView(col);

        title(getString(R.string.theme_settings), 22, t.textColor);
        section("Accent");
        swatchRow(ACCENTS, true);
        section("Background");
        swatchRow(BGS, false);
        section("App icon style");
        toggleRow("Banner", "Icon", t.iconStyle.equals("banner"), v -> {
            t.iconStyle = t.iconStyle.equals("banner") ? "icon" : "banner"; commit();
        });
        section("List style");
        toggleRow("Shelves (rows)", "Grid", t.listStyle.equals("rows"), v -> {
            t.listStyle = t.listStyle.equals("rows") ? "grid" : "rows"; commit();
        });
        section("Focus animation");
        toggleRow("On", "Off", t.focusScale, v -> { t.focusScale = !t.focusScale; commit(); });
        section("Weather + time-of-day backdrop");
        toggleRow("On", "Off", t.weather, v -> { t.weather = !t.weather; commit(); });
        TextView loc = new TextView(this);
        android.content.SharedPreferences wp = getSharedPreferences("sharttv_wx", MODE_PRIVATE);
        String bf = wp.getString("beaconfixUrl", "");
        float la = wp.getFloat("manualLat", Float.NaN), lo = wp.getFloat("manualLon", Float.NaN);
        String locTxt = "Location source: " +
                (!bf.isEmpty() ? "BeaconFix " + bf
                 : (!Float.isNaN(la) ? "manual " + la + "," + lo
                 : "auto-discover BeaconFix on the network"));
        loc.setText(locTxt + "\n(set beaconfixUrl / manualLat / manualLon in the 'sharttv_wx' prefs, or leave blank to auto-discover _beaconfix._tcp)");
        loc.setTextColor(t.textDimColor); loc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        loc.setPadding(0, dp(8), 0, 0);
        col.addView(loc);
        section("Tile corners");
        stepRow(() -> t.cornerRadiusDp + " dp",
                v -> { t.cornerRadiusDp = Math.max(0, t.cornerRadiusDp - 2); commit(); },
                v -> { t.cornerRadiusDp = Math.min(40, t.cornerRadiusDp + 2); commit(); });
        section("Tile size");
        stepRow(() -> t.tileWidthDp + "x" + t.tileHeightDp,
                v -> { t.tileWidthDp = Math.max(120, t.tileWidthDp - 20);
                       t.tileHeightDp = Math.max(72, t.tileHeightDp - 12); commit(); },
                v -> { t.tileWidthDp = Math.min(360, t.tileWidthDp + 20);
                       t.tileHeightDp = Math.min(220, t.tileHeightDp + 12); commit(); });

        Button done = mkButton("Done");
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(28);
        col.addView(done, dlp);

        setContentView(sv);
    }

    private void commit() { t.save(this); recreate(); }

    private void title(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setPadding(0, 0, 0, dp(10));
        col.addView(tv);
    }
    private void section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextColor(t.textDimColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding(0, dp(22), 0, dp(8));
        col.addView(tv);
    }

    private void swatchRow(int[] colors, final boolean accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final int c : colors) {
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(c); g.setCornerRadius(dp(10));
            int cur = accent ? t.accentColor : t.bgColor;
            if (c == cur) g.setStroke(dp(3), t.textColor);
            sw.setBackground(g);
            sw.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(48));
            lp.setMargins(0, 0, dp(14), 0);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> { if (accent) t.accentColor = c; else t.bgColor = c; commit(); });
            sw.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has?1.15f:1f).scaleY(has?1.15f:1f).setDuration(120).start());
            row.addView(sw);
        }
        col.addView(row);
    }

    private interface Txt { String get(); }
    private void toggleRow(String a, String b, boolean firstActive, View.OnClickListener onToggle) {
        Button btn = mkButton((firstActive ? "◉ " : "○ ") + a + "    /    " + (firstActive ? "○ " : "◉ ") + b);
        btn.setOnClickListener(onToggle);
        col.addView(btn);
    }
    private void stepRow(final Txt label, View.OnClickListener minus, View.OnClickListener plus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button m = mkButton("  –  "); m.setOnClickListener(minus);
        Button p = mkButton("  +  "); p.setOnClickListener(plus);
        TextView tv = new TextView(this);
        tv.setText(label.get()); tv.setTextColor(t.textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(dp(18), 0, dp(18), 0);
        row.addView(m); row.addView(tv); row.addView(p);
        col.addView(row);
    }

    private Button mkButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(t.textColor);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.surfaceColor); g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setFocusable(true);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((v, has) -> {
            GradientDrawable gg = new GradientDrawable();
            gg.setColor(t.surfaceColor); gg.setCornerRadius(dp(10));
            if (has) gg.setStroke(dp(3), t.accentColor);
            v.setBackground(gg);
        });
        return b;
    }
}
