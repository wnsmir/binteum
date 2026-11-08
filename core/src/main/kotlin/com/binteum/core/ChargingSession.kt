package com.binteum.core

/**
 * Observation time, not UI render time. If the provider omits the original vehicle timestamp,
 * adapters use the read-request start conservatively; this cannot prove upstream cache freshness.
 */
data class VehicleSnapshot(
    val stateOfCharge: Int?,
    val charging: Boolean?,
    val uxRestricted: Boolean?,
    val vehicleAwake: Boolean,
    val observedAtMillis: Long,
)

enum class SessionStatus {
    WAITING_FOR_DATA, SUSPENDED, DRIVING_LOCKED, CHARGING, TARGET_REACHED, INTERRUPTED,
}

data class SessionResult(val status: SessionStatus, val notifyTargetReached: Boolean = false)
data class SessionCheckpoint(val targetPercent: Int, val targetReached: Boolean, val notificationDelivered: Boolean)

/** No vehicle controls. The target is an app reminder, not a charging limit. */
class ChargingSession(val targetPercent: Int, private val maxDataAgeMillis: Long = 30_000,
                      checkpoint: SessionCheckpoint? = null) {
    private var targetNotificationDelivered = checkpoint?.notificationDelivered ?: false
    private var targetReached = checkpoint?.targetReached ?: false

    init {
        require(targetPercent in 1..100)
        require(maxDataAgeMillis > 0)
        require(checkpoint == null || checkpoint.targetPercent == targetPercent)
        require(!targetNotificationDelivered || targetReached)
    }

    fun checkpoint() = SessionCheckpoint(targetPercent, targetReached, targetNotificationDelivered)

    fun update(snapshot: VehicleSnapshot, nowMillis: Long): SessionResult {
        val age = nowMillis - snapshot.observedAtMillis
        val status = when {
            !snapshot.vehicleAwake -> SessionStatus.SUSPENDED
            snapshot.uxRestricted == true -> SessionStatus.DRIVING_LOCKED
            snapshot.uxRestricted == null || age !in 0..maxDataAgeMillis ||
                snapshot.stateOfCharge == null || snapshot.stateOfCharge !in 0..100 ||
                snapshot.charging == null -> SessionStatus.WAITING_FOR_DATA
            targetReached || snapshot.stateOfCharge >= targetPercent -> SessionStatus.TARGET_REACHED
            !snapshot.charging -> SessionStatus.INTERRUPTED
            else -> SessionStatus.CHARGING
        }
        if (status == SessionStatus.TARGET_REACHED) targetReached = true
        val notify = status == SessionStatus.TARGET_REACHED && !targetNotificationDelivered
        if (notify) targetNotificationDelivered = true
        return SessionResult(status, notify)
    }
}

data class ActivityOption(val title: String, val durationMinutes: Int, val oneWayWalkMinutes: Int = 0)

/** A user-selected window; never presented as an OEM charging-time prediction. */
object TimeBudget {
    fun availableMinutes(windowMinutes: Int, oneWayWalkMinutes: Int, reserveMinutes: Int = 2): Int {
        require(windowMinutes >= 0 && oneWayWalkMinutes >= 0 && reserveMinutes >= 0)
        return (windowMinutes.toLong() - 2L * oneWayWalkMinutes - reserveMinutes)
            .coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun fits(option: ActivityOption, windowMinutes: Int, reserveMinutes: Int = 2): Boolean {
        require(option.durationMinutes > 0)
        return option.durationMinutes <= availableMinutes(windowMinutes, option.oneWayWalkMinutes, reserveMinutes)
    }
}
