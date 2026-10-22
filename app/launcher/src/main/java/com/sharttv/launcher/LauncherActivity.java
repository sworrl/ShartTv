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
                    updateChrome();
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
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(214)));
        }

        RecyclerView shelves = new RecyclerView(this);
        shelves.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        shelves.setPadding(dp(48), dp(10), dp(48), dp(40));
        shelves.setClipToPadding(false);

        List<Section> sections = new ArrayList<>();
        inputs.clear();
        inputs.addAll(Repo.inputs(this, theme));
        if (!inputs.isEmpty()) sections.add(new Section(getString(R.string.inputs), inputs));
        sections.add(new Section(getString(R.string.apps), Repo.apps(this, theme)));
        sections.add(new Section(getString(R.string.settings), Repo.settings(this, theme)));
        shelves.setAdapter(new ShelfAdapter(sections));
        col.addView(shelves, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        registerInputChanged();
        updateChrome();
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

    // ---------- weather hero ----------
    private View buildHero() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, dp(26)));
        Glass.clipRound(wrap, dp(26));
        int pad = dp(22);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad + dp(20), pad, pad + dp(20), pad);

        LinearLayout leftc = new LinearLayout(this);
        leftc.setOrientation(LinearLayout.VERTICAL);
        heroTemp = new TextView(this);
        heroTemp.setTypeface(Fonts.display, Typeface.BOLD);
        heroTemp.setTextColor(theme.textColor);
        heroTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        heroCond = new TextView(this);
        heroCond.setTypeface(Fonts.display);
        heroCond.setTextColor(theme.accentColor);
        heroCond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        heroCond.setLetterSpacing(0.04f);
        heroPlace = new TextView(this);
        heroPlace.setTypeface(Fonts.body);
        heroPlace.setTextColor(theme.textDimColor);
        heroPlace.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        leftc.addView(heroTemp); leftc.addView(heroCond); leftc.addView(heroPlace);
        row.addView(leftc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View div = new View(this);
        div.setBackground(Glass.divider(Glass.withAlpha(theme.accentColor, 0x88)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(2), dp(120));
        dlp.setMargins(dp(10), 0, dp(24), 0);
        // vertical divider: reuse horizontal one rotated is overkill; a thin accent line is fine
        GradientDrawable vd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Glass.withAlpha(theme.accentColor,0), Glass.withAlpha(theme.accentColor,0x99), Glass.withAlpha(theme.accentColor,0)});
        div.setBackground(vd);
        row.addView(div, dlp);

        heroMetrics = new TextView(this);
        heroMetrics.setTypeface(Fonts.body);
        heroMetrics.setTextColor(theme.textColor);
        heroMetrics.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heroMetrics.setLineSpacing(dp(4), 1f);
        row.addView(heroMetrics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrap.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams outer = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // margin so the hero floats
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(48), dp(14), dp(48), dp(6));
        holder.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return holder;
    }

    private void updateChrome() {
        Date now = new Date();
        if (barClock != null) barClock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        if (barDate != null) barDate.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        boolean haveWx = curWx != null && curWx.valid();
        if (barWx != null) {
            barWx.setText(haveWx ? curWx.icon + "   " + f(curWx.tempC) + "°  ·  " + curWx.name
                                 : "Shart TV");
        }
        if (heroTemp != null) {
            if (haveWx) {
                heroTemp.setText(f(curWx.tempC) + "°");
                heroCond.setText(curWx.name);
                heroPlace.setText((curLoc != null && curLoc.place != null && !curLoc.place.isEmpty())
                        ? curLoc.place : "");
                StringBuilder m = new StringBuilder();
                m.append("Feels  ").append(f(curWx.feelsC)).append("°\n");
                if (!Double.isNaN(curWx.tempMaxC) && !Double.isNaN(curWx.tempMinC))
                    m.append("High ").append(f(curWx.tempMaxC)).append("°   Low ").append(f(curWx.tempMinC)).append("°\n");
                if (curWx.humidity > 0) m.append("Humidity  ").append(curWx.humidity).append("%\n");
                m.append("Wind  ").append(compass(curWx.windDirDeg)).append(" ")
                 .append(Math.round(curWx.windKmh * 0.621)).append(" mph");
                heroMetrics.setText(m.toString());
            } else {
                heroTemp.setText("—");
                heroCond.setText("Weather");
                heroPlace.setText("waiting for a location fix");
                heroMetrics.setText("Set a BeaconFix URL or manual\nlat/lon in Appearance.");
            }
        }
    }

    private static int f(double c) { return (int) Math.round(c * 9 / 5 + 32); }
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
                try { if (t.launch != null) startActivity(t.launch); } catch (Exception ignored) {}
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
