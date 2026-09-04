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

## Audit Findings 2026-09-03

Audit-only pass at HEAD `9d70b97` (v0.5.0, tree clean). Baseline: 507 core + 1,349 Play + 1,355 FOSS + 23 Wear unit tests green; `lintPlayDebug`, `wear:lintDebug`, `core:lintDebug` 0 errors, 57 warnings; gitleaks clean over 160 commits. Tracker: issues enabled, zero open, zero closed, no PRs, discussions off, so no reported-issue intake applies. Nothing below duplicates an item above; refinements to existing items are marked as such. The pass was cut short before emulator verification and before four adversarial deep-read sweeps (sync stack, Health Connect and workers, data layer and backup, secondary screens) reported, so the last item records what is unaudited.

#### P1

#### From the sync deep read, 2026-09-04

- [ ] P2: Give the device list a way to forget a peer that never publishes
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/ui/settings/SyncCard.kt` (the device list); `app/src/main/java/com/weighttrack/data/db/SyncPeerDao.kt` (`deleteAll` has no caller but a full erase).
  Problem: the budget now refuses a file that names a crowd of devices, so no new install can be jammed. An install that already holds a poisoned `sync_peers` table has no way back: every name in it is waited for before a deletion may be forgotten, and they can only be retired one tap at a time.
  Fix: offer a way to forget every peer that has not published a file in ninety days, and say what it does. Retiring already exists and is the right verb; this is the bulk version of it.
  Acceptance: a table holding two hundred peers, none of which has published, can be cleared down to the devices actually seen this round in one action; a test asserts the tombstone rule starts pruning again afterwards.
  Confidence: Verified
  Effort: S

- [ ] P1: Retiring a device is irreversible in effect while the app promises it is reversible
  Category: correctness
  Where: `core/src/main/java/com/weighttrack/core/sync/SyncMerge.kt:275-296` (`waiting = peers.filterNot { it.isRetired }`); `core/sync/SyncDocument.kt:224-227` (the comment "retiring one by mistake loses nothing"); `app/src/main/res/values/strings.xml` `sync_devices_explained` and `sync_device_forget` ("Nothing it sent is removed, and you can bring it back"); `ui/settings/SyncCard.kt:140-147,199-211`; `ui/settings/SyncSettingsController.kt:83-99`.
  Problem: retiring a device removes it from the set the tombstone rule waits for. If a tombstone is then dropped past the 30-day floor while that device is away, and the device is later un-retired and switched on, it still holds the deleted row, nothing survives to contradict it, and `SyncMerge.newest` puts the row back on every device. Deleted readings return for good. The control is a one-tap toggle with no confirmation and copy that explicitly says it is reversible and lossless, which is true only until the floor passes.
  Evidence: adversarial read. `SyncTombstoneAcknowledgementTest > bringing a retired device back makes the others wait for it again` un-retires within the same merge in which the tombstone still exists (`merged.deletions` has size 1), so it never runs the drop first and then the un-retire.
  Fix: record `retiredAtUtcMillis` and refuse to un-retire a device that has been retired longer than `TOMBSTONE_RETENTION_FLOOR_MILLIS`, offering instead "set this device up again", which clears its local data before it syncs. Change the copy to say what retiring actually costs after a month. Add the missing test: retire, delete, advance past the floor, merge until the tombstone drops, un-retire, merge the old document, assert the row stays deleted.
  Acceptance: the sequence above leaves the reading deleted; the strings no longer promise a lossless return; the new test fails against today's code.
  Confidence: Verified
  Effort: M

- [ ] P2: A sync that lands never refreshes the widget or the watch
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/sync/SyncWork.kt:196-206` (the Health Connect path calls `surfaces.refresh()`) against `:209-222` (the folder and WebDAV path calls nothing); `data/sync/SyncEngine.kt:60-149` never touches `SurfaceUpdater`; `widget/SurfaceUpdater.kt:10-29`.
  Problem: every other writer in the app refreshes the glanceable surfaces immediately (`LogWeightViewModel:156`, `HistoryViewModel:87`, `PeopleSettingsController:139`, `BackupSettingsController:234`, `HealthConnectSettingsController:75`). Sync is the one path that changes the log silently, so a weight logged on one phone reaches the other phone's database but its home-screen widget and watch tile keep showing yesterday's figure until the app is opened.
  Fix: call `surfaces.refresh()` after a `SyncResult.Done` that applied anything, in the folder and WebDAV branch of `SyncWork` and after a manual `syncNow`.
  Acceptance: a test drives the worker with a document holding a newer reading and asserts `SurfaceUpdater.refresh` was called; a run with nothing to apply does not call it.
  Confidence: Verified
  Effort: S

- [ ] P2: "Last seen" is when the file was noticed, not when it was written
  Category: ux
  Where: `core/src/main/java/com/weighttrack/core/sync/SyncMerge.kt:203-208` (`SyncPeer(lastSeenAtUtcMillis = now)` for every document present in the round); `ui/settings/SyncCard.kt:181-194`; `core/sync/SyncDocument.kt:35` (`writtenAtUtcMillis`, written at `SyncStore.kt:102` and read by nothing).
  Problem: a sold or wiped phone leaves its file in the Syncthing folder for ever, and every sync re-stamps it as seen today. The device card exists so somebody can spot a dead device and retire it, and the one device that needs retiring is the one the screen says is the most active. Tombstones are held open indefinitely as a result, which is the same end state as the peer-flood item above, reached by accident.
  Fix: take `lastSeenAtUtcMillis` from the document's own `writtenAtUtcMillis` (clamped to not exceed now), and show a hint on the card when a device has not written for thirty days.
  Acceptance: a test merges a document written a year ago and asserts the peer row carries that date; the card shows the stale hint.
  Confidence: Verified
  Effort: S

- [ ] P2: Retryable server codes are reported as the person's fault and stop the retry
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/data/sync/WebDavSyncTarget.kt:348-364` (only `0`, `507` and `500..599` are special; everything else becomes `Refused`); `app/src/main/java/com/weighttrack/sync/SyncWorker.kt:60-69` (`Refused -> WorkOutcome.DONE`).
  Problem: a Nextcloud with brute-force protection answering **429**, a WebDAV `423 Locked` while another client holds a lock, or a `408` all end as `Refused`, so WorkManager is told the run succeeded and schedules no backoff, and the card states a bare number the person cannot act on. It undoes the "never refuse an operation on a guess about the network" rule for exactly the codes that mean "come back later".
  Fix: treat 408, 423, 425 and 429 as `Unreachable` (honouring `Retry-After` where present) so the hourly job heals itself, and keep `Refused` for 401, 403, 404 and 409.
  Acceptance: `WebDavTransportTest` gains a 429 case asserting `Unreachable`, and `SyncWorkerTest` asserts the worker retries for it.
  Confidence: Likely
  Effort: S

- [ ] P2: An address the current check rejects leaves sync dead behind a stale success line
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/data/sync/SyncEngine.kt:316-327` (`SyncAddress.isUsable` false makes `forSettings` return null) and `:63` (returns `NotSetUp` without calling `finish`); `ui/settings/SyncSettingsController.kt:212-213`; `ui/settings/SyncCard.kt:226-233`.
  Problem: an install that stored an `http://` address before the plain-http refusal existed still reports `isReady`, so the card looks configured. Every hourly run returns `NotSetUp` and reports DONE, `recordSync` is never reached, and the card keeps showing the last successful "Synced N changes" line for ever. Pressing Sync now answers "Pick somewhere to sync to first" about a server the person did pick. The only trace is `SYNC_ADDRESS_REFUSED` in the activity log.
  Fix: when a stored address exists but fails `isUsable`, return a `Refused` naming the actual problem from `addressMessage`, and have the card show it in place of the last success line with a button that reopens the address dialog.
  Acceptance: a stored `http://` address makes both the hourly run and Sync now say the address is not encrypted; a test asserts the message and that `lastSyncMessage` is replaced.
  Confidence: Verified
  Effort: S

