package com.sharttv.launcher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.sharttv.launcher.ui.Fonts;
import com.sharttv.launcher.ui.Glass;
import com.sharttv.launcher.ui.HourClock;
import com.sharttv.launcher.ui.Semantic;
import com.sharttv.launcher.weather.Loc;
import com.sharttv.launcher.weather.TimeSky;
import com.sharttv.launcher.weather.WeatherEngine;
import com.sharttv.launcher.weather.Wx;
import java.util.Locale;

/**
 * The top-of-the-hour chime: a few seconds before each hour a small glass card slides in at the
 * top-right OVER WHATEVER IS PLAYING, shows the {@link HourClock} rolling over (17:59 -> 18:00) with
 * the weather now and for the coming hour, then fades away. Drawn as an accessibility overlay from
 * {@link HomeRedirectService}, so it needs no extra permission; the window is not focusable or
 * touchable, so the remote, playback, and audio are untouched.
 */
class HourChime {

    private static final long LEAD_MS = 3_000;       // appears this long before the hour
    private static final long HOLD_MS = 3_200;       // stays this long after the rollover
    private static final long CHECK_MS = 30_000;     // re-derive the schedule (survives sleep/clock changes)
    private static final long HOUR = 3_600_000L;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout card;
    private HourClock clock;
    private Loc loc;
    private long refreshedFor;
    private String previewStyle;
    /** True while our own home is in front: it already shows the clock. */
    boolean homeInFront;

    HourChime(Context serviceCtx) {
        ctx = serviceCtx;
        wm = (WindowManager) serviceCtx.getSystemService(Context.WINDOW_SERVICE);
    }

    void start() { h.removeCallbacksAndMessages(null); h.post(check); }

    void stop() { h.removeCallbacksAndMessages(null); removeNow(); }

    /** Show it right now as if the hour were about to turn (from the Appearance screen). */
    void preview() { preview(null); }

    /** As {@link #preview()}, in a given clock style instead of the theme's (null = the theme's). */
    void preview(String style) {
        previewStyle = style;
        h.removeCallbacks(check);
        show(true);
    }

