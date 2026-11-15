package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.binteum.app.nearby.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Live SDK -> local BFF. Defaults to the public test point; current-location mode requires explicit opt-in. */
class NearbySdkIntegrationProbe : Instrumentation() {
    private var useCurrentLocation = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        useCurrentLocation = arguments?.getString("useCurrentEmulatorLocation") == "true"
        start()
    }
    override fun onStart() {
        val report = Bundle()
        val done = CountDownLatch(1)
        var reader: VehicleLocationReader? = null
        var client: NearbySuggestionsClient? = null
        val outcome = AtomicReference<NearbyLookupResult>()
        runOnMainSync {
            reader = VehicleLocationReader(targetContext)
            client = NearbySuggestionsClient()
            report.putBoolean("sdkSupported", reader!!.isSupported())
            report.putBoolean("permissionGranted", reader!!.hasPermission())
            reader!!.requestOnce { result ->
                val fix = result.fix
                report.putString("locationResult", result.failure?.name ?: "FRESH_SDK_FIX")
                if (fix == null) done.countDown()
                else if (!useCurrentLocation && (fix.latitude !in 37.49..37.51 || fix.longitude !in 127.02..127.04)) {
                    report.putString("failure", "NOT_AT_PUBLIC_TEST_POINT"); done.countDown()
                } else client!!.lookup(fix, NearbyKind.CAFE, 1800) {
                    outcome.set(it); done.countDown()
                }
            }
        }
        val finished = done.await(55, TimeUnit.SECONDS)
        val result = outcome.get()
        report.putString("fixture", if (useCurrentLocation) "CURRENT_EMULATOR_GPS; not changed; coordinates not printed"
            else "EMULATOR_GPS_PUBLIC_GANGNAM_TEST_POINT; SDK fix used, not injected into client")
        report.putBoolean("completed", finished)
        report.putInt("placeCount", result?.places?.size ?: 0)
        report.putInt("roundTrips", result?.places?.count { it.outboundSeconds != null && it.returnSeconds != null } ?: 0)
        report.putString("provider", result?.provider?.name ?: "NONE")
        report.putString("coverage", result?.coverage?.name ?: "UNKNOWN")
        result?.failure?.let { report.putString("failure", it.name) }
        val ok = finished && result != null && result.failure == null && (useCurrentLocation || result.places.isNotEmpty())
        runOnMainSync { reader?.cancel(); client?.close() }
        report.putString("result", when {
            !ok -> "FAIL: location or nearby lookup"
            result!!.places.isEmpty() -> "PASS: SDK and nearby response; NO_PLACE_RECOMMENDATIONS"
            else -> "PASS: real SDK to nearby search with emulator GPS"
        })
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
