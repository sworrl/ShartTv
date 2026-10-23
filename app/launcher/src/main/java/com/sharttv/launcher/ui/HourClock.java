package com.sharttv.launcher.ui;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.View;
import java.util.Calendar;

/**
 * The top-right hour clock. Every digit change is animated in the chosen style, and the top of the
 * hour is an event: over the last ten seconds a tick bar under the clock fills one tick a second,
 * then the digits roll over right-to-left like a mechanical carry (17:59 -> 18:00), with a light
 * sheen and glow pulse. A hairline under the clock fills across each hour.
 *
 * Colors follow the 24-hour TimeSky accent ({@link #setAccent}, called every second), so the clock
 * drifts with the backdrop. Styles are self-contained draw routines; add one by extending STYLES and
 * {@link #drawSlot}. Ticks itself only while visible; draws per-frame only while animating.
 */
public class HourClock extends View {

    public static final String[] STYLES = {"flap", "odometer", "ember", "nixie", "gothic", "classic"};
    public static final String[] NAMES  = {"Split-flap", "Odometer", "Ember", "Nixie", "Gothic", "Classic"};

    private static final int SLOTS = 5;                // "H:MM" with a leading slot, or "HH:MM"
    private static final long CHANGE_MS = 760, CARRY_STAGGER_MS = 150, FLOURISH_MS = 1900;

    private String style = "flap", format = "auto";
    private int textColor = 0xFFFFF3E0, surface = 0xFF0E242C, accent = 0xFFFFC56B;
    private final float d;
    private float sizePx;

    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final RectF win = new RectF();

    // layout (px), recomputed when style/size change
    private float slotW, colonW, digitTop, digitBottom, pad, lineGap;
    private LinearGradient drum;

    // animation state, per slot
    private char[] shown = "     ".toCharArray(), from = "     ".toCharArray();
    private final long[] start = new long[SLOTS];
    private long flourishStart = -1;
    private int lastHour = -1, lastSec = -1;
    private float hourFrac;          // 0..1 through the current hour
    private int countdownLit;        // 0 = not counting down, else 1..10 ticks lit

    // preview: run the clock 11s before the next hour, then snap back to real time
    private long offsetMs = 0, demoUntil = 0;
    private boolean ticking;

