package com.example.myapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tab 3: the phone's contacts (synced into the app). Tap a row = call that number. */
final class ContactsPage extends LinearLayout implements Store.Listener {
    private final MainActivity act;
    private final ListView list;
    private final EditText search;
    private final TextView empty, grant;
    private final List<Store.Contact> shown = new ArrayList<>();

    ContactsPage(MainActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);

        search = new EditText(a);
        search.setHint("Search name or number");
        search.setHintTextColor(Ui.TEXT2);
        search.setTextColor(Ui.TEXT);
        search.setSingleLine(true);
        search.setTextSize(15);
        int p = Ui.dp(a, 14);
        search.setPadding(p, 0, p, 0);
        search.setBackground(Ui.round(a, Ui.SURFACE2, 24));
        LayoutParams sp = new LayoutParams(-1, Ui.dp(a, 46));
        sp.setMargins(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6));
        addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(); }
            public void afterTextChanged(Editable e) { }
        });

        FrameLayout box = new FrameLayout(a);
        list = new ListView(a);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        box.addView(list, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout msg = new LinearLayout(a);
        msg.setOrientation(VERTICAL);
        msg.setGravity(Gravity.CENTER);
        empty = Ui.text(a, "No contacts", 16, Ui.TEXT2, false);
        empty.setGravity(Gravity.CENTER);
        msg.addView(empty);
        grant = Ui.text(a, "Allow contacts access", 14, 0xFFFFFFFF, true);
        grant.setGravity(Gravity.CENTER);
        grant.setPadding(Ui.dp(a, 22), Ui.dp(a, 11), Ui.dp(a, 22), Ui.dp(a, 11));
        grant.setBackground(Ui.ripple(Ui.round(a, Ui.ACCENT, 24)));
        LayoutParams gp = new LayoutParams(-2, -2);
        gp.topMargin = Ui.dp(a, 14);
        msg.addView(grant, gp);
        grant.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { act.askContacts(); } });
        box.addView(msg, new FrameLayout.LayoutParams(-1, -1));
        addView(box, new LayoutParams(-1, 0, 1f));
        emptyBox = msg;

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> pr, View v, int pos, long id) { act.dial(shown.get(pos).number); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> pr, View v, int pos, long id) { options(shown.get(pos)); return true; }
        });
    }

    private final View emptyBox;

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Store.get(getContext()).addListener(this);
        filter();
    }

    @Override protected void onDetachedFromWindow() {
        Store.get(getContext()).removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override public void onContactsChanged() { filter(); }
    @Override public void onCallLogChanged() { }

    void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        String qd = PhoneUtil.digits(q);
        shown.clear();
        for (Store.Contact k : Store.get(getContext()).contacts()) {
            if (q.isEmpty() || (k.name != null && k.name.toLowerCase(Locale.getDefault()).contains(q))
                    || (!qd.isEmpty() && PhoneUtil.digits(k.number).contains(qd))) shown.add(k);
        }
        adapter.notifyDataSetChanged();
        boolean perm = Store.canReadContacts(getContext());
        emptyBox.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
        grant.setVisibility(perm ? GONE : VISIBLE);
        empty.setText(!perm ? "Contacts permission is needed to show your phone contacts"
                : (Store.get(getContext()).isSyncing() ? "Syncing contacts…" : (q.isEmpty() ? "No contacts with phone numbers" : "No matches")));
    }

    private void options(final Store.Contact k) {
        final Context c = getContext();
        final String[] o = {"Call " + PhoneUtil.pretty(k.number), "Copy number", "Open in Contacts"};
        new android.app.AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert).setTitle(k.name)
                .setItems(o, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) act.dial(k.number);
                        else if (w == 1) {
                            ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE))
                                    .setPrimaryClip(ClipData.newPlainText("number", k.number));
                            Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
                        } else {
                            try {
                                Uri u = ContactsContract.Contacts.getLookupUri(k.cid, k.lookup);
                                act.startActivity(new Intent(Intent.ACTION_VIEW, u));
                            } catch (Exception e) { Toast.makeText(c, "Can't open contact", Toast.LENGTH_SHORT).show(); }
                        }
                    }
                }).show();
    }

    private final class Adapter extends BaseAdapter implements SectionIndexer {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            ListRow r = v instanceof ListRow ? (ListRow) v : new ListRow(p.getContext());
            Store.Contact k = shown.get(i);
            r.bind(k.name, k.name, PhoneUtil.pretty(k.number), 0, 0, k.starred ? "★" : "", k.photo, Ui.TEXT);
            return r;
        }
        private String[] sections = new String[0];
        private final List<Integer> starts = new ArrayList<>();
        @Override public void notifyDataSetChanged() {
            List<String> s = new ArrayList<>();
            starts.clear();
            for (int i = 0; i < shown.size(); i++) {
                String n = shown.get(i).name;
                String l = (n == null || n.isEmpty() || !Character.isLetter(n.charAt(0))) ? "#" : n.substring(0, 1).toUpperCase();
                if (s.isEmpty() || !s.get(s.size() - 1).equals(l)) { s.add(l); starts.add(i); }
            }
            sections = s.toArray(new String[0]);
            super.notifyDataSetChanged();
        }
        @Override public Object[] getSections() { return sections; }
        @Override public int getPositionForSection(int sec) { return sec >= 0 && sec < starts.size() ? starts.get(sec) : 0; }
        @Override public int getSectionForPosition(int pos) {
            int r = 0;
            for (int i = 0; i < starts.size(); i++) if (starts.get(i) <= pos) r = i;
            return r;
        }
    }

    private final Adapter adapter = new Adapter();
}
