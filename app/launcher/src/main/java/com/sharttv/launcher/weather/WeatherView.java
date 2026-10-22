package com.sharttv.launcher.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import java.util.Random;

/**
 * Animated weather + time-of-day backdrop. Sits BEHIND the shelves. Draws, per live condition:
 *   clear/night  - faint vignette, a few slow stars at night
 *   cloudy/overcast/fog - drifting translucent veils
 *   drizzle - sparse thin streaks + light bottom sheen (wet/dripy)
 *   rain - more/faster streaks + stronger sheen
 *   heavy_rain/storm - dense wind-slanted streaks, rising "flood" sheen, rare lightning
 *   snow - slow drifting flakes; blizzard - dense wind-slanted flakes + white vignette
 *   freezing (<=1C) - frost crystals from the edges + cyan chill (frozen look), over any category
 *
 * Perf: ~20 fps, preallocated particle arrays capped and scaled by intensity, simple primitives,
 * self-pauses when not visible. Never touches the network or the shelf render path.
 */
public class WeatherView extends View {

    private static final int FPS_MS = 50;        // ~20 fps
    private static final int MAX_DROPS = 90;
    private static final int MAX_FLAKES = 70;
    private static final int MAX_STARS = 40;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();

    private Wx wx;
    private int tint = Color.parseColor("#0B0E14");
    private boolean running;

