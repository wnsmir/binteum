package com.binteum.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.app.Activity
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Bundle
import com.binteum.app.nearby.VehicleLocationFix
import com.binteum.app.nearby.VehicleLocationReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only, explicit location diagnostic. No grants, settings changes, fake fixes, or UI.
 * Never prints coordinates, raw timestamps, provider payloads, or error messages.
 * RESULT_OK means the diagnostic ran; location availability is reported separately.
 */
class LocationCapabilityProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val values = ConcurrentHashMap<String, String>()
        val alive = AtomicBoolean(true)
        val gotFresh = CountDownLatch(1)
        val liveCount = AtomicInteger()
        var sdk: FusedLocation? = null
        var subscription: FusedLocationListener? = null
        val permission = targetContext.checkSelfPermission(VehicleLocationReader.PERMISSION) == PackageManager.PERMISSION_GRANTED
        values["permission.fusedLocation"] = permission.toString()
        values["diagnostic.coordinatesPrinted"] = "false"
        values["diagnostic.vehicleWrites"] = "0"
        fun record(prefix: String, location: LocationData?) {
            if (!alive.get()) return
            values["$prefix.received"] = "true"
            values["$prefix.nonNull"] = (location != null).toString()
            if (location == null) return
            val fix = VehicleLocationFix(location.latitude, location.longitude, location.timestampMillis, location.confidence)
            val validCoordinates = location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0
            val age = System.currentTimeMillis() - location.timestampMillis
            values["$prefix.coordinatesValid"] = validCoordinates.toString()
            values["$prefix.confidenceFiniteNonnegative"] = (location.confidence.isFinite() && location.confidence >= 0).toString()
            values["$prefix.timestampAgeBucket"] = when {
                location.timestampMillis <= 0 -> "missing"
                age < 0 -> "future"
                age <= 120_000 -> "within_120_seconds"
                else -> "older_than_120_seconds"
            }
            values["$prefix.fresh"] = fix.isFresh().toString()
            if (fix.isFresh()) gotFresh.countDown()
        }
        try {
            var supported = false
            runOnMainSync {
                val client = FusedLocation(targetContext)
                sdk = client
                supported = runCatching { client.isSupported() }.getOrDefault(false)
                values["service.supported"] = supported.toString()
                if (!supported || !permission) return@runOnMainSync
                client.initialize()
                val live = object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) {
                        values["live.callbackCount"] = liveCount.incrementAndGet().toString()
                        record("live", location)
                    }
                    override fun onError(exception: Exception) { if (alive.get()) values["live.errorClass"] = exception.javaClass.simpleName }
                }
                subscription = live
                try { client.registerFusedLocationCallback(live) }
                catch (error: Exception) { values["live.errorClass"] = error.javaClass.simpleName }
                try {
                    client.getLastKnownLocation(object : FusedLocationListener {
                        override fun onChanged(location: LocationData?) = record("cached", location)
                        override fun onError(exception: Exception) { if (alive.get()) values["cached.errorClass"] = exception.javaClass.simpleName }
                    })
                } catch (error: Exception) { values["cached.errorClass"] = error.javaClass.simpleName }
            }
            if (supported && permission) values["diagnostic.freshFixWithinDeadline"] = gotFresh.await(13, TimeUnit.SECONDS).toString()
            else values["diagnostic.requestSkipped"] = if (!supported) "unsupported" else "permission_required"
            alive.set(false)
            runOnMainSync {
                subscription?.let { runCatching { sdk?.unregisterFusedLocationCallback(it) } }
                runCatching { sdk?.release() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { values.toSortedMap().forEach { (key, value) -> putString(key, value) } })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("diagnostic.errorClass", error.javaClass.simpleName) })
        } finally {
            alive.set(false)
            runOnMainSync { runCatching { sdk?.release() } }
        }
    }
}
