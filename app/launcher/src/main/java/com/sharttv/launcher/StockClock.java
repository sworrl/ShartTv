package com.sharttv.launcher;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Sony's own "Auto clock display" (Settings > Timers & Clock), drawn by Sony's SystemUI infoboard:
 * "0" off, "1" always, "2" every hour (in from :59:55 for 12 s, under our chime and outlasting it).
 * Apps can read it (normal permission) but only Sony's own apps can write it, so sharttv.py turns
 * it off through the Settings screen; until then {@link HourChime} stays up long enough to cover it.
 */
final class StockClock {
    static final String OFF = "0", ALWAYS = "1", HOURLY = "2";
    private static final Uri URI = Uri.parse("content://com.sony.dtv.settingsprovider/global");

    private StockClock() {}

    /** The mode, or "" if this TV has no such setting (or it can't be read). */
    static String mode(Context c) {
        try (Cursor q = c.getContentResolver().query(URI, null, "key='timers.clock_display'", null, null)) {
            if (q != null && q.moveToFirst()) {
                String v = q.getString(q.getColumnIndexOrThrow("value"));
                return v == null ? "" : v;
            }
        } catch (Exception ignored) {}
        return "";
    }
}
