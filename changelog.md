# Changelog

## [3.0.0] - 2026-10-18

### Added
- Full dual camera implementation with simultaneous front+rear capture
- Live composite preview (orientation-aware, shared compositor with recording)
- Layout/placement controls, split ratios (40:60/50:50/60:40), quality/FPS filtering, mirror, scaling modes
- Dual pipeline integrated with existing RecordingService, notifications, segmentation/rollover
- Shared selection dialog for every multi-option settings row (RECORDING,
  FILE SIZE & STORAGE SAFETY, OVERLAY, DUAL CAMERA, HARDWARE)
- Dual-camera settings section with live `CAPABILITY [ SUPPORTED ]` probe
- VIJAYKRSHA.ONLINE logo inside the Settings > ABOUT > DEVELOPER value
  brackets (`[{logo}VIJAYKRSHA.ONLINE]`)
- Fresh on-device screenshot set for the README (consent, home, recording,
  selection, trash, settings, dialogs, dual camera, overlay gating,
  about/developer, widget)

### Changed
- Multi-option rows open a consistent selection dialog instead of cycling
  values on tap; ENABLE/DISABLE toggles keep tap-to-flip behaviour
- Master toggles (DUAL CAMERA MODE, WATERMARK, FILE SPLITTING, STORAGE
  SAFETY) now grey out their dependent rows, matching the dual-camera
  pattern
- WATERMARK moved to the top of the OVERLAY section (master first)
- README updated: dual-camera documented as implemented, settings
  description rewritten, screenshot tables refreshed

### Fixed
- Compositor EGL initialization order
- Preview visibility (SurfaceView z-order)
- Preview orientation logic
- Recorder error listener recursion crash
- Camera lifecycle cleanup on errors

### Tests
- All dual-camera unit tests passing (33 total)
- 1073 unit tests across all six build variants, 0 failures
