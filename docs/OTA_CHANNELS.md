# VEGA STING — Update channels & OTA releases

This document describes how the two update channels work, how versions are
allocated, how releases are built and published, and how the in‑app updater
decides what a user is offered.

## 1. Channels

| Channel  | Audience                     | GitHub release kind                     | Applies to |
|----------|------------------------------|-----------------------------------------|------------|
| `STABLE` | everyone                     | published release, **not** a pre‑release | the `stable` product flavor |
| `DEBUG`  | testers / early adopters     | published **pre‑release**                | the `dev` product flavor     |

* The channel a build belongs to is baked in at build time:
  * `BuildConfig.DEFAULT_UPDATE_CHANNEL` (`"STABLE"` / `"DEBUG"`)
  * the `DEFAULT_UPDATE_CHANNEL` `<meta-data>` entry in `AndroidManifest.xml`
    (used by the release scripts to verify an APK before publishing).
* The **installed** channel (from the build) and the **selected update channel**
  (a user preference, stored as `selected_updateChannel` in DataStore) are kept
  separate. The Settings screen shows both, e.g. `Installed: STABLE · Updates: DEBUG`.
* On first run (or after clearing data) the selected channel falls back to the
  installed channel. A saved preference is never overwritten automatically.

## 2. Package & signing policy

* Every variant of both channels uses the package id `com.vega.sting`.
* The two **publishable** variants — `stableRelease` and `devRelease` — are both
  `release` builds and are signed with the **production** keystore.
* Local `*Debug` variants keep the Android debug keystore and are never
  published.
* Because published DEBUG and STABLE APKs share one package id **and** one
  signing certificate, a user can switch channels without uninstalling. The
  channel‑switch dialog warns that a DEBUG build replaces the current install.
* Signing credentials live **outside** the repository (in
  `~/.gradle/gradle.properties`). The `verifyReleaseSigning` guard fails the
  build if they are missing; `scripts/package-release.ps1` additionally refuses
  to package an APK whose signer certificate does not match the release keystore.

## 3. Version policy

* A **single, globally increasing `versionCode`** (`next.versionCode`) is shared
  by **both** channels, and no two published APKs may ever carry the same code.
  Every release — STABLE or DEBUG — consumes the current value and then advances
  it, so `versionCode` grows monotonically across the channel switch.
* This is what keeps an **in‑place install possible in either direction**:

  | Move            | Codes        | Result                         |
  |-----------------|--------------|--------------------------------|
  | STABLE → DEBUG  | 17 → 18      | ordinary update                |
  | DEBUG → STABLE  | 18 → 19      | ordinary update                |

  (If each channel had its own counter, moving DEBUG → STABLE could produce a
  lower code that Android rejects — forcing an uninstall and data loss.)
* A channel is identified by the release manifest / build flavor — **never** by
  the `versionCode`. Two channels publishing the same code is a **release‑policy
  violation**, reported as a diagnostic (never silently offered as an update).
* `next.versionCode` is advanced **only after** a build succeeds and passes
  verification, and it is advanced by **both** `--stable` and `--dev`. The
  `commit` step **refuses** a code that was already consumed (a lower
  `-VersionCode` than the reserved one), so a code can never be reused.
* `release.bat` runs a `version.ps1 verify` **preflight** before building: it
  fails if the computed code already appears in a local `dist/**/update.json` or
  if the release tag already exists on GitHub.
* Version names:
  * STABLE: `x.y.z` (plain — no suffix; see §8 for why)
  * DEBUG:  `x.y.z-dev.N`
* `version.properties` is the single source of truth:

```properties
next.versionCode=20            # versionCode reserved for the NEXT release on EITHER channel
minSupported.versionCode=1     # oldest installed code still eligible for OTA
release.baseVersion=2.0.5      # versionName of the LAST stable release
debug.baseVersion=2.0.6
debug.buildNumber=2
debug.versionName=2.0.6-dev.2
```

Rules applied by `scripts/version.ps1`:

* `--stable` patch‑bumps the last stable version: `2.0.5 → 2.0.6`, consumes
  `next.versionCode`, and then advances it.
* `--stable --minor` / `--major` bumps minor/major instead: `2.1.0` / `3.0.0`.
* `--stable --respin` is accepted but identical to `--stable` (a patch bump):
  stable names carry no counter, and an already‑installed version can never be
  re‑offered.
* `--dev` increments the dev counter on the current dev base and **also consumes
  and advances `next.versionCode`**, exactly like a stable build.
* After a successful `--stable` build the dev base auto‑advances to
  `stableBase + 1 patch` (but never moves backwards).
* A locally built APK (no `-PvegaVersionName`) reports the **next** release's
  `versionName`/`versionCode` pair, so its in‑app About screen matches that
  release's `update.json`. Such a build is labelled `LOCAL` and is debug‑signed.

## 4. Release naming & `update.json`

* Tag: STABLE `v<versionName>` (e.g. `v2.0.2`), DEBUG `debug-v<versionName>`
  (e.g. `debug-v2.0.3-dev.1`). The updater still accepts the legacy
  `stable-v<versionName>` form.
