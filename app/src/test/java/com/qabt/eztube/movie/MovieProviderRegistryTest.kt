package com.qabt.eztube.movie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MovieProviderRegistryTest {
    private class FakeProvider(
        override val id: String,
        override val displayName: String,
        private val title: String = "Test Movie",
        private val slug: String = "test-movie",
        private val detailFails: Boolean = false
    ) : MovieProvider {
        override fun search(query: String): List<MovieCatalogItem> =
            listOf(
                MovieCatalogItem(
                    providerId = id,
                    providerName = displayName,
                    slug = slug,
                    title = title,
                    originalTitle = "Test Movie",
                    thumbnailUrl = "https://example.com/" + id + ".jpg",
                    year = 2026,
                    language = "Vietsub",
                    durationText = "45 phút",
                    durationSeconds = 2700,
                    episodeBadge = "Full",
                    isSeries = false
                )
            )

        override fun detail(slug: String): MovieDetail {
            if (detailFails) throw IOException(displayName + " detail unavailable")
            val catalog = search(title).first()
            return MovieDetail(
                catalog = catalog,
                episodes = listOf(
                    MovieEpisodeRef(
                        providerId = id,
                        providerName = displayName,
                        movieSlug = slug,
                        movieTitle = title,
                        serverIndex = 0,
                        episodeIndex = 0,
                        serverName = "Vietsub",
                        episodeSlug = "full",
                        episodeName = "Video",
                        thumbnailUrl = catalog.thumbnailUrl,
                        durationSeconds = catalog.durationSeconds,
                        language = catalog.language
                    )
                )
            )
        }

        override fun resolve(mediaId: String): MoviePlayback =
            MoviePlayback(
                mediaId = mediaId,
                hlsUrl = "https://example.com/" + id + "/master.m3u8"
            )
    }

    @Test
    fun detailFallsBackToMirrorWhenPrimaryFails() {
        val primary = FakeProvider(
            id = "phimapi",
            displayName = "KKPhim",
            detailFails = true
        )
        val mirror = FakeProvider(
            id = "ophim",
            displayName = "OPhim"
        )
        val registry = MovieProviderRegistry(
            providers = listOf(primary, mirror),
            nowMs = { 1_000L }
        )

        val search = registry.search("Test Movie")
        assertEquals(1, search.size)
        assertTrue(search.first().id.startsWith("movie-title:phimapi:"))

        val detail = registry.detail(
            mediaId = search.first().id,
            mediaTitle = search.first().title
        )

        assertEquals("ophim", detail.catalog.providerId)
        assertEquals("OPhim", detail.catalog.providerName)
        assertTrue(detail.episodes.first().stableId.startsWith("movie:ophim:"))
    }

    @Test
    fun repeatedPlaybackFailuresPutProviderIntoCooldown() {
        var now = 5_000L
        val primary = FakeProvider(
            id = "phimapi",
            displayName = "KKPhim"
        )
        val mirror = FakeProvider(
            id = "ophim",
            displayName = "OPhim"
        )
        val registry = MovieProviderRegistry(
            providers = listOf(primary, mirror),
            nowMs = { now }
        )

        val mediaId = "movie:phimapi:test-movie:server:episode"
        registry.reportPlaybackFailure(mediaId)
        registry.reportPlaybackFailure(mediaId)

        val health = registry.providerHealth()
            .first { it.providerId == "phimapi" }

        assertEquals(2, health.consecutiveFailures)
        assertTrue(health.cooldownRemainingMs > 0L)
        assertTrue(!health.available)

        now += 20_000L
        val recoveredWindow = registry.providerHealth()
            .first { it.providerId == "phimapi" }

        assertTrue(recoveredWindow.available)
    }

    @Test
    fun defaultRegistryIncludesOPhimAsFirstClassProvider() {
        val ids = MovieProviderRegistry.default()
            .providerHealth()
            .map { it.providerId }

        assertTrue("ophim" in ids)
        assertTrue("phimapi" in ids)
        assertTrue("nguonc" in ids)
        assertTrue("phim4k" in ids)
        assertTrue("tophim" in ids)
        assertTrue("narto" in ids)
        assertTrue("biliintl" in ids)
    }
}
