package com.qabt.eztube.movie

import com.qabt.eztube.youtube.MediaSummary

data class MovieProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrls: List<String>,
    val episodesPath: String = "/api/episodes"
) {
    init {
        require(id.isNotBlank()) { "provider id must not be blank" }
        require(baseUrls.isNotEmpty()) { "provider must have at least one base URL" }
    }

    fun episodesUrl(baseUrl: String, movieId: String): String =
        baseUrl.trimEnd('/') + episodesPath + "?movie_id=" +
            java.net.URLEncoder.encode(movieId, "UTF-8")
}

data class MovieSubtitle(
    val language: String?,
    val label: String?,
    val url: String
)

data class MovieEpisode(
    val id: String,
    val movieId: String,
    val server: String,
    val name: String,
    val slug: String,
    val type: String,
    val link: String,
    val subtitles: List<MovieSubtitle>
) {
    val isHls: Boolean
        get() = type.equals("m3u8", ignoreCase = true) ||
            link.substringBefore('?').lowercase().endsWith(".m3u8")

    fun toMediaSummary(
        movieTitle: String,
        providerName: String,
        thumbnailUrl: String? = null
    ): MediaSummary = MediaSummary(
        id = link,
        title = buildString {
            append(movieTitle)
            if (name.isNotBlank()) append(" · ").append(name)
        },
        channel = "$providerName · $server",
        thumbnailUrl = thumbnailUrl
    )
}

data class MovieEpisodeCatalog(
    val providerId: String,
    val movieId: String,
    val servers: Map<String, List<MovieEpisode>>
) {
    val episodes: List<MovieEpisode>
        get() = servers.values.flatten()

    fun preferredHlsEpisodes(): List<MovieEpisode> =
        servers.values.flatten().filter { it.isHls }
}
