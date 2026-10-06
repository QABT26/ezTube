package com.qabt.eztube.ui

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import com.qabt.eztube.playback.SystemTransportBridge
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.ChannelSummary
import com.qabt.eztube.youtube.PlaylistSummary
import com.qabt.eztube.youtube.PlaylistDetail
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
    var playbackSpeed by remember { mutableFloatStateOf(playbackPrefs.loadSpeed()) }
    var compatibilityFallback by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nowPlaying by remember { mutableStateOf<MediaSummary?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(playbackPrefs.loadQuality()) }
    var autoplay by remember { mutableStateOf(playbackPrefs.loadAutoplay()) }
    var showSettings by remember { mutableStateOf(false) }
    var resolvingId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var playbackEndedToken by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var homeSuggestions by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var channelDetail by remember { mutableStateOf<ChannelSummary?>(null) }
    var channelLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var playlistDetail by remember { mutableStateOf<PlaylistDetail?>(null) }
    var playlistLoading by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                controller = mediaController
                isPlaying = mediaController.isPlaying
                mediaController.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playerError = error.message ?: "Playback error"
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_ENDED) playbackEndedToken += 1
                    }
                })
            }.onFailure { errorMessage = it.message ?: "Playback service unavailable" }
        }, context.mainExecutor)
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    fun openChannel(channelUrl: String?) {
        if (channelUrl.isNullOrBlank() || channelLoading) return
        scope.launch {
            channelLoading = true
            channelError = null
            runCatching { withContext(Dispatchers.IO) { source.channel(channelUrl) } }
                .onSuccess { channelDetail = it }
                .onFailure { channelError = it.message ?: "Unable to load channel" }
            channelLoading = false
        }
    }

    fun openPlaylist(playlistUrl: String) {
        if (playlistLoading) return
        scope.launch {
            playlistLoading = true
            playlistError = null
            runCatching { withContext(Dispatchers.IO) { source.playlist(playlistUrl) } }
                .onSuccess { playlistDetail = it }
                .onFailure { playlistError = it.message ?: "Unable to load playlist" }
            playlistLoading = false
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
                    resumePositionMs = 0L
                    playbackPrefs.save(media, startPositionMs)
                    withContext(Dispatchers.IO) { history.record(media) }
                } ?: run { errorMessage = "Playback service is not ready yet" }
            }.onFailure { errorMessage = it.message ?: "Unable to play this item" }
            resolvingId = null
        }
    }

    DisposableEffect(queue, queueIndex) {
        SystemTransportBridge.onPrevious = {
            scope.launch {
                if (queueIndex > 0) {
                    queueIndex -= 1
                    playMedia(queue[queueIndex])
                }
            }
        }
        SystemTransportBridge.onNext = {
            scope.launch {
                if (queueIndex >= 0 && queueIndex < queue.lastIndex) {
                    queueIndex += 1
                    playMedia(queue[queueIndex])
                }
            }
        }
        onDispose {
            SystemTransportBridge.clear()
        }
    }

    LaunchedEffect(playbackEndedToken) {
        if (autoplay && playbackEndedToken > 0 && queueIndex >= 0 && queueIndex < queue.lastIndex) {
            queueIndex += 1
            playMedia(queue[queueIndex])
        }
    }

    LaunchedEffect(Unit) {
        playbackPrefs.load()?.let { (media, position) ->
            if (nowPlaying == null) {
                nowPlaying = media
                resumePositionMs = position
            }
        }
    }

    LaunchedEffect(nowPlaying?.id, controller) {
        while (true) {
            delay(5_000)
            val media = nowPlaying ?: continue
            val position = controller?.currentPosition?.takeIf { it >= 0 } ?: resumePositionMs
            playbackPrefs.save(media, position)
        }
    }

    LaunchedEffect(sleepMinutes) {
        val minutes = sleepMinutes ?: return@LaunchedEffect
        delay(minutes * 60_000L)
        controller?.pause()
        sleepMinutes = null
    }

    LaunchedEffect(recent.firstOrNull()?.mediaId, favorites.firstOrNull()?.mediaId) {
        val seed = favorites.firstOrNull()?.channel?.takeIf { it.isNotBlank() }
            ?: recent.firstOrNull()?.channel?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        homeLoading = true
        runCatching { withContext(Dispatchers.IO) { source.search(seed) } }
            .onSuccess { items ->
                val played = recent.mapTo(mutableSetOf()) { it.mediaId }
                homeSuggestions = items.filterNot { it.id in played }.take(12)
            }
        homeLoading = false
    }

    MaterialTheme {
        if (playlistDetail != null || playlistLoading || playlistError != null) {
            PlaylistDetailScreen(
                playlist = playlistDetail,
                loading = playlistLoading,
                error = playlistError,
                resolvingId = resolvingId,
                nowPlayingId = nowPlaying?.id,
                onBack = {
                    playlistDetail = null
                    playlistError = null
                    playlistLoading = false
                },
                onPlay = { media, items ->
                    queue = items
                    queueIndex = items.indexOfFirst { it.id == media.id }
                    playMedia(media)
                },
                onPlayAll = { items ->
                    if (items.isNotEmpty()) {
                        queue = items
                        queueIndex = 0
                        playMedia(items.first())
                    }
                }
            )
        } else if (channelDetail != null || channelLoading || channelError != null) {
            ChannelScreen(
                channel = channelDetail,
                loading = channelLoading,
                error = channelError,
                resolvingId = resolvingId,
                onBack = {
                    channelDetail = null
                    channelError = null
                    channelLoading = false
                },
                onPlaylist = { openPlaylist(it.url) },
                onPlay = { media, items ->
                    queue = items
                    queueIndex = items.indexOfFirst { it.id == media.id }
                    playMedia(media)
                }
            )
        } else if (showSettings) {
            SettingsScreen(
                quality = quality,
                onQuality = { quality = it; playbackPrefs.saveQuality(it) },
                speed = playbackSpeed,
                onSpeed = { playbackSpeed = it; playbackPrefs.saveSpeed(it); controller?.setPlaybackSpeed(it) },
                autoplay = autoplay,
                onAutoplay = { autoplay = it; playbackPrefs.saveAutoplay(it) },
                onClose = { showSettings = false }
            )
        } else if (showPlayer && nowPlaying != null) {
            FullPlayer(
                media = requireNotNull(nowPlaying),
                controller = controller,
                isPlaying = isPlaying,
                quality = quality,
                onQuality = {
                    quality = it
                    playbackPrefs.saveQuality(it)
                },
                playbackSpeed = playbackSpeed,
                onSpeed = {
                    playbackSpeed = it
                    playbackPrefs.saveSpeed(it)
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
                onChannel = { openChannel(nowPlaying?.channelUrl) },
                onFavorite = {
                    nowPlaying?.let { media ->
                        scope.launch(Dispatchers.IO) {
                            if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id)
                            else favoritesRepo.add(media)
                        }
                    }
                },
                onToggle = {
                    if (controller?.currentMediaItem == null) {
                        nowPlaying?.let { playMedia(it, resumePositionMs) }
                    } else togglePlayback()
                },
                onClose = { showPlayer = false }
            )
        } else {
            Scaffold(
                topBar = { AppHeader(onSettings = { showSettings = true }) },
                bottomBar = {
                    Column {
                        nowPlaying?.let { media ->
                            MiniPlayer(
                                media = media,
                                isPlaying = isPlaying,
                                onOpen = { showPlayer = true },
                                onToggle = {
                                    if (controller?.currentMediaItem == null) {
                                        nowPlaying?.let { playMedia(it, resumePositionMs) }
                                    } else togglePlayback()
                                }
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
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        results = searchResults,
                        onResultsChange = { searchResults = it },
                        onChannel = { openChannel(it.channelUrl) },
                        onPlay = { media, resultQueue ->
                            queue = resultQueue
                            queueIndex = resultQueue.indexOfFirst { it.id == media.id }
                            playMedia(media)
                        }
                    )
                    Tab.HOME -> HomeScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        suggestions = homeSuggestions,
                        loading = homeLoading,
                        resolvingId = resolvingId,
                        onPlay = { media ->
                            queue = homeSuggestions
                            queueIndex = queue.indexOfFirst { it.id == media.id }
                            playMedia(media)
                        },
                        onSearch = { selected = Tab.SEARCH }
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        recent = recent,
                        favorites = favorites,
                        resolvingId = resolvingId,
                        onPlay = { entry ->
                            queue = recent.map { it.toMediaSummary() }
                            queueIndex = queue.indexOfFirst { it.id == entry.mediaId }
                            playMedia(entry.toMediaSummary())
                        },
                        onPlayFavorite = { entry ->
                            queue = favorites.map { it.toMediaSummary() }
                            queueIndex = queue.indexOfFirst { it.id == entry.mediaId }
                            playMedia(entry.toMediaSummary())
                        },
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
private fun AppHeader(onSettings: () -> Unit) {
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
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, "Settings")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    quality: AudioQuality,
    onQuality: (AudioQuality) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    autoplay: Boolean,
    onAutoplay: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Playback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Column {
                Text("Default audio quality", fontWeight = FontWeight.SemiBold)
                Text("Used when a new track starts.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioQuality.entries.forEach { option ->
                        FilterChip(
                            selected = quality == option,
                            onClick = { onQuality(option) },
                            label = { Text(when (option) {
                                AudioQuality.DATA_SAVER -> "Saver 64"
                                AudioQuality.STANDARD -> "Std 128"
                                AudioQuality.HIGH -> "High"
                            }) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Column {
                Text("Default speed", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { option ->
                        FilterChip(
                            selected = speed == option,
                            onClick = { onSpeed(option) },
                            label = { Text("${option}×") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay queue", fontWeight = FontWeight.SemiBold)
                    Text("Play the next search result automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoplay, onCheckedChange = onAutoplay)
            }

            HorizontalDivider()
            Text("ezTube plays audio streams only when available. Some YouTube videos require a compatibility stream, which may use more data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchScreen(
    modifier: Modifier,
    source: NewPipeYouTubeSource,
    resolvingId: String?,
    playbackError: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<MediaSummary>,
    onResultsChange: (List<MediaSummary>) -> Unit,
    onChannel: (MediaSummary) -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (query.isBlank() || loading) return
        scope.launch {
            loading = true
            searchError = null
            runCatching { withContext(Dispatchers.IO) { source.search(query) } }
                .onSuccess { onResultsChange(it) }
                .onFailure { searchError = it.message ?: "Search failed" }
            loading = false
        }
    }

    Column(modifier.padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(),
            singleLine = true, placeholder = { Text("Search songs, artists, podcasts…") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
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
                    SearchResult(
                        media = media,
                        resolving = resolvingId == media.id,
                        enabled = resolvingId == null,
                        onChannel = { onChannel(media) },
                        onPlay = { onPlay(media, results) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResult(
    media: MediaSummary,
    resolving: Boolean,
    enabled: Boolean,
    onChannel: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onPlay)
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
            Text(
                media.channel.ifBlank { "YouTube" },
                style = MaterialTheme.typography.bodySmall,
                color = if (media.channelUrl != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (media.channelUrl != null) Modifier.clickable(onClick = onChannel) else Modifier
            )
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
    suggestions: List<MediaSummary>,
    loading: Boolean,
    resolvingId: String?,
    onPlay: (MediaSummary) -> Unit,
    onSearch: () -> Unit
) {
    LazyColumn(
        modifier.padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("For you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Suggestions shaped by what you listen to",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onSearch) {
                Icon(Icons.Outlined.Search, null)
                Spacer(Modifier.width(6.dp))
                Text("Search YouTube")
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (suggestions.isNotEmpty()) {
            item { Text("Recommended", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(suggestions, key = { "home-s-" + it.id }) { media ->
                CompactMediaRow(media.title, media.channel, media.thumbnailUrl,
                    resolvingId == media.id) { onPlay(media) }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text("More personalized topics, channels and playlists will improve as you listen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (!loading) {
            item {
                Text("Listen to a few tracks or add favorites to start building recommendations.",
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
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Recent listening · ${recent.size} items", style = MaterialTheme.typography.bodySmall,
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
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp)) {
                        Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Offline downloads will appear here when the download engine is enabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(playlist.thumbnailUrl, null, Modifier.size(width = 104.dp, height = 60.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (playlist.streamCount > 0) Text(playlist.streamCount.toString() + " videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.PlaylistPlay, null)
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: PlaylistDetail?,
    loading: Boolean,
    error: String?,
    resolvingId: String?,
    nowPlayingId: String?,
    onBack: () -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit,
    onPlayAll: (List<MediaSummary>) -> Unit
) {
    val listState = rememberLazyListState()
    val playingIndex = playlist?.items?.indexOfFirst { it.id == nowPlayingId } ?: -1
    LaunchedEffect(nowPlayingId, playlist?.url) {
        if (playingIndex >= 0) {
            // Header is item 0. Keep the active row around the visual center.
            listState.animateScrollToItem((playingIndex + 1).coerceAtLeast(0), scrollOffset = -280)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(playlist?.title ?: "Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (error != null) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(error, color = MaterialTheme.colorScheme.error) }
        else if (playlist != null) LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 18.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(playlist.thumbnailUrl, null, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (playlist.uploaderName.isNotBlank()) Text(playlist.uploaderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(playlist.items.size.toString() + " videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onPlayAll(playlist.items) }, enabled = playlist.items.isNotEmpty() && resolvingId == null) {
                            Icon(Icons.Outlined.PlayArrow, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play all")
                        }
                    }
                }
            }
            items(playlist.items, key = { "pl-item-" + it.id }) { media ->
                val isCurrent = media.id == nowPlayingId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (isCurrent) {
                            Icon(
                                Icons.Outlined.GraphicEq,
                                "Now playing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp).size(20.dp)
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            SearchResult(
                                media,
                                resolvingId == media.id,
                                resolvingId == null,
                                onChannel = {},
                                onPlay = { onPlay(media, playlist.items) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelScreen(
    channel: ChannelSummary?,
    loading: Boolean,
    error: String?,
    resolvingId: String?,
    onBack: () -> Unit,
    onPlaylist: (PlaylistSummary) -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit
) {
    var section by remember(channel?.url) { mutableStateOf("Videos") }
    var sort by remember(channel?.url) { mutableStateOf("Newest") }

    val videos = remember(channel?.videos, sort) {
        when (sort) {
            "Most viewed" -> channel?.videos.orEmpty().sortedByDescending { it.viewCount }
            "Oldest" -> channel?.videos.orEmpty().asReversed()
            else -> channel?.videos.orEmpty()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(channel?.name ?: "Channel", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (error != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        } else if (channel != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
                channel.bannerUrl?.let { banner ->
                    item { AsyncImage(banner, null, Modifier.fillMaxWidth().aspectRatio(16f / 5f), contentScale = ContentScale.Crop) }
                }
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(channel.avatarUrl, null, Modifier.size(72.dp).clip(RoundedCornerShape(50)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (channel.subscriberCount >= 0) Text("%,d subscribers".format(channel.subscriberCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = section == "Videos", onClick = { section = "Videos" }, label = { Text("Videos") })
                        FilterChip(selected = section == "Playlists", onClick = { section = "Playlists" }, label = { Text("Playlists") })
                    }
                }
                if (section == "Videos") {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Newest", "Oldest", "Most viewed").forEach { option ->
                                FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option) })
                            }
                        }
                    }
                    items(videos, key = { "channel-" + it.id }) { media ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = resolvingId == null) { onPlay(media, videos) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(media.thumbnailUrl, null, Modifier.size(width = 116.dp, height = 66.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(media.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val meta = buildList {
                                    if (media.viewCount >= 0) add(formatViews(media.viewCount))
                                    media.uploadDateText?.takeIf { it.isNotBlank() }?.let(::add)
                                }.joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (resolvingId == media.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.PlayCircle, "Play")
                        }
                    }
                    if (videos.isEmpty()) item { Text("No videos found.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    if (channel.playlists.isEmpty()) {
                        item { Text("No playlists found.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        items(channel.playlists, key = { "playlist-" + it.url }) { PlaylistRow(it, onClick = { onPlaylist(it) }) }
                    }
                }
            }
        }
    }
}

private fun formatViews(value: Long): String = when {
    value >= 1_000_000_000 -> String.format("%.1fB views", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format("%.1fM views", value / 1_000_000.0)
    value >= 1_000 -> String.format("%.1fK views", value / 1_000.0)
    else -> "$value views"
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
    onChannel: () -> Unit,
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
                Text(
                    media.channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (media.channelUrl != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(
                        if (media.channelUrl != null) Modifier.clickable(onClick = onChannel) else Modifier
                    )
                )
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
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        controller?.prepare()
                        controller?.play()
                    }) { Text("Retry") }
                }
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
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            seek(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.positionChanged()) {
                                    seek(change.position.x)
                                    change.consume()
                                }
                            } while (!change.changedToUpIgnoreConsumed())
                        }
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
