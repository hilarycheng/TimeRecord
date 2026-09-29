# Time Recorder v2.7 — Unified UI

Native Android app built with Jetpack Compose. No WebView runtime.

## v2.7 UI baseline

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
