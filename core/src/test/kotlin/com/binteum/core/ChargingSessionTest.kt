package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class ChargingSessionTest {
    private val now = 100_000L
    private fun sample(soc: Int? = 79, charging: Boolean? = true, restricted: Boolean? = false,
                       awake: Boolean = true, observed: Long = now) =
        VehicleSnapshot(soc, charging, restricted, awake, observed)

    @Test fun `target crossing emits once even if charging stops at target`() {
        val session = ChargingSession(80)
        assertEquals(SessionStatus.CHARGING, session.update(sample(), now).status)
        assertTrue(session.update(sample(80, false), now).notifyTargetReached)
        assertFalse(session.update(sample(81), now).notifyTargetReached)
        assertEquals(SessionStatus.TARGET_REACHED, session.update(sample(79), now).status)
    }

    @Test fun `charging switch off below target means interruption`() {
        val result = ChargingSession(80).update(sample(60, false), now)
        assertEquals(SessionStatus.INTERRUPTED, result.status)
        assertFalse(result.notifyTargetReached)
    }

    @Test fun `stale missing invalid and future data never trigger completion`() {
        val session = ChargingSession(80)
        listOf(sample(80, observed = now - 30_001), sample(80, observed = now + 1),
            sample(null), sample(101), sample(80, charging = null), sample(80, restricted = null))
            .forEach {
                val result = session.update(it, now)
                assertEquals(SessionStatus.WAITING_FOR_DATA, result.status)
                assertFalse(result.notifyTargetReached)
            }
    }

    @Test fun `driving and suspend suppress notifications until fresh safe observation`() {
        val session = ChargingSession(80)
        assertEquals(SessionStatus.DRIVING_LOCKED, session.update(sample(80, restricted = true), now).status)
        assertEquals(SessionStatus.SUSPENDED, session.update(sample(80, awake = false), now).status)
        assertTrue(session.update(sample(80), now).notifyTargetReached)
    }

    @Test fun `time budget reserves return walk and buffer`() {
        assertEquals(9, TimeBudget.availableMinutes(20, 3, 5))
        assertFalse(TimeBudget.fits(ActivityOption("산책", 10, 3), 20, reserveMinutes = 5))
        assertTrue(TimeBudget.fits(ActivityOption("가벼운 휴식", 5), 20))
        assertEquals(0, TimeBudget.availableMinutes(5, 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid target rejected`() { ChargingSession(101) }

    @Test(expected = IllegalArgumentException::class)
    fun `negative walking time rejected`() { TimeBudget.availableMinutes(20, -1) }

    @Test fun `cached demo snapshot does not become fresh on restart`() {
        var clock = now
        val source = DemoVehicleDataSource { clock }
        var observed = 0L
        source.start { observed = it.observedAtMillis }
        source.stop()
        clock += 60_000
        source.start { assertEquals(observed, it.observedAtMillis) }
    }

    @Test fun `restored session does not repeat completion notification`() {
        val original = ChargingSession(80)
        original.update(sample(80), now)
        val restored = ChargingSession(80, checkpoint = original.checkpoint())
        val result = restored.update(sample(79), now)
        assertEquals(SessionStatus.TARGET_REACHED, result.status)
        assertFalse(result.notifyTargetReached)
    }
}
