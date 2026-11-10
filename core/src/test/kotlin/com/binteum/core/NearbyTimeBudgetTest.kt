package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class NearbyTimeBudgetTest {
    @Test fun `both walking legs plus stay and five minute reserve must fit`() {
        val exact = NearbyTimeBudget.assess(1200, 240, 300, 360, true)
        assertTrue(exact.fits)
        assertEquals(1200L, exact.requiredSeconds)
        assertEquals(0L, exact.spareSeconds)
        val short = NearbyTimeBudget.assess(1199, 240, 300, 360, true)
        assertEquals(NearbyBudgetStatus.NOT_ENOUGH_TIME, short.status)
        assertEquals(-1L, short.spareSeconds)
    }

    @Test fun `a long return route is not substituted with the outbound time`() {
        assertFalse(NearbyTimeBudget.assess(1200, 120, 900, 120, true).fits)
    }

    @Test fun `unknown and unconfirmed data never claims a fitting trip`() {
        assertEquals(NearbyBudgetStatus.UNKNOWN_WINDOW, NearbyTimeBudget.assess(null, 60, 60, 0, true).status)
        assertEquals(NearbyBudgetStatus.UNKNOWN_WINDOW, NearbyTimeBudget.assess(-1, 60, 60, 0, true).status)
        assertEquals(NearbyBudgetStatus.UNCONFIRMED_ROUTE, NearbyTimeBudget.assess(3600, 60, 60, 0, false).status)
    }

    @Test fun `reserve cannot be reduced below five minutes`() {
        val result = NearbyTimeBudget.assess(600, 120, 120, 120, true, returnReserveSeconds = 0)
        assertEquals(300, result.returnReserveSeconds)
        assertFalse(result.fits)
        assertEquals(660L, result.requiredSeconds)
    }

    @Test fun `invalid durations and overflow cannot produce fitting plans`() {
        for (legs in listOf(0 to 60, 60 to -1, -1 to 60)) {
            assertEquals(NearbyBudgetStatus.INVALID_PLAN, NearbyTimeBudget.assess(3600, legs.first, legs.second, 0, true).status)
        }
        assertEquals(NearbyBudgetStatus.INVALID_PLAN, NearbyTimeBudget.assess(3600, 60, 60, -1, true).status)
        val giant = NearbyTimeBudget.assess(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, true)
        assertFalse(giant.fits)
        assertTrue(giant.requiredSeconds!! > Int.MAX_VALUE)
    }
}
