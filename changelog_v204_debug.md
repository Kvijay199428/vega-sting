# VEGA STING 2.0.4-dev.1 - Bidirectional Channel Switching (DEBUG Prerelease)

## Overview
First DEBUG build under the aligned version-code policy. It shares its versionCode
with the 2.0.3 stable release, which is what makes switching between the STABLE and
DEBUG channels installable in both directions. This is a pre-release: it is
invisible to the older pre-channel updater and is only offered to devices that have
already selected the DEBUG channel.

## What changed since 2.0.3

### Shared versionCode for channel switching
- This build carries versionCode 15 -- the same code as STABLE 2.0.3 -- rather than
  a new higher code. Android allows an equal-code reinstall but never a downgrade,
  so a device can move STABLE 2.0.3 <-> DEBUG 2.0.4-dev.1 freely.
- Consecutive DEBUG builds (`2.0.4-dev.1`, `2.0.4-dev.2`, ...) share that code and
  are ordered by versionName; the updater compares version names so a newer dev
  build is still offered.

### Channel-switch messaging
- When a switch would be a downgrade, the app shows a "Channel Not Available Yet"
  dialog that explains the Android downgrade rule and confirms the channel
  preference was saved, instead of the previous misleading "already on the latest
  build" message.
- The "You're Up To Date" dialog now reports the installed build's channel, version
  name and build number.

## Behaviour
- Pre-release channel: intended for testing, may contain unfinished features.
- Nothing is auto-installed; downloads are validated (SHA-256, package id, version
  code, signing certificate) before the system installer is opened.

## Documentation & Tests
- `docs/OTA_CHANNELS.md` updated for the shared-versionCode policy and the
  channel-ahead behaviour.
- Unit tests cover cross-channel equal-code switches, cross-channel downgrades,
  same-channel equal-code dev builds and version-name ordering.

## Release Information
- Type: Pre-release / Development
- Channel: DEBUG
- Tag: debug-v2.0.4-dev.1
- Version: 2.0.4-dev.1 (versionCode 15)
- Package: com.vega.sting
- Assets: vega-sting-debug-2.0.4-dev.1.apk, update.json, vega-sting-debug-2.0.4-dev.1.apk.sha256
- Minimum supported installed versionCode: 1
