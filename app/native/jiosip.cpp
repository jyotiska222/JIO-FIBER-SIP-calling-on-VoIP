/*
 * jiosip.cpp  --  thin JNI layer over pjsua (PJSIP) for the JioFiber Calling app.
 *
 * It does what the web app's `jio-sip-client` did with the pjsua command line, but through the
 * pjsua C API, inside the Android app process:
 *   - registers to the Jio router's local SIP proxy over TLS (digest auth, realm "*")
 *   - keeps a TLS *listener* with a certificate (the router connects back to it to deliver the
 *     caller's ACK / BYE; without a cert incoming calls drop after ~30 s)
 *   - only offers AMR-WB / AMR codecs (the Jio core rejects everything else)
 *   - sends 180 Ringing automatically for incoming calls; the app answers later with 200
 *
 * The Java side is com.example.myapp.SipEngine. If you rename the package, rename the
 * Java_com_example_myapp_SipEngine_* functions and kClassName below.
 */
#include <jni.h>
#include <pjsua-lib/pjsua.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/stat.h>
#include <string>
#include <openssl/evp.h>
#include <openssl/rsa.h>
#include <openssl/x509.h>
#include <openssl/pem.h>
#include <openssl/rand.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "JioSip", __VA_ARGS__)
#define JNI_ENV_PTR(p) (p)
#else
#define LOGI(...) do { fprintf(stderr, "[JioSip] "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define JNI_ENV_PTR(p) ((void **)(p))
#endif

#define THIS_FILE "jiosip"
static const char *kClassName = "com/example/myapp/SipEngine";

static JavaVM   *g_vm  = NULL;
static jclass    g_cls = NULL;
static jmethodID m_onReg, m_onIncoming, m_onCallState, m_onMedia, m_onLog;

static pjsua_acc_id g_acc     = PJSUA_INVALID_ID;
static bool         g_running = false;
static volatile bool g_muted  = false;

/* ------------------------------------------------------------------ JNI helpers */

struct JEnv {
    JNIEnv *env;
    bool    attached;
    JEnv() : env(NULL), attached(false) {
        if (!g_vm) return;
        jint r = g_vm->GetEnv((void **)&env, JNI_VERSION_1_6);
        if (r == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(JNI_ENV_PTR(&env), NULL) == JNI_OK) attached = true;
            else env = NULL;
        } else if (r != JNI_OK) {
            env = NULL;
        }
    }
    ~JEnv() { if (attached && g_vm) g_vm->DetachCurrentThread(); }
};

/* JNI wants (modified) UTF-8. SIP traces can contain arbitrary bytes, which would abort a
 * CheckJNI build, so everything that goes to Java is reduced to printable ASCII + \n. */
static std::string clean(const char *s, int len = -1) {
    std::string o;
    if (!s) return o;
    if (len < 0) len = (int)strlen(s);
    o.reserve(len);
    for (int i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c == '\n' || c == '\t' || (c >= 0x20 && c < 0x7f)) o.push_back((char)c);
        else if (c == '\r') continue;
        else o.push_back('?');
    }
    return o;
}

static std::string S(JNIEnv *env, jstring js) {
    std::string r;
    if (!js) return r;
    const char *c = env->GetStringUTFChars(js, NULL);
    if (c) { r = c; env->ReleaseStringUTFChars(js, c); }
    return r;
}

static void reg_thread() {
    static thread_local pj_thread_desc desc;
    static thread_local pj_thread_t   *thr = NULL;
    if (!pj_thread_is_registered()) pj_thread_register("jni", desc, &thr);
}

/* ------------------------------------------------------------------ callbacks -> Java */

static void on_log(int level, const char *data, int len) {
    JEnv j; if (!j.env) return;
    std::string s = clean(data, len);
    jstring js = j.env->NewStringUTF(s.c_str());
    j.env->CallStaticVoidMethod(g_cls, m_onLog, (jint)level, js);
    j.env->DeleteLocalRef(js);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
}

static void on_reg_state(pjsua_acc_id acc_id) {
    pjsua_acc_info ai;
    if (pjsua_acc_get_info(acc_id, &ai) != PJ_SUCCESS) return;
    JEnv j; if (!j.env) return;
    std::string t = clean(ai.status_text.ptr, (int)ai.status_text.slen);
    jstring js = j.env->NewStringUTF(t.c_str());
    jboolean ok = (ai.status / 100 == 2 && ai.expires > 0) ? JNI_TRUE : JNI_FALSE;
    j.env->CallStaticVoidMethod(g_cls, m_onReg, (jint)ai.status, js, ok);
    j.env->DeleteLocalRef(js);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
}

