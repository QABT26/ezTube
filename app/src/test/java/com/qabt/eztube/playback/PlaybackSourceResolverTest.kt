package com.qabt.eztube.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceResolverTest {
    @Test
    fun youtubeUrlsUseYoutubeAdapter() {
        assertTrue(
            PlaybackSourceResolver.resolve("https://www.youtube.com/watch?v=abc") is
                PlaybackSource.YouTube
        )
        assertTrue(
            PlaybackSourceResolver.resolve("https://youtu.be/abc") is
                PlaybackSource.YouTube
        )
    }

    @Test
    fun hlsManifestUsesHlsEngine() {
        assertTrue(
            PlaybackSourceResolver.resolve(
                "https://cdn.example.com/master.m3u8?token=abc"
            ) is PlaybackSource.Hls
        )
    }

    @Test
    fun dashManifestUsesDashEngine() {
        assertTrue(
            PlaybackSourceResolver.resolve(
                "https://cdn.example.com/manifest.mpd?token=abc"
            ) is PlaybackSource.Dash
        )
    }

    @Test
    fun ordinaryMediaUrlUsesProgressiveEngine() {
        assertTrue(
            PlaybackSourceResolver.resolve(
                "https://cdn.example.com/movie.mp4"
            ) is PlaybackSource.Progressive
        )
    }
}
