package com.qabt.eztube.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import com.qabt.eztube.movie.MovieProviderRegistry
import com.qabt.eztube.movie.MoviePlayback
import com.qabt.eztube.playback.sabr.SabrMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single owner for persisted logical queue + the small playable Media3 window.
 *
 * The logical queue can be large. Only adjacent direct YouTube stream URLs are resolved,
 * avoiding eager extraction and reducing stale URL risk.
 */
@androidx.media3.common.util.UnstableApi
class PlaybackQueueManager(
    private val context: Context,
    private val player: ExoPlayer,
    private val preferences: PlaybackPreferences,
    private val source: NewPipeYouTubeSource,
    private val movieProviders: MovieProviderRegistry,
    private val scope: CoroutineScope
) {
    private data class ResolvedPlayback(
        val mediaItem: MediaItem,
        val mediaSource: MediaSource?,
        val sabrQualityController: SabrMediaSourceFactory.QualityController? = null
    )

    private val mediaSourceFactory by lazy {
        PlaybackMediaSourceFactory(context)
    }
    companion object {
        private const val MAX_SOURCE_RECOVERY_ATTEMPTS = 3
        private const val MAX_RESOLVE_ATTEMPTS = 3
        private const val SABR_FAILURE_COOLDOWN_MS = 15_000L
    }

    @Volatile private var busy = false
    @Volatile private var preloadId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var alternateStreamId: String? = null
    @Volatile private var alternateStreamVideoMode: Boolean? = null
    @Volatile private var alternateStream: AudioStream? = null
    @Volatile private var recoveryJobActive = false
    @Volatile private var recoveryMediaId: String? = null
    @Volatile private var recoveryAttempts = 0
    @Volatile private var recoveryHealthyGeneration = 0L
    @Volatile private var pendingManualDelta = 0
    @Volatile private var activePlaybackEngine: String = PlaybackService.ENGINE_DIRECT
    @Volatile private var activeSabrQualityController: SabrMediaSourceFactory.QualityController? = null
    @Volatile private var sabrQualityWarmGeneration = 0L
    @Volatile private var sabrQualityWarmPending = false
    private val sabrFailureUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastSabrFallbackReason: String? = null

    private fun mediaSourceFor(mediaItem: MediaItem, stream: AudioStream): MediaSource? {
        val audioUrl = stream.companionAudioUrl ?: return null
        return mediaSourceFactory.createMerged(
            mediaItem = mediaItem,
            videoUrl = stream.url,
            videoMimeType = stream.mimeType,
            audioUrl = audioUrl,
            audioMimeType = stream.companionAudioMimeType
        )
    }

    private fun resolvedGenericPlayback(
        media: MediaSummary,
        source: PlaybackSource
    ): ResolvedPlayback {
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .setExtras(android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, false)
                putInt(PlaybackService.EXTRA_VIDEO_HEIGHT, 0)
                putString(PlaybackService.EXTRA_PLAYBACK_ENGINE, PlaybackService.ENGINE_DIRECT)
            })
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        val uri = when (source) {
            is PlaybackSource.Hls -> source.manifestUrl
            is PlaybackSource.Dash -> source.manifestUrl
            is PlaybackSource.Progressive -> source.url
            is PlaybackSource.YouTube -> error("YouTube must use the YouTube playback adapter")
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

        return ResolvedPlayback(
            mediaItem = mediaItem,
            mediaSource = mediaSourceFactory.create(mediaItem, source)
        )
    }

    private fun resolvedMoviePlayback(
        media: MediaSummary,
        playback: MoviePlayback
    ): ResolvedPlayback {
        val splitVideo = playback.videoStreams
            .let { streams ->
                val target = preferences.effectiveVideoQuality().targetHeight
                when {
                    streams.isEmpty() -> null
                    target == null -> streams.maxWithOrNull(
                        compareBy<com.qabt.eztube.movie.MovieVideoStream> { it.height ?: 0 }
                            .thenBy { it.bitrate ?: 0 }
                    )
                    else -> streams
                        .filter { (it.height ?: Int.MAX_VALUE) <= target }
                        .maxWithOrNull(
                            compareBy<com.qabt.eztube.movie.MovieVideoStream> { it.height ?: 0 }
                                .thenBy { it.bitrate ?: 0 }
                        )
                        ?: streams.minWithOrNull(
                            compareBy<com.qabt.eztube.movie.MovieVideoStream> { it.height ?: Int.MAX_VALUE }
                                .thenBy { it.bitrate ?: Int.MAX_VALUE }
                        )
                }
            }

        val isSplit = splitVideo != null && !playback.audioUrl.isNullOrBlank()
        val engine = if (isSplit) PlaybackService.ENGINE_DIRECT else PlaybackService.ENGINE_HLS
        val selectedHeight = splitVideo?.height ?: 0

        val diagnostic = buildString {
            playback.diagnostic?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (isSplit) {
                if (isNotEmpty()) append("\n")
                append("Selected video: ")
                append(selectedHeight.takeIf { it > 0 }?.let { "${it}p" } ?: "unknown")
                append("\nMedia3: merging video + audio")
            }
        }.takeIf { it.isNotBlank() }

        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .setExtras(android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, false)
                putInt(PlaybackService.EXTRA_VIDEO_HEIGHT, selectedHeight)
                putString(PlaybackService.EXTRA_PLAYBACK_ENGINE, engine)
                putBoolean(PlaybackService.EXTRA_IS_MOVIE, true)
                diagnostic?.let {
                    putString(PlaybackService.EXTRA_MOVIE_DIAGNOSTIC, it)
                }
            })
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        val subtitleConfigurations = playback.subtitles.mapIndexedNotNull { index, subtitle ->
            val uri = runCatching { Uri.parse(subtitle.url) }.getOrNull()
                ?: return@mapIndexedNotNull null
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(
                    subtitle.mimeType ?: when {
                        subtitle.url.substringBefore('?').endsWith(".vtt", ignoreCase = true) ->
                            "text/vtt"
                        subtitle.url.substringBefore('?').endsWith(".srt", ignoreCase = true) ->
                            "application/x-subrip"
                        subtitle.url.substringBefore('?').endsWith(".ass", ignoreCase = true) ||
                            subtitle.url.substringBefore('?').endsWith(".ssa", ignoreCase = true) ->
                            "text/x-ssa"
                        else -> "text/vtt"
                    }
                )
                .setLanguage(subtitle.language)
                .setLabel(
                    subtitle.label
                        ?: subtitle.language
                        ?: "Subtitle ${index + 1}"
                )
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }

        if (isSplit) {
            val video = requireNotNull(splitVideo)
            val audioUrl = requireNotNull(playback.audioUrl)
            val mediaItem = MediaItem.Builder()
                .setMediaId(media.id)
                .setUri(video.url)
                .apply { video.mimeType?.let { setMimeType(it) } }
                .setSubtitleConfigurations(subtitleConfigurations)
                .setMediaMetadata(metadata)
                .build()

            return ResolvedPlayback(
                mediaItem = mediaItem,
                mediaSource = mediaSourceFactory.createMerged(
                    mediaItem = mediaItem,
                    videoUrl = video.url,
                    videoMimeType = video.mimeType,
                    audioUrl = audioUrl,
                    audioMimeType = playback.audioMimeType,
                    headers = playback.requestHeaders
                )
            )
        }

        val hlsUrl = playback.hlsUrl
            ?: throw IllegalStateException("Movie playback has no playable source")
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(hlsUrl)
            .setMimeType("application/x-mpegURL")
            .setSubtitleConfigurations(subtitleConfigurations)
            .setMediaMetadata(metadata)
            .build()

        return ResolvedPlayback(
            mediaItem = mediaItem,
            mediaSource = mediaSourceFactory.createMovieHls(
                mediaItem = mediaItem,
                manifestUrl = hlsUrl,
                headers = playback.requestHeaders
            )
        )
    }

    private fun resolvedSabrPlayback(
        media: MediaSummary,
        spec: com.qabt.eztube.playback.sabr.SabrSourceSpec,
        initialPositionMs: Long = 0L
    ): ResolvedPlayback {
        val bootstrapHeight = spec.bootstrapVideoFormat.height.coerceAtLeast(0)
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .setExtras(android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, false)
                putInt(PlaybackService.EXTRA_VIDEO_HEIGHT, bootstrapHeight)
                putString(PlaybackService.EXTRA_PLAYBACK_ENGINE, PlaybackService.ENGINE_SABR)
            })
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri("sabr://" + spec.videoId)
            .setMediaMetadata(metadata)
            .build()
        val created = SabrMediaSourceFactory.create(
            mediaItem,
            spec,
            initialPositionMs.coerceAtLeast(0L)
        )
        return ResolvedPlayback(
            mediaItem = mediaItem,
            mediaSource = created.mediaSource,
            sabrQualityController = created.qualityController
        )
    }

    private fun setResolved(playback: ResolvedPlayback) {
        val engine = playback.mediaItem.mediaMetadata.extras
            ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
            ?: PlaybackService.ENGINE_DIRECT
        activePlaybackEngine = engine
        sabrQualityWarmGeneration += 1
        sabrQualityWarmPending = false
        activeSabrQualityController =
            if (engine == PlaybackService.ENGINE_SABR) playback.sabrQualityController else null

        if (playback.mediaSource != null) {
            player.setMediaSource(playback.mediaSource)
        } else {
            player.setMediaItem(playback.mediaItem)
        }

        if (
            engine == PlaybackService.ENGINE_SABR ||
            engine == PlaybackService.ENGINE_HLS
        ) {
            // A newly attached source does not have its new Tracks yet. Do not
            // build an override from the previous source's track group.
            applyAdaptiveVideoQualityConstraint(useCurrentTracks = false)
        }
    }

    private fun applyAdaptiveVideoQualityConstraint(
        useCurrentTracks: Boolean = true
    ) {
        val targetHeight = preferences.effectiveVideoQuality().targetHeight
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setForceHighestSupportedBitrate(false)

        if (targetHeight == null) {
            builder
                .setMinVideoSize(0, 0)
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            // Keep SABR/HLS inside one adaptive selection. A TrackSelectionOverride
            // hard-switches representation immediately and can discard the already
            // buffered old representation, causing a visible pause while the first
            // segment of the new quality arrives. A height constraint lets Media3
            // change at a safe segment boundary and keep playback moving.
            builder
                .setMinVideoSize(0, 0)
                .setMaxVideoSize(Int.MAX_VALUE, targetHeight)
        }

        player.trackSelectionParameters = builder.build()
    }

    private fun addResolved(playback: ResolvedPlayback) {
        if (playback.mediaSource != null) {
            player.addMediaSource(playback.mediaSource)
        } else {
            player.addMediaItem(playback.mediaItem)
        }
    }

    private fun resolvedPlayback(media: MediaSummary, stream: AudioStream): ResolvedPlayback {
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .setExtras(android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, stream.isFallbackMuxed)
                putInt(PlaybackService.EXTRA_VIDEO_HEIGHT, stream.videoHeight ?: 0)
                putString(PlaybackService.EXTRA_PLAYBACK_ENGINE, PlaybackService.ENGINE_DIRECT)
            })
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(stream.url)
            .setMediaMetadata(metadata)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .build()
        return ResolvedPlayback(
            mediaItem = mediaItem,
            mediaSource = mediaSourceFor(mediaItem, stream)
        )
    }

    private fun invalidatePending() {
        generation += 1
        preloadId = null
    }

    private fun clearAlternateStream() {
        alternateStreamId = null
        alternateStreamVideoMode = null
        alternateStream = null
    }

    fun onTransition(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId ?: return
        val saved = preferences.loadQueue() ?: return
        val index = saved.first.indexOfFirst { it.id == id }
        if (index < 0) return
        preferences.saveQueue(saved.first, index)
        preferences.save(saved.first[index], 0L)
        clearAlternateStream()
        if (
            !preferences.loadSleepEndCurrent() &&
            !id.startsWith("movie:narto:")
        ) {
            ensureNext(saved.first, index)
        }
        warmAlternateStream(saved.first[index])
    }

    fun onPlaybackEnded() {
        if (busy) return
        if (preferences.loadSleepEndCurrent()) {
            preferences.saveSleepEndCurrent(false)
            player.pause()
            checkpointSession()
            return
        }
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        if (items.isEmpty()) return
        val currentId = player.currentMediaItem?.mediaId
        if (currentId?.startsWith("movie:narto:") == true) {
            // Narto requires the user-visible website unlock flow for each episode.
            // Never auto-resolve the next episode behind that gate.
            return
        }
        val current = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        val target = QueueNavigationPolicy.endedTarget(
            current = current,
            size = items.size,
            autoplay = preferences.loadAutoplay(),
            repeatMode = preferences.loadRepeatMode()
        ) ?: return
        resolveAndPlay(items, target)
    }

    fun setSleepEndCurrent(enabled: Boolean) {
        preferences.saveSleepEndCurrent(enabled)
        val playerIndex = player.currentMediaItemIndex
        if (enabled) {
            // End-of-current is a hard stop override. Cancel any in-flight preload before
            // removing the resolved tail; otherwise an older preload coroutine can append
            // another item after this command and Media3 will auto-transition instead of
            // reaching STATE_ENDED. Also force native repeat off in case a transport client
            // enabled it independently of ezTube's logical repeat preference.
            invalidatePending()
            player.repeatMode = Player.REPEAT_MODE_OFF
            if (playerIndex >= 0 && playerIndex + 1 < player.mediaItemCount) {
                player.removeMediaItems(playerIndex + 1, player.mediaItemCount)
            }
        } else {
            val saved = preferences.loadQueue() ?: return
            val currentId = player.currentMediaItem?.mediaId
            val index = saved.first.indexOfFirst { it.id == currentId }
                .takeIf { it >= 0 } ?: saved.second
            if (index in saved.first.indices) ensureNext(saved.first, index)
        }
    }

    fun refreshFromPreferences() {
        invalidatePending()
        val saved = preferences.loadQueue() ?: return
        val currentId = player.currentMediaItem?.mediaId
        val index = saved.first.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second

        if (preferences.loadSleepEndCurrent()) {
            player.repeatMode = Player.REPEAT_MODE_OFF
            val playerIndex = player.currentMediaItemIndex
            if (playerIndex >= 0 && playerIndex + 1 < player.mediaItemCount) {
                player.removeMediaItems(playerIndex + 1, player.mediaItemCount)
            }
            return
        }

        if (!preferences.loadAutoplay()) {
            preloadId = null
            val playerIndex = player.currentMediaItemIndex
            if (playerIndex >= 0 && playerIndex + 1 < player.mediaItemCount) {
                player.removeMediaItems(playerIndex + 1, player.mediaItemCount)
            }
            return
        }

        if (index in saved.first.indices) ensureNext(saved.first, index)
    }

    fun restoreSession() {
        if (busy || player.mediaItemCount > 0) return
        val session = preferences.loadSession() ?: return
        val saved = preferences.loadQueue()
        val items = saved?.first?.takeIf { it.isNotEmpty() } ?: listOf(session.media)
        val target = items.indexOfFirst { it.id == session.media.id }
            .takeIf { it >= 0 } ?: saved?.second?.takeIf { it in items.indices } ?: 0
        // Restoring an app/service process must never unexpectedly start audio.
        // The saved play intent is retained for diagnostics/future policy, but restore is paused.
        resolveAndPlay(items, target, session.positionMs, false)
    }

    fun checkpointSession() {
        val saved = preferences.loadQueue() ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val index = saved.first.indexOfFirst { it.id == id }
        if (index !in saved.first.indices) return
        preferences.saveQueue(saved.first, index)
        preferences.saveSession(
            media = saved.first[index],
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        )
    }

    fun playDirectMovieHls(
        hlsUrl: String,
        referer: String,
        subtitleUrl: String?,
        positionMs: Long,
        playWhenReady: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (busy) {
            onComplete(Result.failure(IllegalStateException("Playback resolver is busy")))
            return
        }
        val parsedHls = runCatching { Uri.parse(hlsUrl) }.getOrNull()
        val hlsHost = parsedHls?.host.orEmpty().lowercase()
        val hlsPath = parsedHls?.path.orEmpty()
        val isSignedNartoManifest =
            parsedHls?.scheme == "https" &&
                hlsHost == "stream.narto-drama.com" &&
                hlsPath.startsWith("/e/m/")
        val isUnlockedDramaWaveManifest =
            parsedHls?.scheme == "https" &&
                (
                    hlsHost == "mydramawave.com" ||
                    hlsHost.endsWith(".mydramawave.com")
                ) &&
                hlsPath.endsWith(".m3u8", ignoreCase = true)

        if (!isSignedNartoManifest && !isUnlockedDramaWaveManifest) {
            onComplete(Result.failure(IllegalArgumentException("Invalid Narto HLS URL")))
            return
        }

        val safeSubtitleUrl = subtitleUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { value ->
                val uri = runCatching { Uri.parse(value) }.getOrNull()
                val host = uri?.host.orEmpty().lowercase()
                val path = uri?.path.orEmpty()
                uri?.scheme == "https" &&
                    (
                        host == "edge.narto-drama.com" ||
                        host == "narto-drama.com" ||
                        host == "stream.narto-drama.com"
                    ) &&
                    path.startsWith("/e/s/")
            }

        val saved = preferences.loadQueue()
        if (saved == null || saved.first.isEmpty()) {
            onComplete(Result.failure(IllegalStateException("Playback queue is empty")))
            return
        }
        val target = saved.second
        val media = saved.first.getOrNull(target)
        if (media == null || !media.id.startsWith("movie:narto:")) {
            onComplete(Result.failure(IllegalStateException("Current item is not Narto")))
            return
        }

        preferences.networkPolicyBlockReason()?.let { reason ->
            onComplete(Result.failure(IllegalStateException(reason)))
            return
        }

        busy = true
        invalidatePending()
        val requestGeneration = generation

        player.stop()
        player.clearMediaItems()

        scope.launch {
            runCatching {
                resolvedMoviePlayback(
                    media,
                    MoviePlayback(
                        mediaId = media.id,
                        hlsUrl = hlsUrl,
                        subtitles = safeSubtitleUrl
                            ?.let {
                                listOf(
                                    com.qabt.eztube.movie.MovieSubtitle(
                                        language = "vi",
                                        label = "Tiếng Việt",
                                        url = it,
                                        mimeType = "text/vtt"
                                    )
                                )
                            }
                            .orEmpty(),
                        requestHeaders = mapOf(
                            "User-Agent" to (
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
                                ),
                            "Referer" to referer,
                            "Origin" to runCatching {
                                val uri = Uri.parse(referer)
                                val host = uri.host.orEmpty()
                                if (uri.scheme == "https" && host.endsWith("narto-drama.com")) {
                                    "https://" + host
                                } else {
                                    "https://narto-drama.com"
                                }
                            }.getOrDefault("https://narto-drama.com")
                        ),
                        diagnostic = buildString {
                            append("Provider: Narto Drama")
                            append("\nPlayback: interactive website unlock")
                            append("\nMedia3: signed HLS")
                        }
                    )
                )
            }.onSuccess { item ->
                if (requestGeneration != generation) {
                    onComplete(
                        Result.failure(
                            IllegalStateException("Playback request was superseded")
                        )
                    )
                    return@onSuccess
                }

                val latest = preferences.loadQueue()
                if (latest == null || latest.first.getOrNull(target)?.id != media.id) {
                    onComplete(
                        Result.failure(
                            IllegalStateException("Playback queue changed during unlock")
                        )
                    )
                    return@onSuccess
                }

                setResolved(item)
                player.prepare()
                if (positionMs > 0L) player.seekTo(positionMs.coerceAtLeast(0L))
                player.setPlaybackSpeed(preferences.loadSpeed())
                if (playWhenReady) player.play() else player.pause()
                preferences.saveQueue(saved.first, target)
                preferences.saveSession(
                    media,
                    positionMs.coerceAtLeast(0L),
                    playWhenReady
                )
                clearAlternateStream()
                onComplete(Result.success(Unit))
            }.onFailure { error ->
                onComplete(Result.failure(error))
            }

            busy = false
        }
    }

    fun playSavedCurrent(
        positionMs: Long,
        playWhenReady: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ) {
        if (busy) {
            onComplete(Result.failure(IllegalStateException("Playback resolver is busy")))
            return
        }
        val saved = preferences.loadQueue()
        if (saved == null || saved.first.isEmpty()) {
            onComplete(Result.failure(IllegalStateException("Playback queue is empty")))
            return
        }
        val target = saved.second
        if (target !in saved.first.indices) {
            onComplete(Result.failure(IllegalStateException("Playback queue index is invalid")))
            return
        }
        resolveAndPlay(
            items = saved.first,
            target = target,
            positionMs = positionMs.coerceAtLeast(0L),
            playWhenReady = playWhenReady,
            onComplete = onComplete
        )
    }

    fun recoverSourceError() {
        if (busy || recoveryJobActive) return
        recoveryHealthyGeneration += 1
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (currentId.startsWith("movie:narto:")) {
            // A failed/expired signed Narto manifest must go through the visible
            // unlock page again; background retries cannot reproduce that session.
            return
        }
        val index = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        if (index !in items.indices) return

        if (recoveryMediaId != currentId) {
            recoveryMediaId = currentId
            recoveryAttempts = 0
        }
        if (recoveryAttempts >= MAX_SOURCE_RECOVERY_ATTEMPTS) return

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady

        // Give a movie source one real Media3 retry before blacklisting its provider.
        // This avoids treating a transient manifest/segment error as proof that the
        // provider is unusable and immediately falling into an unrelated source.
        if (currentId.startsWith("movie:") && recoveryAttempts >= 1) {
            movieProviders.reportPlaybackFailure(currentId)
        }
        val currentEngine = player.currentMediaItem?.mediaMetadata?.extras
            ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
        val isSabr = currentEngine == PlaybackService.ENGINE_SABR

        // A SABR PlayerError is terminal only after SabrLoadErrorHandlingPolicy has already
        // retried the failing segment in-place. Do not recreate SABR and visibly reload it again;
        // blacklist SABR for this item and perform one direct-stream fallback recovery.
        if (isSabr) {
            sabrFailureUntilMs[currentId] =
                android.os.SystemClock.elapsedRealtime() + SABR_FAILURE_COOLDOWN_MS
            lastSabrFallbackReason = "player error; temporary SABR cooldown"
        }
        recoveryJobActive = true
        invalidatePending()
        clearAlternateStream()
        val requestGeneration = generation

        scope.launch {
            val media = items[index]
            while (
                recoveryAttempts < MAX_SOURCE_RECOVERY_ATTEMPTS &&
                requestGeneration == generation
            ) {
                recoveryAttempts += 1
                if (recoveryAttempts > 1) delay((recoveryAttempts - 1) * 500L)

                if (PlaybackSourceResolver.resolve(media.id) is PlaybackSource.YouTube) {
                    source.invalidatePlaybackStreams(media.id)
                }
                val resolved = resolve(media)
                if (resolved.isFailure) continue
                val item = resolved.getOrThrow()

                val latest = preferences.loadQueue()
                if (latest == null || latest.first.getOrNull(index)?.id != media.id) break
                if (requestGeneration != generation) break

                setResolved(item)
                player.prepare()
                if (positionMs > 0L) player.seekTo(positionMs)
                player.setPlaybackSpeed(preferences.loadSpeed())
                if (shouldPlay) player.play() else player.pause()
                preferences.saveQueue(items, index)
                preferences.saveSession(media, positionMs, shouldPlay)
                ensureNext(items, index)
                warmAlternateStream(media)
                break
            }
            recoveryJobActive = false
        }
    }

    fun onPlaybackHealthy() {
        if (player.playbackState != Player.STATE_READY) return

        val healthyId = player.currentMediaItem?.mediaId ?: return
        if (healthyId.startsWith("movie:")) {
            movieProviders.reportPlaybackHealthy(healthyId)
        }
        recoveryMediaId = healthyId
        val healthyGeneration = ++recoveryHealthyGeneration

        // READY can be reached briefly between repeated source failures. Reset the recovery
        // budget only after playback has stayed healthy for a while, otherwise the "3 retries"
        // budget effectively becomes unlimited reload cycles.
        scope.launch {
            delay(10_000L)
            if (healthyGeneration != recoveryHealthyGeneration) return@launch
            if (player.currentMediaItem?.mediaId != healthyId) return@launch
            if (player.playbackState != Player.STATE_READY) return@launch
            if (!player.isPlaying && player.playWhenReady) return@launch
            recoveryAttempts = 0
        }
    }

    fun onTracksChanged() {
        if (
            activePlaybackEngine != PlaybackService.ENGINE_SABR &&
            activePlaybackEngine != PlaybackService.ENGINE_HLS
        ) return
        if (activePlaybackEngine == PlaybackService.ENGINE_SABR && sabrQualityWarmPending) {
            return
        }
        if (preferences.effectiveVideoQuality().targetHeight == null) return
        applyAdaptiveVideoQualityConstraint()
    }

    fun applyVideoQualityChange(positionMs: Long, playWhenReady: Boolean): String {
        if (activePlaybackEngine == PlaybackService.ENGINE_SABR) {
            val controller = activeSabrQualityController
            if (controller == null) {
                applyAdaptiveVideoQualityConstraint()
                return activePlaybackEngine
            }

            val requestGeneration = ++sabrQualityWarmGeneration
            sabrQualityWarmPending = true
            val targetHeight = preferences.effectiveVideoQuality().targetHeight
            val warmPositionMs = maxOf(
                positionMs.coerceAtLeast(0L),
                player.currentPosition.coerceAtLeast(0L)
            )

            scope.launch {
                // Keep the current representation playing while the first target
                // segments are fetched into the same SABR cache. Only the latest
                // quality request is allowed to change Media3's constraint.
                runCatching {
                    withContext(Dispatchers.IO) {
                        controller.warm(targetHeight, warmPositionMs)
                    }
                }
                if (requestGeneration != sabrQualityWarmGeneration) return@launch
                if (activePlaybackEngine != PlaybackService.ENGINE_SABR) return@launch
                if (activeSabrQualityController !== controller) return@launch

                sabrQualityWarmPending = false
                applyAdaptiveVideoQualityConstraint()
            }
            return activePlaybackEngine
        }

        if (activePlaybackEngine == PlaybackService.ENGINE_HLS) {
            applyAdaptiveVideoQualityConstraint()
            return activePlaybackEngine
        }

        val currentId = player.currentMediaItem?.mediaId
        if (
            currentId != null &&
            PlaybackSourceResolver.resolve(currentId) is PlaybackSource.YouTube
        ) {
            sabrFailureUntilMs.remove(currentId)
            lastSabrFallbackReason = "manual quality change retries SABR"
        }
        reloadCurrent(positionMs, playWhenReady)
        return PlaybackService.ENGINE_DIRECT
    }

    fun currentPlaybackEngine(): String = activePlaybackEngine

    fun sabrDiagnosticSnapshot(): String {
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> player.playbackState.toString()
        }
        val target = preferences.effectiveVideoQuality().targetHeight
            ?.let { height -> height.toString() + "p" }
            ?: "Auto"
        val header = buildString {
            append("engine=").append(activePlaybackEngine)
            append(" state=").append(state)
            append(" playWhenReady=").append(player.playWhenReady)
            append(" isPlaying=").append(player.isPlaying)
            append("\npos=").append(player.currentPosition.coerceAtLeast(0L))
            append("ms buffered=").append(player.bufferedPosition.coerceAtLeast(0L))
            append("ms target=").append(target)
            append(" warmPending=").append(sabrQualityWarmPending)
            val mediaId = player.currentMediaItem?.mediaId
            val blockedUntil = mediaId?.let { sabrFailureUntilMs[it] } ?: 0L
            val remaining = (blockedUntil - android.os.SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)
            append("\nsabrBlocked=").append(remaining > 0L)
            if (remaining > 0L) append(" cooldown=").append(remaining).append("ms")
            lastSabrFallbackReason?.let { append("\nfallback=").append(it) }
        }
        val bridge = activeSabrQualityController?.diagnosticSnapshot()
        return if (bridge.isNullOrBlank()) header else header + "\n" + bridge
    }

    fun reloadCurrent(positionMs: Long, playWhenReady: Boolean) {
        if (busy) return
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        val currentId = player.currentMediaItem?.mediaId
        val index = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        if (index !in items.indices) return
        val media = items[index]
        val targetVideoMode = preferences.loadVideoMode()
        val cached = alternateStream?.takeIf {
            alternateStreamId == media.id && alternateStreamVideoMode == targetVideoMode
        }
        if (cached != null) {
            clearAlternateStream()
            playResolved(items, index, media, cached, positionMs.coerceAtLeast(0L), playWhenReady)
            warmAlternateStream(media)
            return
        }
        resolveAndPlay(
            items,
            index,
            positionMs.coerceAtLeast(0L),
            playWhenReady,
            allowFastStart = false
        )
    }

    fun move(delta: Int) {
        if (delta == 0) return
        if (busy) {
            // Do not drop transport taps while a source is resolving. Coalesce rapid
            // presses into one logical delta and drain it as soon as the current
            // resolve completes. This keeps Next/Previous responsive on slow sources.
            pendingManualDelta = (pendingManualDelta + delta).coerceIn(-10, 10)
            return
        }
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        if (items.isEmpty()) return
        val currentId = player.currentMediaItem?.mediaId
        val current = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        val target = QueueNavigationPolicy.manualTarget(
            current = current,
            size = items.size,
            delta = delta,
            repeatMode = preferences.loadRepeatMode()
        ) ?: return

        if (items[target].id.startsWith("movie:narto:")) {
            // App UI owns the visible Narto unlock flow. Background/system
            // transport must not try to resolve a gated episode and surface a
            // misleading source error.
            return
        }

        // Native adjacent items are safe only when they match the logical target.
        // Queue edits can otherwise leave a stale resolved item in Media3.
        if (delta > 0 && target == current + 1 && player.hasNextMediaItem()) {
            val nativeNext = player.getMediaItemAt(player.currentMediaItemIndex + 1)
            if (nativeNext.mediaId == items[target].id) {
                player.seekToNextMediaItem()
                // Manual Next is an explicit play action. If the player was paused,
                // moving to a preloaded native item must resume immediately instead
                // of preserving the old paused playWhenReady state.
                player.play()
                return
            }
        }
        if (delta < 0 && target == current - 1 && player.hasPreviousMediaItem()) {
            val nativePrevious = player.getMediaItemAt(player.currentMediaItemIndex - 1)
            if (nativePrevious.mediaId == items[target].id) {
                player.seekToPreviousMediaItem()
                player.play()
                return
            }
        }
        resolveAndPlay(items, target)
    }

    fun ensureNext(items: List<MediaSummary>, index: Int) {
        if (
            !PlaybackRuntimePolicy.allowPreload(
                autoplay = preferences.loadAutoplay(),
                sleepEndCurrent = preferences.loadSleepEndCurrent()
            ) ||
            !preferences.shouldPreloadOnCurrentNetwork()
        ) return
        val next = items.getOrNull(index + 1) ?: return
        if (next.id.startsWith("movie:narto:")) return
        val current = player.currentMediaItemIndex
        val loadedNext = if (current >= 0 && current + 1 < player.mediaItemCount) {
            player.getMediaItemAt(current + 1)
        } else null
        if (loadedNext?.mediaId == next.id) return
        if (loadedNext != null) {
            player.removeMediaItems(current + 1, player.mediaItemCount)
        }
        // A queue edit can invalidate an in-flight preload for the old next item.
        // Never let that stale marker block resolving the new logical next item.
        if (preloadId != next.id) preloadId = null
        if (preloadId == next.id) return
        preloadId = next.id
        val requestGeneration = generation
        scope.launch {
            resolveWithRetry(next).onSuccess { item ->
                if (requestGeneration != generation) return@onSuccess
                val currentId = player.currentMediaItem?.mediaId
                val latest = preferences.loadQueue()
                val latestIndex = latest?.first?.indexOfFirst { it.id == currentId } ?: -1
                val expected = latest?.first?.getOrNull(latestIndex + 1)?.id
                if (expected == next.id) {
                    val playerIndex = player.currentMediaItemIndex
                    if (playerIndex >= 0 && playerIndex + 1 < player.mediaItemCount) {
                        player.removeMediaItems(playerIndex + 1, player.mediaItemCount)
                    }
                    addResolved(item)
                }
            }
            if (preloadId == next.id) preloadId = null
        }
    }

    private fun resolveAndPlay(
        items: List<MediaSummary>,
        target: Int,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
        onComplete: ((Result<Unit>) -> Unit)? = null,
        allowFastStart: Boolean = true
    ) {
        if (target !in items.indices) {
            onComplete?.invoke(Result.failure(IndexOutOfBoundsException("Playback target is out of range")))
            return
        }
        busy = true
        invalidatePending()
        val requestGeneration = generation

        // The logical selection has already changed in the UI. Do not keep the
        // previous item's audio/video visible while the new source is resolving.
        // Clearing the old Media3 timeline makes the handoff immediate from the
        // user's perspective; the selected item remains available from the saved
        // logical queue and is attached as soon as resolution completes.
        player.stop()
        player.clearMediaItems()

        scope.launch {
            val media = items[target]
            val useFastStart = allowFastStart && preferences.loadVideoMode() && playWhenReady
            val resolved = resolveWithRetry(
                media,
                fastStart = useFastStart,
                initialPositionMs = positionMs
            )
            resolved.onSuccess { item ->
                if (requestGeneration != generation) {
                    onComplete?.invoke(Result.failure(IllegalStateException("Playback request was superseded")))
                    return@onSuccess
                }
                val latest = preferences.loadQueue()
                if (latest == null || latest.first.getOrNull(target)?.id != media.id) {
                    onComplete?.invoke(Result.failure(IllegalStateException("Playback queue changed during resolve")))
                    return@onSuccess
                }
                setResolved(item)
                player.prepare()
                if (positionMs > 0L) player.seekTo(positionMs)
                player.setPlaybackSpeed(preferences.loadSpeed())
                if (playWhenReady) player.play() else player.pause()
                preferences.saveQueue(items, target)
                preferences.saveSession(media, positionMs, playWhenReady)
                clearAlternateStream()
                ensureNext(items, target)
                warmAlternateStream(media)
                val resolvedEngine = item.mediaItem.mediaMetadata.extras
                    ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
                if (useFastStart && resolvedEngine != PlaybackService.ENGINE_SABR) {
                    scheduleVideoUpgrade(items, target, media, requestGeneration)
                }
                onComplete?.invoke(Result.success(Unit))
            }.onFailure { error ->
                onComplete?.invoke(Result.failure(error))
            }
            busy = false
            drainPendingManualMove()
        }
    }

    private fun drainPendingManualMove() {
        val delta = pendingManualDelta
        if (delta == 0) return
        pendingManualDelta = 0
        move(delta)
    }

    private fun scheduleVideoUpgrade(
        items: List<MediaSummary>,
        target: Int,
        media: MediaSummary,
        requestGeneration: Long
    ) {
        scope.launch {
            if (PlaybackSourceResolver.resolve(media.id) !is PlaybackSource.YouTube) return@launch
            delay(1_200L)
            if (requestGeneration != generation) return@launch
            if (!preferences.loadVideoMode()) return@launch
            if (player.currentMediaItem?.mediaId != media.id) return@launch
            if (player.playbackState != Player.STATE_READY) {
                delay(800L)
                if (requestGeneration != generation) return@launch
                if (player.currentMediaItem?.mediaId != media.id) return@launch
            }

            val streams = runCatching {
                withContext(Dispatchers.IO) { source.videoStreams(media.id) }
            }.getOrNull().orEmpty()
            if (streams.isEmpty()) return@launch

            val targetStream = VideoStreamSelector.select(
                streams,
                preferences.effectiveVideoQuality()
            ) ?: return@launch

            val currentHeight = player.currentMediaItem?.mediaMetadata?.extras
                ?.getInt(PlaybackService.EXTRA_VIDEO_HEIGHT, 0) ?: 0
            val targetHeight = targetStream.videoHeight ?: 0
            if (targetHeight <= currentHeight) return@launch

            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            val upgraded = resolvedPlayback(media, targetStream)
            setResolved(upgraded)
            player.prepare()
            if (positionMs > 0L) player.seekTo(positionMs)
            player.setPlaybackSpeed(preferences.loadSpeed())
            if (shouldPlay) player.play() else player.pause()
            preferences.saveQueue(items, target)
            preferences.saveSession(media, positionMs, shouldPlay)
            ensureNext(items, target)
        }
    }

    private fun playResolved(
        items: List<MediaSummary>,
        target: Int,
        media: MediaSummary,
        stream: AudioStream,
        positionMs: Long,
        playWhenReady: Boolean
    ) {
        invalidatePending()
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .setExtras(android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, stream.isFallbackMuxed)
                putInt(PlaybackService.EXTRA_VIDEO_HEIGHT, stream.videoHeight ?: 0)
            })
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(stream.url)
            .setMediaMetadata(metadata)
            .apply { stream.mimeType?.let { setMimeType(it) } }
            .build()
        setResolved(
            ResolvedPlayback(
                mediaItem = mediaItem,
                mediaSource = mediaSourceFor(mediaItem, stream)
            )
        )
        player.prepare()
        if (positionMs > 0L) player.seekTo(positionMs)
        player.setPlaybackSpeed(preferences.loadSpeed())
        if (playWhenReady) player.play() else player.pause()
        preferences.saveQueue(items, target)
        preferences.saveSession(media, positionMs, playWhenReady)
        ensureNext(items, target)
    }

    private fun warmAlternateStream(media: MediaSummary) {
        if (!preferences.shouldPreloadOnCurrentNetwork()) return
        if (PlaybackSourceResolver.resolve(media.id) !is PlaybackSource.YouTube) return
        val requestGeneration = generation
        val targetVideoMode = !preferences.loadVideoMode()
        scope.launch {
            val selected = runCatching {
                val streams = withContext(Dispatchers.IO) {
                    if (targetVideoMode) source.videoStreams(media.id) else source.audioStreams(media.id)
                }
                if (targetVideoMode) VideoStreamSelector.select(streams, preferences.effectiveVideoQuality())
                else AudioStreamSelector.select(streams, preferences.effectiveAudioQuality())
            }.getOrNull() ?: return@launch
            if (requestGeneration != generation) return@launch
            if (player.currentMediaItem?.mediaId != media.id) return@launch
            alternateStreamId = media.id
            alternateStreamVideoMode = targetVideoMode
            alternateStream = selected
        }
    }

    private suspend fun resolveWithRetry(
        media: MediaSummary,
        fastStart: Boolean = false,
        initialPositionMs: Long = 0L
    ): Result<ResolvedPlayback> {
        var lastError: Throwable? = null
        repeat(MAX_RESOLVE_ATTEMPTS) { attempt ->
            val result = resolve(media, fastStart, initialPositionMs)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            if (attempt < MAX_RESOLVE_ATTEMPTS - 1) {
                delay((attempt + 1) * 500L)
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Unable to resolve media"))
    }

    private suspend fun resolve(
        media: MediaSummary,
        fastStart: Boolean = false,
        initialPositionMs: Long = 0L
    ): Result<ResolvedPlayback> = runCatching {
        preferences.networkPolicyBlockReason()?.let { reason ->
            error(reason)
        }

        if (movieProviders.canResolve(media.id)) {
            val moviePlayback = withContext(Dispatchers.IO) {
                movieProviders.resolve(media.id, media.title)
            }
            return@runCatching resolvedMoviePlayback(media, moviePlayback)
        }

        val playbackSource = PlaybackSourceResolver.resolve(media.id)
        if (playbackSource !is PlaybackSource.YouTube) {
            return@runCatching resolvedGenericPlayback(media, playbackSource)
        }

        val videoMode = preferences.loadVideoMode()

        if (videoMode) {
            val now = android.os.SystemClock.elapsedRealtime()
            val blockedUntil = sabrFailureUntilMs[media.id] ?: 0L
            if (blockedUntil <= now) {
                sabrFailureUntilMs.remove(media.id)
                val sabrAttempt = runCatching {
                    withContext(Dispatchers.IO) {
                        val spec = source.sabrSpec(
                            media.id,
                            preferences.effectiveVideoQuality()
                        ) ?: error("SABR spec unavailable")
                        resolvedSabrPlayback(media, spec, initialPositionMs)
                    }
                }
                val sabr = sabrAttempt.getOrNull()
                if (sabr != null) {
                    lastSabrFallbackReason = null
                    return@runCatching sabr
                }
                lastSabrFallbackReason =
                    sabrAttempt.exceptionOrNull()?.message ?: "SABR unavailable"
            } else {
                lastSabrFallbackReason = "temporary SABR cooldown"
            }
        }

        val streams = withContext(Dispatchers.IO) {
            if (videoMode) source.videoStreams(media.id) else source.audioStreams(media.id)
        }
        val stream = if (videoMode) {
            val quality = preferences.effectiveVideoQuality()
            if (fastStart) {
                VideoStreamSelector.selectFastStart(streams, quality)
            } else {
                VideoStreamSelector.select(streams, quality)
            }
        } else {
            AudioStreamSelector.select(streams, preferences.effectiveAudioQuality())
        } ?: error(if (videoMode) "No playable video stream" else "No playable audio stream")
        resolvedPlayback(media, stream)
    }
}
