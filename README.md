# Time Recorder 2.3 — Native Compose

Dark/pink commute time recorder for Android. No WebView runtime.

## Daily workflow

- 返工 / 放工
- 到巴士站
- 上 38 / 42C
- 到餐廳 / 到公司 / 落車 / 到屋企
- 其他事
- Long-press a workflow button to backfill an earlier time
- Long-press a Timeline entry to rename, change time, switch 38 ↔ 42C, or delete

All user-recorded timestamps are stored to milliseconds and displayed to seconds by default. Durations are second-accurate (`mm:ss` / `h:mm:ss`).

If 38/42C is tapped without an earlier 到巴士站 event for that commute, the app creates an AUTO 到巴士站 event at the exact boarding timestamp, making wait time `00:00`.

## Smart / feedback

- Smart Next Action lives in the bottom thumb zone
- Live duration for the current last Timeline segment
- Press animation + haptic feedback
- Non-blocking Snackbar with one-tap Undo
- Timeline bus entry can switch only the route number without changing its timestamp

## ETA

- Work: 德福花園
- Home: 屏麗徑南行
- Routes 38 / 42C
- Uses the KMB / Transport Department public ETA API
- Foreground service refreshes every 60 seconds while commute tracking is active
- Cached absolute ETA timestamps continue counting down between refreshes
- Previous ETA stays visible while refreshing
- Raw non-empty `rmk_tc` remarks are shown in the ETA card
- Background best-effort refresh remains separate

## History / graph

Settings → History / Graph / Weekday Pattern:

- Work / Home
- 38 / 42C
- Journey-duration trend
- Boarding-time trend (useful as an observed bus-arrival pattern)
- Median, typical interquartile range, sample count
- Monday–Friday median pattern
- Waiting-time median

## Today Summary

Shows waiting time, bus time, work commute and home commute.

## Backup / Restore

Settings supports JSON backup and restore using Android's document picker. CSV export is intentionally not included.

## Widget

Android Home Screen widget includes:

- Cached 38 / 42C ETA
- Smart Next Action
- One-tap 38 / 42C boarding buttons

Add it via Android Home Screen → Widgets → Time Recorder.

## UI

Native Jetpack Compose, dark theme with rose pink / violet / turquoise / amber accents. System status/navigation bar insets are respected.

## Build

`.github/workflows/android-build.yml` builds only the signed release APK.

Required GitHub Repository Secrets:

- `SIGNING_KEY_B64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Release artifact: `TimeRecorder-release-apk`.
