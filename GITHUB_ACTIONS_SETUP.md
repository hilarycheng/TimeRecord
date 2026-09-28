# GitHub Actions build

The real workflow is:

`.github/workflows/android-build.yml`

A visible copy is also included at:

`GITHUB_ACTIONS_ANDROID_BUILD.yml`

GitHub only runs the copy under `.github/workflows/`.

## Build without release signing secrets

The workflow always builds an installable debug APK and an unsigned release APK.

## Build an update-compatible signed release

Add these repository Actions secrets:

- `SIGNING_KEY_B64` — base64 of `time-recorder-local.keystore`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Then run **Actions → Android Build → Run workflow**.

The signed/unsigned release artifact is uploaded as `TimeRecorder-release-apk` and debug as `TimeRecorder-debug-apk`.
