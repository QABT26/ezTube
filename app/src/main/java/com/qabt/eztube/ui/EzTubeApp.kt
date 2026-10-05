package com.qabt.eztube.ui

import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.qabt.eztube.playback.AudioQuality
import com.qabt.eztube.playback.AudioStreamSelector
import com.qabt.eztube.playback.PlaybackService
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library") }

@Composable
fun EzTubeApp() {
    var selected by remember { mutableStateOf(Tab.SEARCH) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { NewPipeYouTubeSource() }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controllerFuture by remember { mutableStateOf<ListenableFuture<MediaController>?>(null) }
    var nowPlaying by remember { mutableStateOf<MediaSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            { runCatching { future.get() }.onSuccess { controller = it }.onFailure { error = it.message } },
            context.mainExecutor
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                Surface(shadowElevation = 1.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ezTube", style = MaterialTheme.typography.titleLarge)
                        Text("Audio first", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            bottomBar = {
                Column {
                    nowPlaying?.let { media ->
                        Surface(tonalElevation = 3.dp) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                Column(Modifier.weight(1f)) {
                                    Text(media.title, maxLines = 1)
                                    Text(media.channel, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selected == tab,
                                onClick = { selected = tab },
                                icon = {
                                    Icon(
                                        when (tab) {
                                            Tab.HOME -> Icons.Outlined.Home
                                            Tab.SEARCH -> Icons.Outlined.Search
                                            Tab.LIBRARY -> Icons.Outlined.LibraryMusic
                                        },
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            when (selected) {
                Tab.SEARCH -> SearchScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    error = error,
                    onPlay = { media ->
                        scope.launch {
                            error = null
                            runCatching {
                                val streams = withContext(Dispatchers.IO) { source.audioStreams(media.id) }
                                AudioStreamSelector.select(streams, AudioQuality.STANDARD)
                                    ?: error("No playable audio stream")
                            }.onSuccess { stream ->
                                controller?.apply {
                                    setMediaItem(MediaItem.fromUri(stream.url))
                                    prepare()
                                    play()
                                    nowPlaying = media
                                } ?: run { error = "Playback service is not ready yet" }
                            }.onFailure { error = it.message ?: "Unable to play this item" }
                        }
                    },
                    source = source
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(selected.label, style = MaterialTheme.typography.headlineMedium)
                    Text(if (selected == Tab.HOME) "Audio-first feed foundation." else "History, favorites and playlists will live here.")
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    modifier: Modifier,
    error: String?,
    onPlay: (MediaSummary) -> Unit,
    source: NewPipeYouTubeSource
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search YouTube") },
            trailingIcon = {
                IconButton(
                    enabled = query.isNotBlank() && !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            searchError = null
                            runCatching {
                                withContext(Dispatchers.IO) { source.search(query) }
                            }.onSuccess { results = it }
                                .onFailure { searchError = it.message ?: "Search failed" }
                            loading = false
                        }
                    }
                ) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
            }
        )

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        (searchError ?: error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(results, key = { it.id }) { media ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(media) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(media.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    if (media.channel.isNotBlank()) {
                        Text(media.channel, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
