# Changelog

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
