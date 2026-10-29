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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class M extends Activity {
    static final String URL = "https://www.khaleejtimes.com/prayer-time-uae/dubai";
    static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    static final String EVT = "io.rfsj.weeprayer.EVT";

    TextView t;
    Button ov;

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
        if (e != null) arm(c, e[0], (int) e[1], P.N[(int) e[2]], e[3]);
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

        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackgroundColor(0xFF000000);
        f.setPadding(48, 96, 48, 48);

        t = new TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(22);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        f.addView(t);

        f.addView(btn("refresh now", v -> new Thread(() -> { refresh(this); schedule(this); runOnUiThread(this::draw); }).start()));
        f.addView(btn("test timer in 10 s (go to home screen)", v -> {
            long n = System.currentTimeMillis();
            arm(this, n + 10000, 0, "Test", n + 40000); // fires from the background, 30 s timer
            draw();
        }));
        ov = btn("allow display over other apps", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        f.addView(ov);
        setContentView(f);

        new Thread(() -> {
            if (!fresh(this)) refresh(this);
            schedule(this);
            runOnUiThread(this::draw);
        }).start();
    }

    Button btn(String s, android.view.View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    @Override protected void onResume() { super.onResume(); draw(); }

    void draw() {
        StringBuilder s = new StringBuilder();
        String today = LocalDate.now(P.Z).toString();
        for (String l : lines(this)) {
            if (!l.startsWith(today)) continue;
            String[] f = l.split(",");
            for (int i = 0; i < 5; i++) s.append(String.format("%-8s%s\n", P.N[i], f[i + 1]));
        }
        SharedPreferences p = sp(this);
        s.append('\n').append(fresh(this) ? "data: " + ym() : "no data for " + ym());
        if (!p.getString("e", "").isEmpty()) s.append("\nfetch: ").append(p.getString("e", ""));
        if (!p.getString("te", "").isEmpty()) s.append("\n").append(p.getString("te", ""));
        t.setText(s);
        ov.setVisibility(Settings.canDrawOverlays(this) ? android.view.View.GONE : android.view.View.VISIBLE);
    }
}
