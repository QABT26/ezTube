package com.qabt.eztube.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.qabt.eztube.BuildConfig
import com.qabt.eztube.history.EzTubeDatabase
import com.qabt.eztube.history.HistoryEntry
import com.qabt.eztube.history.FavoriteEntry
import com.qabt.eztube.history.FavoriteRepository
import com.qabt.eztube.history.HistoryRepository
import com.qabt.eztube.history.toMediaSummary
import com.qabt.eztube.history.progress
import com.qabt.eztube.playback.AudioQuality
import com.qabt.eztube.playback.VideoQuality
import com.qabt.eztube.playback.PlaybackService
import com.qabt.eztube.playback.PlaybackPreferences
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
private enum class RepeatMode { OFF, ONE, ALL }
private enum class NextMode { LIST, RECOMMENDED }
private enum class SearchSort(val label: String) {
    RELEVANCE("Relevance"), NEWEST("Newest"), VIEWS("Views"), DURATION("Duration")
}

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
    var videoQuality by remember { mutableStateOf(playbackPrefs.loadVideoQuality()) }
    var actualVideoHeight by remember { mutableIntStateOf(0) }
    var videoMode by remember { mutableStateOf(playbackPrefs.loadVideoMode()) }
    val playbackActivity = context as? Activity
    DisposableEffect(videoMode, playbackActivity) {
        val window = playbackActivity?.window
        if (videoMode) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (videoMode) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    var autoplay by remember { mutableStateOf(playbackPrefs.loadAutoplay()) }
    var nextMode by remember { mutableStateOf(runCatching { NextMode.valueOf(playbackPrefs.loadNextMode()) }.getOrDefault(NextMode.LIST)) }
    var repeatMode by remember {
        mutableStateOf(RepeatMode.entries.getOrElse(playbackPrefs.loadRepeatMode()) { RepeatMode.OFF })
    }
    var showSettings by remember { mutableStateOf(false) }
    var resolvingId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var recentSearches by remember { mutableStateOf(playbackPrefs.loadRecentSearches()) }
    var homeSuggestions by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var trending by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var trendingLoading by remember { mutableStateOf(false) }
    var homeRefreshToken by remember { mutableIntStateOf(0) }
    var trendingTopic by remember { mutableStateOf(playbackPrefs.loadTrendingTopic()) }
    var trendingLanguage by remember { mutableStateOf(playbackPrefs.loadTrendingLanguage()) }
    val searchListState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
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
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val id = mediaItem?.mediaId ?: return
                        compatibilityFallback = mediaItem.mediaMetadata.extras
                            ?.getBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, false) == true
                        actualVideoHeight = mediaItem.mediaMetadata.extras
                            ?.getInt(PlaybackService.EXTRA_VIDEO_HEIGHT, 0) ?: 0
                        val saved = playbackPrefs.loadQueue()
                        val media = saved?.first?.firstOrNull { it.id == id }
                            ?: playbackPrefs.load()?.first?.takeIf { it.id == id }
                            ?: return
                        nowPlaying = media
                        playerError = null
                        saved?.let { (items, _) ->
                            val index = items.indexOfFirst { it.id == id }
                            if (index >= 0) queueIndex = index
                        }
                        resumePositionMs = 0L
                    }
                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                        compatibilityFallback = mediaMetadata.extras
                            ?.getBoolean(PlaybackService.EXTRA_COMPATIBILITY_FALLBACK, false) == true
                        actualVideoHeight = mediaMetadata.extras
                            ?.getInt(PlaybackService.EXTRA_VIDEO_HEIGHT, 0) ?: 0
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playerError = error.message ?: "Playback error"
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = state == Player.STATE_BUFFERING
                        isPlaying = mediaController.isPlaying
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        isPlaying = mediaController.isPlaying
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

    fun queueForStart(media: MediaSummary, sourceItems: List<MediaSummary>): List<MediaSummary> {
        if (nextMode == NextMode.LIST) return sourceItems
        val pool = (sourceItems + homeSuggestions + trending + searchResults)
            .distinctBy { it.id }
            .filterNot { it.id == media.id }
        if (pool.isEmpty()) return listOf(media)
        val seed = (media.id.hashCode().toLong() shl 32) xor System.nanoTime()
        return listOf(media) + pool.shuffled(kotlin.random.Random(seed))
    }

    fun notifyQueueChanged() {
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.COMMAND_QUEUE_CHANGED, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY
        )
    }

    fun reloadCurrentForModeChange() {
        val active = controller ?: return
        val args = android.os.Bundle().apply {
            putLong(PlaybackService.ARG_POSITION_MS, active.currentPosition.coerceAtLeast(0L))
            putBoolean(PlaybackService.ARG_PLAY_WHEN_READY, active.playWhenReady)
        }
        active.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.COMMAND_RELOAD_CURRENT, android.os.Bundle.EMPTY),
            args
        )
    }

    fun playMedia(media: MediaSummary, startPositionMs: Long = 0L) {
        if (resolvingId != null) return
        val active = controller
        if (active == null) {
            errorMessage = "Playback service is not ready yet"
            return
        }

        resolvingId = media.id
        errorMessage = null

        if (queue.isEmpty()) {
            queue = listOf(media)
            queueIndex = 0
        } else {
            val idx = queue.indexOfFirst { it.id == media.id }
            if (idx >= 0) {
                queueIndex = idx
            } else {
                queue = listOf(media)
                queueIndex = 0
            }
        }
        playbackPrefs.saveQueue(queue, queueIndex.coerceAtLeast(0))

        val args = android.os.Bundle().apply {
            putLong(PlaybackService.ARG_POSITION_MS, startPositionMs.coerceAtLeast(0L))
            putBoolean(PlaybackService.ARG_PLAY_WHEN_READY, true)
        }
        val future = active.sendCustomCommand(
            androidx.media3.session.SessionCommand(
                PlaybackService.COMMAND_PLAY_CURRENT,
                android.os.Bundle.EMPTY
            ),
            args
        )
        future.addListener({
            runCatching { future.get() }
                .onSuccess { result ->
                    if (result.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                        playerError = null
                        nowPlaying = media
                        resumePositionMs = 0L
                        playbackPrefs.save(media, startPositionMs.coerceAtLeast(0L))
                        scope.launch(Dispatchers.IO) {
                            history.record(media, startPositionMs.coerceAtLeast(0L), 0L)
                        }
                    } else {
                        errorMessage = result.extras.getString(PlaybackService.ARG_ERROR_MESSAGE)
                            ?: "Unable to play this item"
                    }
                }
                .onFailure {
                    errorMessage = it.message ?: "Unable to play this item"
                }
            if (resolvingId == media.id) resolvingId = null
        }, context.mainExecutor)
    }

    fun startQueue(media: MediaSummary, sourceItems: List<MediaSummary>, startPositionMs: Long = 0L) {
        queue = queueForStart(media, sourceItems)
        queueIndex = queue.indexOfFirst { it.id == media.id }.coerceAtLeast(0)
        playbackPrefs.saveQueue(queue, queueIndex)
        playMedia(media, startPositionMs)
    }

    fun playNext(media: MediaSummary) {
        if (queue.isEmpty() || queueIndex !in queue.indices) {
            startQueue(media, listOf(media))
            return
        }
        val currentId = queue[queueIndex].id
        val updated = queue.filterNot { it.id == media.id }.toMutableList()
        val current = updated.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        updated.add((current + 1).coerceAtMost(updated.size), media)
        queue = updated
        queueIndex = current
        playbackPrefs.saveQueue(queue, queueIndex)
        notifyQueueChanged()
    }

    fun addToQueue(media: MediaSummary) {
        if (queue.isEmpty() || queueIndex !in queue.indices) {
            startQueue(media, listOf(media))
            return
        }
        if (queue.none { it.id == media.id }) {
            queue = queue + media
            playbackPrefs.saveQueue(queue, queueIndex)
            // If the current item used to be the end of the queue, the service needs
            // an immediate refresh so autoplay can preload this newly appended item.
            notifyQueueChanged()
        }
    }

    LaunchedEffect(controller, repeatMode) {
        // Repeat ONE is safe natively. Repeat ALL is owned by PlaybackQueueManager so it
        // wraps the complete persisted logical queue, not only Media3's small resolved window.
        controller?.repeatMode = if (repeatMode == RepeatMode.ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    LaunchedEffect(Unit) {
        playbackPrefs.loadQueue()?.let { (savedQueue, savedIndex) ->
            queue = savedQueue
            queueIndex = savedIndex
        }
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
            val duration = controller?.duration?.takeIf { it > 0 } ?: 0L
            withContext(Dispatchers.IO) { history.updateProgress(media, position, duration) }
        }
    }

    LaunchedEffect(sleepMinutes) {
        val minutes = sleepMinutes ?: return@LaunchedEffect
        delay(minutes * 60_000L)
        controller?.pause()
        sleepMinutes = null
    }

    LaunchedEffect(homeRefreshToken, trendingTopic, trendingLanguage) {
        trendingLoading = true
        runCatching { withContext(Dispatchers.IO) { source.trending(trendingTopic, trendingLanguage) } }
            .onSuccess { trending = it }
        trendingLoading = false
    }

    LaunchedEffect(recent.firstOrNull()?.mediaId, favorites.firstOrNull()?.mediaId, recentSearches, homeRefreshToken) {
        val searchSeeds = recentSearches.filter { it.isNotBlank() }.distinct().take(5)
        val tasteSeeds = (favorites.map { it.channel } + recent.map { it.channel })
            .filter { it.isNotBlank() }.distinct().take(3)
        val seeds = (searchSeeds + tasteSeeds).distinct().take(8)
        if (seeds.isEmpty()) {
            homeSuggestions = emptyList()
            homeLoading = false
            return@LaunchedEffect
        }
        homeLoading = true
        val played = recent.mapTo(mutableSetOf()) { it.mediaId }
        val recommended = withContext(Dispatchers.IO) {
            seeds.flatMapIndexed { index, seed ->
                runCatching { source.search(seed) }.getOrDefault(emptyList()).take(if (index < searchSeeds.size) 6 else 4)
            }
        }
        homeSuggestions = recommended
            .distinctBy { it.id }
            .filterNot { it.id in played }
            .take(18)
        homeLoading = false
    }

    BackHandler(
        enabled = playlistDetail != null || playlistLoading || playlistError != null ||
            channelDetail != null || channelLoading || channelError != null ||
            showSettings || showPlayer
    ) {
        when {
            playlistDetail != null || playlistLoading || playlistError != null -> {
                playlistDetail = null
                playlistError = null
                playlistLoading = false
            }
            channelDetail != null || channelLoading || channelError != null -> {
                channelDetail = null
                channelError = null
                channelLoading = false
            }
            showSettings -> showSettings = false
            showPlayer -> showPlayer = false
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
        if (playlistDetail != null || playlistLoading || playlistError != null) {
            Scaffold(bottomBar = {
                nowPlaying?.let { media -> MiniPlayer(
                    media = media,
                    isPlaying = isPlaying,
                    isFavorite = favorites.any { it.mediaId == media.id },
                    onOpen = { channelDetail = null; playlistDetail = null; showPlayer = true },
                    onFavorite = { scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } },
                    onToggle = { if (controller?.currentMediaItem == null) playMedia(media, resumePositionMs) else togglePlayback() }
                ) }
            }) { detailPadding ->
            Box(Modifier.fillMaxSize().padding(detailPadding)) {
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
                    startQueue(media, items)
                },
                onChannel = { openChannel(it.channelUrl) },
                onPlayAll = { items ->
                    if (items.isNotEmpty()) {
                        startQueue(items.first(), items)
                    }
                }
            )
            }
            }
        } else if (channelDetail != null || channelLoading || channelError != null) {
            Scaffold(bottomBar = {
                nowPlaying?.let { media -> MiniPlayer(
                    media = media,
                    isPlaying = isPlaying,
                    isFavorite = favorites.any { it.mediaId == media.id },
                    onOpen = { channelDetail = null; channelError = null; channelLoading = false; showPlayer = true },
                    onFavorite = { scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } },
                    onToggle = { if (controller?.currentMediaItem == null) playMedia(media, resumePositionMs) else togglePlayback() }
                ) }
            }) { detailPadding ->
            Box(Modifier.fillMaxSize().padding(detailPadding)) {
            ChannelScreen(
                channel = channelDetail,
                loading = channelLoading,
                error = channelError,
                resolvingId = resolvingId,
                nowPlayingId = nowPlaying?.id,
                onBack = {
                    channelDetail = null
                    channelError = null
                    channelLoading = false
                },
                onPlaylist = { openPlaylist(it.url) },
                onPlay = { media, items ->
                    startQueue(media, items)
                }
            )
            }
            }
        } else if (showPlayer && nowPlaying != null) {
            FullPlayer(
                media = requireNotNull(nowPlaying), controller = controller, isPlaying = isPlaying, quality = quality,
                videoQuality = videoQuality,
                actualVideoHeight = actualVideoHeight,
                videoMode = videoMode,
                onVideoMode = { enabled ->
                    if (videoMode != enabled) {
                        videoMode = enabled
                        playbackPrefs.saveVideoMode(enabled)
                        // The service owns stream replacement so a mode switch cannot race
                        // the UI resolver or lose queue identity/position.
                        reloadCurrentForModeChange()
                    }
                },
                onQuality = { quality = it; playbackPrefs.saveQuality(it) },
                onVideoQuality = {
                    if (videoQuality != it) {
                        videoQuality = it
                        playbackPrefs.saveVideoQuality(it)
                        if (videoMode) reloadCurrentForModeChange()
                    }
                },
                playbackSpeed = playbackSpeed,
                onSpeed = { playbackSpeed = it; playbackPrefs.saveSpeed(it); controller?.setPlaybackSpeed(it) },
                compatibilityFallback = compatibilityFallback, isBuffering = isBuffering, playerError = playerError,
                onRetry = { playerError = null; nowPlaying?.let { playMedia(it, controller?.currentPosition ?: 0L) } },
                sleepMinutes = sleepMinutes, onSleep = { sleepMinutes = it }, autoplay = autoplay,
                onAutoplay = { autoplay = it; playbackPrefs.saveAutoplay(it); notifyQueueChanged() }, nextMode = nextMode,
                onNextMode = { nextMode = it; playbackPrefs.saveNextMode(it.name) }, repeatMode = repeatMode,
                onRepeatMode = { repeatMode = it; playbackPrefs.saveRepeatMode(it.ordinal) },
                queue = queue, queueIndex = queueIndex,
                hasPrevious = queueIndex > 0 || (repeatMode == RepeatMode.ALL && queue.size > 1),
                hasNext = (queueIndex >= 0 && queueIndex < queue.lastIndex) ||
                    (repeatMode == RepeatMode.ALL && queue.size > 1),
                onQueueItem = { index ->
                    if (index in queue.indices && index != queueIndex) {
                        queueIndex = index
                        playbackPrefs.saveQueue(queue, queueIndex)
                        playMedia(queue[index])
                    }
                },
                onQueueRemove = { index ->
                    if (index in queue.indices && index != queueIndex) {
                        val updated = queue.toMutableList().also { it.removeAt(index) }
                        queueIndex = if (index < queueIndex) queueIndex - 1 else queueIndex
                        queue = updated
                        playbackPrefs.saveQueue(queue, queueIndex.coerceAtLeast(0))
                        notifyQueueChanged()
                    }
                },
                onQueueMove = { from, to ->
                    if (from in queue.indices && to in queue.indices && from != to) {
                        val updated = queue.toMutableList()
                        val moved = updated.removeAt(from)
                        updated.add(to, moved)
                        queueIndex = when {
                            queueIndex == from -> to
                            from < queueIndex && to >= queueIndex -> queueIndex - 1
                            from > queueIndex && to <= queueIndex -> queueIndex + 1
                            else -> queueIndex
                        }
                        queue = updated
                        playbackPrefs.saveQueue(queue, queueIndex)
                        notifyQueueChanged()
                    }
                },
                onQueueClearUpcoming = {
                    if (queueIndex in queue.indices && queueIndex < queue.lastIndex) {
                        queue = queue.take(queueIndex + 1)
                        playbackPrefs.saveQueue(queue, queueIndex)
                        notifyQueueChanged()
                    }
                },
                onPrevious = {
                    controller?.seekToPrevious()
                },
                onNext = {
                    controller?.seekToNext()
                },
                isFavorite = favorites.any { it.mediaId == nowPlaying?.id }, onChannel = { openChannel(nowPlaying?.channelUrl) },
                onFavorite = { nowPlaying?.let { media -> scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } } },
                onToggle = { if (controller?.currentMediaItem == null) nowPlaying?.let { playMedia(it, resumePositionMs) } else togglePlayback() },
                onClose = { showPlayer = false }
            )
        } else if (showSettings) {
            SettingsScreen(
                quality = quality,
                onQuality = { quality = it; playbackPrefs.saveQuality(it) },
                speed = playbackSpeed,
                onSpeed = { playbackSpeed = it; playbackPrefs.saveSpeed(it); controller?.setPlaybackSpeed(it) },
                autoplay = autoplay,
                onAutoplay = { autoplay = it; playbackPrefs.saveAutoplay(it); notifyQueueChanged() },
                trendingTopic = trendingTopic,
                onTrendingTopic = { trendingTopic = it; playbackPrefs.saveTrendingTopic(it) },
                trendingLanguage = trendingLanguage,
                onTrendingLanguage = { trendingLanguage = it; playbackPrefs.saveTrendingLanguage(it) },
                onClose = { showSettings = false }
            )
        } else {
            Scaffold(
                topBar = { AppHeader(onSettings = { showSettings = true }, onDoubleTapCenter = { scope.launch { when (selected) { Tab.HOME -> homeListState.animateScrollToItem(0); Tab.SEARCH -> searchListState.animateScrollToItem(0); Tab.LIBRARY -> libraryListState.animateScrollToItem(0) } } }) },
                bottomBar = {
                    Column {
                        nowPlaying?.let { media ->
                            MiniPlayer(
                                media = media,
                                isPlaying = isPlaying,
                                isFavorite = favorites.any { it.mediaId == media.id },
                                onOpen = { channelDetail = null; playlistDetail = null; showPlayer = true },
                                onFavorite = { scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } },
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
                        historyEntries = recent,
                        listState = searchListState,
                        onResultsChange = { searchResults = it },
                        recentSearches = recentSearches,
                        onSearchSubmitted = { q -> playbackPrefs.saveSearch(q); recentSearches = playbackPrefs.loadRecentSearches() },
                        onChannel = { openChannel(it.channelUrl) },
                        onPlay = { media, resultQueue -> startQueue(media, resultQueue) },
                        onPlayNext = { playNext(it) },
                        onAddToQueue = { addToQueue(it) }
                    )
                    Tab.HOME -> HomeScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        suggestions = homeSuggestions,
                        trending = trending,
                        trendingLoading = trendingLoading,
                        listState = homeListState,
                        loading = homeLoading,
                        onRefresh = { homeRefreshToken += 1 },
                        resolvingId = resolvingId,
                        onPlay = { media, items ->
                            startQueue(media, items)
                        },
                        onSearch = { selected = Tab.SEARCH }
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        recent = recent,
                        favorites = favorites,
                        listState = libraryListState,
                        resolvingId = resolvingId,
                        onPlay = { entry ->
                            val items = recent.map { it.toMediaSummary() }
                            startQueue(
                                entry.toMediaSummary(),
                                items,
                                startPositionMs = entry.positionMs.coerceAtLeast(0L)
                            )
                        },
                        onPlayFavorite = { entry ->
                            val items = favorites.map { it.toMediaSummary() }
                            startQueue(entry.toMediaSummary(), items)
                        },
                        onPlayAllFavorites = {
                            val items = favorites.map { it.toMediaSummary() }
                            if (items.isNotEmpty()) {
                                startQueue(items.first(), items)
                            }
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
}

@Composable
private fun AppHeader(onSettings: () -> Unit, onDoubleTapCenter: () -> Unit) {
    Surface(tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ez", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Tube", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTapCenter() }) })
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
    trendingTopic: String,
    onTrendingTopic: (String) -> Unit,
    trendingLanguage: String,
    onTrendingLanguage: (String) -> Unit,
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
                                AudioQuality.HIGH -> "High 160+"
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
            Text("Trending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Topic", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("Music", "Podcasts", "Gaming", "Movies", "Live").forEach { option ->
                    CompactPresetButton(option, trendingTopic == option, { onTrendingTopic(option) }, Modifier.weight(1f))
                }
            }
            Text("Language", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("Vietnamese", "English", "Korean", "Japanese", "All").forEach { option ->
                    val label = when(option) { "Vietnamese" -> "VI"; "English" -> "EN"; "Korean" -> "KO"; "Japanese" -> "JA"; else -> "All" }
                    CompactPresetButton(label, trendingLanguage == option, { onTrendingLanguage(option) }, Modifier.weight(1f))
                }
            }

            HorizontalDivider()
            Column {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "ezTube ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_ID}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Package: ${BuildConfig.APPLICATION_ID} · versionCode ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("ezTube plays audio streams only when available. Some YouTube videos require a compatibility stream, which may use more data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(
    modifier: Modifier,
    source: NewPipeYouTubeSource,
    resolvingId: String?,
    playbackError: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<MediaSummary>,
    historyEntries: List<HistoryEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onResultsChange: (List<MediaSummary>) -> Unit,
    recentSearches: List<String>,
    onSearchSubmitted: (String) -> Unit,
    onChannel: (MediaSummary) -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit,
    onPlayNext: (MediaSummary) -> Unit,
    onAddToQueue: (MediaSummary) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchSort by remember { mutableStateOf(SearchSort.RELEVANCE) }
    val displayResults = remember(results, searchSort) {
        when (searchSort) {
            SearchSort.RELEVANCE -> results
            SearchSort.NEWEST -> results.sortedBy { searchAgeRank(it.uploadDateText) }
            SearchSort.VIEWS -> results.sortedByDescending { it.viewCount }
            SearchSort.DURATION -> results.sortedByDescending { it.durationSeconds }
        }
    }

    fun submit(searchText: String = query) {
        val normalized = searchText.trim()
        if (normalized.isBlank() || loading) return
        if (normalized != query) onQueryChange(normalized)
        scope.launch {
            loading = true
            searchError = null
            onSearchSubmitted(normalized)
            runCatching { withContext(Dispatchers.IO) { source.search(normalized) } }
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
        if (query.isBlank() && recentSearches.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                recentSearches.take(5).forEach { recent ->
                    AssistChip(
                        onClick = { submit(recent) },
                        label = {
                            Text(
                                recent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 280.dp)
                            )
                        }
                    )
                }
            }
        }
        if (results.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SearchSort.entries.forEach { option ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clickable { searchSort = option },
                        shape = RoundedCornerShape(10.dp),
                        color = if (searchSort == option) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        border = if (searchSort == option) null else androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
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
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(displayResults, key = { index, media -> "search-" + index + "-" + media.id }) { _, media ->
                    SearchResult(
                        media = media,
                        progressEntry = historyEntries.firstOrNull { it.mediaId == media.id },
                        resolving = resolvingId == media.id,
                        enabled = resolvingId == null,
                        onChannel = { onChannel(media) },
                        onPlay = { onPlay(media, displayResults) },
                        onPlayNext = { onPlayNext(media) },
                        onAddToQueue = { onAddToQueue(media) }
                    )
                }
            }
        }
    }
}

private fun searchAgeRank(text: String?): Long {
    val value = text?.lowercase()?.trim().orEmpty()
    if (value.isBlank()) return Long.MAX_VALUE
    val number = Regex("""\d+""").find(value)?.value?.toLongOrNull() ?: 1L
    val unit = when {
        "minute" in value || "phút" in value -> 60L
        "hour" in value || "giờ" in value -> 3_600L
        "day" in value || "ngày" in value -> 86_400L
        "week" in value || "tuần" in value -> 604_800L
        "month" in value || "tháng" in value -> 2_629_800L
        "year" in value || "năm" in value -> 31_557_600L
        else -> Long.MAX_VALUE / 4
    }
    return if (unit >= Long.MAX_VALUE / 4) unit else number * unit
}

@Composable
private fun SearchResult(
    media: MediaSummary,
    progressEntry: HistoryEntry? = null,
    resolving: Boolean,
    enabled: Boolean,
    onChannel: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var titleTouchActive by remember(media.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(media.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    titleTouchActive = true
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        titleTouchActive = false
                    }
                }
            }
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(7.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 116.dp, height = 66.dp)) {
            AsyncImage(
                media.thumbnailUrl,
                null,
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            if (media.durationSeconds >= 0) {
                Text(
                    formatDuration(media.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.78f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                media.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (titleTouchActive) 1 else 2,
                softWrap = !titleTouchActive,
                overflow = if (titleTouchActive) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (titleTouchActive) {
                    Modifier.fillMaxWidth().basicMarquee(
                        iterations = Int.MAX_VALUE,
                        spacing = MarqueeSpacing(20.dp),
                        initialDelayMillis = 120
                    )
                } else Modifier.fillMaxWidth()
            )
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
            val meta = mediaMeta(media, includeDuration = false)
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            progressEntry?.let { entry ->
                SearchPlaybackProgress(progress = entry.progress)
            }
        }
        Spacer(Modifier.width(2.dp))
        if (resolving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else if (onPlayNext != null || onAddToQueue != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        "Queue actions",
                        modifier = Modifier.size(14.dp)
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    onPlayNext?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            leadingIcon = { Icon(Icons.Outlined.SkipNext, null) },
                            onClick = { showMenu = false; action() }
                        )
                    }
                    onAddToQueue?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Add to queue") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null) },
                            onClick = { showMenu = false; action() }
                        )
                    }
                }
            }
        } else Icon(Icons.Outlined.PlayCircle, "Play")
    }
}

