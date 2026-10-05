package com.qabt.eztube.playback

enum class AudioQuality(val maxBitrateKbps: Int?) {
    DATA_SAVER(64),
    STANDARD(128),
    HIGH(null)
}
