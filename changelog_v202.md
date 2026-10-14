# VEGA STING 2.0.2 - Update Channel Subscription & Migration Bridge (Stable Release)

## Overview
First stable release under the channel-subscription model, and the one-time
migration bridge: installs running the previous updater are offered 2.0.2
automatically in-app, with no manual APK download. After installing 2.0.2 the
user can pick which update channel to follow (STABLE or DEBUG) and the
manifest-driven updater takes over from there.

## Major Features

### User-selectable update channel
- New UPDATE CHANNEL section in Settings showing both the installed build
  channel and the selected update channel.
- Switching channel shows a confirmation dialog; Continue saves the preference
  and immediately checks the selected channel, Cancel leaves it untouched.
- The installed build channel and the selected update channel stay separate,
  and a saved preference is never overwritten on startup.

### Channel-aware updater
- Lists the GitHub /releases endpoint (never /releases/latest) and filters by
  channel.
- Requires a machine-readable update.json manifest on every release and picks
  the highest compatible versionCode within the chosen channel.
- Skipped versions are stored per channel (ignored_version_STABLE /
  ignored_version_DEBUG), so skipping in one channel cannot hide a release in
  the other.
- Preserves throttled/deferred auto-checks during recording, the manual
  "Check for updates" action, FileProvider downloads and the unknown-apps flow.

### Signed channels & in-place switching
- Both channels are production-signed with the same package id
  (com.vega.sting) and signing certificate, so a user can switch channels
  without uninstalling and reinstalling.

## Migration Bridge
- STABLE releases use the plain `v<versionName>` tag form (v2.0.2) and plain
  x.y.z version names, so the updater already installed on older devices can
  still discover the release through /releases/latest.
- Existing installs (2.0.1, 1.0.x and the old v1.0.9-debug pre-release) are
  offered 2.0.2 automatically and update in-app - no manual download needed.

## Integrity & Safety
- Every APK is validated before install: SHA-256 checksum, package id, version
  code and signing certificate.
- A candidate is rejected with a meaningful reason (never a silent fallback to
  the other channel) when the manifest is missing or malformed, the channel,
  package or version do not match, the checksum is not a valid SHA-256, the
  release is a downgrade, or the installed build is below the manifest's
  minSupportedVersionCode.
- The signing certificate is cross-checked against the release keystore, so a
  debug-signed APK can never be published.
- Download and install always require explicit user confirmation; nothing is
  ever installed automatically.

## Versioning & Tooling
- version.properties is the single source of truth; a globally monotonic
  versionCode is shared by both channels so a channel switch can never install
  a downgrade.
- release.bat / debug.bat derive the version automatically (no prompts);
  package-release.ps1 verifies the APK and stages dist/<channel>/ with the
  APK, update.json, the .sha256 file and PUBLISH.txt.
- Release tags: STABLE `v<versionName>`, DEBUG `debug-v<versionName>`.

## Documentation & Tests
- docs/OTA_CHANNELS.md documents the channels, versioning, the update.json
  contract and the migration rules.
- 113 JVM unit tests across both channels (UpdateChecker, UpdateChannel,
  ReleaseManifest, ApkValidator), 0 failures.

## Release Information
- Type: Stable / Production
- Channel: STABLE
- Tag: v2.0.2
- Version: 2.0.2 (versionCode 13)
- Package: com.vega.sting
- Assets: vega-sting-stable-2.0.2.apk, update.json, vega-sting-stable-2.0.2.apk.sha256
- Minimum supported installed versionCode: 1
