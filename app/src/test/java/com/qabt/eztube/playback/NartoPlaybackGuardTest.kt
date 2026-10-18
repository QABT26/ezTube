package com.qabt.eztube.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NartoPlaybackGuardTest {
    @Test
    fun acceptsOnlyKnownNartoPlaybackManifests() {
        assertTrue(
            NartoPlaybackGuard.isAllowedManifest(
                "https://stream.narto-drama.com/e/m/signed-token"
            )
        )
        assertTrue(
            NartoPlaybackGuard.isAllowedManifest(
                "https://cdn.mydramawave.com/vt/episode-12.m3u8"
            )
        )

        assertFalse(
            NartoPlaybackGuard.isAllowedManifest(
                "https://ads.example.com/pre-roll/ad.m3u8"
            )
        )
        assertFalse(
            NartoPlaybackGuard.isAllowedManifest(
                "http://stream.narto-drama.com/e/m/signed-token"
            )
        )
        assertFalse(
            NartoPlaybackGuard.isAllowedManifest(
                "https://evil.example/e/m/signed-token"
            )
        )
    }

    @Test
    fun keepsOnlyNativeNartoVietsubEndpoints() {
        val subtitle = "https://edge.narto-drama.com/e/s/vi-native-token"
        assertEquals(subtitle, NartoPlaybackGuard.allowedSubtitleUrl(subtitle))
        assertEquals(
            "https://stream.narto-drama.com/e/s/vi-token",
            NartoPlaybackGuard.allowedSubtitleUrl(
                "https://stream.narto-drama.com/e/s/vi-token"
            )
        )

        assertNull(
            NartoPlaybackGuard.allowedSubtitleUrl(
                "https://ads.example.com/subtitles/ad.vtt"
            )
        )
        assertNull(
            NartoPlaybackGuard.allowedSubtitleUrl(
                "https://edge.narto-drama.com/not-subtitle/file.vtt"
            )
        )
    }

    @Test
    fun episodeSwitchRecognizesOnlyNartoLogicalItems() {
        assertTrue(
            NartoPlaybackGuard.isNartoMediaId(
                "movie:narto:demo:server:episode-12"
            )
        )
        assertFalse(
            NartoPlaybackGuard.isNartoMediaId(
                "movie:kkphim:demo:server:episode-12"
            )
        )
        assertFalse(NartoPlaybackGuard.isNartoMediaId("youtube:abc"))
    }

    @Test
    fun seekResumePositionIsNeverNegative() {
        assertEquals(0L, NartoPlaybackGuard.safePositionMs(-5_000L))
        assertEquals(0L, NartoPlaybackGuard.safePositionMs(0L))
        assertEquals(83_250L, NartoPlaybackGuard.safePositionMs(83_250L))
    }
}