    public HourClock(Context c, float textSizeSp) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        sizePx = textSizeSp * c.getResources().getDisplayMetrics().scaledDensity;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        configure();
    }

    // ---------- public API ----------

    public void setStyle(String s) {
        for (String k : STYLES) if (k.equals(s)) { style = s; configure(); requestLayout(); invalidate(); return; }
    }

    /** "auto" (follow the TV's 12/24h setting), "12", or "24". */
    public void setFormat(String f) { format = f == null ? "auto" : f; snap(); }

    public void setPalette(int text, int surfaceColor) { textColor = text; surface = surfaceColor; invalidate(); }

    /** The 24-hour time-of-day accent; called once a second. */
    public void setAccent(int a) { if (a != accent) { accent = a; invalidate(); } }

    /** Preview an hour rollover now: jumps to {@code leadMs} before the next hour, plays it, then
     *  returns to real time. */
    public void demo(long leadMs) {
        long real = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(real);
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.HOUR_OF_DAY, 1);
        offsetMs = c.getTimeInMillis() - leadMs - real;
        demoUntil = real + leadMs + 3_500;
        snap();
        restartTick();
    }

    // ---------- time ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (demoUntil != 0 && System.currentTimeMillis() > demoUntil) { demoUntil = 0; offsetMs = 0; snap(); }
            update(true);
            long now = System.currentTimeMillis() + offsetMs;
            postDelayed(this, 1000 - (now % 1000) + 8);   // land just after each second boundary
        }
    };

    private boolean is24() {
        if ("24".equals(format)) return true;
        if ("12".equals(format)) return false;
        return DateFormat.is24HourFormat(getContext());
    }

    private String text(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        if (!is24()) { h %= 12; if (h == 0) h = 12; }
        String s = (is24() && h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
        while (s.length() < SLOTS) s = " " + s;
        return s;
    }

    /** Snap to the current time with no animation. */
    private void snap() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis() + offsetMs);
        shown = text(c).toCharArray();
        from = shown.clone();
        java.util.Arrays.fill(start, 0);
        flourishStart = -1;
        lastHour = c.get(Calendar.HOUR_OF_DAY);
        update(false);
    }

    private void update(boolean animate) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis() + offsetMs);
        int hour = c.get(Calendar.HOUR_OF_DAY), min = c.get(Calendar.MINUTE), sec = c.get(Calendar.SECOND);
        hourFrac = (min * 60 + sec) / 3600f;
        countdownLit = (min == 59 && sec >= 50) ? sec - 49 : 0;
        char[] next = text(c).toCharArray();
        long now = SystemClock.uptimeMillis();
        if (animate) {
            boolean newHour = lastHour != -1 && hour != lastHour;
            int rank = 0;
            for (int i = SLOTS - 1; i >= 0; i--) {   // right to left: the carry propagates leftward
                if (next[i] == shown[i]) continue;
                from[i] = shown[i];
                start[i] = now + rank * CARRY_STAGGER_MS;
                rank++;
            }
            if (newHour) flourishStart = now + (rank - 1) * CARRY_STAGGER_MS;
        }
        shown = next;
        lastHour = hour;
        if (sec != lastSec) { lastSec = sec; invalidate(); }
    }

    private void restartTick() { removeCallbacks(tick); if (ticking) post(tick); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); ticking = true; snap(); restartTick(); }
    @Override protected void onDetachedFromWindow() { ticking = false; removeCallbacks(tick); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        ticking = v == VISIBLE && isAttachedToWindow();
        if (ticking) { snap(); restartTick(); } else removeCallbacks(tick);
    }

    // ---------- layout ----------

    private Typeface face() {
        switch (style) {
            case "gothic": return Fonts.blackletter;
            case "nixie":  return Typeface.create("sans-serif-light", Typeface.NORMAL);
            default:       return Fonts.display != null ? Fonts.display : Typeface.SERIF;
        }
    }

    private boolean windowed() { return style.equals("flap") || style.equals("odometer") || style.equals("nixie"); }

    private void configure() {
        digit.setTypeface(face());
        digit.setTextSize(style.equals("gothic") ? sizePx * 1.15f : sizePx);
        digit.setTextAlign(Paint.Align.CENTER);
        digit.setFakeBoldText(!style.equals("nixie") && !style.equals("gothic"));
        float max = 0;
        for (char ch = '0'; ch <= '9'; ch++) max = Math.max(max, digit.measureText(String.valueOf(ch)));
        Rect b = new Rect();
        digit.getTextBounds("0123456789", 0, 10, b);
        digitTop = b.top; digitBottom = b.bottom;
        pad = windowed() ? sizePx * 0.16f : sizePx * 0.06f;
        slotW = max + (windowed() ? sizePx * 0.30f : sizePx * 0.06f);
        colonW = digit.measureText(":") + sizePx * 0.18f;
        lineGap = 5 * d;
        drum = null;
    }

    private float contentW() { return (SLOTS - 1) * slotW + colonW; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int glow = (int) (6 * d);
        int w = (int) Math.ceil(contentW()) + glow * 2;
        int h = (int) Math.ceil((digitBottom - digitTop) + pad * 2 + lineGap + 3 * d) + glow * 2;
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    // ---------- drawing ----------

    @Override protected void onDraw(Canvas cv) {
        long now = SystemClock.uptimeMillis();
        float x = (getWidth() - contentW()) / 2f;
        float x0 = x;
        float baseline = (getHeight() - lineGap - 3 * d) / 2f - (digitTop + digitBottom) / 2f;
        win.top = baseline + digitTop - pad;
        win.bottom = baseline + digitBottom + pad;

        float fl = flourishStart < 0 ? -1 : (now - flourishStart) / (float) FLOURISH_MS;
        if (fl > 1) { flourishStart = -1; fl = -1; }
        boolean animating = fl >= 0 || countdownLit > 0 || (flourishStart > 0 && now < flourishStart);
        float glowBoost = fl > 0 ? (float) Math.sin(Math.PI * fl) : 0;

        int layer = fl > 0 ? cv.saveLayer(0, 0, getWidth(), getHeight(), null) : -1;

        for (int i = 0; i < SLOTS; i++) {
            boolean colon = shown[i] == ':';
            float w = colon ? colonW : slotW;
            float p = 1f;
            if (start[i] != 0) {
                p = (now - start[i]) / (float) CHANGE_MS;
                if (p >= 1) { p = 1; start[i] = 0; from[i] = shown[i]; } else animating = true;
                if (p < 0) p = 0;
            }
            if (colon) drawColon(cv, x + w / 2, baseline, glowBoost);
            else drawSlot(cv, i, from[i], shown[i], p, start[i] != 0 && now >= start[i], x, w, baseline, glowBoost);
            x += w;
        }

        if (layer >= 0) {   // the hour's light sheen, only over what was just drawn
            float band = contentW() * 0.45f, e = ease(fl);
            float sx = x0 - band + (contentW() + band * 2) * e;
            fx.setShader(new LinearGradient(sx - band / 2, 0, sx + band / 2, 0,
                    new int[]{0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            fx.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            cv.drawRect(0, 0, getWidth(), getHeight(), fx);
            fx.setXfermode(null); fx.setShader(null);
            cv.restoreToCount(layer);
        }

        drawHourLine(cv, x0, win.bottom + lineGap, fl);
        if (animating) postInvalidateOnAnimation();
    }

    /** Hairline that fills across the hour; becomes a 10-tick countdown, then drains on the hour. */
    private void drawHourLine(Canvas cv, float x0, float y, float fl) {
        float w = contentW(), th = 1.6f * d;
        fx.setStyle(Paint.Style.FILL);
        fx.setShadowLayer(0, 0, 0, 0);
        if (countdownLit > 0) {
            float gap = 2.5f * d, tw = (w - gap * 9) / 10f;
            for (int k = 0; k < 10; k++) {
                boolean lit = k < countdownLit;
                fx.setColor(lit ? accent : Glass.withAlpha(textColor, 0x30));
                if (lit) fx.setShadowLayer(4 * d, 0, 0, accent);
                float tx = x0 + k * (tw + gap);
                cv.drawRoundRect(tx, y, tx + tw, y + th * 1.4f, th, th, fx);
                fx.setShadowLayer(0, 0, 0, 0);
            }
            return;
        }
        fx.setColor(Glass.withAlpha(textColor, 0x26));
        cv.drawRoundRect(x0, y, x0 + w, y + th, th, th, fx);
        float f = fl >= 0 ? 1f - ease(fl) * (1f - hourFrac) : hourFrac;   // on the hour: full, then drain
        fx.setColor(Glass.withAlpha(accent, fl >= 0 ? 0xFF : 0xC0));
        if (fl >= 0) fx.setShadowLayer(6 * d, 0, 0, accent);
        cv.drawRoundRect(x0, y, x0 + Math.max(th, w * f), y + th, th, th, fx);
        fx.setShadowLayer(0, 0, 0, 0);
    }

    private void drawColon(Canvas cv, float cx, float baseline, float glowBoost) {
        // during the countdown the colon breathes with the seconds
        float a = countdownLit > 0 ? 0.55f + 0.45f * (float) Math.abs(Math.cos(Math.PI * (SystemClock.uptimeMillis() % 1000) / 1000f)) : 1f;
        float r = Math.max(1.6f * d, sizePx * 0.07f);
        float mid = baseline + (digitTop + digitBottom) / 2f, sp = (digitBottom - digitTop) * 0.22f;
        int col = style.equals("nixie") ? nixie() : digitColor();
        fx.setColor(Glass.withAlpha(col, (int) (255 * a)));
        float glow = (style.equals("nixie") || style.equals("ember") ? 5 * d : 0) + glowBoost * 8 * d;
        if (glow > 0) fx.setShadowLayer(glow, 0, 0, style.equals("nixie") ? nixie() : accent);
        if (style.equals("gothic")) {   // diamonds, not dots
            for (float cy : new float[]{mid - sp, mid + sp}) {
                android.graphics.Path dm = new android.graphics.Path();
                dm.moveTo(cx, cy - r * 1.5f); dm.lineTo(cx + r, cy); dm.lineTo(cx, cy + r * 1.5f); dm.lineTo(cx - r, cy); dm.close();
                cv.drawPath(dm, fx);
            }
        } else {
            cv.drawCircle(cx, mid - sp, r, fx);
            cv.drawCircle(cx, mid + sp, r, fx);
        }
        fx.setShadowLayer(0, 0, 0, 0);
    }

    private void drawSlot(Canvas cv, int i, char o, char n, float p, boolean started,
                          float x, float w, float baseline, float glowBoost) {
        if (n == ' ' && o == ' ') return;
        win.left = x + w * 0.06f; win.right = x + w - w * 0.06f;
        float cx = x + w / 2, H = win.height();
        switch (style) {
            case "flap":     flap(cv, o, n, p, started, cx, baseline, glowBoost); break;
            case "odometer": odometer(cv, o, n, p, cx, baseline, H, glowBoost); break;
            case "ember":    ember(cv, i, o, n, p, cx, w, baseline, H, glowBoost); break;
            case "nixie":    nixieSlot(cv, o, n, p, cx, baseline, glowBoost); break;
            case "gothic":   gothic(cv, o, n, p, cx, baseline, H, glowBoost); break;
            default:         classic(cv, o, n, p, cx, baseline, H, glowBoost); break;
        }
    }

    // --- Split-flap: dark cards hinged at the middle; the top leaf falls, the next one lands.
    private void flap(Canvas cv, char o, char n, float p, boolean started, float cx, float baseline, float gb) {
        if (n == ' ' && (p >= 1 || o == ' ')) return;
        float hinge = win.centerY(), r = 3.5f * d;
        char top = started ? n : o, bottom = p < 1 ? o : n;
        half(cv, top, true, cx, baseline, r, gb, 0);
        half(cv, bottom, false, cx, baseline, r, gb, 0);
        if (started && p < 1) {
            float e = p < 0.5f ? accel(p * 2) : 1;
            boolean firstLeaf = p < 0.5f;
            float angle = firstLeaf ? e * 90f : 90f - outBounce((p - 0.5f) * 2) * 90f;
            cv.save();
            camera.save();
            camera.rotateX(firstLeaf ? -angle : angle);
            camera.getMatrix(matrix);
            camera.restore();
            matrix.preTranslate(-cx, -hinge);
            matrix.postTranslate(cx, hinge);
            cv.concat(matrix);
            half(cv, firstLeaf ? o : n, firstLeaf, cx, baseline, r, gb, angle / 90f * 0.55f);
            cv.restore();
        }
        fx.setColor(0xCC000000);   // the hinge gap
        cv.drawRect(win.left, hinge - 0.6f * d, win.right, hinge + 0.6f * d, fx);
    }

    private void half(Canvas cv, char ch, boolean top, float cx, float baseline, float r, float gb, float shade) {
        float hinge = win.centerY();
        cv.save();
        cv.clipRect(win.left - d, top ? win.top - d : hinge, win.right + d, top ? hinge : win.bottom + d);
        fx.setShader(new LinearGradient(0, win.top, 0, win.bottom,
                Glass.lighten(surface, 1.5f) | 0xFF000000, Glass.darken(surface, 0.55f) | 0xFF000000, Shader.TileMode.CLAMP));
        cv.drawRoundRect(win, r, r, fx);
        fx.setShader(null);
        fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(d);
        fx.setColor(Glass.withAlpha(accent, 0x70));
        cv.drawRoundRect(win, r, r, fx);
        fx.setStyle(Paint.Style.FILL);
        if (ch != ' ') text(cv, ch, cx, baseline, digitColor(), 255, gb * 8 * d, accent);
        if (shade > 0) { fx.setColor(Color.argb((int) (255 * shade), 0, 0, 0)); cv.drawRect(win, fx); }
        cv.restore();
    }

    // --- Odometer: digits roll up through a recessed drum window, settling with a little overshoot.
    private void odometer(Canvas cv, char o, char n, float p, float cx, float baseline, float H, float gb) {
        float r = 4 * d;
        fx.setColor(Glass.withAlpha(Glass.darken(surface, 0.45f), 0xE6));
        cv.drawRoundRect(win, r, r, fx);
        cv.save();
        cv.clipRect(win);
        if (p >= 1) text(cv, n, cx, baseline, digitColor(), 255, gb * 8 * d, accent);
        else {
            float e = outBack(p);
            text(cv, o, cx, baseline - e * H, digitColor(), (int) (255 * (1 - p)), 0, 0);
            text(cv, n, cx, baseline + (1 - e) * H, digitColor(), 255, gb * 8 * d, accent);
        }
        if (drum == null) drum = new LinearGradient(0, win.top, 0, win.bottom,
                new int[]{0xD0000000, 0x00000000, 0x00000000, 0xD0000000}, new float[]{0, 0.3f, 0.7f, 1}, Shader.TileMode.CLAMP);
        fx.setShader(drum);
        cv.drawRect(win, fx);
        fx.setShader(null);
        cv.restore();
        fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(d);
        fx.setColor(Glass.withAlpha(accent, 0x80));
        cv.drawRoundRect(win, r, r, fx);
        fx.setStyle(Paint.Style.FILL);
    }

    // --- Ember: the old digit burns off upward in sparks; the new one condenses out of the glow.
    private void ember(Canvas cv, int slot, char o, char n, float p, float cx, float w, float baseline, float H, float gb) {
        float glow = 5 * d + gb * 10 * d;
        if (p >= 1) { text(cv, n, cx, baseline, digitColor(), 255, glow, accent); return; }
        float e = inOutCubic(p);
        cv.save(); cv.scale(1 + 0.25f * e, 1 + 0.25f * e, cx, baseline - H * 0.4f);
        text(cv, o, cx, baseline - 0.35f * H * e, digitColor(), (int) (255 * (1 - e)), glow * (1 + e), accent);
        cv.restore();
        cv.save(); cv.scale(0.8f + 0.2f * e, 0.8f + 0.2f * e, cx, baseline - H * 0.4f);
        text(cv, n, cx, baseline + 0.25f * H * (1 - e), digitColor(), (int) (255 * e), glow, accent);
        cv.restore();
        for (int k = 0; k < 9; k++) {   // deterministic sparks per slot
            float rx = frac(Math.sin((slot + 1) * 12.9898 + k * 78.233) * 43758.5453);
            float ry = frac(Math.sin((slot + 1) * 39.346 + k * 11.135) * 24634.6345);
            float px = cx + (rx - 0.5f) * w * 0.9f;
            float py = baseline - H * 0.3f - p * H * (0.5f + ry);
            fx.setColor(Glass.withAlpha(Glass.lighten(accent, 1.3f), (int) (230 * (1 - p))));
            fx.setShadowLayer(3 * d, 0, 0, accent);
            cv.drawCircle(px, py, d * (0.7f + ry), fx);
            fx.setShadowLayer(0, 0, 0, 0);
        }
    }

    // --- Nixie: glass tubes with the unlit cathode stack ghosted behind; the new digit strikes with
    // a flicker.
    private void nixieSlot(Canvas cv, char o, char n, float p, float cx, float baseline, float gb) {
        float r = win.width() / 2.2f;
        fx.setShader(new LinearGradient(0, win.top, 0, win.bottom,
                new int[]{0xC0141018, 0xC0080608, 0xC0141018}, null, Shader.TileMode.CLAMP));
        cv.drawRoundRect(win, r, r, fx);
        fx.setShader(null);
        fx.setStyle(Paint.Style.STROKE); fx.setStrokeWidth(0.8f * d);
        fx.setColor(0x40FFFFFF);
        cv.drawRoundRect(win, r, r, fx);
        fx.setStyle(Paint.Style.FILL);
        // cathode stack
        digit.setStyle(Paint.Style.STROKE); digit.setStrokeWidth(0.6f * d);
        digit.setColor(Glass.withAlpha(Glass.darken(nixie(), 0.6f), 0x2A));
        for (char ch = '0'; ch <= '9'; ch++) if (ch != n) cv.drawText(String.valueOf(ch), cx, baseline, digit);
        digit.setStyle(Paint.Style.FILL);
        float glow = 9 * d + gb * 10 * d;
        if (p < 1) {
            if (p < 0.4f && o != ' ') text(cv, o, cx, baseline, nixie(), (int) (255 * (1 - p / 0.4f)), glow, nixie());
            if (p > 0.3f) {
                float a = p < 0.65f ? ((int) (p * 37) % 2 == 0 ? 0.35f : 0.9f) : 1f;   // strike flicker
                lit(cv, n, cx, baseline, (int) (255 * a), glow);
            }
        } else lit(cv, n, cx, baseline, 255, glow);
    }

    private void lit(Canvas cv, char ch, float cx, float baseline, int alpha, float glow) {
        if (ch == ' ') return;
        text(cv, ch, cx, baseline, nixie(), alpha, glow, nixie());
        text(cv, ch, cx, baseline, Glass.lighten(nixie(), 1.6f), alpha, 0, 0);   // hot core
    }

    // --- Gothic: blackletter in gilt; the new numeral is inked in top to bottom behind a bright nib line.
    private void gothic(Canvas cv, char o, char n, float p, float cx, float baseline, float H, float gb) {
        int col = gilt();
        if (p >= 1) { text(cv, n, cx, baseline, col, 255, 3 * d + gb * 9 * d, accent); return; }
        float e = inOutCubic(p);
        text(cv, o, cx, baseline + 0.12f * H * e, col, (int) (255 * (1 - e)), 3 * d, accent);
        float sweep = win.top + (win.height() + 2 * d) * e;
        cv.save();
        cv.clipRect(win.left - 4 * d, win.top - 8 * d, win.right + 4 * d, sweep);
        text(cv, n, cx, baseline, col, 255, 3 * d, accent);
        cv.restore();
        fx.setColor(Glass.withAlpha(Glass.lighten(accent, 1.4f), (int) (255 * Math.sin(Math.PI * e))));
        fx.setShadowLayer(5 * d, 0, 0, accent);
        cv.drawRect(win.left, sweep - 0.7f * d, win.right, sweep + 0.7f * d, fx);
        fx.setShadowLayer(0, 0, 0, 0);
    }

    // --- Classic: soft crossfade with a short slide.
    private void classic(Canvas cv, char o, char n, float p, float cx, float baseline, float H, float gb) {
        if (p >= 1) { text(cv, n, cx, baseline, digitColor(), 255, gb * 8 * d, accent); return; }
        float e = inOutCubic(p);
        text(cv, o, cx, baseline - 0.25f * H * e, digitColor(), (int) (255 * (1 - e)), 0, 0);
        text(cv, n, cx, baseline + 0.25f * H * (1 - e), digitColor(), (int) (255 * e), gb * 8 * d, accent);
    }

    private void text(Canvas cv, char ch, float cx, float baseline, int color, int alpha, float glow, int glowColor) {
        if (ch == ' ' || alpha <= 0) return;
        digit.setColor(color);
        digit.setAlpha(Math.min(255, alpha));
        if (glow > 0) digit.setShadowLayer(glow, 0, 0, Glass.withAlpha(glowColor, Math.min(255, alpha)));
        cv.drawText(String.valueOf(ch), cx, baseline, digit);
        if (glow > 0) digit.setShadowLayer(0, 0, 0, 0);
    }

    // ---------- color ----------

    /** Warm-white text pulled toward the time-of-day accent. */
    private int digitColor() { return blend(textColor, accent, 0.4f); }

    /** Gilt for the gothic face: gold leaning into the time-of-day hue. */
    private int gilt() { return blend(0xFFE6C068, accent, 0.35f); }

    /** Neon from the accent hue: saturated and bright, like a lit cathode. */
    private int nixie() {
        float[] h = new float[3];
        Color.colorToHSV(accent, h);
        h[1] = Math.max(0.75f, h[1]); h[2] = 1f;
        return Color.HSVToColor(h);
    }

    private static int blend(int a, int b, float t) {
        return Color.argb(255,
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ---------- easing ----------

    private static float ease(float t) { return inOutCubic(Math.max(0, Math.min(1, t))); }
    private static float inOutCubic(float t) { return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }
    private static float accel(float t) { return t * t; }
    private static float outBack(float t) { float c1 = 1.5f, c3 = c1 + 1; return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2); }
    private static float outBounce(float t) {
        if (t < 1 / 2.75f) return 7.5625f * t * t;
        if (t < 2 / 2.75f) { t -= 1.5f / 2.75f; return 7.5625f * t * t + 0.75f; }
        if (t < 2.5 / 2.75) { t -= 2.25f / 2.75f; return 7.5625f * t * t + 0.9375f; }
        t -= 2.625f / 2.75f; return 7.5625f * t * t + 0.984375f;
    }
    private static float frac(double v) { return (float) (v - Math.floor(v)); }
}
