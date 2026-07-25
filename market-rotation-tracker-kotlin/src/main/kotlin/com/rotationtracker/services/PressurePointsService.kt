package com.rotationtracker.services

import com.rotationtracker.models.DailyBar
import com.rotationtracker.models.PressurePoint
import com.rotationtracker.models.PressureResult
import kotlin.math.*

// ── RSI ───────────────────────────────────────────────────────────────────────
private fun computeRSI(bars: List<DailyBar>, period: Int = 14): Double {
    if (bars.size < period + 1) return 50.0

    val changes = bars.takeLast(period + 1)
        .zipWithNext { a, b -> b.close - a.close }

    val gains  = changes.map { if (it > 0) it else 0.0 }
    val losses = changes.map { if (it < 0) -it else 0.0 }

    val avgGain = gains.average()
    val avgLoss = losses.average()

    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return "%.2f".format(100 - 100 / (1 + rs)).toDouble()
}

// ── Bollinger Band squeeze ────────────────────────────────────────────────────
private data class BollingerResult(val squeezing: Boolean, val direction: String)

private fun bollingerAnalysis(bars: List<DailyBar>, period: Int = 20): BollingerResult {
    if (bars.size < period * 2) return BollingerResult(false, "neutral")

    val recent = bars.takeLast(period)
    val older  = bars.dropLast(period).takeLast(period)

    fun stdDev(b: List<DailyBar>): Double {
        val mean     = b.map { it.close }.average()
        val variance = b.sumOf { (it.close - mean).pow(2) } / b.size
        return sqrt(variance)
    }

    val squeezing = stdDev(recent) < stdDev(older) * 0.85

    val last3  = bars.takeLast(3)
    val roc3   = if (last3.size >= 2)
        (last3.last().close - last3.first().close) / last3.first().close else 0.0
    val dir    = when {
        roc3 > 0.005  -> "up"
        roc3 < -0.005 -> "down"
        else          -> "neutral"
    }
    return BollingerResult(squeezing, dir)
}

// ── High-volume node (approximate) ───────────────────────────────────────────
private fun findHighVolumeNode(bars: List<DailyBar>): Double? {
    if (bars.size < 10) return null
    val top3     = bars.sortedByDescending { it.volume }.take(3)
    val avgClose = top3.map { it.close }.average()
    return "%.2f".format(avgClose).toDouble()
}

// ── Support / resistance ──────────────────────────────────────────────────────
private data class SupportResistance(val support: Double, val resistance: Double)

private fun findSupportResistance(bars: List<DailyBar>, lookback: Int = 20): SupportResistance {
    val slice = bars.takeLast(lookback)
    return SupportResistance(
        support    = slice.minOf { it.low },
        resistance = slice.maxOf { it.high },
    )
}

// ── Momentum divergence ───────────────────────────────────────────────────────
private fun hasMomentumDivergence(bars: List<DailyBar>): Boolean {
    if (bars.size < 20) return false
    val mid       = bars.size / 2
    val earlyBars = bars.take(mid)
    val lateBars  = bars.drop(mid)

    val earlyRsi  = computeRSI(earlyBars)
    val lateRsi   = computeRSI(lateBars)
    val earlyClose = earlyBars.last().close
    val lateClose  = lateBars.last().close

    val priceUp  = lateClose > earlyClose
    val rsiDown  = lateRsi < earlyRsi - 5
    val priceDown = lateClose < earlyClose
    val rsiUp    = lateRsi > earlyRsi + 5

    return (priceUp && rsiDown) || (priceDown && rsiUp)
}

// ── Relative volume (today vs 20-day avg, excluding today) ───────────────────
private fun computeRelativeVolume(bars: List<DailyBar>, period: Int = 20): Double {
    if (bars.size < period + 1) return 1.0
    val avgVol = bars.dropLast(1).takeLast(period).map { it.volume.toDouble() }.average()
    if (avgVol == 0.0) return 1.0
    return "%.2f".format(bars.last().volume / avgVol).toDouble()
}

