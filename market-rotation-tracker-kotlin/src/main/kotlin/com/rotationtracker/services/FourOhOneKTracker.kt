package com.rotationtracker.services

import com.rotationtracker.config.getFundsByEtf
import com.rotationtracker.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private val STATE_PATH = File("reports/401k-state.json")
private const val MIN_HOLD_DAYS = 30
private const val CONSECUTIVE_LEADER_DAYS_TO_ENTER = 2
private const val CONSECUTIVE_LAGGARD_DAYS_TO_EXIT = 2
private const val MAX_POSITIONS = 4
private const val MAX_POSITION_PCT = 0.35

private val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults    = true
    prettyPrint       = true
}

private fun loadState(): FourOhOneKState {
    if (STATE_PATH.exists()) {
        try {
            return stateJson.decodeFromString<FourOhOneKState>(STATE_PATH.readText())
        } catch (_: Exception) {
            // corrupted state — fall through to default
        }
    }
    // Default initial state
    val today = LocalDate.now(ZoneId.of("America/New_York")).toString()
    return FourOhOneKState(
        lastUpdated    = today,
        cashAvailable  = 2600.0,
        cashNote       = "Update cashAvailable in reports/401k-state.json when you move funds from core 401k",
        positions      = mutableListOf(
            FourOhOneKPosition("FIDRX", "Fidelity Select Industrials",          "XLI", "2026-07-14", 53.5, 0, false, 1, 0),
            FourOhOneKPosition("FSLEX", "Fidelity Environment & Alt Energy",    "XLU", "2026-07-14", 52.3, 0, false, 1, 0),
            FourOhOneKPosition("FSELX", "Fidelity Select Semiconductors",       "SMH", "2026-07-14", 37.6, 0, false, 0, 1),
        ),
        recentActions  = mutableListOf(),
    )
}

private fun saveState(state: FourOhOneKState) {
    STATE_PATH.parentFile?.mkdirs()
    STATE_PATH.writeText(stateJson.encodeToString(state))
}

