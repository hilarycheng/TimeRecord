# Time Recorder — Compose rewrite

Native Android rewrite of the Time Recorder prototype. There is no WebView UI and no JavaScript runtime.

## Architecture

- Jetpack Compose + Material 3 dark UI.
- Native `HttpURLConnection` client for the Hong Kong KMB ETA open-data API.
- Native foreground service while a commute is active. It refreshes ETA on a fixed 60-second cadence and keeps a low-priority notification visible.
- Native `JobScheduler` best-effort refresh outside active trips (15-minute Android system cadence, only 06:00–10:00 and 16:50–18:00 Hong Kong time).
- SharedPreferences + compact JSON for local-only event, ETA-cache and settings storage.
- Best-effort one-time migration scanner for the old v1.x Chromium localStorage files. It never instantiates WebView and leaves the legacy files untouched.

## Fixed ETA context

- Before 10:00 / WORK tracking: 德福花園, routes 38 / 42C toward the work direction.
- After 10:00 / HOME tracking: 屏麗徑南行, routes 38 / 42C toward the home direction.
- Pressing 返工 or 放工 explicitly selects the tracking direction and starts the foreground service.
- Pressing 38 or 42C records boarding and stops the foreground service.
- Cached ETA values are absolute timestamps. The UI counts them down locally and does not clear old values while a refresh is in progress.

## Timeline rules

- Each entry shows the duration until the next entry.
- The last entry on today shows `current time - entry time` live.
- A terminal 到屋企 entry shows `Ending`.
- When a later day is opened, a previous day with events but no terminal entry gets an automatic `18:00 到屋企 · AUTO` event.
- Long-press an action button to backfill a time.
- Long-press a timeline row to rename, edit its time or delete it.

## Build

Requirements: Android SDK 37, Build Tools 36.0.0+, JDK 17+, Gradle 9.6.0+ (wrapper properties target 9.6.1), AGP 9.4.0.

This source bundle intentionally does not contain the private signing key. Copy `keystore.properties.example` to `keystore.properties`, point it to the existing `time-recorder-local.keystore`, and fill the existing passwords to produce an update-compatible signed release build.

The release build enables R8 minification and resource shrinking. No Room, Retrofit, Navigation, icon pack or chart library is included.

## Package / version

- applicationId: `com.quickstamp.timerecorder`
- versionCode: 7
- versionName: `2.0-compose`
- minSdk: 26
- targetSdk / compileSdk: 37
