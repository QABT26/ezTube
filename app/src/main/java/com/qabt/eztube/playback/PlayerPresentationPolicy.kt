package com.qabt.eztube.playback

/**
 * Decides whether selecting media should immediately open the full Now Playing screen.
 *
 * YouTube follows the user's current AUDIO/VIDEO mode. Movie and local video are
 * video-first and always open the full player.
 */
internal object PlayerPresentationPolicy {
    fun shouldOpenFullPlayer(
        isMovie: Boolean,
        isLocalVideo: Boolean,
        videoMode: Boolean
    ): Boolean = isMovie || isLocalVideo || videoMode
}
