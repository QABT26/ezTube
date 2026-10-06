package com.qabt.eztube.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionResult

class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

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
                        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> SystemTransportBridge.onNext?.invoke()
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> SystemTransportBridge.onPrevious?.invoke()
                    }
                    return SessionResult.RESULT_SUCCESS
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
        SystemTransportBridge.clear()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
