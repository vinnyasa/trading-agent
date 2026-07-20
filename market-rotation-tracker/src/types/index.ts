// ── Daily price bar ────────────────────────────────────────────────────────
export interface DailyBar {
    symbol: string;
    date: string;       // YYYY-MM-DD
    open: number;
    high: number;
    low: number;
    close: number;
    volume: number;
}

// ── Macro series ────────────────────────────────────────────────────────────
export interface MacroDataPoint {
    date: string;
    value: number;
}

export interface MacroSeries {
    seriesId: string;   // e.g. 'DFF', 'CPIAUCSL', 'UNRATE', 'GDP'
    label: string;
    data: MacroDataPoint[];
    latestValue: number;
    previousValue: number;
    trend: 'rising' | 'falling' | 'flat';
}

// ── Macro regime ────────────────────────────────────────────────────────────
export type MacroRegime =
    | 'risk-on-growth'
    | 'risk-off-slowdown'
    | 'inflation-pressure'
    | 'disinflation-recovery'
    | 'unknown';

export interface MacroRegimeResult {
    regime: MacroRegime;
    label: string;
    factors: string[];
    confidence: 'high' | 'medium' | 'low';
}

// ── Rotation ─────────────────────────────────────────────────────────────────
export interface SectorScore {
    symbol: string;
    label: string;
    relativeStrength: number;   // vs SPY, 20-day
    momentum: number;           // rate of change, 10-day
    score: number;              // composite 0-100
    trend: 'strengthening' | 'weakening' | 'neutral';
}

export interface RotationResult {
    leaders: SectorScore[];
    laggards: SectorScore[];
    allScores: SectorScore[];   // every scored symbol, ranked — used to look up non-leader/laggard symbols (e.g. always-include benchmarks)
    topRotationMove: string;    // human-readable summary
    rankedAt: string;
}

// ── Pressure points ──────────────────────────────────────────────────────────
export type PressureType =
    | 'support'
    | 'resistance'
    | 'prior-high'
    | 'prior-low'
    | 'high-volume-node'
    | 'rsi-overbought'
    | 'rsi-oversold'
    | 'bollinger-squeeze'
    | 'bollinger-squeeze-up'
    | 'bollinger-squeeze-down'
    | 'momentum-divergence';

export interface PressurePoint {
    symbol: string;
    type: PressureType;
    level: number;              // price level (0 for momentum-only types)
    description: string;
    strength: 'strong' | 'moderate' | 'weak';
}

export interface PressureResult {
    symbol: string;
    points: PressurePoint[];
    rsi: number;
    highVolumeNode: number | null;
    nearSupport: boolean;
    nearResistance: boolean;
}

// ── Confluence ───────────────────────────────────────────────────────────────
export interface ConfluenceSignal {
    symbol: string;
    score: number;              // 0-100
    conviction: 'high' | 'medium' | 'low' | 'noise';
    regime: MacroRegime;
    rotationTrend: 'strengthening' | 'weakening' | 'neutral';
    pressurePoints: PressurePoint[];
    rsi: number;
    reasoning: string;
}

// ── Stock watchlist signal ───────────────────────────────────────────────────
export interface StockSignal {
    symbol: string;
    label: string;
    parentSector: string;          // e.g. 'XLI'
    parentSectorLabel: string;     // e.g. 'Industrials'
    parentRotationScore: number;
    pressurePoints: PressurePoint[];
    rsi: number;
    score: number;                 // 0-100
    conviction: 'high' | 'medium' | 'low' | 'noise';
    reasoning: string;
}

// ── Daily report ─────────────────────────────────────────────────────────────
export interface DailyReport {
    runDate: string;
    runTimestamp: string;
    dataSourceStatus: {
        primary: 'massive' | 'alpha-vantage' | 'none';
        symbolsLoaded: number;
        symbolsFailed: string[];
    };
    macroRegime: MacroRegimeResult;
    rotation: RotationResult;
    topSignals: ConfluenceSignal[];
    stockSignals: StockSignal[];
    watchlistChanges: string[];
    warnings: string[];
    fourOhOneK?: FourOhOneKReport;
}

// ── 401k tracker ─────────────────────────────────────────────────────────────
export interface FourOhOneKPosition {
    ticker: string;
    fundName: string;
    parentEtf: string;
    entryDate: string;
    entrySignalScore: number;
    daysHeld: number;
    canSellWithoutFee: boolean;
    consecutiveLeaderDays: number;
    consecutiveLaggardDays: number;
}

export interface FourOhOneKReport {
    date: string;
    cashAvailable: number;
    cashNote: string;
    totalPositions: number;
    positions: FourOhOneKPosition[];
    recommendations: {
        action: 'hold' | 'consider-exit' | 'exit-when-eligible' | 'exit-now' | 'consider-entry';
        ticker: string;
        fundName: string;
        parentEtf: string;
        daysHeld: number;
        canSellWithoutFee: boolean;
        reason: string;
        urgency: 'high' | 'medium' | 'low';
    }[];
    entryOpportunities: {
        ticker: string;
        fundName: string;
        parentEtf: string;
        parentEtfScore: number;
        reason: string;
        suggestedPositionPct: number;   // % of cashAvailable, capped at MAX_POSITION_PCT
        suggestedDollarAmount: number;  // cashAvailable * suggestedPositionPct
    }[];
    summary: string[];
}

// ── Signal log entry (for future weight tuning) ───────────────────────────────
export interface SignalLogEntry {
    date: string;
    symbol: string;
    signalType: string;
    value: number;
    regime: MacroRegime;
    confluenceScore: number;
}
