package com.binteum.app.nearby

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.binteum.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

enum class NearbyProvider { KAKAO, OPENSTREETMAP }
enum class NearbyCoverage { UNKNOWN, WITHIN_COVERAGE, OUTSIDE_COVERAGE }
enum class NearbyFit { FITS, TOO_LONG, UNKNOWN, ROUTE_UNAVAILABLE }
enum class NearbyFailure {
    NOT_CONFIGURED, LOCATION_STALE, INVALID_REQUEST, RATE_LIMITED,
    PROVIDER_AUTH, PROVIDER_UNAVAILABLE, PROVIDER_TIMEOUT, INVALID_RESPONSE, NETWORK_ERROR,
}

data class NearbyPlace(
    val id: String,
    val name: String,
    val address: String,
    val url: Uri,
    val latitude: Double,
    val longitude: Double,
    val outboundSeconds: Int?,
    val returnSeconds: Int?,
    val staySeconds: Int,
    val totalSeconds: Int?,
    val fit: NearbyFit,
    val outboundUrl: Uri?,
    val returnUrl: Uri?,
    val returnReserveSeconds: Int,
) {
    /** Always recompute against the live charging window before presenting or opening a plan. */
    fun fitFor(availableSeconds: Int?): NearbyFit = when {
        outboundSeconds == null || returnSeconds == null || totalSeconds == null -> NearbyFit.ROUTE_UNAVAILABLE
        availableSeconds == null || availableSeconds < 0 -> NearbyFit.UNKNOWN
        totalSeconds <= availableSeconds -> NearbyFit.FITS
        else -> NearbyFit.TOO_LONG
    }
}

data class NearbyLookupResult(
    val places: List<NearbyPlace> = emptyList(),
    val failure: NearbyFailure? = null,
    val generatedAtMillis: Long? = null,
    val originTimestampMillis: Long? = null,
    val provider: NearbyProvider? = null,
    val attribution: String = "",
    val coverage: NearbyCoverage = NearbyCoverage.UNKNOWN,
)

