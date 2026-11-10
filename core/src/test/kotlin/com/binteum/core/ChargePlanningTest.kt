package com.binteum.core

import org.junit.Assert.*
import org.junit.Test

class ChargePlanningTest {
    private val start = 1_000_000L
    private fun sample(at: Long = start, soc: Int = 60, charging: Boolean? = true,
                       eta: Int? = null, vehicleTarget: Int? = null, etaAt: Long? = at) =
        VehicleSnapshot(soc, charging, false, true, at, eta, vehicleTarget, etaAt)

    @Test fun `OEM estimate is target specific and decreases with its original timestamp`() {
        val planner = ChargeTimePlanner()
        val value = sample(eta = 1200, vehicleTarget = 80)
        val estimate = planner.estimate(value, 80, start + 15_000)
        assertEquals(1185, estimate.seconds)
        assertEquals(ChargeTimeSource.VEHICLE, estimate.source)
        assertNull(planner.estimate(value, 90, start + 15_000).seconds)
        assertNull(planner.estimate(value.copy(vehicleTargetPercent = null), 80, start).seconds)
    }

    @Test fun `unsupported zero stale and disconnected OEM estimates are unknown`() {
        val planner = ChargeTimePlanner()
        assertNull(planner.estimate(sample(eta = 0, vehicleTarget = 80), 80, start).seconds)
        assertNull(planner.estimate(sample(eta = 1200, vehicleTarget = 80, etaAt = start - 91_000), 80, start).seconds)
        assertNull(planner.estimate(sample(eta = 1200, vehicleTarget = 80).copy(observedAtMillis = start - 31_000), 80, start).seconds)
        assertNull(planner.estimate(sample(eta = 1200, vehicleTarget = 80, charging = false), 80, start).seconds)
        assertNull(planner.estimate(sample(eta = 1200, vehicleTarget = 80).copy(uxRestricted = null), 80, start).seconds)
    }

    @Test fun `passing predicted finish does not claim target reached`() {
        val planner = ChargeTimePlanner()
        assertNull(planner.estimate(sample(eta = 10, vehicleTarget = 80), 80, start + 11_000).seconds)
        val full = planner.estimate(sample(soc = 80, charging = false), 80, start)
        assertEquals(0, full.seconds)
        assertEquals(ChargeTimeSource.TARGET_REACHED, full.source)
    }

    @Test fun `recent rate needs two minutes and two percent before estimating`() {
        val planner = ChargeTimePlanner()
        val first = sample(soc = 60)
        val middle = sample(at = start + 60_000, soc = 61)
        val last = sample(at = start + 120_000, soc = 62)
        planner.observe(first, start)
        planner.observe(middle, start + 60_000)
        assertNull(planner.estimate(middle, 80, start + 60_000).seconds)
        planner.observe(last, start + 120_000)
        val estimate = planner.estimate(last, 80, start + 120_000)
        assertEquals(ChargeTimeSource.RECENT_RATE, estimate.source)
        assertEquals(1080, estimate.seconds) // 18 percentage points at the observed 1/min rate.
    }

    @Test fun `rate never promises a goal above the actual vehicle stopping limit`() {
        val planner = ChargeTimePlanner()
        for (i in 0..2) planner.observe(sample(at = start + i * 60_000, soc = 60 + i, vehicleTarget = 80), start + i * 60_000)
        val current = sample(at = start + 120_000, soc = 62, vehicleTarget = 80)
        assertEquals(ChargeEstimateReason.TARGET_DIFFERS, planner.estimate(current, 90, start + 120_000).reason)
        assertNull(planner.estimate(current, 90, start + 120_000).seconds)
        assertEquals(ChargeTimeSource.RECENT_RATE, planner.estimate(current, 70, start + 120_000).source)
        assertEquals(480, planner.estimate(current, 70, start + 120_000).seconds)
        assertEquals(780, planner.estimate(current, 75, start + 120_000).seconds)
    }

    @Test fun `rate history is discarded after interruption decrease stale data and sampling gap`() {
        fun seeded(): ChargeTimePlanner = ChargeTimePlanner().also {
            for (i in 0..2) it.observe(sample(at = start + i * 60_000, soc = 60 + i), start + i * 60_000)
        }
        for (bad in listOf(
            sample(at = start + 150_000, soc = 62, charging = false),
            sample(at = start + 150_000, soc = 61),
            sample(at = start + 250_000, soc = 63),
            sample(at = start + 150_000, soc = 62).copy(uxRestricted = true),
        )) {
            val planner = seeded()
            planner.observe(bad, bad.observedAtMillis)
            val fresh = sample(at = bad.observedAtMillis + 30_000, soc = bad.stateOfCharge!!)
            planner.observe(fresh, fresh.observedAtMillis)
            assertNull(planner.estimate(fresh, 80, fresh.observedAtMillis).seconds)
        }
        val planner = seeded()
        val stale = sample(at = start + 120_000, soc = 62)
        planner.observe(stale, start + 160_000)
        val fresh = sample(at = start + 170_000, soc = 63)
        planner.observe(fresh, fresh.observedAtMillis)
        assertNull(planner.estimate(fresh, 80, fresh.observedAtMillis).seconds)
    }

    @Test fun `repeated timestamp and stalled SOC cannot manufacture useful history`() {
        val planner = ChargeTimePlanner()
        repeat(100) { planner.observe(sample(), start) }
        assertNull(planner.estimate(sample(), 80, start).seconds)
        for (i in 0..5) planner.observe(sample(at = start + i * 60_000, soc = minOf(62, 60 + i)), start + i * 60_000)
        assertNull(planner.estimate(sample(at = start + 300_000, soc = 62), 80, start + 300_000).seconds)
    }

    @Test fun `earlier departure cannot erase the recent rate recommendation margin`() {
        val rate = ChargeEstimate(900, ChargeTimeSource.RECENT_RATE, 80, ChargeEstimateReason.RECENT_CHARGE_RATE)
        assertEquals(ChargeWindow(850, ChargeTimeSource.DEPARTURE), ChargeWindow.resolve(rate, start + 850_000, start))
        assertEquals(ChargeWindow(720, ChargeTimeSource.RECENT_RATE), ChargeWindow.forRecommendations(rate, start + 850_000, start))
        assertEquals(ChargeWindow(600, ChargeTimeSource.DEPARTURE), ChargeWindow.forRecommendations(rate, start + 600_000, start))
        assertEquals(ChargeWindow(850, ChargeTimeSource.DEPARTURE), ChargeWindow.forRecommendations(rate.copy(source = ChargeTimeSource.VEHICLE), start + 850_000, start))
    }

    @Test fun `departure is an independent earlier deadline and unknown ETA remains unknown`() {
        val unknown = ChargeEstimate(null, ChargeTimeSource.UNKNOWN, 80, ChargeEstimateReason.NO_ESTIMATE)
        assertNull(ChargeWindow.resolve(unknown, null, start).seconds)
        assertEquals(ChargeWindow(600, ChargeTimeSource.DEPARTURE), ChargeWindow.resolve(unknown, start + 600_000, start))
        val known = unknown.copy(seconds = 1200, source = ChargeTimeSource.VEHICLE)
        assertEquals(ChargeWindow(600, ChargeTimeSource.DEPARTURE), ChargeWindow.resolve(known, start + 600_000, start))
        assertEquals(ChargeWindow(1200, ChargeTimeSource.VEHICLE), ChargeWindow.resolve(known, start + 1800_000, start))
        assertEquals(ChargeWindow(0, ChargeTimeSource.DEPARTURE), ChargeWindow.resolve(known, start - 1000, start))
    }
}
