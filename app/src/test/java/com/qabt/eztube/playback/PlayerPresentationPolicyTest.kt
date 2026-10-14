package com.qabt.eztube.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPresentationPolicyTest {
    @Test fun youtubeAudioStaysInContextWithMiniPlayer() {
        assertFalse(
            PlayerPresentationPolicy.shouldOpenFullPlayer(
                isMovie = false,
                isLocalVideo = false,
                videoMode = false
            )
        )
    }

    @Test fun youtubeVideoOpensNowPlaying() {
        assertTrue(
            PlayerPresentationPolicy.shouldOpenFullPlayer(
                isMovie = false,
                isLocalVideo = false,
                videoMode = true
            )
        )
    }

    @Test fun movieAlwaysOpensNowPlaying() {
        assertTrue(
            PlayerPresentationPolicy.shouldOpenFullPlayer(
                isMovie = true,
                isLocalVideo = false,
                videoMode = false
            )
        )
    }

    @Test fun localVideoAlwaysOpensNowPlaying() {
        assertTrue(
            PlayerPresentationPolicy.shouldOpenFullPlayer(
                isMovie = false,
                isLocalVideo = true,
                videoMode = false
            )
        )
    }
}
