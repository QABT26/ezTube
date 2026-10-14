package com.qabt.eztube.history

import android.content.Context
import com.qabt.eztube.youtube.MediaSummary
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PersonalPlaylist(
    val id: String,
    val name: String,
    val items: List<MediaSummary>,
    val updatedAt: Long
)

class PersonalPlaylistStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("personal_playlists", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<PersonalPlaylist> {
        val raw = prefs.getString(KEY_DATA, null) ?: return emptyList()
        return runCatching {
            val root = JSONArray(raw)
            buildList {
                for (i in 0 until root.length()) {
                    val obj = root.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    val itemsJson = obj.optJSONArray("items") ?: JSONArray()
                    val items = buildList {
                        for (j in 0 until itemsJson.length()) {
                            val item = itemsJson.optJSONObject(j) ?: continue
                            val mediaId = item.optString("id")
                            if (mediaId.isBlank()) continue
                            add(
                                MediaSummary(
                                    id = mediaId,
                                    title = item.optString("title"),
                                    channel = item.optString("channel"),
                                    thumbnailUrl = item.optString("thumbnail").ifBlank { null },
                                    channelUrl = item.optString("channelUrl").ifBlank { null },
                                    durationSeconds = item.optLong("durationSeconds", -1L),
                                    thumbnailBadge = item.optString("thumbnailBadge").ifBlank { null }
                                )
                            )
                        }
                    }
                    add(
                        PersonalPlaylist(
                            id = id,
                            name = obj.optString("name").ifBlank { "Playlist" },
                            items = items,
                            updatedAt = obj.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun create(name: String): List<PersonalPlaylist> {
        val normalized = name.trim().ifBlank { "Playlist" }
        val now = System.currentTimeMillis()
        val updated = listOf(
            PersonalPlaylist(
                id = UUID.randomUUID().toString(),
                name = normalized,
                items = emptyList(),
                updatedAt = now
            )
        ) + load()
        save(updated)
        return updated
    }

    @Synchronized
    fun rename(id: String, name: String): List<PersonalPlaylist> {
        val normalized = name.trim()
        if (normalized.isBlank()) return load()
        val now = System.currentTimeMillis()
        val updated = load().map {
            if (it.id == id) it.copy(name = normalized, updatedAt = now) else it
        }
        save(updated)
        return updated.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun delete(id: String): List<PersonalPlaylist> {
        val updated = load().filterNot { it.id == id }
        save(updated)
        return updated
    }

    @Synchronized
    fun add(id: String, media: MediaSummary): List<PersonalPlaylist> {
        val now = System.currentTimeMillis()
        val updated = load().map { playlist ->
            if (playlist.id != id) playlist else {
                val items = playlist.items.filterNot { it.id == media.id } + media
                playlist.copy(items = items, updatedAt = now)
            }
        }
        save(updated)
        return updated.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun remove(id: String, mediaId: String): List<PersonalPlaylist> {
        val now = System.currentTimeMillis()
        val updated = load().map { playlist ->
            if (playlist.id != id) playlist else {
                playlist.copy(
                    items = playlist.items.filterNot { it.id == mediaId },
                    updatedAt = now
                )
            }
        }
        save(updated)
        return updated.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun move(id: String, from: Int, to: Int): List<PersonalPlaylist> {
        val now = System.currentTimeMillis()
        val updated = load().map { playlist ->
            if (playlist.id != id || from !in playlist.items.indices || to !in playlist.items.indices) {
                playlist
            } else {
                val items = playlist.items.toMutableList()
                val item = items.removeAt(from)
                items.add(to, item)
                playlist.copy(items = items, updatedAt = now)
            }
        }
        save(updated)
        return updated.sortedByDescending { it.updatedAt }
    }

    private fun save(playlists: List<PersonalPlaylist>) {
        val root = JSONArray()
        playlists.forEach { playlist ->
            val items = JSONArray()
            playlist.items.forEach { media ->
                items.put(
                    JSONObject()
                        .put("id", media.id)
                        .put("title", media.title)
                        .put("channel", media.channel)
                        .put("thumbnail", media.thumbnailUrl ?: "")
                        .put("channelUrl", media.channelUrl ?: "")
                        .put("durationSeconds", media.durationSeconds)
                        .put("thumbnailBadge", media.thumbnailBadge ?: "")
                )
            }
            root.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("updatedAt", playlist.updatedAt)
                    .put("items", items)
            )
        }
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }

    private companion object {
        const val KEY_DATA = "playlists_v1"
    }
}
