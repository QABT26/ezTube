package com.qabt.eztube.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.ComponentName
import android.widget.Toast
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.qabt.eztube.BuildConfig
import com.qabt.eztube.history.EzTubeDatabase
import com.qabt.eztube.history.HistoryEntry
import com.qabt.eztube.history.FavoriteEntry
import com.qabt.eztube.history.FavoriteRepository
import com.qabt.eztube.history.WatchLaterEntry
import com.qabt.eztube.history.WatchLaterRepository
import com.qabt.eztube.history.HistoryRepository
import com.qabt.eztube.history.PersonalPlaylist
import com.qabt.eztube.history.PersonalPlaylistStore
import com.qabt.eztube.history.toMediaSummary
import com.qabt.eztube.history.progress
import com.qabt.eztube.playback.AudioQuality
import com.qabt.eztube.playback.VideoQuality
import com.qabt.eztube.playback.PlaybackService
import com.qabt.eztube.playback.PlaybackPreferences
import com.qabt.eztube.playback.PlaybackSource
import com.qabt.eztube.playback.PlaybackSourceResolver
import com.qabt.eztube.playback.PlaybackRuntimePolicy
import com.qabt.eztube.playback.PlayerPresentationPolicy
import com.qabt.eztube.playback.QueueLimitPolicy
import com.qabt.eztube.playback.NetworkType
import com.qabt.eztube.playback.NetworkEnvironment
import com.qabt.eztube.playback.NetworkUsageTracker
import com.qabt.eztube.playback.DataUsageSnapshot
import com.qabt.eztube.playback.StorageSnapshot
import com.qabt.eztube.playback.StorageUsage
import com.qabt.eztube.playback.MobileDataPriority
import com.qabt.eztube.movie.MovieProviderRegistry
import com.qabt.eztube.movie.MovieDetail
import com.qabt.eztube.youtube.MediaSummary
import com.qabt.eztube.youtube.ChannelSummary
import com.qabt.eztube.youtube.PlaylistSummary
import com.qabt.eztube.youtube.PlaylistDetail
import com.qabt.eztube.youtube.NewPipeYouTubeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library") }
private enum class SearchSourceMode { YOUTUBE, MOVIE }
private data class SubtitleOption(val label: String, val language: String?)
private enum class RepeatMode { OFF, ONE, ALL }
private enum class NextMode { LIST, RECOMMENDED }
private enum class SearchSort(val label: String) {
    RELEVANCE("Relevance"), NEWEST("Newest"), VIEWS("Views"), DURATION("Duration")
}

private val MediaActionButtonSize = 24.dp
private val MediaActionIconSize = 16.dp

