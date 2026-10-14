# VEGA STING 2.0.6-dev.1 - Brand VIJAYKRSHA.ONLINE (DEBUG Prerelease)

## Overview
This pre-release carries the same branding changes as STABLE 2.0.5: consistent VIJAYKRSHA.ONLINE logo branding in the in-app legal dialog and Settings > ABOUT > DEVELOPER, legal documents updated with the logo and a linked developer URL, and legal document version bumped to 1.2 (effective 15 October 2026). This build shares its versionCode with the upcoming STABLE 2.0.5 (code 17), so switching between STABLE and DEBUG remains installable in both directions.

## What changed since 2.0.5-dev.1

### Branding
- Added VIJAYKRSHA.ONLINE triangular logo (`ic_vijaykrsha_logo.xml`).
- Legal document dialog (Terms & Conditions and Privacy Policy) shows a branded header with logo + VIJAYKRSHA.ONLINE name in onboarding and Settings.
- Settings > ABOUT > DEVELOPER shows logo next to the developer name (logo-only; no behavior change).
- README footer branded with logo and linked developer URL.
- Committed brand assets under `.assets/vijaykrsha-online/`.

### Legal documents
- `PRIVACY_POLICY.md`, `TERMS_AND_CONDITIONS.md` updated with logo at top, linked developer URL, **Document Version 1.2**, effective 15 October 2026.
- `res/raw/privacy_policy.txt`, `res/raw/terms_and_conditions.txt` updated to Document Version 1.2 (effective 15 October 2026).
- `LegalConsent.kt` bumped to 1.2 → **re-consent once on next launch**.
- Unit tests updated for 1.2.

## Behaviour
- Pre-release; only offered to devices on DEBUG channel.
- No functional changes to recording/storage/update logic.
- Nothing auto-installed; downloads validated (SHA-256, package id, version code, signing certificate).
- Consecutive DEBUG builds share versionCode and are ordered by versionName.

## Integrity & Safety
- Same production certificate; `keystoreChecked=True`.
- Network only for update checks.

## Documentation & Tests
- Legal consent tests updated; all legal/consent tests passing. 248 unit tests across DevDebug and StableDebug, 0 failures.

## Release Information
- Type: Pre-release / Development
- Channel: DEBUG
- Tag: debug-v2.0.6-dev.1
- Version: 2.0.6-dev.1 (versionCode 17)
- Package: com.vega.sting
- Assets: vega-sting-debug-2.0.6-dev.1.apk, update.json, vega-sting-debug-2.0.6-dev.1.apk.sha256
- Minimum supported installed versionCode: 1
- Signer digest: 25e8a0f03004259b88336f11de623999ff9b8b55e6add80a7c3a8a5cc8b83414
