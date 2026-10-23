package com.sharttv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
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
import com.sharttv.launcher.ui.CardFx;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import com.sharttv.launcher.ui.Semantic;
import com.sharttv.launcher.ui.WeatherSkin;
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
 * Shart TV launcher home. Ad-free, rich, liquid-glass + classical/gothic.
 *
 * Layout honors the real panel: 1920x1080 @ density 320 == 960x540 dp. The whole home is ONE
 * vertical scroller (stock Google TV pattern): a fixed glass top bar, then hero -> forecast ->
 * app/input/settings shelves as rows. Pressing DOWN scrolls the tall weather hero up off screen so
 * the shelves get the full panel, instead of the hero starving them into a sliver.
 */
public class LauncherActivity extends Activity {

    private Theme theme;
    private FrameLayout root;
    private final List<Tile> inputs = new ArrayList<>();
    private BroadcastReceiver inputChanged, packagesChanged;
    private UsageStore usage;

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
    private TextView barDate, barWx;
    private com.sharttv.launcher.ui.HourClock barClock;
    // compact weather glance card on the home (full nerd dashboard lives in WeatherActivity)
    private TextView glanceTemp, glanceIcon, glanceCond, glanceHilo, glancePlace;
    private final java.util.LinkedHashMap<String, TextView> miniMetrics = new java.util.LinkedHashMap<>();
    private com.sharttv.launcher.weather.HourlyChart glanceHourly;
    private LinearLayout miniForecast;
    private int accentNow;

    // perf: cache the enumerated apps so the one slow pass (icon decode) only happens on a cold build;
    // only fully rebuild the tree on a real theme change, never on a plain resume.
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private HomeAdapter homeAdapter;
    private List<Tile> cachedApps;
    private boolean uiBuilt;
    private String themeSig = "";

    private String themeSig(Theme t) {
        return Profiles.currentId(this) + "|" + t.bgColor + "|" + t.accentColor + "|" + t.surfaceColor + "|" + t.textColor + "|" + t.textDimColor
                + "|" + t.tileWidthDp + "|" + t.tileHeightDp + "|" + t.cornerRadiusDp + "|" + t.listStyle
                + "|" + t.iconStyle + "|" + t.appArt + "|" + t.weather + "|" + t.radar + "|" + t.showForecast + "|" + t.showMostUsed
                + "|" + t.autoPromote + "|" + t.focusScale + "|" + t.clockStyle + "|" + t.clockFormat + "|" + (t.bgImagePath == null ? "" : t.bgImagePath);
    }


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

    static class Section {
        String title; List<Tile> tiles; boolean recent;
        Section(String t, List<Tile> x){title=t;tiles=x;}
    }

