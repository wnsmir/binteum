package com.binteum.app.nearby

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

enum class LocationFailure { UNSUPPORTED, PERMISSION_REQUIRED, UNAVAILABLE, TIMEOUT }
data class VehicleLocationResult(val fix: VehicleLocationFix? = null, val failure: LocationFailure? = null)

/** Opt-in, foreground-only, bounded one-shot capture. No background location or disk history. */
class VehicleLocationReader(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var timeout: Runnable? = null
    private var generation = 0L

    fun isSupported(): Boolean = runCatching { FusedLocation(appContext).isSupported() }.getOrDefault(false)
    fun hasPermission(): Boolean = appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Invoke only after the user's location button/permission result, while the host is visible. */
    fun requestOnce(onResult: (VehicleLocationResult) -> Unit) {
        cancel()
        if (!isSupported()) { onResult(VehicleLocationResult(failure = LocationFailure.UNSUPPORTED)); return }
        if (!hasPermission()) { onResult(VehicleLocationResult(failure = LocationFailure.PERMISSION_REQUIRED)); return }
        val requestId = generation
        val client = FusedLocation(appContext)
        sdk = client
        fun finish(result: VehicleLocationResult) {
            main.post {
                if (requestId != generation) return@post
                cancel()
                onResult(result)
            }
        }
        val callback = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                val fix = location?.let { VehicleLocationFix(it.latitude, it.longitude, it.timestampMillis, it.confidence) }
                // Null or stale cached positions are not refreshed merely by receiving them now.
                if (fix?.isFresh() == true) finish(VehicleLocationResult(fix = fix))
            }
            override fun onError(exception: Exception) {
                finish(VehicleLocationResult(failure = if (hasPermission()) LocationFailure.UNAVAILABLE else LocationFailure.PERMISSION_REQUIRED))
            }
        }
        listener = callback
        timeout = Runnable { finish(VehicleLocationResult(failure = LocationFailure.TIMEOUT)) }.also {
            main.postDelayed(it, 12_000L)
        }
        try {
            client.initialize()
            client.getLastKnownLocation(callback)
            client.registerFusedLocationCallback(callback)
        } catch (_: Exception) {
            finish(VehicleLocationResult(failure = if (hasPermission()) LocationFailure.UNAVAILABLE else LocationFailure.PERMISSION_REQUIRED))
        }
    }

    /** Call onStop and before a replacement request; stale callbacks cannot repopulate the UI. */
    fun cancel() {
        generation++
        timeout?.let(main::removeCallbacks)
        timeout = null
        val current = sdk
        listener?.let { callback -> runCatching { current?.unregisterFusedLocationCallback(callback) } }
        runCatching { current?.release() }
        sdk = null
        listener = null
    }

    companion object { const val PERMISSION = "pleos.car.permission.FUSED_LOCATION" }
}
