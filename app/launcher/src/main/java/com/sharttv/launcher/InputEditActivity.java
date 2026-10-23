package com.sharttv.launcher;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sharttv.launcher.ui.CardFrame;
import com.sharttv.launcher.ui.CardFx;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import com.sharttv.launcher.weather.TimeSky;
import com.sharttv.launcher.weather.WeatherEngine;
import com.sharttv.launcher.weather.WeatherView;
import com.sharttv.launcher.weather.Wx;

/**
 * Name an input and give it card art: "HDMI 2" becomes "Steam Deck" with the Steam Deck card.
 * Opened by long-pressing an input on the home, or from Appearance > Inputs.
 */
public class InputEditActivity extends Activity {

    public static final String EXTRA_ID = "input_id", EXTRA_PORT = "port", EXTRA_PORT_ART = "port_art";

    private Theme t;
    private String id, port;
    private int portArt;
    private String preset;
    private EditText name;
    private LinearLayout grid;
    private android.widget.ScrollView sv;
    private WeatherView sky;
    private final android.os.Handler h = new android.os.Handler();
    private Wx wx;
    private com.sharttv.launcher.weather.Loc loc;
    private final Runnable skyTick = new Runnable() {
        @Override public void run() {
            if (sky != null) sky.setTint(TimeSky.weatherAdjust(TimeSky.now(loc).tint, wx));
            h.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Fonts.load(this);
        Glass.init(this);
        t = Theme.load(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        port = getIntent().getStringExtra(EXTRA_PORT);
        portArt = getIntent().getIntExtra(EXTRA_PORT_ART, R.drawable.art_input);
        if (id == null) { finish(); return; }
        preset = InputNames.preset(this, id);
        WeatherEngine we = new WeatherEngine(this);
        wx = we.readCachedWx();
        loc = we.readCachedLoc();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bgColor);
        sky = new WeatherView(this);
        if (wx != null) sky.setWeather(wx);
        root.addView(sky);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(26), dp(48), 0);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setTypeface(Fonts.display, Typeface.BOLD);
        title.setText((port == null ? "INPUT" : port).toUpperCase(java.util.Locale.US));
        title.setTextColor(t.textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setLetterSpacing(0.16f);
        col.addView(title);
        TextView hint = new TextView(this);
        hint.setTypeface(Fonts.body);
        hint.setText("What's plugged in here? Name it and pick its card.");
        hint.setTextColor(t.textDimColor);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(2), 0, dp(12));
        col.addView(hint);

        name = new EditText(this);
        name.setTypeface(Fonts.display, Typeface.BOLD);
        name.setTextColor(t.textColor);
        name.setHintTextColor(t.textDimColor);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setHint(port == null ? "Name" : port);
        name.setText(InputNames.name(this, id));
        name.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable nb = new GradientDrawable();
        nb.setCornerRadius(dp(14));
        nb.setColor(Glass.withAlpha(t.surfaceColor, 0xC8));
        nb.setStroke(dp(1.5f), Glass.withAlpha(t.accentColor, 0x90));
        name.setBackground(nb);
        name.setOnFocusChangeListener((v, has) -> nb.setStroke(dp(has ? 2.5f : 1.5f), has ? t.accentColor : Glass.withAlpha(t.accentColor, 0x90)));
        name.setTag(WeatherView.OCCLUDE);   // the sun passes behind the field, not over it
        // name, Save and Reset share one row so the grid below gets the whole rest of the screen
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMarginEnd(dp(18));
        top.addView(name, nlp);
        top.addView(pill("Save", true, v -> save()));
        top.addView(pill("Reset to " + (port == null ? "default" : port), false, v -> {
            InputNames.reset(this, id);
            finish();
        }));
        col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setPadding(dp(8), 0, 0, 0);   // room for the focused card's lift
        grid.setClipToPadding(false);
        // 50 presets in groups: the one place that scrolls (it follows focus)
        sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        // the column clips it at its edges (so scrolled cards never draw over the name field);
        // its padding and the column's side padding leave the focused card room to lift
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(8), 0, dp(16));
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(28));
        sv.addView(grid);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildGrid();

        setContentView(root);
        View first = grid.findViewWithTag("art:" + preset);
        if (first != null) first.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); if (sky != null) sky.startAnim(); h.removeCallbacks(skyTick); h.post(skyTick); }
    @Override protected void onPause() { super.onPause(); if (sky != null) sky.stopAnim(); h.removeCallbacks(skyTick); }

    private void save() {
        InputNames.set(this, id, name.getText().toString(), preset);
        finish();
    }

    private static final int PER_ROW = 5;

    private void buildGrid() {
        grid.removeAllViews();
        heading("This port");
        LinearLayout row = newRow();
        row.addView(artCard("", port == null ? "Default" : port, portArt));
        for (String g : InputNames.GROUPS) {
            heading(g);
            int n = 0;
            row = null;
            for (InputNames.Preset p : InputNames.PRESETS) {
                if (!p.group.equals(g)) continue;
                if (n++ % PER_ROW == 0) row = newRow();
                row.addView(artCard(p.key, p.label, p.art));
            }
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        grid.addView(row);
        return row;
    }

    private void heading(String s) {
        TextView tv = new TextView(this);
        tv.setTypeface(Fonts.display, Typeface.BOLD);
        tv.setText(s.toUpperCase(java.util.Locale.US));
        tv.setTextColor(t.accentColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLetterSpacing(0.14f);
        tv.setPadding(dp(2), dp(10), 0, dp(2));
        grid.addView(tv);
    }

    private View artCard(final String key, final String label, int art) {
        final boolean on = key.equals(preset);
        final float rad = dp(12);
        CardFrame c = new CardFrame(this);
        c.setRadius(rad);
        c.setFocusable(true);
        c.setDefaultFocusHighlightEnabled(false);
        c.setTag("art:" + key);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageResource(art);
        c.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView cap = new TextView(this);
        cap.setTypeface(Fonts.display, Typeface.BOLD);
        cap.setText(label);
        cap.setTextColor(0xFFF4ECDC);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cap.setSingleLine(true);
        cap.setEllipsize(TextUtils.TruncateAt.END);
        cap.setGravity(Gravity.BOTTOM | Gravity.START);
        cap.setPadding(dp(9), 0, dp(6), dp(6));
        cap.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        cap.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR, new int[]{0xE0000000, 0x40000000, 0x00000000}));
        c.addView(cap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        c.setRing(on, t.accentColor);
        c.setOnFocusChangeListener((v, has) -> {
            c.setRing(has || on, has ? t.accentColor : Glass.withAlpha(t.accentColor, 0xA0));
            CardFx.focus(v, has, 1.08f, rad, t.accentColor, t.focusAnimMs);
            if (has && key.isEmpty()) sv.smoothScrollTo(0, 0);   // back at the top: show its heading
        });
        c.setOnClickListener(v -> CardFx.click(v, rad, t.accentColor, () -> {
            String cur = name.getText().toString().trim();
            boolean auto = cur.isEmpty() || cur.equals(InputNames.label(preset)) || cur.equals(port);
            preset = key;
            if (auto) name.setText(key.isEmpty() ? "" : label);
            buildGrid();
            View again = grid.findViewWithTag("art:" + key);
            if (again != null) again.requestFocus();
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(158), dp(84));
        lp.setMargins(0, dp(6), dp(12), dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    private View pill(String text, boolean primary, View.OnClickListener onClick) {
        final TextView p = new TextView(this);
        p.setTypeface(Fonts.body, Typeface.BOLD);
        p.setText(text);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        p.setPadding(dp(20), dp(8), dp(20), dp(8));
        p.setFocusable(true);
        p.setTag(WeatherView.OCCLUDE);
        final float rad = dp(18);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(rad);
        g.setColor(primary ? Glass.withAlpha(t.accentColor, 0x40) : Glass.withAlpha(t.surfaceColor, 0xB0));
        g.setStroke(dp(1.5f), Glass.withAlpha(t.accentColor, primary ? 0xC0 : 0x50));
        p.setBackground(g);
        p.setTextColor(primary ? t.accentColor : t.textColor);
        p.setOnFocusChangeListener((v, has) -> {
            g.setStroke(dp(has ? 2.5f : 1.5f), has ? t.accentColor : Glass.withAlpha(t.accentColor, primary ? 0xC0 : 0x50));
            CardFx.focus(v, has, 1.07f, rad, t.accentColor, t.focusAnimMs);
        });
        p.setOnClickListener(v -> CardFx.click(v, rad, t.accentColor, () -> onClick.onClick(v)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        p.setLayoutParams(lp);
        return p;
    }
}
