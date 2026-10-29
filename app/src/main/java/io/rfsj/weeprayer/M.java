package io.rfsj.weeprayer;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    static final String URL = "https://www.khaleejtimes.com/prayer-time-uae/dubai";
    static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    static final String EVT = "io.rfsj.weeprayer.EVT";

    TextView t, st;
    Button ov;
    View mainV, confV;
    boolean inConf;

    // ---------- storage ----------
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("p", 0); }

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
        c.setRequestProperty("User-Agent", UA);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    /** Fetch, parse and store this month. Records the outcome in prefs "e" (empty = ok). */
    static void refresh(Context c) {
        String err = "";
        try {
            List<String> l = P.parse(get(), ym());
            if (l == null) err = "page did not contain all days of " + ym();
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
        long[] e = fresh(c) ? P.next(lines(c), now) : null;
        if (e != null) arm(c, e[0], (int) e[1], P.nameAt((int) e[2], e[3]), e[3]);
        else if (fresh(c)) // month finished: refresh just after midnight on the 1st
            arm(c, ym().plusMonths(1).atDay(1).atTime(0, 10).atZone(P.Z).toInstant().toEpochMilli(), 2, "", 0);
        else arm(c, now + 3600_000L, 2, "", 0); // no data for this month: try again in an hour
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
        f.addView(tog("h12", false, "time format", "12h", "24h"));
        f.addView(tog("l3", false, "last third of the night", "shown", "hidden"));
        f.addView(btn("refresh now", v -> new Thread(() -> { refresh(this); schedule(this); runOnUiThread(this::draw); }).start()));
        f.addView(btn("test timer in 10 s (go to home screen)", v -> {
            long n = System.currentTimeMillis();
            arm(this, n + 10000, 0, "Test", n + 40000); // fires from the background, 30 s timer
        }));
        ov = btn("allow display over other apps", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        f.addView(ov);
        confV = f;

        setContentView(mainV);
        new Thread(() -> {
            if (!fresh(this)) refresh(this);
            schedule(this);
            runOnUiThread(this::draw);
        }).start();
    }

    void back() { inConf = false; setContentView(mainV); draw(); }

    @Override public void onBackPressed() { if (inConf) back(); else super.onBackPressed(); }

    Button btn(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    Button tog(final String key, final boolean def, final String label, final String on, final String off) {
        final Button b = btn("", null);
        final Runnable sync = () -> b.setText(label + ": " + (sp(this).getBoolean(key, def) ? on : off));
        sync.run();
        b.setOnClickListener(v -> { sp(this).edit().putBoolean(key, !sp(this).getBoolean(key, def)).apply(); sync.run(); });
        return b;
    }

    final Runnable tick = new Runnable() {
        @Override public void run() { draw(); t.postDelayed(this, 1000); }
    };

    @Override protected void onResume() { super.onResume(); t.removeCallbacks(tick); t.post(tick); }

    @Override protected void onPause() { super.onPause(); t.removeCallbacks(tick); }

    String fmt(String hm) {
        if (!sp(this).getBoolean("h12", false)) return hm;
        int h = Integer.parseInt(hm.substring(0, 2));
        return (h % 12 == 0 ? 12 : h % 12) + hm.substring(2) + (h < 12 ? " AM" : " PM");
    }

    String line(boolean next, String name, String hm, long target, long now) {
        return String.format(java.util.Locale.ROOT, "%s%-8s%8s", next ? "> " : "  ", name, fmt(hm))
                + (next ? "  (" + P.left(target - now) + ")" : "") + "\n";
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
        ov.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
    }
}
