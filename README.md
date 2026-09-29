# VEGA STING

Screen-recording terminal for Android. Black / orange / monospace UI, built with
Kotlin, Jetpack Compose and CameraX.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/kotlin/com/vega/sting/MainActivity.kt` | Home screen, navigation, recording service control |
| `app/src/main/kotlin/com/vega/sting/services/RecordingService.kt` | Foreground recording service |
| `app/src/main/kotlin/com/vega/sting/database/` | Room database for recordings, metadata and trash |
| `app/src/main/kotlin/com/vega/sting/storage/` | Storage backends (internal, SD card, OTG) and health checks |
| `app/src/main/kotlin/com/vega/sting/settings/SettingsManager.kt` | DataStore-backed preferences |
| `app/src/main/kotlin/com/vega/sting/ui/settings/SettingsScreen.kt` | Settings screen |
| `app/src/main/kotlin/com/vega/sting/ui/update/` | OTA dialogs and the update state host |
| `app/src/main/kotlin/com/vega/sting/updater/` | GitHub release check, APK download, installer handoff |
| `app/src/test/kotlin/com/vega/sting/updater/` | OTA version-comparison and release-parsing tests |

## Building

Requirements:

- JDK 21
- Android SDK with `ANDROID_HOME` set (`D:\sdk\android` on the build machine)
- Gradle 8.7 via the bundled wrapper

```powershell
.\gradlew.bat clean
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
```

Build outputs:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

`assembleRelease` runs the `verifyReleaseSigning` guard first. If release-signing
properties are missing, the build fails with a clear message instead of silently
producing an unsigned APK that cannot be installed.

## Release signing

The release key is deliberately **not** in the repository. The build reads these
Gradle properties from `~/.gradle/gradle.properties`:

```properties
VEGA_STING_STORE_FILE=D:/VEGA/.keys/vega-sting.keystore
VEGA_STING_STORE_PASSWORD=<store password>
VEGA_STING_KEY_ALIAS=vega-sting
VEGA_STING_KEY_PASSWORD=<key password>
```

The key is an RSA-4096 PKCS#12 keystore, valid until 2054, SHA-256 fingerprint:

```
25:E8:A0:F0:30:04:25:9B:88:33:6F:11:DE:62:39:99:FF:9B:8B:55:E6:AD:D8:0A:7C:3A:8A:5C:C8:B8:34:14
```

Back up both the keystore and `gradle.properties` somewhere durable and private.
Anyone without the key cannot ship an update that Android will accept over an
existing installation.

### Upgrading from a debug-signed build

`1.0.0` was installed with the standard Android debug key. Android refuses to
install a differently-signed package over an existing app, so the first release
build must be installed manually:

```powershell
adb uninstall com.vega.sting
adb install app/build/outputs/apk/release/app-release.apk
```

**Do this before the first release update.** After that, OTA updates keep the
release signature and install normally.

## OTA updates

The app checks the public GitHub Releases API for a newer version and installs it
through the system Package Installer.

- Endpoint: `https://api.github.com/repos/Kvijay199428/VEGA-STING/releases/latest`
- Owner and repo come from `BuildConfig.GITHUB_OWNER` / `BuildConfig.GITHUB_REPO`
- The **latest** release is used, and the first asset ending in `.apk` is downloaded
- The APK is stored under the app-private `filesDir/updates` directory and shared
  with the installer through a `FileProvider` (`${applicationId}.provider`)

Behaviour:

- A startup check runs at most once every 6 hours and never mid-recording
- Settings → About → **Check For Updates** forces an immediate, unthrottled check
  and re-offers a version the user previously skipped
- An update found mid-recording is deferred and re-offered once recording stops
- **Skip This Version** suppresses that version until a newer one ships
- Downloaded APKs are purged after each install attempt so storage does not grow

Android requires *Install unknown apps* permission for sideloaded packages. When
it is missing, the app routes the user to the per-app settings screen and resumes
at the install prompt once the toggle is enabled.

### Publishing an update

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Build and verify the release APK.
3. Commit, tag and push, then create a GitHub release whose tag is the version
   name and whose first `.apk` asset is `app-release.apk`.

A release marked as a pre-release or draft is not returned by the `latest`
endpoint, so users never see an unfinished build.

## Permissions

| Permission | Reason |
| --- | --- |
| `INTERNET` | Fetching release metadata and downloading the update APK |
| `REQUEST_INSTALL_PACKAGES` | Handing the downloaded APK to the system installer |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` / `FOREGROUND_SERVICE_MICROPHONE` | Recording while the screen is off |
| `CAMERA`, `RECORD_AUDIO` | Video and audio capture |
| `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, storage permissions | Playback and SD/OTG import |

## Testing

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

The OTA tests cover version comparison (`1.10.0` > `1.9.0`, `v` prefixes, missing
segments, invalid input) and release-JSON parsing (APK selection, missing fields,
explicit JSON nulls, malformed input). These are the parts most likely to break
OTA updates silently.