    // particles: x,y,speed,len
    private final float[][] drops = new float[MAX_DROPS][4];
    private final float[][] flakes = new float[MAX_FLAKES][4];
    private final float[][] stars = new float[MAX_STARS][3];
    private int nDrops, nFlakes, nStars;
    private float wind = 0f;           // horizontal slant
    private float flood = 0f;          // rising accumulation 0..1
    private float flashAlpha = 0f;     // lightning
    private long lastFlash = 0;
    private float frost = 0f;          // 0..1 frost growth

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            advance();
            invalidate();
            postDelayed(this, FPS_MS);
        }
    };

    public WeatherView(Context c) { super(c); setFocusable(false); setClickable(false); }

    public void setWeather(Wx w) {
        this.wx = w;
        reseed();
    }
    public void setTint(int t) { this.tint = t; }

    public void startAnim() { if (running) return; running = true; post(tick); }
    public void stopAnim() { running = false; removeCallbacks(tick); }

    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) startAnim(); else stopAnim();
    }
    @Override protected void onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow(); }

    private void reseed() {
        float in = wx == null ? 0f : wx.intensity;
        String cat = wx == null ? "clear" : wx.category;
        boolean rainy = cat.equals("drizzle") || cat.equals("rain") || cat.equals("heavy_rain") || cat.equals("storm");
        boolean snowy = cat.equals("snow") || cat.equals("blizzard");
        boolean night = cat.equals("clear_night") || cat.equals("cloudy_night");

        nDrops = rainy ? (int) (18 + in * (MAX_DROPS - 18)) : 0;
        nFlakes = snowy ? (int) (15 + in * (MAX_FLAKES - 15)) : 0;
        nStars = (night || cat.equals("clear_night")) ? MAX_STARS : 0;
        wind = (wx != null ? (float) Math.min(1.0, wx.windKmh / 60.0) : 0f) * (cat.equals("storm") || cat.equals("blizzard") ? 1f : 0.5f);

        int w = getWidth() > 0 ? getWidth() : 1920, h = getHeight() > 0 ? getHeight() : 1080;
        for (int i = 0; i < nDrops; i++) {
            drops[i][0] = rnd.nextInt(w); drops[i][1] = rnd.nextInt(h);
            drops[i][2] = 16 + rnd.nextFloat() * 22 * (1 + in); drops[i][3] = 10 + rnd.nextFloat() * 22 * (1 + in);
        }
        for (int i = 0; i < nFlakes; i++) {
            flakes[i][0] = rnd.nextInt(w); flakes[i][1] = rnd.nextInt(h);
            flakes[i][2] = 1.2f + rnd.nextFloat() * 2.4f * (1 + in); flakes[i][3] = 2 + rnd.nextFloat() * 3;
        }
        for (int i = 0; i < nStars; i++) {
            stars[i][0] = rnd.nextInt(w); stars[i][1] = rnd.nextInt(h * 2 / 3);
            stars[i][2] = rnd.nextFloat();
        }
        frost = (wx != null && wx.isFreezing()) ? 1f : 0f;
        flood = 0f;
    }

    private void advance() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        String cat = wx == null ? "clear" : wx.category;
        float in = wx == null ? 0f : wx.intensity;

        for (int i = 0; i < nDrops; i++) {
            drops[i][1] += drops[i][2];
            drops[i][0] += wind * drops[i][2] * 0.5f;
            if (drops[i][1] > h) { drops[i][1] = -drops[i][3]; drops[i][0] = rnd.nextInt(w); }
        }
        for (int i = 0; i < nFlakes; i++) {
            flakes[i][1] += flakes[i][2];
            flakes[i][0] += Math.sin(flakes[i][1] / 40.0) * 1.5 + wind * 2.0;
            if (flakes[i][1] > h) { flakes[i][1] = -5; flakes[i][0] = rnd.nextInt(w); }
        }
        // flooding accumulation for heavy rain / storm
        float targetFlood = (cat.equals("heavy_rain") || cat.equals("storm")) ? 0.10f + in * 0.12f : 0f;
        flood += (targetFlood - flood) * 0.02f;
        // lightning for storm
        if (cat.equals("storm")) {
            long now = System.currentTimeMillis();
            if (flashAlpha <= 0 && now - lastFlash > 4000 && rnd.nextInt(30) == 0) { flashAlpha = 0.6f; lastFlash = now; }
        }
        if (flashAlpha > 0) flashAlpha -= 0.06f;
        // twinkle
        for (int i = 0; i < nStars; i++) { stars[i][2] += (rnd.nextFloat() - 0.5f) * 0.1f; if (stars[i][2] < 0) stars[i][2] = 0; if (stars[i][2] > 1) stars[i][2] = 1; }
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        // 1) time-of-day sky gradient
        p.setShader(new LinearGradient(0, 0, 0, h, shade(tint, 1.15f), shade(tint, 0.7f), Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);

        String cat = wx == null ? "clear" : wx.category;
        boolean clearish = cat.equals("clear") || cat.equals("clear_night") || cat.equals("cloudy");

        // 1b) horizon glow band (warm by day, cool by night) — gives depth/artistry
        int glow = (wx != null && wx.isDay) ? Color.argb(70, 255, 190, 120) : Color.argb(60, 90, 120, 200);
        p.setShader(new LinearGradient(0, h * 0.5f, 0, h * 0.72f, Color.argb(0, 0, 0, 0), glow, Shader.TileMode.CLAMP));
        cv.drawRect(0, h * 0.5f, w, h * 0.72f, p);
        p.setShader(null);

        // 1c) celestial body (sun by day / moon by night), arcing by time of day
        if (clearish || cat.equals("overcast")) {
            float frac = (System.currentTimeMillis() % 86400000L) / 86400000f;
            float cx = w * (0.15f + 0.7f * frac);
            float cy = h * (0.42f - 0.22f * (float) Math.sin(Math.PI * frac));
            boolean day = wx == null || wx.isDay;
            float rad = h * 0.07f;
            // halo
            p.setShader(new android.graphics.RadialGradient(cx, cy, rad * 3f,
                    day ? Color.argb(80, 255, 225, 150) : Color.argb(60, 200, 220, 255),
                    Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, rad * 3f, p); p.setShader(null);
            p.setColor(day ? Color.argb(230, 255, 236, 170) : Color.argb(220, 225, 232, 250));
            cv.drawCircle(cx, cy, rad, p);
            if (!day) { // moon crater shadow
                p.setColor(shade(tint, 0.9f)); cv.drawCircle(cx + rad * 0.4f, cy - rad * 0.3f, rad * 0.92f, p);
            }
        }

        // 2) clouds/fog veils
        if (cat.equals("overcast") || cat.equals("cloudy") || cat.equals("fog") || cat.equals("storm")) {
            p.setColor(Color.argb(cat.equals("fog") ? 60 : 40, 180, 188, 200));
            float drift = (float) (Math.sin(System.currentTimeMillis() / 4000.0) * 60);
            for (int i = 0; i < 3; i++) cv.drawCircle(w * (0.2f + 0.3f * i) + drift, h * 0.25f, h * 0.35f, p);
        }

        // 3) stars (night)
        for (int i = 0; i < nStars; i++) {
            p.setColor(Color.argb((int) (120 * stars[i][2]) + 40, 255, 255, 255));
            cv.drawCircle(stars[i][0], stars[i][1], 1.6f, p);
        }

        // 4) rain streaks
        if (nDrops > 0) {
            p.setStrokeWidth(cat.equals("heavy_rain") || cat.equals("storm") ? 2.4f : 1.6f);
            int a = cat.equals("drizzle") ? 70 : 130;
            p.setColor(Color.argb(a, 170, 205, 255));
            for (int i = 0; i < nDrops; i++)
                cv.drawLine(drops[i][0], drops[i][1], drops[i][0] + wind * drops[i][3], drops[i][1] + drops[i][3], p);
        }

        // 5) snow flakes
        if (nFlakes > 0) {
            p.setColor(Color.argb(200, 255, 255, 255));
            for (int i = 0; i < nFlakes; i++) cv.drawCircle(flakes[i][0], flakes[i][1], flakes[i][3], p);
        }

        // 6) wet bottom sheen + flooding
        if (cat.equals("drizzle") || cat.equals("rain") || cat.equals("heavy_rain") || cat.equals("storm")) {
            int sheenA = cat.equals("drizzle") ? 40 : 90;
            p.setShader(new LinearGradient(0, h * (1 - 0.18f), 0, h, Color.argb(0, 60, 110, 180),
                    Color.argb(sheenA, 40, 90, 170), Shader.TileMode.CLAMP));
            cv.drawRect(0, h * (1 - 0.18f), w, h, p);
            p.setShader(null);
            if (flood > 0.001f) {
                p.setColor(Color.argb((int) (120 * Math.min(1f, flood * 3)), 30, 80, 150));
                cv.drawRect(0, h * (1 - flood), w, h, p);
            }
        }

        // 7) frost / frozen look (cold), over everything
        if (frost > 0.01f) {
            // bluish chill vignette
            p.setShader(new android.graphics.RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                    Color.argb(0, 180, 230, 255), Color.argb((int) (90 * frost), 150, 210, 255), Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
            // crystalline edges
            p.setStrokeWidth(1.4f); p.setColor(Color.argb((int) (120 * frost), 210, 240, 255));
            int n = 22;
            for (int i = 0; i < n; i++) {
                float x = w * i / (float) n;
                cv.drawLine(x, 0, x + 18, 40 + (i % 5) * 8, p);
                cv.drawLine(x, h, x - 18, h - 40 - (i % 5) * 8, p);
            }
        }

        // 8) lightning flash
        if (flashAlpha > 0) { cv.drawColor(Color.argb((int) (255 * Math.min(0.7f, flashAlpha)), 235, 240, 255)); }

        // 9) gentle edge vignette for legibility of shelves
        p.setShader(new android.graphics.RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.8f,
                Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private static int shade(int c, float f) {
        return Color.argb(255,
            (int) Math.min(255, Color.red(c) * f),
            (int) Math.min(255, Color.green(c) * f),
            (int) Math.min(255, Color.blue(c) * f));
    }
}
