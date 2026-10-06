# Time Recorder v2.12 — Audio Profiles

Native Android / Jetpack Compose personal commute recorder.

## Existing features retained
- Hong Kong KMB 38 / 42C ETA, foreground tracking, cache countdown
- Timeline, Smart Next, custom vehicle, history/charts, widget
- Hong Kong public holiday calendar from official 1823 iCal
- JSON backup / restore

## Audio Profile Scheduler
Menu → **Audio Profiles**

- `Default` profile for Saturday / Sunday / Hong Kong public holidays
- Two Working Day profiles (defaults: Office 09:00, After Work 18:00)
- Before the Office time on a Working Day, the active profile is Default
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

## v2.14 Explicit Alarms

- Alarms are never created automatically. They only exist after the user taps **Alarms → + Add** and enables them.
- Multiple alarms are supported.
- Each alarm can be **Working Day Only** (Mon–Fri, excluding cached Hong Kong public holidays) or **Every Day**.
- Workday alarms fail safe: if a future holiday is not present in the cached official calendar, a weekday alarm rings rather than silently skipping.
- Uses Android `AlarmManager.setAlarmClock()` and the existing **Alarms & reminders** special access.
- Ringing uses a short-lived `mediaPlayback` foreground service with the system alarm sound.
- Before ringing, the app persists the current alarm-stream volume and temporarily boosts it to the alarm's configured ring volume.
- Dismiss, Snooze (10 minutes), 30-minute timeout, app recovery, or reboot restores the alarm stream.
- If an Audio Profile changes while an alarm is ringing, the profile's requested Alarm level is deferred and becomes the restore target after the alarm stops.
- Existing Android Clock / third-party alarms are not read, edited, enabled, disabled, or cancelled by Time Recorder.


## v2.14

- Clock-style full-screen ringing Activity with Snooze / Stop.
- Full-screen intent notification fallback to heads-up when system access is unavailable.
- First Smart Next label is `開始返工`; all other Smart Next labels are unchanged.
