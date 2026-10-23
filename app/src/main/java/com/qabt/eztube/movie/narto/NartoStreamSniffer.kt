package com.qabt.eztube.movie.narto

import android.net.Uri

/**
 * Collects the Narto media requests observed by WebView.
 *
 * This deliberately does not fetch or probe candidates again. The WebView already made the
 * authenticated request, so its URL and request headers are the strongest signal we have.
 */
data class NartoCapturedRequest(
    val url: String,
    val headers: Map<String, String>
)

class NartoStreamSniffer {
    @Volatile
    private var video: NartoCapturedRequest? = null

    @Volatile
    private var subtitle: NartoCapturedRequest? = null

    fun clear() {
        video = null
        subtitle = null
    }

    fun onRequest(url: String?, headers: Map<String, String> = emptyMap()) {
        val value = url?.takeIf { it.isNotBlank() } ?: return
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        if (!uri.scheme.equals("https", ignoreCase = true)) return

        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val request = NartoCapturedRequest(value, headers)

        when {
            isSubtitle(host, path) -> subtitle = request
            isDirectVideo(host) -> video = request
            isSignedManifest(host, path) && video?.let(::priority).let { it == null || it < 2 } ->
                video = request
            isUpstreamManifest(host, path) && video == null -> video = request
        }
    }

    fun bestVideo(): NartoCapturedRequest? = video

    fun bestSubtitle(): NartoCapturedRequest? = subtitle

    private fun priority(request: NartoCapturedRequest): Int {
        val uri = runCatching { Uri.parse(request.url) }.getOrNull() ?: return 0
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        return when {
            isDirectVideo(host) -> 3
            isSignedManifest(host, path) -> 2
            isUpstreamManifest(host, path) -> 1
            else -> 0
        }
    }

    private fun isSubtitle(host: String, path: String): Boolean =
        (
            host == "edge.narto-drama.com" ||
                host == "narto-drama.com" ||
                host == "stream.narto-drama.com"
        ) && path.startsWith("/e/s/")

    private fun isDirectVideo(host: String): Boolean =
        host == "dramabox-stream.narto-drama.com"

    private fun isSignedManifest(host: String, path: String): Boolean =
        (
            host == "stream.narto-drama.com" ||
                host == "edge.narto-drama.com"
        ) && (
            path.startsWith("/e/m/") ||
                path.endsWith(".m3u8", ignoreCase = true)
        )

    private fun isUpstreamManifest(host: String, path: String): Boolean =
        (
            host == "mydramawave.com" ||
                host.endsWith(".mydramawave.com")
        ) && path.endsWith(".m3u8", ignoreCase = true)
}
