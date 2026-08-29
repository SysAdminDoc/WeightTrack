# Changelog

## Unreleased

- A second review pass found more holes, all closed. Adding water while looking at an earlier day now records it on that day instead of quietly filing it under today. The water widget writes to Health Connect the same way the screen does, so the one-tap path is not a lesser one. The home water total follows the calendar instead of freezing on the day the app was opened. Turning the app lock on refreshes the widget straight away rather than leaving your weight on the home screen for another half hour. And the app lock no longer opens the app when the fingerprint sensor is merely busy: only a phone with genuinely nothing to authenticate against skips it.
- Granting Health Connect access no longer depends on the optional extras. Steps, active calories and writing water are each separate now, so refusing any of them leaves weight syncing exactly as it was. This also fixes an upgrade where an existing connection would have started reporting itself as unauthorised.
- Stones now mean millilitres rather than fluid ounces, since Britain measures drinks in millilitres.
- Fasting timer. Pick a window from 12:12 up to OMAD, start it, and watch a ring fill as the hours go by. Ending a fast files it in a history with no limit, and any fast can be corrected afterwards or deleted, because forgetting to press stop should not leave a wrong record you cannot fix.
- Movement on the Charts screen. Daily steps and active calories come from Health Connect and sit under the weight trend for context. Days nothing was recorded are left out rather than drawn as zero, because a day the watch spent on the charger is not a day you did not move. Steps and active calories are read-only extras, so refusing them leaves weight sync working exactly as before.
- App lock hardening after a review pass. Turning the lock on no longer throws the lock screen up in your face while you are still in Settings. Rotating the phone no longer asks for your fingerprint again. The window is marked secure, so the unlocked screen stays out of the recents switcher and out of screenshots. The home screen widget now hides your weight while the lock is on, instead of showing on the home screen exactly what the lock was hiding. And if the device screen lock is ever removed, WeightTrack opens normally rather than locking you out of your own history for good.
- Crash reports no longer overwrite each other when two crashes land in the same millisecond, the count in Settings refreshes after you clear them, and a report whose file has gone says so instead of ignoring the tap.
- Water tracking. A screen with today's total against a target you pick, one big button for a serving, quick amounts for a glass or a bottle, a per-day history you can correct, and the last fortnight at a glance. There is a home screen widget that adds a serving with one tap without opening the app, and each drink is written to Health Connect as a hydration record. Millilitres or fluid ounces, following the weight unit you already chose.
- Upgrading keeps everything. The new water table arrives through a proper database migration rather than a reset, verified against a real version 1 database.
- App lock. Turn it on in Settings and WeightTrack asks for your fingerprint, face or screen lock every time you come back to it. The lock screen shows nothing but a padlock, so a phone left on a desk gives away no readings. Devices with no screen lock set say so instead of offering a toggle that could not work.
- Crash reports. If the app ever closes unexpectedly, the exception, the thread, your app and Android versions and the full stack trace are written to a file in private storage. Settings has a reader that lists them, shows one in full and shares it as plain text. Nothing is uploaded on its own, the newest twenty are kept, and the files are excluded from backup and device transfer like everything else.

## 0.2.0 (2026-08-29)

The whole app now shares one focused AMOLED interface. The weight trend stays first, controls take less space, and logging still needs only a few taps.

### Interface

- Reworked Home around the current trend, a thirty-day sparkline, weekly rate and a compact goal summary
- Rebuilt Charts as a flat analysis view with a clearer range selector, raw readings and an unfilled trend line
- Turned History and Measurements into continuous grouped lists with dividers and stronger scan order
- Tightened Log weight and Edit goal around the number pad, with rectangular date, time and milestone controls
- Grouped Settings into full-width sections and made AMOLED black the default for new installs
- Refreshed onboarding around the promise that readings stay on the phone

### Design system

- Added one mint and blue palette across AMOLED, dark and light themes
- Standardized typography on Android's system sans with tabular figures for changing values
- Capped corners at 12 dp and removed pill-shaped navigation indicators, filters and selectors
- Added page mockups and emulator comparison evidence for every route

### Reliability

- Added direct Android 13 notification permission guards before posting reminders
- Made number and weekday formatting react to locale changes, and fixed UTF-8 BOM handling during CSV import

## 0.1.0 (2026-08-29)

First working build. Everything in this release is free, and the licence means it stays that way.

### The trend

- Weight log stored in whole grams, so switching between kilograms, pounds and stones never alters a reading
- Hacker's Diet exponential moving average, made gap-aware: the smoothing factor compounds across missed days, so a reading after three weeks away pulls a stale trend back to reality instead of nudging it
- Smoothing window adjustable from 7 to 30 days, defaulting to the classic 10
- Rate of change per week, fitted by least squares to the smoothed line, with the daily calorie balance it implies
- Plateau detection once the line has been flat for a fortnight

### Goals

- Lose, gain or maintain, with the direction taken from the numbers rather than a toggle
- Projected finish date from your actual rate, with a range around it from the confidence interval on the slope
- No date is offered at all when the trend is flat, heading away from the target, or so slow the answer would be years out
- Milestones split the journey automatically and are awarded against the trend, so one dehydrated morning cannot win one back
- Optional target date, with the pace it would need and a warning when that pace is unrealistic

### Body

- Measurements for thirteen sites, with a US Navy body fat estimate from neck, waist and hips
- Lean and fat mass, waist-to-height ratio, BMI with its category and your healthy weight range
- Basal rate from Mifflin-St Jeor, or Katch-McArdle once body fat is known
- Maintenance calories at your activity level

### Charts

- Raw readings faded behind the smoothed line, drawn on a single canvas so both layers and the goal line share one coordinate space
- Ranges from a week to everything, with pan, pinch to zoom and tap to read a day off
- Week-by-week change bars
- Weekday pattern, measured as distance from the trend so the underlying loss does not swamp it
- Logging consistency and current streak

### Your data

- CSV import that reads exports from Libra, Happy Scale, openScale, MyFitnessPal, Renpho, Withings and others: columns matched by meaning, units read from the header, and the date format worked out from the file itself
- Re-importing the same file updates the same rows rather than doubling your history
- CSV export carrying both kilograms and pounds on every row
- Full JSON backup and restore covering readings, measurements, goal and settings
- Health Connect sync in both directions, deduplicated on both the client record id and the Health Connect id
- Cloud backup and device transfer are switched off deliberately

### Everything else

- Home screen widget showing the trend and the week's change, refreshed the moment a reading changes
- Daily weigh-in reminder on exact alarms with a Doze-safe fallback, a test button, and no nagging on a day you have already weighed in
- AMOLED black by default, plus light, dark and wallpaper colours
- Onboarding that asks for four things and lets you skip three of them
- No ads, no subscription, no account, no analytics, no network calls
