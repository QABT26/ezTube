package com.qabt.eztube.playback

import com.qabt.eztube.youtube.MediaSummary

internal object QueueLimitPolicy {
    const val MAX_ITEMS = 30
    private const val PREVIOUS_CONTEXT = 5

    /**
     * Keep the logical queue bounded while preserving the selected item and useful
     * context on both sides. Ordered queues keep up to five previous items and fill
     * the remaining slots with upcoming items.
     */
    fun bound(
        items: List<MediaSummary>,
        currentId: String,
        maxItems: Int = MAX_ITEMS
    ): List<MediaSummary> {
        if (items.isEmpty() || maxItems <= 0) return emptyList()

        val unique = items.distinctBy { it.id }
        if (unique.size <= maxItems) return unique

        val current = unique.indexOfFirst { it.id == currentId }
        if (current < 0) return unique.take(maxItems)

        val previous = minOf(PREVIOUS_CONTEXT, current, maxItems - 1)
        var start = current - previous
        var end = minOf(unique.size, start + maxItems)

        // Near the end, shift backward so the bounded queue still uses all slots.
        if (end - start < maxItems) {
            start = (end - maxItems).coerceAtLeast(0)
        }

        return unique.subList(start, end)
    }
}
