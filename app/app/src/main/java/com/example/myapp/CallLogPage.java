package com.example.myapp;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.ContactsContract;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Tab 1: call history from the app's own database. Tap a row = call that number. */
final class CallLogPage extends LinearLayout implements Store.Listener {
    private final MainActivity act;
    private final ListView list;
    private final TextView empty, tabAll, tabMissed;
    private final List<Store.Entry> items = new ArrayList<>();
    private boolean missedOnly;

    CallLogPage(MainActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);

        LinearLayout chips = new LinearLayout(a);
        chips.setPadding(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6));
        tabAll = chip("All", true);
        tabMissed = chip("Missed", false);
        chips.addView(tabAll);
        chips.addView(tabMissed);
        addView(chips);
        tabAll.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { setFilter(false); } });
        tabMissed.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { setFilter(true); } });

        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        list = new ListView(a);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(adapter);
        box.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(a, "No calls yet", 16, Ui.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        box.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        addView(box, new LayoutParams(-1, 0, 1f));

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                act.dial(items.get(pos).number);                    // one tap = call
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                options(items.get(pos));
                return true;
            }
        });
    }

    private TextView chip(String s, boolean on) {
        TextView t = Ui.text(getContext(), s, 14, on ? 0xFFFFFFFF : Ui.TEXT2, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(getContext(), 16), Ui.dp(getContext(), 7), Ui.dp(getContext(), 16), Ui.dp(getContext(), 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(getContext(), 8);
        t.setLayoutParams(lp);
        t.setBackground(Ui.round(getContext(), on ? Ui.ACCENT : Ui.SURFACE2, 20));
        return t;
    }

    private void setFilter(boolean missed) {
        missedOnly = missed;
        tabAll.setTextColor(!missed ? 0xFFFFFFFF : Ui.TEXT2);
        tabMissed.setTextColor(missed ? 0xFFFFFFFF : Ui.TEXT2);
        tabAll.setBackground(Ui.round(getContext(), !missed ? Ui.ACCENT : Ui.SURFACE2, 20));
        tabMissed.setBackground(Ui.round(getContext(), missed ? Ui.ACCENT : Ui.SURFACE2, 20));
        reload();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Store.get(getContext()).addListener(this);
        reload();
    }

    @Override protected void onDetachedFromWindow() {
        Store.get(getContext()).removeListener(this);
        super.onDetachedFromWindow();
    }

    void reload() {
        final Store s = Store.get(getContext());
        final boolean m = missedOnly;
        s.runIo(new Runnable() { @Override public void run() {
            final List<Store.Entry> l = s.loadCalls(m);
            post(new Runnable() { @Override public void run() {
                items.clear(); items.addAll(l);
                adapter.notifyDataSetChanged();
                empty.setText(m ? "No missed calls" : "No calls yet");
                empty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
            }});
        }});
    }

    @Override public void onCallLogChanged() { reload(); }
    @Override public void onContactsChanged() { adapter.notifyDataSetChanged(); }

    private void options(final Store.Entry e) {
        final Context c = getContext();
        final boolean known = Store.get(c).find(e.number) != null;
        String[] opts = known ? new String[]{"Call", "Copy number", "Delete from log"}
                : new String[]{"Call", "Copy number", "Add to contacts", "Delete from log"};
        new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(PhoneUtil.pretty(e.number))
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String o = known ? new String[]{"Call", "Copy number", "Delete from log"}[which]
                                : new String[]{"Call", "Copy number", "Add to contacts", "Delete from log"}[which];
                        if (o.equals("Call")) act.dial(e.number);
                        else if (o.equals("Copy number")) {
                            ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE))
                                    .setPrimaryClip(ClipData.newPlainText("number", e.number));
                            Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
                        } else if (o.equals("Add to contacts")) {
                            Intent i = new Intent(Intent.ACTION_INSERT_OR_EDIT);
                            i.setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE);
                            i.putExtra(ContactsContract.Intents.Insert.PHONE, e.number);
                            act.startActivity(i);
                        } else Store.get(c).deleteCall(e.id);
                    }
                }).show();
    }

    static String when(Context c, long t) {
        if (DateUtils.isToday(t)) return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(t));
        if (DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)) return "Yesterday";
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(t));
    }

    static String duration(int s) {
        if (s <= 0) return "";
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }
        @Override public View getView(int i, View v, ViewGroup p) {
            ListRow r = v instanceof ListRow ? (ListRow) v : new ListRow(p.getContext());
            Store.Entry e = items.get(i);
            Store.Contact k = Store.get(p.getContext()).find(e.number);
            String name = k != null ? k.name : e.name;
            int icon, tint; String label;
            switch (e.dir) {
                case Store.IN:       icon = R.drawable.ic_call_received; tint = Ui.GREEN; label = "Incoming"; break;
                case Store.MISSED:   icon = R.drawable.ic_call_missed;   tint = Ui.RED;   label = "Missed"; break;
                case Store.REJECTED: icon = R.drawable.ic_call_missed;   tint = Ui.AMBER; label = "Declined"; break;
                default:             icon = R.drawable.ic_call_made;     tint = Ui.ACCENT; label = "Outgoing";
            }
            String d = duration(e.dur);
            String sub = (name != null && !name.isEmpty() ? PhoneUtil.pretty(e.number) + " · " : "") + label
                    + (d.isEmpty() ? "" : " · " + d);
            r.bind(name, name != null && !name.isEmpty() ? name : PhoneUtil.pretty(e.number), sub, icon, tint,
                    when(p.getContext(), e.start), k == null ? null : k.photo,
                    e.dir == Store.MISSED ? Ui.RED : Ui.TEXT);
            return r;
        }
    };
}
