package com.example.myapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private SipConfig cfg;
    private EditText host, port, domain, id, user, pass, prefix;
    private Switch swPhone, swIn, swCpu, swBoot, swVib, swVerbose;
    private LinearLayout col;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = SipConfig.get(this);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setFitsSystemWindows(true);
        sv.setBackgroundColor(Ui.BG);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        col.setPadding(p, p, p, p * 2);
        sv.addView(col);

        col.addView(Ui.text(this, "Settings", 24, Ui.TEXT, true));

        header("Jio account");
        note("You don't need to type anything here. The app fetches these from your JioFiber router "
                + "(and re-fetches them when Jio changes them). Open the login page if something looks wrong.");
        button("Jio login / refresh credentials", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new android.content.Intent(SettingsActivity.this, LoginActivity.class)); } });
        button("Forget saved credentials", Ui.SURFACE2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Remove the saved Jio username and password from this phone? You can fetch them again from the router.")
                        .setPositiveButton("Forget", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                cfg.clearCredentials();
                                CallManager.get(SettingsActivity.this).stopEngine(true);
                                toast("Credentials removed");
                                recreate(); } })
                        .setNegativeButton("Cancel", null).show(); } });

        header("Details (advanced, filled in automatically)");
        host = field("Router address", cfg.host(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field("Router SIP port", String.valueOf(cfg.port()), InputType.TYPE_CLASS_NUMBER);
        domain = field("Domain for calls", cfg.domain(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        id = field("SIP identity", cfg.id(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = field("Auth username", cfg.user(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pass = field("Password", cfg.pass(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        prefix = field("Prefix added to 10-digit numbers", cfg.numberPrefix(), InputType.TYPE_CLASS_PHONE);
        note("This phone is known to the router as \"" + cfg.deviceName() + "\"  (device id " + cfg.instanceId()
                + ", " + cfg.deviceMac() + "). It is derived automatically and stays the same after reinstalling.");

        header("Dialling");
        swPhone = sw("Add ;user=phone to call addresses", cfg.userPhone());
        swIn = sw("Convert +91 / 91 numbers to the 10-digit form", cfg.convertIN());

        header("Background & ringing");
        swCpu = sw("Keep CPU awake for incoming calls (uses a little battery)", cfg.keepCpu());
        swBoot = sw("Start the line after the phone restarts", cfg.startOnBoot());
        swVib = sw("Allow vibration (follows the phone's ringer mode)", cfg.vibrate());
        swVerbose = sw("Detailed SIP log (for troubleshooting)", cfg.verboseLog());
        button("Battery / background settings", Ui.SURFACE2, new View.OnClickListener() {
            @Override public void onClick(View v) { Permissions.requestBatteryExemption(SettingsActivity.this); } });

        button("Save and reconnect", Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { save(); } });
        button("Reset behaviour settings", Ui.SURFACE2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Reset the dialling, background and ringing options to their defaults? Your Jio login is kept.")
                        .setPositiveButton("Reset", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                cfg.edit().remove("prefix").remove("user_phone").remove("convert_in").remove("keep_cpu")
                                        .remove("boot").remove("vibrate").remove("verbose").apply();
                                recreate(); } })
                        .setNegativeButton("Cancel", null).show(); } });
        setContentView(sv);
    }

    private void save() {
        String h = host.getText().toString().trim();
        int pt;
        try { pt = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { pt = 0; }
        if (pt <= 0 || pt > 65535) { toast("Check the router port"); return; }
        String idv = id.getText().toString().trim();
        if (!idv.isEmpty() && !idv.startsWith("sip:")) idv = "sip:" + idv;
        cfg.edit().putString("host", h).putInt("port", pt)
                .putString("domain", domain.getText().toString().trim())
                .putString("id", idv)
                .putString("user", user.getText().toString().trim())
                .putString("pass", pass.getText().toString())
                .putString("prefix", prefix.getText().toString().trim())
                .putBoolean("user_phone", swPhone.isChecked()).putBoolean("convert_in", swIn.isChecked())
                .putBoolean("keep_cpu", swCpu.isChecked()).putBoolean("boot", swBoot.isChecked())
                .putBoolean("vibrate", swVib.isChecked()).putBoolean("verbose", swVerbose.isChecked()).apply();
        if (CallManager.get(this).inCall()) { toast("Saved. Applies after the current call."); return; }
        SipService.stop(this);
        SipService.start(this);                       // service restart re-reads the wake-lock option too
        CallManager.get(this).restartEngine(true);
        toast("Saved. Reconnecting…");
        finish();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void header(String s) {
        TextView t = Ui.text(this, s, 13, Ui.ACCENT, true);
        t.setAllCaps(true);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    private void note(String s) {
        TextView t = Ui.text(this, s, 12.5f, Ui.TEXT2, false);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    private EditText field(String label, String value, int type) {
        TextView l = Ui.text(this, label, 12, Ui.TEXT2, false);
        l.setPadding(0, Ui.dp(this, 10), 0, 0);
        col.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(15);
        e.setBackground(Ui.round(this, Ui.SURFACE2, 10));
        int p = Ui.dp(this, 12);
        e.setPadding(p, p, p, p);
        col.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Switch sw(String label, boolean on) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(14.5f);
        s.setChecked(on);
        s.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        col.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private void button(String s, int bg, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 15, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.ripple(Ui.round(this, bg, 12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        lp.topMargin = Ui.dp(this, 14);
        t.setOnClickListener(l);
        col.addView(t, lp);
    }
}
