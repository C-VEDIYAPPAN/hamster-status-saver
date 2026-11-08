package com.statusvault.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** A soft, blurred, gently breathing accent glow (sits behind the floating tab bar). */
public class GlowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private int colorA = 0xFF00696B, colorB = 0xFF1A5FB4;
    private ValueAnimator breath;

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter needs a software layer
        paint.setMaskFilter(new BlurMaskFilter(14 * density, BlurMaskFilter.Blur.NORMAL));
    }

    public void setColors(int a, int b) {
        colorA = a;
        colorB = b;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float blur = 16 * density;
        rect.set(blur, blur, getWidth() - blur, getHeight() - blur);
        paint.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                (colorA & 0x00FFFFFF) | 0x47000000, (colorB & 0x00FFFFFF) | 0x47000000, Shader.TileMode.CLAMP));
        float r = rect.height() / 2f;
        canvas.drawRoundRect(rect, r, r, paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        breath = ValueAnimator.ofFloat(0.6f, 0.9f);
        breath.setDuration(3200);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                setAlpha((Float) a.getAnimatedValue());
            }
        });
        breath.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (breath != null) breath.cancel();
        super.onDetachedFromWindow();
    }
}
