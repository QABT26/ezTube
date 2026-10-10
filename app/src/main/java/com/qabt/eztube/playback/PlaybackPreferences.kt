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

    data class SavedSession(
        val media: MediaSummary,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val updatedAtMs: Long
    )

    fun load(): Pair<MediaSummary, Long>? = loadSession()?.let { it.media to it.positionMs }

    fun saveSession(media: MediaSummary, positionMs: Long, playWhenReady: Boolean) {
        save(media, positionMs)
        prefs.edit()
            .putBoolean("session_play_when_ready", playWhenReady)
            .putLong("session_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun loadSession(): SavedSession? {
        val id = prefs.getString("id", null) ?: return null
        return SavedSession(
            media = MediaSummary(
                id = id,
                title = prefs.getString("title", "").orEmpty(),
                channel = prefs.getString("channel", "").orEmpty(),
                thumbnailUrl = prefs.getString("thumbnail", null),
                channelUrl = prefs.getString("channel_url", null)
            ),
            positionMs = prefs.getLong("position", 0L).coerceAtLeast(0L),
            playWhenReady = prefs.getBoolean("session_play_when_ready", false),
            updatedAtMs = prefs.getLong("session_updated_at", 0L)
        )
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

    fun saveVideoMode(enabled: Boolean) {
        prefs.edit().putBoolean("video_mode", enabled).apply()
    }

    fun loadVideoMode(): Boolean = prefs.getBoolean("video_mode", false)

    fun saveQuality(quality: AudioQuality) {
        prefs.edit().putString("quality", quality.name).apply()
    }

    fun loadQuality(): AudioQuality =
        runCatching { AudioQuality.valueOf(prefs.getString("quality", AudioQuality.STANDARD.name).orEmpty()) }
            .getOrDefault(AudioQuality.STANDARD)

    fun saveVideoQuality(quality: VideoQuality) {
        prefs.edit().putString("video_quality", quality.name).apply()
    }

    fun loadVideoQuality(): VideoQuality =
        runCatching {
            VideoQuality.valueOf(
                prefs.getString("video_quality", VideoQuality.AUTO.name).orEmpty()
            )
        }.getOrDefault(VideoQuality.AUTO)

    fun saveSpeed(speed: Float) {
        prefs.edit().putFloat("speed", speed).apply()
    }

    fun loadSpeed(): Float = prefs.getFloat("speed", 1f).takeIf { it in 0.5f..2f } ?: 1f

    fun saveNextMode(mode: String) {
        prefs.edit().putString("next_mode", if (mode == "RECOMMENDED") "RECOMMENDED" else "LIST").apply()
    }

    fun loadNextMode(): String = prefs.getString("next_mode", "LIST") ?: "LIST"

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

    fun saveSearchSourceMode(mode: String) {
        prefs.edit().putString("search_source_mode", if (mode == "MOVIE") "MOVIE" else "YOUTUBE").apply()
    }

    fun loadSearchSourceMode(): String =
        prefs.getString("search_source_mode", "YOUTUBE") ?: "YOUTUBE"

    fun saveRecommendationMode(mode: String) {
        prefs.edit().putString("recommendation_mode", if (mode == "MOVIE") "MOVIE" else "YOUTUBE").apply()
    }

    fun loadRecommendationMode(): String =
        prefs.getString("recommendation_mode", "YOUTUBE") ?: "YOUTUBE"

    fun saveTrendingTopic(topic: String) { prefs.edit().putString("trending_topic", topic).apply() }
    fun loadTrendingTopic(): String = prefs.getString("trending_topic", "Music") ?: "Music"
    fun saveTrendingLanguage(language: String) { prefs.edit().putString("trending_language", language).apply() }
    fun loadTrendingLanguage(): String = prefs.getString("trending_language", "Vietnamese") ?: "Vietnamese"
}
