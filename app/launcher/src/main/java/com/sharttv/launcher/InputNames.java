package com.sharttv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What's plugged into each input: a name ("Steam Deck") and a card-art preset, per TV input id.
 * Shared by every profile, since it describes the hardware, not a person.
 */
public class InputNames {

    public static final String[] KEYS = {
            "steamdeck", "macmini", "laptop", "gamingpc", "console", "handheld",
            "streamer", "cablebox", "bluray", "soundbar", "camera", "retro"};
    public static final String[] LABELS = {
            "Steam Deck", "Mac mini", "Laptop", "Gaming PC", "Game console", "Handheld",
            "Streaming stick", "Cable box", "Blu-ray", "Soundbar", "Camera", "Retro console"};
    public static final int[] ART = {
            R.drawable.art_dev_steamdeck, R.drawable.art_dev_macmini, R.drawable.art_dev_laptop,
            R.drawable.art_dev_gamingpc, R.drawable.art_dev_console, R.drawable.art_dev_handheld,
            R.drawable.art_dev_streamer, R.drawable.art_dev_cablebox, R.drawable.art_dev_bluray,
            R.drawable.art_dev_soundbar, R.drawable.art_dev_camera, R.drawable.art_dev_retro};

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("sharttv_inputs", Context.MODE_PRIVATE);
    }

    /** The custom name for an input, or "" if it keeps the TV's own label. */
    public static String name(Context c, String inputId) { return sp(c).getString("name_" + inputId, ""); }

    /** The chosen preset key, or "" for the port's default art. */
    public static String preset(Context c, String inputId) { return sp(c).getString("art_" + inputId, ""); }

    public static void set(Context c, String inputId, String name, String preset) {
        sp(c).edit().putString("name_" + inputId, name == null ? "" : name.trim())
                .putString("art_" + inputId, preset == null ? "" : preset).apply();
    }

    public static void reset(Context c, String inputId) {
        sp(c).edit().remove("name_" + inputId).remove("art_" + inputId).apply();
    }

    /** Art for a preset key, 0 if none. */
    public static int art(String preset) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(preset)) return ART[i];
        return 0;
    }

    public static String label(String preset) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(preset)) return LABELS[i];
        return "";
    }
}