- [ ] P2: Five syncable kinds are merged per profile but applied by name alone
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/data/sync/SyncStore.kt:544` (measurements: `dao.measurements().associateBy { it.syncId }`), and the same shape at `:594` water, `:639` fasts, `:685` goals, `:740` macro targets; against the scoped form at `:456` weights, `:940` food log, `:1135`/`:1175` medication; `core/sync/SyncMerge.kt:66-67` merges under `owned(profileSyncId, syncId)`, so a merged document may legitimately hold two rows sharing a `syncId`; `SyncStore.kt:1031` deletes unscoped too, while weights are deliberately scoped at `:1021-1029` with a comment explaining why.
  Problem: a document carrying the same measurement name under two profiles resolves both to one local row. `candidate = existing.copy(...)` keeps the first profile's `profileId`, so the second person's values are written onto the first person's measurement and their own row is never inserted. It is stable across syncs, and a later tombstone for either name removes both. The same shape as the identity bug already fixed for weights, left in five kinds.
  Evidence: adversarial read. `SyncRegressionTest > two people with the same record name keep their own readings` and `> deleting one person's reading leaves the other person's alone` both use weights, the one kind already scoped.
  Fix: key all five maps on `profileId to syncId` as weights do, and scope their deletions the same way. No first-party writer produces a duplicate name today, so this is hardening rather than a live bug, but the CSV importer already derives names from content for weights and the next kind to do so would make it live.
  Acceptance: a test applies a document with measurement `m-1` under two profiles and asserts two rows, one per profile; deleting one leaves the other.
  Confidence: Likely
  Effort: S

#### From the Health Connect, alarms and widgets deep read, 2026-09-04

- [ ] P1: The daily-lowest setting stops working after the first sync
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/health/HealthConnectSync.kt:740-746` (the rule lives only in `importWeights`, the full-read path) against `:662-671` (`importChanges` calls `take` per record) reached from `:578`.
  Problem: once a changes token is stored, every later sync goes down the incremental path, which never applies the rule. So the option works for the first import and silently never again, and the trend it exists to protect gets dragged by second and third weigh-ins exactly as before. Nothing on screen says the setting has stopped applying.
  Evidence: adversarial read. Every lowest-of-day test builds a fresh `HealthConnectSync` and calls `sync()` once with no stored token, so only the `token == null` branch has ever run. `HealthChangesTest > a reading added in the other app arrives on the next sync` does not set the option.
  Fix: apply the rule in `importChanges` too, comparing each incoming record against what is already stored for that day rather than against the batch, since a change set does not carry the whole day.
  Acceptance: a test syncs once, stores a token, then delivers two readings for one day through the changes path and asserts only the lower one is kept.
  Confidence: Verified
  Effort: M

- [ ] P1: A fresh install's first profile has no travelling name, so deleting it leaves no tombstone
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/di/DataModule.kt:62-73` (the create callback inserts profile 1 naming only id, name, position and created time, leaving `syncId` at its `defaultValue = ''`); `data/db/Entities.kt:68`; `data/repo/ProfileRepository.kt:189-190` (`ensureDefault` returns early because a row exists); `data/db/WeightTrackDatabase.kt:124-134` (`AddSyncIds.onPostMigrate` runs only on the 8 to 9 migration, never on a fresh create); the drop happens at `data/repo/DeletionRecorder.kt:82`, called from `ProfileRepository.kt:156`.
  Problem: on every install since schema 9 the default profile carries a blank `syncId`. Deleting that person records no PROFILE tombstone, because `DeletionRecorder.record` returns on a blank name, so the other phone has no reason to drop them and hands the profile straight back. Their row tombstones do travel, under `profileSyncId = ""`, so the person reappears as an empty profile that cannot be removed.
  Evidence: adversarial read. `DeletionCoverageTest > deleting a profile is remembered, and so is everything it owned` builds the database with `inMemoryDatabaseBuilder`, which does not run the `DataModule` callback, so `ensureDefault` really does insert a row with `newSyncId()`; the test then deletes a profile it added rather than profile 1. The fixture differs from the app in exactly the field under test.
  Fix: give profile 1 a real `syncId` in the create callback, and add a one-off repair that fills any blank profile `syncId` on start, since existing installs already have one. Make the coverage test build the database the way `DataModule` does.
  Acceptance: a fresh database has a non-blank `syncId` on profile 1; deleting it records a PROFILE tombstone; a second store syncing the tombstone drops the profile and it does not return.
  Confidence: Verified
  Effort: S

- [ ] P2: A watch reading is filed against whoever is on screen when it arrives, and can duplicate
  Category: correctness
  Where: `app/src/play/java/com/weighttrack/wear/PhoneWearListenerService.kt:78-88` (calls `weightRepository.add`, which uses `profiles.activeId()` at `data/repo/WeightRepository.kt:209`); `core/src/main/java/com/weighttrack/core/sync/WearSync.kt` (`WearWeightLog` carries no owner); `data/db/Daos.kt:241-243` (`upsertByIdentity` keys on profile plus record id).
  Problem: weigh on the watch out of range, switch profile on the phone, then bring them together: the reading is filed against the wrong person. Worse, because identity includes the profile, a redelivery after the `deleteDataItems` call at `:68-74` fails inserts the same reading a second time under a second person instead of deduping.
  Fix: carry the profile's travelling name in `WearWeightLog` (the watch already receives a summary for one person) and file against it, falling back to the claim holder.
  Acceptance: a test delivers a watch log while a different profile is active and asserts the row lands under the originating profile; a second delivery of the same log adds nothing.
  Confidence: Verified
  Effort: M

- [ ] P2: The Health Connect import mark can move on a run that read nothing
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/health/HealthConnectSync.kt:630` (`readToTheEnd` is an instance field on a singleton), `:566-579` (the `!way.reads` branch returns without clearing it), `:584-589` (the mark is written whenever it is true), `:692-718` (`startAgain` never sets it).
  Problem: sync two-way once, which sets the flag, then switch to publish-only. The next hourly run writes `setHealthImportedThrough(now)` having read nothing, which the comment at `:566-570` says must not happen. If the token later expires while the profile holds no local readings, `startAgain` restarts from that inflated mark minus two days and everything recorded while reading was off is skipped for good. The mirror case: a successful full re-read inside `startAgain` leaves the flag false, so the mark never advances after a recovery.
  Fix: make it a local value returned by the read, not a field, so it can only describe the run that produced it, and set it on the `startAgain` path too.
  Acceptance: a test syncs two-way, switches to write-only, runs again and asserts the mark did not move; a second asserts a recovery read does move it.
  Confidence: Likely
  Effort: S

