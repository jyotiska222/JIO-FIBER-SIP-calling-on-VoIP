package com.example.myapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** All notifications: service (persistent), incoming call (full screen), missed call. */
final class CallNotifier {
    private CallNotifier() {}

    static final String CH_SERVICE = "sip_service", CH_INCOMING = "incoming_call", CH_MISSED = "missed_call", CH_LOGIN = "jio_login";
    static final int ID_SERVICE = 1, ID_INCOMING = 2, ID_MISSED = 3, ID_LOGIN = 4;
    static final String ACT_ANSWER = "com.example.myapp.ANSWER", ACT_DECLINE = "com.example.myapp.DECLINE",
            ACT_HANGUP = "com.example.myapp.HANGUP";

    static void createChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel svc = new NotificationChannel(CH_SERVICE, "Line status", NotificationManager.IMPORTANCE_LOW);
        svc.setDescription("Keeps the line connected so calls can arrive");
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        // Sound and vibration are produced by Ringer (so they follow the phone's ringer mode / DND),
        // so the channel itself is silent.
        NotificationChannel inc = new NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        inc.setSound(null, null);
        inc.enableVibration(false);
        inc.setBypassDnd(false);
        inc.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(inc);

        NotificationChannel miss = new NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(miss);

        NotificationChannel login = new NotificationChannel(CH_LOGIN, "Jio login", NotificationManager.IMPORTANCE_HIGH);
        login.setDescription("Asks you to log in with an OTP when the router doesn't know this phone");
        nm.createNotificationChannel(login);
    }

    private static PendingIntent activity(Context c, int req, Intent i) {
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent broadcast(Context c, int req, String action) {
        Intent i = new Intent(c, CallActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Intent callScreenIntent(Context c) {
        return new Intent(c, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    // ------------------------------------------------------------------ persistent / ongoing
    static Notification service(Context c) {
        CallManager cm = CallManager.get(c);
        PendingIntent open = activity(c, 10, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
        Notification.Builder b = new Notification.Builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_call)
                .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .setContentIntent(open);
        CallManager.State s = cm.state();
        if (cm.inCall()) {
            PendingIntent screen = activity(c, 11, callScreenIntent(c));
            String who = cm.displayName();
            if (s == CallManager.State.RINGING_IN) {
                b.setContentTitle("Incoming call").setContentText(who);
            } else {
                b.setContentTitle(who).setContentText(s == CallManager.State.ACTIVE || s == CallManager.State.HOLD
                        ? (s == CallManager.State.HOLD ? "On hold" : "Ongoing call") : "Calling…");
                if (cm.talkSeconds() > 0 || s == CallManager.State.ACTIVE) {
                    b.setUsesChronometer(true).setShowWhen(true)
                     .setWhen(System.currentTimeMillis() - cm.talkSeconds() * 1000L);
                }
                b.addAction(new Notification.Action.Builder(null, "Hang up", broadcast(c, 12, ACT_HANGUP)).build());
            }
            b.setContentIntent(screen).setCategory(Notification.CATEGORY_CALL);
        } else {
            b.setContentTitle("JioFiber Calling")
             .setContentText(cm.isRegistered() ? "Ready for calls" : cm.regText())
             .setCategory(Notification.CATEGORY_SERVICE);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ incoming
    static void showIncoming(Context c, String name, String number) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent full = activity(c, 20, callScreenIntent(c));
        // "Answer" opens the call screen with ANSWER set (an activity PendingIntent, because Android 12+
        // blocks starting an activity from a broadcast triggered by a notification)
        PendingIntent answer = activity(c, 21, callScreenIntent(c).setAction(ACT_ANSWER));
        PendingIntent decline = broadcast(c, 22, ACT_DECLINE);

        Notification.Builder b = new Notification.Builder(c, CH_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true).setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(full, true)
                .setContentIntent(full);
        if (Build.VERSION.SDK_INT >= 31) {
            Person p = new Person.Builder().setName(name).setImportant(true).build();
            b.setStyle(Notification.CallStyle.forIncomingCall(p, decline, answer))
             .setContentText(number);
        } else {
            b.setContentTitle(name).setContentText("Incoming call · " + number)
             .addAction(new Notification.Action.Builder(null, "Decline", decline).build())
             .addAction(new Notification.Action.Builder(null, "Answer", answer).build());
        }
        nm.notify(ID_INCOMING, b.build());
    }

    static void cancelIncoming(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID_INCOMING);
    }

    // ------------------------------------------------------------------ missed
    static void showMissed(Context c, String number) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String name = Store.get(c).nameFor(number);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TAB, 0);
        Intent back = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setAction(MainActivity.ACTION_CALL_BACK).putExtra(MainActivity.EXTRA_NUMBER, number);
        Notification.Builder b = new Notification.Builder(c, CH_MISSED)
                .setSmallIcon(R.drawable.ic_call_missed)
                .setContentTitle("Missed call")
                .setContentText(name != null ? name : PhoneUtil.pretty(number))
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setContentIntent(activity(c, 30, open))
                .addAction(new Notification.Action.Builder(null, "Call back", activity(c, 31, back)).build());
        nm.notify(ID_MISSED, b.build());
    }

    // ------------------------------------------------------------------ Jio login (OTP needed)
    static void showLoginNeeded(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent i = new Intent(c, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(c, CH_LOGIN)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("Jio login needed")
                .setContentText("Tap to log in with the OTP so this phone can use your line")
                .setAutoCancel(true)
                .setContentIntent(activity(c, 40, i));
        nm.notify(ID_LOGIN, b.build());
    }

    static void cancelLoginNeeded(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID_LOGIN);
    }
}
