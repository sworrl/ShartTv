package com.sharttv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Household profiles, like the stock launcher's avatar switcher, but local to Shart TV (switching
 * real Android users / Google accounts needs system privileges this locked build won't grant).
 * Each profile owns its own theme, learned usage, and recents. Profile "p0" keeps the original
 * un-suffixed storage, so learning from before profiles existed carries over to the first profile.
 */
public class Profiles {

    private static final String PREFS = "sharttv_profiles";
    public static final String FIRST = "p0";
    public static final int[] COLORS = {
            Color.parseColor("#FFC56B"), Color.parseColor("#6FB0FF"), Color.parseColor("#FF7A9A"),
            Color.parseColor("#9CCC65"), Color.parseColor("#BA68C8"), Color.parseColor("#4DD0C4")
    };

    public static class Profile {
        public final String id; public final String name; public final int color;
        Profile(String id, String name, int color) { this.id = id; this.name = name; this.color = color; }
        public String initial() { return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(); }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> ids(SharedPreferences sp) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("ids", FIRST).split(",")) if (!s.isEmpty()) out.add(s);
        if (out.isEmpty()) out.add(FIRST);
        return out;
    }

    public static List<Profile> all(Context c) {
        SharedPreferences sp = sp(c);
        List<Profile> out = new ArrayList<>();
        List<String> ids = ids(sp);
        for (int i = 0; i < ids.size(); i++) out.add(get(sp, ids.get(i), i));
        return out;
    }

    private static Profile get(SharedPreferences sp, String id, int idx) {
        return new Profile(id, sp.getString("name_" + id, idx == 0 ? "Home" : "Profile " + (idx + 1)),
                sp.getInt("color_" + id, COLORS[idx % COLORS.length]));
    }

    public static String currentId(Context c) {
        SharedPreferences sp = sp(c);
        String cur = sp.getString("cur", FIRST);
        return ids(sp).contains(cur) ? cur : FIRST;
    }

    public static Profile current(Context c) {
        SharedPreferences sp = sp(c);
        List<String> ids = ids(sp);
        String cur = currentId(c);
        return get(sp, cur, ids.indexOf(cur));
    }

    public static void switchTo(Context c, String id) { sp(c).edit().putString("cur", id).apply(); }

    /** Adds a profile that starts from the current profile's theme; returns its id. */
    public static String add(Context c, String name) {
        SharedPreferences sp = sp(c);
        List<String> ids = ids(sp);
        int n = sp.getInt("next", 1);
        String id = "p" + n;
        Theme.load(c).saveFor(c, id);
        ids.add(id);
        sp.edit().putString("ids", join(ids)).putInt("next", n + 1)
          .putString("name_" + id, name)
          .putInt("color_" + id, COLORS[(ids.size() - 1) % COLORS.length]).apply();
        return id;
    }

    public static void rename(Context c, String id, String name) { sp(c).edit().putString("name_" + id, name).apply(); }

    public static void cycleColor(Context c, String id) {
        SharedPreferences sp = sp(c);
        List<String> ids = ids(sp);
        int cur = get(sp, id, ids.indexOf(id)).color, next = COLORS[0];
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == cur) next = COLORS[(i + 1) % COLORS.length];
        sp.edit().putInt("color_" + id, next).apply();
    }

    /** Removes a profile and its data. The first profile can't be removed (something must remain). */
    public static void remove(Context c, String id) {
        if (FIRST.equals(id)) return;
        SharedPreferences sp = sp(c);
        List<String> ids = ids(sp);
        ids.remove(id);
        SharedPreferences.Editor e = sp.edit().putString("ids", join(ids)).remove("name_" + id).remove("color_" + id);
        if (id.equals(sp.getString("cur", FIRST))) e.putString("cur", FIRST);
        e.apply();
        new java.io.File(c.getFilesDir(), Theme.fileFor(id)).delete();
        c.getApplicationContext().getSharedPreferences(UsageStore.prefsFor(id), Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String join(List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String s : ids) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }
}
