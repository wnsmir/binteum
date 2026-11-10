package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class MediaSelectionQueueTest {
    private val first = "PVGeM40dABA"
    private val second = "aqz-KE-bpKQ"

    @Test fun readinessNeverCreatesAnAutomaticSelection() {
        val queue = MediaSelectionQueue()
        queue.markDocumentReady()
        assertNull(queue.claim())
    }

    @Test fun tapsBeforeDocumentReadyKeepOnlyTheLatestSelection() {
        val queue = MediaSelectionQueue()
        queue.select(first)
        queue.select(second)
        assertNull(queue.claim())
        queue.markDocumentReady()
        val request = queue.claim()!!
        assertEquals(second, request.videoId)
        assertFalse(queue.acknowledge(request, true))
        assertNull(queue.claim())
    }

    @Test fun callbackForAnEarlierTapDoesNotConsumeANewerTapOfTheSameVideo() {
        val queue = MediaSelectionQueue()
        queue.markDocumentReady()
        queue.select(first)
        val earlier = queue.claim()!!
        queue.select(first)
        assertNull(queue.claim())
        assertTrue(queue.acknowledge(earlier, true))
        val later = queue.claim()!!
        assertEquals(first, later.videoId)
        assertNotEquals(earlier, later)
        assertFalse(queue.acknowledge(later, true))
        assertNull(queue.claim())
    }

    @Test fun missingHtmlFunctionRetainsTheRequestForALaterPoll() {
        val queue = MediaSelectionQueue()
        queue.markDocumentReady()
        queue.select(first)
        val request = queue.claim()!!
        assertFalse(queue.acknowledge(request, false))
        assertEquals(request, queue.claim())
        assertFalse(queue.acknowledge(request, true))
        assertNull(queue.claim())
    }

    @Test fun destroyingTheSurfaceCancelsQueuedPlaybackAndRejectsLateCallbacks() {
        val queue = MediaSelectionQueue()
        queue.markDocumentReady()
        queue.select(first)
        val old = queue.claim()!!
        queue.reset()
        queue.markDocumentReady()
        assertNull(queue.claim())
        queue.select(second)
        val current = queue.claim()!!
        assertFalse(queue.acknowledge(old, true))
        assertNull(queue.claim())
        assertFalse(queue.acknowledge(current, true))
        assertNull(queue.claim())
    }
}
