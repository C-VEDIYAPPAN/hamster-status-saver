package com.statusvault.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A portrait (3:4) tile frame. It can draw a story-style ring in its padding:
 * a slowly rotating multi-colour gradient for new statuses, or a faint hairline otherwise.
 * Also gives a soft press-in effect when touched.
 */
public class AspectFrameLayout extends FrameLayout {

    private static final long TURN_MS = 3600; // one full rotation of the ring

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float density;
    private SweepGradient gradient;
    private int[] ringColors;
    private boolean ringAnimated;
    private int hairline;
    private float radiusDp = 21f;

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    /** Animated gradient ring (new status). */
    public void setRing(int[] colors) {
        ringColors = colors;
        ringAnimated = true;
        gradient = null;
        invalidate();
    }

    /** Thin static outline (already seen), or 0 for none. */
    public void setHairline(int color) {
        ringColors = null;
        ringAnimated = false;
        hairline = color;
        invalidate();
    }

    public void setRingRadius(float dp) {
        radiusDp = dp;
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
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        gradient = null; // rebuild for the new centre
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float r = radiusDp * density;
        if (ringAnimated && ringColors != null) {
            float stroke = 2.6f * density;
            ringRect.set(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
            if (gradient == null) gradient = new SweepGradient(getWidth() / 2f, getHeight() / 2f, ringColors, null);
            float angle = (SystemClock.uptimeMillis() % TURN_MS) * 360f / TURN_MS;
            matrix.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
            gradient.setLocalMatrix(matrix);
            ringPaint.setShader(gradient);
            ringPaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(ringRect, r, r, ringPaint);
            postInvalidateOnAnimation(); // keep it turning while on screen
        } else if (hairline != 0) {
            float stroke = Math.max(1f, density);
            float inset = 2f * density; // sit just outside the card
            ringRect.set(inset - stroke / 2, inset - stroke / 2, getWidth() - inset + stroke / 2, getHeight() - inset + stroke / 2);
            ringPaint.setShader(null);
            ringPaint.setColor(hairline);
            ringPaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(ringRect, r - 1.5f * density, r - 1.5f * density, ringPaint);
        }
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
