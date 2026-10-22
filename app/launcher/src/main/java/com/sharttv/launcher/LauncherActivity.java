package com.sharttv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import com.sharttv.launcher.ui.Semantic;
import com.sharttv.launcher.weather.Loc;
import com.sharttv.launcher.weather.TimeSky;
import com.sharttv.launcher.weather.WeatherEngine;
import com.sharttv.launcher.weather.WeatherView;
import com.sharttv.launcher.weather.Wx;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shart TV launcher home. Ad-free, rich, liquid-glass + classical/gothic. A live weather + time-of-
 * day backdrop, a glass top bar (clock + weather), a weather hero with real readings, and glass
 * cards for inputs, apps and settings.
 */
public class LauncherActivity extends Activity {

    private Theme theme;
    private FrameLayout root;
    private final List<Tile> inputs = new ArrayList<>();
    private BroadcastReceiver inputChanged;
    private UsageStore usage;
    private LinearLayout forecastStrip;
    private android.webkit.WebView radar;

    private static String usageKey(Tile t) {
        String p = t.kind == Tile.Kind.APP ? "app:" : t.kind == Tile.Kind.INPUT ? "input:" : "set:";
        return p + t.key;
    }

    // weather + time-of-day
    private WeatherView weatherView;
    private WeatherEngine engine;
    private Wx curWx;
    private Loc curLoc;

    // chrome
    private TextView barClock, barDate, barWx;
    private TextView heroTemp, heroCond, heroPlace, heroMetrics;
    private int accentNow;

    private final android.os.Handler sky = new android.os.Handler();
    private final Runnable skyTick = new Runnable() {
        @Override public void run() {
            TimeSky.Sky s = TimeSky.now(curLoc);
            int tint = TimeSky.weatherAdjust(s.tint, curWx);
            accentNow = s.accent;
            if (weatherView != null) weatherView.setTint(tint);
            updateChrome();
            sky.postDelayed(this, 1000);   // once a second: cheap, never the same twice
        }
    };

