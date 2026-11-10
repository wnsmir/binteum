package com.binteum.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.PleosPermission
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ComfortStatus {
    STOPPED, CONNECTING, READY, UNAVAILABLE, PERMISSION_REQUIRED, PARK_REQUIRED,
    CHECKING, APPLIED, RESTORED, CHANGED_ELSEWHERE, UNCONFIRMED,
}

data class ComfortState(
    val busy: Boolean = false,
    val status: ComfortStatus = ComfortStatus.STOPPED,
    val currentCelsius: Float? = null,
    val supportedCelsius: List<Float> = emptyList(),
    /** The selected physical climate zone; never assumed to be the driver's seat. */
    val zoneLabel: String? = null,
    val originalCelsius: Float? = null,
    val canSet: Boolean = false,
    val canRestore: Boolean = false,
    /** Vehicle-specific raw position, deliberately not presented as degrees or percent. */
    val seatPosition: Int? = null,
)

/**
 * Foreground, explicitly requested climate adjustment for a parked rest session.
 *
 * Starting, stopping, resuming, and ending a nap never write to the vehicle. Only
 * setTemperature/restoreTemperature write, after a new capability, current-value,
 * P/zero-speed and host-safety check. Setter echoes are not treated as readback.
 *
 * The SDK exposes continuous seat MOVE rather than an absolute position setter.
 * Without a verified movement/stop contract we read its raw position only. No
 * guessed recline angle, timed seat pulse, or automatic seat restoration is used.
 */
