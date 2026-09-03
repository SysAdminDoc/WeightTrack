# WeightTrack Roadmap

Single task tracker for the project. Research was refreshed 2026-08-31; prices and app behavior drift, so re-check a store listing before quoting it publicly.

## Positioning

Happy Scale quality, on Android, free forever. No account, no ads, no subscription, no cloud you don't control. Your data lives on your phone and leaves only when you export it.

Current release: v0.5.0.

Every feature the paid apps lock up is either client-side math or a free API call. WeightTrack ships all of it in the open.

## What the market looks like

| App | Price | What's locked behind the paywall | Loudest complaints |
|---|---|---|---|
| MyFitnessPal | $19.99/mo, $79.99/yr | Barcode scanner (free for a decade, paid since 2022), macros in grams, fasting, ad removal | Ads, crashes on add-food, login wall, scanner paywall |
| Lose It! | $79.99/yr, $299.99 lifetime | Macros, custom goals, water, device sync, photos | "Gift" ads, watch sync broken, trial auto-charges a year |
| Noom | ~$42 to $70/mo, $209/yr | Everything (no free tier) | 2,800+ BBB complaints, cancellation friction, NY AG settlement |
| WeightWatchers | $10 to $23/mo, $74 to $99/mo with GLP-1 | Everything | 70% unfavorable on ConsumerAffairs, hard cancellation |
| Happy Scale (iOS) | $11.99/yr, $39.99 lifetime | Export, sync, advanced predictions | No Android version. Constantly requested. |
| Libra (Android) | Free with ads, ~1 EUR/mo | Ad removal, friend charts | Crashes, flaky Health Connect and scale sync, reminders that don't fire, unit bugs |
| Monitor Your Weight | Free, $0.99 cloud | Cloud sync | Dated UI, long setup |
| Zero | $69.99/yr | History past 7 days, body comp, insights | "A timer with extra steps", can't edit fasts |
| Cronometer | $59.99/yr | Ad removal, custom reports, timestamps | Intrusive ads, slow search |
| Yazio | $47.90/yr | Barcode, fasting, recipes | Video ads after every log, no offline mode |
| MacroFactor | $71.99/yr, no free tier | Everything. Adaptive TDEE is the product. | Subscription only, needs daily logging |
| Carb Manager | $59.99/yr | Nearly everything useful | Heavy ads, refund disputes |
| FatSecret | Free, $35.99/yr | Ad removal, photo recognition, reports | Dated UI, accuracy |
| MyNetDiary | Free, $59.99/yr | Diet plans, advanced reports | Closest commercial app to our positioning |
| openScale (OSS) | Free | Nothing | Functional but dated UI, no trend/ETA focus |
| Waistline (OSS) | Free | Nothing | Cordova, hidden gestures, no onboarding |
| Food You (OSS) | Free | Nothing | Nutrition first, weight is secondary |

The gap: nobody ships a modern Compose app with a trend-first weight experience plus Health Connect two-way sync, Bluetooth scales, widgets and a watch tile. openScale has the hardware support, Libra has the trend UX, neither has both, and Libra is closed source with ads.

## What users value most

1. A smoothed trend line that hides daily noise, and a goal date they can believe. This is why Happy Scale and Libra exist and why MacroFactor charges $72 a year.
2. Logging in three seconds. Widgets and watch entry rank right behind.
3. Charts that read well at a week, a month, a year and all-time, with scroll and pinch.
4. Barcode scanning for calorie logging. Its removal from MFP's free tier is the loudest complaint in the category.
5. Data ownership: CSV export, import from the old app, no account.
6. Health Connect sync so a Withings, Renpho or Samsung scale lands in the app on its own.
7. Milestones and small wins, as long as they don't nag.

## Complaints we will not repeat

- No trials, no auto-renew, no cancellation flow. There is nothing to cancel.
- Zero ads, ever.
- No login wall. First launch uses a short private setup and asks for your first weight.
- Nothing gets moved behind a paywall later. The license makes that a structural promise.
- Crashes and lost data are the top cause of 1-star reviews. Room with WAL, automatic local backups, export always available.
- Sync must be idempotent (dedupe on timestamp plus source), with a visible "last synced" and a manual sync button.
- Reminders use exact alarms with a Doze-safe fallback, a "send test notification" button, and battery-optimization guidance for Samsung and Xiaomi.
- Store weight in grams. Render kg, lb, or st+lb at 0.05 kg / 0.1 lb precision. No unit mix-ups.
- Goals work for lose, gain and maintain. Simple trackers break on gain goals.
- Every record is editable. Undo via snackbar, never a confirm dialog.
- Offline by design. Cache food lookups.
- No automated coach, no social feed, no gamification without substance.
- A watch tile has to accept input, not just display.

## Stack

Kotlin 2.x, Jetpack Compose, Material 3 with dynamic color, AMOLED black by default and a light option. Single activity, Compose navigation. Room (WAL, schema export, auto-migrations), DataStore, Hilt, WorkManager, Glance for widgets, Wear Compose + Tiles + Data Layer for the watch, kotlinx-serialization for import/export. Charts drawn directly on a Compose canvas: a library was tried and dropped, because the raw readings, the trend, the goal line and the milestone marks all need to share one coordinate space exactly. minSdk 26, compileSdk 37. R8 on, signed release builds only.

Two product flavors from day one: `play` (ML Kit barcode allowed) and `foss` (ZXing, no Google Play services) so F-Droid can build it.

Reference codebases: Food You (Open Food Facts in Compose), openScale (BLE scale protocols, GPL-3 so re-implement from the wiki docs rather than copying).

### Health Connect notes (Aug 2026)

- `androidx.health.connect:connect-client` 1.1.0 stable; `connect-testing` gives `FakeHealthConnectClient`.
- Records: WeightRecord, HeightRecord, BodyFatRecord, LeanBodyMassRecord, BoneMassRecord, BodyWaterMassRecord, BasalMetabolicRateRecord, HydrationRecord, NutritionRecord.
- Reads are limited to 30 days before grant unless `PERMISSION_READ_HEALTH_DATA_HISTORY` is requested. Ask for it up front so years of scale data import.
- Use changes tokens for incremental sync. Set `clientRecordId` to the local row id so upserts dedupe. `recordingMethod` and `Device.type` are mandatory.
- Manifest needs the permissions-rationale activity. Play Console needs the health declaration and the exact data types declared. Request only types with a user-facing feature.
- Do not touch the Google Fit API. It shuts down end of 2026.
- Health Connect does not run on the watch. The watch writes through the phone over the Data Layer.

### Trend engine

Hacker's Diet exponential moving average: `trend[n] = trend[n-1] + alpha * (weight[n] - trend[n-1])`, alpha 0.1 by default (about a 10-day window), user-tunable from 7 to 30 days. Advance the EMA per calendar day, not per entry, so gaps behave. Linear regression over the last 2 to 4 weeks of trend gives rate per week, goal ETA, and an implied daily calorie balance (7,700 kcal per kg).

### Open Food Facts

v3 API. Rate limits are hard: 15 product reads and 10 searches per minute per IP, bans for abuse. Send a real User-Agent. ODbL license, so attribute in-app. For an offline database use their dump, never scrape.

## Phase 3: v0.4.x, nutrition

Kept optional so the weight-only experience stays clean. Off by default, one toggle to enable.

## Phase 4: v0.4.x, sync and insights

## Research-Driven Additions

Added 2026-08-29 from RESEARCH.md. Every item cites what it rests on.

### P0

### P2

