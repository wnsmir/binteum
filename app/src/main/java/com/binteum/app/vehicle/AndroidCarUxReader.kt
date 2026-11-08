package com.binteum.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.EvChargeState
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executors

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
    private var active = false
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
        // Re-read the current manager so a queued event from an old connection cannot unlock UI.
        if (active) refresh()
    }

    fun start() {
        active = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onChanged(null, "AAOS UX 연결은 Android 10 이상 필요")
            return
        }
        car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
            if (!active) return@createCar
            if (!ready) {
                manager = null
                propertyManager = null
                onChanged(null, "AAOS UX 서비스 연결 끊김")
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
                    onChanged(null, "AAOS UX 접근 실패")
                }
            }
        }
    }

    data class ChargeObservation(val charging: Boolean?, val changedAtMillis: Long, val status: String)

    fun readChargeState(onComplete: (ChargeObservation) -> Unit) {
        if (!active || context.checkSelfPermission(Car.PERMISSION_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            onComplete(ChargeObservation(null, 0L, "AAOS CAR_ENERGY 권한 없음"))
            return
        }
        val properties = propertyManager
        if (properties == null || car?.isConnected != true) {
            onComplete(ChargeObservation(null, 0L, "AAOS 충전 서비스 미연결"))
            return
        }
        // Synchronous VHAL reads may block. The caller owns a bounded timeout and ignores late results.
        worker.execute {
            val observation = try {
                val value = properties.getProperty<Int>(VehiclePropertyIds.EV_CHARGE_STATE, 0)
                val timestamp = value?.timestamp ?: 0L
                val ageNanos = SystemClock.elapsedRealtimeNanos() - timestamp
                if (value?.status != CarPropertyValue.STATUS_AVAILABLE) {
                    ChargeObservation(null, 0L, "AAOS 충전 상태 미확인")
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
                ChargeObservation(null, 0L, "AAOS 충전 상태 권한 거부")
            } catch (_: Exception) {
                ChargeObservation(null, 0L, "AAOS 충전 상태 미지원/읽기 실패")
            }
            handler.post {
                if (active) {
                    onComplete(if (car?.isConnected == true && propertyManager === properties) observation
                    else ChargeObservation(null, 0L, "AAOS 충전 서비스 연결 끊김"))
                }
            }
        }
    }

    fun refresh() {
        if (!active) return
        if (car?.isConnected != true) {
            onChanged(null, "AAOS UX 서비스 미연결")
            return
        }
        try {
            accept(manager?.currentCarUxRestrictions)
        } catch (_: Exception) {
            onChanged(null, "AAOS UX 조회 실패")
        }
    }

    private fun accept(restrictions: CarUxRestrictions?) {
        if (restrictions == null) {
            onChanged(null, "AAOS UX 제한 상태 미확인")
            return
        }
        // Conservatively restrict the waiting experience for any active UX restriction.
        val restricted = restrictions.isRequiresDistractionOptimization ||
            restrictions.activeRestrictions != CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        onChanged(restricted, if (restricted) "AAOS UX 제한 적용" else "AAOS UX 제한 없음")
    }

    fun stop() {
        active = false
        runCatching { manager?.unregisterListener() }
        manager = null
        propertyManager = null
        runCatching { car?.disconnect() }
        car = null
        worker.shutdownNow()
    }
}
