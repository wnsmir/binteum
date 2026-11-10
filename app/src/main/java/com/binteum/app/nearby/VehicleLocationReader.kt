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

/**
 * Foreground, bounded one-shot vehicle capture after an explicit nearby-category choice.
 * Cached and live requests are independent: an empty/failed cache must not cancel a live fix.
 * No background location, host-computer location, or disk history.
 */
class VehicleLocationReader(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var timeout: Runnable? = null
    private var generation = 0L

    fun isSupported(): Boolean = runCatching { FusedLocation(appContext).isSupported() }.getOrDefault(false)
    fun hasPermission(): Boolean = appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Invoke after a nearby-category tap or its permission result, while the host is visible. */
    fun requestOnce(onResult: (VehicleLocationResult) -> Unit) {
        if (Looper.myLooper() != main.looper) { main.post { requestOnce(onResult) }; return }
        cancel()
        if (!isSupported()) { onResult(VehicleLocationResult(failure = LocationFailure.UNSUPPORTED)); return }
        if (!hasPermission()) { onResult(VehicleLocationResult(failure = LocationFailure.PERMISSION_REQUIRED)); return }
        val requestId = generation
        val client = FusedLocation(appContext)
        sdk = client
        var encounteredServiceError = false
        fun finish(result: VehicleLocationResult) {
            main.post {
                if (requestId != generation) return@post
                cancel()
                onResult(if (result.fix != null && !hasPermission())
                    VehicleLocationResult(failure = LocationFailure.PERMISSION_REQUIRED) else result)
            }
        }
        fun handleLocation(location: LocationData?) {
            main.post {
                if (requestId != generation) return@post
                val fix = location?.let { VehicleLocationFix(it.latitude, it.longitude, it.timestampMillis, it.confidence) }
                // Null or stale cached positions are not refreshed merely by receiving them now.
                if (fix?.isFresh() == true) finish(VehicleLocationResult(fix = fix))
            }
        }
        fun handleError(error: Exception) {
            main.post {
                if (requestId != generation) return@post
                if (error is SecurityException || !hasPermission()) {
                    finish(VehicleLocationResult(failure = LocationFailure.PERMISSION_REQUIRED))
                } else {
                    // The SDK can reconnect and deliver a live fix after a failed cached read.
                    // Keep the subscription alive until a valid fix or the bounded deadline.
                    encounteredServiceError = true
                }
            }
        }
        fun callback() = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) = handleLocation(location)
            override fun onError(exception: Exception) = handleError(exception)
        }
        val updates = callback()
        listener = updates
        timeout = Runnable { finish(VehicleLocationResult(failure =
            if (encounteredServiceError) LocationFailure.UNAVAILABLE else LocationFailure.TIMEOUT)) }.also {
            main.postDelayed(it, 12_000L)
        }
        try {
            client.initialize()
        } catch (error: Exception) {
            finish(VehicleLocationResult(failure = if (hasPermission()) LocationFailure.UNAVAILABLE else LocationFailure.PERMISSION_REQUIRED))
            return
        } catch (_: LinkageError) {
            finish(VehicleLocationResult(failure = LocationFailure.UNSUPPORTED)); return
        }
        // Subscribe first so the cache response cannot create a gap before live observation.
        try { client.registerFusedLocationCallback(updates) } catch (error: Exception) { handleError(error) }
        try { client.getLastKnownLocation(callback()) } catch (error: Exception) { handleError(error) }
    }

    /** Call onStop and before a replacement request; stale callbacks cannot repopulate the UI. */
    fun cancel() {
        if (Looper.myLooper() != main.looper) { main.post { cancel() }; return }
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
