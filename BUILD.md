# Building Memo

## Path A — GitHub Actions (recommended; no local toolchain needed)

1. Push to `main` (any commit) or run **Actions → Build Memo APK → Run workflow**.
2. When green, open the run → **Artifacts** → download `memo-apk` (retained 14 days).
3. Unzip, enable "Install unknown apps" for your file manager, install
   `app-debug.apk` on an Android 11+ device.
4. The workflow also prints the APK's `sha256sum` in the *Verify APK* step —
   compare it with your downloaded file if you care about integrity.

## Path B — Local build (needs JDK 17 + Android SDK 35)

```bash
# prerequisites
export JAVA_HOME=<jdk-17>
export ANDROID_HOME=<sdk-root>            # must contain platforms;android-35, build-tools;35.0.0

./gradlew --no-daemon --stacktrace testDebugUnitTest lintDebug assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

To assemble only: `./gradlew assembleDebug`. Tests only: `./gradlew testDebugUnitTest`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| "sdkmanager not found" in CI | unusual runner layout | the workflow already probes `cmdline-tools/latest`, `cmdline-tools/16.0`, and falls back to `find`; open an issue if it still fails |
| "Could not find platform-tools/android-35" | SDK license not accepted | workflow accepts licenses; locally run `yes \| sdkmanager --licenses` |
| Wrapper checksum mismatch (`distributionSha256Sum`) | corrupted Gradle download | delete `~/.gradle/wrapper/dists/gradle-8.14.6-*` and retry; the SHA in `gradle/wrapper/gradle-wrapper.properties` is the official services.gradle.org value |
| `AndroidX` errors like "androidx.compose..." unresolved | missing `android.useAndroidX=true` | it lives in `gradle.properties`; the CI also re-asserts it |
| Kotlin metadata / LTR-LIM incompatibility | Kotlin patch drift vs litertlm | keep Kotlin 2.4.2x with litertlm-android 0.18.1 (see ENGINEERING_LOG C-004) |
| AGP requires newer Gradle | wrapper drift | wrapper is pinned 8.14.6, matching AGP 8.13.0; don't hand-bump one without the other |

## Versioning

`gradle-wrapper` 8.14.6 · AGP 8.13.0 · Kotlin 2.4.21 · compileSdk/target 35 · minSdk 30 · JDK 17.
All pins are recorded with rationale in `docs/ENGINEERING_LOG.md` (decision D-004).
