package com.rotationtracker.services

import com.rotationtracker.models.DailyBar
import com.rotationtracker.sharedClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MASSIVE_BASE = "https://api.polygon.io/v2"
private const val AV_BASE = "https://www.alphavantage.co/query"
private const val MASSIVE_RATE_DELAY_MS = 13_000L
private const val AV_DAILY_CAP = 20

private val json = Json { ignoreUnknownKeys = true }

private suspend fun fetchFromMassive(symbol: String, days: Int): List<DailyBar>? {
    val apiKey = System.getenv("MASSIVE_API_KEY") ?: return null

    val to = LocalDate.now()
    val from = to.minusDays((days + 10).toLong())

    return try {
        val response = sharedClient.get("$MASSIVE_BASE/aggs/ticker/$symbol/range/1/day/$from/$to") {
            parameter("adjusted", "true")
            parameter("sort", "asc")
            parameter("limit", days + 10)
            parameter("apiKey", apiKey)
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            println("[market] massive $symbol HTTP ${response.status.value}: ${bodyText.take(200)}")
            return null
        }
        val body = json.parseToJsonElement(bodyText).jsonObject
        val results = body["results"]?.jsonArray ?: run {
            println("[market] massive $symbol no 'results' field: ${bodyText.take(200)}")
            return null
        }
        if (results.isEmpty()) return null

        results.map { r ->
            val bar = r.jsonObject
            DailyBar(
                symbol = symbol,
                date = Instant.ofEpochMilli(bar["t"]!!.jsonPrimitive.long)
                    .atZone(ZoneOffset.UTC).toLocalDate().toString(),
                open   = bar["o"]!!.jsonPrimitive.double,
                high   = bar["h"]!!.jsonPrimitive.double,
                low    = bar["l"]!!.jsonPrimitive.double,
                close  = bar["c"]!!.jsonPrimitive.double,
                // Polygon returns volume in scientific notation (e.g. 6.04e+07) sometimes,
                // which jsonPrimitive.long can't parse directly.
                volume = bar["v"]!!.jsonPrimitive.double.toLong(),
            )
        }
    } catch (e: Exception) {
        println("[market] massive $symbol exception: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}

private suspend fun fetchFromAlphaVantage(symbol: String): List<DailyBar>? {
    val apiKey = System.getenv("ALPHA_VANTAGE_API_KEY") ?: return null

    return try {
        val response = sharedClient.get(AV_BASE) {
            // TIME_SERIES_DAILY_ADJUSTED is now a premium-only endpoint on Alpha Vantage's
            // free tier — use the free TIME_SERIES_DAILY endpoint (unadjusted close) instead.
            parameter("function", "TIME_SERIES_DAILY")
            parameter("symbol", symbol)
            parameter("outputsize", "compact") // last ~100 trading days
            parameter("apikey", apiKey)
        }

        val bodyText = response.bodyAsText()
        val body = json.parseToJsonElement(bodyText).jsonObject
        val ts = body["Time Series (Daily)"]?.jsonObject ?: run {
            println("[market] alpha-vantage $symbol no time series: ${bodyText.take(200)}")
            return null
        }

        ts.entries.map { (date, v) ->
            val bar = v.jsonObject
            DailyBar(
                symbol = symbol,
                date   = date,
                open   = bar["1. open"]!!.jsonPrimitive.content.toDouble(),
                high   = bar["2. high"]!!.jsonPrimitive.content.toDouble(),
                low    = bar["3. low"]!!.jsonPrimitive.content.toDouble(),
                close  = bar["4. close"]!!.jsonPrimitive.content.toDouble(),
                volume = bar["5. volume"]!!.jsonPrimitive.content.toLong(),
            )
        }.sortedBy { it.date }
    } catch (e: Exception) {
        println("[market] alpha-vantage $symbol exception: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}

data class FetchResult(
    val results: MutableMap<String, List<DailyBar>>,
    val failed: List<String>,
    val primarySource: String,
)

suspend fun fetchUniverseBars(symbols: List<String>, days: Int = 60): FetchResult {
    val results = mutableMapOf<String, List<DailyBar>>()
    val failed = mutableListOf<String>()
    var avCallsUsed = 0
    var primarySource = "none"

    symbols.forEachIndexed { i, symbol ->
        if (i > 0) {
            println("[market] Waiting ${MASSIVE_RATE_DELAY_MS / 1000}s for rate limit...")
            delay(MASSIVE_RATE_DELAY_MS)
        }

        val massive = fetchFromMassive(symbol, days)
        if (massive != null && massive.isNotEmpty()) {
            results[symbol] = massive.takeLast(days)
            primarySource = "massive"
            return@forEachIndexed
        }

        if (avCallsUsed < AV_DAILY_CAP) {
            val av = fetchFromAlphaVantage(symbol)
            avCallsUsed++
            if (av != null && av.isNotEmpty()) {
                results[symbol] = av.takeLast(days)
                if (primarySource == "none") primarySource = "alpha-vantage"
                return@forEachIndexed
            }
        }

        failed.add(symbol)
    }

    return FetchResult(results, failed, primarySource)
}
