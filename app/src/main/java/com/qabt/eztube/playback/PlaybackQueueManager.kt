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

    fun onTransition(mediaItem: MediaItem?) {
        val id = mediaItem?.mediaId ?: return
        val saved = preferences.loadQueue() ?: return
        val index = saved.first.indexOfFirst { it.id == id }
        if (index < 0) return
        preferences.saveQueue(saved.first, index)
        preferences.save(saved.first[index], 0L)
        ensureNext(saved.first, index)
    }

    fun onPlaybackEnded() {
        if (busy || !preferences.loadAutoplay()) return
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        if (items.isEmpty()) return
        val currentId = player.currentMediaItem?.mediaId
        val current = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        val repeat = preferences.loadRepeatMode()
        val target = when {
            repeat == 1 -> current
            current < items.lastIndex -> current + 1
            repeat == 2 -> 0
            else -> return
        }
        resolveAndPlay(items, target)
    }

    fun refreshFromPreferences() {
        val saved = preferences.loadQueue() ?: return
        val currentId = player.currentMediaItem?.mediaId
        val index = saved.first.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        if (index in saved.first.indices) ensureNext(saved.first, index)
    }

    fun move(delta: Int) {
        if (busy) return
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        val currentId = player.currentMediaItem?.mediaId
        val current = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        val target = current + delta
        if (target !in items.indices) return

        if (delta > 0 && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            return
        }
        if (delta < 0 && player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            return
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
        scope.launch {
            resolve(next).onSuccess { item ->
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

    private fun resolveAndPlay(items: List<MediaSummary>, target: Int) {
        busy = true
        scope.launch {
            val media = items[target]
            resolve(media).onSuccess { item ->
                player.setMediaItem(item)
                player.prepare()
                player.setPlaybackSpeed(preferences.loadSpeed())
                player.play()
                preferences.saveQueue(items, target)
                preferences.save(media, 0L)
                ensureNext(items, target)
            }
            busy = false
        }
    }

    private suspend fun resolve(media: MediaSummary): Result<MediaItem> = runCatching {
        val streams = withContext(Dispatchers.IO) { source.audioStreams(media.id) }
        val stream = AudioStreamSelector.select(streams, preferences.loadQuality())
            ?: error("No playable audio stream")
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
