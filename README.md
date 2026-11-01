# weeprayer

Tiny Dubai prayer-time reminder. Pure Java, no libraries. Once a month it reads the Dubai table from Khaleej Times, then starts Clock-app timers: 20 minutes before each prayer (labelled with its name) and 10 minutes from each azaan. Timer sound is set in the Clock app (set it to silent/vibrate there).

One-time setup: open the app, allow "display over other apps" (lets it start the Clock timer from the background), then use "test timer" and go to the home screen.

Build: Actions -> "test build" (side-by-side APK) or "release". Needs the secrets KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS.

GPL-3.0.

## Data

The `times` workflow (main) scrapes the Dubai table with `scripts/scrape.py` as the month turns and publishes it to the `data` branch as a single commit, replaced each time. The app reads `https://raw.githubusercontent.com/santarl/weeprayer/data/dubai.txt` (the repo must be public). First run: Actions -> times -> Run workflow. If the page layout changes the run fails and keeps the page as an artifact.

## Adirampattinam

Choose it under Location in config. The times are built into the app (`A.java`), copied from the town's printed timetable at Siddiq Palli (sheet dated 14-08-1972, worked out for 10.21N 79.25E in IST). The sheet lists five dates a month (1, 6, 12, 18, 24) and is the same every year; each date uses the latest row on or before it, and Luhr is 12:30 all year. The sheet has no Asr, so the earlier (Shafi'i) Asr is calculated from the sheet's noon. The 1 April Fajr was hidden behind prayer beads in the photo (4:4?) and is an estimate, 4:48. The iqamah defaults follow the local rule: 30 minutes after the azaan for Fajr, 20 for Luhr, Asr and Isha, 7 for Maghrib. Nothing is downloaded for this location.
