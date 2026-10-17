# VEGA STING 3.0.0-dev.1 - Dual Camera & Unified Settings (Debug Pre-release)

## Overview
Prerelease build of VEGA STING 3.0.0 for the DEBUG channel. Contains the full
dual-camera implementation, the unified settings dialogs with master-toggle
gating, and the VIJAYKRSHA.ONLINE branding polish. Prereleases may contain
unfinished features; use the STABLE channel for production use.

## What changed

### Dual camera (new)
- Simultaneous front + rear capture (Camera2 + shared OpenGL compositor) into
  one MP4, with live orientation-aware composite preview
- Placement/layout controls, split ratios 40:60 / 50:50 / 60:40, quality/FPS
  filtering, mirror, crop-to-fit / letterbox scaling
- `CAPABILITY [ SUPPORTED ]` probe; DUAL CAMERA MODE gates dependent rows;
  integrated with RecordingService, notifications and segmentation

### Settings experience
- Shared selection dialog for every multi-option row (RECORDING, FILE SIZE &
  STORAGE SAFETY, OVERLAY, DUAL CAMERA, HARDWARE); ENABLE/DISABLE toggles
  still flip on tap
- Master toggles (WATERMARK, FILE SPLITTING, DUAL CAMERA MODE) grey out their
  dependent rows
- WATERMARK moved to the top of the OVERLAY section

### Branding
- ABOUT > DEVELOPER value renders `[{logo}VIJAYKRSHA.ONLINE]` with the
  VIJAYKRSHA.ONLINE logo inside the brackets

### Fixed
- Compositor EGL init order; preview z-order and orientation; recorder error
  listener recursion crash; camera lifecycle cleanup on errors

## Versioning & Tooling
- Version: **3.0.0-dev.1** (versionCode **20**), tag **debug-v3.0.0-dev.1**
- First prerelease of the 3.0.0 major line; new development base via
  `release.bat --dev --base 3.0.0`
- Version codes remain globally unique and increasing across both channels

## Documentation & Tests
- 1073 unit tests across all six build variants, 0 failures
- On-device verified on a dual-camera Moto A015

## Release Information
- Type: Prerelease
- Channel: DEBUG
- Tag: debug-v3.0.0-dev.1
- Version: 3.0.0-dev.1 (versionCode 20)
- Package: com.vega.sting
- Minimum supported installed versionCode: 1
- Signer digest: 25e8a0f03004259b88336f11de623999ff9b8b55e6add80a7c3a8a5cc8b83414
