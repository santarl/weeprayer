package io.rfsj.weeprayer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure logic (no Android classes): parse the monthly table, find the next event. */
final class P {
    static final String[] N = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final ZoneId Z = ZoneId.of("Asia/Dubai");
    static final long PRE = 20 * 60000L;

    private static final String MON = "JanFebMarAprMayJunJulAugSepOctNovDec";
    private static final Pattern D = Pattern.compile("\\b(\\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Pattern T = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\s?([AaPp][Mm])");

    /**
     * Markup-agnostic: strip tags, then for every "DD Mon" take the first six times after it
     * (fajr, sunrise, dhuhr, asr, maghrib, isha). Returns one line per day,
     * "yyyy-MM-dd,fajr,dhuhr,asr,maghrib,isha" (24h), or null unless every day of ym was found.
     */
    static List<String> parse(String html, YearMonth ym) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ");
        List<int[]> pos = new ArrayList<>(); // start, end, day, month
        Matcher d = D.matcher(s);
        while (d.find()) pos.add(new int[]{d.start(), d.end(), Integer.parseInt(d.group(1)), MON.indexOf(d.group(2)) / 3 + 1});
        TreeMap<Integer, String> days = new TreeMap<>();
        for (int i = 0; i < pos.size(); i++) {
            int[] p = pos.get(i);
            if (p[3] != ym.getMonthValue() || p[2] < 1 || p[2] > ym.lengthOfMonth() || days.containsKey(p[2])) continue;
            String seg = s.substring(p[1], i + 1 < pos.size() ? pos.get(i + 1)[0] : s.length());
            Matcher t = T.matcher(seg);
            String[] v = new String[6];
            int n = 0;
            while (n < 6 && t.find()) {
                int h = Integer.parseInt(t.group(1)) % 12 + (t.group(3).equalsIgnoreCase("PM") ? 12 : 0);
                v[n++] = String.format(java.util.Locale.ROOT, "%02d:%s", h, t.group(2));
            }
            if (n < 6) continue;
            days.put(p[2], ym.atDay(p[2]) + "," + v[0] + "," + v[2] + "," + v[3] + "," + v[4] + "," + v[5]);
        }
        return days.size() == ym.lengthOfMonth() ? new ArrayList<>(days.values()) : null;
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

    static String left(long ms) {
        long m = ms / 60000;
        return m < 1 ? "<1m" : m < 60 ? m + "m" : m / 60 + "h " + m % 60 + "m";
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

    /** Next event after now: {fireAt, kind (0 = 20 min before, 1 = azaan), prayer index, prayer time}, or null. */
    static long[] next(List<String> lines, long now) {
        for (String l : lines) {
            String[] f = l.split(",");
            LocalDate d = LocalDate.parse(f[0]);
            for (int i = 0; i < 5; i++) {
                long t = at(d, f[i + 1]);
                if (t - PRE > now) return new long[]{t - PRE, 0, i, t};
                if (t > now) return new long[]{t, 1, i, t};
            }
        }
        return null;
    }
}
