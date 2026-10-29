package io.rfsj.weeprayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Five-column widget; also holds the update code for both widgets (the compact one is N). */
public class W extends AppWidgetProvider {
    static final int[] IC = {R.drawable.i_fajr, R.drawable.i_dhuhr, R.drawable.i_asr, R.drawable.i_maghrib, R.drawable.i_isha};

    @Override public void onUpdate(Context c, AppWidgetManager am, int[] ids) { all(c); }

    static void all(Context c) {
        AppWidgetManager am = AppWidgetManager.getInstance(c);
        int[] a = am.getAppWidgetIds(new ComponentName(c, W.class));
        int[] b = am.getAppWidgetIds(new ComponentName(c, N.class));
        if (a.length + b.length == 0) return;
        long now = System.currentTimeMillis();
        List<String> l = M.lines(c);
        LocalDate d = Instant.ofEpochMilli(now).atZone(P.Z).toLocalDate();
        String[] r = P.row(l, d);
        long[] s = P.state(l, now);
        long q = M.on(c, "wl3", false) ? P.lastThird(l, now) : 0;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, M.class), PendingIntent.FLAG_IMMUTABLE);
        if (a.length > 0) am.updateAppWidget(a, row(c, r, d, s, q, now, open));
        if (b.length > 0) am.updateAppWidget(b, next(c, s, q, now, open));
    }

    static RemoteViews row(Context c, String[] r, LocalDate d, long[] s, long q, long now, PendingIntent open) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_row);
        v.setOnClickPendingIntent(R.id.root, open);
        v.removeAllViews(R.id.row);
        if (r == null || s == null) {
            v.setViewVisibility(R.id.ft, View.VISIBLE);
            v.setTextViewText(R.id.fl, "open the app to load the times");
            v.setViewVisibility(R.id.cd, View.GONE);
            v.setViewVisibility(R.id.f3, View.GONE);
            return v;
        }
        for (int i = 0; i < 5; i++) {
            RemoteViews k = new RemoteViews(c.getPackageName(), R.layout.w_col);
            k.setImageViewResource(R.id.ic, IC[i]);
            k.setTextViewText(R.id.nm, P.name(i, d));
            k.setTextViewText(R.id.tm, M.fw(c, r[i + 1]));
            if (i == s[0]) k.setInt(R.id.col, "setBackgroundResource", R.drawable.w_hi);
            v.addView(R.id.row, k);
        }
        boolean cd = M.on(c, "wcd", true) && s[1] >= 0;
        v.setViewVisibility(R.id.ft, cd || q != 0 ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.fl, cd ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.cd, cd ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.f3, q != 0 ? View.VISIBLE : View.GONE);
        if (cd) {
            v.setTextViewText(R.id.fl, P.nameAt((int) s[1], s[2]) + " in ");
            clock(v, R.id.cd, s[2], now);
        }
        if (q != 0) v.setTextViewText(R.id.f3, (cd ? "    " : "") + "Last 1/3 " + M.fw(c, P.hm(q)));
        return v;
    }

    static RemoteViews next(Context c, long[] s, long q, long now, PendingIntent open) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.w_next);
        v.setOnClickPendingIntent(R.id.root, open);
        if (s == null || s[1] < 0) {
            v.setTextViewText(R.id.nm, "open the app");
            v.setViewVisibility(R.id.cd, View.GONE);
            v.setViewVisibility(R.id.f3, View.GONE);
            return v;
        }
        int i = (int) s[1];
        v.setImageViewResource(R.id.ic, IC[i]);
        v.setTextViewText(R.id.nm, P.nameAt(i, s[2]) + "  " + M.fw(c, P.hm(s[2])));
        v.setViewVisibility(R.id.cd, View.VISIBLE);
        clock(v, R.id.cd, s[2], now);
        v.setViewVisibility(R.id.f3, q != 0 ? View.VISIBLE : View.GONE);
        if (q != 0) v.setTextViewText(R.id.f3, "Last 1/3 " + M.fw(c, P.hm(q)));
        return v;
    }

    /** Self-ticking countdown (shows seconds) that needs no updates from us. */
    static void clock(RemoteViews v, int id, long target, long now) {
        v.setChronometer(id, SystemClock.elapsedRealtime() + (target - now), null, true);
        v.setChronometerCountDown(id, true);
    }
}
