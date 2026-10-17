# VEGA STING 2.0.6-dev.3 - Unique version codes & reliable in-app updates (DEBUG Prerelease)

## Overview
This pre-release fixes the in-app updater and the release tooling so a published
version can never collide with the installed one again. Version codes are now
strictly unique and strictly increasing across both channels, the release
builder refuses to reuse a code, and the About screen always agrees with the
published `update.json`.

DEBUG builds are pre-releases: they are only offered to devices already on the
DEBUG channel and are never installed automatically.

## What changed since 2.0.6-dev.2

### Fixed: "Download failed: ... Android rejects downgrades"
- The updater could offer a build whose **versionCode equalled** the installed
  one (matched by a newer `versionName`), and then the download check rejected it
  because Android only accepts a strictly higher code. This contradiction is
  gone.
- Same channel: only a **strictly higher** versionCode is offered. An equal code
  now means "You're up to date", not an update.

### Version policy: one globally unique, strictly increasing code
- A single `next.versionCode` sequence is shared by both channels; every release
  consumes and advances it. No two published APKs may ever share a code.
- `scripts/version.ps1 commit` now **refuses** a code that was already consumed
  instead of silently adopting it.
- New `scripts/version.ps1 verify` scans packaged `dist/**/update.json` files and
  the published release tags, and fails if the version is already used.
- `release.bat` runs that verification as a **preflight** (step 1 of 5) before
  building, so a reused version is caught before anything is compiled.

### Fixed: About screen did not match the release
- A locally built APK paired the *last released* versionName with the *next*
  reserved versionCode. Local builds now derive their versionName from the same
  "next" computation as the code, so the pair is coherent.
- Such builds are labelled `VERSION · LOCAL` with a "debug-signed, not a
  published release" note, so a local APK is never mistaken for a release.

### Channel switching
- An equal versionCode seen while switching channels is now reported as a
  **release-policy violation** diagnostic (two published APKs sharing a code),
  never offered as an update and never silently substituted. A strictly newer
  build on the other channel is offered as an ordinary update; a lower one only
  through the existing manual-downgrade flow.

### Tooling
- `release.bat --publish` optionally creates the GitHub release and attaches the
  APK, `update.json` and the `.sha256` file (next to the existing manual path).
- `.gitattributes` pins `*.bat` to CRLF; an LF-only batch file makes `cmd`'s
  `goto` fail with "cannot find the batch label".
- The equal-code and downgrade messages are split and made unit-testable.

### Tests
- Updated the channel/selection tests for the strict rule and added
  strictly-higher-code and policy-violation cases, plus version-guard wording
  tests.
- 227 unit tests, 0 failures (DevRelease).

## Behaviour
- In-app updates require a strictly higher versionCode; equal = up to date.
- Downloads are still fully validated (SHA-256, package id, version code,
  signing certificate) before the system installer is opened.
- Pre-release; only offered to devices on the DEBUG channel. Nothing is
  installed automatically.

## Integrity & Safety
- Same production certificate (signer digest 25e8a0f0...), keystoreChecked=True.
- No production certificate or signing changes.
- Network is used only for update checks.

## Release Information
- Type: Pre-release / Development (DEBUG)
- Channel: DEBUG
- Tag: debug-v2.0.6-dev.3
- Version: 2.0.6-dev.3 (versionCode 19)
- Package: com.vega.sting
- Assets: vega-sting-debug-2.0.6-dev.3.apk, update.json, vega-sting-debug-2.0.6-dev.3.apk.sha256
- APK size: 8,833,559 bytes
- SHA-256: 2abc51bc1a6a9842e5a202b1e2670f0108c9987ae1fdf12df9cbb49f1291384e
- Minimum supported installed versionCode: 1
- Signer digest: 25e8a0f03004259b88336f11de623999ff9b8b55e6add80a7c3a8a5cc8b83414

## Note on installing over a locally-built (debug-signed) APK
Only relevant if the installed build was produced by Android Studio's `Debug`
variants (signed with the Android debug key) rather than by `release.bat`. Android
refuses to replace a debug-signed install with a production-signed one, so a
one-time uninstall is required before installing this release. Production-signed
dev.1/dev.2 installs update in place with no uninstall.
