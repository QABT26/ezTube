package com.qabt.eztube.playback

/**
 * Pure queue navigation policy shared by runtime code and JVM tests.
 * repeatMode follows PlaybackPreferences: 0=off, 1=one, 2=all.
 */
internal object QueueNavigationPolicy {
    fun manualTarget(current: Int, size: Int, delta: Int, repeatMode: Int): Int? {
        if (size <= 0 || current !in 0 until size || delta == 0) return null
        val raw = current + delta
        return when {
            raw in 0 until size -> raw
            repeatMode == 2 && raw >= size -> 0
            repeatMode == 2 && raw < 0 -> size - 1
            else -> null
        }
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
