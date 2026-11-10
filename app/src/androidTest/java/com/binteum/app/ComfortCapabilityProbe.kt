package com.binteum.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.app.Activity
import android.app.Instrumentation
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in diagnostic runner: reads capabilities and current values only.
 * No Activity, UI automation, permission grants, preference writes, or vehicle setters.
 * RESULT_OK means the diagnostic ran; individual fields report actual availability.
 */
class ComfortCapabilityProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val values = ConcurrentHashMap<String, String>()
        val completed = CountDownLatch(4)
        val finished = ConcurrentHashMap.newKeySet<String>()
        val alive = AtomicBoolean(true)
        fun complete(key: String) { if (finished.add(key)) completed.countDown() }
        fun describe(error: Exception) = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(300)}"
        val worker = Executors.newSingleThreadExecutor()
        var vehicle: Vehicle? = null
        var car: Car? = null
        fun permission(name: String) = targetContext.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        listOf("pleos.car.permission.CAR_CLIMATE", "pleos.car.permission.CONTROL_CAR_CLIMATE",
            "pleos.car.permission.CAR_SEATS", "android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_SPEED")
            .forEach { values[it] = permission(it).toString() }
        try {
            runOnMainSync {
                try {
                    vehicle = Vehicle(targetContext).also { it.initialize() }
                    val hvac = vehicle!!.getHVAC()
                    hvac.checkHvacTemperatureCapabilities({ config, areas, table ->
                        values["temperature.access"] = config.accessType.name
                        values["temperature.zones"] = areas.entries.joinToString("; ") {
                            "${it.key.values.joinToString { seat -> seat.name }}: ${it.value.minValue}..${it.value.maxValue} C"
                        }
                        values["temperature.step"] = table?.incrementCelsius?.toString() ?: "unavailable"
                        val zone = areas.keys.filter { SeatValue.ROW_1_LEFT in it.values }.minByOrNull { it.values.size }
                        if (zone == null) complete("temperature")
                        else try {
                            hvac.getHvacTemperature(zone, TemperatureUnit.CELSIUS, {
                                values["temperature.currentSetpoint"] = it?.toString() ?: "unavailable"
                                complete("temperature")
                            }, { values["temperature.readError"] = describe(it); complete("temperature") })
                        } catch (error: Exception) {
                            values["temperature.readError"] = describe(error); complete("temperature")
                        }
                    }, { values["temperature.error"] = describe(it); complete("temperature") })
                    hvac.checkHvacPowerCapabilities({ config, areas ->
                        values["climatePower.access"] = config.accessType.name
                        val area = areas.filter { SeatValue.ROW_1_LEFT in it.values }.minByOrNull { it.values.size }
                        if (area == null) { values["climatePower.state"] = "no matching zone"; complete("power") }
                        else try {
                            hvac.getHvacPowerState(area, {
                                values["climatePower.state"] = it?.toString() ?: "unavailable"; complete("power")
                            }, { values["climatePower.error"] = describe(it); complete("power") })
                        } catch (error: Exception) { values["climatePower.error"] = describe(error); complete("power") }
                    }, { values["climatePower.error"] = describe(it); complete("power") })
                    val seat = vehicle!!.getSeat()
                    seat.checkSeatBackrestAnglePositionCapabilities({ config, areas ->
                        values["seat.positionAccess"] = config.accessType.name
                        values["seat.positionZones"] = areas.entries.joinToString("; ") {
                            "${it.key.values.joinToString { seatValue -> seatValue.name }}: raw ${it.value.minValue}..${it.value.maxValue}"
                        }
                        complete("seat")
                    }, { values["seat.error"] = it.javaClass.simpleName; complete("seat") })
                } catch (error: Exception) {
                    values["pleos.error"] = error.javaClass.simpleName
                    complete("temperature"); complete("seat"); complete("power")
                }
                try {
                    car = Car.createCar(targetContext, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                        if (!ready || !alive.get()) return@createCar
                        worker.execute {
                            try {
                                val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                                if (permission("android.car.permission.CAR_POWERTRAIN")) {
                                    val gear = properties.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
                                    values["gear.value"] = gear?.value?.toString() ?: "unavailable"
                                    values["gear.status"] = gear?.status?.toString() ?: "unavailable"
                                }
                                if (permission("android.car.permission.CAR_SPEED")) {
                                    val speed = properties.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                                    values["speed.metersPerSecond"] = speed?.value?.toString() ?: "unavailable"
                                    values["speed.status"] = speed?.status?.toString() ?: "unavailable"
                                    values["speed.sampleAgeMs"] = speed?.let { ((SystemClock.elapsedRealtimeNanos() - it.timestamp) / 1_000_000L).toString() } ?: "unavailable"
                                }
                            } catch (error: Exception) { values["aaos.error"] = error.javaClass.simpleName }
                            complete("aaos")
                        }
                    }
                } catch (error: Exception) { values["aaos.error"] = error.javaClass.simpleName; complete("aaos") }
            }
            val allReturned = completed.await(15, TimeUnit.SECONDS)
            values["diagnostic.completedWithinTimeout"] = allReturned.toString()
            values["diagnostic.vehicleWrites"] = "0"
            alive.set(false)
            runOnMainSync { runCatching { car?.disconnect() }; runCatching { vehicle?.release() } }
            finish(Activity.RESULT_OK, Bundle().apply { values.toSortedMap().forEach { (key, value) -> putString(key, value) } })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("diagnostic.error", error.javaClass.simpleName) })
        } finally {
            alive.set(false)
            runOnMainSync { runCatching { car?.disconnect() }; runCatching { vehicle?.release() } }
            worker.shutdownNow()
        }
    }
}
