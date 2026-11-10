package com.binteum.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.binteum.app.nearby.*

/** Headless boundary checks. Does not fetch location, open UI, or send any request. */
class NearbyMapProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        try {
            val now = System.currentTimeMillis()
            val fix = VehicleLocationFix(37.5, 127.0, now, 1f)
            verify(fix.isFresh(now))
            verify(!fix.copy(timestampMillis = now - VehicleLocationFix.MAX_AGE_MILLIS - 1).isFresh(now))
            verify(!fix.copy(timestampMillis = now + 1).isFresh(now))
            verify(!fix.copy(latitude = Double.NaN).isFresh(now))
            verify(!fix.copy(confidence = Float.NaN).isFresh(now))
            verify(NearbyMapLinks.searchLink(MapProvider.KAKAO, "", NearbyKind.CAFE) == null)
            verify(NearbyMapLinks.searchLink(MapProvider.NAVER, "", NearbyKind.CAFE, fix) == null)
            val keyword = NearbyMapLinks.searchLink(MapProvider.KAKAO, "강남 / a?b&c", NearbyKind.FOOD)!!
            verify(keyword.host == "map.kakao.com" && keyword.query == null && keyword.pathSegments.size == 3)
            val byLocation = NearbyMapLinks.searchLink(MapProvider.KAKAO, "", NearbyKind.PARK, fix)!!
            verify(byLocation.getQueryParameter("p") == "37.5,127.0")
            val byArea = NearbyMapLinks.searchLink(MapProvider.KAKAO, "강남역", NearbyKind.FOOD, fix)!!
            verify(byArea.getQueryParameter("p") == null && !byArea.toString().contains("37.5"))
            val naver = NearbyMapLinks.searchLink(MapProvider.NAVER, "서울역", NearbyKind.CONVENIENCE, fix)!!
            verify(naver.getQueryParameter("query") == "서울역 편의점" && !naver.toString().contains("37.5"))
            verify(!NearbyMapLauncher(targetContext) { false }.open(byLocation))
            verify(NearbyMapLinks.vehicleLink(fix.copy(timestampMillis = now - 121_000)) == null)
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "PASS: $checks nearby URL/privacy/freshness checks") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("result", "FAIL after $checks checks: ${error.javaClass.simpleName}") })
        }
    }
}
