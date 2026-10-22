package com.sharttv.launcher.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Lightweight weather + location engine.
 *   Location: BeaconFix (mDNS _beaconfix._tcp, or a configured URL) -> manual lat/lon -> cache.
 *   Weather:  Open-Meteo current endpoint (HTTPS, no key, SSL-pinned in network_security_config).
 * All network runs off the UI thread; results are cached to prefs. Fetch cadence is minutes, so
 * this never touches the render loop.
 */
public class WeatherEngine {

    public interface Listener { void onWeather(Wx wx, Loc loc); }

    private final Context ctx;
    private final SharedPreferences sp;
    private Listener listener;
    private volatile boolean running;
    private Thread worker;

    private static final long REFRESH_MS = 10 * 60 * 1000;  // 10 min
    private static final int BEACONFIX_PORT = 47822;

    public WeatherEngine(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("sharttv_wx", Context.MODE_PRIVATE);
    }

    public void setListener(Listener l) { listener = l; }

    public void start() {
        if (running) return;
        running = true;
        // Emit cached immediately so the UI themes right away.
        Wx cached = readCachedWx();
        if (cached != null && listener != null) listener.onWeather(cached, readCachedLoc());
        worker = new Thread(this::loop, "sharttv-wx");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() { running = false; if (worker != null) worker.interrupt(); }

    private void loop() {
        while (running) {
            try {
                Loc loc = resolveLocation();
                if (loc != null && loc.valid) {
                    Wx wx = fetchWeather(loc.lat, loc.lon);
                    if (wx != null) {
                        wx.lat = loc.lat; wx.lon = loc.lon; wx.place = loc.place;
                        wx.classify();
                        cache(wx, loc);
                        final Wx fw = wx; final Loc fl = loc;
                        if (listener != null) post(() -> listener.onWeather(fw, fl));
                    }
                }
            } catch (Exception ignored) {}
            // sleep in small slices so stop() is responsive
            long end = System.currentTimeMillis() + REFRESH_MS;
            while (running && System.currentTimeMillis() < end) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void post(Runnable r) {
        new android.os.Handler(ctx.getMainLooper()).post(r);
    }

    // ---------- Location ----------

    private Loc resolveLocation() {
        // 1) configured BeaconFix URL (most reliable on a fixed LAN)
        String url = sp.getString("beaconfixUrl", "");
        if (!url.isEmpty()) { Loc l = beaconfix(url); if (l != null && l.valid) return l; }
        // 2) mDNS discovery (best-effort, short)
        String disco = discoverBeaconfix();
        if (disco != null) { Loc l = beaconfix(disco); if (l != null && l.valid) return l; }
        // 3) manual lat/lon
        float mlat = sp.getFloat("manualLat", Float.NaN);
        float mlon = sp.getFloat("manualLon", Float.NaN);
        if (!Float.isNaN(mlat) && !Float.isNaN(mlon)) {
            Loc l = new Loc(); l.valid = true; l.lat = mlat; l.lon = mlon; l.source = "manual"; l.place = "Set location";
            return l;
        }
        // 4) last cached
        return readCachedLoc();
    }

    private Loc beaconfix(String base) {
        try {
            if (!base.startsWith("http")) base = "http://" + base;
            if (base.matches("https?://[^/:]+")) base = base + ":" + BEACONFIX_PORT;
            String token = sp.getString("beaconfixToken", "");
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/v1/location").openConnection();
            c.setConnectTimeout(2500); c.setReadTimeout(3000);
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (c.getResponseCode() != 200) return null;
            JSONObject o = new JSONObject(readAll(c));
            if (!o.optBoolean("valid", false)) return null;
            Loc l = new Loc();
            l.valid = true; l.lat = o.getDouble("lat"); l.lon = o.getDouble("lon");
            l.place = o.optString("place", ""); l.source = "beaconfix:" + o.optString("source", "");
            JSONObject sun = o.optJSONObject("sun");
            if (sun != null) {
                l.sunriseSec = hhmmToSec(sun.optString("sunrise", null));
                l.sunsetSec  = hhmmToSec(sun.optString("sunset", null));
                l.solarNoonSec = hhmmToSec(sun.optString("solarNoon", null));
                l.civilDawnSec = hhmmToSec(sun.optString("civilDawn", null));
                l.civilDuskSec = hhmmToSec(sun.optString("civilDusk", null));
            }
            return l;
        } catch (Exception e) { return null; }
    }

    private String discoverBeaconfix() {
        try {
            NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return null;
            final String[] host = {null};
            final Object lock = new Object();
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String s, int e) {}
                public void onStopDiscoveryFailed(String s, int e) {}
                public void onDiscoveryStarted(String s) {}
                public void onDiscoveryStopped(String s) {}
                public void onServiceLost(NsdServiceInfo s) {}
                public void onServiceFound(NsdServiceInfo s) {
                    try {
                        nsd.resolveService(s, new NsdManager.ResolveListener() {
                            public void onResolveFailed(NsdServiceInfo i, int e) {}
                            public void onServiceResolved(NsdServiceInfo i) {
                                synchronized (lock) {
                                    host[0] = i.getHost().getHostAddress() + ":" + i.getPort();
                                    lock.notifyAll();
                                }
                            }
                        });
                    } catch (Exception ignored) {}
                }
            };
            nsd.discoverServices("_beaconfix._tcp", NsdManager.PROTOCOL_DNS_SD, dl);
            synchronized (lock) { if (host[0] == null) lock.wait(3000); }
            try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) {}
            return host[0];
        } catch (Exception e) { return null; }
    }

