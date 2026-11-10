package com.binteum.app.nearby

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

enum class MapProvider { KAKAO, NAVER }
enum class NearbyKind(val keyword: String) {
    CAFE("카페"), FOOD("음식점"), CONVENIENCE("편의점"), PARK("공원")
}

/** A vehicle fix, never the user's phone position. Kept only in memory. */
data class VehicleLocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val confidence: Float,
) {
    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            confidence.isFinite() && confidence >= 0f && timestampMillis > 0 &&
            timestampMillis <= nowMillis && nowMillis - timestampMillis <= MAX_AGE_MILLIS

    companion object { const val MAX_AGE_MILLIS = 120_000L }
}

/** Public provider links only. No credentials, scraping, invented routes, or provider API calls. */
object NearbyMapLinks {
    fun searchLink(
        provider: MapProvider,
        area: String,
        kind: NearbyKind,
        location: VehicleLocationFix? = null,
    ): Uri? {
        val cleanArea = area.trim().filterNot { it.isISOControl() }.take(80)
        val fix = location?.takeIf { it.isFresh() }
        // Naver's documented /search contract has no coordinate-centre parameter.
        // A named area prevents accidentally searching around the phone's own location.
        if (provider == MapProvider.NAVER && cleanArea.isBlank()) return null
        if (cleanArea.isBlank() && fix == null) return null
        val query = listOf(cleanArea, kind.keyword).filter { it.isNotBlank() }.joinToString(" ")
        return when (provider) {
            MapProvider.KAKAO -> if (fix != null && cleanArea.isBlank()) {
                Uri.parse("https://m.map.kakao.com/scheme/search").buildUpon()
                    .appendQueryParameter("q", kind.keyword)
                    .appendQueryParameter("p", "${fix.latitude},${fix.longitude}").build()
            } else {
                Uri.parse("https://map.kakao.com/link/search").buildUpon().appendPath(query).build()
            }
            MapProvider.NAVER -> Uri.parse("nmap://search").buildUpon()
                .appendQueryParameter("query", query)
                .appendQueryParameter("appname", "com.binteum.app").build()
        }
    }

    /** A portable web pin to help the user remember the charging vehicle's position. */
    fun vehicleLink(location: VehicleLocationFix): Uri? = location.takeIf { it.isFresh() }?.let {
        Uri.parse("https://map.kakao.com/link/map").buildUpon()
            .appendPath("충전 차량,${it.latitude},${it.longitude}").build()
    }
}

class NearbyMapLauncher(private val context: Context, private val canOpen: () -> Boolean) {
    /** false means no launch occurred. The caller can continue displaying a local phone QR. */
    fun open(uri: Uri): Boolean {
        if (!canOpen() || !isProviderLink(uri)) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            if (uri.scheme == "nmap") intent.setPackage("com.nhn.android.nmap")
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isProviderLink(uri: Uri): Boolean = when (uri.scheme) {
        "https" -> NearbyResponseParser.mapUri(uri.toString()) != null
        "nmap" -> uri.host == "search" && uri.getQueryParameter("appname") == "com.binteum.app"
        else -> false
    }
}
