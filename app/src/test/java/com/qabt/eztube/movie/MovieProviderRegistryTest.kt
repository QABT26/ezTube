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
        private val year: Int = 2026,
        private val detailFails: Boolean = false,
        private val resolveFails: Boolean = false,
        private val episodes: List<Pair<String, String>> = listOf(
            "tap-1" to "Tập 1",
            "tap-2" to "Tập 2"
        )
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
                    year = year,
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
                episodes = episodes.mapIndexed { index, (episodeSlug, episodeName) ->
                    MovieEpisodeRef(
                        providerId = id,
                        providerName = displayName,
                        movieSlug = slug,
                        movieTitle = title,
                        serverIndex = 0,
                        episodeIndex = index,
                        serverName = "Vietsub",
                        episodeSlug = episodeSlug,
                        episodeName = episodeName,
                        thumbnailUrl = catalog.thumbnailUrl,
                        durationSeconds = catalog.durationSeconds,
                        language = catalog.language
                    )
                }
            )
        }

        override fun resolve(mediaId: String): MoviePlayback {
            if (resolveFails) throw IOException(displayName + " playback unavailable")
            return MoviePlayback(
                mediaId = mediaId,
                hlsUrl = "https://example.com/" + id + "/master.m3u8"
            )
        }
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
    fun playbackFallsBackToMatchingEpisodeOnMirrorProvider() {
        val primary = FakeProvider(
            id = "phimapi",
            displayName = "KKPhim",
            resolveFails = true
        )
        val mirror = FakeProvider(
            id = "ophim",
            displayName = "OPhim"
        )
        val registry = MovieProviderRegistry(
            providers = listOf(primary, mirror),
            nowMs = { 10_000L }
        )

        registry.search("Test Movie")
        val primaryDetail = registry.detail(
            mediaId = "movie-title:phimapi:test-movie",
            mediaTitle = "Test Movie"
        )
        val episodeTwo = primaryDetail.episodes.first {
            it.episodeSlug == "tap-2"
        }

        val playback = registry.resolve(
            mediaId = episodeTwo.stableId,
            mediaTitle = episodeTwo.toMediaSummary().title
        )

        assertTrue(playback.mediaId.startsWith("movie:ophim:"))
        assertTrue(playback.hlsUrl.orEmpty().contains("/ophim/"))
    }

    @Test
    fun searchKeepsSameTitleDifferentYearsSeparate() {
        val oldMovie = FakeProvider(
            id = "phimapi",
            displayName = "KKPhim",
            title = "Same Movie",
            slug = "same-movie-2020",
            year = 2020
        )
        val remake = object : MovieProvider {
            override val id = "ophim"
            override val displayName = "OPhim"

            override fun search(query: String): List<MovieCatalogItem> =
                listOf(
                    MovieCatalogItem(
                        providerId = id,
                        providerName = displayName,
                        slug = "same-movie-2026",
                        title = "Same Movie",
                        originalTitle = "Same Movie",
                        thumbnailUrl = null,
                        year = 2026,
                        language = "Vietsub",
                        durationText = null,
                        durationSeconds = null,
                        episodeBadge = null,
                        isSeries = false
                    )
                )

            override fun detail(slug: String): MovieDetail =
                throw UnsupportedOperationException()

            override fun resolve(mediaId: String): MoviePlayback =
                throw UnsupportedOperationException()
        }

        val registry = MovieProviderRegistry(
            providers = listOf(oldMovie, remake),
            nowMs = { 20_000L }
        )

        val results = registry.search("Same Movie")

        assertEquals(2, results.size)
    }

    @Test
    fun playbackDoesNotUseAmbiguousWrongEpisodeMirror() {
        val primary = FakeProvider(
            id = "phimapi",
            displayName = "KKPhim",
            resolveFails = true,
            episodes = listOf(
                "tap-9" to "Tập 9"
            )
        )
        val mirror = FakeProvider(
            id = "ophim",
            displayName = "OPhim",
            episodes = listOf(
                "tap-1" to "Tập 1",
                "tap-2" to "Tập 2"
            )
        )
        val registry = MovieProviderRegistry(
            providers = listOf(primary, mirror),
            nowMs = { 30_000L }
        )

        registry.search("Test Movie")
        val episode = primary.detail("test-movie").episodes.first()

        val result = runCatching {
            registry.resolve(
                mediaId = episode.stableId,
                mediaTitle = episode.toMediaSummary().title
            )
        }

        assertTrue(result.isFailure)
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
