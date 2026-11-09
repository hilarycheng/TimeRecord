# Build locally

Requirements: JDK 17+, Android SDK Platform 37, Build Tools 36.0.0+.
The Gradle Wrapper downloads Gradle 9.6.1 when it is not already cached.

For an update-compatible release build, copy the supplied `time-recorder-local.keystore` to the project root and create `keystore.properties`:

```
storeFile=time-recorder-local.keystore
storePassword=<store password>
keyAlias=<alias>
keyPassword=<key password>
```

Then run `./gradlew :app:assembleRelease` (or use Android Studio's Gradle task).
The APK is emitted under `app/build/outputs/apk/release/`.
