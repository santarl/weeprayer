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

/** Pure logic (no Android classes): read the monthly table, find the next event. */
final class P {
    static final String[] N = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static ZoneId Z = ZoneId.of("Asia/Dubai"); // follows the chosen location, see M.loc


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

    private static final String MON = "JanFebMarAprMayJunJulAugSepOctNovDec";
    private static final Pattern D = Pattern.compile("\\b(\\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b");
    private static final Pattern T = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\s?([AaPp][Mm])");

    /**
     * Fallback for when the data branch is behind: read Khaleej Times' own page. Markup-agnostic: strip tags, then
     * for every "DD Mon" take the first six times after it (fajr, sunrise, dhuhr, asr, maghrib, isha). Returns one
     * line per day, "yyyy-MM-dd,fajr,dhuhr,asr,maghrib,isha" (24h), or null unless every day of ym was found.
     * (scripts/scrape.py does the same for the workflow.)
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
            String[] row = {v[0], v[2], v[3], v[4], v[5]};
            if (!java.util.Arrays.equals(row, java.util.Arrays.stream(row).sorted().toArray(String[]::new))) continue; // out of order: misread
            days.put(p[2], ym.atDay(p[2]) + "," + String.join(",", row));
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

    /** Start of the last third of the night that ends at Fajr on day d (0 if d is not stored). */
    static long third(List<String> lines, LocalDate d) {
        String[] t = row(lines, d), y = row(lines, d.minusDays(1));
        if (t == null) return 0;
        long m = at(d.minusDays(1), (y != null ? y : t)[4]), f = at(d, t[1]);
        return m + (f - m) * 2 / 3;
    }

    /** Countdown without seconds: "1h 12m", "12m", "<1m". */
    static String leftMin(long ms) {
        long m = Math.max(0, ms / 60000);
        return m < 1 ? "<1m" : m < 60 ? m + "m" : m / 60 + "h " + m % 60 + "m";
    }

    /** Text size (sp) for a widget list line, "> Maghrib  5:42 PM" being 18 monospace characters, so it fits the width. */
    static float lineSize(int wDp) { return Math.max(8f, Math.min(14f, Math.max(20, wDp - 16) / 10.8f)); }

    /** For a widget w x h dp: {prayers shown, text lines that fit}. A list needs about 130 dp of width; narrower ones are a card. */
    static int[] fit(int wDp, int hDp, boolean countdown) {
        int lines = Math.max(1, (int) ((hDp - 16) / (lineSize(wDp) * 1.3f)));
        return new int[]{wDp >= 130 ? count(lines, countdown) : 1, lines};
    }

    /** How many prayers a widget with this many text lines shows: one up to 3 lines, then 2, 3, 4 and all 5 (a countdown takes a line). */
    static int count(int lines, boolean countdown) { return lines < 4 ? 1 : Math.min(5, lines - (countdown ? 2 : 1)); }

    /**
     * What a widget shows, as {name, "HH:mm", target millis, "1" if it is the next prayer}.
     * n < 5: the next prayer and the ones after it, rolling into tomorrow. n >= 5: all five of today, then tomorrow's
     * Fajr once Isha has passed. Empty only if today is unknown.
     */
    static List<String[]> upcoming(List<String> lines, long now, int n) {
        LocalDate d = Instant.ofEpochMilli(now).atZone(Z).toLocalDate();
        String[] r = row(lines, d), t = row(lines, d.plusDays(1));
        List<String[]> out = new ArrayList<>();
        if (r == null) return out;
        List<String[]> seq = new ArrayList<>(); // today's five, then tomorrow's five (if stored)
        for (int i = 0; i < 5; i++) seq.add(new String[]{name(i, d), r[i + 1], "" + at(d, r[i + 1])});
        if (t != null) for (int i = 0; i < 5; i++) seq.add(new String[]{name(i, d.plusDays(1)), t[i + 1], "" + at(d.plusDays(1), t[i + 1])});
        int nx = 5;
        for (int i = 4; i >= 0; i--) if (at(d, r[i + 1]) > now) nx = i;
        int from = n >= 5 ? 0 : nx, to = n >= 5 ? Math.min(seq.size(), nx == 5 ? 6 : 5) : Math.min(seq.size(), nx + n);
        for (int k = from; k < to; k++) out.add(new String[]{seq.get(k)[0], seq.get(k)[1], seq.get(k)[2], k == nx ? "1" : "0"});
        if (out.isEmpty()) out.add(new String[]{seq.get(4)[0], seq.get(4)[1], seq.get(4)[2], "0"}); // after Isha on the last stored day
        return out;
    }

    /** Orders events: by time, then kind (so two events at the same time are both handled, in order). */
    static long key(long time, int kind) { return time * 8 + kind; }

    private static long[] pick(long[] best, long time, int kind, int idx, long target, long afterKey) {
        if (key(time, kind) <= afterKey) return best;
        if (best != null && key(best[0], (int) best[1]) <= key(time, kind)) return best;
        return new long[]{time, kind, idx, target};
    }

    /**
     * The first event after `afterKey` (see key): {fireAt, kind, prayer index (5 = last third), prayer time}, or null.
     * Kinds: 0 = countdown starts (`pre` ms before), 1 = the azaan, 3 = new day, 6 / 7 = Do Not Disturb on
     * (`dndB` ms before) / off (`dndA` ms after) for the five prayers, only if dndB > 0.
     */
    static long[] next(List<String> lines, long afterKey, long pre, boolean third, long dndB, long dndA) {
        long[] best = null;
        for (String l : lines) {
            String[] f = l.split(",");
            LocalDate d = LocalDate.parse(f[0]);
            best = pick(best, d.atStartOfDay(Z).toInstant().toEpochMilli() + 5000, 3, 0, 0, afterKey); // refresh widgets
            if (third) { // the night that ends at this day's Fajr: countdown only
                long q = third(lines, d);
                if (q != 0) {
                    best = pick(best, q - pre, 0, 5, q, afterKey);
                    best = pick(best, q, 1, 5, q, afterKey);
                }
            }
            for (int i = 0; i < 5; i++) {
                long t = at(d, f[i + 1]);
                best = pick(best, t - pre, 0, i, t, afterKey);
                best = pick(best, t, 1, i, t, afterKey);
                if (dndB > 0) {
                    best = pick(best, t - dndB, 6, i, t, afterKey);
                    best = pick(best, t + dndA, 7, i, t, afterKey);
                }
            }
        }
        return best;
    }
}
