package com.qabt.eztube.youtube

import com.qabt.eztube.playback.AudioStream

data class MediaSummary(
    val id: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val channelUrl: String? = null
)

data class ChannelSummary(
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCount: Long,
    val videos: List<MediaSummary>
)

interface YouTubeSource {
    suspend fun search(query: String): List<MediaSummary>
    suspend fun audioStreams(mediaId: String): List<AudioStream>
    suspend fun channel(channelUrl: String): ChannelSummary
}
