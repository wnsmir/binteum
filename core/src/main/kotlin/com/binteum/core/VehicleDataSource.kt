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
    private var snapshot = initialSnapshot ?: VehicleSnapshot(65, true, false, true, now())

    override fun start(onSnapshot: (VehicleSnapshot) -> Unit) {
        listener = onSnapshot
        onSnapshot(snapshot)
    }

    fun emit(stateOfCharge: Int = 65, charging: Boolean = true, uxRestricted: Boolean? = false,
             awake: Boolean = true, stale: Boolean = false) {
        snapshot = VehicleSnapshot(stateOfCharge, charging, uxRestricted, awake,
            now() - if (stale) 60_000 else 0)
        listener?.invoke(snapshot)
    }

    override fun stop() { listener = null }
}
