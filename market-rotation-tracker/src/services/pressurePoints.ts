import { DailyBar, PressurePoint, PressureResult } from '../types';

// ── RSI ────────────────────────────────────────────────────────────────────
function computeRSI(bars: DailyBar[], period = 14): number {
    if (bars.length < period + 1) return 50;

    const changes = bars.slice(-period - 1).map((b, i, arr) =>
        i === 0 ? 0 : b.close - arr[i - 1].close
    ).slice(1);

    const gains = changes.map(c => (c > 0 ? c : 0));
    const losses = changes.map(c => (c < 0 ? -c : 0));

    const avgGain = gains.reduce((a, b) => a + b, 0) / period;
    const avgLoss = losses.reduce((a, b) => a + b, 0) / period;

    if (avgLoss === 0) return 100;
    const rs = avgGain / avgLoss;
    return parseFloat((100 - 100 / (1 + rs)).toFixed(2));
}

// ── Bollinger Band width (squeeze detection + direction) ──────────────────
function bollingerAnalysis(bars: DailyBar[], period = 20): {
    squeezing: boolean;
    direction: 'up' | 'down' | 'neutral';
} {
    if (bars.length < period * 2) return { squeezing: false, direction: 'neutral' };

    const recent = bars.slice(-period);
    const older = bars.slice(-period * 2, -period);

    const stdDev = (b: DailyBar[]) => {
        const mean = b.reduce((s, x) => s + x.close, 0) / b.length;
        const variance = b.reduce((s, x) => s + Math.pow(x.close - mean, 2), 0) / b.length;
        return Math.sqrt(variance);
    };

    const squeezing = stdDev(recent) < stdDev(older) * 0.85;

    // Direction: 3-day close momentum as directional bias during squeeze
    const last3 = bars.slice(-3);
    const roc3 = last3.length >= 2
        ? (last3[last3.length - 1].close - last3[0].close) / last3[0].close
        : 0;

    const direction = roc3 > 0.005 ? 'up' : roc3 < -0.005 ? 'down' : 'neutral';
    return { squeezing, direction };
}

// ── High volume node (approximate) ────────────────────────────────────────
function findHighVolumeNode(bars: DailyBar[]): number | null {
    if (bars.length < 10) return null;
    const sorted = [...bars].sort((a, b) => b.volume - a.volume);
    const top3 = sorted.slice(0, 3);
    const avgClose = top3.reduce((s, b) => s + b.close, 0) / top3.length;
    return parseFloat(avgClose.toFixed(2));
}

// ── Support / resistance (prior swing highs/lows) ─────────────────────────
function findSupportResistance(bars: DailyBar[], lookback = 20): {
    support: number;
    resistance: number;
} {
    const slice = bars.slice(-lookback);
    const support = Math.min(...slice.map(b => b.low));
    const resistance = Math.max(...slice.map(b => b.high));
    return { support, resistance };
}

// ── Relative volume (today vs 20-day avg, excluding today) ──────────────
function computeRelativeVolume(bars: DailyBar[], period = 20): number {
    if (bars.length < period + 1) return 1;
    const avgVol = bars.slice(-period - 1, -1).reduce((s, b) => s + b.volume, 0) / period;
    if (avgVol === 0) return 1;
    return parseFloat((bars[bars.length - 1].volume / avgVol).toFixed(2));
}

// ── Consecutive candle streak ─────────────────────────────────────────────
function candleStreakLength(bars: DailyBar[]): { count: number; direction: 'up' | 'down' | 'none' } {
    if (bars.length < 2) return { count: 0, direction: 'none' };
    const last = bars[bars.length - 1];
    const direction: 'up' | 'down' | 'none' =
        last.close > last.open ? 'up' :
        last.close < last.open ? 'down' : 'none';
    if (direction === 'none') return { count: 0, direction: 'none' };
    let count = 1;
    for (let i = bars.length - 2; i >= 0; i--) {
        const b = bars[i];
        if (direction === 'up' && b.close > b.open) count++;
        else if (direction === 'down' && b.close < b.open) count++;
        else break;
    }
    return { count, direction };
}

// ── Momentum divergence (price up, RSI down or vice versa) ────────────────
function hasMomentumDivergence(bars: DailyBar[]): boolean {
    if (bars.length < 20) return false;
    const mid = Math.floor(bars.length / 2);
    const earlyBars = bars.slice(0, mid);
    const lateBars = bars.slice(mid);

    const earlyRsi = computeRSI(earlyBars);
    const lateRsi = computeRSI(lateBars);
    const earlyClose = earlyBars[earlyBars.length - 1].close;
    const lateClose = lateBars[lateBars.length - 1].close;

    const priceUp = lateClose > earlyClose;
    const rsiDown = lateRsi < earlyRsi - 5;
    const priceDown = lateClose < earlyClose;
    const rsiUp = lateRsi > earlyRsi + 5;

    return (priceUp && rsiDown) || (priceDown && rsiUp);
}

