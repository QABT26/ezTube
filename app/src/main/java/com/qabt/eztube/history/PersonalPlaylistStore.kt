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
    val updatedAt: Long,
    val createdAt: Long = updatedAt,
    val pinned: Boolean = false
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
                        val updatedAt = obj.optLong("updatedAt", 0L)
                        PersonalPlaylist(
                            id = id,
                            name = obj.optString("name").ifBlank { "Playlist" },
                            items = items,
                            updatedAt = updatedAt,
                            createdAt = obj.optLong("createdAt", updatedAt),
                            pinned = obj.optBoolean("pinned", false)
                        )
                    )
                }
            }.sortedWith(
                compareByDescending<PersonalPlaylist> { it.pinned }
                    .thenByDescending { it.updatedAt }
            )
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
                updatedAt = now,
                createdAt = now,
                pinned = false
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
        return sort(updated)
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
        return sort(updated)
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
        return sort(updated)
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
        return sort(updated)
    }


    @Synchronized
    fun setPinned(id: String, pinned: Boolean): List<PersonalPlaylist> {
        val now = System.currentTimeMillis()
        val updated = load().map { playlist ->
            if (playlist.id == id) {
                playlist.copy(pinned = pinned, updatedAt = now)
            } else {
                playlist
            }
        }
        save(updated)
        return sort(updated)
    }

    @Synchronized
    fun duplicate(id: String): List<PersonalPlaylist> {
        val source = load().firstOrNull { it.id == id } ?: return load()
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = source.name + " copy",
            updatedAt = now,
            createdAt = now,
            pinned = false
        )
        val updated = listOf(copy) + load()
        save(updated)
        return sort(updated)
    }

    @Synchronized
    fun clearItems(id: String): List<PersonalPlaylist> {
        val now = System.currentTimeMillis()
        val updated = load().map { playlist ->
            if (playlist.id == id) {
                playlist.copy(items = emptyList(), updatedAt = now)
            } else {
                playlist
            }
        }
        save(updated)
        return sort(updated)
    }

    @Synchronized
    fun addMany(id: String, media: List<MediaSummary>): List<PersonalPlaylist> {
        if (media.isEmpty()) return load()
        val now = System.currentTimeMillis()
        val updated = load().map { playlist ->
            if (playlist.id != id) playlist else {
                val existing = playlist.items.associateBy { it.id }.toMutableMap()
                val order = playlist.items.map { it.id }.toMutableList()
                media.forEach { item ->
                    if (item.id !in existing) order += item.id
                    existing[item.id] = item
                }
                playlist.copy(
                    items = order.mapNotNull(existing::get),
                    updatedAt = now
                )
            }
        }
        save(updated)
        return sort(updated)
    }

    private fun sort(playlists: List<PersonalPlaylist>): List<PersonalPlaylist> =
        playlists.sortedWith(
            compareByDescending<PersonalPlaylist> { it.pinned }
                .thenByDescending { it.updatedAt }
        )

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
                    .put("createdAt", playlist.createdAt)
                    .put("pinned", playlist.pinned)
                    .put("items", items)
            )
        }
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }

    private companion object {
        const val KEY_DATA = "playlists_v1"
    }
}
