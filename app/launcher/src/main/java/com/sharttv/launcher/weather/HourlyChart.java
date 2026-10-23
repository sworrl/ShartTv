package com.sharttv.launcher.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import com.sharttv.launcher.ui.Semantic;

/**
 * The next N hours at a glance, sized to whatever space it's given: a smooth temperature curve
 * with each hour's value (colored by temperature), rain-chance bars along the bottom, an icon per
 * hour, and hour labels. Used in the home weather card and the Weather screen.
 */
public class HourlyChart extends View {

    private Wx wx;
    private int hours = 12;
    private int text = 0xFFF4ECDC, dim = 0xB0E8E0D0, accent = 0xFFE9C46A;
    private Typeface display = Typeface.SERIF, body = Typeface.SERIF;
    private boolean h24;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d, sd;

    public HourlyChart(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        sd = c.getResources().getDisplayMetrics().scaledDensity;
        h24 = android.text.format.DateFormat.is24HourFormat(c);
    }

    public void setWeather(Wx w) { wx = w; invalidate(); }
    public void setHours(int n) { hours = n; invalidate(); }
    public void setColors(int text, int dim, int accent) { this.text = text; this.dim = dim; this.accent = accent; invalidate(); }
    public void setFonts(Typeface display, Typeface body) { if (display != null) this.display = display; if (body != null) this.body = body; }
    public void set24h(boolean b) { h24 = b; invalidate(); }

    private static int f(double c) { return (int) Math.round(c * 9 / 5 + 32); }

    @Override protected void onDraw(Canvas cv) {
        if (wx == null || wx.hrAt == null || wx.hrAt.length < 2) return;
        int n = Math.min(hours, wx.hrAt.length);
        float w = getWidth(), h = getHeight();
        float padX = 10 * d;
        float labelH = 13 * sd;          // hour labels (bottom)
        float popH = Math.max(10 * d, h * 0.16f);  // rain bars
        float iconH = 15 * sd;           // icons (top)
        float valH = 12 * sd;            // temp values above the curve
        float top = iconH + valH + 4 * d, bot = h - labelH - popH - 6 * d;
        if (bot - top < 8 * d) { top = valH + 2 * d; iconH = 0; }
        float step = (w - 2 * padX) / (n - 1);

        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { double t = wx.hrTempC[i]; if (!Double.isNaN(t)) { lo = Math.min(lo, t); hi = Math.max(hi, t); } }
        if (hi - lo < 2) { hi += 1; lo -= 1; }

        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = padX + i * step;
            double t = Double.isNaN(wx.hrTempC[i]) ? lo : wx.hrTempC[i];
            ys[i] = (float) (bot - (t - lo) / (hi - lo) * (bot - top));
        }

        // rain-chance bars
        float barBase = h - labelH - 2 * d;
        for (int i = 0; i < n; i++) {
            int pop = wx.hrPop == null ? -1 : wx.hrPop[i];
            if (pop <= 0) continue;
            float bh = popH * pop / 100f;
            p.setColor((Semantic.precip(pop) & 0xFFFFFF) | 0xB0000000);
            float bw = Math.max(3 * d, step * 0.42f);
            cv.drawRoundRect(new RectF(xs[i] - bw / 2, barBase - bh, xs[i] + bw / 2, barBase), 2 * d, 2 * d, p);
            if (pop >= 30 && step > 26 * d) txt(cv, pop + "%", xs[i], barBase - bh - 2 * d, 8.5f, (Semantic.precip(pop) & 0xFFFFFF) | 0xFF000000, body, Paint.Align.CENTER);
        }

        // smooth curve + soft fill
        Path line = new Path();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            float cx = (xs[i - 1] + xs[i]) / 2;
            line.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i]);
        }
        Path fill = new Path(line);
        fill.lineTo(xs[n - 1], bot + 2 * d);
        fill.lineTo(xs[0], bot + 2 * d);
        fill.close();
        p.setShader(new LinearGradient(0, top, 0, bot, (accent & 0xFFFFFF) | 0x50000000, accent & 0xFFFFFF, Shader.TileMode.CLAMP));
        cv.drawPath(fill, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * d);
        p.setColor(accent);
        cv.drawPath(line, p);
        p.setStyle(Paint.Style.FILL);

        // points, values, icons, hour labels (thinned so labels never collide)
        int every = Math.max(1, (int) Math.ceil(30 * d / step));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < n; i++) {
            int tf = f(wx.hrTempC[i]);
            p.setColor(Semantic.temp(tf));
            cv.drawCircle(xs[i], ys[i], 2.6f * d, p);
            if (i % every != 0 && i != n - 1) continue;
            txt(cv, tf + "°", xs[i], ys[i] - 5 * d, 11, Semantic.temp(tf), display, Paint.Align.CENTER);
            cal.setTimeInMillis(wx.hrAt[i]);
            int hr = cal.get(java.util.Calendar.HOUR_OF_DAY);
            if (iconH > 0) txt(cv, glyph(wx.hrCode[i], hr), xs[i], iconH - 1 * d, 11, text, body, Paint.Align.CENTER);
            String lab = i == 0 ? "Now" : h24 ? String.format(java.util.Locale.US, "%02d", hr)
                    : (hr % 12 == 0 ? 12 : hr % 12) + (hr < 12 ? "a" : "p");
            txt(cv, lab, xs[i], h - 2 * d, 10, i == 0 ? accent : dim, body, Paint.Align.CENTER);
        }
    }

    /** Clear and mostly-clear hours after sunset show the moon instead of the sun. */
    private String glyph(int code, int hour) {
        int sec = hour * 3600 + 1800;
        boolean night = wx.sunriseSec >= 0 && wx.sunsetSec >= 0 && (sec < wx.sunriseSec || sec > wx.sunsetSec);
        if (night && code >= 0 && code <= 1) return "\u263E";
        if (night && code == 2) return "\u2601";
        return Wx.glyph(code);
    }

    private void txt(Canvas cv, String s, float x, float y, float sp, int color, Typeface tf, Paint.Align a) {
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(tf);
        tp.setTextSize(sp * sd);
        tp.setColor(color);
        tp.setTextAlign(a);
        tp.setShadowLayer(3 * d, 0, d, 0xAA000000);
        cv.drawText(s, x, y, tp);
    }
}