/** One explicit nearby search. No position/result history, persistent cache, analytics, or credentials. */
class NearbySuggestionsClient(private val baseUrl: String = BuildConfig.NEARBY_API_BASE_URL) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0L
    private var task: Future<*>? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false

    fun lookup(fix: VehicleLocationFix, kind: NearbyKind, availableSeconds: Int?, onResult: (NearbyLookupResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Nearby lookups must start on the main thread" }
        cancel()
        if (closed) return
        if (!fix.isFresh()) { onResult(NearbyLookupResult(failure = NearbyFailure.LOCATION_STALE)); return }
        val endpoint = endpoint(baseUrl) ?: run { onResult(NearbyLookupResult(failure = NearbyFailure.NOT_CONFIGURED)); return }
        val requestId = generation
        val request = JSONObject().put("lat", fix.latitude).put("lon", fix.longitude)
            .put("category", kind.name).put("originTimestampMillis", fix.timestampMillis)
            .put("availableSeconds", availableSeconds?.coerceIn(0, 86_400) ?: JSONObject.NULL).toString()
        task = worker.submit {
            if (closed || generation != requestId || Thread.currentThread().isInterrupted) return@submit
            val result = try {
                val current = URL(endpoint).openConnection() as HttpURLConnection
                connection = current
                try {
                    current.requestMethod = "POST"
                    current.instanceFollowRedirects = false
                    current.connectTimeout = 8_000
                    current.readTimeout = 35_000
                    current.useCaches = false
                    current.doOutput = true
                    current.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    current.setRequestProperty("Accept", "application/json")
                    current.setRequestProperty("Cache-Control", "no-store")
                    val bytes = request.toByteArray(Charsets.UTF_8)
                    current.setFixedLengthStreamingMode(bytes.size)
                    if (closed || generation != requestId || Thread.currentThread().isInterrupted) return@submit
                    current.outputStream.use { it.write(bytes) }
                    val status = current.responseCode
                    val stream = if (status in 200..299) current.inputStream else current.errorStream
                    val body = stream?.use { input ->
                        val bytesOut = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (bytesOut.size() + read > MAX_RESPONSE_BYTES) throw IllegalArgumentException("Oversized nearby response")
                            bytesOut.write(buffer, 0, read)
                        }
                        bytesOut.toString(Charsets.UTF_8.name())
                    }.orEmpty()
                    if (status == 200) NearbyResponseParser.parse(body, fix.timestampMillis, System.currentTimeMillis())
                    else NearbyLookupResult(failure = NearbyResponseParser.failure(status, body))
                } finally {
                    current.disconnect()
                    if (connection === current) connection = null
                }
            } catch (_: SocketTimeoutException) {
                NearbyLookupResult(failure = NearbyFailure.PROVIDER_TIMEOUT)
            } catch (_: IllegalArgumentException) {
                NearbyLookupResult(failure = NearbyFailure.INVALID_RESPONSE)
            } catch (_: Exception) {
                NearbyLookupResult(failure = NearbyFailure.NETWORK_ERROR)
            }
            main.post { if (!closed && generation == requestId) { task = null; onResult(result) } }
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Nearby cancellation must run on the main thread" }
        generation++
        task?.cancel(true); task = null
        connection?.disconnect(); connection = null
    }

    fun close() { cancel(); closed = true; worker.shutdownNow() }

    private fun endpoint(value: String): String? = runCatching {
        if (value.isBlank()) return null
        val uri = URI(value)
        val localDebug = BuildConfig.DEBUG && uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 3210
        if ((!localDebug && uri.scheme != "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null) return null
        "${value.trimEnd('/')}/v1/nearby"
    }.getOrNull()

    private companion object { const val MAX_RESPONSE_BYTES = 512 * 1024 }
}

/** Strict response boundary. Provider time/status claims are not accepted without complete route times. */
object NearbyResponseParser {
    fun parse(body: String, expectedOriginTimestampMillis: Long, nowMillis: Long): NearbyLookupResult = try {
        val data = JSONObject(body)
        val generatedAt = data.integer("generatedAtMillis", 1, Long.MAX_VALUE)
        require(generatedAt <= nowMillis + 10_000 && nowMillis - generatedAt <= 120_000)
        val originAt = data.integer("originTimestampMillis", 1, Long.MAX_VALUE)
        require(originAt == expectedOriginTimestampMillis)
        val provider = NearbyProvider.valueOf(data.getString("provider"))
        val coverage = if (data.isNull("coverageStatus")) NearbyCoverage.UNKNOWN
            else NearbyCoverage.valueOf(data.getString("coverageStatus"))
        val reserve = data.integer("returnReserveSeconds", 300, 3_600).toInt()
        val records = data.getJSONArray("places")
        require(records.length() <= 6)
        require(coverage != NearbyCoverage.OUTSIDE_COVERAGE || records.length() == 0)
        val ids = mutableSetOf<String>()
        val places = (0 until records.length()).map { index ->
            val place = records.getJSONObject(index)
            val id = place.boundedString("id", 120, false)
            require(ids.add(id))
            val outgoing = place.optionalSeconds("outboundSeconds", 21_600)
            val returning = place.optionalSeconds("returnSeconds", 21_600)
            val stay = place.integer("staySeconds", 0, 7_200).toInt()
            val total = place.optionalSeconds("totalSeconds", 86_400)
            val fit = NearbyFit.valueOf(place.getString("fit"))
            if (outgoing == null || returning == null) {
                require(total == null && fit == NearbyFit.ROUTE_UNAVAILABLE)
            } else {
                require(total != null && total == outgoing + returning + stay + reserve && fit != NearbyFit.ROUTE_UNAVAILABLE)
            }
            NearbyPlace(id, place.boundedString("name", 160, false), place.boundedString("address", 300, true),
                mapUri(place.getString("url")) ?: error("Unexpected place URL"),
                place.coordinate("latitude", -90.0, 90.0), place.coordinate("longitude", -180.0, 180.0),
                outgoing, returning, stay, total, fit,
                place.optionalMapUri("outboundUrl"), place.optionalMapUri("returnUrl"), reserve)
        }
        val attribution = if (data.has("attribution")) data.boundedString("attribution", 300, false) else when (provider) {
            NearbyProvider.KAKAO -> "Kakao Map"
            NearbyProvider.OPENSTREETMAP -> "© OpenStreetMap contributors"
        }
        NearbyLookupResult(places, generatedAtMillis = generatedAt, originTimestampMillis = originAt, provider = provider, attribution = attribution, coverage = coverage)
    } catch (_: Exception) { NearbyLookupResult(failure = NearbyFailure.INVALID_RESPONSE) }

    fun failure(status: Int, body: String): NearbyFailure {
        val code = runCatching { JSONObject(body).optJSONObject("error")?.optString("code") }.getOrNull()
        return when (code) {
            "NOT_CONFIGURED" -> NearbyFailure.NOT_CONFIGURED
            "LOCATION_STALE" -> NearbyFailure.LOCATION_STALE
            "INVALID_REQUEST" -> NearbyFailure.INVALID_REQUEST
            "RATE_LIMITED" -> NearbyFailure.RATE_LIMITED
            "PROVIDER_AUTH" -> NearbyFailure.PROVIDER_AUTH
            "PROVIDER_TIMEOUT" -> NearbyFailure.PROVIDER_TIMEOUT
            "PROVIDER_UNAVAILABLE" -> NearbyFailure.PROVIDER_UNAVAILABLE
            else -> if (status == 429) NearbyFailure.RATE_LIMITED else NearbyFailure.PROVIDER_UNAVAILABLE
        }
    }

    fun mapUri(value: String): Uri? = runCatching {
        val uri = Uri.parse(value)
        uri.takeIf { it.scheme == "https" && it.host in setOf("map.kakao.com", "m.map.kakao.com", "place.map.kakao.com", "www.openstreetmap.org", "openstreetmap.org") &&
            it.userInfo == null && (it.port == -1 || it.port == 443) }
    }.getOrNull()

    private fun JSONObject.optionalMapUri(name: String): Uri? = if (isNull(name)) null else mapUri(getString(name)) ?: error("Unexpected route URL")
    private fun JSONObject.boundedString(name: String, max: Int, blankAllowed: Boolean): String {
        val raw = get(name)
        require(raw is String && raw.length <= max && (blankAllowed || raw.isNotBlank()) && raw.none(Char::isISOControl))
        return raw
    }
    private fun JSONObject.coordinate(name: String, low: Double, high: Double): Double {
        val raw = get(name); require(raw is Number)
        return raw.toDouble().also { require(it.isFinite() && it in low..high) }
    }
    private fun JSONObject.integer(name: String, low: Long, high: Long): Long {
        val raw = get(name); require(raw is Number)
        val value = raw.toLong()
        require(raw.toDouble() == value.toDouble() && value in low..high)
        return value
    }
    private fun JSONObject.optionalSeconds(name: String, max: Long): Int? = if (isNull(name)) null else integer(name, 0, max).toInt()
}
