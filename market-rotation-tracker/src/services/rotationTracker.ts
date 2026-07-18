import { DailyBar, SectorScore, RotationResult } from '../types';

// Compute simple rate of change over n periods
function roc(bars: DailyBar[], periods: number): number {
    if (bars.length < periods + 1) return 0;
    const latest = bars[bars.length - 1].close;
    const prev = bars[bars.length - 1 - periods].close;
    return prev > 0 ? ((latest - prev) / prev) * 100 : 0;
}

// Relative strength: sector ROC minus benchmark ROC (SPY)
function relativeStrength(sectorBars: DailyBar[], spyBars: DailyBar[], periods = 20): number {
    return roc(sectorBars, periods) - roc(spyBars, periods);
}

export const SECTOR_UNIVERSE: { symbol: string; label: string }[] = [
    { symbol: 'SPY',  label: 'S&P 500 (Benchmark)' },
    { symbol: 'XLK',  label: 'Technology' },
    { symbol: 'XLF',  label: 'Financials' },
    { symbol: 'XLV',  label: 'Health Care' },
    { symbol: 'XLE',  label: 'Energy' },
    { symbol: 'XLI',  label: 'Industrials' },
    { symbol: 'XLC',  label: 'Communication Services' },
    { symbol: 'XLY',  label: 'Consumer Discretionary' },
    { symbol: 'XLP',  label: 'Consumer Staples' },
    { symbol: 'XLB',  label: 'Materials' },
    { symbol: 'XLU',  label: 'Utilities' },
    { symbol: 'XLRE', label: 'Real Estate' },
    { symbol: 'QQQ',  label: 'Nasdaq 100 (Growth)' },
    { symbol: 'IWM',  label: 'Russell 2000 (Small Cap)' },
    { symbol: 'SMH',  label: 'Semiconductors' },
];

export function scoreRotation(
    barsBySymbol: Map<string, DailyBar[]>,
    spyBars: DailyBar[]
): RotationResult {
    const scores: SectorScore[] = [];

    for (const sector of SECTOR_UNIVERSE) {
        const bars = barsBySymbol.get(sector.symbol);
        if (!bars || bars.length < 25) continue;

        const rs = relativeStrength(bars, spyBars, 20);
        const mom10 = roc(bars, 10);
        // Composite: 60% relative strength + 40% short-term momentum
        const composite = rs * 0.6 + mom10 * 0.4;
        // Normalise to 0-100 range (clamp between -20 and +20 raw)
        const score = Math.max(0, Math.min(100, 50 + composite * 2.5));

        scores.push({
            symbol: sector.symbol,
            label: sector.label,
            relativeStrength: parseFloat(rs.toFixed(2)),
            momentum: parseFloat(mom10.toFixed(2)),
            score: parseFloat(score.toFixed(1)),
            trend: rs > 1 ? 'strengthening' : rs < -1 ? 'weakening' : 'neutral',
        });
    }

    scores.sort((a, b) => b.score - a.score);

    const leaders = scores.slice(0, 3);
    const laggards = scores.slice(-3).reverse();
    const topMove = leaders[0]
        ? `${leaders[0].label} (${leaders[0].symbol}) leading with RS +${leaders[0].relativeStrength}% vs SPY`
        : 'No clear rotation leader';

    return {
        leaders,
        laggards,
        allScores: scores,
        topRotationMove: topMove,
        rankedAt: new Date().toISOString(),
    };
}
