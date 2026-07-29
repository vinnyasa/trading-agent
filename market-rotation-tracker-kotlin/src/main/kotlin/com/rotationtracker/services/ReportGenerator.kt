package com.rotationtracker.services

import com.rotationtracker.models.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

private val reportJson = Json {
    prettyPrint      = true
    encodeDefaults   = true
}

private fun convictionEmoji(c: String) = when (c) {
    "high"   -> "🟢"
    "medium" -> "🟡"
    "low"    -> "🔴"
    else     -> "⚪"
}

// ── Action summary ────────────────────────────────────────────────────────────
private fun generateActionSummary(report: DailyReport): List<String> {
    val lines         = mutableListOf<String>()
    val regime        = report.macroRegime.regime
    val leaders       = report.rotation.leaders
    val laggards      = report.rotation.laggards
    val topSignals    = report.topSignals
    val stockSignals  = report.stockSignals

    val macroRead = mapOf(
        "risk-on-growth"        to "Macro is risk-on growth — favor cyclicals, tech, financials. Reduce defensive exposure.",
        "risk-off-slowdown"     to "Macro is risk-off — favor defensives (XLV, XLP, XLU). Reduce cyclical and growth exposure.",
        "inflation-pressure"    to "Inflation-pressure regime — favor energy (XLE), materials (XLB), short-duration assets. Reduce rate-sensitive sectors.",
        "disinflation-recovery" to "Disinflation in progress — favor rate-sensitive sectors (XLRE, XLU, XLV). Bond proxies re-rating.",
        "unknown"               to "Macro regime unclear — reduce position sizing, wait for confirmation before new entries.",
    )
    lines.add("**Macro:** ${macroRead[regime] ?: macroRead["unknown"]!!}")
    lines.add("")
    lines.add("**Position sizing guide:** High conviction 15–20% of account · Medium 8–12% · Low 3–5% · Noise/none, sit out. (401k fund positions are additionally capped at 35% each.)")
    lines.add("")

    if (leaders.isNotEmpty()) {
        val names = leaders.joinToString(", ") {
            val sign = if (it.relativeStrength >= 0) "+" else ""
            "${it.symbol} (${it.label}, RS $sign${it.relativeStrength}%)"
        }
        lines.add("**Rotation leaders:** $names")
    }
    if (laggards.isNotEmpty()) {
        val names = laggards.joinToString(", ") { "${it.symbol} (RS ${it.relativeStrength}%)" }
        lines.add("**Avoid / underweight:** $names")
    }
    lines.add("")

    val highEtf   = topSignals.filter { it.conviction == "high" }
    val mediumEtf = topSignals.filter { it.conviction == "medium" }

    if (highEtf.isNotEmpty()) {
        highEtf.forEach { s ->
            val squeeze    = s.pressurePoints.find { it.type.startsWith("bollinger-squeeze") }
            val overbought = s.pressurePoints.find { it.type == "rsi-overbought" }
            val resistance = s.pressurePoints.find { it.type == "resistance" }
            val support    = s.pressurePoints.find { it.type == "support" }
            val emaPullback = s.pressurePoints.find { it.type.startsWith("ema-pullback") }
            var waitForTrigger = false
            var body = when {
                overbought != null && resistance != null -> {
                    waitForTrigger = true
                    "RSI extended and near resistance — do not chase. Wait for pullback or resistance break with volume."
                }
                overbought != null -> "RSI overbought at ${s.rsi.toInt()} — rotation is strong but price may need to rest. Tighten stops if already long."
                resistance != null -> "Near resistance — watch for breakout. Entry on close above resistance with volume confirmation."
                squeeze?.type == "bollinger-squeeze-up" -> "Squeeze with upward bias — volatility coiling. Watch for breakout candle above recent range. RSI ${s.rsi.toInt()}."
                squeeze?.type == "bollinger-squeeze-down" -> {
                    waitForTrigger = true
                    "Squeeze with downward bias — wait on a long. Bearish alternative: puts on ${s.symbol} or an inverse sector ETF, sized small, until direction confirms."
                }
                support != null -> "Near support with rotation strength — potential long entry with stop below ${"%.2f".format(support.level)}."
                else -> "Clean rotation signal, RSI ${s.rsi.toInt()} — trend entry. No major overhead resistance."
            }
            if (emaPullback != null) {
                val period = emaPullback.type.substringAfterLast("-")
                body += " Also worth noting: pulled back to the $period-EMA (${"%.2f".format(emaPullback.level)}) after breaking out — a better risk/reward add point than chasing here."
            }
            val sizeNote = if (waitForTrigger)
                "_No long entry now — if it triggers, size at: ${getPositionSizeGuidance(s.conviction).label}._"
            else
                "_Suggested size: ${getPositionSizeGuidance(s.conviction).label}._"
            lines.add("**${s.symbol} — HIGH conviction (${s.score}/100):** $body $sizeNote")
        }
    }

    mediumEtf.forEach { s ->
        lines.add("**${s.symbol} — MEDIUM conviction (${s.score}/100):** Monitor. RS ${s.rotationTrend}, RSI ${s.rsi.toInt()}. _Suggested size: ${getPositionSizeGuidance(s.conviction).label}._")
    }

    if (highEtf.isEmpty() && mediumEtf.isEmpty())
        lines.add("**No high or medium conviction ETF setups today.** Reduce exposure, wait for cleaner signals.")
    lines.add("")

    val highStocks = stockSignals.filter { it.conviction == "high" }
    if (highStocks.isNotEmpty()) {
        lines.add("**Top stock setups:**")
        highStocks.forEach { s ->
            val squeeze    = s.pressurePoints.find { it.type.startsWith("bollinger-squeeze") }
            val overbought = s.pressurePoints.find { it.type == "rsi-overbought" }
            val oversold   = s.pressurePoints.find { it.type == "rsi-oversold" }
            val emaPullback = s.pressurePoints.find { it.type.startsWith("ema-pullback") }
            var waitForTrigger = false
            var note = when {
                overbought != null -> { waitForTrigger = true; "RSI extended — wait for pullback" }
                oversold != null   -> "RSI oversold in a leading sector — potential bounce entry"
                squeeze?.type == "bollinger-squeeze-up"   -> "Squeeze-up in strong sector — watch for breakout"
                squeeze?.type == "bollinger-squeeze-down" -> { waitForTrigger = true; "Squeeze-down — hold off on a long. Bearish alt: puts on ${s.symbol} or an inverse ETF" }
                else -> "RSI ${s.rsi.toInt()}, sector rotation supporting"
            }
            if (emaPullback != null) {
                val period = emaPullback.type.substringAfterLast("-")
                note += " (also pulled back to $period-EMA after breakout — better risk/reward entry)"
            }
            val sizeNote = if (waitForTrigger)
                "no long entry yet — if it triggers, size at ${getPositionSizeGuidance(s.conviction).label}"
            else
                "size: ${getPositionSizeGuidance(s.conviction).label}"
            lines.add("- **${s.symbol}** (${s.label}, ${s.parentSector}): $note — Score ${s.score}/100 _(${sizeNote})_")
        }
        lines.add("")
    }

    return lines
}

