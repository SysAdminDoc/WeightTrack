<p align="center">
  <img alt="Version" src="https://img.shields.io/badge/version-0.0.1-58A6FF?style=for-the-badge">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-4ade80?style=for-the-badge">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-58A6FF?style=for-the-badge">
</p>

# WeightTrack v0.0.1

A free, open-source Android app for tracking weight loss, body measurements and the habits that drive them. No subscription, no account, no ads. Your data stays on your phone unless you choose to export it.

Most weight apps hide trend lines, goal projections, photos or exports behind a monthly plan. WeightTrack ships all of it for nothing.

## Status

Pre-release. The project is being scaffolded and the feature plan lives in [ROADMAP.md](ROADMAP.md). Nothing is installable yet.

## Planned highlights

- Weight log with a smoothed trend line so daily noise doesn't hide real progress
- Goal tracking with a projected finish date based on your actual rate
- Body measurements, body fat estimates and progress photos
- Calorie and macro logging with barcode scanning through Open Food Facts
- Intermittent fasting timer, water intake and step counts
- Health Connect sync in both directions
- Home screen widgets and daily reminders
- Full CSV and JSON export and import. Your data is yours.

## Stack

Kotlin, Jetpack Compose, Material 3, Room, Hilt. Min SDK 26.

## Building

Requires Android Studio (or the command line SDK) with JDK 17+.

```
./gradlew assembleDebug
```

Release builds are signed locally and attached to GitHub Releases.

## License

MIT. See [LICENSE](LICENSE).
