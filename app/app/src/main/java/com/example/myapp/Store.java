package com.example.myapp;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-private storage:
 *  - the call log (SQLite in the app's internal storage: survives restarts, removed on uninstall)
 *  - a cached copy of the phone's contacts, re-synced from ContactsContract whenever they change
 */
final class Store extends SQLiteOpenHelper {
    static final int OUT = 0, IN = 1, MISSED = 2, REJECTED = 3;

    static final class Contact {
        long cid; String name, number, key, photo, lookup; boolean starred;
    }

    static final class Entry {
        long id; String number, name; int dir; long start; int dur;
    }

    interface Listener { void onCallLogChanged(); void onContactsChanged(); }

    private static Store inst;
    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile List<Contact> contacts = Collections.emptyList();
    private volatile Map<String, Contact> byKey = Collections.emptyMap();
    private volatile boolean syncing;
    private volatile long lastSync;

    private Store(Context c) {
        super(c, "jiocalls.db", null, 1);
        ctx = c;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE calls(_id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT, name TEXT, "
                + "dir INTEGER, start INTEGER, dur INTEGER)");
        db.execSQL("CREATE INDEX calls_start ON calls(start DESC)");
        db.execSQL("CREATE TABLE contacts(_id INTEGER PRIMARY KEY AUTOINCREMENT, cid INTEGER, name TEXT, "
                + "number TEXT, key TEXT, photo TEXT, starred INTEGER, lookup TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    void addListener(Listener l) { listeners.addIfAbsent(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void fire(final boolean callLog) {
        main.post(new Runnable() { @Override public void run() {
            for (Listener l : listeners) { if (callLog) l.onCallLogChanged(); else l.onContactsChanged(); }
        }});
    }

    // ------------------------------------------------------------------------- call log
    void addCall(final Entry e) {
        io.execute(new Runnable() { @Override public void run() {
            ContentValues v = new ContentValues();
            v.put("number", e.number); v.put("name", e.name); v.put("dir", e.dir);
            v.put("start", e.start); v.put("dur", e.dur);
            SQLiteDatabase db = getWritableDatabase();
            db.insert("calls", null, v);
            db.execSQL("DELETE FROM calls WHERE _id NOT IN (SELECT _id FROM calls ORDER BY start DESC LIMIT 2000)");
            fire(true);
        }});
    }

    /** Blocking read - call from a background thread. */
    List<Entry> loadCalls(boolean missedOnly) {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("calls", null,
                missedOnly ? "dir=" + MISSED : null, null, null, null, "start DESC", "1000");
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                e.number = c.getString(c.getColumnIndexOrThrow("number"));
                e.name = c.getString(c.getColumnIndexOrThrow("name"));
                e.dir = c.getInt(c.getColumnIndexOrThrow("dir"));
                e.start = c.getLong(c.getColumnIndexOrThrow("start"));
                e.dur = c.getInt(c.getColumnIndexOrThrow("dur"));
                out.add(e);
            }
        } finally { c.close(); }
        return out;
    }

    void deleteCall(final long id) {
        io.execute(new Runnable() { @Override public void run() {
            getWritableDatabase().delete("calls", "_id=?", new String[]{String.valueOf(id)});
            fire(true);
        }});
    }

    void clearCalls() {
        io.execute(new Runnable() { @Override public void run() {
            getWritableDatabase().delete("calls", null, null);
            fire(true);
        }});
    }

    void runIo(Runnable r) { io.execute(r); }

    // ------------------------------------------------------------------------- contacts
    List<Contact> contacts() { return contacts; }
    boolean isSyncing() { return syncing; }
    long lastSync() { return lastSync; }

    static boolean canReadContacts(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    Contact find(String number) {
        String k = PhoneUtil.key(number);
        if (k.length() < 7) return null;
        return byKey.get(k);
    }

    String nameFor(String number) {
        Contact c = find(number);
        return c == null ? null : c.name;
    }

    boolean isStarred(String number) {
        Contact c = find(number);
        return c != null && c.starred;
    }

    /** Show the cached list immediately, then refresh from the phone. */
    void loadCacheThenSync() {
        io.execute(new Runnable() { @Override public void run() {
            List<Contact> list = new ArrayList<>();
            Cursor c = getReadableDatabase().query("contacts", null, null, null, null, null, "name COLLATE NOCASE");
            try {
                while (c.moveToNext()) list.add(fromCursor(c));
            } finally { c.close(); }
            if (!list.isEmpty()) { publish(list); fire(false); }
            doSync();
        }});
    }

    void sync() { io.execute(new Runnable() { @Override public void run() { doSync(); } }); }

    private static Contact fromCursor(Cursor c) {
        Contact k = new Contact();
        k.cid = c.getLong(c.getColumnIndexOrThrow("cid"));
        k.name = c.getString(c.getColumnIndexOrThrow("name"));
        k.number = c.getString(c.getColumnIndexOrThrow("number"));
        k.key = c.getString(c.getColumnIndexOrThrow("key"));
        k.photo = c.getString(c.getColumnIndexOrThrow("photo"));
        k.starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1;
        k.lookup = c.getString(c.getColumnIndexOrThrow("lookup"));
        return k;
    }

    private void publish(List<Contact> list) {
        Map<String, Contact> m = new HashMap<>();
        for (Contact k : list) if (k.key != null && k.key.length() >= 7 && !m.containsKey(k.key)) m.put(k.key, k);
        byKey = m;
        contacts = list;
    }

    private void doSync() {
        if (!canReadContacts(ctx)) return;
        syncing = true;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] proj = {
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.STARRED,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY};
            List<Contact> list = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
            if (c == null) return;
            try {
                while (c.moveToNext()) {
                    String num = PhoneUtil.clean(c.getString(2));
                    if (num.isEmpty()) continue;
                    long cid = c.getLong(0);
                    String key = PhoneUtil.key(num);
                    if (!seen.add(cid + ":" + key)) continue;       // same number saved twice
                    Contact k = new Contact();
                    k.cid = cid; k.name = c.getString(1); k.number = num; k.key = key;
                    k.photo = c.getString(3); k.starred = c.getInt(4) == 1; k.lookup = c.getString(5);
                    if (k.name == null || k.name.trim().isEmpty()) k.name = num;
                    list.add(k);
                }
            } finally { c.close(); }

            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("contacts", null, null);
                for (Contact k : list) {
                    ContentValues v = new ContentValues();
                    v.put("cid", k.cid); v.put("name", k.name); v.put("number", k.number);
                    v.put("key", k.key); v.put("photo", k.photo); v.put("starred", k.starred ? 1 : 0);
                    v.put("lookup", k.lookup);
                    db.insert("contacts", null, v);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            publish(list);
            lastSync = System.currentTimeMillis();
            fire(false);
        } catch (SecurityException ignored) {
        } finally { syncing = false; }
    }
}
