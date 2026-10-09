package com.example.myapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tab 2: on-screen dialer. */
final class DialerPage extends LinearLayout {
    private static final String[][] KEYS = {
            {"1", ""}, {"2", "ABC"}, {"3", "DEF"}, {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
            {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"}, {"*", ""}, {"0", "+"}, {"#", ""}};
    private static final int[] TONES = {ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
            ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_6, ToneGenerator.TONE_DTMF_7,
            ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9, ToneGenerator.TONE_DTMF_S, ToneGenerator.TONE_DTMF_0,
            ToneGenerator.TONE_DTMF_P};

    private final MainActivity act;
    private final TextView display, match;
    private final StringBuilder num = new StringBuilder();
    private ToneGenerator tone;
    private static String lastDialed = "";

    DialerPage(MainActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        display = Ui.text(a, "", 34, Ui.TEXT, false);
        display.setGravity(Gravity.CENTER);
        display.setSingleLine(true);
        display.setEllipsize(android.text.TextUtils.TruncateAt.START);
        LayoutParams dp = new LayoutParams(-1, Ui.dp(a, 64));
        dp.topMargin = Ui.dp(a, 12);
        dp.leftMargin = dp.rightMargin = Ui.dp(a, 24);
        addView(display, dp);
        display.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { paste(); return true; }
        });

        match = Ui.text(a, "", 14, Ui.ACCENT, false);
        match.setGravity(Gravity.CENTER);
        addView(match, new LayoutParams(-1, Ui.dp(a, 24)));

        LinearLayout pad = new LinearLayout(a);
        pad.setOrientation(VERTICAL);
        pad.setGravity(Gravity.CENTER);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) row.addView(key(r * 3 + c));
            pad.addView(row);
        }
        LinearLayout bottom = new LinearLayout(a);
        bottom.setGravity(Gravity.CENTER);
        bottom.addView(spacer());
        ImageView call = Ui.circleButton(a, R.drawable.ic_call, Ui.GREEN, 0xFFFFFFFF, 68, 18);
        LayoutParams cp = new LayoutParams(Ui.dp(a, 68), Ui.dp(a, 68));
        cp.setMargins(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8));
        call.setLayoutParams(cp);
        call.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { placeCall(); } });
        bottom.addView(call);
        ImageView back = Ui.circleButton(a, R.drawable.ic_backspace, 0x00000000, Ui.TEXT2, 56, 16);
        back.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { backspace(); } });
        back.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { num.setLength(0); refresh(); return true; }
        });
        bottom.addView(back);
        pad.addView(bottom);
        addView(pad, new LayoutParams(-1, 0, 1f));
    }

    private View spacer() { return new View(getContext()) {
        { setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(getContext(), 56), Ui.dp(getContext(), 56))); }
    }; }

    private View key(final int idx) {
        Context c = getContext();
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(VERTICAL);
        k.setGravity(Gravity.CENTER);
        LayoutParams lp = new LayoutParams(Ui.dp(c, 84), Ui.dp(c, 70));
        lp.setMargins(Ui.dp(c, 6), Ui.dp(c, 3), Ui.dp(c, 6), Ui.dp(c, 3));
        k.setLayoutParams(lp);
        k.setBackground(Ui.ripple(Ui.oval(0x00000000)));
        k.setClickable(true);
        TextView d = Ui.text(c, KEYS[idx][0], 28, Ui.TEXT, false);
        d.setGravity(Gravity.CENTER);
        k.addView(d);
        TextView s = Ui.text(c, KEYS[idx][1], 10, Ui.TEXT2, false);
        s.setGravity(Gravity.CENTER);
        k.addView(s);
        k.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { press(KEYS[idx][0], idx); v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
        });
        if (idx == 10) k.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { press("+", -1); return true; }
        });
        return k;
    }

    private void press(String s, int toneIdx) {
        if (num.length() < 32) num.append(s);
        refresh();
        if (toneIdx >= 0) beep(TONES[toneIdx]);
    }

    private void beep(int t) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_DTMF, 70);
            tone.startTone(t, 90);
        } catch (Exception ignored) { }
    }

    private void backspace() {
        if (num.length() > 0) num.setLength(num.length() - 1);
        refresh();
    }

    private void paste() {
        ClipboardManager cb = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cb.getPrimaryClip().getItemAt(0).getText();
            if (t != null) { num.setLength(0); num.append(PhoneUtil.clean(t.toString())); refresh(); }
        }
    }

    void setNumber(String n) { num.setLength(0); num.append(PhoneUtil.clean(n)); refresh(); }

    private void refresh() {
        String n = num.toString();
        display.setText(n.isEmpty() ? "" : n);
        display.setTextSize(n.length() > 14 ? 24 : (n.length() > 10 ? 28 : 34));
        String name = n.length() >= 3 ? Store.get(getContext()).nameFor(n) : null;
        // as-you-type suggestion: first contact whose number contains what was typed
        if (name == null && n.length() >= 4) {
            String d = PhoneUtil.digits(n);
            for (Store.Contact k : Store.get(getContext()).contacts())
                if (k.key != null && k.number != null && PhoneUtil.digits(k.number).contains(d)) { name = k.name; break; }
        }
        match.setText(name == null ? "" : name);
    }

    private void placeCall() {
        if (num.length() == 0) {                 // like a normal phone: call button on empty = redial
            if (!lastDialed.isEmpty()) setNumber(lastDialed);
            return;
        }
        lastDialed = num.toString();
        act.dial(lastDialed);
    }
}
