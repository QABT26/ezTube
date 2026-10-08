package com.qabt.eztube.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStreamSelectorTest {
    private fun stream(height: Int, bitrate: Int = height * 2) = AudioStream(
        url = "https://example.test/$height",
        bitrateKbps = bitrate,
        codec = null,
        mimeType = "video/mp4",
        isFallbackMuxed = true,
        videoHeight = height
    )

    private val streams = listOf(stream(360), stream(480), stream(720), stream(1080))

    @Test fun autoUsesHighestAvailableResolution() {
        assertEquals(1080, VideoStreamSelector.select(streams, VideoQuality.AUTO)?.videoHeight)
    }

    @Test fun fixedResolutionUsesHighestNotAboveTarget() {
        assertEquals(360, VideoStreamSelector.select(streams, VideoQuality.P360)?.videoHeight)
        assertEquals(480, VideoStreamSelector.select(streams, VideoQuality.P480)?.videoHeight)
        assertEquals(720, VideoStreamSelector.select(streams, VideoQuality.P720)?.videoHeight)
        assertEquals(1080, VideoStreamSelector.select(streams, VideoQuality.P1080)?.videoHeight)
    }

    @Test fun unavailableTargetFallsBackToClosestPlayableStream() {
        val sparse = listOf(stream(360), stream(720))
        assertEquals(360, VideoStreamSelector.select(sparse, VideoQuality.P480)?.videoHeight)
    }
}
