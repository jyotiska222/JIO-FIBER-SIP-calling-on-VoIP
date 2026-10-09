package com.example.myapp;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

/**
 * Rings like the phone's own dialer: the user's default ringtone on the RING volume, with the
 * phone's ringer mode (normal / vibrate / silent), "vibrate for calls" setting and Do Not Disturb
 * rules (incl. "priority only: calls from contacts / starred") respected.
 */
final class Ringer {
    private final Context ctx;
    private Ringtone ringtone;
    private MediaPlayer player;
    private Vibrator vibrator;
    private boolean ringing;

    Ringer(Context c) { ctx = c.getApplicationContext(); }

    boolean isRinging() { return ringing; }

    /** DND: true when the current Do Not Disturb setting should keep this caller quiet. */
    static boolean blockedByDnd(Context c, String number) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            int f = nm.getCurrentInterruptionFilter();
            if (f == NotificationManager.INTERRUPTION_FILTER_NONE
                    || f == NotificationManager.INTERRUPTION_FILTER_ALARMS) return true;
            if (f == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                NotificationManager.Policy p = nm.getNotificationPolicy();
                if ((p.priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) == 0) return true;
                Store s = Store.get(c);
                switch (p.priorityCallSenders) {
                    case NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS: return s.find(number) == null;
                    case NotificationManager.Policy.PRIORITY_SENDERS_STARRED:  return !s.isStarred(number);
                    default: return false;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    void start(String number) {
        stop();
        ringing = true;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int mode = am.getRingerMode();
        boolean dnd = blockedByDnd(ctx, number);
        if (dnd || mode == AudioManager.RINGER_MODE_SILENT) return;

        boolean sound = mode == AudioManager.RINGER_MODE_NORMAL && am.getStreamVolume(AudioManager.STREAM_RING) > 0;
        boolean vib = false;
        if (SipConfig.get(ctx).vibrate()) {
            if (mode == AudioManager.RINGER_MODE_VIBRATE) vib = true;
            else if (mode == AudioManager.RINGER_MODE_NORMAL) {
                try { vib = Settings.System.getInt(ctx.getContentResolver(), "vibrate_when_ringing", 0) == 1; }
                catch (Exception ignored) { }
            }
        }
        if (sound) startSound();
        if (vib) startVibration();
    }

    private void startSound() {
        try {
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            if (Build.VERSION.SDK_INT >= 28) {
                ringtone = RingtoneManager.getRingtone(ctx, uri);
                if (ringtone != null) {
                    ringtone.setAudioAttributes(attrs);
                    ringtone.setLooping(true);
                    ringtone.play();
                }
            } else {
                player = new MediaPlayer();
                player.setDataSource(ctx, uri);
                player.setAudioAttributes(attrs);
                player.setLooping(true);
                player.prepare();
                player.start();
            }
        } catch (Exception e) {
            // fall back to the built-in default if the chosen ringtone can't be opened
            try {
                ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
                if (ringtone != null) ringtone.play();
            } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private void startVibration() {
        try {
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 900, 900}, 0), attrs);
        } catch (Exception ignored) { }
    }

    /** Volume-key press while ringing: stop the noise but keep the call ringing. */
    void silence() {
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Exception ignored) { }
        try { if (player != null && player.isPlaying()) player.pause(); } catch (Exception ignored) { }
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) { }
    }

    void stop() {
        ringing = false;
        try { if (ringtone != null) ringtone.stop(); } catch (Exception ignored) { }
        ringtone = null;
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { }
        player = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) { }
        vibrator = null;
    }
}
