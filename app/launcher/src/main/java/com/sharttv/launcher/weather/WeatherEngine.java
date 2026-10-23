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
 *   Location: BeaconFix (mDNS _beaconfix._tcp, or a configured URL) -> manual lat/lon -> the TV's
 *             own network location (Wi-Fi based, needs Location on) -> cache -> seed.
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
    private volatile int gen;   // bumps on every start(); a loop from an older start() exits
    private Thread worker;
    private static final java.util.concurrent.atomic.AtomicBoolean ONCE_BUSY = new java.util.concurrent.atomic.AtomicBoolean();

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
        final int my = ++gen;
        worker = new Thread(() -> loop(my), "sharttv-wx");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() { running = false; if (worker != null) worker.interrupt(); }

    /** One fetch now, off-thread, if the cache is older than {@code maxAgeMs}. For callers that
     *  aren't on screen (the hour chime) and just need a fresh enough snapshot. */
    public void refreshIfOlderThan(long maxAgeMs) {
        if (System.currentTimeMillis() - sp.getLong("w_at", 0) < maxAgeMs) return;
        if (!ONCE_BUSY.compareAndSet(false, true)) return;   // one already in flight
        Thread t = new Thread(() -> {
            try { fetchOnce(); } catch (Exception ignored) {} finally { ONCE_BUSY.set(false); }
        }, "sharttv-wx-once");
        t.setDaemon(true);
        t.start();
    }

    private void fetchOnce() {
        Loc loc = resolveLocation();
        if (loc == null || !loc.valid) return;
        Wx wx = fetchWeather(loc.lat, loc.lon);
        if (wx == null) return;
        wx.lat = loc.lat; wx.lon = loc.lon; wx.place = loc.place;
        fetchEcmwf(wx, loc.lat, loc.lon);
        fetchAqi(wx, loc.lat, loc.lon);
        wx.classify();
        cache(wx, loc);
        android.util.Log.i("ShartWx", "bg refresh " + loc.source + " " + loc.place + " " + f(wx.tempC));
    }

    private static long f(double c) { return Math.round(c * 9 / 5 + 32); }

    private void loop(int my) {
        while (running && gen == my) {
            try {
                Loc loc = resolveLocation();
                android.util.Log.i("ShartWx", "loc=" + (loc==null?"null":(loc.source+" "+loc.lat+","+loc.lon+" "+loc.place)));
                if (loc != null && loc.valid) {
                    Wx wx = fetchWeather(loc.lat, loc.lon);
                    android.util.Log.i("ShartWx", "fetch=" + (wx==null?"NULL":("ok code="+wx.code+" fcDays="+(wx.fcDay==null?0:wx.fcDay.length))));
                    if (wx != null) {
                        wx.lat = loc.lat; wx.lon = loc.lon; wx.place = loc.place;
                        fetchEcmwf(wx, loc.lat, loc.lon);
                        fetchAqi(wx, loc.lat, loc.lon);
                        wx.classify();
                        cache(wx, loc);
                        final Wx fw = wx; final Loc fl = loc;
                        if (listener != null) post(() -> listener.onWeather(fw, fl));
                    }
                }
            } catch (Exception e) { android.util.Log.w("ShartWx", "refresh failed", e); }
            // sleep in small slices so stop() is responsive
            long end = System.currentTimeMillis() + REFRESH_MS;
            while (running && gen == my && System.currentTimeMillis() < end) {
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
        // 3b) the TV's own network location (follows the TV if it moves)
        Loc tv = tvLocation();
        if (tv != null) return tv;
        // 4) last cached
        Loc cached = readCachedLoc();
        if (cached != null && cached.valid) return cached;
        // 5) optional seed from app/location.properties (gitignored), so weather works before any fix
        try {
            String la = com.sharttv.launcher.BuildConfig.SEED_LAT, lo = com.sharttv.launcher.BuildConfig.SEED_LON;
            if (!la.isEmpty() && !lo.isEmpty()) {
                Loc seed = new Loc();
                seed.valid = true; seed.lat = Double.parseDouble(la); seed.lon = Double.parseDouble(lo);
                seed.place = com.sharttv.launcher.BuildConfig.SEED_PLACE; seed.source = "seed";
                return seed;
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /** Last-known (or a fresh one-shot) fix from the TV's network/fused provider, with a place name. */
    private Loc tvLocation() {
        try {
            if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null || !lm.isLocationEnabled()) return null;
            android.location.Location best = null;
            for (String p : new String[]{"fused", android.location.LocationManager.NETWORK_PROVIDER}) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Exception ignored) {}
            }
            if (best == null || System.currentTimeMillis() - best.getTime() > 30 * 60_000L) {
                android.location.Location fresh = currentLocation(lm);
                if (fresh != null) best = fresh;
            }
            if (best == null) return null;
            Loc l = new Loc();
            l.valid = true; l.lat = best.getLatitude(); l.lon = best.getLongitude();
            l.source = "tv:" + best.getProvider();
            l.place = placeName(l.lat, l.lon);
            return l;
        } catch (Exception e) { return null; }
    }

    private android.location.Location currentLocation(android.location.LocationManager lm) {
        if (android.os.Build.VERSION.SDK_INT < 30
                || !lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) return null;
        final android.location.Location[] out = {null};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            lm.getCurrentLocation(android.location.LocationManager.NETWORK_PROVIDER, null, ctx.getMainExecutor(),
                    l -> { out[0] = l; done.countDown(); });
            done.await(12, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return out[0];
    }

    /** "Springfield, IL" for a coordinate, cached per ~1 km so we don't re-ask on every fetch. */
    private String placeName(double lat, double lon) {
        String key = String.format(java.util.Locale.US, "geo_%.2f_%.2f", lat, lon);
        String hit = sp.getString(key, null);
        if (hit != null) return hit;
        String name = "";
        try {
            if (android.location.Geocoder.isPresent()) {
                java.util.List<android.location.Address> a =
                        new android.location.Geocoder(ctx, java.util.Locale.US).getFromLocation(lat, lon, 1);
                if (a != null && !a.isEmpty()) {
                    android.location.Address ad = a.get(0);
                    String town = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea();
                    name = join(town, state(ad.getAdminArea()));
                }
            }
        } catch (Exception ignored) {}
        if (name.isEmpty()) try {   // no Geocoder backend: a keyless reverse lookup
            HttpURLConnection c = (HttpURLConnection) new URL(String.format(java.util.Locale.US,
                    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=en",
                    lat, lon)).openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(4000);
            if (c.getResponseCode() == 200) {
                JSONObject o = new JSONObject(readAll(c));
                String town = o.optString("city", "");
                if (town.isEmpty()) town = o.optString("locality", "");
                String sub = o.optString("principalSubdivisionCode", "");
                name = join(town, sub.startsWith("US-") ? sub.substring(3) : o.optString("principalSubdivision", ""));
            }
        } catch (Exception ignored) {}
        if (!name.isEmpty()) sp.edit().putString(key, name).apply();
        return name;
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a.trim(); b = b == null ? "" : b.trim();
        return a.isEmpty() ? b : b.isEmpty() ? a : a + ", " + b;
    }

    private static final String STATES = "Alabama:AL,Alaska:AK,Arizona:AZ,Arkansas:AR,California:CA,Colorado:CO,"
            + "Connecticut:CT,Delaware:DE,Florida:FL,Georgia:GA,Hawaii:HI,Idaho:ID,Illinois:IL,Indiana:IN,Iowa:IA,"
            + "Kansas:KS,Kentucky:KY,Louisiana:LA,Maine:ME,Maryland:MD,Massachusetts:MA,Michigan:MI,Minnesota:MN,"
            + "Mississippi:MS,Missouri:MO,Montana:MT,Nebraska:NE,Nevada:NV,New Hampshire:NH,New Jersey:NJ,"
            + "New Mexico:NM,New York:NY,North Carolina:NC,North Dakota:ND,Ohio:OH,Oklahoma:OK,Oregon:OR,"
            + "Pennsylvania:PA,Rhode Island:RI,South Carolina:SC,South Dakota:SD,Tennessee:TN,Texas:TX,Utah:UT,"
            + "Vermont:VT,Virginia:VA,Washington:WA,West Virginia:WV,Wisconsin:WI,Wyoming:WY";

    /** "West Virginia" -> "WV"; anything else unchanged. */
    private static String state(String s) {
        if (s == null) return "";
        for (String kv : STATES.split(",")) {
            int i = kv.indexOf(':');
            if (kv.substring(0, i).equals(s)) return kv.substring(i + 1);
        }
        return s;
    }

    /** "Springfield, IL  ·  ECMWF  ·  Updated 7:52 PM" — the hero's place line, with how fresh it is. */
    public static String placeLine(Context c, Wx w, Loc l) { return placeLine(c, w, l, false); }

    /** {@code twoLines}: the place on its own line, then "Model · Updated 08:30" (for narrow cards). */
    public static String placeLine(Context c, Wx w, Loc l, boolean twoLines) {
        String sep = twoLines ? " · " : "   ·   ";
        String place = "";
        if (l != null) place = l.place != null && !l.place.isEmpty() ? l.place
                : String.format(java.util.Locale.US, "%.2f, %.2f", l.lat, l.lon);
        if (l != null && "seed".equals(l.source)) place += " (default)";
        StringBuilder b = new StringBuilder(place);
        String gap = twoLines && b.length() > 0 ? "\n" : sep;
        if (w != null && w.model != null) { b.append(gap).append(w.model); gap = sep; }
        if (w != null && w.fetchedAt > 0) {
            long age = System.currentTimeMillis() - w.fetchedAt;
            b.append(gap).append("Updated ").append(android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(w.fetchedAt)));
            if (age > 30 * 60_000L) b.append(" (old)");
        }
        return b.toString();
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

    /** ECMWF IFS 0.25° (via Open-Meteo, keyless): overrides the best-match current values with the
     *  ECMWF model wherever it has them (it has no visibility or UV, so those stay best-match). */
    private void fetchEcmwf(Wx w, double lat, double lon) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude="
                    + String.format(java.util.Locale.US, "%.4f", lat) + "&longitude="
                    + String.format(java.util.Locale.US, "%.4f", lon)
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,"
                    + "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,dew_point_2m,pressure_msl,"
                    + "surface_pressure,cape,wind_speed_10m,wind_direction_10m,wind_gusts_10m&models=ecmwf_ifs025";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "ShartTV/0.1");
            if (c.getResponseCode() != 200) { android.util.Log.i("ShartWx", "ecmwf http " + c.getResponseCode()); return; }
            JSONObject cur = new JSONObject(readAll(c)).optJSONObject("current");
            if (cur == null || cur.isNull("temperature_2m")) return;
            w.tempC = num(cur, "temperature_2m", w.tempC);
            w.feelsC = num(cur, "apparent_temperature", w.feelsC);
            w.humidity = (int) Math.round(num(cur, "relative_humidity_2m", w.humidity));
            w.precipMm = num(cur, "precipitation", w.precipMm);
            if (!cur.isNull("weather_code")) w.code = cur.optInt("weather_code", w.code);
            w.cloudPct = (int) Math.round(num(cur, "cloud_cover", w.cloudPct));
            w.cloudLow = (int) Math.round(num(cur, "cloud_cover_low", w.cloudLow));
            w.cloudMid = (int) Math.round(num(cur, "cloud_cover_mid", w.cloudMid));
            w.cloudHigh = (int) Math.round(num(cur, "cloud_cover_high", w.cloudHigh));
            w.dewC = num(cur, "dew_point_2m", w.dewC);
            w.pressureHpa = num(cur, "pressure_msl", w.pressureHpa);
            w.surfacePressureHpa = num(cur, "surface_pressure", w.surfacePressureHpa);
            w.cape = num(cur, "cape", w.cape);
            w.windKmh = num(cur, "wind_speed_10m", w.windKmh);
            w.windDirDeg = num(cur, "wind_direction_10m", w.windDirDeg);
            w.windGust = num(cur, "wind_gusts_10m", w.windGust);
            w.model = "ECMWF";
        } catch (Exception e) { android.util.Log.i("ShartWx", "ecmwf err " + e.getMessage()); }
    }

    private static double num(JSONObject o, String k, double fallback) {
        return o.isNull(k) ? fallback : o.optDouble(k, fallback);
    }

    private void fetchAqi(Wx w, double lat, double lon) {
        try {
            String url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                    + String.format(java.util.Locale.US, "%.4f", lat) + "&longitude="
                    + String.format(java.util.Locale.US, "%.4f", lon) + "&current=us_aqi,pm2_5";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(4000);
            if (c.getResponseCode() != 200) return;
            JSONObject cur = new JSONObject(readAll(c)).optJSONObject("current");
            if (cur != null) { w.aqi = cur.optInt("us_aqi", -1); w.pm25 = cur.optDouble("pm2_5", Double.NaN); }
        } catch (Exception ignored) {}
    }

    private Wx fetchWeather(double lat, double lon) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude="
                    + String.format(java.util.Locale.US, "%.4f", lat) + "&longitude="
                    + String.format(java.util.Locale.US, "%.4f", lon)
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,"
                    + "precipitation,weather_code,cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,"
                    + "dew_point_2m,pressure_msl,surface_pressure,visibility,uv_index,cape,"
                    + "wind_speed_10m,wind_direction_10m,wind_gusts_10m"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,"
                    + "precipitation_probability_max,wind_speed_10m_max&forecast_days=7&timezone=auto";
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
            w.windDirDeg = cur.optDouble("wind_direction_10m", 0);
            w.dewC = cur.optDouble("dew_point_2m", Double.NaN);
            w.pressureHpa = cur.optDouble("pressure_msl", Double.NaN);
            w.surfacePressureHpa = cur.optDouble("surface_pressure", Double.NaN);
            w.visibilityM = cur.optDouble("visibility", Double.NaN);
            w.uvIndex = cur.optDouble("uv_index", Double.NaN);
            w.cape = cur.optDouble("cape", Double.NaN);
            w.cloudLow = cur.optInt("cloud_cover_low", -1);
            w.cloudMid = cur.optInt("cloud_cover_mid", -1);
            w.cloudHigh = cur.optInt("cloud_cover_high", -1);
            parseHourly(w, root.optJSONObject("hourly"), root.optInt("utc_offset_seconds", 0));
            org.json.JSONObject daily = root.optJSONObject("daily");
            if (daily != null) {
                org.json.JSONArray hi = daily.optJSONArray("temperature_2m_max");
                org.json.JSONArray lo = daily.optJSONArray("temperature_2m_min");
                if (hi != null && hi.length() > 0) w.tempMaxC = hi.optDouble(0, Double.NaN);
                if (lo != null && lo.length() > 0) w.tempMinC = lo.optDouble(0, Double.NaN);
                org.json.JSONArray sr = daily.optJSONArray("sunrise");
                org.json.JSONArray ss = daily.optJSONArray("sunset");
                if (sr != null && sr.length() > 0) w.sunriseSec = hhmmToSec(sr.optString(0, null));
                if (ss != null && ss.length() > 0) w.sunsetSec = hhmmToSec(ss.optString(0, null));
                org.json.JSONArray uvm = daily.optJSONArray("uv_index_max");
                if (uvm != null && uvm.length() > 0) w.uvMax = uvm.optDouble(0, Double.NaN);
                org.json.JSONArray ppm = daily.optJSONArray("precipitation_probability_max");
                if (ppm != null && ppm.length() > 0) w.precipProb = ppm.optDouble(0, Double.NaN);
                org.json.JSONArray dcode = daily.optJSONArray("weather_code");
                org.json.JSONArray dtime = daily.optJSONArray("time");
                int nd = dtime != null ? Math.min(7, dtime.length()) : 0;
                org.json.JSONArray dwind = daily.optJSONArray("wind_speed_10m_max");
                if (nd > 1) {
                    int days = nd - 1;  // skip today (index 0); show the next days
                    w.fcDay = new String[days]; w.fcCode = new int[days];
                    w.fcMax = new double[days]; w.fcMin = new double[days];
                    w.fcPop = new int[days]; w.fcWindKmh = new double[days];
                    for (int d = 0; d < days; d++) {
                        int idx = d + 1;
                        w.fcDay[d] = dayAbbrev(dtime.optString(idx, ""));
                        w.fcCode[d] = dcode != null ? dcode.optInt(idx, 0) : 0;
                        w.fcMax[d] = hi != null ? hi.optDouble(idx, Double.NaN) : Double.NaN;
                        w.fcMin[d] = lo != null ? lo.optDouble(idx, Double.NaN) : Double.NaN;
                        w.fcPop[d] = ppm != null ? ppm.optInt(idx, -1) : -1;
                        w.fcWindKmh[d] = dwind != null ? dwind.optDouble(idx, Double.NaN) : Double.NaN;
                    }
                }
            }
            w.fetchedAt = System.currentTimeMillis();
            return w;
        } catch (Exception e) { android.util.Log.e("ShartWx", "fetch err", e); return null; }
    }

    /** Keep the hourly forecast from the current hour through the next 24, keyed by epoch hour. */
    private static void parseHourly(Wx w, JSONObject h, int utcOffsetSec) {
        if (h == null) return;
        org.json.JSONArray time = h.optJSONArray("time"), temp = h.optJSONArray("temperature_2m");
        org.json.JSONArray code = h.optJSONArray("weather_code"), pop = h.optJSONArray("precipitation_probability");
        if (time == null || temp == null) return;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        long thisHour = System.currentTimeMillis() / 3_600_000L * 3_600_000L;
        java.util.List<Integer> keep = new java.util.ArrayList<>();
        java.util.List<Long> at = new java.util.ArrayList<>();
        for (int i = 0; i < time.length(); i++) {
            try {
                long ms = f.parse(time.getString(i)).getTime() - utcOffsetSec * 1000L;   // local -> epoch
                if (ms >= thisHour && ms <= thisHour + 24 * 3_600_000L) { keep.add(i); at.add(ms); }
            } catch (Exception ignored) {}
        }
        int n = keep.size();
        w.hrAt = new long[n]; w.hrTempC = new double[n]; w.hrCode = new int[n]; w.hrPop = new int[n];
        for (int k = 0; k < n; k++) {
            int i = keep.get(k);
            w.hrAt[k] = at.get(k);
            w.hrTempC[k] = temp.optDouble(i, Double.NaN);
            w.hrCode[k] = code != null ? code.optInt(i, -1) : -1;
            w.hrPop[k] = pop != null ? pop.optInt(i, -1) : -1;
        }
    }

    // ---------- Cache ----------

    private void cache(Wx w, Loc l) {
        sp.edit()
          .putInt("w_code", w.code).putInt("w_day", w.isDay ? 1 : 0)
          .putFloat("w_temp", (float) w.tempC).putFloat("w_precip", (float) w.precipMm)
          .putFloat("w_wind", (float) w.windKmh).putInt("w_cloud", (int) w.cloudPct)
          .putFloat("w_hi", (float) w.tempMaxC).putFloat("w_lo", (float) w.tempMinC).putFloat("w_wdir", (float) w.windDirDeg)
          .putLong("w_at", w.fetchedAt).putString("w_place", w.place).putString("w_model", w.model)
          .putString("l_place", l.place == null ? "" : l.place).putString("l_src", l.source == null ? "" : l.source)
          .putFloat("l_lat", (float) l.lat).putFloat("l_lon", (float) l.lon)
          .putInt("l_sunrise", l.sunriseSec).putInt("l_sunset", l.sunsetSec)
          .putInt("l_dawn", l.civilDawnSec).putInt("l_dusk", l.civilDuskSec)
          .putInt("l_noon", l.solarNoonSec)
          .putString("w_hourly", hourlyJson(w))
          .putString("w_full", snapshot(w))
          .apply();
    }

    /** Every public field of {@code w} as JSON, so a cached read restores the whole card (humidity,
     *  UV, AQI, the 6-day forecast...) instead of just the basics. Wx's fields are kept by R8. */
    private static String snapshot(Wx w) {
        org.json.JSONObject o = new org.json.JSONObject();
        for (java.lang.reflect.Field f : Wx.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object v = f.get(w);
                if (v == null) continue;
                if (v.getClass().isArray()) {
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (int i = 0; i < java.lang.reflect.Array.getLength(v); i++) {
                        Object e = java.lang.reflect.Array.get(v, i);
                        a.put(e instanceof Double && ((Double) e).isNaN() ? org.json.JSONObject.NULL : e);
                    }
                    o.put(f.getName(), a);
                } else if (!(v instanceof Double && ((Double) v).isNaN())) o.put(f.getName(), v);
            } catch (Exception ignored) {}
        }
        return o.toString();
    }

    private static void restore(Wx w, String json) throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject(json);
        for (java.lang.reflect.Field f : Wx.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !o.has(f.getName())) continue;
            try {
                Class<?> t = f.getType();
                String k = f.getName();
                if (t == double.class) f.setDouble(w, o.getDouble(k));
                else if (t == int.class) f.setInt(w, o.getInt(k));
                else if (t == long.class) f.setLong(w, o.getLong(k));
                else if (t == boolean.class) f.setBoolean(w, o.getBoolean(k));
                else if (t == String.class) f.set(w, o.getString(k));
                else if (t.isArray()) {
                    org.json.JSONArray a = o.getJSONArray(k);
                    Class<?> c = t.getComponentType();
                    Object arr = java.lang.reflect.Array.newInstance(c, a.length());
                    for (int i = 0; i < a.length(); i++) {
                        if (c == double.class) java.lang.reflect.Array.setDouble(arr, i, a.optDouble(i, Double.NaN));
                        else if (c == int.class) java.lang.reflect.Array.setInt(arr, i, a.getInt(i));
                        else if (c == long.class) java.lang.reflect.Array.setLong(arr, i, a.getLong(i));
                        else if (c == String.class) java.lang.reflect.Array.set(arr, i, a.getString(i));
                    }
                    f.set(w, arr);
                }
            } catch (Exception ignored) {}
        }
    }

    public Wx readCachedWx() {
        if (!sp.contains("w_code")) return null;
        Wx w = new Wx();
        String full = sp.getString("w_full", null);
        if (full != null) {
            try { restore(w, full); w.classify(); return w; } catch (Exception ignored) { w = new Wx(); }
        }
        w.code = sp.getInt("w_code", -1); w.isDay = sp.getInt("w_day", 1) == 1;
        w.tempC = sp.getFloat("w_temp", 0); w.precipMm = sp.getFloat("w_precip", 0);
        w.windKmh = sp.getFloat("w_wind", 0); w.cloudPct = sp.getInt("w_cloud", 0);
        w.tempMaxC = sp.getFloat("w_hi", Float.NaN); w.tempMinC = sp.getFloat("w_lo", Float.NaN); w.windDirDeg = sp.getFloat("w_wdir", 0);
        w.fetchedAt = sp.getLong("w_at", 0); w.place = sp.getString("w_place", "");
        w.model = sp.getString("w_model", w.model);
        readHourly(w, sp.getString("w_hourly", null));
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
        l.place = sp.getString("l_place", sp.getString("w_place", "")); l.source = sp.getString("l_src", "");
        return l;
    }

    private static String hourlyJson(Wx w) {
        org.json.JSONArray a = new org.json.JSONArray();
        if (w.hrAt != null) for (int k = 0; k < w.hrAt.length; k++) {
            try {
                a.put(new org.json.JSONArray().put(w.hrAt[k]).put(w.hrTempC[k]).put(w.hrCode[k]).put(w.hrPop[k]));
            } catch (Exception ignored) {}
        }
        return a.toString();
    }

    private static void readHourly(Wx w, String json) {
        if (json == null) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            int n = a.length();
            w.hrAt = new long[n]; w.hrTempC = new double[n]; w.hrCode = new int[n]; w.hrPop = new int[n];
            for (int k = 0; k < n; k++) {
                org.json.JSONArray e = a.getJSONArray(k);
                w.hrAt[k] = e.getLong(0); w.hrTempC[k] = e.optDouble(1, Double.NaN);
                w.hrCode[k] = e.optInt(2, -1); w.hrPop[k] = e.optInt(3, -1);
            }
        } catch (Exception ignored) {}
    }

    // ---------- helpers ----------

    private static String readAll(HttpURLConnection c) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close(); return sb.toString();
    }

    /** "2026-09-26" -> "Fri". */
    private static String dayAbbrev(String ymd) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date d = in.parse(ymd.length() >= 10 ? ymd.substring(0, 10) : ymd);
            return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(d);
        } catch (Exception e) { return ""; }
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
