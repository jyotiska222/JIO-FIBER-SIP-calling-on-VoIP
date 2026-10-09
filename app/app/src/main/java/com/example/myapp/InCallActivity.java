package com.example.myapp;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen call UI for incoming, outgoing and ongoing calls.
 *
 * Proximity: CallManager holds the system PROXIMITY_SCREEN_OFF_WAKE_LOCK while a call is up and
 * not on speaker/headset: the screen switches off when the phone is held to the ear and comes back
 * when it is moved away. If a phone doesn't support that lock, this class does the same with the
 * proximity sensor (black overlay + backlight off).
 */
public class InCallActivity extends Activity implements CallManager.Observer, SensorEventListener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private CallManager cm;

    private TextView nameV, numberV, statusV, avatarV;
    private LinearLayout incomingRow, activeBox, padBox;
    private ImageView btnMute, btnSpeaker, btnHold, btnKeypad;
    private View overlay;
    private TextView dtmfDisplay;
    private boolean sensorOn;
    private SensorManager sm;
    private Sensor prox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cm = CallManager.get(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build();
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handle(i); }

    private void handle(Intent i) {
        if (i != null && CallNotifier.ACT_ANSWER.equals(i.getAction())) {
            i.setAction(null);
            if (Permissions.has(this, android.Manifest.permission.RECORD_AUDIO)) cm.answer();
            else requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
        }
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == 7 && g.length > 0 && g[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) cm.answer();
    }

    // ======================================================================= layout
    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setFitsSystemWindows(true);
        col.setPadding(Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 24));

        statusV = Ui.text(this, "", 14, Ui.TEXT2, false);
        statusV.setGravity(Gravity.CENTER);
        col.addView(statusV);

        avatarV = Ui.text(this, "", 44, 0xFFFFFFFF, true);
        avatarV.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112));
        ap.topMargin = Ui.dp(this, 28);
        avatarV.setLayoutParams(ap);
        col.addView(avatarV);

        nameV = Ui.text(this, "", 28, Ui.TEXT, true);
        nameV.setGravity(Gravity.CENTER);
        nameV.setSingleLine(true);
        nameV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = Ui.dp(this, 18);
        col.addView(nameV, np);
        numberV = Ui.text(this, "", 15, Ui.TEXT2, false);
        numberV.setGravity(Gravity.CENTER);
        col.addView(numberV);

        dtmfDisplay = Ui.text(this, "", 22, Ui.TEXT, false);
        dtmfDisplay.setGravity(Gravity.CENTER);
        col.addView(dtmfDisplay, new LinearLayout.LayoutParams(-1, Ui.dp(this, 36)));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        // in-call keypad (DTMF)
        padBox = new LinearLayout(this);
        padBox.setOrientation(LinearLayout.VERTICAL);
        padBox.setGravity(Gravity.CENTER);
        padBox.setVisibility(View.GONE);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"*", "0", "#"}};
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (final String k : r) {
                TextView t = Ui.text(this, k, 26, Ui.TEXT, false);
                t.setGravity(Gravity.CENTER);
                t.setBackground(Ui.ripple(Ui.oval(Ui.SURFACE)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68));
                lp.setMargins(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
                t.setLayoutParams(lp);
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        cm.sendDtmf(k);
                        String s = dtmfDisplay.getText().toString() + k;
                        dtmfDisplay.setText(s.length() > 20 ? s.substring(s.length() - 20) : s);
                    }
                });
                row.addView(t);
            }
            padBox.addView(row);
        }
        col.addView(padBox);

        // active call controls
        activeBox = new LinearLayout(this);
        activeBox.setOrientation(LinearLayout.VERTICAL);
        activeBox.setGravity(Gravity.CENTER);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER);
        btnMute = ctl(R.drawable.ic_mic_off, new View.OnClickListener() { @Override public void onClick(View v) { cm.toggleMute(); } });
        btnKeypad = ctl(R.drawable.ic_dialpad, new View.OnClickListener() { @Override public void onClick(View v) {
            padBox.setVisibility(padBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); refresh(); } });
        btnSpeaker = ctl(R.drawable.ic_speaker, new View.OnClickListener() { @Override public void onClick(View v) { cm.setSpeaker(!cm.isSpeaker()); } });
        btnHold = ctl(R.drawable.ic_pause, new View.OnClickListener() { @Override public void onClick(View v) { cm.toggleHold(); } });
        r1.addView(label(btnMute, "Mute"));
        r1.addView(label(btnKeypad, "Keypad"));
        r1.addView(label(btnSpeaker, "Speaker"));
        r1.addView(label(btnHold, "Hold"));
        activeBox.addView(r1);
        ImageView end = Ui.circleButton(this, R.drawable.ic_call_end, Ui.RED, 0xFFFFFFFF, 72, 20);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72));
        ep.topMargin = Ui.dp(this, 22);
        end.setLayoutParams(ep);
        end.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cm.hangup(); } });
        activeBox.addView(end);
        col.addView(activeBox);

        // incoming controls
        incomingRow = new LinearLayout(this);
        incomingRow.setGravity(Gravity.CENTER);
        incomingRow.addView(bigAction(R.drawable.ic_call_end, Ui.RED, "Decline", new View.OnClickListener() {
            @Override public void onClick(View v) { cm.hangup(); } }));
        View gap = new View(this);
        incomingRow.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 70), 1));
        incomingRow.addView(bigAction(R.drawable.ic_call, Ui.GREEN, "Answer", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Permissions.has(InCallActivity.this, android.Manifest.permission.RECORD_AUDIO)) cm.answer();
                else requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
            } }));
        col.addView(incomingRow);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // black cover used only by the software proximity fallback
        overlay = new View(this);
        overlay.setBackgroundColor(0xFF000000);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private ImageView ctl(int icon, View.OnClickListener l) {
        ImageView v = Ui.circleButton(this, icon, Ui.SURFACE2, Ui.TEXT, 60, 16);
        v.setOnClickListener(l);
        return v;
    }

    private View label(ImageView btn, String text) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 78), -2);
        c.setLayoutParams(lp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60));
        btn.setLayoutParams(bp);
        c.addView(btn);
        TextView t = Ui.text(this, text, 12, Ui.TEXT2, false);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        return c;
    }

    private View bigAction(int icon, int color, String text, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView b = Ui.circleButton(this, icon, color, 0xFFFFFFFF, 76, 22);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)));
        b.setOnClickListener(l);
        c.addView(b);
        TextView t = Ui.text(this, text, 13, Ui.TEXT2, false);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        return c;
    }

    // ======================================================================= state
    @Override public void onCallChanged() { refresh(); }
    @Override public void onRegChanged() { }

    private void refresh() {
        CallManager.State s = cm.state();
        if (s == CallManager.State.IDLE) { finish(); return; }

        String name = cm.displayName();
        String pretty = PhoneUtil.pretty(cm.number());
        nameV.setText(name);
        numberV.setText(name.equals(pretty) ? "" : pretty);
        String ini = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        boolean known = Store.get(this).nameFor(cm.number()) != null;
        avatarV.setText(known ? ini : "");
        avatarV.setBackground(Ui.oval(known ? Ui.ACCENT : Ui.SURFACE2));
        if (!known) avatarV.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_person, 0, 0);
        else avatarV.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        avatarV.setPadding(0, known ? 0 : Ui.dp(this, 30), 0, 0);

        boolean ringingIn = s == CallManager.State.RINGING_IN;
        incomingRow.setVisibility(ringingIn ? View.VISIBLE : View.GONE);
        activeBox.setVisibility(ringingIn ? View.GONE : View.VISIBLE);
        if (ringingIn) padBox.setVisibility(View.GONE);

        switch (s) {
            case DIALING:     statusV.setText("Calling…"); break;
            case RINGING_OUT: statusV.setText("Ringing…"); break;
            case RINGING_IN:  statusV.setText("Incoming call"); break;
            case HOLD:        statusV.setText("On hold · " + fmt(cm.talkSeconds())); break;
            case ACTIVE:      statusV.setText(fmt(cm.talkSeconds())); break;
            case ENDED:       statusV.setText(cm.endText()); padBox.setVisibility(View.GONE); break;
            default: break;
        }
        boolean live = s == CallManager.State.ACTIVE || s == CallManager.State.HOLD;
        setOn(btnMute, cm.isMuted(), R.drawable.ic_mic_off, R.drawable.ic_mic_off);
        setOn(btnSpeaker, cm.isSpeaker(), R.drawable.ic_speaker, R.drawable.ic_speaker);
        setOn(btnHold, s == CallManager.State.HOLD, R.drawable.ic_pause, R.drawable.ic_pause);
        setOn(btnKeypad, padBox.getVisibility() == View.VISIBLE, R.drawable.ic_dialpad, R.drawable.ic_dialpad);
        btnMute.setEnabled(live); btnHold.setEnabled(live); btnKeypad.setEnabled(live);
        btnMute.setAlpha(live ? 1f : 0.4f); btnHold.setAlpha(live ? 1f : 0.4f); btnKeypad.setAlpha(live ? 1f : 0.4f);
        if (s == CallManager.State.ENDED) { h.removeCallbacks(tick); h.postDelayed(closer, 1500); }
        else { h.removeCallbacks(closer); h.removeCallbacks(tick); h.postDelayed(tick, 1000); }
        syncSensor();
    }

    private void setOn(ImageView v, boolean on, int a, int b) {
        v.setBackground(Ui.ripple(Ui.oval(on ? 0xFFFFFFFF : Ui.SURFACE2)));
        v.setColorFilter(on ? 0xFF111111 : Ui.TEXT);
    }

    private static String fmt(int s) { return String.format("%02d:%02d", s / 60, s % 60); }

    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); } };
    private final Runnable closer = new Runnable() { @Override public void run() { finish(); } };

    // ======================================================================= proximity fallback
    private void syncSensor() {
        boolean need = !CallManager.proximityLockSupported(this) && !cm.isSpeaker()
                && (cm.state() == CallManager.State.ACTIVE || cm.state() == CallManager.State.HOLD
                    || cm.state() == CallManager.State.RINGING_OUT || cm.state() == CallManager.State.DIALING)
                && !cm.audio().headsetConnected();
        if (need && !sensorOn) {
            sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (prox != null) { sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL); sensorOn = true; }
        } else if (!need && sensorOn) stopSensor();
    }

    private void stopSensor() {
        if (sm != null) sm.unregisterListener(this);
        sensorOn = false;
        setNear(false);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        boolean near = e.values[0] < Math.min(prox.getMaximumRange(), 5f);
        setNear(near);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private void setNear(boolean near) {
        overlay.setVisibility(near ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = near ? 0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    // ======================================================================= lifecycle / keys
    @Override protected void onResume() {
        super.onResume();
        cm.addObserver(this);
        refresh();
    }

    @Override protected void onPause() {
        cm.removeObserver(this);
        h.removeCallbacks(tick);
        if (sensorOn) stopSensor();
        super.onPause();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
                && cm.state() == CallManager.State.RINGING_IN) {
            cm.ringer().silence();                       // volume key silences the ringer, like the stock dialer
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
