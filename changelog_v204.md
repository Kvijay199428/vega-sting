# VEGA STING 2.0.4 - Offer the Other Channel's Newest Build (Stable Release)

## Overview
2.0.3 aligned the two channels' version codes so a STABLE <-> DEBUG switch
installs in both directions. It still refused to *offer* a switch when the target
channel's newest build was numerically older than the installed one: the user was
shown a "Channel Not Available Yet" dialog and got nothing to install, even though
that build was the newest in its own channel.

2.0.4 makes a channel switch offer the target channel's **newest valid build**
whatever its number relative to the installed app, warns before downloading when
that build is older, and reports the outcome if Android refuses the install. The
two channels stay fully isolated: a check never offers a build from the channel
the user did not select.

## What changed

### Channel switching offers the target channel's newest build
- Selecting a channel now resolves to that channel's highest `versionCode`
  release (ties broken by `versionName`), regardless of how it compares to the
  installed build - so both channels' newest builds are reachable from either
  side.
- An **equal** `versionCode` is still the normal case (an equal-code reinstall).
  A **lower** one is now offered too, on manual checks, as a deliberate
  reinstall; Android's refusal of a true downgrade is surfaced instead of being
  pre-emptively blocked.
- The updater never silently substitutes the other channel's release.

### Manual switches offer a downgrade; periodic checks stay quiet
- The channel-switch confirmation and **Check for updates** run a manual check,
  which may offer an older target build (and immediately shows its dialog).
- The throttled (6 h) automatic startup check never offers a downgrade, so it
  cannot nag while the selected channel only has older builds.

### Clear warning, and feedback when the install is refused
- The update dialog shows a warning when the offered build is older than the
  installed one, explaining that Android will not replace a newer app with an
  older one.
- `ACTION_VIEW` gives no install result, so a refused downgrade used to fail
  silently. The app now records a downgrade attempt and, on the next launch,
  shows an **Install Not Completed** dialog when the version did not change.

### "Channel Not Available Yet" is now only for an empty channel
- That dialog no longer means "your build is ahead"; it is shown only when the
  selected channel has published no release the updater can use yet.

## Behaviour
- The channel preference is always saved, whether or not the switch can be
  installed immediately.
- Nothing is ever auto-installed; the system installer still requires explicit
  confirmation, and downloads remain verified by SHA-256, package id, version
  code and signing certificate.
- Same-channel updates still require a real bump: a strictly higher `versionCode`,
  or an equal code with a newer `versionName` (the shared-code `x.y.z-dev.N`
  line).

## Integrity & Safety
- No change to the signing model: both channels remain production-signed with the
  same package id (`com.vega.sting`) and certificate.
- Candidates are still rejected (never swapped) when the manifest is missing or
  malformed, the channel/package/version do not match, the checksum is not a
  valid SHA-256, or the installed version is below `minSupportedVersionCode`.

## Versioning & Tooling
- `version.properties` semantics are unchanged: `next.versionCode` is the code
  for the next STABLE release and moves only on a stable build; DEBUG builds reuse
  `next.versionCode - 1` and leave the counter untouched, so STABLE and DEBUG
  newest builds share a code and switch as an equal-code reinstall.
- `release.bat` / `debug.bat` / `scripts/version.ps1` are unchanged by this
  release and still never text-replace Gradle files, never auto-publish, and
  return a non-zero exit code on failure.

## Documentation & Tests
- `docs/OTA_CHANNELS.md` documents that a switch offers the target channel's
  newest build, the manual-vs-automatic downgrade rule and the refused-downgrade
  feedback.
- Unit tests cover: a manual switch to an older build is offered; the automatic
  switch stays quiet; a switch picks the channel's newest when several are older;
  plus the existing equal-code and version-name ordering cases.

## Release Information
- Type: Stable / Production
- Channel: STABLE
- Tag: v2.0.4
- Version: 2.0.4 (versionCode 16)
- Package: com.vega.sting
- Assets: vega-sting-stable-2.0.4.apk, update.json, vega-sting-stable-2.0.4.apk.sha256
- Minimum supported installed versionCode: 1