- [ ] P2: An edit made during a sync is never sent to Health Connect
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/data/db/Daos.kt:88-92` (`markHealthExported` sets `healthExportedAtUtcMillis = updatedAtUtcMillis`), read at `health/HealthConnectSync.kt:863` and written at `:889` after the batch round trip.
  Problem: the mark records the row's version at marking time, not the version that was actually sent. Save a correction to a weigh-in while the hourly sync sits between reading `awaitingHealthExport` and marking it done, and the row is stamped as exported at the new version although the old value went across. `awaitingHealthExport` never returns it again, so the health record keeps the wrong weight permanently.
  Fix: pass the `updatedAtUtcMillis` that was read into the mark, so a row edited since is left pending.
  Acceptance: a test reads the pending set, edits a row, then marks, and asserts the row is still pending.
  Confidence: Likely
  Effort: S

- [ ] P2: The water widget ignores the app lock
  Category: security
  Where: `app/src/main/java/com/weighttrack/widget/WaterWidget.kt:56-69` (`loadWaterData` reads settings but never `appLockEnabled`) and `:127-168`; compare `widget/WeightWidget.kt:85-87`, which returns `hidden = true`, and `wear/WearSummaryBuilder.current()`.
  Problem: with the app lock on, the weight widget and the watch blank out and the water widget keeps showing the day's intake and target on a locked phone's home screen. The lock is the app's answer to "somebody else can see my phone", and one glanceable surface was missed.
  Fix: read `appLockEnabled` in `loadWaterData` and return a hidden state, with a pure seam so it can be tested the way `buildWidgetData` is.
  Acceptance: a `WaterWidgetDataTest` asserts the hidden state with the lock on, mirroring `WeightWidgetDataTest`.
  Confidence: Verified
  Effort: S

- [ ] P2: A failed meal deletion in Health Connect is never retried and never recorded
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/health/HealthConnectSync.kt:274-285` (`.getOrDefault(false)` with no `failed(...)` call, unlike the writers at `:270` and `:990`); caller `ui/diary/DiaryViewModel.kt:308` discards the result. Weight deletions have a queue (`pendingDeletions` at `:924-930` plus `healthDeletionsSent`).
  Problem: delete a logged meal while the provider is busy and the record stays in Health Connect for ever, with nothing in the activity log to say so. The diary and the health record diverge permanently on one transient failure.
  Fix: record the failure through `failed(...)` and give nutrition deletions the same pending queue weights already have.
  Acceptance: a test makes the delete fail, asserts the event is logged and the deletion is queued, then succeeds on the next run.
  Confidence: Verified
  Effort: S

#### From the data and backup deep read, 2026-09-04

- [ ] P1: Restoring a pre-0.5.0 backup duplicates every body measurement
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/data/repo/MeasurementRepository.kt:154-159` (`upsertAll` has no identity at all); called from `data/io/BackupService.kt:550` on the legacy branch; `data/db/Daos.kt:299-300` (`insertAll` is REPLACE on an autogenerated key); `data/db/Mappers.kt:276-292` (`toEntity` mints a fresh `syncId`); `data/io/Backup.kt:151-166` (`BackupMeasurement` carries no id and no syncId).
  Problem: a backup written by 0.4.0 or earlier takes the legacy branch, and the measurement path is an insert wearing an upsert's name. Restore such a file twice, or restore it onto a phone that already restored it, and every measurement is doubled; each copy then publishes to the other device as a separate record. The summary reports the full count as restored each time, so nothing on screen suggests anything was duplicated. The weights on the line above dedupe correctly through `upsertByIdentity`.
  Evidence: adversarial read. `BackupRoundTripTest > restoring the same file twice changes nothing the second time` exercises only the version-2 document path and asserts only on `syncDao().weights()`; `BackupServiceTest` restores a v1 file exactly once.
  Fix: give `BackupMeasurement` the row's travelling name, write it on export, and match on it when restoring; for older files with no name, derive one from the timestamp and site the way the CSV importer derives a weight's, so a repeat restore matches.
  Acceptance: restoring a v1 fixture twice leaves one copy of each measurement; a test asserts the count and fails if the identity is removed.
  Confidence: Verified
  Effort: M

- [ ] P2: The whole progress snapshot is recomputed on the main thread, and unrelated writes trigger it
  Category: perf
  Where: `app/src/main/java/com/weighttrack/domain/ProgressCalculator.kt:39-62` (a six-way `combine` with no `flowOn`); every collector is `stateIn(viewModelScope, ...)`, i.e. the main dispatcher: `ui/home/HomeViewModel.kt:33-38`, `ui/charts/ChartsViewModel.kt:93-98`, `ui/diary/DiaryViewModel.kt:169`, `ui/photos/PhotosViewModel.kt:91`, `ui/medication/MedicationViewModel.kt:78`. Compare `ui/fasting/FastingViewModel.kt:84`, which does use `flowOn`.
  Problem: `settingsRepository.settings` re-emits on any DataStore write, including keys with nothing to do with the trend: `setHealthChangesToken` and `setHealthImportedThrough` (hourly, and on every Sync now), `setLastAutoBackup`, `setScale`. Each re-emission re-runs `TrendEngine.computeSeries`, which walks one iteration per calendar day since the first reading, plus the goal projection and the milestones, on the UI thread in every live collector, and emits a new snapshot that recomposes every chart. The repo's own recorded lesson says exactly this about Room flows: the query moves off main, the operators after it do not.
  Fix: `flowOn(Dispatchers.Default)` after the combine, and narrow the settings input to the fields the calculation actually reads (`distinctUntilChanged` on a small projection) so a token write cannot trigger it.
  Acceptance: a test asserts the computation does not run on the main dispatcher, and that writing a Health Connect token produces no new snapshot; the benchmark's Charts frame timing is recorded before and after.
  Confidence: Verified
  Effort: S

- [ ] P2: Undoing a profile deletion can leave two people holding Health Connect
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/data/repo/ProfileRepository.kt:168-180` (the restore re-inserts the row verbatim through `dao.restoreWithData`); exclusivity is enforced only inside `setHealthConnect` at `:314-331`; the invariant is stated at `data/db/Entities.kt:53-60`; the resolver is `:258-260`, `firstOrNull { it.healthConnectEnabled }` over position order.
  Problem: profile A holds Health Connect. Delete A, and while the undo snackbar is up turn Health Connect on for B, which finds no other holder to clear. Press Undo and A returns with the flag still set. Which person Health Connect exchanges with is then decided by row position, permanently, and nothing repairs it, because `healthConnectDecided` is already true so the claim logic will not re-run.
  Fix: clear the flag on restore unless no other profile holds it, or run the same exclusivity sweep `setHealthConnect` runs after any restore.
  Acceptance: `UndoDeleteTest` gains a case that sets the flag on the doomed profile, enables it on another during the window, undoes, and asserts exactly one holder.
  Confidence: Likely
  Effort: S

- [ ] P2: A chart start date after the last weigh-in silently shows a two-day window under the wrong label
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/ui/charts/ChartsScreen.kt:111` (`newest` is the last reading, not today), `:114-116` (`ChronoUnit.DAYS.between(since, newest) + 1` then `coerceAtLeast(2)`), `:119`; the picker clamps only against today at `:367`.
  Problem: stop weighing for a fortnight, then pick a date inside that gap. The day count goes negative, the floor turns it into 2, and the chart draws the last two days that have readings while the header says "Since" the date that was picked. `changeOverRange` then reports the change across those two days. It is a stretch nobody asked for, labelled with a date it does not contain.
  Fix: clamp the picked date to the newest reading as well as to today, and say so on screen when it was moved, or keep the date and show the empty-window state the comparison already has a null case for.
  Acceptance: a test with the newest reading two weeks old and a `since` of yesterday asserts either the clamped window or the empty state, and that the header and the chart agree.
  Confidence: Verified
  Effort: S

- [ ] P2: An oversized archive fails with an out-of-memory error instead of the size message
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/data/io/BackupService.kt:323-326` (`backupFile.readText()`, an unbounded read of the staged file) against `:784-796` and `MAX_BACKUP_BYTES` at `:805`; `data/io/ArchiveCodec.kt:67-72` permits a 64 MB entry, enforced at `:310-312`.
  Problem: the same content that the plain JSON path refuses cleanly with the too-large message becomes an `OutOfMemoryError` when it arrives inside an archive, caught by the `runCatching` at `:301` and surfaced as a restore failure carrying an OOM message. The staging copy is written to the cache directory first, up to the codec's total ceiling.
  Fix: read the staged `backup.json` through the same bounded reader the URI path uses.
  Acceptance: an `ArchiveBackupTest` case with an oversized entry gets the too-large message; nothing is left in the cache directory.
  Confidence: Likely
  Effort: S

