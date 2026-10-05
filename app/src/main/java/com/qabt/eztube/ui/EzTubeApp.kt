package com.qabt.eztube.ui

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.qabt.eztube.history.EzTubeDatabase
import com.qabt.eztube.history.HistoryEntry
import com.qabt.eztube.history.FavoriteEntry
import com.qabt.eztube.history.FavoriteRepository
import com.qabt.eztube.history.HistoryRepository
import com.qabt.eztube.history.toMediaSummary
import com.qabt.eztube.playback.AudioQuality
import com.qabt.eztube.playback.AudioStreamSelector
import com.qabt.eztube.playback.PlaybackService
import com.qabt.eztube.playback.PlaybackPreferences
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library") }

@Composable
fun EzTubeApp() {
    var selected by remember { mutableStateOf(Tab.SEARCH) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { NewPipeYouTubeSource() }
    val database = remember { EzTubeDatabase.get(context) }
    val history = remember { HistoryRepository(database.historyDao()) }
    val favoritesRepo = remember { FavoriteRepository(database.favoriteDao()) }
    val recent by history.recent.collectAsState(initial = emptyList())
    val favorites by favoritesRepo.all.collectAsState(initial = emptyList())
    val playbackPrefs = remember { PlaybackPreferences(context) }
    var queue by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var queueIndex by remember { mutableIntStateOf(-1) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var compatibilityFallback by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nowPlaying by remember { mutableStateOf<MediaSummary?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(AudioQuality.STANDARD) }
    var resolvingId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                controller = mediaController
                isPlaying = mediaController.isPlaying
                mediaController.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = state == Player.STATE_BUFFERING
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playerError = error.message ?: "Playback error"
                    }
                })
            }.onFailure { errorMessage = it.message ?: "Playback service unavailable" }
        }, context.mainExecutor)
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun playMedia(media: MediaSummary, startPositionMs: Long = 0L) {
        if (resolvingId != null) return
        scope.launch {
            resolvingId = media.id
            errorMessage = null
            runCatching {
                val streams = withContext(Dispatchers.IO) { source.audioStreams(media.id) }
                AudioStreamSelector.select(streams, quality) ?: error("No playable audio stream")
            }.onSuccess { stream ->
                compatibilityFallback = stream.isFallbackMuxed
                playerError = null
                controller?.apply {
                    val metadata = MediaMetadata.Builder()
                        .setTitle(media.title)
                        .setArtist(media.channel)
                        .apply { media.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
                        .build()
                    setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(media.id)
                            .setUri(stream.url)
                            .setMediaMetadata(metadata)
                            .build()
                    )
                    prepare()
                    if (startPositionMs > 0) seekTo(startPositionMs)
                    setPlaybackSpeed(playbackSpeed)
                    play()
                    nowPlaying = media
                    playbackPrefs.save(media, startPositionMs)
                    withContext(Dispatchers.IO) { history.record(media) }
                } ?: run { errorMessage = "Playback service is not ready yet" }
            }.onFailure { errorMessage = it.message ?: "Unable to play this item" }
            resolvingId = null
        }
    }

    LaunchedEffect(Unit) {
        playbackPrefs.load()?.let { (media, position) ->
            if (nowPlaying == null) {
                nowPlaying = media
                playMedia(media, position)
            }
        }
    }

    LaunchedEffect(sleepMinutes) {
        val minutes = sleepMinutes ?: return@LaunchedEffect
        delay(minutes * 60_000L)
        controller?.pause()
        sleepMinutes = null
    }

    MaterialTheme {
        if (showPlayer && nowPlaying != null) {
            FullPlayer(
                media = requireNotNull(nowPlaying),
                controller = controller,
                isPlaying = isPlaying,
                quality = quality,
                onQuality = { quality = it },
                playbackSpeed = playbackSpeed,
                onSpeed = {
                    playbackSpeed = it
                    controller?.setPlaybackSpeed(it)
                },
                compatibilityFallback = compatibilityFallback,
                isBuffering = isBuffering,
                playerError = playerError,
                sleepMinutes = sleepMinutes,
                onSleep = { sleepMinutes = it },
                hasPrevious = queueIndex > 0,
                hasNext = queueIndex >= 0 && queueIndex < queue.lastIndex,
                onPrevious = {
                    if (queueIndex > 0) {
                        queueIndex -= 1
                        playMedia(queue[queueIndex])
                    }
                },
                onNext = {
                    if (queueIndex >= 0 && queueIndex < queue.lastIndex) {
                        queueIndex += 1
                        playMedia(queue[queueIndex])
                    }
                },
                isFavorite = favorites.any { it.mediaId == nowPlaying?.id },
                onFavorite = {
                    val media = nowPlaying ?: return@FullPlayer
                    scope.launch(Dispatchers.IO) {
                        if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id)
                        else favoritesRepo.add(media)
                    }
                },
                onToggle = { togglePlayback() },
                onClose = { showPlayer = false }
            )
        } else {
            Scaffold(
                topBar = { AppHeader() },
                bottomBar = {
                    Column {
                        nowPlaying?.let { media ->
                            MiniPlayer(
                                media = media,
                                isPlaying = isPlaying,
                                onOpen = { showPlayer = true },
                                onToggle = { togglePlayback() }
                            )
                        }
                        NavigationBar(windowInsets = NavigationBarDefaults.windowInsets) {
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
                                            }, contentDescription = tab.label
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
                        source = source,
                        resolvingId = resolvingId,
                        playbackError = errorMessage,
                        onPlay = { media, resultQueue ->
                            queue = resultQueue
                            queueIndex = resultQueue.indexOfFirst { it.id == media.id }
                            playMedia(media)
                        }
                    )
                    Tab.HOME -> HomeScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        recent = recent.take(10),
                        favorites = favorites.take(10),
                        resolvingId = resolvingId,
                        onPlayRecent = { playMedia(it.toMediaSummary()) },
                        onPlayFavorite = { playMedia(it.toMediaSummary()) },
                        onSearch = { selected = Tab.SEARCH }
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        recent = recent,
                        favorites = favorites,
                        resolvingId = resolvingId,
                        onPlay = { playMedia(it.toMediaSummary()) },
                        onPlayFavorite = { playMedia(it.toMediaSummary()) },
                        onRemoveFavorite = { entry -> scope.launch(Dispatchers.IO) { favoritesRepo.remove(entry.mediaId) } },
                        onDelete = { entry -> scope.launch(Dispatchers.IO) { history.delete(entry.mediaId) } },
                        onClear = { scope.launch(Dispatchers.IO) { history.clear() } }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Surface(tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ez", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Tube", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("AUDIO ONLY", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SearchScreen(
    modifier: Modifier,
    source: NewPipeYouTubeSource,
    resolvingId: String?,
    playbackError: String?,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (query.isBlank() || loading) return
        scope.launch {
            loading = true
            searchError = null
            runCatching { withContext(Dispatchers.IO) { source.search(query) } }
                .onSuccess { results = it }
                .onFailure { searchError = it.message ?: "Search failed" }
            loading = false
        }
    }

    Column(modifier.padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, placeholder = { Text("Search songs, artists, podcasts…") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Outlined.Close, "Clear")
                }
            },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            shape = RoundedCornerShape(18.dp)
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        (searchError ?: playbackError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (results.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Search YouTube, play the audio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.id }) { media ->
                    SearchResult(media, resolvingId == media.id, resolvingId == null) { onPlay(media, results) }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(media: MediaSummary, resolving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(media.thumbnailUrl, null,
            Modifier.size(width = 116.dp, height = 66.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(media.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(media.channel.ifBlank { "YouTube" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        if (resolving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.PlayCircle, "Play")
    }
}

@Composable
private fun MiniPlayer(
    media: MediaSummary,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).clickable(onClick = onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(media.thumbnailUrl, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(media.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(media.channel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (isPlaying) "Pause" else "Play")
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    recent: List<HistoryEntry>,
    favorites: List<FavoriteEntry>,
    resolvingId: String?,
    onPlayRecent: (HistoryEntry) -> Unit,
    onPlayFavorite: (FavoriteEntry) -> Unit,
    onSearch: () -> Unit
) {
    LazyColumn(modifier.padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Listen without the video", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("Audio-first YouTube listening", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, null)
                Spacer(Modifier.width(6.dp))
                Text("Search YouTube")
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("Recently played", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(recent, key = { "home-r-" + it.mediaId }) { entry ->
                CompactMediaRow(entry.title, entry.channel, entry.thumbnailUrl,
                    resolvingId == entry.mediaId) { onPlayRecent(entry) }
            }
        }
        if (favorites.isNotEmpty()) {
            item { Text("Favorites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(favorites, key = { "home-f-" + it.mediaId }) { entry ->
                CompactMediaRow(entry.title, entry.channel, entry.thumbnailUrl,
                    resolvingId == entry.mediaId) { onPlayFavorite(entry) }
            }
        }
        if (recent.isEmpty() && favorites.isEmpty()) {
            item {
                Text("Play a few tracks and your listening shortcuts will appear here.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompactMediaRow(
    title: String,
    channel: String,
    thumbnailUrl: String?,
    resolving: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !resolving, onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(thumbnailUrl, null, Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)),
            contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(channel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (resolving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.PlayArrow, "Play")
    }
}

@Composable
private fun LibraryScreen(
    modifier: Modifier,
    recent: List<HistoryEntry>,
    favorites: List<FavoriteEntry>,
    resolvingId: String?,
    onPlay: (HistoryEntry) -> Unit,
    onPlayFavorite: (FavoriteEntry) -> Unit,
    onRemoveFavorite: (FavoriteEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier.padding(horizontal = 14.dp)) {
        if (favorites.isNotEmpty()) {
            Text("Favorites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(favorites, key = { "fav-" + it.mediaId }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = resolvingId == null) { onPlayFavorite(entry) }
                            .padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(entry.thumbnailUrl, null,
                            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.channel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = { onRemoveFavorite(entry) }) {
                            Icon(Icons.Outlined.Favorite, "Remove favorite")
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Recently played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${recent.size} items", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recent.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear all") }
            }
        }
        if (recent.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Songs you play will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(recent, key = { it.mediaId }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = resolvingId == null) { onPlay(entry) }
                            .padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            entry.thumbnailUrl, null,
                            Modifier.size(58.dp).clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text(entry.channel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (resolvingId == entry.mediaId) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { onDelete(entry) }) {
                                Icon(Icons.Outlined.Close, "Remove from history")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPlayer(
    media: MediaSummary,
    controller: MediaController?,
    isPlaying: Boolean,
    quality: AudioQuality,
    onQuality: (AudioQuality) -> Unit,
    playbackSpeed: Float,
    onSpeed: (Float) -> Unit,
    compatibilityFallback: Boolean,
    isBuffering: Boolean,
    playerError: String?,
    sleepMinutes: Int?,
    onSleep: (Int?) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller, media.id) {
        while (true) {
            position = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = controller?.duration?.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.KeyboardArrowDown, "Close player") }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                media.thumbnailUrl, null,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(18.dp))
            Text(media.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(media.channel, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (isFavorite) "Remove favorite" else "Add favorite"
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioQuality.entries.forEach { option ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = quality == option,
                        onClick = { onQuality(option) },
                        label = {
                            Text(when (option) {
                                AudioQuality.DATA_SAVER -> "Saver 64"
                                AudioQuality.STANDARD -> "Std 128"
                                AudioQuality.HIGH -> "High"
                            }, maxLines = 1)
                        }
                    )
                }
            }
            Text(
                if (compatibilityFallback) "Compatibility stream · may use more data"
                else "Audio-only · quality applies to next track",
                style = MaterialTheme.typography.labelSmall,
                color = if (compatibilityFallback) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isBuffering) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            playerError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(
                        selected = playbackSpeed == speed,
                        onClick = { onSpeed(speed) },
                        label = { Text("${speed}×") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null, 15, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = sleepMinutes == minutes,
                        onClick = { onSleep(minutes) },
                        label = { Text(minutes?.let { "${it}m" } ?: "Sleep off") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            val progress = if (duration > 0) {
                (position.toFloat() / duration).coerceIn(0f, 1f)
            } else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(duration) {
                        fun seek(x: Float) {
                            if (duration > 0) {
                                val fraction = (x / size.width).coerceIn(0f, 1f)
                                controller?.seekTo((duration * fraction).toLong())
                            }
                        }
                        detectDragGestures(
                            onDragStart = { seek(it.x) },
                            onDrag = { change, _ -> seek(change.position.x) }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious, enabled = hasPrevious) {
                    Icon(Icons.Outlined.SkipPrevious, "Previous")
                }
                FilledTonalIconButton(onClick = { controller?.seekBack() }) {
                    Icon(Icons.Outlined.Replay10, "Back 10 seconds")
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(68.dp)) {
                    Icon(if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (isPlaying) "Pause" else "Play", modifier = Modifier.size(34.dp))
                }
                FilledTonalIconButton(onClick = { controller?.seekForward() }) {
                    Icon(Icons.Outlined.Forward10, "Forward 10 seconds")
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Outlined.SkipNext, "Next")
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun EmptyPage(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
