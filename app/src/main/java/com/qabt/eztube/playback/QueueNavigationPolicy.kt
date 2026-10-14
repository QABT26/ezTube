package com.qabt.eztube.playback

/**
 * Pure queue navigation policy shared by runtime code and JVM tests.
 * repeatMode follows PlaybackPreferences: 0=off, 1=one, 2=all.
 */
internal object QueueNavigationPolicy {
    fun manualTarget(current: Int, size: Int, delta: Int, repeatMode: Int): Int? {
        if (size <= 0 || current !in 0 until size || delta == 0) return null

        val target = if (repeatMode == 2) {
            // A coalesced delta represents repeated manual taps. Preserve every tap
            // across queue boundaries instead of collapsing any overflow to only 0/last.
            Math.floorMod(current + delta, size)
        } else {
            // Without Repeat All, repeated taps stop at the queue edge.
            (current + delta).coerceIn(0, size - 1)
        }

        return target.takeIf { it != current }
    }

    fun endedTarget(current: Int, size: Int, autoplay: Boolean, repeatMode: Int): Int? {
        if (!autoplay || size <= 0 || current !in 0 until size) return null
        return when {
            current < size - 1 -> current + 1
            repeatMode == 2 -> 0
            else -> null
        }
    }
}
