# VEGA STING 3.0.0 - Dual Camera & Unified Settings (Stable Release)

## Overview
The first major release of VEGA STING. 3.0.0 ships the full dual-camera
implementation (simultaneous front+rear capture into a single file with a live
composite preview), a unified settings experience with consistent selection
dialogs and master-toggle gating, and the VIJAYKRSHA.ONLINE branding polish in
About. Dual-camera support is hardware-dependent: devices without dual cameras
keep the single-camera experience, and Settings shows a live capability probe.

## What changed

### Dual camera (new)
- Simultaneous front + rear capture with Camera2, composited into one MP4 via a
  shared OpenGL pipeline
- Live orientation-aware composite preview (`PREVIEW STACKED`) using the same
  compositor as recording, so preview and file match exactly
- Portrait/landscape placement controls (front top/left, etc.), layout
  (auto/split/pip), split ratios 40:60 / 50:50 / 60:40
- Quality filtering (dual caps at 720p), FPS filtering, front-camera mirror,
  crop-to-fit / letterbox scaling
- `CAPABILITY [ SUPPORTED ]` live probe in Settings; DUAL CAMERA MODE master
  toggle gates every dependent row
- Integrated with the existing foreground RecordingService, notifications and
  file segmentation/rollover

### Settings experience
- All multi-option rows (RECORDING, FILE SIZE & STORAGE SAFETY, OVERLAY,
  DUAL CAMERA, HARDWARE) open one consistent selection dialog instead of
  cycling values on tap
- ENABLE/DISABLE toggles keep tap-to-flip behaviour
- Master toggles grey out their dependent rows exactly like DUAL CAMERA MODE
  (WATERMARK gates the overlay group, FILE SPLITTING gates the file limits)
- WATERMARK moved to the top of the OVERLAY section

### Branding
- ABOUT > DEVELOPER shows the VIJAYKRSHA.ONLINE logo inside the value
  brackets: `[{logo}VIJAYKRSHA.ONLINE]`

### Fixed
- Compositor EGL initialization order; preview visibility (SurfaceView
  z-order); preview orientation logic
- Recorder error listener recursion crash; camera lifecycle cleanup on errors

### Documentation
- README rewritten for the dual-camera feature, new settings behaviour and a
  fresh on-device screenshot set (consent, home, recording, selection, trash,
  settings, selection dialog, dual camera, overlay gating, about, widget)

## Versioning & Tooling
- Version: **3.0.0** (versionCode **21**), tag **v3.0.0**
- Major version bump from 2.0.5; version codes remain globally unique and
  increasing across both channels (2.0.6-dev.3 = 19, 3.0.0-dev.1 = 20,
  3.0.0 = 21)
- `release.bat --stable --major` / `scripts/version.ps1` handle the bump and
  refuse reused version codes or tags

## Documentation & Tests
- 1073 unit tests across all six build variants (DevDebug/DevRelease/
  StableDebug/StableRelease 227 each, Debug 94, Release 71), 0 failures
- On-device verified on a dual-camera Moto A015: all six dual-camera dialogs,
  master gating, overlay gating, storage picker, update channel, About

## Release Information
- Type: Stable / Production
- Channel: STABLE
- Tag: v3.0.0
- Version: 3.0.0 (versionCode 21)
- Package: com.vega.sting
- Minimum supported installed versionCode: 1
- Signer digest: 25e8a0f03004259b88336f11de623999ff9b8b55e6add80a7c3a8a5cc8b83414
