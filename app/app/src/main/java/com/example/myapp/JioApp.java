package com.example.myapp;

import android.app.Activity;
import android.app.Application;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;

public class JioApp extends Application {
    private static int visible;
    static boolean inForeground() { return visible > 0; }

    @Override public void onCreate() {
        super.onCreate();
        CallNotifier.createChannels(this);
        Store store = Store.get(this);
        CallManager.get(this);
        store.loadCacheThenSync();

        // "Contact sync": re-read the phone's address book whenever it changes
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable resync = new Runnable() { @Override public void run() { Store.get(JioApp.this).sync(); } };
        try {
            getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true,
                    new ContentObserver(h) {
                        @Override public void onChange(boolean selfChange) {
                            h.removeCallbacks(resync);
                            h.postDelayed(resync, 1500);
                        }
                    });
        } catch (SecurityException ignored) { }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityStarted(Activity a) { visible++; }
            public void onActivityStopped(Activity a) { visible--; }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });

        SipConfig cfg = SipConfig.get(this);
        if (cfg.isComplete()) SipService.start(this);
    }
}
