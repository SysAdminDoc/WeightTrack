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

- [ ] P1: Distinguish Health Connect token expiry from transient failure
  Why: Any `getChanges` exception currently clears the token and triggers a five-year reread, turning outages and rate limits into expensive full imports.
  Evidence: `app/src/main/java/com/weighttrack/health/HealthConnectSync.kt:401-456`; https://developer.android.com/health-and-fitness/health-connect/sync-data; https://developer.android.com/health-and-fitness/health-connect/rate-limiting
  Touches: `health/HealthConnectSync.kt`, per-profile cursor metadata, worker retry policy, fake-client tests
  Acceptance: Transient, permission, rate-limit, and expired-token failures follow separate paths; only confirmed expiry replaces the cursor; recovery reads a bounded overlap from the last successful timestamp; fixtures prove retry does not duplicate rows or issue a five-year query.
  Complexity: M

- [ ] P1: Store Health Connect origin and add independent read and write controls
  Why: Imported rows do not expose their data origin, while the permission set requests directions and record types that users cannot configure independently, making duplicate and source conflicts hard to diagnose.
  Evidence: `app/src/main/java/com/weighttrack/health/HealthConnectSync.kt`; https://support.google.com/android/answer/12990553?hl=en; https://help.macrofactorapp.com/en/articles/102-integrations; https://github.com/Monkopedia/health-disconnect/issues/93
  Touches: weight source metadata and migration, Health Connect settings, permission contract, history row details, dedupe tests
  Acceptance: Read-only, write-only, and two-way modes request exactly their needed permissions; every imported row displays application and device origin when available; a user can exclude an origin or choose precedence; repeated records from the same origin and client ID remain one row.
  Complexity: L

- [ ] P1: Add an encrypted portable archive with progress photos
  Why: JSON and CSV cannot restore progress-photo files, while a phone-to-phone archive needs confidentiality and tamper detection without exporting Keystore-bound service credentials.
  Evidence: `app/src/main/java/com/weighttrack/data/io/Backup.kt`; `app/src/main/java/com/weighttrack/data/repo/ProgressPhotoRepository.kt`; https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
  Touches: versioned archive codec, backup service, photo repository, export and restore UI, malformed-archive tests
  Acceptance: A password-protected archive restores all structured data and photo bytes with verified hashes; wrong password, modified content, path traversal, excessive expansion, or unsupported version changes nothing; non-secret settings travel, WebDAV passwords and API keys do not; JSON and CSV remain available with a clear photo-exclusion label.
  Complexity: L

- [ ] P1: Add undo for destructive profile and journal actions
  Why: The product promises snackbar undo for records, but profile, photo, fast, water, food, recipe, diary, and goal deletion is immediate while only weight deletion has a complete undo path.
  Evidence: `ROADMAP.md` section `Complaints we will not repeat`; `app/src/main/java/com/weighttrack/data/repo/ProfileRepository.kt`; `app/src/main/java/com/weighttrack/data/repo/ProgressPhotoRepository.kt`
  Touches: repositories for destructive entities, deletion staging, ViewModels and snackbars, file recovery cache, tests
  Acceptance: Each destructive action removes the item immediately and offers one timed undo without a confirmation dialog; undo restores relationships and files with their original sync IDs; expiry writes one tombstone; process recreation during the undo window resolves deterministically and is covered by tests.
  Complexity: L

- [ ] P1: Finish locale-safe text, weekdays, and numeric input
  Why: Biometric, food, and settings literals bypass resources; enum weekdays render in English; several decimal fields use `toDoubleOrNull`, which rejects valid locale separators and digits.
  Evidence: `app/src/main/java/com/weighttrack/security/AppLockSupport.kt:113`; `app/src/main/java/com/weighttrack/ui/food/FoodScreen.kt`; `app/src/main/java/com/weighttrack/ui/diary/DiaryScreen.kt:396-513`; https://developer.android.com/reference/java/text/NumberFormat
  Touches: string resources, biometric prompt builder, weekday presentation, shared locale number parser, `NoHardcodedTextTest`
  Acceptance: German comma decimals, French grouped values, and Arabic digits parse and round-trip in every weight, measurement, serving, and macro field; weekdays and default profile text use the active locale; the hardcoded-text test catches builder arguments, fragments, wrappers, and enum-derived labels.
  Complexity: M