* APK asset: `vega-sting-stable-<versionName>.apk` /
  `vega-sting-debug-<versionName>.apk`.
* Every release **must** attach an `update.json` next to the APK:

```json
{
  "schemaVersion": 1,
  "channel": "STABLE",
  "versionName": "2.0.5",
  "versionCode": 17,
  "packageName": "com.vega.sting",
  "apkAsset": "vega-sting-stable-2.0.5.apk",
  "apkSizeBytes": 8817143,
  "sha256": "<64 lowercase hex chars>",
  "minSupportedVersionCode": 1
}
```

`apkSizeBytes` is optional: releases published before the field existed simply
omit it, and the updater falls back to the size GitHub reports for the asset.
When present it is authoritative — it is computed from the exact bytes that were
hashed, and a download whose length differs is rejected as truncated.

The updater lists `/releases` (never `/releases/latest`), filters by channel and
tag prefix, downloads the manifest and selects the **highest compatible
versionCode** — not the most recently published release. A candidate is rejected
(never silently swapped for the other channel) when the manifest is malformed or
missing, the channel/package/version do not match, the checksum is not a valid
SHA‑256, the release is a downgrade, or the installed version is below the
manifest's `minSupportedVersionCode`.

Candidate eligibility depends on whether the user is **switching channels**:

* Same channel — a real bump is required: a **strictly higher** `versionCode`.
  Because codes are globally unique, an equal code is never a newer build; it
  means "up to date", not an update.
* Channel switch — the selected channel's **newest valid release** is offered
  when it is strictly newer. An **equal** `versionCode` on a *different* channel
  is a release‑policy violation (two published APKs sharing a code); it is
  reported as a diagnostic and never offered and never silently substituted.
  On a **manual** check (the channel‑switch confirm and **Check for updates**) a
  *lower* `versionCode` is offered too — a deliberate reinstall — but the update
  dialog warns that it is older and Android refuses to replace a newer app with
  an older one. The throttled automatic check never offers a downgrade, so it
  stays quiet until a newer build ships. Android's
  `INSTALL_FAILED_VERSION_DOWNGRADE` gives no result callback, so a refused
  downgrade is reported on the next launch.

## 5. Building a release

```bat
release.bat --stable              :: next production release (patch bump)
release.bat --stable --minor      :: next minor production release
release.bat --stable --major      :: next major production release
release.bat --stable --respin     :: alias of --stable (patch bump)
release.bat --dev                 :: next DEBUG/prerelease build
release.bat --dev --base 2.0.3    :: start a new DEBUG base
release.bat --dev --publish       :: build and create the GitHub release

debug.bat                          :: alias for `release.bat --dev`
```

Each run:

1. derives the version from `version.properties` (no manual prompt);
2. **preflight** (`version.ps1 verify`): refuses a versionCode that is already
   present in a local `dist/**/update.json`, or a release tag that already exists
   on GitHub;
3. runs the channel unit tests (`testStableReleaseUnitTest` / `testDevReleaseUnitTest`);
4. assembles `assembleStableRelease` / `assembleDevRelease` with
   `-PvegaVersionName` / `-PvegaVersionCode`;
5. verifies the APK (package id, version, embedded default channel, signature,
   keystore match) and writes `dist/<channel>/` — the APK, `update.json`, the
   `.sha256` file and `PUBLISH.txt`;
6. advances `version.properties` **only** on success (and refuses a code that was
   already consumed); returns a non‑zero exit code on any failure;
7. with `--publish`, creates the GitHub release (pre‑release for DEBUG) and
   attaches the three artifacts; otherwise it leaves them in `dist/` for a manual
   upload and prints the tag to use.

Gradle files are never edited by text replacement, and the other channel's
version name is never touched.

## 6. In‑app behaviour

* Automatic checks are throttled (6 h) and deferred while a recording is in
  progress. Manual **Check for updates** always runs.
* Selecting a channel in Settings shows a confirmation dialog; **Continue** saves
  the preference and immediately checks the chosen channel, **Cancel** leaves the
  previous preference untouched.
* Switching to a channel whose newest release is older than the installed build
  still offers that build on a manual check, but through the **manual‑downgrade**
  dialog (§6.1) rather than the ordinary update prompt: Android will refuse an
  in‑place downgrade. The preference is saved either way. If the chosen channel
  has published nothing yet, a **channel‑ahead** dialog says so instead — it never
  claims the channel is already installed.
* Downloads are streamed to a private staging file, **verified** (size,
  SHA‑256, package, version, signer — see `ApkValidator`), and only then promoted
  to the canonical public folder `Download/VEGA-STING/`. A cancelled or invalid
  download never leaves a partial file behind and never overwrites a previously
  verified APK. The user must confirm the install in the system installer;
  nothing is ever auto‑installed.
* Before a download starts the updater checks free space: it requires
  `apkSize + max(20 MiB, 10%)`. If that is not available it shows a
  **not‑enough‑storage** dialog (Try Again / Check for Updates) and does **not**
  change the channel or the target release. Free space is re‑checked while the
  transfer runs, so a stream that grows past the estimate cannot fill the volume.
