# VEGA STING - v1.0.9 Release Notes

## Major Features

### Dual Camera Support (Foundation)
- Capability Checker: Added DualCameraCapabilityChecker to detect front/rear camera availability and concurrent access support
- Configuration System: Added DualCameraConfig with layout options (portrait stack/landscape split), camera positioning, split ratio control, and mirroring preferences
- Core Components: Added foundational stubs for DualCameraController, DualCameraCompositor, and DualRecordingPipeline

### Dual Camera Preview UI
- Settings Integration: Added DUAL CAMERA section in Settings
- Stacked Preview: Added PREVIEW STACKED button displaying live visual preview dialog
- Visual Layout Display: Preview shows front and back camera views in portrait (top/bottom) or landscape (left/right) stacked layout
- Color-coded preview (Front in blue, Back in green) matching planned stacked layout

### OTA Update System
- Channel Separation: Implemented separate OTA channels - stable (release) and debug (development)
- Channel-Aware Fetching: UpdateChecker fetches from appropriate GitHub endpoints based on channel
- BuildConfig Integration: Added UPDATE_CHANNEL to BuildConfig (stable for release, debug for debug builds)
- Isolated Update Flows: Release and debug channels fully isolated

## Improvements
- Settings UI enhanced with dual camera configuration and preview access
- Updated to v1.0.9 (versionCode 10)
- Channel-aware OTA configuration integrated into build pipeline

## Technical Details
- Dual-camera components follow phased implementation plan
- Preview UI uses Jetpack Compose
- OTA channel logic respects GitHub release types (stable vs prerelease)
