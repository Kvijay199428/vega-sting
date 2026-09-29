package com.vega.sting.camera

import android.util.Log

/**
 * A recording preset that can be applied to MediaRecorder + CaptureRequest.
 */
data class RecordingPreset(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: String,           // "H264" or "HEVC"
    val videoStabilization: Boolean,
    val opticalStabilization: Boolean,
    val score: Int               // higher = safer/better
)

/**
 * User's video configuration preference (from settings).
 *
 * [resolution] is one of "AUTO", "480P", "720P", "1080P", "1440P", "4K".
 * [ratio] is one of "AUTO", "16:9", "4:3", "1:1", "DISPLAY".
 * [displayAspect] is the device screen aspect (max/min, e.g. 2.22 for 20:9)
 * used when a DISPLAY/display-ratio match is requested.
 */
data class VideoPreference(
    val resolution: String = "AUTO",
    val ratio: String = "AUTO",
    val displayAspect: Float = 16f / 9f
)

/**
 * Selects the safest optimal recording preset from a [DeviceProfile].
 *
 * Scoring weights:
 *  - Resolution quality   (30%)
 *  - FPS match            (20%)
 *  - Encoder reliability  (25%)
 *  - Hardware level bonus (15%)
 *  - Stabilization bonus  (10%)
 *
 * Aggressive combos (4K60 on LIMITED, HEVC without hardware encoder)
 * are heavily penalized to prevent crashes.
 */
object SafePresetSelector {

    private const val TAG = "SafePresetSelector"
    private const val ASPECT_TOLERANCE = 0.05f

    // ── Target tiers ordered by preference ──────────────────────────────

    private data class ResolutionTier(
        val width: Int,
        val height: Int,
        val label: String,
        val qualityScore: Int  // out of 30
    )

    private val RESOLUTION_TIERS = listOf(
        ResolutionTier(1920, 1080, "1080p", 30),
        ResolutionTier(2560, 1440, "1440p", 28),
        ResolutionTier(3840, 2160, "4K",    20),  // risky on many devices
        ResolutionTier(1280,  720, "720p",  22),
        ResolutionTier( 854,  480, "480p",  10),  // 16:9 480p (SD widescreen)
        ResolutionTier( 640,  480, "480p",  10)   // 4:3 480p (legacy SD)
    )

