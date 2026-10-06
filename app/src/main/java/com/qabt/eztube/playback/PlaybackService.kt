package com.qabt.eztube.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import com.google.common.collect.ImmutableList
import android.net.Uri
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionResult

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: PlaybackPreferences
    private val source by lazy { NewPipeYouTubeSource() }
    @Volatile private var transportBusy = false

    override fun onCreate() {
        super.onCreate()
        preferences = PlaybackPreferences(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
            }

        setMediaNotificationProvider(object : MediaNotification.Provider {
            private val delegate = androidx.media3.session.DefaultMediaNotificationProvider(this@PlaybackService)
            override fun createNotification(mediaSession: MediaSession, customLayout: ImmutableList<CommandButton>, actionFactory: MediaNotification.ActionFactory, onNotificationChangedCallback: MediaNotification.Provider.Callback): MediaNotification =
                delegate.createNotification(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)
            override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle): Boolean =
                delegate.handleCustomCommand(session, action, extras)
        })

        session = MediaSession.Builder(this, requireNotNull(player))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ConnectionResult {
                    val sessionCommands = ConnectionResult.DEFAULT_SESSION_COMMANDS
                    val playerCommands = ConnectionResult.DEFAULT_PLAYER_COMMANDS
                        .buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()
                    val result = ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)

                    val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .setSlots(CommandButton.SLOT_BACK)
                        .build()
                    val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build()
                    result.setMediaButtonPreferences(listOf(previousButton, nextButton))
                    return result.build()
                }

                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    when (playerCommand) {
                        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> moveQueue(1)
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> moveQueue(-1)
                    }
                    return SessionResult.RESULT_SUCCESS
                }
            })
            .build()
    }

    private fun moveQueue(delta: Int) {
        if (transportBusy) return
        val saved = preferences.loadQueue() ?: return
        val items = saved.first
        val currentId = player?.currentMediaItem?.mediaId
        val current = items.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: saved.second
        val target = current + delta
        if (target !in items.indices) return
        transportBusy = true
        serviceScope.launch {
            val media = items[target]
            runCatching {
                val streams = withContext(Dispatchers.IO) { source.audioStreams(media.id) }
                AudioStreamSelector.select(streams, preferences.loadQuality()) ?: error("No playable audio stream")
            }.onSuccess { stream ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(media.title).setArtist(media.channel)
                    .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }.build()
                player?.apply {
                    setMediaItem(MediaItem.Builder().setMediaId(media.id).setUri(stream.url).setMediaMetadata(metadata).build())
                    prepare()
                    setPlaybackSpeed(preferences.loadSpeed())
                    play()
                }
                preferences.saveQueue(items, target)
                preferences.save(media, 0L)
            }
            transportBusy = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val activePlayer = player
        if (activePlayer == null ||
            !activePlayer.playWhenReady ||
            activePlayer.mediaItemCount == 0 ||
            activePlayer.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SystemTransportBridge.clear()
        serviceScope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