    static class Section { String title; List<Tile> tiles; Section(String t, List<Tile> x){title=t;tiles=x;} }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Fonts.load(this);
        Glass.init(this);
        usage = new UsageStore(this);
        theme = Theme.load(this);
        accentNow = theme.accentColor;
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        theme = Theme.load(this);
        buildUi();
        if (theme.weather) {
            if (engine == null) {
                engine = new WeatherEngine(this);
                engine.setListener((wx, loc) -> {
                    curWx = wx; curLoc = loc;
                    if (weatherView != null) weatherView.setWeather(wx);
                    updateWeatherUi();
                });
            }
            engine.start();
            sky.removeCallbacks(skyTick);
            sky.post(skyTick);
            if (weatherView != null) weatherView.startAnim();
        } else {
            sky.removeCallbacks(skyTick);
            sky.post(skyTick);   // still drive the clock
        }
    }

    @Override protected void onPause() {
        super.onPause();
        sky.removeCallbacks(skyTick);
        if (engine != null) engine.stop();
        if (weatherView != null) weatherView.stopAnim();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bgColor);

        if (theme.weather) {
            weatherView = new WeatherView(this);
            root.addView(weatherView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (curWx != null) weatherView.setWeather(curWx);
            TimeSky.Sky s0 = TimeSky.now(curLoc);
            weatherView.setTint(TimeSky.weatherAdjust(s0.tint, curWx));
        } else {
            applyWallpaper();
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (theme.weather) {
            col.addView(buildHero(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        }

        RecyclerView shelves = new RecyclerView(this);
        shelves.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        shelves.setPadding(dp(48), dp(10), dp(48), dp(40));
        shelves.setClipToPadding(false);

        List<Section> sections = new ArrayList<>();
        inputs.clear();
        inputs.addAll(Repo.inputs(this, theme));
        List<Tile> apps = Repo.apps(this, theme);
        List<Tile> settings = Repo.settings(this, theme);

        // Learned usage: auto-promote (sort each row by score) when enabled.
        if (theme.autoPromote) {
            UsageStore.KeyOf<Tile> ko = LauncherActivity::usageKey;
            usage.sortByScore(inputs, ko);
            usage.sortByScore(apps, ko);
        }

        // "Most used" row from everything the owner actually launches.
        if (theme.showMostUsed && usage.hasData()) {
            List<Tile> pool = new ArrayList<>(); pool.addAll(inputs); pool.addAll(apps); pool.addAll(settings);
            java.util.Map<String, Tile> byKey = new java.util.HashMap<>();
            for (Tile t : pool) byKey.put(usageKey(t), t);
            List<Tile> most = new ArrayList<>();
            for (String k : usage.topKeys(8)) { Tile t = byKey.get(k); if (t != null) most.add(t); }
            if (!most.isEmpty()) sections.add(new Section(getString(R.string.most_used), most));
        }

        // Featured Hione apps (HioneCast etc.) — always promoted to the very top.
        List<Tile> hione = new ArrayList<>();
        for (Tile t : apps) {
            String k = t.key == null ? "" : t.key.toLowerCase(Locale.US);
            String l = t.label == null ? "" : t.label.toLowerCase(Locale.US);
            if (k.startsWith("com.hione") || k.contains(".hione.") || l.contains("hione")) hione.add(t);
        }
        if (!hione.isEmpty()) sections.add(0, new Section("Hione", hione));

        if (!inputs.isEmpty()) sections.add(new Section(getString(R.string.inputs), inputs));
        sections.add(new Section(getString(R.string.apps), apps));
        sections.add(new Section(getString(R.string.settings), settings));
        shelves.setAdapter(new ShelfAdapter(sections));
        col.addView(shelves, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        registerInputChanged();
        updateChrome();
        if (theme.weather) updateWeatherUi();
    }

    // ---------- top bar ----------
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(48), dp(18), dp(48), dp(14));
        bar.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(2)));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        barClock = new TextView(this);
        barClock.setTypeface(Fonts.display, Typeface.BOLD);
        barClock.setTextColor(theme.textColor);
        barClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        barClock.setLetterSpacing(0.06f);
        barDate = new TextView(this);
        barDate.setTypeface(Fonts.body);
        barDate.setTextColor(theme.textDimColor);
        barDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        barDate.setLetterSpacing(0.14f);
        left.addView(barClock); left.addView(barDate);
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        barWx = new TextView(this);
        barWx.setTypeface(Fonts.body);
        barWx.setTextColor(theme.textColor);
        barWx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        barWx.setGravity(Gravity.END);
        bar.addView(barWx, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    // ---------- weather hero (nerd dashboard) ----------
    private final java.util.LinkedHashMap<String, TextView> metricViews = new java.util.LinkedHashMap<>();
    private static final String[] METRIC_KEYS = {
        "Feels","Dew Pt","Humidity","Pressure","Wind","Gust","UV","UV Max",
        "Visibility","Cloud L/M/H","CAPE","Precip %","AQI","PM2.5","Sunrise","Sunset"
    };

    private View buildHero() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(26)));
        Glass.clipRound(wrap, dp(26));
        int pad = dp(20);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad + dp(18), pad, pad + dp(18), pad);

        // ---- main row: left summary | metrics grid | radar ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftc = new LinearLayout(this);
        leftc.setOrientation(LinearLayout.VERTICAL);
        heroTemp = new TextView(this);
        heroTemp.setTypeface(Fonts.display, Typeface.BOLD);
        heroTemp.setTextColor(theme.textColor);
        heroTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        heroCond = new TextView(this);
        heroCond.setTypeface(Fonts.display);
        heroCond.setTextColor(theme.accentColor);
        heroCond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        heroCond.setLetterSpacing(0.03f);
        heroCond.setMaxLines(2);
        heroPlace = new TextView(this);
        heroPlace.setTypeface(Fonts.body);
        heroPlace.setTextColor(theme.textDimColor);
        heroPlace.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        leftc.addView(heroTemp); leftc.addView(heroCond); leftc.addView(heroPlace);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(leftc, llp);

        // metrics: 4 even columns (4 metrics each) so nothing clips
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        metricViews.clear();
        int cols = 4, perCol = (METRIC_KEYS.length + cols - 1) / cols;
        for (int c = 0; c < cols; c++) {
            LinearLayout colv = new LinearLayout(this);
            colv.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < perCol; r++) {
                int idx = c * perCol + r;
                if (idx >= METRIC_KEYS.length) break;
                String key = METRIC_KEYS[idx];
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(4), dp(4), dp(10), dp(4));
                TextView val = new TextView(this);
                val.setTypeface(Fonts.display, Typeface.BOLD);
                val.setTextColor(theme.textColor);
                val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                val.setSingleLine(true);
                val.setText("—");
                TextView lab = new TextView(this);
                lab.setTypeface(Fonts.body);
                lab.setTextColor(theme.textDimColor);
                lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                lab.setLetterSpacing(0.03f);
                lab.setSingleLine(true);
                lab.setText(key.toUpperCase(Locale.getDefault()));
                cell.addView(val); cell.addView(lab);
                metricViews.put(key, val);
                colv.addView(cell);
            }
            grid.addView(colv, new LinearLayout.LayoutParams(dp(98), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(dp(18), 0, dp(10), 0);
        row.addView(grid, glp);

        // radar inlay (Windy embed; keyless)
        if (theme.radar) {
            radar = new android.webkit.WebView(this);
            android.webkit.WebSettings ws = radar.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            radar.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout radarWrap = new FrameLayout(this);
            Glass.clipRound(radarWrap, dp(16));
            radarWrap.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(16)));
            radarWrap.addView(radar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(236), 1f);
            rlp.setMarginStart(dp(6));
            row.addView(radarWrap, rlp);
        }

        card.addView(row);

        // ---- forecast strip ----
        forecastStrip = new LinearLayout(this);
        forecastStrip.setOrientation(LinearLayout.HORIZONTAL);
        forecastStrip.setGravity(Gravity.CENTER_VERTICAL);
        View rule = new View(this);
        rule.setBackground(Glass.divider(Glass.withAlpha(theme.accentColor, 0x55)));
        LinearLayout.LayoutParams rulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
        rulp.setMargins(0, dp(6), 0, dp(2));
        card.addView(rule, rulp);
        card.addView(forecastStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(48), dp(14), dp(48), dp(6));
        holder.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return holder;
    }

    /** clock + tint, every second (cheap). */
    private void updateChrome() {
        Date now = new Date();
        if (barClock != null) barClock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        if (barDate != null) barDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        if (barWx == null) updateWeatherUi();
    }

    /** heavy weather UI; only on a new reading. */
    private void updateWeatherUi() {
        boolean haveWx = curWx != null && curWx.valid();
        if (barWx != null)
            barWx.setText(haveWx ? curWx.icon + "   " + f(curWx.tempC) + "°  ·  " + curWx.name : "Shart TV");
        if (heroTemp == null) return;
        if (!haveWx) {
            heroTemp.setText("—"); heroCond.setText("Weather");
            heroPlace.setText("waiting for a location fix");
            return;
        }
        heroTemp.setText(f(curWx.tempC) + "°");
        heroCond.setText(curWx.name);
        heroPlace.setText((curLoc != null && curLoc.place != null && !curLoc.place.isEmpty())
                ? curLoc.place : (curLoc != null ? String.format(Locale.US,"%.2f, %.2f",curLoc.lat,curLoc.lon) : ""));
        setM("Feels", f(curWx.feelsC) + "°");
        setM("Dew Pt", Double.isNaN(curWx.dewC) ? "—" : f(curWx.dewC) + "°");
        setM("Humidity", curWx.humidity > 0 ? curWx.humidity + "%" : "—");
        setM("Pressure", Double.isNaN(curWx.pressureHpa) ? "—" : Math.round(curWx.pressureHpa) + " hPa");
        setM("Wind", compass(curWx.windDirDeg) + " " + Math.round(curWx.windKmh * 0.621) + " mph");
        setM("Gust", Double.isNaN(curWx.windGust) ? "—" : Math.round(curWx.windGust * 0.621) + " mph");
        setM("UV", Double.isNaN(curWx.uvIndex) ? "—" : String.valueOf((int) Math.round(curWx.uvIndex)));
        setM("UV Max", Double.isNaN(curWx.uvMax) ? "—" : String.valueOf((int) Math.round(curWx.uvMax)));
        setM("Visibility", Double.isNaN(curWx.visibilityM) ? "—" : Math.round(curWx.visibilityM / 1609.0) + " mi");
        setM("Cloud L/M/H", curWx.cloudLow < 0 ? "—" : curWx.cloudLow + "/" + curWx.cloudMid + "/" + curWx.cloudHigh);
        setM("CAPE", Double.isNaN(curWx.cape) ? "—" : Math.round(curWx.cape) + "");
        setM("Precip %", Double.isNaN(curWx.precipProb) ? "—" : (int) curWx.precipProb + "%");
        setM("AQI", curWx.aqi < 0 ? "—" : String.valueOf(curWx.aqi));
        setM("PM2.5", Double.isNaN(curWx.pm25) ? "—" : String.format(Locale.US, "%.0f", curWx.pm25));
        setM("Sunrise", curWx.sunriseSec < 0 ? "—" : hm(curWx.sunriseSec));
        setM("Sunset", curWx.sunsetSec < 0 ? "—" : hm(curWx.sunsetSec));
        buildForecast();
        updateRadar();
    }

    private void setM(String k, String v) { TextView t = metricViews.get(k); if (t != null) t.setText(v); }
    private void setMC(String k, String v, int color) { TextView t = metricViews.get(k); if (t != null) { t.setText(v); t.setTextColor(color); } }

    private double radarLat = Double.NaN, radarLon = Double.NaN;
    private void updateRadar() {
        if (radar == null || curLoc == null) return;
        if (!Double.isNaN(radarLat) && Math.abs(radarLat - curLoc.lat) < 0.05 && Math.abs(radarLon - curLoc.lon) < 0.05) return;
        radarLat = curLoc.lat; radarLon = curLoc.lon;
        String url = "https://embed.windy.com/embed2.html?lat=" + curLoc.lat + "&lon=" + curLoc.lon
                + "&detailLat=" + curLoc.lat + "&detailLon=" + curLoc.lon
                + "&zoom=7&level=surface&overlay=radar&product=radar&menu=&message=true&marker=true"
                + "&calendar=&pressure=&type=map&location=coordinates&detail=&metricWind=mph&metricTemp=%C2%B0F&radarRange=-1";
        radar.loadUrl(url);
    }

    private void buildForecast() {
        if (forecastStrip == null) return;
        forecastStrip.removeAllViews();
        if (!theme.showForecast || curWx == null || curWx.fcDay == null) return;
        int n = curWx.fcDay.length;
        for (int i = 0; i < n; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(14), dp(2), dp(14), dp(2));
            TextView day = new TextView(this);
            day.setTypeface(Fonts.body);
            day.setTextColor(theme.textDimColor);
            day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            day.setLetterSpacing(0.08f);
            day.setGravity(Gravity.CENTER);
            day.setText(curWx.fcDay[i].toUpperCase(Locale.getDefault()));
            TextView g = new TextView(this);
            g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            g.setGravity(Gravity.CENTER);
            g.setText(Wx.glyph(curWx.fcCode[i]));
            TextView hiT = new TextView(this); hiT.setTypeface(Fonts.body); hiT.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
            TextView loT = new TextView(this); loT.setTypeface(Fonts.body); loT.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
            if (!Double.isNaN(curWx.fcMax[i])) { hiT.setText(f(curWx.fcMax[i]) + "°"); hiT.setTextColor(Semantic.temp(f(curWx.fcMax[i]))); }
            if (!Double.isNaN(curWx.fcMin[i])) { loT.setText(f(curWx.fcMin[i]) + "°"); loT.setTextColor(Semantic.temp(f(curWx.fcMin[i]))); }
            LinearLayout hlRow = new LinearLayout(this); hlRow.setOrientation(LinearLayout.HORIZONTAL); hlRow.setGravity(Gravity.CENTER);
            TextView slash = new TextView(this); slash.setText("  /  "); slash.setTextColor(theme.textDimColor); slash.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
            hlRow.addView(hiT); hlRow.addView(slash); hlRow.addView(loT);
            cell.addView(day); cell.addView(g); cell.addView(hlRow);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            forecastStrip.addView(cell, clp);
        }
    }

    private static int f(double c) { return (int) Math.round(c * 9 / 5 + 32); }
    private static String hm(int sec) {
        int h = sec / 3600, m = (sec % 3600) / 60; String ap = h < 12 ? "a" : "p";
        int h12 = h % 12; if (h12 == 0) h12 = 12;
        return h12 + ":" + String.format(Locale.US, "%02d", m) + ap;
    }
    private static String compass(double deg) {
        String[] d = {"N","NE","E","SE","S","SW","W","NW"};
        return d[(int) Math.round(deg / 45.0) % 8];
    }

    private void applyWallpaper() {
        if (theme.bgImagePath == null || theme.bgImagePath.isEmpty()) return;
        try {
            ImageView bg = new ImageView(this);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Drawable d;
            if (theme.bgImagePath.startsWith("content://")) {
                d = Drawable.createFromStream(getContentResolver().openInputStream(Uri.parse(theme.bgImagePath)), null);
            } else {
                d = Drawable.createFromStream(new FileInputStream(theme.bgImagePath), null);
            }
            if (d != null) {
                bg.setImageDrawable(d);
                root.addView(bg, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                View dim = new View(this);
                dim.setBackgroundColor(Color.argb(140, 0, 0, 0));
                root.addView(dim, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Exception ignored) {}
    }

    private void registerInputChanged() {
        if (inputChanged != null) return;
        inputChanged = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String id = i.getStringExtra("inputId");
                if (id == null) id = i.getStringExtra("input_id");
                if (id == null) return;
                for (int k = 0; k < inputs.size(); k++) {
                    if (id.equals(inputs.get(k).key)) {
                        Tile t = inputs.remove(k); inputs.add(0, t); break;
                    }
                }
            }
        };
        try {
            registerReceiver(inputChanged,
                    new IntentFilter("com.sony.dtv.tvplayer.intent.action.INPUT_CHANGED"),
                    Context.RECEIVER_EXPORTED);
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (inputChanged != null) { try { unregisterReceiver(inputChanged); } catch (Exception ignored) {} }
        sky.removeCallbacks(skyTick);
        if (engine != null) engine.stop();
    }

    @Override public void onBackPressed() { /* HOME is the root; swallow BACK */ }

    // ===== Adapters =====

    class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.VH> {
        final List<Section> data;
        ShelfAdapter(List<Section> d) { data = d; }

        class VH extends RecyclerView.ViewHolder {
            TextView header; View rule; RecyclerView row;
            VH(LinearLayout v, TextView h, View ru, RecyclerView r) { super(v); header = h; rule = ru; row = r; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            Context ctx = p.getContext();
            LinearLayout colv = new LinearLayout(ctx);
            colv.setOrientation(LinearLayout.VERTICAL);
            colv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout headRow = new LinearLayout(ctx);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            headRow.setPadding(dp(4), dp(16), 0, dp(8));
            TextView h = new TextView(ctx);
            h.setTypeface(Fonts.display, Typeface.BOLD);
            h.setTextColor(theme.textColor);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            h.setLetterSpacing(0.12f);
            headRow.addView(h);
            View rule = new View(ctx);
            rule.setBackground(Glass.divider(Glass.withAlpha(theme.accentColor, 0x77)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(16), 1f);
            rlp.setMargins(dp(18), 0, dp(6), 0);
            headRow.addView(rule, rlp);
            colv.addView(headRow);

            RecyclerView r = new RecyclerView(ctx);
            if ("grid".equals(theme.listStyle)) {
                r.setLayoutManager(new GridLayoutManager(ctx, 5));
            } else {
                r.setLayoutManager(new LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false));
            }
            r.setClipToPadding(false);
            r.setPadding(0, dp(6), 0, dp(10));
            colv.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(colv, h, rule, r);
        }

        @Override public void onBindViewHolder(@NonNull VH vh, int pos) {
            Section s = data.get(pos);
            vh.header.setText(s.title.toUpperCase(Locale.getDefault()));
            vh.row.setAdapter(new TileAdapter(s.tiles));
        }
        @Override public int getItemCount() { return data.size(); }
    }

    class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
        final List<Tile> tiles;
        TileAdapter(List<Tile> t) { tiles = t; }

        class VH extends RecyclerView.ViewHolder {
            FrameLayout card; ImageView img; TextView glyph; TextView label;
            VH(View v, FrameLayout c, ImageView i, TextView g, TextView l) { super(v); card = c; img = i; glyph = g; label = l; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            Context c = p.getContext();
            LinearLayout wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int w = dp(theme.tileWidthDp);
            RecyclerView.LayoutParams wlp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.setMargins(dp(10), dp(8), dp(10), dp(8));
            wrap.setLayoutParams(wlp);

            FrameLayout card = new FrameLayout(c);
            card.setLayoutParams(new LinearLayout.LayoutParams(w, dp(theme.tileHeightDp)));
            float rad = dp(theme.cornerRadiusDp);
            card.setBackground(Glass.card(theme.surfaceColor, accentNow, false, rad));
            Glass.clipRound(card, rad);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);

            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView glyph = new TextView(c);   // for inputs/settings: a centered gothic label
            glyph.setTypeface(Fonts.display, Typeface.BOLD);
            glyph.setTextColor(theme.textColor);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            glyph.setGravity(Gravity.CENTER);
            glyph.setLetterSpacing(0.05f);
            card.addView(glyph, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView label = new TextView(c);
            label.setTypeface(Fonts.body);
            label.setTextColor(theme.textColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setMaxLines(1);
            label.setPadding(dp(6), dp(8), dp(6), 0);
            label.setGravity(Gravity.START);

            wrap.addView(card);
            wrap.addView(label);
            return new VH(wrap, card, img, glyph, label);
        }

        @Override public void onBindViewHolder(@NonNull VH vh, int pos) {
            final Tile t = tiles.get(pos);
            final float rad = dp(theme.cornerRadiusDp);
            vh.label.setText(t.label);
            if (t.kind == Tile.Kind.APP && t.icon != null) {
                vh.img.setImageDrawable(t.icon);
                vh.glyph.setText("");
            } else {
                vh.img.setImageDrawable(null);
                vh.glyph.setText(t.label);   // gothic centered label for inputs/settings
            }
            vh.card.setBackground(Glass.card(theme.surfaceColor, accentNow, false, rad));
            vh.card.setOnClickListener(v -> {
                try {
                    usage.record(usageKey(t));   // learn what gets used
                    if (t.launch != null) startActivity(t.launch);
                } catch (Exception ignored) {}
            });
            vh.card.setOnFocusChangeListener((v, has) -> {
                int ring = (theme.weather && curWx != null && curWx.isFreezing())
                        ? Color.parseColor("#9FE8FF") : accentNow;
                v.setBackground(Glass.card(theme.surfaceColor, ring, has, rad));
                if (theme.focusScale) {
                    float scv = has ? theme.focusScaleAmount : 1f;
                    v.animate().scaleX(scv).scaleY(scv).setDuration(theme.focusAnimMs).start();
                    v.setElevation(has ? dp(10) : 0);
                }
                vh.label.setTextColor(has ? accentNow : theme.textColor);
            });
        }
        @Override public int getItemCount() { return tiles.size(); }
    }
}
