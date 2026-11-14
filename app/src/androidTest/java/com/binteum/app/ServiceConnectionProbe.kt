package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.pm.PackageManager
import ai.pleos.playground.vehicle.Vehicle
import com.binteum.app.music.*
import com.binteum.app.nearby.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only capability/handler diagnosis. No seat commands, grants or location transmission. */
class ServiceConnectionProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = ConcurrentHashMap<String, String>()
        val done = CountDownLatch(2)
        var vehicle: Vehicle? = null
        var location: VehicleLocationReader? = null
        runOnMainSync {
            MusicProvider.entries.forEach { provider ->
                val pkg = when (provider) {
                    MusicProvider.SPOTIFY -> "com.spotify.music"
                    MusicProvider.APPLE_MUSIC -> "com.apple.android.music"
                    MusicProvider.YOUTUBE_MUSIC -> "com.google.android.apps.youtube.music"
                }
                report["music.${provider.name}.appInstalled"] = runCatching { targetContext.packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false).toString()
                report["music.${provider.name}.browsers"] = MusicBrowserResolver.packages(targetContext, MusicLinks.home(provider)).size.toString()
            }
            report["permission.speed"] = (targetContext.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED).toString()
            try {
                vehicle = Vehicle(targetContext).also { it.initialize() }
                vehicle!!.getSeat().checkSeatMemoryCapabilities({ config, areas ->
                    report["seat.memory.access"] = config.accessType.name
                    report["seat.memory.zones"] = areas.entries.joinToString(";") { "${it.key.values.joinToString { v -> v.name }}:${it.value.minValue}..${it.value.maxValue}" }
                    done.countDown()
                }, { report["seat.memory.error"] = it.javaClass.simpleName; done.countDown() })
            } catch (e: Exception) { report["seat.memory.error"] = e.javaClass.simpleName; done.countDown() }
            location = VehicleLocationReader(targetContext)
            report["location.permission"] = location!!.hasPermission().toString()
            report["location.supported"] = location!!.isSupported().toString()
            location!!.requestOnce {
                report["location.result"] = it.failure?.name ?: "FRESH_FIX"
                it.fix?.let { fix ->
                    report["location.inKoreaBoundingBox"] = (fix.latitude in 33.0..39.5 && fix.longitude in 124.0..132.0).toString()
                }
                done.countDown()
            }
        }
        report["completedWithinDeadline"] = done.await(15, TimeUnit.SECONDS).toString()
        runOnMainSync { location?.cancel(); runCatching { vehicle?.release() } }
        finish(Activity.RESULT_OK, Bundle().apply {
            report.toSortedMap().forEach { (key, value) -> putString(key, value) }
            putString("vehicleWrites", "0"); putBoolean("coordinatesPrintedOrSent", false)
        })
    }
}
