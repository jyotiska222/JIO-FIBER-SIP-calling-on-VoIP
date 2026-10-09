package com.example.myapp;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;

/**
 * Single source of truth for the SIP line and the current call. Everything the UI sees is changed
 * on the main thread; every native call runs on one background thread (pjsua start/stop can block).
 */
final class CallManager implements SipEngine.Listener {
    enum State { IDLE, DIALING, RINGING_OUT, RINGING_IN, ACTIVE, HOLD, ENDED }

    interface Observer { void onCallChanged(); void onRegChanged(); }

    private static CallManager inst;
    static synchronized CallManager get(Context c) {
        if (inst == null) inst = new CallManager(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final Ringer ringer;
    private final AudioRoute audio;
    private final SipConfig cfg;
    private PowerManager.WakeLock proxLock;
    private final Provisioner prov;

    // ---- self-healing login (see Provisioner) ----
    private static final Pattern AUTH_FAIL = Pattern.compile("whitelist|forbidden|unauthori[sz]ed|\\b40[137]\\b", Pattern.CASE_INSENSITIVE);
    private boolean authFailed, restartPending;
    private String retryNumber;                 // outgoing call to redo once after new credentials arrived
    private long retryAt;

    // ---- line (registration) ----
    private volatile boolean engineRunning;
    private boolean registered;
    private String regText = "Starting…";
    private long regChangedAt = SystemClock.elapsedRealtime();

    // ---- call ----
    private State state = State.IDLE;
    private int callId = -1;
    private final List<Integer> legs = new ArrayList<>();   // Jio can fork one incoming call into several legs
    private boolean incoming, answered, declinedByUs, muted, speaker;
    private String number = "", endText = "";
    private long startWall, connectedAt;          // wall clock of ring/dial, elapsedRealtime when connected

    private CallManager(Context c) {
        ctx = c;
        cfg = SipConfig.get(c);
        ringer = new Ringer(c);
        audio = new AudioRoute(c);
        prov = ProvisionEnv.get(c);
        SipEngine.listener = this;
    }

    // ===================================================================== observers / getters
    void addObserver(Observer o) { observers.addIfAbsent(o); }
    void removeObserver(Observer o) { observers.remove(o); }
    private void fireCall() { for (Observer o : observers) o.onCallChanged(); }
    private void fireReg()  { for (Observer o : observers) o.onRegChanged(); }

    State state() { return state; }
    boolean inCall() { return state != State.IDLE && state != State.ENDED; }
    boolean isIncoming() { return incoming; }
    String number() { return number; }
    String endText() { return endText; }
    boolean isMuted() { return muted; }
    boolean isSpeaker() { return speaker; }
    boolean isRegistered() { return registered; }
    String regText() { return regText; }
    boolean engineRunning() { return engineRunning; }
    boolean authFailed() { return authFailed; }
    Provisioner provisioner() { return prov; }
    void appLog(String s) { addLog(s); }
    long regAgeMs() { return SystemClock.elapsedRealtime() - regChangedAt; }
    Ringer ringer() { return ringer; }
    AudioRoute audio() { return audio; }
    int talkSeconds() { return connectedAt == 0 ? 0 : (int) ((SystemClock.elapsedRealtime() - connectedAt) / 1000); }

    String displayName() {
        String n = Store.get(ctx).nameFor(number);
        return n != null ? n : PhoneUtil.pretty(number);
    }

    synchronized String logText() {
        StringBuilder b = new StringBuilder();
        for (String s : log) b.append(s).append('\n');
        return b.toString();
    }

    private synchronized void addLog(String s) {
        log.addLast(s);
        while (log.size() > 700) log.removeFirst();
    }

    // ===================================================================== engine lifecycle
    void startEngine() {
        exec.execute(new Runnable() { @Override public void run() {
            if (engineRunning) return;
            if (!SipEngine.isAvailable()) {
                setReg(false, "SIP library not found: " + SipEngine.loadError()); return;
            }
            if (!cfg.isComplete()) {                                  // first run: fetch the credentials from the router
                setReg(false, "Jio login needed");
                prov.ensureCredentials("engine start");
                return;
            }
            // the router's address can change (new Wi-Fi / new router): find it again before connecting
            String found = ProvisionEnv.discoverRouter(ctx);
            if (found != null && !found.equals(cfg.host())) {
                addLog("[app] router address is now " + found + " (was " + (cfg.host().isEmpty() ? "unknown" : cfg.host()) + ")");
                cfg.setHost(found);
            }
            if (cfg.host().isEmpty()) {
                setReg(false, "Router not found. Is the phone on the JioFiber Wi-Fi?");
                return;
            }
            String[] tls = TlsFiles.ensure(ctx);
            if (tls == null) addLog("[app] could not create the TLS certificate: incoming calls will not work");
            setReg(false, "Connecting…");
            addLog("[app] starting SIP engine (pjsip " + SipEngine.nativeVersion() + ") -> " + cfg.registrar()
                    + "  device " + cfg.deviceName() + " / " + cfg.instanceId());
            int rc = SipEngine.nativeStart(cfg.userAgent(), cfg.registrar(), cfg.proxy(), cfg.id(), "*",
                    cfg.user(), cfg.pass(), tls == null ? "" : tls[0], tls == null ? "" : tls[1],
                    cfg.instanceId(), cfg.verboseLog() ? 5 : 3, 4000, true);
            if (rc == 0) engineRunning = true;
            else setReg(false, "Could not start the SIP engine (error " + rc + ")");
        }});
    }

    void stopEngine(final boolean graceful) {
        exec.execute(new Runnable() { @Override public void run() {
            if (!engineRunning) return;
            engineRunning = false;
            SipEngine.nativeStop(graceful);
            setReg(false, "Stopped");
        }});
    }

    /** Re-read settings and reconnect (also used after the Wi-Fi address changed). */
    void restartEngine(boolean graceful) { stopEngine(graceful); startEngine(); }

    void reRegister() {
        exec.execute(new Runnable() { @Override public void run() { if (engineRunning) SipEngine.nativeReRegister(); } });
    }

    private void setReg(final boolean ok, final String text) {
        main.post(new Runnable() { @Override public void run() {
            if (registered != ok || !text.equals(regText)) regChangedAt = SystemClock.elapsedRealtime();
            registered = ok; regText = text;
            fireReg();
        }});
    }

    void onWifi(boolean up, boolean addressChanged) {
        if (!up) {
            if (engineRunning) stopEngine(false);
            setReg(false, "Waiting for Wi-Fi (connect to your JioFiber network)");
        } else if (!engineRunning) {
            startEngine();
        } else if (addressChanged && !inCall()) {
            restartEngine(false);
        } else {
            reRegister();
        }
    }

    // ===================================================================== user actions
    /** @return null if the call is being placed, otherwise a message for the user */
    String placeCall(String raw) {
        String num = PhoneUtil.clean(raw);
        if (num.length() < 2) return "Enter a number first";
        if (!SipEngine.isAvailable()) return "SIP library missing: " + SipEngine.loadError();
        if (!cfg.isComplete()) return "Fill in the SIP settings first";
        if (inCall()) return "A call is already in progress";
        if (!engineRunning || !registered) return "Not connected to the router yet: " + regText;

        cancelEnded();
        number = num; incoming = false; answered = false; declinedByUs = false;
        muted = false; speaker = false; connectedAt = 0; callId = -1; legs.clear();
        startWall = System.currentTimeMillis();
        endText = "";
        state = State.DIALING;
        audio.begin(false);
        updateProximity();
        fireCall();

        final String uri = PhoneUtil.sipUri(num, cfg);
        addLog("[app] dialing " + uri);
        exec.execute(new Runnable() { @Override public void run() {
            final int id = SipEngine.nativeCall(uri);
            main.post(new Runnable() { @Override public void run() {
                if (id < 0) { if (state == State.DIALING) finishCall("Could not place the call"); }
                else if (callId == -1 && state != State.IDLE) callId = id;
            }});
        }});
        return null;
    }

    void answer() {
        if (state != State.RINGING_IN || callId < 0) return;
        ringer.stop();
        CallNotifier.cancelIncoming(ctx);
        answered = true;
        audio.begin(false);
        final int id = callId;
        final List<Integer> others = new ArrayList<>(legs);
        others.remove(Integer.valueOf(id));
        exec.execute(new Runnable() { @Override public void run() {
            SipEngine.nativeAnswer(id, 200);
            for (int o : others) SipEngine.nativeHangup(o, 486);
        }});
        legs.clear(); legs.add(id);
        updateProximity();
        fireCall();
    }

    /** Hang up, or decline when the call is still ringing. */
    void hangup() {
        if (!inCall()) return;
        if (state == State.RINGING_IN) {
            declinedByUs = true;
            ringer.stop();
            CallNotifier.cancelIncoming(ctx);
        }
        final List<Integer> ids = new ArrayList<>(legs);
        if (callId >= 0 && !ids.contains(callId)) ids.add(callId);
        final boolean ring = state == State.RINGING_IN;
        if (ids.isEmpty()) { finishCall("Call ended"); return; }
        exec.execute(new Runnable() { @Override public void run() {
            for (int id : ids) SipEngine.nativeHangup(id, ring ? 603 : 0);
        }});
        // pjsua confirms with a DISCONNECTED callback; if it never arrives, don't leave the UI stuck
        main.postDelayed(new Runnable() { @Override public void run() {
            if (inCall()) finishCall("Call ended");
        }}, 4000);
    }

    void toggleMute() {
        if (state != State.ACTIVE && state != State.HOLD) return;
        muted = !muted;
        final int id = callId; final boolean m = muted;
        exec.execute(new Runnable() { @Override public void run() { SipEngine.nativeMute(id, m); } });
        fireCall();
    }

    void toggleHold() {
        if (state != State.ACTIVE && state != State.HOLD) return;
        final boolean hold = state == State.ACTIVE;
        final int id = callId;
        state = hold ? State.HOLD : State.ACTIVE;
        fireCall();
        exec.execute(new Runnable() { @Override public void run() {
            final int rc = SipEngine.nativeHold(id, hold);
            if (rc != 0) main.post(new Runnable() { @Override public void run() {
                if (state == State.HOLD || state == State.ACTIVE) {
                    state = hold ? State.ACTIVE : State.HOLD;      // revert
                    addLog("[app] hold/resume failed (" + rc + ")");
                    fireCall();
                }
            }});
        }});
    }

    void setSpeaker(boolean on) {
        speaker = on;
        audio.route(on);
        updateProximity();
        fireCall();
    }

    void sendDtmf(final String digits) {
        if (state != State.ACTIVE) return;
        final int id = callId;
        exec.execute(new Runnable() { @Override public void run() { SipEngine.nativeDtmf(id, digits); } });
    }

    // ===================================================================== proximity sensor
    /** Screen off near the ear, back on when the phone is moved away: the system's own proximity lock. */
    void updateProximity() {
        boolean want = (state == State.DIALING || state == State.RINGING_OUT || state == State.ACTIVE
                || state == State.HOLD) && !speaker && !audio.headsetConnected();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        try {
            if (want) {
                if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
                if (proxLock == null) proxLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "jio:prox");
                if (!proxLock.isHeld()) proxLock.acquire(4 * 60 * 60 * 1000L);
            } else if (proxLock != null && proxLock.isHeld()) {
                proxLock.release();
            }
        } catch (Exception ignored) { }
    }

    static boolean proximityLockSupported(Context c) {
        return ((PowerManager) c.getSystemService(Context.POWER_SERVICE))
                .isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK);
    }

