package com.statusvault.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** A small circular timer: the ring empties as a status's 24 hours run out, hours in the middle. */
public class CountdownBadge extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float density;
    private float fraction = 1f;
    private String label = "";

    public CountdownBadge(Context context) {
        this(context, null);
    }

    public CountdownBadge(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        fill.setColor(0x8C000000);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(0x40FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    /** [millisLeft] of the 24h; [color] for the arc. */
    public void set(long millisLeft, int color) {
        fraction = Math.max(0f, Math.min(1f, millisLeft / (24f * 3600_000f)));
        long hours = millisLeft / 3600_000L;
        label = hours >= 1 ? hours + "h" : Math.max(1, millisLeft / 60_000L) + "m";
        arc.setColor(color);
        setContentDescription(label + " left");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float stroke = Math.max(2f * density, size * 0.09f);
        canvas.drawCircle(cx, cy, size / 2f, fill);
        float r = size / 2f - stroke / 2f - density;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        canvas.drawCircle(cx, cy, r, track);
        canvas.drawArc(oval, -90f, -360f * fraction, false, arc); // drains anticlockwise
        text.setTextSize(size * (label.length() > 2 ? 0.30f : 0.36f));
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }
}
