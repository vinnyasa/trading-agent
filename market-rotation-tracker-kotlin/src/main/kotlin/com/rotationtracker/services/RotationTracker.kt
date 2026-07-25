package com.rotationtracker.services

import com.rotationtracker.models.DailyBar
import com.rotationtracker.models.RotationResult
import com.rotationtracker.models.SectorScore
import java.time.Instant

val SECTOR_UNIVERSE: List<Pair<String, String>> = listOf(
    "SPY"  to "S&P 500 (Benchmark)",
    "XLK"  to "Technology",
    "XLF"  to "Financials",
    "XLV"  to "Health Care",
    "XLE"  to "Energy",
    "XLI"  to "Industrials",
    "XLC"  to "Communication Services",
    "XLY"  to "Consumer Discretionary",
    "XLP"  to "Consumer Staples",
    "XLB"  to "Materials",
    "XLU"  to "Utilities",
    "XLRE" to "Real Estate",
    "QQQ"  to "Nasdaq 100 (Growth)",
    "IWM"  to "Russell 2000 (Small Cap)",
    "SMH"  to "Semiconductors",
)

/** Rate of change over n periods (%). */
private fun roc(bars: List<DailyBar>, periods: Int): Double {
    if (bars.size < periods + 1) return 0.0
    val latest = bars.last().close
    val prev = bars[bars.size - 1 - periods].close
    return if (prev > 0) ((latest - prev) / prev) * 100 else 0.0
}

/** Sector ROC minus SPY ROC over the lookback period. */
private fun relativeStrength(sectorBars: List<DailyBar>, spyBars: List<DailyBar>, periods: Int = 20): Double {
    return roc(sectorBars, periods) - roc(spyBars, periods)
}

fun scoreRotation(
    barsBySymbol: Map<String, List<DailyBar>>,
    spyBars: List<DailyBar>,
): RotationResult {
    val scores = mutableListOf<SectorScore>()

    for ((symbol, label) in SECTOR_UNIVERSE) {
        val bars = barsBySymbol[symbol] ?: continue
        if (bars.size < 25) continue

        val rs = relativeStrength(bars, spyBars, 20)
        val mom10 = roc(bars, 10)
        // 60% relative strength + 40% short-term momentum, normalised to 0–100
        val composite = rs * 0.6 + mom10 * 0.4
        val score = composite * 2.5 + 50.0

        scores.add(
            SectorScore(
                symbol          = symbol,
                label           = label,
                relativeStrength = "%.2f".format(rs).toDouble(),
                momentum        = "%.2f".format(mom10).toDouble(),
                score           = "%.1f".format(score.coerceIn(0.0, 100.0)).toDouble(),
                trend           = when {
                    rs > 1  -> "strengthening"
                    rs < -1 -> "weakening"
                    else    -> "neutral"
                },
            )
        )
    }

    scores.sortByDescending { it.score }

    val leaders  = scores.take(3)
    val laggards = scores.takeLast(3).reversed()
    val topMove  = leaders.firstOrNull()?.let {
        "${it.label} (${it.symbol}) leading with RS +${it.relativeStrength}% vs SPY"
    } ?: "No clear rotation leader"

    return RotationResult(
        leaders          = leaders,
        laggards         = laggards,
        allScores        = scores,
        topRotationMove  = topMove,
        rankedAt         = Instant.now().toString(),
    )
}