    // ===================================================================== SipEngine.Listener (native threads)
    @Override public void onReg(final int code, final String text, final boolean ok) {
        addLog("[reg] " + code + " " + text);
        final boolean auth = !ok && (code == 401 || code == 403 || code == 407 || AUTH_FAIL.matcher(text).find());
        setReg(ok, ok ? "Connected: ready for calls"
                : (code / 100 == 2 ? "Unregistered" : (code == 0 ? text : "Registration failed: " + code + " " + text)));
        main.post(new Runnable() { @Override public void run() {
            if (ok) {
                authFailed = false;
                prov.registeredOk();
                maybeRetryCall();
            } else if (auth) {
                authFailed = true;                                    // password / device rejected -> fetch new credentials
                prov.onProblem("registration rejected: " + code + " " + text);
            }
        }});
    }

    /** New credentials were saved by the Provisioner: restart the line so they are used straight away. */
    void onCredentialsUpdated(boolean changed) {
        authFailed = false;
        if (inCall()) { restartPending = true; return; }               // never cut a call: restart afterwards
        if (changed || !engineRunning) restartEngine(false);
        else reRegister();
    }

    private void maybeRetryCall() {
        final String n = retryNumber;
        if (n == null) return;
        retryNumber = null;
        if (SystemClock.elapsedRealtime() - retryAt > 90000 || inCall()) return;
        main.postDelayed(new Runnable() { @Override public void run() {
            if (placeCall(n) == null) showCallScreen();               // same as the web app: redo the call once
        }}, 800);
    }

