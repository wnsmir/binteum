package com.binteum.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.PleosPermission
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.binteum.core.VehicleDataSource
import com.binteum.core.VehicleSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pleos Vehicle 2.3.2 integration. The owner starts/stops it with the visible UI.
 *
 * Pleos SOC and charge-switch callbacks do not contain a vehicle measurement timestamp. SOC
 * uses its request START time, never the time of re-emitting a cached snapshot. The AAOS charge
 * property's ON_CHANGE timestamp is kept separately: unchanged state may be hours old even
 * after a successful current read. This does not prove freshness inside either service.
 * A charge switch is the requested
 * charging setting, so only AAOS EV_CHARGE_STATE determines VehicleSnapshot.charging.
 *
 * vehicleAwake means this foreground client's device is interactive. It is not an ignition
 * signal or a promise of monitoring while the driver is away. The only write is an explicitly
 * requested charge-percent limit; charging start/stop and current are never controlled.
 */
class PleosVehicleDataSource(context: Context) : VehicleDataSource {
    private val localizedContext = context.applicationContext.createConfigurationContext(context.resources.configuration)
    override val isSimulation = false

    @Volatile
    var diagnostics: String = localizedContext.getString(com.binteum.app.R.string.diagnostic_00)
        private set

    /** Requested start/stop setting; never treated as proof that the battery is charging. */
    @Volatile
    var chargeSwitchEnabled: Boolean? = null
        private set

    /** Last AAOS state-change time, not the time at which this client read the current value. */
    @Volatile
    var chargeStateChangedAtMillis: Long? = null
        private set

