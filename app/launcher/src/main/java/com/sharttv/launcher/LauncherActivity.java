package com.sharttv.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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
import com.sharttv.launcher.weather.Loc;
import com.sharttv.launcher.weather.TimeSky;
import com.sharttv.launcher.weather.WeatherEngine;
import com.sharttv.launcher.weather.WeatherView;
import com.sharttv.launcher.weather.Wx;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Shart TV launcher home. Ad-free, no network. Shelves: Inputs, Apps, Settings.
 * Appearance is driven entirely by Theme (colors, wallpaper, tile shape, focus animation, style).
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
    private TextView readout;
    private final android.os.Handler sky = new android.os.Handler();
    private final Runnable skyTick = new Runnable() {
        @Override public void run() {
            TimeSky.Sky s = TimeSky.now(curLoc);
            int tint = TimeSky.weatherAdjust(s.tint, curWx);
            if (weatherView != null) weatherView.setTint(tint);
            sky.postDelayed(this, 1000);   // once a second: cheap, never the same twice
        }
    };

    static class Section { String title; List<Tile> tiles; Section(String t, List<Tile> x){title=t;tiles=x;} }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        theme = Theme.load(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        // Re-read theme + app list each time we return (apps may have changed, theme may be edited).
        theme = Theme.load(this);
        buildUi();
        if (theme.weather) {
            if (engine == null) {
                engine = new WeatherEngine(this);
                engine.setListener((wx, loc) -> {
                    curWx = wx; curLoc = loc;
                    if (weatherView != null) weatherView.setWeather(wx);
                    updateReadout();
                });
            }
            engine.start();
            sky.removeCallbacks(skyTick);
            sky.post(skyTick);
            if (weatherView != null) weatherView.startAnim();
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

        // Weather + time-of-day backdrop (behind everything). Only if enabled in theme.
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

        RecyclerView shelves = new RecyclerView(this);
        shelves.setLayoutManager(new LinearLayoutManager(this, RecyclerView.VERTICAL, false));
        shelves.setPadding(dp(48), dp(40), dp(48), dp(40));
        shelves.setClipToPadding(false);

        List<Section> sections = new ArrayList<>();
        inputs.clear();
        inputs.addAll(Repo.inputs(this, theme));
        if (!inputs.isEmpty()) sections.add(new Section(getString(R.string.inputs), inputs));
        sections.add(new Section(getString(R.string.apps), Repo.apps(this, theme)));
        sections.add(new Section(getString(R.string.settings), Repo.settings(this, theme)));

        shelves.setAdapter(new ShelfAdapter(sections));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(shelves, lp);

        // live weather readout, top-right
        if (theme.weather) {
            readout = new TextView(this);
            readout.setTextColor(theme.textColor);
            readout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            readout.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(Color.argb(90, 0, 0, 0)); rb.setCornerRadius(dp(12));
            readout.setBackground(rb);
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.gravity = Gravity.TOP | Gravity.END;
            rl.setMargins(0, dp(28), dp(48), 0);
            root.addView(readout, rl);
            updateReadout();
        }

        setContentView(root);
        registerInputChanged();
    }

    private void updateReadout() {
        if (readout == null) return;
        if (curWx == null || !curWx.valid()) { readout.setText("Shart TV"); return; }
        String loc = (curLoc != null && curLoc.place != null && !curLoc.place.isEmpty()) ? "  ·  " + curLoc.place : "";
        readout.setText(curWx.icon + "  " + Math.round(curWx.tempC * 9 / 5 + 32) + "°F  ·  " + curWx.name + loc);
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
                // dim overlay for legibility
                View dim = new View(this);
                dim.setBackgroundColor(Color.argb(140, 0, 0, 0));
                root.addView(dim, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Exception ignored) {}
    }

    // ---- last-input-first: listen for Sony's INPUT_CHANGED broadcast (no permission needed) ----
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
            TextView header; RecyclerView row;
            VH(LinearLayout v, TextView h, RecyclerView r) { super(v); header = h; row = r; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            LinearLayout col = new LinearLayout(p.getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView h = new TextView(p.getContext());
            h.setTextColor(theme.textDimColor);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            h.setPadding(dp(6), dp(18), 0, dp(10));
            col.addView(h);
            RecyclerView r = new RecyclerView(p.getContext());
            if ("grid".equals(theme.listStyle)) {
                r.setLayoutManager(new GridLayoutManager(p.getContext(), 5));
            } else {
                r.setLayoutManager(new LinearLayoutManager(p.getContext(), RecyclerView.HORIZONTAL, false));
            }
            r.setClipToPadding(false);
            r.setPadding(0, dp(6), 0, dp(6));
            col.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(col, h, r);
        }

        @Override public void onBindViewHolder(@NonNull VH vh, int pos) {
            Section s = data.get(pos);
            vh.header.setText(s.title);
            vh.row.setAdapter(new TileAdapter(s.tiles));
        }
        @Override public int getItemCount() { return data.size(); }
    }

    class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
        final List<Tile> tiles;
        TileAdapter(List<Tile> t) { tiles = t; }

        class VH extends RecyclerView.ViewHolder {
            FrameLayout card; ImageView img; TextView label;
            VH(View v, FrameLayout c, ImageView i, TextView l) { super(v); card = c; img = i; label = l; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            Context c = p.getContext();
            LinearLayout wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int w = dp(theme.tileWidthDp);
            RecyclerView.LayoutParams wlp = new RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.setMargins(dp(8), dp(8), dp(8), dp(8));
            wrap.setLayoutParams(wlp);

            FrameLayout card = new FrameLayout(c);
            card.setLayoutParams(new LinearLayout.LayoutParams(w, dp(theme.tileHeightDp)));
            card.setBackground(tileShape(false));
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);

            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView label = new TextView(c);
            label.setTextColor(theme.textColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setMaxLines(1);
            label.setPadding(dp(4), dp(6), dp(4), 0);
            label.setGravity(Gravity.START);

            wrap.addView(card);
            wrap.addView(label);
            return new VH(wrap, card, img, label);
        }

        @Override public void onBindViewHolder(@NonNull VH vh, int pos) {
            final Tile t = tiles.get(pos);
            vh.label.setText(t.label);
            if (t.icon != null) vh.img.setImageDrawable(t.icon);
            vh.img.setColorFilter(null);
            // Inputs / settings use a glyph-free tinted card with centered label inside
            if (t.kind != Tile.Kind.APP) {
                vh.img.setImageDrawable(null);
                vh.card.setBackground(tileShape(false));
            }
            vh.card.setOnClickListener(v -> {
                try { if (t.launch != null) startActivity(t.launch); } catch (Exception ignored) {}
            });
            vh.card.setOnFocusChangeListener((v, has) -> {
                v.setBackground(tileShape(has));
                if (theme.focusScale) {
                    float sc = has ? theme.focusScaleAmount : 1f;
                    v.animate().scaleX(sc).scaleY(sc).setDuration(theme.focusAnimMs).start();
                    vh.label.setTextColor(has ? theme.accentColor : theme.textColor);
                }
            });
        }
        @Override public int getItemCount() { return tiles.size(); }
    }

    private Drawable tileShape(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        // Slightly translucent when weather is on, so the live condition reads through the tile.
        int fill = theme.weather
                ? Color.argb(0xD8, Color.red(theme.surfaceColor), Color.green(theme.surfaceColor), Color.blue(theme.surfaceColor))
                : theme.surfaceColor;
        g.setColor(fill);
        g.setCornerRadius(dp(theme.cornerRadiusDp));
        if (focused) {
            // frosty ring when freezing, otherwise accent
            int ring = (theme.weather && curWx != null && curWx.isFreezing())
                    ? Color.parseColor("#9FE8FF") : theme.accentColor;
            g.setStroke(dp(3), ring);
        }
        return g;
    }
}
