package io.rfsj.weeprayer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure logic (no Android classes): read the monthly table, find the next event. */
final class P {
    static final String[] N = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final ZoneId Z = ZoneId.of("Asia/Dubai");
    static final long PRE = 20 * 60000L;


    private static final Pattern LINE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(,\\d{2}:\\d{2}){5}");

    /**
     * Reads the file the workflow publishes: one line per day, "yyyy-MM-dd,fajr,dhuhr,asr,maghrib,isha"
     * (24h). Null unless it holds exactly the days of ym.
     */
    static List<String> load(String text, YearMonth ym) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            l = l.trim();
            if (l.isEmpty()) continue;
            if (!LINE.matcher(l).matches() || !l.startsWith(ym.toString())) return null;
            out.add(l);
        }
        return out.size() == ym.lengthOfMonth() ? out : null;
    }

    /** The stored line of day d split into {date, fajr, dhuhr, asr, maghrib, isha}, or null. */
    static String[] row(List<String> lines, LocalDate d) {
        String k = d.toString();
        for (String l : lines) if (l.startsWith(k)) return l.split(",");
        return null;
    }

    static long at(LocalDate d, String hhmm) {
        return d.atTime(LocalTime.parse(hhmm)).atZone(Z).toInstant().toEpochMilli();
    }

    static String hm(long ms) {
        return Instant.ofEpochMilli(ms).atZone(Z).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String p2(long x) { return x < 10 ? "0" + x : "" + x; }

    /** Time left with seconds: "1h 12m 05s", "12m 05s", "5s". */
    static String left(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = s / 60 % 60;
        if (h > 0) return h + "h " + p2(m) + "m " + p2(s % 60) + "s";
        if (m > 0) return m + "m " + p2(s % 60) + "s";
        return s + "s";
    }

    /** Prayer name; Dhuhr is Jumu'ah on Fridays. */
    static String name(int i, LocalDate d) {
        return i == 1 && d.getDayOfWeek() == java.time.DayOfWeek.FRIDAY ? "Jumu'ah" : N[i];
    }

    static String nameAt(int i, long ms) {
        return name(i, Instant.ofEpochMilli(ms).atZone(Z).toLocalDate());
    }

    /**
     * Start of the last third of the night (maghrib to fajr) for the night that is under way or
     * next. Uses neighbouring days when stored, else today's times. 0 if today is unknown.
     */
    static long lastThird(List<String> lines, long now) {
        LocalDate d = Instant.ofEpochMilli(now).atZone(Z).toLocalDate();
        String[] y = row(lines, d.minusDays(1)), t = row(lines, d), n = row(lines, d.plusDays(1));
        if (t == null) return 0;
        long fajr = at(d, t[1]), m, f;
        if (now < fajr) { // after midnight: the night began yesterday
            m = at(d.minusDays(1), (y != null ? y : t)[4]);
            f = fajr;
        } else {
            m = at(d, t[4]);
            f = at(d.plusDays(1), (n != null ? n : t)[1]);
        }
        return m + (f - m) * 2 / 3;
    }

    /**
     * Display state for today: {current prayer (the last one started; Isha before Fajr),
     * next prayer index (tomorrow's Fajr after Isha, -1 if unknown), its time}, or null.
     */
    static long[] state(List<String> lines, long now) {
        LocalDate d = Instant.ofEpochMilli(now).atZone(Z).toLocalDate();
        String[] r = row(lines, d);
        if (r == null) return null;
        int cur = 4, nx = -1;
        long nt = 0;
        for (int i = 0; i < 5; i++) {
            long t = at(d, r[i + 1]);
            if (t <= now) cur = i;
            else if (nx < 0) { nx = i; nt = t; }
        }
        if (nx < 0) {
            String[] n = row(lines, d.plusDays(1));
            if (n != null) { nx = 0; nt = at(d.plusDays(1), n[1]); }
        }
        return new long[]{cur, nx, nt};
    }

    /** Next event after now: {fireAt, kind (0 = 20 min before, 1 = azaan, 3 = new day), prayer index, prayer time}, or null. */
    static long[] next(List<String> lines, long now) {
        for (String l : lines) {
            String[] f = l.split(",");
            LocalDate d = LocalDate.parse(f[0]);
            long mid = d.atStartOfDay(Z).toInstant().toEpochMilli() + 5000; // new day: refresh widgets
            if (mid > now) return new long[]{mid, 3, 0, 0};
            for (int i = 0; i < 5; i++) {
                long t = at(d, f[i + 1]);
                if (t - PRE > now) return new long[]{t - PRE, 0, i, t};
                if (t > now) return new long[]{t, 1, i, t};
            }
        }
        return null;
    }
}
