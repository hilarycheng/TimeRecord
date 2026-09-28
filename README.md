# Time Recorder v2.5

Native Jetpack Compose commute recorder.

## v2.5
- Summary uses 3 main blocks: 返工路程, 工作時間, 放工返屋企. Commute blocks include wait/bus sub-stats.
- Completion is calendar-day scoped. After midnight/new day, workflow buttons and Smart Next return automatically.
- Bus History / Charts has 3 metrics: 到站/上車時間, 等車時間, 車程.
- Filters: 返工/放工, 38/42C, 7日/30日/3個月/全部.
- One data point is still plotted; small samples are marked Limited data.
- Boarding-time analytics use second-accurate user timestamps.
- Existing Midnight Rose theme, native ETA tracking, Backup/Restore, Widget, Snackbar Undo and long-press editing remain.
