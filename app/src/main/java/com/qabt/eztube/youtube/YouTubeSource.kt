package com.qabt.eztube.youtube

import com.qabt.eztube.playback.AudioStream

data class MediaSummary(
    val id: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val channelUrl: String? = null,
    val viewCount: Long = -1,
    val uploadDateText: String? = null,
    val durationSeconds: Long = -1,
    val thumbnailBadge: String? = null,
    val isSeries: Boolean? = null
)

data class CommentSummary(
    val id: String,
    val author: String,
    val authorAvatarUrl: String?,
    val text: String,
    val uploadDateText: String?,
    val likeCount: Int,
    val pinned: Boolean,
    val heartedByUploader: Boolean
)

data class CommentsPage(
    val comments: List<CommentSummary>,
    val totalCount: Int,
    val disabled: Boolean
)

data class PlaylistSummary(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val streamCount: Long
)

data class PlaylistDetail(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val uploaderName: String,
    val items: List<MediaSummary>
)

data class ChannelSummary(
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCount: Long,
    val videos: List<MediaSummary>,
    val playlists: List<PlaylistSummary>
)

interface YouTubeSource {
    suspend fun search(query: String): List<MediaSummary>
    suspend fun searchLive(query: String): List<MediaSummary>
    suspend fun searchShorts(query: String): List<MediaSummary>
    suspend fun comments(mediaId: String): CommentsPage
    suspend fun trending(topic: String = "Music", language: String = "Vietnamese"): List<MediaSummary>
    suspend fun audioStreams(mediaId: String): List<AudioStream>
    suspend fun videoStreams(mediaId: String): List<AudioStream>
    suspend fun channel(channelUrl: String): ChannelSummary
    suspend fun playlist(playlistUrl: String): PlaylistDetail
}
