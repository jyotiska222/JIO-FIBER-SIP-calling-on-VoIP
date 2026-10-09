package com.example.myapp;

import android.content.Context;

import java.io.File;

/**
 * The router connects BACK to the phone over TLS (to deliver the caller's ACK / BYE), so the phone's
 * TLS listener needs a certificate. Like the web app (which runs `openssl req -x509 ...` on first
 * start) the app creates its own self-signed certificate on this phone the first time: no key ships
 * inside the APK.
 */
final class TlsFiles {
    private TlsFiles() {}

    /** @return {certPath, keyPath} or null if a certificate could not be created */
    static String[] ensure(Context c) {
        try {
            File dir = new File(c.getFilesDir(), "tls");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            new File(dir, "webcall-cert.pem").delete();          // shared certificate of the older app version
            new File(dir, "webcall-key.pem").delete();
            File cert = new File(dir, "sip-cert.pem"), key = new File(dir, "sip-key.pem");
            if (!cert.isFile() || !key.isFile()) {
                if (!SipEngine.isAvailable() || !SipEngine.nativeMakeCert(cert.getAbsolutePath(), key.getAbsolutePath())) return null;
            }
            return new String[]{cert.getAbsolutePath(), key.getAbsolutePath()};
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean present(Context c) { return ensure(c) != null; }
}
