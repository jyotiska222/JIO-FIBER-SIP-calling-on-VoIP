package com.example.myapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * SIP account + behaviour settings. NOTHING about your Jio account is built into the app: the
 * username / password / domain / router address are fetched from the router (see Provisioner)
 * and kept in this app's private storage. They are refreshed automatically if Jio changes them.
 */
public final class SipConfig {
    // protocol constants (not credentials)
    static final int    DEF_PORT   = JioProtocol.PORT_SIP;   // router SIP/TLS port
    static final String DEF_PREFIX = "0";                    // number_prefix
    static final String DEF_UA     = "MicroSIP/3.21.4";      // User-Agent the router is used to seeing

    private static SipConfig inst;
    private final SharedPreferences p;
    private final Context ctx;

    private SipConfig(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("sip_config", Context.MODE_PRIVATE);
    }

    public static synchronized SipConfig get(Context c) {
        if (inst == null) inst = new SipConfig(c);
        return inst;
    }

    // ---- account (filled in by the router login) ----
    String host()         { return p.getString("host", ""); }
    int port()            { return p.getInt("port", DEF_PORT); }
    String domain()       { return p.getString("domain", ""); }
    String id()           { return p.getString("id", ""); }
    String user()         { return p.getString("user", ""); }
    String pass()         { return p.getString("pass", ""); }

    // ---- behaviour ----
    String numberPrefix() { return p.getString("prefix", DEF_PREFIX); }
    String userAgent()    { return p.getString("ua", DEF_UA); }
    boolean userPhone()   { return p.getBoolean("user_phone", true); }
    boolean convertIN()   { return p.getBoolean("convert_in", true); }
    boolean keepCpu()     { return p.getBoolean("keep_cpu", true); }
    boolean startOnBoot() { return p.getBoolean("boot", true); }
    boolean verboseLog()  { return p.getBoolean("verbose", false); }
    boolean vibrate()     { return p.getBoolean("vibrate", true); }

    String registrar() { return "sip:" + host() + ":" + port(); }
    String proxy()     { return registrar() + ";transport=tls"; }

    // ---- device identity (what the router knows this phone as) ----
    /**
     * Stable per phone (survives reinstalling the app), like the hostname on the PC. The router's
     * "device id" (MAC) and the SIP +sip.instance are both derived from it with the same hash
     * the web app uses, so they always match each other.
     */
    synchronized String deviceName() {
        String n = p.getString("device_name", null);
        if (n == null || !n.matches("[A-Za-z0-9-]+")) {
            String aid = null;
            try { aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID); } catch (Exception ignored) { }
            if (aid == null || aid.length() < 8) {
                byte[] b = new byte[4];
                new SecureRandom().nextBytes(b);
                StringBuilder s = new StringBuilder();
                for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
                aid = s.toString();
            }
            n = "android-" + aid.substring(0, 8).toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "x");
            p.edit().putString("device_name", n).apply();
        }
        return n;
    }

    String instanceId() { return JioProtocol.instanceHex(deviceName()); }
    String deviceMac()  { return JioProtocol.mac(deviceName()); }

    // ---- credentials ----
    boolean hasCredentials() { return !user().isEmpty() && !pass().isEmpty() && !domain().isEmpty(); }

    /** Enough to start the SIP engine (the router address is re-discovered at start if it is missing). */
    boolean isComplete() { return hasCredentials(); }

    /** @return true if anything changed */
    synchronized boolean setCredentials(String routerIp, String user, String pass, String domain) {
        String newId = "sip:+" + user;
        boolean changed = !(user.equals(user()) && pass.equals(pass()) && domain.equals(domain())
                && routerIp.equals(host()) && newId.equals(id()) && port() == DEF_PORT);
        if (changed) p.edit().putString("host", routerIp).putInt("port", DEF_PORT).putString("domain", domain)
                .putString("id", newId).putString("user", user).putString("pass", pass).apply();
        return changed;
    }

    void setHost(String ip) { p.edit().putString("host", ip).apply(); }

    synchronized void clearCredentials() {
        p.edit().remove("host").remove("domain").remove("id").remove("user").remove("pass").apply();
    }

    SharedPreferences.Editor edit() { return p.edit(); }
    Context context() { return ctx; }

    static String modelHint() { return Build.MODEL == null ? "" : Build.MODEL; }
}
