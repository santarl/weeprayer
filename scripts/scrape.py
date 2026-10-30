#!/usr/bin/env python3
"""Fetch the Dubai monthly prayer table from Khaleej Times and write one line per day:
yyyy-MM-dd,fajr,dhuhr,asr,maghrib,isha   (24h). Standard library only.

usage: scrape.py CURRENT_FILE OUT_FILE
  - exits 0 without writing OUT_FILE if CURRENT_FILE already holds the current Dubai month
  - exits 1 (page saved to debug/page.html) if the page does not contain every day of the month
"""
import calendar, datetime, os, re, sys, time, urllib.request
from zoneinfo import ZoneInfo

URL = os.environ.get("PRAYER_URL", "https://www.khaleejtimes.com/prayer-time-uae/dubai")
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
MON = "JanFebMarAprMayJunJulAugSepOctNovDec"
D = re.compile(r"\b(\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\b")
T = re.compile(r"\b(\d{1,2}):(\d{2})\s?([AaPp][Mm])")


def parse(html, year, month):
    """Markup-agnostic: strip tags, then after every "DD Mon" take the first six times
    (fajr, sunrise, dhuhr, asr, maghrib, isha). None unless every day of the month is found."""
    s = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", " ", html)
    s = re.sub(r"<[^>]+>", " ", s).replace("&nbsp;", " ")
    s = re.sub(r"\s+", " ", s)
    pos = [(m.end(), int(m.group(1)), MON.index(m.group(2)) // 3 + 1, m.start()) for m in D.finditer(s)]
    last = calendar.monthrange(year, month)[1]
    days = {}
    for i, (end, day, mon, _) in enumerate(pos):
        if mon != month or not 1 <= day <= last or day in days:
            continue
        seg = s[end: pos[i + 1][3] if i + 1 < len(pos) else len(s)]
        v = []
        for t in T.finditer(seg):
            h = int(t.group(1)) % 12 + (12 if t.group(3).upper() == "PM" else 0)
            v.append(f"{h:02d}:{t.group(2)}")
            if len(v) == 6:
                break
        if len(v) < 6:
            continue
        row = [v[0], v[2], v[3], v[4], v[5]]
        if row != sorted(row):  # sanity: a mis-parsed column or AM/PM would break the order
            continue
        days[day] = f"{year:04d}-{month:02d}-{day:02d}," + ",".join(row)
    return [days[d] for d in sorted(days)] if len(days) == last else None


def fetch():
    err = None
    for n in range(3):
        try:
            req = urllib.request.Request(URL, headers={"User-Agent": UA, "Accept-Language": "en"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # retry a couple of times
            err = e
            time.sleep(5 * (n + 1))
    raise SystemExit(f"fetch failed: {err}")


def main():
    cur, out = sys.argv[1], sys.argv[2]
    now = datetime.datetime.now(ZoneInfo("Asia/Dubai"))
    y, m = now.year, now.month
    prefix = f"{y:04d}-{m:02d}-"
    have = [l for l in open(cur).read().split("\n") if l.strip()] if os.path.exists(cur) else []
    if len(have) == calendar.monthrange(y, m)[1] and all(l.startswith(prefix) for l in have):
        print("already have", prefix[:-1])
        return
    html = fetch()
    lines = parse(html, y, m)
    if lines is None:
        os.makedirs("debug", exist_ok=True)
        with open("debug/page.html", "w") as f:
            f.write(html)
        raise SystemExit(f"could not find every day of {prefix[:-1]} in the page")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", len(lines), "days for", prefix[:-1])


if __name__ == "__main__":
    main()
