package com.binteum.core

import kotlin.math.ceil

/** DEPARTURE is a user deadline, never a prediction that charging will finish. */
enum class ChargeTimeSource { VEHICLE, RECENT_RATE, UNKNOWN, TARGET_REACHED, DEPARTURE }
enum class ChargeEstimateReason {
    VEHICLE_ESTIMATE, RECENT_CHARGE_RATE, TARGET_REACHED, NO_FRESH_DATA,
    NOT_CHARGING, NO_ESTIMATE, TARGET_DIFFERS, COLLECTING_RATE,
}

data class ChargeEstimate(
    val seconds: Int?,
    val source: ChargeTimeSource,
    val targetPercent: Int,
    val reason: ChargeEstimateReason,
)

/**
 * Uses the vehicle's own target-specific ETA first, otherwise a deliberately coarse SOC trend.
 * Never scales an OEM ETA to a different target: charge taper makes that relationship nonlinear.
 * Estimates must be labelled approximate; they are not a charging command or completion signal.
 */
class ChargeTimePlanner {
    private data class Point(val atMillis: Long, val percent: Int)
    private val points = ArrayDeque<Point>()
    private var lastIncreaseAt = 0L

    fun reset() { points.clear(); lastIncreaseAt = 0L }

    fun observe(snapshot: VehicleSnapshot, nowMillis: Long) {
        if (!fresh(snapshot, nowMillis) || snapshot.charging != true) { reset(); return }
        val percent = snapshot.stateOfCharge!!
        val at = snapshot.observedAtMillis
        val previous = points.lastOrNull()
        if (previous != null) {
            if (at < previous.atMillis || at - previous.atMillis > MAX_SAMPLE_GAP_MILLIS || percent < previous.percent) reset()
            else if (at == previous.atMillis) {
                // A changed value carrying the same timestamp is not a usable rate sample.
                if (percent != previous.percent) reset()
                return
            }
        }
        val last = points.lastOrNull()
        if (last == null || percent > last.percent) lastIncreaseAt = at
        points.addLast(Point(at, percent))
        while (points.size > 1 && at - points.first().atMillis > RATE_WINDOW_MILLIS) points.removeFirst()
        while (points.size > 1000) points.removeFirst()
    }

    fun estimate(snapshot: VehicleSnapshot?, targetPercent: Int, nowMillis: Long): ChargeEstimate {
        require(targetPercent in 1..100)
        fun unknown(reason: ChargeEstimateReason) = ChargeEstimate(null, ChargeTimeSource.UNKNOWN, targetPercent, reason)
        if (snapshot == null || !fresh(snapshot, nowMillis)) { reset(); return unknown(ChargeEstimateReason.NO_FRESH_DATA) }
        val current = snapshot.stateOfCharge!!
        if (current >= targetPercent) return ChargeEstimate(0, ChargeTimeSource.TARGET_REACHED, targetPercent, ChargeEstimateReason.TARGET_REACHED)
        if (snapshot.charging != true) { reset(); return unknown(ChargeEstimateReason.NOT_CHARGING) }
        // An app goal does not override the vehicle's actual stopping limit.
        if (snapshot.vehicleTargetPercent?.let { it in 1..100 && targetPercent > it } == true)
            return unknown(ChargeEstimateReason.TARGET_DIFFERS)

        val eta = snapshot.chargeTimeRemainingSeconds
        val etaAt = snapshot.chargeTimeObservedAtMillis
        val etaAge = etaAt?.let { nowMillis - it }
        if (snapshot.vehicleTargetPercent == targetPercent && eta != null && eta in 1..MAX_ESTIMATE_SECONDS &&
            etaAge != null && etaAge in 0..MAX_ETA_AGE_MILLIS) {
            val remaining = eta - (etaAge / 1000).toInt()
            // A predicted finish passing is not evidence the battery reached the target.
            if (remaining > 0) return ChargeEstimate(remaining, ChargeTimeSource.VEHICLE, targetPercent, ChargeEstimateReason.VEHICLE_ESTIMATE)
        }

        val first = points.firstOrNull()
        val last = points.lastOrNull()
        if (first != null && last != null && last.percent == current &&
            nowMillis - last.atMillis in 0..MAX_SAMPLE_AGE_MILLIS &&
            nowMillis - lastIncreaseAt in 0..MAX_STALL_MILLIS) {
            val spanMillis = last.atMillis - first.atMillis
            val gain = last.percent - first.percent
            if (spanMillis >= MIN_RATE_SPAN_MILLIS && gain >= 2) {
                val percentPerMinute = gain.toDouble() * 60_000 / spanMillis
                if (percentPerMinute in 0.05..20.0) {
                    // Return the raw approximate rate ETA; callers shorten recommendation budgets separately.
                    val predicted = ceil((targetPercent - current).toDouble() * spanMillis / gain / 1000)
                    if (predicted >= 1 && predicted <= MAX_ESTIMATE_SECONDS) {
                        return ChargeEstimate(predicted.toInt(), ChargeTimeSource.RECENT_RATE, targetPercent, ChargeEstimateReason.RECENT_CHARGE_RATE)
                    }
                }
            }
        }
        return unknown(if (snapshot.vehicleTargetPercent != null && snapshot.vehicleTargetPercent != targetPercent)
            ChargeEstimateReason.TARGET_DIFFERS else if (points.isNotEmpty()) ChargeEstimateReason.COLLECTING_RATE else ChargeEstimateReason.NO_ESTIMATE)
    }