    @Override public void onLog(int level, String line) {
        String t = line.trim();
        if (!t.isEmpty()) addLog(t);
    }

    @Override public void onIncoming(final int id, final String remote) {
        main.post(new Runnable() { @Override public void run() { handleIncoming(id, remote); } });
    }

    @Override public void onCallState(final int id, final int st, final int status, final String text) {
        main.post(new Runnable() { @Override public void run() { handleCallState(id, st, status, text); } });
    }

    @Override public void onMedia(final int id, final boolean active) {
        main.post(new Runnable() { @Override public void run() {
            if (active && inCall()) { audio.route(speaker); updateProximity(); }
        }});
    }

    // ===================================================================== main-thread handlers
    private void handleIncoming(final int id, String remote) {
        String num = PhoneUtil.extractNumber(remote);
        if (state == State.RINGING_IN && incoming && !answered && PhoneUtil.same(num, number)) {
            if (!legs.contains(id)) legs.add(id);                  // another leg of the same call
            return;
        }
        if (inCall()) {            // busy: reject, but remember it
            exec.execute(new Runnable() { @Override public void run() { SipEngine.nativeHangup(id, 486); } });
            logCall(num, Store.MISSED, System.currentTimeMillis(), 0);
            CallNotifier.showMissed(ctx, num);
            return;
        }
        cancelEnded();
        number = num; incoming = true; answered = false; declinedByUs = false;
        muted = false; speaker = false; connectedAt = 0; callId = id;
        legs.clear(); legs.add(id);
        startWall = System.currentTimeMillis();
        endText = "";
        state = State.RINGING_IN;
        addLog("[app] incoming call from " + num);
        ringer.start(num);
        CallNotifier.showIncoming(ctx, displayName(), PhoneUtil.pretty(num));
        fireCall();
    }

