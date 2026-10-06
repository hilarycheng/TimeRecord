# Time Recorder v2.8 — Unified UI

Native Android app built with Jetpack Compose. No WebView runtime.

## v2.8 UI baseline

This version unifies the whole app around the approved main-screen design:

- simple date/header row (no title tile bar)
- compact Live ETA panel with 38 / 42C, three upcoming ETAs, countdown + clock time
- two-column workflow cards with roomy touch targets
- flat front-view Hong Kong double-decker bus icon drawn natively in Compose
- colourful, understated icon tiles (not neon / mono)
- vertical Today timeline using the same card, colour, spacing and icon language
- sticky purple Smart Next action at the thumb zone
- Summary and Charts use the same dark navy surfaces, borders and teal/purple/yellow accents
- Chart remains an independent page with 7d / 30d / 3m / all ranges

## Behaviour retained

- deterministic Smart Next flow
- second-precision timestamps and durations
- KMB / government ETA cache + foreground tracking
- 3 ETA arrivals per route when available
- backup / restore
- widget
- signed Release-only GitHub Actions workflow

## Build

The GitHub workflow is included at `.github/workflows/android-build.yml`.
It builds the Release APK only and uses repository signing secrets when present.

## v2.8 custom vehicle

- Added **其他巴士 / 車** as a real transport event, separate from **其他事**.
- It uses the same commute flow as 38 / 42C: 到巴士站 → 上車 → 落車.
- Waiting time and journey duration are calculated normally.
- Tap records immediately as `上 其他車`; long-press the timeline entry can change only the route/vehicle name (for example 72, 290A, 的士) without changing the timestamp.
- No note field is added.
- Fixed 38 / 42C charts stay clean; custom vehicles do not get mixed into those route-specific charts.
- Daily commute summary includes custom-vehicle journey time under **車程**.

## v2.10 Holiday Calendar

- Settings/Menu adds **Calendar · 香港公眾假期**.
- Calendar uses only the official 1823 Traditional Chinese public-holiday iCal feed:
  `https://www.1823.gov.hk/common/ical/tc.ics`
- Opening Calendar auto-checks when the previous check is older than 7 days.
- Cached holidays stay visible while refreshing or when the network fails.
- Calendar shows both the source/data update date (when available from iCal/HTTP metadata) and the last check time.
- Manual **立即更新** is available on the Calendar page.
