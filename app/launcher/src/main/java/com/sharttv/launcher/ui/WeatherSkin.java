package com.sharttv.launcher.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The "organic" weather treatment that every app/source card wears, so the live weather is woven
 * into the UI instead of sitting in one dashboard block. It's a cheap, STATIC overlay (gradients +
 * a few strokes, no per-frame animation) painted over the card:
 *   - freezing / snow  -> a frosty blue-white rim + ice flecks
 *   - rain / storm     -> cool tint + faint diagonal droplet streaks (stronger with intensity)
 *   - clear day        -> warm golden corner glow
 *   - clear night      -> cool indigo corner glow
 *   - cloud / fog       -> soft neutral veil
 * Kept at low alpha so card art and labels stay readable (the owner asked for it done "lightly").
 */
public class WeatherSkin extends Drawable {

    private final String category;
    private final float intensity;   // 0..1
    private final boolean freezing;
    private final float d;           // px per dp
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WeatherSkin(String category, float intensity, boolean freezing, float density) {
        this.category = category == null ? "clear" : category;
        this.intensity = Math.max(0f, Math.min(1f, intensity));
        this.freezing = freezing;
        this.d = density;
    }

    @Override public void draw(Canvas cv) {
        RectF r = new RectF(getBounds());
        if (r.width() <= 0 || r.height() <= 0) return;

        boolean rainy = category.equals("rain") || category.equals("heavy_rain")
                || category.equals("drizzle") || category.equals("storm");
        boolean snowy = category.equals("snow") || category.equals("blizzard") || freezing;

        if (snowy) {
            // frosty rim: cool white-blue glow hugging top + bottom edges
            int frost = 0x9FE8FF;
            p.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                    new int[]{withA(frost, 0x3A), withA(frost, 0x00), withA(frost, 0x00), withA(0xCFE9FF, 0x2A)},
                    new float[]{0f, 0.28f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(r, p);
            p.setShader(null);
            // a few ice flecks
            p.setColor(withA(0xFFFFFF, 0x55));
            for (int i = 0; i < 5; i++) {
                float fx = r.left + r.width() * (0.14f + 0.18f * i);
                float fy = r.top + r.height() * (0.2f + 0.14f * ((i * 7) % 5));
                cv.drawCircle(fx, fy, d * 1.1f, p);
            }
        } else if (rainy) {
            // cool wet tint + diagonal droplet streaks, denser with intensity
            int cool = category.equals("storm") ? 0x3D5A80 : 0x4A90C2;
            p.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                    withA(cool, (int) (0x14 + 0x26 * intensity)), withA(cool, 0x08), Shader.TileMode.CLAMP));
            cv.drawRect(r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d * 1.1f);
            p.setColor(withA(0xBFE3FF, (int) (0x1E + 0x3C * intensity)));
            int streaks = 3 + (int) (6 * intensity);
            float step = r.width() / (streaks + 1);
            float slant = r.height() * 0.5f;
            for (int i = 1; i <= streaks; i++) {
                float x = r.left + step * i;
                cv.drawLine(x, r.top, x - slant * 0.35f, r.bottom, p);
            }
            p.setStyle(Paint.Style.FILL);
        } else if (category.equals("clear")) {
            // warm golden corner glow (top-right)
            p.setShader(new RadialGradient(r.right - r.width() * 0.08f, r.top + r.height() * 0.1f,
                    r.width() * 0.6f, withA(0xFFC56B, 0x2E), withA(0xFFC56B, 0x00), Shader.TileMode.CLAMP));
            cv.drawRect(r, p);
            p.setShader(null);
        } else if (category.equals("clear_night")) {
            // cool indigo corner glow
            p.setShader(new RadialGradient(r.right - r.width() * 0.1f, r.top + r.height() * 0.12f,
                    r.width() * 0.6f, withA(0x6F8CFF, 0x24), withA(0x6F8CFF, 0x00), Shader.TileMode.CLAMP));
            cv.drawRect(r, p);
            p.setShader(null);
        } else {
            // cloudy / overcast / fog: soft neutral veil across the top
            p.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom,
                    withA(0x9AA4B2, 0x1C), withA(0x9AA4B2, 0x00), Shader.TileMode.CLAMP));
            cv.drawRect(r, p);
            p.setShader(null);
        }
    }

    private static int withA(int rgb, int a) { return (a << 24) | (rgb & 0xFFFFFF); }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
