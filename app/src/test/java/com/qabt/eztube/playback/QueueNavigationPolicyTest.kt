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

    @Test fun coalescedRapidNextTapsCanAdvanceMultipleItems() {
        assertEquals(3, QueueNavigationPolicy.manualTarget(1, 5, 2, 0))
        assertEquals(4, QueueNavigationPolicy.manualTarget(1, 5, 3, 0))
    }

    @Test fun coalescedRapidPreviousTapsCanMoveBackMultipleItems() {
        assertEquals(1, QueueNavigationPolicy.manualTarget(3, 5, -2, 0))
        assertEquals(0, QueueNavigationPolicy.manualTarget(3, 5, -3, 0))
    }

    @Test fun coalescedDeltaStopsAtEdgesWithoutRepeatAll() {
        assertEquals(4, QueueNavigationPolicy.manualTarget(1, 5, 10, 0))
        assertEquals(0, QueueNavigationPolicy.manualTarget(3, 5, -10, 0))
        assertNull(QueueNavigationPolicy.manualTarget(4, 5, 3, 0))
        assertNull(QueueNavigationPolicy.manualTarget(0, 5, -3, 0))
    }

    @Test fun coalescedDeltaWrapsByEveryTapWithRepeatAll() {
        assertEquals(1, QueueNavigationPolicy.manualTarget(3, 4, 2, 2))
        assertEquals(2, QueueNavigationPolicy.manualTarget(0, 4, -2, 2))
        assertEquals(3, QueueNavigationPolicy.manualTarget(1, 4, 10, 2))
    }

    @Test fun invalidCurrentAndEmptyQueueNeverNavigate() {
        assertNull(QueueNavigationPolicy.manualTarget(-1, 5, 1, 2))
        assertNull(QueueNavigationPolicy.manualTarget(0, 0, 1, 2))
        assertNull(QueueNavigationPolicy.endedTarget(-1, 5, true, 2))
        assertNull(QueueNavigationPolicy.endedTarget(0, 0, true, 2))
    }
}
