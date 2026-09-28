# Time Recorder v2.2 — Native Compose, Web-theme visual refresh

Native Android app using Jetpack Compose. No WebView runtime.

## v2.2 changes

- Restores the visual language of the earlier Web UI in native Compose:
  - charcoal `#090B10` background
  - layered `#151923 / #1B202C` cards
  - purple accent `#8B7CFF`
  - teal bus accent `#41D6C3`
  - amber extra-item accent `#FFBD5B`
  - denser single-card Timeline layout
- ETA is a separate large panel above the action buttons.
- 38 / 42C remain dedicated large boarding buttons without embedded ETA text.
- Explicit status-bar and navigation-bar padding fixes Android edge-to-edge overlap.
- Settings drawer also applies system-bar insets.
- All ETA/network/background logic stays native Android.

## GitHub Actions

`.github/workflows/android-build.yml` builds the release APK only.

Required repository secrets for signed release:
- `SIGNING_KEY_B64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
