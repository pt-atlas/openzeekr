package com.openzeekr.app.auto

import com.openzeekr.app.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Will it rain at the car in the next hour or so?" via Open-Meteo (free, no API key, no account).
 * Only the car's rounded coordinates are sent. Returns null when the forecast can't be fetched, so
 * the engine can tell "no rain" from "don't know".
 */
object RainForecast {

    /** Rain counts as "soon" at or above either threshold in the current or next hour. */
    private const val PROBABILITY_PCT = 50.0
    private const val PRECIP_MM = 0.3

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun rainSoon(lat: Double, lng: Double): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            // 2 decimals ≈ 1 km: plenty for weather, and keeps the exact parking spot private.
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&hourly=precipitation_probability,precipitation&forecast_hours=2&timezone=auto",
                lat, lng,
            )
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                parse(resp.body?.string().orEmpty())
            }
        }.onFailure { Logx.w("auto", "rain forecast failed: ${it.message}") }.getOrNull()
    }

    internal fun parse(body: String): Boolean? {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val h = root["hourly"] as? JsonObject ?: return null
        fun nums(key: String) = (h[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()
        val prob = nums("precipitation_probability")
        val mm = nums("precipitation")
        if (prob.isEmpty() && mm.isEmpty()) return null
        return prob.any { it >= PROBABILITY_PCT } || mm.any { it >= PRECIP_MM }
    }
}
