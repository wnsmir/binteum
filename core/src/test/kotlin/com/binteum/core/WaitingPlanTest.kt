package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class WaitingPlanTest {
    @Test fun durationDoesNotCompleteEarlyAndSurvivesBackgroundTime() {
        val plan = WaitingPlan(5000, 60, 65)
        assertEquals(1, plan.remaining(64999, 0))
        assertEquals(0, plan.remaining(65000, null))
        assertEquals(0, plan.remaining(100000, 900))
        assertEquals(60, plan.remaining(4000, null))
    }
    @Test fun recommendationsRespectTheShorterBudget() {
        val plan = WaitingPlan(0, 1200, 65)
        assertEquals(600, plan.budget(600, 0))
        assertEquals(1200, plan.budget(1800, 0))
        assertEquals(1100, plan.budget(null, 100000))
        assertEquals(0, plan.budget(0, 0))
    }
    @Test fun targetUsesLiveEstimateAndNeverInventsOne() {
        val plan = WaitingPlan(0, null, 65)
        assertNull(plan.remaining(10000, null))
        assertEquals(700, plan.remaining(10000, 700))
        assertEquals(900, plan.remaining(20000, 900))
        assertEquals(.5f, plan.progress(20000, 75, 85), .001f)
        assertEquals(1f, plan.progress(20000, 85, 85), .001f)
    }

    @Test fun sameProcessActivityRecreationKeepsTheOriginalDeadline() {
        val restored = WaitingPlan.restore("process-a", "process-a", 5000, 60, 65, 35000)
        assertNotNull(restored)
        assertEquals(30, restored!!.remaining(35000, null))
        assertEquals(0, restored.remaining(65000, null))
        val chargePlan = WaitingPlan.restore("process-a", "process-a", 5000, null, 65, 35000)
        assertNull(chargePlan!!.remaining(35000, null))
        assertEquals(900, chargePlan.remaining(35000, 900))
    }

    @Test fun newProcessAndLegacySavedStateCannotRestoreAnActivePlan() {
        assertNull(WaitingPlan.restore("process-a", "process-b", 5000, 60, 65, 35000))
        assertNull(WaitingPlan.restore(null, "process-b", 5000, 60, 65, 35000))
        assertNull(WaitingPlan.restore("", "", 5000, 60, 65, 35000))
    }

    @Test fun resetClockOrInvalidSavedValuesCannotRenewTheWait() {
        assertNull(WaitingPlan.restore("process-a", "process-a", 5000, 60, 65, 4000))
        assertNull(WaitingPlan.restore("process-a", "process-a", -1, 60, 65, 35000))
        assertNull(WaitingPlan.restore("process-a", "process-a", 5000, 0, 65, 35000))
        assertNull(WaitingPlan.restore("process-a", "process-a", 5000, 60, 101, 35000))
    }
}
