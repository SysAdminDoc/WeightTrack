# Changelog

## Unreleased

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
