package com.example.myapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.TextView;

/** Tiny helpers so the whole UI can be built in code (no XML layouts to keep in sync). */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0E1014, SURFACE = 0xFF171A21, SURFACE2 = 0xFF222733;
    static final int TEXT = 0xFFF1F3F7, TEXT2 = 0xFF9BA3B1, ACCENT = 0xFF3D7BFF;
    static final int GREEN = 0xFF22B866, RED = 0xFFE5484D, AMBER = 0xFFF5A623, DIV = 0x1FFFFFFF;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** Wraps a background so it shows a touch ripple. */
    static Drawable ripple(Drawable bg) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null);
    }

    static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(c, sizeDp);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(p, p));
        return v;
    }

    /** Round coloured button holding an icon. */
    static ImageView circleButton(Context c, int res, int bg, int tint, int sizeDp, int padDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        v.setBackground(ripple(oval(bg)));
        int p = dp(c, padDp);
        v.setPadding(p, p, p, p);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        int s = dp(c, sizeDp);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(s, s));
        return v;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