- [ ] P2: Undoing a cleared goal can leave two goals active
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/data/repo/GoalRepository.kt:88-97` (the restore re-activates unconditionally and never re-reads whether something else became active; `clearActive` also reads then writes outside a transaction); the invariant is stated at `data/db/Daos.kt:377-385`.
  Problem: clear the active goal, set a new one, then press Undo. Both rows end up active. `observeActive` hides it behind `ORDER BY createdAtUtcMillis DESC LIMIT 1`, but `activeAll` returns both, both publish to the other device as active goals, and every later `setGoal` stamps and retires two rows where one changed, which is the noise the `AND active = 1` fix was added to stop.
  Fix: on restore, re-activate only if nothing else is active, otherwise bring the goal back retired; wrap the clear in the same transaction as its tombstone.
  Acceptance: `UndoDeleteTest` gains a case setting a second goal inside the undo window and asserts exactly one active row afterwards.
  Confidence: Likely
  Effort: S

#### From the secondary-screens deep read, 2026-09-04

- [ ] P3: The bundled food shelf can still collide on a list key
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/ui/food/FoodScreen.kt` `foodKey`, used for `state.local`; the shelf is built by `tools/build_offline_foods.py` into `app/src/main/assets/offline_foods.db`.
  Problem: `foodKey` falls back to the barcode plus the name for anything off the shelf, both of which carry a zero identifier. Two shelf rows sharing a name with no barcode would produce the same key and throw, the same way the online list did before it was keyed by position. The online list is fixed; this one rests on the shelf builder never emitting such a pair, which nothing checks.
  Fix: either make the shelf's own identifier travel on `Food` so a real key exists, or assert uniqueness of `(barcode, name)` when the shelf is built and fail the build on a duplicate.
  Acceptance: a test loads the shipped shelf and asserts every row produces a distinct `foodKey`; the builder refuses a duplicate.
  Confidence: Likely
  Effort: S

- [ ] P1: Editing a day's own calorie target silently overwrites the everyday one
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/ui/diary/DiaryScreen.kt:536` (`var justThisDay by remember { mutableStateOf(false) }`, always starting false), `:629` (`day.takeIf { justThisDay }`), `:317-321` (the dialog is never told `state.targetIsForThisDay`); `ui/diary/DiaryViewModel.kt:337-345`; `data/repo/MacroTargetRepository.kt:37-56`. The helper written for exactly this is `ui/diary/TargetRevision.kt`, wired only to the recommendation button.
  Problem: set an everyday target, then a Saturday-only target. Come back the next Saturday, open Target (it prefills the Saturday figure), change it and save. Because the toggle reset to false, the write lands on the everyday row: the other six days lose their target, the Saturday row is unchanged, the screen looks the same and the snackbar says the target was set. `TargetRevision` exists because "the button would appear to do nothing at all", and the dialog people actually use bypasses it.
  Evidence: adversarial read. `TargetRevisionTest` covers the pure function, not the dialog.
  Fix: seed `justThisDay` from `state.targetIsForThisDay` and route the save through `TargetRevision.rowFor`.
  Acceptance: the sequence above leaves the everyday target untouched and updates the Saturday row; a test asserts both rows.
  Confidence: Verified
  Effort: S

- [ ] P1: Switching the target dialog between grams and percent reinterprets the numbers
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/ui/diary/DiaryScreen.kt:531-535` (the three fields are seeded once by `fieldFor(..., basis)` under a `remember` with no key), `:541-548` (`grams()` reads the live `basis`), `:563-577` (the chips only mutate `basis`).
  Problem: with a target of 2,000 kcal and 150 g of protein, tapping the percent chip to look at the split leaves "150" in the box and now reads it as 150 percent, so saving stores 750 g of protein. The same happens to carbohydrate and fat. Nothing on screen changed except which chip is lit.
  Evidence: adversarial read. No test exists for the dialog; `MacroTargetTest` covers `gramsFromPercent` in isolation.
  Fix: convert the three field values when the basis changes, so the numbers on screen keep meaning the same thing.
  Acceptance: switching basis and saving without editing leaves the stored grams unchanged; a test asserts a round trip through both bases.
  Confidence: Verified
  Effort: S

- [ ] P2: Two measurement paths discard a value in silence
  Category: ux
  Where: `app/src/main/java/com/weighttrack/ui/measurements/MeasurementsScreen.kt:176` (Save has no `enabled`) with `ui/measurements/MeasurementsViewModel.kt:181-187` (`if (value == null || value <= 0) { _editor.value = null; return }`); and `MeasurementsViewModel.kt:128-135,138` with `:110`, where a non-blank but unreadable entry enters `changed` and is then dropped by `mapNotNull`, so the site is neither measured nor carried forward.
  Problem: typing `0` or `34.5.5` into a single measurement closes the dialog and writes nothing, indistinguishable from a successful save. In a set, one mistyped box removes that site from the set entirely, including the carry-forward it would have had if the box had not been touched. No message names the dropped site in either case.
  Evidence: adversarial read. No test names `saveEditor`; `MeasurementSetEditorTest > changing one site saves every site that has ever been measured` uses only parsable values.
  Fix: disable Save while any touched field is unreadable and say which one, and treat an unreadable box in a set as untouched so the stored value is carried.
  Acceptance: a test saves a set with one unreadable box and asserts that site carries its previous value; the single editor refuses to close on `0`.
  Confidence: Verified
  Effort: S

- [ ] P2: In pounds, no water target or serving can be selected
  Category: ux
  Where: `app/src/main/java/com/weighttrack/ui/water/WaterScreen.kt:56-75` (the fluid-ounce options are 1479, 1893, 2366 and 2957 ml, and the servings 177, 237, 355 and 473 ml), `:241` and `:253` (`selected = state.targetMl == amount`); `data/prefs/SettingsRepository.kt:45-46` (defaults 2,000 ml and 250 ml).
  Problem: a fresh install in pounds shows both chip rows with nothing selected, so it reads as no target set, while the progress bar and the "to go" figure are still computed against 2,000 ml and the main button offers an 8.5 fl oz serving. This is the same defect already recorded and fixed for the goal band by `GoalBands.nearest`, never applied here.
  Fix: select the nearest option to the stored value, as the goal band does.
  Acceptance: with the default target and fluid-ounce units, one target chip and one serving chip are selected; a test asserts it for both unit systems.
  Confidence: Verified
  Effort: S

