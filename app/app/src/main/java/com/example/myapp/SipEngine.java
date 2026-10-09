package com.example.myapp;

/**
 * JNI wrapper around libjiosip.so (pjsua + the Jio patches). The static on* methods are called
 * from native pjsip threads; {@link CallManager} moves them to the main thread.
 */
public final class SipEngine {
    private static boolean loaded;
    private static String loadError = "";

    static {
        try {
            System.loadLibrary("jiosip");
            loaded = true;
        } catch (Throwable t) {
            loadError = String.valueOf(t.getMessage());
        }
    }

    public static boolean isAvailable() { return loaded; }
    public static String loadError() { return loadError; }

    interface Listener {
        void onReg(int code, String text, boolean registered);
        void onIncoming(int callId, String remoteInfo);
        void onCallState(int callId, int state, int status, String text);
        void onMedia(int callId, boolean active);
        void onLog(int level, String line);
    }

    // pjsip_inv_state values
    static final int INV_CALLING = 1, INV_INCOMING = 2, INV_EARLY = 3, INV_CONNECTING = 4,
            INV_CONFIRMED = 5, INV_DISCONNECTED = 6;

    static volatile Listener listener;

    // ---- called from native threads ----
    static void onReg(int code, String text, boolean registered) {
        Listener l = listener; if (l != null) l.onReg(code, text, registered);
    }
    static void onIncoming(int callId, String remoteInfo) {
        Listener l = listener; if (l != null) l.onIncoming(callId, remoteInfo);
    }
    static void onCallState(int callId, int state, int status, String text) {
        Listener l = listener; if (l != null) l.onCallState(callId, state, status, text);
    }
    static void onMedia(int callId, boolean active) {
        Listener l = listener; if (l != null) l.onMedia(callId, active);
    }
    static void onLog(int level, String line) {
        Listener l = listener; if (l != null) l.onLog(level, line);
    }

    // ---- native API (see native/jiosip.cpp) ----
    static native int nativeStart(String userAgent, String regUri, String proxyUri, String accountId,
                                  String realm, String username, String password, String certFile,
                                  String keyFile, String instanceHex, int logLevel, int rtpPort,
                                  boolean useTls);
    static native void nativeStop(boolean graceful);
    static native void nativeReRegister();
    static native int nativeCall(String uri);
    static native int nativeAnswer(int callId, int code);
    static native int nativeHangup(int callId, int code);
    static native int nativeHold(int callId, boolean hold);
    static native int nativeDtmf(int callId, String digits);
    static native void nativeMute(int callId, boolean mute);
    static native boolean nativeMakeCert(String certPath, String keyPath);
    static native String nativeVersion();
}
