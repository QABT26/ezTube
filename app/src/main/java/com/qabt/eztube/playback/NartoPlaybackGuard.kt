package com.qabt.eztube.playback

import java.net.URI

/**
 * Pure allow-list policy for the interactive Narto unlock handoff.
 *
 * Keep this free of Android dependencies so the security/regression behavior can be
 * locked with local unit tests. The WebView may observe many third-party requests;
 * only the provider playback endpoints below are allowed to reach Media3.
 */
internal object NartoPlaybackGuard {
    fun isNartoMediaId(mediaId: String): Boolean =
        mediaId.startsWith("movie:narto:")

    fun isAllowedManifest(url: String): Boolean {
        val uri = parseHttps(url) ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()

        val signedNarto =
            host == "stream.narto-drama.com" &&
                path.startsWith("/e/m/")

        val unlockedDramaWave =
            (host == "mydramawave.com" || host.endsWith(".mydramawave.com")) &&
                path.endsWith(".m3u8", ignoreCase = true)

        return signedNarto || unlockedDramaWave
    }

    fun allowedSubtitleUrl(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val uri = parseHttps(value) ?: return null
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()

        return value.takeIf {
            host in setOf(
                "edge.narto-drama.com",
                "narto-drama.com",
                "stream.narto-drama.com"
            ) &&
                path.startsWith("/e/s/")
        }
    }

    fun safePositionMs(positionMs: Long): Long = positionMs.coerceAtLeast(0L)

    private fun parseHttps(value: String): URI? =
        runCatching { URI(value) }
            .getOrNull()
            ?.takeIf { it.scheme.equals("https", ignoreCase = true) }
}
