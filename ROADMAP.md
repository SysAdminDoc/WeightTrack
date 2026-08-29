# WeightTrack Roadmap

Single task tracker for the project. Research was done 2026-08-29; prices and app behavior drift, so re-check a store listing before quoting it publicly.

## Positioning

Happy Scale quality, on Android, free forever. No account, no ads, no subscription, no cloud you don't control. Your data lives on your phone and leaves only when you export it.

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
- No login wall. First launch opens straight onto "enter your weight".
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

Kotlin 2.x, Jetpack Compose, Material 3 with dynamic color, dark theme default with a light option. Single activity, Compose navigation. Room (WAL, schema export, auto-migrations), DataStore, Hilt, WorkManager, Glance for widgets, Wear Compose + Tiles + Data Layer for the watch, kotlinx-serialization for import/export. Charts drawn directly on a Compose canvas: a library was tried and dropped, because the raw readings, the trend, the goal line and the milestone marks all need to share one coordinate space exactly. minSdk 26, compileSdk 37. R8 on, signed release builds only.

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

## Phase 2: v0.3.x, hardware and habits

- [ ] Wear OS companion: tile with one-tap log (rotary picker), complication showing trend and delta, Data Layer sync
- [ ] Progress photos: camera or gallery, side-by-side compare with weight overlay, stored locally, optional app lock
- [ ] Bluetooth scales, foreground "step on the scale" screen. Order: standard Weight Scale / Body Composition services (0x181D / 0x181B), Xiaomi Mi Scale 2 broadcast decoding (no pairing), then Renpho, Eufy, Beurer/Sanitas re-implemented from openScale's protocol docs. Auto-assign to a profile by weight range.
- [ ] Multiple profiles (family), each with its own Health Connect mapping and reminder
- [ ] Water intake with a widget and HydrationRecord write
- [ ] Fasting timer: presets (16:8, 18:6, OMAD, custom), editable and deletable fasts, optional zones, history with no limit
- [ ] Steps and active calories read from Health Connect, shown against the trend
- [ ] F-Droid submission

## Phase 3: v0.4.x, nutrition

Kept optional so the weight-only experience stays clean. Off by default, one toggle to enable.

- [ ] Food database: Open Food Facts v3 (cached, rate-limited client, attribution), USDA FoodData Central, custom foods, recipes
- [ ] Barcode scanning: ML Kit in `play`, ZXing in `foss`
- [ ] Calorie and macro logging by meal, favorites and recents, copy yesterday, quick-add calories
- [ ] Custom macro targets in grams or percent, per-day targets
- [ ] Adaptive expenditure: weekly calorie target recommendation from trend rate versus logged intake (the MacroFactor loop, free)
- [ ] NutritionRecord write to Health Connect
- [ ] Bundled offline food subset from the OFF dump for the most common products

## Phase 4: v0.4.x, sync and insights

- [ ] Sync without an account: file-based format friendly to Syncthing, plus WebDAV/Nextcloud
- [ ] Insight cards: weekday effect, steps versus weight, sleep versus weight where Health Connect provides it
- [ ] Weekly summary notification
- [ ] Shareable milestone card image (local render, no social integration)
- [ ] Localization

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
