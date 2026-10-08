package com.qabt.eztube.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.NewPipeYouTubeSource
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
    private val scope: CoroutineScope
) {
    private data class ResolvedPlayback(
        val mediaItem: MediaItem,
        val mediaSource: MediaSource?
    )

    private val mediaSourceFactory by lazy {
        PlaybackMediaSourceFactory(context)
    }
    companion object {
        private const val MAX_SOURCE_RECOVERY_ATTEMPTS = 3
        private const val MAX_RESOLVE_ATTEMPTS = 3
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
    private val sabrFailedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

    private fun resolvedSabrPlayback(
        media: MediaSummary,
        spec: com.qabt.eztube.playback.sabr.SabrSourceSpec
    ): ResolvedPlayback {
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .setExtras(android.os.Bundle().apply {
                putBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, false)
                putInt(PlaybackService.EXTRA_VIDEO_HEIGHT, 0)
                putString(PlaybackService.EXTRA_PLAYBACK_ENGINE, PlaybackService.ENGINE_SABR)
            })
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri("sabr://" + spec.videoId)
            .setMediaMetadata(metadata)
            .build()
        return ResolvedPlayback(
            mediaItem = mediaItem,
            mediaSource = SabrMediaSourceFactory.create(mediaItem, spec, 0L)
        )
    }

    private fun setResolved(playback: ResolvedPlayback) {
        val engine = playback.mediaItem.mediaMetadata.extras
            ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
        if (engine == PlaybackService.ENGINE_SABR) {
            applySabrVideoQualityConstraint()
        }

        if (playback.mediaSource != null) {
            player.setMediaSource(playback.mediaSource)
        } else {
            player.setMediaItem(playback.mediaItem)
        }
    }

    private fun applySabrVideoQualityConstraint() {
        val maxHeight = preferences.loadVideoQuality().targetHeight ?: Int.MAX_VALUE
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            .build()
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
        ensureNext(saved.first, index)
        warmAlternateStream(saved.first[index])
    }

    fun onPlaybackEnded() {
        if (busy) return
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        if (items.isEmpty()) return
        val currentId = player.currentMediaItem?.mediaId
        val current = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        val target = QueueNavigationPolicy.endedTarget(
            current = current,
            size = items.size,
            autoplay = preferences.loadAutoplay(),
            repeatMode = preferences.loadRepeatMode()
        ) ?: return
        resolveAndPlay(items, target)
    }

    fun refreshFromPreferences() {
        invalidatePending()
        val saved = preferences.loadQueue() ?: return
        val currentId = player.currentMediaItem?.mediaId
        val index = saved.first.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second

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
        val index = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        if (index !in items.indices) return

        if (recoveryMediaId != currentId) {
            recoveryMediaId = currentId
            recoveryAttempts = 0
        }
        if (recoveryAttempts >= MAX_SOURCE_RECOVERY_ATTEMPTS) return

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady
        val currentEngine = player.currentMediaItem?.mediaMetadata?.extras
            ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
        val isSabr = currentEngine == PlaybackService.ENGINE_SABR

        // A SABR PlayerError is terminal only after SabrLoadErrorHandlingPolicy has already
        // retried the failing segment in-place. Do not recreate SABR and visibly reload it again;
        // blacklist SABR for this item and perform one direct-stream fallback recovery.
        if (isSabr) {
            sabrFailedIds.add(currentId)
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
        if (busy || delta == 0) return
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

        // Native adjacent items are safe only when they match the logical target.
        // Queue edits can otherwise leave a stale resolved item in Media3.
        if (delta > 0 && target == current + 1 && player.hasNextMediaItem()) {
            val nativeNext = player.getMediaItemAt(player.currentMediaItemIndex + 1)
            if (nativeNext.mediaId == items[target].id) {
                player.seekToNextMediaItem()
                return
            }
        }
        if (delta < 0 && target == current - 1 && player.hasPreviousMediaItem()) {
            val nativePrevious = player.getMediaItemAt(player.currentMediaItemIndex - 1)
            if (nativePrevious.mediaId == items[target].id) {
                player.seekToPreviousMediaItem()
                return
            }
        }
        resolveAndPlay(items, target)
    }

    fun ensureNext(items: List<MediaSummary>, index: Int) {
        if (!preferences.loadAutoplay()) return
        val next = items.getOrNull(index + 1) ?: return
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
        scope.launch {
            val media = items[target]
            val useFastStart = allowFastStart && preferences.loadVideoMode() && playWhenReady
            val resolved = resolveWithRetry(media, fastStart = useFastStart)
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
        }
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
                preferences.loadVideoQuality()
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
        if (PlaybackSourceResolver.resolve(media.id) !is PlaybackSource.YouTube) return
        val requestGeneration = generation
        val targetVideoMode = !preferences.loadVideoMode()
        scope.launch {
            val selected = runCatching {
                val streams = withContext(Dispatchers.IO) {
                    if (targetVideoMode) source.videoStreams(media.id) else source.audioStreams(media.id)
                }
                if (targetVideoMode) VideoStreamSelector.select(streams, preferences.loadVideoQuality())
                else AudioStreamSelector.select(streams, preferences.loadQuality())
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
        fastStart: Boolean = false
    ): Result<ResolvedPlayback> {
        var lastError: Throwable? = null
        repeat(MAX_RESOLVE_ATTEMPTS) { attempt ->
            val result = resolve(media, fastStart)
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
        fastStart: Boolean = false
    ): Result<ResolvedPlayback> = runCatching {
        val playbackSource = PlaybackSourceResolver.resolve(media.id)
        if (playbackSource !is PlaybackSource.YouTube) {
            return@runCatching resolvedGenericPlayback(media, playbackSource)
        }

        val videoMode = preferences.loadVideoMode()

        if (videoMode && !sabrFailedIds.contains(media.id)) {
            val sabr = runCatching {
                withContext(Dispatchers.IO) {
                    val spec = source.sabrSpec(
                        media.id,
                        preferences.loadVideoQuality()
                    ) ?: return@withContext null
                    resolvedSabrPlayback(media, spec)
                }
            }.getOrNull()
            if (sabr != null) {
                return@runCatching sabr
            }
        }

        val streams = withContext(Dispatchers.IO) {
            if (videoMode) source.videoStreams(media.id) else source.audioStreams(media.id)
        }
        val stream = if (videoMode) {
            val quality = preferences.loadVideoQuality()
            if (fastStart) {
                VideoStreamSelector.selectFastStart(streams, quality)
            } else {
                VideoStreamSelector.select(streams, quality)
            }
        } else {
            AudioStreamSelector.select(streams, preferences.loadQuality())
        } ?: error(if (videoMode) "No playable video stream" else "No playable audio stream")
        resolvedPlayback(media, stream)
    }
}
