package com.qabt.eztube.playback

object AudioStreamSelector {
    fun select(streams: List<AudioStream>, quality: AudioQuality): AudioStream? {
        if (streams.isEmpty()) return null
        val sorted = streams.sortedBy { it.bitrateKbps ?: Int.MAX_VALUE }
        val ceiling = quality.maxBitrateKbps ?: return sorted.last()

        return sorted
            .filter { (it.bitrateKbps ?: Int.MAX_VALUE) <= ceiling }
            .maxByOrNull { it.bitrateKbps ?: 0 }
            ?: sorted.first()
    }
}
