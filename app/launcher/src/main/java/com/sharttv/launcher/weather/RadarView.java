package com.sharttv.launcher.weather;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Our own precipitation radar, drawn natively so it matches the glass/gothic look: a dark map
 * (Esri's keyless dark-gray canvas, tinted to the theme's night navy) under RainViewer's last ~2 hours of radar, played as
 * a crossfading loop, with a pulsing marker where the TV is, a time chip, a frame scrubber and a
 * legend. Keyless. Refetches every 10 minutes while it's on screen.
 */
public class RadarView extends View {

    private static final int Z = 7;                 // RainViewer's free tiles stop at zoom 7
    private static final int TILE = 256;
    private static final int COLOR_SCHEME = 4;      // The Weather Channel palette
    private static final long FRAME_MS = 520, HOLD_MS = 1800, FADE_MS = 360;
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final int RADAR_DOWNSCALE = 2;
    private static final String ESRI = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/";   // radar is blobby; half-res frames keep memory small

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float d;
    private double lat = Double.NaN, lon = Double.NaN;
    private int accent = 0xFFE9C46A, text = 0xFFF4ECDC, surface = 0xFF101826;
    private Typeface display = Typeface.SERIF, body = Typeface.SERIF;

    private volatile Bitmap base;                   // tinted map + labels, view-sized
    private final List<Bitmap> frames = new ArrayList<>();
    private final List<Long> frameTimes = new ArrayList<>();
    private int builtW, builtH;
    private double builtLat = Double.NaN, builtLon = Double.NaN;
    private long lastFetch;
    private boolean running, loading;
    private long t0;
    private ExecutorService pool;

