package com.qabt.eztube.playback

import android.content.Context
import android.webkit.CookieManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import okhttp3.OkHttpClient
import okhttp3.Request

@UnstableApi
class PlaybackMediaSourceFactory(private val context: Context) {
    private val appContext = context.applicationContext
    private val dataSourceFactory = DefaultDataSource.Factory(
        appContext,
        CacheDataSource.Factory()
            .setCache(sharedCache(appContext))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    )

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
        val cached = CacheDataSource.Factory()
            .setCache(sharedCache(appContext))
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultDataSource.Factory(appContext, cached)
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

    fun prebuffer(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = PREBUFFER_BYTES
    ): Long {
        if (url.isBlank() || maxBytes <= 0L) return 0L

        return if (looksLikeHls(url)) {
            prebufferHls(url, headers, maxBytes)
        } else {
            prebufferRange(url, headers, maxBytes)
        }
    }

    private fun prebufferHls(
        manifestUrl: String,
        headers: Map<String, String>,
        segmentBytes: Long
    ): Long {
        var total = 0L
        var current = manifestUrl

        repeat(2) {
            // Warm the small manifest itself into the same Media3 cache.
            total += runCatching {
                prebufferWhole(current, headers)
            }.getOrDefault(0L)

            val body = runCatching {
                fetchText(current, headers)
            }.getOrNull() ?: return total

            val nextRaw = firstHlsUri(body) ?: return total
            val next = resolveRelative(current, nextRaw) ?: return total

            if (looksLikeHls(next)) {
                current = next
            } else {
                total += runCatching {
                    prebufferRange(next, headers, segmentBytes)
                }.getOrDefault(0L)
                return total
            }
        }

        return total
    }

    private fun prebufferWhole(
        url: String,
        headers: Map<String, String>
    ): Long {
        val cacheFactory = cacheFactoryFor(url, headers)
        val dataSpec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0L)
            .build()
        val writer = CacheWriter(
            cacheFactory.createDataSource(),
            dataSpec,
            null,
            null
        )
        writer.cache()
        return MANIFEST_PREBUFFER_BYTES
    }

    private fun prebufferRange(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long
    ): Long {
        val cacheFactory = cacheFactoryFor(url, headers)
        val dataSpec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0L)
            .setLength(maxBytes)
            .build()
        val writer = CacheWriter(
            cacheFactory.createDataSource(),
            dataSpec,
            null,
            null
        )
        writer.cache()
        return maxBytes
    }

    private fun cacheFactoryFor(
        url: String,
        headers: Map<String, String>
    ): CacheDataSource.Factory {
        val requestHeaders = effectiveHeaders(url, headers)
        val upstream = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(requestHeaders)
        return CacheDataSource.Factory()
            .setCache(sharedCache(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun effectiveHeaders(
        url: String,
        headers: Map<String, String>
    ): Map<String, String> {
        val base = headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        val cookie = CookieManager.getInstance().getCookie(url)
        return if (cookie.isNullOrBlank()) base else base + ("Cookie" to cookie)
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>
    ): String {
        val builder = Request.Builder().url(url).get()
        effectiveHeaders(url, headers).forEach { (name, value) ->
            builder.header(name, value)
        }
        return PREBUFFER_HTTP.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Prebuffer manifest HTTP " + response.code)
            }
            response.body?.string().orEmpty()
        }
    }

    private fun firstHlsUri(body: String): String? {
        val lines = body.lineSequence().map { it.trim() }.toList()
        for (index in lines.indices) {
            if (lines[index].startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                return lines.drop(index + 1)
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            }
        }
        return lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun resolveRelative(baseUrl: String, value: String): String? =
        runCatching {
            java.net.URI(baseUrl).resolve(value).toString()
        }.getOrNull()

    private fun looksLikeHls(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    companion object {
        private const val CACHE_BYTES = 32L * 1024L * 1024L
        private const val PREBUFFER_BYTES = 128L * 1024L
        private const val MANIFEST_PREBUFFER_BYTES = 64L * 1024L
        private val PREBUFFER_HTTP = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        @Volatile
        private var cacheInstance: SimpleCache? = null

        private fun sharedCache(context: Context): SimpleCache =
            cacheInstance ?: synchronized(this) {
                cacheInstance ?: SimpleCache(
                    java.io.File(context.cacheDir, "media3-warm-cache"),
                    LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                    StandaloneDatabaseProvider(context)
                ).also { cacheInstance = it }
            }
    }

}
