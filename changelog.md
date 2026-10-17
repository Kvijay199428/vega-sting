# Changelog

## [2.0.6] - 2026-10-16

### Added
- Full dual camera implementation with simultaneous front+rear capture
- Live composite preview (orientation-aware, shared compositor with recording)
- Layout/placement controls, split ratios (40:60/50:50/60:40), quality/FPS filtering, mirror, scaling modes
- Dual pipeline integrated with existing RecordingService, notifications, segmentation/rollover

### Fixed
- Compositor EGL initialization order
- Preview visibility (SurfaceView z-order)
- Preview orientation logic
- Recorder error listener recursion crash
- Camera lifecycle cleanup on errors

### Tests
- All dual-camera unit tests passing (33 total)

