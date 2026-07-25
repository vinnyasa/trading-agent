package com.rotationtracker.services

import com.rotationtracker.models.ShortInterestRecord
import com.rotationtracker.sharedClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.time.LocalDate

/**
 * FINRA publishes short interest twice a month (settlement on the 15th and the
 * last calendar day of each month), as a free, unauthenticated flat file:
 *   https://cdn.finra.org/equity/otcmarket/biweekly/shrt{YYYYMMDD}.csv
 * The file is pipe-delimited despite the .csv extension and is published ~7
 * business days after each settlement date. No API key/OAuth required.
 */
private const val FINRA_BASE_URL = "https://cdn.finra.org/equity/otcmarket/biweekly/shrt"

/** Most recent biweekly settlement dates on/before [reference], newest first. */
private fun candidateSettlementDates(reference: LocalDate, count: Int = 4): List<LocalDate> {
    val dates = mutableListOf<LocalDate>()
    var cursor = reference

    // Walk backwards from "reference", generating the 15th and month-end settlement
    // dates until we have enough candidates (covers the ~7 business day publish lag
    // and the case where the latest settlement hasn't been published yet).
    while (dates.size < count) {
        val monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth())
        val fifteenth = cursor.withDayOfMonth(15)

        if (!monthEnd.isAfter(reference) && monthEnd !in dates) dates.add(monthEnd)
        if (!fifteenth.isAfter(reference) && fifteenth !in dates) dates.add(fifteenth)

        cursor = cursor.minusMonths(1)
    }

    return dates.sortedDescending().take(count)
}

private fun parseShortInterestCsv(csv: String, symbols: Set<String>): Map<String, ShortInterestRecord> {
    val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
    if (!lines.hasNext()) return emptyMap()

    val header = lines.next().split("|")
    val symbolIdx      = header.indexOf("symbolCode")
    val currentIdx     = header.indexOf("currentShortPositionQuantity")
    val previousIdx    = header.indexOf("previousShortPositionQuantity")
    val avgVolIdx      = header.indexOf("averageDailyVolumeQuantity")
    val daysToCoverIdx = header.indexOf("daysToCoverQuantity")
    val changePctIdx   = header.indexOf("changePercent")
    val settlementIdx  = header.indexOf("settlementDate")

    if (symbolIdx < 0 || currentIdx < 0) return emptyMap()

    val result = mutableMapOf<String, ShortInterestRecord>()
    for (line in lines) {
        val cols = line.split("|")
        val symbol = cols.getOrNull(symbolIdx) ?: continue
        if (symbol !in symbols) continue

        result[symbol] = ShortInterestRecord(
            symbol                 = symbol,
            currentShortQuantity   = cols.getOrNull(currentIdx)?.toLongOrNull() ?: 0L,
            previousShortQuantity  = cols.getOrNull(previousIdx)?.toLongOrNull() ?: 0L,
            avgDailyVolume         = cols.getOrNull(avgVolIdx)?.toLongOrNull() ?: 0L,
            daysToCover            = cols.getOrNull(daysToCoverIdx)?.toDoubleOrNull() ?: 0.0,
            changePercent          = cols.getOrNull(changePctIdx)?.toDoubleOrNull() ?: 0.0,
            settlementDate         = cols.getOrNull(settlementIdx) ?: "",
        )
    }
    return result
}

/**
 * Fetches the latest available FINRA short interest file and returns records for
 * [symbols]. Tries the most recent settlement dates first, falling back to earlier
 * ones since the newest file may not be published yet (~7 business day lag).
 */
suspend fun fetchShortInterest(symbols: Set<String>, today: LocalDate = LocalDate.now()): Map<String, ShortInterestRecord> {
    for (settlementDate in candidateSettlementDates(today)) {
        val dateStr = "%04d%02d%02d".format(settlementDate.year, settlementDate.monthValue, settlementDate.dayOfMonth)
        val url = "$FINRA_BASE_URL$dateStr.csv"

        try {
            val response = sharedClient.get(url)
            if (response.status != HttpStatusCode.OK) {
                println("[short-interest] $url -> HTTP ${response.status.value}, trying next settlement date")
                continue
            }
            val body = response.bodyAsText()
            if (body.isBlank()) {
                println("[short-interest] $url -> empty response body, trying next settlement date")
                continue
            }
            val parsed = parseShortInterestCsv(body, symbols)
            if (parsed.isEmpty()) {
                val header = body.lineSequence().firstOrNull { it.isNotBlank() } ?: "(no header)"
                println("[short-interest] $url -> fetched OK but 0 matching symbols parsed. Header: $header")
                continue
            }
            println("[short-interest] $url -> matched ${parsed.size} symbols")
            return parsed
        } catch (e: Exception) {
            println("[short-interest] $url -> exception: ${e.message}")
        }
    }
    println("[short-interest] No short interest data found across any candidate settlement date")
    return emptyMap()
}
