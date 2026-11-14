package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.*
import ai.pleos.playground.vehicle.constant.PleosPermission
import ai.pleos.playground.vehicle.constant.area.SeatValue
import com.binteum.app.vehicle.*
import java.lang.reflect.Proxy

/** Fake Seat interface only. No Vehicle initialization, permission grant or motor command. */
class SeatMemoryGuardProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        var failure: Throwable? = null
        runOnMainSync {
            val controllers = mutableListOf<ComfortController>()
            try {
                var safe = true
                var denied: String? = null
                var queries = 0
                var writes = 0
                var access = AccessType.WRITE
                var area = SeatArea(SeatValue.ROW_1_LEFT)
                var bounds = AreaBounds(1, 2)
                var defer = false
                var pending: ((CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit)? = null
                val context = object : ContextWrapper(targetContext) {
                    override fun getApplicationContext(): Context = this
                    override fun checkSelfPermission(permission: String): Int =
                        if (permission == denied) PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
                }
                @Suppress("UNCHECKED_CAST")
                val seat = Proxy.newProxyInstance(Seat::class.java.classLoader, arrayOf(Seat::class.java)) { _, method, args ->
                    when (method.name) {
                        "checkSeatMemoryCapabilities" -> {
                            queries++
                            val callback = args!![0] as (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit
                            if (defer) pending = callback else callback(CapabilityConfig(access), mapOf(area to bounds))
                        }
                        "selectSeatMemory" -> writes++
                    }
                    null
                } as Seat
                fun controller(): ComfortController = ComfortController(context) { safe }.also {
                    controllers += it
                    ComfortController::class.java.getDeclaredField("active").apply { isAccessible = true }.setBoolean(it, true)
                    ComfortController::class.java.getDeclaredField("seat").apply { isAccessible = true }.set(it, seat)
                }
                fun verify(value: Boolean, name: String) { check(value) { name }; checks++ }
                safe = false
                controller().applySeatMemory(1)
                verify(queries == 0 && writes == 0, "unsafe host sends no request")
                safe = true; denied = PleosPermission.CONTROL_CAR_SEATS
                val deniedController = controller(); deniedController.applySeatMemory(1)
                verify(deniedController.state.memoryStatus == SeatMemoryStatus.PERMISSION_REQUIRED && queries == 0, "permission guard")
                denied = null
                access = AccessType.READ
                val readOnly = controller(); readOnly.applySeatMemory(1)
                verify(readOnly.state.memoryStatus == SeatMemoryStatus.UNAVAILABLE && writes == 0, "read-only capability rejected")
                access = AccessType.WRITE
                val outOfRange = controller(); outOfRange.applySeatMemory(3)
                verify(outOfRange.state.memoryStatus == SeatMemoryStatus.UNAVAILABLE && writes == 0, "unsupported slot rejected")
                area = SeatArea(SeatValue.ROW_1_RIGHT)
                val wrongSeat = controller(); wrongSeat.applySeatMemory(1)
                verify(wrongSeat.state.memoryStatus == SeatMemoryStatus.UNAVAILABLE, "does not substitute another physical seat")
                area = SeatArea(SeatValue.ROW_1_LEFT); bounds = AreaBounds(2, 1)
                val invalidBounds = controller(); invalidBounds.applySeatMemory(1)
                verify(invalidBounds.state.memoryStatus == SeatMemoryStatus.UNAVAILABLE, "invalid bounds rejected")
                bounds = AreaBounds(1, 2)
                val missingPark = controller(); missingPark.applySeatMemory(1)
                verify(missingPark.state.memoryStatus == SeatMemoryStatus.PARK_REQUIRED && writes == 0, "no fresh P and speed means no motion")
                defer = true
                val stopped = controller(); stopped.applySeatMemory(1)
                val before = queries
                stopped.applySeatMemory(1)
                verify(queries == before && stopped.state.busy, "duplicate tap is blocked while checking")
                stopped.stop()
                pending!!(CapabilityConfig(AccessType.WRITE), mapOf(area to bounds))
                verify(!stopped.state.busy && stopped.state.memoryStatus == SeatMemoryStatus.NOT_CHECKED && writes == 0, "late callback ignored after stop")
                verify(writes == 0, "all blocked scenarios send zero commands")
            } catch (error: Throwable) { failure = error.cause ?: error }
            finally { controllers.forEach { it.stop() } }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putInt("checks", checks)
            putString("result", failure?.let { "FAIL: ${it.javaClass.simpleName}: ${it.message}" } ?: "PASS: fake seat safety guards")
            putInt("realVehicleWrites", 0)
        })
    }
}