- [ ] P1: Require explicit routing for ambiguous BLE profile matches
  Why: Nearest-last-weight routing accepts any winner inside 8 kg, even when two household profiles are nearly tied and automatic ownership is unreliable.
  Evidence: `app/src/main/java/com/weighttrack/ble/ScaleReadingRouter.kt`; https://support.withings.com/hc/en-us/articles/32171073185937-Scales-Setting-up-your-scale-for-multiple-users
  Touches: `ble/ScaleReadingRouter.kt`, `ui/scale/ScaleScreen.kt`, profile routing preferences, router tests
  Acceptance: A clear nearest match still files automatically; a tie or small margin creates one pending reading and an inline profile picker; choosing a profile files exactly once and records enough context to make the next unambiguous session easier without changing another profile.
  Complexity: M

- [ ] P1: Surface and recover progress-photo failures
  Why: Capture, copy, decode, and database failures collapse to null and can leave the person without an explanation or retry path.
  Evidence: `app/src/main/java/com/weighttrack/data/repo/ProgressPhotoRepository.kt`; `app/src/test/java/com/weighttrack/data/repo/ProgressPhotoRepositoryTest.kt`
  Touches: progress-photo result type, ViewModel, photo UI, diagnostics, orphan cleanup tests
  Acceptance: Invalid format, unreadable URI, insufficient storage, database failure, and missing file each produce a specific toast or inline status and diagnostic event; no failed operation leaves a database row or orphan file; retry succeeds without duplicating the photo.
  Complexity: S

- [ ] P1: Enforce payload budgets for folder and WebDAV sync
  Why: Both targets materialize an entire remote response with no byte, document, row, or string limits, so a malformed peer can exhaust memory before decode.
  Evidence: `app/src/main/java/com/weighttrack/data/sync/FolderSyncTarget.kt:58`; `app/src/main/java/com/weighttrack/data/sync/WebDavSyncTarget.kt:119`; https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html
  Touches: sync target streaming reads, `SyncDocument` validation, engine diagnostics, adversarial fixtures
  Acceptance: Configured hard limits are checked while streaming and before decode; oversized documents, excessive collections, and oversized strings are rejected with the source filename or URL; the last valid local and remote documents remain unchanged; boundary-size tests pass for folder and WebDAV targets.
  Complexity: M

- [ ] P1: Repair corrupt goal dates deterministically
  Why: An invalid stored goal date maps to the current date, so the same damaged row changes meaning every day without a diagnostic.
  Evidence: `app/src/main/java/com/weighttrack/data/db/Mappers.kt`; `app/src/main/java/com/weighttrack/data/db/Entities.kt`
  Touches: goal mapper, migration or repair routine, diagnostics, mapper tests
  Acceptance: A corrupt date maps to one stable fallback derived from stored creation time or is quarantined for repair; the app records the affected sync ID; repeated loads on different dates return the same result; valid rows are unchanged.
  Complexity: S

- [ ] P1: Gate releases on accessibility and UI state coverage
  Why: Current screenshots cover populated AMOLED screens but not light theme, 200 percent font, RTL, pseudo-locales, empty, loading, permission, error, or destructive recovery states.
  Evidence: `docs/screenshots`; `app/src/test/java/com/weighttrack/ui/NoHardcodedTextTest.kt`; https://developer.android.com/develop/ui/compose/accessibility/scalable-content; https://www.w3.org/TR/WCAG22/
  Touches: Compose UI tests, screenshot fixtures, semantics assertions, `design-qa.md`
  Acceptance: The release check renders every top-level screen in light and dark themes at normal and 200 percent font, plus RTL and pseudo-locales; named fixtures cover empty, loading, denied permission, recoverable error, and undo states; tests fail on clipped required controls, missing labels, or touch targets below platform guidance.
  Complexity: M

