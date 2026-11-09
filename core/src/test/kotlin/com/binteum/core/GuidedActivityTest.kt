package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class GuidedActivityTest {
    @Test fun `guided activities require their duration plus the full reserve`() {
        ActivityCatalog.guided.forEach {
            assertFalse(ActivityCatalog.canStart(it.id, 299, SessionStatus.CHARGING))
            assertTrue(ActivityCatalog.canStart(it.id, 300, SessionStatus.CHARGING))
            assertFalse(ActivityCatalog.canStart(it.id, -1, SessionStatus.CHARGING))
        }
        assertFalse(ActivityCatalog.canStart("missing", Int.MAX_VALUE, SessionStatus.CHARGING))
    }

    @Test fun `direct guided selection cannot bypass non charging safety states`() {
        SessionStatus.values().filter { it != SessionStatus.CHARGING }.forEach { status ->
            ActivityCatalog.guided.forEach {
                assertFalse("${it.id} leaked in $status", ActivityCatalog.canStart(it.id, Int.MAX_VALUE, status))
            }
        }
    }

    @Test fun `guided completion supports checkpoint lookup and deduplicated feedback`() {
        ActivityCatalog.guided.forEach { activity ->
            assertEquals(activity, ActivityCatalog.find(activity.id))
            val event = OwnActivityFeedback("event", "attempt", activity.id, ActivityRating.LIKE, true)
            val first = ActivityFeedbackReducer.apply(ActivityProfile(), event)
            assertTrue(first.applied)
            val repeated = ActivityFeedbackReducer.apply(first.profile, event)
            assertFalse(repeated.applied)
            assertEquals(1, repeated.profile.feedback[activity.id]?.likes)
        }
    }
}
