package com.qabt.eztube.playback

import android.media.AudioFocusRequest
import android.media.AudioManager
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
import com.qabt.eztube.movie.MovieProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        const val COMMAND_PLAY_CURRENT = "com.qabt.eztube.PLAY_CURRENT"
        const val COMMAND_SET_VIDEO_QUALITY = "com.qabt.eztube.SET_VIDEO_QUALITY"
        const val COMMAND_GET_SABR_DIAGNOSTIC = "com.qabt.eztube.GET_SABR_DIAGNOSTIC"
        const val COMMAND_SET_SLEEP_END_CURRENT = "com.qabt.eztube.SET_SLEEP_END_CURRENT"
        const val COMMAND_SET_AUDIO_BALANCE = "com.qabt.eztube.SET_AUDIO_BALANCE"
        const val COMMAND_MOVE_QUEUE = "com.qabt.eztube.MOVE_QUEUE"
        const val COMMAND_SYSTEM_PREVIOUS = "com.qabt.eztube.SYSTEM_PREVIOUS"
        const val COMMAND_SYSTEM_NEXT = "com.qabt.eztube.SYSTEM_NEXT"
        const val ARG_ENABLED = "enabled"
        const val ARG_AUDIO_BALANCE_MODE = "audio_balance_mode"
        const val ARG_DELTA = "delta"
        const val ARG_POSITION_MS = "position_ms"
        const val ARG_PLAY_WHEN_READY = "play_when_ready"
        const val ARG_ERROR_MESSAGE = "error_message"
        const val EXTRA_COMPATIBILITY_FALLBACK = "compatibility_fallback"
        const val EXTRA_VIDEO_HEIGHT = "video_height"
        const val EXTRA_PLAYBACK_ENGINE = "playback_engine"
        const val EXTRA_IS_MOVIE = "is_movie"
        const val EXTRA_MOVIE_DIAGNOSTIC = "movie_diagnostic"
        const val EXTRA_SABR_DIAGNOSTIC = "sabr_diagnostic"
        const val ENGINE_SABR = "sabr"
        const val ENGINE_HLS = "hls"
        const val ENGINE_DIRECT = "direct"
    }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: PlaybackPreferences
    private lateinit var networkUsageTracker: NetworkUsageTracker
    private val source by lazy { NewPipeYouTubeSource() }
    private val movieProviders by lazy { MovieProviderRegistry.default() }
    private lateinit var queueManager: PlaybackQueueManager
    private val audioBalanceController = AudioBalanceController()
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var resumeAfterTransientAudioFocusLoss = false
    private var handlingAudioFocusChange = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val activePlayer = player
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterTransientAudioFocusLoss && activePlayer != null) {
                    resumeAfterTransientAudioFocusLoss = false
                    handlingAudioFocusChange = true
                    activePlayer.play()
                    handlingAudioFocusChange = false
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (
                    activePlayer != null &&
                    activePlayer.playWhenReady &&
                    activePlayer.mediaItemCount > 0 &&
                    activePlayer.playbackState != Player.STATE_ENDED
                ) {
                    resumeAfterTransientAudioFocusLoss = true
                    handlingAudioFocusChange = true
                    activePlayer.pause()
                    handlingAudioFocusChange = false
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientAudioFocusLoss = false
                if (activePlayer?.playWhenReady == true) {
                    handlingAudioFocusChange = true
                    activePlayer.pause()
                    handlingAudioFocusChange = false
                }
                if (::audioManager.isInitialized && ::audioFocusRequest.isInitialized) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = PlaybackPreferences(this)
        networkUsageTracker = NetworkUsageTracker(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        serviceScope.launch {
            while (true) {
                networkUsageTracker.sample()
                if (
                    preferences.networkPolicyBlockReason() != null &&
                    player?.isPlaying == true
                ) {
                    player?.pause()
                }
                delay(5_000L)
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                // PlaybackService owns audio focus explicitly so transient interruptions such as
                // phone calls can resume only when playback was active before the interruption.
                setAudioAttributes(audioAttributes, false)
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        queueManager.onTransition(mediaItem)
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        audioBalanceController.reapply(audioSessionId)
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                queueManager.onTracksChanged()
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
                        if (!handlingAudioFocusChange) {
                            if (playWhenReady) {
                                val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
                                if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                    resumeAfterTransientAudioFocusLoss = false
                                    handlingAudioFocusChange = true
                                    pause()
                                    handlingAudioFocusChange = false
                                }
                            } else if (!resumeAfterTransientAudioFocusLoss) {
                                audioManager.abandonAudioFocusRequest(audioFocusRequest)
                            }
                        }
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

        queueManager = PlaybackQueueManager(
            this,
            requireNotNull(player),
            preferences,
            source,
            movieProviders,
            serviceScope
        )
        audioBalanceController.apply(
            requireNotNull(player).audioSessionId,
            preferences.loadAudioBalanceMode()
        )
        // Do not resolve YouTube on service/app startup. UI restores logical media/position
        // from PlaybackPreferences; the network source is resolved only when playback starts.

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
                        .add(androidx.media3.session.SessionCommand(COMMAND_PLAY_CURRENT, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_SET_VIDEO_QUALITY, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_GET_SABR_DIAGNOSTIC, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_SET_SLEEP_END_CURRENT, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_SET_AUDIO_BALANCE, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_MOVE_QUEUE, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_SYSTEM_PREVIOUS, android.os.Bundle.EMPTY))
                        .add(androidx.media3.session.SessionCommand(COMMAND_SYSTEM_NEXT, android.os.Bundle.EMPTY))
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

                    // Historical lockscreen fix (30d1fd6) kept Next visible by ensuring
                    // a real next Media3 timeline item. That worked while queue-next was always
                    // preloaded, but it becomes incorrect once users can disable autoplay/preload
                    // or mobile-data prefetch: manual Next must remain available even when no
                    // native next item is intentionally resolved. Keep system transport buttons
                    // independent from the resolved timeline and route them to the logical queue
                    // owner instead.
                    val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setDisplayName("Previous")
                        .setSessionCommand(
                            androidx.media3.session.SessionCommand(
                                COMMAND_SYSTEM_PREVIOUS,
                                android.os.Bundle.EMPTY
                            )
                        )
                        .setSlots(CommandButton.SLOT_BACK)
                        .build()
                    val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setDisplayName("Next")
                        .setSessionCommand(
                            androidx.media3.session.SessionCommand(
                                COMMAND_SYSTEM_NEXT,
                                android.os.Bundle.EMPTY
                            )
                        )
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
                    if (customCommand.customAction == COMMAND_PLAY_CURRENT) {
                        val future = com.google.common.util.concurrent.SettableFuture.create<SessionResult>()
                        queueManager.playSavedCurrent(
                            positionMs = args.getLong(ARG_POSITION_MS, 0L),
                            playWhenReady = args.getBoolean(ARG_PLAY_WHEN_READY, true)
                        ) { result ->
                            if (result.isSuccess) {
                                future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                            } else {
                                val extras = android.os.Bundle().apply {
                                    putString(
                                        ARG_ERROR_MESSAGE,
                                        result.exceptionOrNull()?.message ?: "Unable to resolve media"
                                    )
                                }
                                future.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN, extras))
                            }
                        }
                        return future
                    }
                    if (customCommand.customAction == COMMAND_SET_VIDEO_QUALITY) {
                        val engine = queueManager.applyVideoQualityChange(
                            positionMs = args.getLong(ARG_POSITION_MS, 0L),
                            playWhenReady = args.getBoolean(ARG_PLAY_WHEN_READY, true)
                        )
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(
                                SessionResult.RESULT_SUCCESS,
                                android.os.Bundle().apply {
                                    putString(EXTRA_PLAYBACK_ENGINE, engine)
                                }
                            )
                        )
                    }
                    if (customCommand.customAction == COMMAND_SET_SLEEP_END_CURRENT) {
                        queueManager.setSleepEndCurrent(args.getBoolean(ARG_ENABLED, false))
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction == COMMAND_SET_AUDIO_BALANCE) {
                        val mode = runCatching {
                            AudioBalanceMode.valueOf(
                                args.getString(
                                    ARG_AUDIO_BALANCE_MODE,
                                    AudioBalanceMode.OFF.name
                                ).orEmpty()
                            )
                        }.getOrDefault(AudioBalanceMode.OFF)
                        preferences.saveAudioBalanceMode(mode)
                        audioBalanceController.apply(requireNotNull(player).audioSessionId, mode)
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction == COMMAND_MOVE_QUEUE) {
                        queueManager.move(args.getInt(ARG_DELTA, 0))
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction == COMMAND_SYSTEM_PREVIOUS) {
                        queueManager.move(-1)
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction == COMMAND_SYSTEM_NEXT) {
                        queueManager.move(1)
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction == COMMAND_GET_SABR_DIAGNOSTIC) {
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(
                                SessionResult.RESULT_SUCCESS,
                                android.os.Bundle().apply {
                                    putString(
                                        EXTRA_SABR_DIAGNOSTIC,
                                        queueManager.sabrDiagnosticSnapshot()
                                    )
                                }
                            )
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
        audioBalanceController.release()
        resumeAfterTransientAudioFocusLoss = false
        if (::audioManager.isInitialized && ::audioFocusRequest.isInitialized) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
        serviceScope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