### P2


- [ ] P2: GLP-1 log: dose, site with rotation, side effects, protein target and a clinician PDF, off by default
  Why: MyFitnessPal (free, 2026-04-28), Noom (free, 2026-06-24) and MyNetDiary (paid, 2026-05-05) all shipped this in 2026; no OSS tracker has it; the science says 1.2 to 1.6 g/kg protein and a lean-mass watch during the 10 to 13 percent loss these users see.
  Evidence: https://finance.yahoo.com/sectors/healthcare/articles/myfitnesspal-launches-comprehensive-glp-1-130000875.html ; https://shotsyapp.com/glp-1-tracker/ (feature bar: site rotation, severity on the dose timeline, level between doses, PDF) ; https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12673431/ ; https://www.clinicalnutritionreport.com/articles/preventing-lean-mass-loss-glp1/
  Touches: new core/medication (dose schedule, site rotation, pharmacokinetic decay per drug from published half-lives), new data/db MedicationDose + SideEffect entities (Room v12, syncable with tombstones, added to DeletionCoverageTest), new ui/medication screen behind a Settings toggle, charts overlay of dose markers on the trend, PDF via android.graphics.pdf, protein target surfaced in the diary when the toggle is on
  Acceptance: a dose logged with a site suggests the next site in rotation; a side effect appears on the same day axis as doses on the chart; the PDF lists doses, side effects and the weight trend for a chosen range with no other data; deleting a dose produces a tombstone; the feature is invisible while the toggle is off. Stays local: Health Connect Medical Records has no Play policy yet (https://developer.android.com/health-and-fitness/health-connect/medical-records).
  Complexity: XL

- [ ] P2: Cycle-aware trend: read MenstruationPeriodRecord and annotate expected water weight
  Why: the measured effect is +0.45 kg at menstruation from extracellular water with no fat change, which the trend and the expenditure loop both misread; only three tiny iOS apps do this and none on Android.
  Evidence: https://onlinelibrary.wiley.com/doi/full/10.1002/ajhb.23951 ; https://mytideline.com/ ; https://support.google.com/googlehealth/answer/14237115?hl=en (Health Connect cycle data) ; https://macrofactor.com/expenditure-v3/ (damped updates during flagged water events)
  Touches: AndroidManifest.xml (READ_MENSTRUATION, its own grant like sleep), health/HealthConnectSync.kt, core/math/Insights.kt (phase markers), ui/charts (shaded phase band, optional), core/math/AdaptiveExpenditure.kt (down-weight days flagged as menstruation)
  Acceptance: with the permission granted and periods recorded, the chart shows a band for each period and the weekday/insight cards exclude those days from "unusual" calls; a core test shows expenditure moves less than 50 kcal when a flagged 0.5 kg spike is injected versus more without the flag; refusing the permission changes nothing else.
  Complexity: L

- [ ] P2: Holt double-exponential trend as a selectable smoothing mode
  Why: an EMA lags a steady slope by design; TrendWeight 2.11.0 (2026-08-26) added Holt's linear trend for exactly this, trale #481 proposes a Kalman equivalent, and Happy Scale offers four modes.
  Evidence: https://github.com/trendweight/trendweight/issues/396 ; https://github.com/QuantumPhysique/trale/issues/481 ; https://happyscale.com/support ; core/src/main/java/com/weighttrack/core/math/TrendEngine.kt
  Touches: core/math/TrendEngine.kt (second smoother with the same gap-aware compounding), data/prefs/SettingsRepository.kt (mode), ui/settings smoothing section, ui/components/TrendChart.kt (no change if TrendSeries stays the shape)
  Acceptance: on a synthetic steady 0.5 kg/week loss the Holt trend lags the true line by under 0.1 kg after 30 days where the EMA lags by more; milestones, rate and ETA all read off whichever mode is chosen; the default stays EMA alpha 0.1.
  Complexity: M

- [ ] P2: Use step counts as a confidence signal for expenditure updates and correct for a goal switch
  Why: MacroFactor's 2025 modifiers (steps never converted to kcal, expenditure shifted by 4× the weekly %-change target when the goal changes) cut median 100-day prediction error by about 20 percent; steps are already read from Health Connect.
  Evidence: https://macrofactor.com/expenditure-modifiers/ ; https://macrofactor.com/expenditure-v3/ ; core/src/main/java/com/weighttrack/core/math/AdaptiveExpenditure.kt
  Touches: core/math/AdaptiveExpenditure.kt (weight the window by step consistency; apply the goal-switch shift), domain/ProgressCalculator.kt, tests
  Acceptance: a fixture where steps double in week two moves the estimate faster than the same intake and weight without steps; changing a goal from lose to maintain shifts the recommended intake immediately by the documented factor; a test proves steps never enter the kcal arithmetic directly.
  Complexity: M

- [ ] P2: User-set maintain band, and a maintenance mode when a loss goal is reached
  Why: the maintain tolerance is a fixed 1 kg constant; trale #454 asks for a chosen band; Happy Scale is criticised for telling someone below their loss goal that the trend "improved".
  Evidence: core/src/main/java/com/weighttrack/core/math/GoalProjection.kt:47 ; https://github.com/QuantumPhysique/trale/issues/454 ; https://unimeal.reviews/weight-loss-apps/happy-scale/
  Touches: core/math/GoalProjection.kt, data/db GoalEntity (band grams, Room v12 auto-migration), ui/goal/GoalScreen.kt, domain/ProgressCalculator.kt (reached loss goal reports "holding" not "still losing")
  Acceptance: a 2 kg band keeps a maintain goal "on track" at +1.8 kg drift; a reached loss goal whose trend keeps falling is described as below target rather than improving; migration test covers the new column.
  Complexity: S

- [ ] P2: Refresh cached online foods and dedupe Open Food Facts units
  Why: a product kept from a lookup never updates when the label changes (Food You #241, 6 reactions), and OSS trackers' loudest food complaint is contradictory or meaningless units from crowdsourced entries.
  Evidence: https://github.com/maksimowiczm/FoodYou/issues/241 ; https://lemmy.world/post/22208606 ; data/food and core/nutrition (no fetchedAt or refresh path, repo scan 2026-08-29)
  Touches: core/nutrition/Food.kt (fetchedAt, source revision), data/repo/FoodRepository.kt (refresh action under the 15 req/min limiter), ui/food food detail ("last checked", refresh button), core/nutrition/OpenFoodFacts.kt (normalise per-100g vs per-serving, drop entries whose kcal disagrees with macros by more than 20 percent)
  Acceptance: refreshing a cached product updates its numbers without changing logged diary rows; a product with kcal 900 and 0 g of every macro is rejected by a unit test; the limiter is respected across a refresh of ten foods.
  Complexity: M

- [ ] P2: Scheduled CSV export beside the backup, and a Google Fit takeout importer
  Why: openScale #338 wants scheduled export distinct from backup; Google Fit ends in 2026 and Google Health's export deadline for discontinued data was 2026-07-15, so takeout CSVs are what migrants hold.
  Evidence: https://github.com/oliexdev/openScale/issues/338 ; https://developer.android.com/health-and-fitness/health-connect/migration/fit ; https://blog.google/products-and-platforms/products/google-health/google-health-app/ ; data/io/WeightCsvImporter.kt (no Fit or Takeout column set)
  Touches: data/io/WeightCsvImporter.kt (Takeout "Daily activity metrics" and Fit weight CSV layouts), data/io/AutoBackup.kt (CSV variant), tests with real header rows
  Acceptance: a Takeout daily-metrics CSV with the "Average weight (kg)" column imports with correct dates; re-import updates rather than duplicates; the scheduled export writes a CSV next to the JSON.
  Complexity: S

- [ ] P2: Split SettingsScreen and add view model tests for the untested screens
  Why: SettingsScreen.kt is 941 lines and the app has no tests for SettingsRepository, SyncWorker, or the goal, history (undo), log, onboarding and settings view models; Compose UI test dependencies are declared and never used.
  Evidence: app/src/main/java/com/weighttrack/ui/settings/SettingsScreen.kt ; app/build.gradle.kts:199,202 ; repo test inventory 2026-08-29
  Touches: ui/settings/* (one file per section), new tests under app/src/test for SettingsRepository, HistoryViewModel undo, GoalViewModel, LogWeightViewModel, SyncWorker (WorkManager test helpers)
  Acceptance: no settings file over 300 lines; the undo path has a test that goes red when `undoDelete` is stubbed out; SyncWorker returns `success` on `Refused` and `retry` on a network failure under `TestListenableWorkerBuilder`.
  Complexity: M

- [ ] P2: network_security_config with a user-supplied certificate path for a self-signed NAS, and a clear cleartext refusal
  Why: there is no network security config, so an `http://` WebDAV URL fails with a generic error and a self-signed Nextcloud cannot be trusted at all; Android 17 turns Certificate Transparency on by default and `usesCleartextTraffic` is slated for deprecation.
  Evidence: https://developer.android.com/about/versions/17/behavior-changes-all ; https://developer.android.com/about/versions/17/behavior-changes-17 ; app/src/main/res/xml (no network_security_config.xml); ui/settings/SyncCard.kt (no scheme validation)
  Touches: res/xml/network_security_config.xml, AndroidManifest.xml, data/sync/WebDavSyncTarget.kt (OkHttp client with an optional pinned user certificate loaded from a SAF pick), ui/settings/SyncCard.kt (reject http:// with a sentence, "trust this server's certificate" flow)
  Acceptance: an http:// URL is refused at entry with the reason; a self-signed HTTPS server passes after the certificate is picked and fails before; cleartext stays blocked.
  Complexity: M

- [ ] P2: Replace wall-clock tombstone expiry with peer acknowledgements
  Why: Last-write-wins timestamps come from device clocks and tombstones expire after six months, so a skewed or long-offline peer can republish an older live row after its deletion marker disappears.
  Evidence: `core/src/main/java/com/weighttrack/core/sync/SyncMerge.kt:143-158`; `core/src/main/java/com/weighttrack/core/sync/SyncDocument.kt`; https://cse.buffalo.edu/~demirbas/publications/hlc.pdf
  Touches: sync stamp model, `SyncDocument` format, `SyncMerge`, peer retirement UI, migration and convergence tests
  Acceptance: Mutations use a hybrid logical clock with device ID tie-breaking; a tombstone is pruned only after the retention floor and acknowledgement from every non-retired known peer; a fixture returning after nine months cannot resurrect its deleted row; clock rollback and equal-millisecond edits converge identically on every merge order.
  Complexity: XL

- [ ] P2: Record startup and worker failures in runtime diagnostics
  Why: Crash reports and the runtime log exist, but initialization exceptions, progress-worker stop reasons, and scheduler decisions can disappear before the user can diagnose missed background work.
  Evidence: `app/src/main/java/com/weighttrack/diagnostics`; `app/src/main/java/com/weighttrack/sync/SyncWorker.kt`; `app/src/main/java/com/weighttrack/data/io/AutoBackupWorker.kt`
  Touches: application initializer, WorkManager workers and observers, runtime log event model, diagnostics UI and tests
  Acceptance: Each worker records start, completion, retry cause, cancellation, and platform stop reason with profile-safe context; startup component failures appear on the next diagnostics screen; a support export redacts secrets; tests verify one terminal event per work run.
  Complexity: M

- [ ] P2: Add a long-history performance regression fixture
  Why: Weight trackers commonly accumulate years of daily data, and trale issue 279 reports navigation degradation on large histories; WeightTrack has no pinned dataset or frame-time budget for this case.
  Evidence: https://github.com/QuantumPhysique/trale/issues/279; `app/src/main/java/com/weighttrack/ui/charts`; `app/src/main/java/com/weighttrack/data/db/Daos.kt`
  Touches: benchmark fixtures, DAO paging and aggregation queries, chart downsampling, Baseline Profile or Macrobenchmark checks
  Acceptance: A fixture with 7,300 daily readings, 1,825 diary days, and monthly measurements opens Home and Charts within 500 ms and switches a date range within 100 ms on the pinned medium emulator; peak memory and frame timing are recorded; the benchmark fails on a regression over the checked-in budget.
  Complexity: M

### P3

- [ ] P3: Vibrate on a successful scale capture and show the settling weight live
  Why: openScale #1097 asks for feedback at the moment the reading lands; ble-scale-sync 1.26.0 shows the weight settling before it is final, which tells the person to keep standing still.
  Evidence: https://github.com/oliexdev/openScale/issues/1097 ; https://github.com/KristianP26/ble-scale-sync/releases/tag/v1.26.0 ; ui/scale/ScaleScreen.kt
  Touches: ui/scale/ScaleScreen.kt, ble/ScaleReadingRouter.kt (emit unstable readings as a separate state), VibratorManager use
  Acceptance: unstable frames update a live number without filing; the stable frame files, vibrates once, and the live number stops.
  Complexity: S

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

- [ ] P3: Move the wear module to targetSdk 36 with the Tiles 1.6 interaction API
  Why: Wear target 35 already meets the 2026-08-31 Play requirement; this proactive bump prepares for the Tiles 1.6 removal of `onEnterEvent` and `onLeaveEvent` at target 36 by moving to `onRecentInteractionEvents` in the same change.
  Evidence: https://developer.android.com/google/play/requirements/target-sdk ; https://developer.android.com/jetpack/androidx/releases/wear-tiles ; wear/build.gradle.kts (targetSdk 35)
  Touches: wear/build.gradle.kts, wear/WeightTileService.kt
  Acceptance: `:wear:assembleRelease` at targetSdk 36 with no deprecation warning from tiles; the tile still refreshes after a phone-side change on the Wear AVD.
  Complexity: S

- [ ] P3: Bump Compose BOM to 2026.08.01 line, Material3 1.4.0, OkHttp 5.5.0
  Why: compose-ui 1.12.0 (2026-08-12) and Material3 1.4.0 (2026-08-26) are stable and the toolchain already meets their AGP 9.2 floor; OkHttp 5.2.1 predates the 5.3.2 timeout-regression fix and 5.5.0 rotated its signing key.
  Evidence: https://developer.android.com/jetpack/androidx/releases/compose-ui ; https://developer.android.com/jetpack/androidx/releases/compose-material3 ; https://github.com/square/okhttp/blob/master/CHANGELOG.md ; gradle/libs.versions.toml
  Touches: gradle/libs.versions.toml
  Acceptance: all four unit suites and both lint tasks green; both release APKs assemble; WebDAV PROPFIND round trip against the recorded-reply test passes.
  Complexity: S

- [ ] P3: Move design-qa.md under docs/ and add CONTRIBUTING.md and SECURITY.md
  Why: design-qa.md is tracked at the repo root outside the documented doc set; there is no contribution or vulnerability-report guidance, and README still says "Next up: translations" after string extraction shipped.
  Evidence: `git ls-files` 2026-08-29; README.md line 104
  Touches: docs/design-qa.md, CONTRIBUTING.md, SECURITY.md, README.md
  Acceptance: root holds only README, CHANGELOG, ROADMAP, LICENSE and build files; README roadmap line names the current next item.
  Complexity: S

### P0 additions from 2026-08-31

- [ ] P0: Persist complete scale composition with quality and provenance
  Why: Parsers and the live scale screen carry muscle, lean mass, water, impedance, and basal metabolism, but save keeps only weight and body-fat percentage, silently discarding the rest and whether values were scale-reported, app-estimated, absent, or incomplete.
  Evidence: `core/src/main/java/com/weighttrack/core/scale/ScaleReading.kt:10-23`; `core/src/main/java/com/weighttrack/core/scale/StandardScaleParser.kt:120-142`; `app/src/main/java/com/weighttrack/ui/scale/ScaleViewModel.kt:292-337`; https://www.bluetooth.com/specifications/specs/body-composition-service-bcs/; https://github.com/oliexdev/openScale/issues/1469; https://pubmed.ncbi.nlm.nih.gov/33929337/
  Touches: Room composition entity and migration, `WeightRepository.kt`, scale save paths, history and detail UI, sync document, structured backup, CSV or public export, Health Connect mapping tests
  Acceptance: Every non-null `ScaleReading` field round-trips through Room, backup, sync, and export without unit loss; each value retains device, adapter or protocol, and quality state; UI uses “reported by scale” when the vendor method is unknown and never presents BIA as a clinical measurement; weight-only and incomplete captures remain valid and visibly distinct.
  Complexity: L

### P1 additions from 2026-08-31

- [ ] P1: Lock resolved dependencies and verify every fetched artifact
  Why: The version catalog pins direct coordinates and the wrapper has a checksum, but transitive versions and downloaded plugin or library bytes can still change without a reviewed lock or verification failure.
  Evidence: `gradle/libs.versions.toml`; `gradle/wrapper/gradle-wrapper.properties`; absent `gradle.lockfile` and `gradle/verification-metadata.xml`; https://docs.gradle.org/current/userguide/dependency_locking.html; https://docs.gradle.org/current/userguide/dependency_verification.html
  Touches: root and module Gradle configuration, lock state, `gradle/verification-metadata.xml`, local dependency-update and release checks
  Acceptance: Strict lock state covers all resolvable app, Wear, test, plugin, and release configurations; changing a transitive version without refreshing locks fails; replacing a cached artifact with different bytes fails verification; the documented local update command regenerates metadata for review without disabling verification.
  Complexity: M

- [ ] P1: Rebook reminders after civil-time changes and remove unnecessary exact-alarm access
  Why: Reminders are one-shot RTC alarms rescheduled only after boot or package replacement, so manual time, timezone, or seasonal offset changes can move them away from the chosen local time; daily weigh-in reminders do not require privileged exact delivery.
  Evidence: `app/src/main/java/com/weighttrack/notifications/ReminderScheduler.kt`; `app/src/main/java/com/weighttrack/notifications/ReminderReceiver.kt:153-176`; `app/src/main/AndroidManifest.xml:6-11,128-136`; https://developer.android.com/develop/background-work/services/alarms; https://developer.android.com/reference/android/content/Intent; https://developer.android.com/privacy-and-security/risks/insecure-broadcast-receiver
  Touches: reminder and weekly-summary schedulers, reschedule receiver, manifest, exact-alarm settings UI, DST and clock-change tests
  Acceptance: Weigh-in and weekly-summary alarms are rebuilt after `TIME_SET`, `TIMEZONE_CHANGED`, and API 37 `TIMEZONE_OFFSET_CHANGED`; tests cover spring gaps and fall overlaps in at least two zones; the receiver is not callable by third-party apps; `SCHEDULE_EXACT_ALARM` and its settings flow are removed, and inexact delivery remains visible in reminder copy.
  Complexity: M

- [ ] P1: Publish release checksums and the stable APK signing identity
  Why: Direct-download users can verify neither asset integrity nor signing continuity from the current release materials, while comparable OSS users have explicitly requested the certificate fingerprint.
  Evidence: https://github.com/davidhealey/waistline/issues/950; https://github.com/guiloklex-hub/ControlaPeso/releases/tag/v1.1.0; https://github.com/SysAdminDoc/WeightTrack/releases/tag/v0.4.0; existing Android developer verification item
  Touches: local release script, `SECURITY.md` or equivalent permanent verification guide, GitHub release assets, artifact-verification test
  Acceptance: Every release includes `SHA256SUMS.txt`; the permanent guide publishes the SHA-256 signing-certificate fingerprint for each channel and exact `apksigner` commands; the local release gate fails if an APK has the wrong package, signer, version, or checksum; key rotation documents the old and new fingerprints before an update ships.
  Complexity: S

### P2 additions from 2026-08-31

- [ ] P2: Use one configurable calendar-week rule everywhere
  Why: Weekly analytics currently count backward from the latest point while Settings has a summary day but no first-day-of-week rule, a correctness class that caused wrong-row selection in openScale and appeared again in a 2026-08-31 tracker discussion.
  Evidence: `app/src/main/java/com/weighttrack/domain`; `app/src/main/java/com/weighttrack/ui/charts`; https://github.com/oliexdev/openScale/issues/1454; https://www.reddit.com/r/loseit/comments/1vvcm8n/what_apps_or_calorie_trackers_are_people_using/
  Touches: shared week-boundary helper, analytics and chart grouping, weekly summary, settings and sync preference, locale tests
  Acceptance: Locale default and an explicit Sunday-through-Saturday override produce consistent boundaries in charts, summaries, averages, comparisons, and exports; US and German locale fixtures select the expected rows across year boundaries; changing the rule never rewrites stored dates.
  Complexity: M

- [ ] P2: Publish a versioned interchange schema separate from private restore state
  Why: CSV and JSON exist, but third-party tools have no stable machine-readable contract, compatibility policy, or extension rules; adjacent local-first tools make documented open files the integration boundary.
  Evidence: `app/src/main/java/com/weighttrack/data/io/Backup.kt`; `core/src/main/java/com/weighttrack/core/sync/SyncDocument.kt`; https://github.com/LuminaAppsDev/cairn; https://github.com/woop/awesome-quantified-self
  Touches: public JSON Schema or JSON Lines model, exporter and importer, versioned fixtures, compatibility documentation
  Acceptance: A published schema covers documented user data without secrets or internal acknowledgement state; v1 fixtures validate and round-trip; unknown additive fields are ignored; breaking changes require a new schema version and migration fixture; a sample consumer can stream weights and composition without importing app code.
  Complexity: M

- [ ] P2: Finish actionable F-Droid metadata and reproducibility work
  Why: The final fdroiddata submission needs an external account and review, but upstream descriptions, graphics, version-code changelog, FOSS dependency proof, and reproducible-build evidence can be completed now and are currently absent.
  Evidence: absent `fastlane/metadata/android/en-US`; `Roadmap_Blocked.md`; https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/; https://f-droid.org/en/docs/Reproducible_Builds/; https://f-droid.org/en/categories/health-manager/
  Touches: upstream Fastlane or Triple-T metadata, current icon and screenshots, local FOSS release recipe, reproducibility comparison, F-Droid notes
  Acceptance: Upstream metadata contains short and full descriptions, icon, current phone screenshots, and a changelog for every published version code, including code 5 for v0.4.0, and passes `fdroid lint`; the FOSS release resolves no proprietary runtime dependency; two clean builds from the same tag compare byte-for-byte before signing or have every difference explained with `diffoscope`; `Roadmap_Blocked.md` is left only with account, submission, and reviewer-controlled steps.
  Complexity: M

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