- [ ] P2: The dose field is the one number in the app that bypasses the locale number reader
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/ui/medication/MedicationScreen.kt:295` (`amount.replace(',', '.').toDoubleOrNull()`), `:313-315` (the filter keeps anything `Char.isDigit()`, which is Unicode-aware), `:342` (`enabled = parsed != null && parsed > 0`). Every other numeric field goes through `core/format/LocaleNumbers.kt`.
  Problem: on a phone with an Arabic, Persian or Devanagari keyboard the filter accepts the local digits into the box but drops the local decimal separator, and `toDoubleOrNull` reads only ASCII, so the digits appear on screen and Save stays grey with no explanation. That is exactly the failure `LocaleNumbers` was written to fix.
  Fix: parse with `LocaleNumbers.decimal` and filter with the same `keepNumeric` the other fields use.
  Acceptance: a Robolectric test under a locale with non-ASCII digits enters a dose and asserts Save is enabled and the stored value is right.
  Confidence: Verified
  Effort: S

- [ ] P2: A permanently denied camera or Bluetooth permission has no way out
  Category: ux
  Where: `app/src/main/java/com/weighttrack/ui/barcode/ScanScreen.kt:80-86,112-114` (the only remedy is to launch the same request again) and `ui/scale/ScaleScreen.kt:236-238` for `PERMISSION_MISSING`; compare `ScaleScreen.kt:241-251`, where a lost bond correctly opens `ACTION_BLUETOOTH_SETTINGS`.
  Problem: after two denials Android returns denied immediately without showing a dialog, so the "Allow the camera" and "Allow" buttons do nothing visible, for ever, with no copy saying the permission now has to be changed in system settings. The pattern for the fix is three lines away in the same file.
  Fix: when a request returns denied without a prompt, show a line saying so and a button that opens `ACTION_APPLICATION_DETAILS_SETTINGS`.
  Acceptance: a test drives the permanently-denied state and asserts the settings affordance is present on both screens.
  Confidence: Verified
  Effort: S

- [ ] P2: Both food-search URL builders corrupt ordinary queries, and one failure is swallowed
  Category: correctness
  Where: `core/src/main/java/com/weighttrack/core/nutrition/OpenFoodFacts.kt:128-129` (`filter { it.isLetterOrDigit() || it in "+-_.&" }`, so `&` passes through raw and an apostrophe is deleted); `core/nutrition/UsdaFoodData.kt:88` (`replace(" ", "%20")` followed by a filter that keeps `%`); `ui/food/FoodViewModel.kt:136-137` (`is Result.NoKey -> Unit; else -> Unit`).
  Problem: searching "Ben & Jerry" builds `?q=Ben+&+Jerrys&page_size=20`, so the server sees only "Ben " and returns the wrong results. Searching "2% milk" with a USDA key builds `query=2%%20milk`, an invalid escape, and the resulting error lands in `else -> Unit` and is discarded, so the screen says nothing was found. Accented words pass through unencoded because `isLetterOrDigit` is Unicode-aware.
  Fix: percent-encode the query properly in both clients rather than filtering characters out, and surface the USDA failure the way the Open Food Facts one is surfaced.
  Acceptance: `OpenFoodFactsClientTest` gains cases for an ampersand, an apostrophe and an accent asserting the encoded URL; a USDA failure shows a message rather than an empty result.
  Confidence: Verified
  Effort: S

- [ ] P2: A one-unit milestone step lays fifty labels across one non-scrolling row
  Category: visual
  Where: `app/src/main/java/com/weighttrack/ui/goal/GoalScreen.kt:193-223` (`Milestones.generate(...)` then `forEach { Text(...) }` inside one `Row` with `SpaceBetween`, and the same list as ticks on `GoalProgressBar`); `core/src/main/java/com/weighttrack/core/math/GoalProjection.kt:321` caps generation at 500 marks.
  Problem: at 120 kg with a 70 kg target, choosing the 1 kg milestone chip, one tap from the default, produces fifty labels squeezed into one row that neither wraps nor scrolls, running off both edges, with fifty ticks on the preview bar. The core function has a guard against an unusable list; the screen that renders it has none.
  Fix: show a count and the next few marks rather than all of them, or wrap them in a `FlowRow` with a cap and a "and N more" line.
  Acceptance: a fixture with fifty milestones renders inside the screen bounds; the state gate covers it.
  Confidence: Verified
  Effort: S

- [ ] P2: A typed weight has no plausibility floor while every imported one does
  Category: correctness
  Where: `app/src/main/java/com/weighttrack/ui/components/WeightKeypad.kt:81` (`isValid` is only `displayValue > 0.0`); `ui/goal/GoalViewModel.kt:138-141`; `ui/onboarding/OnboardingViewModel.kt:148-150`; `core/src/main/java/com/weighttrack/core/model/WeightPlausibility.kt:8-9` (20 to 400 kg), applied at `ble/ScaleReadingRouter.kt:80`, `health/HealthConnectSync.kt:769` and `core/io/WeightCsvImporter.kt:248`, and nowhere under `ui/`.
  Problem: the same 100 g that a Bluetooth scale refuses as implausible and a CSV rejects with a row problem can be typed in as a first weight or a goal, seeding the trend and every projection; five digits give a 9,999.9 kg goal. The shared policy exists precisely so every boundary agrees, and the typed boundary was left out.
  Fix: run typed weights through `WeightPlausibility` and say which figure is out of range rather than silently accepting it.
  Acceptance: typing 0.1 kg on the log, goal and onboarding screens leaves Save disabled with a reason; a test covers all three.
  Confidence: Verified
  Effort: S

- [ ] P2: Six English words escape the translation gate through shapes it cannot match (refines the "(showing)" item)
  Category: ux
  Where: `app/src/main/java/com/weighttrack/ui/diary/DiaryScreen.kt:142` (`"${-left.kcalRounded} over"`); `ui/fasting/FastingScreen.kt:202` (`"of ${formatTarget(...)}"`), `:367` and `:374` (`... + " at " + ...`); `ui/measurements/MeasurementsScreen.kt:303` (`"Updated ${...}"`) and `:310` (`?: "Add"`); `ui/barcode/ScanScreen.kt:133` (`"Read $it"`). The gate: `app/src/test/java/com/weighttrack/ui/NoHardcodedTextTest.kt:46-52` (a sink needs the run-up to end at `text =` plus an optional bare `if (...)`) and `:60` (the alternative branch needs `else` or `?:` at the end of the run-up).
  Problem: "over", "of", " at ", "Updated" and "Add" render un-accented in every language. "Add" is the affordance on every never-measured row and "Updated" on all thirteen. The gate misses them because `text = if (x) { ... } else { "lit" }` puts a brace after the condition, `?.let {` puts a brace before the literal, and the elvis has no resource in front of it. None of these is the concatenation shape the "(showing)" item names, so both fixes are needed.
  Fix: move all six into resources, and extend the gate to carry sink state across an opening brace and across `?:` with no resource before it. Add one self-test line per shape that must be caught.
  Acceptance: the pseudo-locale build accents all six; each new self-test goes red when its handling is removed.
  Confidence: Verified
  Effort: S

#### P2

- [ ] P2: The chosen theme is not applied to the launch window, the first frame, or the status-bar icons
  Category: visual
  Where: `app/src/main/res/values/themes.xml:4` with `values/colors.xml:3` (`#FFFBFE`) and `values-night/colors.xml:3` (`#000000`); `app/src/main/java/com/weighttrack/MainActivity.kt:99` (`enableEdgeToEdge()` with no `SystemBarStyle`); `MainActivity.kt:118` (`settings?.themeMode ?: ThemeMode.SYSTEM` while `data/prefs/SettingsRepository.kt:31` defaults to `AMOLED`); `MainActivity.kt:142` (blank `Box` drawn on the SYSTEM-derived surface while settings load).
  Problem: all three follow the phone's night mode, not `ThemeMode`. The default install is AMOLED black; on a phone in light mode it launches with a white window, draws a white first frame, then snaps to black, and `enableEdgeToEdge` picks dark status-bar icons because the system is light, so the clock, battery and signal icons are black on the app's black background and are invisible for the whole session. Choosing Light on a dark phone gives the mirror image.
  Evidence: measured on the API 35 AVD at HEAD `9d70b97`. With `cmd uimode night no` and the app on its default theme, a screencap of the status-bar strip (y 10 to 70, every second column) contains **zero** non-black pixels, so nothing in the system bar is legible. With `cmd uimode night yes` the identical strip has 1,378 non-black pixels reaching full white. `Theme.kt:40-55` resolves the scheme from `themeMode`, but nothing feeds that resolution back to the window or the bars; `AmoledColors.background = 0xFF000000` (`Color.kt:88`).
  Fix: call `enableEdgeToEdge(statusBarStyle = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT), navigationBarStyle = ...)` inside `setContent` once `settings` has loaded, re-invoking it when `themeMode` changes; make the loading placeholder use the stored default (`ThemeMode.AMOLED`) rather than SYSTEM; set `android:windowBackground` to black in both `values` and `values-night` (or add a light theme overlay applied in `onCreate` for LIGHT) so the launch window matches the default.
  Acceptance: on the API 35 AVD with `cmd uimode night no`, a fresh install shows a black launch window and light status-bar icons; with `night yes` and Light chosen, a white window and dark icons. `ScreenStateCoverageTest` gains an assertion that the window background colour matches the scheme background for each appearance.
  Confidence: Verified
  Effort: S

- [ ] P2: Photo captions are unreadable in the default dark themes
  Category: visual
  Where: `app/src/main/java/com/weighttrack/ui/photos/PhotosScreen.kt:263-275`; `ui/theme/Color.kt:78-79` (`inverseOnSurface = Slate900` in `DarkColors`, inherited by `AmoledColors`).
  Problem: the date and weight on every photo tile are drawn in `inverseOnSurface` over `scrim.copy(alpha = 0.55f)`. In light mode that is near-white on a dark band and reads fine. In Dark and Black, the default, `inverseOnSurface` is `#0F172A`, so the caption is near-black text on a 55 percent black band over the photo. The `ComparisonCard` beneath uses `onSurfaceVariant` on the card and is fine.
  Evidence: colour values read from `Color.kt`; the caption band is not a themed surface, it is a fixed dark overlay.
  Fix: a fixed overlay wants fixed text: use `Color.White` (or `LightColors.inverseOnSurface`) for both caption lines regardless of scheme, or replace the scrim with `inverseSurface.copy(alpha = 0.7f)` so `inverseOnSurface` is the right pairing in every scheme. Add the photo grid fixture to `ScreenStateCoverageTest` with a contrast assertion on those two nodes.
  Acceptance: photo tile captions read white on dark in Black, Dark and Light; the gate asserts a contrast ratio of at least 4.5 on them in all appearances.
  Confidence: Verified
  Effort: S

- [ ] P2: Segment chips announce no selection state or role to TalkBack
  Category: a11y
  Where: `app/src/main/java/com/weighttrack/ui/components/Common.kt:94-121` (`SegmentButton` is `Surface(onClick)` with no `selectable`, `Role` or `stateDescription`); used by every `ChipRow` in `ui/settings/SettingsControls.kt:44-56` (units, theme, smoothing, sex, activity, week start, weekly summary day and hour), `ui/settings/ReminderCard.kt:52-58` (reminder days), `ui/log/LogWeightScreen.kt:180-186` (tags), `ui/goal/GoalScreen.kt` (milestone and band chips). Compare `ui/charts/ChartsScreen.kt:174-179`, which does it right with `selectable(selected, role = Role.RadioButton)` inside `selectableGroup()`.
  Problem: a screen reader hears "Kilograms, button" for every chip and cannot tell which unit, theme, sex, activity level or reminder day is chosen; the selected state is conveyed only by colour and border, which also fails the colour-alone rule. `ScreenStateCoverageTest` checks that pressables have a label, not that they carry state.
  Fix: give `SegmentButton` a `Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)` (or `Role.Checkbox` for the multi-select tag and day rows via a `role` parameter), wrap `ChipRow` in `selectableGroup()`, and drop the `Surface(onClick)`. Extend the gate with "every selectable says whether it is selected" over `SemanticsProperties.Selected`.
  Acceptance: TalkBack reads "Kilograms, selected, radio button"; the new gate assertion fails if `selectable` is removed.
  Confidence: Verified
  Effort: S

- [ ] P2: "(showing)" is hardcoded English, and the translation gate cannot see a literal joined with `+`
  Category: ux
  Where: `app/src/main/java/com/weighttrack/ui/settings/SettingsPersonSections.kt:220` (`text = if (showing) profile.name + "  (showing)" else profile.name`); `app/src/test/java/com/weighttrack/ui/NoHardcodedTextTest.kt` (`ownComposableSink` matches a literal only when its run-up ends in a sink call or a named argument).
  Problem: the active-profile marker in Settings is untranslatable and padded with two spaces, and it survived the gate rebuilt in commit `713dc66` because the literal follows `+` rather than `text =`. Any future literal in the same shape passes too.
  Evidence: read directly; pseudo-locale build would show it un-accented.
  Fix: add `settings_profile_showing` (`%1$s (showing)`) and use it; extend the gate so a literal preceded by `+` or `plus(` on a line whose enclosing expression started at a sink is still a finding (track the sink state across `+`), and add a self-test line `Text(text = name + " (x)")` that must be caught.
  Acceptance: the pseudo-locale build accents the marker; the gate self-test goes red when the `+` handling is removed.
  Confidence: Verified
  Effort: S

- [ ] P2: Restores, exports, archive writes and manual sync die with the Settings screen
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/ui/settings/SettingsViewModel.kt:88-129` (all four controllers are built with `scope = viewModelScope`); `ui/settings/BackupSettingsController.kt:224-233` (`runBackup` launches into that scope); `ui/settings/SyncSettingsController.kt:203-224` (`syncNow`); `ui/WeightTrackApp.kt:645` (Settings is a tab destination whose entry is popped for real by the system Back button).
  Problem: pressing Back on Settings while an archive of photos is being written, a backup restored, or a manual sync running cancels the coroutine. The Room transaction rolls back and the archive undo runs, so the database is not corrupted, but the person sees nothing: no message, the file is half-written on the provider (for exports, `writeText` is cut off mid-stream), and the sync's own file is never published. Tab switches keep the ViewModel alive (`saveState`), so this needs the Back button or a process-level clear, which is common with a sleeping phone.
  Evidence: traced; `runBackup` catches the `CancellationException` into a message no longer observed. No test drives a cancellation.
  Fix: run these four operations in an application-scoped `CoroutineScope` (a `@Singleton` `AppScope` provided from `DataModule`, `SupervisorJob + Dispatchers.Default`) and have the controllers observe their `busy`/`syncing` flags from a singleton holder, or hand them to WorkManager as expedited one-time work. Keep the message sink on the ViewModel but source it from the holder.
  Acceptance: start an archive export, press Back immediately, reopen Settings: the export finishes and its message appears. A test cancels the ViewModel scope mid-restore and asserts the restore still completes.
  Confidence: Likely
  Effort: M

- [ ] P2: Four failures are swallowed with no log line and no message
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/security/SecretStore.kt:127-130` (`reveal` returns null on a keystore failure; `protect` beside it reports through `RuntimeLog`); `app/src/main/java/com/weighttrack/notifications/ReminderScheduler.kt:65-67` (`runCatching { setAndAllowWhileIdle }` discarded); `app/src/main/java/com/weighttrack/data/io/AutoBackupWorker.kt:143-149` (prune of `.partial` and old copies wrapped in discarded `runCatching`); `app/src/main/java/com/weighttrack/sync/SyncWork.kt:81` (a `Result.failure` from `healthConnect.sync()` becomes `RETRY` with no cause written, while the throw path at `:68` logs `BACKGROUND_SYNC_THREW`).
  Problem: after a keystore key is invalidated (new screen lock, restore to a new phone) the WebDAV password and USDA key decrypt to null and sync and food search start failing with the server's error and no hint that the secret needs re-entering; a reminder that could not be booked stays "on" in Settings; a backup folder that refuses deletes fills up with weekly copies for ever; an hourly Health Connect exchange can retry indefinitely with nothing in the activity log. Every one of these is the kind of thing `RuntimeLog` exists to answer.
  Fix: `reveal` writes `LogEvent.SECRET_UNREADABLE` and the sync card shows "Enter the password again" when the stored credential is present but unreadable; `schedule` writes `LogEvent.REMINDER_NOT_BOOKED` with the exception class; the prune writes `BACKUP_PRUNE_FAILED`; `SyncWork` records the failure's `HealthFailure` kind before returning `RETRY`. Add one `RuntimeLogTest` case per event.
  Acceptance: each path, forced in a test, leaves the named event in the log; the sync card shows the re-enter prompt when `reveal` returns null for a stored value.
  Confidence: Verified
  Effort: S

- [ ] P2: The scanner blocks the main thread on camera start and shows a black screen when binding fails
  Category: ux
  Where: `app/src/main/java/com/weighttrack/ui/barcode/ScanScreen.kt:163-166` (`future.get()` inside `LaunchedEffect`, which runs on the main dispatcher; failure returns silently); `:195-203` (`runCatching { provider.unbindAll(); bindToLifecycle(...) }` discarded).
  Problem: `ProcessCameraProvider.getInstance().get()` blocks the UI thread until CameraX initialises, which is a visible freeze on first open; if initialisation or binding throws (camera held by another app, no back camera, CameraX failing on a device) the preview stays black with no message and no way out but Back.
  Fix: `await()` the future (`kotlinx-coroutines-guava` or `suspendCancellableCoroutine`), and on either failure set a state the screen renders as "The camera could not be started" with a retry and a "Type the barcode" fallback (the Foods screen already has the number entry).
  Acceptance: forcing `bindToLifecycle` to throw in a Robolectric test shows the message; the main thread is not blocked during camera start (StrictMode or a `Looper` assertion in the test).
  Confidence: Verified
  Effort: S

- [ ] P2: Wallpaper colours silently cancel Black
  Category: visual
  Where: `app/src/main/java/com/weighttrack/ui/theme/Theme.kt:48-51` (dynamic scheme wins before the `amoled` branch); `ui/settings/SettingsBasicSections.kt:70-85` (the two controls sit together with no explanation).
  Problem: with "Use wallpaper colours" on, choosing Black gives `dynamicDarkColorScheme`, whose surfaces are grey; the chip says Black and the screen is not. The two settings override each other with nothing on screen saying so.
  Fix: when `amoled` and dynamic are both on, take the dynamic scheme and `copy(background = Black, surface = Black, surfaceContainerLowest = Black, ...)` as `AmoledColors` does over `DarkColors`; keep accents dynamic. Add the pair to the appearance fixtures.
  Acceptance: Black plus wallpaper colours renders a pure black background with wallpaper-derived primary; the state gate renders that appearance.
  Confidence: Verified
  Effort: S

#### P3

- [ ] P3: The Health Connect rationale still stacks a second activity on Android 14 and later
  Category: reliability
  Where: `app/src/main/AndroidManifest.xml` (`<activity-alias android:name="ViewPermissionUsageActivity">`, targeting `.MainActivity`); `app/src/main/java/com/weighttrack/MainActivity.kt` `openAt`.
  Problem: `android:launchMode="singleTop"` on `MainActivity` fixed the shortcuts, both notifications and the file filter, but the Android 14 and later route into the rationale arrives on the alias, which is a different component name. The top-of-task match is against that name, so a running `MainActivity` is not matched and a second instance is started. Tapping through from Health Connect's own settings while WeightTrack is open therefore still discards whatever was on screen.
  Evidence: found by the fresh-context review of commit `338573a`, which confirmed the alias is a distinct component for launch-mode matching. `LauncherShortcutsTest > the activity every reopening aims at is single top` asserts the launch mode on `MainActivity` only, so it does not cover the alias.
  Fix: check whether `android:launchMode` on an `activity-alias` is honoured on the minimum supported release; if it is not, have the alias target a thin trampoline that forwards to `MainActivity` with `SINGLE_TOP` set, or set the flag on the intent Health Connect is answered with.
  Acceptance: with the app open on Foods, entering the rationale from Health Connect's settings and pressing back returns to Foods with its state intact; the manifest test covers the alias as well as the activity.
  Confidence: Likely
  Effort: S

- [ ] P3: Stat values clip at large font sizes
  Category: a11y
  Where: `app/src/main/java/com/weighttrack/ui/components/Common.kt:136` (`StatTile` value `maxLines = 1` with the default `TextOverflow.Clip`), `:138-142` (caption `maxLines = 2`, clip); `ui/home/HomeScreen.kt:349-372` (two tiles side by side at 22 sp).
  Problem: at 200 percent font scale "-0.45 kg" and "350 kcal" in half a phone width are wider than the tile and are cut mid-glyph. `ScreenStateCoverageTest` checks clipping only for pressable nodes, so text is not gated.
  Fix: `overflow = TextOverflow.Ellipsis` plus `softWrap = false` on the value and let the tile `Column` wrap the two tiles with `FlowRow` at large scale, or use `autoSize`. Extend the gate to assert every `Text` node's bounds lie inside its parent for the 200 percent appearances.
  Acceptance: the Home rate card at font scale 2 shows both values whole; the extended gate fails on the current code.
  Confidence: Needs-repro (set `adb shell settings put system font_scale 2.0` on the API 35 AVD and open Home with a goal set)
  Effort: S

- [ ] P3: Watch picker buttons are unlabelled, and three wear strings drift from the phone
  Category: a11y
  Where: `wear/src/main/java/com/weighttrack/wear/WearMainActivity.kt:181-182` (`Button(label = { Text("-") })` and `"+"`, no `contentDescription`); `wear/src/main/res/values/strings.xml` `wear_queued` ("when they are next together": "they" has no antecedent), `wear_no_value` (`--` while the phone uses `—`), `wear_failed` (asks the person a diagnostic question instead of saying what happened to the reading); `wear/src/main/java/com/weighttrack/wear/WeightTileService.kt:74-76` (hardcoded white, `#B4C0CE`, `#6EE7B7` text with no background, ignoring the tile theme).
  Fix: give both buttons a `contentDescription` ("Lower by one step" / "Raise by one step" with the unit), reword the three strings to match the phone's phrasing, and take the tile colours from `androidx.wear.protolayout.material3` `Colors` rather than literals. Cover the descriptions in `WearWeightPickerTest`.
  Acceptance: the wear semantics tree shows both descriptions; strings match the phone's wording for the same state.
  Confidence: Verified
  Effort: S

- [ ] P3: Background entry points lack the guards the foreground has
  Category: reliability
  Where: `app/src/main/java/com/weighttrack/notifications/ReminderReceiver.kt:172-183` (`BootReceiver` launches with `try/finally` and no `catch`, so a throw from `reschedule` crashes the process at boot and leaves every reminder unbooked until the app is next opened; compare `ui/AppViewModel.kt:72-99`, which wraps the same steps in `runtimeLog.step`); `app/src/main/java/com/weighttrack/sync/SyncWorker.kt:118` (periodic request with no `NetworkType.CONNECTED` constraint, so a WebDAV sync wakes the phone hourly with no network); `app/src/main/java/com/weighttrack/ble/ScaleConnection.kt:369-380` (`gatt.close()` skipped when `BLUETOOTH_CONNECT` was revoked mid-session, leaking the client).
  Fix: wrap the receiver body in `runtimeLog.step`; add the network constraint when the mode is WebDAV; always call `close()` (it needs no permission) and only gate `disconnect()`.
  Acceptance: a throwing scheduler in a `BootReceiver` test leaves a log event and no crash; `SyncWorkerTest` asserts the constraint for WebDAV mode.
  Confidence: Verified
  Effort: S

- [ ] P3: Hardening: an explicit VIEW intent accepts any scheme, and a photo import reads without a ceiling
  Category: security
  Where: `app/src/main/java/com/weighttrack/MainActivity.kt:95-96` (`fileFrom` takes `intent.data` whatever its scheme; the manifest's `content`-only filter binds implicit intents only and `MainActivity` is exported); `app/src/main/java/com/weighttrack/data/repo/ProgressPhotoRepository.kt:169-172` (`input.copyTo(output)` with no size bound, unlike `BackupService.readText` at `:784-796` which caps at `MAX_BACKUP_BYTES`).
  Problem: another app can hand WeightTrack a `file://` or hostile `content://` URI and have it read and parsed (bounded at 10 MB and gated by the restore confirm, so low impact); a hostile picker can stream an unbounded photo into private storage until the disk is full (recovered as `NO_ROOM`, but only after filling the device).
  Fix: `fileFrom` keeps only `scheme == "content"`; the photo copy uses a counting stream with a 50 MB ceiling and refuses past it.
  Acceptance: `OpenedFileKindTest` gains a `file://` case that is refused; `ProgressPhotoFailureTest` gains an over-size source that fails fast with `TOO_LARGE`.
  Confidence: Verified
  Effort: S

- [ ] P3: Visual consistency: one M3 Card, two button radii, and the History inset
  Category: visual
  Where: `app/src/main/java/com/weighttrack/ui/medication/MedicationScreen.kt:89` (the only `Card(colors = CardDefaults.cardColors())` in the app; every other grouped surface is `SectionCard` at `ui/components/Common.kt:37-53`); `RoundedCornerShape(8.dp)` on buttons in `ui/log/LogWeightScreen.kt:141,149,165,197`, `ui/onboarding/OnboardingScreen.kt:104`, `ui/WeightTrackApp.kt:534` against the theme's 6 dp `small` shape everywhere else; `ui/history/HistoryScreen.kt:202,305` (rows padded 14 dp inside a list already padded 16 dp, and the divider inset does not line up with the row content); `ui/history/HistoryScreen.kt:189-198` (selected row painted `primaryContainer` by `Modifier.background`, which does not switch content colour, so the text stays `onSurface`/`onSurfaceVariant`; readable in the four static schemes, unpaired under dynamic colour).
  Fix: replace the `Card` with `SectionCard`; drop the explicit 8 dp shapes so buttons take `MaterialTheme.shapes.small`, or raise `small` to 8 dp in `Shape.kt` once; make the History row inset 0 inside the 16 dp list and align the divider; wrap the selected row's content in `CompositionLocalProvider(LocalContentColor provides onPrimaryContainer)` or use `Surface(color = primaryContainer)`.
  Acceptance: no `Card(` outside `SectionCard`; a grep for `RoundedCornerShape(8.dp)` on buttons returns nothing; the docs/screenshots set is re-captured.
  Confidence: Verified
  Effort: S

- [ ] P3: Dead code, duplicates and the lint warnings worth clearing
  Category: maintainability
  Where: unreferenced in every source set: `core/src/main/java/com/weighttrack/core/format/Formatters.kt:59` `energyBalance`, `core/math/Insights.kt:135` `roundedGrams`, `core/math/UnitConverter.kt:100,105` `mlToDisplay`/`displayToMl`, `core/model/WeightPlausibility.kt:16` `isTimestampPlausible` (superseded by `problem()` beside it); `shortUuid` duplicated at `app/.../ble/ScaleConnection.kt:398`, `ble/ScaleScanner.kt:248` and `core/scale/VendorScaleProtocol.kt:50`; three little-endian u16 readers (`VendorScaleProtocol.kt:55-66`, `StandardScaleParser.kt:18-24`, `MiScaleParser.kt:118`); the identical 21-line `signingConfigs` block in `app/build.gradle.kts:25-45` and `wear/build.gradle.kts:29-49`; lint: five unused strings (`food_last_checked`, `food_never_checked`, `home_rate_of_change`, `sync_certificate_unreadable_stored`, plus `R.array.android_wear_capabilities`, which is a false positive read by Play services by name and should be suppressed with a comment), `EmptySuperCall` at `ui/scale/ScaleViewModel.kt:471`, `ObsoleteSdkInt` at `notifications/ReminderScheduler.kt:105` and `WeeklySummaryScheduler.kt:80` and `mipmap-anydpi-v26`, `LocalContextResourcesRead` at `ui/goal/ProjectionExplainer.kt:63`, `ReportShortcutUsage` at `shortcuts/LauncherShortcuts.kt:52` (call `reportShortcutUsed` when a shortcut route is taken so the launcher can rank them).
  Fix: delete the dead functions, route the two app `shortUuid` callers to the core one, move the signing block into `buildSrc`, resolve each lint line.
  Acceptance: `lintPlayDebug` warning count drops from 57; `lint.xml` carries the one documented suppression.
  Confidence: Verified
  Effort: S

- [ ] P3: Microcopy specifics not covered by the plurals and string-hygiene items above (refines both)
  Category: ux
  Where: `app/src/main/res/values/strings.xml`: `projection_days_and_weigh_ins` `quantity="one"` reads "%1$d days" (the singular item is for one day and says days); `settings_import_skipped` and `settings_health_connect_removed` are leading-comma fragments glued onto another sentence in code (`ui/settings/BackupSettingsController.kt:118-124`) and the second is ungrammatical on its own ("removed 3 deleted elsewhere"); four heading questions with no question mark (`onboarding_how_active_are_you`, `onboarding_what_do_you_weigh_today`, `onboarding_where_are_you_heading`, `diary_what_was_it_optional`) beside two that have one; `sync_address_missing` "That address is not there." for a 404; `scale_something_went_wrong` with no next step while its neighbours have one; the unit label "lb" is a Kotlin literal in `ui/log/LogWeightScreen.kt:130`, `ui/goal/GoalScreen.kt:132` and `ui/onboarding/OnboardingScreen.kt:356` while every other unit label comes from `WeightFormatter`; `restore_count_weights` says "Weigh-ins" in a dialog whose messages say "readings"; `charts_range_change_against` reuses `%2$d` in two grammatical roles.
  Fix: one plural with a correct singular; two whole sentences for the import and Health Connect summaries built with placeholders rather than concatenation; question marks; a 404 message that says the folder or file was not found on the server; a next step for the scale error; `WeightFormatter.unitLabel` for the stones case; one word for a reading in the restore dialog; a separate placeholder for the second span.
  Acceptance: `StringRenderingTest` renders the singular projection line and the two summaries; the pseudo-locale build shows no bare "lb".
  Confidence: Verified
  Effort: S

- [ ] P3: Refinement of "Accessible equivalents for chart interaction and the goal bar": the History row also needs its selection announced
  Category: a11y
  Where: `app/src/main/java/com/weighttrack/ui/history/HistoryScreen.kt:194-198` (`combinedClickable` with no `Role` and no `Selected`/`stateDescription`; the tick box at `:209-231` is a plain `Box`).
  Fix: in selection mode use `Modifier.toggleable(value = selected, role = Role.Checkbox)` and expose `stateDescription`; outside it, `Role.Button`. Add the History selection fixture to the state gate.
  Confidence: Verified
  Effort: S

- [ ] P3: Unaudited, needs a pass
  Category: testing
  Where: whole repo.
  Problem: what the 2026-09-03 and 2026-09-04 sessions did NOT cover. Done since: all four adversarial deep reads reported (sync, Health Connect with alarms and widgets, data with backup, secondary screens), the three P1 items each survived a fresh-context refutation, and the shortcut and theme findings were reproduced on the API 35 AVD. Still not covered: (1) the `:wear` module beyond its strings and the tile colours, and the phone-to-watch hop, which cannot be exercised on this machine (see `Roadmap_Blocked.md`); (2) the BLE stack against real hardware, `core/scale` protocols being tested only against published frames; (3) `:benchmark` was not run, so no performance regression was measured this pass; (4) the release tooling under `tools/` was not exercised; (5) instrumented tests (`app/src/androidTest`, the Room migration suite) were not run; (6) no accessibility finding was checked with TalkBack actually speaking, only through the semantics tree; (7) the P2 and P3 findings from the four sweeps were not individually refuted, so treat their confidence as one reviewer's, unlike the P1 items.
  Fix: run the migration suite and the benchmark on the attached phone, and put the sweeps' P2 items through a refutation pass before implementing them.
  Confidence: Verified
  Effort: M

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
