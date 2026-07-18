import { MacroSeries, MacroRegime, MacroRegimeResult } from '../types';

// Classify macro regime from FRED series.
// Logic: top-down, checks the most dominant condition first.
export function detectMacroRegime(series: MacroSeries[]): MacroRegimeResult {
    const get = (id: string) => series.find(s => s.seriesId === id);

    const ff    = get('DFF');
    const spread = get('T10Y2Y');
    const cpi   = get('CPIAUCSL');
    const unrate = get('UNRATE');
    const indpro = get('INDPRO');

    const factors: string[] = [];
    let regime: MacroRegime = 'unknown';
    let confidence: 'high' | 'medium' | 'low' = 'low';

    // ── Rule 1: Inflation pressure ────────────────────────────────────────────
    // CPI rising AND Fed Funds rising
    if (cpi?.trend === 'rising' && ff?.trend === 'rising') {
        regime = 'inflation-pressure';
        factors.push(`CPI trending ${cpi.trend} (${cpi.latestValue.toFixed(2)})`);
        factors.push(`Fed Funds trending ${ff.trend} (${ff.latestValue.toFixed(2)}%)`);
        confidence = (cpi.latestValue > 4) ? 'high' : 'medium';
    }

    // ── Rule 2: Risk-off slowdown ─────────────────────────────────────────────
    // Inverted yield curve (spread < 0) OR unemployment rising + indpro falling
    else if ((spread && spread.latestValue < 0) ||
             (unrate?.trend === 'rising' && indpro?.trend === 'falling')) {
        regime = 'risk-off-slowdown';
        if (spread && spread.latestValue < 0) {
            factors.push(`Yield curve inverted (${spread.latestValue.toFixed(2)}%)`);
        }
        if (unrate?.trend === 'rising') {
            factors.push(`Unemployment rising (${unrate.latestValue.toFixed(1)}%)`);
        }
        if (indpro?.trend === 'falling') {
            factors.push('Industrial production declining');
        }
        confidence = (spread && spread.latestValue < -0.5) ? 'high' : 'medium';
    }

    // ── Rule 3: Disinflation recovery ─────────────────────────────────────────
    // CPI falling AND Fed Funds flat or falling AND spread improving
    else if (cpi?.trend === 'falling' && (ff?.trend === 'falling' || ff?.trend === 'flat')) {
        regime = 'disinflation-recovery';
        factors.push(`CPI trending down (${cpi.latestValue.toFixed(2)})`);
        if (ff) factors.push(`Fed Funds ${ff.trend} (${ff.latestValue.toFixed(2)}%)`);
        if (spread && spread.latestValue > 0) {
            factors.push(`Yield curve positive (${spread.latestValue.toFixed(2)}%)`);
        }
        confidence = 'medium';
    }

    // ── Rule 4: Risk-on growth ────────────────────────────────────────────────
    // Spread positive, indpro rising, unemployment flat or falling
    else if ((spread && spread.latestValue > 0.5) &&
             indpro?.trend === 'rising' &&
             (unrate?.trend === 'falling' || unrate?.trend === 'flat')) {
        regime = 'risk-on-growth';
        factors.push(`Yield curve healthy (${spread?.latestValue.toFixed(2)}%)`);
        factors.push('Industrial production expanding');
        if (unrate) factors.push(`Unemployment ${unrate.trend} (${unrate.latestValue.toFixed(1)}%)`);
        confidence = 'high';
    }

    // ── Fallback ──────────────────────────────────────────────────────────────
    else {
        regime = 'unknown';
        factors.push('Mixed or inconclusive macro signals');
        confidence = 'low';
    }

    const labels: Record<MacroRegime, string> = {
        'risk-on-growth':       'Risk-On Growth',
        'risk-off-slowdown':    'Risk-Off Slowdown',
        'inflation-pressure':   'Inflation Pressure',
        'disinflation-recovery':'Disinflation Recovery',
        'unknown':              'Unknown / Mixed',
    };

    return { regime, label: labels[regime], factors, confidence };
}
