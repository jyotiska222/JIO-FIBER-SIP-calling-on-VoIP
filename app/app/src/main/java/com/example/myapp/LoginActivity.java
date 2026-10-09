package com.example.myapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The Jio login page. The app opens it by itself when the router does not know this phone yet
 * (first time only): "Send OTP" -> type the OTP SMS -> "Log in". After that the credentials are
 * kept up to date silently and this page is never needed again.
 */
public class LoginActivity extends Activity implements Provisioner.Listener {
    private Provisioner prov;
    private SipConfig cfg;
    private TextView title, message, info;
    private ProgressBar spinner;
    private LinearLayout otpBox, ipBox;
    private TextView btnSend, btnLogin, btnRefresh, btnIp;
    private EditText code, ip;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean closing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prov = ProvisionEnv.get(this);
        cfg = SipConfig.get(this);
        build();
        if (cfg.hasCredentials() && prov.state() == Provisioner.State.IDLE) prov.manualRefresh();
        else if (prov.state() == Provisioner.State.IDLE || prov.state() == Provisioner.State.FAILED
                || (prov.state() == Provisioner.State.OK && !cfg.hasCredentials())) prov.manualRefresh();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setFitsSystemWindows(true);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        col.setPadding(p, Ui.dp(this, 30), p, p);
        sv.addView(col);

        title = Ui.text(this, "Jio login", 26, Ui.TEXT, true);
        col.addView(title);
        TextView sub = Ui.text(this, "The app gets your SIP line from your JioFiber router. The first time, Jio asks you to confirm "
                + "this phone with an OTP. After that, password changes are picked up automatically.", 13.5f, Ui.TEXT2, false);
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 18));
        col.addView(sub);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.SURFACE, 16));
        int cp = Ui.dp(this, 18);
        card.setPadding(cp, cp, cp, cp);
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        message = Ui.text(this, "", 16, Ui.TEXT, false);
        message.setGravity(Gravity.START);
        card.addView(message);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
        sp.topMargin = Ui.dp(this, 12);
        card.addView(spinner, sp);

        // OTP row (shown while the router wants an OTP)
        otpBox = new LinearLayout(this);
        otpBox.setOrientation(LinearLayout.VERTICAL);
        btnSend = button("Send OTP", Ui.SURFACE2);
        otpBox.addView(btnSend, lp(12));
        code = new EditText(this);
        code.setHint("Enter OTP");
        code.setHintTextColor(Ui.TEXT2);
        code.setTextColor(Ui.TEXT);
        code.setTextSize(22);
        code.setGravity(Gravity.CENTER);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        code.setBackground(Ui.round(this, Ui.SURFACE2, 12));
        try { code.setAutofillHints("smsOTPCode"); } catch (Throwable ignored) { }
        otpBox.addView(code, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        ((LinearLayout.LayoutParams) code.getLayoutParams()).topMargin = Ui.dp(this, 10);
        btnLogin = button("Log in", Ui.ACCENT);
        otpBox.addView(btnLogin, lp(10));
        card.addView(otpBox);

        // manual router address (only if the router can't be found automatically)
        ipBox = new LinearLayout(this);
        ipBox.setOrientation(LinearLayout.VERTICAL);
        ip = new EditText(this);
        ip.setHint("Router address, e.g. 192.168.31.1");
        ip.setHintTextColor(Ui.TEXT2);
        ip.setTextColor(Ui.TEXT);
        ip.setSingleLine(true);
        ip.setText(cfg.host());
        ip.setBackground(Ui.round(this, Ui.SURFACE2, 12));
        int ipad = Ui.dp(this, 12);
        ip.setPadding(ipad, ipad, ipad, ipad);
        ipBox.addView(ip, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        btnIp = button("Use this router address", Ui.SURFACE2);
        ipBox.addView(btnIp, lp(10));
        card.addView(ipBox, lp(14));

        btnRefresh = button("Fix login / refresh credentials", Ui.SURFACE2);
        card.addView(btnRefresh, lp(14));

        info = Ui.text(this, "", 12, Ui.TEXT2, false);
        info.setPadding(0, Ui.dp(this, 16), 0, 0);
        col.addView(info);

        btnSend.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            prov.startOtp(new Provisioner.Result() { @Override public void done(boolean ok, String m) { toast(m); } });
        }});
        btnLogin.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            hideKeyboard();
            prov.submitOtp(code.getText().toString().trim(), new Provisioner.Result() {
                @Override public void done(boolean ok, String m) { toast(m); if (!ok) code.setText(""); } });
        }});
        btnRefresh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { prov.manualRefresh(); } });
        btnIp.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String a = ip.getText().toString().trim();
            if (a.matches("\\d{1,3}(\\.\\d{1,3}){3}")) { hideKeyboard(); prov.useRouterAddress(a); }
            else toast("Enter an IPv4 address such as 192.168.31.1");
        }});
        setContentView(sv);
    }

    private TextView button(String s, int bg) {
        TextView t = Ui.text(this, s, 15, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.ripple(Ui.round(this, bg, 12)));
        t.setClickable(true);
        t.setMinHeight(Ui.dp(this, 50));
        return t;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
        l.topMargin = Ui.dp(this, topDp);
        return l;
    }

    private void toast(String s) { if (s != null && !s.isEmpty()) Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && getCurrentFocus() != null) im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    @Override public void onProvisionChanged() { refresh(); }

    private void refresh() {
        Provisioner.State s = prov.state();
        String m = prov.message();
        if (m.isEmpty()) m = cfg.hasCredentials() ? "Logged in." : "Checking with the router…";
        if (s == Provisioner.State.NEED_OTP && !prov.phone().isEmpty()) m += "\nOTP is sent to " + prov.phone();
        message.setText(m);
        spinner.setVisibility(s == Provisioner.State.WORKING ? View.VISIBLE : View.GONE);
        otpBox.setVisibility(s == Provisioner.State.NEED_OTP ? View.VISIBLE : View.GONE);
        boolean notFound = s == Provisioner.State.FAILED && prov.message().startsWith("Router not found");
        ipBox.setVisibility(notFound ? View.VISIBLE : View.GONE);
        btnRefresh.setVisibility(s == Provisioner.State.WORKING ? View.GONE : View.VISIBLE);
        info.setText("Phone ID: " + cfg.deviceName() + "  (" + cfg.deviceMac() + ")"
                + (prov.routerIp() != null ? "\nRouter: " + prov.routerIp() : ""));
        if (s == Provisioner.State.OK && cfg.hasCredentials() && !closing) {
            closing = true;
            toast("Logged in. The line is connecting…");
            h.postDelayed(new Runnable() { @Override public void run() { finish(); } }, 900);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        prov.addListener(this);
        CallNotifier.cancelLoginNeeded(this);
        refresh();
    }

    @Override protected void onPause() {
        prov.removeListener(this);
        super.onPause();
    }
}
