package com.qabt.eztube.playback

/**
 * Pure runtime playback rules that must stay identical between the UI and
 * PlaybackQueueManager. Keeping these decisions here prevents Compose state and
 * Media3 state from drifting apart during repeat/sleep/autoplay interactions.
 *
 * repeatMode follows PlaybackPreferences: 0=off, 1=one, 2=all.
 */
internal object PlaybackRuntimePolicy {
    fun allowPreload(autoplay: Boolean, sleepEndCurrent: Boolean): Boolean =
        autoplay && !sleepEndCurrent

    fun nativeRepeatOneEnabled(repeatMode: Int, sleepEndCurrent: Boolean): Boolean =
        repeatMode == 1 && !sleepEndCurrent
}
