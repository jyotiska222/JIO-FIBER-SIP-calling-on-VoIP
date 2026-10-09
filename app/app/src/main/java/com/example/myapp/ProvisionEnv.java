package com.example.myapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Android side of the Provisioner: Wi-Fi-bound sockets, router discovery, storage, notifications. */
final class ProvisionEnv implements Provisioner.Env {
    private static Provisioner inst;
    private static ProvisionEnv env;

    static synchronized Provisioner get(Context c) {
        if (inst == null) {
            env = new ProvisionEnv(c.getApplicationContext());
            inst = new Provisioner(env);
            final Context app = c.getApplicationContext();
            final Provisioner p = inst;
            inst.addListener(new Provisioner.Listener() {
                @Override public void onProvisionChanged() {
                    // the OTP page needs the user: if the app isn't on screen, say so in a notification
                    if (p.state() == Provisioner.State.NEED_OTP && !JioApp.inForeground()) CallNotifier.showLoginNeeded(app);
                    else if (p.state() != Provisioner.State.NEED_OTP) CallNotifier.cancelLoginNeeded(app);
                }
            });
        }
        return inst;
    }

    private final Context ctx;
    private final SipConfig cfg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private final ConnectivityManager cm;

    private ProvisionEnv(Context c) {
        ctx = c;
        cfg = SipConfig.get(c);
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private Network wifi() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override public JioProtocol.Connector connector() throws IOException {
        final Network n = wifi();
        if (n == null) throw new IOException("This phone is not connected to Wi-Fi. Join your JioFiber network.");
        return new JioProtocol.Connector() {
            @Override public Socket connect(String host, int port, int timeoutMs) throws IOException {
                Socket s = n.getSocketFactory().createSocket();      // bound to the Wi-Fi, never mobile data
                try {
                    s.connect(new InetSocketAddress(host, port), timeoutMs);
                } catch (IOException e) {
                    try { s.close(); } catch (IOException ignored) { }
                    throw e;
                }
                return s;
            }
        };
    }

    @Override public List<String> routerCandidates() {
        List<String> out = new ArrayList<>();
        final Network n = wifi();
        if (n == null) return out;
        try {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo r : lp.getRoutes()) {                  // default gateway first
                    InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) add(out, g.getHostAddress());
                }
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (g instanceof Inet4Address && !g.isAnyLocalAddress()) add(out, g.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null && d.gateway != 0) {
                int g = d.gateway;
                add(out, (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff));
            }
        } catch (Exception ignored) { }
        try {                                                         // jiofiber.local.html, with a 2 s limit
            Future<InetAddress[]> f = bg.submit(new Callable<InetAddress[]>() {
                @Override public InetAddress[] call() throws Exception { return n.getAllByName(JioProtocol.FALLBACK_HOST); }
            });
            for (InetAddress a : f.get(2, TimeUnit.SECONDS)) if (a instanceof Inet4Address) add(out, a.getHostAddress());
        } catch (Exception ignored) { }
        return out;
    }

    private static void add(List<String> l, String s) { if (s != null && !l.contains(s)) l.add(s); }

    /** Re-discover the router address (called before the SIP engine starts). null = not found. */
    String discoverRouter() {
        try {
            JioProtocol.Connector c = connector();
            List<String> cands = routerCandidates();
            if (!cfg.host().isEmpty() && !cands.contains(cfg.host())) cands.add(cfg.host());
            for (String ip : cands) if (JioProtocol.portOpen(c, ip, JioProtocol.PORT_HTTPS, 1500)) return ip;
        } catch (Exception ignored) { }
        return null;
    }

    static String discoverRouter(Context c) {
        get(c);
        return env.discoverRouter();
    }

    @Override public String savedRouter() { return cfg.host(); }
    @Override public void saveRouter(String ip) { cfg.setHost(ip); }
    @Override public String deviceName() { return cfg.deviceName(); }
    @Override public boolean hasCredentials() { return cfg.hasCredentials(); }

    @Override public boolean saveCredentials(String routerIp, String username, String password, String domain) {
        return cfg.setCredentials(routerIp, username, password, domain);
    }

    @Override public void credentialsUpdated(final boolean changed) {
        main.post(new Runnable() { @Override public void run() { CallManager.get(ctx).onCredentialsUpdated(changed); } });
    }

    @Override public void runBackground(Runnable r) { bg.execute(r); }

    @Override public void runMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else main.post(r);
    }

    @Override public long now() { return System.currentTimeMillis(); }
    @Override public void log(String line) { CallManager.get(ctx).appLog(line); }
}
