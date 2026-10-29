package io.rfsj.weeprayer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground service whose only job is its quiet notification: it makes Android less eager to kill the app. */
public class S extends Service {
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        M.keepChannel(this);
        Notification.Builder b = new Notification.Builder(this, "keep")
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, M.class), PendingIntent.FLAG_IMMUTABLE));
        long[] s = P.state(M.lines(this), System.currentTimeMillis());
        if (s != null && s[1] >= 0) {
            b.setContentTitle(P.nameAt((int) s[1], s[2]) + "  " + M.fmt(this, P.hm(s[2])))
                    .setContentText("next prayer")
                    .setWhen(s[2]).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        } else {
            b.setContentTitle("Prayer reminders running");
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, b.build());
        return START_STICKY;
    }
}
