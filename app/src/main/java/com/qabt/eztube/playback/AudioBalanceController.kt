package com.qabt.eztube.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build

/**
 * Keeps ezTube playback volume more consistent without changing the device media volume.
 *
 * Android 9+ uses DynamicsProcessing (compressor + limiter). Older supported Android
 * versions fall back to a conservative LoudnessEnhancer attached to the player session.
 */
class AudioBalanceController {
    private var dynamics: DynamicsProcessing? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId: Int = 0
    private var mode: AudioBalanceMode = AudioBalanceMode.OFF

    fun apply(audioSessionId: Int, mode: AudioBalanceMode) {
        if (audioSessionId <= 0 || mode == AudioBalanceMode.OFF) {
            releaseEffects()
            sessionId = audioSessionId
            this.mode = mode
            return
        }

        if (
            sessionId == audioSessionId &&
            this.mode == mode &&
            (dynamics != null || loudness != null)
        ) {
            return
        }

        releaseEffects()
        sessionId = audioSessionId
        this.mode = mode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val strong = mode == AudioBalanceMode.STRONG
                val mbc = DynamicsProcessing.Mbc(true, true, 1).apply {
                    setBand(
                        0,
                        DynamicsProcessing.MbcBand(
                            true,
                            20_000f,
                            if (strong) 5f else 8f,
                            if (strong) 180f else 240f,
                            if (strong) 3.2f else 2.0f,
                            if (strong) -24f else -18f,
                            6f,
                            -80f,
                            1f,
                            if (strong) 4f else 2f,
                            0f
                        )
                    )
                }
                val limiter = DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    1f,
                    80f,
                    if (strong) 20f else 10f,
                    -1f,
                    0f
                )
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    2,
                    false,
                    0,
                    true,
                    1,
                    false,
                    0,
                    true
                )
                    .setMbcAllChannelsTo(mbc)
                    .setLimiterAllChannelsTo(limiter)
                    .build()

                DynamicsProcessing(0, audioSessionId, config).also {
                    it.enabled = true
                    dynamics = it
                }
            }.onFailure {
                attachLoudnessFallback(audioSessionId, mode)
            }
        } else {
            attachLoudnessFallback(audioSessionId, mode)
        }
    }

    fun reapply(audioSessionId: Int) {
        val currentMode = mode
        releaseEffects()
        sessionId = 0
        apply(audioSessionId, currentMode)
    }

    private fun attachLoudnessFallback(audioSessionId: Int, mode: AudioBalanceMode) {
        runCatching {
            LoudnessEnhancer(audioSessionId).also {
                // Conservative fallback: the newer DynamicsProcessing path performs
                // the real compression/limiting. Here we only reduce large track-to-track gaps.
                it.setTargetGain(if (mode == AudioBalanceMode.STRONG) 400 else 200)
                it.enabled = true
                loudness = it
            }
        }
    }

    fun release() {
        releaseEffects()
        sessionId = 0
        mode = AudioBalanceMode.OFF
    }

    private fun releaseEffects() {
        runCatching { dynamics?.enabled = false }
        runCatching { dynamics?.release() }
        dynamics = null
        runCatching { loudness?.enabled = false }
        runCatching { loudness?.release() }
        loudness = null
    }
}
