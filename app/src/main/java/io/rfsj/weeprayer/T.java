package io.rfsj.weeprayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class T extends BroadcastReceiver {
    @Override public void onReceive(final Context c, Intent i) {
        if (M.EVT.equals(i.getAction())) {
            int k = i.getIntExtra("k", 2);
            if (k == 2) { // monthly refresh (or retry)
                final PendingResult r = goAsync();
                new Thread(() -> {
                    try { M.refresh(c); M.schedule(c); } finally { r.finish(); }
                }).start();
                return;
            }
            String n = i.getStringExtra("n");
            long target = i.getLongExtra("t", 0), now = System.currentTimeMillis();
            boolean notif = M.on(c, "nt", false);
            if (k == 0 && M.on(c, "tp", true)) { // prayer timer: runs until the prayer
                long secs = (target - now) / 1000;
                if (secs >= 10) {
                    if (notif) M.remind(c, 100, n, "prayer at " + M.fmt(c, P.hm(target)), target);
                    else M.timer(c, n, (int) secs);
                }
            } else if (k == 1 && M.on(c, "ti", true)) { // iqama timer: 10 minutes from the azaan
                if (notif) M.remind(c, 101, n + " iqama", "10 min from azaan", now + 600000);
                else M.timer(c, n + " +10", 600);
            }
        }
        M.schedule(c); // boot, time change, or just fired: set the next alarm
    }
}
