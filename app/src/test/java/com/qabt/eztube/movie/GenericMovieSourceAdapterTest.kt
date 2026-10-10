package com.qabt.eztube.movie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericMovieSourceAdapterTest {
    private val adapter = GenericMovieSourceAdapter(
        MovieProviderConfig(
            id = "test",
            displayName = "Test",
            baseUrls = listOf("https://example.com")
        )
    )

    @Test
    fun parsesHlsEpisodesAndSubtitles() {
        val json = """
            {
              "status":"success",
              "movie_id":84392,
              "servers":[
                {
                  "name":"K - Vietsub",
                  "items":[
                    {
                      "id":"1",
                      "movie_id":"84392",
                      "server":"K - Vietsub",
                      "name":"Tập 01",
                      "slug":"k-tap-01",
                      "type":"m3u8",
                      "link":"https://cdn.example.com/a/index.m3u8",
                      "subtitles":null
                    },
                    {
                      "id":"2",
                      "movie_id":"84392",
                      "server":"K - Vietsub",
                      "name":"Tập 01",
                      "slug":"k-tap-01",
                      "type":"embed",
                      "link":"https://player.example.com/?url=x",
                      "subtitles":null
                    }
                  ]
                },
                {
                  "name":"V - Vietsub",
                  "items":[
                    {
                      "id":"3",
                      "movie_id":"84392",
                      "server":"V - Vietsub",
                      "name":"1",
                      "slug":"v-1",
                      "type":"m3u8",
                      "link":"https://cdn.example.com/b/master.m3u8",
                      "subtitles":"[{\"lang\":\"vie\",\"url\":\"https://cdn.example.com/sub.vtt\",\"label\":\"Tiếng Việt\"}]"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val catalog = adapter.parseEpisodeCatalog(json)

        assertEquals("84392", catalog.movieId)
        assertEquals(3, catalog.episodes.size)
        assertEquals(2, catalog.preferredHlsEpisodes().size)
        assertEquals("Tiếng Việt", catalog.servers["V - Vietsub"]!![0].subtitles[0].label)
    }

    @Test
    fun extractsMovieIdFromProviderPage() {
        val html = """<script>fetch("/api/episodes?movie_id=84392")</script>"""
        assertEquals("84392", adapter.extractMovieId(html))
    }

    @Test
    fun recognizesProviderUrlAndExplicitMovieIdOnly() {
        assertTrue(adapter.canHandle("https://example.com/phim/test"))
        assertTrue(adapter.canHandle("movie:84392"))
        assertFalse(adapter.canHandle("84392"))
        assertFalse(adapter.canHandle("https://youtube.com/watch?v=x"))
    }

    @Test
    fun extractsStableSlugFromRotatingProviderUrl() {
        assertEquals(
            "trung-so-doc-dac-van-phai-di-lam",
            adapter.extractStableMovieSlug(
                "https://example.com/phim/trung-so-doc-dac-van-phai-di-lam-1789048828"
            )
        )
    }

}
