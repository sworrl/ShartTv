package com.sharttv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What's plugged into each input: a name ("Steam Deck") and a card-art preset, per TV input id.
 * Shared by every profile, since it describes the hardware, not a person.
 */
public class InputNames {

    /** A card preset: stored key, the name it suggests, its picker group, and its art. */
    public static final class Preset {
        public final String key, label, group; public final int art;
        Preset(String key, String label, String group, int art) { this.key = key; this.label = label; this.group = group; this.art = art; }
    }

    public static final String[] GROUPS = {
            "PC & handheld", "Nintendo", "PlayStation", "Xbox", "Sega", "Retro & classic", "Computers & media"};

    public static final Preset[] PRESETS = {
            new Preset("steamdeck", "Steam Deck", "PC & handheld", R.drawable.art_dev_steamdeck),
            new Preset("rogally", "ROG Ally", "PC & handheld", R.drawable.art_game_rogally),
            new Preset("legiongo", "Legion Go", "PC & handheld", R.drawable.art_game_legiongo),
            new Preset("gamingpc", "Gaming PC", "PC & handheld", R.drawable.art_dev_gamingpc),
            new Preset("handheld", "Handheld", "PC & handheld", R.drawable.art_dev_handheld),

            new Preset("nes", "NES", "Nintendo", R.drawable.art_game_nes),
            new Preset("snes", "Super Nintendo", "Nintendo", R.drawable.art_game_snes),
            new Preset("n64", "Nintendo 64", "Nintendo", R.drawable.art_game_n64),
            new Preset("gamecube", "GameCube", "Nintendo", R.drawable.art_game_gamecube),
            new Preset("wii", "Wii", "Nintendo", R.drawable.art_game_wii),
            new Preset("wiiu", "Wii U", "Nintendo", R.drawable.art_game_wiiu),
            new Preset("switch", "Switch", "Nintendo", R.drawable.art_game_switch),
            new Preset("switch2", "Switch 2", "Nintendo", R.drawable.art_game_switch2),

            new Preset("ps1", "PlayStation", "PlayStation", R.drawable.art_game_ps1),
            new Preset("ps2", "PlayStation 2", "PlayStation", R.drawable.art_game_ps2),
            new Preset("ps3", "PlayStation 3", "PlayStation", R.drawable.art_game_ps3),
            new Preset("ps4", "PlayStation 4", "PlayStation", R.drawable.art_game_ps4),
            new Preset("ps5", "PlayStation 5", "PlayStation", R.drawable.art_game_ps5),

            new Preset("xbox", "Xbox", "Xbox", R.drawable.art_game_xbox),
            new Preset("xbox360", "Xbox 360", "Xbox", R.drawable.art_game_xbox360),
            new Preset("xboxone", "Xbox One", "Xbox", R.drawable.art_game_xboxone),
            new Preset("xboxseriesx", "Xbox Series X", "Xbox", R.drawable.art_game_xboxseriesx),
            new Preset("xboxseriess", "Xbox Series S", "Xbox", R.drawable.art_game_xboxseriess),

            new Preset("mastersystem", "Master System", "Sega", R.drawable.art_game_mastersystem),
            new Preset("genesis", "Genesis", "Sega", R.drawable.art_game_genesis),
            new Preset("saturn", "Saturn", "Sega", R.drawable.art_game_saturn),
            new Preset("dreamcast", "Dreamcast", "Sega", R.drawable.art_game_dreamcast),

            new Preset("atari2600", "Atari 2600", "Retro & classic", R.drawable.art_game_atari2600),
            new Preset("intellivision", "Intellivision", "Retro & classic", R.drawable.art_game_intellivision),
            new Preset("colecovision", "ColecoVision", "Retro & classic", R.drawable.art_game_colecovision),
            new Preset("turbografx", "TurboGrafx-16", "Retro & classic", R.drawable.art_game_turbografx),
            new Preset("neogeo", "Neo Geo", "Retro & classic", R.drawable.art_game_neogeo),
            new Preset("3do", "3DO", "Retro & classic", R.drawable.art_game_3do),
            new Preset("jaguar", "Atari Jaguar", "Retro & classic", R.drawable.art_game_jaguar),
            new Preset("analogue", "Analogue", "Retro & classic", R.drawable.art_game_analogue),
            new Preset("mister", "MiSTer FPGA", "Retro & classic", R.drawable.art_game_mister),
            new Preset("raspberrypi", "Emulation box", "Retro & classic", R.drawable.art_game_raspberrypi),
            new Preset("evercade", "Evercade VS", "Retro & classic", R.drawable.art_game_evercade),
            new Preset("arcade", "Arcade stick", "Retro & classic", R.drawable.art_game_arcade),
            new Preset("retro", "Retro console", "Retro & classic", R.drawable.art_dev_retro),
            new Preset("console", "Game console", "Retro & classic", R.drawable.art_dev_console),

            new Preset("macmini", "Mac mini", "Computers & media", R.drawable.art_dev_macmini),
            new Preset("laptop", "Laptop", "Computers & media", R.drawable.art_dev_laptop),
            new Preset("shield", "NVIDIA Shield", "Computers & media", R.drawable.art_game_shield),
            new Preset("streamer", "Streaming stick", "Computers & media", R.drawable.art_dev_streamer),
            new Preset("cablebox", "Cable box", "Computers & media", R.drawable.art_dev_cablebox),
            new Preset("bluray", "Blu-ray", "Computers & media", R.drawable.art_dev_bluray),
            new Preset("soundbar", "Soundbar", "Computers & media", R.drawable.art_dev_soundbar),
            new Preset("camera", "Camera", "Computers & media", R.drawable.art_dev_camera),
    };

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

    private static Preset find(String key) {
        for (Preset p : PRESETS) if (p.key.equals(key)) return p;
        return null;
    }

    /** Art for a preset key, 0 if none. */
    public static int art(String key) { Preset p = find(key); return p == null ? 0 : p.art; }

    public static String label(String key) { Preset p = find(key); return p == null ? "" : p.label; }
}
