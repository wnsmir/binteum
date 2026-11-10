package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.binteum.app.nearby.*
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic response validation only: no location request, network, UI, or recorded preference. */
class NearbySuggestionsProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        val now = System.currentTimeMillis()
        fun fixture() = JSONObject().put("provider", "OPENSTREETMAP").put("generatedAtMillis", now)
            .put("originTimestampMillis", now).put("returnReserveSeconds", 300)
            .put("places", JSONArray().put(JSONObject().put("id", "node:123").put("name", "Parser fixture")
                .put("address", "").put("url", "https://www.openstreetmap.org/node/123")
                .put("latitude", 37.5).put("longitude", 127.0)
                .put("outboundSeconds", 180).put("returnSeconds", 240).put("staySeconds", 600)
                .put("totalSeconds", 1320).put("fit", "FITS")
                .put("outboundUrl", JSONObject.NULL).put("returnUrl", JSONObject.NULL)))
        fun parse(data: JSONObject) = NearbyResponseParser.parse(data.toString(), now, now)
        fun invalid(block: (JSONObject, JSONObject) -> Unit): Boolean {
            val data = fixture(); block(data, data.getJSONArray("places").getJSONObject(0))
            return parse(data).failure == NearbyFailure.INVALID_RESPONSE
        }
        try {
            val result = parse(fixture())
            verify(result.failure == null && result.provider == NearbyProvider.OPENSTREETMAP && result.places.size == 1 && result.coverage == NearbyCoverage.UNKNOWN)
            val place = result.places.single()
            verify(place.fitFor(1320) == NearbyFit.FITS)
            verify(place.fitFor(1319) == NearbyFit.TOO_LONG)
            verify(place.fitFor(null) == NearbyFit.UNKNOWN)
            verify(place.fitFor(-1) == NearbyFit.UNKNOWN)
            verify(invalid { data, _ -> data.put("originTimestampMillis", now - 1) })
            verify(invalid { data, _ -> data.put("generatedAtMillis", now - 120_001) })
            verify(invalid { data, _ -> data.put("generatedAtMillis", now + 10_001) })
            verify(invalid { data, _ -> data.put("returnReserveSeconds", 299) })
            verify(invalid { _, row -> row.put("totalSeconds", 42) })
            verify(invalid { _, row -> row.put("returnSeconds", JSONObject.NULL) })
            verify(invalid { _, row -> row.put("outboundSeconds", "180") })
            verify(invalid { _, row -> row.put("latitude", 91) })
            verify(invalid { _, row -> row.put("url", "https://www.openstreetmap.org.evil.example/node/123") })
            verify(invalid { _, row -> row.put("url", "https://user@www.openstreetmap.org/node/123") })
            verify(invalid { _, row -> row.put("url", "http://www.openstreetmap.org/node/123") })
            verify(invalid { _, row -> row.put("returnUrl", "javascript:alert(1)") })
            verify(invalid { data, row -> data.getJSONArray("places").put(row) })
            val missingRoute = fixture().apply { getJSONArray("places").getJSONObject(0)
                .put("returnSeconds", JSONObject.NULL).put("totalSeconds", JSONObject.NULL).put("fit", "ROUTE_UNAVAILABLE") }
            verify(parse(missingRoute).places.single().fitFor(99999) == NearbyFit.ROUTE_UNAVAILABLE)
            val empty = fixture().put("places", JSONArray())
            verify(parse(empty).failure == null && parse(empty).places.isEmpty())
            val outside = fixture().put("places", JSONArray()).put("coverageStatus", "OUTSIDE_COVERAGE")
            verify(parse(outside).failure == null && parse(outside).coverage == NearbyCoverage.OUTSIDE_COVERAGE)
            verify(invalid { data, _ -> data.put("coverageStatus", "OUTSIDE_COVERAGE") })
            verify(NearbyResponseParser.failure(429, "") == NearbyFailure.RATE_LIMITED)
            verify(NearbyResponseParser.failure(503, "{\"error\":{\"code\":\"NOT_CONFIGURED\"}}") == NearbyFailure.NOT_CONFIGURED)
            verify(NearbyResponseParser.mapUri("https://www.openstreetmap.org/directions?route=37.5%2C127%3B37.6%2C127") != null)
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS: $checks automatic nearby response/route/privacy checks") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "FAIL after $checks checks: ${error.javaClass.simpleName}") })
        }
    }
}
