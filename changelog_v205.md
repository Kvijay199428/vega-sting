# VEGA STING 2.0.5 - Brand VIJAYKRSHA.ONLINE (Stable Release)

## Overview
This release introduces consistent branding for the app's owner identity (VIJAYKRSHA.ONLINE). It adds the owner logo to the in-app legal reader and the Settings > ABOUT > DEVELOPER section, updates the legal documents to carry the logo and a linked developer URL, bumps the legal document version to 1.2 (effective 15 October 2026) so consent is re-collected once on the next launch, and brands the README footer. The release also ships the updated logo assets in the repository.

## What changed

### Branding
- Added the VIJAYKRSHA.ONLINE triangular logo (vector drawable `ic_vijaykrsha_logo.xml`) derived from the provided favicon/icon artwork.
- Legal document dialog (Terms & Conditions and Privacy Policy) now shows a branded header with the logo and the VIJAYKRSHA.ONLINE name, visible in onboarding and Settings.
- Settings > ABOUT > DEVELOPER now displays the logo next to the developer name (logo-only; no behavior change).
- README footer now credits the owner with the logo and a linked developer URL.
- Repository includes the committed brand assets under `.assets/vijaykrsha-online/` for consistent Markdown image rendering.

### Legal documents
- `PRIVACY_POLICY.md` and `TERMS_AND_CONDITIONS.md` updated to include the owner logo at the top, link the developer name to `https://app.vijaykrsha.online`, bump **Document Version** to **1.2**, and set **Effective Date** to 15 October 2026.
- `res/raw/privacy_policy.txt` and `res/raw/terms_and_conditions.txt` updated to Document Version 1.2 (effective 15 October 2026).
- `LegalConsent.kt` constants updated to 1.2; consent model unchanged. The version bump means **every installed user re-consents once** on the next launch.
- Unit tests updated to match the new shipped version.

## Behaviour
- No functional changes to recording, storage, or update logic. Branding is cosmetic and isolated to UI + documentation.
- Nothing is auto-installed; downloads remain verified by SHA-256, package id, version code and signing certificate.

## Integrity & Safety
- Signing unchanged (same production certificate). `keystoreChecked=True` at build time.
- Network connectivity remains used only to check for updates from the public GitHub Releases repository; no recording content or personal data is transmitted.
- Same channel rules and update semantics unchanged.

## Versioning & Tooling
- Version: **2.0.5** (versionCode **17**), tag **v2.0.5**.
- DEBUG builds reuse the stable versionCode minus one; channels remain switchable in both directions via equal-code reinstall.
- `release.bat` / `debug.bat` / `scripts/version.ps1` unchanged; no auto-publish, non-zero exit on failure.

## Documentation & Tests
- Legal consent tests updated for version 1.2 (all legal/consent tests passing).
- 248 unit tests total across DevDebug and StableDebug, 0 failures.

## Release Information
- Type: Stable / Production
- Channel: STABLE
- Tag: v2.0.5
- Version: 2.0.5 (versionCode 17)
- Package: com.vega.sting
- Assets: vega-sting-stable-2.0.5.apk, update.json, vega-sting-stable-2.0.5.apk.sha256
- Minimum supported installed versionCode: 1
- Signer digest: 25e8a0f03004259b88336f11de623999ff9b8b55e6add80a7c3a8a5cc8b83414
