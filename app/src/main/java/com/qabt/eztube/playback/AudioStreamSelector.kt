package com.qabt.eztube.playback

object AudioStreamSelector {
    fun select(streams: List<AudioStream>, quality: AudioQuality): AudioStream? {
        if (streams.isEmpty()) return null

        val known = streams.filter { it.bitrateKbps != null }
        if (known.isEmpty()) return streams.firstOrNull()

        fun codecRank(stream: AudioStream): Int {
            val value = "${stream.codec.orEmpty()} ${stream.mimeType.orEmpty()}".lowercase()
            return when {
                "opus" in value || "webm" in value -> 2
                "aac" in value || "m4a" in value || "mp4" in value -> 1
                else -> 0
            }
        }

        val ceiling = quality.maxBitrateKbps
        val candidates = if (ceiling == null) known
        else known.filter { requireNotNull(it.bitrateKbps) <= ceiling }.ifEmpty {
            listOf(known.minBy { requireNotNull(it.bitrateKbps) })
        }

        return candidates.maxWithOrNull(
            compareBy<AudioStream> { requireNotNull(it.bitrateKbps) }
                .thenBy { codecRank(it) }
        )
    }
}
