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

    fun saveQueue(items: List<MediaSummary>, index: Int) {
        val encoded = items.joinToString("\u001e") { item ->
            listOf(item.id, item.title, item.channel, item.thumbnailUrl.orEmpty(), item.channelUrl.orEmpty())
                .joinToString("\u001f") { android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP) }
        }
        prefs.edit().putString("queue_v1", encoded).putInt("queue_index", index).apply()
    }

    fun loadQueue(): Pair<List<MediaSummary>, Int>? {
        val raw = prefs.getString("queue_v1", null) ?: return null
        val items = raw.split("\u001e").mapNotNull { row ->
            val p = row.split("\u001f")
            if (p.size < 5) return@mapNotNull null
            fun d(v: String) = runCatching { String(android.util.Base64.decode(v, android.util.Base64.NO_WRAP)) }.getOrDefault("")
            val id = d(p[0])
            if (id.isBlank()) null else MediaSummary(id, d(p[1]), d(p[2]), d(p[3]).ifBlank { null }, d(p[4]).ifBlank { null })
        }
        if (items.isEmpty()) return null
        return items to prefs.getInt("queue_index", 0).coerceIn(items.indices)
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

    fun saveSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val current = loadRecentSearches().filterNot { it.equals(normalized, ignoreCase = true) }
        prefs.edit().putString("recent_searches", (listOf(normalized) + current).take(5).joinToString("\n")).apply()
    }

    fun loadRecentSearches(): List<String> =
        prefs.getString("recent_searches", "").orEmpty().lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.take(5).toList()

    fun saveTrendingTopic(topic: String) { prefs.edit().putString("trending_topic", topic).apply() }
    fun loadTrendingTopic(): String = prefs.getString("trending_topic", "Music") ?: "Music"
    fun saveTrendingLanguage(language: String) { prefs.edit().putString("trending_language", language).apply() }
    fun loadTrendingLanguage(): String = prefs.getString("trending_language", "Vietnamese") ?: "Vietnamese"
}
