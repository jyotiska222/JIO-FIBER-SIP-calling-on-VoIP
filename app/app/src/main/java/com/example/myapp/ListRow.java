package com.example.myapp;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One row used by both the call log and the contacts list: avatar | title + subtitle | call icon. */
final class ListRow extends LinearLayout {
    private static final int[] PALETTE = {0xFF5B8DEF, 0xFF7C6BF2, 0xFFE0709A, 0xFF2BB59A, 0xFFE39B3B, 0xFF4FA3C7, 0xFFB5679A, 0xFF6BAA5A};

    private final FrameLayout avatar;
    private final TextView initial;
    private final ImageView photo, personIcon;
    private final TextView title, sub, trailing;
    private final ImageView subIcon;

    ListRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int h = Ui.dp(c, 16);
        setPadding(h, Ui.dp(c, 10), h, Ui.dp(c, 10));
        setBackground(Ui.ripple(new android.graphics.drawable.ColorDrawable(0x00000000)));

        avatar = new FrameLayout(c);
        LayoutParams ap = new LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46));
        ap.rightMargin = Ui.dp(c, 14);
        avatar.setLayoutParams(ap);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        avatar.setClipToOutline(true);
        initial = Ui.text(c, "", 18, 0xFFFFFFFF, true);
        initial.setGravity(Gravity.CENTER);
        avatar.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        personIcon = new ImageView(c);
        personIcon.setImageResource(R.drawable.ic_person);
        personIcon.setColorFilter(0xFFFFFFFF);
        int pp = Ui.dp(c, 10);
        personIcon.setPadding(pp, pp, pp, pp);
        avatar.addView(personIcon, new FrameLayout.LayoutParams(-1, -1));
        photo = new ImageView(c);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        addView(avatar);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        LayoutParams cp = new LayoutParams(0, -2, 1f);
        col.setLayoutParams(cp);
        title = Ui.text(c, "", 16.5f, Ui.TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);

        LinearLayout subRow = new LinearLayout(c);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subIcon = new ImageView(c);
        LayoutParams sp = new LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
        sp.rightMargin = Ui.dp(c, 5);
        subIcon.setLayoutParams(sp);
        subRow.addView(subIcon);
        sub = Ui.text(c, "", 13, Ui.TEXT2, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        subRow.addView(sub);
        col.addView(subRow);
        addView(col);

        trailing = Ui.text(c, "", 12, Ui.TEXT2, false);
        trailing.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LayoutParams tp = new LayoutParams(-2, -2);
        tp.leftMargin = Ui.dp(c, 8);
        trailing.setLayoutParams(tp);
        addView(trailing);

        ImageView call = Ui.icon(c, R.drawable.ic_call, Ui.ACCENT, 22);
        LayoutParams cl = new LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
        cl.leftMargin = Ui.dp(c, 14);
        call.setLayoutParams(cl);
        addView(call);
    }

    /** @param name null/empty -> unknown number (generic person icon) */
    void bind(String name, String titleText, String subText, int subIconRes, int subIconTint,
              String trailingText, String photoUri, int titleColor) {
        title.setText(titleText);
        title.setTextColor(titleColor);
        sub.setText(subText);
        if (subIconRes != 0) {
            subIcon.setVisibility(VISIBLE);
            subIcon.setImageResource(subIconRes);
            subIcon.setColorFilter(subIconTint);
        } else subIcon.setVisibility(GONE);
        trailing.setText(trailingText == null ? "" : trailingText);

        boolean known = name != null && !name.trim().isEmpty() && !Character.isDigit(name.trim().charAt(0))
                && name.trim().charAt(0) != '+';
        avatar.setBackground(Ui.oval(PALETTE[Math.abs((name == null ? titleText : name).hashCode()) % PALETTE.length]));
        initial.setVisibility(known ? VISIBLE : GONE);
        personIcon.setVisibility(known ? GONE : VISIBLE);
        if (known) initial.setText(name.trim().substring(0, 1).toUpperCase());
        photo.setImageDrawable(null);
        if (photoUri != null) {
            try { photo.setImageURI(Uri.parse(photoUri)); } catch (Exception ignored) { }
        }
    }
}