* A verified APK the user has not installed is **kept**. APKs strictly older than
  the installed version (i.e. an update that has already been applied) are
  reconciled away on the next launch. Reconciliation is idempotent and only ever
  touches files under `Download/VEGA-STING/`; an APK marked "keep" is never
  deleted.
* Skipped versions are stored per channel (`ignored_version_STABLE` /
  `ignored_version_DEBUG`), so skipping in one channel cannot hide a release in
  the other.
* A persistent **DEBUG build** banner is shown on the home screen whenever the
  *installed* build is a DEBUG prerelease. It is driven by the build's baked‑in
  channel, **not** by the selected subscription, so switching the subscription to
  STABLE never hides the fact that a prerelease is running.

### 6.1 Manual downgrade (channel switch to an older build)

When a manual check finds that the selected channel's newest build is **older**
than the installed one, the ordinary update prompt is replaced by a dedicated
**manual‑downgrade** dialog, because Android will not replace a newer app with an
older one in place. The flow is:

1. **Download for Manual Install** — the older APK is downloaded, verified (with
   the downgrade check relaxed but package/signature/checksum/size still
   enforced) and saved to `Download/VEGA-STING/`.
2. A **saved for manual install** dialog explains the next steps: open the folder
   in a file manager, uninstall VEGA STING, then open the saved APK.
3. Nothing is uninstalled automatically, and no install is launched — the user
   performs the uninstall/reinstall deliberately.

> **Data‑preservation limitation.** Recordings live on shared storage
> (`<storage root>/VEGA STING`) and survive an uninstall. The app database and
> preferences (settings, timestamp choices, in‑app history) are app‑private and
> **are removed** by an uninstall. There is currently no export/backup step; the
> app warns about this rather than silently losing data. A future release may add
> one (see the follow‑up note at the end of this document).

## 7. Publishing

1. Run the appropriate `release.bat` invocation (add `--publish` to create the
   GitHub release automatically, or do the steps below by hand).
2. Create a GitHub release with the printed tag (`v<versionName>` for STABLE,
   `debug-v<versionName>` for DEBUG).
3. Publish it as a **normal** release (STABLE) or a **pre‑release** (DEBUG);
   never a draft.
4. Attach the APK, `update.json` and the `.sha256` file from `dist/<channel>/`.
5. Note the channel and version in the release notes.

Never reuse a tag or a `versionCode`: the preflight in §5 refuses both, and a
reused code would make two published APKs indistinguishable to the installer.

## 8. Migration from pre‑channel builds

Builds already installed on user devices run the **previous** updater, which
cannot be changed. It only ever:

1. requests `GET /releases/latest` — GitHub returns the newest **non‑prerelease,
   non‑draft** release;
2. runs `normalizeVersion(tag)`, which strips an optional leading `v` and then
   keeps the leading digits of each dot‑segment — so a tag that begins with a
   letter yields `null` and the build reports "up to date" forever;
3. downloads the first `.apk` asset and installs it when the parsed version is
   newer than the installed `VERSION_NAME`.

Consequences, and why the rules in §3/§4 exist:

* **STABLE tags must begin with the version number** — hence the plain
  `v<versionName>` form. A `stable-v…` tag would be invisible to every existing
  install.
* **STABLE releases must never be pre‑releases**, or `/releases/latest` skips
  them.
* **STABLE version names must never be reused**, because the installer rejects a
  build that does not have a strictly higher `versionCode` in the same‑channel
  case and there is otherwise no way to distinguish two builds named `2.0.2`.

So the first STABLE release under this scheme is the bridge: existing users are
offered it automatically in‑app (no manual download) and, once installed, run
the channel‑aware updater with `update.json`. Every later STABLE release keeps
the same digit‑leading tag, so stragglers stay reachable indefinitely.

DEBUG releases are pre‑releases with a letter‑leading tag, so they are invisible
to those older builds by design.

> Releases published before this scheme carry no `update.json`. The new updater
> skips them while scanning, but selection still works: it keeps the highest
> compatible candidate, so one valid STABLE release is enough.

## 9. Known limitations & follow‑ups

* **No recording export/backup.** Because a manual downgrade requires an
  uninstall, and uninstall removes the app‑private database and preferences,
  users who downgrade lose in‑app settings/history while their recording files
  (on shared storage) are kept. The manual‑downgrade dialog warns about this.
  A future release could add an export/backup step; it is intentionally **not**
  implemented yet. This is the only follow‑up created by the updater overhaul.
* **MediaStore retention.** On API 29+ verified APKs are published through
  `MediaStore.Downloads`. Whether the *system* removes an app's Downloads
  contributions on uninstall is Android‑version dependent; the APK is written so
  it is visible in a file manager on all supported versions, and the updater
  never relies on it being present after an uninstall (the user can re‑download).
* **Legacy storage path.** On API 26–28 the APK is written directly to
  `Download/VEGA-STING/` under `WRITE_EXTERNAL_STORAGE` (declared `maxSdkVersion
  28`); API 29 uses `requestLegacyExternalStorage`; API 30+ relies on the
  all‑files access the recording feature already requires. This is untested on
  every OEM/API combination and should be verified on a representative device
  matrix before a production rollout.