    private static long nextHour(long now) { return (now / HOUR + 1) * HOUR; }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long hour = nextHour(now);
            long dt = hour - LEAD_MS - now;
            if (dt < 0) { hour += HOUR; dt += HOUR; }   // too late for this one
            if (dt > 60_000 && dt < 150_000 && refreshedFor != hour) {   // fresh weather for the card
                refreshedFor = hour;
                new WeatherEngine(ctx).refreshIfOlderThan(20 * 60_000);
            }
            if (dt <= CHECK_MS) h.postDelayed(() -> show(false), dt);
            else h.postDelayed(this, CHECK_MS);
        }
    };

    private void show(boolean preview) {
        Theme t = Theme.load(ctx);
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();
        if (!preview && (!t.hourChime || !screenOn || homeInFront)) { h.postDelayed(check, LEAD_MS + 1_000); return; }
        removeNow();
        try {
            card = build(t, preview);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(40); lp.y = dp(24);
            wm.addView(card, lp);
            card.setAlpha(0f);
            card.setTranslationY(-dp(10));
            card.animate().alpha(1f).translationY(0).setDuration(380).start();
            h.post(tint);
            h.postDelayed(this::hide, LEAD_MS + HOLD_MS);
        } catch (Exception e) {
            card = null;
            h.postDelayed(check, LEAD_MS + 1_000);
        }
    }

    private final Runnable tint = new Runnable() {
        @Override public void run() {
            if (card == null) return;
            int accent = TimeSky.now(loc).accent;
            clock.setAccent(accent);
            card.setBackground(Glass.card(Theme.load(ctx).surfaceColor, accent, false, dp(18)));
            h.postDelayed(this, 1000);
        }
    };

    private void hide() {
        final View v = card;
        if (v == null) return;
        v.animate().alpha(0f).translationY(-dp(10)).setDuration(420).withEndAction(() -> {
            removeNow();
            h.postDelayed(check, 1_000);
        }).start();
    }

    private void removeNow() {
        h.removeCallbacks(tint);
        if (card != null) { try { wm.removeViewImmediate(card); } catch (Exception ignored) {} }
        card = null;
    }

    // ---------- the card ----------

    private LinearLayout build(Theme t, boolean preview) {
        Fonts.load(ctx);
        Glass.init(ctx);
        WeatherEngine we = new WeatherEngine(ctx);
        Wx wx = we.readCachedWx();
        loc = we.readCachedLoc();
        int accent = TimeSky.now(loc).accent;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(18), dp(8));
        row.setBackground(Glass.card(t.surfaceColor, accent, false, dp(18)));
        Glass.clipRound(row, dp(18));
        clock = new HourClock(ctx, 30);
        clock.setStyle(preview && previewStyle != null ? previewStyle : t.clockStyle);
        clock.setFormat(t.clockFormat);
        clock.setPalette(t.textColor, t.surfaceColor);
        clock.setAccent(accent);
        row.addView(clock);

        // weather: now + the coming hour
        long theHour = nextHour(System.currentTimeMillis() + LEAD_MS / 2);   // the hour that's about to start
        CharSequence now = nowLine(t, wx);
        CharSequence next = hourLine(t, wx, theHour + HOUR);
        if (now != null || next != null) {
            View rule = new View(ctx);
            rule.setBackgroundColor(Glass.withAlpha(t.textColor, 0x33));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(1), dp(40));
            rlp.setMargins(dp(10), 0, dp(14), 0);
            row.addView(rule, rlp);
            LinearLayout wcol = new LinearLayout(ctx);
            wcol.setOrientation(LinearLayout.VERTICAL);
            if (now != null) wcol.addView(line(t, now));
            if (next != null) wcol.addView(line(t, next));
            row.addView(wcol);
        }

        if (preview) clock.demo(LEAD_MS);
        return row;
    }

    private TextView line(Theme t, CharSequence s) {
        TextView tv = new TextView(ctx);
        tv.setTypeface(Fonts.body, Typeface.BOLD);
        tv.setTextColor(t.textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setSingleLine(true);
        tv.setText(s);
        tv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    /** "NOW  ⛅ 54°" from the live snapshot if it's recent, else from the hourly forecast. */
    private CharSequence nowLine(Theme t, Wx wx) {
        if (wx == null) return null;
        boolean fresh = System.currentTimeMillis() - wx.fetchedAt < 75 * 60_000L;
        if (fresh) return weatherLine(t, "NOW", wx.icon, f(wx.tempC), -1);
        int i = wx.hourIndex(System.currentTimeMillis());
        if (i < 0) return null;
        return weatherLine(t, "NOW", Wx.glyph(wx.hrCode[i]), f(wx.hrTempC[i]), -1);
    }

    /** "7 PM  🌧 52°  40%" for the hour after the one that's starting. */
    private CharSequence hourLine(Theme t, Wx wx, long hourStart) {
        if (wx == null) return null;
        int i = wx.hourIndex(hourStart);
        if (i < 0 || Double.isNaN(wx.hrTempC[i])) return null;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(hourStart);
        int hr = c.get(java.util.Calendar.HOUR_OF_DAY);
        boolean h24 = "24".equals(t.clockFormat) || ("auto".equals(t.clockFormat) && android.text.format.DateFormat.is24HourFormat(ctx));
        String label = h24 ? String.format(Locale.US, "%02d:00", hr) : ((hr % 12 == 0 ? 12 : hr % 12) + (hr < 12 ? " AM" : " PM"));
        return weatherLine(t, label, Wx.glyph(wx.hrCode[i]), f(wx.hrTempC[i]), wx.hrPop[i]);
    }

    private CharSequence weatherLine(Theme t, String label, String glyph, int tempF, int pop) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        span(sb, label + "  ", t.textDimColor, 0.72f);
        sb.append(glyph).append(" ");
        span(sb, tempF + "°", Semantic.temp(tempF), 1f);
        if (pop >= 10) { sb.append("  "); span(sb, pop + "%", Semantic.precip(pop), 0.8f); }
        return sb;
    }

    private static void span(SpannableStringBuilder sb, String s, int color, float size) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (size != 1f) sb.setSpan(new RelativeSizeSpan(size), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int f(double c) { return (int) Math.round(c * 9 / 5 + 32); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
