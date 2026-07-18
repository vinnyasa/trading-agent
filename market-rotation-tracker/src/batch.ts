import 'dotenv/config';
import * as fs from 'fs';
import * as path from 'path';
import { fetchUniverseBars } from './services/marketData';
import { fetchMacroSeries } from './services/fredData';
import { detectMacroRegime } from './services/macroRegime';
import { scoreRotation, SECTOR_UNIVERSE } from './services/rotationTracker';
import { scorePressurePoints } from './services/pressurePoints';
import { scoreConfluence } from './services/confluenceScorer';
import { scoreStockWatchlist } from './services/stockScorer';
import { saveReport, buildWatchlistChanges } from './services/reportGenerator';
import { updateFourOhOneK } from './services/fourOhOneKTracker';
import { DailyReport, ConfluenceSignal, SignalLogEntry, MacroSeries, StockSignal } from './types';
import { STOCK_WATCHLIST } from './config/stockWatchlist';

const REPORTS_DIR = path.join(__dirname, '..', 'reports', 'daily');
const SIGNAL_LOG  = path.join(__dirname, '..', 'reports', 'signal-log.jsonl');

// Report dates are anchored to the US market timezone (America/New_York), not the
// host/container's local time or UTC. Without this, a container running in UTC
// (the Docker default) rolls over to the next calendar day as early as 8pm ET
// (5pm PT), causing reports to be dated a day ahead of the actual trading day.
function todayStr(): string {
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York' }).format(new Date());
}

function appendSignalLog(entries: SignalLogEntry[]): void {
    const lines = entries.map(e => JSON.stringify(e)).join('\n') + '\n';
    fs.mkdirSync(path.dirname(SIGNAL_LOG), { recursive: true });
    fs.appendFileSync(SIGNAL_LOG, lines, 'utf-8');
}

async function run(): Promise<void> {
    const runDate = todayStr();
    const runTimestamp = new Date().toISOString();
    const warnings: string[] = [];

    console.log(`[batch] Starting daily run for ${runDate}`);

    // ── 1. Fetch macro data ─────────────────────────────────────────────────
    console.log('[batch] Fetching FRED macro series...');
    let macroSeries: MacroSeries[] = [];
    try {
        macroSeries = await fetchMacroSeries();
    } catch (e) {
        warnings.push(`FRED fetch failed: ${(e as Error).message}`);
    }
    const macroRegime = detectMacroRegime(macroSeries);
    console.log(`[batch] Macro regime: ${macroRegime.label} (${macroRegime.confidence})`);

    // ── 2. Fetch stock/ETF universe ─────────────────────────────────────────
    const allSymbols = Array.from(new Set(['SPY', ...SECTOR_UNIVERSE.map(s => s.symbol)]));
    console.log(`[batch] Fetching daily bars for ${allSymbols.length} symbols...`);
    const { results: barsBySymbol, failed, primarySource } = await fetchUniverseBars(allSymbols, 60);

    if (failed.length > 0) {
        warnings.push(`Failed to fetch data for: ${failed.join(', ')}`);
        console.warn(`[batch] Failed symbols: ${failed.join(', ')}`);
    }

    const spyBars = barsBySymbol.get('SPY') ?? [];
    if (spyBars.length === 0) {
        warnings.push('SPY data unavailable — rotation scores will be unreliable');
    }

    // ── 3. Score rotation ───────────────────────────────────────────────────
    console.log('[batch] Computing rotation scores...');
    const rotation = scoreRotation(barsBySymbol, spyBars);

    // ── 4. Score pressure points + confluence for each sector ───────────────
    console.log('[batch] Scoring pressure points and confluence...');
    const signals: ConfluenceSignal[] = [];
    const logEntries: SignalLogEntry[] = [];

    // Always report on these regardless of leader/laggard ranking
    const ALWAYS_INCLUDE = ['QQQ', 'SPY', 'IWM'];

    for (const sector of SECTOR_UNIVERSE) {
        const bars = barsBySymbol.get(sector.symbol);
        if (!bars || bars.length < 20) continue;

        const pressure = scorePressurePoints(sector.symbol, bars);
        const sectorScore = rotation.allScores.find(s => s.symbol === sector.symbol);

        if (!sectorScore) continue;

        const signal = scoreConfluence(sectorScore, pressure, macroRegime);
        signals.push(signal);

        // Log each signal independently for future weight tuning
        logEntries.push({
            date: runDate,
            symbol: sector.symbol,
            signalType: 'confluence',
            value: signal.score,
            regime: macroRegime.regime,
            confluenceScore: signal.score,
        });
    }

    // Sort by score descending, filter noise
    const topSignals = signals
        .filter(s => s.conviction !== 'noise')
        .sort((a, b) => b.score - a.score)
        .slice(0, 8);

    // Guarantee always-include symbols show up in the report, even if noise/low score
    for (const sym of ALWAYS_INCLUDE) {
        if (!topSignals.find(s => s.symbol === sym)) {
            const sig = signals.find(s => s.symbol === sym);
            if (sig) topSignals.push(sig);
        }
    }

    appendSignalLog(logEntries);

    // ── 5. Fetch + score individual stocks for leader sectors ────────────────────
    // Collect unique stock symbols from watchlist for leader sectors only
    const stockSymbols: string[] = [];
    const seenStocks = new Set<string>();
    for (const leader of rotation.leaders) {
        const stocks = STOCK_WATCHLIST[leader.symbol] ?? [];
        for (const s of stocks) {
            if (!seenStocks.has(s.symbol)) {
                seenStocks.add(s.symbol);
                stockSymbols.push(s.symbol);
            }
        }
    }

    let stockSignals: StockSignal[] = [];
    if (stockSymbols.length > 0) {
        console.log(`[batch] Fetching ${stockSymbols.length} stocks for leader sectors...`);
        const { results: stockBars, failed: stockFailed } = await fetchUniverseBars(stockSymbols, 60);
        if (stockFailed.length > 0) {
            warnings.push(`Stock fetch failed: ${stockFailed.join(', ')}`);
        }
        // Merge stock bars into barsBySymbol for scoring
        for (const [sym, bars] of stockBars) {
            barsBySymbol.set(sym, bars);
        }
        stockSignals = scoreStockWatchlist(rotation.leaders, barsBySymbol);
        console.log(`[batch] Stock signals: ${stockSignals.length} scored`);
    }

    // ── 6. Update 401k tracker ────────────────────────────────────────────────────
    console.log('[batch] Updating 401k tracker...');
    const fourOhOneK = updateFourOhOneK(rotation, runDate);
    console.log(`[batch] 401k summary: ${fourOhOneK.summary.join(' | ')}`);

    // ── 7. Build and save report ──────────────────────────────────────────────────
    const report: DailyReport = {
        runDate,
        runTimestamp,
        dataSourceStatus: {
            primary: primarySource,
            symbolsLoaded: barsBySymbol.size,
            symbolsFailed: failed,
        },
        macroRegime,
        rotation,
        topSignals,
        stockSignals,
        watchlistChanges: buildWatchlistChanges(topSignals),
        warnings,
        fourOhOneK,
    };

    const savedPath = saveReport(report, REPORTS_DIR);
    console.log(`[batch] Report saved: ${savedPath}`);
    console.log(`[batch] Top signal: ${topSignals[0]?.symbol ?? 'none'} — ${topSignals[0]?.score ?? 0}/100 (${topSignals[0]?.conviction ?? 'n/a'})`);
    console.log('[batch] Done.');
}

run().catch(err => {
    console.error('[batch] Fatal error:', err);
    process.exit(1);
});
