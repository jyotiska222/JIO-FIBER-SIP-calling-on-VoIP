package com.example.myapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the always-on line after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        SipConfig cfg = SipConfig.get(c);
        if (cfg.startOnBoot() && cfg.isComplete()) SipService.start(c);
    }
}
