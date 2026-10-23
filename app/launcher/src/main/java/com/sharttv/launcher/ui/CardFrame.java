package com.sharttv.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.FrameLayout;

/**
 * A card container that rounds its own corners: it clips its children (art, captions, weather
 * skin) to a rounded path in {@link #dispatchDraw}, and draws the focus ring on top. On this TV,
 * outline clipping ({@code setClipToOutline}) silently stops applying to a focused card, which
 * showed square art corners and hid the ring; a canvas path clip doesn't have that problem.
 */
public class CardFrame extends FrameLayout {

    private final Path clip = new Path();
    private final RectF r = new RectF();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private int ringColor;
    private boolean showRing;

    public CardFrame(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * c.getResources().getDisplayMetrics().density);
        setWillNotDraw(false);
    }

    public void setRadius(float px) { radius = px; invalidate(); }

    /** Show or hide the focus ring in {@code color}. */
    public void setRing(boolean on, int color) { showRing = on; ringColor = color; invalidate(); }

    @Override protected void dispatchDraw(Canvas cv) {
        r.set(0, 0, getWidth(), getHeight());
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        super.dispatchDraw(cv);
        cv.restore();
        if (showRing) {
            float in = ring.getStrokeWidth() / 2f;
            ring.setColor(ringColor);
            r.inset(in, in);
            float rr = Math.max(0f, radius - in);
            cv.drawRoundRect(r, rr, rr, ring);
        }
    }

    @Override public void onDrawForeground(Canvas cv) {
        // keep the foreground (weather skin) inside the rounded shape too
        cv.save();
        cv.clipPath(clip);
        super.onDrawForeground(cv);
        cv.restore();
    }
}
