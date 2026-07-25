package com.rotationtracker.services

import com.rotationtracker.models.ConfluenceSignal
import com.rotationtracker.models.MacroRegimeResult
import com.rotationtracker.models.PressureResult
import com.rotationtracker.models.SectorScore

private val OFFENSIVE_SECTORS = setOf("XLK", "XLY", "XLF", "XLI", "QQQ")
private val DEFENSIVE_SECTORS = setOf("XLU", "XLP", "XLRE", "XLV")

fun scoreConfluence(
    sector: SectorScore,
    pressure: PressureResult,
    regime: MacroRegimeResult,
): ConfluenceSignal {
    var score = 0

    // ── Macro regime alignment (35%) ──────────────────────────────────────────
    val isOffensive = sector.symbol in OFFENSIVE_SECTORS
    val isDefensive = sector.symbol in DEFENSIVE_SECTORS
    score += when {
        regime.regime == "risk-on-growth"        && isOffensive -> 35
        regime.regime == "risk-off-slowdown"     && isDefensive -> 35
        regime.regime == "disinflation-recovery" && (isOffensive || isDefensive) -> 25
        regime.regime == "inflation-pressure"    && sector.symbol == "XLE" -> 30
        else -> 10
    }

    // ── Rotation strength (30%) ───────────────────────────────────────────────
    score += when {
        sector.trend == "strengthening" -> when {
            sector.relativeStrength > 3 -> 30
            sector.relativeStrength > 1 -> 20
            else -> 10
        }
        sector.trend == "weakening" -> 0
        else -> 10
    }

    // ── Pressure point quality (25%) ─────────────────────────────────────────
    val strongPts    = pressure.points.count { it.strength == "strong" }
    val moderatePts  = pressure.points.count { it.strength == "moderate" }
    score += minOf(25, strongPts * 10 + moderatePts * 5)

    // ── Momentum confirmation (10%) ───────────────────────────────────────────
    score += when {
        pressure.rsi in 50.0..65.0 -> 10
        pressure.rsi > 65 && pressure.rsi < 70 -> 5
        pressure.rsi < 40 -> 3
        else -> 0
    }

    score = minOf(100, score)

    val conviction = when {
        score >= 70 -> "high"
        score >= 50 -> "medium"
        score >= 30 -> "low"
        else        -> "noise"
    }

    val rsSign = if (sector.relativeStrength >= 0) "+" else ""
    val reasoning = listOf(
        "Regime: ${regime.label}",
        "Rotation: ${sector.label} ${sector.trend} (RS $rsSign${sector.relativeStrength}% vs SPY)",
        if (pressure.points.isNotEmpty())
            "Pressure: ${pressure.points.joinToString("; ") { it.description }}"
        else "No significant pressure points",
        "RSI: ${pressure.rsi}",
    ).joinToString(" | ")

    return ConfluenceSignal(
        symbol         = sector.symbol,
        score          = score,
        conviction     = conviction,
        regime         = regime.regime,
        rotationTrend  = sector.trend,
        pressurePoints = pressure.points,
        rsi            = pressure.rsi,
        reasoning      = reasoning,
    )
}
