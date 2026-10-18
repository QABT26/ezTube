package com.qabt.eztube.movie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NartoDramaMovieProviderTest {
    @Test
    fun detectsCloudflareChallengeResponses() {
        assertTrue(
            NartoDramaMovieProvider.looksLikeChallenge(
                "<html><script src=\"/cdn-cgi/challenge-platform/x\"></script></html>",
                "https://narto-drama.com/",
                200
            )
        )
        assertTrue(
            NartoDramaMovieProvider.looksLikeChallenge(
                "ok",
                "https://challenges.cloudflare.com/turnstile",
                200
            )
        )
        assertFalse(
            NartoDramaMovieProvider.looksLikeChallenge(
                "<html><body>movie catalog</body></html>",
                "https://edge.narto-drama.com/",
                200
            )
        )
    }

    @Test
    fun normalizesRelativeAndProtocolRelativeArtworkUrls() {
        assertEquals(
            "https://edge.narto-drama.com/images/poster.webp",
            NartoDramaMovieProvider.normalizeImageUrl("/images/poster.webp")
        )
        assertEquals(
            "https://cdn.example/poster.webp",
            NartoDramaMovieProvider.normalizeImageUrl("//cdn.example/poster.webp")
        )
    }

    @Test
    fun doesNotTreatLargeCatalogWithTurnstileScriptAsChallenge() {
        val body = buildString {
            append("<html><script>turnstile challenge-platform</script>")
            append("<h3>Tôi Có Ba Bạn Gái Nhờ Hệ Thống</h3>")
            append("<a href=\"/detail/watch/toi-co-ba-ban-gai-nho-he-thong/1\">Xem</a>")
            append("x".repeat(25_000))
            append("</html>")
        }

        assertFalse(
            NartoDramaMovieProvider.looksLikeChallenge(
                body,
                "https://edge.narto-drama.com/",
                200
            )
        )
    }

    @Test
    fun parsesNartoCatalogCardsForPartialTitleSearch() {
        val html = """
            <a href="/detail/watch/toi-co-ba-ban-gai-nho-he-thong/1?lang=vi-VN">
                <img src="https://cdn.example/poster.jpg">
                <h3>Tôi Có Ba Bạn Gái Nhờ Hệ Thống</h3>
                <span>Ep: 30</span>
            </a>
        """.trimIndent()

        val items = NartoDramaMovieProvider.extractCatalogItems(html)

        assertEquals(1, items.size)
        assertEquals("toi-co-ba-ban-gai-nho-he-thong", items.single().slug)
        assertEquals("Tôi Có Ba Bạn Gái Nhờ Hệ Thống", items.single().title)
        assertEquals("30 tập", items.single().episodeBadge)
    }

    @Test
    fun parsesCatalogWhenTitleIsSiblingOfWatchLink() {
        val html = """
            <article>
                <a href="/detail/watch/toi-co-ba-ban-gai-nho-he-thong/1?lang=vi-VN">
                    <img src="https://cdn.example/poster.jpg">
                </a>
                <div class="meta">
                    <h3>Tôi Có Ba Bạn Gái Nhờ Hệ Thống</h3>
                    <span>Ep: 30</span>
                </div>
            </article>
        """.trimIndent()

        val item = NartoDramaMovieProvider.extractCatalogItems(html).single()

        assertEquals("toi-co-ba-ban-gai-nho-he-thong", item.slug)
        assertEquals("Tôi Có Ba Bạn Gái Nhờ Hệ Thống", item.title)
        assertEquals("30 tập", item.episodeBadge)
    }

    @Test
    fun slugifiesVietnameseTitleForExactTitleLookup() {
        assertEquals(
            "toi-co-ba-ban-gai-nho-he-thong",
            NartoDramaMovieProvider.slugify("Tôi Có Ba Bạn Gái Nhờ Hệ Thống")
        )
    }

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
