# weeprayer

Tiny Dubai prayer-time reminder. Pure Java, no libraries. Once a month it reads the Dubai table from Khaleej Times, then starts Clock-app timers: 20 minutes before each prayer (labelled with its name) and 10 minutes from each azaan. Timer sound is set in the Clock app (set it to silent/vibrate there).

One-time setup: open the app, allow "display over other apps" (lets it start the Clock timer from the background), then use "test timer" and go to the home screen.

Build: Actions -> "test build" (side-by-side APK) or "release". Needs the secrets KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS.

GPL-3.0.
