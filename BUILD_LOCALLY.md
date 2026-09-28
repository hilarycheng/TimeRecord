# Build locally

Requirements: JDK 17+, Android SDK Platform 37, Build Tools 36.0.0+, Gradle 9.6.x.

For an update-compatible release build, copy the supplied `time-recorder-local.keystore` to the project root and create `keystore.properties`:

```
storeFile=time-recorder-local.keystore
storePassword=<store password>
keyAlias=<alias>
keyPassword=<key password>
```

Then run `gradle :app:assembleRelease` (or use Android Studio's Gradle task).
The APK is emitted under `app/build/outputs/apk/release/`.