static void on_incoming_call(pjsua_acc_id acc_id, pjsua_call_id call_id, pjsip_rx_data *rdata) {
    PJ_UNUSED_ARG(acc_id); PJ_UNUSED_ARG(rdata);
    pjsua_call_info ci;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;
    pjsua_call_answer(call_id, 180, NULL, NULL);            /* ring the caller */
    JEnv j; if (!j.env) return;
    std::string from = clean(ci.remote_info.ptr, (int)ci.remote_info.slen);
    jstring js = j.env->NewStringUTF(from.c_str());
    j.env->CallStaticVoidMethod(g_cls, m_onIncoming, (jint)call_id, js);
    j.env->DeleteLocalRef(js);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
}

static void on_call_state(pjsua_call_id call_id, pjsip_event *e) {
    PJ_UNUSED_ARG(e);
    pjsua_call_info ci;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;
    JEnv j; if (!j.env) return;
    std::string t = clean(ci.last_status_text.ptr, (int)ci.last_status_text.slen);
    jstring js = j.env->NewStringUTF(t.c_str());
    j.env->CallStaticVoidMethod(g_cls, m_onCallState, (jint)call_id, (jint)ci.state,
                                (jint)ci.last_status, js);
    j.env->DeleteLocalRef(js);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
}

static void on_call_media_state(pjsua_call_id call_id) {
    pjsua_call_info ci;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;
    bool active = (ci.media_status == PJSUA_CALL_MEDIA_ACTIVE && ci.conf_slot != PJSUA_INVALID_ID);
    if (active) {
        pjsua_conf_connect(ci.conf_slot, 0);                 /* caller -> speaker */
        if (!g_muted) pjsua_conf_connect(0, ci.conf_slot);   /* microphone -> caller */
    }
    JEnv j; if (!j.env) return;
    j.env->CallStaticVoidMethod(g_cls, m_onMedia, (jint)call_id, active ? JNI_TRUE : JNI_FALSE);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
}

/* ------------------------------------------------------------------ JNI entry points */

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
#ifdef __ANDROID__
    pj_jni_set_jvm(vm);          /* pjmedia's Android audio device needs the JVM */
#endif
    JNIEnv *env = NULL;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = env->FindClass(kClassName);
    if (!c) return JNI_ERR;
    g_cls = (jclass)env->NewGlobalRef(c);
    m_onReg       = env->GetStaticMethodID(c, "onReg",       "(ILjava/lang/String;Z)V");
    m_onIncoming  = env->GetStaticMethodID(c, "onIncoming",  "(ILjava/lang/String;)V");
    m_onCallState = env->GetStaticMethodID(c, "onCallState", "(IIILjava/lang/String;)V");
    m_onMedia     = env->GetStaticMethodID(c, "onMedia",     "(IZ)V");
    m_onLog       = env->GetStaticMethodID(c, "onLog",       "(ILjava/lang/String;)V");
    if (!m_onReg || !m_onIncoming || !m_onCallState || !m_onMedia || !m_onLog) return JNI_ERR;
    return JNI_VERSION_1_6;
}

static void set_codecs() {
    pjsua_codec_info ci[48];
    unsigned n = PJ_ARRAY_SIZE(ci);
    if (pjsua_enum_codecs(ci, &n) != PJ_SUCCESS) return;
    for (unsigned i = 0; i < n; i++) {
        std::string id(ci[i].codec_id.ptr, (size_t)ci[i].codec_id.slen);
        pj_uint8_t prio = 0;                                  /* everything except AMR is off */
        if (id.compare(0, 6, "AMR-WB") == 0) prio = 255;
        else if (id.compare(0, 3, "AMR") == 0) prio = 200;
        pjsua_codec_set_priority(&ci[i].codec_id, prio);
        if (prio) PJ_LOG(3, (THIS_FILE, "codec enabled: %s (priority %d)", id.c_str(), (int)prio));
    }
}

