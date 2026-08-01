package com.rotationtracker

import com.rotationtracker.config.STOCK_WATCHLIST
import com.rotationtracker.models.*
import com.rotationtracker.services.*
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

private val REPORTS_DIR = "reports/daily"
private val SIGNAL_LOG  = "reports/signal-log.jsonl"

private val logJson = Json { encodeDefaults = true }

/** Returns today's date in YYYY-MM-DD format anchored to US/Eastern time. */
private fun todayStr(): String =
    LocalDate.now(ZoneId.of("America/New_York")).toString()

private fun appendSignalLog(entries: List<SignalLogEntry>) {
    val lines = entries.joinToString("\n") { logJson.encodeToString(it) } + "\n"
    File(SIGNAL_LOG).also { it.parentFile?.mkdirs() }.appendText(lines)
}

fun main() = runBlocking {
    // Load .env if present (local development); GitHub Actions uses real env vars
    runCatching { dotenv { ignoreIfMissing = true } }

    val runDate      = todayStr()
    val runTimestamp = java.time.Instant.now().toString()
    val warnings     = mutableListOf<String>()

    println("[batch] Starting daily run for $runDate")

    // ── 1. Fetch macro data ───────────────────────────────────────────────────
    println("[batch] Fetching FRED macro series...")
    val macroSeries = try {
        fetchMacroSeries()
    } catch (e: Exception) {
        warnings.add("FRED fetch failed: ${e.message}")
        emptyList()
    }
    val macroRegime = detectMacroRegime(macroSeries)
    println("[batch] Macro regime: ${macroRegime.label} (${macroRegime.confidence})")

    // ── 2. Fetch sector/ETF universe ──────────────────────────────────────────
    val allSymbols = (listOf("SPY") + SECTOR_UNIVERSE.map { it.first }).distinct()
    println("[batch] Fetching daily bars for ${allSymbols.size} symbols...")
    val (barsBySymbol, failed, primarySource) = fetchUniverseBars(allSymbols, 60)

    if (failed.isNotEmpty()) {
        warnings.add("Failed to fetch data for: ${failed.joinToString(", ")}")
        println("[batch] Failed symbols: ${failed.joinToString(", ")}")
    }

    val spyBars = barsBySymbol["SPY"] ?: emptyList()
    if (spyBars.isEmpty())
        warnings.add("SPY data unavailable — rotation scores will be unreliable")

    // ── 3. Score rotation ─────────────────────────────────────────────────────
    println("[batch] Computing rotation scores...")
    val rotation = scoreRotation(barsBySymbol, spyBars)

    // ── 3b. Fetch short interest (FINRA biweekly public file, no API key) ─────
    println("[batch] Fetching FINRA short interest data...")
    val shortInterestUniverse = (allSymbols + STOCK_WATCHLIST.values.flatten().map { it.first }).toSet()
    val shortInterestBySymbol = try {
        fetchShortInterest(shortInterestUniverse)
    } catch (e: Exception) {
        warnings.add("FINRA short interest fetch failed: ${e.message}")
        emptyMap()
    }
    if (shortInterestBySymbol.isEmpty())
        println("[batch] No short interest data available for this run")

    // ── 4. Score pressure points + confluence ─────────────────────────────────
    println("[batch] Scoring pressure points and confluence...")
    val signals    = mutableListOf<ConfluenceSignal>()
    val logEntries = mutableListOf<SignalLogEntry>()
    val ALWAYS_INCLUDE = setOf("QQQ", "SPY", "IWM")

    for ((symbol, _) in SECTOR_UNIVERSE) {
        val bars        = barsBySymbol[symbol] ?: continue
        if (bars.size < 20) continue
        val pressure    = scorePressurePoints(symbol, bars, shortInterestBySymbol[symbol])
        val sectorScore = rotation.allScores.find { it.symbol == symbol } ?: continue
        val signal      = scoreConfluence(sectorScore, pressure, macroRegime)
        signals.add(signal)
        logEntries.add(SignalLogEntry(
            date           = runDate,
            symbol         = symbol,
            signalType     = "confluence",
            value          = signal.score.toDouble(),
            regime         = macroRegime.regime,
            confluenceScore = signal.score,
        ))
    }

    val topSignals = signals
        .filter  { it.conviction != "noise" }
        .sortedByDescending { it.score }
        .take(8)
        .toMutableList()

    // Guarantee always-include symbols appear in the report
    for (sym in ALWAYS_INCLUDE) {
        if (topSignals.none { it.symbol == sym }) {
            signals.find { it.symbol == sym }?.let { topSignals.add(it) }
        }
    }

    appendSignalLog(logEntries)

    // ── 5. Fetch + score individual stocks for leader sectors ─────────────────
    // Union of rotation.leaders (top-3 by raw RS+momentum) and the top-3 sectors
    // by confluence score (which also weighs pressure points / short interest /
    // RSI quality) — a sector can score highest on confluence without making the
    // raw rotation-leaders cutoff (e.g. slightly negative momentum), and we still
    // want its stock watchlist scored in that case.
    val topConfluenceSectors = signals
        .sortedByDescending { it.score }
        .take(3)
        .mapNotNull { sig -> rotation.allScores.find { it.symbol == sig.symbol } }

    val stockScoringSectors = (rotation.leaders + topConfluenceSectors).distinctBy { it.symbol }

    val stockSymbols = mutableListOf<String>()
    val seenStocks   = mutableSetOf<String>()
    for (leader in stockScoringSectors) {
        for ((sym, _) in STOCK_WATCHLIST[leader.symbol] ?: emptyList()) {
            if (seenStocks.add(sym)) stockSymbols.add(sym)
        }
    }

    var stockSignals = emptyList<com.rotationtracker.models.StockSignal>()
    if (stockSymbols.isNotEmpty()) {
        println("[batch] Fetching ${stockSymbols.size} stocks for leader sectors...")
        val (stockBars, stockFailed, _) = fetchUniverseBars(stockSymbols, 60)
        if (stockFailed.isNotEmpty())
            warnings.add("Stock fetch failed: ${stockFailed.joinToString(", ")}")
        barsBySymbol.putAll(stockBars)
        stockSignals = scoreStockWatchlist(stockScoringSectors, barsBySymbol, shortInterestBySymbol)
        println("[batch] Stock signals: ${stockSignals.size} scored")
    }

    // ── 6. Update 401k tracker ────────────────────────────────────────────────
    println("[batch] Updating 401k tracker...")
    val fourOhOneK = updateFourOhOneK(rotation, runDate)
    println("[batch] 401k summary: ${fourOhOneK.summary.joinToString(" | ")}")

    // ── 7. Build and save report ──────────────────────────────────────────────
    val report = DailyReport(
        runDate           = runDate,
        runTimestamp      = runTimestamp,
        dataSourceStatus  = DataSourceStatus(
            primary        = primarySource,
            symbolsLoaded  = barsBySymbol.size,
            symbolsFailed  = failed,
        ),
        macroRegime       = macroRegime,
        rotation          = rotation,
        topSignals        = topSignals,
        stockSignals      = stockSignals,
        watchlistChanges  = buildWatchlistChanges(topSignals),
        warnings          = warnings,
        fourOhOneK        = fourOhOneK,
    )

    val savedPath = saveReport(report, REPORTS_DIR)
    println("[batch] Report saved: $savedPath")
    println("[batch] Top signal: ${topSignals.firstOrNull()?.symbol ?: "none"} — ${topSignals.firstOrNull()?.score ?: 0}/100 (${topSignals.firstOrNull()?.conviction ?: "n/a"})")
    println("[batch] Done.")
}
