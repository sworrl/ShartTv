package com.sharttv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns which apps/inputs the owner actually uses and ranks them. Local only, no network, no
 * tracking off-device. Score = launch count with exponential recency decay, so a thing used a lot
 * lately outranks something used often long ago. Drives the "Most used" row and, when the owner
 * opts in, auto-promotion (sorting each row by score). Per profile; also keeps recency for the
 * Recent row, fed by launcher clicks AND by HomeRedirectService noticing which app is on screen.
 */
public class UsageStore {

    private static final double HALF_LIFE_DAYS = 10.0;   // recency decay
    private static final long SAME_LAUNCH_MS = 30_000;   // click + on-screen sighting = one launch
    private final SharedPreferences sp;

    static String prefsFor(String profileId) {
        return Profiles.FIRST.equals(profileId) ? "sharttv_usage" : "sharttv_usage_" + profileId;
    }

    /** The current profile's usage. */
    public UsageStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(prefsFor(Profiles.currentId(c)), Context.MODE_PRIVATE);
    }

    /** Record one launch of a key ("app:pkg" or "input:id"). A repeat of the same key within a few
     *  seconds (our click, then the service seeing that app come up) only refreshes its time. */
    public void record(String key) {
        if (key == null) return;
        long now = System.currentTimeMillis();
        String c = "c_" + key, t = "t_" + key;
        boolean dup = key.equals(sp.getString("last_key", null)) && now - sp.getLong("last_ts", 0) < SAME_LAUNCH_MS;
        SharedPreferences.Editor e = sp.edit().putLong(t, now).putString("last_key", key).putLong("last_ts", now);
        if (!dup) e.putInt(c, sp.getInt(c, 0) + 1);
        e.apply();
    }

    /** The app on screen changed to {@code key}; counts only when it differs from the last one seen. */
    public void noteForeground(String key) {
        if (key.equals(sp.getString("fg_key", null))) return;
        sp.edit().putString("fg_key", key).apply();
        record(key);
    }

    /** Home came up: the next app on screen is a fresh launch, even if it's the same one as before. */
    public void clearForeground() { sp.edit().remove("fg_key").apply(); }

    /** Most recently used keys, newest first. */
    public List<String> recentKeys(int n) {
        List<Map.Entry<String, Long>> e = new ArrayList<>();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            if (en.getKey().startsWith("t_") && en.getValue() instanceof Long)
                e.add(new java.util.AbstractMap.SimpleEntry<>(en.getKey().substring(2), (Long) en.getValue()));
        }
        Collections.sort(e, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, e.size()); i++) out.add(e.get(i).getKey());
        return out;
    }

    /** Recency-weighted score for a key. */
    public double score(String key) {
        int count = sp.getInt("c_" + key, 0);
        if (count == 0) return 0;
        long last = sp.getLong("t_" + key, 0);
        double ageDays = (System.currentTimeMillis() - last) / 86400000.0;
        double decay = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
        return count * (0.35 + 0.65 * decay);   // keep a floor so popular-but-old still counts
    }

    /** Sort a list of keys by score, descending (stable for ties). */
    public <T> void sortByScore(List<T> items, KeyOf<T> keyOf) {
        Collections.sort(items, new Comparator<T>() {
            public int compare(T a, T b) {
                double sa = score(keyOf.key(a)), sb = score(keyOf.key(b));
                return Double.compare(sb, sa);
            }
        });
    }

    /** Top-N keys overall (any kind), highest score first, score > 0 only. */
    public List<String> topKeys(int n) {
        Map<String, Double> scores = new HashMap<>();
        for (String k : sp.getAll().keySet()) {
            if (!k.startsWith("c_")) continue;
            String key = k.substring(2);
            double s = score(key);
            if (s > 0) scores.put(key, s);
        }
        List<Map.Entry<String, Double>> e = new ArrayList<>(scores.entrySet());
        Collections.sort(e, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, e.size()); i++) out.add(e.get(i).getKey());
        return out;
    }

    public boolean hasData() {
        for (String k : sp.getAll().keySet()) if (k.startsWith("c_")) return true;
        return false;
    }

    public interface KeyOf<T> { String key(T item); }
}
