# VEGA STING 1.0.9 - Dual Camera Preview and OTA Channel Separation

## Major Features

### Dual Camera Support (Foundation)
- Added DualCameraCapabilityChecker to detect front/rear camera availability
- Added DualCameraConfig for layout options (portrait stack/landscape split)
- Added foundational stubs for DualCameraController, DualCameraCompositor, and DualRecordingPipeline

### Dual Camera Preview UI
- Added DUAL CAMERA section in Settings
- Added PREVIEW STACKED button with visual preview dialog
- Preview shows front and back views in stacked layouts (portrait/landscape)
- Color-coded visualization matching planned layout

### OTA Update System
- Implemented separate OTA channels - stable (release) and debug (development)
- UpdateChecker fetches from appropriate GitHub endpoints based on channel
- Added UPDATE_CHANNEL to BuildConfig (stable for release, debug for debug builds)
- Release and debug channels fully isolated

## Improvements
- Settings UI enhanced with dual camera configuration and preview access
- Updated to v1.0.9 (versionCode 10)
- Channel-aware OTA configuration integrated into build pipeline

## Technical Details
- Dual-camera components follow phased implementation plan
- Preview UI uses Jetpack Compose
- OTA channel logic respects GitHub release types (stable vs prerelease)

## Downloads
- vega-sting-1.0.9.apk (Release - Stable)
- app-debug.apk (Debug Build)

## Status
- Dual camera currently provides the settings/configuration foundation only.
  Concurrent dual-camera capture and broader device support are not enabled in
  this release; support will be implemented in a future release once supported
  devices are confirmed.
