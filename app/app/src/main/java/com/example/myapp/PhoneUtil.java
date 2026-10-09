package com.example.myapp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Number clean-up and the "how do I dial this on the Jio router" rules from jio-sip-client. */
final class PhoneUtil {
    private PhoneUtil() {}

    private static final Pattern URI_USER = Pattern.compile("(?:sip|sips|tel):([^@>;\\s]+)", Pattern.CASE_INSENSITIVE);

    /** Keeps digits, a leading '+', '*' and '#'. */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '*' || c == '#') b.append(c);
            else if (c == '+' && b.length() == 0) b.append(c);
        }
        return b.toString();
    }

    static String digits(String s) {
        StringBuilder b = new StringBuilder();
        if (s != null) for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    /** Key used to match a number against contacts / call log (country-code independent). */
    static String key(String number) {
        String d = digits(number);
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    static boolean same(String a, String b) {
        String ka = key(a), kb = key(b);
        return !ka.isEmpty() && ka.equals(kb);
    }

    /**
     * Same as build_uri() in jio-sip-client: a 10-digit mobile number gets the router's number
     * prefix ("0"), everything else is dialled as given. With {@code convertIN} on, +91 / 91 / 0091
     * numbers are first reduced to the 10-digit form so contacts saved as "+91 98765 43210" work.
     */
    static String sipUri(String number, SipConfig cfg) {
        String dialed = clean(number);
        if (dialed.startsWith("+")) dialed = dialed.substring(1);
        if (cfg.convertIN()) {
            if (dialed.startsWith("0091") && dialed.length() == 14) dialed = dialed.substring(4);
            else if (dialed.startsWith("91") && dialed.length() == 12) dialed = dialed.substring(2);
        }
        if (dialed.length() == 10) dialed = cfg.numberPrefix() + dialed;
        String uri = "sip:" + dialed + "@" + cfg.domain();
        if (cfg.userPhone()) uri += ";user=phone";
        return uri;
    }

    /** "<sip:+919876543210@ims.example.net>" or "\"Name\" <sip:...>" -> "+919876543210" */
    static String extractNumber(String sipRemote) {
        if (sipRemote == null) return "";
        Matcher m = URI_USER.matcher(sipRemote);
        if (m.find()) {
            String u = m.group(1);
            try { u = java.net.URLDecoder.decode(u, "UTF-8"); } catch (Exception ignored) { }
            return u;
        }
        return clean(sipRemote);
    }

    /** "+919876543210" -> "+91 98765 43210" (only for the common Indian shapes). */
    static String pretty(String number) {
        String n = clean(number);
        if (n.startsWith("+91") && n.length() == 13) return "+91 " + n.substring(3, 8) + " " + n.substring(8);
        if (n.length() == 10 && !n.startsWith("+")) return n.substring(0, 5) + " " + n.substring(5);
        if (n.startsWith("0") && n.length() == 11) return n.substring(0, 6) + " " + n.substring(6);
        return n.isEmpty() ? (number == null ? "" : number) : n;
    }
}
