package com.qabt.eztube.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource

@UnstableApi
class PlaybackMediaSourceFactory(context: Context) {
    private val dataSourceFactory = DefaultDataSource.Factory(context)
    private val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
    private val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
    private val dashFactory = DashMediaSource.Factory(dataSourceFactory)

    fun create(mediaItem: MediaItem, source: PlaybackSource): MediaSource =
        when (source) {
            is PlaybackSource.Hls -> {
                hlsFactory.createMediaSource(
                    mediaItem.buildUpon()
                        .setUri(source.manifestUrl)
                        .setMimeType("application/x-mpegURL")
                        .build()
                )
            }
            is PlaybackSource.Dash -> {
                dashFactory.createMediaSource(
                    mediaItem.buildUpon()
                        .setUri(source.manifestUrl)
                        .setMimeType("application/dash+xml")
                        .build()
                )
            }
            is PlaybackSource.Progressive -> {
                progressiveFactory.createMediaSource(
                    mediaItem.buildUpon()
                        .setUri(source.url)
                        .apply { source.mimeType?.let { setMimeType(it) } }
                        .build()
                )
            }
            is PlaybackSource.YouTube ->
                error("YouTube source must be resolved by the YouTube adapter first")
        }

    fun createMerged(
        mediaItem: MediaItem,
        videoUrl: String,
        videoMimeType: String?,
        audioUrl: String,
        audioMimeType: String?
    ): MediaSource {
        val video = progressiveFactory.createMediaSource(
            mediaItem.buildUpon()
                .setUri(videoUrl)
                .apply { videoMimeType?.let { setMimeType(it) } }
                .build()
        )
        val audio = progressiveFactory.createMediaSource(
            MediaItem.Builder()
                .setMediaId(mediaItem.mediaId + "#audio")
                .setUri(audioUrl)
                .apply { audioMimeType?.let { setMimeType(it) } }
                .build()
        )
        return MergingMediaSource(video, audio)
    }
}
