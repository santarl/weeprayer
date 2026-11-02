package io.rfsj.weeprayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;

/**
 * One free-size widget in the app's own text style. What it shows depends on its size: a card (name, time, countdown)
 * for the small ones, then the next 2, 3, 4 and finally all 5 prayers as in the app. A countdown with seconds is a
 * Chronometer that the system ticks by itself and needs about 2 cells of width; narrower ones show minutes only,
 * redrawn once a minute (see M.minute).
 */
public class W extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager am, int[] ids) { all(c); }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager am, int id, Bundle o) { all(c); }

    @Override public void onDisabled(Context c) { M.minute(c, false); }

    /** Redraws every widget and keeps the once-a-minute redraw on only while one of them needs it. */
    static void all(Context c) { M.minute(c, render(c)); }

    /** Draws every widget; true if one of them shows minutes only. */
    static boolean render(Context c) {
        M.loc(c);
        AppWidgetManager am = AppWidgetManager.getInstance(c);
        int[] ids = am.getAppWidgetIds(new ComponentName(c, W.class));
        if (ids.length == 0) return false;
        long now = System.currentTimeMillis();
        List<String> l = M.lines(c);
        boolean port = c.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        boolean minute = false;
        for (int id : ids) {
            Bundle o = am.getAppWidgetOptions(id);
            int w = o.getInt(port ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
            int h = o.getInt(port ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            boolean[] m = new boolean[1];
            am.updateAppWidget(id, view(c, l, now, w > 0 ? w : 110, h > 0 ? h : 60, m));
            minute |= m[0];
        }
        return minute;
    }

    static RemoteViews view(Context c, List<String> l, long now, int w, int h, boolean[] minute) {
        String pkg = c.getPackageName();
        RemoteViews v = new RemoteViews(pkg, R.layout.w_root);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, new Intent(c, M.class), PendingIntent.FLAG_IMMUTABLE));
        v.removeAllViews(R.id.rows);
        boolean cd = M.on(c, "wcd", true), secs = w >= 110;
        float avail = Math.max(20, w - 16);
        int[] fit = P.fit(w, h, cd);
        int lines = fit[1];
        List<String[]> e = P.upcoming(l, now, fit[0]);
        if (e.isEmpty()) {
            text(v, pkg, R.layout.w_tc, "open the app to load the times", Math.max(8f, Math.min(12f, avail / 18f)));
            return v;
        }
        if (e.size() == 1) { // a card: name, time, countdown
            String[] x = e.get(0);
            long t = Long.parseLong(x[2]);
            boolean next = cd && x[3].equals("1");
            String time = M.fmt(c, x[1]);
            int rows = lines >= 3 ? (next ? 3 : 2) : (next ? 2 : 1);
            float sz = Math.max(8f, Math.min(18f, Math.min(avail / 5.6f, (h - 16) / (rows * 1.3f))));
            if (lines >= 3) {
                text(v, pkg, R.layout.w_big, x[0], sz);
                text(v, pkg, R.layout.w_tc, time, sz);
            } else text(v, pkg, R.layout.w_big, x[0] + " " + time, sz);
            if (next) {
                if (secs) chrono(v, pkg, R.layout.w_cc, t, now, sz);
                else {
                    text(v, pkg, R.layout.w_tc, "(" + P.leftMin(t - now) + ")", sz);
                    minute[0] = true;
                }
            }
            return v;
        }
        float sz = P.lineSize(w); // a list as in the app: the next prayer is marked, its countdown right-aligned under it
        for (String[] x : e) {
            boolean next = x[3].equals("1");
            text(v, pkg, R.layout.w_t, String.format(Locale.ROOT, "%s%-8s%8s", next ? "> " : "  ", x[0], M.fmt(c, x[1])), sz);
            if (next && cd) chrono(v, pkg, R.layout.w_c, Long.parseLong(x[2]), now, sz);
        }
        if (M.on(c, "wl3", false) && lines >= e.size() + (cd ? 1 : 0) + 1) {
            long q = P.lastThird(l, now);
            if (q != 0) text(v, pkg, R.layout.w_t, String.format(Locale.ROOT, "  %-8s%8s", "Last 1/3", M.fmt(c, P.hm(q))), sz);
        }
        return v;
    }

    private static void text(RemoteViews v, String pkg, int layout, String s, float sz) {
        RemoteViews r = new RemoteViews(pkg, layout);
        r.setTextViewText(R.id.tx, s);
        r.setTextViewTextSize(R.id.tx, TypedValue.COMPLEX_UNIT_SP, sz);
        v.addView(R.id.rows, r);
    }

    /** "(1:12:05)", ticking down by itself in the launcher: nothing has to be re-posted every second. */
    private static void chrono(RemoteViews v, String pkg, int layout, long t, long now, float sz) {
        RemoteViews r = new RemoteViews(pkg, layout);
        r.setChronometer(R.id.ch, SystemClock.elapsedRealtime() + (t - now), "(%s)", true);
        r.setChronometerCountDown(R.id.ch, true);
        r.setTextViewTextSize(R.id.ch, TypedValue.COMPLEX_UNIT_SP, sz);
        v.addView(R.id.rows, r);
    }
}