@Composable
private fun MiniPlayer(
    media: MediaSummary,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
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
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        repeatDelayMillis = 1_200,
                        initialDelayMillis = 900,
                        spacing = MarqueeSpacing(32.dp)
                    )
                )
                Text(media.channel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    if (isFavorite) "Remove from favorites" else "Add to favorites"
                )
            }
            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (isPlaying) "Pause" else "Play")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    modifier: Modifier,
    suggestions: List<MediaSummary>,
    trending: List<MediaSummary>,
    trendingLoading: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    loading: Boolean,
    onRefresh: () -> Unit,
    resolvingId: String?,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit,
    onSearch: () -> Unit
) {
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = loading || trendingLoading,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        state = listState,
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("For you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Suggestions shaped by what you listen to",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSearch, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Search YouTube", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { scope.launch { listState.animateScrollToItem(if (suggestions.isNotEmpty()) suggestions.size + 3 else 2) } }
                ) {
                    Icon(Icons.AutoMirrored.Outlined.TrendingUp, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Trending")
                }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (suggestions.isNotEmpty()) {
            item { Text("Recommended", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            itemsIndexed(suggestions, key = { index, media -> "home-s-" + index + "-" + media.id }) { _, media ->
                CompactMediaRow(media, resolvingId == media.id) { onPlay(media, suggestions) }
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
        item {
            Spacer(Modifier.height(10.dp))
            Text("Trending now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Popular on YouTube right now", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trendingLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        itemsIndexed(trending, key = { index, media -> "trend-" + index + "-" + media.id }) { index, media ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((index + 1).toString(), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Box(Modifier.weight(1f)) {
                    CompactMediaRow(media, resolvingId == media.id) { onPlay(media, trending) }
                }
            }
        }
        if (!trendingLoading && trending.isEmpty()) {
            item { Text("Trending is temporarily unavailable.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall) }
        }
    }
    }
}

@Composable
private fun CompactMediaRow(
    media: MediaSummary,
    resolving: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !resolving, onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(media.thumbnailUrl, null, Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)),
            contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(media.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(media.channel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val meta = mediaMeta(media)
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (resolving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.PlayArrow, "Play")
    }
}

@Composable
private fun SearchPlaybackProgress(progress: Float) {
    if (progress <= 0f) return
    val percent = (progress * 100).toInt().coerceIn(1, 100)
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 28.dp)
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50))
        )
    }
}

@Composable
private fun PlaybackProgress(progress: Float) {
    if (progress <= 0f) return
    val percent = (progress * 100).toInt().coerceIn(1, 100)
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
        )
        Text(
            if (progress >= 0.95f) "Watched" else "Watched $percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProgressMediaRow(entry: HistoryEntry, resolving: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !resolving, onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(entry.thumbnailUrl, null, Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)),
            contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(entry.channel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            SearchPlaybackProgress(entry.progress)
        }
        if (resolving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.PlayArrow, "Play")
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryScreen(
    modifier: Modifier,
    recent: List<HistoryEntry>,
    favorites: List<FavoriteEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    resolvingId: String?,
    onPlay: (HistoryEntry) -> Unit,
    onPlayFavorite: (FavoriteEntry) -> Unit,
    onPlayAllFavorites: () -> Unit,
    onRemoveFavorite: (FavoriteEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClear: () -> Unit
) {
    var showAllFavorites by remember { mutableStateOf(false) }
    var showAllRecent by remember { mutableStateOf(false) }
    var showAllHistory by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 14.dp),
        state = listState,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stickyHeader {
            Surface(tonalElevation = if (showAllFavorites) 2.dp else 0.dp) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Favorites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (favorites.size > 3) {
                    TextButton(onClick = { showAllFavorites = !showAllFavorites }) {
                        Text(if (showAllFavorites) "Show less" else "View all")
                    }
                }
                if (favorites.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onPlayAllFavorites,
                        enabled = resolvingId == null,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play all")
                    }
                }
            }
            }
        }
        if (favorites.isEmpty()) {
            item { Text("Favorite tracks will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        } else {
            items(if (showAllFavorites) favorites else favorites.take(3), key = { "fav-" + it.mediaId }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = resolvingId == null) { onPlayFavorite(entry) }.padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(entry.thumbnailUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { onRemoveFavorite(entry) }) { Icon(Icons.Outlined.Favorite, "Remove favorite") }
                }
            }
        }

        stickyHeader {
            Surface(tonalElevation = if (showAllRecent) 2.dp else 0.dp) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Jump back into your latest listening", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recent.size > 3) {
                    TextButton(onClick = { showAllRecent = !showAllRecent }) {
                        Text(if (showAllRecent) "Show less" else "View all")
                    }
                }
            }
            }
        }
        if (recent.isEmpty()) {
            item { Text("Nothing played yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        } else {
            items(if (showAllRecent) recent else recent.take(3), key = { "recent-" + it.mediaId }) { entry ->
                ProgressMediaRow(entry = entry, resolving = resolvingId == entry.mediaId) { onPlay(entry) }
            }
        }

        stickyHeader {
            Surface(tonalElevation = if (showAllHistory) 2.dp else 0.dp) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(recent.size.toString() + " items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recent.size > 3) {
                    TextButton(onClick = { showAllHistory = !showAllHistory }) {
                        Text(if (showAllHistory) "Show less" else "View all")
                    }
                }
                if (recent.isNotEmpty()) {
                    IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteSweep, "Clear history") }
                }
            }
            }
        }
        items(if (showAllHistory) recent else recent.take(3), key = { "history-" + it.mediaId }) { entry ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = resolvingId == null) { onPlay(entry) }.padding(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(entry.thumbnailUrl, null,
                    Modifier.size(58.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.channel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SearchPlaybackProgress(progress = entry.progress)
                }
                if (resolvingId == entry.mediaId) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = { onPlay(entry) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, "Continue", Modifier.size(19.dp))
                    }
                    IconButton(
                        onClick = { onDelete(entry) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Outlined.Close, "Remove from history", Modifier.size(16.dp))
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }
            Text("Offline downloads are not enabled in this beta.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
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
        Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null)
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
    onChannel: (MediaSummary) -> Unit,
    onPlayAll: (List<MediaSummary>) -> Unit
) {
    val listState = rememberLazyListState()
    val playingIndex = playlist?.items?.indexOfFirst { it.id == nowPlayingId } ?: -1
    LaunchedEffect(nowPlayingId, playlist?.url) {
        if (playingIndex >= 0) {
            val target = playingIndex + 1
            listState.animateScrollToItem(target)
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (visible != null) {
                val viewportCenter = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                val itemCenter = visible.offset + visible.size / 2
                listState.animateScrollBy((itemCenter - viewportCenter).toFloat())
            }
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
            itemsIndexed(playlist.items, key = { index, media -> "pl-item-$index-${media.id}" }) { _, media ->
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
                                media = media,
                                resolving = resolvingId == media.id,
                                enabled = resolvingId == null,
                                onChannel = { onChannel(media) },
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
    nowPlayingId: String?,
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
                        val isCurrent = nowPlayingId == media.id
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ) {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = resolvingId == null) { onPlay(media, videos) }.padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrent) {
                                Icon(Icons.Outlined.GraphicEq, "Now playing", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp, end = 6.dp).size(20.dp))
                            }
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
                            else Icon(if (isCurrent) Icons.Outlined.GraphicEq else Icons.Outlined.PlayCircle,
                                if (isCurrent) "Now playing" else "Play",
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
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

private fun formatDuration(seconds: Long): String {
    if (seconds < 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

private fun mediaMeta(media: MediaSummary, includeDuration: Boolean = true): String =
    buildList {
        if (media.viewCount >= 0) add(formatViews(media.viewCount))
        media.uploadDateText?.takeIf { it.isNotBlank() }?.let(::add)
        if (includeDuration && media.durationSeconds >= 0) add(formatDuration(media.durationSeconds))
    }.joinToString(" · ")

@Composable
private fun FullPlayer(
    media: MediaSummary,
    controller: MediaController?,
    isPlaying: Boolean,
    quality: AudioQuality,
    videoQuality: VideoQuality,
    actualVideoHeight: Int,
    videoMode: Boolean,
    onVideoMode: (Boolean) -> Unit,
    onQuality: (AudioQuality) -> Unit,
    onVideoQuality: (VideoQuality) -> Unit,
    playbackSpeed: Float,
    onSpeed: (Float) -> Unit,
    compatibilityFallback: Boolean,
    isBuffering: Boolean,
    playerError: String?,
    onRetry: () -> Unit,
    sleepMinutes: Int?,
    onSleep: (Int?) -> Unit,
    autoplay: Boolean,
    onAutoplay: (Boolean) -> Unit,
    nextMode: NextMode,
    onNextMode: (NextMode) -> Unit,
    repeatMode: RepeatMode,
    onRepeatMode: (RepeatMode) -> Unit,
    queue: List<MediaSummary>,
    queueIndex: Int,
    onQueueItem: (Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onQueueClearUpcoming: () -> Unit,
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
    val context = LocalContext.current
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showQueue by remember { mutableStateOf(false) }
    var draggedQueueId by remember { mutableStateOf<String?>(null) }
    var draggedQueueIndex by remember { mutableIntStateOf(-1) }
    var draggedQueueStartIndex by remember { mutableIntStateOf(-1) }
    var dragQueueY by remember { mutableFloatStateOf(0f) }
    var holdSeekPreviewMs by remember { mutableLongStateOf(-1L) }
    var fullscreenVideo by remember { mutableStateOf(false) }
    var fullscreenControlsVisible by remember { mutableStateOf(true) }
    var fullscreenControlsEpoch by remember { mutableIntStateOf(0) }
    var fullscreenSeeking by remember { mutableStateOf(false) }
    var fullscreenSeekPreviewMs by remember { mutableLongStateOf(-1L) }
    var fullscreenSpeedMenu by remember { mutableStateOf(false) }
    var fullscreenQualityMenu by remember { mutableStateOf(false) }
    var fullscreenSleepMenu by remember { mutableStateOf(false) }
    val playerScrollState = rememberScrollState()
    val queueScrollState = rememberScrollState()
    val dragScope = rememberCoroutineScope()
    LaunchedEffect(controller, media.id) {
        while (true) {
            position = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = controller?.duration?.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    val activity = context as? Activity
    val originalOrientation = remember(activity) { activity?.requestedOrientation }

    DisposableEffect(fullscreenVideo, activity) {
        if (fullscreenVideo && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null) {
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)
                activity.requestedOrientation =
                    originalOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    BackHandler(enabled = fullscreenVideo) {
        fullscreenVideo = false
    }

    LaunchedEffect(
        fullscreenVideo,
        fullscreenControlsVisible,
        fullscreenControlsEpoch,
        fullscreenSpeedMenu,
        fullscreenQualityMenu,
        fullscreenSleepMenu
    ) {
        if (
            fullscreenVideo &&
            fullscreenControlsVisible &&
            !fullscreenSpeedMenu &&
            !fullscreenQualityMenu &&
            !fullscreenSleepMenu
        ) {
            delay(2_500L)
            fullscreenControlsVisible = false
        }
    }

    if (fullscreenVideo && videoMode && controller != null) {
        val fullscreenController = controller
        val fullscreenDisplayedPosition =
            fullscreenSeekPreviewMs.takeIf { it >= 0L } ?: position
        val fullscreenProgress = if (duration > 0L) {
            (fullscreenDisplayedPosition.toFloat() / duration).coerceIn(0f, 1f)
        } else 0f

        Box(
            Modifier.fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
        ) {
            AndroidView(
                factory = { viewContext ->
                    androidx.media3.ui.PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = fullscreenController
                    }
                },
                update = { view ->
                    view.player = fullscreenController
                    view.useController = false
                    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize()
            )

            // Fullscreen surface gestures:
            // single tap toggles controls; double tap seeks 5s; hold seeks continuously.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(fullscreenController, duration) {
                        var lastTapAt = 0L
                        var lastTapForward = false
                        var singleTapJob: kotlinx.coroutines.Job? = null

                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startedAt = down.uptimeMillis
                            val forward = down.position.x >= size.width / 2f
                            var longPress = false
                            var lastSeekAt = startedAt
                            var released = false

                            while (!released) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val now = change.uptimeMillis

                                if (!longPress &&
                                    now - startedAt >= viewConfiguration.longPressTimeoutMillis
                                ) {
                                    longPress = true
                                    singleTapJob?.cancel()
                                    fullscreenControlsVisible = false
                                    fullscreenSeeking = true
                                    fullscreenSeekPreviewMs =
                                        fullscreenController.currentPosition.coerceAtLeast(0L)
                                }

                                if (longPress && now - lastSeekAt >= 180L) {
                                    val current = fullscreenSeekPreviewMs
                                        .takeIf { it >= 0L }
                                        ?: fullscreenController.currentPosition.coerceAtLeast(0L)
                                    val target = if (forward) {
                                        val endPosition = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                        (current + 2_000L).coerceAtMost(endPosition)
                                    } else {
                                        (current - 2_000L).coerceAtLeast(0L)
                                    }
                                    fullscreenSeekPreviewMs = target
                                    fullscreenController.seekTo(target)
                                    lastSeekAt = now
                                }

                                released = change.changedToUpIgnoreConsumed()
                                change.consume()
                            }

                            if (longPress) {
                                fullscreenSeeking = false
                                fullscreenSeekPreviewMs = -1L
                            } else {
                                val releasedAt = android.os.SystemClock.uptimeMillis()
                                val isDoubleTap =
                                    lastTapAt > 0L &&
                                    releasedAt - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                                    forward == lastTapForward

                                if (isDoubleTap) {
                                    singleTapJob?.cancel()
                                    singleTapJob = null
                                    lastTapAt = 0L

                                    val current =
                                        fullscreenController.currentPosition.coerceAtLeast(0L)
                                    val target = if (forward) {
                                        val endPosition = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                        (current + 5_000L).coerceAtMost(endPosition)
                                    } else {
                                        (current - 5_000L).coerceAtLeast(0L)
                                    }
                                    fullscreenController.seekTo(target)
                                    fullscreenSeekPreviewMs = target
                                    fullscreenSeeking = true

                                    dragScope.launch {
                                        delay(650L)
                                        fullscreenSeeking = false
                                        fullscreenSeekPreviewMs = -1L
                                    }
                                } else {
                                    lastTapAt = releasedAt
                                    lastTapForward = forward
                                    singleTapJob?.cancel()
                                    singleTapJob = dragScope.launch {
                                        delay(viewConfiguration.doubleTapTimeoutMillis.toLong())
                                        fullscreenControlsVisible = !fullscreenControlsVisible
                                        if (fullscreenControlsVisible) fullscreenControlsEpoch += 1
                                        lastTapAt = 0L
                                    }
                                }
                            }
                        }
                    }
            )

            if (fullscreenControlsVisible) {
                Row(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 46.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box {
                        Surface(
                            modifier = Modifier.height(28.dp).clickable {
                                fullscreenSpeedMenu = !fullscreenSpeedMenu
                                fullscreenQualityMenu = false
                                fullscreenSleepMenu = false
                                fullscreenControlsEpoch += 1
                            },
                            shape = RoundedCornerShape(50),
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
                        ) {
                            Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    when (playbackSpeed) {
                                        0.5f -> "0.5×"
                                        1f -> "1×"
                                        1.25f -> "1.25×"
                                        1.5f -> "1.5×"
                                        else -> "2×"
                                    },
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        DropdownMenu(expanded = fullscreenSpeedMenu, onDismissRequest = { fullscreenSpeedMenu = false }) {
                            listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (playbackSpeed == option) "✓ " else "") +
                                                when (option) {
                                                    0.5f -> "0.5×"
                                                    1f -> "1×"
                                                    1.25f -> "1.25×"
                                                    1.5f -> "1.5×"
                                                    else -> "2×"
                                                }
                                        )
                                    },
                                    onClick = {
                                        fullscreenSpeedMenu = false
                                        onSpeed(option)
                                        fullscreenControlsEpoch += 1
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        val displayedQuality = if (actualVideoHeight > 0) {
                            actualVideoHeight.toString() + "p"
                        } else {
                            when (videoQuality) {
                                VideoQuality.AUTO -> "Auto"
                                VideoQuality.P360 -> "360p"
                                VideoQuality.P480 -> "480p"
                                VideoQuality.P720 -> "720p"
                                VideoQuality.P1080 -> "1080p"
                            }
                        }
                        Surface(
                            modifier = Modifier.height(28.dp).clickable {
                                fullscreenQualityMenu = !fullscreenQualityMenu
                                fullscreenSpeedMenu = false
                                fullscreenSleepMenu = false
                                fullscreenControlsEpoch += 1
                            },
                            shape = RoundedCornerShape(50),
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
                        ) {
                            Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    displayedQuality,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        DropdownMenu(expanded = fullscreenQualityMenu, onDismissRequest = { fullscreenQualityMenu = false }) {
                            VideoQuality.entries.forEach { option ->
                                val label = when (option) {
                                    VideoQuality.AUTO -> "Auto"
                                    VideoQuality.P360 -> "360p"
                                    VideoQuality.P480 -> "480p"
                                    VideoQuality.P720 -> "720p"
                                    VideoQuality.P1080 -> "1080p"
                                }
                                DropdownMenuItem(
                                    text = { Text((if (videoQuality == option) "✓ " else "") + label) },
                                    onClick = {
                                        fullscreenQualityMenu = false
                                        onVideoQuality(option)
                                        fullscreenControlsEpoch += 1
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        Surface(
                            modifier = Modifier.height(28.dp).clickable {
                                fullscreenSleepMenu = !fullscreenSleepMenu
                                fullscreenSpeedMenu = false
                                fullscreenQualityMenu = false
                                fullscreenControlsEpoch += 1
                            },
                            shape = RoundedCornerShape(50),
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
                        ) {
                            Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    sleepMinutes?.let { it.toString() + "m" } ?: "Sleep",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        DropdownMenu(expanded = fullscreenSleepMenu, onDismissRequest = { fullscreenSleepMenu = false }) {
                            listOf<Int?>(null, 15, 30, 60, 120).forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (sleepMinutes == option) "✓ " else "") +
                                                (option?.let { it.toString() + " phút" } ?: "Tắt")
                                        )
                                    },
                                    onClick = {
                                        fullscreenSleepMenu = false
                                        onSleep(option)
                                        fullscreenControlsEpoch += 1
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.Center)
                        .offset(y = 92.dp)
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .pointerInput(hasPrevious, fullscreenController, duration) {
                                if (!hasPrevious) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val startedAt = down.uptimeMillis
                                    var longPress = false
                                    var preview = fullscreenController.currentPosition.coerceAtLeast(0L)
                                    var lastPreviewAt = startedAt
                                    var released = false
                                    while (!released) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val now = change.uptimeMillis
                                        if (!longPress && now - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                            longPress = true
                                        }
                                        if (longPress && now - lastPreviewAt >= 180L) {
                                            preview = (preview - 2_000L).coerceAtLeast(0L)
                                            lastPreviewAt = now
                                        }
                                        released = change.changedToUpIgnoreConsumed()
                                        change.consume()
                                    }
                                    if (longPress) fullscreenController.seekTo(preview) else onPrevious()
                                    fullscreenControlsEpoch += 1
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SkipPrevious,
                            "Previous / hold to rewind",
                            Modifier.size(26.dp),
                            tint = androidx.compose.ui.graphics.Color.White.copy(
                                alpha = if (hasPrevious) 1f else 0.38f
                            )
                        )
                    }

                    FilledTonalIconButton(
                        onClick = {
                            fullscreenController.seekBack()
                            fullscreenControlsEpoch += 1
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(Icons.Outlined.Replay10, "Back 10 seconds", Modifier.size(21.dp))
                    }

                    FilledIconButton(
                        onClick = {
                            onToggle()
                            fullscreenControlsEpoch += 1
                        },
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            Modifier.size(26.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = {
                            fullscreenController.seekForward()
                            fullscreenControlsEpoch += 1
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(Icons.Outlined.Forward10, "Forward 10 seconds", Modifier.size(21.dp))
                    }

                    Box(
                        Modifier
                            .size(42.dp)
                            .pointerInput(hasNext, fullscreenController, duration) {
                                if (!hasNext) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val startedAt = down.uptimeMillis
                                    var longPress = false
                                    var preview = fullscreenController.currentPosition.coerceAtLeast(0L)
                                    var lastPreviewAt = startedAt
                                    var released = false
                                    while (!released) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val now = change.uptimeMillis
                                        if (!longPress && now - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                            longPress = true
                                        }
                                        if (longPress && now - lastPreviewAt >= 180L) {
                                            val endPosition = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                            preview = (preview + 2_000L).coerceAtMost(endPosition)
                                            lastPreviewAt = now
                                        }
                                        released = change.changedToUpIgnoreConsumed()
                                        change.consume()
                                    }
                                    if (longPress) fullscreenController.seekTo(preview) else onNext()
                                    fullscreenControlsEpoch += 1
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SkipNext,
                            "Next / hold to fast-forward",
                            Modifier.size(26.dp),
                            tint = androidx.compose.ui.graphics.Color.White.copy(
                                alpha = if (hasNext) 1f else 0.38f
                            )
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = { fullscreenVideo = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(42.dp)
                ) {
                    Icon(Icons.Outlined.FullscreenExit, "Exit fullscreen", Modifier.size(24.dp))
                }
            }
            if (fullscreenControlsVisible || fullscreenSeeking) {
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.24f))
                    .padding(horizontal = 16.dp, vertical = 5.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(28.dp)
                        .pointerInput(duration) {
                            fun seek(x: Float) {
                                if (duration > 0L) {
                                    val fraction = (x / size.width).coerceIn(0f, 1f)
                                    fullscreenController.seekTo((duration * fraction).toLong())
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
                                fullscreenControlsEpoch += 1
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LinearProgressIndicator(
                        progress = { fullscreenProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatTime(fullscreenDisplayedPosition),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        formatTime(duration),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            }

        }
        return
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
            Modifier.weight(1f).verticalScroll(playerScrollState).padding(horizontal = 14.dp)
        ) {
            if (!showQueue) {
            Spacer(Modifier.height(8.dp))
            if (videoMode && controller != null) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AndroidView(
                        factory = { context ->
                            androidx.media3.ui.PlayerView(context).apply {
                                useController = false
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                player = controller
                            }
                        },
                        update = { view ->
                            view.player = controller
                            view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    FilledTonalIconButton(
                        onClick = { fullscreenVideo = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp)
                    ) {
                        Icon(Icons.Outlined.Fullscreen, "Fullscreen", Modifier.size(22.dp))
                    }
                }
            } else {
                AsyncImage(
                    media.thumbnailUrl, null,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(media.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
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

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    modifier = Modifier.height(32.dp),
                    selected = !videoMode,
                    onClick = { onVideoMode(false) },
                    label = { Text("AUDIO") },
                    leadingIcon = { Icon(Icons.Outlined.Headphones, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    modifier = Modifier.height(32.dp),
                    selected = videoMode,
                    onClick = { onVideoMode(true) },
                    label = { Text("VIDEO") },
                    leadingIcon = { Icon(Icons.Outlined.OndemandVideo, null, Modifier.size(16.dp)) }
                )
            }
            if (!videoMode) Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AudioQuality.entries.forEach { option ->
                    FilterChip(
                        modifier = Modifier.weight(1f).height(30.dp),
                        selected = quality == option,
                        onClick = { onQuality(option) },
                        label = {
                            Text(when (option) {
                                AudioQuality.DATA_SAVER -> "Saver 64"
                                AudioQuality.STANDARD -> "Std 128"
                                AudioQuality.HIGH -> "High 160+"
                            }, maxLines = 1)
                        }
                    )
                }
            }
            if (videoMode) Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VideoQuality.entries.forEach { option ->
                    FilterChip(
                        modifier = Modifier.weight(1f).height(30.dp),
                        selected = videoQuality == option,
                        onClick = { onVideoQuality(option) },
                        label = {
                            Text(
                                when (option) {
                                    VideoQuality.AUTO -> "Auto"
                                    VideoQuality.P360 -> "360p"
                                    VideoQuality.P480 -> "480p"
                                    VideoQuality.P720 -> "720p"
                                    VideoQuality.P1080 -> "1080p"
                                },
                                maxLines = 1
                            )
                        }
                    )
                }
            }
            if (!videoMode) {
                Text(
                    if (compatibilityFallback) "Compatibility stream · may use more data"
                    else "Audio-only · quality applies to next track",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (compatibilityFallback) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            playerError?.let {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    CompactPresetButton(
                        text = when (speed) {
                            0.5f -> "0.5×"
                            1f -> "1×"
                            1.25f -> "1.25×"
                            1.5f -> "1.5×"
                            else -> "2×"
                        },
                        selected = playbackSpeed == speed,
                        onClick = { onSpeed(speed) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(null, 15, 30, 60, 120).forEach { minutes ->
                    CompactPresetButton(
                        text = minutes?.let { "${it}m" } ?: "Off",
                        selected = sleepMinutes == minutes,
                        onClick = { onSleep(minutes) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = autoplay,
                    onClick = { onAutoplay(!autoplay) },
                    leadingIcon = { Icon(Icons.Outlined.SkipNext, null, Modifier.size(18.dp)) },
                    label = {
                        Text("Auto next", modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                    },
                    modifier = Modifier.weight(1f).height(30.dp)
                )
                FilterChip(
                    selected = nextMode == NextMode.RECOMMENDED,
                    onClick = {
                        onNextMode(if (nextMode == NextMode.LIST) NextMode.RECOMMENDED else NextMode.LIST)
                    },
                    leadingIcon = {
                        Icon(
                            if (nextMode == NextMode.LIST) Icons.AutoMirrored.Outlined.PlaylistPlay else Icons.Outlined.Shuffle,
                            null,
                            Modifier.size(18.dp)
                        )
                    },
                    label = {
                        Text(
                            "Next",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.weight(1f).height(30.dp)
                )
                FilterChip(
                    selected = repeatMode != RepeatMode.OFF,
                    onClick = {
                        onRepeatMode(
                            when (repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.OFF
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                            null,
                            Modifier.size(18.dp)
                        )
                    },
                    label = {
                        Text(
                            when (repeatMode) {
                                RepeatMode.OFF -> "Repeat off"
                                RepeatMode.ONE -> "Repeat 1"
                                RepeatMode.ALL -> "Repeat all"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.weight(1f).height(30.dp)
                )
            }

            }
            Spacer(Modifier.height(if (showQueue) 2.dp else 8.dp))
            val displayedPosition = holdSeekPreviewMs.takeIf { it >= 0L } ?: position
            val progress = if (duration > 0) {
                (displayedPosition.toFloat() / duration).coerceIn(0f, 1f)
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
                Text(formatTime(displayedPosition), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showQueue = !showQueue },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (showQueue) "Hide queue" else "Up next")
                }
                Spacer(Modifier.weight(1f))
                if (showQueue && queueIndex >= 0 && queueIndex < queue.lastIndex) {
                    TextButton(
                        onClick = onQueueClearUpcoming,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Clear upcoming", style = MaterialTheme.typography.labelMedium) }
                } else if (queue.isNotEmpty()) {
                    Text(
                        "${(queueIndex + 1).coerceAtLeast(1)} / ${queue.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showQueue) {
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .verticalScroll(queueScrollState)
                            .padding(vertical = 5.dp)
                    ) {
                        queue.forEachIndexed { index, item ->
                            key(item.id) {
                            val current = index == queueIndex
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !current) { onQueueItem(index) }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    item.thumbnailUrl, null,
                                    Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        item.channel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (current) {
                                    Icon(Icons.Outlined.GraphicEq, "Playing", Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    Icon(
                                        Icons.Outlined.DragHandle,
                                        "Drag to reorder",
                                        Modifier
                                            .size(32.dp)
                                            .pointerInput(item.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        draggedQueueId = item.id
                                                        draggedQueueIndex = queue.indexOfFirst { it.id == item.id }
                                                        draggedQueueStartIndex = draggedQueueIndex
                                                        dragQueueY = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggedQueueId = null
                                                        draggedQueueIndex = -1
                                                        draggedQueueStartIndex = -1
                                                        dragQueueY = 0f
                                                    },
                                                    onDragEnd = {
                                                        draggedQueueId = null
                                                        draggedQueueIndex = -1
                                                        draggedQueueStartIndex = -1
                                                        dragQueueY = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        if (draggedQueueId != item.id) return@detectDragGesturesAfterLongPress
                                                        dragQueueY += dragAmount.y
                                                        val rowHeight = 54.dp.toPx()
                                                        val deltaRows = (dragQueueY / rowHeight).toInt()
                                                        val target = (draggedQueueStartIndex + deltaRows)
                                                            .coerceIn(0, queue.lastIndex)
                                                        if (draggedQueueIndex >= 0 && target != draggedQueueIndex) {
                                                            onQueueMove(draggedQueueIndex, target)
                                                            draggedQueueIndex = target
                                                        }

                                                        // Keep exposing more queue while the finger continues
                                                        // beyond roughly two rows. Reorder target is based on the
                                                        // gesture's total displacement, not on the mutated row.
                                                        if (dragQueueY > rowHeight * 2 && queueScrollState.value < queueScrollState.maxValue) {
                                                            dragScope.launch {
                                                                queueScrollState.scrollTo(
                                                                    (queueScrollState.value + rowHeight.toInt()).coerceAtMost(queueScrollState.maxValue)
                                                                )
                                                            }
                                                        } else if (dragQueueY < -rowHeight * 2 && queueScrollState.value > 0) {
                                                            dragScope.launch {
                                                                queueScrollState.scrollTo(
                                                                    (queueScrollState.value - rowHeight.toInt()).coerceAtLeast(0)
                                                                )
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            .padding(6.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(onClick = { onQueueRemove(index) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Outlined.Close, "Remove from queue", Modifier.size(18.dp))
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }

        }
        Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Column {
                if (isBuffering) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .pointerInput(hasPrevious, controller, duration) {
                                if (!hasPrevious) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val startedAt = down.uptimeMillis
                                    var longPress = false
                                    var preview = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                    var lastPreviewAt = startedAt
                                    var released = false
                                    while (!released) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val now = change.uptimeMillis
                                        if (!longPress && now - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                            longPress = true
                                            holdSeekPreviewMs = preview
                                        }
                                        if (longPress && now - lastPreviewAt >= 180L) {
                                            preview = (preview - 2_000L).coerceAtLeast(0L)
                                            holdSeekPreviewMs = preview
                                            lastPreviewAt = now
                                        }
                                        released = change.changedToUpIgnoreConsumed()
                                        change.consume()
                                    }
                                    if (longPress) {
                                        controller?.seekTo(preview)
                                        holdSeekPreviewMs = -1L
                                    } else {
                                        onPrevious()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SkipPrevious,
                            "Previous / hold to rewind",
                            tint = if (hasPrevious) LocalContentColor.current
                            else LocalContentColor.current.copy(alpha = 0.38f)
                        )
                    }
                    FilledTonalIconButton(onClick = { controller?.seekBack() }) {
                        Icon(Icons.Outlined.Replay10, "Back 10 seconds")
                    }
                    FilledIconButton(onClick = onToggle, modifier = Modifier.size(58.dp)) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    FilledTonalIconButton(onClick = { controller?.seekForward() }) {
                        Icon(Icons.Outlined.Forward10, "Forward 10 seconds")
                    }
                    Box(
                        Modifier
                            .size(48.dp)
                            .pointerInput(hasNext, controller, duration) {
                                if (!hasNext) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val startedAt = down.uptimeMillis
                                    var longPress = false
                                    var preview = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                    var lastPreviewAt = startedAt
                                    var released = false
                                    while (!released) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val now = change.uptimeMillis
                                        if (!longPress && now - startedAt >= viewConfiguration.longPressTimeoutMillis) {
                                            longPress = true
                                            holdSeekPreviewMs = preview
                                        }
                                        if (longPress && now - lastPreviewAt >= 180L) {
                                            val end = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                            preview = (preview + 2_000L).coerceAtMost(end)
                                            holdSeekPreviewMs = preview
                                            lastPreviewAt = now
                                        }
                                        released = change.changedToUpIgnoreConsumed()
                                        change.consume()
                                    }
                                    if (longPress) {
                                        controller?.seekTo(preview)
                                        holdSeekPreviewMs = -1L
                                    } else {
                                        onNext()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SkipNext,
                            "Next / hold to fast-forward",
                            tint = if (hasNext) LocalContentColor.current
                            else LocalContentColor.current.copy(alpha = 0.38f)
                        )
                    }
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
private fun CompactPresetButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(30.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
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