    // one flat row list for the single vertical scroller
    private static final int ROW_HERO = 0, ROW_FORECAST = 1, ROW_SECTION = 2;
    static class Row { int type; Section section; Row(int t, Section s){type=t;section=s;} }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Fonts.load(this);
        Glass.init(this);
        usage = new UsageStore(this);
        theme = Theme.load(this);
        themeSig = themeSig(theme);
        // the weather follows the TV's network location (asked once; denying falls back to the seed)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION}, 7);
        accentNow = theme.accentColor;
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(theme.bgColor));
        buildUi();
    }

    /** True while the home is resumed; the HOME redirect ignores launcherx redraws behind us. */
    static volatile boolean inFront;

    @Override protected void onResume() {
        super.onResume();
        inFront = true;
        usage = new UsageStore(this);   // the profile may have changed
        Theme nt = Theme.load(this);
        String sig = themeSig(nt);
        boolean changed = !uiBuilt || !sig.equals(themeSig);
        theme = nt; themeSig = sig; accentNow = theme.accentColor;
        focusRecent = !backFromOwnScreen;   // coming home from an app: land on the app that was just running
        backFromOwnScreen = false;
        if (changed) {
            cachedApps = null;   // theme affects icon style etc. — reload
            buildUi();
        } else {
            refreshRows();       // same tree, re-ranked rows (recents/usage moved while we were away)
        }
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
            if (weatherView != null) weatherView.startAnim();
        }
        sky.removeCallbacks(skyTick);
        sky.post(skyTick);
    }

    @Override protected void onPause() {
        super.onPause();
        inFront = false;
        sky.removeCallbacks(skyTick);
        if (engine != null) engine.stop();
        if (weatherView != null) weatherView.stopAnim();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // single source of truth for horizontal gutters, so every block lines up
    private int edge() { return dp(40); }

    private void buildUi() {
        Glass.ACCENT2 = theme.accentColor2;   // gradient glass edges
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

        // fixed glass top bar (clock + live weather readout)
        col.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // one vertical scroller: hero -> forecast -> shelves. Paint the chrome IMMEDIATELY with just
        // the fixed rows; the app/input/settings shelves (which load icons) come in off-thread so the
        // first frame is never blocked on PackageManager icon decoding.
        RecyclerView home = new RecyclerView(this);
        home.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        home.setPadding(edge(), dp(4), edge(), dp(24));
        home.setClipToPadding(false);
        home.setItemViewCacheSize(8);

        List<Row> rows = new ArrayList<>();
        if (theme.weather) rows.add(new Row(ROW_HERO, null));   // forecast strip now lives inside the hero
        for (Section s : buildSections()) rows.add(new Row(ROW_SECTION, s));
        homeAdapter = new HomeAdapter(rows);
        home.setAdapter(homeAdapter);
        col.addView(home, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        uiBuilt = true;
        registerInputChanged();
        registerPackagesChanged();
        updateChrome();
        if (theme.weather) updateWeatherUi();
    }

    private boolean focusRecent, backFromOwnScreen;

    /** Opening our own screens (weather, appearance, profiles) shouldn't yank focus to Recent on return. */
    @Override public void startActivity(Intent intent) {
        if (intent.getComponent() != null && getPackageName().equals(intent.getComponent().getPackageName()))
            backFromOwnScreen = true;
        super.startActivity(intent);
    }

    /** Re-rank the rows without touching the window tree or reloading app icons (unless an app was
     *  installed/removed, which drops the cache). */
    private void refreshRows() {
        if (homeAdapter == null) return;
        List<Row> rows = new ArrayList<>();
        if (theme.weather) rows.add(new Row(ROW_HERO, null));
        for (Section s : buildSections()) rows.add(new Row(ROW_SECTION, s));
        homeAdapter.data.clear();
        homeAdapter.data.addAll(rows);
        homeAdapter.notifyDataSetChanged();
    }

    // Hione apps + learned most-used, merged into ONE featured strip that sits beside the weather card
    // (separate single-tile rows each wasted a full row of empty space).
    private List<Tile> featured = new ArrayList<>();

    private static boolean isHione(Tile t) {
        String k = t.key == null ? "" : t.key.toLowerCase(Locale.US);
        String l = t.label == null ? "" : t.label.toLowerCase(Locale.US);
        return k.startsWith("com.hione") || k.contains(".hione.") || l.contains("hione");
    }

    /** Inputs + Apps shelves, and the featured strip. Apps (the slow part — icon decode) are cached
     *  after the first build, so this is only costly on a cold start, never on resume. Settings live
     *  in the top bar, like stock Google TV. */
    private List<Section> buildSections() {
        inputs.clear();
        inputs.addAll(Repo.inputs(this, theme));
        if (cachedApps == null) cachedApps = Repo.apps(this, theme);
        List<Tile> apps = new ArrayList<>(cachedApps);

        if (theme.autoPromote) {
            UsageStore.KeyOf<Tile> ko = LauncherActivity::usageKey;
            usage.sortByScore(inputs, ko);
            usage.sortByScore(apps, ko);
        }

        featured = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Tile t : apps) if (isHione(t) && seen.add(usageKey(t))) featured.add(t);
        if (theme.showMostUsed && usage.hasData()) {
            java.util.Map<String, Tile> byKey = new java.util.HashMap<>();
            for (Tile t : inputs) byKey.put(usageKey(t), t);
            for (Tile t : apps) byKey.put(usageKey(t), t);
            for (String k : usage.topKeys(8)) {
                Tile t = byKey.get(k);
                if (t != null && seen.add(k)) featured.add(t);
            }
        }

        List<Section> sections = new ArrayList<>();
        // Recent: newest first, so the app that was just running is the first tile (and gets focus).
        java.util.Map<String, Tile> all = new java.util.HashMap<>();
        for (Tile t : inputs) all.put(usageKey(t), t);
        for (Tile t : apps) all.put(usageKey(t), t);
        List<Tile> recent = new ArrayList<>();
        for (String k : usage.recentKeys(12)) {
            Tile t = all.get(k);
            if (t != null && recent.size() < 8) recent.add(t);
        }
        if (!recent.isEmpty()) {
            Section rs = new Section(getString(R.string.recent), recent);
            rs.recent = true;
            sections.add(rs);
        }
        if (!inputs.isEmpty()) sections.add(new Section(getString(R.string.inputs), inputs));
        sections.add(new Section(getString(R.string.apps), apps));
        return sections;
    }

    // ---------- top bar ----------
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(edge(), dp(9), edge(), dp(7));

        // clock + date on one baseline to keep the bar slim
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        barClock = new com.sharttv.launcher.ui.HourClock(this, 22);   // same animated clock as the hour chime
        barClock.setStyle(theme.clockStyle);
        barClock.setFormat(theme.clockFormat);
        barClock.setPalette(theme.textColor, theme.surfaceColor);
        barDate = new TextView(this);
        barDate.setTypeface(Fonts.body);
        barDate.setTextColor(theme.textDimColor);
        barDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        barDate.setLetterSpacing(0.12f);
        barDate.setPadding(dp(14), dp(3), 0, 0);
        left.addView(barClock); left.addView(barDate);
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        barWx = new TextView(this);
        barWx.setTypeface(Fonts.body);
        barWx.setTextColor(theme.textColor);
        barWx.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        barWx.setGravity(Gravity.END);
        bar.addView(barWx, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // settings as small glass pills (Appearance / System / Network)
        for (final Tile s : Repo.settings(this, theme)) {
            final TextView pill = new TextView(this);
            pill.setTypeface(Fonts.body);
            pill.setTextColor(theme.textColor);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pill.setLetterSpacing(0.06f);
            pill.setText(s.label.toUpperCase(Locale.getDefault()));
            pill.setPadding(dp(12), dp(5), dp(12), dp(5));
            final float pr = dp(14);
            pill.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, pr));
            pill.setFocusable(true);
            pill.setOnFocusChangeListener((v, has) -> {
                v.setBackground(Glass.card(theme.surfaceColor, accentNow, has, pr));
                pill.setTextColor(has ? accentNow : theme.textColor);
                CardFx.focus(v, has, 1.08f, pr, accentNow, theme.focusAnimMs);
            });
            pill.setOnClickListener(v -> CardFx.click(v, pr, accentNow,
                    () -> { try { startActivity(s.launch); } catch (Exception ignored) {} }));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMarginStart(dp(10));
            bar.addView(pill, plp);
        }

        // profile avatar, top-right like stock: opens "Who's watching?"
        Profiles.Profile prof = Profiles.current(this);
        final TextView av = new TextView(this);
        av.setTypeface(Fonts.display, Typeface.BOLD);
        av.setText(prof.initial());
        av.setGravity(Gravity.CENTER);
        av.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        av.setTextColor(theme.bgColor);
        av.setFocusable(true);
        av.setBackground(avatarBg(prof.color, false));
        av.setOnFocusChangeListener((v, has) -> {
            v.setBackground(avatarBg(prof.color, has));
            CardFx.focus(v, has, 1.18f, dp(14), accentNow, theme.focusAnimMs);
        });
        av.setOnClickListener(v -> CardFx.click(v, dp(14), accentNow,
                () -> { try { startActivity(new Intent(this, ProfilesActivity.class)); } catch (Exception ignored) {} }));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(28), dp(28));
        alp.setMarginStart(dp(12));
        bar.addView(av, alp);
        return bar;
    }

    private android.graphics.drawable.GradientDrawable avatarBg(int color, boolean focused) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        if (focused) g.setStroke(dp(2), theme.textColor);
        return g;
    }

    // ---------- hero band: rich weather card (left) + featured tiles (right) ----------
    private View buildHeroBand() {
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setGravity(Gravity.CENTER_VERTICAL);

        View wcard = buildWeatherCard();
        wcard.setTag(WeatherView.OCCLUDE);
        band.addView(wcard, new LinearLayout.LayoutParams(0, dp(196), featured.isEmpty() ? 1f : 1.12f));

        if (!featured.isEmpty()) {
            LinearLayout fcol = new LinearLayout(this);
            fcol.setOrientation(LinearLayout.VERTICAL);
            fcol.setPadding(dp(18), 0, 0, 0);
            TextView fh = new TextView(this);
            fh.setTypeface(Fonts.display, Typeface.BOLD);
            fh.setTextColor(theme.textColor);
            fh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            fh.setLetterSpacing(0.16f);
            fh.setText("FEATURED");
            fh.setPadding(dp(8), 0, 0, dp(2));
            fcol.addView(fh);
            RecyclerView fr = new RecyclerView(this);
            fr.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
            fr.setClipToPadding(false);
            fr.setClipChildren(false);
            fr.setAdapter(new TileAdapter(featured, 190, 107));
            fcol.addView(fr, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            band.addView(fcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return band;
    }

    private View buildWeatherCard() {
        FrameLayout wrap = new FrameLayout(this);
        final float rad = dp(22);
        wrap.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, rad));
        Glass.clipRound(wrap, rad);
        if (curWx != null && curWx.valid())
            wrap.setForeground(new WeatherSkin(curWx.category, curWx.intensity, curWx.isFreezing(), Glass.D).radius(rad));
        wrap.setFocusable(true);
        wrap.setOnClickListener(v -> CardFx.click(v, rad, accentNow,
                () -> { try { startActivity(new Intent(this, WeatherActivity.class)); } catch (Exception ignored) {} }));
        wrap.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Glass.card(theme.surfaceColor, accentNow, has, rad));
            CardFx.focus(v, has, theme.focusScale ? 1.02f : 1f, rad, accentNow, theme.focusAnimMs);
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(12), dp(20), dp(10));

        // row 1: icon · big temp · condition / H-L / place
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        glanceIcon = new TextView(this);
        glanceIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        glanceIcon.setPadding(0, 0, dp(12), 0);
        top.addView(glanceIcon);
        glanceTemp = new TextView(this);
        glanceTemp.setTypeface(Fonts.display, Typeface.BOLD);
        glanceTemp.setTextColor(theme.textColor);
        glanceTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        glanceTemp.setIncludeFontPadding(false);
        glanceTemp.setPadding(0, 0, dp(16), 0);
        top.addView(glanceTemp);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        glanceCond = new TextView(this);
        glanceCond.setTypeface(Fonts.display);
        glanceCond.setTextColor(theme.accentColor);
        glanceCond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        glanceCond.setMaxLines(1); glanceCond.setEllipsize(TextUtils.TruncateAt.END);
        glanceCond.setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, TypedValue.COMPLEX_UNIT_SP);
        glanceHilo = new TextView(this);
        glanceHilo.setTypeface(Fonts.body);
        glanceHilo.setTextColor(theme.textColor);
        glanceHilo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        glancePlace = new TextView(this);
        glancePlace.setTypeface(Fonts.body);
        glancePlace.setTextColor(theme.textDimColor);
        glancePlace.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        glancePlace.setMaxLines(2); glancePlace.setEllipsize(TextUtils.TruncateAt.END);   // place, then model · updated
        // autosize only shrinks text in a fixed height, so the condition gets one ("Torrential Downpour" fits)
        mid.addView(glanceCond, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        mid.addView(glanceHilo); mid.addView(glancePlace);
        top.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // the next 8 hours fill the card's right side (the whole card opens the full Weather screen)
        glanceHourly = new com.sharttv.launcher.weather.HourlyChart(this);
        glanceHourly.setHours(8);
        glanceHourly.setFonts(Fonts.display, Fonts.body);
        glanceHourly.setColors(theme.textColor, theme.textDimColor, theme.accentColor);
        LinearLayout.LayoutParams ghl = new LinearLayout.LayoutParams(dp(172), dp(66));
        ghl.setMarginStart(dp(6));
        top.addView(glanceHourly, ghl);
        col.addView(top);

        // row 2: six semantic-colored metrics
        LinearLayout mx = new LinearLayout(this);
        mx.setOrientation(LinearLayout.HORIZONTAL);
        miniMetrics.clear();
        for (String k : new String[]{"FEELS", "WIND", "GUST", "HUMID", "DEW", "UV", "PRECIP", "AQI"}) miniMetrics.put(k, miniMetric(mx, k));
        LinearLayout.LayoutParams mxlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mxlp.topMargin = dp(6);
        col.addView(mx, mxlp);

        // row 3: slim forecast
        View rule = new View(this);
        rule.setBackground(Glass.divider(Glass.withAlpha(theme.accentColor2, 0x4A)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        rlp.setMargins(0, dp(4), 0, dp(2));
        col.addView(rule, rlp);
        miniForecast = new LinearLayout(this);
        miniForecast.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(miniForecast, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrap.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        return wrap;
    }

    private TextView miniMetric(LinearLayout parent, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView val = new TextView(this);
        val.setTypeface(Fonts.display, Typeface.BOLD);
        val.setTextColor(theme.textColor);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        val.setSingleLine(true); val.setGravity(Gravity.CENTER);
        val.setText("—");
        TextView lab = new TextView(this);
        lab.setTypeface(Fonts.body);
        lab.setTextColor(theme.textDimColor);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        lab.setLetterSpacing(0.06f); lab.setGravity(Gravity.CENTER);
        lab.setText(label);
        cell.addView(val); cell.addView(lab);
        parent.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return val;
    }

    /** clock + tint, every second (cheap). */
    private void updateChrome() {
        Date now = new Date();
        if (barClock != null) barClock.setAccent(accentNow);   // 24-hour color shift
        if (barDate != null) barDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        if (barWx == null) updateWeatherUi();
    }

    private void mm(String k, String v, int color) {
        TextView t = miniMetrics.get(k);
        if (t != null) { t.setText(v); t.setTextColor(color); }
    }

    /** Update the top-bar readout + the hero weather card (cheap). Full dashboard is WeatherActivity. */
    private void updateWeatherUi() {
        boolean haveWx = curWx != null && curWx.valid();
        if (barWx != null) {
            if (haveWx) {
                barWx.setText(curWx.icon + "   " + f(curWx.tempC) + "°  ·  " + curWx.name);
                barWx.setTextColor(Semantic.temp(f(curWx.tempC)));
            } else { barWx.setText("Shart TV"); barWx.setTextColor(theme.textColor); }
        }
        if (glanceTemp == null) return;
        if (!haveWx) {
            glanceIcon.setText("·"); glanceTemp.setText("—"); glanceCond.setText("Weather");
            glanceHilo.setText(""); glancePlace.setText("waiting for a location fix");
            return;
        }
        glanceIcon.setText(curWx.icon);
        glanceTemp.setText(f(curWx.tempC) + "°");
        glanceTemp.setTextColor(Semantic.temp(f(curWx.tempC)));
        glanceCond.setText(curWx.name);
        glanceCond.setTextColor(accentNow);
        if (!Double.isNaN(curWx.tempMaxC) && !Double.isNaN(curWx.tempMinC))
            glanceHilo.setText("H " + f(curWx.tempMaxC) + "°   L " + f(curWx.tempMinC) + "°");
        else glanceHilo.setText("");
        glancePlace.setText(WeatherEngine.placeLine(this, curWx, curLoc, true));

        mm("FEELS", f(curWx.feelsC) + "°", Semantic.temp(f(curWx.feelsC)));
        int wmph = (int) Math.round(curWx.windKmh * 0.621);
        mm("WIND", compass(curWx.windDirDeg) + " " + wmph, Semantic.wind(wmph));
        if (Double.isNaN(curWx.windGust)) mm("GUST", "—", theme.textColor);
        else { int g = (int) Math.round(curWx.windGust * 0.621); mm("GUST", String.valueOf(g), Semantic.wind(g)); }
        mm("DEW", Double.isNaN(curWx.dewC) ? "—" : f(curWx.dewC) + "°",
                Double.isNaN(curWx.dewC) ? theme.textColor : Semantic.dew(f(curWx.dewC)));
        if (glanceHourly != null) glanceHourly.setWeather(curWx);
        mm("HUMID", curWx.humidity > 0 ? curWx.humidity + "%" : "—",
                curWx.humidity > 0 ? Semantic.humidity(curWx.humidity) : theme.textColor);
        mm("UV", Double.isNaN(curWx.uvIndex) ? "—" : String.valueOf((int) Math.round(curWx.uvIndex)),
                Double.isNaN(curWx.uvIndex) ? theme.textColor : Semantic.uv(curWx.uvIndex));
        mm("PRECIP", Double.isNaN(curWx.precipProb) ? "—" : (int) curWx.precipProb + "%",
                Double.isNaN(curWx.precipProb) ? theme.textColor : Semantic.precip(curWx.precipProb));
        mm("AQI", curWx.aqi < 0 ? "—" : String.valueOf(curWx.aqi),
                curWx.aqi < 0 ? theme.textColor : Semantic.aqi(curWx.aqi));

        if (miniForecast != null) {
            miniForecast.removeAllViews();
            if (curWx.fcDay != null) {
                int n = Math.min(5, curWx.fcDay.length);
                for (int i = 0; i < n; i++) {
                    TextView c = new TextView(this);
                    c.setTypeface(Fonts.body);
                    c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    c.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
                    c.setGravity(Gravity.CENTER);
                    c.setMaxLines(1);
                    android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                    sb.append(curWx.fcDay[i].toUpperCase(Locale.getDefault())).append(" ")
                      .append(Wx.glyph(curWx.fcCode[i])).append(" ");
                    span(sb, Double.isNaN(curWx.fcMax[i]) ? "—" : f(curWx.fcMax[i]) + "°",
                            Double.isNaN(curWx.fcMax[i]) ? theme.textColor : Semantic.temp(f(curWx.fcMax[i])));
                    sb.append("/");
                    span(sb, Double.isNaN(curWx.fcMin[i]) ? "—" : f(curWx.fcMin[i]) + "°",
                            Double.isNaN(curWx.fcMin[i]) ? theme.textColor : Semantic.temp(f(curWx.fcMin[i])));
                    c.setTextColor(theme.textDimColor);
                    c.setText(sb);
                    miniForecast.addView(c, new LinearLayout.LayoutParams(0, dp(18), 1f));
                }
            }
        }
    }

    private static void span(android.text.SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), st, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                d = Drawable.createFromStream(getContentResolver().openInputStream(android.net.Uri.parse(theme.bgImagePath)), null);
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

    /** Installs/uninstalls drop the app cache so the next home refresh re-enumerates. */
    private void registerPackagesChanged() {
        if (packagesChanged != null) return;
        packagesChanged = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { cachedApps = null; }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        try { registerReceiver(packagesChanged, f); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (inputChanged != null) { try { unregisterReceiver(inputChanged); } catch (Exception ignored) {} }
        if (packagesChanged != null) { try { unregisterReceiver(packagesChanged); } catch (Exception ignored) {} }
        sky.removeCallbacks(skyTick);
        if (engine != null) engine.stop();
    }

    @Override public void onBackPressed() { /* HOME is the root; swallow BACK */ }

    // ===== Adapters =====

    class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Row> data;
        HomeAdapter(List<Row> d) { data = new ArrayList<>(d); }

        @Override public int getItemViewType(int pos) { return data.get(pos).type; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            if (vt == ROW_HERO) {
                View hero = buildHeroBand();
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(4);
                hero.setLayoutParams(lp);
                RecyclerView.ViewHolder vh = new RecyclerView.ViewHolder(hero) {};
                vh.setIsRecyclable(false);   // keep hero field refs stable
                return vh;
            }
            return newSectionVH(p.getContext());
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            Row r = data.get(pos);
            if (r.type == ROW_HERO) { updateWeatherUi(); return; }
            SectionVH s = (SectionVH) vh;
            s.header.setText(r.section.title.toUpperCase(Locale.getDefault()));
            s.row.setAdapter(new TileAdapter(r.section.tiles));
            if (r.section.recent && focusRecent) {
                focusRecent = false;
                s.row.scrollToPosition(0);
                s.row.post(() -> {
                    View first = s.row.getLayoutManager() == null ? null : s.row.getLayoutManager().findViewByPosition(0);
                    if (first instanceof ViewGroup && ((ViewGroup) first).getChildCount() > 0)
                        ((ViewGroup) first).getChildAt(0).requestFocus();
                });
            }
        }
        @Override public int getItemCount() { return data.size(); }
    }

    static class SectionVH extends RecyclerView.ViewHolder {
        final TextView header; final RecyclerView row;
        SectionVH(View v, TextView h, RecyclerView r) { super(v); header = h; row = r; }
    }

    /** section = gothic header + accent rule, then a horizontal shelf of tiles. */
    private SectionVH newSectionVH(Context ctx) {
        LinearLayout colv = new LinearLayout(ctx);
        colv.setOrientation(LinearLayout.VERTICAL);
        colv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headRow = new LinearLayout(ctx);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(dp(4), dp(8), 0, dp(0));
        TextView h = new TextView(ctx);
        h.setTypeface(Fonts.display, Typeface.BOLD);
        h.setTextColor(theme.textColor);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        h.setLetterSpacing(0.14f);
        headRow.addView(h);
        View rule = new View(ctx);
        rule.setBackground(Glass.divider(Glass.withAlpha(theme.accentColor, 0x77)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(14), 1f);
        rlp.setMargins(dp(16), 0, dp(6), 0);
        headRow.addView(rule, rlp);
        colv.addView(headRow);

        RecyclerView r = new RecyclerView(ctx);
        if ("grid".equals(theme.listStyle)) {
            r.setLayoutManager(new GridLayoutManager(ctx, 5));
        } else {
            r.setLayoutManager(new LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false));
        }
        r.setClipToPadding(false);
        r.setClipChildren(false);
        colv.setClipChildren(false);
        r.setPadding(0, 0, 0, dp(2));
        colv.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new SectionVH(colv, h, r);
    }

    class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
        final List<Tile> tiles;
        final int wDp, hDp;
        TileAdapter(List<Tile> t) { this(t, theme.tileWidthDp, theme.tileHeightDp); }
        TileAdapter(List<Tile> t, int wDp, int hDp) { tiles = t; this.wDp = wDp; this.hDp = hDp; }

        class VH extends RecyclerView.ViewHolder {
            FrameLayout card; ImageView img; TextView glyph; TextView label;
            LinearLayout cap; TextView capTitle, capSub; ImageView capBadge;
            android.graphics.drawable.Drawable skin;
            VH(View v, FrameLayout c, ImageView i, TextView g, TextView l, LinearLayout cp, TextView ct, TextView cs, ImageView cb) {
                super(v); card = c; img = i; glyph = g; label = l; cap = cp; capTitle = ct; capSub = cs; capBadge = cb;
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            Context c = p.getContext();
            LinearLayout wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setClipChildren(false);   // a focused card scales past this wrapper
            int w = dp(wDp);
            RecyclerView.LayoutParams wlp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.setMargins(dp(7), dp(6), dp(7), dp(6));
            wrap.setLayoutParams(wlp);

            com.sharttv.launcher.ui.CardFrame card = new com.sharttv.launcher.ui.CardFrame(c);
            card.setLayoutParams(new LinearLayout.LayoutParams(w, dp(hDp)));
            float rad = dp(theme.cornerRadiusDp);
            card.setBackground(Glass.card(theme.surfaceColor, accentNow, false, rad));
            card.setRadius(rad);   // rounds itself; outline clipping drops out on focused cards here
            card.setTag(WeatherView.OCCLUDE);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setDefaultFocusHighlightEnabled(false);   // its square highlight showed in the rounded corners

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
            glyph.setPadding(dp(10), dp(6), dp(10), dp(6));
            glyph.setMaxLines(2);
            // shrink long names to fit instead of spilling out of the card
            glyph.setAutoSizeTextTypeUniformWithConfiguration(10, 20, 1, TypedValue.COMPLEX_UNIT_SP);
            card.addView(glyph, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // name lives INSIDE the card on a soft scrim and fades in on focus (stock Google TV
            // behavior) — an external label row cost ~22dp under every card.
            TextView label = new TextView(c);
            label.setTypeface(Fonts.body, Typeface.BOLD);
            label.setTextColor(theme.textColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(10), dp(14), dp(10), dp(6));
            label.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0xCC000000}));
            label.setAlpha(0f);
            card.addView(label, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            // caption for cards with bespoke art: title + small caption, bottom-left on a scrim
            LinearLayout cap = new LinearLayout(c);
            cap.setOrientation(LinearLayout.VERTICAL);
            cap.setGravity(Gravity.BOTTOM | Gravity.START);
            cap.setPadding(dp(12), dp(8), dp(12), dp(9));
            cap.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
                    new int[]{0xE6000000, 0x66000000, 0x00000000}));
            TextView capTitle = new TextView(c);
            capTitle.setTypeface(Fonts.display, Typeface.BOLD);
            capTitle.setTextColor(0xFFF4ECDC);
            capTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            capTitle.setLetterSpacing(0.06f);
            capTitle.setSingleLine(true);
            capTitle.setEllipsize(TextUtils.TruncateAt.END);
            capTitle.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
            TextView capSub = new TextView(c);
            capSub.setTypeface(Fonts.body, Typeface.BOLD);
            capSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            capSub.setLetterSpacing(0.16f);
            capSub.setSingleLine(true);
            capSub.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
            LinearLayout titleRow = new LinearLayout(c);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            ImageView capBadge = new ImageView(c);
            capBadge.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Glass.clipRound(capBadge, dp(4));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(18), dp(18));
            blp.setMarginEnd(dp(7));
            titleRow.addView(capBadge, blp);
            titleRow.addView(capTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cap.addView(titleRow);
            cap.addView(capSub);
            card.addView(cap, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            wrap.addView(card);
            return new VH(wrap, card, img, glyph, label, cap, capTitle, capSub, capBadge);
        }

        @Override public void onBindViewHolder(@NonNull VH vh, int pos) {
            final Tile t = tiles.get(pos);
            final float rad = dp(theme.cornerRadiusDp);
            vh.label.setText(t.label);
            vh.cap.setVisibility(t.art != 0 ? View.VISIBLE : View.GONE);
            if (t.art != 0) {
                vh.img.setImageResource(t.art);
                vh.glyph.setText("");
                vh.capTitle.setText(t.label);
                vh.capSub.setText(t.sub.toUpperCase(java.util.Locale.US));
                vh.capSub.setTextColor(accentNow);
                vh.capSub.setVisibility(t.sub.isEmpty() ? View.GONE : View.VISIBLE);
                vh.capBadge.setImageDrawable(t.badge);
                vh.capBadge.setVisibility(t.badge != null ? View.VISIBLE : View.GONE);
            } else if (t.kind == Tile.Kind.APP && t.icon != null) {
                vh.img.setImageDrawable(t.icon);
                vh.glyph.setText("");
            } else {
                vh.img.setImageDrawable(null);
                vh.glyph.setText(t.label);   // gothic centered label for inputs/settings
            }
            final boolean fullBleed = t.art != 0 || (t.kind == Tile.Kind.APP && t.icon != null);
            // full-bleed cards have no glass behind them (nothing would show but its corners)
            vh.card.setBackground(fullBleed ? null : Glass.card(theme.surfaceColor, accentNow, false, rad));
            // organic weather treatment baked into every card (frost/rain/warm glow), kept subtle
            vh.skin = theme.weather && curWx != null && curWx.valid()
                    ? new WeatherSkin(curWx.category, curWx.intensity, curWx.isFreezing(), Glass.D).radius(rad) : null;
            vh.card.setForeground(vh.skin);
            ((com.sharttv.launcher.ui.CardFrame) vh.card).setRing(vh.card.isFocused(), accentNow);
            vh.card.setOnClickListener(v -> CardFx.click(v, rad, accentNow, () -> {
                try {
                    usage.record(usageKey(t));   // learn what gets used
                    if (t.launch != null) startActivity(t.launch);
                } catch (Exception ignored) {}
            }));
            if (t.kind == Tile.Kind.INPUT) {
                // long-press OK: name this input and pick its card ("Steam Deck", "Mac mini", ...)
                vh.card.setOnLongClickListener(v -> {
                    try {
                        backFromOwnScreen = true;
                        startActivity(new Intent(LauncherActivity.this, InputEditActivity.class)
                                .putExtra(InputEditActivity.EXTRA_ID, t.key)
                                .putExtra(InputEditActivity.EXTRA_PORT, t.port)
                                .putExtra(InputEditActivity.EXTRA_PORT_ART, t.portArt));
                    } catch (Exception ignored) {}
                    return true;
                });
            } else vh.card.setOnLongClickListener(null);
            vh.card.setOnFocusChangeListener((v, has) -> {
                int ring = (theme.weather && curWx != null && curWx.isFreezing())
                        ? Color.parseColor("#9FE8FF") : accentNow;
                if (!fullBleed) v.setBackground(Glass.card(theme.surfaceColor, ring, has, rad));
                // the ring sits ABOVE the art (the background one is hidden under full-bleed images)
                ((com.sharttv.launcher.ui.CardFrame) v).setRing(has, ring);
                CardFx.focus(v, has, theme.focusScale ? theme.focusScaleAmount : 1f, rad, ring, theme.focusAnimMs);
                boolean showName = t.kind == Tile.Kind.APP && t.art == 0;   // art cards carry a caption
                vh.label.animate().alpha(has && showName ? 1f : 0f).setDuration(theme.focusAnimMs).start();
            });
        }
        @Override public int getItemCount() { return tiles.size(); }
    }
}
