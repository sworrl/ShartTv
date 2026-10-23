package com.sharttv.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import com.sharttv.launcher.ui.Semantic;
import com.sharttv.launcher.weather.Loc;
import com.sharttv.launcher.weather.TimeSky;
import com.sharttv.launcher.weather.WeatherEngine;
import com.sharttv.launcher.weather.WeatherView;
import com.sharttv.launcher.weather.Wx;
import java.util.Locale;

/**
 * The full "WEATHER NERD LEVEL" dashboard, on its own screen. The home launcher shows only a compact
 * glance card; opening it lands here: animated weather backdrop, big current conditions, a 16-metric
 * grid, a large Windy radar, and the multi-day forecast — every value semantically colored. Fits
 * the panel exactly; nothing scrolls.
 */
public class WeatherActivity extends Activity {

    private Theme theme;
    private WeatherView weatherView;
    private WeatherEngine engine;
    private Wx curWx;
    private Loc curLoc;
    private android.webkit.WebView radar;
    private double radarLat = Double.NaN, radarLon = Double.NaN;

    private TextView bigTemp, bigCond, bigPlace, bigHilo, clock;
    private LinearLayout forecastStrip;
    private final java.util.LinkedHashMap<String, TextView> metricViews = new java.util.LinkedHashMap<>();
    private static final String[] METRIC_KEYS = {
        "Feels","Dew Pt","Humidity","Pressure","Wind","Gust","UV","UV Max",
        "Visibility","Cloud L/M/H","CAPE","Precip %","AQI","PM2.5","Sunrise","Sunset"
    };

    private final android.os.Handler sky = new android.os.Handler();
    private final Runnable skyTick = new Runnable() {
        @Override public void run() {
            TimeSky.Sky s = TimeSky.now(curLoc);
            if (weatherView != null) weatherView.setTint(TimeSky.weatherAdjust(s.tint, curWx));
            if (clock != null) clock.setText(new java.text.SimpleDateFormat("h:mm", Locale.getDefault()).format(new java.util.Date()));
            sky.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Fonts.load(this);
        Glass.init(this);
        theme = Theme.load(this);
        Glass.ACCENT2 = theme.accentColor2;
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(theme.bgColor));
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (engine == null) {
            engine = new WeatherEngine(this);
            engine.setListener((wx, loc) -> {
                curWx = wx; curLoc = loc;
                if (weatherView != null) weatherView.setWeather(wx);
                bind();
            });
        }
        engine.start();
        if (weatherView != null) weatherView.startAnim();
        sky.removeCallbacks(skyTick); sky.post(skyTick);
    }

    @Override protected void onPause() {
        super.onPause();
        sky.removeCallbacks(skyTick);
        if (engine != null) engine.stop();
        if (weatherView != null) weatherView.stopAnim();
    }

    /**
     * One fixed screen, no scrolling: the panel is 960x540 dp, so everything is laid out with weights
     * to fill exactly that. Header on top, forecast strip on the bottom, and between them current
     * conditions + the 16-metric grid (left) beside the radar (right).
     */
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(theme.bgColor);

