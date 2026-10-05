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
            .putLong("position", positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun load(): Pair<MediaSummary, Long>? {
        val id = prefs.getString("id", null) ?: return null
        return MediaSummary(
            id = id,
            title = prefs.getString("title", "").orEmpty(),
            channel = prefs.getString("channel", "").orEmpty(),
            thumbnailUrl = prefs.getString("thumbnail", null)
        ) to prefs.getLong("position", 0L)
    }
}
