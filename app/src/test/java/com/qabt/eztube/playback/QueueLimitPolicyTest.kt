package com.qabt.eztube.playback

import com.qabt.eztube.youtube.MediaSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLimitPolicyTest {
    private fun item(index: Int) = MediaSummary(
        id = "id:$index",
        title = "Item $index",
        channel = "Test",
        thumbnailUrl = null
    )

    @Test fun shortQueueIsPreserved() {
        val items = (0 until 10).map(::item)
        assertEquals(items, QueueLimitPolicy.bound(items, "id:4"))
    }

    @Test fun hugeQueueIsBoundedAndKeepsCurrent() {
        val items = (0 until 677).map(::item)
        val bounded = QueueLimitPolicy.bound(items, "id:320")

        assertEquals(QueueLimitPolicy.MAX_ITEMS, bounded.size)
        assertTrue(bounded.any { it.id == "id:320" })
        assertEquals("id:315", bounded.first().id)
        assertEquals("id:344", bounded.last().id)
    }

    @Test fun queueNearStartKeepsUpcomingCapacity() {
        val items = (0 until 100).map(::item)
        val bounded = QueueLimitPolicy.bound(items, "id:2")

        assertEquals(30, bounded.size)
        assertEquals("id:0", bounded.first().id)
        assertEquals("id:29", bounded.last().id)
    }

    @Test fun queueNearEndShiftsBackward() {
        val items = (0 until 100).map(::item)
        val bounded = QueueLimitPolicy.bound(items, "id:98")

        assertEquals(30, bounded.size)
        assertEquals("id:70", bounded.first().id)
        assertEquals("id:99", bounded.last().id)
        assertTrue(bounded.any { it.id == "id:98" })
    }
}