        weatherView = new WeatherView(this);
        root.addView(weatherView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (curWx != null) weatherView.setWeather(curWx);
        weatherView.setTint(TimeSky.weatherAdjust(TimeSky.now(curLoc).tint, curWx));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(36), dp(18), dp(36), dp(18));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // header: title + clock
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setTypeface(Fonts.display, Typeface.BOLD);
        title.setText("WEATHER");
        title.setTextColor(theme.textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setLetterSpacing(0.18f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clock = new TextView(this);
        clock.setTypeface(Fonts.display, Typeface.BOLD);
        clock.setTextColor(theme.textDimColor);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.addView(clock);
        col.addView(head);

        // body: left (current + metrics) beside right (radar); takes all height the header and
        // forecast leave
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(10);
        col.addView(body, blp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));

        // current conditions: big temp, then condition / H-L / place
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(20)));
        Glass.clipRound(card, dp(20));
        card.setTag(WeatherView.OCCLUDE);
        LinearLayout cc = new LinearLayout(this);
        cc.setOrientation(LinearLayout.HORIZONTAL);
        cc.setGravity(Gravity.CENTER_VERTICAL);
        cc.setPadding(dp(20), dp(10), dp(20), dp(10));
        bigTemp = new TextView(this);
        bigTemp.setTypeface(Fonts.display, Typeface.BOLD);
        bigTemp.setTextColor(theme.textColor);
        bigTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 58);
        bigTemp.setIncludeFontPadding(false);
        bigTemp.setPadding(0, 0, dp(18), 0);
        cc.addView(bigTemp);
        LinearLayout curc = new LinearLayout(this);
        curc.setOrientation(LinearLayout.VERTICAL);
        bigCond = new TextView(this);
        bigCond.setTypeface(Fonts.display);
        bigCond.setTextColor(theme.accentColor);
        bigCond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bigCond.setSingleLine(true); bigCond.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bigHilo = new TextView(this);
        bigHilo.setTypeface(Fonts.body);
        bigHilo.setTextColor(theme.textColor);
        bigHilo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bigPlace = new TextView(this);
        bigPlace.setTypeface(Fonts.body);
        bigPlace.setTextColor(theme.textDimColor);
        bigPlace.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bigPlace.setSingleLine(true); bigPlace.setEllipsize(android.text.TextUtils.TruncateAt.END);
        curc.addView(bigCond); curc.addView(bigHilo); curc.addView(bigPlace);
        cc.addView(curc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(cc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // metric grid: 4x4, rows share whatever height is left so it always fits
        FrameLayout gcard = new FrameLayout(this);
        gcard.setBackground(Glass.card(theme.surfaceColor, theme.accentColor2, false, dp(20)));
        Glass.clipRound(gcard, dp(20));
        gcard.setTag(WeatherView.OCCLUDE);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(14), dp(8), dp(14), dp(8));
        metricViews.clear();
        int cols = 4;
        for (int base = 0; base < METRIC_KEYS.length; base += cols) {
            LinearLayout mrow = new LinearLayout(this);
            mrow.setOrientation(LinearLayout.HORIZONTAL);
            mrow.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = base; i < base + cols && i < METRIC_KEYS.length; i++) {
                String key = METRIC_KEYS[i];
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(4), 0, dp(4), 0);
                TextView val = new TextView(this);
                val.setTypeface(Fonts.display, Typeface.BOLD);
                val.setTextColor(theme.textColor);
                val.setSingleLine(true);
                val.setIncludeFontPadding(false);
                // long values ("100/100/100", "1013 hPa") shrink to fit the column instead of clipping
                val.setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, TypedValue.COMPLEX_UNIT_SP);
                val.setText("—");
                TextView lab = new TextView(this);
                lab.setTypeface(Fonts.body);
                lab.setTextColor(theme.textDimColor);
                lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                lab.setLetterSpacing(0.05f);
                lab.setSingleLine(true);
                lab.setText(key.toUpperCase(Locale.getDefault()));
                cell.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
                cell.addView(lab);
                metricViews.put(key, val);
                mrow.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(mrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        gcard.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = dp(10);
        left.addView(gcard, glp);

        // radar fills the right column
        final FrameLayout radarWrap = new FrameLayout(this);
        Glass.clipRound(radarWrap, dp(20));
        radarWrap.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(20)));
        radarWrap.setTag(WeatherView.OCCLUDE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.setMarginStart(dp(12));
        body.addView(radarWrap, rlp);
        sky.post(() -> {
            if (isFinishing()) return;
            radar = new android.webkit.WebView(this);
            android.webkit.WebSettings ws = radar.getSettings();
            ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
            radar.setBackgroundColor(Color.TRANSPARENT);
            radarWrap.addView(radar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            updateRadar();
        });

        // forecast strip along the bottom
        FrameLayout fcard = new FrameLayout(this);
        fcard.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(20)));
        Glass.clipRound(fcard, dp(20));
        fcard.setTag(WeatherView.OCCLUDE);
        forecastStrip = new LinearLayout(this);
        forecastStrip.setOrientation(LinearLayout.HORIZONTAL);
        forecastStrip.setGravity(Gravity.CENTER_VERTICAL);
        forecastStrip.setPadding(dp(14), dp(8), dp(14), dp(8));
        fcard.addView(forecastStrip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(10);
        col.addView(fcard, flp);

        setContentView(root);
        bind();
    }

    private void bind() {
        boolean have = curWx != null && curWx.valid();
        if (bigTemp == null) return;
        if (!have) {
            bigTemp.setText("—"); bigCond.setText("Waiting for a location fix");
            bigHilo.setText(""); bigPlace.setText("");
            return;
        }
        bigTemp.setText(f(curWx.tempC) + "°");
        bigTemp.setTextColor(Semantic.temp(f(curWx.tempC)));
        bigCond.setText(curWx.icon + "  " + curWx.name);
        bigCond.setTextColor(theme.accentColor);
        if (!Double.isNaN(curWx.tempMaxC) && !Double.isNaN(curWx.tempMinC))
            bigHilo.setText("H " + f(curWx.tempMaxC) + "°    L " + f(curWx.tempMinC) + "°");
        bigPlace.setText(WeatherEngine.placeLine(this, curWx, curLoc));

        setMC("Feels", f(curWx.feelsC) + "°", Semantic.temp(f(curWx.feelsC)));
        setMC("Dew Pt", Double.isNaN(curWx.dewC) ? "—" : f(curWx.dewC) + "°", Double.isNaN(curWx.dewC) ? theme.textColor : Semantic.dew(f(curWx.dewC)));
        setMC("Humidity", curWx.humidity > 0 ? curWx.humidity + "%" : "—", curWx.humidity > 0 ? Semantic.humidity(curWx.humidity) : theme.textColor);
        setMC("Pressure", Double.isNaN(curWx.pressureHpa) ? "—" : Math.round(curWx.pressureHpa) + " hPa", Double.isNaN(curWx.pressureHpa) ? theme.textColor : Semantic.pressure(curWx.pressureHpa));
        int windMph = (int) Math.round(curWx.windKmh * 0.621);
        setMC("Wind", compass(curWx.windDirDeg) + " " + windMph + " mph", Semantic.wind(windMph));
        if (Double.isNaN(curWx.windGust)) setMC("Gust", "—", theme.textColor);
        else { int g = (int) Math.round(curWx.windGust * 0.621); setMC("Gust", g + " mph", Semantic.wind(g)); }
        setMC("UV", Double.isNaN(curWx.uvIndex) ? "—" : String.valueOf((int) Math.round(curWx.uvIndex)), Double.isNaN(curWx.uvIndex) ? theme.textColor : Semantic.uv(curWx.uvIndex));
        setMC("UV Max", Double.isNaN(curWx.uvMax) ? "—" : String.valueOf((int) Math.round(curWx.uvMax)), Double.isNaN(curWx.uvMax) ? theme.textColor : Semantic.uv(curWx.uvMax));
        double visMi = curWx.visibilityM / 1609.0;
        setMC("Visibility", Double.isNaN(curWx.visibilityM) ? "—" : Math.round(visMi) + " mi", Double.isNaN(curWx.visibilityM) ? theme.textColor : Semantic.visibility(visMi));
        int cloudAvg = curWx.cloudLow < 0 ? 0 : (curWx.cloudLow + curWx.cloudMid + curWx.cloudHigh) / 3;
        setMC("Cloud L/M/H", curWx.cloudLow < 0 ? "—" : curWx.cloudLow + "/" + curWx.cloudMid + "/" + curWx.cloudHigh, curWx.cloudLow < 0 ? theme.textColor : Semantic.cloud(cloudAvg));
        setMC("CAPE", Double.isNaN(curWx.cape) ? "—" : Math.round(curWx.cape) + "", Double.isNaN(curWx.cape) ? theme.textColor : Semantic.cape(curWx.cape));
        setMC("Precip %", Double.isNaN(curWx.precipProb) ? "—" : (int) curWx.precipProb + "%", Double.isNaN(curWx.precipProb) ? theme.textColor : Semantic.precip(curWx.precipProb));
        setMC("AQI", curWx.aqi < 0 ? "—" : String.valueOf(curWx.aqi), curWx.aqi < 0 ? theme.textColor : Semantic.aqi(curWx.aqi));
        setMC("PM2.5", Double.isNaN(curWx.pm25) ? "—" : String.format(Locale.US, "%.0f", curWx.pm25), Double.isNaN(curWx.pm25) ? theme.textColor : Semantic.pm25(curWx.pm25));
        setMC("Sunrise", curWx.sunriseSec < 0 ? "—" : hm(curWx.sunriseSec), Semantic.sun());
        setMC("Sunset", curWx.sunsetSec < 0 ? "—" : hm(curWx.sunsetSec), Semantic.sun());
        buildForecast();
        updateRadar();
    }

    private void setMC(String k, String v, int color) { TextView t = metricViews.get(k); if (t != null) { t.setText(v); t.setTextColor(color); } }

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
        if (curWx == null || curWx.fcDay == null) return;
        for (int i = 0; i < curWx.fcDay.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(10), dp(2), dp(10), dp(2));
            TextView day = new TextView(this);
            day.setTypeface(Fonts.display, Typeface.BOLD);
            day.setTextColor(theme.textColor);
            day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            day.setGravity(Gravity.CENTER);
            day.setText(curWx.fcDay[i].toUpperCase(Locale.getDefault()));
            TextView g = new TextView(this);
            g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            g.setGravity(Gravity.CENTER);
            g.setText(Wx.glyph(curWx.fcCode[i]));
            LinearLayout hl = new LinearLayout(this); hl.setOrientation(LinearLayout.HORIZONTAL); hl.setGravity(Gravity.CENTER);
            TextView hiT = new TextView(this); hiT.setTypeface(Fonts.body, Typeface.BOLD); hiT.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
            TextView loT = new TextView(this); loT.setTypeface(Fonts.body); loT.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
            TextView sl = new TextView(this); sl.setText(" / "); sl.setTextColor(theme.textDimColor); sl.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
            if (!Double.isNaN(curWx.fcMax[i])) { hiT.setText(f(curWx.fcMax[i]) + "°"); hiT.setTextColor(Semantic.temp(f(curWx.fcMax[i]))); }
            if (!Double.isNaN(curWx.fcMin[i])) { loT.setText(f(curWx.fcMin[i]) + "°"); loT.setTextColor(Semantic.temp(f(curWx.fcMin[i]))); }
            hl.addView(hiT); hl.addView(sl); hl.addView(loT);
            cell.addView(day); cell.addView(g); cell.addView(hl);
            forecastStrip.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
}
