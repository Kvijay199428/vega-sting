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

* `next.versionCode` is the `versionCode` for the **next STABLE release**. It is
  advanced **only after** a stable build succeeds and passes verification.
* A **DEBUG build reuses the latest STABLE release's `versionCode`**
  (`next.versionCode - 1`) and does not consume a code of its own. Android
  refuses a lower `versionCode` but permits an **equal‑code reinstall**, so
  keeping DEBUG level with STABLE is what lets a device move STABLE ⇄ DEBUG in
  either direction. (If DEBUG were allowed to run ahead, switching back to
  STABLE would be a downgrade Android rejects — which is exactly the trap this
  policy avoids.)
* Consecutive DEBUG builds therefore share a `versionCode` and are told apart by
  their `versionName` (`x.y.z-dev.N`); the updater compares version names to
  order equal codes within a channel.
* Version names:
  * STABLE: `x.y.z` (plain — no suffix; see §8 for why)
  * DEBUG:  `x.y.z-dev.N`
* `version.properties` is the single source of truth:

```properties
next.versionCode=13            # versionCode for the NEXT stable release
minSupported.versionCode=1     # oldest installed code still eligible for OTA
release.baseVersion=2.0.1      # versionName of the LAST stable release
debug.baseVersion=2.0.2
debug.buildNumber=0
debug.versionName=2.0.2-dev.1
```

Rules applied by `scripts/version.ps1`:

* `--stable` patch‑bumps the last stable version: `2.0.1 → 2.0.2`, and consumes
  `next.versionCode` (which then advances).
* `--stable --minor` / `--major` bumps minor/major instead: `2.1.0` / `3.0.0`.
* `--stable --respin` is accepted but identical to `--stable` (a patch bump):
  stable names carry no counter, and an already‑installed version can never be
  re‑offered.
* `--dev` increments the dev counter on the current dev base and reuses the last
  stable's `versionCode` (the counter does not move).
* After a successful `--stable` build the dev base auto‑advances to
  `stableBase + 1 patch` (but never moves backwards).

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
  "versionName": "2.0.2",
  "versionCode": 13,
  "packageName": "com.vega.sting",
  "apkAsset": "vega-sting-stable-2.0.2.apk",
  "sha256": "<64 lowercase hex chars>",
  "minSupportedVersionCode": 1
}
```

The updater lists `/releases` (never `/releases/latest`), filters by channel and
tag prefix, downloads the manifest and selects the **highest compatible
versionCode** — not the most recently published release. A candidate is rejected
(never silently swapped for the other channel) when the manifest is malformed or
missing, the channel/package/version do not match, the checksum is not a valid
SHA‑256, the release is a downgrade, or the installed version is below the
manifest's `minSupportedVersionCode`.

Candidate eligibility depends on whether the user is **switching channels**:

* Same channel — a real bump is required: a strictly higher `versionCode`, or an
  equal code with a newer `versionName` (the shared‑code `x.y.z-dev.N` line).
* Channel switch — the selected channel's **newest valid release** is offered, so
  both channels' newest builds are reachable from either side. An **equal**
  `versionCode` is a plain equal‑code reinstall. On a **manual** check (the
  channel‑switch confirm and **Check for updates**) a *lower* `versionCode` is
  offered too — a deliberate reinstall — but the update dialog warns that it is
  older and Android refuses to replace a newer app with an older one. The
  throttled automatic check never offers a downgrade, so it stays quiet until a
  newer build ships. Android's `INSTALL_FAILED_VERSION_DOWNGRADE` gives no result
  callback, so a refused downgrade is reported on the next launch.

## 5. Building a release

```bat
release.bat --stable              :: next production release (patch bump)
release.bat --stable --minor      :: next minor production release
release.bat --stable --major      :: next major production release
release.bat --stable --respin     :: alias of --stable (patch bump)
release.bat --dev                 :: next DEBUG/prerelease build
release.bat --dev --base 2.0.3    :: start a new DEBUG base

debug.bat                          :: alias for `release.bat --dev`
```

Each run:

1. derives the version from `version.properties` (no manual prompt);
2. runs the channel unit tests (`testStableReleaseUnitTest` / `testDevReleaseUnitTest`);
3. assembles `assembleStableRelease` / `assembleDevRelease` with
   `-PvegaVersionName` / `-PvegaVersionCode`;
4. verifies the APK (package id, version, embedded default channel, signature,
   keystore match) and writes `dist/<channel>/` — the APK, `update.json`, the
   `.sha256` file and `PUBLISH.txt`;
5. advances `version.properties` **only** on success; returns a non‑zero exit
   code on any failure and never publishes anything.

Gradle files are never edited by text replacement, and the other channel's
version name is never touched.

## 6. In‑app behaviour

* Automatic checks are throttled (6 h) and deferred while a recording is in
  progress. Manual **Check for updates** always runs.
* Selecting a channel in Settings shows a confirmation dialog; **Continue** saves
  the preference and immediately checks the chosen channel, **Cancel** leaves the
  previous preference untouched.
* Switching to a channel whose newest release is older than the installed build
  still offers that build on a manual check, with an in‑dialog warning that
  Android may refuse the downgrade. The preference is saved either way. If
  Android refuses the install, an **install‑not‑completed** dialog explains it on
  the next launch. If the chosen channel has published nothing yet, a
  **channel‑ahead** dialog says so instead — it never claims the channel is
  already installed.
* Downloads go to app‑private storage and are validated (SHA‑256, package,
  version, signer) before the system installer is opened. The user must confirm
  the install; nothing is ever auto‑installed.
* Skipped versions are stored per channel (`ignored_version_STABLE` /
  `ignored_version_DEBUG`), so skipping in one channel cannot hide a release in
  the other.

## 7. Publishing (manual)

1. Run the appropriate `release.bat` invocation.
2. Create a GitHub release with the printed tag (`v<versionName>` for STABLE,
   `debug-v<versionName>` for DEBUG).
3. Publish it as a **normal** release (STABLE) or a **pre‑release** (DEBUG);
   never a draft.
4. Attach the APK, `update.json` and the `.sha256` file from `dist/<channel>/`.
5. Note the channel and version in the release notes.

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
  build that is not at least equal by `versionCode` in the same‑channel case and
  there is otherwise no way to distinguish two builds named `2.0.2`.

So the first STABLE release under this scheme is the bridge: existing users are
offered it automatically in‑app (no manual download) and, once installed, run
the channel‑aware updater with `update.json`. Every later STABLE release keeps
the same digit‑leading tag, so stragglers stay reachable indefinitely.

DEBUG releases are pre‑releases with a letter‑leading tag, so they are invisible
to those older builds by design.

> Releases published before this scheme carry no `update.json`. The new updater
> skips them while scanning, but selection still works: it keeps the highest
> compatible candidate, so one valid STABLE release is enough.
