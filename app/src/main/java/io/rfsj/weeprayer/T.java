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
            // k 0: timer runs until the prayer; k 1: 10 minutes from the azaan
            long secs = k == 0 ? (i.getLongExtra("t", 0) - System.currentTimeMillis()) / 1000 : 600;
            if (secs >= 10) M.timer(c, k == 0 ? n : n + " +10", (int) secs);
        }
        M.schedule(c); // boot, time change, or just fired: set the next alarm
    }
}