fun generateMarkdownReport(report: DailyReport): String {
    val lines = mutableListOf<String>()

    lines.add("# Market Rotation Daily Report — ${report.runDate}")
    lines.add("")
    lines.add("Run: ${report.runTimestamp}")
    lines.add("Data source: ${report.dataSourceStatus.primary} | Symbols loaded: ${report.dataSourceStatus.symbolsLoaded}")
    if (report.dataSourceStatus.symbolsFailed.isNotEmpty())
        lines.add("⚠ Failed symbols: ${report.dataSourceStatus.symbolsFailed.joinToString(", ")}")
    lines.add("")

    lines.add("## Today's Action Summary")
    lines.add("")
    lines.addAll(generateActionSummary(report))

    lines.add("## Macro Regime")
    lines.add("")
    lines.add("**${report.macroRegime.label}** (${report.macroRegime.confidence} confidence)")
    lines.add("")
    report.macroRegime.factors.forEach { lines.add("- $it") }
    lines.add("")

    lines.add("## Sector Rotation")
    lines.add("")
    lines.add("**${report.rotation.topRotationMove}**")
    lines.add("")
    lines.add("| Rank | Symbol | Sector | RS vs SPY | Momentum | Score | Trend |")
    lines.add("|---|---|---|---|---|---|---|")
    (report.rotation.leaders + report.rotation.laggards).forEachIndexed { i, s ->
        val tag   = if (i < 3) "▲ Leader" else "▼ Laggard"
        val rsSign = if (s.relativeStrength >= 0) "+" else ""
        val mSign  = if (s.momentum >= 0) "+" else ""
        lines.add("| $tag | ${s.symbol} | ${s.label} | $rsSign${s.relativeStrength}% | $mSign${s.momentum}% | ${s.score} | ${s.trend} |")
    }
    lines.add("")

    lines.add("## Top Confluence Signals")
    lines.add("")
    if (report.topSignals.isEmpty()) {
        lines.add("No high or medium conviction signals today.")
    } else {
        report.topSignals.forEach { sig ->
            lines.add("### ${convictionEmoji(sig.conviction)} ${sig.symbol} — Score: ${sig.score}/100 (${sig.conviction.uppercase()})")
            lines.add("")
            lines.add(sig.reasoning)
            lines.add("")
            lines.add("**Suggested position size:** ${getPositionSizeGuidance(sig.conviction).label}")
            if (sig.pressurePoints.isNotEmpty()) {
                lines.add("")
                lines.add("**Pressure points:**")
                sig.pressurePoints.forEach { p -> lines.add("- [${p.strength}] ${p.description}") }
            }
            lines.add("")
        }
    }

    if (report.stockSignals.isNotEmpty()) {
        lines.add("## Stock Signals (Leader Sectors)")
        lines.add("")
        lines.add("> Stocks within today's leading sectors, scored for entry timing.")
        lines.add("")
        var lastSector = ""
        for (sig in report.stockSignals) {
            if (sig.parentSector != lastSector) {
                lines.add("### ${sig.parentSectorLabel} (${sig.parentSector})")
                lines.add("")
                lastSector = sig.parentSector
            }
            lines.add("#### ${convictionEmoji(sig.conviction)} ${sig.symbol} — ${sig.label} — Score: ${sig.score}/100 (${sig.conviction.uppercase()})")
            lines.add("")
            lines.add(sig.reasoning)
            lines.add("")
            lines.add("**Suggested position size:** ${getPositionSizeGuidance(sig.conviction).label}")
            if (sig.pressurePoints.isNotEmpty()) {
                lines.add("")
                lines.add("**Pressure points:**")
                sig.pressurePoints.forEach { p -> lines.add("- [${p.strength}] ${p.description}") }
            }
            lines.add("")
        }
    }

    if (report.watchlistChanges.isNotEmpty()) {
        lines.add("## Watchlist Changes")
        lines.add("")
        report.watchlistChanges.forEach { lines.add("- $it") }
        lines.add("")
    }

    val k = report.fourOhOneK
    if (k != null) {
        lines.add("## 401k / BrokerageLink Tracker")
        lines.add("")
        lines.add("**Cash available:** ${"$%,.0f".format(k.cashAvailable)}")
        if (k.cashNote.isNotBlank()) lines.add("> ${k.cashNote}")
        lines.add("")
        k.summary.forEach { lines.add(it) }
        lines.add("")

        if (k.positions.isNotEmpty()) {
            lines.add("### Current Positions")
            lines.add("")
            lines.add("| Fund | ETF Signal | Days Held | Fee-Free? | Leader Days | Laggard Days |")
            lines.add("|---|---|---|---|---|---|")
            k.positions.forEach { p ->
                val feeFree = if (p.canSellWithoutFee) "✅ Yes" else "⏳ ${maxOf(0, 30 - p.daysHeld)}d"
                lines.add("| ${p.ticker} — ${p.fundName} | ${p.parentEtf} | ${p.daysHeld} | $feeFree | ${p.consecutiveLeaderDays} | ${p.consecutiveLaggardDays} |")
            }
            lines.add("")
        }

        if (k.recommendations.isNotEmpty()) {
            lines.add("### Recommendations")
            lines.add("")
            k.recommendations.forEach { r ->
                val icon = when (r.action) {
                    "exit-now"          -> "🔴"
                    "exit-when-eligible" -> "🟠"
                    "consider-exit"     -> "🟡"
                    else                -> "✅"
                }
                lines.add("$icon **${r.ticker}** (${r.action.uppercase()}): ${r.reason}")
            }
            lines.add("")
        }

        if (k.entryOpportunities.isNotEmpty()) {
            lines.add("### Entry Opportunities")
            lines.add("")
            lines.add("> Confirm 2 consecutive leader days before entering. Check RSI not overbought. Sizing suggestions are % of cash available, capped at 35% per fund.")
            lines.add("")
            k.entryOpportunities.forEach { e ->
                lines.add("- **${e.ticker}** — ${e.fundName} (${e.parentEtf}, score ${e.parentEtfScore}): ${e.reason} _Suggested: ${e.suggestedPositionPct}% of cash (~${"$%,.0f".format(e.suggestedDollarAmount.toDouble())})._")
            }
            lines.add("")
        }
    }

    if (report.warnings.isNotEmpty()) {
        lines.add("## Warnings")
        lines.add("")
        report.warnings.forEach { lines.add("⚠ $it") }
        lines.add("")
    }

    lines.add("---")
    lines.add("*Signal summarizer only. Not financial advice. Final decisions are yours.*")

    return lines.joinToString("\n")
}

// Distinguishes the pre-open and post-close runs so the same calendar day's two
// reports don't overwrite each other. Threshold of 16:00 UTC sits comfortably
// between the pre-open cron (~11:13 UTC) and post-close cron (~21:07 UTC),
// with margin for GitHub Actions scheduling delay.
private fun sessionLabel(runTimestamp: String): String {
    val hour = Instant.parse(runTimestamp).atZone(ZoneOffset.UTC).hour
    return if (hour < 16) "premarket" else "postclose"
}

fun saveReport(report: DailyReport, outputDir: String): String {
    File(outputDir).mkdirs()
    val session  = sessionLabel(report.runTimestamp)
    val mdPath   = "$outputDir/${report.runDate}-$session.md"
    val jsonPath = "$outputDir/${report.runDate}-$session.json"
    File(mdPath).writeText(generateMarkdownReport(report))
    File(jsonPath).writeText(reportJson.encodeToString(report))
    return mdPath
}

fun buildWatchlistChanges(signals: List<ConfluenceSignal>): List<String> =
    signals
        .filter { it.conviction == "high" || it.conviction == "medium" }
        .take(5)
        .map { "${it.symbol}: ${if (it.conviction == "high") "ADD to watchlist" else "Monitor"} — score ${it.score}/100" }
