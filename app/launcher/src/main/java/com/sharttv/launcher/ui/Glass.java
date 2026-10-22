package com.sharttv.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Liquid-glass, gothic card chrome: translucent frosted fill, a specular top sheen (the "wet
 * glass" highlight), a luminous beveled border, and a soft pointed-arch crown so cards read
 * organic + classical rather than flat rectangles. Corners are clipped via an outline so content
 * (banners) is actually rounded. Cheap: all vector/gradient, no bitmaps or blur passes.
 */
public class Glass {

    public static float D = 2f; // px per dp, set in init()
    public static void init(Context c) { D = c.getResources().getDisplayMetrics().density; }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Clip a view to a rounded rect so its children (ImageView) get real rounded corners. */
    public static void clipRound(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** The frosted-glass gothic background drawable for a card. */
    public static Drawable card(final int surface, final int accent, final boolean focused,
                                final float radiusPx) {
        return new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas cv) {
                RectF r = new RectF(getBounds());
                float rad = radiusPx;

                // 1) frosted translucent body: vertical gradient, lighter at top (glass depth)
                int top = withAlpha(lighten(surface, 1.35f), focused ? 0xE6 : 0xCC);
                int bot = withAlpha(darken(surface, 0.8f), focused ? 0xEE : 0xD8);
                p.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom, top, bot, Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(r, rad, rad, p);
                p.setShader(null);

                // 2) specular sheen across the top third (liquid highlight)
                RectF sh = new RectF(r.left, r.top, r.right, r.top + r.height() * 0.42f);
                p.setShader(new LinearGradient(sh.left, sh.top, sh.left, sh.bottom,
                        withAlpha(0xFFFFFF, focused ? 0x4A : 0x30), withAlpha(0xFFFFFF, 0x00), Shader.TileMode.CLAMP));
                Path clip = new Path(); clip.addRoundRect(r, rad, rad, Path.Direction.CW);
                cv.save(); cv.clipPath(clip); cv.drawRect(sh, p); cv.restore();
                p.setShader(null);

                // 3) luminous beveled border
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dpF(focused ? 2.6f : 1.4f));
                int edge = focused ? accent : withAlpha(lighten(accent, 1.1f), 0x66);
                p.setColor(edge);
                float inset = p.getStrokeWidth() / 2f;
                cv.drawRoundRect(new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), rad, rad, p);

                // 4) gothic pointed-arch crown: faint apex lines meeting at the top-center
                p.setStrokeWidth(dpF(1.2f));
                p.setColor(withAlpha(accent, focused ? 0x9A : 0x44));
                float cx = r.centerX(), apex = r.top + dpF(focused ? 5 : 7);
                cv.drawLine(r.left + rad, r.top + rad * 0.5f, cx, apex, p);
                cv.drawLine(r.right - rad, r.top + rad * 0.5f, cx, apex, p);
            }
            float dpF(float v) { return v * D; }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /** A thin ornamental divider with a central diamond — classical section rule. */
    public static Drawable divider(final int color) {
        return new Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas cv) {
                RectF r = new RectF(getBounds());
                float cy = r.centerY();
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
                p.setShader(new LinearGradient(r.left, cy, r.right, cy,
                        new int[]{withAlpha(color,0), color, withAlpha(color,0)}, null, Shader.TileMode.CLAMP));
                cv.drawLine(r.left, cy, r.right, cy, p);
                p.setShader(null);
                // center diamond
                p.setStyle(Paint.Style.FILL); p.setColor(color);
                float cx = r.centerX(), s = r.height() * 0.42f;
                Path d = new Path(); d.moveTo(cx, cy - s); d.lineTo(cx + s, cy); d.lineTo(cx, cy + s); d.lineTo(cx - s, cy); d.close();
                cv.drawPath(d, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    public static int withAlpha(int rgb, int a) { return (a << 24) | (rgb & 0xFFFFFF); }
    public static int lighten(int c, float f) {
        return Color.rgb(clamp((int)(Color.red(c)*f)), clamp((int)(Color.green(c)*f)), clamp((int)(Color.blue(c)*f)));
    }
    public static int darken(int c, float f) { return lighten(c, f); }
    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
