package com.example.myapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification buttons that don't need a screen: Decline and Hang up. */
public class CallActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        CallManager cm = CallManager.get(c);
        if (CallNotifier.ACT_DECLINE.equals(a) || CallNotifier.ACT_HANGUP.equals(a)) cm.hangup();
    }
}
