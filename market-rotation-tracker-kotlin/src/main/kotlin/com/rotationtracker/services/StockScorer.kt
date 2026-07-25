package com.rotationtracker.services

import com.rotationtracker.config.STOCK_WATCHLIST
import com.rotationtracker.models.DailyBar
import com.rotationtracker.models.SectorScore
import com.rotationtracker.models.ShortInterestRecord
import com.rotationtracker.models.StockSignal

fun scoreStockWatchlist(
    leaders: List<SectorScore>,
    barsBySymbol: Map<String, List<DailyBar>>,
    shortInterestBySymbol: Map<String, ShortInterestRecord> = emptyMap(),
): List<StockSignal> {
    val signals = mutableListOf<StockSignal>()
    val seen    = mutableSetOf<String>() // deduplicate (e.g. NVDA in XLK + SMH)

    for (sector in leaders) {
        val stocks = STOCK_WATCHLIST[sector.symbol] ?: continue

        for ((symbol, label) in stocks) {
            if (!seen.add(symbol)) continue

            val bars = barsBySymbol[symbol] ?: continue
            if (bars.size < 20) continue

            val pressure = scorePressurePoints(symbol, bars, shortInterestBySymbol[symbol])
            var score    = 0

            // Parent sector rotation strength (40%)
            score += when {
                sector.relativeStrength > 3 -> 40
                sector.relativeStrength > 1 -> 30
                else -> 20
            }

            // Pressure point quality (40%)
            val strongPts    = pressure.points.count { it.strength == "strong" }
            val moderatePts  = pressure.points.count { it.strength == "moderate" }
            score += minOf(40, strongPts * 15 + moderatePts * 8)

            // RSI timing (20%) — favour neutral/mild RSI for clean entries
            val rsi = pressure.rsi
            score += when {
                rsi in 45.0..65.0  -> 20 // ideal entry zone
                rsi > 65 && rsi < 72 -> 10
                rsi in 30.0..<45.0 -> 14 // mild pullback
                rsi < 30           -> 16 // oversold — potential bounce
                else               -> 3  // overbought (>72) — avoid
            }

            score = minOf(100, score)

            val conviction = when {
                score >= 70 -> "high"
                score >= 50 -> "medium"
                score >= 30 -> "low"
                else        -> "noise"
            }

            if (conviction == "noise") continue

            val rsSign = if (sector.relativeStrength >= 0) "+" else ""
            val reasoning = listOf(
                "Parent: ${sector.label} (RS $rsSign${sector.relativeStrength}% vs SPY, ${sector.trend})",
                if (pressure.points.isNotEmpty())
                    "Pressure: ${pressure.points.joinToString("; ") { it.description }}"
                else "No significant pressure points",
                "RSI: ${pressure.rsi}",
            ).joinToString(" | ")

            signals.add(
                StockSignal(
                    symbol               = symbol,
                    label                = label,
                    parentSector         = sector.symbol,
                    parentSectorLabel    = sector.label,
                    parentRotationScore  = sector.score,
                    pressurePoints       = pressure.points,
                    rsi                  = rsi,
                    score                = score,
                    conviction           = conviction,
                    reasoning            = reasoning,
                )
            )
        }
    }

    return signals.sortedByDescending { it.score }
}
