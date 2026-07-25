package com.rotationtracker.services

import com.rotationtracker.models.MacroDataPoint
import com.rotationtracker.models.MacroSeries
import com.rotationtracker.sharedClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import kotlin.math.abs

private const val FRED_BASE = "https://api.stlouisfed.org/fred/series/observations"

val MACRO_SERIES_CONFIG: List<Pair<String, String>> = listOf(
    "DFF"      to "Fed Funds Rate",
    "T10Y2Y"   to "10Y-2Y Yield Spread",
    "CPIAUCSL" to "CPI Inflation (YoY)",
    "UNRATE"   to "Unemployment Rate",
    "INDPRO"   to "Industrial Production",
)

private val json = Json { ignoreUnknownKeys = true }

private suspend fun fetchFredSeries(seriesId: String, observations: Int = 6): List<MacroDataPoint> {
    val apiKey = System.getenv("FRED_API_KEY")
        ?: error("FRED_API_KEY not set")

    val response = sharedClient.get(FRED_BASE) {
        parameter("series_id", seriesId)
        parameter("api_key", apiKey)
        parameter("file_type", "json")
        parameter("sort_order", "desc")
        parameter("limit", observations)
    }

    val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
    val obs = body["observations"]?.jsonArray ?: return emptyList()

    return obs
        .filter { it.jsonObject["value"]?.jsonPrimitive?.content != "." }
        .map { o ->
            MacroDataPoint(
                date  = o.jsonObject["date"]!!.jsonPrimitive.content,
                value = o.jsonObject["value"]!!.jsonPrimitive.content.toDouble(),
            )
        }
        .reversed()
}

private fun computeTrend(data: List<MacroDataPoint>): String {
    if (data.size < 2) return "flat"
    val latest = data.last().value
    val anchor = data[maxOf(0, data.size - 3)].value
    val pct = if (abs(anchor) > 0) (latest - anchor) / abs(anchor) else 0.0
    return when {
        pct > 0.001  -> "rising"
        pct < -0.001 -> "falling"
        else         -> "flat"
    }
}

suspend fun fetchMacroSeries(): List<MacroSeries> {
    val results = mutableListOf<MacroSeries>()

    for ((id, label) in MACRO_SERIES_CONFIG) {
        try {
            val data = fetchFredSeries(id)
            if (data.isEmpty()) continue
            results.add(
                MacroSeries(
                    seriesId      = id,
                    label         = label,
                    data          = data,
                    latestValue   = data.last().value,
                    previousValue = if (data.size > 1) data[data.size - 2].value else data[0].value,
                    trend         = computeTrend(data),
                )
            )
        } catch (e: Exception) {
            println("[FRED] Failed to fetch $id: ${e.message}")
        }
    }

    return results
}
