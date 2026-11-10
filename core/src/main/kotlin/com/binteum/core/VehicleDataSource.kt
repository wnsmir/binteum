package com.binteum.core

/**
 * Production integration must check permissions/capabilities and report original timestamps.
 * uxRestricted must come from CarUxRestrictions, not a guess based on gear or speed.
 * FusedLocation is the vehicle's location; it is not a pedestrian or phone location provider.
 */
interface VehicleDataSource {
    val isSimulation: Boolean
    fun start(onSnapshot: (VehicleSnapshot) -> Unit)
    fun stop()
}

class DemoVehicleDataSource(initialSnapshot: VehicleSnapshot? = null,
                            private val now: () -> Long = System::currentTimeMillis) : VehicleDataSource {
    override val isSimulation = true
    private var listener: ((VehicleSnapshot) -> Unit)? = null
    private val defaultObservedAt = now()
    private var snapshot = initialSnapshot ?: VehicleSnapshot(65, true, false, true, defaultObservedAt,
        chargeTimeRemainingSeconds = 20 * 60, vehicleTargetPercent = 80,
        chargeTimeObservedAtMillis = defaultObservedAt)
    private var requestedEtaSeconds: Int? = snapshot.chargeTimeRemainingSeconds
    private var estimateEndAt = defaultObservedAt + (requestedEtaSeconds ?: 0) * 1_000L

    override fun start(onSnapshot: (VehicleSnapshot) -> Unit) {
        listener = onSnapshot
        onSnapshot(snapshot)
    }

    fun emit(stateOfCharge: Int = 65, charging: Boolean = true, uxRestricted: Boolean? = false,
             awake: Boolean = true, stale: Boolean = false,
             chargeTimeRemainingSeconds: Int? = 20 * 60, vehicleTargetPercent: Int? = 80) {
        val currentTime = now()
        if (snapshot.charging != charging || snapshot.vehicleTargetPercent != vehicleTargetPercent ||
            requestedEtaSeconds != chargeTimeRemainingSeconds) {
            estimateEndAt = currentTime + (chargeTimeRemainingSeconds ?: 0) * 1_000L
        }
        requestedEtaSeconds = chargeTimeRemainingSeconds
        val remaining = chargeTimeRemainingSeconds?.let {
            ((estimateEndAt - currentTime).coerceAtLeast(0L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val observedAt = currentTime - if (stale) 60_000 else 0
        snapshot = VehicleSnapshot(stateOfCharge, charging, uxRestricted, awake,
            observedAt, remaining.takeIf { charging }, vehicleTargetPercent,
            observedAt.takeIf { charging && chargeTimeRemainingSeconds != null })
        listener?.invoke(snapshot)
    }

    override fun stop() { listener = null }
}
