package com.statusvault.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.view.animation.DecelerateInterpolator;

/**
 * Light/dark switch without the flash: before the screen is rebuilt we take a picture of it,
 * lay that picture over the rebuilt screen, and fade it out — a short, soft crossfade.
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

    /** Call at the end of onCreate() of the rebuilt activity: the old screen softly fades away. */
    static void play(final Activity a) {
        final Bitmap old = snapshot;
        snapshot = null;
        if (old == null) return;
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        final ImageView cover = new ImageView(a);
        cover.setImageBitmap(old);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setClickable(true); // ignore taps during the short fade
        decor.addView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover.animate().alpha(0f).setStartDelay(40).setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        decor.removeView(cover);
                        old.recycle();
                    }
                }).start();
    }
}
