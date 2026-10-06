package com.qabt.eztube.playback

/**
 * In-process bridge for system media transport controls.
 *
 * Stream URLs are resolved lazily by the Compose playback layer, so the
 * MediaSession must not enqueue unresolved YouTube IDs as ExoPlayer URIs.
 */
object SystemTransportBridge {
    @Volatile
    var onNext: (() -> Unit)? = null

    @Volatile
    var onPrevious: (() -> Unit)? = null

    fun clear() {
        onNext = null
        onPrevious = null
    }
}
