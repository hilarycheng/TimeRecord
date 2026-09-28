# Time Recorder v2.6

Native Jetpack Compose commute recorder.

## v2.6
- Smart Next is a deterministic commute state machine: after boarding 38/42C, next is always 落車. Work then continues to 到餐廳 → 到公司; home continues to 到屋企.
- Main workflow buttons remain visible during an active day; Smart Next stays as a thumb-reach shortcut only.
- ETA panel shows up to three upcoming buses per route, each with countdown and clock time, while stale-while-refresh cache behavior remains.
- 今日記錄 is rendered as a vertical timeline with colored nodes, live second-accurate segment duration, and a hollow predicted next step. Long press still edits entries.
- History/Charts is a full standalone page, not a dialog. The only selector is time range (7 days / 30 days / 3 months / all); multiple plots for boarding time, wait time, and journey time are shown for work/home and 38/42C. One sample still plots as a point.
- Partial/new-day summary is hidden. Completed-day summary remains available.
- Original cool web-theme palette restored: #090B10 background, dark blue-gray cards, teal/purple/yellow accents.
- Native ETA service, Backup/Restore, Widget, Snackbar Undo, route correction and GitHub release workflow remain.