    // ---------- Weather ----------

    private Wx fetchWeather(double lat, double lon) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude="
                    + String.format(java.util.Locale.US, "%.4f", lat) + "&longitude="
                    + String.format(java.util.Locale.US, "%.4f", lon)
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,"
                    + "precipitation,weather_code,cloud_cover,wind_speed_10m,wind_gusts_10m&timezone=auto";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "ShartTV/0.1");
            if (c.getResponseCode() != 200) return null;
            JSONObject root = new JSONObject(readAll(c));
            JSONObject cur = root.getJSONObject("current");
            Wx w = new Wx();
            w.tempC = cur.optDouble("temperature_2m", 0);
            w.feelsC = cur.optDouble("apparent_temperature", w.tempC);
            w.humidity = cur.optInt("relative_humidity_2m", 0);
            w.isDay = cur.optInt("is_day", 1) == 1;
            w.precipMm = cur.optDouble("precipitation", 0);
            w.code = cur.optInt("weather_code", 0);
            w.cloudPct = cur.optInt("cloud_cover", 0);
            w.windKmh = cur.optDouble("wind_speed_10m", 0);
            w.windGust = cur.optDouble("wind_gusts_10m", 0);
            w.fetchedAt = System.currentTimeMillis();
            return w;
        } catch (Exception e) { return null; }
    }

    // ---------- Cache ----------

    private void cache(Wx w, Loc l) {
        sp.edit()
          .putInt("w_code", w.code).putInt("w_day", w.isDay ? 1 : 0)
          .putFloat("w_temp", (float) w.tempC).putFloat("w_precip", (float) w.precipMm)
          .putFloat("w_wind", (float) w.windKmh).putInt("w_cloud", (int) w.cloudPct)
          .putLong("w_at", w.fetchedAt).putString("w_place", w.place)
          .putFloat("l_lat", (float) l.lat).putFloat("l_lon", (float) l.lon)
          .putInt("l_sunrise", l.sunriseSec).putInt("l_sunset", l.sunsetSec)
          .putInt("l_dawn", l.civilDawnSec).putInt("l_dusk", l.civilDuskSec)
          .putInt("l_noon", l.solarNoonSec)
          .apply();
    }

    public Wx readCachedWx() {
        if (!sp.contains("w_code")) return null;
        Wx w = new Wx();
        w.code = sp.getInt("w_code", -1); w.isDay = sp.getInt("w_day", 1) == 1;
        w.tempC = sp.getFloat("w_temp", 0); w.precipMm = sp.getFloat("w_precip", 0);
        w.windKmh = sp.getFloat("w_wind", 0); w.cloudPct = sp.getInt("w_cloud", 0);
        w.fetchedAt = sp.getLong("w_at", 0); w.place = sp.getString("w_place", "");
        w.classify();
        return w;
    }

    public Loc readCachedLoc() {
        if (!sp.contains("l_lat")) return null;
        Loc l = new Loc();
        l.valid = true; l.lat = sp.getFloat("l_lat", 0); l.lon = sp.getFloat("l_lon", 0);
        l.sunriseSec = sp.getInt("l_sunrise", -1); l.sunsetSec = sp.getInt("l_sunset", -1);
        l.civilDawnSec = sp.getInt("l_dawn", -1); l.civilDuskSec = sp.getInt("l_dusk", -1);
        l.solarNoonSec = sp.getInt("l_noon", -1);
        return l;
    }

    // ---------- helpers ----------

    private static String readAll(HttpURLConnection c) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close(); return sb.toString();
    }

    /** "2026-09-26T17:13:46" or "17:13" -> seconds since local midnight, -1 on failure. */
    private static int hhmmToSec(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            int t = s.indexOf('T');
            String hms = t >= 0 ? s.substring(t + 1) : s;
            String[] p = hms.split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            int sec = p.length > 2 ? Integer.parseInt(p[2].trim().substring(0, Math.min(2, p[2].trim().length()))) : 0;
            return h * 3600 + m * 60 + sec;
        } catch (Exception e) { return -1; }
    }
}