// ── EMA (exponential moving average) ─────────────────────────────────────────
private fun computeEMA(bars: List<DailyBar>, period: Int): Double {
    if (bars.size < period) return bars.last().close
    val k = 2.0 / (period + 1)
    var ema = bars.take(period).map { it.close }.average()
    for (bar in bars.drop(period)) {
        ema = bar.close * k + ema * (1 - k)
    }
    return ema
}

// ── Breakout-then-pullback-to-EMA (better risk/reward than chasing) ─────────
// A stock that broke out to a recent high and has since pulled back to test
// the 8- or 21-EMA — while the uptrend is still intact — offers a tighter,
// better-defined stop than entering during the breakout extension itself.
private data class EmaPullback(val emaPeriod: Int, val emaValue: Double, val pctFromHigh: Double)

private fun findEmaPullbackSetup(bars: List<DailyBar>): EmaPullback? {
    if (bars.size < 25) return null

    val currentPrice = bars.last().close

    // Recent swing high, excluding the last 2 bars — so we're looking at a high
    // the price has already pulled back from, not today's/yesterday's high.
    val lookback = bars.takeLast(20).dropLast(2)
    if (lookback.isEmpty()) return null
    val recentHigh = lookback.maxOf { it.high }
    val pullbackFromHigh = (recentHigh - currentPrice) / recentHigh

    // Must be a genuine pullback — not a fresh high, not a trend break
    if (pullbackFromHigh < 0.02 || pullbackFromHigh > 0.15) return null

    val ema8  = computeEMA(bars, 8)
    val ema21 = computeEMA(bars, 21)

    // Uptrend still intact: price holding above the 21-EMA, 8-EMA not rolled over
    if (currentPrice < ema21 * 0.98 || ema8 < ema21 * 0.98) return null

    fun proximity(level: Double) = abs(currentPrice - level) / currentPrice

    return when {
        proximity(ema8) <= 0.015 -> EmaPullback(8, ema8, pullbackFromHigh)
        proximity(ema21) <= 0.02 -> EmaPullback(21, ema21, pullbackFromHigh)
        else -> null
    }
}

// ── Consecutive candle streak ─────────────────────────────────────────────────
private data class CandleStreak(val count: Int, val direction: String)

private fun candleStreakLength(bars: List<DailyBar>): CandleStreak {
    if (bars.size < 2) return CandleStreak(0, "none")
    val last      = bars.last()
    val direction = when {
        last.close > last.open -> "up"
        last.close < last.open -> "down"
        else                   -> "none"
    }
    if (direction == "none") return CandleStreak(0, "none")

    var count = 1
    for (i in bars.size - 2 downTo 0) {
        val b = bars[i]
        val matches = if (direction == "up") b.close > b.open else b.close < b.open
        if (matches) count++ else break
    }
    return CandleStreak(count, direction)
}

