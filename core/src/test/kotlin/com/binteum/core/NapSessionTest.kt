package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class NapSessionTest {
    @Test fun `requested nap is capped by charge window and reserves time to wake`() {
        val nap = NapSession.start(1200, 900, 1000)!!
        assertEquals(780, nap.plannedDurationSeconds)
        val end = nap.tick(781000, 120)
        assertTrue(end.finished)
        assertTrue(end.shouldWake)
        assertEquals(NapFinishReason.CHARGE_WINDOW_ENDING, end.finishReason)
    }

    @Test fun `short user choice completes at chosen duration`() {
        val nap = NapSession.start(600, 1800, 1000)!!
        assertEquals(1, nap.tick(600999, 1200).remainingSeconds)
        assertFalse(nap.tick(600999, 1200).finished)
        val end = nap.tick(601000, 1200)
        assertTrue(end.finished)
        assertEquals(NapFinishReason.REQUESTED_DURATION_COMPLETE, end.finishReason)
        assertFalse(nap.tick(602000, 1199).shouldWake)
    }

    @Test fun `a shrinking charge estimate advances wake time and later growth cannot extend it`() {
        val nap = NapSession.start(1200, 1800, 0)!!
        assertEquals(180, nap.tick(60000, 300).remainingSeconds)
        assertEquals(120, nap.tick(120000, 1800).remainingSeconds)
        val end = nap.tick(240000, 1680)
        assertTrue(end.finished)
        assertEquals(NapFinishReason.CHARGE_WINDOW_ENDING, end.finishReason)
    }

    @Test fun `charge target reached wakes an ongoing nap immediately`() {
        val nap = NapSession.start(600, 1800, 1000)!!
        val end = nap.tick(2000, 0)
        assertTrue(end.finished)
        assertEquals(NapFinishReason.CHARGE_WINDOW_ENDING, end.finishReason)
        assertEquals(1, end.elapsedSeconds)
    }

    @Test fun `unknown budget cannot start a charge bounded nap`() {
        assertNull(NapSession.start(600, null, 0))
        assertNull(NapSession.start(600, -1, 0))
        assertNull(NapSession.start(600, 120, 0))
        assertNull(NapSession.start(0, 1800, 0))
        assertNull(NapSession.start(600, 1800, -1))
    }

    @Test fun `loss of estimate ends active nap rather than continuing a stale countdown`() {
        val nap = NapSession.start(600, 1800, 0)!!
        val end = nap.tick(60000, null)
        assertEquals(NapFinishReason.ESTIMATE_UNAVAILABLE, end.finishReason)
        assertTrue(end.shouldWake)
        // A returning estimate does not silently restart the stopped nap.
        assertTrue(nap.tick(61000, 1800).finished)
        assertFalse(nap.tick(62000, 1800).shouldWake)
    }

    @Test fun `elapsed clock reset ends session and manual cancellation emits no wake cue`() {
        val reset = NapSession.start(600, 1800, 5000)!!
        reset.tick(6000, 1799)
        val end = reset.tick(1000, 1800)
        assertTrue(end.finished)
        assertEquals(NapFinishReason.CLOCK_RESET, end.finishReason)
        assertEquals(1, end.elapsedSeconds)
        val cancelled = NapSession.start(600, 1800, 0)!!.cancel(60000)
        assertEquals(NapFinishReason.CANCELLED, cancelled.finishReason)
        assertFalse(cancelled.shouldWake)
        assertEquals(60, cancelled.elapsedSeconds)
    }

    @Test fun `wake reserve cannot be removed and huge durations cannot overflow`() {
        assertEquals(60, NapSession.start(600, 180, 0, wakeReserveSeconds = 0)!!.plannedDurationSeconds)
        val huge = NapSession.start(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE - 1000)!!
        assertEquals(1, huge.tick(Long.MAX_VALUE - 999, Int.MAX_VALUE).remainingSeconds)
        assertTrue(huge.tick(Long.MAX_VALUE, Int.MAX_VALUE).finished)
    }
}
