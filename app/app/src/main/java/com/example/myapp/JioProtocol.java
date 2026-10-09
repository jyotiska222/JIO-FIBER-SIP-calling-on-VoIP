package com.example.myapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The router's provisioning protocol, ported 1:1 from jio_provision.py (the JFC SIP Configuration
 * Tool inside your web app). Plain Java, no Android classes, so it can be tested on a PC.
 *
 *   GET https://<router>:8443/?terminal_sw_version=RCSAndrd&...&mac_address=..&nwk_intf=eth
 *       200 + XML  -> credentials (device already registered on the router: no OTP)
 *       407        -> this device is not registered yet -> OTP login
 *   ...&op_type=add   (raw query)  -> router/Jio sends an OTP SMS; reply has a cookie + X-AMN (masked number)
 *   GET /?OTP=123456  + cookie     -> 200 when the OTP is right
 *
 * HTTP is done by hand over a TLS socket instead of HttpURLConnection: Android's HttpURLConnection
 * throws on a 407 reply from a server that is not a proxy, and 407 is exactly how the router says
 * "device not registered".
 */
final class JioProtocol {
    private JioProtocol() {}

    static final int PORT_HTTPS = 8443;      // router provisioning port
    static final int PORT_SIP = 5068;        // router SIP proxy port
    static final String FALLBACK_HOST = "jiofiber.local.html";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Opens TCP connections that are bound to the right network (the Wi-Fi) on Android. */
    interface Connector {
        Socket connect(String host, int port, int timeoutMs) throws IOException;
    }

    // ------------------------------------------------------------------------- device identity
    /** pjlib's pj_hash_calc(0, s): h = h*33 + byte. Same value the SIP patch and jio_provision.py use. */
    static long hash(String s) {
        long h = 0;
        for (byte b : s.getBytes(UTF8)) h = (h * 33 + (b & 0xFF)) & 0xFFFFFFFFL;
        return h;
    }

    /** 8 hex digits, bytes reversed: a name like "android-1a2b3c4d" gives its own 8 hex digits (the id in +sip.instance). */
    static String instanceHex(String deviceName) {
        String hx = String.format(Locale.US, "%08X", hash(deviceName));
        StringBuilder r = new StringBuilder();
        for (int i = 6; i >= 0; i -= 2) r.append(hx, i, i + 2);
        return r.toString();
    }

    /** e.g. "00:00:e2:5c:e1:1f": the MAC the router stores for this device (same bytes as the instance id). */
    static String mac(String deviceName) {
        String hx = ("0000" + instanceHex(deviceName)).toLowerCase(Locale.US);
        StringBuilder m = new StringBuilder();
        for (int i = 0; i < 12; i += 2) { if (i > 0) m.append(':'); m.append(hx, i, i + 2); }
        return m.toString();
    }

    // ------------------------------------------------------------------------- requests
    static List<String[]> params(String host, String mac, boolean eth, boolean add) {
        List<String[]> p = new ArrayList<>();
        String[][] fixed = {
                {"terminal_sw_version", "RCSAndrd"}, {"terminal_vendor", host}, {"terminal_model", host},
                {"SMS_port", "0"}, {"act_type", "volatile"}, {"IMSI", ""}, {"msisdn", ""}, {"IMEI", ""},
                {"vers", "0"}, {"token", ""}, {"rcs_state", "0"}, {"rcs_version", "5.1B"},
                {"rcs_profile", "joyn_blackbird"}, {"client_vendor", "JUIC"}, {"default_sms_app", "2"},
                {"default_vvm_app", "0"}, {"device_type", "vvm"}, {"client_version", "JSEAndrd-1.0"},
                {"mac_address", mac}, {"alias", host}, {"nwk_intf", eth ? "eth" : "wifi"}};
        for (String[] kv : fixed) p.add(kv);
        if (add) p.add(new String[]{"op_type", "add"});
        return p;
    }

    static String encode(List<String[]> p) {
        StringBuilder b = new StringBuilder();
        for (String[] kv : p) {
            if (b.length() > 0) b.append('&');
            b.append(enc(kv[0])).append('=').append(enc(kv[1]));
        }
        return b.toString();
    }

