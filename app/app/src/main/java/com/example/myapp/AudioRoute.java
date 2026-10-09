package com.example.myapp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import java.util.List;

/** Puts the phone in "voice call" audio mode and picks earpiece / speaker / headset. */
final class AudioRoute {
    private final AudioManager am;
    private AudioFocusRequest focus;
    private int oldMode = AudioManager.MODE_NORMAL;
    private boolean active;

    AudioRoute(Context c) { am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE); }

    void begin(boolean speaker) {
        if (!active) {
            active = true;
            oldMode = am.getMode();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override public void onAudioFocusChange(int change) { }
                    }).build();
            am.requestAudioFocus(focus);
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }
        route(speaker);
    }

    void route(boolean speaker) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo pick = null;
                List<AudioDeviceInfo> devs = am.getAvailableCommunicationDevices();
                if (speaker) {
                    pick = find(devs, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
                } else {
                    int[] pref = {AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE};
                    for (int t : pref) { pick = find(devs, t); if (pick != null) break; }
                }
                if (pick != null) am.setCommunicationDevice(pick);
            } else {
                am.setSpeakerphoneOn(speaker);
            }
        } catch (Exception ignored) { }
    }

    private static AudioDeviceInfo find(List<AudioDeviceInfo> l, int type) {
        for (AudioDeviceInfo d : l) if (d.getType() == type) return d;
        return null;
    }

    /** Wired or Bluetooth headset connected? (then the screen shouldn't switch off near the ear) */
    boolean headsetConnected() {
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true;
                if (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    void end() {
        if (!active) return;
        active = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else am.setSpeakerphoneOn(false);
            am.setMode(oldMode == AudioManager.MODE_IN_COMMUNICATION ? AudioManager.MODE_NORMAL : oldMode);
            if (focus != null) am.abandonAudioFocusRequest(focus);
        } catch (Exception ignored) { }
        focus = null;
    }
}
