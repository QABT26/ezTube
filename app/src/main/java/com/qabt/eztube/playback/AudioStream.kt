package com.qabt.eztube.playback

data class AudioStream(
    val url: String,
    val bitrateKbps: Int?,
    val codec: String?,
    val mimeType: String?
)
