package com.example.myapp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Self-healing Jio credentials: the Android port of the Provisioner class in jio_provision.py.
 *
 *   IDLE      nothing going on
 *   WORKING   looking for the router / fetching credentials
 *   NEED_OTP  the router does not know this device yet -> the login page asks for an OTP (first time only)
 *   OK        credentials saved
 *   FAILED    router not found / refused  (the "Fix login" button tries again)
 *
 * Registration failures call {@link #onProblem}: rate limited to one attempt per 60 s and at most
 * 3 automatic attempts in a row, exactly like the web app. Fetching credentials never needs an OTP
 * once the router knows the device.
 *
 * No Android classes here; everything platform-specific goes through {@link Env}.
 */
final class Provisioner {
    enum State { IDLE, WORKING, NEED_OTP, OK, FAILED }

    static final long MIN_GAP_MS = 60_000;     // between automatic attempts
    static final int MAX_AUTO_FAILS = 3;       // then wait for the user (Fix login button)

    interface Env {
        /** Connector bound to the Wi-Fi network; throws if the phone is not on Wi-Fi. */
        JioProtocol.Connector connector() throws IOException;
        /** Router address candidates, best first (Wi-Fi gateway, jiofiber.local.html ...). */
        List<String> routerCandidates();
        String savedRouter();
        void saveRouter(String ip);
        String deviceName();
        boolean hasCredentials();
        /** Stores the credentials; returns true if anything changed. */
        boolean saveCredentials(String routerIp, String username, String password, String domain);
        void credentialsUpdated(boolean changed);
        void runBackground(Runnable r);
        void runMain(Runnable r);
        long now();
        void log(String line);
    }

    interface Listener { void onProvisionChanged(); }
    interface Result { void done(boolean ok, String message); }

    private final Env env;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object busy = new Object();
    private boolean running;

    private volatile State state = State.IDLE;
    private volatile String message = "", phone = "";
    private volatile int version;
    private volatile long lastAttempt;
    private volatile int fails;
    private volatile String ip, cookie;

    Provisioner(Env env) { this.env = env; }

    // ------------------------------------------------------------------------- state for the UI
    State state() { return state; }
    String message() { return message; }
    String phone() { return phone; }
    int version() { return version; }
    int fails() { return fails; }
    String routerIp() { return ip; }
    boolean needsLogin() { return !env.hasCredentials(); }
    boolean isBusy() { return state == State.WORKING; }

    void addListener(Listener l) { listeners.addIfAbsent(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void set(State s, String msg) {
        state = s; message = msg; version++;
        env.log("[login] " + s + (msg.isEmpty() ? "" : ": " + msg));
        env.runMain(new Runnable() { @Override public void run() {
            for (Listener l : listeners) l.onProvisionChanged();
        }});
    }

    // ------------------------------------------------------------------------- triggers
    /** Registration / password failure reported by the SIP engine. Rate limited, runs in the background. */
    boolean onProblem(final String reason) {
        long now = env.now();
        if (state == State.WORKING || state == State.NEED_OTP || now - lastAttempt < MIN_GAP_MS
                || fails >= MAX_AUTO_FAILS) return false;
        env.log("[login] problem detected (" + reason + ") -> fetching fresh credentials from the router");
        env.runBackground(new Runnable() { @Override public void run() { refresh(reason); } });
        return true;
    }

    /** No credentials at all yet (first start): try the silent fetch once in a while. */
    void ensureCredentials(final String reason) {
        if (env.hasCredentials() || state == State.WORKING || state == State.NEED_OTP) return;
        if (env.now() - lastAttempt < MIN_GAP_MS || fails >= MAX_AUTO_FAILS) return;
        env.runBackground(new Runnable() { @Override public void run() { refresh(reason); } });
    }

    /** The registration worked: the credentials are good. */
    void registeredOk() { fails = 0; }

    /** The "Fix login / refresh credentials" button. */
    void manualRefresh() {
        if (state == State.WORKING) return;
        fails = 0;
        lastAttempt = 0;
        env.runBackground(new Runnable() { @Override public void run() { refresh("button"); } });
    }

    void useRouterAddress(String address) {
        env.saveRouter(address.trim());
        manualRefresh();
    }

    // ------------------------------------------------------------------------- silent refresh
    /** Silent refresh (no OTP). Falls back to NEED_OTP if the router does not know this device. */
    void refresh(String reason) {
        synchronized (busy) { if (running) return; running = true; }
        try {
            lastAttempt = env.now();
            set(State.WORKING, "Looking for the router and fetching fresh Jio credentials...");
            JioProtocol.Connector c = env.connector();
            ip = findRouter(c);
            if (ip == null) {
                fails++;
                set(State.FAILED, "Router not found. Is this phone on the JioFiber/AirFiber Wi-Fi?");
                return;
            }
            JioProtocol.Fetch f = JioProtocol.fetchCredentials(c, ip, env.deviceName(), JioProtocol.mac(env.deviceName()));
            if (f.xml != null) { apply(f.xml); return; }
            if (f.status == 407 || f.status == 401 || f.status == 403) {
                set(State.NEED_OTP, "First-time login: this phone is not registered on the router yet.");
                return;
            }
            fails++;
            set(State.FAILED, "Router did not give credentials (HTTP " + f.status + ").");
        } catch (Exception e) {
            fails++;
            set(State.FAILED, "Refresh failed: " + describe(e));
        } finally {
            synchronized (busy) { running = false; }
        }
    }

    // ------------------------------------------------------------------------- OTP login
    void startOtp(final Result cb) {
        env.runBackground(new Runnable() { @Override public void run() { finish(cb, doStartOtp()); } });
    }

    void submitOtp(final String otp, final Result cb) {
        env.runBackground(new Runnable() { @Override public void run() { finish(cb, doSubmitOtp(otp)); } });
    }

    private void finish(final Result cb, final Object[] r) {
        if (cb == null) return;
        env.runMain(new Runnable() { @Override public void run() { cb.done((Boolean) r[0], (String) r[1]); } });
    }

    private Object[] doStartOtp() {
        try {
            JioProtocol.Connector c = env.connector();
            if (ip == null) ip = findRouter(c);
            if (ip == null) return new Object[]{false, "Router not found."};
            JioProtocol.OtpSession s = JioProtocol.sendOtp(c, ip, env.deviceName(), JioProtocol.mac(env.deviceName()));
            cookie = s.cookie;
            phone = s.phone;
            set(State.NEED_OTP, "OTP sent. Enter it below.");
            return new Object[]{true, s.phone.isEmpty() ? "OTP sent" : "OTP sent to " + s.phone};
        } catch (Exception e) {
            return new Object[]{false, describe(e)};
        }
    }

    private Object[] doSubmitOtp(String otp) {
        try {
            if (otp == null || !otp.matches("\\d{4,8}")) return new Object[]{false, "The OTP is 4 to 8 digits."};
            if (ip == null || cookie == null) return new Object[]{false, "Press \"Send OTP\" first."};
            JioProtocol.Connector c = env.connector();
            if (!JioProtocol.verifyOtp(c, ip, cookie, otp)) return new Object[]{false, "Wrong OTP. Try again."};
            set(State.WORKING, "OTP accepted, fetching credentials...");
            JioProtocol.Fetch f = JioProtocol.fetchCredentials(c, ip, env.deviceName(), JioProtocol.mac(env.deviceName()));
            if (f.xml == null) {
                set(State.NEED_OTP, "Registered, but the router still gave no credentials (HTTP " + f.status + ").");
                return new Object[]{false, "Router gave no credentials (HTTP " + f.status + "). Try again."};
            }
            apply(f.xml);
            return new Object[]{true, "Logged in"};
        } catch (Exception e) {
            set(State.NEED_OTP, "OTP step failed: " + describe(e));
            return new Object[]{false, describe(e)};
        }
    }

    // ------------------------------------------------------------------------- helpers
    private void apply(String xml) throws IOException {
        JioProtocol.Creds cr = JioProtocol.parseCreds(xml);
        boolean changed = env.saveCredentials(ip, cr.username, cr.password, cr.domain);
        if (changed) fails = 0;
        else fails++;       // credentials were already up to date: if registration still fails, don't loop forever
        set(State.OK, changed ? "New credentials saved." : "Credentials are already up to date.");
        env.credentialsUpdated(changed);
    }

    private String findRouter(JioProtocol.Connector c) {
        Set<String> cands = new LinkedHashSet<>(env.routerCandidates());
        String saved = env.savedRouter();
        if (saved != null && !saved.isEmpty()) cands.add(saved);
        for (String cand : new ArrayList<>(cands)) {
            if (JioProtocol.portOpen(c, cand, JioProtocol.PORT_HTTPS, 2000)) return cand;
        }
        return null;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
