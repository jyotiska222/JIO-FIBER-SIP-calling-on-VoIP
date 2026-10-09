package com.example.myapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Three tabs: 1 Call log, 2 Dialer, 3 Contacts. */
public class MainActivity extends Activity implements CallManager.Observer, Provisioner.Listener {
    static final String EXTRA_TAB = "tab", EXTRA_NUMBER = "number", ACTION_CALL_BACK = "com.example.myapp.CALL_BACK";
    private static final int REQ_PERMS = 1, REQ_MIC_FOR_CALL = 2, REQ_CONTACTS = 3;

    private CallLogPage logPage;
    private DialerPage dialerPage;
    private ContactsPage contactsPage;
    private LinearLayout[] tabs = new LinearLayout[3];
    private FrameLayout body;
    private View[] pages = new View[3];
    private int current = -1;
    private TextView status;
    private View statusDot;
    private TextView bannerCall;
    private String pendingNumber;
    private Provisioner prov;
    private int loginShownForVersion = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prov = ProvisionEnv.get(this);
        buildUi();
        if (b == null) select(0);
        handleIntent(getIntent());
        SipConfig cfg = SipConfig.get(this);
        if (cfg.isComplete() && !SipService.isRunning()) SipService.start(this);
        askFirstRun();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra(EXTRA_TAB)) select(i.getIntExtra(EXTRA_TAB, 0));
        if (ACTION_CALL_BACK.equals(i.getAction())) { dial(i.getStringExtra(EXTRA_NUMBER)); return; }
        Uri d = i.getData();
        if (d != null && "tel".equals(d.getScheme())) {
            select(1);
            dialerPage.setNumber(Uri.decode(d.getSchemeSpecificPart()));
        }
    }

    // ================================================================== UI
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        // ---- top bar: line status + menu
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView name = Ui.text(this, "JioFiber Calling", 20, Ui.TEXT, true);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        pill.setBackground(Ui.round(this, Ui.SURFACE2, 16));
        statusDot = new View(this);
        statusDot.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9)));
        pill.addView(statusDot);
        status = Ui.text(this, "", 12, Ui.TEXT, false);
        status.setPadding(Ui.dp(this, 7), 0, 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setMaxWidth(Ui.dp(this, 170));
        pill.addView(status);
        pill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Provisioner.State ps = prov.state();
            if (!SipConfig.get(MainActivity.this).hasCredentials() || ps == Provisioner.State.NEED_OTP) openLogin();
            else showStatusDetails();
        }});
        top.addView(pill);
        ImageView more = Ui.circleButton(this, R.drawable.ic_more, 0, Ui.TEXT, 44, 10);
        more.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { menu(v); } });
        top.addView(more);
        root.addView(top);

        // ---- "return to call" banner
        bannerCall = Ui.text(this, "", 14, 0xFFFFFFFF, true);
        bannerCall.setGravity(Gravity.CENTER);
        bannerCall.setBackgroundColor(Ui.GREEN);
        bannerCall.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        bannerCall.setVisibility(View.GONE);
        bannerCall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(CallNotifier.callScreenIntent(MainActivity.this)); }
        });
        root.addView(bannerCall);

        // ---- pages
        body = new FrameLayout(this);
        logPage = new CallLogPage(this);
        dialerPage = new DialerPage(this);
        contactsPage = new ContactsPage(this);
        pages = new View[]{logPage, dialerPage, contactsPage};
        for (View p : pages) { p.setVisibility(View.GONE); body.addView(p, new FrameLayout.LayoutParams(-1, -1)); }
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- bottom tabs
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.SURFACE);
        String[] labels = {"Call log", "Dialer", "Contacts"};
        int[] icons = {R.drawable.ic_history, R.drawable.ic_dialpad, R.drawable.ic_person};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            t.setBackground(Ui.ripple(new android.graphics.drawable.ColorDrawable(0)));
            ImageView ic = Ui.icon(this, icons[i], Ui.TEXT2, 24);
            t.addView(ic);
            TextView tx = Ui.text(this, labels[i], 11.5f, Ui.TEXT2, false);
            tx.setGravity(Gravity.CENTER);
            t.addView(tx);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { select(idx); } });
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(bar);
        setContentView(root);
    }

    void select(int i) {
        if (i < 0 || i > 2) i = 0;
        for (int k = 0; k < 3; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            ((ImageView) tabs[k].getChildAt(0)).setColorFilter(on ? Ui.ACCENT : Ui.TEXT2);
            TextView t = (TextView) tabs[k].getChildAt(1);
            t.setTextColor(on ? Ui.ACCENT : Ui.TEXT2);
            t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        current = i;
    }

    // ================================================================== calling
    /** One-tap call entry point used by every tab. */
    void dial(String number) {
        if (number == null || number.isEmpty()) return;
        if (!Permissions.has(this, android.Manifest.permission.RECORD_AUDIO)) {
            pendingNumber = number;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_FOR_CALL);
            return;
        }
        if (!SipService.isRunning()) SipService.start(this);
        String err = CallManager.get(this).placeCall(number);
        if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
        startActivity(CallNotifier.callScreenIntent(this));
    }

    void askContacts() {
        requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
    }

    // ================================================================== permissions / first run
    private void askFirstRun() {
        String[] missing = Permissions.runtimeMissing(this);
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        if (missing.length > 0 && !sp.getBoolean("asked_perms", false)) {
            sp.edit().putBoolean("asked_perms", true).apply();
            requestPermissions(missing, REQ_PERMS);
        } else {
            maybeBackgroundSetup();
        }
    }

    private void maybeBackgroundSetup() {
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        if (sp.getBoolean("bg_shown", false)) return;
        if (Permissions.ignoringBattery(this) && Permissions.fullScreenAllowed(this)) return;
        sp.edit().putBoolean("bg_shown", true).apply();
        showBackgroundDialog();
    }

    private void showBackgroundDialog() {
        final boolean batt = !Permissions.ignoringBattery(this), fs = !Permissions.fullScreenAllowed(this);
        StringBuilder m = new StringBuilder("To receive calls with the app closed or the screen off:\n\n");
        m.append(batt ? "• Allow the app to run in the background (battery optimisation off)\n" : "• Background running: OK\n");
        m.append(fs ? "• Allow full-screen call alerts on the lock screen\n" : "• Full-screen call alerts: OK\n");
        m.append("\nOn Xiaomi / Oppo / Vivo / Realme / Samsung phones also set Battery → \"No restrictions\" "
                + "and enable Auto-start for this app in the phone's own settings.");
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Keep the line alive").setMessage(m)
                .setNegativeButton("Later", null)
                .setNeutralButton("App settings", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface x, int w) { Permissions.openAppSettings(MainActivity.this); }
                });
        if (batt || fs) d.setPositiveButton("Fix now", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface x, int w) {
                if (batt) Permissions.requestBatteryExemption(MainActivity.this);
                else Permissions.openFullScreenSettings(MainActivity.this);
            }
        });
        d.show();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_MIC_FOR_CALL) {
            boolean ok = res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (ok && pendingNumber != null) dial(pendingNumber);
            else Toast.makeText(this, "Microphone permission is required to make calls", Toast.LENGTH_LONG).show();
            pendingNumber = null;
            return;
        }
        if (Store.canReadContacts(this)) Store.get(this).sync();
        contactsPage.filter();
        if (req == REQ_PERMS) maybeBackgroundSetup();
    }

    // ================================================================== status / menu
    private void showStatusDetails() {
        CallManager cm = CallManager.get(this);
        String s = cm.regText() + (prov.message().isEmpty() ? "" : "\nLogin: " + prov.message())
                + "\n\nRouter: " + (SipConfig.get(this).host().isEmpty() ? "not found yet" : SipConfig.get(this).registrar())
                + "\nThis phone: " + SipConfig.get(this).deviceName()
                + "\nPhone must be on the JioFiber Wi-Fi.\nSIP engine: "
                + (SipEngine.isAvailable() ? "pjsip " + SipEngine.nativeVersion() : "NOT LOADED (" + SipEngine.loadError() + ")")
                + "\nTLS certificate: " + (TlsFiles.present(this) ? "ok" : "missing")
                + "\nProximity wake lock: " + (CallManager.proximityLockSupported(this) ? "supported" : "not supported (using sensor fallback)")
                + "\nBattery optimisation off: " + (Permissions.ignoringBattery(this) ? "yes" : "NO")
                + "\nFull-screen call alerts: " + (Permissions.fullScreenAllowed(this) ? "yes" : "NO");
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Line status").setMessage(s)
                .setPositiveButton("Reconnect", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        SipService.start(MainActivity.this);
                        CallManager.get(MainActivity.this).restartEngine(true);
                    }
                })
                .setNeutralButton("SIP log", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { showLog(); }
                })
                .setNegativeButton("Fix login", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { openLogin(); }
                }).show();
    }

    void showLog() {
        final String text = CallManager.get(this).logText();
        TextView t = Ui.text(this, text.isEmpty() ? "(empty)" : text, 10.5f, Ui.TEXT, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 14);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("SIP log").setView(sv)
                .setPositiveButton("Copy", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("sip log", text));
                        Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("Close", null).show();
        sv.post(new Runnable() { @Override public void run() { ((ScrollView) sv).fullScroll(View.FOCUS_DOWN); } });
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 6, 0, "Jio login / refresh credentials");
        m.getMenu().add(0, 1, 0, "Settings");
        m.getMenu().add(0, 2, 1, "Sync contacts now");
        m.getMenu().add(0, 3, 2, "Permissions & background");
        m.getMenu().add(0, 4, 3, "SIP log");
        m.getMenu().add(0, 5, 4, "Clear call log");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem it) {
                switch (it.getItemId()) {
                    case 1: startActivity(new Intent(MainActivity.this, SettingsActivity.class)); break;
                    case 6: openLogin(); break;
                    case 2:
                        if (!Store.canReadContacts(MainActivity.this)) askContacts();
                        else { Store.get(MainActivity.this).sync(); Toast.makeText(MainActivity.this, "Syncing contacts…", Toast.LENGTH_SHORT).show(); }
                        break;
                    case 3: {
                        String[] miss = Permissions.runtimeMissing(MainActivity.this);
                        if (miss.length > 0) requestPermissions(miss, REQ_PERMS); else showBackgroundDialog();
                        break;
                    }
                    case 4: showLog(); break;
                    case 5:
                        new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                                .setMessage("Delete the whole call log?")
                                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) { Store.get(MainActivity.this).clearCalls(); }
                                }).setNegativeButton("Cancel", null).show();
                        break;
                }
                return true;
            }
        });
        m.show();
    }

    @Override public void onRegChanged() { updateStatus(); }

    @Override public void onProvisionChanged() {
        updateStatus();
        maybeOpenLogin();
    }

    void openLogin() { startActivity(new Intent(this, LoginActivity.class)); }

    /** The router doesn't know this phone: open the OTP page by itself (once per request). */
    private void maybeOpenLogin() {
        if (prov.state() == Provisioner.State.NEED_OTP && loginShownForVersion != prov.version() && !CallManager.get(this).inCall()) {
            loginShownForVersion = prov.version();
            openLogin();
        }
    }
    @Override public void onCallChanged() { updateStatus(); }

    private void updateStatus() {
        CallManager cm = CallManager.get(this);
        boolean ok = cm.isRegistered();
        String t = cm.regText();
        int color = ok ? Ui.GREEN : (t.startsWith("Connecting") || t.startsWith("Starting") ? Ui.AMBER : Ui.RED);
        String label = ok ? "Connected" : (t.length() > 34 ? t.substring(0, 34) + "…" : t);
        Provisioner.State ps = prov.state();
        if (!SipConfig.get(this).hasCredentials()) {
            color = Ui.AMBER;
            label = ps == Provisioner.State.NEED_OTP ? "Login needed: tap" : (ps == Provisioner.State.WORKING ? "Getting login…" : "Not logged in: tap");
        } else if (!ok && ps == Provisioner.State.WORKING) {
            color = Ui.AMBER;
            label = "Refreshing login…";
        } else if (!ok && ps == Provisioner.State.NEED_OTP) {
            color = Ui.AMBER;
            label = "Login needed: tap";
        }
        status.setText(label);
        statusDot.setBackground(Ui.oval(color));
        if (cm.inCall()) {
            bannerCall.setVisibility(View.VISIBLE);
            bannerCall.setText(cm.state() == CallManager.State.RINGING_IN ? "Incoming call · tap to answer" : "Ongoing call · tap to return");
        } else bannerCall.setVisibility(View.GONE);
    }

    @Override protected void onResume() {
        super.onResume();
        CallManager cm = CallManager.get(this);
        cm.addObserver(this);
        prov.addListener(this);
        if (!SipConfig.get(this).hasCredentials()) prov.ensureCredentials("app opened");
        updateStatus();
        maybeOpenLogin();
        contactsPage.filter();
        // the service owns the engine (it starts it when Wi-Fi is up and its watchdog repairs it)
        if (SipConfig.get(this).isComplete() && !SipService.isRunning()) SipService.start(this);
    }

    @Override protected void onPause() {
        prov.removeListener(this);
        CallManager.get(this).removeObserver(this);
        super.onPause();
    }
}
