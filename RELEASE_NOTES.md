# VEGA STING Release Notes

## v2.0.6 - Dual Camera Full Implementation (2026-10-16)

This release completes the end-to-end dual camera feature. Both front and rear cameras can be captured simultaneously with a single composited video file containing one audio track.

### New Features

- Dual Camera Mode: Enable simultaneous front + rear camera capture in Settings > DUAL CAMERA.
- Live Composite Preview: Real-time preview showing both feeds composited according to current settings.
- Flexible Layout: Auto layout adapts to orientation; portrait (FRONT TOP/BOTTOM) and landscape (FRONT LEFT/RIGHT) placement.
- Adjustable Split: 40:60, 50:50, 60:40 split ratios.
- Quality & FPS: Capability-filtered based on hardware/encoder support.
- Mirror Front Camera: Independent mirroring for front feed.
- Scaling Modes: CROP TO FILL and FIT WITH BARS for each region.

### Bug Fixes

- Fixed compositor EGL init order causing black/composite rendering issues.
- Fixed preview visibility and orientation logic.
- Fixed infinite recursion in recorder error listeners.
- Improved resource cleanup and camera lifecycle error handling.

### Technical Details

- Camera2 with concurrent camera support detection.
- OpenGL compositor feeds single MediaRecorder surface; one mic track per segment.
- Segmentation/rollover supported; dual pipeline only when supported.
- Unit tests: DualCameraConfig (8), DualCameraGeometry (19), DualCameraCapabilityResult (6) passing.

### APKs

- Debug: app-dev-debug-2.0.6-dev.1-42fbff7.apk (commit 42fbff7)
- Stable: vega-sting-2.0.6-stable-42fbff7.apk (commit 42fbff7)

