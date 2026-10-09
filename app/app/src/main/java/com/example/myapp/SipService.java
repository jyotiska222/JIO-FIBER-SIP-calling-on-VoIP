package com.example.myapp;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Foreground service that keeps the SIP line registered while the app is closed or the screen is
 * off. Holds a partial CPU wake lock and a Wi-Fi lock (so Wi-Fi power-save can't drop the TLS
 * connection the router uses to reach the phone), follows Wi-Fi changes, and watches the line.
 */
public class SipService extends Service implements CallManager.Observer {
    private static boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private String lastAddr = "";
    private Network current;
    private boolean wifiUp;

    static boolean isRunning() { return running; }

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, SipService.class));
        } catch (Exception e) {
            // Android 12+ can refuse background starts; the app will retry when it is opened
        }
    }

    static void stop(Context c) { c.stopService(new Intent(c, SipService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        CallNotifier.createChannels(this);
        goForeground();
        CallManager call = CallManager.get(this);
        call.addObserver(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (SipConfig.get(this).keepCpu()) {
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jio:sip");
            cpu.setReferenceCounted(false);
            cpu.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jio:wifi");
        wifi.setReferenceCounted(false);
        wifi.acquire();

        watchNetwork();
        main.postDelayed(watchdog, 45000);
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(CallNotifier.ID_SERVICE, CallNotifier.service(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(CallNotifier.ID_SERVICE, CallNotifier.service(this));
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        goForeground();
        // The first Wi-Fi callback starts the engine; if Wi-Fi is already up make sure it is running.
        if (wifiUp) CallManager.get(this).onWifi(true, false);
        return START_STICKY;
    }

    private void watchNetwork() {
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { current = n; handleNet(n, true); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { if (n.equals(current)) handleNet(n, true); }
            @Override public void onLost(Network n) { if (n.equals(current)) { current = null; handleNet(n, false); } }
        };
        cm.registerNetworkCallback(req, netCb);
    }

    private void handleNet(final Network n, final boolean up) {
        final String addr = up ? ipOf(n) : "";
        main.post(new Runnable() { @Override public void run() {
            boolean changed = up && !addr.isEmpty() && !lastAddr.isEmpty() && !addr.equals(lastAddr);
            if (up && !addr.isEmpty()) lastAddr = addr;
            if (!up) lastAddr = "";
            wifiUp = up;
            // The router is a local address: keep every socket of this app on the Wi-Fi, even if Android
            // has marked that Wi-Fi "no internet" and prefers mobile data.
            try { cm.bindProcessToNetwork(up ? n : null); } catch (Exception ignored) { }
            if (up) ProvisionEnv.get(SipService.this).ensureCredentials("Wi-Fi connected");
            CallManager.get(SipService.this).onWifi(up, changed);
        }});
    }

    private String ipOf(Network n) {
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return "";
            for (LinkAddress a : lp.getLinkAddresses())
                if (a.getAddress() instanceof java.net.Inet4Address && !a.getAddress().isLoopbackAddress())
                    return a.getAddress().getHostAddress();
        } catch (Exception ignored) { }
        return "";
    }

    /** Safety net: reconnect if the line has been dead for a while although Wi-Fi is up. */
    private final Runnable watchdog = new Runnable() { @Override public void run() {
        CallManager c = CallManager.get(SipService.this);
        if (wifiUp && !c.inCall()) {
            if (!c.engineRunning()) c.startEngine();
            else if (!c.isRegistered() && c.regAgeMs() > 120000 && !c.authFailed()) c.restartEngine(false);
        }
        main.postDelayed(this, 45000);
    }};

    @Override public void onCallChanged() { refresh(); }
    @Override public void onRegChanged() { refresh(); }

    private void refresh() {
        try {
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(CallNotifier.ID_SERVICE, CallNotifier.service(this));
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        CallManager.get(this).removeObserver(this);
        if (cm != null && netCb != null) try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        CallManager.get(this).stopEngine(true);
        if (cpu != null && cpu.isHeld()) cpu.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
