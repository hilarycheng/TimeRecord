# Time Recorder v2.11 — Audio Profiles

Native Android / Jetpack Compose personal commute recorder.

## Existing features retained
- Hong Kong KMB 38 / 42C ETA, foreground tracking, cache countdown
- Timeline, Smart Next, custom vehicle, history/charts, widget
- Hong Kong public holiday calendar from official 1823 iCal
- JSON backup / restore

## Audio Profile Scheduler
Menu → **Audio Profiles**

- `Default` profile for Saturday / Sunday / Hong Kong public holidays
- Three Working Day profiles (defaults: Morning 07:00, Office 09:00, After Work 18:00)
- Each profile controls Ring, Notification, Media, Alarm and System volume levels
- Profile times and levels are stored in AndroidX Preferences DataStore
- Uses `Asia/Hong_Kong` for all schedule / holiday decisions
- Uses exact alarms; no 24-hour audio foreground service
- Re-schedules after reboot, app replacement and time changes
- App resume self-check: if enabled and actual volume differs from the profile that should be active now, it re-applies the target levels
- Ongoing silent notification shows the current profile and next transition
- Notification tap opens the Audio Profiles page
- Test Profile and Apply Now actions

## Permissions
When Audio Schedule is enabled, Android may ask for:
- **Alarms & reminders** — exact profile changes at configured times
- **Notifications** — ongoing current-profile status notification

No Do Not Disturb access is requested or used.

## Holiday source
Official 1823 Traditional Chinese iCal:
`https://www.1823.gov.hk/common/ical/tc.ics`

Cached holiday data is reused by both Calendar and Audio Profiles. If the cache is older than 7 days, the app refreshes it in the background when opened.

## Release build
GitHub Actions workflow: `.github/workflows/android-build.yml`

Release signing uses repository secrets already supported by this project.