fun updateFourOhOneK(rotation: RotationResult, runDate: String): FourOhOneKReport {
    val state            = loadState()
    val recommendations  = mutableListOf<FourOhOneKRecommendation>()
    val entryOpportunities = mutableListOf<EntryOpportunity>()
    val summary          = mutableListOf<String>()

    val leaderSymbols  = rotation.leaders.map  { it.symbol }.toSet()
    val laggardSymbols = rotation.laggards.map { it.symbol }.toSet()

    // ── Update existing positions ────────────────────────────────────────────
    for (pos in state.positions) {
        // Advance days held
        if (pos.entryDate != runDate) {
            val entry = LocalDate.parse(pos.entryDate)
            val today = LocalDate.parse(runDate)
            pos.daysHeld = (today.toEpochDay() - entry.toEpochDay()).toInt()
        }
        pos.canSellWithoutFee = pos.daysHeld >= MIN_HOLD_DAYS

        // Track consecutive leader/laggard days
        when {
            pos.parentEtf in leaderSymbols  -> { pos.consecutiveLeaderDays++;  pos.consecutiveLaggardDays = 0 }
            pos.parentEtf in laggardSymbols -> { pos.consecutiveLaggardDays++; pos.consecutiveLeaderDays  = 0 }
            else                            -> { pos.consecutiveLaggardDays = 0; pos.consecutiveLeaderDays = 0 }
        }

        val daysUntilFree = maxOf(0, MIN_HOLD_DAYS - pos.daysHeld)

        // Exit logic
        if (pos.consecutiveLaggardDays >= CONSECUTIVE_LAGGARD_DAYS_TO_EXIT) {
            if (pos.canSellWithoutFee) {
                recommendations.add(FourOhOneKRecommendation(
                    action = "exit-now", ticker = pos.ticker, fundName = pos.fundName,
                    parentEtf = pos.parentEtf, daysHeld = pos.daysHeld, canSellWithoutFee = true,
                    reason = "${pos.parentEtf} has been a laggard for ${pos.consecutiveLaggardDays} consecutive days. Exit confirmed — no redemption fee.",
                    urgency = "high",
                ))
            } else {
                recommendations.add(FourOhOneKRecommendation(
                    action = "exit-when-eligible", ticker = pos.ticker, fundName = pos.fundName,
                    parentEtf = pos.parentEtf, daysHeld = pos.daysHeld, canSellWithoutFee = false,
                    reason = "${pos.parentEtf} lagging for ${pos.consecutiveLaggardDays} days. Exit planned — $daysUntilFree days until free of redemption fee.",
                    urgency = "medium",
                ))
            }
        } else if (pos.consecutiveLaggardDays == 1) {
            recommendations.add(FourOhOneKRecommendation(
                action = "consider-exit", ticker = pos.ticker, fundName = pos.fundName,
                parentEtf = pos.parentEtf, daysHeld = pos.daysHeld, canSellWithoutFee = pos.canSellWithoutFee,
                reason = "${pos.parentEtf} entered laggard territory today (day 1 of 2). Watch tomorrow — exit if laggard again.",
                urgency = "medium",
            ))
        } else {
            val sectorScore = (rotation.leaders + rotation.laggards).find { it.symbol == pos.parentEtf }
            val rsStr = sectorScore?.let {
                val sign = if (it.relativeStrength >= 0) "+" else ""
                " RS $sign${it.relativeStrength}% vs SPY"
            } ?: ""
            val feeNote = if (pos.canSellWithoutFee) "No fee to exit." else "$daysUntilFree days until fee-free exit."
            recommendations.add(FourOhOneKRecommendation(
                action = "hold", ticker = pos.ticker, fundName = pos.fundName,
                parentEtf = pos.parentEtf, daysHeld = pos.daysHeld, canSellWithoutFee = pos.canSellWithoutFee,
                reason = "${pos.parentEtf} rotation holding.$rsStr $feeNote",
                urgency = "low",
            ))
        }
    }

    // ── Entry opportunities ──────────────────────────────────────────────────
    val heldEtfs   = state.positions.map { it.parentEtf }.toSet()
    val hasCapacity = state.positions.size < MAX_POSITIONS && state.cashAvailable > 500

    if (hasCapacity) {
        for (leader in rotation.leaders) {
            if (leader.symbol in heldEtfs) continue
            if (leader.score < 52) continue

            val conviction = convictionFromScore(leader.score)
            val sizing     = getPositionSizeGuidance(conviction)
            val suggestedPct    = minOf(sizing.maxPct.toDouble(), MAX_POSITION_PCT * 100)
            val suggestedAmount = (state.cashAvailable * suggestedPct / 100).toInt()
            val rsSign          = if (leader.relativeStrength >= 0) "+" else ""

            for (fund in getFundsByEtf(leader.symbol)) {
                entryOpportunities.add(EntryOpportunity(
                    ticker              = fund.ticker,
                    fundName            = fund.name,
                    parentEtf           = leader.symbol,
                    parentEtfScore      = leader.score,
                    reason              = "${leader.label} (${leader.symbol}) is a rotation leader with RS $rsSign${leader.relativeStrength}% vs SPY, score ${leader.score}. Confirm 2nd consecutive leader day before entering.",
                    suggestedPositionPct = suggestedPct,
                    suggestedDollarAmount = suggestedAmount,
                ))
            }
        }
    }

    // ── Summary ───────────────────────────────────────────────────────────────
    val exitNow  = recommendations.filter { it.action == "exit-now" }
    val exitSoon = recommendations.filter { it.action == "exit-when-eligible" }
    val watching = recommendations.filter { it.action == "consider-exit" }
    val holds    = recommendations.filter { it.action == "hold" }

    if (exitNow.isNotEmpty())  summary.add("🔴 EXIT NOW: ${exitNow.joinToString(", ") { it.ticker }} — sector signal turned negative, fee-free")
    if (exitSoon.isNotEmpty()) summary.add("🟡 EXIT PLANNED: ${exitSoon.joinToString(", ") { it.ticker }} — waiting for ${MIN_HOLD_DAYS}-day hold")
    if (watching.isNotEmpty()) summary.add("👀 WATCH: ${watching.joinToString(", ") { it.ticker }} — 1st day of laggard signal, confirm tomorrow")
    if (holds.isNotEmpty())    summary.add("✅ HOLD: ${holds.joinToString(", ") { it.ticker }}")
    if (entryOpportunities.isNotEmpty()) {
        summary.add("💰 CASH AVAILABLE: ${"%.0f".format(state.cashAvailable)} — ${entryOpportunities.size} entry opportunity/ies identified")
        summary.add("📋 POTENTIAL ENTRIES: ${entryOpportunities.joinToString(", ") { "${it.ticker} (${it.parentEtf})" }}")
    }
    if (!hasCapacity && state.cashAvailable <= 500)
        summary.add("ℹ️ Fully deployed — add cash to BrokerageLink to enable new positions")

    // ── Persist updated state ────────────────────────────────────────────────
    state.lastUpdated = runDate
    state.recentActions.add(0, "$runDate: ${summary.joinToString(" | ")}")
    while (state.recentActions.size > 10) state.recentActions.removeLast()
    saveState(state)

    return FourOhOneKReport(
        date                 = runDate,
        cashAvailable        = state.cashAvailable,
        cashNote             = state.cashNote,
        totalPositions       = state.positions.size,
        positions            = state.positions.toList(),
        recommendations      = recommendations,
        entryOpportunities   = entryOpportunities,
        summary              = summary,
    )
}
