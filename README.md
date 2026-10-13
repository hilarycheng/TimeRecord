# Time Recorder v2.20 — Relative Notifications + Rule Simulation

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

## v2.14.1 build fix

- Removed invalid alarm-theme `windowShowWhenLocked` / `windowTurnScreenOn` XML attributes; the ringing Activity continues to use `setShowWhenLocked(true)` and `setTurnScreenOn(true)` at runtime.
- Removed Compose `Modifier.weight()` usage from the ringing screen and uses explicit equal button widths instead.

## v2.15 alarm reliability + personal leave

- New/enabled alarms now require a complete core preflight: Exact alarm, Notifications and Full-screen alarm access.
- Alarms page shows an explicit protection status card for those permissions.
- Added **1 分鐘後測試鬧鐘** so the full lock-screen path can be verified on the real device before relying on it.
- If a ringing alarm cannot surface full-screen, opening Time Recorder automatically lands on the Alarms page with Snooze / Dismiss controls.
- Optional `READ_CALENDAR` access: a Working Day alarm skips an all-day system-calendar event whose title is exactly `放假`.
- Calendar permission denied / query failure is fail-safe: the alarm rings rather than silently skipping.
- Personal `放假` days are considered both when computing the next trigger and again at the actual trigger time.


## v2.16 Audio Profile leave-day rule

Audio Profile now treats an all-day system Calendar event whose title is exactly `放假` as a non-working day, matching the existing alarm rule. Weekend, official Hong Kong public holidays, and personal `放假` days all use the Default profile. Calendar permission/query failures keep the previous fail-safe Working Day behaviour.

## v2.17 Private reminders + calendar rule engine

- The existing Calendar page is now a combined calendar surface:
  - official Hong Kong public holidays (1823 cache)
  - read-only Android/System/Google Calendar events when `READ_CALENDAR` is granted
  - app-local **Private Reminder** anchor dates
  - required-action dates/windows generated by reminder rules
- Private reminders are stored only in the app's local SQLite database. They are **not** written to Google/System Calendar. Android backup remains enabled, so normal device backup may include the database.
- Reminder Rules support two modes:
  - **Countdown** — e.g. D-14 / D-3 / D-1 / D-day
  - **Required Window** — e.g. complete a prerequisite between D-18 and D-14
- System Calendar rules match event-title keywords. Private reminders select a rule directly and do not require keywords.
- Required Window tasks appear every day in the window until completed, show **最後一日** on the last valid day, then **已錯過有效期限** until the anchor date if still incomplete.
- Home screen shows **今日必做** only when there are unmatched, incomplete tasks for today.
- Reminder notifications are scheduled at each rule's configured time; the notification includes a **完成** action.
- Editing/deleting/moving a source Calendar event is naturally reflected because reminder instances are regenerated from the current Calendar data; stale posted reminders are reconciled when the app/scheduler runs.
- Calendar permission failures never delete local/private reminders.


## v2.18 Advanced Calendar / Related Tasks / Notifications

- Local Private Events support **Full Day** or an explicit **Start / End date + time**. Existing v2.17 events remain full-day.
- Reminder Rules combine main Event notifications, automatically derived **Related Range Tasks** and independent **D-N / H-N preparation steps** (e.g. fasting).
- A Related Task (such as bloodwork before a follow-up) has **one completion key across its entire D-18...D-14 window**; no day selection or appointment scheduling is required. Eligible dates are shown in Calendar.
- Related Tasks can use every day, Mon–Fri, or Mon–Sat Morning (Saturday ends at 12:00); optionally exclude government-1823 public holidays. If the holiday cache does not cover a relevant year, the UI warns that the dates are provisional.
- **Multiple independent notifications** per main Event / child Task / preparation step. D-N notifications have their own 24-hour clock times (default 09:00; never silently 00:00). H-N notifications count actual hours from a timed Event or step, including across midnight.
- A rule can combine D-28, D-14, D-7, D-3, D-1, D-day, multiple times on one day, window-daily reminders and relative-hour alerts.
- Completing a Related Task stops only its own future notifications; main Event notifications are unaffected. Early countdown notifications do not include a misleading 'Completed' action.
- An **in-app persistent banner** appears at the top of all pages for active/approaching tasks until the task window/appointment expires or is marked complete. Includes a short Undo for completion. This is not an Android system-wide overlay.
- Notifications use one rolling upcoming AlarmManager alarm at a time; both exact-alarm special access and notification permission are needed for the best available delivery. Android Doze/battery policies prevent an absolute 100% guarantee. The rolling look-ahead is 400 days and is refreshed on app entry, rescheduling and boot/time changes.
- Existing `private_reminders.db` upgrades from schema v1 to v2 using non-destructive ALTER TABLE statements, retaining rules, private items and completion records. Legacy rule notification settings are mapped to the new notice schedule. Legacy daily-slot alarms are cancelled on upgrade.
- Calendar is read-only; Private Events and tasks remain local SQLite (device backup continues to be allowed).

