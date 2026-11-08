package com.statusvault.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Checkable;
import android.widget.Switch;

/**
 * A Material 3 style switch drawn by hand, so it is exactly the same size and shape on every
 * phone and in both states (the platform Switch measures custom drawables inconsistently).
 * Track 52×32 dp; the thumb grows from 16 dp (off) to 24 dp (on) as it slides.
 */
public class ToggleSwitch extends View implements Checkable {

    public interface OnChangeListener {
        void onChanged(ToggleSwitch toggle, boolean checked);
    }

    private static final ArgbEvaluator ARGB = new ArgbEvaluator();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float density;
    private boolean checked;
    private float progress; // 0 = off, 1 = on
    private ValueAnimator animator;
    private OnChangeListener listener;
    private int onTrack = 0xFF00696B, onThumb = 0xFFFFFFFF, offTrack = 0xFFE3ECEB, offOutline = 0xFF6F7979;

    public ToggleSwitch(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(4 * density); // bigger touch target without changing the drawn size
        setPadding(pad, pad, pad, pad);
        setClickable(true);
        setFocusable(true);
    }

    public void setColors(int onTrack, int onThumb, int offTrack, int offOutline) {
        this.onTrack = onTrack;
        this.onThumb = onThumb;
        this.offTrack = offTrack;
        this.offOutline = offOutline;
        invalidate();
    }

    public void setOnChangeListener(OnChangeListener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(52 * density) + getPaddingLeft() + getPaddingRight();
        int h = Math.round(32 * density) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(w, h); // always the same size, whatever the parent offers
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft(), top = getPaddingTop();
        float w = 52 * density, h = 32 * density, r = h / 2f;
        track.set(left, top, left + w, top + h);

        // Track fill
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((Integer) ARGB.evaluate(progress, offTrack, onTrack));
        canvas.drawRoundRect(track, r, r, paint);

        // Outline fades out as it turns on
        if (progress < 1f) {
            float sw = 2 * density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            paint.setColor(offOutline);
            paint.setAlpha(Math.round(255 * (1f - progress)));
            RectF inner = new RectF(track.left + sw / 2, track.top + sw / 2, track.right - sw / 2, track.bottom - sw / 2);
            canvas.drawRoundRect(inner, r - sw / 2, r - sw / 2, paint);
        }

        // Thumb slides and grows
        float thumbR = (8 + 4 * progress) * density;
        float cx = left + r + progress * (w - h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((Integer) ARGB.evaluate(progress, offOutline, onThumb));
        canvas.drawCircle(cx, top + r, thumbR, paint);
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        if (isEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            setChecked(!checked, true);
        }
        return handled;
    }

    @Override
    public void setChecked(boolean value) {
        setChecked(value, isAttachedToWindow());
    }

    public void setChecked(boolean value, boolean animate) {
        if (value == checked) return;
        checked = value;
        if (animator != null) animator.cancel();
        if (animate) {
            animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
            animator.setDuration(200);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    progress = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        } else {
            progress = value ? 1f : 0f;
            invalidate();
        }
        if (listener != null) listener.onChanged(this, value);
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    @Override
    public void toggle() {
        setChecked(!checked, true);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Switch.class.getName());
        info.setCheckable(true);
        info.setChecked(checked);
    }
}