// ── Main scorer ───────────────────────────────────────────────────────────────
fun scorePressurePoints(symbol: String, bars: List<DailyBar>): PressureResult {
    val points       = mutableListOf<PressurePoint>()
    val currentPrice = bars.last().close

    val rsi                  = computeRSI(bars)
    val (support, resistance) = findSupportResistance(bars).let { it.support to it.resistance }
    val hvn                  = findHighVolumeNode(bars)
    val (squeezing, sqDir)   = bollingerAnalysis(bars).let { it.squeezing to it.direction }
    val diverging            = hasMomentumDivergence(bars)
    val rvol                 = computeRelativeVolume(bars)
    val (streakCount, streakDir) = candleStreakLength(bars).let { it.count to it.direction }

    fun proximity(level: Double) = abs(currentPrice - level) / currentPrice

    // RSI extremes
    if (rsi >= 70) points.add(PressurePoint(symbol, "rsi-overbought", 0.0,
        "RSI overbought at $rsi", if (rsi >= 80) "strong" else "moderate"))
    else if (rsi <= 30) points.add(PressurePoint(symbol, "rsi-oversold", 0.0,
        "RSI oversold at $rsi", if (rsi <= 20) "strong" else "moderate"))

    // Near support (within 2%)
    if (proximity(support) <= 0.02)
        points.add(PressurePoint(symbol, "support", support,
            "Near 20-day support at ${"%.2f".format(support)} (${"%.1f".format(proximity(support) * 100)}% away)",
            if (proximity(support) <= 0.005) "strong" else "moderate"))

    // Near resistance (within 2%)
    if (proximity(resistance) <= 0.02)
        points.add(PressurePoint(symbol, "resistance", resistance,
            "Near 20-day resistance at ${"%.2f".format(resistance)} (${"%.1f".format(proximity(resistance) * 100)}% away)",
            if (proximity(resistance) <= 0.005) "strong" else "moderate"))

    // High-volume node proximity (within 3%)
    if (hvn != null && proximity(hvn) <= 0.03)
        points.add(PressurePoint(symbol, "high-volume-node", hvn,
            "Near high-volume node at ${"%.2f".format(hvn)}", "moderate"))

    // Bollinger squeeze
    if (squeezing) {
        val (type, desc) = when (sqDir) {
            "up"   -> "bollinger-squeeze-up"   to "Bollinger squeeze with upward bias — volatility coiling, momentum tilting up"
            "down" -> "bollinger-squeeze-down" to "Bollinger squeeze with downward bias — volatility coiling, momentum tilting down"
            else   -> "bollinger-squeeze"      to "Bollinger Band squeeze — compressed volatility, breakout direction unclear"
        }
        points.add(PressurePoint(symbol, type, 0.0, desc, "moderate"))
    }

    // Momentum divergence
    if (diverging)
        points.add(PressurePoint(symbol, "momentum-divergence", 0.0,
            "Price and RSI diverging — potential trend exhaustion", "weak"))

    // Volume dry-up: price up over 5 days but today's volume well below 20-day avg
    val priceTrend5d = if (bars.size >= 6)
        (currentPrice - bars[bars.size - 6].close) / bars[bars.size - 6].close else 0.0
    if (priceTrend5d > 0.01 && rvol < 0.8)
        points.add(PressurePoint(symbol, "volume-dry-up", 0.0,
            "Volume dry-up: price +${"%.1f".format(priceTrend5d * 100)}% over 5d but RVol ${rvol}x (20d avg) — participation fading",
            if (rvol < 0.6) "strong" else "moderate"))

    // Candle streak exhaustion: 7+ consecutive candles in the same direction
    if (streakCount >= 7)
        points.add(PressurePoint(symbol, "candle-streak-exhaustion", 0.0,
            "$streakCount consecutive ${if (streakDir == "up") "green" else "red"} candles — streak exhaustion risk",
            if (streakCount >= 9) "strong" else "moderate"))

    // Breakout pulled back to the 8/21-EMA — better risk/reward than chasing the highs
    findEmaPullbackSetup(bars)?.let { setup ->
        points.add(PressurePoint(symbol, "ema-pullback-${setup.emaPeriod}", setup.emaValue,
            "Pulled back ${"%.1f".format(setup.pctFromHigh * 100)}% from recent high to the ${setup.emaPeriod}-EMA " +
                "(${"%.2f".format(setup.emaValue)}) in an intact uptrend — better risk/reward than chasing the breakout",
            "strong"))
    }

    return PressureResult(
        symbol          = symbol,
        points          = points,
        rsi             = rsi,
        highVolumeNode  = hvn,
        nearSupport     = proximity(support) <= 0.02,
        nearResistance  = proximity(resistance) <= 0.02,
        relativeVolume  = rvol,
    )
}
