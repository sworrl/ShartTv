package com.sharttv.launcher.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * The shared focus and click motion for every card, pill and avatar, so the whole UI moves the
 * same way:
 *  - focus: a springy lift (overshoot scale + depth) and one sweep of light across the face
 *  - click: a quick press-in, a bounce back, and a bloom of accent light from the center; the
 *    action runs as the bounce peaks, so the press is seen before the next screen opens
 * Both draw in the view's overlay, clipped to its rounded corners, so they work on any view
 * without touching its background or foreground.
 */
public final class CardFx {
    private CardFx() {}

    /** Focus in/out. {@code scale} is the focused size (1.0 = no lift). */
    public static void focus(View v, boolean has, float scale, float radiusPx, int accent, long ms) {
        v.setDefaultFocusHighlightEnabled(false);   // Android's own square highlight showed in rounded corners
        v.animate().cancel();
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .setInterpolator(has ? new OvershootInterpolator(2.2f) : new DecelerateInterpolator())
                .setDuration(has ? Math.max(ms, 220) : Math.max(ms, 160)).start();
        if (has) sweep(v, radiusPx, accent);
        // NOTE: the lifted view grows past its parent; every ancestor up to the scrolling list needs
        // setClipChildren(false), or the parent's rectangle cuts off the rounded corners and ring.
    }



    /** Press feedback, then {@code action} at the top of the bounce. */
    public static void click(final View v, float radiusPx, int accent, final Runnable action) {
        final float base = v.getScaleX();
        bloom(v, radiusPx, accent);
        v.animate().cancel();
        v.animate().scaleX(base * 0.93f).scaleY(base * 0.93f).setDuration(70)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> v.animate().scaleX(base).scaleY(base).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(3f))
                        .withEndAction(null).start())
                .start();
        v.postDelayed(() -> { if (action != null) action.run(); }, 150);
    }

    // ---------- overlay drawables ----------

    private static void sweep(final View v, float radiusPx, int accent) {
        final FxDrawable d = new FxDrawable(radiusPx, accent, true);
        run(v, d, 720);
    }

    private static void bloom(final View v, float radiusPx, int accent) {
        final FxDrawable d = new FxDrawable(radiusPx, accent, false);
        run(v, d, 460);
    }

    private static void run(final View v, final FxDrawable d, long ms) {
        if (v.getWidth() == 0) return;
        d.setBounds(0, 0, v.getWidth(), v.getHeight());
        v.getOverlay().add(d);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        a.setInterpolator(new DecelerateInterpolator(1.4f));
        a.addUpdateListener(an -> { d.t = (float) an.getAnimatedValue(); d.invalidateSelf(); v.invalidate(); });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { v.getOverlay().remove(d); }
        });
        a.start();
    }

    /** A light sweep (diagonal band crossing left to right) or a bloom (accent glow from center). */
    private static final class FxDrawable extends Drawable {
        final float r; final int accent; final boolean sheen;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path();
        float t;

        FxDrawable(float r, int accent, boolean sheen) { this.r = r; this.accent = accent; this.sheen = sheen; }

        @Override public void draw(Canvas cv) {
            RectF b = new RectF(getBounds());
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            clip.reset();
            clip.addRoundRect(b, r, r, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            if (sheen) {
                float band = Math.max(w, h) * 0.45f;
                float x = -band + (w + 2 * band) * t;
                int a = (int) (110 * Math.sin(Math.PI * t));
                p.setShader(new LinearGradient(x - band, 0, x + band, h * 0.35f,
                        new int[]{0x00FFFFFF, (a << 24) | 0xFFF4DC, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                cv.drawRect(b, p);
            } else {
                float rad = Math.max(w, h) * (0.25f + 0.9f * t);
                int a = (int) (150 * (1 - t));
                p.setShader(new RadialGradient(w / 2f, h / 2f, rad,
                        new int[]{(a << 24) | (accent & 0xFFFFFF), 0x00000000}, null, Shader.TileMode.CLAMP));
                cv.drawRect(b, p);
            }
            p.setShader(null);
            cv.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
