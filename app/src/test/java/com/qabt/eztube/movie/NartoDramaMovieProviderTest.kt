package com.qabt.eztube.movie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NartoDramaMovieProviderTest {
    @Test
    fun parsesDirectWatchUrlWithoutPersistingSignedPlayback() {
        val parsed = NartoDramaMovieProvider.parseWatchUrl(
            "https://narto-drama.com/detail/watch/toi-co-ba-ban-gai-nho-he-thong/12?lang=vi-VN"
        )

        assertEquals("toi-co-ba-ban-gai-nho-he-thong", parsed?.first)
        assertEquals(12, parsed?.second)
    }

    @Test
    fun extractsEpisodeCountFromLinksAndVisibleEpisodeText() {
        val html = """
            <a href="/detail/watch/demo-title/1">EP 1</a>
            <a href="/detail/watch/demo-title/30">EP 30</a>
            <div>Episode 12 dari 30</div>
        """.trimIndent()

        assertEquals(
            30,
            NartoDramaMovieProvider.extractEpisodeCount(html, "demo-title")
        )
    }

    @Test
    fun extractsSignedHlsFromAbsoluteOrEscapedRelativePageData() {
        val token = "eyJ2IjoxLCJ0IjoicHJvdiJ9.signature"
        val absolute =
            "{\\\"stream\\\":\\\"https://stream.narto-drama.com/e/m/" + token + "\\\"}"
        val relative =
            "{\\\"stream\\\":\\\"\\\\/e\\\\/m\\\\/" + token + "\\\"}"

        assertEquals(
            "https://stream.narto-drama.com/e/m/" + token,
            NartoDramaMovieProvider.extractSignedHlsUrl(absolute)
        )
        assertEquals(
            "https://stream.narto-drama.com/e/m/" + token,
            NartoDramaMovieProvider.extractSignedHlsUrl(relative)
        )
    }

    @Test
    fun doesNotInventPlaybackUrlWhenPageDoesNotExposeOne() {
        assertNull(
            NartoDramaMovieProvider.extractSignedHlsUrl(
                "<html><body>unlock stream on the website</body></html>"
            )
        )
    }

    @Test
    fun extractsPublicNartoSubtitleUrls() {
        val first = "https://narto-drama.com/e/s/abc.def"
        val second = "https://narto-drama.com/e/s/ghi.jkl"
        val html = first + "\n" + second + "\n" + first

        val subtitles = NartoDramaMovieProvider.extractSubtitleUrls(html)

        assertEquals(listOf(first, second), subtitles)
        assertTrue(subtitles.distinct().size == subtitles.size)
    }
}
