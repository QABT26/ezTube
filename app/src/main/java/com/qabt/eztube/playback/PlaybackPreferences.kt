package com.qabt.eztube.playback

import android.content.Context
import com.qabt.eztube.youtube.MediaSummary

class PlaybackPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    fun save(media: MediaSummary, positionMs: Long) {
        prefs.edit()
            .putString("id", media.id)
            .putString("title", media.title)
            .putString("channel", media.channel)
            .putString("thumbnail", media.thumbnailUrl)
            .putString("channel_url", media.channelUrl)
            .putLong("position", positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun load(): Pair<MediaSummary, Long>? {
        val id = prefs.getString("id", null) ?: return null
        return MediaSummary(
            id = id,
            title = prefs.getString("title", "").orEmpty(),
            channel = prefs.getString("channel", "").orEmpty(),
            thumbnailUrl = prefs.getString("thumbnail", null),
            channelUrl = prefs.getString("channel_url", null)
        ) to prefs.getLong("position", 0L)
    }

    fun saveQuality(quality: AudioQuality) {
        prefs.edit().putString("quality", quality.name).apply()
    }

    fun loadQuality(): AudioQuality =
        runCatching { AudioQuality.valueOf(prefs.getString("quality", AudioQuality.STANDARD.name).orEmpty()) }
            .getOrDefault(AudioQuality.STANDARD)

    fun saveSpeed(speed: Float) {
        prefs.edit().putFloat("speed", speed).apply()
    }

    fun loadSpeed(): Float = prefs.getFloat("speed", 1f).takeIf { it in 0.5f..2f } ?: 1f

    fun saveAutoplay(enabled: Boolean) {
        prefs.edit().putBoolean("autoplay", enabled).apply()
    }

    fun loadAutoplay(): Boolean = prefs.getBoolean("autoplay", true)

    fun saveRepeatMode(mode: Int) {
        prefs.edit().putInt("repeat_mode", mode.coerceIn(0, 2)).apply()
    }

    fun loadRepeatMode(): Int = prefs.getInt("repeat_mode", 0).coerceIn(0, 2)
}
