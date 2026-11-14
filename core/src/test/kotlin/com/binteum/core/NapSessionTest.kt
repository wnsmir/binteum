package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class NapSessionTest {
    @Test fun `simple sleep uses every remaining second with no hidden buffer`() {
        assertEquals(1200, NapSession.forRemainingTime(1200, 0)!!.plannedDurationSeconds)
        assertEquals(1, NapSession.forRemainingTime(1, 0)!!.plannedDurationSeconds)
        val sleep = NapSession.forRemainingTime(60, 0)!!
        assertEquals(1, sleep.tick(59000, 1).remainingSeconds)
        assertTrue(sleep.tick(60000, 0).shouldWake)
        assertFalse(sleep.tick(61000, 0).shouldWake)
    }

    @Test fun `simple sleep needs a known positive remaining time`() {
        assertNull(NapSession.forRemainingTime(null, 0))
        assertNull(NapSession.forRemainingTime(0, 0))
        assertNull(NapSession.forRemainingTime(-1, 0))
        assertNull(NapSession.forRemainingTime(60, -1))
    }

    @Test fun `simple sleep shortens to the current budget but never extends itself`() {
        val sleep = NapSession.forRemainingTime(1200, 0)!!
        assertEquals(200, sleep.tick(30000, 200).remainingSeconds)
        assertEquals(170, sleep.tick(60000, 1800).remainingSeconds)
        assertTrue(sleep.cancel(65000).finished)
        assertFalse(sleep.tick(230000, 0).shouldWake)
    }

    @Test fun `sleep uses full remaining timer instead of a separate duration choice`() {
        assertEquals(1020, NapSession.forWindow(1200, 0, 180)!!.plannedDurationSeconds)
        assertEquals(3420, NapSession.forWindow(3600, 0, 180)!!.plannedDurationSeconds)
        assertEquals(30, NapSession.forWindow(210, 0, 180)!!.plannedDurationSeconds)
    }

    @Test fun `sleep is unavailable without a usable timer window`() {
        assertNull(NapSession.forWindow(null, 0, 180))
        assertNull(NapSession.forWindow(180, 0, 180))
        assertNull(NapSession.forWindow(0, 0, 180))
        assertNull(NapSession.forWindow(-1, 0, 180))
    }

    @Test fun `timer sleep follows a shorter budget and never extends on a later estimate`() {
        val sleep = NapSession.forWindow(1200, 0, 180)!!
        assertEquals(960, sleep.tick(60000, 1140).remainingSeconds)
        assertEquals(120, sleep.tick(90000, 300).remainingSeconds)
        assertEquals(90, sleep.tick(120000, 2400).remainingSeconds)
        assertTrue(sleep.tick(210000, 2310).shouldWake)
    }

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

    @Test fun `chosen five minute wake reserve shortens nap and survives changing charge estimates`() {
        val nap = NapSession.start(1200, 900, 0, wakeReserveSeconds = 300)!!
        assertEquals(600, nap.plannedDurationSeconds)
        assertEquals(60, nap.tick(60000, 360).remainingSeconds)
        assertEquals(30, nap.tick(90000, 900).remainingSeconds)
        assertEquals(NapFinishReason.CHARGE_WINDOW_ENDING, nap.tick(120000, 900).finishReason)
        assertNull(NapSession.start(600, 300, 0, wakeReserveSeconds = 300))
    }
}
