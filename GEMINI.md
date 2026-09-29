# VEGA STING - Project Instructions

## Overview
VEGA STING is a professional Android audio and video recording application with a Bloomberg Terminal-style UI.

## Tech Stack
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose
- **Database:** Room
- **Camera API:** CameraX
- **Recording Engine:** MediaCodec + MediaMuxer (avoid pure MediaRecorder)
- **Minimum SDK:** 26
- **Target SDK:** 35

## UI Design (Bloomberg Terminal Style)
- **Background:** `#000000` (Black)
- **Typography:** Monospace fonts (JetBrains Mono, IBM Plex Mono, Roboto Mono)
- **Accent Color:** `#FF7A00` (Orange)
- **Style:** Dense information, high contrast, grid-based, functional over decorative.
- **Buttons:** Minimal corner radius (4dp), orange borders/text.

## Core Architecture
Follow a modular package structure within the `app` module:
- `ui`: Jetpack Compose screens and components.
- `services`: Foreground services for background recording.
- `recording`: Core recording engine (CameraX, MediaCodec).
- `storage`: File management and Room database.
- `permissions`: Permission handling logic.
- `settings`: App configuration management.
- `trash`: Soft delete and recovery system.
- `watermark`: Overlay processing logic.

## Storage Structure
Internal Storage/VEGA STING/
- `Videos/`
- `Audio/`
- `Trash/`
- `Temp/`

## Platform Constraints
- **Foreground Service:** Mandatory for background recording with a persistent notification.
- **Permissions:** Handle camera, audio, and storage permissions gracefully. Support permission reset detection.
- **Codecs:** Dynamically detect supported codecs using `MediaCodecList`. Default to H.264/AAC in MP4.

## Development Phases
1. **Phase 1:** Core recorder (Camera, Audio, Save files).
2. **Phase 2:** UI + database (Home screen, Trash, Settings).
3. **Phase 3:** Advanced recording (Watermarks, Stabilization, Codec selector).
4. **Phase 4:** Widget + gesture recording.
5. **Phase 5:** Security + encryption.
