package com.sharttv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sharttv.launcher.ui.CardFx;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import java.util.List;

/**
 * "Who's watching?" — pick, add, rename, recolor, or remove a household profile. Picking one
 * switches the home to that profile's theme, recents, and learned usage. The edit pills act on the
 * avatar that last had focus.
 */
public class ProfilesActivity extends Activity {

    private Theme theme;
    private String selectedId;
    private com.sharttv.launcher.weather.WeatherView sky;
    private com.sharttv.launcher.weather.Wx wx;
    private com.sharttv.launcher.weather.Loc loc;
    private final android.os.Handler h = new android.os.Handler();
    private final Runnable skyTick = new Runnable() {
        @Override public void run() {
            if (sky != null) sky.setTint(com.sharttv.launcher.weather.TimeSky.weatherAdjust(
                    com.sharttv.launcher.weather.TimeSky.now(loc).tint, wx));
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
        com.sharttv.launcher.weather.WeatherEngine we = new com.sharttv.launcher.weather.WeatherEngine(this);
        wx = we.readCachedWx();
        loc = we.readCachedLoc();
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (sky != null) sky.startAnim();
        h.removeCallbacks(skyTick); h.post(skyTick);
    }

    @Override protected void onPause() {
        super.onPause();
        if (sky != null) sky.stopAnim();
        h.removeCallbacks(skyTick);
    }

    private void build() {
        theme = Theme.load(this);
        if (selectedId == null) selectedId = Profiles.currentId(this);
        String curId = Profiles.currentId(this);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(theme.bgColor);
        if (sky != null) sky.stopAnim();
        sky = new com.sharttv.launcher.weather.WeatherView(this);
        if (wx != null) sky.setWeather(wx);
        root.addView(sky);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        root.addView(col, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setTypeface(Fonts.display, Typeface.BOLD);
        title.setText("WHO'S WATCHING?");
        title.setTextColor(theme.textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setLetterSpacing(0.18f);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(10), 0, 0, 0x99000000);
        title.setPadding(0, 0, 0, dp(34));
        col.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        View focusMe = null;
        List<Profiles.Profile> all = Profiles.all(this);
        for (final Profiles.Profile p : all) {
            View av = avatar(p.initial(), p.name, p.color, p.id.equals(curId));
            av.setOnClickListener(v -> CardFx.click(v, dp(60), p.color, () -> { Profiles.switchTo(this, p.id); finish(); }));
            av.setOnFocusChangeListener((v, has) -> {
                if (has) selectedId = p.id;
                CardFx.focus(v, has, 1.12f, dp(60), theme.accentColor, theme.focusAnimMs);
            });
            row.addView(av);
            if (p.id.equals(selectedId)) focusMe = av;
        }
        if (all.size() < 6) {
            View add = avatar("+", "Add", theme.textDimColor, false);
            add.setOnClickListener(v -> askName("New profile", "", name -> {
                Profiles.switchTo(this, Profiles.add(this, name));
                finish();
            }));
            add.setOnFocusChangeListener((v, has) -> CardFx.focus(v, has, 1.12f, dp(60), theme.accentColor, theme.focusAnimMs));
            row.addView(add);
        }
        col.addView(row);

        LinearLayout edit = new LinearLayout(this);
        edit.setOrientation(LinearLayout.HORIZONTAL);
        edit.setGravity(Gravity.CENTER);
        edit.setPadding(0, dp(34), 0, 0);
        edit.addView(pill("RENAME", v -> {
            Profiles.Profile p = find(selectedId);
            askName("Rename", p == null ? "" : p.name, name -> { Profiles.rename(this, selectedId, name); build(); });
        }));
        edit.addView(pill("COLOR", v -> { Profiles.cycleColor(this, selectedId); build(); }));
        if (all.size() > 1) edit.addView(pill("REMOVE", v -> {
            if (Profiles.FIRST.equals(selectedId)) return;   // the first profile always stays
            Profiles.Profile p = find(selectedId);
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove " + (p == null ? "profile" : p.name) + "?")
                    .setMessage("Its recents, learned apps, and theme are deleted.")
                    .setPositiveButton("Remove", (d, w) -> { Profiles.remove(this, selectedId); selectedId = null; build(); })
                    .setNegativeButton("Cancel", null)
                    .show();
        }));
        col.addView(edit);

        setContentView(root);
        if (sky != null && isResumedSafe()) sky.startAnim();
        if (focusMe != null) focusMe.requestFocus();
    }

    private boolean isResumedSafe() { return getWindow() != null && hasWindowFocus(); }

    private Profiles.Profile find(String id) {
        for (Profiles.Profile p : Profiles.all(this)) if (p.id.equals(id)) return p;
        return null;
    }

    private View avatar(String initial, String name, int color, boolean current) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setFocusable(true);
        cell.setPadding(dp(18), dp(10), dp(18), dp(10));

        TextView circle = new TextView(this);
        circle.setTypeface(Fonts.display, Typeface.BOLD);
        circle.setText(initial);
        circle.setGravity(Gravity.CENTER);
        circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        circle.setTextColor(theme.bgColor);
        // jewel avatar: a lit radial gradient in the profile color with a gilded rim
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(70));
        g.setGradientCenter(0.35f, 0.3f);
        g.setColors(new int[]{Glass.lighten(color, 1.3f), color, Glass.darken(color, 0.55f)});
        g.setStroke(dp(current ? 4 : 2), current ? 0xFFE9C46A : Glass.withAlpha(0xE9C46A, 0x90));
        circle.setBackground(g);
        circle.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        circle.setElevation(dp(current ? 14 : 6));
        cell.addView(circle, new LinearLayout.LayoutParams(dp(104), dp(104)));

        TextView label = new TextView(this);
        label.setTypeface(Fonts.body, Typeface.BOLD);
        label.setText(name);
        label.setTextColor(current ? theme.accentColor : theme.textColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(10), 0, 0);
        cell.addView(label, new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private View pill(String text, View.OnClickListener onClick) {
        final TextView pill = new TextView(this);
        pill.setTypeface(Fonts.body);
        pill.setText(text);
        pill.setTextColor(theme.textColor);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setLetterSpacing(0.08f);
        pill.setPadding(dp(16), dp(7), dp(16), dp(7));
        final float pr = dp(16);
        pill.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, false, pr));
        pill.setFocusable(true);
        pill.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Glass.card(theme.surfaceColor, theme.accentColor, has, pr));
            pill.setTextColor(has ? theme.accentColor : theme.textColor);
            CardFx.focus(v, has, 1.08f, pr, theme.accentColor, theme.focusAnimMs);
        });
        pill.setOnClickListener(v -> CardFx.click(v, pr, theme.accentColor, () -> onClick.onClick(v)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(8), 0);
        pill.setLayoutParams(lp);
        return pill;
    }

    private interface OnName { void got(String name); }

    private void askName(String title, String current, OnName cb) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        in.setSingleLine(true);
        in.setText(current);
        in.setSelection(current.length());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (!n.isEmpty()) cb.got(n.length() > 16 ? n.substring(0, 16) : n);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
