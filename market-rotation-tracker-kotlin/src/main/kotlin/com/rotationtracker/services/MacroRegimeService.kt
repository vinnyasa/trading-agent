package com.rotationtracker.services

import com.rotationtracker.models.MacroRegimeResult
import com.rotationtracker.models.MacroSeries

/** Classify macro regime from FRED series. Top-down: most dominant condition first. */
fun detectMacroRegime(series: List<MacroSeries>): MacroRegimeResult {
    fun get(id: String) = series.find { it.seriesId == id }

    val ff     = get("DFF")
    val spread = get("T10Y2Y")
    val cpi    = get("CPIAUCSL")
    val unrate = get("UNRATE")
    val indpro = get("INDPRO")

    val factors = mutableListOf<String>()
    val regime: String
    val confidence: String

    // ── Rule 1: Inflation pressure ─────────────────────────────────────────────
    if (cpi?.trend == "rising" && ff?.trend == "rising") {
        regime = "inflation-pressure"
        factors += "CPI trending ${cpi.trend} (${String.format("%.2f", cpi.latestValue)})"
        factors += "Fed Funds trending ${ff.trend} (${String.format("%.2f", ff.latestValue)}%)"
        confidence = if (cpi.latestValue > 4) "high" else "medium"
    }
    // ── Rule 2: Risk-off slowdown ──────────────────────────────────────────────
    else if ((spread != null && spread.latestValue < 0) ||
             (unrate?.trend == "rising" && indpro?.trend == "falling")) {
        regime = "risk-off-slowdown"
        if (spread != null && spread.latestValue < 0)
            factors += "Yield curve inverted (${String.format("%.2f", spread.latestValue)}%)"
        if (unrate?.trend == "rising")
            factors += "Unemployment rising (${String.format("%.1f", unrate.latestValue)}%)"
        if (indpro?.trend == "falling")
            factors += "Industrial production declining"
        confidence = if (spread != null && spread.latestValue < -0.5) "high" else "medium"
    }
    // ── Rule 3: Disinflation recovery ─────────────────────────────────────────
    else if (cpi?.trend == "falling" && (ff?.trend == "falling" || ff?.trend == "flat")) {
        regime = "disinflation-recovery"
        factors += "CPI trending down (${String.format("%.2f", cpi.latestValue)})"
        if (ff != null) factors += "Fed Funds ${ff.trend} (${String.format("%.2f", ff.latestValue)}%)"
        if (spread != null && spread.latestValue > 0)
            factors += "Yield curve positive (${String.format("%.2f", spread.latestValue)}%)"
        confidence = "medium"
    }
    // ── Rule 4: Risk-on growth ─────────────────────────────────────────────────
    else if (spread != null && spread.latestValue > 0.5 &&
             indpro?.trend == "rising" &&
             (unrate?.trend == "falling" || unrate?.trend == "flat")) {
        regime = "risk-on-growth"
        factors += "Yield curve healthy (${String.format("%.2f", spread.latestValue)}%)"
        factors += "Industrial production expanding"
        if (unrate != null)
            factors += "Unemployment ${unrate.trend} (${String.format("%.1f", unrate.latestValue)}%)"
        confidence = "high"
    }
    // ── Fallback ──────────────────────────────────────────────────────────────
    else {
        regime = "unknown"
        factors += "Mixed or inconclusive macro signals"
        confidence = "low"
    }

    val label = when (regime) {
        "risk-on-growth"        -> "Risk-On Growth"
        "risk-off-slowdown"     -> "Risk-Off Slowdown"
        "inflation-pressure"    -> "Inflation Pressure"
        "disinflation-recovery" -> "Disinflation Recovery"
        else                    -> "Unknown / Mixed"
    }

    return MacroRegimeResult(regime = regime, label = label, factors = factors, confidence = confidence)
}
