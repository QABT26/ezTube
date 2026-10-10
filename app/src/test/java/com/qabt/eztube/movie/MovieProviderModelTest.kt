package com.qabt.eztube.movie

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieProviderModelTest {
    @Test
    fun stableEpisodeIdDoesNotDependOnProviderIndexes() {
        val first = MovieEpisodeRef(
            providerId = "phimapi",
            providerName = "KKPhim",
            movieSlug = "test-movie",
            movieTitle = "Test Movie",
            serverIndex = 0,
            episodeIndex = 0,
            serverName = "Vietsub",
            episodeSlug = "tap-01",
            episodeName = "Tập 01",
            thumbnailUrl = null,
            durationSeconds = 2700,
            language = "Vietsub"
        )
        val reordered = first.copy(serverIndex = 3, episodeIndex = 9)

        assertTrue(first.stableId == reordered.stableId)
        assertTrue(first.stableId.startsWith("movie:phimapi:test-movie:"))
        assertFalse(first.stableId.contains(":0:0"))
    }

    @Test
    fun titleSummaryIsNotAPlayableEpisodeId() {
        val title = MovieCatalogItem(
            providerId = "phimapi",
            providerName = "KKPhim",
            slug = "test-movie",
            title = "Test Movie",
            originalTitle = null,
            thumbnailUrl = "https://example.com/poster.jpg",
            year = 2026,
            language = "Vietsub",
            durationText = "45 phút",
            durationSeconds = 2700
        )

        assertTrue(title.stableId == "movie-title:phimapi:test-movie")
        assertFalse(title.stableId.startsWith("movie:"))
    }
}
