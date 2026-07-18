import { SectorScore, PressureResult, MacroRegimeResult, ConfluenceSignal } from '../types';

export function scoreConfluence(
    sector: SectorScore,
    pressure: PressureResult,
    regime: MacroRegimeResult
): ConfluenceSignal {
    let score = 0;

    // ── Macro regime alignment (35%) ─────────────────────────────────────────
    // Risk-on regime boosts rotation leaders; risk-off boosts defensives
    const offensiveSectors = ['XLK', 'XLY', 'XLF', 'XLI', 'QQQ'];
    const defensiveSectors = ['XLU', 'XLP', 'XLRE', 'XLV'];
    const isOffensive = offensiveSectors.includes(sector.symbol);
    const isDefensive = defensiveSectors.includes(sector.symbol);

    if (regime.regime === 'risk-on-growth' && isOffensive) score += 35;
    else if (regime.regime === 'risk-off-slowdown' && isDefensive) score += 35;
    else if (regime.regime === 'disinflation-recovery' && (isOffensive || isDefensive)) score += 25;
    else if (regime.regime === 'inflation-pressure' && sector.symbol === 'XLE') score += 30;
    else score += 10; // partial credit for any regime signal

    // ── Rotation strength (30%) ───────────────────────────────────────────────
    if (sector.trend === 'strengthening') {
        score += sector.relativeStrength > 3 ? 30 : sector.relativeStrength > 1 ? 20 : 10;
    } else if (sector.trend === 'weakening') {
        score += 0;
    } else {
        score += 10;
    }

    // ── Pressure point quality (25%) ─────────────────────────────────────────
    const strongPoints = pressure.points.filter(p => p.strength === 'strong').length;
    const moderatePoints = pressure.points.filter(p => p.strength === 'moderate').length;
    score += Math.min(25, strongPoints * 10 + moderatePoints * 5);

    // ── Momentum confirmation (10%) ───────────────────────────────────────────
    if (pressure.rsi >= 50 && pressure.rsi <= 65) score += 10;  // healthy momentum
    else if (pressure.rsi > 65 && pressure.rsi < 70) score += 5;
    else if (pressure.rsi < 40) score += 3; // oversold can be a setup too

    score = Math.min(100, Math.round(score));

    const conviction =
        score >= 70 ? 'high' :
        score >= 50 ? 'medium' :
        score >= 30 ? 'low' :
        'noise';

    const reasoning = [
        `Regime: ${regime.label}`,
        `Rotation: ${sector.label} ${sector.trend} (RS ${sector.relativeStrength > 0 ? '+' : ''}${sector.relativeStrength}% vs SPY)`,
        pressure.points.length > 0
            ? `Pressure: ${pressure.points.map(p => p.description).join('; ')}`
            : 'No significant pressure points',
        `RSI: ${pressure.rsi}`,
    ].join(' | ');

    return {
        symbol: sector.symbol,
        score,
        conviction,
        regime: regime.regime,
        rotationTrend: sector.trend,
        pressurePoints: pressure.points,
        rsi: pressure.rsi,
        reasoning,
    };
}
