package com.qabt.eztube.playback

sealed interface PlaybackSource {
    val mediaId: String

    data class YouTube(
        override val mediaId: String
    ) : PlaybackSource

    data class Hls(
        override val mediaId: String,
        val manifestUrl: String
    ) : PlaybackSource

    data class Dash(
        override val mediaId: String,
        val manifestUrl: String
    ) : PlaybackSource

    data class Progressive(
        override val mediaId: String,
        val url: String,
        val mimeType: String? = null
    ) : PlaybackSource
}

object PlaybackSourceResolver {
    fun resolve(mediaId: String): PlaybackSource {
        val normalized = mediaId.trim()
        val lower = normalized.lowercase()

        if (
            lower.contains("youtube.com/") ||
            lower.contains("youtu.be/") ||
            lower.contains("music.youtube.com/")
        ) {
            return PlaybackSource.YouTube(normalized)
        }

        return when {
            lower.substringBefore('?').endsWith(".m3u8") ->
                PlaybackSource.Hls(normalized, normalized)
            lower.substringBefore('?').endsWith(".mpd") ->
                PlaybackSource.Dash(normalized, normalized)
            else ->
                PlaybackSource.Progressive(normalized, normalized)
        }
    }
}