    public RadarView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
    }

    public void setLocation(double lat, double lon) {
        boolean moved = Double.isNaN(this.lat) || Math.abs(this.lat - lat) > 0.05 || Math.abs(this.lon - lon) > 0.05;
        this.lat = lat; this.lon = lon;
        if (moved) { lastFetch = 0; maybeLoad(); }
    }

    public void setPalette(int text, int surface, int accent) { this.text = text; this.surface = surface; this.accent = accent; invalidate(); }
    public void setFonts(Typeface display, Typeface body) { if (display != null) this.display = display; if (body != null) this.body = body; }

    public void start() {
        if (running) return;
        running = true;
        t0 = System.currentTimeMillis();
        if (pool == null) pool = Executors.newFixedThreadPool(4);
        maybeLoad();
        h.post(tick);
    }

    public void stop() {
        running = false;
        h.removeCallbacks(tick);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
        if (pool != null) { pool.shutdownNow(); pool = null; }
    }

    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { lastFetch = 0; maybeLoad(); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (System.currentTimeMillis() - lastFetch > REFRESH_MS) maybeLoad();
            invalidate();
            h.postDelayed(this, 50);
        }
    };

    // ---------- loading ----------

    private void maybeLoad() {
        final int w = getWidth(), hh = getHeight();
        if (!running || loading || Double.isNaN(lat) || w == 0 || hh == 0 || pool == null) return;
        loading = true;
        lastFetch = System.currentTimeMillis();
        final double la = lat, lo = lon;
        final boolean newBase = base == null || w != builtW || hh != builtH || la != builtLat || lo != builtLon;
        new Thread(() -> {
            try {
                Bitmap b = newBase ? buildBase(la, lo, w, hh) : base;
                List<Long> times = new ArrayList<>();
                List<Bitmap> fr = buildFrames(la, lo, w, hh, times);
                h.post(() -> {
                    if (b != null) { base = b; builtW = w; builtH = hh; builtLat = la; builtLon = lo; }
                    if (!fr.isEmpty()) {
                        for (Bitmap old : frames) if (!fr.contains(old)) old.recycle();
                        frames.clear(); frames.addAll(fr);
                        frameTimes.clear(); frameTimes.addAll(times);
                        t0 = System.currentTimeMillis();
                    }
                    loading = false;
                    invalidate();
                });
            } catch (Exception e) {
                h.post(() -> loading = false);
            }
        }, "sharttv-radar").start();
    }

    /** Pixel position of lat/lon at zoom Z, in global tile pixels. */
    private static double px(double lon) { return (lon + 180) / 360 * (1 << Z) * TILE; }
    private static double py(double lat) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << Z) * TILE;
    }

    private Bitmap buildBase(double la, double lo, int w, int hh) throws Exception {
        Bitmap out = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.rgb(8, 12, 22));
        // night navy: darken, shift toward blue
        ColorMatrix navy = new ColorMatrix(new float[]{
                0.55f, 0, 0, 0, 4,
                0, 0.68f, 0, 0, 10,
                0, 0, 0.95f, 0, 26,
                0, 0, 0, 1, 0});
        Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
        mp.setColorFilter(new ColorMatrixColorFilter(navy));
        // Esri tiles are z/y/x
        drawTiles(cv, la, lo, w, hh, ESRI + "World_Dark_Gray_Base/MapServer/tile/%1$d/%3$d/%2$d", mp);
        // labels in warm parchment
        Paint lp = new Paint(Paint.FILTER_BITMAP_FLAG);
        lp.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0, 0, 0, 0, 0xF4,
                0, 0, 0, 0, 0xE6,
                0, 0, 0, 0, 0xC8,
                0.33f, 0.33f, 0.33f, 0.75f, -10})));
        drawTiles(cv, la, lo, w, hh, ESRI + "World_Dark_Gray_Reference/MapServer/tile/%1$d/%3$d/%2$d", lp);
        return out;
    }

    private List<Bitmap> buildFrames(double la, double lo, int w, int hh, List<Long> times) throws Exception {
        HttpURLConnection c = open("https://api.rainviewer.com/public/weather-maps.json");
        org.json.JSONObject o = new org.json.JSONObject(read(c));
        String host = o.getString("host");
        org.json.JSONArray past = o.getJSONObject("radar").getJSONArray("past");
        List<Bitmap> out = new ArrayList<>();
        int sw = Math.max(1, w / RADAR_DOWNSCALE), sh = Math.max(1, hh / RADAR_DOWNSCALE);
        for (int i = 0; i < past.length(); i++) {
            org.json.JSONObject f = past.getJSONObject(i);
            String tmpl = host + f.getString("path") + "/256/%1$d/%2$d/%3$d/" + COLOR_SCHEME + "/1_1.png";
            Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.scale(1f / RADAR_DOWNSCALE, 1f / RADAR_DOWNSCALE);
            drawTiles(cv, la, lo, w, hh, tmpl, new Paint(Paint.FILTER_BITMAP_FLAG));
            out.add(b);
            times.add(f.getLong("time") * 1000L);
        }
        return out;
    }

    /** Fetch every tile covering a w x hh window centered on la/lo (in parallel) and draw them. */
    private void drawTiles(Canvas cv, double la, double lo, int w, int hh, String tmpl, Paint paint) throws Exception {
        double cx = px(lo), cy = py(la);
        double ox = cx - w / 2.0, oy = cy - hh / 2.0;
        int tx0 = (int) Math.floor(ox / TILE), ty0 = (int) Math.floor(oy / TILE);
        int tx1 = (int) Math.floor((ox + w) / TILE), ty1 = (int) Math.floor((oy + hh) / TILE);
        int n = 1 << Z;
        List<Future<Bitmap>> fs = new ArrayList<>();
        List<int[]> at = new ArrayList<>();
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = tx0; tx <= tx1; tx++) {
                final String url = String.format(java.util.Locale.US, tmpl, Z, ((tx % n) + n) % n, ty);
                fs.add(pool.submit(() -> fetch(url)));
                at.add(new int[]{(int) Math.round(tx * TILE - ox), (int) Math.round(ty * TILE - oy)});
            }
        }
        for (int i = 0; i < fs.size(); i++) {
            Bitmap t = fs.get(i).get();
            if (t == null) continue;
            int[] xy = at.get(i);
            cv.drawBitmap(t, null, new Rect(xy[0], xy[1], xy[0] + TILE, xy[1] + TILE), paint);
            t.recycle();
        }
    }

    private static Bitmap fetch(String url) {
        try {
            HttpURLConnection c = open(url);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return BitmapFactory.decodeStream(in); }
        } catch (Exception e) { return null; }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "ShartTV/0.1");
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    // ---------- drawing ----------

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), hh = getHeight();
        if (base != null) cv.drawBitmap(base, 0, 0, p);
        else {
            cv.drawColor(Color.rgb(8, 12, 22));
            label(cv, "Loading radar…", w / 2f, hh / 2f, 15, Paint.Align.CENTER, alpha(text, 0xB0), body);
        }

        // radar loop: each frame FRAME_MS, the newest held HOLD_MS, crossfading FADE_MS
        int nf = frames.size();
        int cur = -1;
        if (nf > 0) {
            long cycle = (nf - 1) * FRAME_MS + HOLD_MS;
            long t = (System.currentTimeMillis() - t0) % cycle;
            cur = (int) Math.min(nf - 1, t / FRAME_MS);
            long into = t - cur * FRAME_MS;
            Rect dst = new Rect(0, 0, w, hh);
            p.setAlpha(215);
            cv.drawBitmap(frames.get(cur), null, dst, p);
            if (cur < nf - 1 && into > FRAME_MS - FADE_MS) {
                p.setAlpha((int) (215 * (into - (FRAME_MS - FADE_MS)) / (float) FADE_MS));
                cv.drawBitmap(frames.get(cur + 1), null, dst, p);
            } else if (cur == nf - 1 && into > HOLD_MS - FADE_MS) {
                p.setAlpha((int) (215 * (into - (HOLD_MS - FADE_MS)) / (float) FADE_MS));
                cv.drawBitmap(frames.get(0), null, dst, p);
            }
            p.setAlpha(255);
        }

        // soft vignette so the map melts into the glass card
        p.setShader(new RadialGradient(w / 2f, hh / 2f, Math.max(w, hh) * 0.72f,
                new int[]{0x00000000, 0x00000000, 0xB0050812}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, hh, p);
        p.setShader(null);

        // the TV: a gold pin with a slow pulse
        float mx = w / 2f, my = hh / 2f;
        float ph = ((System.currentTimeMillis() % 2400) / 2400f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(alpha(accent, (int) (200 * (1 - ph))));
        cv.drawCircle(mx, my, (6 + 22 * ph) * d, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC000000);
        cv.drawCircle(mx, my, 7 * d, p);
        p.setColor(accent);
        cv.drawCircle(mx, my, 5 * d, p);

        // top-left chip: RADAR + frame time
        String when = cur >= 0 ? android.text.format.DateFormat.getTimeFormat(getContext())
                .format(new java.util.Date(frameTimes.get(cur))) : "";
        String ago = "";
        if (cur >= 0) {
            long mins = Math.round((System.currentTimeMillis() - frameTimes.get(cur)) / 60_000.0);
            ago = mins <= 5 ? "now" : "−" + mins + " min";
        }
        float cx0 = 14 * d, cy0 = 14 * d;
        RectF chip = new RectF(cx0, cy0, cx0 + 190 * d, cy0 + 50 * d);
        p.setColor(alpha(surface, 0xC8));
        cv.drawRoundRect(chip, 14 * d, 14 * d, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1 * d); p.setColor(alpha(accent, 0x90));
        cv.drawRoundRect(chip, 14 * d, 14 * d, p);
        p.setStyle(Paint.Style.FILL);
        label(cv, "RADAR", chip.left + 14 * d, chip.top + 20 * d, 12, Paint.Align.LEFT, accent, display);
        label(cv, when, chip.left + 14 * d, chip.top + 40 * d, 16, Paint.Align.LEFT, text, body);
        label(cv, ago, chip.right - 14 * d, chip.top + 40 * d, 13, Paint.Align.RIGHT, alpha(text, 0xB0), body);

        // bottom: frame scrubber + legend + attribution, on a dark band so map labels don't fight them
        p.setShader(new LinearGradient(0, hh - 70 * d, 0, hh, 0x00050812, 0xE6050812, Shader.TileMode.CLAMP));
        cv.drawRect(0, hh - 70 * d, w, hh, p);
        p.setShader(null);
        float bx = 18 * d, by = hh - 44 * d, bw = w - 36 * d;
        if (nf > 1) {
            p.setColor(alpha(text, 0x30));
            cv.drawRoundRect(new RectF(bx, by, bx + bw, by + 3 * d), 2 * d, 2 * d, p);
            float fx = bx + bw * cur / (float) (nf - 1);
            p.setColor(accent);
            cv.drawRoundRect(new RectF(bx, by, fx, by + 3 * d), 2 * d, 2 * d, p);
            cv.drawCircle(fx, by + 1.5f * d, 5 * d, p);
        }
        float ly = hh - 26 * d, lw = Math.min(220 * d, bw * 0.5f);
        p.setShader(new LinearGradient(bx, 0, bx + lw, 0,
                new int[]{0xFF3BAA3B, 0xFF1F7A1F, 0xFFE8E83A, 0xFFF29A1F, 0xFFE0281E, 0xFFB01A8C},
                null, Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(bx, ly, bx + lw, ly + 6 * d), 3 * d, 3 * d, p);
        p.setShader(null);
        label(cv, "light", bx, ly + 19 * d, 11, Paint.Align.LEFT, alpha(text, 0xB0), body);
        label(cv, "heavy", bx + lw, ly + 19 * d, 11, Paint.Align.RIGHT, alpha(text, 0xB0), body);
        label(cv, "RainViewer · Esri, HERE, Garmin", w - 14 * d, ly + 19 * d, 10,
                Paint.Align.RIGHT, alpha(text, 0x80), body);
    }

    private void label(Canvas cv, String s, float x, float y, float sp, Paint.Align a, int color, Typeface tf) {
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(tf);
        tp.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        tp.setTextAlign(a);
        tp.setColor(color);
        tp.setLetterSpacing(tf == display ? 0.14f : 0.02f);
        tp.setShadowLayer(4 * d, 0, d, 0xCC000000);
        cv.drawText(s, x, y, tp);
    }

    private static int alpha(int rgb, int a) { return (a << 24) | (rgb & 0xFFFFFF); }
}
