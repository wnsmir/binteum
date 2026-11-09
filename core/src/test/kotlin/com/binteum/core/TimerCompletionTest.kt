package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class TimerCompletionTest {
    @Test fun `rounded zero must not complete before the deadline`() {
        assertFalse(TimerCompletion.isDue(60_999, 61_000, 1_000, 0, false))
        assertTrue(TimerCompletion.isDue(61_000, 61_000, 1_000, 0, false))
    }
    @Test fun `completion is not repeated and ignores inactive or target sessions`() {
        assertFalse(TimerCompletion.isDue(62_000, 61_000, 1_000, 61_000, false))
        assertFalse(TimerCompletion.isDue(62_000, 61_000, 0, 0, false))
        assertFalse(TimerCompletion.isDue(62_000, 61_000, 1_000, 0, true))
    }
    @Test fun `old or background completions are not announced on return`() {
        assertFalse(TimerCompletion.canAnnounce(61_000, 61_000, false))
        assertFalse(TimerCompletion.canAnnounce(63_001, 61_000, true))
        assertFalse(TimerCompletion.canAnnounce(60_999, 61_000, true))
        assertTrue(TimerCompletion.canAnnounce(61_500, 61_000, true))
    }
}
