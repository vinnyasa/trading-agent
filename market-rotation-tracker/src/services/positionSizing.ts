// Simple, conviction-tiered position sizing.
//
// Goal: keep sizing decisions easy to follow by hand (no volatility/Kelly math)
// while still respecting the core risk principle of trading strategies —
// put more capital behind higher-conviction signals, less behind weaker ones,
// and nothing behind noise. Ranges are deliberately conservative so that a
// handful of concurrent positions never over-concentrates an account.
export type ConvictionLevel = 'high' | 'medium' | 'low' | 'noise';

export interface PositionSizeGuidance {
    minPct: number;   // suggested minimum % of account/cash for this position
    maxPct: number;   // suggested maximum %
    label: string;    // human-readable range, e.g. "15–20% of account"
}

const SIZE_TIERS: Record<'high' | 'medium' | 'low', { min: number; max: number }> = {
    high:   { min: 15, max: 20 },
    medium: { min: 8,  max: 12 },
    low:    { min: 3,  max: 5  },
};

export function getPositionSizeGuidance(conviction: ConvictionLevel): PositionSizeGuidance {
    if (conviction === 'noise') {
        return { minPct: 0, maxPct: 0, label: 'No position — signal is noise-level' };
    }
    const { min, max } = SIZE_TIERS[conviction];
    return { minPct: min, maxPct: max, label: `${min}–${max}% of account` };
}

// Same thresholds used by confluenceScorer/stockScorer, exposed here so callers
// that only have a raw score (e.g. 401k entry opportunities) can derive a tier.
export function convictionFromScore(score: number): ConvictionLevel {
    return score >= 70 ? 'high' : score >= 50 ? 'medium' : score >= 30 ? 'low' : 'noise';
}