    static String raw(List<String[]> p) {                  // the JFC "add device" request is not URL-encoded
        StringBuilder b = new StringBuilder();
        for (String[] kv : p) {
            if (b.length() > 0) b.append('&');
            b.append(kv[0]).append('=').append(kv[1]);
        }
        return b.toString();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static final class Resp {
        int status;
        final Map<String, String> headers = new HashMap<>();   // lower-case names, last value wins
        String body = "";
    }

    private static SSLSocketFactory trustAll;

    /** The router uses a self-signed certificate. Trust is relaxed ONLY for this provisioning socket. */
    private static synchronized SSLSocketFactory trustAllFactory() throws IOException {
        if (trustAll == null) {
            try {
                TrustManager[] tm = {new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tm, new SecureRandom());
                trustAll = ctx.getSocketFactory();
            } catch (Exception e) { throw new IOException("TLS setup failed: " + e); }
        }
        return trustAll;
    }

    static boolean portOpen(Connector c, String host, int port, int timeoutMs) {
        try (Socket s = c.connect(host, port, timeoutMs)) { return true; }
        catch (Exception e) { return false; }
    }

    static Resp get(Connector c, String ip, int port, String query, String cookie) throws IOException {
        Socket plain = c.connect(ip, port, 8000);
        try {
            plain.setSoTimeout(15000);
            SSLSocket ssl = (SSLSocket) trustAllFactory().createSocket(plain, ip, port, true);
            ssl.setSoTimeout(15000);
            ssl.startHandshake();
            StringBuilder req = new StringBuilder("GET /?").append(query).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(ip).append(':').append(port).append("\r\n")
                    .append("Accept-Encoding: identity\r\n")
                    .append("Connection: close\r\n");
            if (cookie != null && !cookie.isEmpty()) req.append("Cookie: ").append(cookie).append("\r\n");
            req.append("\r\n");
            OutputStream out = ssl.getOutputStream();
            out.write(req.toString().getBytes(UTF8));
            out.flush();
            return readResponse(ssl.getInputStream());
        } finally {
            try { plain.close(); } catch (IOException ignored) { }
        }
    }

    static Resp readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0 && all.size() < 2_000_000) all.write(buf, 0, n);
        } catch (IOException e) {
            if (all.size() == 0) throw e;                   // routers often just close the TLS stream
        }
        byte[] data = all.toByteArray();
        int split = indexOf(data, "\r\n\r\n".getBytes(UTF8));
        if (split < 0) throw new IOException("router sent an incomplete HTTP reply");
        String head = new String(data, 0, split, UTF8);
        String[] lines = head.split("\r\n");
        Matcher m = Pattern.compile("^HTTP/\\d\\.\\d\\s+(\\d{3})").matcher(lines[0]);
        if (!m.find()) throw new IOException("router sent a malformed HTTP reply");
        Resp r = new Resp();
        r.status = Integer.parseInt(m.group(1));
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
        }
        byte[] body = java.util.Arrays.copyOfRange(data, split + 4, data.length);
        if ("chunked".equalsIgnoreCase(r.headers.get("transfer-encoding"))) body = dechunk(body);
        r.body = new String(body, UTF8);
        return r;
    }

    private static byte[] dechunk(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int i = 0;
        while (i < b.length) {
            int e = indexOf(b, i, "\r\n".getBytes(UTF8));
            if (e < 0) break;
            String sz = new String(b, i, e - i, UTF8).trim();
            int semi = sz.indexOf(';');
            if (semi >= 0) sz = sz.substring(0, semi);
            int len;
            try { len = Integer.parseInt(sz, 16); } catch (NumberFormatException ex) { break; }
            if (len == 0) break;
            i = e + 2;
            int end = Math.min(i + len, b.length);
            o.write(b, i, end - i);
            i = end + 2;
        }
        return o.toByteArray();
    }

    private static int indexOf(byte[] h, byte[] n) { return indexOf(h, 0, n); }

    private static int indexOf(byte[] h, int from, byte[] n) {
        outer:
        for (int i = from; i <= h.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------------- the three router calls
    static final class Fetch {
        String xml;        // null when the router did not hand out credentials
        int status;
    }

    /** Asks for the provisioning XML. Registered devices answer on "eth" (JFC --no-otp) or "wifi". */
    static Fetch fetchCredentials(Connector c, String ip, String host, String mac) throws IOException {
        Fetch f = new Fetch();
        for (boolean eth : new boolean[]{true, false}) {
            Resp r = get(c, ip, PORT_HTTPS, encode(params(host, mac, eth, false)), null);
            f.status = r.status;
            if (r.status == 200 && r.body.contains("<parm")) { f.xml = r.body; return f; }
        }
        return f;                                       // 407 = this device is not registered yet
    }

    static final class OtpSession {
        String cookie = "";
        String phone = "";
    }

    /** Registers this device: the router/Jio then sends an OTP SMS to the line's owner. */
    static OtpSession sendOtp(Connector c, String ip, String host, String mac) throws IOException {
        Resp r = get(c, ip, PORT_HTTPS, raw(params(host, mac, false, true)), null);
        if (r.status != 200) throw new IOException("router refused to send an OTP (HTTP " + r.status + ")");
        OtpSession s = new OtpSession();
        StringBuilder ck = new StringBuilder();
        String sc = r.headers.get("set-cookie");
        if (sc != null) for (String part : sc.split("; ")) {
            if (part.contains("=")) { if (ck.length() > 0) ck.append("; "); ck.append(part); }
        }
        s.cookie = ck.toString();
        String amn = r.headers.get("x-amn");
        s.phone = amn == null ? "" : amn;
        return s;
    }

    static boolean verifyOtp(Connector c, String ip, String cookie, String otp) throws IOException {
        return get(c, ip, PORT_HTTPS, "OTP=" + enc(otp), cookie).status == 200;
    }

    // ------------------------------------------------------------------------- reply parsing
    static final class Creds {
        String username, password, domain;
    }

    private static final Pattern PARM = Pattern.compile("<parm\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    /** Pulls every <parm name=".." value=".."/> out of the provisioning document. */
    static Map<String, String> parseParms(String xml) {
        Map<String, String> vals = new HashMap<>();
        Matcher m = PARM.matcher(xml);
        while (m.find()) {
            String name = null, value = "";
            Matcher a = ATTR.matcher(m.group(1));
            while (a.find()) {
                String v = a.group(3) != null ? a.group(3) : a.group(4);
                if (a.group(1).equals("name")) name = unescape(v);
                else if (a.group(1).equals("value")) value = unescape(v);
            }
            if (name != null) vals.put(name, value);
        }
        return vals;
    }

    static Creds parseCreds(String xml) throws IOException {
        Map<String, String> v = parseParms(xml);
        Creds c = new Creds();
        c.username = v.get("username");
        c.password = v.get("userpwd");
        c.domain = v.get("home_network_domain_name");
        if (isEmpty(c.username) || isEmpty(c.password) || isEmpty(c.domain))
            throw new IOException("router answered but the credentials were missing from its reply");
        return c;
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                default:
                    try {
                        int cp = e.charAt(1) == 'x' ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        r = new String(Character.toChars(cp));
                    } catch (Exception ex) { r = m.group(0); }
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }
}
