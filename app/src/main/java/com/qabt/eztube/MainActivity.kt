package com.qabt.eztube

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.qabt.eztube.playback.PlaybackPreferences
import com.qabt.eztube.playback.PlaybackService
import com.qabt.eztube.ui.EzTubeApp
import com.qabt.eztube.youtube.MediaSummary

class MainActivity : ComponentActivity() {
    private val externalMedia = mutableStateOf<MediaSummary?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val pipMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalMedia.value = mediaFromIntent(intent)
        connectController()
        setContent {
            EzTubeApp(
                externalMedia = externalMedia.value,
                onExternalMediaConsumed = { externalMedia.value = null },
                isInPictureInPictureMode = pipMode.value
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalMedia.value = mediaFromIntent(intent)
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }.onSuccess { controller ->
                mediaController = controller
                controller.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            updatePipParams(
                                autoEnter = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    isInPictureInPictureMode.not()
                            )
                        }
                    }
                })
            }
        }, mainExecutor)
    }

    private fun mediaFromIntent(intent: Intent?): MediaSummary? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val mime = intent.type.orEmpty()
        if (!mime.startsWith("audio/") && !mime.startsWith("video/")) return null

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                intent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
        }

        var title = uri.lastPathSegment?.substringAfterLast('/') ?: "Local media"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) title = cursor.getString(index) ?: title
                }
            }
        }

        return MediaSummary(
            id = uri.toString(),
            title = title,
            channel = if (mime.startsWith("video/")) "Local video" else "Local audio",
            thumbnailUrl = null
        )
    }

    private fun canEnterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!PlaybackPreferences(this).loadVideoMode()) return false
        val controller = mediaController ?: return false
        return controller.currentMediaItem != null &&
            controller.playbackState != Player.STATE_IDLE &&
            controller.playbackState != Player.STATE_ENDED &&
            controller.playWhenReady
    }

    private fun currentPipAspectRatio(): Rational {
        val size = mediaController?.videoSize
        if (size != null && size.width > 0 && size.height > 0) {
            val correctedWidth = (size.width * size.pixelWidthHeightRatio)
                .roundToInt()
                .coerceAtLeast(1)
            val ratio = correctedWidth.toFloat() / size.height.toFloat()
            // Android PiP only accepts aspect ratios in the platform-supported range.
            if (ratio in (1f / 2.39f)..2.39f) {
                return Rational(correctedWidth, size.height)
            }
        }
        return Rational(16, 9)
    }

    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(currentPipAspectRatio())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams(autoEnter: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        setPictureInPictureParams(buildPipParams(autoEnter))
    }

    override fun onUserLeaveHint() {
        if (canEnterPip()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                updatePipParams(true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = buildPipParams(autoEnter = false)
                setPictureInPictureParams(params)
                enterPictureInPictureMode(params)
            }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updatePipParams(false)
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
        super.onDestroy()
    }
}
