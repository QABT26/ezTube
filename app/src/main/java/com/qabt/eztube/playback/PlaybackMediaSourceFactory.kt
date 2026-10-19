package com.qabt.eztube.playback

import android.content.Context
import android.webkit.CookieManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource

@UnstableApi
class PlaybackMediaSourceFactory(private val context: Context) {
    private val dataSourceFactory = DefaultDataSource.Factory(context)

    private fun movieDataSourceFactory(headers: Map<String, String>): androidx.media3.datasource.DataSource.Factory {
        val baseHeaders = headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(baseHeaders)
        val cookieManager = CookieManager.getInstance()
        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val cookie = cookieManager.getCookie(dataSpec.uri.toString())
            if (cookie.isNullOrBlank()) {
                dataSpec
            } else {
                dataSpec.withAdditionalHeaders(mapOf("Cookie" to cookie))
            }
        }
        return DefaultDataSource.Factory(context, resolvingFactory)
    }
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

    fun createMovieHls(
        mediaItem: MediaItem,
        manifestUrl: String,
        headers: Map<String, String>
    ): MediaSource {
        val dataFactory = movieDataSourceFactory(headers)

        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataFactory)
            .createMediaSource(
                mediaItem.buildUpon()
                    .setUri(manifestUrl)
                    .setMimeType("application/x-mpegURL")
                    .build()
            )
    }

    fun createMovieDirect(
        mediaItem: MediaItem,
        url: String,
        headers: Map<String, String>
    ): MediaSource {
        val dataFactory = movieDataSourceFactory(headers)

        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataFactory)
            .createMediaSource(
                mediaItem.buildUpon()
                    .setUri(url)
                    .build()
            )
    }

    fun createMerged(
        mediaItem: MediaItem,
        videoUrl: String,
        videoMimeType: String?,
        audioUrl: String,
        audioMimeType: String?,
        headers: Map<String, String> = emptyMap()
    ): MediaSource {
        val dataFactory = movieDataSourceFactory(headers)

        val movieFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataFactory)

        // The video MediaItem carries subtitleConfigurations. DefaultMediaSourceFactory
        // merges those side-loaded subtitle sources before we merge the separate audio.
        val video = movieFactory.createMediaSource(
            mediaItem.buildUpon()
                .setUri(videoUrl)
                .apply { videoMimeType?.let { setMimeType(it) } }
                .build()
        )
        val audio = ProgressiveMediaSource.Factory(dataFactory).createMediaSource(
            MediaItem.Builder()
                .setMediaId(mediaItem.mediaId + "#audio")
                .setUri(audioUrl)
                .apply { audioMimeType?.let { setMimeType(it) } }
                .build()
        )
        return MergingMediaSource(video, audio)
    }

}
