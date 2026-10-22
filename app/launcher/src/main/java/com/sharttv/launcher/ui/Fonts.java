package com.sharttv.launcher.ui;

import android.content.Context;
import android.graphics.Typeface;
import androidx.core.content.res.ResourcesCompat;
import com.sharttv.launcher.R;

/** Classical/gothic type: Cinzel (display), EB Garamond (body), a blackletter accent. */
public class Fonts {
    public static Typeface display, body, blackletter;
    public static void load(Context c) {
        try { display = ResourcesCompat.getFont(c, R.font.cinzel); } catch (Exception e) { display = Typeface.SERIF; }
        try { body = ResourcesCompat.getFont(c, R.font.garamond); } catch (Exception e) { body = Typeface.SERIF; }
        try { blackletter = ResourcesCompat.getFont(c, R.font.blackletter); } catch (Exception e) { blackletter = Typeface.SERIF; }
        if (display == null) display = Typeface.SERIF;
        if (body == null) body = Typeface.SERIF;
        if (blackletter == null) blackletter = Typeface.SERIF;
    }
}
