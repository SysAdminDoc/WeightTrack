# Design QA

## Evidence

The eight approved screen references are in `docs/design/v0.4/`. Matching implementation captures are in `docs/screenshots/`. Each final comparison below places the reference on the left and the running app on the right.

| Route | Final paired evidence |
| --- | --- |
| Home | `docs/design/qa/home-comparison.png` |
| Charts | `docs/design/qa/charts-comparison.png` |
| History | `docs/design/qa/history-comparison.png` |
| Log weight | `docs/design/qa/log-comparison.png` |
| Settings | `docs/design/qa/settings-comparison.png` |
| Edit goal | `docs/design/qa/goal-comparison.png` |
| Measurements | `docs/design/qa/measurements-comparison.png` |
| Onboarding | `docs/design/qa/onboarding-comparison.png` |

The app captures use a 1080 by 2400 Android emulator at 420 dpi, which reports a 411 by 914 dp app configuration. Reference screens are 853 by 1844 and were resized to the capture dimensions for paired inspection. No device frame was added.

## States checked

- Home uses a seeded 31-day downward trend, a 78.0 kg target, and a current reading of 84.2 kg.
- Charts shows the one-month range with recorded and trend lines.
- History shows the latest grouped entries and search control.
- Log weight shows a new 84.2 kg entry with the primary tags visible.
- Settings uses the default AMOLED theme.
- Edit goal shows a 78.0 kg loss goal, 2 kg milestones, and no target date.
- Measurements shows the empty grouped measurement editor.
- Onboarding shows step one on a clean install.

Full-screen captures were sufficient for typography, spacing, input controls, navigation, and milestone inspection. Important controls remain legible at the paired comparison size, so separate crops were not needed.

## Surface review

- Typography: system sans typography, tabular numeric figures, and the reference hierarchy are present across every route.
- Spacing: page gutters, section rhythm, grouped rows, keypad density, and bottom navigation align with the references.
- Color: AMOLED black, graphite surfaces, slate text, mint actions, and blue chart accents are consistent.
- Shape: controls use 4 to 12 dp corners. Selectors are rectangular and no chip-shaped navigation or filter controls remain.
- Assets: Material icons provide native Android equivalents for the reference symbols. No raster substitution is used for interface icons.
- Copy: headings, metric labels, tags, settings groups, and action labels match the intended hierarchy and meaning.

## Comparison history

### Pass one

The first implementation pass had too many stacked cards, rounded filter chips, and a floating action button shared across routes. Home, Charts, and Settings were flattened into clearer sections. Selectors became compact rectangles, while the add action now appears only in History.

The initial chart used an area fill and lacked a visible key. The fill was removed and a recorded-versus-trend legend was added. Log weight originally exposed every tag and optional field at once, so the primary three tags now lead and secondary details stay collapsed.

The onboarding heading wrapped, History lacked its latest-section label, and Edit goal did not preview milestones. The heading size was corrected, the missing label was added, and the goal screen now includes a readable milestone track with full unit labels.

### Pass two

The second paired review tightened Home's trend labels and action hierarchy, joined the Charts range selector, reduced History row height, reordered the first Settings sections and corrected the smoothing control. Log gained a clearer secondary-details row, while Onboarding and Measurements received final spacing and color corrections.

The Goal save action was still clipped below the first viewport. Only that screen now uses a shorter keypad and tighter section rhythm, leaving the full save action visible while Log and Onboarding retain the larger keypad.

## Final findings

No actionable P0, P1, or P2 mismatch remains. Native Android status and navigation bars add height that the borderless references do not show. The running app also uses live seeded values where a reference value was illustrative. The goal reference contains milestone numbers that do not reconcile with its target, so the implementation keeps mathematically correct values.

Two acceptable P3 differences remain: native Material icon geometry differs slightly from the illustrative reference symbols, and system bar insets vary by Android device.

final result: passed
