package com.qabt.eztube.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueNavigationPolicyTest {
    @Test fun autoplayOffNeverAdvancesAtEnd() {
        assertNull(QueueNavigationPolicy.endedTarget(0, 3, false, 0))
        assertNull(QueueNavigationPolicy.endedTarget(1, 3, false, 2))
    }

    @Test fun autoplayAdvancesAndStopsAtLastWhenRepeatOff() {
        assertEquals(1, QueueNavigationPolicy.endedTarget(0, 3, true, 0))
        assertEquals(2, QueueNavigationPolicy.endedTarget(1, 3, true, 0))
        assertNull(QueueNavigationPolicy.endedTarget(2, 3, true, 0))
    }

    @Test fun repeatAllWrapsNaturalEnd() {
        assertEquals(0, QueueNavigationPolicy.endedTarget(2, 3, true, 2))
    }

    @Test fun repeatOneIsNativeAndDoesNotLogicalWrap() {
        assertNull(QueueNavigationPolicy.endedTarget(2, 3, true, 1))
    }

    @Test fun manualTransportMovesWithAutoplayIndependentPolicy() {
        assertEquals(2, QueueNavigationPolicy.manualTarget(1, 4, 1, 0))
        assertEquals(0, QueueNavigationPolicy.manualTarget(1, 4, -1, 0))
    }

    @Test fun manualRepeatAllWrapsBothDirections() {
        assertEquals(0, QueueNavigationPolicy.manualTarget(3, 4, 1, 2))
        assertEquals(3, QueueNavigationPolicy.manualTarget(0, 4, -1, 2))
    }

    @Test fun manualTransportStopsAtEdgesWithoutRepeatAll() {
        assertNull(QueueNavigationPolicy.manualTarget(3, 4, 1, 0))
        assertNull(QueueNavigationPolicy.manualTarget(0, 4, -1, 0))
    }
}
