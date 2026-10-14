package com.qabt.eztube.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRuntimePolicyTest {
    @Test fun autoplayPreloadsWhenNoSleepEndIsArmed() {
        assertTrue(
            PlaybackRuntimePolicy.allowPreload(
                autoplay = true,
                sleepEndCurrent = false
            )
        )
    }

    @Test fun sleepEndBlocksPreloadEvenWhenAutoplayIsEnabled() {
        assertFalse(
            PlaybackRuntimePolicy.allowPreload(
                autoplay = true,
                sleepEndCurrent = true
            )
        )
    }

    @Test fun autoplayOffBlocksPreload() {
        assertFalse(
            PlaybackRuntimePolicy.allowPreload(
                autoplay = false,
                sleepEndCurrent = false
            )
        )
    }

    @Test fun repeatOneUsesNativeRepeatOnlyWhenSleepEndIsOff() {
        assertTrue(
            PlaybackRuntimePolicy.nativeRepeatOneEnabled(
                repeatMode = 1,
                sleepEndCurrent = false
            )
        )
        assertFalse(
            PlaybackRuntimePolicy.nativeRepeatOneEnabled(
                repeatMode = 1,
                sleepEndCurrent = true
            )
        )
    }

    @Test fun repeatOffAndRepeatAllNeverUseNativeRepeatOne() {
        assertFalse(
            PlaybackRuntimePolicy.nativeRepeatOneEnabled(
                repeatMode = 0,
                sleepEndCurrent = false
            )
        )
        assertFalse(
            PlaybackRuntimePolicy.nativeRepeatOneEnabled(
                repeatMode = 2,
                sleepEndCurrent = false
            )
        )
    }
}
