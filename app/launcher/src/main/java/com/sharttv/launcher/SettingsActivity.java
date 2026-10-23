package com.sharttv.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import com.sharttv.launcher.ui.HourClock;
import com.sharttv.launcher.weather.Loc;
import com.sharttv.launcher.weather.TimeSky;
import com.sharttv.launcher.weather.WeatherEngine;
import com.sharttv.launcher.weather.WeatherView;
import com.sharttv.launcher.weather.Wx;

/**
 * Appearance. A rail of section cards (each with its own art) on the left, the focused section's
 * controls in a glass pane on the right, over the same live sky as the home. Focusing a section
 * card shows it; RIGHT moves into its controls. Changes save immediately and only the pane
 * redraws, so focus stays where it was.
 */
public class SettingsActivity extends Activity {

    private Theme t;
    private LinearLayout pane;
    private TextView paneTitle;
    private int section;
    private WeatherView sky;
    private final android.os.Handler h = new android.os.Handler();
    private Wx wx;
    private Loc loc;

    private static final int[] ACCENTS = {
            Color.parseColor("#4FC3F7"), Color.parseColor("#FF6E6E"), Color.parseColor("#9CCC65"),
            Color.parseColor("#FFB74D"), Color.parseColor("#BA68C8"), Color.parseColor("#F5F5F5")
    };
    private static final int[] BGS = {
            Color.parseColor("#0B0E14"), Color.parseColor("#000000"), Color.parseColor("#12161C"),
            Color.parseColor("#1A1026"), Color.parseColor("#04140E")
    };

