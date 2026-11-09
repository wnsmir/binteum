package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class ReminderModeTest {
    private val now = 100_000L
    private fun snapshot(soc: Int = 90, charging: Boolean = true, ux: Boolean? = false, at: Long = now) =
        VehicleSnapshot(soc, charging, ux, true, at)

    @Test fun `time mode does not finish at the unused battery target`() {
        val session = ChargingSession(80, targetEnabled = false)
        assertEquals(SessionStatus.CHARGING, session.update(snapshot(), now).status)
        assertFalse(session.update(snapshot(), now).notifyTargetReached)
    }

    @Test fun `time mode preserves actual charging and safety restrictions`() {
        val session = ChargingSession(80, targetEnabled = false)
        assertEquals(SessionStatus.INTERRUPTED, session.update(snapshot(charging = false), now).status)
        assertEquals(SessionStatus.DRIVING_LOCKED, session.update(snapshot(ux = true), now).status)
        assertEquals(SessionStatus.WAITING_FOR_DATA, session.update(snapshot(ux = null), now).status)
        assertEquals(SessionStatus.WAITING_FOR_DATA, session.update(snapshot(at = 0), now).status)
    }

    @Test fun `target mode notifies once at or beyond target`() {
        val session = ChargingSession(80)
        assertEquals(SessionStatus.CHARGING, session.update(snapshot(soc = 79), now).status)
        assertTrue(session.update(snapshot(soc = 81), now).notifyTargetReached)
        assertFalse(session.update(snapshot(soc = 81), now).notifyTargetReached)
    }

    @Test fun `video fit respects exact duration plus reserve without overflow`() {
        assertTrue(VideoCatalog.recommend(304).isEmpty())
        assertEquals(listOf("PVGeM40dABA"), VideoCatalog.recommend(305).map { it.id })
        assertEquals(listOf("PVGeM40dABA"), VideoCatalog.recommend(754).map { it.id })
        assertEquals("aqz-KE-bpKQ", VideoCatalog.recommend(755).first().id)
        assertEquals(2, VideoCatalog.recommend(Int.MAX_VALUE).size)
        assertTrue(VideoCatalog.recommend(-1).isEmpty())
        assertEquals(VideoCatalog.all, VideoCatalog.recommend(null))
    }
}
