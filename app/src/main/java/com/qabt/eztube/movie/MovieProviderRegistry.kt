package com.qabt.eztube.movie

import com.qabt.eztube.youtube.MediaSummary
import java.io.IOException

class MovieProviderRegistry(
    private val providers: List<MovieProvider>
) {
    init {
        require(providers.isNotEmpty()) { "MovieProviderRegistry requires at least one provider" }
    }

    fun search(query: String): List<MediaSummary> =
        providers.flatMap { provider ->
            runCatching { provider.search(query) }.getOrDefault(emptyList())
        }
            .distinctBy { it.providerId + ":" + it.slug }
            .map { it.toMediaSummary() }

    fun detail(mediaId: String): MovieDetail {
        val parsed = parseTitleId(mediaId)
            ?: throw IOException("Invalid movie title id")
        val provider = providers.firstOrNull { it.id == parsed.first }
            ?: throw IOException("Movie provider not installed: ${parsed.first}")
        return provider.detail(parsed.second)
    }

    fun canResolve(mediaId: String): Boolean =
        mediaId.startsWith("movie:") &&
            providers.any { it.canResolve(mediaId) }

    fun resolve(mediaId: String): MoviePlayback {
        val provider = providers.firstOrNull { it.canResolve(mediaId) }
            ?: throw IOException("No movie provider can resolve this item")
        return provider.resolve(mediaId)
    }

    fun isTitle(mediaId: String): Boolean = mediaId.startsWith("movie-title:")

    private fun parseTitleId(mediaId: String): Pair<String, String>? {
        val parts = mediaId.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "movie-title") return null
        if (parts[1].isBlank() || parts[2].isBlank()) return null
        return parts[1] to parts[2]
    }

    companion object {
        fun default(): MovieProviderRegistry =
            MovieProviderRegistry(
                providers = listOf(
                    PhimApiMovieProvider()
                )
            )
    }
}
