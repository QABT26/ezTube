package com.qabt.eztube.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaNotification
import com.google.common.collect.ImmutableList
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionResult

@UnstableApi
class PlaybackService : MediaSessionService() {
    companion object {
        const val COMMAND_QUEUE_CHANGED = "com.qabt.eztube.QUEUE_CHANGED"
        const val COMMAND_RELOAD_CURRENT = "com.qabt.eztube.RELOAD_CURRENT"
        const val ARG_POSITION_MS = "position_ms"
        const val ARG_PLAY_WHEN_READY = "play_when_ready"
    }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: PlaybackPreferences
    private val source by lazy { NewPipeYouTubeSource() }
    private lateinit var queueManager: PlaybackQueueManager

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
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        queueManager.onTransition(mediaItem)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) queueManager.onPlaybackEnded()
                        if (playbackState == Player.STATE_READY) queueManager.onPlaybackHealthy()
                        queueManager.checkpointSession()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        queueManager.checkpointSession()
                        queueManager.recoverSourceError()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        queueManager.checkpointSession()
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        queueManager.checkpointSession()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        queueManager.checkpointSession()
                    }
                })
            }

        queueManager = PlaybackQueueManager(requireNotNull(player), preferences, source, serviceScope)
        queueManager.restoreSession()

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
                        .buildUpon()
                        .add(androidx.media3.session.SessionCommand(COMMAND_QUEUE_CHANGED, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_RELOAD_CURRENT, android.os.Bundle.EMPTY))
                        .build()
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

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle
                ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
                    if (customCommand.customAction == COMMAND_QUEUE_CHANGED) {
                        queueManager.refreshFromPreferences()
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction == COMMAND_RELOAD_CURRENT) {
                        queueManager.reloadCurrent(
                            positionMs = args.getLong(ARG_POSITION_MS, 0L),
                            playWhenReady = args.getBoolean(ARG_PLAY_WHEN_READY, true)
                        )
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    return when (playerCommand) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                            queueManager.move(1)
                            // PlaybackQueueManager owns the logical queue. Reject the native
                            // timeline mutation after dispatch so Media3 cannot advance twice.
                            SessionResult.RESULT_ERROR_NOT_SUPPORTED
                        }
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                            queueManager.move(-1)
                            SessionResult.RESULT_ERROR_NOT_SUPPORTED
                        }
                        else -> SessionResult.RESULT_SUCCESS
                    }
                }
            })
            .build()
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
        if (::queueManager.isInitialized) queueManager.checkpointSession()
        SystemTransportBridge.clear()
        serviceScope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