### Validation

- Pure Kotlin engine and scheduler: compiled with local Android API stubs and tested with weekday/PH filtering, independent completion, two D-28 times, H-based preparation, and Saturday-morning cutoff.
- Reminder Rules Compose screen and SQLite store: compiled with local API stubs for syntax/type checks.
- A full Android Gradle build/device alarm test was **not** possible in this environment. Use the existing GitHub Actions build workflow or Android Studio before deploying this as a production alarm.


## v2.19 Full-screen Rule editing

- Replaced the **Rule**, **Related Range Task**, **Preparation Task** and **Notification** form popups with true full-screen, scrollable Compose pages (not Dialogs). The system time picker remains a native time picker.
- Every editor has an explicit Back / Cancel action and a bottom-pinned Save button; Android system Back navigates to the parent editor instead of exiting Calendar.
- Unsaved parent Rule / Task form fields and their scroll positions remain intact while you edit a nested Task or Notification. Child edits apply to the draft, and are written to SQLite only when the top-level Rule is saved.
- Existing Rule, Related Task, Step and Notification IDs, multiple notification schedules, eligibility conditions and SQLite schema remain unchanged. Added inline validation for invalid reversed Range bounds, integer limits and duplicate notification entries.
- Deleting a Rule still requires a confirmation because linked private events are deleted too; no full-form edit uses a popup. The **Private Event editor** is unchanged in this UI-only release.

### v2.19 validation

- The full-screen Rule screens compile in Kotlin against local API/Compose/model stubs. Navigation and nested-draft retention are statically verified; no Android SDK is installed here, so a real Gradle build, instrumentation and device tests remain outstanding.


## v2.20 Signed D-N / D+N & Rule Simulation

- Notification rules now support **D-N / D-Day / D+N** at user-defined local clock times. A signed, inclusive **Day Range** (e.g. D-2 through D+2) delivers one notification per day at that chosen time. Existing D-N/H-N/eligible-window notices remain compatible.
- System Calendar and private-event lookup ranges are expanded by actual configured offsets, so a D+N alert can be found after the source Event date. Main Event notifications deliberately configured after the date are preserved independently of marking the original Event completed.
- **Rules → Edit Rule → Simulate Rule** opens a standalone full-screen, read-only preview. Use a sample Event date; toggle Full Day or set Start and End time (Hong Kong time).
- Simulation uses the **same source-expansion and notification calculations as production**, including PH-aware qualifying dates, Mon–Sat Morning cutoff, independent related/preparation tasks, N-hour timings and overlapping-time deduplication.
- Preview shows all generated Tasks, can expand all eligible days and pages through all resulting Notification instants (100 per page). Warnings identify no eligible dates, missing holiday-year coverage, absent H-N base on full-day events, duplicate notifications and disabled rules. Historical instants are labeled as past.
- **Test Notification Now** and individual **Test** buttons immediately post a sample via the real normal/urgent Calendar reminder Notification Channel, honoring its configured sound, vibration, mute/DND and visibility. Android 13+ notification permission is requested *only* when the user explicitly taps Test. A test does not add a Complete action.
- **Simulation/Test do not save the draft Rule or Event, modify SQLite task completions, mark notifications delivered, alter posted-notification reconciliation ledgers, or schedule an AlarmManager trigger.** Test posts are identified with a distinct notification tag and `[測試]` title.
- Selecting Simulate doesn't require saving the parent Rule draft. Returning to the Rule editor keeps unsaved edits in memory. Nested Task/Notification drafts must first be accepted back into the parent form to be included in a whole-Rule simulation.

### Verification limitations

- Compiled the pure Kotlin Reminder Engine with local Android API stubs; regression tests passed for two separate D-28 times, post-event D+2 lookup, H-4 and H-N preparation, holiday exclusion, duplicate suppression and full-day warnings. Simulation did not read completion records or write source data.
- Compiled the full-screen Rule editor and new Rule Simulation screen against Kotlin/Compose API stubs. These checks are **not** an Android Gradle build or real-device validation.
- **APK build, Android 13+ permission flow, actual sounds/vibration/channel settings, and on-device AlarmManager delivery still require testing in Android Studio or the existing GitHub Actions build pipeline.** Testing a notification immediately does not verify future scheduled alarm reliability.
