package com.qabt.eztube.playback

enum class VideoQuality(val targetHeight: Int?) {
    AUTO(null),
    P360(360),
    P480(480),
    P720(720),
    P1080(1080)
}

object VideoStreamSelector {
    fun selectFastStart(streams: List<AudioStream>, quality: VideoQuality): AudioStream? {
        val target = quality.targetHeight ?: 480
        val cap = minOf(target, 480)
        val muxed = streams.filter {
            (it.videoHeight ?: 0) > 0 &&
                (it.videoHeight ?: 0) <= cap &&
                it.companionAudioUrl == null
        }
        return muxed.maxWithOrNull(
            compareBy<AudioStream> { it.videoHeight ?: 0 }
                .thenBy { it.bitrateKbps ?: 0 }
        ) ?: select(streams, quality)
    }

    fun select(streams: List<AudioStream>, quality: VideoQuality): AudioStream? {
        if (streams.isEmpty()) return null
        val videos = streams.filter { (it.videoHeight ?: 0) > 0 }
        if (videos.isEmpty()) return streams.firstOrNull()

        if (quality == VideoQuality.AUTO) {
            return videos.maxWithOrNull(
                compareBy<AudioStream> { it.videoHeight ?: 0 }
                    .thenBy { it.bitrateKbps ?: 0 }
            )
        }

        val target = requireNotNull(quality.targetHeight)
        return videos
            .filter { (it.videoHeight ?: 0) <= target }
            .maxWithOrNull(
                compareBy<AudioStream> { it.videoHeight ?: 0 }
                    .thenBy { it.bitrateKbps ?: 0 }
            )
            ?: videos.minWithOrNull(
                compareBy<AudioStream> { kotlin.math.abs((it.videoHeight ?: target) - target) }
                    .thenByDescending { it.videoHeight ?: 0 }
            )
    }
}
