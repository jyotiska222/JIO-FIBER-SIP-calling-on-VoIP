package com.example.myapp;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

final class Permissions {
    private Permissions() {}

    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    static String[] runtimeMissing(Context c) {
        List<String> l = new ArrayList<>();
        if (!has(c, Manifest.permission.RECORD_AUDIO)) l.add(Manifest.permission.RECORD_AUDIO);
        if (!has(c, Manifest.permission.READ_CONTACTS)) l.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 33 && !has(c, Manifest.permission.POST_NOTIFICATIONS))
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31 && !has(c, Manifest.permission.BLUETOOTH_CONNECT))
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
        return l.toArray(new String[0]);
    }

    static boolean ignoringBattery(Context c) {
        return ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static boolean fullScreenAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        return ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).canUseFullScreenIntent();
    }

    static void requestBatteryExemption(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    static void openFullScreenSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) { openAppSettings(a); }
    }

    static void openAppSettings(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
    }
}
