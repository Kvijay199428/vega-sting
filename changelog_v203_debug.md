# VEGA STING 2.0.3-dev.1 - Update Channel Subscription (Debug / Pre-release)

## Overview
DEBUG-channel pre-release of the update channel subscription model. It is
distributed as a pre-release only and is intentionally invisible to installs
running the previous updater; it is intended for testers and early adopters who
select the DEBUG channel in Settings. The matching STABLE release is v2.0.2.

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
- DEBUG accepts published pre-releases; STABLE never surfaces a pre-release.
- Skipped versions are stored per channel (ignored_version_STABLE /
  ignored_version_DEBUG), so skipping in one channel cannot hide a release in
  the other.
- Preserves throttled/deferred auto-checks during recording, the manual
  "Check for updates" action, FileProvider downloads and the unknown-apps flow.

### Signed channels & in-place switching
- Both channels are production-signed with the same package id
  (com.vega.sting) and signing certificate, so testers can move between DEBUG
  and STABLE without uninstalling.

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
- Type: Debug / Pre-release
- Channel: DEBUG
- Tag: debug-v2.0.3-dev.1
- Version: 2.0.3-dev.1 (versionCode 14)
- Package: com.vega.sting
- Assets: vega-sting-debug-2.0.3-dev.1.apk, update.json, vega-sting-debug-2.0.3-dev.1.apk.sha256
- Minimum supported installed versionCode: 1