    private fun fresh(snapshot: VehicleSnapshot, nowMillis: Long): Boolean =
        snapshot.vehicleAwake && snapshot.uxRestricted == false && snapshot.stateOfCharge?.let { it in 0..100 } == true &&
            nowMillis - snapshot.observedAtMillis in 0..MAX_SAMPLE_AGE_MILLIS

    companion object {
        private const val MAX_SAMPLE_AGE_MILLIS = 30_000L
        private const val MAX_SAMPLE_GAP_MILLIS = 90_000L
        private const val MAX_ETA_AGE_MILLIS = 90_000L
        private const val MIN_RATE_SPAN_MILLIS = 120_000L
        private const val RATE_WINDOW_MILLIS = 15 * 60_000L
        private const val MAX_STALL_MILLIS = 120_000L
        private const val MAX_ESTIMATE_SECONDS = 24 * 60 * 60
    }
}

/** Time available for recommendations; a departure limit does not change the charge estimate. */
data class ChargeWindow(val seconds: Int?, val source: ChargeTimeSource) {
    companion object {
        /** Reduce an uncertain charge-rate budget before comparing with the departure deadline. */
        fun forRecommendations(estimate: ChargeEstimate, departureAtMillis: Long?, nowMillis: Long): ChargeWindow {
            val conservative = if (estimate.source == ChargeTimeSource.RECENT_RATE)
                estimate.copy(seconds = estimate.seconds?.let { (it.coerceAtLeast(0) * .8).toInt() }) else estimate
            return resolve(conservative, departureAtMillis, nowMillis)
        }

        fun resolve(estimate: ChargeEstimate, departureAtMillis: Long?, nowMillis: Long): ChargeWindow {
            val chargeSeconds = estimate.seconds?.coerceAtLeast(0)
            if (departureAtMillis == null) return ChargeWindow(chargeSeconds, estimate.source)
            val difference = if (departureAtMillis <= nowMillis) 0L else
                (departureAtMillis.toDouble() - nowMillis.toDouble()).coerceAtMost(Int.MAX_VALUE.toDouble() * 1000).toLong()
            val departureSeconds = (difference / 1000).toInt()
            return if (chargeSeconds != null && chargeSeconds <= departureSeconds) ChargeWindow(chargeSeconds, estimate.source)
            else ChargeWindow(departureSeconds, ChargeTimeSource.DEPARTURE)
        }
    }
}
