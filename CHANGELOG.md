# Changelog

## Unreleased

- Sync between your own devices, with no account anywhere in it. Point each phone at the same folder and they keep each other up to date: a Syncthing folder, or a directory on your own Nextcloud over WebDAV. Every device writes one file and reads the others, so nothing ever writes to somebody else's file and a folder sync tool has nothing to fall out over. The most recent version of a record wins. Deleting something deletes it everywhere and it stays deleted, which is the part that is usually wrong. It runs in the background about once an hour, and there is a button when you cannot wait.
- Twenty two thousand common products now ship inside the app. Scanning a barcode in a shop with no signal has a good chance of answering, and typing into the food search gets an answer straight away instead of after a round trip. Anything not on the list still goes out to Open Food Facts as before, and your own foods always come first. The list is picked by how often each product actually gets scanned, with a quota per country so it is not simply a French supermarket. It costs about two megabytes of the download.
- Meals go to Health Connect, if you allow it. Write only, and never read back: pulling other apps' meals in would double every day for anybody who logs in two places, and there is no way to tell a duplicate from a second helping. Removing a meal here removes it there, so the two cannot drift apart. Refusing the permission costs the Health Connect records and nothing else.
- What you actually burn, worked out from what you ate and what your weight did. No formula, no activity multiplier, no guessing how hard you exercise: if you ate two thousand a day for a fortnight and lost half a kilogram a week, you burn about twenty five hundred, and your own body did the measuring. This is the loop the subscription apps charge for. It says nothing at all until there are ten days of food to go on, because a confident number made of nothing is worse than no number, and it will not recommend eating under 1200 a day whatever the goal says; below that it stops being a diet and becomes a medical decision.
- Daily targets, in grams or as a share of the day, whichever you think in. Lifting and medical advice come in grams and most diets are described as percentages, so the app converts rather than insisting. Any day of the week can have its own, because eating the same on a rest day as on a long run is the reason per-day targets exist. The diary shows what is left, and going over is stated as a fact rather than a telling-off.
- A food diary, by meal. Add something to breakfast, lunch, dinner or snacks, walk back through the week, and copy the day before or just yesterday's breakfast, because people eat the same breakfast for months and retyping it is what makes them stop logging. A quick add takes a number of calories and nothing else, for the meal out with no barcode. What each food was worth is written down when you log it, so a label corrected next month does not rewrite what last month added up to, and deleting a food does not take a day's total with it. A macro nobody recorded shows as unknown rather than as zero grams of it.
- Barcode scanning, in both builds. The Play build reads with ML Kit and the F-Droid build with ZXing, rather than the free build going without the feature people miss most. Only retail barcodes are read, and the check digit is verified before anything is looked up: a misread digit gets a confident answer about a different product, which is worse than no answer. A code already in your foods needs no signal and no request at all. The camera runs only while the scanning screen is open, and the frame is looked at for a number and thrown away.
- A food database, off until you ask for it. Turn it on in Settings and a Foods screen appears: your own foods, recipes that work out their own nutrition, and lookups against Open Food Facts for packaged food and USDA FoodData Central for plain ingredients. Anything found online is kept on the phone, so scanning the same tin again works with no signal. The published request limits are respected rather than tested by trying, and Open Food Facts is credited wherever its data appears, which is what its licence asks. USDA needs a free key of your own; this app will not ship one, because a key inside an open source app is a shared quota in a public repository.
- Profiles, for a household that shares a scale. Each person keeps their own readings, goal, measurements, water, fasts and photos, and their own weigh-in reminder at their own time. Everything you had before the update stays where it is, under the first profile. A weight read off a Bluetooth scale is offered to whoever it fits rather than filed under whoever happens to have the app open. Health Connect can only belong to one profile, because it keeps a single set of weights for whoever owns the phone and has no idea a household exists.
- More scales. Beurer and Sanitas diagnostic scales, Renpho and the QN-Scale family, and eufy's C1, P1 and BodySense now talk to the app as well as the ones that speak the standard Bluetooth services. Beurer's newer range already worked through the standard services and still does. These were written from published protocol descriptions and the frame handling is covered by tests, but none of that hardware was on hand here, so a scale that connects and then says nothing is worth reporting.
- Bluetooth scales. Open "Weigh in on your scale", step on, and the reading goes straight into the log with whatever body composition the scale measured alongside it. Scales that speak the standard Bluetooth weight and body composition services are connected to; Xiaomi's put the weight in their broadcast, so those need no pairing at all. The scan runs only while that screen is open, and the permission it asks for is declared as never being about your location, because it isn't. A reading a long way from your last one is shown with a question rather than filed, since the usual explanation is somebody else in the house standing on the same scale.
- A watch app. Wear OS gets the trend, the week's change and how long ago you last weighed in, on a tile, on a watch face as a complication, and in the app itself. Logging is a turn of the crown: the picker steps in your own unit, 0.05 kg or 0.1 lb at a time, and the reading is handed to the Data Layer rather than sent, so one entered on a walk with the phone at home arrives when they are next together. Turn the app lock on and the watch shows a padlock instead of your weight, the same as the home screen widget does. The watch app ships with the Play build; the F-Droid build has no Google dependency and no watch.
- The app lock can no longer strand you. A phone whose biometric sensor Android has switched off pending a security update, with no screen lock behind it, now opens normally instead of holding up a prompt that can never be answered. The switch to turn the lock off also stays on the Settings screen whenever the lock is on, rather than only appearing when the sensor is healthy.
- A photo brought in from the gallery is now filed under the day it was taken, read from the picture's own EXIF date and falling back to what the gallery knows. The weight shown against it is the reading from that day rather than today's, so importing a year of old pictures lines each one up with what the scale actually said. A camera clock that was never set, or set to the wrong year, is ignored rather than filing the photo in 1970 or in the future.
- Fasting fixes from the third review pass. Tapping start twice no longer files a zero length fast, and a start after the clock has jumped backwards can no longer record a fast that ends before it began. A running fast is now editable, so "I actually started this two hours ago" is a correction you can make. Both the start and the end carry a date as well as a time, which is what a fast you forgot to stop overnight needs. The ring caption reads the target off the running fast instead of the last chip you tapped. An edit that cannot be saved says why rather than closing in silence. And the screen no longer walks your whole fasting history on the interface thread once a second.
- Progress photo database updates now resolve and validate their private image files on the I/O dispatcher instead of making the screen collector do filesystem work.
- Camera captures now survive the app process being reclaimed while the camera is open. The pending private file is restored and filed when control returns, instead of leaving an unreachable JPEG behind.
- Progress photo tiles now decode a display-sized sample in the background instead of expanding every camera image at full resolution on the interface thread.
- Weekly summaries now verify notification access at the point where Android posts them, and water logging keeps its local-date behavior on every supported Android version.
- Weekly summary. Pick a day and a time and WeightTrack sends one short note on how the week went, leading with a milestone if you crossed one. In a week with too few readings to say anything honest it stays quiet rather than sending you a notification with nothing in it.
- Progress photos. Add them from the camera or the gallery, and pick any two to see them side by side with the dates and the weight change between them. Pictures are copied into the app's own storage rather than referenced, so they keep working, they sit behind the app lock, and they are excluded from backup like the rest of your data. Nothing is ever uploaded.
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