@Composable
fun EzTubeApp(
    externalMedia: MediaSummary? = null,
    onExternalMediaConsumed: () -> Unit = {},
    isInPictureInPictureMode: Boolean = false
) {
    var selected by remember { mutableStateOf(Tab.SEARCH) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { NewPipeYouTubeSource() }
    val movieProviders = remember { MovieProviderRegistry.default() }
    val database = remember { EzTubeDatabase.get(context) }
    val history = remember { HistoryRepository(database.historyDao()) }
    val favoritesRepo = remember { FavoriteRepository(database.favoriteDao()) }
    val watchLaterRepo = remember { WatchLaterRepository(database.watchLaterDao()) }
    val recent by history.recent.collectAsState(initial = emptyList())
    val favorites by favoritesRepo.all.collectAsState(initial = emptyList())
    val watchLater by watchLaterRepo.all.collectAsState(initial = emptyList())

    LaunchedEffect(watchLater, recent) {
        if (watchLater.isEmpty() || recent.isEmpty()) return@LaunchedEffect
        val progressById = recent.associateBy { it.mediaId }
        val watchedIds = watchLater.mapNotNull { entry ->
            progressById[entry.mediaId]
                ?.takeIf { it.durationMs > 0L && it.progress >= 0.95f }
                ?.mediaId
        }
        if (watchedIds.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                watchLaterRepo.removeMany(watchedIds)
            }
        }
    }

    val playbackPrefs = remember { PlaybackPreferences(context) }
    val networkUsageTracker = remember { NetworkUsageTracker(context) }
    var networkType by remember { mutableStateOf(NetworkEnvironment.type(context)) }
    var dataUsage by remember { mutableStateOf(networkUsageTracker.sample()) }
    var storageSnapshot by remember { mutableStateOf(StorageUsage.snapshot(context)) }
    var mobilePlaybackAllowed by remember {
        mutableStateOf(playbackPrefs.loadMobilePlaybackAllowed())
    }
    var dataSaverEnabled by remember {
        mutableStateOf(playbackPrefs.loadDataSaverEnabled())
    }
    var mobileDataPriority by remember {
        mutableStateOf(playbackPrefs.loadMobileDataPriority())
    }
    var mobileDataLimitMb by remember {
        mutableLongStateOf(playbackPrefs.loadMobileDataLimitMb())
    }
    var preloadWifi by remember { mutableStateOf(playbackPrefs.loadPreloadWifi()) }
    var preloadMobile by remember { mutableStateOf(playbackPrefs.loadPreloadMobile()) }
    var warnMobileVideo by remember { mutableStateOf(playbackPrefs.loadWarnMobileVideo()) }
    val personalPlaylistStore = remember { PersonalPlaylistStore(context) }
    var personalPlaylists by remember { mutableStateOf(personalPlaylistStore.load()) }
    var queue by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var queueIndex by remember { mutableIntStateOf(-1) }
    var playbackSpeed by remember { mutableFloatStateOf(playbackPrefs.loadSpeed()) }
    var compatibilityFallback by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var sleepMinutes by remember {
        mutableStateOf<Int?>(if (playbackPrefs.loadSleepEndCurrent()) -1 else null)
    }
    var shuffleEnabled by remember { mutableStateOf(playbackPrefs.loadShuffle()) }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var nowPlaying by remember { mutableStateOf<MediaSummary?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(playbackPrefs.loadQuality()) }
    var videoQuality by remember { mutableStateOf(playbackPrefs.loadVideoQuality()) }
    var actualVideoHeight by remember { mutableIntStateOf(0) }
    var playbackEngine by remember { mutableStateOf(PlaybackService.ENGINE_DIRECT) }
    var movieDiagnostic by remember { mutableStateOf<String?>(null) }
    var sabrDiagnostic by remember { mutableStateOf<String?>(null) }
    var developerUnlocked by remember {
        mutableStateOf(playbackPrefs.loadDeveloperUnlocked())
    }
    var developerMode by remember {
        mutableStateOf(
            playbackPrefs.loadDeveloperMode() &&
                playbackPrefs.loadDeveloperUnlocked()
        )
    }
    var subtitleOptions by remember { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var selectedSubtitleLabel by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(Unit) {
        while (true) {
            networkType = NetworkEnvironment.type(context)
            dataUsage = networkUsageTracker.sample()
            delay(2_000L)
        }
    }
    LaunchedEffect(showSettings) {
        if (showSettings) {
            dataUsage = networkUsageTracker.sample()
            storageSnapshot = withContext(Dispatchers.IO) { StorageUsage.snapshot(context) }
        }
    }

    var resolvingId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var recentSearches by remember { mutableStateOf(playbackPrefs.loadRecentSearches()) }
    var searchSourceMode by remember {
        mutableStateOf(
            runCatching { SearchSourceMode.valueOf(playbackPrefs.loadSearchSourceMode()) }
                .getOrDefault(SearchSourceMode.YOUTUBE)
        )
    }
    var recommendationMode by remember {
        mutableStateOf(
            runCatching { SearchSourceMode.valueOf(playbackPrefs.loadRecommendationMode()) }
                .getOrDefault(SearchSourceMode.YOUTUBE)
        )
    }
    var homeSuggestions by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var homeLoading by remember { mutableStateOf(false) }
    var movieSuggestions by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var movieSuggestionsLoading by remember { mutableStateOf(false) }
    var trending by remember { mutableStateOf<List<MediaSummary>>(emptyList()) }
    var trendingLoading by remember { mutableStateOf(false) }
    var homeRefreshToken by remember { mutableIntStateOf(0) }
    var trendingTopics by remember { mutableStateOf(playbackPrefs.loadTrendingTopics()) }
    var trendingLanguage by remember { mutableStateOf(playbackPrefs.loadTrendingLanguage()) }
    var metadataLanguage by remember { mutableStateOf(playbackPrefs.loadMetadataLanguage()) }
    var contentRegion by remember { mutableStateOf(playbackPrefs.loadContentRegion()) }
    val searchListState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    var channelDetail by remember { mutableStateOf<ChannelSummary?>(null) }
    var channelLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var playlistDetail by remember { mutableStateOf<PlaylistDetail?>(null) }
    var playlistLoading by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var movieDetail by remember { mutableStateOf<MovieDetail?>(null) }
    var movieDetailLoading by remember { mutableStateOf(false) }
    var movieDetailError by remember { mutableStateOf<String?>(null) }
    var movieDetailFocusId by remember { mutableStateOf<String?>(null) }

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
                        playbackEngine = mediaItem.mediaMetadata.extras
                            ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
                            ?: PlaybackService.ENGINE_DIRECT
                        movieDiagnostic = mediaItem.mediaMetadata.extras
                            ?.getString(PlaybackService.EXTRA_MOVIE_DIAGNOSTIC)
                        val saved = playbackPrefs.loadQueue()
                        val media = saved?.first?.firstOrNull { it.id == id }
                            ?: playbackPrefs.load()?.first?.takeIf { it.id == id }
                            ?: return
                        nowPlaying = normalizeMoviePresentation(media)
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
                        playbackEngine = mediaMetadata.extras
                            ?.getString(PlaybackService.EXTRA_PLAYBACK_ENGINE)
                            ?: PlaybackService.ENGINE_DIRECT
                        movieDiagnostic = mediaMetadata.extras
                            ?.getString(PlaybackService.EXTRA_MOVIE_DIAGNOSTIC)
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val selectedHeight = tracks.groups
                            .asSequence()
                            .flatMap { group ->
                                (0 until group.length).asSequence()
                                    .filter { index -> group.isTrackSelected(index) }
                                    .map { index -> group.getTrackFormat(index) }
                            }
                            .filter { format ->
                                format.sampleMimeType?.startsWith("video/") == true
                            }
                            .map { it.height }
                            .filter { it > 0 }
                            .maxOrNull()
                        if (selectedHeight != null) {
                            actualVideoHeight = selectedHeight
                        }

                        val subtitles = mutableListOf<SubtitleOption>()
                        var selectedText: String? = null
                        tracks.groups.forEach { group ->
                            for (index in 0 until group.length) {
                                val format = group.getTrackFormat(index)
                                val isText =
                                    format.sampleMimeType?.startsWith("text/") == true ||
                                    format.sampleMimeType == "application/x-subrip" ||
                                    format.sampleMimeType == "application/ttml+xml"
                                if (!isText) continue
                                val label = format.label
                                    ?: format.language?.uppercase()
                                    ?: "CC"
                                val option = SubtitleOption(label, format.language)
                                if (subtitles.none { it.label == option.label && it.language == option.language }) {
                                    subtitles += option
                                }
                                if (group.isTrackSelected(index)) {
                                    selectedText = label
                                }
                            }
                        }
                        subtitleOptions = subtitles
                        selectedSubtitleLabel = selectedText
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playerError = error.message ?: "Playback error"
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = state == Player.STATE_BUFFERING
                        isPlaying = mediaController.isPlaying
                        if (state == Player.STATE_READY) {
                            playerError = null
                            errorMessage = null
                        }
                        if (state == Player.STATE_ENDED && sleepMinutes == -1) {
                            // PlaybackQueueManager clears the persisted flag when the
                            // armed "end of current item" condition fires. Mirror that
                            // completion immediately in Compose so the sleep UI does not
                            // stay highlighted after playback has stopped.
                            sleepMinutes = null
                        }
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

    fun openMovie(
        media: MediaSummary,
        focusEpisodeId: String? = null,
        onSingleMovie: (MediaSummary, List<MediaSummary>) -> Unit
    ) {
        if (!movieProviders.isTitle(media.id) || movieDetailLoading) return
        scope.launch {
            movieDetailLoading = true
            movieDetailError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    movieProviders.detail(media.id, media.title)
                }
            }
                .onSuccess { detail ->
                    val playableItems = detail.episodes.map { it.toMediaSummary() }
                    val uniqueEpisodeKeys = detail.episodes
                        .map { it.episodeSlug.ifBlank { it.episodeName } }
                        .distinct()

                    val isSeries = detail.catalog.isSeries
                        ?: (uniqueEpisodeKeys.size > 1)

                    if (isSeries) {
                        movieDetailFocusId = focusEpisodeId
                        movieDetail = detail
                    } else {
                        movieDetailFocusId = null
                        movieDetail = null
                        movieDetailError = null
                        val first = playableItems.firstOrNull()
                        if (first != null) {
                            // Publish the selected movie before opening the full player.
                            // Otherwise the UI can briefly reopen the previously played item
                            // while the new movie is still resolving in the playback service.
                            nowPlaying = first
                            playerError = null
                            errorMessage = null
                            movieDiagnostic = null
                            showPlayer = true

                            // A single movie can be mirrored on multiple servers.
                            // Keep only the selected playable item in the logical queue so
                            // autoplay/next cannot jump to another mirror of the same movie.
                            onSingleMovie(first, listOf(first))
                        } else {
                            movieDetailError = "Movie has no playable item"
                        }
                    }
                }
                .onFailure { movieDetailError = it.message ?: "Unable to load movie" }
            movieDetailLoading = false
        }
    }

    fun selectMovieSubtitle(option: SubtitleOption?) {
        val active = controller ?: return
        val builder = active.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)

        if (option == null) {
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            active.trackSelectionParameters = builder.build()
            selectedSubtitleLabel = null
            return
        }

        builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
        var overrideApplied = false
        active.currentTracks.groups.forEach { group ->
            if (overrideApplied) return@forEach
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val label = format.label ?: format.language?.uppercase() ?: "CC"
                if (label == option.label && format.language == option.language) {
                    builder.setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(
                            group.mediaTrackGroup,
                            index
                        )
                    )
                    overrideApplied = true
                    break
                }
            }
        }
        active.trackSelectionParameters = builder.build()
        selectedSubtitleLabel = option.label
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

    fun moveQueue(delta: Int) {
        val active = controller ?: return
        if (delta == 0) return
        active.sendCustomCommand(
            androidx.media3.session.SessionCommand(
                PlaybackService.COMMAND_MOVE_QUEUE,
                android.os.Bundle.EMPTY
            ),
            android.os.Bundle().apply {
                putInt(PlaybackService.ARG_DELTA, delta)
            }
        )
    }

    fun queueForStart(
        media: MediaSummary,
        sourceItems: List<MediaSummary>,
        preserveSourceOrder: Boolean = false
    ): List<MediaSummary> {
        // Explicit user collections (Watch later / playlists / Play all) keep their
        // stored order regardless of the global Recommended next-mode.
        val candidate = when {
            preserveSourceOrder -> sourceItems.distinctBy { it.id }
            // Movie episode queues represent an ordered series/server. Never mix or
            // shuffle them with recommendations; Next/Previous must stay episodic.
            media.id.startsWith("movie:") -> sourceItems.distinctBy { it.id }
            nextMode == NextMode.LIST -> sourceItems.distinctBy { it.id }
            else -> {
                val pool = (sourceItems + homeSuggestions + trending + searchResults)
                    .distinctBy { it.id }
                    .filterNot { it.id == media.id }
                if (pool.isEmpty()) {
                    listOf(media)
                } else {
                    val seed = (media.id.hashCode().toLong() shl 32) xor System.nanoTime()
                    listOf(media) + pool.shuffled(kotlin.random.Random(seed))
                }
            }
        }
        return QueueLimitPolicy.bound(candidate, media.id)
    }

    fun notifyQueueChanged() {
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.COMMAND_QUEUE_CHANGED, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY
        )
    }

    fun applyVideoQualityWithoutGuessingEngine() {
        val active = controller ?: return
        val args = android.os.Bundle().apply {
            putLong(PlaybackService.ARG_POSITION_MS, active.currentPosition.coerceAtLeast(0L))
            putBoolean(PlaybackService.ARG_PLAY_WHEN_READY, active.playWhenReady)
        }
        val future = active.sendCustomCommand(
            androidx.media3.session.SessionCommand(
                PlaybackService.COMMAND_SET_VIDEO_QUALITY,
                android.os.Bundle.EMPTY
            ),
            args
        )
        future.addListener({
            runCatching { future.get() }.onSuccess { result ->
                playbackEngine = result.extras.getString(
                    PlaybackService.EXTRA_PLAYBACK_ENGINE
                ) ?: playbackEngine
            }
        }, context.mainExecutor)
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

        when {
            media.id.startsWith("movie:") -> {
                if (!videoMode) {
                    videoMode = true
                    playbackPrefs.saveVideoMode(true)
                }
            }
            PlaybackSourceResolver.resolve(media.id) is PlaybackSource.Hls ||
                PlaybackSourceResolver.resolve(media.id) is PlaybackSource.Dash -> {
                if (!videoMode) {
                    videoMode = true
                    playbackPrefs.saveVideoMode(true)
                }
            }
            else -> Unit
        }
        val active = controller
        if (active == null) {
            errorMessage = "Playback service is not ready yet"
            return
        }

        if (
            warnMobileVideo &&
            networkType == NetworkType.CELLULAR &&
            videoMode
        ) {
            Toast.makeText(
                context,
                "Playing video over mobile data",
                Toast.LENGTH_SHORT
            ).show()
        }

        resolvingId = media.id
        errorMessage = null

        // Publish the user's selection immediately. Navigation/presentation must not
        // wait for extractor/network/SABR resolution; the player can show loading/error
        // state while COMMAND_PLAY_CURRENT resolves in the service.
        nowPlaying = media
        resumePositionMs = startPositionMs.coerceAtLeast(0L)

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

    fun startQueue(
        media: MediaSummary,
        sourceItems: List<MediaSummary>,
        startPositionMs: Long = 0L,
        preserveSourceOrder: Boolean = false
    ) {
        queue = queueForStart(media, sourceItems, preserveSourceOrder)
        queueIndex = queue.indexOfFirst { it.id == media.id }.coerceAtLeast(0)
        playbackPrefs.saveQueue(queue, queueIndex)

        // Decide presentation before playback resolve starts so VIDEO/Movie/local video
        // responds to the tap immediately. AUDIO mode intentionally stays in context
        // and surfaces the same immediate selection through MiniPlayer.
        // YouTube in VIDEO mode opens the full Now Playing screen immediately.
        // YouTube in AUDIO mode keeps the user in context and uses MiniPlayer.
        // Movie/local video are video-first and therefore open Now Playing.
        val isMovie = media.id.startsWith("movie:")
        val isLocalVideo = media.channel == "Local video"
        showPlayer = PlayerPresentationPolicy.shouldOpenFullPlayer(
            isMovie = isMovie,
            isLocalVideo = isLocalVideo,
            videoMode = videoMode
        )

        playMedia(media, startPositionMs)
    }

    LaunchedEffect(externalMedia?.id, controller) {
        val media = externalMedia ?: return@LaunchedEffect
        if (controller == null) return@LaunchedEffect
        val localVideo = media.channel == "Local video"
        if (videoMode != localVideo) {
            videoMode = localVideo
            playbackPrefs.saveVideoMode(localVideo)
        }
        startQueue(media, listOf(media))
        onExternalMediaConsumed()
    }

    fun continueMovieFromHistory(entry: HistoryEntry) {
        if (!entry.mediaId.startsWith("movie:")) return

        val parts = entry.mediaId.split(':', limit = 5)
        val providerId = parts.getOrNull(1).orEmpty()
        val movieSlug = parts.getOrNull(2).orEmpty()
        if (providerId.isBlank() || movieSlug.isBlank()) {
            val fallback = entry.toMediaSummary()
            startQueue(
                fallback,
                listOf(fallback),
                startPositionMs = entry.positionMs.coerceAtLeast(0L)
            )
            showPlayer = true
            return
        }

        scope.launch {
            val detail = runCatching {
                withContext(Dispatchers.IO) {
                    movieProviders.detail(
                        mediaId = "movie-title:$providerId:$movieSlug",
                        mediaTitle = movieLookupTitle(entry.title)
                    )
                }
            }.getOrNull()

            if (detail == null) {
                val fallback = entry.toMediaSummary()
                startQueue(
                    fallback,
                    listOf(fallback),
                    startPositionMs = entry.positionMs.coerceAtLeast(0L)
                )
                showPlayer = true
                return@launch
            }

            val selectedRef = detail.episodes.firstOrNull {
                it.stableId == entry.mediaId
            }
            if (selectedRef == null) {
                val fallback = entry.toMediaSummary()
                startQueue(
                    fallback,
                    listOf(fallback),
                    startPositionMs = entry.positionMs.coerceAtLeast(0L)
                )
                showPlayer = true
                return@launch
            }

            // Continue Watching must restore the logical episode list, not a one-item
            // queue. Keep only the selected server because alternate servers are mirrors,
            // while episodes inside that server are the real sequential queue.
            val serverQueue = detail.episodes
                .filter { it.serverName == selectedRef.serverName }
                .map { it.toMediaSummary() }
                .distinctBy { it.id }

            val selectedMedia = serverQueue.firstOrNull {
                it.id == entry.mediaId
            } ?: selectedRef.toMediaSummary()

            startQueue(
                selectedMedia,
                serverQueue.ifEmpty { listOf(selectedMedia) },
                startPositionMs = entry.positionMs.coerceAtLeast(0L)
            )
            showPlayer = true
        }
    }

    fun openMovieFromHistory(entry: HistoryEntry) {
        if (!entry.mediaId.startsWith("movie:") || movieDetailLoading) return
        val providerId = entry.mediaId.split(':').getOrNull(1).orEmpty()
        val lookupTitle = movieLookupTitle(entry.title)
        if (providerId.isBlank() || lookupTitle.isBlank()) return

        scope.launch {
            movieDetailLoading = true
            movieDetailError = null
            val titleMedia = runCatching {
                withContext(Dispatchers.IO) {
                    movieProviders.search(lookupTitle)
                        .firstOrNull {
                            it.id.startsWith("movie-title:$providerId:")
                        }
                        ?: movieProviders.search(lookupTitle).firstOrNull()
                }
            }.getOrNull()

            movieDetailLoading = false
            if (titleMedia == null) {
                movieDetailError = "Unable to reopen movie detail"
                return@launch
            }

            openMovie(
                media = titleMedia,
                focusEpisodeId = entry.mediaId
            ) { single, items ->
                startQueue(
                    single,
                    items,
                    startPositionMs = entry.positionMs.coerceAtLeast(0L)
                )
            }
        }
    }

    fun openMovieFromFavorite(entry: FavoriteEntry) {
        if (!entry.mediaId.startsWith("movie:") || movieDetailLoading) return
        val providerId = entry.mediaId.split(':').getOrNull(1).orEmpty()
        val lookupTitle = movieLookupTitle(entry.title)
        if (providerId.isBlank() || lookupTitle.isBlank()) return

        scope.launch {
            movieDetailLoading = true
            movieDetailError = null
            val titleMedia = runCatching {
                withContext(Dispatchers.IO) {
                    val results = movieProviders.search(lookupTitle)
                    results.firstOrNull {
                        it.id.startsWith("movie-title:$providerId:")
                    } ?: results.firstOrNull()
                }
            }.getOrNull()

            movieDetailLoading = false
            if (titleMedia == null) {
                movieDetailError = "Unable to reopen movie detail"
                return@launch
            }

            openMovie(
                media = titleMedia,
                focusEpisodeId = entry.mediaId
            ) { single, items ->
                startQueue(single, items)
            }
        }
    }

    fun openMovieFromWatchLater(entry: WatchLaterEntry) {
        val media = entry.toMediaSummary()
        if (movieProviders.isTitle(entry.mediaId) || entry.mediaId.startsWith("movie-title:")) {
            openMovie(media) { single, items ->
                startQueue(single, items)
            }
            return
        }
        if (!entry.mediaId.startsWith("movie:") || movieDetailLoading) return

        val providerId = entry.mediaId.split(':').getOrNull(1).orEmpty()
        val lookupTitle = movieLookupTitle(entry.title)
        if (providerId.isBlank() || lookupTitle.isBlank()) return

        scope.launch {
            movieDetailLoading = true
            movieDetailError = null
            val titleMedia = runCatching {
                withContext(Dispatchers.IO) {
                    val results = movieProviders.search(lookupTitle)
                    results.firstOrNull {
                        it.id.startsWith("movie-title:$providerId:")
                    } ?: results.firstOrNull()
                }
            }.getOrNull()

            movieDetailLoading = false
            if (titleMedia == null) {
                movieDetailError = "Unable to reopen movie detail"
                return@launch
            }

            openMovie(
                media = titleMedia,
                focusEpisodeId = entry.mediaId
            ) { single, items ->
                startQueue(single, items)
            }
        }
    }

    fun toggleWatchLater(media: MediaSummary) {
        scope.launch(Dispatchers.IO) {
            if (watchLater.any { it.mediaId == media.id }) {
                watchLaterRepo.remove(media.id)
            } else {
                watchLaterRepo.add(media)
            }
        }
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
        queue = QueueLimitPolicy.bound(updated, currentId)
        queueIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        playbackPrefs.saveQueue(queue, queueIndex)
        notifyQueueChanged()
    }

    fun addToQueue(media: MediaSummary) {
        if (queue.isEmpty() || queueIndex !in queue.indices) {
            startQueue(media, listOf(media))
            return
        }
        if (queue.none { it.id == media.id }) {
            val currentId = queue[queueIndex].id
            queue = QueueLimitPolicy.bound(queue + media, currentId)
            queueIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            playbackPrefs.saveQueue(queue, queueIndex)
            // If the current item used to be the end of the queue, the service needs
            // an immediate refresh so autoplay can preload this newly appended item.
            notifyQueueChanged()
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        playbackPrefs.saveShuffle(enabled)
        if (!enabled || queueIndex !in queue.indices || queueIndex >= queue.lastIndex) return
        val stablePrefix = queue.take(queueIndex + 1)
        val shuffledUpcoming = queue.drop(queueIndex + 1).shuffled()
        queue = stablePrefix + shuffledUpcoming
        playbackPrefs.saveQueue(queue, queueIndex)
        notifyQueueChanged()
    }

    LaunchedEffect(controller, repeatMode, sleepMinutes) {
        // End-of-current is a hard stop override. Keep native Repeat ONE disabled while
        // it is armed even if the user changes Repeat after arming Sleep End. Once End
        // fires (or is turned off), the user's saved repeat choice becomes active again.
        controller?.repeatMode = if (
            PlaybackRuntimePolicy.nativeRepeatOneEnabled(
                repeatMode = repeatMode.ordinal,
                sleepEndCurrent = sleepMinutes == -1
            )
        ) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    LaunchedEffect(
        developerMode,
        controller,
        playbackEngine,
        videoMode,
        nowPlaying?.id
    ) {
        if (
            !developerMode ||
            controller == null ||
            !videoMode ||
            nowPlaying?.id?.startsWith("movie:") == true
        ) {
            sabrDiagnostic = null
            return@LaunchedEffect
        }

        while (true) {
            val active = controller ?: break
            val future = active.sendCustomCommand(
                androidx.media3.session.SessionCommand(
                    PlaybackService.COMMAND_GET_SABR_DIAGNOSTIC,
                    android.os.Bundle.EMPTY
                ),
                android.os.Bundle.EMPTY
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { future.get() }.getOrNull()
            }
            sabrDiagnostic = result?.extras
                ?.getString(PlaybackService.EXTRA_SABR_DIAGNOSTIC)
            delay(500L)
        }
    }

    LaunchedEffect(Unit) {
        playbackPrefs.loadQueue()?.let { (savedQueue, savedIndex) ->
            val normalizedQueue = savedQueue.map(::normalizeMoviePresentation)
            val savedCurrentId = normalizedQueue
                .getOrNull(savedIndex)
                ?.id
                ?: normalizedQueue.firstOrNull()?.id
            val boundedQueue = if (savedCurrentId != null) {
                QueueLimitPolicy.bound(normalizedQueue, savedCurrentId)
            } else {
                emptyList()
            }
            queue = boundedQueue
            queueIndex = savedCurrentId
                ?.let { id -> boundedQueue.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: 0
            if (boundedQueue != savedQueue || queueIndex != savedIndex) {
                playbackPrefs.saveQueue(boundedQueue, queueIndex)
            }
        }
        playbackPrefs.load()?.let { (media, position) ->
            if (nowPlaying == null) {
                val normalized = normalizeMoviePresentation(media)
                nowPlaying = normalized
                resumePositionMs = position
                if (normalized != media) {
                    playbackPrefs.save(normalized, position)
                }
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
        if (minutes <= 0) return@LaunchedEffect
        delay(minutes * 60_000L)
        controller?.pause()
        sleepMinutes = null
    }

    LaunchedEffect(homeRefreshToken, trendingTopics, trendingLanguage) {
        trendingLoading = true
        val topicOrder = listOf("Music", "Podcasts", "Gaming", "Movies", "Live")
        val selectedTopics = topicOrder.filter { it in trendingTopics }.ifEmpty { listOf("Music") }

        val topicResults = withContext(Dispatchers.IO) {
            selectedTopics.map { topic ->
                topic to runCatching {
                    source.trending(topic, trendingLanguage)
                }.getOrDefault(emptyList())
            }
        }

        // Interleave topics instead of concatenating them so one large category
        // cannot push the other selected categories out of the first screen.
        val merged = mutableListOf<MediaSummary>()
        var row = 0
        while (merged.size < 30) {
            var added = false
            for ((_, items) in topicResults) {
                items.getOrNull(row)?.let {
                    if (merged.none { existing -> existing.id == it.id }) {
                        merged += it
                    }
                    added = true
                }
            }
            if (!added) break
            row += 1
        }

        trending = merged.take(30)
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

    LaunchedEffect(recentSearches, recent.firstOrNull()?.mediaId, homeRefreshToken) {
        val movieSeeds = (
            recentSearches.filter { it.isNotBlank() } +
                recent
                    .filter { it.mediaId.startsWith("movie:") }
                    .map { entry -> entry.title.substringBefore(" · ").trim() }
        )
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)

        if (movieSeeds.isEmpty()) {
            movieSuggestions = emptyList()
            movieSuggestionsLoading = false
            return@LaunchedEffect
        }

        movieSuggestionsLoading = true
        val moviePlayed = recent
            .filter { it.mediaId.startsWith("movie:") }
            .mapTo(mutableSetOf()) { entry ->
                entry.mediaId.split(':').getOrNull(2).orEmpty()
            }

        val recommendedMovies = withContext(Dispatchers.IO) {
            movieSeeds.flatMap { seed ->
                runCatching { movieProviders.search(seed) }
                    .getOrDefault(emptyList())
                    .take(8)
            }
        }

        movieSuggestions = recommendedMovies
            .distinctBy { it.id }
            .filterNot { media ->
                val slug = media.id.split(':').getOrNull(2).orEmpty()
                slug.isNotBlank() && slug in moviePlayed
            }
            .take(18)
        movieSuggestionsLoading = false
    }

    BackHandler(
        enabled = movieDetail != null || movieDetailLoading || movieDetailError != null ||
            playlistDetail != null || playlistLoading || playlistError != null ||
            channelDetail != null || channelLoading || channelError != null ||
            showSettings || showPlayer
    ) {
        when {
            movieDetail != null || movieDetailLoading || movieDetailError != null -> {
                movieDetail = null
                movieDetailError = null
                movieDetailLoading = false
            }
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
        if (isInPictureInPictureMode && videoMode && controller != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) {
                AndroidView(
                    factory = { pipContext ->
                        androidx.media3.ui.PlayerView(pipContext).apply {
                            useController = false
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setKeepContentOnPlayerReset(true)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            player = controller
                        }
                    },
                    update = { view ->
                        view.player = controller
                        view.useController = false
                        view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
        Box(Modifier.fillMaxSize()) {
        if (movieDetail != null || movieDetailLoading || movieDetailError != null) {
            Scaffold(bottomBar = {
                nowPlaying?.let { media -> MiniPlayer(
                    media = media,
                    controller = controller,
                    isPlaying = isPlaying,
                    isFavorite = favorites.any { it.mediaId == media.id },
                    isWatchLater = watchLater.any { it.mediaId == media.id },
                    networkType = networkType,
                    onOpen = {
                        movieDetail = null
                        movieDetailError = null
                        movieDetailLoading = false
                        showPlayer = true
                    },
                    onFavorite = {
                        scope.launch(Dispatchers.IO) {
                            if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id)
                            else favoritesRepo.add(media)
                        }
                    },
                    onWatchLater = { toggleWatchLater(media) },
                    onToggle = {
                        if (controller?.currentMediaItem == null) playMedia(media, resumePositionMs)
                        else togglePlayback()
                    }
                ) }
            }) { detailPadding ->
                MovieDetailScreen(
                    modifier = Modifier.fillMaxSize().padding(detailPadding),
                    detail = movieDetail,
                    loading = movieDetailLoading,
                    error = movieDetailError,
                    resolvingId = resolvingId,
                    nowPlayingId = nowPlaying?.id,
                    focusEpisodeId = movieDetailFocusId,
                    historyEntries = recent,
                    onBack = {
                        movieDetailFocusId = null
                        movieDetail = null
                        movieDetailError = null
                        movieDetailLoading = false
                    },
                    onPlay = { media, items, startPositionMs ->
                        nowPlaying = media
                        playerError = null
                        errorMessage = null
                        movieDetailFocusId = null
                        movieDetail = null
                        movieDetailError = null
                        movieDetailLoading = false
                        showPlayer = true
                        startQueue(media, items, startPositionMs)
                    }
                )
            }
        } else if (playlistDetail != null || playlistLoading || playlistError != null) {
            Scaffold(bottomBar = {
                nowPlaying?.let { media -> MiniPlayer(
                    media = media,
                    controller = controller,
                    isPlaying = isPlaying,
                    isFavorite = favorites.any { it.mediaId == media.id },
                    isWatchLater = watchLater.any { it.mediaId == media.id },
                    networkType = networkType,
                    onOpen = { channelDetail = null; playlistDetail = null; showPlayer = true },
                    onFavorite = { scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } },
                    onWatchLater = { toggleWatchLater(media) },
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
                    controller = controller,
                    isPlaying = isPlaying,
                    isFavorite = favorites.any { it.mediaId == media.id },
                    isWatchLater = watchLater.any { it.mediaId == media.id },
                    networkType = networkType,
                    onOpen = { channelDetail = null; channelError = null; channelLoading = false; showPlayer = true },
                    onFavorite = { scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } },
                    onWatchLater = { toggleWatchLater(media) },
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
                state = FullPlayerState(
                    media = requireNotNull(nowPlaying),
                    controller = controller,
                    isPlaying = isPlaying,
                    quality = quality,
                    isMovie = requireNotNull(nowPlaying).id.startsWith("movie:"),
                    subtitleOptions = subtitleOptions,
                    selectedSubtitleLabel = selectedSubtitleLabel,
                    videoQuality = videoQuality,
                    actualVideoHeight = actualVideoHeight,
                    videoMode = videoMode,
                    playbackSpeed = playbackSpeed,
                    compatibilityFallback = compatibilityFallback,
                    isBuffering = isBuffering,
                    playerError = playerError ?: errorMessage,
                    movieDiagnostic = movieDiagnostic,
                    sabrDiagnostic = sabrDiagnostic,
                    developerMode = developerMode,
                    sleepMinutes = sleepMinutes,
                    autoplay = autoplay,
                    nextMode = nextMode,
                    repeatMode = repeatMode,
                    queue = queue,
                    queueIndex = queueIndex,
                    hasPrevious = queueIndex > 0 || (repeatMode == RepeatMode.ALL && queue.size > 1),
                    hasNext = (queueIndex >= 0 && queueIndex < queue.lastIndex) ||
                        (repeatMode == RepeatMode.ALL && queue.size > 1),
                    isFavorite = favorites.any { it.mediaId == nowPlaying?.id },
                    isWatchLater = watchLater.any { it.mediaId == nowPlaying?.id },
                    personalPlaylists = personalPlaylists,
                    networkType = networkType
                ),
                actions = FullPlayerActions(
                    onSubtitle = { selectMovieSubtitle(it) },
                    onVideoMode = { enabled ->
                        if (videoMode != enabled) {
                            videoMode = enabled
                            playbackPrefs.saveVideoMode(enabled)
                            reloadCurrentForModeChange()
                        }
                    },
                    onQuality = { quality = it; playbackPrefs.saveQuality(it) },
                    onVideoQuality = {
                        if (videoQuality != it) {
                            videoQuality = it
                            playbackPrefs.saveVideoQuality(it)
                            if (videoMode) {
                                applyVideoQualityWithoutGuessingEngine()
                            }
                        }
                    },
                    onSpeed = { playbackSpeed = it; playbackPrefs.saveSpeed(it); controller?.setPlaybackSpeed(it) },
                    onRetry = {
                        playerError = null
                        errorMessage = null
                        nowPlaying?.let { playMedia(it, controller?.currentPosition ?: 0L) }
                    },
                    onSleep = { option ->
                        sleepMinutes = option
                        val active = controller
                        if (active != null) {
                            active.sendCustomCommand(
                                androidx.media3.session.SessionCommand(
                                    PlaybackService.COMMAND_SET_SLEEP_END_CURRENT,
                                    android.os.Bundle.EMPTY
                                ),
                                android.os.Bundle().apply {
                                    putBoolean(PlaybackService.ARG_ENABLED, option == -1)
                                }
                            )
                        }
                    },
                    onAutoplay = { autoplay = it; playbackPrefs.saveAutoplay(it); notifyQueueChanged() },
                    onNextMode = { nextMode = it; playbackPrefs.saveNextMode(it.name) },
                    onRepeatMode = { repeatMode = it; playbackPrefs.saveRepeatMode(it.ordinal) },
                    onQueueItem = { index ->
                        if (index in queue.indices) {
                            if (index == queueIndex) {
                                // Tapping the current queue row acts as Resume when paused.
                                controller?.play()
                            } else {
                                queueIndex = index
                                playbackPrefs.saveQueue(queue, queueIndex)
                                playMedia(queue[index])
                            }
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
                    onQueueRefresh = {
                        if (queueIndex in queue.indices) {
                            val current = queue[queueIndex]
                            if (current.id.startsWith("movie:")) {
                                // Movie queues are ordered episodes/servers. Keep their
                                // episode ordering intact and only resync playback ownership.
                                playbackPrefs.saveQueue(queue, queueIndex)
                                notifyQueueChanged()
                            } else {
                                scope.launch {
                                    val query = when {
                                        searchQuery.isNotBlank() &&
                                            searchResults.any { it.id == current.id } -> searchQuery
                                        current.channel.isNotBlank() -> current.channel
                                        else -> current.title
                                    }

                                    val fresh = runCatching {
                                        withContext(Dispatchers.IO) { source.search(query) }
                                    }.getOrDefault(emptyList())
                                        .map(::normalizeMoviePresentation)
                                        .distinctBy { it.id }

                                    if (fresh.isNotEmpty()) {
                                        // Reload replaces the complete logical queue, not only
                                        // the tail. Keep the currently playing media represented
                                        // in the new queue so playback can continue uninterrupted.
                                        val rebuilt = fresh.toMutableList()
                                        var currentIndex = rebuilt.indexOfFirst { it.id == current.id }
                                        if (currentIndex < 0) {
                                            rebuilt.add(0, current)
                                            currentIndex = 0
                                        }

                                        val replacement = if (nextMode == NextMode.RECOMMENDED) {
                                            val head = rebuilt[currentIndex]
                                            val rest = rebuilt
                                                .filterNot { it.id == head.id }
                                                .shuffled()
                                            listOf(head) + rest
                                        } else {
                                            rebuilt
                                        }

                                        queue = replacement.take(30)
                                        queueIndex = queue.indexOfFirst { it.id == current.id }
                                            .coerceAtLeast(0)
                                        playbackPrefs.saveQueue(queue, queueIndex)
                                        notifyQueueChanged()
                                    } else {
                                        // If refresh returns nothing, keep the previous queue.
                                        playbackPrefs.saveQueue(queue, queueIndex)
                                        notifyQueueChanged()
                                    }
                                }
                            }
                        }
                    },
                    onPrevious = { moveQueue(-1) },
                    onNext = { moveQueue(1) },
                    onChannel = { openChannel(nowPlaying?.channelUrl) },
                    onFavorite = {
                        nowPlaying?.let { media ->
                            scope.launch(Dispatchers.IO) {
                                if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id)
                                else favoritesRepo.add(media)
                            }
                        }
                    },
                    onWatchLater = {
                        nowPlaying?.let { toggleWatchLater(it) }
                    },
                    onAddToPlaylist = { playlistId, media ->
                        personalPlaylists = personalPlaylistStore.add(playlistId, media)
                    },
                    onCreatePlaylistWithMedia = { name, media ->
                        val existingIds = personalPlaylists.mapTo(mutableSetOf()) { it.id }
                        val created = personalPlaylistStore.create(name)
                        val createdPlaylist = created.firstOrNull { it.id !in existingIds }
                        personalPlaylists = if (createdPlaylist != null) {
                            personalPlaylistStore.add(createdPlaylist.id, media)
                        } else {
                            created
                        }
                    },
                    onToggle = {
                        if (controller?.currentMediaItem == null) {
                            nowPlaying?.let { playMedia(it, resumePositionMs) }
                        } else togglePlayback()
                    },
                    onClose = { showPlayer = false }
                )
            )
        } else if (showSettings) {
            SettingsScreen(
                quality = quality,
                onQuality = { quality = it; playbackPrefs.saveQuality(it) },
                speed = playbackSpeed,
                onSpeed = { playbackSpeed = it; playbackPrefs.saveSpeed(it); controller?.setPlaybackSpeed(it) },
                autoplay = autoplay,
                onAutoplay = { autoplay = it; playbackPrefs.saveAutoplay(it); notifyQueueChanged() },
                shuffleEnabled = shuffleEnabled,
                onShuffle = { setShuffleEnabled(it) },
                trendingTopics = trendingTopics,
                onTrendingTopics = { topics ->
                    trendingTopics = topics
                    playbackPrefs.saveTrendingTopics(topics)
                },
                trendingLanguage = trendingLanguage,
                onTrendingLanguage = { trendingLanguage = it; playbackPrefs.saveTrendingLanguage(it) },
                metadataLanguage = metadataLanguage,
                onMetadataLanguage = { language ->
                    metadataLanguage = language
                    playbackPrefs.saveMetadataLanguage(language)
                    NewPipe.setupLocalization(
                        Localization(metadataLanguage, contentRegion),
                        ContentCountry(contentRegion)
                    )
                    homeRefreshToken += 1
                },
                contentRegion = contentRegion,
                onContentRegion = { region ->
                    contentRegion = region
                    playbackPrefs.saveContentRegion(region)
                    NewPipe.setupLocalization(
                        Localization(metadataLanguage, contentRegion),
                        ContentCountry(contentRegion)
                    )
                    homeRefreshToken += 1
                },
                networkType = networkType,
                dataUsage = dataUsage,
                storageSnapshot = storageSnapshot,
                mobilePlaybackAllowed = mobilePlaybackAllowed,
                onMobilePlaybackAllowed = { enabled ->
                    mobilePlaybackAllowed = enabled
                    playbackPrefs.saveMobilePlaybackAllowed(enabled)
                    if (!enabled && networkType == NetworkType.CELLULAR) {
                        controller?.pause()
                    }
                    notifyQueueChanged()
                },
                dataSaverEnabled = dataSaverEnabled,
                onDataSaverEnabled = { enabled ->
                    dataSaverEnabled = enabled
                    playbackPrefs.saveDataSaverEnabled(enabled)
                },
                mobileDataPriority = mobileDataPriority,
                onMobileDataPriority = { priority ->
                    mobileDataPriority = priority
                    playbackPrefs.saveMobileDataPriority(priority)
                },
                mobileDataLimitMb = mobileDataLimitMb,
                onMobileDataLimitMb = { limit ->
                    mobileDataLimitMb = limit
                    playbackPrefs.saveMobileDataLimitMb(limit)
                    notifyQueueChanged()
                },
                preloadWifi = preloadWifi,
                onPreloadWifi = { enabled ->
                    preloadWifi = enabled
                    playbackPrefs.savePreloadWifi(enabled)
                    notifyQueueChanged()
                },
                preloadMobile = preloadMobile,
                onPreloadMobile = { enabled ->
                    preloadMobile = enabled
                    playbackPrefs.savePreloadMobile(enabled)
                    notifyQueueChanged()
                },
                warnMobileVideo = warnMobileVideo,
                onWarnMobileVideo = { enabled ->
                    warnMobileVideo = enabled
                    playbackPrefs.saveWarnMobileVideo(enabled)
                },
                onResetDataUsage = {
                    dataUsage = networkUsageTracker.reset()
                },
                onClearCache = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            StorageUsage.clearCache(context)
                        }
                        storageSnapshot = withContext(Dispatchers.IO) {
                            StorageUsage.snapshot(context)
                        }
                        Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                    }
                },
                developerUnlocked = developerUnlocked,
                developerMode = developerMode,
                onDeveloperMode = {
                    developerMode = it
                    playbackPrefs.saveDeveloperMode(it)
                },
                onDeveloperUnlocked = {
                    developerUnlocked = true
                    playbackPrefs.saveDeveloperUnlocked(true)
                },
                onDeveloperLock = {
                    developerMode = false
                    developerUnlocked = false
                    playbackPrefs.saveDeveloperMode(false)
                    playbackPrefs.saveDeveloperUnlocked(false)
                },
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
                                controller = controller,
                                isPlaying = isPlaying,
                                isFavorite = favorites.any { it.mediaId == media.id },
                                isWatchLater = watchLater.any { it.mediaId == media.id },
                                networkType = networkType,
                                onOpen = { channelDetail = null; playlistDetail = null; showPlayer = true },
                                onFavorite = { scope.launch(Dispatchers.IO) { if (favorites.any { it.mediaId == media.id }) favoritesRepo.remove(media.id) else favoritesRepo.add(media) } },
                                onWatchLater = { toggleWatchLater(media) },
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
                        movieProviders = movieProviders,
                        resolvingId = resolvingId,
                        playbackError = errorMessage,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        results = searchResults,
                        historyEntries = recent,
                        listState = searchListState,
                        onResultsChange = { searchResults = it },
                        recentSearches = recentSearches,
                        onRecentSearchDelete = { recent ->
                            playbackPrefs.removeRecentSearch(recent)
                            recentSearches = playbackPrefs.loadRecentSearches()
                        },
                        sourceMode = searchSourceMode,
                        onSourceModeChange = { mode ->
                            searchSourceMode = mode
                            playbackPrefs.saveSearchSourceMode(mode.name)
                        },
                        onSearchSubmitted = { q -> playbackPrefs.saveSearch(q); recentSearches = playbackPrefs.loadRecentSearches() },
                        onChannel = { openChannel(it.channelUrl) },
                        onMovie = {
                            openMovie(it) { single, items ->
                                startQueue(single, items)
                            }
                        },
                        onPlay = { media, resultQueue -> startQueue(media, resultQueue) },
                        onPlayNext = { playNext(it) },
                        onAddToQueue = { addToQueue(it) },
                        personalPlaylists = personalPlaylists,
                        onAddToPlaylist = { playlistId, media ->
                            personalPlaylists = personalPlaylistStore.add(playlistId, media)
                        },
                        onCreatePlaylistWithMedia = { name, media ->
                            val existingIds = personalPlaylists.mapTo(mutableSetOf()) { it.id }
                            val created = personalPlaylistStore.create(name)
                            val createdPlaylist = created.firstOrNull { it.id !in existingIds }
                            personalPlaylists = if (createdPlaylist != null) {
                                personalPlaylistStore.add(createdPlaylist.id, media)
                            } else {
                                created
                            }
                        },
                        watchLaterIds = watchLater.mapTo(mutableSetOf()) { it.mediaId },
                        onToggleWatchLater = { toggleWatchLater(it) }
                    )
                    Tab.HOME -> HomeScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        continueWatching = recent.filter { entry ->
                            entry.positionMs > 5_000L &&
                                (entry.durationMs <= 0L || entry.progress in 0.01f..0.94f)
                        }.take(3),
                        movieProviders = movieProviders,
                        suggestions = homeSuggestions,
                        movieSuggestions = movieSuggestions,
                        movieSuggestionsLoading = movieSuggestionsLoading,
                        recommendationMode = recommendationMode,
                        onRecommendationModeChange = { mode ->
                            recommendationMode = mode
                            playbackPrefs.saveRecommendationMode(mode.name)
                        },
                        trending = trending,
                        trendingLoading = trendingLoading,
                        listState = homeListState,
                        loading = homeLoading,
                        onRefresh = { homeRefreshToken += 1 },
                        resolvingId = resolvingId,
                        onContinue = { entry ->
                            if (isMovieLibraryItem(entry.mediaId, entry.channel)) {
                                continueMovieFromHistory(entry)
                            } else {
                                val media = entry.toMediaSummary()
                                startQueue(
                                    media,
                                    listOf(media),
                                    startPositionMs = entry.positionMs.coerceAtLeast(0L)
                                )
                            }
                        },
                        onPlay = { media, items ->
                            startQueue(media, items)
                        },
                        onMovie = {
                            openMovie(it) { single, items ->
                                startQueue(single, items)
                            }
                        },
                        watchLaterIds = watchLater.mapTo(mutableSetOf()) { it.mediaId },
                        onToggleWatchLater = { toggleWatchLater(it) },
                        favoriteIds = favorites.mapTo(mutableSetOf()) { it.mediaId },
                        onToggleFavorite = { media ->
                            scope.launch(Dispatchers.IO) {
                                if (favorites.any { it.mediaId == media.id }) {
                                    favoritesRepo.remove(media.id)
                                } else {
                                    favoritesRepo.add(media)
                                }
                            }
                        },
                        onSearch = { selected = Tab.SEARCH }
                    )
                    Tab.LIBRARY -> LibraryScreen(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        recent = recent,
                        favorites = favorites,
                        watchLater = watchLater,
                        personalPlaylists = personalPlaylists,
                        nowPlaying = nowPlaying,
                        movieProviders = movieProviders,
                        listState = libraryListState,
                        resolvingId = resolvingId,
                        onPlay = { entry ->
                            val isMovie = isMovieLibraryItem(entry.mediaId, entry.channel)
                            val items = if (isMovie) {
                                listOf(entry.toMediaSummary())
                            } else {
                                recent
                                    .filterNot {
                                        isMovieLibraryItem(it.mediaId, it.channel)
                                    }
                                    .map { it.toMediaSummary() }
                            }
                            startQueue(
                                entry.toMediaSummary(),
                                items,
                                startPositionMs = entry.positionMs.coerceAtLeast(0L)
                            )
                        },
                        onOpenMovie = { entry -> openMovieFromHistory(entry) },
                        onOpenFavoriteMovie = { entry -> openMovieFromFavorite(entry) },
                        onPlayFavorite = { entry ->
                            val media = entry.toMediaSummary()
                            val isMovie = isMovieLibraryItem(entry.mediaId, entry.channel)
                            val items = if (isMovie) {
                                listOf(media)
                            } else {
                                favorites
                                    .filterNot {
                                        isMovieLibraryItem(it.mediaId, it.channel)
                                    }
                                    .map { it.toMediaSummary() }
                            }
                            startQueue(media, items)
                        },
                        onPlayAllYouTubeFavorites = {
                            val items = favorites
                                .filterNot {
                                    isMovieLibraryItem(it.mediaId, it.channel)
                                }
                                .map { it.toMediaSummary() }
                            if (items.isNotEmpty()) {
                                startQueue(items.first(), items, preserveSourceOrder = true)
                            }
                        },
                        onPlayAllMovieFavorites = {
                            val items = favorites
                                .filter {
                                    isMovieLibraryItem(it.mediaId, it.channel)
                                }
                                .map { it.toMediaSummary() }
                            if (items.isNotEmpty()) {
                                startQueue(items.first(), items)
                            }
                        },
                        onRemoveFavorite = { entry -> scope.launch(Dispatchers.IO) { favoritesRepo.remove(entry.mediaId) } },
                        onPlayWatchLater = { entry ->
                            if (isMovieLibraryItem(entry.mediaId, entry.channel)) {
                                openMovieFromWatchLater(entry)
                            } else {
                                val media = entry.toMediaSummary()
                                val items = watchLater
                                    .filterNot {
                                        movieProviders.isTitle(it.mediaId) ||
                                            it.mediaId.startsWith("movie-title:")
                                    }
                                    .map { it.toMediaSummary() }
                                startQueue(media, items, preserveSourceOrder = true)
                            }
                        },
                        onPlayAllWatchLater = {
                            val items = watchLater
                                .filterNot {
                                    movieProviders.isTitle(it.mediaId) ||
                                        it.mediaId.startsWith("movie-title:")
                                }
                                .map { it.toMediaSummary() }
                            if (items.isNotEmpty()) {
                                startQueue(
                                    items.first(),
                                    items,
                                    preserveSourceOrder = true
                                )
                            }
                        },
                        onRemoveWatchLater = { entry ->
                            scope.launch(Dispatchers.IO) {
                                watchLaterRepo.remove(entry.mediaId)
                            }
                        },
                        onToggleWatchLaterMedia = { media ->
                            toggleWatchLater(media)
                        },
                        onAddToPlaylist = { playlistId, media ->
                            personalPlaylists = personalPlaylistStore.add(playlistId, media)
                        },
                        onCreatePlaylistWithMedia = { name, media ->
                            val existingIds = personalPlaylists.mapTo(mutableSetOf()) { it.id }
                            val created = personalPlaylistStore.create(name)
                            val createdPlaylist = created.firstOrNull { it.id !in existingIds }
                            personalPlaylists = if (createdPlaylist != null) {
                                personalPlaylistStore.add(createdPlaylist.id, media)
                            } else {
                                created
                            }
                        },
                        onCreatePlaylist = { name ->
                            personalPlaylists = personalPlaylistStore.create(name)
                        },
                        onRenamePlaylist = { id, name ->
                            personalPlaylists = personalPlaylistStore.rename(id, name)
                        },
                        onDeletePlaylist = { id ->
                            personalPlaylists = personalPlaylistStore.delete(id)
                        },
                        onPinPlaylist = { id, pinned ->
                            personalPlaylists = personalPlaylistStore.setPinned(id, pinned)
                        },
                        onDuplicatePlaylist = { id ->
                            personalPlaylists = personalPlaylistStore.duplicate(id)
                        },
                        onClearPlaylist = { id ->
                            personalPlaylists = personalPlaylistStore.clearItems(id)
                        },
                        onAddCurrentToPlaylist = { id ->
                            nowPlaying?.let { media ->
                                personalPlaylists = personalPlaylistStore.add(id, media)
                            }
                        },
                        onRemovePlaylistItem = { id, mediaId ->
                            personalPlaylists = personalPlaylistStore.remove(id, mediaId)
                        },
                        onMovePlaylistItem = { id, from, to ->
                            personalPlaylists = personalPlaylistStore.move(id, from, to)
                        },
                        onPlayPlaylist = { playlist ->
                            if (playlist.items.isNotEmpty()) {
                                startQueue(
                                    playlist.items.first(),
                                    playlist.items,
                                    preserveSourceOrder = true
                                )
                            }
                        },
                        onPlayPlaylistItem = { media, items ->
                            startQueue(media, items, preserveSourceOrder = true)
                        },
                        onDelete = { entry -> scope.launch(Dispatchers.IO) { history.delete(entry.mediaId) } },
                        onClear = { scope.launch(Dispatchers.IO) { history.clear() } }
                    )
                }
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
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, "Settings")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    quality: AudioQuality,
    onQuality: (AudioQuality) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    autoplay: Boolean,
    onAutoplay: (Boolean) -> Unit,
    shuffleEnabled: Boolean,
    onShuffle: (Boolean) -> Unit,
    trendingTopics: Set<String>,
    onTrendingTopics: (Set<String>) -> Unit,
    trendingLanguage: String,
    onTrendingLanguage: (String) -> Unit,
    metadataLanguage: String,
    onMetadataLanguage: (String) -> Unit,
    contentRegion: String,
    onContentRegion: (String) -> Unit,
    networkType: NetworkType,
    dataUsage: DataUsageSnapshot,
    storageSnapshot: StorageSnapshot,
    mobilePlaybackAllowed: Boolean,
    onMobilePlaybackAllowed: (Boolean) -> Unit,
    dataSaverEnabled: Boolean,
    onDataSaverEnabled: (Boolean) -> Unit,
    mobileDataPriority: MobileDataPriority,
    onMobileDataPriority: (MobileDataPriority) -> Unit,
    mobileDataLimitMb: Long,
    onMobileDataLimitMb: (Long) -> Unit,
    preloadWifi: Boolean,
    onPreloadWifi: (Boolean) -> Unit,
    preloadMobile: Boolean,
    onPreloadMobile: (Boolean) -> Unit,
    warnMobileVideo: Boolean,
    onWarnMobileVideo: (Boolean) -> Unit,
    onResetDataUsage: () -> Unit,
    onClearCache: () -> Unit,
    developerUnlocked: Boolean,
    developerMode: Boolean,
    onDeveloperMode: (Boolean) -> Unit,
    onDeveloperUnlocked: () -> Unit,
    onDeveloperLock: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var developerTapCount by remember { mutableIntStateOf(0) }

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

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shuffle upcoming", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Shuffle only items after the current item; current playback and resume stay unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = shuffleEnabled, onCheckedChange = onShuffle)
            }

            HorizontalDivider()
            Text(
                "Network & data",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        NetworkStatusIcon(networkType, 17.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            NetworkEnvironment.label(networkType),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatBytes(dataUsage.last30Days.totalBytes) + " / 30 days",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Today", style = MaterialTheme.typography.bodySmall)
                        Text(formatBytes(dataUsage.today.totalBytes), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last 7 days", style = MaterialTheme.typography.bodySmall)
                        Text(formatBytes(dataUsage.last7Days.totalBytes), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Wi-Fi", style = MaterialTheme.typography.bodySmall)
                        Text(formatBytes(dataUsage.last30Days.wifiBytes), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Mobile", style = MaterialTheme.typography.bodySmall)
                        Text(formatBytes(dataUsage.last30Days.mobileBytes), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = onResetDataUsage,
                        modifier = Modifier.align(Alignment.End),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Reset statistics")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow mobile-data playback", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Turn off to play only on Wi-Fi or other non-mobile networks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mobilePlaybackAllowed,
                    onCheckedChange = onMobilePlaybackAllowed
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Data saver", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Uses saver audio and caps video around 360p for new items.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dataSaverEnabled,
                    onCheckedChange = onDataSaverEnabled
                )
            }

            Column {
                Text("Mobile-data priority", fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MobileDataPriority.entries.forEach { option ->
                        FilterChip(
                            selected = mobileDataPriority == option,
                            onClick = { onMobileDataPriority(option) },
                            label = {
                                Text(
                                    when (option) {
                                        MobileDataPriority.DATA_SAVER -> "Saver"
                                        MobileDataPriority.BALANCED -> "Balanced"
                                        MobileDataPriority.QUALITY -> "Quality"
                                    },
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Column {
                Text("Mobile-data limit", fontWeight = FontWeight.SemiBold)
                Text(
                    "Stops new playback on mobile data after the selected amount since statistics were reset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(
                        0L to "No limit",
                        250L to "250 MB",
                        500L to "500 MB",
                        1024L to "1 GB",
                        2048L to "2 GB"
                    ).forEach { (limit, label) ->
                        FilterChip(
                            selected = mobileDataLimitMb == limit,
                            onClick = { onMobileDataLimitMb(limit) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
                if (mobileDataLimitMb > 0L) {
                    val used = dataUsage.sinceReset.mobileBytes
                    val limitBytes = mobileDataLimitMb * 1024L * 1024L
                    val fraction = (used.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Text(
                        formatBytes(used) + " / " + formatBytes(limitBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Warn for video on mobile", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Shows a warning when a video starts while mobile data is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = warnMobileVideo,
                    onCheckedChange = onWarnMobileVideo
                )
            }

            Column {
                Text("Preload next item", fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wi-Fi", modifier = Modifier.weight(1f))
                    Switch(checked = preloadWifi, onCheckedChange = onPreloadWifi)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mobile data", modifier = Modifier.weight(1f))
                    Switch(checked = preloadMobile, onCheckedChange = onPreloadMobile)
                }
            }

            HorizontalDivider()
            Text(
                "Storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("App")
                        Text(formatBytes(storageSnapshot.apkBytes))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("App data")
                        Text(formatBytes(storageSnapshot.appDataBytes))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cache")
                        Text(formatBytes(storageSnapshot.cacheBytes))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Total " + formatBytes(storageSnapshot.totalBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = onClearCache,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.CleaningServices, null, Modifier.size(MediaActionIconSize))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear cache")
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("YouTube", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Column {
                Text("Metadata language", fontWeight = FontWeight.SemiBold)
                Text(
                    "Language used for YouTube text metadata such as relative upload dates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("en" to "English", "vi" to "Tiếng Việt").forEach { (code, label) ->
                        FilterChip(
                            selected = metadataLanguage == code,
                            onClick = { onMetadataLanguage(code) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Column {
                Text("Content region", fontWeight = FontWeight.SemiBold)
                Text(
                    "Controls the regional YouTube catalogue and feed. This is separate from metadata language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf("VN", "US", "GB", "KR", "JP").forEach { region ->
                        CompactPresetButton(
                            region,
                            contentRegion == region,
                            { onContentRegion(region) },
                            Modifier.weight(1f)
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Trending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Topic", fontWeight = FontWeight.SemiBold)
            Text(
                "Select one or more topics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Music", "Podcasts", "Gaming", "Movies", "Live").forEach { option ->
                    val selected = option in trendingTopics
                    FilterChip(
                        modifier = Modifier.height(32.dp),
                        selected = selected,
                        onClick = {
                            val updated = if (selected) {
                                trendingTopics - option
                            } else {
                                trendingTopics + option
                            }
                            // Trending should always have at least one source category.
                            if (updated.isNotEmpty()) onTrendingTopics(updated)
                        },
                        label = {
                            Text(
                                option,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
                }
            }
            Text("Language", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("Vietnamese", "English", "Korean", "Japanese", "All").forEach { option ->
                    val label = when(option) { "Vietnamese" -> "VI"; "English" -> "EN"; "Korean" -> "KO"; "Japanese" -> "JA"; else -> "All" }
                    CompactPresetButton(label, trendingLanguage == option, { onTrendingLanguage(option) }, Modifier.weight(1f))
                }
            }

            if (developerUnlocked) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Developer options", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Show playback source, network, and diagnostic information.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = developerMode,
                        onCheckedChange = onDeveloperMode
                    )
                }
                TextButton(
                    onClick = onDeveloperLock,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Lock developer access")
                }
            }

            HorizontalDivider()
            Column {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "ezTube ${BuildConfig.VERSION_NAME} · ${BuildConfig.MOVIE_MILESTONE}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${BuildConfig.BUILD_ID} · Android build ${BuildConfig.VERSION_CODE}",
                    modifier = Modifier.clickable {
                        if (!developerUnlocked) {
                            developerTapCount += 1
                            val remaining = 7 - developerTapCount
                            when {
                                remaining <= 0 -> {
                                    developerTapCount = 0
                                    onDeveloperUnlocked()
                                    Toast.makeText(
                                        context,
                                        "Developer access unlocked",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                developerTapCount >= 3 -> {
                                    Toast.makeText(
                                        context,
                                        "$remaining more taps to unlock developer access",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
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
    movieProviders: MovieProviderRegistry,
    resolvingId: String?,
    playbackError: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<MediaSummary>,
    historyEntries: List<HistoryEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onResultsChange: (List<MediaSummary>) -> Unit,
    recentSearches: List<String>,
    onRecentSearchDelete: (String) -> Unit,
    sourceMode: SearchSourceMode,
    onSourceModeChange: (SearchSourceMode) -> Unit,
    onSearchSubmitted: (String) -> Unit,
    onChannel: (MediaSummary) -> Unit,
    onMovie: (MediaSummary) -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit,
    onPlayNext: (MediaSummary) -> Unit,
    onAddToQueue: (MediaSummary) -> Unit,
    personalPlaylists: List<PersonalPlaylist>,
    onAddToPlaylist: (String, MediaSummary) -> Unit,
    onCreatePlaylistWithMedia: (String, MediaSummary) -> Unit,
    watchLaterIds: Set<String>,
    onToggleWatchLater: (MediaSummary) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchSort by remember { mutableStateOf(SearchSort.RELEVANCE) }
    var playlistTarget by remember { mutableStateOf<MediaSummary?>(null) }
    val displayResults = remember(results, searchSort) {
        when (searchSort) {
            SearchSort.RELEVANCE -> results
            SearchSort.NEWEST -> results.sortedBy { searchAgeRank(it.uploadDateText) }
            SearchSort.VIEWS -> results.sortedByDescending { it.viewCount }
            SearchSort.DURATION -> results.sortedByDescending { it.durationSeconds }
        }
    }

    fun submit(
        searchText: String = query,
        mode: SearchSourceMode = sourceMode
    ) {
        val normalized = searchText.trim()
        if (normalized.isBlank() || loading) return
        if (normalized != query) onQueryChange(normalized)
        scope.launch {
            loading = true
            searchError = null
            onSearchSubmitted(normalized)
            runCatching {
                withContext(Dispatchers.IO) {
                    when (mode) {
                        SearchSourceMode.MOVIE -> movieProviders.search(normalized)
                        SearchSourceMode.YOUTUBE -> source.search(normalized)
                    }
                }
            }
                .onSuccess { onResultsChange(it) }
                .onFailure { searchError = it.message ?: "Search failed" }
            loading = false
        }
    }

    Column(modifier.padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = sourceMode == SearchSourceMode.YOUTUBE,
                onClick = {
                    if (sourceMode != SearchSourceMode.YOUTUBE) {
                        onSourceModeChange(SearchSourceMode.YOUTUBE)
                        searchError = null
                        onResultsChange(emptyList())
                        if (query.isNotBlank()) submit(query, SearchSourceMode.YOUTUBE)
                    }
                },
                label = { Text("YouTube", maxLines = 1) },
                modifier = Modifier.weight(1f).height(40.dp)
            )
            FilterChip(
                selected = sourceMode == SearchSourceMode.MOVIE,
                onClick = {
                    if (sourceMode != SearchSourceMode.MOVIE) {
                        onSourceModeChange(SearchSourceMode.MOVIE)
                        searchError = null
                        onResultsChange(emptyList())
                        if (query.isNotBlank()) submit(query, SearchSourceMode.MOVIE)
                    }
                },
                label = { Text("Movie", maxLines = 1) },
                modifier = Modifier.weight(1f).height(40.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp, max = 104.dp),
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            placeholder = {
                Text(
                    if (sourceMode == SearchSourceMode.MOVIE)
                        "Search movies…"
                    else
                        "Search YouTube…"
                )
            },
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
            shape = RoundedCornerShape(16.dp)
        )
        if (query.isBlank() && recentSearches.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                recentSearches.take(5).forEach { recent ->
                    Box(
                        modifier = Modifier.padding(end = 2.dp, top = 1.dp)
                    ) {
                        AssistChip(
                            onClick = { submit(recent) },
                            modifier = Modifier.height(34.dp),
                            label = {
                                Text(
                                    recent,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 210.dp)
                                )
                            }
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(14.dp)
                                .clickable { onRecentSearchDelete(recent) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Delete search",
                                    modifier = Modifier.size(8.dp)
                                )
                            }
                        }
                    }
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
        (searchError ?: playbackError?.takeIf { results.isEmpty() })?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (results.isEmpty() && !loading) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (sourceMode == SearchSourceMode.MOVIE) {
                                Icons.Outlined.LocalMovies
                            } else {
                                Icons.Outlined.Search
                            },
                            null,
                            Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (sourceMode == SearchSourceMode.MOVIE) {
                                "Find a movie"
                            } else {
                                "Find something to watch or listen to"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (sourceMode == SearchSourceMode.MOVIE) {
                                "Search by movie title across available providers."
                            } else {
                                "Search videos, music and channels. Recent searches stay above for quick access."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
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
                        onPlay = {
                            if (movieProviders.isTitle(media.id)) onMovie(media)
                            else onPlay(media, displayResults)
                        },
                        onPlayNext = if (movieProviders.isTitle(media.id)) null else { { onPlayNext(media) } },
                        onAddToQueue = if (movieProviders.isTitle(media.id)) null else { { onAddToQueue(media) } },
                        onAddToPlaylist = { playlistTarget = media },
                        isWatchLater = media.id in watchLaterIds,
                        onToggleWatchLater = { onToggleWatchLater(media) }
                    )
                }
            }
        }
    }

    playlistTarget?.let { target ->
        AddToPlaylistDialog(
            media = target,
            playlists = personalPlaylists,
            onDismiss = { playlistTarget = null },
            onSelect = { playlistId ->
                onAddToPlaylist(playlistId, target)
                playlistTarget = null
            },
            onCreate = { name ->
                onCreatePlaylistWithMedia(name, target)
                playlistTarget = null
            }
        )
    }
}

private fun movieProvidersTitleId(mediaId: String): Boolean =
    mediaId.startsWith("movie-title:")

private fun normalizeMoviePresentation(media: MediaSummary): MediaSummary {
    if (!media.id.startsWith("movie:biliintl:")) return media

    var title = media.title
        .replace(Regex("""\s*·\s*Video\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
    title = title
        .replace(
            Regex("""\s*(?:[-|·]\s*)?BiliBili\s*$""", RegexOption.IGNORE_CASE),
            ""
        )
        .trim()

    val channel = media.channel
        .split('·')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .joinToString(" · ")

    return if (title == media.title && channel == media.channel) {
        media
    } else {
        media.copy(title = title, channel = channel)
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
private fun MovieDetailScreen(
    modifier: Modifier,
    detail: MovieDetail?,
    loading: Boolean,
    error: String?,
    resolvingId: String?,
    nowPlayingId: String?,
    focusEpisodeId: String?,
    historyEntries: List<HistoryEntry>,
    onBack: () -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>, Long) -> Unit
) {
    val listState = rememberLazyListState()
    val historyByMediaId = remember(historyEntries) {
        historyEntries.associateBy { it.mediaId }
    }
    val grouped = remember(detail) {
        detail?.episodes?.groupBy { it.serverName }.orEmpty()
    }
    val serverNames = remember(grouped) { grouped.keys.toList() }
    val navigationEpisodeId = focusEpisodeId ?: nowPlayingId
    val initialServer = remember(detail?.catalog?.stableId, navigationEpisodeId) {
        detail?.episodes
            ?.firstOrNull { it.stableId == navigationEpisodeId }
            ?.serverName
            ?: serverNames.firstOrNull()
    }
    var selectedServer by remember(detail?.catalog?.stableId) {
        mutableStateOf(initialServer)
    }

    LaunchedEffect(detail?.catalog?.stableId, navigationEpisodeId) {
        val playingServer = detail?.episodes
            ?.firstOrNull { it.stableId == navigationEpisodeId }
            ?.serverName
        if (playingServer != null && grouped.containsKey(playingServer)) {
            selectedServer = playingServer
        } else if (selectedServer !in grouped) {
            selectedServer = serverNames.firstOrNull()
        }
    }

    val selectedRefs = selectedServer?.let { grouped[it] }.orEmpty()
    val serverQueue = remember(selectedRefs) {
        selectedRefs.map { it.toMediaSummary() }
    }
    val currentLocalIndex = selectedRefs.indexOfFirst { it.stableId == navigationEpisodeId }
    val episodeRowsStartIndex =
        1 + // movie header
            (if (serverNames.size > 1) 1 else 0) + // server selector
            1 + // selected server heading
            (if (selectedRefs.size > 1) 1 else 0) // quick picker
    val targetListIndex =
        if (currentLocalIndex >= 0) episodeRowsStartIndex + currentLocalIndex else -1

    LaunchedEffect(
        detail?.catalog?.stableId,
        selectedServer,
        navigationEpisodeId,
        targetListIndex
    ) {
        if (targetListIndex >= 0) {
            listState.animateScrollToItem(targetListIndex)
            val visible = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetListIndex }
            if (visible != null) {
                val viewportCenter =
                    (listState.layoutInfo.viewportStartOffset +
                        listState.layoutInfo.viewportEndOffset) / 2
                val itemCenter = visible.offset + visible.size / 2
                listState.animateScrollBy((itemCenter - viewportCenter).toFloat())
            }
        }
    }

    Column(modifier.statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Text(
                detail?.catalog?.title ?: "Movie",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            detail != null -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                detail.catalog.thumbnailUrl,
                                null,
                                Modifier.size(width = 104.dp, height = 148.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    detail.catalog.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                detail.catalog.originalTitle
                                    ?.takeIf { it.isNotBlank() && it != detail.catalog.title }
                                    ?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    buildList {
                                        add(detail.catalog.providerName)
                                        detail.catalog.year?.let { add(it.toString()) }
                                        detail.catalog.language
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let(::add)
                                        detail.catalog.durationText
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let(::add)
                                    }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    buildString {
                                        append(detail.episodes.size)
                                        append(
                                            if (detail.episodes.size == 1) {
                                                " playable item"
                                            } else {
                                                " episodes"
                                            }
                                        )
                                        if (serverNames.size > 1) {
                                            append(" · ")
                                            append(serverNames.size)
                                            append(" servers")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (serverNames.size > 1) {
                        item(key = "movie-server-selector") {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                items(serverNames, key = { "server-tab-$it" }) { server ->
                                    val count = grouped[server]?.size ?: 0
                                    FilterChip(
                                        selected = selectedServer == server,
                                        onClick = { selectedServer = server },
                                        label = {
                                            Text(
                                                "$server · $count",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "selected-server-heading-$selectedServer") {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                selectedServer.orEmpty().ifBlank { "Episodes" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (selectedRefs.isNotEmpty()) {
                                Text(
                                    "${selectedRefs.size} items",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (selectedRefs.size > 1) {
                        item(key = "server-picker-$selectedServer") {
                            val pickerState = rememberLazyListState()
                            val currentPickerIndex = selectedRefs.indexOfFirst {
                                it.stableId == navigationEpisodeId
                            }

                            LaunchedEffect(
                                detail.catalog.stableId,
                                selectedServer,
                                navigationEpisodeId,
                                currentPickerIndex
                            ) {
                                if (currentPickerIndex >= 0) {
                                    pickerState.animateScrollToItem(currentPickerIndex)
                                }
                            }

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                state = pickerState,
                                contentPadding = PaddingValues(
                                    start = 14.dp,
                                    end = 14.dp,
                                    bottom = 4.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(
                                    selectedRefs,
                                    key = { _, ref -> "picker-${ref.stableId}" }
                                ) { index, ref ->
                                    val media = serverQueue[index]
                                    val progressEntry = historyByMediaId[media.id]
                                    val rawLabel = ref.episodeName
                                        .takeIf {
                                            it.isNotBlank() &&
                                                !it.equals("Video", ignoreCase = true)
                                        }
                                        ?: "Ep ${index + 1}"

                                    FilterChip(
                                        modifier = Modifier
                                            .height(32.dp)
                                            .widthIn(max = 132.dp),
                                        selected = media.id == nowPlayingId,
                                        enabled = resolvingId == null,
                                        onClick = {
                                            onPlay(
                                                media,
                                                serverQueue,
                                                progressEntry?.positionMs
                                                    ?.coerceAtLeast(0L)
                                                    ?: 0L
                                            )
                                        },
                                        label = {
                                            Text(
                                                rawLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = if (media.id == nowPlayingId) {
                                            {
                                                Icon(
                                                    Icons.Outlined.GraphicEq,
                                                    null,
                                                    Modifier.size(15.dp)
                                                )
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }

                    items(
                        serverQueue,
                        key = { it.id }
                    ) { media ->
                        val progressEntry = historyByMediaId[media.id]
                        val isCurrent = media.id == nowPlayingId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Outlined.GraphicEq,
                                        "Now playing",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(20.dp)
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    SearchResult(
                                        media = media,
                                        progressEntry = progressEntry,
                                        resolving = resolvingId == media.id,
                                        enabled = resolvingId == null,
                                        onChannel = {},
                                        onPlay = {
                                            onPlay(
                                                media,
                                                serverQueue,
                                                progressEntry?.positionMs
                                                    ?.coerceAtLeast(0L)
                                                    ?: 0L
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    isWatchLater: Boolean = false,
    onToggleWatchLater: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
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
            val thumbnailBadge = media.thumbnailBadge
                ?: media.durationSeconds.takeIf { it >= 0 }?.let(::formatDuration)
            if (thumbnailBadge != null) {
                Text(
                    thumbnailBadge,
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
            TouchMarqueeTitle(
                text = media.title,
                maxLines = 2,
                fontWeight = FontWeight.SemiBold
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
                SearchPlaybackProgress(
                    progress = entry.progress,
                    durationMs = entry.durationMs
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        if (resolving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else if (
            onPlayNext != null ||
            onAddToQueue != null ||
            onAddToPlaylist != null ||
            onToggleWatchLater != null
        ) {
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
                    onAddToPlaylist?.let { action ->
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, null) },
                            onClick = { showMenu = false; action() }
                        )
                    }
                    onToggleWatchLater?.let { action ->
                        DropdownMenuItem(
                            text = {
                                Text(if (isWatchLater) "Remove from Watch later" else "Watch later")
                            },
                            leadingIcon = {
                                Icon(
                                    if (isWatchLater) Icons.Outlined.Bookmark
                                    else Icons.Outlined.BookmarkBorder,
                                    null
                                )
                            },
                            onClick = { showMenu = false; action() }
                        )
                    }
                }
            }
        } else Icon(Icons.Outlined.PlayCircle, "Play")
    }
}

@Composable
private fun AddToPlaylistDialog(
    media: MediaSummary,
    playlists: List<PersonalPlaylist>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    var creating by remember(media.id, playlists.isEmpty()) {
        mutableStateOf(playlists.isEmpty())
    }
    var playlistName by remember(media.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    media.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                if (playlists.isNotEmpty()) {
                    playlists.forEach { playlist ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(playlist.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    null,
                                    Modifier.size(MediaActionIconSize)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        playlist.items.size.toString() + " items",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (creating) {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("New playlist name") }
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (playlists.isNotEmpty()) {
                            TextButton(onClick = {
                                creating = false
                                playlistName = ""
                            }) {
                                Text("Back")
                            }
                        }
                        TextButton(onClick = { onCreate(playlistName) }) {
                            Text("Create & add")
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(MediaActionIconSize))
                        Spacer(Modifier.width(6.dp))
                        Text("New playlist")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TouchMarqueeTitle(
    text: String,
    maxLines: Int = 1,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    var marqueeActive by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = fontWeight,
        maxLines = if (marqueeActive) 1 else maxLines,
        softWrap = !marqueeActive,
        overflow = if (marqueeActive) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(text) {
                detectTapGestures(
                    onPress = {
                        marqueeActive = true
                        tryAwaitRelease()
                        delay(2_500L)
                        marqueeActive = false
                    }
                )
            }
            .then(
                if (marqueeActive) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        spacing = MarqueeSpacing(20.dp),
                        initialDelayMillis = 80
                    )
                } else {
                    Modifier
                }
            )
    )
}

@Composable
private fun NetworkStatusIcon(
    networkType: NetworkType,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = when (networkType) {
            NetworkType.WIFI -> Icons.Outlined.Wifi
            NetworkType.CELLULAR -> Icons.Outlined.SignalCellularAlt
            NetworkType.OTHER -> Icons.Outlined.Public
            NetworkType.OFFLINE -> Icons.Outlined.CloudOff
        },
        contentDescription = NetworkEnvironment.label(networkType),
        modifier = modifier.size(size),
        tint = if (networkType == NetworkType.OFFLINE) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < 1024L) return "${value} B"
    val kb = value / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

@Composable
private fun MiniPlayer(
    media: MediaSummary,
    controller: MediaController?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    isWatchLater: Boolean,
    networkType: NetworkType,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onWatchLater: () -> Unit,
    onToggle: () -> Unit
) {
    var positionMs by remember(media.id) { mutableLongStateOf(0L) }
    var durationMs by remember(media.id) { mutableLongStateOf(0L) }

    LaunchedEffect(controller, media.id) {
        while (true) {
            val active = controller
            val isCurrent = active?.currentMediaItem?.mediaId == media.id
            positionMs = if (isCurrent) {
                active.currentPosition.coerceAtLeast(0L)
            } else {
                0L
            }
            durationMs = if (isCurrent) {
                active.duration.takeIf { it > 0L } ?: 0L
            } else {
                0L
            }
            delay(500L)
        }
    }

    Surface(tonalElevation = 4.dp) {
        Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(70.dp).clickable(onClick = onOpen).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                media.thumbnailUrl,
                null,
                Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        media.channel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.AccessTime,
                        null,
                        Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        formatDuration(positionMs / 1000L) + " / " +
                            if (durationMs > 0L) formatDuration(durationMs / 1000L) else "--:--",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-2).dp)
            ) {
                IconButton(
                    onClick = onWatchLater,
                    modifier = Modifier.size(25.dp)
                ) {
                    Icon(
                        if (isWatchLater) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (isWatchLater) "Remove from Watch later" else "Watch later",
                        Modifier.size(MediaActionIconSize)
                    )
                }
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.size(25.dp)
                ) {
                    Icon(
                        if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (isFavorite) "Remove from favorites" else "Add to favorites",
                        Modifier.size(MediaActionIconSize)
                    )
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (isPlaying) "Pause" else "Play"
                )
            }
        }
        NetworkStatusIcon(
            networkType = networkType,
            size = 10.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp)
        )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    modifier: Modifier,
    continueWatching: List<HistoryEntry>,
    movieProviders: MovieProviderRegistry,
    suggestions: List<MediaSummary>,
    movieSuggestions: List<MediaSummary>,
    movieSuggestionsLoading: Boolean,
    recommendationMode: SearchSourceMode,
    onRecommendationModeChange: (SearchSourceMode) -> Unit,
    trending: List<MediaSummary>,
    trendingLoading: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    loading: Boolean,
    onRefresh: () -> Unit,
    resolvingId: String?,
    onContinue: (HistoryEntry) -> Unit,
    onPlay: (MediaSummary, List<MediaSummary>) -> Unit,
    onMovie: (MediaSummary) -> Unit,
    watchLaterIds: Set<String>,
    onToggleWatchLater: (MediaSummary) -> Unit,
    favoriteIds: Set<String>,
    onToggleFavorite: (MediaSummary) -> Unit,
    onSearch: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val displayedRecommendations = if (recommendationMode == SearchSourceMode.MOVIE) {
        movieSuggestions
    } else {
        suggestions
    }
    val recommendationLoading =
        loading || (recommendationMode == SearchSourceMode.MOVIE && movieSuggestionsLoading)
    var showAllRecommendations by remember(recommendationMode) { mutableStateOf(false) }
    val visibleRecommendations =
        if (showAllRecommendations) displayedRecommendations else displayedRecommendations.take(5)
    val trendingListIndex = run {
        var index = 1 // "For you"
        if (continueWatching.isNotEmpty()) {
            index += 1 + continueWatching.size // heading + rows
        }
        if (recommendationLoading) index += 1
        index += 1 // Recommended heading
        if (displayedRecommendations.isNotEmpty()) {
            index += visibleRecommendations.size + 1 // rows + footer
        } else if (!recommendationLoading) {
            index += 1 // empty-state row
        }
        index
    }
    PullToRefreshBox(
        isRefreshing = loading || movieSuggestionsLoading || trendingLoading,
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
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(trendingListIndex)
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Outlined.TrendingUp, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Trending")
                }
            }
        }
        if (continueWatching.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Continue Watching",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(
                continueWatching,
                key = { "home-continue-" + it.mediaId }
            ) { entry ->
                ProgressMediaRow(
                    entry = entry,
                    movieProviders = movieProviders,
                    resolving = resolvingId == entry.mediaId,
                    isWatchLater = entry.mediaId in watchLaterIds,
                    onToggleWatchLater = {
                        onToggleWatchLater(entry.toMediaSummary())
                    },
                    isFavorite = entry.mediaId in favoriteIds,
                    onToggleFavorite = {
                        onToggleFavorite(entry.toMediaSummary())
                    },
                    showPlayAction = false,
                    onClick = { onContinue(entry) },
                    onPlay = { onContinue(entry) }
                )
            }
        }
        if (recommendationLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recommended",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f)
                )
                HomeRecommendationChip(
                    text = "YouTube",
                    selected = recommendationMode == SearchSourceMode.YOUTUBE,
                    onClick = { onRecommendationModeChange(SearchSourceMode.YOUTUBE) }
                )
                Spacer(Modifier.width(4.dp))
                HomeRecommendationChip(
                    text = "Movie",
                    selected = recommendationMode == SearchSourceMode.MOVIE,
                    onClick = { onRecommendationModeChange(SearchSourceMode.MOVIE) }
                )
                if (displayedRecommendations.size > 5) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        modifier = Modifier
                            .height(26.dp)
                            .clickable { showAllRecommendations = !showAllRecommendations },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Box(
                            Modifier.padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (showAllRecommendations) "Less" else "Show all",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
        if (displayedRecommendations.isNotEmpty()) {
            itemsIndexed(
                visibleRecommendations,
                key = { index, media ->
                    "home-rec-" + recommendationMode.name + "-" + index + "-" + media.id
                }
            ) { _, media ->
                CompactMediaRow(
                    media = media,
                    resolving = resolvingId == media.id,
                    isWatchLater = media.id in watchLaterIds,
                    onToggleWatchLater = { onToggleWatchLater(media) },
                    isFavorite = media.id in favoriteIds,
                    onToggleFavorite = { onToggleFavorite(media) }
                ) {
                    if (recommendationMode == SearchSourceMode.MOVIE && movieProvidersTitleId(media.id)) {
                        onMovie(media)
                    } else {
                        onPlay(media, displayedRecommendations)
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (recommendationMode == SearchSourceMode.MOVIE)
                        "Movie suggestions from your recent searches and viewing."
                    else
                        "More personalized topics, channels and playlists will improve as you listen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (!recommendationLoading) {
            item {
                Text(
                    if (recommendationMode == SearchSourceMode.MOVIE)
                        "Search or watch a few movies to build movie recommendations."
                    else
                        "Listen to a few tracks or add favorites to start building recommendations.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Trending now",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (trending.isNotEmpty()) {
                    AssistChip(
                        onClick = { onPlay(trending.first(), trending) },
                        enabled = resolvingId == null,
                        label = { Text("All", maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(15.dp))
                        },
                        modifier = Modifier.height(30.dp)
                    )
                }
            }
            Text(
                "Popular on YouTube right now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trendingLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        itemsIndexed(trending, key = { index, media -> "trend-" + index + "-" + media.id }) { index, media ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (index + 1).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp)
                )
                Box(Modifier.weight(1f)) {
                    CompactMediaRow(
                        media = media,
                        resolving = resolvingId == media.id,
                        isWatchLater = media.id in watchLaterIds,
                        onToggleWatchLater = { onToggleWatchLater(media) },
                        isFavorite = media.id in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(media) },
                        durationInThumbnail = true
                    ) { onPlay(media, trending) }
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
private fun HomeRecommendationChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(26.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline
            )
        }
    ) {
        Box(
            Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun CompactMediaRow(
    media: MediaSummary,
    resolving: Boolean,
    isWatchLater: Boolean = false,
    onToggleWatchLater: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    durationInThumbnail: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !resolving, onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(54.dp)) {
            AsyncImage(
                media.thumbnailUrl,
                null,
                Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)),
                contentScale = ContentScale.Crop
            )
            if (durationInThumbnail) {
                media.durationSeconds.takeIf { it >= 0 }?.let { seconds ->
                    Text(
                        formatDuration(seconds),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.82f
                        ),
                        color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(1.dp)
                            .background(
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.80f),
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            TouchMarqueeTitle(text = media.title)
            Text(media.channel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val meta = mediaMeta(media, includeDuration = !durationInThumbnail)
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (resolving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                onToggleWatchLater?.let { action ->
                    IconButton(
                        onClick = action,
                        modifier = Modifier.size(MediaActionButtonSize)
                    ) {
                        Icon(
                            if (isWatchLater) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            if (isWatchLater) "Remove from Watch later" else "Watch later",
                            Modifier.size(MediaActionIconSize)
                        )
                    }
                }
                onToggleFavorite?.let { action ->
                    IconButton(
                        onClick = action,
                        modifier = Modifier.size(MediaActionButtonSize)
                    ) {
                        Icon(
                            if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            if (isFavorite) "Remove favorite" else "Add favorite",
                            Modifier.size(MediaActionIconSize)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPlaybackProgress(
    progress: Float,
    durationMs: Long = 0L
) {
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
        if (durationMs > 0L) {
            Spacer(Modifier.width(5.dp))
            Text(
                formatDuration(durationMs / 1000L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

private fun isMovieLibraryItem(mediaId: String, channel: String): Boolean {
    if (mediaId.startsWith("movie:") || mediaId.startsWith("movie-title:")) return true
    val source = channel.lowercase()
    return "motphim" in source ||
        "kkphim" in source ||
        "phimapi" in source ||
        "bilibili" in source ||
        "biliintl" in source
}

@Composable
private fun LibrarySourceBadge(
    mediaId: String,
    channel: String,
    modifier: Modifier = Modifier
) {
    val isMovie = isMovieLibraryItem(mediaId, channel)
    Surface(
        modifier = modifier.size(16.dp),
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isMovie) {
                    Icons.Outlined.LocalMovies
                } else {
                    Icons.Outlined.PlayCircle
                },
                contentDescription = if (isMovie) "Movie" else "YouTube",
                modifier = Modifier.size(10.dp),
                tint = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

private fun movieLookupTitle(title: String): String =
    title
        .substringBefore(" · ")
        .replace(Regex("""(?i)\s*[-–—]?\s*(?:tập|tap|episode|ep)\s*\d+\s*$"""), "")
        .trim()

@Composable
private fun LibraryThumbnail(
    mediaId: String,
    title: String,
    channel: String,
    thumbnailUrl: String?,
    movieProviders: MovieProviderRegistry,
    modifier: Modifier,
    cornerRadius: Int
) {
    var enrichedThumbnail by remember(mediaId, thumbnailUrl) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(mediaId, title, channel, thumbnailUrl) {
        if (!thumbnailUrl.isNullOrBlank() || !isMovieLibraryItem(mediaId, channel)) {
            enrichedThumbnail = null
            return@LaunchedEffect
        }

        val lookupTitle = movieLookupTitle(title)
        if (lookupTitle.isBlank()) return@LaunchedEffect

        enrichedThumbnail = withContext(Dispatchers.IO) {
            runCatching {
                movieProviders.search(lookupTitle)
                    .firstOrNull()
                    ?.thumbnailUrl
            }.getOrNull()
        }
    }

    Box(modifier) {
        AsyncImage(
            model = thumbnailUrl?.takeIf { it.isNotBlank() } ?: enrichedThumbnail,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        LibrarySourceBadge(
            mediaId = mediaId,
            channel = channel,
            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
        )
    }
}

@Composable
private fun ProgressMediaRow(
    entry: HistoryEntry,
    movieProviders: MovieProviderRegistry,
    resolving: Boolean,
    isWatchLater: Boolean = false,
    onToggleWatchLater: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    showPlayAction: Boolean = true,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !resolving, onClick = onClick).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibraryThumbnail(
            mediaId = entry.mediaId,
            title = entry.title,
            channel = entry.channel,
            thumbnailUrl = entry.thumbnailUrl,
            movieProviders = movieProviders,
            modifier = Modifier.size(54.dp),
            cornerRadius = 9
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            TouchMarqueeTitle(text = entry.title)
            Text(entry.channel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            SearchPlaybackProgress(
                progress = entry.progress,
                durationMs = entry.durationMs
            )
        }
        if (resolving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                onToggleWatchLater?.let { action ->
                    IconButton(
                        onClick = action,
                        modifier = Modifier.size(MediaActionButtonSize)
                    ) {
                        Icon(
                            if (isWatchLater) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            if (isWatchLater) "Remove from Watch later" else "Watch later",
                            Modifier.size(MediaActionIconSize)
                        )
                    }
                }
                onAddToPlaylist?.let { action ->
                    IconButton(
                        onClick = action,
                        modifier = Modifier.size(MediaActionButtonSize)
                    ) {
                        Icon(Icons.Outlined.PlaylistAdd, "Add to playlist", Modifier.size(MediaActionIconSize))
                    }
                }
                onToggleFavorite?.let { action ->
                    IconButton(
                        onClick = action,
                        modifier = Modifier.size(MediaActionButtonSize)
                    ) {
                        Icon(
                            if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            if (isFavorite) "Remove favorite" else "Add favorite",
                            Modifier.size(MediaActionIconSize)
                        )
                    }
                }
                if (showPlayAction) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(MediaActionButtonSize)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, "Continue", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun LibraryScreen(
    modifier: Modifier,
    recent: List<HistoryEntry>,
    favorites: List<FavoriteEntry>,
    watchLater: List<WatchLaterEntry>,
    personalPlaylists: List<PersonalPlaylist>,
    nowPlaying: MediaSummary?,
    movieProviders: MovieProviderRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    resolvingId: String?,
    onPlay: (HistoryEntry) -> Unit,
    onOpenMovie: (HistoryEntry) -> Unit,
    onOpenFavoriteMovie: (FavoriteEntry) -> Unit,
    onPlayFavorite: (FavoriteEntry) -> Unit,
    onPlayAllYouTubeFavorites: () -> Unit,
    onPlayAllMovieFavorites: () -> Unit,
    onRemoveFavorite: (FavoriteEntry) -> Unit,
    onPlayWatchLater: (WatchLaterEntry) -> Unit,
    onPlayAllWatchLater: () -> Unit,
    onRemoveWatchLater: (WatchLaterEntry) -> Unit,
    onToggleWatchLaterMedia: (MediaSummary) -> Unit,
    onAddToPlaylist: (String, MediaSummary) -> Unit,
    onCreatePlaylistWithMedia: (String, MediaSummary) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPinPlaylist: (String, Boolean) -> Unit,
    onDuplicatePlaylist: (String) -> Unit,
    onClearPlaylist: (String) -> Unit,
    onAddCurrentToPlaylist: (String) -> Unit,
    onRemovePlaylistItem: (String, String) -> Unit,
    onMovePlaylistItem: (String, Int, Int) -> Unit,
    onPlayPlaylist: (PersonalPlaylist) -> Unit,
    onPlayPlaylistItem: (MediaSummary, List<MediaSummary>) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClear: () -> Unit
) {
    var showAllHistory by remember { mutableStateOf(false) }
    var showAllYouTubeFavorites by remember { mutableStateOf(false) }
    var showAllMovieFavorites by remember { mutableStateOf(false) }
    var showAllWatchLater by remember { mutableStateOf(false) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var renamePlaylist by remember { mutableStateOf<PersonalPlaylist?>(null) }
    var playlistNameDraft by remember { mutableStateOf("") }
    var playlistTarget by remember { mutableStateOf<MediaSummary?>(null) }
    val youtubeFavorites = remember(favorites) {
        favorites.filterNot { isMovieLibraryItem(it.mediaId, it.channel) }
    }
    val movieFavorites = remember(favorites) {
        favorites.filter { isMovieLibraryItem(it.mediaId, it.channel) }
    }
    val continueRecent = remember(recent) {
        recent.filter { entry ->
            entry.durationMs <= 0L || entry.progress < 0.95f
        }.take(3)
    }
    val historyByMediaId = remember(recent) { recent.associateBy { it.mediaId } }

    val selectedPlaylist = selectedPlaylistId?.let { id ->
        personalPlaylists.firstOrNull { it.id == id }
    }
    if (selectedPlaylist != null) {
        BackHandler { selectedPlaylistId = null }
        PersonalPlaylistDetailScreen(
            modifier = modifier,
            playlist = selectedPlaylist,
            nowPlaying = nowPlaying,
            resolvingId = resolvingId,
            onBack = { selectedPlaylistId = null },
            onPlayAll = { onPlayPlaylist(selectedPlaylist) },
            onShuffle = {
                if (selectedPlaylist.items.isNotEmpty()) {
                    val shuffled = selectedPlaylist.items.shuffled()
                    onPlayPlaylistItem(shuffled.first(), shuffled)
                }
            },
            onAddCurrent = { onAddCurrentToPlaylist(selectedPlaylist.id) },
            onRename = {
                playlistNameDraft = selectedPlaylist.name
                renamePlaylist = selectedPlaylist
            },
            onPin = { onPinPlaylist(selectedPlaylist.id, !selectedPlaylist.pinned) },
            onDuplicate = { onDuplicatePlaylist(selectedPlaylist.id) },
            onClear = { onClearPlaylist(selectedPlaylist.id) },
            onDelete = {
                onDeletePlaylist(selectedPlaylist.id)
                selectedPlaylistId = null
            },
            onPlayItem = { media, items -> onPlayPlaylistItem(media, items) },
            onRemoveItem = { mediaId -> onRemovePlaylistItem(selectedPlaylist.id, mediaId) },
            onMoveItem = { from, to -> onMovePlaylistItem(selectedPlaylist.id, from, to) }
        )

        renamePlaylist?.let { playlist ->
            AlertDialog(
                onDismissRequest = { renamePlaylist = null },
                title = { Text("Rename playlist") },
                text = {
                    OutlinedTextField(
                        value = playlistNameDraft,
                        onValueChange = { playlistNameDraft = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenamePlaylist(playlist.id, playlistNameDraft)
                            renamePlaylist = null
                            playlistNameDraft = ""
                        }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { renamePlaylist = null }) { Text("Cancel") }
                }
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 14.dp),
        state = listState,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stickyHeader {
            Surface(tonalElevation = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Favorites",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (favorites.isEmpty()) {
            item {
                Text(
                    "Favorite videos and movies will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            if (youtubeFavorites.isNotEmpty()) {
                item(key = "favorites-youtube-heading") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.PlayCircle, null, Modifier.size(MediaActionIconSize))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "YouTube",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        if (youtubeFavorites.size > 3) {
                            TextButton(
                                onClick = {
                                    showAllYouTubeFavorites = !showAllYouTubeFavorites
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (showAllYouTubeFavorites) "Show less" else "View all"
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onPlayAllYouTubeFavorites,
                            enabled = resolvingId == null,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(MediaActionIconSize))
                            Spacer(Modifier.width(3.dp))
                            Text("All")
                        }
                    }
                }
                items(
                    if (showAllYouTubeFavorites) youtubeFavorites else youtubeFavorites.take(3),
                    key = { "fav-yt-" + it.mediaId }
                ) { entry ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = resolvingId == null) {
                                onPlayFavorite(entry)
                            }
                            .padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LibraryThumbnail(
                            mediaId = entry.mediaId,
                            title = entry.title,
                            channel = entry.channel,
                            thumbnailUrl = entry.thumbnailUrl,
                            movieProviders = movieProviders,
                            modifier = Modifier.size(52.dp),
                            cornerRadius = 8
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            TouchMarqueeTitle(text = entry.title)
                            Text(
                                entry.channel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy((-1).dp)
                        ) {
                            IconButton(
                                onClick = { onRemoveFavorite(entry) },
                                modifier = Modifier.size(MediaActionButtonSize)
                            ) {
                                Icon(
                                    Icons.Outlined.Favorite,
                                    "Remove favorite",
                                    Modifier.size(MediaActionIconSize)
                                )
                            }
                            IconButton(
                                onClick = { playlistTarget = entry.toMediaSummary() },
                                modifier = Modifier.size(MediaActionButtonSize)
                            ) {
                                Icon(Icons.Outlined.PlaylistAdd, "Add to playlist", Modifier.size(MediaActionIconSize))
                            }
                        }
                    }
                }
            }

            if (movieFavorites.isNotEmpty()) {
                item(key = "favorites-movie-heading") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.LocalMovies, null, Modifier.size(MediaActionIconSize))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Movie",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        if (movieFavorites.size > 3) {
                            TextButton(
                                onClick = {
                                    showAllMovieFavorites = !showAllMovieFavorites
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (showAllMovieFavorites) "Show less" else "View all"
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onPlayAllMovieFavorites,
                            enabled = resolvingId == null,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(MediaActionIconSize))
                            Spacer(Modifier.width(3.dp))
                            Text("All")
                        }
                    }
                }
                items(
                    if (showAllMovieFavorites) movieFavorites else movieFavorites.take(3),
                    key = { "fav-movie-" + it.mediaId }
                ) { entry ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = resolvingId == null) {
                                onOpenFavoriteMovie(entry)
                            }
                            .padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LibraryThumbnail(
                            mediaId = entry.mediaId,
                            title = entry.title,
                            channel = entry.channel,
                            thumbnailUrl = entry.thumbnailUrl,
                            movieProviders = movieProviders,
                            modifier = Modifier.size(52.dp),
                            cornerRadius = 8
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            TouchMarqueeTitle(text = entry.title)
                            Text(
                                entry.channel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy((-1).dp)
                        ) {
                            IconButton(
                                onClick = { onRemoveFavorite(entry) },
                                modifier = Modifier.size(MediaActionButtonSize)
                            ) {
                                Icon(
                                    Icons.Outlined.Favorite,
                                    "Remove favorite",
                                    Modifier.size(MediaActionIconSize)
                                )
                            }
                            IconButton(
                                onClick = { playlistTarget = entry.toMediaSummary() },
                                modifier = Modifier.size(MediaActionButtonSize)
                            ) {
                                Icon(
                                    Icons.Outlined.PlaylistAdd,
                                    "Add to playlist",
                                    Modifier.size(MediaActionIconSize)
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "watch-later-heading") {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Watch later",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        watchLater.size.toString() + " saved · watched items clear automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (watchLater.size > 3) {
                    TextButton(
                        onClick = { showAllWatchLater = !showAllWatchLater },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(if (showAllWatchLater) "Show less" else "View all")
                    }
                }
                if (watchLater.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onPlayAllWatchLater,
                        enabled = resolvingId == null,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("All")
                    }
                }
            }
        }

        if (watchLater.isEmpty()) {
            item(key = "watch-later-empty") {
                Text(
                    "Save videos and movies here to watch them later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(
                if (showAllWatchLater) watchLater else watchLater.take(3),
                key = { "watch-later-" + it.mediaId }
            ) { entry ->
                val progressEntry = historyByMediaId[entry.mediaId]
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = resolvingId == null) {
                            onPlayWatchLater(entry)
                        }
                        .padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryThumbnail(
                        mediaId = entry.mediaId,
                        title = entry.title,
                        channel = entry.channel,
                        thumbnailUrl = entry.thumbnailUrl,
                        movieProviders = movieProviders,
                        modifier = Modifier.size(58.dp),
                        cornerRadius = 9
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        TouchMarqueeTitle(
                            text = entry.title,
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            entry.channel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (progressEntry != null) {
                            SearchPlaybackProgress(progress = progressEntry.progress)
                        } else {
                            Text(
                                "Not started",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (resolvingId == entry.mediaId) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy((-1).dp)
                        ) {
                            IconButton(
                                onClick = { onRemoveWatchLater(entry) },
                                modifier = Modifier.size(MediaActionButtonSize)
                            ) {
                                Icon(
                                    Icons.Outlined.Bookmark,
                                    "Remove from Watch later",
                                    Modifier.size(MediaActionIconSize)
                                )
                            }
                            IconButton(
                                onClick = { playlistTarget = entry.toMediaSummary() },
                                modifier = Modifier.size(MediaActionButtonSize)
                            ) {
                                Icon(Icons.Outlined.PlaylistAdd, "Add to playlist", Modifier.size(MediaActionIconSize))
                            }
                        }
                    }
                }
            }
        }

        item(key = "personal-playlists-heading") {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Personal playlists",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (personalPlaylists.isEmpty()) "Local playlists · no login required"
                        else personalPlaylists.size.toString() + " playlists · local",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = {
                        playlistNameDraft = ""
                        showCreatePlaylist = true
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(MediaActionIconSize))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
        }

        if (personalPlaylists.isEmpty()) {
            item(key = "personal-playlists-empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            null,
                            Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Build your own queue", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Create a playlist, then add the current item. Playlist V2 keeps custom order and works offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                personalPlaylists,
                key = { "personal-playlist-" + it.id }
            ) { playlist ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { selectedPlaylistId = playlist.id },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val cover = playlist.items.firstOrNull()?.thumbnailUrl
                            if (!cover.isNullOrBlank()) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    null,
                                    Modifier.align(Alignment.Center).size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (playlist.pinned) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                                ) {
                                    Icon(
                                        Icons.Outlined.PushPin,
                                        "Pinned",
                                        Modifier.padding(3.dp).size(12.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                playlist.items.size.toString() + " items" +
                                    if (playlist.items.isNotEmpty()) {
                                        " · " + formatPlaylistDuration(playlist.items)
                                    } else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            playlist.items.firstOrNull()?.let { first ->
                                Text(
                                    playlist.items.getOrNull(1)?.let { second ->
                                        first.title + " · " + second.title
                                    } ?: first.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { onAddCurrentToPlaylist(playlist.id) },
                            enabled = nowPlaying != null,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.PlaylistAdd,
                                "Add current",
                                Modifier.size(MediaActionIconSize)
                            )
                        }
                        IconButton(
                            onClick = { onPlayPlaylist(playlist) },
                            enabled = playlist.items.isNotEmpty() && resolvingId == null,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, "Play all", Modifier.size(20.dp))
                        }
                        Icon(
                            Icons.Outlined.KeyboardArrowRight,
                            "Open playlist",
                            Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        stickyHeader {
            Surface(tonalElevation = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Continue your latest unfinished items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (continueRecent.isEmpty()) {
            item {
                Text(
                    if (recent.isEmpty()) "Nothing played yet." else "No unfinished items.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            items(continueRecent, key = { "recent-" + it.mediaId }) { entry ->
                ProgressMediaRow(
                    entry = entry,
                    movieProviders = movieProviders,
                    resolving = resolvingId == entry.mediaId,
                    isWatchLater = watchLater.any { it.mediaId == entry.mediaId },
                    onToggleWatchLater = {
                        onToggleWatchLaterMedia(entry.toMediaSummary())
                    },
                    onAddToPlaylist = { playlistTarget = entry.toMediaSummary() },
                    showPlayAction = false,
                    onClick = {
                        if (isMovieLibraryItem(entry.mediaId, entry.channel)) {
                            onOpenMovie(entry)
                        } else {
                            onPlay(entry)
                        }
                    },
                    onPlay = { onPlay(entry) }
                )
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
                    .clickable(enabled = resolvingId == null) {
                        if (isMovieLibraryItem(entry.mediaId, entry.channel)) {
                            onOpenMovie(entry)
                        } else {
                            onPlay(entry)
                        }
                    }.padding(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LibraryThumbnail(
                    mediaId = entry.mediaId,
                    title = entry.title,
                    channel = entry.channel,
                    thumbnailUrl = entry.thumbnailUrl,
                    movieProviders = movieProviders,
                    modifier = Modifier.size(58.dp),
                    cornerRadius = 9
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    TouchMarqueeTitle(
                        text = entry.title,
                        maxLines = 1,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(entry.channel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SearchPlaybackProgress(
                        progress = entry.progress,
                        durationMs = entry.durationMs
                    )
                }
                if (resolvingId == entry.mediaId) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy((-1).dp)
                    ) {
                        IconButton(
                            onClick = { onDelete(entry) },
                            modifier = Modifier.size(MediaActionButtonSize)
                        ) {
                            Icon(Icons.Outlined.Close, "Remove from history", Modifier.size(MediaActionIconSize))
                        }
                        IconButton(
                            onClick = { playlistTarget = entry.toMediaSummary() },
                            modifier = Modifier.size(MediaActionButtonSize)
                        ) {
                            Icon(Icons.Outlined.PlaylistAdd, "Add to playlist", Modifier.size(MediaActionIconSize))
                        }

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

    playlistTarget?.let { target ->
        AddToPlaylistDialog(
            media = target,
            playlists = personalPlaylists,
            onDismiss = { playlistTarget = null },
            onSelect = { playlistId ->
                onAddToPlaylist(playlistId, target)
                playlistTarget = null
            },
            onCreate = { name ->
                onCreatePlaylistWithMedia(name, target)
                playlistTarget = null
            }
        )
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = playlistNameDraft,
                    onValueChange = { playlistNameDraft = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreatePlaylist(playlistNameDraft)
                        showCreatePlaylist = false
                        playlistNameDraft = ""
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") }
            }
        )
    }

    renamePlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { renamePlaylist = null },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = playlistNameDraft,
                    onValueChange = { playlistNameDraft = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenamePlaylist(playlist.id, playlistNameDraft)
                        renamePlaylist = null
                        playlistNameDraft = ""
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamePlaylist = null }) { Text("Cancel") }
            }
        )
    }
}

private fun formatPlaylistDuration(items: List<MediaSummary>): String {
    val seconds = items.map { it.durationSeconds }.filter { it > 0L }.sum()
    if (seconds <= 0L) return "duration unknown"
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun PersonalPlaylistDetailScreen(
    modifier: Modifier,
    playlist: PersonalPlaylist,
    nowPlaying: MediaSummary?,
    resolvingId: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddCurrent: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onPlayItem: (MediaSummary, List<MediaSummary>) -> Unit,
    onRemoveItem: (String) -> Unit,
    onMoveItem: (Int, Int) -> Unit
) {
    var query by remember(playlist.id) { mutableStateOf("") }
    var sortMode by remember(playlist.id) { mutableStateOf("Custom") }
    var showMenu by remember(playlist.id) { mutableStateOf(false) }
    var confirmClear by remember(playlist.id) { mutableStateOf(false) }
    var confirmDelete by remember(playlist.id) { mutableStateOf(false) }

    val displayedItems = remember(playlist.items, query, sortMode) {
        val filtered = if (query.isBlank()) {
            playlist.items
        } else {
            val needle = query.trim().lowercase()
            playlist.items.filter {
                it.title.lowercase().contains(needle) ||
                    it.channel.lowercase().contains(needle)
            }
        }
        when (sortMode) {
            "A-Z" -> filtered.sortedBy { it.title.lowercase() }
            "Z-A" -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered
        }
    }
    val canReorder = query.isBlank() && sortMode == "Custom"
    val listState = rememberLazyListState()
    val currentDisplayedIndex = displayedItems.indexOfFirst { it.id == nowPlaying?.id }

    LaunchedEffect(nowPlaying?.id, playlist.id, sortMode, query) {
        if (currentDisplayedIndex >= 0 && query.isBlank()) {
            runCatching {
                listState.animateScrollToItem(currentDisplayedIndex)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    playlist.items.size.toString() + " items · " + formatPlaylistDuration(playlist.items),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (playlist.pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    "Pinned",
                    Modifier.size(MediaActionIconSize),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(2.dp))
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Playlist menu")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (playlist.pinned) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = { showMenu = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { showMenu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { showMenu = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear items") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                        enabled = playlist.items.isNotEmpty(),
                        onClick = { showMenu = false; confirmClear = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = { showMenu = false; confirmDelete = true }
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(74.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val cover = playlist.items.firstOrNull()?.thumbnailUrl
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            null,
                            Modifier.align(Alignment.Center).size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Local playlist · custom order",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onPlayAll,
                            enabled = playlist.items.isNotEmpty() && resolvingId == null,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(MediaActionIconSize))
                            Spacer(Modifier.width(3.dp))
                            Text("All")
                        }
                        FilledTonalButton(
                            onClick = onShuffle,
                            enabled = playlist.items.size > 1 && resolvingId == null,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.Shuffle, null, Modifier.size(MediaActionIconSize))
                            Spacer(Modifier.width(3.dp))
                            Text("Shuffle")
                        }
                        IconButton(
                            onClick = onAddCurrent,
                            enabled = nowPlaying != null,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Outlined.PlaylistAdd, "Add current", Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        if (playlist.items.size >= 4) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(MediaActionIconSize)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search in playlist") },
                shape = RoundedCornerShape(14.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Custom", "A-Z", "Z-A").forEach { option ->
                FilterChip(
                    selected = sortMode == option,
                    onClick = { sortMode = option },
                    label = { Text(option) },
                    modifier = Modifier.height(32.dp)
                )
            }
            if (!canReorder && playlist.items.size > 1) {
                Text(
                    "Reorder in Custom view",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        if (playlist.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                        null,
                        Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Playlist is empty", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Play something, then use Add current.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (displayedItems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No items match “$query”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(displayedItems, key = { "local-pl-" + it.id }) { media ->
                    val originalIndex = playlist.items.indexOfFirst { it.id == media.id }
                    val isCurrent = nowPlaying?.id == media.id
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = resolvingId == null) {
                                    onPlayItem(media, displayedItems)
                                }
                                .padding(horizontal = 5.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                AsyncImage(
                                    model = media.thumbnailUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop
                                )
                                if (isCurrent) {
                                    Surface(
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Icon(
                                            Icons.Outlined.GraphicEq,
                                            "Playing",
                                            Modifier.padding(2.dp).size(12.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                TouchMarqueeTitle(
                                    text = media.title,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else null
                                )
                                Text(
                                    media.channel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (media.durationSeconds > 0L) {
                                    Text(
                                        formatDuration(media.durationSeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (resolvingId == media.id) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                if (canReorder) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy((-3).dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (originalIndex > 0) {
                                                    onMoveItem(originalIndex, originalIndex - 1)
                                                }
                                            },
                                            enabled = originalIndex > 0,
                                            modifier = Modifier.size(25.dp)
                                        ) {
                                            Icon(Icons.Outlined.KeyboardArrowUp, "Move up", Modifier.size(MediaActionIconSize))
                                        }
                                        IconButton(
                                            onClick = {
                                                if (originalIndex in 0 until playlist.items.lastIndex) {
                                                    onMoveItem(originalIndex, originalIndex + 1)
                                                }
                                            },
                                            enabled = originalIndex in 0 until playlist.items.lastIndex,
                                            modifier = Modifier.size(25.dp)
                                        ) {
                                            Icon(Icons.Outlined.KeyboardArrowDown, "Move down", Modifier.size(MediaActionIconSize))
                                        }
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy((-3).dp)
                                ) {
                                    IconButton(
                                        onClick = { onRemoveItem(media.id) },
                                        modifier = Modifier.size(25.dp)
                                    ) {
                                        Icon(Icons.Outlined.Close, "Remove", Modifier.size(MediaActionIconSize))
                                    }
                                    IconButton(
                                        onClick = { onPlayItem(media, displayedItems) },
                                        modifier = Modifier.size(25.dp)
                                    ) {
                                        Icon(Icons.Outlined.PlayArrow, "Play", Modifier.size(MediaActionIconSize))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear playlist?") },
            text = { Text("Remove all items from “${playlist.name}”? The playlist itself stays.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("Delete “${playlist.name}” and its local item list?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
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
                            Text("All")
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
        media.uploadDateText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        if (includeDuration && media.durationSeconds >= 0) add(formatDuration(media.durationSeconds))
    }.joinToString(" · ")

private data class FullPlayerState(
    val media: MediaSummary,
    val controller: MediaController?,
    val isPlaying: Boolean,
    val quality: AudioQuality,
    val isMovie: Boolean,
    val subtitleOptions: List<SubtitleOption>,
    val selectedSubtitleLabel: String?,
    val videoQuality: VideoQuality,
    val actualVideoHeight: Int,
    val videoMode: Boolean,
    val playbackSpeed: Float,
    val compatibilityFallback: Boolean,
    val isBuffering: Boolean,
    val playerError: String?,
    val movieDiagnostic: String?,
    val sabrDiagnostic: String?,
    val developerMode: Boolean,
    val sleepMinutes: Int?,
    val autoplay: Boolean,
    val nextMode: NextMode,
    val repeatMode: RepeatMode,
    val queue: List<MediaSummary>,
    val queueIndex: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val isFavorite: Boolean,
    val isWatchLater: Boolean,
    val personalPlaylists: List<PersonalPlaylist>,
    val networkType: NetworkType
)

private class FullPlayerActions(
    val onSubtitle: (SubtitleOption?) -> Unit,
    val onVideoMode: (Boolean) -> Unit,
    val onQuality: (AudioQuality) -> Unit,
    val onVideoQuality: (VideoQuality) -> Unit,
    val onSpeed: (Float) -> Unit,
    val onRetry: () -> Unit,
    val onSleep: (Int?) -> Unit,
    val onAutoplay: (Boolean) -> Unit,
    val onNextMode: (NextMode) -> Unit,
    val onRepeatMode: (RepeatMode) -> Unit,
    val onQueueItem: (Int) -> Unit,
    val onQueueRemove: (Int) -> Unit,
    val onQueueMove: (Int, Int) -> Unit,
    val onQueueClearUpcoming: () -> Unit,
    val onQueueRefresh: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onChannel: () -> Unit,
    val onFavorite: () -> Unit,
    val onWatchLater: () -> Unit,
    val onAddToPlaylist: (String, MediaSummary) -> Unit,
    val onCreatePlaylistWithMedia: (String, MediaSummary) -> Unit,
    val onToggle: () -> Unit,
    val onClose: () -> Unit
)

@Composable
private fun FullPlayer(
    state: FullPlayerState,
    actions: FullPlayerActions
) {
    val media = state.media
    val controller = state.controller
    val isPlaying = state.isPlaying
    val quality = state.quality
    val isMovie = state.isMovie
    val subtitleOptions = state.subtitleOptions
    val selectedSubtitleLabel = state.selectedSubtitleLabel
    val videoQuality = state.videoQuality
    val actualVideoHeight = state.actualVideoHeight
    val videoMode = state.videoMode
    val playbackSpeed = state.playbackSpeed
    val compatibilityFallback = state.compatibilityFallback
    val isBuffering = state.isBuffering
    val playerError = state.playerError
    val movieDiagnostic = state.movieDiagnostic
    val sabrDiagnostic = state.sabrDiagnostic
    val developerMode = state.developerMode
    val sleepMinutes = state.sleepMinutes
    val autoplay = state.autoplay
    val nextMode = state.nextMode
    val effectiveNextMode = if (isMovie) NextMode.LIST else nextMode
    val repeatMode = state.repeatMode
    val queue = state.queue
    val queueIndex = state.queueIndex
    val hasPrevious = state.hasPrevious
    val hasNext = state.hasNext
    val isFavorite = state.isFavorite
    val isWatchLater = state.isWatchLater
    val personalPlaylists = state.personalPlaylists
    val containingPlaylists = remember(personalPlaylists, media.id) {
        personalPlaylists.filter { playlist ->
            playlist.items.any { it.id == media.id }
        }
    }
    val playlistMembershipLabel = containingPlaylists.firstOrNull()?.let { first ->
        if (containingPlaylists.size > 1) {
            first.name + " +" + (containingPlaylists.size - 1)
        } else {
            first.name
        }
    }
    val networkType = state.networkType
    val movieEpisodePosition =
        if (isMovie && queue.size > 1 && queueIndex in queue.indices) {
            "${queueIndex + 1} / ${queue.size}"
        } else {
            null
        }
    val movieNextLabel =
        if (isMovie && queueIndex in queue.indices && queueIndex < queue.lastIndex) {
            queue[queueIndex + 1].title
                .substringAfter(" · ", queue[queueIndex + 1].title)
                .takeIf { it.isNotBlank() }
        } else {
            null
        }

    val onSubtitle = actions.onSubtitle
    val onVideoMode = actions.onVideoMode
    val onQuality = actions.onQuality
    val onVideoQuality = actions.onVideoQuality
    val onSpeed = actions.onSpeed
    val onRetry = actions.onRetry
    val onSleep = actions.onSleep
    val onAutoplay = actions.onAutoplay
    val onNextMode = actions.onNextMode
    val onRepeatMode = actions.onRepeatMode
    val onQueueItem = actions.onQueueItem
    val onQueueRemove = actions.onQueueRemove
    val onQueueMove = actions.onQueueMove
    val onQueueClearUpcoming = actions.onQueueClearUpcoming
    val onQueueRefresh = actions.onQueueRefresh
    val onPrevious = actions.onPrevious
    val onNext = actions.onNext
    val onChannel = actions.onChannel
    val onFavorite = actions.onFavorite
    val onWatchLater = actions.onWatchLater
    val onAddToPlaylist = actions.onAddToPlaylist
    val onCreatePlaylistWithMedia = actions.onCreatePlaylistWithMedia
    val onToggle = actions.onToggle
    val onClose = actions.onClose

    val context = LocalContext.current
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showQueue by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember(media.id) { mutableStateOf(false) }
    var draggedQueueId by remember { mutableStateOf<String?>(null) }
    var draggedQueueIndex by remember { mutableIntStateOf(-1) }
    var draggedQueueStartIndex by remember { mutableIntStateOf(-1) }
    var dragQueueY by remember { mutableFloatStateOf(0f) }
    var holdSeekPreviewMs by remember { mutableLongStateOf(-1L) }
    var showDeveloperDiagnostics by remember { mutableStateOf(false) }
    var fullscreenRequested by remember { mutableStateOf(false) }
    var fullscreenRendered by remember { mutableStateOf(false) }
    var fullscreenControlsVisible by remember { mutableStateOf(true) }
    var fullscreenControlsEpoch by remember { mutableIntStateOf(0) }
    var fullscreenSeeking by remember { mutableStateOf(false) }
    var fullscreenSeekPreviewMs by remember { mutableLongStateOf(-1L) }
    var fullscreenSpeedMenu by remember { mutableStateOf(false) }
    var fullscreenQualityMenu by remember { mutableStateOf(false) }
    var fullscreenSubtitleMenu by remember { mutableStateOf(false) }
    var fullscreenSleepMenu by remember { mutableStateOf(false) }
    val playerScrollState = rememberScrollState()
    val queueScrollState = rememberScrollState()
    val dragScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current

    LaunchedEffect(showQueue, queueIndex, queue.size) {
        if (showQueue && queueIndex in queue.indices) {
            // Up next is a bounded window that starts at the current item. Reset to
            // the top whenever playback advances so the current row stays visible.
            delay(60L)
            queueScrollState.animateScrollTo(0)
        }
    }
    LaunchedEffect(developerMode) {
        if (!developerMode) showDeveloperDiagnostics = false
    }
    LaunchedEffect(controller, media.id) {
        while (true) {
            position = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = controller?.duration?.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    val activity = context as? Activity
    val originalOrientation = remember(activity) { activity?.requestedOrientation }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Keep the normal player visible while Android is rotating into landscape.
    // Likewise, keep the fullscreen surface alive while rotating back to portrait.
    // This prevents Compose from rendering the wrong layout for a transient
    // orientation and avoids the diagonal/half-rotated frames seen during transition.
    LaunchedEffect(fullscreenRequested, isLandscape) {
        when {
            fullscreenRequested && isLandscape -> {
                fullscreenRendered = true
                fullscreenControlsVisible = true
                fullscreenControlsEpoch += 1
            }
            !fullscreenRequested && !isLandscape -> {
                fullscreenRendered = false
                fullscreenSpeedMenu = false
                fullscreenQualityMenu = false
                fullscreenSubtitleMenu = false
                fullscreenSleepMenu = false
            }
        }
    }

    DisposableEffect(fullscreenRequested, activity) {
        if (fullscreenRequested && activity != null) {
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

    BackHandler(enabled = fullscreenRequested || fullscreenRendered) {
        fullscreenRequested = false
    }

    LaunchedEffect(
        fullscreenRendered,
        fullscreenControlsVisible,
        fullscreenControlsEpoch,
        fullscreenSpeedMenu,
        fullscreenQualityMenu,
        fullscreenSubtitleMenu,
        fullscreenSleepMenu
    ) {
        if (
            fullscreenRendered &&
            fullscreenControlsVisible &&
            !fullscreenSpeedMenu &&
            !fullscreenQualityMenu &&
            !fullscreenSubtitleMenu &&
            !fullscreenSleepMenu
        ) {
            delay(2_500L)
            fullscreenControlsVisible = false
        }
    }

    if (fullscreenRendered && videoMode && controller != null) {
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
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
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

            if (
                developerMode &&
                showDeveloperDiagnostics &&
                !isMovie &&
                !sabrDiagnostic.isNullOrBlank()
            ) {
                val fullscreenDiagnostic = sabrDiagnostic
                    .split('\n')
                    .takeLast(8)
                    .joinToString("\n")
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.58f)
                ) {
                    Text(
                        fullscreenDiagnostic,
                        modifier = Modifier.padding(8.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

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
                if (movieEpisodePosition != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 14.dp, top = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.34f)
                    ) {
                        Column(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            Text(
                                "Episode $movieEpisodePosition",
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            movieNextLabel?.let { nextLabel ->
                                Text(
                                    "Next: $nextLabel",
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.82f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

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
                                fullscreenSubtitleMenu = false
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
                                fullscreenSubtitleMenu = false
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

                    if (isMovie) {
                        Box {
                            Surface(
                                modifier = Modifier.height(28.dp).clickable {
                                    fullscreenSubtitleMenu = !fullscreenSubtitleMenu
                                    fullscreenSpeedMenu = false
                                    fullscreenQualityMenu = false
                                    fullscreenSleepMenu = false
                                    fullscreenControlsEpoch += 1
                                },
                                shape = RoundedCornerShape(50),
                                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
                            ) {
                                Box(
                                    Modifier.padding(horizontal = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        selectedSubtitleLabel?.let { "CC $it" } ?: "CC Off",
                                        color = androidx.compose.ui.graphics.Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = fullscreenSubtitleMenu,
                                onDismissRequest = { fullscreenSubtitleMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (selectedSubtitleLabel == null) "✓ " else "") +
                                                "CC Off"
                                        )
                                    },
                                    onClick = {
                                        fullscreenSubtitleMenu = false
                                        onSubtitle(null)
                                        fullscreenControlsEpoch += 1
                                    }
                                )
                                subtitleOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (selectedSubtitleLabel == option.label) "✓ " else "") +
                                                    option.label
                                            )
                                        },
                                        onClick = {
                                            fullscreenSubtitleMenu = false
                                            onSubtitle(option)
                                            fullscreenControlsEpoch += 1
                                        }
                                    )
                                }
                                if (subtitleOptions.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No subtitles") },
                                        onClick = {},
                                        enabled = false
                                    )
                                }
                            }
                        }
                    }

                    Box {
                        Surface(
                            modifier = Modifier.height(28.dp).clickable {
                                fullscreenSleepMenu = !fullscreenSleepMenu
                                fullscreenSpeedMenu = false
                                fullscreenQualityMenu = false
                                fullscreenSubtitleMenu = false
                                fullscreenControlsEpoch += 1
                            },
                            shape = RoundedCornerShape(50),
                            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
                        ) {
                            Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    sleepMinutes?.let { if (it == -1) "End" else it.toString() + "m" } ?: "Sleep",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        DropdownMenu(expanded = fullscreenSleepMenu, onDismissRequest = { fullscreenSleepMenu = false }) {
                            listOf<Int?>(null, -1, 15, 30, 60, 120).forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (sleepMinutes == option) "✓ " else "") +
                                                (when (option) { null -> "Tắt"; -1 -> "Hết bài hiện tại"; else -> option.toString() + " phút" })
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
                    onClick = { fullscreenRequested = false },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                LibrarySourceBadge(
                    mediaId = media.id,
                    channel = media.channel,
                    modifier = Modifier
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterEnd) {
                NetworkStatusIcon(networkType, 14.dp)
            }
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
                                setKeepContentOnPlayerReset(true)
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                        onClick = { fullscreenRequested = true },
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
            movieEpisodePosition?.let { episodePosition ->
                Spacer(Modifier.height(3.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { showQueue = true },
                        label = { Text("Episode $episodePosition") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.PlaylistPlay,
                                null,
                                Modifier.size(15.dp)
                            )
                        }
                    )
                    movieNextLabel?.let { nextLabel ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Next: $nextLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
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
                IconButton(onClick = onWatchLater) {
                    Icon(
                        if (isWatchLater) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (isWatchLater) "Remove from Watch later" else "Watch later"
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (isFavorite) "Remove favorite" else "Add favorite"
                    )
                }
                Column(
                    modifier = Modifier.width(58.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = { showAddToPlaylist = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.PlaylistAdd, "Add to playlist")
                    }
                    playlistMembershipLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.72f
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(54.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            if (isMovie) {
                val subtitleScrollState = rememberScrollState()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(subtitleScrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        modifier = Modifier.height(32.dp),
                        selected = selectedSubtitleLabel == null,
                        onClick = { onSubtitle(null) },
                        label = { Text("CC Off") },
                        leadingIcon = {
                            Icon(Icons.Outlined.ClosedCaptionOff, null, Modifier.size(MediaActionIconSize))
                        }
                    )
                    if (subtitleOptions.isEmpty()) {
                        AssistChip(
                            modifier = Modifier.height(32.dp),
                            onClick = {},
                            enabled = false,
                            label = { Text("No subtitles") },
                            leadingIcon = {
                                Icon(Icons.Outlined.SubtitlesOff, null, Modifier.size(MediaActionIconSize))
                            }
                        )
                    } else {
                        subtitleOptions.forEach { option ->
                            FilterChip(
                                modifier = Modifier.height(32.dp),
                                selected = selectedSubtitleLabel == option.label,
                                onClick = { onSubtitle(option) },
                                label = { Text(option.label, maxLines = 1) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.ClosedCaption, null, Modifier.size(MediaActionIconSize))
                                }
                            )
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        modifier = Modifier.height(32.dp),
                        selected = !videoMode,
                        onClick = { onVideoMode(false) },
                        label = { Text("AUDIO") },
                        leadingIcon = { Icon(Icons.Outlined.Headphones, null, Modifier.size(MediaActionIconSize)) }
                    )
                    FilterChip(
                        modifier = Modifier.height(32.dp),
                        selected = videoMode,
                        onClick = { onVideoMode(true) },
                        label = {
                            Text(
                                if (videoMode) {
                                    when {
                                        actualVideoHeight > 0 -> "${actualVideoHeight}p"
                                        videoQuality == VideoQuality.AUTO -> "Auto"
                                        else -> "${videoQuality.targetHeight ?: 0}p"
                                    }
                                } else {
                                    "VIDEO"
                                }
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.OndemandVideo, null, Modifier.size(MediaActionIconSize))
                        }
                    )
                }
            }

            if (!isMovie && !videoMode) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AudioQuality.entries.forEach { option ->
                        FilterChip(
                            modifier = Modifier.weight(1f).height(30.dp),
                            selected = quality == option,
                            onClick = { onQuality(option) },
                            label = {
                                Text(
                                    when (option) {
                                        AudioQuality.DATA_SAVER -> "Saver 64"
                                        AudioQuality.STANDARD -> "Std 128"
                                        AudioQuality.HIGH -> "High 160+"
                                    },
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }

            if (videoMode || isMovie) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VideoQuality.entries.forEach { option ->
                        CompactPresetButton(
                            text = when (option) {
                                VideoQuality.AUTO -> "Auto"
                                VideoQuality.P360 -> "360p"
                                VideoQuality.P480 -> "480p"
                                VideoQuality.P720 -> "720p"
                                VideoQuality.P1080 -> "1080p"
                            },
                            selected = videoQuality == option,
                            onClick = { onVideoQuality(option) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (!isMovie && !videoMode) {
                Text(
                    if (compatibilityFallback) "Compatibility stream · may use more data"
                    else "Audio-only · quality applies to next track",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (compatibilityFallback) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val developerDiagnostic = when {
                !developerMode -> null
                isMovie -> movieDiagnostic
                videoMode -> sabrDiagnostic
                else -> null
            }
            if (!developerDiagnostic.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            showDeveloperDiagnostics = !showDeveloperDiagnostics
                        },
                        label = {
                            Text(
                                if (showDeveloperDiagnostics) "Hide diagnostics"
                                else "Diagnostics"
                            )
                        }
                    )
                }
                if (showDeveloperDiagnostics) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            developerDiagnostic,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                listOf<Int?>(null, -1, 15, 30, 60, 120).forEach { minutes ->
                    CompactPresetButton(
                        text = when (minutes) {
                            null -> "Off"
                            -1 -> "End"
                            else -> "${minutes}m"
                        },
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
                    leadingIcon = { Icon(Icons.Outlined.SkipNext, null, Modifier.size(MediaActionIconSize)) },
                    label = {
                        Text("Auto next", modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                    },
                    modifier = Modifier.weight(1f).height(30.dp)
                )
                FilterChip(
                    selected = effectiveNextMode == NextMode.RECOMMENDED,
                    enabled = !isMovie,
                    onClick = {
                        if (!isMovie) {
                            onNextMode(
                                if (effectiveNextMode == NextMode.LIST) {
                                    NextMode.RECOMMENDED
                                } else {
                                    NextMode.LIST
                                }
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            if (effectiveNextMode == NextMode.LIST) {
                                Icons.Outlined.FormatListNumbered
                            } else {
                                Icons.Outlined.Shuffle
                            },
                            if (effectiveNextMode == NextMode.LIST) {
                                "Sequential next"
                            } else {
                                "Random next"
                            },
                            Modifier.size(MediaActionIconSize)
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
                            Modifier.size(MediaActionIconSize)
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
                    Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(MediaActionIconSize))
                    Spacer(Modifier.width(5.dp))
                    Text(if (showQueue) "Hide queue" else "Up next")
                }
                Spacer(Modifier.weight(1f))
                if (showQueue && queue.isNotEmpty()) {
                    IconButton(
                        onClick = onQueueRefresh,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            "Refresh queue",
                            Modifier.size(MediaActionIconSize)
                        )
                    }
                    TextButton(
                        onClick = onQueueClearUpcoming,
                        enabled = queueIndex >= 0 && queueIndex < queue.lastIndex,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "Clear upcoming",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
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
                        val visibleStart = queueIndex.coerceIn(0, queue.lastIndex.coerceAtLeast(0))
                        val visibleEndExclusive = (visibleStart + 1 + MAX_UP_NEXT_VISIBLE)
                            .coerceAtMost(queue.size)
                        val visibleEntries = queue
                            .subList(visibleStart, visibleEndExclusive)
                            .mapIndexed { offset, item -> (visibleStart + offset) to item }

                        visibleEntries.forEach { (index, item) ->
                            key(item.id) {
                            val current = index == queueIndex
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onQueueItem(index) }
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
                                    Icon(Icons.Outlined.GraphicEq, "Playing", Modifier.size(MediaActionIconSize),
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
                                                            .coerceIn(
                                                                visibleStart,
                                                                (visibleEndExclusive - 1).coerceAtLeast(visibleStart)
                                                            )
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
                                        Icon(Icons.Outlined.Close, "Remove from queue", Modifier.size(MediaActionIconSize))
                                    }
                                }
                            }
                            }
                        }

                        val hiddenUpcoming = (queue.size - visibleEndExclusive).coerceAtLeast(0)
                        if (hiddenUpcoming > 0) {
                            Text(
                                "+$hiddenUpcoming more in queue",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

    if (showAddToPlaylist) {
        AddToPlaylistDialog(
            media = media,
            playlists = personalPlaylists,
            onDismiss = { showAddToPlaylist = false },
            onSelect = { playlistId ->
                onAddToPlaylist(playlistId, media)
                showAddToPlaylist = false
            },
            onCreate = { name ->
                onCreatePlaylistWithMedia(name, media)
                showAddToPlaylist = false
            }
        )
    }
}

private const val MAX_UP_NEXT_VISIBLE = 12

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
