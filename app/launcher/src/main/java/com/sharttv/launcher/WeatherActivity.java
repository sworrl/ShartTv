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
 * grid, our own animated radar, and the multi-day forecast — every value semantically colored. Fits
 * the panel exactly; nothing scrolls.
 */
public class WeatherActivity extends Activity {

    private Theme theme;
    private WeatherView weatherView;
    private WeatherEngine engine;
    private Wx curWx;
    private Loc curLoc;
    private com.sharttv.launcher.weather.RadarView radar;

    private TextView bigTemp, bigCond, bigPlace, bigHilo, clock;
    private LinearLayout forecastStrip;
    private final java.util.LinkedHashMap<String, TextView> metricViews = new java.util.LinkedHashMap<>();
    private static final String[] METRIC_KEYS = {
        "Feels","Dew Pt","Humidity","Pressure","Sfc Press",
        "Wind","Gust","UV","UV Max","Visibility",
        "Cloud","Cloud L/M/H","CAPE","Precip %","Rain Now",
        "AQI","PM2.5","Sunrise","Sunset","Daylight"
    };
    private TextView moonLine, todayLine;
    private com.sharttv.launcher.weather.HourlyChart hourly;

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
        if (radar != null) radar.start();
        sky.removeCallbacks(skyTick); sky.post(skyTick);
    }

    @Override protected void onPause() {
        super.onPause();
        sky.removeCallbacks(skyTick);
        if (engine != null) engine.stop();
        if (weatherView != null) weatherView.stopAnim();
        if (radar != null) radar.stop();
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
        bigPlace.setMaxLines(2); bigPlace.setEllipsize(android.text.TextUtils.TruncateAt.END);
        curc.addView(bigCond); curc.addView(bigHilo); curc.addView(bigPlace);
        cc.addView(curc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // right edge of the card: moon + today's rain/daylight
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        moonLine = new TextView(this);
        moonLine.setTypeface(Fonts.body, Typeface.BOLD);
        moonLine.setTextColor(theme.textColor);
        moonLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        moonLine.setGravity(Gravity.END);
        todayLine = new TextView(this);
        todayLine.setTypeface(Fonts.body);
        todayLine.setTextColor(theme.textDimColor);
        todayLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        todayLine.setGravity(Gravity.END);
        todayLine.setLineSpacing(0, 1.1f);
        side.addView(moonLine); side.addView(todayLine);
        cc.addView(side);
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
        int cols = 5;
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
                val.setMaxLines(1);   // not setSingleLine: that defeats autosize
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
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.setMarginStart(dp(12));
        body.addView(right, rlp);
        right.addView(radarWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // next 24 hours under the radar
        FrameLayout hcard = new FrameLayout(this);
        hcard.setBackground(Glass.card(theme.surfaceColor, theme.accentColor2, false, dp(20)));
        Glass.clipRound(hcard, dp(20));
        hcard.setTag(WeatherView.OCCLUDE);
        hcard.setPadding(dp(8), dp(6), dp(8), dp(4));
        hourly = new com.sharttv.launcher.weather.HourlyChart(this);
        hourly.setHours(24);
        hourly.setFonts(Fonts.display, Fonts.body);
        hourly.setColors(theme.textColor, theme.textDimColor, theme.accentColor);
        hcard.addView(hourly, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118));
        hlp.topMargin = dp(10);
        right.addView(hcard, hlp);
        radar = new com.sharttv.launcher.weather.RadarView(this);
        radar.setPalette(theme.textColor, theme.surfaceColor, theme.accentColor);
        radar.setFonts(Fonts.display, Fonts.body);
        radarWrap.addView(radar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        radar.start();
        updateRadar();

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
        setMC("Sfc Press", Double.isNaN(curWx.surfacePressureHpa) ? "—" : String.format(Locale.US, "%.2f\"", curWx.surfacePressureHpa * 0.02953),
                Double.isNaN(curWx.surfacePressureHpa) ? theme.textColor : Semantic.neutral());
        setMC("Cloud", Math.round(curWx.cloudPct) + "%", Semantic.cloud(curWx.cloudPct));
        double inNow = curWx.precipMm / 25.4;
        setMC("Rain Now", inNow <= 0 ? "0\"" : String.format(Locale.US, "%.2f\"", inNow), Semantic.precip(Math.min(100, inNow * 400)));
        if (curWx.sunriseSec >= 0 && curWx.sunsetSec > curWx.sunriseSec) {
            int dl = (curWx.sunsetSec - curWx.sunriseSec) / 60;
            setMC("Daylight", (dl / 60) + "h " + (dl % 60) + "m", Semantic.sun());
        } else setMC("Daylight", "—", theme.textColor);
        if (moonLine != null) {
            double[] mp = moonPhase(System.currentTimeMillis());
            moonLine.setText(MOON_GLYPH[(int) mp[0]] + "  " + MOON_NAME[(int) mp[0]] + "  " + Math.round(mp[1] * 100) + "%");
            StringBuilder tb = new StringBuilder();
            if (!Double.isNaN(curWx.precipProb)) tb.append("\u2602 ").append((int) curWx.precipProb).append("% rain today");
            if (curWx.sunriseSec >= 0) tb.append(tb.length() > 0 ? "\n" : "").append("\u2600 ").append(hm(curWx.sunriseSec)).append(" \u2192 ").append(hm(curWx.sunsetSec));
            todayLine.setText(tb);
        }
        if (hourly != null) hourly.setWeather(curWx);
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
        radar.setLocation(curLoc.lat, curLoc.lon);
    }

    private static final String[] MOON_GLYPH = {"\uD83C\uDF11","\uD83C\uDF12","\uD83C\uDF13","\uD83C\uDF14","\uD83C\uDF15","\uD83C\uDF16","\uD83C\uDF17","\uD83C\uDF18"};
    private static final String[] MOON_NAME = {"New moon","Waxing crescent","First quarter","Waxing gibbous","Full moon","Waning gibbous","Last quarter","Waning crescent"};

    /** {phase index 0..7, illuminated fraction 0..1} from the mean synodic month. */
    private static double[] moonPhase(long ms) {
        double days = (ms - 947182440000L) / 86400000.0;   // since the new moon of 2000-01-06 18:14 UTC
        double ph = ((days % 29.530588853) + 29.530588853) % 29.530588853 / 29.530588853;
        return new double[]{(int) Math.floor(ph * 8 + 0.5) % 8, (1 - Math.cos(2 * Math.PI * ph)) / 2};
    }

    /** Six days: name, icon, high/low with a range bar against the week's spread, rain chance, wind. */
    private void buildForecast() {
        if (forecastStrip == null) return;
        forecastStrip.removeAllViews();
        if (curWx == null || curWx.fcDay == null) return;
        double wlo = Double.MAX_VALUE, whi = -Double.MAX_VALUE;
        for (int i = 0; i < curWx.fcDay.length; i++) {
            if (!Double.isNaN(curWx.fcMin[i])) wlo = Math.min(wlo, curWx.fcMin[i]);
            if (!Double.isNaN(curWx.fcMax[i])) whi = Math.max(whi, curWx.fcMax[i]);
        }
        for (int i = 0; i < curWx.fcDay.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(6), dp(2), dp(6), dp(2));
            TextView g = new TextView(this);
            g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            g.setText(Wx.glyph(curWx.fcCode[i]));
            g.setPadding(0, 0, dp(6), 0);
            cell.addView(g);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView day = new TextView(this);
            day.setTypeface(Fonts.display, Typeface.BOLD);
            day.setTextColor(theme.textColor);
            day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            day.setText(curWx.fcDay[i].toUpperCase(Locale.getDefault()));
            info.addView(day);
            android.text.SpannableStringBuilder hl = new android.text.SpannableStringBuilder();
            if (!Double.isNaN(curWx.fcMax[i])) span(hl, f(curWx.fcMax[i]) + "°", Semantic.temp(f(curWx.fcMax[i])));
            span(hl, " / ", theme.textDimColor);
            if (!Double.isNaN(curWx.fcMin[i])) span(hl, f(curWx.fcMin[i]) + "°", Semantic.temp(f(curWx.fcMin[i])));
            TextView hlv = new TextView(this);
            hlv.setTypeface(Fonts.body, Typeface.BOLD);
            hlv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hlv.setText(hl);
            info.addView(hlv);
            // range bar: where this day's low..high sits within the week
            RangeBar bar = new RangeBar(this, wlo, whi, curWx.fcMin[i], curWx.fcMax[i]);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
            bp.topMargin = dp(2);
            info.addView(bar, bp);
            android.text.SpannableStringBuilder ex = new android.text.SpannableStringBuilder();
            int pop = curWx.fcPop == null ? -1 : curWx.fcPop[i];
            if (pop >= 0) span(ex, "\u2602" + pop + "%", Semantic.precip(pop));
            double wk = curWx.fcWindKmh == null ? Double.NaN : curWx.fcWindKmh[i];
            if (!Double.isNaN(wk)) { int mph = (int) Math.round(wk * 0.621); span(ex, "  \u2248" + mph + "mph", Semantic.wind(mph)); }
            TextView exv = new TextView(this);
            exv.setTypeface(Fonts.body);
            exv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            exv.setSingleLine(true);
            exv.setText(ex);
            info.addView(exv);
            cell.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            forecastStrip.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static void span(android.text.SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** A low..high bar placed within the week's overall range, colored cool to warm. */
    private static class RangeBar extends View {
        final double lo, hi, a, b;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        RangeBar(android.content.Context c, double lo, double hi, double a, double b) { super(c); this.lo = lo; this.hi = hi; this.a = a; this.b = b; }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(0x33FFFFFF);
            cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            if (Double.isNaN(a) || Double.isNaN(b) || hi <= lo) return;
            float x0 = (float) ((a - lo) / (hi - lo) * w), x1 = (float) ((b - lo) / (hi - lo) * w);
            p.setShader(new android.graphics.LinearGradient(x0, 0, Math.max(x1, x0 + 1), 0,
                    Semantic.temp(f(a)), Semantic.temp(f(b)), android.graphics.Shader.TileMode.CLAMP));
            cv.drawRoundRect(x0, 0, Math.max(x1, x0 + h), h, h / 2, h / 2, p);
            p.setShader(null);
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
