# VEGA STING 2.0.5-dev.1 - Offer the Other Channel's Newest Build (DEBUG Prerelease)

## Overview
First DEBUG build after the channel-switch offer fix. It shares its versionCode
with the 2.0.4 stable release (both code 16), which is what keeps switching
between the STABLE and DEBUG channels installable in both directions. This is a
pre-release: it is invisible to the older pre-channel updater and is only offered
to devices that have already selected the DEBUG channel.

## What changed since 2.0.4

### Channel switching offers the target channel's newest build
- Selecting a channel now offers that channel's newest valid release, whatever
  its number relative to the installed build, instead of refusing when the target
  build was older.
- An **equal** `versionCode` remains the normal equal-code reinstall; a **lower**
  one is offered on manual checks as a deliberate reinstall.
- The updater never substitutes the other channel's release.

### Manual switches offer a downgrade; periodic checks stay quiet
- The channel-switch confirmation and **Check for updates** may offer an older
  target build. The throttled (6 h) automatic startup check never does, so it
  cannot nag.

### Clear warning, and feedback when the install is refused
- The update dialog warns when the offered build is older than the installed one.
- A refused downgrade (Android gives no install result through `ACTION_VIEW`) is
  now reported with an **Install Not Completed** dialog on the next launch.

### "Channel Not Available Yet" is now only for an empty channel
- The dialog is shown only when the selected channel has published nothing the
  updater can use yet, not when the installed build happens to be ahead.

## Behaviour
- Pre-release channel: intended for testing, may contain unfinished features.
- Nothing is auto-installed; downloads are validated (SHA-256, package id, version
  code, signing certificate) before the system installer is opened.
- Same-channel debug updates still need a newer version name; consecutive dev
  builds share a versionCode and are ordered by `versionName`.

## Documentation & Tests
- `docs/OTA_CHANNELS.md` updated for the newest-build offer, the manual-vs-
  automatic downgrade rule and the refused-downgrade feedback.
- Unit tests cover a manual switch to an older build, the quiet automatic switch,
  and picking the channel's newest when several are older.

## Release Information
- Type: Pre-release / Development
- Channel: DEBUG
- Tag: debug-v2.0.5-dev.1
- Version: 2.0.5-dev.1 (versionCode 16)
- Package: com.vega.sting
- Assets: vega-sting-debug-2.0.5-dev.1.apk, update.json, vega-sting-debug-2.0.5-dev.1.apk.sha256
- Minimum supported installed versionCode: 1
