# Time Recorder — Compose clean build

Native Android / Jetpack Compose version.

This build intentionally starts with a clean native data store. It does **not** import v1.x WebView/localStorage data and does not read the previous native-v2 preference file.

## Startup hardening

- No WebView runtime or legacy WebView storage scanner.
- No JobScheduler or network request before the first Compose frame.
- Background scheduling is deferred and failure-isolated.
- Release minification/resource shrinking is temporarily disabled for stability.
- ETA remains native and is refreshed by the foreground service while commute tracking is active.

## GitHub Actions

`.github/workflows/android-build.yml` builds only the signed release APK.

Required repository secrets:

- `SIGNING_KEY_B64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

The artifact appears at the bottom of the successful Actions run as `TimeRecorder-release-apk`.
