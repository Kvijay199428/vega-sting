VEGA STING v1.0.6 - Continuous Recording, File Splitting & Storage Protection

## New Features
- Continuous file splitting: Automatically starts next segment when approaching file size limit (500 MB default); video and audio segmented with seamless rotation and fallback.
- Session-aware recording: Tracks recording sessions and part indices (_part-N) for stable naming and reliable recovery.
- Storage protection: Configurable free-space reserve (100 MB default), low storage warnings (1 GB threshold), critical handling, and video→audio fallback when reserve+margin cannot be maintained.
- New settings: "FILE SIZE & STORAGE SAFETY" section in Settings (enable/disable splitting, set video/audio max MB, free-space reserve MB, audio fallback margin MB).

## Improvements
- Recovery: Detects interrupted segments on startup and restores state; orphaned part files named with part pattern are imported.
- Database: v4 schema with sessionId/partIndex/segmentStatus/finalizationReason/isSessionComplete; migration 3→4.
- UX: Low storage dialogs, part counts in library rows, service-centered entry points.
- Reliability: Single RecordingService authority with 1s storage monitor.

## Tests
- JVM tests: StoragePolicyTest, RecordingSessionManagerTest, RecordingSettingsTest all pass.
- Instrumentation: AppDatabaseMigrationTest validates v3→v4 migration.
