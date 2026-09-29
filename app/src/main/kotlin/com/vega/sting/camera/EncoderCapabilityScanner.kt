package com.vega.sting.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * Scans MediaCodecList for detailed encoder capabilities.
 * Replaces the simple name-list approach in CodecHelper with
 * profile/level, bitrate range, max dimensions, and color format data.
 */
object EncoderCapabilityScanner {

    private const val TAG = "EncoderCapScanner"

    private val VIDEO_MIME_TYPES = listOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,    // H.264
        MediaFormat.MIMETYPE_VIDEO_HEVC,   // H.265
        MediaFormat.MIMETYPE_VIDEO_VP8,
        MediaFormat.MIMETYPE_VIDEO_VP9
    )

    private val AUDIO_MIME_TYPES = listOf(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_OPUS,
        MediaFormat.MIMETYPE_AUDIO_AMR_NB,
        MediaFormat.MIMETYPE_AUDIO_AMR_WB,
        MediaFormat.MIMETYPE_AUDIO_FLAC
    )

    fun scan(): EncoderCapabilities {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val videoEncoders = mutableListOf<VideoEncoderInfo>()
        val audioEncoders = mutableListOf<AudioEncoderInfo>()

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue

            for (mimeType in codecInfo.supportedTypes) {
                try {
                    when {
                        mimeType in VIDEO_MIME_TYPES -> {
                            videoEncoders.add(extractVideoEncoder(codecInfo, mimeType))
                        }
                        mimeType in AUDIO_MIME_TYPES -> {
                            audioEncoders.add(extractAudioEncoder(codecInfo, mimeType))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to scan encoder ${codecInfo.name} for $mimeType", e)
                }
            }
        }

        return EncoderCapabilities(
            videoEncoders = videoEncoders,
            audioEncoders = audioEncoders
        )
    }

    // ── Video encoder extraction ────────────────────────────────────────

    private fun extractVideoEncoder(
        codecInfo: MediaCodecInfo,
        mimeType: String
    ): VideoEncoderInfo {
        val caps = codecInfo.getCapabilitiesForType(mimeType)
        val videoCaps = caps.videoCapabilities

        val profiles = extractVideoProfiles(caps, mimeType)

        val bitrateRange = videoCaps.bitrateRange
        val frameRateRange = videoCaps.supportedFrameRates

        val maxWidth = videoCaps.supportedWidths.upper
        val maxHeight = videoCaps.getSupportedHeightsFor(maxWidth).upper

        val isHardware = if (Build.VERSION.SDK_INT >= 29) {
            codecInfo.isHardwareAccelerated
        } else {
            // Heuristic: vendor codecs are usually hardware
            !codecInfo.name.startsWith("OMX.google.")
        }

        return VideoEncoderInfo(
            name = codecInfo.name,
            mimeType = mimeType,
            profiles = profiles,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            bitrateRange = "${bitrateRange.lower}-${bitrateRange.upper}",
            frameRateRange = "${frameRateRange.lower}-${frameRateRange.upper}",
            isHardwareAccelerated = isHardware,
            colorFormats = caps.colorFormats.toList()
        )
    }

    private fun extractVideoProfiles(
        caps: MediaCodecInfo.CodecCapabilities,
        mimeType: String
    ): List<String> {
        val profileLevels = caps.profileLevels ?: return emptyList()
        return profileLevels.map { pl ->
            mapVideoProfile(mimeType, pl.profile)
        }.distinct()
    }

    private fun mapVideoProfile(mimeType: String, profile: Int): String {
        return when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> mapAvcProfile(profile)
            MediaFormat.MIMETYPE_VIDEO_HEVC -> mapHevcProfile(profile)
            else -> "PROFILE_$profile"
        }
    }

    private fun mapAvcProfile(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High422"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High444"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "ConstrainedBaseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "ConstrainedHigh"
        else -> "AVC_PROFILE_$profile"
    }

    private fun mapHevcProfile(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "MainStill"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10HDR10Plus"
        else -> "HEVC_PROFILE_$profile"
    }

    // ── Audio encoder extraction ────────────────────────────────────────

    private fun extractAudioEncoder(
        codecInfo: MediaCodecInfo,
        mimeType: String
    ): AudioEncoderInfo {
        val caps = codecInfo.getCapabilitiesForType(mimeType)
        val audioCaps = caps.audioCapabilities

        val bitrateRange = audioCaps.bitrateRange
        val maxChannels = audioCaps.maxInputChannelCount

        // Sample rates — supportedSampleRateRanges gives ranges
        val sampleRateRanges = audioCaps.supportedSampleRateRanges
            ?.map { "${it.lower}-${it.upper}" }
            .orEmpty()

        return AudioEncoderInfo(
            name = codecInfo.name,
            mimeType = mimeType,
            maxChannels = maxChannels,
            bitrateRange = "${bitrateRange.lower}-${bitrateRange.upper}",
            sampleRateRanges = sampleRateRanges
        )
    }
}
