package com.vega.sting.recording

import android.media.MediaCodecList

object CodecHelper {
    fun getSupportedVideoCodecs(): List<String> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
            .map { it.name }
    }

    fun getSupportedAudioCodecs(): List<String> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.startsWith("audio/") } }
            .map { it.name }
    }
}
