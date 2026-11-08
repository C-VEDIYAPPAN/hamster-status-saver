package com.statusvault.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** A FrameLayout that is always 4:3 tall (portrait), like a status. Clips children to its rounded background. */
public class AspectFrameLayout extends FrameLayout {
    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = width * 4 / 3;
        super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    }
}
