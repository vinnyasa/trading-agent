package com.rotationtracker.models

import kotlinx.serialization.Serializable

// ── Daily price bar ───────────────────────────────────────────────────────────
@Serializable
data class DailyBar(
    val symbol: String,
    val date: String,   // YYYY-MM-DD
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

// ── Macro ─────────────────────────────────────────────────────────────────────
@Serializable
data class MacroDataPoint(val date: String, val value: Double)

@Serializable
data class MacroSeries(
    val seriesId: String,
    val label: String,
    val data: List<MacroDataPoint>,
    val latestValue: Double,
    val previousValue: Double,
    val trend: String,  // "rising" | "falling" | "flat"
)

@Serializable
data class MacroRegimeResult(
    val regime: String,     // "risk-on-growth" | "risk-off-slowdown" | "inflation-pressure" | "disinflation-recovery" | "unknown"
    val label: String,
    val factors: List<String>,
    val confidence: String, // "high" | "medium" | "low"
)

// ── Rotation ──────────────────────────────────────────────────────────────────
@Serializable
data class SectorScore(
    val symbol: String,
    val label: String,
    val relativeStrength: Double,
    val momentum: Double,
    val score: Double,
    val trend: String,  // "strengthening" | "weakening" | "neutral"
)

@Serializable
data class RotationResult(
    val leaders: List<SectorScore>,
    val laggards: List<SectorScore>,
    val allScores: List<SectorScore>,
    val topRotationMove: String,
    val rankedAt: String,
)

// ── Pressure points ───────────────────────────────────────────────────────────
@Serializable
data class PressurePoint(
    val symbol: String,
    val type: String,       // "support" | "resistance" | "rsi-overbought" | etc.
    val level: Double,      // price level (0 for momentum-only types)
    val description: String,
    val strength: String,   // "strong" | "moderate" | "weak"
)

@Serializable
data class PressureResult(
    val symbol: String,
    val points: List<PressurePoint>,
    val rsi: Double,
    val highVolumeNode: Double?,
    val nearSupport: Boolean,
    val nearResistance: Boolean,
    val relativeVolume: Double, // today vs 20-day avg (1.0 = average)
)

// ── Short interest (FINRA biweekly, free public file — no float/shares data) ──
@Serializable
data class ShortInterestRecord(
    val symbol: String,
    val currentShortQuantity: Long,
    val previousShortQuantity: Long,
    val avgDailyVolume: Long,
    val daysToCover: Double,   // currentShortQuantity / avgDailyVolume
    val changePercent: Double, // % change in short position vs previous settlement
    val settlementDate: String,
)

// ── Confluence ────────────────────────────────────────────────────────────────
@Serializable
data class ConfluenceSignal(
    val symbol: String,
    val score: Int,
    val conviction: String,     // "high" | "medium" | "low" | "noise"
    val regime: String,
    val rotationTrend: String,  // "strengthening" | "weakening" | "neutral"
    val pressurePoints: List<PressurePoint>,
    val rsi: Double,
    val reasoning: String,
)

// ── Stock signals ─────────────────────────────────────────────────────────────
@Serializable
data class StockSignal(
    val symbol: String,
    val label: String,
    val parentSector: String,
    val parentSectorLabel: String,
    val parentRotationScore: Double,
    val pressurePoints: List<PressurePoint>,
    val rsi: Double,
    val score: Int,
    val conviction: String,
    val reasoning: String,
)

// ── 401k tracker ──────────────────────────────────────────────────────────────
@Serializable
data class FourOhOneKPosition(
    val ticker: String,
    val fundName: String,
    val parentEtf: String,
    val entryDate: String,
    val entrySignalScore: Double,
    var daysHeld: Int,
    var canSellWithoutFee: Boolean,
    var consecutiveLeaderDays: Int,
    var consecutiveLaggardDays: Int,
)

@Serializable
data class FourOhOneKState(
    var lastUpdated: String,
    var cashAvailable: Double,
    val cashNote: String = "Update cashAvailable in reports/401k-state.json when you move funds from core 401k",
    val positions: MutableList<FourOhOneKPosition> = mutableListOf(),
    val recentActions: MutableList<String> = mutableListOf(),
)

@Serializable
data class FourOhOneKRecommendation(
    val action: String,     // "hold" | "consider-exit" | "exit-when-eligible" | "exit-now" | "consider-entry"
    val ticker: String,
    val fundName: String,
    val parentEtf: String,
    val daysHeld: Int,
    val canSellWithoutFee: Boolean,
    val reason: String,
    val urgency: String,    // "high" | "medium" | "low"
)

@Serializable
data class EntryOpportunity(
    val ticker: String,
    val fundName: String,
    val parentEtf: String,
    val parentEtfScore: Double,
    val reason: String,
    val suggestedPositionPct: Double,
    val suggestedDollarAmount: Int,
)

@Serializable
data class FourOhOneKReport(
    val date: String,
    val cashAvailable: Double,
    val cashNote: String,
    val totalPositions: Int,
    val positions: List<FourOhOneKPosition>,
    val recommendations: List<FourOhOneKRecommendation>,
    val entryOpportunities: List<EntryOpportunity>,
    val summary: List<String>,
)

// ── Daily report ──────────────────────────────────────────────────────────────
@Serializable
data class DataSourceStatus(
    val primary: String,            // "massive" | "alpha-vantage" | "none"
    val symbolsLoaded: Int,
    val symbolsFailed: List<String>,
)

@Serializable
data class DailyReport(
    val runDate: String,
    val runTimestamp: String,
    val dataSourceStatus: DataSourceStatus,
    val macroRegime: MacroRegimeResult,
    val rotation: RotationResult,
    val topSignals: List<ConfluenceSignal>,
    val stockSignals: List<StockSignal>,
    val watchlistChanges: List<String>,
    val warnings: List<String>,
    val fourOhOneK: FourOhOneKReport? = null,
)

// ── Signal log ────────────────────────────────────────────────────────────────
@Serializable
data class SignalLogEntry(
    val date: String,
    val symbol: String,
    val signalType: String,
    val value: Double,
    val regime: String,
    val confluenceScore: Int,
)
