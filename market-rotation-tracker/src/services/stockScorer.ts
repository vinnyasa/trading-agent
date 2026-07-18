import { DailyBar, SectorScore, StockSignal } from '../types';
import { scorePressurePoints } from './pressurePoints';
import { STOCK_WATCHLIST } from '../config/stockWatchlist';

// Score individual stocks for the top leader sectors.
// Uses parent sector rotation strength as macro/rotation proxy,
// then pressure points and RSI for timing.
export function scoreStockWatchlist(
    leaders: SectorScore[],
    barsBySymbol: Map<string, DailyBar[]>
): StockSignal[] {
    const signals: StockSignal[] = [];
    const seen = new Set<string>(); // deduplicate (e.g. NVDA in XLK + SMH)

    for (const sector of leaders) {
        const stocks = STOCK_WATCHLIST[sector.symbol];
        if (!stocks) continue;

        for (const stock of stocks) {
            if (seen.has(stock.symbol)) continue;
            seen.add(stock.symbol);

            const bars = barsBySymbol.get(stock.symbol);
            if (!bars || bars.length < 20) continue;

            const pressure = scorePressurePoints(stock.symbol, bars);

            // ── Score components ─────────────────────────────────────────────
            let score = 0;

            // Parent sector rotation strength (40%)
            // Inherit from the sector's RS — the sector is already a leader
            const rs = sector.relativeStrength;
            score += rs > 3 ? 40 : rs > 1 ? 30 : 20;

            // Pressure point quality (40%) — strong signals are high-value setups
            const strongPts  = pressure.points.filter(p => p.strength === 'strong').length;
            const moderatePts = pressure.points.filter(p => p.strength === 'moderate').length;
            score += Math.min(40, strongPts * 15 + moderatePts * 8);

            // RSI timing (20%) — favor neutral/mild RSI for clean entries
            const rsi = pressure.rsi;
            if (rsi >= 45 && rsi <= 65)      score += 20; // ideal entry zone
            else if (rsi > 65 && rsi < 72)   score += 10; // mildly extended, still ok
            else if (rsi >= 30 && rsi < 45)  score += 14; // mild pullback in uptrend
            else if (rsi < 30)               score += 16; // oversold — potential bounce
            else                             score += 3;  // overbought (>72) — avoid

            score = Math.min(100, Math.round(score));

            const conviction =
                score >= 70 ? 'high'   :
                score >= 50 ? 'medium' :
                score >= 30 ? 'low'    :
                'noise';

            const pressureSummary = pressure.points.length > 0
                ? pressure.points.map(p => p.description).join('; ')
                : 'No significant pressure points';

            const reasoning = [
                `Parent: ${sector.label} (RS ${rs > 0 ? '+' : ''}${rs}% vs SPY, ${sector.trend})`,
                `Pressure: ${pressureSummary}`,
                `RSI: ${rsi}`,
            ].join(' | ');

            signals.push({
                symbol: stock.symbol,
                label: stock.label,
                parentSector: sector.symbol,
                parentSectorLabel: sector.label,
                parentRotationScore: sector.score,
                pressurePoints: pressure.points,
                rsi,
                score,
                conviction,
                reasoning,
            });
        }
    }

    return signals
        .filter(s => s.conviction !== 'noise')
        .sort((a, b) => b.score - a.score);
}
