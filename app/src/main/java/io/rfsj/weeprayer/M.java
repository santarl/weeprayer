package io.rfsj.weeprayer;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class M extends Activity {
    // published by the "times" workflow (see scripts/scrape.py); the repo must be public
    static final String URL = "https://raw.githubusercontent.com/santarl/weeprayer/data/dubai.txt";
    static final String EVT = "io.rfsj.weeprayer.EVT";
    static final String DISMISS = "io.rfsj.weeprayer.DISMISS";
    static final int NOTE = 100; // the countdown / iqamah notification (one, replaced in place)

    /** One colour per prayer (Fajr, Dhuhr/Jumu'ah, Asr, Maghrib, Isha) and for the last third of the night. */
    static final int[] COL = {0xFF7E57C2, 0xFFFFCA28, 0xFFFF9800, 0xFFEF5350, 0xFF42A5F5, 0xFF26A69A};

    TextView t, st;
    Button ov, bat, dndPerm, kaBtn;
    View mainV, confV, leadRow, dtBox, postRow, dndbRow, dndaRow;
    boolean inConf;

    // ---------- storage ----------
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("p", 0); }

    static boolean on(Context c, String k, boolean def) { return sp(c).getBoolean(k, def); }

    /** A number setting kept inside [lo, hi]. */
    static int val(Context c, String k, int def, int lo, int hi) { return Math.max(lo, Math.min(hi, sp(c).getInt(k, def))); }

    /** How long before the azaan the countdown starts. */
    static long pre(Context c) { return val(c, "lead", 20, 1, 180) * 60000L; }

    /** How long the iqamah stopwatch stays after the azaan, in minutes. */
    static int post(Context c) { return val(c, "post", 10, 1, 180); }

    static int col(int idx) { return COL[Math.max(0, Math.min(5, idx))]; }

    static YearMonth ym() { return YearMonth.now(P.Z); }

    static List<String> lines(Context c) {
        String s = sp(c).getString("d", "");
        return s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split("\n"));
    }

    static boolean fresh(Context c) { return sp(c).getString("m", "").equals(ym().toString()); }

    // ---------- network ----------
    static String get() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    /** Fetch the workflow's file and store this month. Records the outcome in prefs "e" (empty = ok). */
    static void refresh(Context c) {
        String err = "";
        try {
            List<String> l = P.load(get(), ym());
            if (l == null) err = "no data for " + ym() + " yet";
            else sp(c).edit().putString("d", String.join("\n", l)).putString("m", ym().toString())
                    .putLong("f", System.currentTimeMillis()).apply();
        } catch (Exception e) { err = e.toString(); }
        sp(c).edit().putString("e", err).apply();
    }

    // ---------- scheduling: one alarm at a time, each firing sets the next ----------
    static void arm(Context c, long at, int kind, String name, long target, int idx) {
        Intent i = new Intent(c, T.class).setAction(EVT).putExtra("k", kind).putExtra("n", name)
                .putExtra("t", target).putExtra("p", idx).putExtra("e", at);
        set(c, at, PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    static void set(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** Arms the first event after `afterKey` (an event's time*8+kind; a late alarm never loses the next one). */
    static void schedule(Context c, long afterKey) {
        long now = System.currentTimeMillis();
        boolean dnd = on(c, "dnd", false);
        long b = dnd ? val(c, "dndb", 5, 1, 120) * 60000L : 0, a = dnd ? val(c, "dnda", 10, 1, 120) * 60000L : 0;
        long[] e = fresh(c) ? P.next(lines(c), afterKey, pre(c), on(c, "l3r", false), b, a) : null;
        if (e != null) arm(c, e[0], (int) e[1], e[2] == 5 ? "Last third" : P.nameAt((int) e[2], e[3]), e[3], (int) e[2]);
        else if (fresh(c)) // month finished: refresh just after midnight on the 1st
            arm(c, ym().plusMonths(1).atDay(1).atTime(0, 10).atZone(P.Z).toInstant().toEpochMilli(), 2, "", 0, 0);
        else arm(c, now + 3600_000L, 2, "", 0, 0); // no data for this month: try again in an hour
    }

    /** After anything changed: next alarm, Do Not Disturb safety check, widgets, keep-alive. */
    static void after(Context c, long afterKey) {
        schedule(c, afterKey);
        dndOff(c); // does nothing unless a Do Not Disturb window has ended
        W.all(c);
        keep(c);
        c.getSystemService(NotificationManager.class).deleteNotificationChannel("live"); // from an earlier version
    }

    static void after(Context c) { after(c, System.currentTimeMillis() * 8 + 7); }

    // ---------- keep-alive foreground notification (own channel; hide it in Android's settings) ----------
    static void keepChannel(Context c) {
        c.getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel("keep", "Keep-alive (hide this one)", NotificationManager.IMPORTANCE_MIN));
    }

    static void keep(Context c) {
        try {
            Intent i = new Intent(c, S.class);
            if (on(c, "ka", false)) c.startForegroundService(i);
            else c.stopService(i);
        } catch (Exception e) { sp(c).edit().putString("te", "keep-alive: " + e).apply(); }
    }

    // ---------- Clock app timer, and dismissing it when it runs out ----------
    static void timer(Context c, String label, int secs, int slot) {
        String err = "";
        try {
            c.startActivity(new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { err = "timer: " + e; }
        sp(c).edit().putString("te", err).putLong("tl", System.currentTimeMillis()).apply();
        // it rings when it runs out: clear it just after that instead of waiting for a tap
        if (err.isEmpty() && on(c, "dt", true)) armDismiss(c, slot, System.currentTimeMillis() + secs * 1000L + 2500);
    }

    /** A separate alarm that clears the expired timer. */
    static void armDismiss(Context c, int slot, long at) {
        set(c, at, PendingIntent.getBroadcast(c, slot, new Intent(c, T.class).setAction(DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    /** Asks the Clock app to dismiss timers that have run out (not every Clock app supports it). */
    static void dismissTimers(Context c) {
        String err = "";
        try {
            c.startActivity(new Intent(AlarmClock.ACTION_DISMISS_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { err = "dismiss: " + e; }
        sp(c).edit().putString("te", err).apply();
    }

    // ---------- the countdown / iqamah notification ----------
    /** One ongoing notification, replaced in place: a chronometer to `when`, counting down or up, in the prayer's colour. */
    static void note(Context c, String title, String text, long when, boolean down, long timeoutMs, int color) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rem", "Prayer reminders", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(c, "rem")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(color)
                .setColorized(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(when)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(down)
                .setTimeoutAfter(Math.max(1000, timeoutMs))
                .setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, M.class), PendingIntent.FLAG_IMMUTABLE));
        nm.notify(NOTE, b.build());
    }

    static void cancelNote(Context c) { c.getSystemService(NotificationManager.class).cancel(NOTE); }

    // ---------- Do Not Disturb around the prayers ----------
    /** Turns Do Not Disturb on (unless the user already has some mode on) until at least `until`. */
    static void dndOn(Context c, long until) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!on(c, "dnd", false) || !nm.isNotificationPolicyAccessGranted()) return;
        SharedPreferences p = sp(c);
        if (!p.getBoolean("dndon", false)) {
            if (nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) return; // their own mode: leave it
            p.edit().putBoolean("dndon", true).apply();
        }
        p.edit().putLong("dnduntil", Math.max(p.getLong("dnduntil", 0), until)).apply();
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    /** Turns it off again, unless a later prayer's window is still running. */
    static void dndOff(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("dndon", false) || System.currentTimeMillis() < p.getLong("dnduntil", 0) - 1000) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        p.edit().putBoolean("dndon", false).apply();
    }

    /** Widget time: like the app's, but 12h without AM/PM and with a leading zero. */
    static String fw(Context c, String hm) {
        if (!on(c, "h12", false)) return hm;
        int h = Integer.parseInt(hm.substring(0, 2)) % 12;
        return (h == 0 ? "12" : h < 10 ? "0" + h : "" + h) + hm.substring(2);
    }

    static String fmt(Context c, String hm) {
        if (!on(c, "h12", false)) return hm;
        int h = Integer.parseInt(hm.substring(0, 2));
        return (h % 12 == 0 ? 12 : h % 12) + hm.substring(2) + (h < 12 ? " AM" : " PM");
    }

    // ---------- UI ----------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        // main screen: times centred, "config" top left
        t = new TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(22);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        st = new TextView(this);
        st.setTextColor(0xFF666666);
        st.setTextSize(12);
        st.setPadding(0, 48, 0, 0);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(t);
        c.addView(st);
        TextView cf = new TextView(this);
        cf.setText("config");
        cf.setTextColor(0xFF666666);
        cf.setPadding(48, 120, 96, 48);
        cf.setOnClickListener(v -> { inConf = true; setContentView(confV); draw(); });
        FrameLayout m = new FrameLayout(this);
        m.setBackgroundColor(0xFF000000);
        m.addView(c, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        m.addView(cf, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        mainV = m;
        confV = config();

        setContentView(mainV);
        new Thread(() -> {
            if (!fresh(this)) refresh(this);
            after(this);
            runOnUiThread(this::draw);
        }).start();
    }

    /** The config screen: grouped by what the options do; options that need another one grey out without it. */
    View config() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackgroundColor(0xFF000000);
        f.setPadding(48, 120, 48, 96);
        f.addView(btn("< back", v -> back()));

        f.addView(head("Display"));
        f.addView(radios("h12", false, "24-hour time", "12-hour time"));
        f.addView(chk("l3", false, "show the last third of the night on the main screen"));

        f.addView(head("Countdown before the azaan"));
        f.addView(hint("Starts this long before each azaan and runs until it. Show it as:"));
        leadRow = num("lead", 20, "starts (min before the azaan)");
        f.addView(leadRow);
        f.addView(chk("tp", true, "a Clock timer"));
        dtBox = chk("dt", true, "dismiss the timer when it ends");
        sub(f, dtBox);
        f.addView(chk("np", true, "a notification (a colour for each prayer)"));
        f.addView(chk("l3r", false, "also count down to the last third of the night"));

        f.addView(head("Iqamah (after the azaan)"));
        f.addView(chk("ti", true, "a stopwatch notification counting up from the azaan"));
        postRow = num("post", 10, "keep it for (min)");
        sub(f, postRow);

        f.addView(head("Do Not Disturb"));
        f.addView(chk("dnd", false, "turn it on around each prayer"));
        dndbRow = num("dndb", 5, "on (min before the azaan)");
        sub(f, dndbRow);
        dndaRow = num("dnda", 10, "off (min after the azaan)");
        sub(f, dndaRow);
        dndPerm = btn("", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        f.addView(dndPerm);

        f.addView(head("Widget"));
        f.addView(hint("Uses the time format above."));
        f.addView(chk("wcd", true, "show the countdown to the next prayer"));
        f.addView(chk("wl3", false, "show the last third of the night"));

        f.addView(head("Keeping it running"));
        bat = btn("", v -> startActivity(ignoring()
                ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        f.addView(bat);
        f.addView(chk("ka", false, "keep-alive notification (own channel, can be hidden)"));
        kaBtn = btn("keep-alive notification settings", v -> {
            keepChannel(this);
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "keep"));
        });
        sub(f, kaBtn);
        ov = btn("allow display over other apps (lets the Clock timer start)", v -> startActivity(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        f.addView(ov);

        f.addView(head("Tools"));
        f.addView(btn("refresh the times now", v -> new Thread(() -> { refresh(this); after(this); runOnUiThread(this::draw); }).start()));
        f.addView(btn("test the countdown in 10 s (then go to the home screen)", v -> {
            long n = System.currentTimeMillis();
            arm(this, n + 10000, 0, "Test", n + 40000, 2);
        }));
        f.addView(btn("test the iqamah stopwatch (1 min)", v -> {
            askNotif();
            note(this, "Iqamah", "Test", System.currentTimeMillis(), false, 60000, col(2));
        }));

        gates();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF000000);
        sv.addView(f);
        return sv;
    }

    /** Greys out (and disables) the options whose parent option is off. */
    void gates() {
        boolean tp = on(this, "tp", true), np = on(this, "np", true), d = on(this, "dnd", false);
        en(leadRow, tp || np);
        en(dtBox, tp);
        en(postRow, on(this, "ti", true));
        en(dndbRow, d);
        en(dndaRow, d);
        en(dndPerm, d);
        en(kaBtn, on(this, "ka", false));
    }

    static void en(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) en(((ViewGroup) v).getChildAt(i), on);
    }

    boolean ignoring() { return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()); }

    void back() { inConf = false; setContentView(mainV); draw(); after(this); }

    @Override public void onBackPressed() { if (inConf) back(); else super.onBackPressed(); }

    Button btn(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    /** A yes/no option. */
    CheckBox chk(final String key, boolean def, String label) {
        CheckBox b = new CheckBox(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setChecked(on(this, key, def));
        b.setOnCheckedChangeListener((v, isOn) -> {
            sp(this).edit().putBoolean(key, isOn).apply();
            if (isOn && (key.equals("np") || key.equals("ti") || key.equals("ka"))) askNotif();
            if (key.equals("ka")) keep(this);
            if (key.equals("dnd") && !isOn) { sp(this).edit().putLong("dnduntil", 0).apply(); dndOff(this); }
            gates();
            W.all(this);
        });
        return b;
    }

    /** An either/or choice (stored as a boolean: true = the second one). */
    RadioGroup radios(final String key, boolean def, String off, String onLabel) {
        RadioGroup g = new RadioGroup(this);
        final RadioButton a = new RadioButton(this), b = new RadioButton(this);
        a.setText(off);
        b.setText(onLabel);
        for (RadioButton r : new RadioButton[]{a, b}) {
            r.setTextColor(0xFFFFFFFF);
            r.setId(View.generateViewId());
            g.addView(r);
        }
        (on(this, key, def) ? b : a).setChecked(true);
        g.setOnCheckedChangeListener((grp, id) -> { sp(this).edit().putBoolean(key, id == b.getId()).apply(); W.all(this); });
        return g;
    }

    /** A number setting. */
    LinearLayout num(final String key, int def, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextColor(0xFFFFFFFF);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setEms(3);
        e.setTextColor(0xFFFFFFFF);
        e.setText(String.valueOf(sp(this).getInt(key, def)));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(Editable s) {
                try { sp(M.this).edit().putInt(key, Integer.parseInt(s.toString().trim())).apply(); }
                catch (NumberFormatException ex) { /* empty while typing */ }
            }
        });
        r.addView(lb, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(e);
        return r;
    }

    TextView head(String s) {
        TextView x = new TextView(this);
        x.setText(s);
        x.setTextColor(0xFF80CBC4);
        x.setTextSize(16);
        x.setPadding(0, 64, 0, 8);
        return x;
    }

    TextView hint(String s) {
        TextView x = new TextView(this);
        x.setText(s);
        x.setTextColor(0xFF888888);
        x.setTextSize(12);
        x.setPadding(0, 0, 0, 8);
        return x;
    }

    /** Adds an option that belongs to the one above it, indented. */
    void sub(LinearLayout f, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = 64;
        f.addView(v, lp);
    }

    void askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    final Runnable tick = new Runnable() {
        @Override public void run() { draw(); t.postDelayed(this, 1000); }
    };

    @Override protected void onResume() { super.onResume(); t.removeCallbacks(tick); t.post(tick); }

    @Override protected void onPause() { super.onPause(); t.removeCallbacks(tick); }

    String line(boolean next, String name, String hm, long target, long now) {
        String l = String.format(java.util.Locale.ROOT, "%s%-8s%8s", next ? "> " : "  ", name, fmt(this, hm));
        // the time left goes on its own line, right-aligned under the time
        return l + "\n" + (next ? String.format(java.util.Locale.ROOT, "%" + l.length() + "s", "(" + P.left(target - now) + ")") + "\n" : "");
    }

    void draw() {
        List<String> l = lines(this);
        long now = System.currentTimeMillis();
        LocalDate d = Instant.ofEpochMilli(now).atZone(P.Z).toLocalDate();
        String[] r = P.row(l, d);
        StringBuilder s = new StringBuilder();
        if (r != null) {
            int nx = 5;
            for (int i = 4; i >= 0; i--) if (P.at(d, r[i + 1]) > now) nx = i;
            for (int i = 0; i < 5; i++) s.append(line(i == nx, P.name(i, d), r[i + 1], P.at(d, r[i + 1]), now));
            String[] n = P.row(l, d.plusDays(1));
            if (nx == 5 && n != null) s.append(line(true, P.name(0, d.plusDays(1)), n[1], P.at(d.plusDays(1), n[1]), now));
            long q = P.lastThird(l, now);
            if (q != 0 && sp(this).getBoolean("l3", false)) s.append('\n').append(line(false, "Last 1/3", P.hm(q), q, now));
        } else s.append(fresh(this) ? "no times for today" : "no data for " + ym());
        t.setText(s.toString().replaceAll("\\s+$", ""));
        SharedPreferences p = sp(this);
        String e = p.getString("e", ""), te = p.getString("te", "");
        st.setText((e.isEmpty() ? "" : "fetch: " + e + "\n") + te);
        st.setVisibility(e.isEmpty() && te.isEmpty() ? View.GONE : View.VISIBLE);
        bat.setText(ignoring() ? "battery: unoptimized (tap to review)" : "set battery to unoptimized");
        dndPerm.setText(getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted()
                ? "Do Not Disturb access: allowed (tap to review)" : "allow Do Not Disturb access");
        ov.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
    }
}
