# VEGA STING 2.0.6-dev.2 - Unified Overlay for Dual Camera (DEBUG Prerelease)

## Overview
This pre-release adds unified overlay support for dual camera mode. The watermark/timestamp/signature overlay now applies to both dual camera recording and the in-app dual preview (WYSIWYG). Positioning is across the whole composite frame. Single-camera overlay behaviour remains unchanged.

## What changed since 2.0.6-dev.1

### Overlay (Unified)
- Added shared overlay infrastructure: OverlayLayer, OverlayGeometry, OverlaySettingsComposer, OverlaySettingsLoader (overlay package).
- Refactored OverlayRenderer to delegate overlay rendering to shared OverlayLayer.
- DualCameraCompositor: added optional overlay pass with full-viewport restore and blend; overlay drawn after clipped feeds before eglSwapBuffers.
- DualRecordingPipeline: loads overlay settings via shared loader; attaches OverlayLayer (rotationHint=0, composite already upright); survives rebuild/rollover.
- DualCameraController (preview): attaches overlay to preview compositor (WYSIWYG), reattaches on surface resize, releases cleanly.
- RecordingManager: delegates loadOverlaySettings() to shared loader.

### Tests
- Added OverlayGeometryTest (7 tests).
- Added OverlaySettingsComposerTest (5 tests).
- Full suite: 221 unit tests, 0 failures (up from 209).

### Build
- Built app/build/outputs/apk/dev/debug/app-dev-debug.apk (md5: B8D3618F2406D467DA7F059F138EDA76).

## Behaviour
- Dual preview (PREVIEW STACKED) shows overlay (WYSIWYG). Dual recording bakes overlay across whole composite frame. Single-camera unchanged.
- If watermark disabled or timestamp/signature empty, overlay pass draws nothing.

## Release Information
- Type: Pre-release / Development (DEBUG)
- Channel: DEBUG
- Tag: v2.0.6-dev.2
- Version: 2.0.6-dev.2 (versionCode 17)
- Package: com.vega.sting
- Assets: app-dev-debug.apk (debug only)