class ComfortController(
    context: Context,
    private val isSafeForControl: () -> Boolean,
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var vehicle: Vehicle? = null
    private var hvac: HVAC? = null
    private var seat: Seat? = null
    private var active = false
    private var epoch = 0L
    private var operation = 0L
    private var listener: ((ComfortState) -> Unit)? = null
    private var timeout: Runnable? = null
    private var original: Float? = null
    private var lastRequested: Float? = null
    private var changedArea: SeatArea? = null
    private var operationStartedAt = 0L

    var state = ComfortState()
        private set

    fun start(onChanged: (ComfortState) -> Unit) = onMain {
        stopInternal()
        listener = onChanged
        active = true
        worker = Executors.newSingleThreadExecutor()
        val generation = epoch
        emit(state.copy(status = ComfortStatus.CONNECTING, busy = false, canSet = false, canRestore = false))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            emit(state.copy(status = ComfortStatus.UNAVAILABLE))
            return@onMain
        }
        try {
            vehicle = Vehicle(context).also { it.initialize() }
            hvac = vehicle?.getHVAC()
            seat = vehicle?.getSeat()
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!active || generation != epoch) return@createCar
                properties = if (ready) runCatching {
                    connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                }.getOrNull() else null
                if (ready) refresh() else emit(state.copy(status = ComfortStatus.UNAVAILABLE, canSet = false, canRestore = false))
            }
            // A connection that never arrives must not leave a permanent spinner.
            handler.postDelayed({
                if (active && generation == epoch && state.status == ComfortStatus.CONNECTING) {
                    emit(state.copy(status = ComfortStatus.UNAVAILABLE))
                }
            }, TIMEOUT_MS)
        } catch (_: Exception) {
            emit(state.copy(status = ComfortStatus.UNAVAILABLE))
        } catch (_: LinkageError) {
            emit(state.copy(status = ComfortStatus.UNAVAILABLE))
        }
    }

    /** Read-only inspection; it never enables climate power or changes a setting. */
    fun refresh() = onMain {
        if (!active || state.busy) return@onMain
        val token = begin() ?: return@onMain
        readConfiguration(token) { config ->
            readCurrent(token, config) { current ->
                readParked(token) { parked ->
                    finish(token, state.copy(
                        status = if (parked && safe()) ComfortStatus.READY else ComfortStatus.PARK_REQUIRED,
                        currentCelsius = current,
                        supportedCelsius = config.choices,
                        zoneLabel = label(config.area),
                        originalCelsius = original,
                        canSet = parked && safe(),
                        canRestore = parked && safe() && original != null && changedArea == config.area &&
                            matches(current, lastRequested),
                    ))
                    readSeatPosition()
                }
            }
        }
    }

    /** Explicit user action. 20–24 °C are choices, not a medical sleeping-temperature claim. */
    fun setTemperature(celsius: Float) = onMain { write(celsius, restoring = false) }

    /** Explicit action only; another user's/intervening climate change is never overwritten. */
    fun restoreTemperature() = onMain {
        val previous = original ?: return@onMain
        write(previous, restoring = true)
    }

    /** Releases connections only. An applied temperature remains until explicitly changed. */
    fun stop() = onMain { stopInternal() }

    private data class Configuration(val area: SeatArea, val allValues: List<Float>, val choices: List<Float>)

    private fun write(requested: Float, restoring: Boolean) {
        if (!active || state.busy || !safe()) return
        val token = begin() ?: return
        readConfiguration(token) { config ->
            if (config.allValues.none { matches(it, requested) } ||
                (!restoring && config.choices.none { matches(it, requested) })) {
                fail(token, ComfortStatus.UNAVAILABLE); return@readConfiguration
            }
            readCurrent(token, config) { current ->
                if (restoring && (changedArea != config.area || !matches(current, lastRequested))) {
                    fail(token, ComfortStatus.CHANGED_ELSEWHERE); return@readCurrent
                }
                readParked(token) { parked ->
                    if (!parked || !safe()) { fail(token, ComfortStatus.PARK_REQUIRED); return@readParked }
                    if (!restoring && (original == null || changedArea != config.area || !matches(current, lastRequested))) {
                        original = current
                        changedArea = config.area
                    }
                    // During restoration keep the prior applied value as the retry guard.
                    // A failed restore must not make its unconfirmed target the expected value.
                    if (!restoring) lastRequested = requested
                    // Keep recovery information even if a write is accepted but its callback is lost.
                    emit(state.copy(originalCelsius = original, canRestore = false))
                    try {
                        hvac?.setHvacTemperature(config.area, requested, TemperatureUnit.CELSIUS, { _, _ -> onMain {
                            if (!valid(token)) return@onMain
                            if (!safe()) { fail(token, ComfortStatus.UNCONFIRMED); return@onMain }
                            readCurrent(token, config, ComfortStatus.UNCONFIRMED) readback@{ actual ->
                                if (!matches(actual, requested)) { fail(token, ComfortStatus.UNCONFIRMED); return@readback }
                                if (restoring) { original = null; lastRequested = null; changedArea = null }
                                finish(token, state.copy(status = if (restoring) ComfortStatus.RESTORED else ComfortStatus.APPLIED,
                                    currentCelsius = actual, supportedCelsius = config.choices, zoneLabel = label(config.area),
                                    originalCelsius = original, canSet = safe(), canRestore = !restoring && safe()))
                            }
                        } }, { fail(token, ComfortStatus.UNCONFIRMED) })
                            ?: fail(token, ComfortStatus.UNAVAILABLE)
                    } catch (_: Exception) { fail(token, ComfortStatus.UNCONFIRMED) }
                }
            }
        }
    }

    private fun readConfiguration(token: Long, done: (Configuration) -> Unit) {
        val api = hvac ?: run { fail(token, ComfortStatus.UNAVAILABLE); return }
        try {
            api.checkHvacTemperatureCapabilities({ config, areas, table -> onMain {
                if (!valid(token)) return@onMain
                val area = areas.keys.filter { SeatValue.ROW_1_LEFT in it.values }.minByOrNull { it.values.size }
                val bounds = area?.let { areas[it] }
                if (config.accessType != AccessType.READ_WRITE || area == null || bounds == null || table == null ||
                    !table.minCelsius.isFinite() || !table.maxCelsius.isFinite() || !table.incrementCelsius.isFinite() ||
                    table.incrementCelsius <= 0f || table.maxCelsius < table.minCelsius ||
                    !bounds.minValue.isFinite() || !bounds.maxValue.isFinite()) {
                    fail(token, ComfortStatus.UNAVAILABLE); return@onMain
                }
                val steps = ((table.maxCelsius - table.minCelsius) / table.incrementCelsius).roundToInt()
                if (steps !in 1..300) { fail(token, ComfortStatus.UNAVAILABLE); return@onMain }
                val values = (0..steps).map { table.minCelsius + it * table.incrementCelsius }
                    .filter { it >= bounds.minValue && it <= bounds.maxValue && it <= table.maxCelsius + .01f }
                val choices = values.filter { it in 20f..24f }
                if (choices.isEmpty()) { fail(token, ComfortStatus.UNAVAILABLE); return@onMain }
                done(Configuration(area, values, choices))
            } }, { fail(token, ComfortStatus.UNAVAILABLE) })
        } catch (_: Exception) { fail(token, ComfortStatus.UNAVAILABLE) }
    }

    private fun readCurrent(token: Long, config: Configuration,
        failureStatus: ComfortStatus = ComfortStatus.UNAVAILABLE, done: (Float) -> Unit) {
        try {
            hvac?.getHvacTemperature(config.area, TemperatureUnit.CELSIUS, { value -> onMain {
                if (!valid(token)) return@onMain
                if (value == null || !value.isFinite() || config.allValues.none { matches(value, it) }) {
                    fail(token, failureStatus)
                } else done(value)
            } }, { fail(token, failureStatus) }) ?: fail(token, ComfortStatus.UNAVAILABLE)
        } catch (_: Exception) { fail(token, failureStatus) }
    }

    /** New synchronous reads on a worker; continuous speed must have a recent VHAL timestamp. */
    private fun readParked(token: Long, done: (Boolean) -> Unit) {
        val manager = properties
        val executor = worker
        if (manager == null || executor == null || car?.isConnected != true) { done(false); return }
        try {
            executor.execute {
                val requestedAt = SystemClock.elapsedRealtime()
                val parked = runCatching {
                    val gearBefore = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
                    val speed = manager.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                    val gearAfter = manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
                    val speedAge = SystemClock.elapsedRealtimeNanos() - (speed?.timestamp ?: 0L)
                    gearBefore?.status == CarPropertyValue.STATUS_AVAILABLE &&
                        gearAfter?.status == CarPropertyValue.STATUS_AVAILABLE &&
                        gearBefore.value == VehicleGear.GEAR_PARK && gearAfter.value == VehicleGear.GEAR_PARK &&
                        gearBefore.timestamp == gearAfter.timestamp &&
                        speed?.status == CarPropertyValue.STATUS_AVAILABLE && speed.value.isFinite() &&
                        abs(speed.value) < .01f && speed.timestamp > 0L && speedAge in 0L..2_000_000_000L
                }.getOrDefault(false)
                handler.post {
                    if (valid(token)) done(parked && properties === manager && car?.isConnected == true &&
                        SystemClock.elapsedRealtime() - requestedAt in 0L..2_000L)
                }
            }
        } catch (_: Exception) { done(false) }
    }

    private fun readSeatPosition() {
        val generation = epoch
        val api = seat ?: return
        if (!has(PleosPermission.CAR_SEATS)) return
        try {
            api.checkSeatBackrestAnglePositionCapabilities({ config, areas -> onMain {
                if (!active || generation != epoch || config.accessType !in listOf(AccessType.READ, AccessType.READ_WRITE)) return@onMain
                val area = areas.keys.firstOrNull { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) } ?: return@onMain
                val bounds = areas[area] ?: return@onMain
                try {
                    api.getCurrentSeatBackrestAnglePosition(area, { position -> onMain {
                        if (active && generation == epoch) emit(state.copy(seatPosition = position?.takeIf { it in bounds.minValue..bounds.maxValue }))
                    } }, { })
                } catch (_: Exception) { /* A disconnected optional seat read must not affect climate. */ }
            } }, { })
        } catch (_: Exception) { /* Optional read; the climate feature stays independent. */ }
    }

    private fun begin(): Long? {
        if (!active || state.busy) return null
        if (!has(PleosPermission.CAR_CLIMATE) || !has(PleosPermission.CONTROL_CAR_CLIMATE) ||
            !has("android.car.permission.CAR_POWERTRAIN") || !has("android.car.permission.CAR_SPEED")) {
            emit(state.copy(status = ComfortStatus.PERMISSION_REQUIRED, canSet = false, canRestore = false)); return null
        }
        operation += 1
        val token = operation
        operationStartedAt = SystemClock.elapsedRealtime()
        emit(state.copy(busy = true, status = ComfortStatus.CHECKING, canSet = false, canRestore = false))
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable { fail(token, ComfortStatus.UNCONFIRMED) }.also { handler.postDelayed(it, TIMEOUT_MS) }
        return token
    }

    private fun valid(token: Long) = active && operation == token && state.busy &&
        SystemClock.elapsedRealtime() - operationStartedAt in 0L..TIMEOUT_MS

    private fun finish(token: Long, next: ComfortState) = onMain {
        if (!active || token != operation || !state.busy) return@onMain
        timeout?.let(handler::removeCallbacks); timeout = null
        emit(next.copy(busy = false))
    }

    private fun fail(token: Long, status: ComfortStatus) = onMain {
        finish(token, state.copy(status = status, canSet = false, canRestore = false, originalCelsius = original))
    }

    private fun stopInternal() {
        active = false; epoch += 1; operation += 1
        timeout?.let(handler::removeCallbacks); timeout = null
        properties = null
        runCatching { car?.disconnect() }; car = null
        runCatching { vehicle?.release() }; vehicle = null; hvac = null; seat = null
        worker?.shutdownNow(); worker = null
        state = state.copy(busy = false, status = ComfortStatus.STOPPED, canSet = false, canRestore = false, seatPosition = null)
        listener = null
    }

    private fun safe() = active && runCatching { isSafeForControl() }.getOrDefault(false)
    private fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun matches(a: Float?, b: Float?) = a != null && b != null && abs(a - b) < .025f
    private fun label(area: SeatArea): String = if (area.values.size == 1) "앞좌석 왼쪽" else "앞좌석 왼쪽 포함 ${area.values.size}석 공조"
    private fun emit(next: ComfortState) { state = next; listener?.invoke(next) }
    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == handler.looper) block() else handler.post(block) }

    private companion object { const val TIMEOUT_MS = 8_000L }
}
