package io.rfsj.weeprayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class T extends BroadcastReceiver {
    @Override public void onReceive(final Context c, Intent i) {
        String a = i.getAction();
        if (M.DISMISS.equals(a)) { M.dismissTimers(c); return; }
        long key = 0; // the event just handled: the next alarm is the first one after it, even if this one was late
        if (M.EVT.equals(a)) {
            int k = i.getIntExtra("k", 2);
            if (k == 2) { // monthly refresh (or retry)
                final PendingResult r = goAsync();
                new Thread(() -> {
                    try { M.refresh(c); M.after(c); } finally { r.finish(); }
                }).start();
                return;
            }
            String n = i.getStringExtra("n");
            int p = i.getIntExtra("p", 0);
            boolean third = p == 5; // the last third of the night, not a prayer
            long target = i.getLongExtra("t", 0), now = System.currentTimeMillis();
            key = P.key(i.getLongExtra("e", now), k);
            if (k == 0) { // the countdown starts
                long left = target - now;
                if (left >= 10000) {
                    if (M.on(c, "tp", true)) M.timer(c, n, (int) (left / 1000), 1);
                    if (M.on(c, "np", true))
                        M.note(c, n, (third ? "starts at " : "azaan at ") + M.fmt(c, P.hm(target)), target, true, left + 60000, M.col(p));
                }
            } else if (k == 1) { // the azaan: the countdown notification becomes the iqamah stopwatch, or goes away
                if (!third && M.on(c, "ti", true))
                    M.note(c, "Iqamah", n, target, false, target + M.post(c) * 60000L - now, M.col(p));
                else M.cancelNote(c);
            } else if (k == 6) { // Do Not Disturb on
                M.dndOn(c, target + M.val(c, "dnda", 10, 1, 120) * 60000L);
            } else if (k == 7) { // Do Not Disturb off
                M.dndOff(c);
            }
        }
        if (key != 0) M.after(c, key); // boot, time change, or just handled: set the next alarm
        else M.after(c);
    }
}