// ── Main pressure point scorer ─────────────────────────────────────────────
export function scorePressurePoints(symbol: string, bars: DailyBar[]): PressureResult {
    const points: PressurePoint[] = [];
    const latest = bars[bars.length - 1];
    const currentPrice = latest.close;

    const rsi = computeRSI(bars);
    const { support, resistance } = findSupportResistance(bars);
    const hvn = findHighVolumeNode(bars);
    const { squeezing, direction: squeezeDir } = bollingerAnalysis(bars);
    const diverging = hasMomentumDivergence(bars);
    const rvol = computeRelativeVolume(bars);
    const { count: streakCount, direction: streakDir } = candleStreakLength(bars);

    const proximity = (level: number) => Math.abs(currentPrice - level) / currentPrice;

    // RSI extremes
    if (rsi >= 70) {
        points.push({
            symbol, type: 'rsi-overbought', level: 0,
            description: `RSI overbought at ${rsi}`,
            strength: rsi >= 80 ? 'strong' : 'moderate',
        });
    } else if (rsi <= 30) {
        points.push({
            symbol, type: 'rsi-oversold', level: 0,
            description: `RSI oversold at ${rsi}`,
            strength: rsi <= 20 ? 'strong' : 'moderate',
        });
    }

    // Near support (within 2%)
    if (proximity(support) <= 0.02) {
        points.push({
            symbol, type: 'support', level: support,
            description: `Near 20-day support at $${support.toFixed(2)} (${(proximity(support) * 100).toFixed(1)}% away)`,
            strength: proximity(support) <= 0.005 ? 'strong' : 'moderate',
        });
    }

    // Near resistance (within 2%)
    if (proximity(resistance) <= 0.02) {
        points.push({
            symbol, type: 'resistance', level: resistance,
            description: `Near 20-day resistance at $${resistance.toFixed(2)} (${(proximity(resistance) * 100).toFixed(1)}% away)`,
            strength: proximity(resistance) <= 0.005 ? 'strong' : 'moderate',
        });
    }

    // High volume node proximity (within 3%)
    if (hvn && proximity(hvn) <= 0.03) {
        points.push({
            symbol, type: 'high-volume-node', level: hvn,
            description: `Near high-volume node at $${hvn.toFixed(2)}`,
            strength: 'moderate',
        });
    }

    // Bollinger squeeze with directional bias
    if (squeezing) {
        if (squeezeDir === 'up') {
            points.push({
                symbol, type: 'bollinger-squeeze-up', level: 0,
                description: 'Bollinger squeeze with upward bias — volatility coiling, momentum tilting up',
                strength: 'moderate',
            });
        } else if (squeezeDir === 'down') {
            points.push({
                symbol, type: 'bollinger-squeeze-down', level: 0,
                description: 'Bollinger squeeze with downward bias — volatility coiling, momentum tilting down',
                strength: 'moderate',
            });
        } else {
            points.push({
                symbol, type: 'bollinger-squeeze', level: 0,
                description: 'Bollinger Band squeeze — compressed volatility, breakout direction unclear',
                strength: 'moderate',
            });
        }
    }

    // Momentum divergence
    if (diverging) {
        points.push({
            symbol, type: 'momentum-divergence', level: 0,
            description: 'Price and RSI diverging — potential trend exhaustion',
            strength: 'weak',
        });
    }

    // Volume dry-up: price up over 5 days but today's volume well below average
    const priceTrend5d = bars.length >= 6
        ? (currentPrice - bars[bars.length - 6].close) / bars[bars.length - 6].close
        : 0;
    if (priceTrend5d > 0.01 && rvol < 0.8) {
        points.push({
            symbol, type: 'volume-dry-up', level: 0,
            description: `Volume dry-up: price +${(priceTrend5d * 100).toFixed(1)}% over 5d but RVol ${rvol}x (20d avg) — participation fading`,
            strength: rvol < 0.6 ? 'strong' : 'moderate',
        });
    }

    // Candle streak exhaustion: 7+ consecutive candles in same direction
    if (streakCount >= 7) {
        points.push({
            symbol, type: 'candle-streak-exhaustion', level: 0,
            description: `${streakCount} consecutive ${streakDir === 'up' ? 'green' : 'red'} candles — streak exhaustion risk`,
            strength: streakCount >= 9 ? 'strong' : 'moderate',
        });
    }

    return {
        symbol,
        points,
        rsi,
        highVolumeNode: hvn,
        nearSupport: proximity(support) <= 0.02,
        nearResistance: proximity(resistance) <= 0.02,
        relativeVolume: rvol,
    };
}
