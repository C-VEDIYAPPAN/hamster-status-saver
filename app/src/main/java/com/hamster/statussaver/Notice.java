package com.hamster.statussaver;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * In-app message pill, used instead of system toasts. It sits just above the screen's floating
 * controls (system toasts land on top of them), replaces the current message immediately instead
 * of queuing, shows a ✓ / ⓘ / ! icon, and can carry one action such as "View".
 */
final class Notice {

    static final int INFO = 0, SUCCESS = 1, ERROR = 2;

    /** Where the bottom of the pill should sit, in px from the bottom of the window. */
    interface Offset {
        int bottomPx();
    }

    private final Activity activity;
    private final Offset offset;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private LinearLayout pill;
    private ImageView icon;
    private TextView text, action;
    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            dismiss();
        }
    };

    /**
     * How much of the navigation bar overlaps the content area. Zero when the window already sits
     * above the nav bar (older Android), the full bar height when drawing edge-to-edge.
     */
    static int navOverlap(Activity a) {
        View decor = a.getWindow().getDecorView();
        android.view.WindowInsets in = decor.getRootWindowInsets();
        if (in == null) return 0;
        View content = a.findViewById(android.R.id.content);
        int[] c = new int[2], d = new int[2];
        content.getLocationOnScreen(c);
        decor.getLocationOnScreen(d);
        int alreadyAbove = (d[1] + decor.getHeight()) - (c[1] + content.getHeight());
        return Math.max(0, in.getSystemWindowInsetBottom() - alreadyAbove);
    }

    Notice(Activity activity, Offset offset) {
        this.activity = activity;
        this.offset = offset;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    void show(String message, int kind) {
        show(message, kind, null, null);
    }

    void show(String message, int kind, String actionLabel, final Runnable onAction) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ensureView();
        boolean dark = Palette.isDark(activity);
        Palette pal = new Palette(activity);
        int accent = dark ? pal.primary : Palette.viewerPrimary(activity); // light tint reads on the dark pill
        int iconColor = kind == ERROR ? 0xFFFFB4AB : kind == SUCCESS ? accent : 0xFFE0E8E7;
        icon.setImageResource(kind == ERROR ? R.drawable.ic_error : kind == SUCCESS ? R.drawable.ic_done : R.drawable.ic_info);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        text.setText(message);
        if (actionLabel != null && onAction != null) {
            action.setVisibility(View.VISIBLE);
            action.setText(actionLabel);
            action.setTextColor(accent);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    onAction.run();
                }
            });
        } else {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        lp.bottomMargin = offset.bottomPx();
        pill.setLayoutParams(lp);

        pill.animate().cancel();
        if (pill.getVisibility() == View.VISIBLE && pill.getAlpha() > 0.5f) {
            // Already showing: swap the text in place with a small bump, no queue.
            pill.setScaleX(0.96f);
            pill.setScaleY(0.96f);
            pill.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0).setDuration(180)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        } else {
            pill.setVisibility(View.VISIBLE);
            pill.setAlpha(0f);
            pill.setScaleX(1f);
            pill.setScaleY(1f);
            pill.setTranslationY(16 * density);
            pill.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        pill.announceForAccessibility(message);
        main.removeCallbacks(hide);
        long ms = kind == ERROR || actionLabel != null ? 3500 : 2200;
        main.postDelayed(hide, ms);
    }

    void dismiss() {
        main.removeCallbacks(hide);
        if (pill == null || pill.getVisibility() != View.VISIBLE) return;
        pill.animate().cancel();
        pill.animate().alpha(0f).translationY(10 * density).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                pill.setVisibility(View.GONE);
            }
        }).start();
    }

    private void ensureView() {
        if (pill != null) return;
        int d4 = Math.round(4 * density);
        pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(d4 * 4, d4 * 3, d4 * 4, d4 * 3);
        pill.setMinimumHeight(Math.round(48 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF51E2423);
        bg.setStroke(Math.max(1, Math.round(density)), 0x26FFFFFF);
        bg.setCornerRadius(24 * density);
        pill.setBackground(bg);
        pill.setElevation(16 * density);
        pill.setClickable(true); // swallow taps so they don't reach what's underneath

        icon = new ImageView(activity);
        pill.addView(icon, new LinearLayout.LayoutParams(Math.round(20 * density), Math.round(20 * density)));

        text = new TextView(activity);
        text.setTextColor(0xFFF2F5F4);
        text.setTextSize(14);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = d4 * 3;
        pill.addView(text, tp);

        action = new TextView(activity);
        action.setTextSize(14);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setPadding(d4 * 4, d4 * 2, d4, d4 * 2);
        action.setVisibility(View.GONE);
        pill.addView(action);

        // The pill hugs its text, up to the screen width minus 16dp a side (420dp max on tablets).
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screen - Math.round(32 * density), Math.round(420 * density));
        text.setMaxWidth(width - Math.round(130 * density)); // room for icon, padding and an action
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pill.setVisibility(View.GONE);
        ((FrameLayout) activity.findViewById(android.R.id.content)).addView(pill, lp);
    }
}