    private val context = localizedContext
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = this.context.getSystemService(PowerManager::class.java)
    private var listener: ((VehicleSnapshot) -> Unit)? = null
    private var vehicle: Vehicle? = null
    private var battery: EvBattery? = null
    private var uxReader: AndroidCarUxReader? = null
    private var generation = 0L
    private var cycle = 0L
    private var screenEpoch = 0L
    private var active = false
    private var receiverRegistered = false
    private var socReadable = false
    private var chargeReadable = false
    private var pollingHalted = false
    private var stateOfCharge: Int? = null
    private var charging: Boolean? = null
    private var chargeTimeRemainingSeconds: Int? = null
    private var vehicleTargetPercent: Int? = null
    private var chargeTimeObservedAtMillis: Long? = null
    private var uxRestricted: Boolean? = null
    private var observedAtMillis = 0L
    private var batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_01)
    private var switchStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_02)
    private var uxStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_03)
    private var chargeStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_04)
    private var chargeDetailsStatus = "AAOS charge estimate unavailable"
    private var cachedChargeTargets: List<Int>? = null
    private var targetCapabilityPending = false
    private var targetWritePending = false
    private var targetWriteEpoch = 0L
    private var etaNotBeforeMillis = 0L

    private fun safeToSetChargeTarget(): Boolean = active && !pollingHalted && vehicle != null && battery != null &&
        uxReader?.isConnected == true && powerManager?.isInteractive == true && uxRestricted == false && charging != null &&
        stateOfCharge?.let { it in 0..100 } == true &&
        System.currentTimeMillis() - observedAtMillis in 0L..30_000L &&
        context.checkSelfPermission(PleosPermission.CAR_ENERGY) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(PleosPermission.CONTROL_CAR_ENERGY) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission("android.car.permission.CAR_ENERGY") == PackageManager.PERMISSION_GRANTED

    private fun supportedWholePercents(values: List<Float>): List<Int> = values.mapNotNull { raw ->
        if (!raw.isFinite() || raw !in 1f..100f) null
        else raw.roundToInt().takeIf { abs(raw - it) < .001f }
    }.distinct().sorted()

    /** Empty means unavailable here, including missing permission or unconfirmed parked state. */
    fun supportedChargeTargets(onComplete: (List<Int>) -> Unit) = onMain {
        uxReader?.refresh()
        if (!safeToSetChargeTarget()) { onComplete(emptyList()); return@onMain }
        cachedChargeTargets?.let { onComplete(it); return@onMain }
        if (targetCapabilityPending) { onComplete(emptyList()); return@onMain }
        val api = battery ?: run { onComplete(emptyList()); return@onMain }
        val session = generation
        targetCapabilityPending = true
        var done = false
        val timeout = Runnable {
            if (active && generation == session && !done) {
                done = true; targetCapabilityPending = false; onComplete(emptyList())
            }
        }
        fun finish(values: List<Int>) = onMain {
            if (!active || generation != session || done) return@onMain
            done = true; targetCapabilityPending = false; handler.removeCallbacks(timeout)
            val result = values.takeIf { battery === api && safeToSetChargeTarget() }.orEmpty()
            if (result.isNotEmpty()) cachedChargeTargets = result
            onComplete(result)
        }
        handler.postDelayed(timeout, READ_TIMEOUT_MS)
        try {
            api.checkEvChargePercentLimitCapabilities(
                { config, values -> finish(if (config.accessType == AccessType.READ_WRITE) supportedWholePercents(values) else emptyList()) },
                { finish(emptyList()) },
            )
        } catch (_: Exception) { finish(emptyList()) }
    }

    /**
     * Explicit UI action only. true means a separate read confirmed the requested target.
     * false includes timeout/unconfirmed application; callers must not claim the old value remains.
     * No automatic retries and no charging switch/current control.
     */
    fun setChargeTarget(percent: Int, onComplete: (Boolean) -> Unit) = onMain {
        uxReader?.refresh()
        if (percent !in 1..100 || !safeToSetChargeTarget() || targetWritePending) {
            onComplete(false); return@onMain
        }
        val api = battery ?: run { onComplete(false); return@onMain }
        val reader = uxReader ?: run { onComplete(false); return@onMain }
        val session = generation
        val screenAtRequest = screenEpoch
        targetWritePending = true
        var done = false
        fun stillValid() = active && generation == session && battery === api && uxReader === reader &&
            screenEpoch == screenAtRequest && safeToSetChargeTarget()
        val timeout = Runnable {
            if (active && generation == session && !done) {
                done = true; targetWritePending = false; onComplete(false)
            }
        }
        fun finish(success: Boolean) = onMain {
            if (!active || generation != session || done) return@onMain
            done = true; targetWritePending = false; handler.removeCallbacks(timeout)
            onComplete(success && stillValid())
        }
        handler.postDelayed(timeout, TARGET_WRITE_TIMEOUT_MS)
        try {
            // Always recheck capability for a write; cached options are for presentation only.
            api.checkEvChargePercentLimitCapabilities({ config, values -> onMain {
                if (done) return@onMain
                val supported = supportedWholePercents(values)
                if (!stillValid() || config.accessType != AccessType.READ_WRITE || percent !in supported) {
                    finish(false); return@onMain
                }
                cachedChargeTargets = supported
                try {
                    api.setEvChargePercentLimit(percent.toFloat(), { _ -> onMain {
                        if (done) return@onMain
                        if (!stillValid()) { finish(false); return@onMain }
                        try {
                            reader.readChargeTarget { actual ->
                                if (done || !stillValid()) { finish(false); return@readChargeTarget }
                                if (actual == percent) {
                                    targetWriteEpoch += 1
                                    etaNotBeforeMillis = System.currentTimeMillis()
                                    vehicleTargetPercent = actual
                                    // The previous ETA was measured against the previous target.
                                    chargeTimeRemainingSeconds = null; chargeTimeObservedAtMillis = null
                                    publish()
                                }
                                finish(actual == percent)
                            }
                        } catch (_: Exception) { finish(false) }
                    } }, { finish(false) })
                } catch (_: Exception) { finish(false) }
            } }, { finish(false) })
        } catch (_: Exception) { finish(false) }
    }

    private fun clearChargeDetails() {
        chargeTimeRemainingSeconds = null
        vehicleTargetPercent = null
        chargeTimeObservedAtMillis = null
        chargeDetailsStatus = "AAOS charge estimate unavailable"
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active) return
            screenEpoch += 1
            // Do not carry pre-sleep data across resume. The next poll obtains a new pair.
            stateOfCharge = null
            charging = null
            chargeSwitchEnabled = null
            chargeStateChangedAtMillis = null
            clearChargeDetails()
            observedAtMillis = 0L
            publish()
        }
    }

    override fun start(onSnapshot: (VehicleSnapshot) -> Unit) = onMain {
        stopOnMain()
        active = true
        listener = onSnapshot
        val session = generation
        batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_05)
        uxStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_03)
        publish()

        try {
            uxReader = AndroidCarUxReader(context, handler) { value, status ->
                onMain {
                    if (active && generation == session) {
                        uxRestricted = value
                        uxStatus = status
                        publish()
                    }
                }
            }.also { it.start() }
        } catch (_: LinkageError) {
            uxStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_06)
        } catch (_: Exception) {
            uxStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_07)
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // isInteractive is checked at every publish and before each read as well.
        }
        if (context.checkSelfPermission(PleosPermission.CAR_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_08)
            publish()
            return@onMain
        }
        try {
            vehicle = Vehicle(context).also { it.initialize() }
            battery = vehicle?.getEvBattery()
            checkCapabilities(session)
        } catch (_: Exception) {
            batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_09)
            publish()
        }
    }

    private fun checkCapabilities(session: Long) {
        val api = battery ?: return
        var remaining = 2
        var finished = false
        val timeout = Runnable {
            if (active && generation == session && !finished) {
                finished = true
                pollingHalted = true
                batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_10)
                publish()
            }
        }
        fun complete(isSoc: Boolean, access: AccessType?, failed: Boolean) = onMain {
            if (!active || generation != session || finished) return@onMain
            val readable = access == AccessType.READ || access == AccessType.READ_WRITE
            if (isSoc) socReadable = readable else chargeReadable = readable
            if (failed) batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_11)
            remaining -= 1
            if (remaining == 0) {
                finished = true
                handler.removeCallbacks(timeout)
                poll(session)
            }
        }
        handler.postDelayed(timeout, CAPABILITY_TIMEOUT_MS)
        try {
            api.checkEvBatteryStateOfChargeCapabilities(
                { complete(true, it.accessType, false) }, { complete(true, null, true) },
            )
        } catch (_: Exception) {
            complete(true, null, true)
        }
        try {
            api.checkEvChargeSwitchCapabilities(
                { complete(false, it.accessType, false) }, { complete(false, null, true) },
            )
        } catch (_: Exception) {
            complete(false, null, true)
        }
    }

    private fun poll(session: Long) {
        if (!active || generation != session || pollingHalted) return
        uxReader?.refresh()
        if (powerManager?.isInteractive != true) {
            stateOfCharge = null
            charging = null
            clearChargeDetails()
            observedAtMillis = 0L
            publish()
            handler.postDelayed({ poll(session) }, POLL_INTERVAL_MS)
            return
        }
        val api = battery ?: return
        val request = ++cycle
        val screenAtRequest = screenEpoch
        val startedAt = System.currentTimeMillis()
        readOptionalChargeDetails(session, request, screenAtRequest)
        var pending = (if (socReadable) 1 else 0) + (if (chargeReadable) 1 else 0) + 1
        var soc: Int? = null
        var switch: Boolean? = null
        var charge: Boolean? = null
        var chargeChangedAt = 0L
        var socFailed = false
        var switchFailed = false
        var finished = false
        val timeout = Runnable {
            if (active && generation == session && cycle == request && !finished) {
                finished = true
                // The SDK queues requests while disconnected. Do not grow that queue forever.
                pollingHalted = true
                stateOfCharge = null
                charging = null
                chargeSwitchEnabled = null
                chargeStateChangedAtMillis = null
                clearChargeDetails()
                observedAtMillis = 0L
                batteryStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_12)
                switchStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_02)
                chargeStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_13)
                publish()
            }
        }
        fun complete(kind: Int, socValue: Int?, chargeValue: Boolean?, error: Boolean) = onMain {
            if (!active || generation != session || cycle != request || finished) return@onMain
            if (kind == 0) soc = socValue?.takeIf { it in 0..100 }
            if (kind == 1) switch = chargeValue
            if (kind == 0) socFailed = error
            if (kind == 1) switchFailed = error
            pending -= 1
            if (pending == 0) {
                finished = true
                handler.removeCallbacks(timeout)
                // A request which spans screen-off must not revive an earlier observation.
                val awake = powerManager?.isInteractive == true && screenEpoch == screenAtRequest
                stateOfCharge = if (awake) soc else null
                charging = if (awake) charge else null
                chargeSwitchEnabled = if (awake) switch else null
                chargeStateChangedAtMillis = chargeChangedAt.takeIf { awake && it > 0L }
                observedAtMillis = if (awake) startedAt else 0L
                batteryStatus = when {
                    socFailed -> localizedContext.getString(com.binteum.app.R.string.diagnostic_14)
                    soc == null -> localizedContext.getString(com.binteum.app.R.string.diagnostic_15)
                    else -> localizedContext.getString(com.binteum.app.R.string.diagnostic_16)
                }
                switchStatus = when {
                    switchFailed -> localizedContext.getString(com.binteum.app.R.string.diagnostic_17)
                    switch == null -> localizedContext.getString(com.binteum.app.R.string.diagnostic_18)
                    else -> localizedContext.getString(com.binteum.app.R.string.charge_switch_detail, switch.toString())
                }
                publish()
                handler.postDelayed({ poll(session) }, POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(timeout, READ_TIMEOUT_MS)
        if (socReadable) {
            try {
                api.getEvBatteryStateOfCharge(
                    { complete(0, it, null, false) }, { complete(0, null, null, true) },
                )
            } catch (_: Exception) {
                complete(0, null, null, true)
            }
        }
        if (chargeReadable) {
            try {
                api.getEvChargeSwitchState(
                    { complete(1, null, it, false) }, { complete(1, null, null, true) },
                )
            } catch (_: Exception) {
                complete(1, null, null, true)
            }
        }
        val reader = uxReader
        if (reader == null) {
            chargeStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_19)
            complete(2, null, null, false)
        } else {
            try {
                reader.readChargeState { result ->
                    if (!active || generation != session || cycle != request || finished) return@readChargeState
                    charge = result.charging
                    chargeChangedAt = result.changedAtMillis
                    chargeStatus = result.status
                    complete(2, null, null, false)
                }
            } catch (_: Exception) {
                chargeStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_20)
                complete(2, null, null, true)
            }
        }
    }

    private fun readOptionalChargeDetails(session: Long, request: Long, screenAtRequest: Long) {
        val reader = uxReader ?: run { clearChargeDetails(); return }
        val targetEpochAtRequest = targetWriteEpoch
        var finished = false
        val timeout = Runnable {
            if (active && generation == session && cycle == request && !finished && targetWriteEpoch == targetEpochAtRequest) {
                finished = true
                clearChargeDetails()
                publish()
            }
        }
        handler.postDelayed(timeout, READ_TIMEOUT_MS)
        try {
            reader.readChargeDetails { result ->
                if (!active || generation != session || cycle != request || finished || targetWriteEpoch != targetEpochAtRequest) return@readChargeDetails
                finished = true
                handler.removeCallbacks(timeout)
                if (powerManager?.isInteractive == true && screenEpoch == screenAtRequest) {
                    chargeTimeRemainingSeconds = result.remainingSeconds.takeIf {
                        result.sampledAtMillis != null && result.sampledAtMillis >= etaNotBeforeMillis
                    }
                    vehicleTargetPercent = result.targetPercent
                    chargeTimeObservedAtMillis = result.sampledAtMillis.takeIf { chargeTimeRemainingSeconds != null }
                    chargeDetailsStatus = result.status
                } else clearChargeDetails()
                publish()
            }
        } catch (_: Exception) {
            finished = true
            handler.removeCallbacks(timeout)
            clearChargeDetails()
        }
    }

    private fun publish() {
        if (!active) return
        diagnostics = "$batteryStatus / $switchStatus / $chargeStatus / $chargeDetailsStatus / $uxStatus"
        val estimateFresh = chargeTimeObservedAtMillis?.let { System.currentTimeMillis() - it in 0L..30_000L } == true
        val chargingWithEstimate = charging == true && estimateFresh && powerManager?.isInteractive == true
        listener?.invoke(VehicleSnapshot(stateOfCharge, charging, uxRestricted,
            powerManager?.isInteractive == true, observedAtMillis,
            chargeTimeRemainingSeconds.takeIf { chargingWithEstimate }, vehicleTargetPercent,
            chargeTimeObservedAtMillis.takeIf { chargingWithEstimate }))
    }

    override fun stop() = onMain { stopOnMain() }

    private fun stopOnMain() {
        active = false
        generation += 1
        cycle += 1
        listener = null
        handler.removeCallbacksAndMessages(null)
        uxReader?.stop()
        uxReader = null
        if (receiverRegistered) runCatching { context.unregisterReceiver(screenReceiver) }
        receiverRegistered = false
        runCatching { vehicle?.release() }
        vehicle = null
        battery = null
        socReadable = false
        chargeReadable = false
        pollingHalted = false
        cachedChargeTargets = null
        targetCapabilityPending = false
        targetWritePending = false
        targetWriteEpoch += 1
        etaNotBeforeMillis = 0L
        stateOfCharge = null
        charging = null
        chargeSwitchEnabled = null
        chargeStateChangedAtMillis = null
        clearChargeDetails()
        switchStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_02)
        chargeStatus = localizedContext.getString(com.binteum.app.R.string.diagnostic_04)
        uxRestricted = null
        observedAtMillis = 0L
        diagnostics = localizedContext.getString(com.binteum.app.R.string.diagnostic_21)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val READ_TIMEOUT_MS = 3_000L
        const val CAPABILITY_TIMEOUT_MS = 20_000L
        const val TARGET_WRITE_TIMEOUT_MS = 8_000L
    }
}