- [ ] P3: Bump Compose BOM to 2026.08.00, Material3 1.4.0, OkHttp 5.5.0
  Why: compose-ui 1.12.0 (2026-08-12) and Material3 1.4.0 (2026-08-26) are stable and the toolchain already meets their AGP 9.2 floor; OkHttp 5.2.1 predates the 5.3.2 timeout-regression fix and the 5.4.0 response-header cap, and 5.5.0 rotated its signing key. (2026-08-31: everything else in the catalog verified current; Kotlin 2.4.20 with the KAPT CVE fix is due September 2026 and can ride along when stable.) (2026-09-03: widen to AGP 9.3.1 to 9.4.0 (shipped 2026-09-01, Gradle 9.7.1 already meets its 9.6 floor), Navigation 2.9.8 to 2.10.0 (predictive pop transitions, hardened handleDeepLink), Truth 1.4.4 to 1.4.5; BOM 2026.09.00 and Material3 1.5 are not out; Kotlin 2.4.20 is still RC3 on 2026-09-02 and its GHSA-r937-wjx7-w2jp range does include 2.4.10; record the OkHttp Commonhaus key with Gradle 9.7's origin and reason attributes in verification-metadata.xml; replace Modifier.onFirstVisible with onVisibilityChanged after the bump.)
  Evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui ; https://developer.android.com/jetpack/androidx/releases/compose-material3 ; https://github.com/square/okhttp/blob/master/CHANGELOG.md ; https://developer.android.com/build/releases/agp-9-4-0-release-notes ; https://developer.android.com/jetpack/androidx/releases/navigation ; https://github.com/google/truth/releases ; https://docs.gradle.org/9.7.1/release-notes.html ; gradle/libs.versions.toml
  Touches: gradle/libs.versions.toml, gradle/verification-metadata.xml, settings-gradle.lockfile, tools/update-dependency-trust.ps1 run
  Acceptance: all four unit suites and both lint tasks green; both release APKs assemble; WebDAV PROPFIND round trip against the recorded-reply test passes; tools/test-dependency-verification.ps1 still refuses lock drift and a tampered jar.
  Complexity: S

### P2 additions from 2026-08-31

### P3 additions from 2026-08-31

- [ ] P3: Replace Material Icons Extended with checked-in Material Symbols vectors
  Why: Compose Material no longer recommends or updates the icons library, and the extended bundle increases build and preview cost while WeightTrack uses a finite icon set.
  Evidence: `gradle/libs.versions.toml:63`; `app/build.gradle.kts:158`; `Icons.*` imports under `app/src/main`; https://developer.android.com/jetpack/androidx/releases/compose-material3
  Touches: `app/src/main/res/drawable`, shared icon wrappers, Compose screens and navigation, version catalog
  Acceptance: Every used icon is a reviewed vector resource with correct RTL behavior and content description at the call site; `material-icons-extended` is absent from the resolved graph; phone screenshots and accessibility semantics remain equivalent; release APK size and clean build time are recorded before and after.
  Complexity: M

### Additions from 2026-08-31 (afternoon pass)

### Additions from 2026-09-03

Every item rests on RESEARCH.md dated 2026-09-03. Stores, developer verification and the watch are not researched or planned, by owner rule.

#### P1

- [ ] P1: Preview before archive restore and CSV import, with every rejected row listed
  Why: the JSON restore shows counts before it merges because it "reaches into every screen at once", yet the encrypted archive (a superset that carries photos) and the CSV import write on confirm with no preview, and the CSV result shows only the first rejected row plus a count.
  Evidence: `app/src/main/java/com/weighttrack/ui/settings/BackupSettingsController.kt:85-100,109-124`; `app/src/main/java/com/weighttrack/ui/settings/SettingsDialogs.kt:29-88`; https://github.com/QuantumPhysique/trale/issues/508 (silent import failures are the niche's chronic complaint)
  Touches: ui/settings/BackupSettingsController.kt, ui/settings/SettingsDialogs.kt, data/io/BackupService.kt (a dry-run for archives), core/io/WeightCsvImporter.kt result type, strings
  Acceptance: opening an archive shows per-type counts and the photo count before the password commit; a CSV import shows matched columns, row count, unit and every rejected row with its reason in a scrollable list before anything is written; cancelling either leaves the database byte-identical; the existing atomic-restore tests cover the new path.
  Complexity: M

- [ ] P1: Run the unit suite at the Robolectric SDK the app targets
  Why: robolectric.properties pins sdk=34 while compile and target are 37, so no unit test has ever executed API 35 or 36 behaviour (predictive back, local network permission checks, Bluetooth bond changes); Robolectric 4.16.1 supports 36 today and 4.17 adds 37 once it leaves beta.
  Evidence: `app/src/test/resources/robolectric.properties`; `app/build.gradle.kts` target 37; https://github.com/robolectric/robolectric/releases
  Touches: app/src/test/resources/robolectric.properties, any test that fails on 36, gradle/libs.versions.toml when 4.17 is stable
  Acceptance: all four unit suites pass at sdk=36 with the same counts; a follow-up note in the item records which tests needed changes and why; when Robolectric 4.17 ships stable the pin moves to 37 in the same way.
  Complexity: S

- [ ] P1: Say so when Health Connect capped an import at 30 days
  Why: Health Connect returns only the 30 days before the grant unless the history permission is held; the app requests it, but a refusal produces a silent short import that reads as data loss, which is exactly the bug trale fixed on 2026-09-03 after a user imported zero rows of Garmin history.
  Evidence: https://github.com/QuantumPhysique/trale/issues/508 ; `app/src/main/java/com/weighttrack/health/HealthConnectSync.kt:209,1119`; `app/src/main/res/values/strings.xml:881` (the only mention of the cap)
  Touches: health/HealthConnectSync.kt outcome type, ui/settings/HealthConnectCard.kt, strings, HealthConnectImportTest
  Acceptance: after an import run without the history grant the Health Connect card shows a line saying the window was limited to 30 days with a button that requests the history permission; the line disappears once it is granted and a full read has run; a test drives FakeHealthConnectClient with the grant absent and asserts the outcome and the copy.
  Complexity: S

- [ ] P1: Choose the scale driver by the services the device serves, with a manual override
  Why: vendor protocols are picked by advertised name, so a weight-only scale that advertises a composition model's name gets the wrong state machine and hangs waiting for a notification; openScale hit this on the weight-only Mi Smart Scale 2 (2026-08-26) and ble-scale-sync's 42-comment thread is the same class between QN and Inlife.
  Evidence: `core/src/main/java/com/weighttrack/core/scale/VendorScales.kt:24`; https://github.com/oliexdev/openScale/issues/1489 ; https://github.com/KristianP26/ble-scale-sync/issues/235
  Touches: core/scale/VendorScales.kt, ble/ScaleConnection.kt (service discovery before protocol choice), ui/scale/ScaleScreen.kt (driver picker), ScaleViewModelTest, VendorScaleProtocolTest
  Acceptance: a device advertising a vendor name but serving only 0x181D or 0x181B is routed to the standard parser; a device serving a vendor service is routed to that vendor regardless of name; the scale screen offers "Try a different protocol" listing the catalogue when a connection sits idle past a timeout; a test feeds a MI SCALE2 name with a 0x181D-only service table and asserts a reading lands.
  Complexity: M

- [ ] P1: Give the photo delete control a real label, size and description
  Why: the delete on every photo tile is a TextButton whose entire label is the letter x at 11 sp with no content description, overlaid on the tile that selects the photo; the accessibility gate passes it because a single character counts as a label.
  Evidence: `app/src/main/java/com/weighttrack/ui/photos/PhotosScreen.kt:257,298-303`; `app/src/main/res/values/strings.xml` `photos_remove`; `app/src/test/java/com/weighttrack/ui/a11y/ScreenStateCoverageTest.kt`
  Touches: ui/photos/PhotosScreen.kt, strings.xml, ui/a11y/ScreenStateCoverageTest.kt
  Acceptance: the control is an IconButton with a localised content description and a 48 dp target, separated from the selection tap; the gate refuses any clickable whose only label is a single character; the tile selection carries Role.Checkbox.
  Complexity: S

- [ ] P1: Keep every privacy claim truthful, including the three that lost their qualifier
  Why: the audit corrected most absolute claims, but "Stored on this device", "It stays on this phone" for the USDA key, and the persistent "Privacy first" badge remain unqualified, and the about text lists backups, sync and Health Connect as the only ways data leaves while every food search, barcode lookup and USDA query sends the query or the key off the phone.
  Evidence: `app/src/main/res/values/strings.xml` `onboarding_no_account_no_ads_stored_on`, `food_get_a_free_key_at_and`, `app_privacy_first`, `settings_free_and_open_source_under_the`; `app/src/main/java/com/weighttrack/ui/food/FoodViewModel.kt:122,133,166`
  Touches: strings.xml, README.md privacy paragraph, docs/SECURITY.md if it repeats the claim
  Acceptance: every user-visible sentence about where data goes names the four exits (export, sync, Health Connect, food lookups) or is scoped to "on its own"; the USDA copy says the key is sent to USDA with each search; a test lists the privacy strings by name and fails if a new string containing "never" or "stays on" is added without the qualifier.
  Complexity: S

- [ ] P1: Stop the test notification replacing profile 1's reminder
  Why: the test notification posts under REMINDER_NOTIFICATION_ID + 1, which is the id the real reminder for profile 1 uses, so pressing "send a test notification" while that reminder is showing silently replaces it.
  Evidence: `app/src/main/java/com/weighttrack/notifications/ReminderReceiver.kt:105,141`
  Touches: notifications/Notifications.kt, notifications/ReminderReceiver.kt, ReminderScheduleTest
  Acceptance: the test notification has an id outside the profile band; a test posts a profile-1 reminder, then a test notification, and asserts both are present in the shadow notification manager.
  Complexity: S

#### P2

- [ ] P2: Show the calorie estimate's uncertainty and refuse the 7,700 kcal/kg density on short windows
  Why: the expenditure fit converts weight change with 7,700 kcal/kg on windows as short as fourteen days, but 84 percent of a two-week change is fat-free mass and water at about 2,380 kcal/kg, and intake inferred from weight carries about 215 kcal/day of individual error; a target shown as one number on a fortnight of data overstates both the deficit and the certainty.
  Evidence: `core/src/main/java/com/weighttrack/core/math/AdaptiveExpenditure.kt:32-37,244,390`; https://physoc.onlinelibrary.wiley.com/doi/full/10.14814/phy2.13336 ; https://pubmed.ncbi.nlm.nih.gov/26040640/
  Touches: core/math/AdaptiveExpenditure.kt, domain/ProgressCalculator.kt, ui/diary/DiaryScreen.kt expenditure card, AdaptiveExpenditureTest
  Acceptance: the fit reports a range (about plus or minus 200 kcal/day, narrowing with window length) and the diary shows it beside the point estimate; windows under 28 days either use the lower energy density or are labelled provisional; a test with a fourteen-day fixture asserts the estimate is not presented as settled and a ninety-day fixture asserts it is.
  Complexity: M

- [ ] P2: Label-accurate titration steps, missed-dose windows and a next-dose reminder in the injection log
  Why: every GLP-1 tracker ships the schedule and the missed-dose rule, and the rules are public label text: tirzepatide steps by 2.5 mg only after four weeks on a dose with a 96-hour missed-dose window, semaglutide steps 0.25 to 2.4 mg (7.2 mg maintenance added 2026) with a 48-hour window; the log records doses but offers no schedule, no reminder and no rule.
  Evidence: https://www.accessdata.fda.gov/drugsatfda_docs/label/2023/217806s000lbl.pdf ; https://www.accessdata.fda.gov/drugsatfda_docs/label/2021/215256s000lbl.pdf ; https://www.novomedlink.com/obesity/products/treatments/wegovy/dosing-administration/dosing.html ; https://helloregimen.com/glp1-tracker ; `core/src/main/java/com/weighttrack/core/medication/Medication.kt`; https://github.com/jameskokoska/Cashew (recurring due-date pattern)
  Touches: core/medication (schedule model, missed-dose rule per GlpDrug), data/db MedicationDoseEntity or a schedule table with a migration, notifications (a reminder channel reusing the reminder scheduler), ui/medication/MedicationScreen.kt, strings, MedicationRepositoryTest
  Acceptance: a profile can set a dose day and interval; the medication screen shows the next due dose and a countdown; a dose logged late shows "within the window, take it" or "skip and wait" from the label rule for that drug; a step-up before four weeks on the current dose is flagged, never blocked; a reminder fires on the due day and stays quiet once the dose is logged; nothing here diagnoses or recommends a dose, and the copy says so.
  Complexity: M

- [ ] P2: A weekly food-noise score on the injection log, from the validated five-item questionnaire
  Why: food noise is the outcome GLP-1 users talk about, MeAgain and Glapp chart a daily slider against the dose cycle, and a validated instrument exists: the Food Noise Questionnaire, five items scored 0 to 5, total 0 to 20, alpha 0.93, seven-day retest 0.79, which argues for a weekly cadence.
  Evidence: https://onlinelibrary.wiley.com/doi/full/10.1002/oby.24216 ; https://meagain.com/glp-1-food-noise ; https://glapp.io/ ; `app/src/main/java/com/weighttrack/ui/medication/MedicationLevelChart.kt`
  Touches: core/medication (score model), data/db (a weekly score table, migration, SyncKind and deletion coverage), ui/medication (a weekly prompt card and a line on the level chart), data/io/MedicationPdf.kt (one line per week in the report), strings
  Acceptance: once a week the injection log offers the five questions with the published wording; the total is drawn on the level chart against the dose curve; the PDF lists the weekly totals; the score syncs and is covered by DeletionAtomicityTest and DeletionCoverageTest; the questionnaire is attributed in the app.
  Complexity: M

- [ ] P2: Never call a GLP-1 user a non-responder before week 24
  Why: SURMOUNT-1 post hoc data show 18 percent of users are late responders (under 5 percent at week 12), 70 percent of whom reach 5 percent by week 24 and 90 percent by week 72; a plateau or "trend flat" card in the first months of treatment reads as failure and is wrong most of the time.
  Evidence: https://dom-pubs.onlinelibrary.wiley.com/doi/full/10.1111/dom.16554 ; `core/src/main/java/com/weighttrack/core/math/TrendEngine.kt:345-348`; `app/src/main/res/values/strings.xml` `home_the_line_has_been_flat_for`
  Touches: domain/ProgressCalculator.kt, ui/home/HomeScreen.kt plateau card, core/medication (first dose date), strings
  Acceptance: while the injection log is on and the first dose is under 24 weeks old, the plateau card explains that early response varies and names the week; after week 24 the ordinary card returns; a test fixes a flat trend at week 10 with a dose logged and asserts the copy, then at week 30 and asserts the plain plateau card.
  Complexity: S

- [ ] P2: Replace the streak with a tally and a single 30-day-gap nudge
  Why: the Charts screen shows a consecutive-day streak, and 2025 analyses of 13,799 negative posts tie lost streaks to guilt and dropout, while the evidence for prompting is a 30-day gap (weight rose 0.6 to 1.4 kg across such gaps in 9,768 scale users); "no gamification without substance" is already the rule.
  Evidence: `app/src/main/java/com/weighttrack/ui/charts/ChartsScreen.kt:525-530`; `core/src/main/java/com/weighttrack/core/math/Analytics.kt:202-209`; https://bpspsychub.onlinelibrary.wiley.com/doi/10.1111/bjhp.70026 ; https://www.jmir.org/2021/6/e25529
  Touches: ui/charts/ChartsScreen.kt, core/math/Analytics.kt, notifications/ReminderReceiver.kt (the reminder already stays quiet on weighed days), strings, AnalyticsTest
  Acceptance: the Charts card shows total weigh-ins and days logged in the last 30, never a streak; a reminder-enabled profile that has not weighed in for 30 days gets one gentle notification naming the gap, once, and the daily reminder is otherwise unchanged; the "1 days" plural bug goes with it.
  Complexity: S

- [ ] P2: Move a reading between household profiles
  Why: the scale asks when two people are within two kilograms, but a wrong answer or a manual entry under the wrong person cannot be corrected except by deleting and retyping; openScale users asked for exactly this.
  Evidence: https://github.com/oliexdev/openScale/issues/999 ; `app/src/main/java/com/weighttrack/data/repo/WeightRepository.kt` (no move method, grep 2026-09-03); `app/src/main/java/com/weighttrack/ble/ScaleReadingRouter.kt`
  Touches: data/repo/WeightRepository.kt, data/repo/DeletionRecorder.kt (a move is a tombstone under the old owner plus a new row under the new one, since identity is profile plus name), ui/history/HistoryScreen.kt edit sheet, ui/scale, strings, DeletionCoverageTest, SyncStoreTest
  Acceptance: the edit sheet offers "Belongs to" when more than one profile exists; moving a reading records a tombstone for the old identity and a new row for the new one so both devices agree after a sync; undo reverses both; a test syncs a move to a second store and asserts the reading exists once, under the new profile.
  Complexity: S

- [ ] P2: Insights honour the chart window
  Why: the steps and sleep association cards correlate across the whole series while the chart beside them shows a chosen range, so a "since my operation" view still explains a lifetime of data; openScale filed the same complaint after seven years of readings.
  Evidence: `app/src/main/java/com/weighttrack/ui/charts/ChartsViewModel.kt:195-197`; `core/src/main/java/com/weighttrack/core/math/Insights.kt:58-64`; https://github.com/oliexdev/openScale/issues/1506
  Touches: ui/charts/ChartsViewModel.kt, core/math/Insights.kt (a from date), InsightsTest, ActivityStateTest
  Acceptance: the association cards recompute from the chart's window start and say how many weeks they used; a window shorter than the minimum paired weeks shows the existing "not enough" state rather than lifetime numbers; a test asserts different associations for two windows over one fixture.
  Complexity: S

- [ ] P2: Samsung Health, Garmin Connect, Fitbit takeout and Apple Health importers
  Why: the importer reads only a first-line header, so Samsung Health (metadata line, header on line 2, time_offset column), Garmin Connect (a date row above each day's rows, values suffixed with the unit) and Fitbit takeout (JSON with split date and time) all fail structurally, and Apple Health is XML; Renpho's forced migration and the Fitbit API sunset make 2026 the year people move.
  Evidence: `core/src/main/java/com/weighttrack/core/io/Csv.kt:33`; `core/src/main/java/com/weighttrack/core/io/WeightCsvImporter.kt:62-110`; https://www.technowizardry.net/2022/02/plot-your-health-with-samsung-health-and-pandas/ ; https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-web/365932/exporting-weight ; https://support.mydatahelps.org/fitbit-body-weight-log-export-format ; https://www.tdda.info/in-defence-of-xml-exporting-and-analysing-apple-health-data ; https://codeberg.org/OpenVitals/mobile-app (reference importer)
  Touches: core/io (a header-row finder, a Garmin two-row reader, a Fitbit JSON reader, a streaming XmlPullParser filtered on HKQuantityTypeIdentifierBodyMass and BodyFatPercentage, zip handling), core/io/OpenedFile.kt kinds, ui/settings import entry, CsvImportTest with one fixture per format
  Acceptance: a fixture for each of the four formats imports with the right count, unit and timestamps (Samsung honours time_offset, Fitbit reads lb or kg from the account unit, Apple Health reads the zip without loading it whole); all four pass through WeightPlausibility and the preview; the README's supported list names them.
  Complexity: M

- [ ] P2: Header aliases for Zepp, Eufy, Lose It and a Cronometer pivot
  Why: Zepp Life writes fatRate and bodyWaterRate, Eufy writes "BODY FAT %", Lose It writes a bare Date and Weight, and Cronometer writes one metric per row keyed by a Metric column; the alias lists and the matcher cannot reach any of them today.
  Evidence: `core/src/main/java/com/weighttrack/core/io/WeightCsvImporter.kt:62-84`; https://github.com/oliexdev/openScale/wiki/Useful-import-scripts ; https://www.markwilson.co.uk/thoughts/2026/03/15/eufy-garmin-data-import/ ; https://github.com/jrmycanady/cronometer-export
  Touches: core/io/WeightCsvImporter.kt alias tables and a long-format pivot, CsvImportTest fixtures
  Acceptance: one fixture per app imports with body fat preserved where the file carries it; the Cronometer fixture pivots Weight rows into readings and ignores other metrics; the openScale-compatible export still round-trips.
  Complexity: S

- [ ] P2: A sync-health card that names a revoked folder grant or a conflict copy
  Why: cloud DocumentsProviders revoke persistable URI grants and serve stale blobs, and file-sync tools leave sync-conflict copies beside device files; WeightTrack refuses conflict names silently and reports a revoked grant as a generic failure, so the fix (re-pick the folder, merge or delete the copy) is never offered.
  Evidence: https://github.com/Kunzisoft/KeePassDX/wiki/File-Manager-and-Sync ; https://github.com/Kunzisoft/KeePassDX/issues/2601 ; `core/src/main/java/com/weighttrack/core/sync/SyncDocument.kt:145-148`; `app/src/main/java/com/weighttrack/data/sync/FolderSyncTarget.kt` (no SecurityException branch, grep 2026-09-03)
  Touches: data/sync/FolderSyncTarget.kt, data/sync/SyncEngine.kt outcome, ui/settings/SyncCard.kt, diagnostics/RuntimeLog.kt events, strings, FolderSyncTargetTest
  Acceptance: a SecurityException on the persisted tree URI produces a card saying the folder access lapsed with a one-tap re-pick; a sync-conflict sibling of a device file produces a card naming it with "ignore" and "open folder"; both are recorded in the activity log as their own events; tests drive both through FakeDocumentsProvider.
  Complexity: M

- [ ] P2: Generate a Baseline Profile from the benchmark journey and ship it in every release
  Why: cold start measured 873 to 1,010 ms on the pinned emulator and no profile exists; published results are 25 percent (Trello) to 3 to 40 percent (Meta) startup gains, and the Macrobenchmark fixture already drives launch, chart and log.
  Evidence: `Roadmap_Blocked.md` (benchmark numbers); `benchmark/`; no profileinstaller or baselineprofile reference in any build file (grep 2026-09-03); https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles/ ; https://android-developers.googleblog.com/2025/11/deeper-performance-considerations.html
  Touches: gradle/libs.versions.toml (androidx.baselineprofile plugin, profileinstaller), benchmark/ (a generator test), app/build.gradle.kts, app/src/main/baseline-prof.txt, tools/prepare-release.ps1 (regenerate step), gradle/verification-metadata.xml
  Acceptance: a checked-in baseline-prof.txt covers launch, Charts and Log; the release script regenerates it; cold start on the pinned emulator improves against tools/benchmark-budgets.json and the budget is tightened to the new number; the profile stays under the size Meta warns about (record the line count in the item when done).
  Complexity: M

- [ ] P2: Tests for the ViewModels and controllers that build every screen state
  Why: screens are rendered from hand-built states by the accessibility gate, but HomeViewModel, ChartsViewModel, LogWeightViewModel, FoodViewModel, MedicationViewModel, PhotosViewModel, WaterViewModel, UndoViewModel, CrashLogViewModel, AppViewModel and the Backup, HealthConnect and People settings controllers have no test naming them, so the layer that turns repositories into those states is the untested one.
  Evidence: test inventory in RESEARCH.md 2026-09-03; `app/src/test/java/com/weighttrack/ui/a11y/ScreenFixtures.kt`; `app/src/test/java/com/weighttrack/ui/scale/ScaleViewModelTest.kt` (the pattern to copy)
  Touches: app/src/test/java/com/weighttrack/ui/** (one test class per ViewModel), TestStores
  Acceptance: each listed class has a test that constructs it with in-memory Room and fakes, drives one real repository write and asserts the exposed state changed; the Home test covers the empty, first-reading and goal-reached states; mutation checks are recorded in the commit message for at least three of them.
  Complexity: M

- [ ] P2: A Play-flavour unit test source set covering the ML Kit reader
  Why: app/src/testFoss tests the ZXing reader against a barcode it draws itself, but app/src/testPlay does not exist, so MlKitBarcodeReader has never run under a test and a broken Play barcode path would ship green.
  Evidence: `app/src/testFoss/java/com/weighttrack/barcode/ZxingBarcodeReaderTest.kt`; `app/src/play/java/com/weighttrack/barcode/MlKitBarcodeReader.kt`; no app/src/testPlay directory (2026-09-03)
  Touches: app/src/testPlay/java/com/weighttrack/barcode/MlKitBarcodeReaderTest.kt, app/build.gradle.kts if the source set needs declaring
  Acceptance: the Play suite gains a test that hands the reader a generated barcode through its seam and asserts the number, or documents in the test why ML Kit cannot decode under Robolectric and asserts the failure path instead; testPlayDebugUnitTest count rises.
  Complexity: S

- [ ] P2: Locale-correct dates, times, percentages and ranges everywhere they are built by hand
  Why: date formatters are pinned to day-first order, times are built with padStart and 24-hour patterns regardless of the device setting, a reminder message prints the English enum weekday, a body-fat percentage is formatted with no locale, and the healthy range and progress percent are string-concatenated; each is a translation that can never be right.
  Evidence: `app/src/main/java/com/weighttrack/ui/format/DateFormatters.kt:16-17`; `app/src/main/java/com/weighttrack/ui/log/LogWeightScreen.kt:155`; `app/src/main/java/com/weighttrack/ui/settings/PeopleSettingsController.kt:194,201`; `app/src/main/java/com/weighttrack/ui/settings/SettingsBasicSections.kt:222-223`; `app/src/main/java/com/weighttrack/ui/scale/ScaleScreen.kt:125`; `app/src/main/java/com/weighttrack/ui/home/HomeScreen.kt:413,646,652,659`; `app/src/main/res/values/strings.xml:928`
  Touches: ui/format/DateFormatters.kt (DateTimeFormatter.ofLocalizedDate and DateFormat.is24HourFormat), the call sites above, strings, StringRenderingTest
  Acceptance: a Robolectric run under en-US shows month-first dates and 12-hour times, under de-DE day-first and 24-hour, with the weekday from getDisplayName; percentages and ranges come from NumberFormat and a resource with two placeholders; a gate test scans ui/ for padStart on time fields, `.name.lowercase()` on DayOfWeek and String.format without a locale and fails on any.
  Complexity: M

- [ ] P2: Plurals for every count the app speaks
  Why: 908 strings hold three plurals; "%1$d days" renders "1 days", servings are two hardcoded strings, the crash-report count is baked into one string and templated in another, and every "N readings" style string shares the shape.
  Evidence: `app/src/main/res/values/strings.xml` `chartsscreen_streak_days`, `common_days_ago`, `diary_serving`, `diary_servings`, `settings_crash_reports`, `settings_crash_reports_2`, `history_readings_deleted`; `app/src/main/java/com/weighttrack/ui/charts/ChartsScreen.kt:530`
  Touches: strings.xml, every call site of the converted strings, StringFormatArgumentsTest (already checks plurals)
  Acceptance: every count-bearing string is a plurals resource with one and other; the baked "(1)" string is deleted; the format-argument gate covers the new plurals; a Robolectric render of "1 day" and "2 days" is asserted.
  Complexity: S

- [ ] P2: Undo for the three deletes that still have none, and a longer undo for a profile
  Why: the rule is undo, never confirm, yet crash-log Clear all and per-report delete have neither and remove the only copy, "Discard this fast" and the scale's "Forget it" bypass the undo coordinator, and deleting a whole person offers a four-second snackbar from a plain button placed beside Rename.
  Evidence: `app/src/main/java/com/weighttrack/ui/diagnostics/CrashLogViewModel.kt:98-113`; `app/src/main/java/com/weighttrack/ui/fasting/FastingViewModel.kt` cancel; `app/src/main/java/com/weighttrack/ui/settings/SettingsPersonSections.kt:219-223`; `app/src/main/java/com/weighttrack/ui/components/UndoSnackbar.kt:44-46`; https://developer.android.com/develop/ui/compose/components/snackbar (Long is 10 s and TalkBack extends it)
  Touches: ui/diagnostics, ui/fasting, ui/scale, ui/settings/SettingsPersonSections.kt, ui/UndoCoordinator.kt (a duration per offer), UndoDeleteTest
  Acceptance: each of the three routes through undoOffers and restores the row, file or bond on undo; profile deletion uses the Long duration; the Delete row sits in its own section below the person's settings rather than beside Rename; the undo test drives all three new paths.
  Complexity: S

- [ ] P2: A button and a timely nudge for battery optimisation, not a paragraph
  Why: reminders that stop arriving are the top complaint class for Libra, and the guidance here is plain text with no action, rendered only after the reminder is already on; the one user who needs it, with reminders enabled and nothing arriving, has to find and read it unprompted.
  Evidence: `app/src/main/java/com/weighttrack/ui/settings/ReminderCard.kt:43,83-87`; `app/src/main/res/values/strings.xml` `settings_if_reminders_stop_arriving_check_that`; RESEARCH.md 2026-09-03 review mining (Libra reminders)
  Touches: ui/settings/ReminderCard.kt, notifications/ReminderScheduler.kt (record last delivery), strings
  Acceptance: the card has a button that opens the app's battery settings (ACTION_APPLICATION_DETAILS_SETTINGS or the ignore-optimisations request where allowed) and shows the last time a reminder was actually delivered; when the app is in the restricted bucket or optimisation is on, the card says so above the fold; a test asserts the copy for each state.
  Complexity: S

- [ ] P2: Surface silent Health Connect failures on the chart and diary
  Why: a revoked or failing read of periods or sleep is swallowed into an empty map and a failing steps read into a quieter expenditure, so a permission that lapsed looks like "no periods recorded" and the shading and cards simply vanish.
  Evidence: `app/src/main/java/com/weighttrack/ui/charts/ChartsViewModel.kt:133-140,192-194`; `app/src/main/java/com/weighttrack/ui/diary/DiaryViewModel.kt:133-134,147-148`
  Touches: ui/charts/ChartsViewModel.kt, ui/diary/DiaryViewModel.kt, the existing ActivityStatus.FAILED pattern, strings
  Acceptance: each read reports LOADING, OK, EMPTY or FAILED separately and the screen shows one line for FAILED with the failure kind from HealthFailure; a test revokes the menstruation permission on the fake client and asserts the chart state says it could not be read, not that nothing was recorded.
  Complexity: S

- [ ] P2: Notify when the weekly copy has failed twice in a row
  Why: an auto-backup failure is visible only inside Settings, so a folder that has gone missing or a provider that refuses writes can fail for a month with nothing on screen, in the app whose stated reason for the weekly copy is that there is no cloud to fall back on.
  Evidence: `app/src/main/java/com/weighttrack/ui/settings/SettingsDataSections.kt:138-145`; `app/src/main/java/com/weighttrack/data/io/AutoBackupWorker.kt`; https://github.com/simonoppowa/OpenNutriTracker/releases (0-byte export caught only by users, v2.2.0)
  Touches: data/io/AutoBackupWorker.kt, notifications (a low-importance channel or the summary channel), strings, AutoBackupTest
  Acceptance: two consecutive failures post one notification naming the reason and opening Settings at the backup section; a later success clears it; the activity log records both; a test runs the worker twice against a refusing provider and asserts the notification, then once against a working one and asserts it is gone.
  Complexity: S

- [ ] P2: Let onboarding be replayed and let it fail loudly
  Why: onboarding is written once and nothing resets it, so the guided setup cannot be revisited, and finish() has no failure path: a DataStore or Room write that throws leaves the user on the last step with no message.
  Evidence: `app/src/main/java/com/weighttrack/ui/onboarding/OnboardingViewModel.kt:128-161`; `app/src/main/java/com/weighttrack/data/prefs/SettingsRepository.kt:248`
  Touches: ui/onboarding/OnboardingViewModel.kt, ui/settings (a "Run setup again" row under About), strings, a new OnboardingViewModelTest
  Acceptance: a failing repository in the test makes finish() surface a message and keep the state; Settings offers replaying the setup without clearing data; the replay pre-fills existing values.
  Complexity: S

#### P3

- [ ] P3: Implement the eufy P3 broadcast and the Renpho ES-CS20M Yunmai flow from their two agreeing sources, marked unverified on hardware
  Why: the block on these was "uncorroborated or contradicted", and that has changed: two independent Home Assistant integrations agree on the P3 advertisement (manufacturer 0xCF64, weight bytes 12 to 13 LE/100, heart rate byte 15, impedance bytes 17 to 18 LE/10), and the ES-CS20M 0x1A10 GATT flow is in openScale (ported from an HCI capture) and independently in a HA integration, with the three hardware variants documented; eufy P2 impedance still disagrees across sources and Beurer GS4xx has no evidence anywhere, so both stay blocked.
  Evidence: https://github.com/cubinet-code/eufylife_ble ; https://github.com/Skriipt/eufy-smart-scale-ble ; https://github.com/oliexdev/openScale/pull/1330 ; https://github.com/ronnnnnnnnnnnnn/renpho_fitness_scale_ble ; https://github.com/oliexdev/openScale/issues/1308 ; `Roadmap_Blocked.md`
  Touches: core/scale (two new VendorScaleProtocol state machines and their catalogue entries), VendorScaleProtocolTest with frames transcribed from both sources, CompositionQuality (a "protocol unverified on hardware" provenance value), Roadmap_Blocked.md
  Acceptance: both protocols parse the published frames byte for byte in tests; readings they produce carry the unverified provenance and the history row shows it; the blocked note is rewritten to list only P2 impedance and GS4xx; the Wyze Scale X protocol (published 2026-08-16) is recorded as a candidate in the same note.
  Complexity: M

- [ ] P3: Widget preview, tap-to-log and a water undo
  Why: the widget picker shows the launcher icon because previewImage points at it and no Glance providePreview exists, the weight widget opens Home although one of its own lines says "Tap to log", and the water widget's one-tap add can neither be undone nor open the app.
  Evidence: `app/src/main/res/xml/weight_widget_info.xml:7`; `app/src/main/res/xml/water_widget_info.xml:7`; `app/src/main/java/com/weighttrack/widget/WeightWidget.kt:164`; `app/src/main/java/com/weighttrack/widget/WaterWidget.kt:91-96,134`; https://developer.android.com/jetpack/androidx/releases/glance (1.2.0 providePreview)
  Touches: widget/WeightWidget.kt, widget/WaterWidget.kt, res/xml widget info, shortcuts/LauncherShortcuts.kt (reuse the log route), strings, WeightWidgetDataTest
  Acceptance: both widgets provide a generated preview and the picker shows it; tapping the weight widget opens the log screen through the shortcut route; the water widget shows a five-second "undo" state after a tap and a small open-app affordance; the widget receivers gain a test each.
  Complexity: M

- [ ] P3: One dark means one dark, and "Follow system" follows it
  Why: choosing Follow system in a dark system yields the AMOLED palette while choosing Dark yields the softer one, and the default is AMOLED rather than the system, so the theme a person gets by not choosing is the more extreme of the two darks.
  Evidence: `app/src/main/java/com/weighttrack/ui/theme/Theme.kt:43-53`; `app/src/main/java/com/weighttrack/data/prefs/SettingsRepository.kt:31`; `app/src/main/java/com/weighttrack/ui/charts/ChartsScreen.kt:450` (a Color.Gray literal)
  Touches: ui/theme/Theme.kt, ui/charts/ChartsScreen.kt, strings for the chip labels
  Acceptance: Follow system maps to the same dark palette the Dark chip gives, with AMOLED only when chosen (the default stays AMOLED as the owner's rule requires, but it is shown as chosen); the chart zero line uses a theme token; the appearance fixtures in the state gate render both darks.
  Complexity: S

- [ ] P3: A noise-floor band on the chart and quiet arrows inside it
  Why: day-to-day weight varies by about 0.5 percent of body weight (0.45 kg at 90 kg) with no change in fat, and nothing on the chart or the home card says which of this morning's movements is inside that band, so ordinary wobble reads as news.
  Evidence: https://www.tandfonline.com/doi/full/10.1080/0886022X.2023.2273421 ; `app/src/main/java/com/weighttrack/ui/components/TrendChart.kt`; `app/src/main/java/com/weighttrack/widget/WidgetLines.kt`
  Touches: core/math (a noise floor from trend weight), ui/components/TrendChart.kt (a shaded band around the trend), ui/home/HomeScreen.kt and widget/WidgetLines.kt (no direction word inside the band), strings, TrendChartBoundsTest
  Acceptance: the chart shades plus or minus 0.5 percent around the trend with a legend entry; the home card and glance mode say "about the same" for a reading inside the band; a test asserts the wording at 0.3 percent and the direction word at 1 percent.
  Complexity: M

- [ ] P3: Show scale body fat as a change band and flag BMI-derived figures
  Why: fifteen consumer BIA devices ran minus 3.5 to plus 11.7 percentage points off a four-compartment reference while tracking change within about plus or minus 2.5 points, and at least one shipping scale's body fat is exactly 1.5 times BMI minus 17.5 with no impedance in it; the app stores impedance and provenance but shows the absolute percentage without a formula version.
  Evidence: https://pmc.ncbi.nlm.nih.gov/articles/PMC10404482/ ; https://news.ycombinator.com/item?id=49472008 ; https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/6393 ; https://github.com/oliexdev/openScale/issues/1483 ; `app/src/main/java/com/weighttrack/data/io/Backup.kt` CSV header (composition_protocol, composition_quality)
  Touches: core/scale/BodyCompositionAssembler.kt (formula version constant per protocol, a BMI-derived detector), data/db (formula version column with migration), ui/history and ui/measurements body fat rows, strings
  Acceptance: every composition row records the formula version that produced it; a reading whose body fat equals 1.5 times BMI minus 17.5 within rounding is labelled as derived from BMI; the measurements screen shows body fat change with a band rather than a bare absolute; the CSV export carries the version.
  Complexity: M

- [ ] P3: Stable record ids and a UTC offset in the CSV export
  Why: the export has no record identity and its time column has no zone, so a re-import dedupes on timestamp plus weight only and a file that crosses a time-zone change shifts every reading; OpenTracks embeds a UUID per export for exactly this reason and openScale's own CSV lost times on re-import.
  Evidence: `app/src/main/java/com/weighttrack/data/io/Backup.kt` HEADER; `core/src/main/java/com/weighttrack/core/io/WeightCsvImporter.kt`; https://github.com/OpenTracksApp/OpenTracks ; https://github.com/oliexdev/openScale/issues/1475
  Touches: data/io/Backup.kt exporter, core/io/WeightCsvImporter.kt (read id and offset when present), CsvImportExportTest
  Acceptance: the export adds an id column carrying the sync id and an offset column; importing the app's own export updates rows by id rather than adding duplicates and keeps instants across a zone change; files without the columns import as before.
  Complexity: S

- [ ] P3: FHIR Observation export for weight and body fat
  Why: personal health aggregators (Fasten, CommonHealth, Metriport) read FHIR Bundles, and a body-weight Observation is about forty lines: LOINC 29463-7, UCUM kg or [lb_av], effectiveDateTime; it makes the data readable by any clinical tool without a bespoke connector.
  Evidence: https://www.hl7.org/fhir/observation-example.html ; https://github.com/fastenhealth/fasten-onprem ; `app/src/main/java/com/weighttrack/data/io/BackupService.kt` export entry points
  Touches: core/io (a FHIR bundle writer), data/io/BackupService.kt, ui/settings/SettingsDataSections.kt, strings, a serialisation test against the HL7 example shape
  Acceptance: Settings offers "Export as FHIR"; the file validates against the Observation shape for weight and body fat with UCUM units; a test round-trips one reading and compares the JSON to a checked-in expected document.
  Complexity: M

- [ ] P3: Daily-totals CSV for the food diary
  Why: FoodYou's most-reacted issue (14) wants a per-day calories and macros CSV to compare against a watch's expenditure, and its maintainer's answer was to have an LLM write a script against the schema; WeightTrack exports raw entries only.
  Evidence: https://github.com/maksimowiczm/FoodYou/issues/37 ; https://github.com/maksimowiczm/FoodYou/issues/406 ; `app/src/main/java/com/weighttrack/data/io/BackupService.kt`
  Touches: data/io (a daily-totals writer), ui/settings data section, strings, CsvImportExportTest
  Acceptance: the export writes one row per day with calories, protein, carbohydrate, fat and the expenditure estimate for that day; a test with two days of entries asserts the sums.
  Complexity: S

- [ ] P3: A Bluetooth kitchen scale into the food entry
  Why: no tracker does it (FoodYou #375 has a fork with a demo), the standard Weight Scale service is what many kitchen scales speak, and the app already owns the BLE plumbing and parsers.
  Evidence: https://github.com/maksimowiczm/FoodYou/issues/375 ; `core/src/main/java/com/weighttrack/core/scale/StandardScaleParser.kt`; `app/src/main/java/com/weighttrack/ble/`
  Touches: ble/ (a grams-range route that does not go through WeightPlausibility), ui/diary quantity field, strings, ScaleRoutingTest
  Acceptance: with the food database on, the quantity field offers "Read from a scale"; a reading under two kilograms fills the grams field and never reaches the weight log; a test feeds a 0x2A9D frame at 250 g and asserts it lands in the diary field only.
  Complexity: M

- [ ] P3: Caliper body-fat methods on the measurements screen
  Why: trale's most-reacted composition request lists five caliper protocols because BMI-based fat is "notoriously inaccurate"; the measurements screen already holds sites and the Navy method, so three-site Jackson-Pollock is a formula and three fields.
  Evidence: https://github.com/QuantumPhysique/trale/issues/43 ; `core/src/main/java/com/weighttrack/core/math/BodyMetrics.kt`; `app/src/main/java/com/weighttrack/ui/measurements/MeasurementsScreen.kt`
  Touches: core/math/BodyMetrics.kt (Jackson-Pollock 3-site and 7-site with the Siri conversion), core/model measurement sites for skinfolds, data/db migration, ui/measurements, strings, BodyMetricsTest against published worked examples
  Acceptance: skinfold sites can be recorded in a set; the body fat row shows which method produced the figure; tests reproduce the published example values within 0.1 percent.
  Complexity: M

- [ ] P3: QR pairing for a second device's sync settings
  Why: setting up folder or WebDAV sync on a household's second phone means retyping a server address and app password; Logseq's 2026 LAN sync pairs by a QR code carrying the endpoint with the token only in the URL fragment, and the sync settings here are a small JSON.
  Evidence: https://github.com/logseq/logseq/pull/12919 ; `app/src/main/java/com/weighttrack/ui/settings/SyncCard.kt`; `app/src/main/java/com/weighttrack/barcode/BarcodeReader.kt` (both flavours already read QR)
  Touches: ui/settings/SyncCard.kt (show and scan), core/sync (a pairing payload with the address, folder name and a one-time secret; never the stored password), barcode/, strings, PinnedTrustTest for the pinned certificate hand-off
  Acceptance: the first phone shows a QR that expires in ten minutes; scanning it on the second phone fills the address, folder and pinned certificate and prompts for the password separately; the payload is tested to contain no credential.
  Complexity: M

- [ ] P3: A "Deleted" view backed by the tombstones, with restore
  Why: undo lasts seconds while tombstones live at least thirty days and until every device acknowledges them; a view of what was deleted, with restore, turns that retention into a feature the person can see, which is what Obsidian and Immich do with their trash.
  Evidence: `core/src/main/java/com/weighttrack/core/sync/SyncMerge.kt` (TOMBSTONE_RETENTION_FLOOR_MILLIS); `app/src/main/java/com/weighttrack/data/repo/DeletionRecorder.kt`; https://obsidian.md/help/sync/version-history ; https://docs.immich.app/features/mobile-app
  Touches: data/db (deleted rows need their content kept beside the tombstone, or a soft-delete column with a migration), data/repo/UndoableDelete.kt, ui/settings data section, strings, DeletionCoverageTest
  Acceptance: Settings lists deletions from the last thirty days by kind and date; restore re-creates the row and forgets the tombstone under the right owner; a sync after a restore leaves the row present on both devices; the view shows nothing once a tombstone is pruned.
  Complexity: M

- [ ] P3: Explicit Certificate Transparency and domain-encryption blocks in the network security config
  Why: target 37 turns Certificate Transparency on by default and adds ECH per domain; the config declares only trust anchors, so a self-signed home server pinned at the socket now also has to pass CT unless the domain opts out, and OkHttp 5.5.0 is where ECH lives.
  Evidence: `app/src/main/res/xml/network_security_config.xml`; https://developer.android.com/privacy-and-security/security-config ; https://developer.android.com/about/versions/17/behavior-changes-17 ; https://github.com/square/okhttp/blob/master/CHANGELOG.md
  Touches: res/xml/network_security_config.xml, data/sync/PinnedTrust.kt, WebDavTransportTest against a self-signed MockWebServer
  Acceptance: a WebDAV sync to a pinned self-signed server succeeds on an API 37 Robolectric run with CT enforcement modelled; the config documents which domains skip CT and why; the food lookup hosts keep CT on.
  Complexity: S

- [ ] P3: Tell the person why an update will not install under Advanced Protection
  Why: Android 16's Advanced Protection mode blocks unknown-source installs and, per 2026-06 reporting, updates to already-sideloaded apps; a direct-APK app that cannot update should say so rather than let the install silently fail.
  Evidence: https://developer.android.com/privacy-and-security/advanced-protection-mode ; https://www.androidauthority.com/android-advanced-protection-mode-developer-options-3679725/ ; `docs/SECURITY.md`
  Touches: ui/settings About section, security/ (AdvancedProtectionManager query behind an API check), strings, docs/SECURITY.md
  Acceptance: on a device with the mode on, About shows one line explaining that updates must be installed by ADB or after turning the mode off; the line is absent otherwise; SECURITY.md records the same.
  Complexity: S

- [ ] P3: Accessible equivalents for chart interaction and the goal bar
  Why: the chart describes itself but pan, zoom and tap-a-day have no accessible equivalent, one canvas sets an empty description that makes it focusable and silent, and the goal progress bar carries no progress semantics.
  Evidence: `app/src/main/java/com/weighttrack/ui/components/TrendChart.kt:573`; `app/src/main/java/com/weighttrack/ui/components/Common.kt:153-202`; `app/src/main/java/com/weighttrack/ui/home/HomeScreen.kt:376,432,529`; `app/src/main/java/com/weighttrack/ui/history/HistoryScreen.kt:199`
  Touches: ui/components/TrendChart.kt (a custom action per range and a "read the newest N days" action), ui/components/Common.kt (progressSemantics), ui/home/HomeScreen.kt and ui/history/HistoryScreen.kt roles, CanvasIsDescribedTest (reject an empty description unless the node is marked decorative)
  Acceptance: TalkBack exposes custom actions on the chart that move the window and read the selected day's trend and reading; the goal bar announces its percent; every clickable in the cited files carries a role; the canvas gate refuses an empty description without an explicit decorative flag.
  Complexity: M

- [ ] P3: String hygiene: duplicates, padding, stale names and the diagnostics door
  Why: two strings exist for Sync now and for Syncing, labels carry leading spaces as padding, one resource name contradicts its value, "Crash reports (1)" is baked in, the activity log is shown as raw machine lines under "Recent activity", and the only door to that log is a button labelled by the crash count.
  Evidence: `app/src/main/res/values/strings.xml` `synccard_sync_now`, `settingsscreen_sync_now`, `home_log_your_first_weight`, `photos_gallery`, `settings_meals_drinks_and_steps_are_not`, `settings_crash_reports`; `app/src/main/java/com/weighttrack/ui/diagnostics/CrashLogScreen.kt:124-130`; `app/src/main/java/com/weighttrack/ui/settings/SettingsDataSections.kt:244-249`
  Touches: strings.xml, the call sites, ui/diagnostics/CrashLogScreen.kt (a human line per LogEvent), ui/settings/SettingsDataSections.kt
  Acceptance: one string per phrase; padding comes from modifiers; every resource name matches its value's subject; the diagnostics row is labelled "Activity and crash reports" with counts for both; each LogEvent has a localised sentence and the raw line is behind a "details" toggle.
  Complexity: S

- [ ] P3: Empty states that offer the next action
  Why: Charts, History, Photos and Medication show text with no button while Home's empty state has one, Water has no empty state at all, and the Foods empty state renders below the USDA card so a new user scrolls past three cards to find it.
  Evidence: `app/src/main/java/com/weighttrack/ui/charts/ChartsScreen.kt:99-107`; `app/src/main/java/com/weighttrack/ui/history/HistoryScreen.kt:101-113`; `app/src/main/java/com/weighttrack/ui/photos/PhotosScreen.kt:156-164`; `app/src/main/java/com/weighttrack/ui/medication/MedicationScreen.kt:160-178`; `app/src/main/java/com/weighttrack/ui/water/WaterScreen.kt:268`; `app/src/main/java/com/weighttrack/ui/food/FoodScreen.kt:268-277`
  Touches: the six screens, strings, ScreenFixtures empty states
  Acceptance: every empty state names what will appear and offers the one action that fills it; Foods shows its empty state first; Water has one; the state gate renders each.
  Complexity: S

- [ ] P3: Remove the dead milestone-step setting from the app-level settings and the sync document
  Why: GoalViewModel writes AppSettings.milestoneStepGrams, nothing reads it but the backup and sync writers, and behaviour comes from the goal row; a dead value that still travels between devices is a merge conflict waiting for a reason.
  Evidence: `app/src/main/java/com/weighttrack/ui/goal/GoalViewModel.kt:151`; `app/src/main/java/com/weighttrack/data/io/BackupService.kt:162,194`; `app/src/main/java/com/weighttrack/domain/ProgressCalculator.kt:98`
  Touches: data/prefs/SettingsRepository.kt, ui/goal/GoalViewModel.kt, data/io/Backup.kt (read old files, never write the field), data/sync/SyncEngine.kt, SettingsRepositoryTest
  Acceptance: the field is gone from AppSettings; old backups and peer files still decode; the goal row remains the only source.
  Complexity: S

- [ ] P3: Submit WeightTrack to awesome-privacy and Privacy Guides
  Why: neither list carries a weight tracker, Privacy Guides' stated criteria (auto-updates, no unencrypted data off-device, works offline) are met, and distribution is the growth bottleneck for a project with zero stars and an empty tracker.
  Evidence: https://github.com/pluja/awesome-privacy ; https://www.privacyguides.org/en/health-and-wellness/ ; https://discuss.privacyguides.net/t/recommendation-for-smart-scales/29968 (users asking for exactly this)
  Touches: a pull request to awesome-privacy, a forum post on Privacy Guides, README.md screenshots and the one-paragraph description they reuse
  Acceptance: the awesome-privacy PR is open with the entry in the list's format; the Privacy Guides suggestion thread exists and links the release page with checksums; both are recorded in the CHANGELOG under a docs entry.
  Complexity: S

## Never

Ads. Subscriptions. Accounts. Proprietary cloud. Automated coaching. Social feed. Google Fit API. Moving a shipped feature behind anything.

## Sources

- MyFitnessPal: https://www.pocket-lint.com/apps/news/162386-wow-myfitnesspal-put-its-popular-barcode-scanner-feature-behind-a-paywall/ , https://www.garagegymreviews.com/myfitnesspal-review
- Lose It: https://www.trustpilot.com/review/loseit.com , https://nutriscan.app/blog/posts/lose-it-pricing-2026-free-vs-premium-2b4e921555
- Noom: https://www.noom.com/blog/weight-management/noom-cost/ , https://www.choosingtherapy.com/noom-review/
- WeightWatchers: https://www.consumeraffairs.com/nutrition/weight_watchers.html
- Happy Scale: https://www.iphonejd.com/iphone_jd/2025/01/review-happy-scale.html , https://screenrant.com/happy-scale-app-android-best-alternatives/
- Libra: https://play.google.com/store/apps/details?id=net.cachapa.libra , https://chrome-stats.com/d/net.cachapa.libra/reviews
- Monitor Your Weight: https://play.google.com/store/apps/details?id=monitoryourweight.bustan.net
- Zero: https://healthfitpublishing.com/is-zero-fasting-app-worth-it-2026-pricing-features/
- Cronometer: https://calorie-trackers.com/reviews/cronometer/
- Yazio: https://nutriscan.app/blog/posts/yazio-pricing-2026-free-vs-pro-what-pro-unlocks-33b26f8fc7
- MacroFactor: https://macrofactor.com/workouts/price/ , https://outlift.com/macrofactor-review/
- Carb Manager: https://www.caloriescanai.com/blog/carb-manager-keto-app-review
- FatSecret: https://thetestdesk.com/reviews/fatsecret/
- MyNetDiary: https://www.mynetdiary.com/
- Health Connect: https://developer.android.com/health-and-fitness/health-connect/get-started , https://developer.android.com/health-and-fitness/health-connect/data-types , https://developer.android.com/jetpack/androidx/releases/health-connect , https://support.google.com/googleplay/android-developer/answer/14738291
- Google Fit shutdown: https://9to5google.com/2026/05/07/google-fit-shut-down-health-replacement-migration-tool-coming/
- Wear OS 6: https://developer.android.com/training/wearables/versions/6/changes
- OSS apps: https://f-droid.org/en/packages/com.health.openscale/ , https://github.com/oliexdev/openscale/wiki/supported-scales-in-openscale , https://f-droid.org/packages/com.waist.line/ , https://f-droid.org/en/packages/com.maksimowiczm.foodyou/
- Trend math: https://www.fourmilab.ch/hackdiet/e4/signalnoise.html
- Vico: https://github.com/patrykandpatrick/vico
- Open Food Facts: https://openfoodfacts.github.io/openfoodfacts-server/api/
- Bluetooth: https://www.bluetooth.com/specifications/specs/weight-scale-service-1-0/ , https://blescalesync.dev/guide/supported-scales , https://developer.android.com/develop/connectivity/bluetooth/bt-permissions , https://github.com/nordicsemi/Kotlin-BLE-Library