    private val FPS_TIERS = listOf(30, 24, 25, 60, 15)

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns a ranked list of safe presets for the given camera, best first.
     * [cameraId] defaults to "0" (back camera).
     * [blacklistedConfigs] are "WxH@FPS" strings from telemetry failures.
     * [preference] narrows selection to the requested size/ratio and steps
     * down to the nearest compatible tier when the exact request is unsupported.
     */
    fun selectPresets(
        profile: DeviceProfile,
        cameraId: String = "0",
        blacklistedConfigs: Set<String> = emptySet(),
        preference: VideoPreference = VideoPreference()
    ): List<RecordingPreset> {
        val camera = profile.cameras[cameraId]
        if (camera == null) {
            Log.w(TAG, "Camera $cameraId not found in profile, using fallback")
            return listOf(fallbackPreset())
        }

        val supportedSizes = camera.videoSizes.mapNotNull { parseSize(it) }.toSet()
        val supportedFps = camera.fpsRanges.mapNotNull { parseFpsUpper(it) }.toSet()
        val hwLevel = camera.hardwareLevel
        val stab = camera.stabilization

        val hasHardwareH264 = profile.encoders.videoEncoders.any {
            it.mimeType == "video/avc" && it.isHardwareAccelerated
        }

        val h264Encoder = profile.encoders.videoEncoders.firstOrNull {
            it.mimeType == "video/avc" && it.isHardwareAccelerated
        } ?: profile.encoders.videoEncoders.firstOrNull {
            it.mimeType == "video/avc"
        }

        // ── Apply user preference (size + ratio), stepping down if needed ──
        val requestedWidth = preferredWidthFor(preference.resolution)
        val ratioPredicate = ratioPredicateFor(preference)

        val orderedTiers = if (requestedWidth > 0) {
            RESOLUTION_TIERS.sortedWith(
                compareBy<ResolutionTier> {
                    when {
                        it.width == requestedWidth -> 0   // exact match first
                        it.width < requestedWidth -> 1    // then step down
                        else -> 2                         // then anything larger
                    }
                }.thenBy { kotlin.math.abs(it.width - requestedWidth) }
            )
        } else {
            RESOLUTION_TIERS
        }

        // Ratio-constrained list; if nothing matches that ratio, relax to the
        // fully ordered list so we always step down to the nearest compatible.
        val constrainedTiers = if (ratioPredicate == null) {
            orderedTiers
        } else {
            orderedTiers.filter(ratioPredicate)
        }
        val usableTiers = if (constrainedTiers.isEmpty()) orderedTiers else constrainedTiers

        val presets = mutableListOf<RecordingPreset>()

        for (tier in usableTiers) {
            // Check if device supports this resolution
            if (Pair(tier.width, tier.height) !in supportedSizes &&
                Pair(tier.height, tier.width) !in supportedSizes
            ) continue

            for (fps in FPS_TIERS) {
                if (fps !in supportedFps) continue

                val configKey = "${tier.width}x${tier.height}@$fps"
                if (configKey in blacklistedConfigs) continue

                // Score this combination
                var score = tier.qualityScore  // resolution quality (0-30)

                // FPS match score (0-20)
                score += when (fps) {
                    30 -> 20
                    24 -> 15
                    25 -> 15
                    60 -> 8   // risky
                    15 -> 5
                    else -> 3
                }

                // Encoder reliability (0-25)
                score += if (hasHardwareH264) 25 else 10

                // Hardware level bonus (0-15)
                score += when (hwLevel) {
                    "LEVEL_3" -> 15
                    "FULL" -> 13
                    "LIMITED" -> 8
                    "LEGACY" -> 3
                    "EXTERNAL" -> 2
                    else -> 1
                }

                // Stabilization bonus (0-10)
                if (stab.opticalStabilization) score += 6
                if (stab.videoStabilization) score += 4

                // ── Penalties ──

                // 4K60 on LIMITED hardware — massive crash risk
                if (tier.width >= 3840 && fps >= 60 && hwLevel != "LEVEL_3" && hwLevel != "FULL") {
                    score -= 40
                }

                // 4K on LEGACY — very risky
                if (tier.width >= 3840 && hwLevel == "LEGACY") {
                    score -= 30
                }

                // 60fps on anything below FULL
                if (fps >= 60 && hwLevel != "LEVEL_3" && hwLevel != "FULL") {
                    score -= 15
                }

                val bitrate = calculateBitrate(tier.width, tier.height, fps, h264Encoder)

                presets.add(
                    RecordingPreset(
                        width = tier.width,
                        height = tier.height,
                        fps = fps,
                        bitrate = bitrate,
                        codec = "H264",
                        videoStabilization = stab.videoStabilization,
                        opticalStabilization = stab.opticalStabilization,
                        score = score.coerceAtLeast(1)
                    )
                )
            }
        }

        val ranked = if (requestedWidth > 0 && presets.isNotEmpty()) {
            // Explicit resolution request: honor it first, then the nearest
            // compatible size (below/above), ranking by proximity to the
            // target rather than by raw safety score.
            presets.sortedWith(
                compareBy<RecordingPreset> {
                    when {
                        it.width == requestedWidth -> 0
                        it.width < requestedWidth -> 1
                        else -> 2
                    }
                }.thenBy { kotlin.math.abs(it.width - requestedWidth) }
                    .thenByDescending { it.score }
            )
        } else {
            presets.sortedByDescending { it.score }
        }

        return ranked.also {
            if (it.isEmpty()) {
                Log.w(TAG, "No viable presets found, returning fallback")
            } else {
                Log.i(TAG, "Top preset: ${it.first().width}x${it.first().height}@${it.first().fps} score=${it.first().score}")
            }
        }.ifEmpty { listOf(fallbackPreset()) }
    }