    private static final String[] SECTIONS = {"Colors", "Home layout", "Hour clock", "Weather", "Learning"};
    private static final String[] BLURBS = {
            "Accent and background", "Tiles, rows and motion", "The top-of-the-hour chime",
            "Sky backdrop and location", "What the home learns"};
    private static final int[] ART = {
            R.drawable.art_set_colors, R.drawable.art_set_layout, R.drawable.art_set_clock,
            R.drawable.art_set_weather, R.drawable.art_set_learning};

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private final Runnable skyTick = new Runnable() {
        @Override public void run() {
            if (sky != null) sky.setTint(TimeSky.weatherAdjust(TimeSky.now(loc).tint, wx));
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Fonts.load(this);
        Glass.init(this);
        t = Theme.load(this);
        WeatherEngine we = new WeatherEngine(this);
        wx = we.readCachedWx();
        loc = we.readCachedLoc();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bgColor);
        sky = new WeatherView(this);
        if (wx != null) sky.setWeather(wx);
        root.addView(sky, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(40), dp(26), dp(40), dp(26));
        root.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- left rail: title + section cards
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        body.addView(rail, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = new TextView(this);
        title.setTypeface(Fonts.display, Typeface.BOLD);
        title.setText("APPEARANCE");
        title.setTextColor(t.textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setLetterSpacing(0.16f);
        title.setPadding(dp(4), 0, 0, dp(12));
        rail.addView(title);
        View first = null;
        for (int i = 0; i < SECTIONS.length; i++) {
            View c = sectionCard(i);
            rail.addView(c);
            if (i == 0) first = c;
        }

        // ---- right: the section's controls in a glass pane
        FrameLayout pcard = new FrameLayout(this);
        pcard.setBackground(Glass.card(t.surfaceColor, t.accentColor, false, dp(22)));
        Glass.clipRound(pcard, dp(22));
        pcard.setTag(WeatherView.OCCLUDE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        plp.setMarginStart(dp(22));
        body.addView(pcard, plp);
        LinearLayout pcol = new LinearLayout(this);
        pcol.setOrientation(LinearLayout.VERTICAL);
        pcol.setPadding(dp(28), dp(22), dp(28), dp(18));
        pcard.addView(pcol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paneTitle = new TextView(this);
        paneTitle.setTypeface(Fonts.display, Typeface.BOLD);
        paneTitle.setTextColor(t.accentColor);
        paneTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        paneTitle.setLetterSpacing(0.12f);
        pcol.addView(paneTitle);
        View rule = new View(this);
        rule.setBackground(Glass.divider(t.accentColor));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        rlp.setMargins(0, dp(8), 0, dp(6));
        pcol.addView(rule, rlp);
        ScrollView sv = new ScrollView(this);   // a safety net; every section is laid out to fit
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        sv.addView(pane);
        pcol.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showSection(0);
        if (first != null) first.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (sky != null) sky.startAnim();
        h.removeCallbacks(skyTick);
        h.post(skyTick);
    }

    @Override protected void onPause() {
        super.onPause();
        if (sky != null) sky.stopAnim();
        h.removeCallbacks(skyTick);
    }

    // ---------- rail ----------

    private View sectionCard(final int i) {
        FrameLayout card = new FrameLayout(this);
        final float rad = dp(16);
        card.setBackground(Glass.card(t.surfaceColor, t.accentColor, false, rad));
        Glass.clipRound(card, rad);
        card.setTag(WeatherView.OCCLUDE);
        card.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, dp(4), 0, dp(4));
        card.setLayoutParams(lp);

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageResource(ART[i]);
        art.setAlpha(0.85f);
        card.addView(art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setGravity(Gravity.CENTER_VERTICAL);
        cap.setPadding(dp(16), 0, dp(12), 0);
        cap.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF0000000, 0x80000000, 0x00000000}));
        TextView name = new TextView(this);
        name.setTypeface(Fonts.display, Typeface.BOLD);
        name.setText(SECTIONS[i].toUpperCase(java.util.Locale.US));
        name.setTextColor(0xFFF4ECDC);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setLetterSpacing(0.1f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(5), 0, dp(1), 0xCC000000);
        TextView blurb = new TextView(this);
        blurb.setTypeface(Fonts.body);
        blurb.setText(BLURBS[i]);
        blurb.setTextColor(0xCCE8E0D0);
        blurb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        blurb.setSingleLine(true);
        blurb.setEllipsize(TextUtils.TruncateAt.END);
        cap.addView(name);
        cap.addView(blurb);
        card.addView(cap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Glass.card(t.surfaceColor, t.accentColor, has, rad));
            art.animate().alpha(has ? 1f : 0.7f).scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setDuration(t.focusAnimMs).start();
            name.setTextColor(has ? t.accentColor : 0xFFF4ECDC);
            if (has && section != i) showSection(i);
        });
        card.setOnClickListener(v -> focusFirst(pane));
        return card;
    }

    private static boolean focusFirst(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v.requestFocus();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (focusFirst(g.getChildAt(i))) return true;
        }
        return false;
    }

    // ---------- pane ----------

    private void showSection(int i) {
        section = i;
        paneTitle.setText(SECTIONS[i].toUpperCase(java.util.Locale.US));
        rebuildPane();
    }

    /** Redraw the pane in place, then put focus back on the control that had it. */
    private void rebuildPane() {
        View f = getCurrentFocus();
        Object key = f != null && isInPane(f) ? f.getTag() : null;
        pane.removeAllViews();
        switch (section) {
            case 0: buildColors(); break;
            case 1: buildLayout(); break;
            case 2: buildClock(); break;
            case 3: buildWeather(); break;
            case 4: buildLearning(); break;
        }
        if (key != null) {
            View again = pane.findViewWithTag(key);
            if (again != null) again.requestFocus();
        }
    }

    private boolean isInPane(View v) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) if (p == pane) return true;
        return false;
    }

    private void commit() { t.save(this); rebuildPane(); }

    private void buildColors() {
        label("Accent");
        swatches(ACCENTS, true);
        label("Background");
        swatches(BGS, false);
        note("The accent tints focus rings and highlights. Across the day the home blends it with "
                + "the color of the sky.");
    }

    private void buildLayout() {
        label("Apps show as");
        choice("icons", new String[]{"Banners", "Icons"}, t.iconStyle.equals("banner") ? 0 : 1,
                k -> { t.iconStyle = k == 0 ? "banner" : "icon"; commit(); });
        label("Lists");
        choice("list", new String[]{"Shelves", "Grid"}, t.listStyle.equals("rows") ? 0 : 1,
                k -> { t.listStyle = k == 0 ? "rows" : "grid"; commit(); });
        label("Tile size");
        choice("size", new String[]{"Compact", "Stock", "Large"},
                t.tileWidthDp <= 140 ? 0 : t.tileWidthDp >= 200 ? 2 : 1, k -> {
                    int[][] sz = {{132, 74}, {168, 94}, {224, 126}};
                    t.tileWidthDp = sz[k][0]; t.tileHeightDp = sz[k][1]; commit();
                });
        label("Corners");
        choice("corners", new String[]{"Square", "Soft", "Round"},
                t.cornerRadiusDp <= 4 ? 0 : t.cornerRadiusDp >= 22 ? 2 : 1, k -> {
                    t.cornerRadiusDp = new int[]{2, 16, 26}[k]; commit();
                });
        label("Motion");
        choice("motion", new String[]{"Grow on focus", "Still"}, t.focusScale ? 0 : 1,
                k -> { t.focusScale = k == 0; commit(); });
    }

    private void buildClock() {
        label("Over anything playing, a few seconds before each hour");
        choice("chime", new String[]{"On", "Off"}, t.hourChime ? 0 : 1, k -> { t.hourChime = k == 0; commit(); });
        label("Style");
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < HourClock.STYLES.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            row.addView(styleCard(HourClock.STYLES[i], HourClock.NAMES[i]));
        }
        pane.addView(grid);
        label("Format");
        choice("format", new String[]{"Follow TV", "12-hour", "24-hour"},
                "12".equals(t.clockFormat) ? 1 : "24".equals(t.clockFormat) ? 2 : 0,
                k -> { t.clockFormat = new String[]{"auto", "12", "24"}[k]; commit(); });
        View play = pill("play", "▶  Preview the hour change", true);
        play.setOnClickListener(v -> HomeRedirectService.previewChime());
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) play.getLayoutParams();
        lp.topMargin = dp(14);
        pane.addView(play);
    }

    /** A focusable card with a live mini clock in that style; the chosen one is ringed. */
    private View styleCard(final String key, String name) {
        boolean on = key.equals(t.clockStyle);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(8), dp(8), dp(8), dp(6));
        c.setFocusable(true);
        c.setTag("style:" + key);
        final float rad = dp(14);
        c.setBackground(Glass.card(t.surfaceColor, on ? t.accentColor : t.textDimColor, on, rad));
        HourClock hc = new HourClock(this, 20);
        hc.setStyle(key);
        hc.setFormat(t.clockFormat);
        hc.setPalette(t.textColor, t.surfaceColor);
        hc.setAccent(TimeSky.now(loc).accent);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(hc, hlp);
        TextView tv = new TextView(this);
        tv.setTypeface(Fonts.body, Typeface.BOLD);
        tv.setText(name);
        tv.setTextColor(on ? t.accentColor : t.textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(4), 0, 0);
        tv.setGravity(Gravity.CENTER);
        c.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(10), dp(10));
        c.setLayoutParams(lp);
        c.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Glass.card(t.surfaceColor, has || on ? t.accentColor : t.textDimColor, has || on, rad));
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(t.focusAnimMs).start();
            if (has) hc.demo(2_200);   // roll the hour over so you can see the style move
        });
        c.setOnClickListener(v -> { t.clockStyle = key; commit(); });
        return c;
    }

    private void buildWeather() {
        label("Live sky behind the home");
        choice("sky", new String[]{"On", "Off"}, t.weather ? 0 : 1, k -> { t.weather = k == 0; commit(); });
        label("Forecast strip on the home");
        choice("fc", new String[]{"On", "Off"}, t.showForecast ? 0 : 1, k -> { t.showForecast = k == 0; commit(); });
        label("Location");
        note(locationLine());
        note("Weather is Open-Meteo, with the ECMWF model where it has a reading. It refreshes every "
                + "10 minutes while the screen is on, even when an app is in front.");
    }

    private String locationLine() {
        String src = loc == null ? "" : loc.source == null ? "" : loc.source;
        String place = loc == null ? "" : loc.place == null ? "" : loc.place;
        String how = src.startsWith("beaconfix") ? "From BeaconFix on your network"
                : src.startsWith("tv") ? "Following the TV's own Wi-Fi location"
                : src.equals("manual") ? "Set by hand"
                : src.equals("seed") ? "Using the built-in fallback until the TV gets a fix"
                : "Waiting for a location fix";
        String when = wx != null && wx.fetchedAt > 0
                ? " · updated " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(wx.fetchedAt))
                : "";
        return how + (place.isEmpty() ? "" : ": " + place) + when;
    }

    private void buildLearning() {
        label("Move the apps and inputs you use most toward the front");
        choice("promote", new String[]{"On", "Off"}, t.autoPromote ? 0 : 1, k -> { t.autoPromote = k == 0; commit(); });
        label("Show a Most Used row");
        choice("mostused", new String[]{"On", "Off"}, t.showMostUsed ? 0 : 1, k -> { t.showMostUsed = k == 0; commit(); });
        note("Learning stays on the TV and belongs to the current profile.");
    }

    // ---------- controls ----------

    private void label(String s) {
        TextView tv = new TextView(this);
        tv.setTypeface(Fonts.body, Typeface.BOLD);
        tv.setText(s);
        tv.setTextColor(t.textDimColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(12), 0, dp(6));
        pane.addView(tv);
    }

    private void note(String s) {
        TextView tv = new TextView(this);
        tv.setTypeface(Fonts.body);
        tv.setText(s);
        tv.setTextColor(t.textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLineSpacing(0, 1.15f);
        tv.setPadding(0, dp(6), dp(8), dp(2));
        pane.addView(tv);
    }

    private interface Pick { void on(int k); }

    /** A row of options; the current one is filled. LEFT/RIGHT moves, OK picks. */
    private void choice(String key, String[] opts, int cur, Pick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < opts.length; i++) {
            final int k = i;
            View p = pill(key + ":" + i, opts[i], i == cur);
            p.setOnClickListener(v -> { if (k != cur) pick.on(k); });
            row.addView(p);
        }
        pane.addView(row);
    }

    private View pill(Object tag, String text, final boolean selected) {
        final TextView p = new TextView(this);
        p.setTypeface(Fonts.body, Typeface.BOLD);
        p.setText(text);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        p.setLetterSpacing(0.04f);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(18), dp(8), dp(18), dp(8));
        p.setFocusable(true);
        p.setTag(tag);
        final float rad = dp(18);
        style(p, selected, false, rad);
        p.setOnFocusChangeListener((v, has) -> style(p, selected, has, rad));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        p.setLayoutParams(lp);
        return p;
    }

    private void style(TextView p, boolean selected, boolean focused, float rad) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(rad);
        g.setColor(selected ? Glass.withAlpha(t.accentColor, 0x40) : Glass.withAlpha(t.surfaceColor, 0xB0));
        g.setStroke(dp(focused ? 2.5f : 1), focused ? t.accentColor
                : selected ? Glass.withAlpha(t.accentColor, 0xA0) : Glass.withAlpha(t.textColor, 0x30));
        p.setBackground(g);
        p.setTextColor(selected || focused ? t.accentColor : t.textColor);
        p.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(t.focusAnimMs).start();
    }

    private void swatches(int[] colors, final boolean accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(4), 0, dp(4));
        int cur = accent ? t.accentColor : t.bgColor;
        for (final int c : colors) {
            View sw = new View(this);
            sw.setTag((accent ? "acc:" : "bg:") + c);
            final boolean on = c == cur;
            sw.setBackground(swatch(c, on, false));
            sw.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(54));
            lp.setMarginEnd(dp(14));
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> { if (accent) t.accentColor = c; else t.bgColor = c; commit(); });
            sw.setOnFocusChangeListener((v, has) -> {
                v.setBackground(swatch(c, on, has));
                v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(t.focusAnimMs).start();
            });
            row.addView(sw);
        }
        pane.addView(row);
    }

    private GradientDrawable swatch(int c, boolean on, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c);
        g.setStroke(dp(on || focused ? 3 : 1), focused ? t.accentColor : on ? t.textColor : Glass.withAlpha(t.textColor, 0x40));
        return g;
    }
}
