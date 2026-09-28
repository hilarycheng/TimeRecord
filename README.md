# Time Recorder v2.4 — Midnight Rose

Native Android / Jetpack Compose commute recorder for the fixed 38 / 42C workflow.

## v2.4 fixes

- Midnight Rose dark palette with explicit high-contrast text colors.
- Summary calculation corrected:
  - Work duration = `到公司 -> 放工`.
  - Home duration = `放工 -> 到屋企`.
  - Summary durations use explicit units such as `9h 36m 12s` / `41m 50s`.
- Completed day mode hides the workflow / ETA / Smart Next buttons and leaves the summary + timeline.
- Android left-edge Back gesture is no longer used for the Settings drawer. Open Settings from the top-right gear; Back closes the drawer.
- `Chart` is now a top-level button. History / Charts opens at near-full width with journey-duration and boarding-time graphs, route/direction filters, weekday pattern, medians and ranges.
- User timestamps and timeline durations remain second-accurate.

## Existing v2.3 features retained

- 返工 / 放工, 到巴士站, 38 / 42C, 到餐廳, 到公司, 落車, 到屋企, 其他事.
- Auto zero-wait stop when boarding directly without pressing 到巴士站.
- Native KMB / government ETA cache + foreground 60-second refresh while tracking.
- Snackbar Undo, haptics, long-press edit/delete, bus-route-only correction.
- JSON Backup / Restore and home-screen widget.
- GitHub Actions release build in `.github/workflows/android-build.yml`.
