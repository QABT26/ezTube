package com.qabt.eztube.movie

import com.qabt.eztube.youtube.MediaSummary

data class MovieCatalogItem(
    val providerId: String,
    val providerName: String,
    val slug: String,
    val title: String,
    val originalTitle: String?,
    val thumbnailUrl: String?,
    val year: Int?,
    val language: String?,
    val durationText: String?,
    val durationSeconds: Long?
) {
    val stableId: String
        get() = "movie-title:$providerId:$slug"

    fun toMediaSummary(): MediaSummary = MediaSummary(
        id = stableId,
        title = title,
        channel = buildList {
            add(providerName)
            language?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · "),
        thumbnailUrl = thumbnailUrl,
        uploadDateText = year?.toString(),
        durationSeconds = durationSeconds ?: -1L
    )
}

data class MovieEpisodeRef(
    val providerId: String,
    val providerName: String,
    val movieSlug: String,
    val movieTitle: String,
    val serverIndex: Int,
    val episodeIndex: Int,
    val serverName: String,
    val episodeSlug: String,
    val episodeName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val language: String?
) {
    val stableId: String
        get() = "movie:$providerId:$movieSlug:${stablePart(serverName)}:${stablePart(episodeSlug)}"

    fun toMediaSummary(): MediaSummary = MediaSummary(
        id = stableId,
        title = buildString {
            append(movieTitle)
            if (episodeName.isNotBlank()) append(" · ").append(episodeName)
        },
        channel = buildList {
            add(providerName)
            if (serverName.isNotBlank()) add(serverName)
            language?.takeIf { it.isNotBlank() && !serverName.contains(it, ignoreCase = true) }?.let(::add)
        }.joinToString(" · "),
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds ?: -1L
    )
}

data class MovieDetail(
    val catalog: MovieCatalogItem,
    val episodes: List<MovieEpisodeRef>
)

data class MoviePlayback(
    val mediaId: String,
    val hlsUrl: String,
    val subtitles: List<MovieSubtitle> = emptyList()
)

interface MovieProvider {
    val id: String
    val displayName: String

    fun search(query: String): List<MovieCatalogItem>
    fun detail(slug: String): MovieDetail
    fun canResolve(mediaId: String): Boolean = mediaId.startsWith("movie:$id:")
    fun resolve(mediaId: String): MoviePlayback
}


private fun stablePart(value: String): String =
    java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
