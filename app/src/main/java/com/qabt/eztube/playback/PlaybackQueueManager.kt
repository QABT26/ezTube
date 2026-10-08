package com.qabt.eztube.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single owner for persisted logical queue + the small playable Media3 window.
 *
 * The logical queue can be large. Only adjacent direct YouTube stream URLs are resolved,
 * avoiding eager extraction and reducing stale URL risk.
 */
class PlaybackQueueManager(
    private val player: ExoPlayer,
    private val preferences: PlaybackPreferences,
    private val source: NewPipeYouTubeSource,
    private val scope: CoroutineScope
) {
    @Volatile private var busy = false
    @Volatile private var preloadId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var alternateStreamId: String? = null
    @Volatile private var alternateStreamVideoMode: Boolean? = null
    @Volatile private var alternateStream: AudioStream? = null

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
        resolveAndPlay(items, index, positionMs.coerceAtLeast(0L), playWhenReady)
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
            resolve(next).onSuccess { item ->
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
                    player.addMediaItem(item)
                }
            }
            if (preloadId == next.id) preloadId = null
        }
    }

    private fun resolveAndPlay(
        items: List<MediaSummary>,
        target: Int,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        if (target !in items.indices) return
        busy = true
        invalidatePending()
        val requestGeneration = generation
        scope.launch {
            val media = items[target]
            resolve(media).onSuccess { item ->
                if (requestGeneration != generation) return@onSuccess
                val latest = preferences.loadQueue()
                if (latest == null || latest.first.getOrNull(target)?.id != media.id) return@onSuccess
                player.setMediaItem(item)
                player.prepare()
                if (positionMs > 0L) player.seekTo(positionMs)
                player.setPlaybackSpeed(preferences.loadSpeed())
                if (playWhenReady) player.play() else player.pause()
                preferences.saveQueue(items, target)
                preferences.save(media, positionMs)
                clearAlternateStream()
                ensureNext(items, target)
                warmAlternateStream(media)
            }
            busy = false
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
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(media.id)
                .setUri(stream.url)
                .setMediaMetadata(metadata)
                .build()
        )
        player.prepare()
        if (positionMs > 0L) player.seekTo(positionMs)
        player.setPlaybackSpeed(preferences.loadSpeed())
        if (playWhenReady) player.play() else player.pause()
        preferences.saveQueue(items, target)
        preferences.save(media, positionMs)
        ensureNext(items, target)
    }

    private fun warmAlternateStream(media: MediaSummary) {
        val requestGeneration = generation
        val targetVideoMode = !preferences.loadVideoMode()
        scope.launch {
            val selected = runCatching {
                val streams = withContext(Dispatchers.IO) {
                    if (targetVideoMode) source.videoStreams(media.id) else source.audioStreams(media.id)
                }
                if (targetVideoMode) streams.firstOrNull()
                else AudioStreamSelector.select(streams, preferences.loadQuality())
            }.getOrNull() ?: return@launch
            if (requestGeneration != generation) return@launch
            if (player.currentMediaItem?.mediaId != media.id) return@launch
            alternateStreamId = media.id
            alternateStreamVideoMode = targetVideoMode
            alternateStream = selected
        }
    }

    private suspend fun resolve(media: MediaSummary): Result<MediaItem> = runCatching {
        val videoMode = preferences.loadVideoMode()
        val streams = withContext(Dispatchers.IO) {
            if (videoMode) source.videoStreams(media.id) else source.audioStreams(media.id)
        }
        val stream = if (videoMode) {
            streams.firstOrNull()
        } else {
            AudioStreamSelector.select(streams, preferences.loadQuality())
        } ?: error(if (videoMode) "No playable video stream" else "No playable audio stream")
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.channel)
            .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(stream.url)
            .setMediaMetadata(metadata)
            .build()
    }
}
