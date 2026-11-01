package io.rfsj.weeprayer;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adirampattinam: the town's printed prayer timetable (Siddiq Palli; the sheet is dated 14-08-1972, worked out for
 * 10.21N 79.25E and converted to IST). It lists five dates a month (1, 6, 12, 18, 24) and is the same every year;
 * like the sheet, every date uses the latest row listed on or before it. Luhr is 12:30 all year. The sheet has no Asr:
 * the earlier (Shafi'i) Asr is calculated from the sheet's own noon (the zenith column).
 */
final class A {
    static final double LAT = 10.21;
    static final int[] DAYS = {1, 6, 12, 18, 24};

    /** Per month, per listed day: "subh zenith maghrib isha" (24h). 1 Apr subh was hidden in the photo (4:4?), 4:48 is an estimate. */
    private static final String[][] S = {
        {"05:03 12:15 18:01 19:17", "05:05 12:18 18:05 19:20", "05:06 12:20 18:08 19:23", "05:07 12:22 18:11 19:25", "05:08 12:24 18:15 19:28"},
        {"05:09 12:26 18:19 19:31", "05:08 12:26 18:20 19:31", "05:07 12:26 18:21 19:32", "05:06 12:26 18:23 19:33", "05:05 12:26 18:24 19:34"},
        {"05:02 12:24 18:23 19:32", "05:00 12:23 18:23 19:33", "04:58 12:22 18:23 19:33", "04:55 12:20 18:22 19:32", "04:52 12:18 18:22 19:32"},
        {"04:48 12:16 18:24 19:32", "04:46 12:14 18:24 19:33", "04:43 12:13 18:25 19:34", "04:39 12:11 18:26 19:34", "04:36 12:10 18:27 19:34"},
        {"04:31 12:09 18:29 19:35", "04:27 12:09 18:29 19:37", "04:24 12:08 18:29 19:39", "04:21 12:08 18:30 19:40", "04:21 12:09 18:32 19:43"},
        {"04:21 12:10 18:33 19:46", "04:20 12:10 18:34 19:48", "04:20 12:12 18:37 19:52", "04:19 12:13 18:38 19:54", "04:21 12:14 18:39 19:56"},
        {"04:24 12:16 18:41 19:58", "04:27 12:17 18:42 19:59", "04:29 12:17 18:41 19:58", "04:32 12:18 18:41 19:57", "04:34 12:18 18:40 19:55"},
        {"04:38 12:18 18:39 19:53", "04:41 12:18 18:38 19:48", "04:44 12:17 18:36 19:45", "04:46 12:16 18:33 19:43", "04:46 12:14 18:30 19:40"},
        {"04:45 12:12 18:27 19:36", "04:44 12:10 18:24 19:33", "04:43 12:08 18:20 19:29", "04:42 12:06 18:16 19:25", "04:41 12:04 18:12 19:20"},
        {"04:40 12:02 18:08 19:16", "04:40 12:00 18:04 19:13", "04:40 11:59 18:02 19:12", "04:39 11:57 17:59 19:09", "04:39 11:56 17:57 19:07"},
        {"04:40 11:56 17:55 19:07", "04:41 11:56 17:54 19:06", "04:41 11:56 17:53 19:04", "04:43 11:57 17:52 19:05", "04:45 11:59 17:53 19:06"},
        {"04:48 12:01 17:54 19:07", "04:50 12:03 17:54 19:08", "04:53 12:06 17:56 19:11", "04:57 12:09 17:57 19:13", "05:00 12:12 17:58 19:15"}
    };

    /** One line per day, "yyyy-MM-dd,fajr,dhuhr,asr,maghrib,isha" (24h). */
    static List<String> lines(YearMonth ym) {
        List<String> out = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) out.add(line(ym.atDay(d)));
        return out;
    }

    static String line(LocalDate date) {
        int r = 0;
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i] <= date.getDayOfMonth()) r = i;
        String[] v = S[date.getMonthValue() - 1][r].split(" ");
        return date + "," + v[0] + ",12:30," + hm(asr(date, minutes(v[1]))) + "," + v[2] + "," + v[3];
    }

    private static int minutes(String hm) { return Integer.parseInt(hm.substring(0, 2)) * 60 + Integer.parseInt(hm.substring(3)); }

    private static String hm(int m) { return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60); }

    /** Earlier Asr (shadow = object + its noon shadow) as minutes after midnight, counted from the sheet's noon. */
    static int asr(LocalDate d, int noon) {
        double dec = declination(d), la = Math.toRadians(LAT);
        double alt = Math.atan(1 / (1 + Math.tan(Math.abs(la - dec))));
        double c = (Math.sin(alt) - Math.sin(la) * Math.sin(dec)) / (Math.cos(la) * Math.cos(dec));
        return (int) Math.floor(noon + Math.toDegrees(Math.acos(c)) * 4 + 0.5);
    }

    /** Sun's declination at noon of the day (NOAA series), in radians. */
    static double declination(LocalDate d) {
        double g = 2 * Math.PI / 365 * (d.getDayOfYear() - 1 + 0.5);
        return 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
    }
}
