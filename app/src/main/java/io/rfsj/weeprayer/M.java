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
import android.view.Gravity;
import android.view.View;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

    TextView t, st;
    Button ov, bat;
    View mainV, confV;
    boolean inConf;

    // ---------- storage ----------
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("p", 0); }

    static boolean on(Context c, String k, boolean def) { return sp(c).getBoolean(k, def); }

    /** Minutes before the azaan that the countdown starts (1-180, default 20). */
    static long pre(Context c) { return Math.max(1, Math.min(180, sp(c).getInt("lead", 20))) * 60000L; }

    /** Minutes the reminder keeps going after the azaan (0-180, default 10). */
    static int post(Context c) { return Math.max(0, Math.min(180, sp(c).getInt("post", 10))); }

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
    static void arm(Context c, long at, int kind, String name, long target) {
        Intent i = new Intent(c, T.class).setAction(EVT).putExtra("k", kind).putExtra("n", name).putExtra("t", target);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static void schedule(Context c) {
        long now = System.currentTimeMillis();
        long[] e = fresh(c) ? P.next(lines(c), now, pre(c)) : null;
        if (e != null) arm(c, e[0], (int) e[1], P.nameAt((int) e[2], e[3]), e[3]);
        else if (fresh(c)) // month finished: refresh just after midnight on the 1st
            arm(c, ym().plusMonths(1).atDay(1).atTime(0, 10).atZone(P.Z).toInstant().toEpochMilli(), 2, "", 0);
        else arm(c, now + 3600_000L, 2, "", 0); // no data for this month: try again in an hour
    }

    /** After anything changed: set the next alarm and redraw the widgets. */
    static void after(Context c) {
        schedule(c);
        W.all(c);
        keep(c);
    }

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

    // ---------- Clock app timer ----------
    static void timer(Context c, String label, int secs) {
        String err = "";
        try {
            c.startActivity(new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { err = "timer: " + e; }
        sp(c).edit().putString("te", err).putLong("tl", System.currentTimeMillis()).apply();
    }

    // ---------- notification instead of a Clock timer ----------
    static void remind(Context c, int id, String title, String text, long target) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rem", "Prayer reminders", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(c, "rem")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(target)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(Math.max(1000, target - System.currentTimeMillis()))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, new Intent(c, M.class), PendingIntent.FLAG_IMMUTABLE));
        nm.notify(id, b.build());
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

        // config screen
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackgroundColor(0xFF000000);
        f.setPadding(48, 120, 48, 48);
        f.addView(btn("< back", v -> back()));
        f.addView(chk("h12", false, "12-hour time"));
        f.addView(chk("l3", false, "show last third of the night"));
        f.addView(chk("tp", true, "prayer timer (countdown before azaan)"));
        f.addView(chk("ti", true, "iqama timer (after azaan)"));
        f.addView(num("lead", 20, "start countdown (min before azaan)"));
        f.addView(num("post", 10, "keep going after azaan (min)"));
        f.addView(chk("nt", false, "notification instead of timer"));
        f.addView(chk("ka", false, "keep-alive notification (own channel, can be hidden)"));
        f.addView(lab("widget (12-hour time above applies too)"));
        f.addView(chk("wl3", false, "show last third of the night"));
        f.addView(chk("wcd", true, "show countdown to the next prayer"));
        f.addView(lab("tools"));
        f.addView(btn("refresh now", v -> new Thread(() -> { refresh(this); after(this); runOnUiThread(this::draw); }).start()));
        f.addView(btn("test timer in 10 s (go to home screen)", v -> {
            long n = System.currentTimeMillis();
            arm(this, n + 10000, 0, "Test", n + 40000); // fires from the background, 30 s timer
        }));
        bat = btn("", v -> startActivity(ignoring()
                ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        f.addView(bat);
        f.addView(btn("keep-alive notification settings", v -> {
            keepChannel(this);
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "keep"));
        }));
        ov = btn("allow display over other apps", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        f.addView(ov);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF000000);
        sv.addView(f);
        confV = sv;

        setContentView(mainV);
        new Thread(() -> {
            if (!fresh(this)) refresh(this);
            after(this);
            runOnUiThread(this::draw);
        }).start();
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

    CheckBox chk(final String key, boolean def, String label) {
        CheckBox b = new CheckBox(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setChecked(on(this, key, def));
        b.setOnCheckedChangeListener((v, isOn) -> {
            sp(this).edit().putBoolean(key, isOn).apply();
            if (isOn && (key.equals("nt") || key.equals("ka"))) askNotif();
            if (key.equals("ka")) keep(this);
            W.all(this);
        });
        return b;
    }

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

    TextView lab(String s) {
        TextView x = new TextView(this);
        x.setText(s);
        x.setTextColor(0xFF666666);
        x.setPadding(0, 48, 0, 8);
        return x;
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
        ov.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
    }
}