static pj_status_t make_transport(pjsip_transport_type_e type, pjsua_transport_config *tc,
                                  unsigned port) {
    pjsua_transport_id tid;
    tc->port = port;
    pj_status_t st = pjsua_transport_create(type, tc, &tid);
    if (st != PJ_SUCCESS && port != 0) { tc->port = 0; st = pjsua_transport_create(type, tc, &tid); }
    return st;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapp_SipEngine_nativeStart(JNIEnv *env, jclass,
        jstring jUa, jstring jRegUri, jstring jProxy, jstring jId, jstring jRealm,
        jstring jUser, jstring jPass, jstring jCert, jstring jKey, jstring jInstance,
        jint logLevel, jint rtpPort, jboolean useTls) {
    if (g_running) return 0;
    std::string ua = S(env, jUa), regUri = S(env, jRegUri), proxy = S(env, jProxy),
                id = S(env, jId), realm = S(env, jRealm), user = S(env, jUser),
                pass = S(env, jPass), cert = S(env, jCert), key = S(env, jKey),
                inst = S(env, jInstance);

    /* read by the patched pjsua_acc.c (apply_instance_id.py): the 8 hex digits of +sip.instance */
    if (inst.size() == 8) setenv("JFC_INSTANCE_ID", inst.c_str(), 1);

    pj_status_t st = pjsua_create();
    if (st != PJ_SUCCESS) return st;
    reg_thread();

    pjsua_config ucfg;      pjsua_config_default(&ucfg);
    pjsua_logging_config lc; pjsua_logging_config_default(&lc);
    pjsua_media_config mc;  pjsua_media_config_default(&mc);

    ucfg.cb.on_incoming_call    = &on_incoming_call;
    ucfg.cb.on_call_state       = &on_call_state;
    ucfg.cb.on_call_media_state = &on_call_media_state;
    ucfg.cb.on_reg_state        = &on_reg_state;
    ucfg.max_calls = 6;
    if (!ua.empty()) ucfg.user_agent = pj_str((char *)ua.c_str());

    lc.console_level = logLevel;     /* pjsua only calls lc.cb for levels <= console_level */
    lc.level = logLevel;
    lc.cb = &on_log;
    lc.decor = PJ_LOG_HAS_TIME | PJ_LOG_HAS_MICRO_SEC | PJ_LOG_HAS_SENDER;

    mc.clock_rate = 16000;           /* AMR-WB is 16 kHz */
    mc.snd_clock_rate = 16000;
    mc.channel_count = 1;
    mc.no_vad = PJ_TRUE;             /* MicroSIP: vad = 0 */
    mc.ec_tail_len = 200;            /* phone speaker/earpiece -> mic echo */

    st = pjsua_init(&ucfg, &lc, &mc);
    if (st != PJ_SUCCESS) { pjsua_destroy(); return st; }

    pjsua_transport_config tc;
    pjsua_transport_config_default(&tc);
    st = make_transport(PJSIP_TRANSPORT_UDP, &tc, 0);        /* same as pjsua CLI (UDP + TLS) */
    if (st != PJ_SUCCESS) LOGI("UDP transport failed: %d", st);

    if (useTls) {
        pjsua_transport_config tt;
        pjsua_transport_config_default(&tt);
        if (!cert.empty() && !key.empty()) {
            tt.tls_setting.cert_file    = pj_str((char *)cert.c_str());
            tt.tls_setting.privkey_file = pj_str((char *)key.c_str());
        }
        tt.tls_setting.verify_server = PJ_FALSE;
        tt.tls_setting.verify_client = PJ_FALSE;
        tt.tls_setting.require_client_cert = PJ_FALSE;
        st = make_transport(PJSIP_TRANSPORT_TLS, &tt, 5061);
        if (st != PJ_SUCCESS) { LOGI("TLS transport failed: %d", st); pjsua_destroy(); return st; }
    }

    st = pjsua_start();
    if (st != PJ_SUCCESS) { pjsua_destroy(); return st; }
    set_codecs();

    pjsua_acc_config ac;
    pjsua_acc_config_default(&ac);
    ac.id      = pj_str((char *)id.c_str());
    ac.reg_uri = pj_str((char *)regUri.c_str());
    ac.proxy_cnt = 1;
    ac.proxy[0]  = pj_str((char *)proxy.c_str());
    ac.cred_count = 1;
    ac.cred_info[0].realm     = pj_str((char *)realm.c_str());
    ac.cred_info[0].scheme    = pj_str((char *)"digest");
    ac.cred_info[0].username  = pj_str((char *)user.c_str());
    ac.cred_info[0].data_type = PJSIP_CRED_DATA_PLAIN_PASSWD;
    ac.cred_info[0].data      = pj_str((char *)pass.c_str());
    ac.reg_retry_interval = 60;      /* don't hammer the router with a rejected password */
    ac.rtp_cfg.port = rtpPort > 0 ? (unsigned)rtpPort : 4000;

    st = pjsua_acc_add(&ac, PJ_TRUE, &g_acc);
    if (st != PJ_SUCCESS) { pjsua_destroy(); return st; }
    g_running = true;
    g_muted = false;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapp_SipEngine_nativeStop(JNIEnv *, jclass, jboolean graceful) {
    if (!g_running) return;
    reg_thread();
    g_running = false;
    pjsua_call_hangup_all();
    if (graceful) {
        pjsua_acc_set_registration(g_acc, PJ_FALSE);         /* unregister so the router drops us */
        pj_thread_sleep(400);
        pjsua_destroy();
    } else {
        pjsua_destroy2(PJSUA_DESTROY_NO_NETWORK);            /* network is gone: don't wait */
    }
    g_acc = PJSUA_INVALID_ID;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapp_SipEngine_nativeReRegister(JNIEnv *, jclass) {
    if (!g_running) return;
    reg_thread();
    pjsua_acc_set_registration(g_acc, PJ_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapp_SipEngine_nativeCall(JNIEnv *env, jclass, jstring jUri) {
    if (!g_running) return -1;
    reg_thread();
    std::string uri = S(env, jUri);
    pj_str_t u = pj_str((char *)uri.c_str());
    pjsua_call_id cid = PJSUA_INVALID_ID;
    g_muted = false;
    pj_status_t st = pjsua_call_make_call(g_acc, &u, NULL, NULL, NULL, &cid);
    return st == PJ_SUCCESS ? (jint)cid : -2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapp_SipEngine_nativeAnswer(JNIEnv *, jclass, jint callId, jint code) {
    if (!g_running) return -1;
    reg_thread();
    return (jint)pjsua_call_answer(callId, (unsigned)code, NULL, NULL);
}

/* code 0 = default (BYE for a connected call, 603 for an unanswered incoming call) */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapp_SipEngine_nativeHangup(JNIEnv *, jclass, jint callId, jint code) {
    if (!g_running) return -1;
    reg_thread();
    return (jint)pjsua_call_hangup(callId, (unsigned)code, NULL, NULL);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapp_SipEngine_nativeHold(JNIEnv *, jclass, jint callId, jboolean hold) {
    if (!g_running) return -1;
    reg_thread();
    if (hold) return (jint)pjsua_call_set_hold(callId, NULL);
    return (jint)pjsua_call_reinvite(callId, PJSUA_CALL_UNHOLD, NULL);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_myapp_SipEngine_nativeDtmf(JNIEnv *env, jclass, jint callId, jstring jDigits) {
    if (!g_running) return -1;
    reg_thread();
    std::string d = S(env, jDigits);
    pj_str_t s = pj_str((char *)d.c_str());
    return (jint)pjsua_call_dial_dtmf(callId, &s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapp_SipEngine_nativeMute(JNIEnv *, jclass, jint callId, jboolean mute) {
    if (!g_running) return;
    reg_thread();
    g_muted = mute;
    int slot = pjsua_call_get_conf_port(callId);
    if (slot < 0) return;
    if (mute) pjsua_conf_disconnect(0, slot);
    else      pjsua_conf_connect(0, slot);
}


/* Self-signed certificate for the TLS listener (the router connects back to it). Same parameters the
 * web app used:  openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 -subj /CN=webcall-sip-client
 * Generated on the phone at first start, so no private key ships inside the APK. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_myapp_SipEngine_nativeMakeCert(JNIEnv *env, jclass, jstring jCert, jstring jKey) {
    std::string certPath = S(env, jCert), keyPath = S(env, jKey);
    bool ok = false;
    EVP_PKEY *pkey = NULL;
    X509 *x = NULL;
    FILE *fc = NULL, *fk = NULL;

    pkey = EVP_RSA_gen(2048);
    if (!pkey) goto done;
    x = X509_new();
    if (!x) goto done;
    {
        unsigned char rnd[8];
        if (RAND_bytes(rnd, sizeof rnd) != 1) goto done;
        rnd[0] &= 0x7f;                                       /* positive serial */
        BIGNUM *bn = BN_bin2bn(rnd, sizeof rnd, NULL);
        if (!bn) goto done;
        BN_to_ASN1_INTEGER(bn, X509_get_serialNumber(x));
        BN_free(bn);
    }
    X509_set_version(x, 2);
    X509_gmtime_adj(X509_getm_notBefore(x), 0);
    X509_gmtime_adj(X509_getm_notAfter(x), 60L * 60 * 24 * 3650);
    if (X509_set_pubkey(x, pkey) != 1) goto done;
    {
        X509_NAME *nm = X509_get_subject_name(x);
        X509_NAME_add_entry_by_txt(nm, "CN", MBSTRING_ASC, (const unsigned char *)"webcall-sip-client", -1, -1, 0);
        X509_set_issuer_name(x, nm);
    }
    if (X509_sign(x, pkey, EVP_sha256()) <= 0) goto done;

    fk = fopen(keyPath.c_str(), "wb");
    if (!fk) goto done;
    chmod(keyPath.c_str(), 0600);
    if (PEM_write_PrivateKey(fk, pkey, NULL, NULL, 0, NULL, NULL) != 1) goto done;
    fc = fopen(certPath.c_str(), "wb");
    if (!fc) goto done;
    if (PEM_write_X509(fc, x) != 1) goto done;
    ok = true;
done:
    if (fk) fclose(fk);
    if (fc) fclose(fc);
    if (x) X509_free(x);
    if (pkey) EVP_PKEY_free(pkey);
    if (!ok) { remove(keyPath.c_str()); remove(certPath.c_str()); }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_myapp_SipEngine_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(pj_get_version());
}
