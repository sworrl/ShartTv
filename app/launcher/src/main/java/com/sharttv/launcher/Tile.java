package com.sharttv.launcher;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One selectable item: an app, an input, or a settings shortcut. */
public class Tile {
    public enum Kind { APP, INPUT, SETTINGS }
    public Kind kind;
    public String label;
    public Drawable icon;      // app banner/icon, or a tinted glyph for inputs/settings
    public Intent launch;      // what to fire on click
    public String key;         // package name or input id, for ordering

    public Tile(Kind kind, String label, Drawable icon, Intent launch, String key) {
        this.kind = kind; this.label = label; this.icon = icon; this.launch = launch; this.key = key;
    }
}