    private void handleCallState(int id, int st, int status, String text) {
        if (state == State.IDLE || state == State.ENDED) return;
        if (callId == -1 && !incoming && state == State.DIALING) callId = id;
        if (id != callId && !legs.contains(id)) return;

        switch (st) {
            case SipEngine.INV_CALLING:
                break;
            case SipEngine.INV_EARLY:
                if (!incoming && state == State.DIALING) { state = State.RINGING_OUT; fireCall(); }
                break;
            case SipEngine.INV_CONNECTING:
            case SipEngine.INV_CONFIRMED:
                if (state == State.DIALING || state == State.RINGING_OUT || state == State.RINGING_IN) {
                    // for incoming calls our 200 OK is out: treat as connected even if the caller's
                    // ACK is slow (the router sometimes needs a moment to deliver it)
                    if (state == State.RINGING_IN && !answered) break;
                    callId = id;
                    state = State.ACTIVE;
                    connectedAt = SystemClock.elapsedRealtime();
                    answered = true;
                    ringer.stop();
                    CallNotifier.cancelIncoming(ctx);
                    audio.begin(speaker);
                    updateProximity();
                    fireCall();
                }
                break;
            case SipEngine.INV_DISCONNECTED:
                legs.remove(Integer.valueOf(id));
                if (state == State.RINGING_IN && !answered && !legs.isEmpty()) {
                    if (id == callId) callId = legs.get(0);        // another fork leg is still ringing
                    break;
                }
                if (!incoming && !answered && connectedAt == 0 && (status == 401 || status == 403 || status == 407)) {
                    retryNumber = number;                          // credentials may be stale: refresh, then redo once
                    retryAt = SystemClock.elapsedRealtime();
                    prov.onProblem("call rejected: " + status + " " + text);
                }
                if (id == callId || legs.isEmpty()) finishCall(endTextFor(status, text));
                break;
            default:
                break;
        }
    }

    private String endTextFor(int status, String text) {
        if (answered || connectedAt != 0) return "Call ended";
        if (incoming) return declinedByUs ? "Call declined" : "Missed call";
        switch (status) {
            case 486: case 600: return "Busy";
            case 487: return "Call cancelled";
            case 603: return "Declined";
            case 404: case 604: return "Number not found";
            case 403: return "Call not allowed (403)";
            case 408: case 480: return "No answer";
            case 488: case 606: return "Call not accepted by the network (" + status + ")";
            case 401: case 407: return "Authentication failed";
            default: return status >= 300 ? "Call failed: " + status + " " + text : "Call ended";
        }
    }

    private void finishCall(String reason) {
        if (state == State.IDLE || state == State.ENDED) return;
        boolean wasIncoming = incoming;
        int dur = talkSeconds();
        boolean connected = connectedAt != 0;
        ringer.stop();
        CallNotifier.cancelIncoming(ctx);
        audio.end();
        State prev = state;
        state = State.ENDED;
        endText = reason;
        updateProximity();

        int dir;
        if (!wasIncoming) dir = Store.OUT;
        else if (connected || answered) dir = Store.IN;
        else dir = declinedByUs ? Store.REJECTED : Store.MISSED;
        logCall(number, dir, startWall, dur);
        if (dir == Store.MISSED) CallNotifier.showMissed(ctx, number);

        callId = -1; legs.clear();
        fireCall();
        main.postDelayed(endedToIdle, 1800);
        if (restartPending) { restartPending = false; restartEngine(false); }
    }

    private final Runnable endedToIdle = new Runnable() { @Override public void run() {
        if (state == State.ENDED) { state = State.IDLE; fireCall(); }
    }};

    private void cancelEnded() { main.removeCallbacks(endedToIdle); }

    private void logCall(String num, int dir, long start, int dur) {
        Store.Entry e = new Store.Entry();
        e.number = num; e.dir = dir; e.start = start; e.dur = dur;
        e.name = Store.get(ctx).nameFor(num);
        Store.get(ctx).addCall(e);
    }

    /** Opens the call screen (used when the app is already in the foreground). */
    void showCallScreen() {
        Intent i = new Intent(ctx, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
