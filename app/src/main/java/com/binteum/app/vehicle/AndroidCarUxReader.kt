package com.binteum.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.EvChargeState
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kept separate so a device without the optional android.car library can fail closed. */
internal class AndroidCarUxReader(
    private val context: Context,
    private val handler: Handler,
    private val onChanged: (Boolean?, String) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var propertyManager: CarPropertyManager? = null
    private val worker = Executors.newSingleThreadExecutor()
    // Optional ETA/target reads must never delay the mandatory charge-state observation.
    private val detailsWorker = Executors.newSingleThreadExecutor()
    private var detailsBusy = false
    private var active = false
    val isConnected: Boolean get() = active && car?.isConnected == true && manager != null && propertyManager != null
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
        // Re-read the current manager so a queued event from an old connection cannot unlock UI.
        if (active) refresh()
    }

    fun start() {
        active = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onChanged(null, context.getString(com.binteum.app.R.string.diagnostic_22))
            return
        }
        car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
            if (!active) return@createCar
            if (!ready) {
                manager = null
                propertyManager = null
                onChanged(null, context.getString(com.binteum.app.R.string.diagnostic_23))
            } else {
                try {
                    propertyManager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    manager?.unregisterListener()
                    manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                        as? CarUxRestrictionsManager
                    manager?.registerListener(listener)
                    refresh()
                } catch (_: Exception) {
                    manager = null
                    onChanged(null, context.getString(com.binteum.app.R.string.diagnostic_24))
                }
            }
        }
    }

    data class ChargeObservation(val charging: Boolean?, val changedAtMillis: Long, val status: String)

    data class ChargeDetailsObservation(
        val remainingSeconds: Int? = null,
        val targetPercent: Int? = null,
        val sampledAtMillis: Long? = null,
        val status: String = "AAOS charge estimate unavailable",
    )

    /** A separate current read confirms a Pleos write; its setter callback only echoes input. */
    fun readChargeTarget(onComplete: (Int?) -> Unit) {
        val properties = propertyManager
        if (!active || properties == null || car?.isConnected != true ||
            context.checkSelfPermission(Car.PERMISSION_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            onComplete(null)
            return
        }
        detailsWorker.execute {
            val target = runCatching {
                val value = properties.getProperty<Float>(VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT, 0)
                val raw = value?.value
                if (value?.status != CarPropertyValue.STATUS_AVAILABLE || raw == null || !raw.isFinite() || raw !in 1f..100f) null
                else raw.roundToInt().takeIf { abs(raw - it) < .001f }
            }.getOrNull()
            handler.post {
                if (active) onComplete(target.takeIf { car?.isConnected == true && propertyManager === properties })
            }
        }
    }

    /** Optional properties. Failure never changes the separately observed charge or UX state. */
    fun readChargeDetails(onComplete: (ChargeDetailsObservation) -> Unit) {
        val properties = propertyManager
        if (!active || detailsBusy || properties == null || car?.isConnected != true ||
            context.checkSelfPermission(Car.PERMISSION_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            onComplete(ChargeDetailsObservation())
            return
        }
        detailsBusy = true
        detailsWorker.execute {
            fun readable(id: Int, type: Class<*>): Boolean = runCatching {
                val config = properties.getCarPropertyConfig(id)
                config != null && (config.access and CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ) != 0 &&
                    config.propertyType == type
            }.getOrDefault(false)

            fun readTarget(): Pair<Int, Long>? = runCatching {
                if (!readable(VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT, Float::class.javaObjectType)) null
                else properties.getProperty<Float>(VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT, 0)?.let { value ->
                    val raw = value.value
                    if (value.status != CarPropertyValue.STATUS_AVAILABLE || !raw.isFinite() || raw !in 1f..100f) null
                    else raw.roundToInt().takeIf { abs(raw - it) < .001f }?.let { it to value.timestamp }
                }
            }.getOrNull()
            val targetBefore = readTarget()
            val estimate = runCatching {
                if (!readable(VehiclePropertyIds.EV_CHARGE_TIME_REMAINING, Int::class.javaObjectType)) null
                else properties.getProperty<Int>(VehiclePropertyIds.EV_CHARGE_TIME_REMAINING, 0)?.let { value ->
                    val age = SystemClock.elapsedRealtimeNanos() - value.timestamp
                    // Unlike ON_CHANGE charge state, this is a CONTINUOUS measurement. Reject
                    // stale/future/absent sample timestamps; a re-read must not restart its clock.
                    if (value.status != CarPropertyValue.STATUS_AVAILABLE || value.timestamp <= 0L ||
                        age !in 0L..30_000_000_000L || value.value !in 1..604_800 ||
                        (targetBefore != null && value.timestamp < targetBefore.second)) null
                    else value.value to (System.currentTimeMillis() - age / 1_000_000L)
                }
            }.getOrNull()
            // These properties are not an atomic snapshot. A limit change during the reads
            // invalidates this ETA instead of attaching an old estimate to the new target.
            val targetAfter = readTarget()
            val target = targetAfter?.first
            val coherentEstimate = estimate.takeIf {
                targetBefore == targetAfter && (targetAfter?.second ?: 0L) > 0L
            }
            val estimateLabel = coherentEstimate?.first?.toString() ?: "unavailable"
            val targetLabel = target?.toString() ?: "unavailable"
            val observation = ChargeDetailsObservation(coherentEstimate?.first, target, coherentEstimate?.second,
                "AAOS ETA seconds: $estimateLabel / vehicle target percent: $targetLabel")
            handler.post {
                detailsBusy = false
                if (active) onComplete(if (car?.isConnected == true && propertyManager === properties) observation
                    else ChargeDetailsObservation())
            }
        }
    }

    fun readChargeState(onComplete: (ChargeObservation) -> Unit) {
        if (!active || context.checkSelfPermission(Car.PERMISSION_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            onComplete(ChargeObservation(null, 0L, context.getString(com.binteum.app.R.string.diagnostic_25)))
            return
        }
        val properties = propertyManager
        if (properties == null || car?.isConnected != true) {
            onComplete(ChargeObservation(null, 0L, context.getString(com.binteum.app.R.string.diagnostic_26)))
            return
        }
        // Synchronous VHAL reads may block. The caller owns a bounded timeout and ignores late results.
        worker.execute {
            val observation = try {
                val value = properties.getProperty<Int>(VehiclePropertyIds.EV_CHARGE_STATE, 0)
                val timestamp = value?.timestamp ?: 0L
                val ageNanos = SystemClock.elapsedRealtimeNanos() - timestamp
                if (value?.status != CarPropertyValue.STATUS_AVAILABLE) {
                    ChargeObservation(null, 0L, context.getString(com.binteum.app.R.string.diagnostic_27))
                } else {
                    val charging = when (value.value) {
                        EvChargeState.STATE_CHARGING -> true
                        EvChargeState.STATE_FULLY_CHARGED,
                        EvChargeState.STATE_NOT_CHARGING,
                        EvChargeState.STATE_ERROR -> false
                        else -> null
                    }
                    // ON_CHANGE timestamps describe the last state change, not this read's age.
                    val changedAt = if (timestamp > 0L && ageNanos >= 0L)
                        System.currentTimeMillis() - ageNanos / 1_000_000L else 0L
                    ChargeObservation(charging, changedAt,
                        "AAOS EV_CHARGE_STATE: ${EvChargeState.toString(value.value)}")
                }
            } catch (_: SecurityException) {
                ChargeObservation(null, 0L, context.getString(com.binteum.app.R.string.diagnostic_28))
            } catch (_: Exception) {
                ChargeObservation(null, 0L, context.getString(com.binteum.app.R.string.diagnostic_29))
            }
            handler.post {
                if (active) {
                    onComplete(if (car?.isConnected == true && propertyManager === properties) observation
                    else ChargeObservation(null, 0L, context.getString(com.binteum.app.R.string.diagnostic_30)))
                }
            }
        }
    }

    fun refresh() {
        if (!active) return
        if (car?.isConnected != true) {
            onChanged(null, context.getString(com.binteum.app.R.string.diagnostic_31))
            return
        }
        try {
            accept(manager?.currentCarUxRestrictions)
        } catch (_: Exception) {
            onChanged(null, context.getString(com.binteum.app.R.string.diagnostic_32))
        }
    }

    private fun accept(restrictions: CarUxRestrictions?) {
        if (restrictions == null) {
            onChanged(null, context.getString(com.binteum.app.R.string.diagnostic_33))
            return
        }
        // Conservatively restrict the waiting experience for any active UX restriction.
        val restricted = restrictions.isRequiresDistractionOptimization ||
            restrictions.activeRestrictions != CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        onChanged(restricted, if (restricted) context.getString(com.binteum.app.R.string.diagnostic_34) else context.getString(com.binteum.app.R.string.diagnostic_35))
    }

    fun stop() {
        active = false
        runCatching { manager?.unregisterListener() }
        manager = null
        propertyManager = null
        runCatching { car?.disconnect() }
        car = null
        worker.shutdownNow()
        detailsWorker.shutdownNow()
    }
}
