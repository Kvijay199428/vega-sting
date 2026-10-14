# VEGA STING 2.0.3 - Bidirectional Channel Switching (Stable Release)

## Overview
Fixes the one-way channel trap introduced by the 2.0.2 migration bridge. On 2.0.2
a device could move STABLE -> DEBUG but not back: the DEBUG build carried a higher
versionCode than the current STABLE release, and Android refuses to install a
lower versionCode over a higher one. 2.0.3 aligns the two channels' version codes
so switching works in both directions, and replaces the misleading "already on the
latest STABLE build" dialog with an accurate explanation when a switch cannot be
applied yet.

## Fixes

### Channel switching now works both ways
- A DEBUG build now reuses the versionCode of the latest STABLE release instead of
  consuming a new, higher one. Android permits an equal-code reinstall but never a
  downgrade, so STABLE <-> DEBUG switches always install.
- Consecutive DEBUG builds share a versionCode and are distinguished by their
  versionName (`x.y.z-dev.N`); the updater now orders equal codes by version name,
  so `2.0.4-dev.2` is still offered over `2.0.4-dev.1`.

### Accurate "channel ahead" messaging
- When the selected channel's newest release is older than the installed build
  (e.g. device on DEBUG 2.0.3-dev.1 selecting STABLE whose newest is 2.0.2), the
  app no longer claims the channel is already installed. A dedicated
  "Channel Not Available Yet" dialog explains that Android cannot downgrade, that
  the channel preference has been saved, and that the next release on that channel
  will be offered automatically.
- The "You're Up To Date" dialog now also shows which build is actually installed
  (channel, version name and build number), so it can never report the wrong
  channel.
- Switching to a channel that has published nothing yet reports the same
  channel-ahead result rather than "up to date".

## Behaviour
- The channel preference is kept on a blocked switch; the user stays subscribed to
  the chosen channel and receives its next release automatically.
- Nothing is ever auto-installed; the system installer still requires explicit
  confirmation, and downloads remain verified by SHA-256, package id, version code
  and signing certificate.

## Integrity & Safety
- No change to the signing model: both channels remain production-signed with the
  same package id (`com.vega.sting`) and certificate.
- A lower versionCode is still never offered; the change only allows an equal-code
  cross-channel reinstall.

## Versioning & Tooling
- `version.properties` semantics clarified: `next.versionCode` is the code for the
  next STABLE release and moves only on a stable build; DEBUG builds use
  `next.versionCode - 1` and leave the counter untouched.
- `release.bat` / `debug.bat` / `scripts/version.ps1` updated accordingly; the
  version engine never text-replaces Gradle files and returns a non-zero exit code
  on failure.

## Documentation & Tests
- `docs/OTA_CHANNELS.md` documents the shared-versionCode policy, the channel-switch
  eligibility rules and the channel-ahead behaviour.
- Unit tests added for cross-channel equal-code switches, cross-channel downgrades,
  same-channel equal-code dev builds, empty-channel switches and version-name
  ordering.

## Release Information
- Type: Stable / Production
- Channel: STABLE
- Tag: v2.0.3
- Version: 2.0.3 (versionCode 15)
- Package: com.vega.sting
- Assets: vega-sting-stable-2.0.3.apk, update.json, vega-sting-stable-2.0.3.apk.sha256
- Minimum supported installed versionCode: 1
