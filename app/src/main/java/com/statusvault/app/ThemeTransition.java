package com.statusvault.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

/**
 * Light/dark switch without the flash: before the screen is rebuilt we take a picture of it,
 * show that picture behind the rebuilt screen, and reveal the new theme in a growing circle
 * from the point the user tapped.
 */
final class ThemeTransition {

    private static Bitmap snapshot;
    private static float cx, cy;

    private ThemeTransition() {
    }

    /** Call just before recreate(). [x],[y] are window coordinates of the tap. */
    static void capture(Activity a, float x, float y) {
        try {
            View decor = a.getWindow().getDecorView();
            if (decor.getWidth() == 0 || decor.getHeight() == 0) return;
            Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(b));
            snapshot = b;
            cx = x;
            cy = y;
        } catch (RuntimeException | OutOfMemoryError e) {
            snapshot = null; // no picture, no animation — the switch still works
        }
    }

    /** Call at the end of onCreate() of the rebuilt activity. */
    static void play(final Activity a) {
        final Bitmap old = snapshot;
        snapshot = null;
        if (old == null) return;
        final View decor = a.getWindow().getDecorView();
        final View content = a.findViewById(android.R.id.content);
        final Drawable originalBackground = decor.getBackground();
        // The old screen sits behind the new content while the circle grows.
        decor.setBackground(new BitmapDrawable(a.getResources(), old));
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] loc = new int[2];
                content.getLocationInWindow(loc);
                int x = Math.round(cx - loc[0]);
                int y = Math.round(cy - loc[1]);
                float w = content.getWidth(), h = content.getHeight();
                float radius = (float) Math.hypot(Math.max(x, w - x), Math.max(y, h - y));
                try {
                    Animator reveal = ViewAnimationUtils.createCircularReveal(content, x, y, 0f, radius);
                    reveal.setDuration(480);
                    reveal.setInterpolator(new DecelerateInterpolator(1.4f));
                    reveal.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            decor.setBackground(originalBackground);
                            old.recycle();
                        }
                    });
                    reveal.start();
                } catch (RuntimeException e) {
                    decor.setBackground(originalBackground);
                    old.recycle();
                }
                return true;
            }
        });
    }
}
