package com.statusvault.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A portrait (3:4) tile frame. Unopened statuses get a small glowing dot in the top-left
 * corner with a soft pulse breathing out of it. Also gives a press-in effect when touched.
 */
public class AspectFrameLayout extends FrameLayout {

    private static final long PULSE_MS = 1800;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int dotColor; // 0 = no dot

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        rimPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    /** Show the "new" dot in [color], or 0 to hide it. */
    public void setNewDot(int color) {
        if (dotColor == color) return;
        dotColor = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = width * 4 / 3;
        super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (dotColor == 0) return;
        float cx = getPaddingLeft() + 15 * density;
        float cy = getPaddingTop() + 15 * density;
        float r = 5 * density;

        // Pulse: a halo grows and fades, over and over
        float t = (SystemClock.uptimeMillis() % PULSE_MS) / (float) PULSE_MS;
        haloPaint.setColor(dotColor);
        haloPaint.setAlpha(Math.round(150 * (1f - t)));
        canvas.drawCircle(cx, cy, r + 9 * density * t, haloPaint);

        // Steady soft glow + the dot with a white rim so it reads on any photo
        haloPaint.setAlpha(90);
        canvas.drawCircle(cx, cy, r + 3 * density, haloPaint);
        dotPaint.setColor(dotColor);
        canvas.drawCircle(cx, cy, r, dotPaint);
        rimPaint.setColor(0xF2FFFFFF);
        rimPaint.setStrokeWidth(1.6f * density);
        canvas.drawCircle(cx, cy, r, rimPaint);

        postInvalidateOnAnimation(); // keep pulsing while on screen
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().scaleX(0.95f).scaleY(0.95f).setDuration(110).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isSelected()) animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }
}
