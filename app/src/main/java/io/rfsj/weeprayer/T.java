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
                    try { M.refresh(c); M.after(c); } finally { r.finish(); }
                }).start();
                return;
            }
            String n = i.getStringExtra("n");
            long target = i.getLongExtra("t", 0), now = System.currentTimeMillis();
            boolean notif = M.on(c, "nt", false), live = M.on(c, "lu", false);
            int post = M.post(c);
            long end = target + post * 60000L; // when the after-azaan part ends
            if (k == 1 && live) { // live update, second half: count up (or down) from the azaan, or end it
                if (M.on(c, "ti", true) && post > 0) {
                    boolean up = M.on(c, "cu", true);
                    M.live(c, n, up ? "since azaan" : "until iqama", up ? target : end, !up, end - now);
                } else M.cancelLive(c);
            } else if (k == 0 && M.on(c, "tp", true)) { // prayer timer: runs until the prayer
                long secs = (target - now) / 1000;
                if (secs >= 10) {
                    if (live) M.live(c, n, "azaan at " + M.fmt(c, P.hm(target)), target, true, end - now);
                    else if (notif) M.remind(c, 100, n, "prayer at " + M.fmt(c, P.hm(target)), target);
                    else M.timer(c, n, (int) secs);
                }
            } else if (k == 1 && M.on(c, "ti", true) && post > 0) { // iqama timer: `post` minutes from the azaan
                if (notif) M.remind(c, 101, n + " iqama", post + " min from azaan", end);
                else M.timer(c, n + " +" + post, post * 60);
            }
        }
        M.after(c); // boot, time change, or just fired: set the next alarm and redraw widgets
    }
}
