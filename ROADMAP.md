# WeightTrack Roadmap

Single task tracker for the project. Research was refreshed 2026-08-31; prices and app behavior drift, so re-check a store listing before quoting it publicly.

## Positioning

Happy Scale quality, on Android, free forever. No account, no ads, no subscription, no cloud you don't control. Your data lives on your phone and leaves only when you export it.

Current release: v0.4.0. The Quiet Ledger redesign is complete across the eight main screens, with paired emulator evidence under `docs/design/qa/`.

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


- [ ] P2: GLP-1 log: dose, site with rotation, side effects, protein target and a clinician PDF, off by default
  Why: MyFitnessPal (free, 2026-04-28), Noom (free, 2026-06-24) and MyNetDiary (paid, 2026-05-05) all shipped this in 2026; no OSS tracker has it; the science says 1.2 to 1.6 g/kg protein and a lean-mass watch during the 10 to 13 percent loss these users see.
  Evidence: https://finance.yahoo.com/sectors/healthcare/articles/myfitnesspal-launches-comprehensive-glp-1-130000875.html ; https://shotsyapp.com/glp-1-tracker/ (feature bar: site rotation, severity on the dose timeline, level between doses, PDF) ; https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12673431/ ; https://www.clinicalnutritionreport.com/articles/preventing-lean-mass-loss-glp1/
  Touches: new core/medication (dose schedule, site rotation, pharmacokinetic decay per drug from published half-lives), new data/db MedicationDose + SideEffect entities (Room v12, syncable with tombstones, added to DeletionCoverageTest), new ui/medication screen behind a Settings toggle, charts overlay of dose markers on the trend, PDF via android.graphics.pdf, protein target surfaced in the diary when the toggle is on
  Note (2026-08-31 afternoon): demand confirmed this is table stakes. MyFitnessPal made GLP-1 dose, site and side-effect logging free on 2026-04-28 (https://blog.myfitnesspal.com/glp-1-support-medication-tracking/). Shotsy, Pep and Noyoyo are dedicated Android trackers, but none is local-first or exports a clinician report, so the niche below stands.
  Acceptance: a dose logged with a site suggests the next site in rotation; a side effect appears on the same day axis as doses on the chart; the PDF lists doses, side effects and the weight trend for a chosen range with no other data; deleting a dose produces a tombstone; the feature is invisible while the toggle is off. Stays local: Health Connect Medical Records has no Play policy yet (https://developer.android.com/health-and-fitness/health-connect/medical-records).
  Complexity: XL

- [ ] P2: Cycle-aware trend: read MenstruationPeriodRecord and annotate expected water weight
  Why: the measured effect is +0.45 kg at menstruation from extracellular water with no fat change, which the trend and the expenditure loop both misread; only three tiny iOS apps do this and none on Android.
  Evidence: https://onlinelibrary.wiley.com/doi/full/10.1002/ajhb.23951 ; https://mytideline.com/ ; https://support.google.com/googlehealth/answer/14237115?hl=en (Health Connect cycle data) ; https://macrofactor.com/expenditure-v3/ (damped updates during flagged water events)
  Touches: AndroidManifest.xml (READ_MENSTRUATION, its own grant like sleep), health/HealthConnectSync.kt, core/math/Insights.kt (phase markers), ui/charts (shaded phase band, optional), core/math/AdaptiveExpenditure.kt (down-weight days flagged as menstruation)
  Acceptance: with the permission granted and periods recorded, the chart shows a band for each period and the weekday/insight cards exclude those days from "unusual" calls; a core test shows expenditure moves less than 50 kcal when a flagged 0.5 kg spike is injected versus more without the flag; refusing the permission changes nothing else.
  Complexity: L


- [ ] P2: Replace wall-clock tombstone expiry with peer acknowledgements
  Why: Last-write-wins timestamps come from device clocks and tombstones expire after six months, so a skewed or long-offline peer can republish an older live row after its deletion marker disappears.
  Evidence: `core/src/main/java/com/weighttrack/core/sync/SyncMerge.kt:143-158`; `core/src/main/java/com/weighttrack/core/sync/SyncDocument.kt`; https://cse.buffalo.edu/~demirbas/publications/hlc.pdf
  Touches: sync stamp model, `SyncDocument` format, `SyncMerge`, peer retirement UI, migration and convergence tests
  Acceptance: Mutations use a hybrid logical clock with device ID tie-breaking; a tombstone is pruned only after the retention floor and acknowledgement from every non-retired known peer; a fixture returning after nine months cannot resurrect its deleted row; clock rollback and equal-millisecond edits converge identically on every merge order.
  Complexity: XL

### P3

- [ ] P3: New measurement entry pre-fills unchanged sites from the last entry
  Why: openScale 3.1.2 (2026-08-09) added it because thirteen sites retyped every time is why people stop measuring.
  Evidence: https://github.com/oliexdev/openScale/releases/tag/v3.1.2 ; ui/measurements/MeasurementsScreen.kt
  Touches: ui/measurements, data/repo/MeasurementRepository.kt (latest per site)
  Acceptance: opening a new measurement shows last values greyed; saving with none changed creates no row; changing one site saves all thirteen with the carried values marked as carried in the CSV export.
  Complexity: S

- [ ] P3: Editable chart date shortcuts and week-over-week comparison
  Why: Happy Scale 2026.5.3 made the shortcut row editable (last X days, since date, custom range); Withings users' loudest chart complaint is losing the period delta and week-over-week view.
  Evidence: https://apps.apple.com/bw/app/happy-scale/id532430574 ; https://support.withings.com/hc/en-us/community/posts/11251967828497 ; ui/charts/ChartsScreen.kt
  Touches: ui/charts/ChartsScreen.kt (range chips become editable, "since" picker), core/math/Analytics.kt (this week vs last week delta already derivable from weekly bars)
  Acceptance: a custom "since 2026-01-01" chip persists across launches; the header shows the change over the chosen range and versus the previous equal range.
  Complexity: M

- [ ] P3: Above/below-trend glance mode on the widget and tile
  Why: Hacker's Diet readers ask for a display that shows only whether today is above or below the trend, with no raw number, and the widget already hides the number under the lock.
  Evidence: https://news.ycombinator.com/item?id=39301552 ; widget/WeightWidget.kt ; wear/WeightTileService.kt
  Touches: widget/WeightWidget.kt, wear tile and complication, data/prefs/SettingsRepository.kt (mode)
  Acceptance: with the mode on, widget and tile show an arrow and the delta from trend with no absolute weight; a widget snapshot test asserts no digit sequence resembling a weight.
  Complexity: S

- [ ] P3: Bump Compose BOM to 2026.08.00, Material3 1.4.0, OkHttp 5.5.0
  Why: compose-ui 1.12.0 (2026-08-12) and Material3 1.4.0 (2026-08-26) are stable and the toolchain already meets their AGP 9.2 floor; OkHttp 5.2.1 predates the 5.3.2 timeout-regression fix and the 5.4.0 response-header cap, and 5.5.0 rotated its signing key. (2026-08-31: everything else in the catalog verified current; Kotlin 2.4.20 with the KAPT CVE fix is due September 2026 and can ride along when stable.)
  Evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui ; https://developer.android.com/jetpack/androidx/releases/compose-material3 ; https://github.com/square/okhttp/blob/master/CHANGELOG.md ; gradle/libs.versions.toml
  Touches: gradle/libs.versions.toml
  Acceptance: all four unit suites and both lint tasks green; both release APKs assemble; WebDAV PROPFIND round trip against the recorded-reply test passes.
  Complexity: S

- [ ] P3: Move design-qa.md under docs/ and add CONTRIBUTING.md and SECURITY.md
  Why: design-qa.md is tracked at the repo root outside the documented doc set, and there is no contribution or vulnerability-report guidance. (2026-08-31: the stale README "Next up: translations" line named here has already been fixed; the rest of the item stands.)
  Evidence: `git ls-files` 2026-08-29; README.md line 104
  Touches: docs/design-qa.md, CONTRIBUTING.md, SECURITY.md, README.md
  Acceptance: root holds only README, CHANGELOG, ROADMAP, LICENSE and build files; README roadmap line names the current next item.
  Complexity: S

### P2 additions from 2026-08-31

### P3 additions from 2026-08-31

- [ ] P3: Add launcher shortcuts for Log weight and Read scale
  Why: Weight entry is the dominant open-app action in a completed trale request, and WeightTrack's three-second logging goal should extend to the Android launcher.
  Evidence: https://github.com/QuantumPhysique/trale/issues/468; no shortcut declaration or `ShortcutManager` usage in the 2026-08-31 repository scan
  Touches: shortcut XML or manager, manifest, navigation deep links, app-lock and onboarding routing tests
  Acceptance: Both shortcuts appear on supported launchers; Log weight and Read scale land on the intended screen after app lock or onboarding is satisfied; denial and unavailable-Bluetooth states remain visible; repeated shortcut use creates no duplicate navigation or saved row.
  Complexity: S

- [ ] P3: Open supported export files directly into a safe import preview
  Why: Users receiving or downloading a WeightTrack CSV or JSON must currently launch the app and browse for it, while a narrow Android file handoff is a requested OSS migration path.
  Evidence: `app/src/main/AndroidManifest.xml`; `app/src/main/java/com/weighttrack/data/io`; https://github.com/simonoppowa/OpenNutriTracker/issues/621
  Touches: narrow `ACTION_VIEW` intent filters, URI permission handling, app-lock routing, import preview, atomic restore tests
  Acceptance: A supported CSV or WeightTrack JSON opened from Files reaches the same preview and atomic import path as the in-app picker; an unrelated, oversized, malformed, or unsupported file changes nothing and shows a clear result; URI access is temporary and survives process recreation only as long as the preview needs it.
  Complexity: S

- [ ] P3: Replace Material Icons Extended with checked-in Material Symbols vectors
  Why: Compose Material no longer recommends or updates the icons library, and the extended bundle increases build and preview cost while WeightTrack uses a finite icon set.
  Evidence: `gradle/libs.versions.toml:63`; `app/build.gradle.kts:158`; `Icons.*` imports under `app/src/main`; https://developer.android.com/jetpack/androidx/releases/compose-material3
  Touches: `app/src/main/res/drawable`, shared icon wrappers, Compose screens and navigation, version catalog
  Acceptance: Every used icon is a reviewed vector resource with correct RTL behavior and content description at the call site; `material-icons-extended` is absent from the resolved graph; phone screenshots and accessibility semantics remain equivalent; release APK size and clean build time are recorded before and after.
  Complexity: M

### Additions from 2026-08-31 (afternoon pass)

- [ ] P3: Make the last two absolute privacy lines as precise as the settings copy
  Why: Onboarding still says "Your readings stay on this phone" and the extraction-rules comment says data is never uploaded anywhere, while user-configured folder or WebDAV sync deliberately moves readings and demographics off the phone; the settings copy was already corrected this cycle and these two were missed.
  Evidence: `app/src/main/res/values/strings.xml:245`; `app/src/main/res/xml/data_extraction_rules.xml:3-5`; `app/src/main/java/com/weighttrack/data/sync/SyncStore.kt` (demographics travel in sync)
  Touches: strings.xml onboarding copy, data_extraction_rules.xml comment
  Acceptance: onboarding states the true contract (data leaves only when the person exports or sets up sync, matching the README's own phrasing); the extraction-rules comment no longer claims "never uploads anywhere"; no other absolute claim survives a grep for "never leave", "stays on this phone", or "never uploads".
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
