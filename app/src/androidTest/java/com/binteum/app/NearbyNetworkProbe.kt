package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.binteum.app.nearby.NearbyFailure
import com.binteum.app.nearby.NearbyFit
import com.binteum.app.nearby.NearbyKind
import com.binteum.app.nearby.NearbyLookupResult
import com.binteum.app.nearby.NearbySuggestionsClient
import com.binteum.app.nearby.VehicleLocationFix
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One real transport request using a PUBLIC_TEST_LOCATION at Gangnam Station.
 * This fixture is never obtained from or presented as the current device/vehicle location.
 * No UI, location access, preferences, vehicle writes, or automatic retries.
 */
class NearbyNetworkProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = AtomicReference<NearbyLookupResult>()
        val client = AtomicReference<NearbySuggestionsClient>()
        val complete = CountDownLatch(1)
        val report = Bundle().apply { putString("source", "PUBLIC_TEST_LOCATION") }
        var resultCode = Activity.RESULT_CANCELED
        try {
            runOnMainSync {
                val connection = NearbySuggestionsClient()
                client.set(connection)
                connection.lookup(
                    VehicleLocationFix(37.4979, 127.0276, System.currentTimeMillis(), 1f),
                    NearbyKind.CAFE, TEST_AVAILABLE_SECONDS,
                ) { response -> result.set(response); complete.countDown() }
            }
            val received = complete.await(45, TimeUnit.SECONDS)
            val response = result.get()
            if (!received || response == null) {
                report.putString("failure", NearbyFailure.PROVIDER_TIMEOUT.name)
                report.putString("result", "FAIL: automatic nearby transport timed out")
            } else {
                report.putString("provider", response.provider?.name ?: "UNKNOWN")
                report.putString("coverage", response.coverage.name)
                report.putInt("placeCount", response.places.size)
                report.putInt("verifiedRoundTrips", response.places.count { it.outboundSeconds != null && it.returnSeconds != null })
                NearbyFit.entries.forEach { fit ->
                    report.putInt("fit.${fit.name}", response.places.count { it.fitFor(TEST_AVAILABLE_SECONDS) == fit })
                }
                if (response.failure == null && response.places.isNotEmpty()) {
                    resultCode = Activity.RESULT_OK
                    report.putString("result", "PASS: real Android nearby transport using public test location")
                } else {
                    report.putString("failure", response.failure?.name ?: "EMPTY_RESULT")
                    report.putString("result", "FAIL: no verified nonempty nearby response")
                }
            }
        } catch (error: Exception) {
            report.putString("failure", error.javaClass.simpleName)
            report.putString("result", "FAIL: automatic nearby transport probe")
        } finally {
            runOnMainSync { client.get()?.close() }
        }
        finish(resultCode, report)
    }

    private companion object { const val TEST_AVAILABLE_SECONDS = 1_800 }
}
