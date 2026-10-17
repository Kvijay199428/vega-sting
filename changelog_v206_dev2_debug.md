# VEGA STING 2.0.6-dev.2 - Unified Overlay for Dual Camera (DEBUG Prerelease)

## Overview
This pre-release brings the camera overlay (watermark, timestamp, signature) to dual camera mode. Until now the overlay was applied to single-camera recordings only; dual camera recordings and the dual live preview ignored it. Both paths now share one overlay implementation, so the same settings produce the same overlay everywhere.

DEBUG builds are pre-releases: they are only offered to devices already on the DEBUG channel and are never installed automatically.

## What changed since 2.0.6-dev.1

### Unified overlay (dual + single)
- The overlay is now baked into dual camera recordings across the whole composite frame (720x1280 portrait / 1280x720 landscape), matching the preview.
- The dual live preview (PREVIEW STACKED) now shows the overlay as well, so the preview matches the recording (what you see is what you get).
- Placement is tracked against the whole composite frame. The existing overlay settings apply unchanged — watermark on/off, timestamp on/off, timestamp position and format, signature text and position, and text size. No new settings or screens were added.
- Single-camera recording and preview behaviour is unchanged.
- Added a shared overlay layer plus pure helpers (geometry, settings composition, settings loading) now used by the single-camera renderer, the dual compositor (recording) and the dual preview controller.
- Guarded so that an empty overlay (watermark off and no timestamp/signature) draws nothing.

### Dual camera orientation
- Fixed front-camera feed orientation in dual camera mode so the front feed is oriented correctly in the composite.
- The single and dual paths now share rotation resolution.

### Update / OTA
- The background update check now runs in a small self-stopping service, with no foreground notification.
- Added a storage preflight before downloading an update, with a clear "Not Enough Storage" explanation and a retry action.
- Added the manual path used when the newest build on a channel is older than the installed one ("Download for Manual Install" saves the APK and explains the uninstall/install steps; "Saved for Manual Install").
- Added a "Check for Updates" action and a DEBUG build banner ("DEBUG BUILD - prerelease channel") with a "Manage channel" shortcut.

### Settings
- On a device that cannot run dual capture (no cameras / missing facing camera / concurrent-unsupported), dual capture is switched off and its controls are disabled instead of failing at capture time. A transient camera-enumeration error only disables the controls and leaves the stored preference alone.
- The current screen (including the open playback item) now survives device rotation instead of returning to home.

### Tests
- Added overlay geometry and settings-composition tests, plus dual-camera geometry/orientation/config/capability and orientation-state tests.
- 221 unit tests, 0 failures (DevRelease).

## Behaviour
- The overlay applies when the dual camera capability is SUPPORTED and dual camera mode is ON.
- Dual composite frames stay upright (recording orientation hint 0); single camera keeps its own orientation handling.
- Pre-release; only offered to devices on the DEBUG channel. Nothing is installed automatically.
- Downloads are validated (SHA-256, package id, version code, signing certificate).

## Integrity & Safety
- Same production certificate (signer digest 25e8a0f0...), keystoreChecked=True.
- No production certificate or signing changes.
- Network is used only for update checks.

## Release Information
- Type: Pre-release / Development (DEBUG)
- Channel: DEBUG
- Tag: debug-v2.0.6-dev.2
- Version: 2.0.6-dev.2 (versionCode 18)
- Package: com.vega.sting
- Assets: vega-sting-debug-2.0.6-dev.2.apk, update.json, vega-sting-debug-2.0.6-dev.2.apk.sha256
- APK size: 8,833,555 bytes
- SHA-256: 414055b6807a43ac4171bf43ea04eeb2ee526325ddb0f25fa3a289fb4a11588a
- Minimum supported installed versionCode: 1
- Signer digest: 25e8a0f03004259b88336f11de623999ff9b8b55e6add80a7c3a8a5cc8b83414
