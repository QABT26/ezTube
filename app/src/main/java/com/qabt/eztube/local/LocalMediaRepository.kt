package com.qabt.eztube.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.qabt.eztube.youtube.MediaSummary

enum class LocalMediaKind { VIDEO, AUDIO }

class LocalMediaRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun load(kind: LocalMediaKind): List<MediaSummary> {
        val collection = when (kind) {
            LocalMediaKind.VIDEO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            }
            LocalMediaKind.AUDIO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
            }
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = if (kind == LocalMediaKind.AUDIO) {
            MediaStore.Audio.Media.IS_MUSIC + " != 0"
        } else {
            null
        }

        val result = mutableListOf<MediaSummary>()
        resolver.query(
            collection,
            projection,
            selection,
            null,
            MediaStore.MediaColumns.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex).orEmpty()
                val title = cursor.getString(titleIndex)
                    ?.takeIf { it.isNotBlank() }
                    ?: displayName.ifBlank { "Local media" }
                val durationMs = cursor.getLong(durationIndex).coerceAtLeast(0L)
                val uri = ContentUris.withAppendedId(collection, id)

                result += MediaSummary(
                    id = uri.toString(),
                    title = title,
                    channel = if (kind == LocalMediaKind.VIDEO) "Local video" else "Local audio",
                    thumbnailUrl = null,
                    durationSeconds = durationMs / 1000L
                )
            }
        }
        return result
    }
}