    /**
     * Returns the single best preset. Convenience wrapper.
     */
    fun selectBest(
        profile: DeviceProfile,
        cameraId: String = "0",
        blacklistedConfigs: Set<String> = emptySet(),
        preference: VideoPreference = VideoPreference()
    ): RecordingPreset {
        return selectPresets(profile, cameraId, blacklistedConfigs, preference).first()
    }

    // ── Internal ────────────────────────────────────────────────────────

    /** Target width for a named resolution preference ("1080P" → 1920). */
    private fun preferredWidthFor(resolution: String): Int {
        return when (resolution.uppercase()) {
            "480P" -> 854   // 16:9 SD widescreen
            "720P" -> 1280
            "1440P" -> 2560
            "4K" -> 3840
            "1080P" -> 1920
            else -> 0 // AUTO
        }
    }

    /** Aspect predicate for the chosen ratio, or null for no filter. */
    private fun ratioPredicateFor(preference: VideoPreference): ((ResolutionTier) -> Boolean)? {
        return when (preference.ratio) {
            "16:9", "4:3", "1:1" -> {
                val target = when (preference.ratio) {
                    "16:9" -> 16f / 9f
                    "4:3" -> 4f / 3f
                    else -> 1f
                }
                { tier: ResolutionTier -> aspectMatches(tier, target) }
            }

            "DISPLAY" -> { tier: ResolutionTier -> aspectMatches(tier, preference.displayAspect) }
            else -> null // AUTO
        }
    }

    private fun aspectMatches(tier: ResolutionTier, target: Float): Boolean {
        val aspect = tier.width.toFloat() / tier.height.toFloat()
        val inverted = tier.height.toFloat() / tier.width.toFloat()
        return kotlin.math.abs(aspect - target) <= ASPECT_TOLERANCE ||
            kotlin.math.abs(inverted - target) <= ASPECT_TOLERANCE
    }

    private fun calculateBitrate(
        width: Int,
        @Suppress("UNUSED_PARAMETER") height: Int,
        fps: Int,
        encoder: VideoEncoderInfo?
    ): Int {
        // Base bitrate per pixel tier
        val baseBitrate = when {
            width >= 3840 -> 20_000_000  // 4K base
            width >= 2560 -> 12_000_000  // 1440p
            width >= 1920 -> 16_000_000  // 1080p — matches stock camera HEVC
            width >= 1280 -> 5_000_000   // 720p
            else -> 3_000_000            // sub-720p
        }

        // Scale for FPS
        val fpsScale = when {
            fps >= 60 -> 1.8
            fps >= 30 -> 1.0
            fps >= 24 -> 0.85
            else -> 0.7
        }

        val computed = (baseBitrate * fpsScale).toInt()

        // Clamp to encoder's supported range if known
        if (encoder != null) {
            val range = encoder.bitrateRange.split("-")
            if (range.size == 2) {
                val min = range[0].toIntOrNull() ?: 0
                val max = range[1].toIntOrNull() ?: Int.MAX_VALUE
                return computed.coerceIn(min, max)
            }
        }

        return computed
    }

    private fun parseSize(sizeStr: String): Pair<Int, Int>? {
        val parts = sizeStr.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return Pair(w, h)
    }

    private fun parseFpsUpper(rangeStr: String): Int? {
        val parts = rangeStr.split("-")
        return parts.lastOrNull()?.toIntOrNull()
    }

    private fun fallbackPreset(): RecordingPreset {
        return RecordingPreset(
            width = 1280,
            height = 720,
            fps = 24,
            bitrate = 5_000_000,
            codec = "H264",
            videoStabilization = false,
            opticalStabilization = false,
            score = 1
        )
    }
}
