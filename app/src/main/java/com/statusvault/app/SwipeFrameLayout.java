package com.statusvault.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Watches every touch for horizontal flings and taps without stealing them from children. */
public class SwipeFrameLayout extends FrameLayout {

    public interface Listener {
        void onSwipeNext();
        void onSwipePrevious();
        void onTap();
    }

    private Listener listener;
    private final GestureDetector detector;

    public SwipeFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || listener == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float min = 80 * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > min) {
                    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                    boolean towardsNext = rtl ? dx > 0 : dx < 0;
                    if (towardsNext) listener.onSwipeNext(); else listener.onSwipePrevious();
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Taps on the bars' buttons should not count as "tap on media". */
    private boolean isOnClickableChild(MotionEvent ev) {
        int[] ids = {R.id.top_bar, R.id.bottom_bar, R.id.video_controls, R.id.prev, R.id.next, R.id.play_state};
        for (int id : ids) {
            android.view.View v = findViewById(id);
            if (v != null && v.getVisibility() == VISIBLE && v.getAlpha() > 0.1f
                    && ev.getX() >= v.getLeft() && ev.getX() <= v.getRight()
                    && ev.getY() >= v.getTop() && ev.getY() <= v.getBottom()) {
                return true;
            }
        }
        return false;
    }

    /** True while the current gesture started on a toolbar, so it's left to the buttons alone. */
    private boolean ignoreGesture;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ignoreGesture = isOnClickableChild(ev);
        }
        if (!ignoreGesture) {
            detector.onTouchEvent(ev);
        }
        super.dispatchTouchEvent(ev);
        return true;
    }
}
