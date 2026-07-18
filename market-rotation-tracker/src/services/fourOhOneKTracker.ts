import * as fs from 'fs';
import * as path from 'path';
import { RotationResult, SectorScore } from '../types';
import { FIDELITY_FUNDS, getFundsByEtf } from '../config/fidelityFunds';

// ── State file ─────────────────────────────────────────────────────────────
export interface FundPosition {
    ticker: string;
    fundName: string;
    parentEtf: string;
    entryDate: string;          // YYYY-MM-DD
    entrySignalScore: number;   // rotation score at entry
    daysHeld: number;
    canSellWithoutFee: boolean; // true once >= minHoldDays
    consecutiveLeaderDays: number;   // days sector has been a leader
    consecutiveLaggardDays: number;  // days sector has been a laggard
}

export interface FourOhOneKState {
    lastUpdated: string;
    cashAvailable: number;       // USD — update manually when you add funds
    cashNote: string;
    positions: FundPosition[];
    recentActions: string[];     // log of past recommendations
}

const STATE_PATH = path.join(__dirname, '..', '..', 'reports', '401k-state.json');
const MIN_HOLD_DAYS = 30;
const CONSECUTIVE_LEADER_DAYS_TO_ENTER = 2;   // signal must be leader 2 days in a row
const CONSECUTIVE_LAGGARD_DAYS_TO_EXIT  = 2;   // exit after 2 days in laggard territory
const MAX_POSITIONS = 4;                        // max simultaneous sector bets
const MAX_POSITION_PCT = 0.35;                  // max 35% in any single fund

// ── State persistence ──────────────────────────────────────────────────────
export function loadState(): FourOhOneKState {
    if (fs.existsSync(STATE_PATH)) {
        try {
            return JSON.parse(fs.readFileSync(STATE_PATH, 'utf-8'));
        } catch {
            // corrupted state — start fresh
        }
    }
    // Default initial state — cash reflects what user said they're moving in
    return {
        lastUpdated: new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York' }).format(new Date()),
        cashAvailable: 2600,   // matches current BrokerageLink cash
        cashNote: 'Update cashAvailable in reports/401k-state.json when you move funds from core 401k',
        positions: [
            {
                ticker: 'FIDRX',
                fundName: 'Fidelity Select Industrials',
                parentEtf: 'XLI',
                entryDate: '2026-07-14',
                entrySignalScore: 53.5,
                daysHeld: 0,
                canSellWithoutFee: false,
                consecutiveLeaderDays: 1,
                consecutiveLaggardDays: 0,
            },
            {
                ticker: 'FSLEX',
                fundName: 'Fidelity Environment & Alt Energy',
                parentEtf: 'XLU',
                entryDate: '2026-07-14',
                entrySignalScore: 52.3,
                daysHeld: 0,
                canSellWithoutFee: false,
                consecutiveLeaderDays: 1,
                consecutiveLaggardDays: 0,
            },
            {
                ticker: 'FSELX',
                fundName: 'Fidelity Select Semiconductors',
                parentEtf: 'SMH',
                entryDate: '2026-07-14',
                entrySignalScore: 37.6,
                daysHeld: 0,
                canSellWithoutFee: false,
                consecutiveLeaderDays: 0,
                consecutiveLaggardDays: 1,
            },
        ],
        recentActions: [],
    };
}

function saveState(state: FourOhOneKState): void {
    fs.mkdirSync(path.dirname(STATE_PATH), { recursive: true });
    fs.writeFileSync(STATE_PATH, JSON.stringify(state, null, 2), 'utf-8');
}

// ── Core update logic ──────────────────────────────────────────────────────
export interface FourOhOneKRecommendation {
    action: 'hold' | 'consider-exit' | 'exit-when-eligible' | 'exit-now' | 'consider-entry';
    ticker: string;
    fundName: string;
    parentEtf: string;
    daysHeld: number;
    canSellWithoutFee: boolean;
    reason: string;
    urgency: 'high' | 'medium' | 'low';
}

export interface FourOhOneKReport {
    date: string;
    cashAvailable: number;
    cashNote: string;
    totalPositions: number;
    positions: FundPosition[];
    recommendations: FourOhOneKRecommendation[];
    entryOpportunities: {
        ticker: string;
        fundName: string;
        parentEtf: string;
        parentEtfScore: number;
        reason: string;
    }[];
    summary: string[];
}

export function updateFourOhOneK(
    rotation: RotationResult,
    runDate: string,
): FourOhOneKReport {
    const state = loadState();
    const recommendations: FourOhOneKRecommendation[] = [];
    const entryOpportunities: FourOhOneKRecommendation[] = [];
    const summary: string[] = [];

    const leaderSymbols  = new Set(rotation.leaders.map(l => l.symbol));
    const laggardSymbols = new Set(rotation.laggards.map(l => l.symbol));

    // ── Update existing positions ────────────────────────────────────────────
    for (const pos of state.positions) {
        // Advance days held
        if (pos.entryDate !== runDate) {
            const entry = new Date(pos.entryDate);
            const today = new Date(runDate);
            const diff  = Math.floor((today.getTime() - entry.getTime()) / 86400000);
            pos.daysHeld = diff;
        }
        pos.canSellWithoutFee = pos.daysHeld >= MIN_HOLD_DAYS;

        // Track consecutive leader/laggard days
        if (leaderSymbols.has(pos.parentEtf)) {
            pos.consecutiveLeaderDays++;
            pos.consecutiveLaggardDays = 0;
        } else if (laggardSymbols.has(pos.parentEtf)) {
            pos.consecutiveLaggardDays++;
            pos.consecutiveLeaderDays = 0;
        } else {
            // Neutral — reset streaks
            pos.consecutiveLaggardDays = 0;
            pos.consecutiveLeaderDays  = 0;
        }

        // ── Exit logic ────────────────────────────────────────────────────────
        const daysUntilFree = Math.max(0, MIN_HOLD_DAYS - pos.daysHeld);

        if (pos.consecutiveLaggardDays >= CONSECUTIVE_LAGGARD_DAYS_TO_EXIT) {
            if (pos.canSellWithoutFee) {
                recommendations.push({
                    action: 'exit-now',
                    ticker: pos.ticker,
                    fundName: pos.fundName,
                    parentEtf: pos.parentEtf,
                    daysHeld: pos.daysHeld,
                    canSellWithoutFee: true,
                    reason: `${pos.parentEtf} has been a laggard for ${pos.consecutiveLaggardDays} consecutive days. Exit confirmed — no redemption fee.`,
                    urgency: 'high',
                });
            } else {
                recommendations.push({
                    action: 'exit-when-eligible',
                    ticker: pos.ticker,
                    fundName: pos.fundName,
                    parentEtf: pos.parentEtf,
                    daysHeld: pos.daysHeld,
                    canSellWithoutFee: false,
                    reason: `${pos.parentEtf} lagging for ${pos.consecutiveLaggardDays} days. Exit planned — ${daysUntilFree} days until free of redemption fee.`,
                    urgency: 'medium',
                });
            }
        } else if (pos.consecutiveLaggardDays === 1) {
            recommendations.push({
                action: 'consider-exit',
                ticker: pos.ticker,
                fundName: pos.fundName,
                parentEtf: pos.parentEtf,
                daysHeld: pos.daysHeld,
                canSellWithoutFee: pos.canSellWithoutFee,
                reason: `${pos.parentEtf} entered laggard territory today (day 1 of 2). Watch tomorrow — exit if laggard again.`,
                urgency: 'medium',
            });
        } else {
            // Hold
            const sectorScore = [...rotation.leaders, ...rotation.laggards]
                .find(s => s.symbol === pos.parentEtf);
            const rsStr = sectorScore
                ? ` RS ${sectorScore.relativeStrength > 0 ? '+' : ''}${sectorScore.relativeStrength}% vs SPY`
                : '';
            recommendations.push({
                action: 'hold',
                ticker: pos.ticker,
                fundName: pos.fundName,
                parentEtf: pos.parentEtf,
                daysHeld: pos.daysHeld,
                canSellWithoutFee: pos.canSellWithoutFee,
                reason: `${pos.parentEtf} rotation holding.${rsStr} ${pos.canSellWithoutFee ? 'No fee to exit.' : `${daysUntilFree} days until fee-free exit.`}`,
                urgency: 'low',
            });
        }
    }

    // ── Entry opportunities ──────────────────────────────────────────────────
    const heldEtfs = new Set(state.positions.map(p => p.parentEtf));
    const hasCapacity = state.positions.length < MAX_POSITIONS && state.cashAvailable > 500;

    if (hasCapacity) {
        for (const leader of rotation.leaders) {
            if (heldEtfs.has(leader.symbol)) continue; // already in this sector

            // Check if this sector has been a leader before (we approximate with score > 50)
            // True 2-day confirmation requires prior state — use score threshold as proxy
            if (leader.score >= 52) {
                const funds = getFundsByEtf(leader.symbol);
                for (const fund of funds) {
                    entryOpportunities.push({
                        ticker: fund.ticker,
                        fundName: fund.name,
                        parentEtf: leader.symbol,
                        parentEtfScore: leader.score,
                        reason: `${leader.label} (${leader.symbol}) is a rotation leader with RS ${leader.relativeStrength > 0 ? '+' : ''}${leader.relativeStrength}% vs SPY, score ${leader.score}. Confirm 2nd consecutive leader day before entering.`,
                    } as any);
                }
            }
        }
    }

    // ── Summary lines ────────────────────────────────────────────────────────
    const exitNow    = recommendations.filter(r => r.action === 'exit-now');
    const exitSoon   = recommendations.filter(r => r.action === 'exit-when-eligible');
    const watching   = recommendations.filter(r => r.action === 'consider-exit');
    const holds      = recommendations.filter(r => r.action === 'hold');

    if (exitNow.length > 0)  summary.push(`🔴 EXIT NOW: ${exitNow.map(r => r.ticker).join(', ')} — sector signal turned negative, fee-free`);
    if (exitSoon.length > 0) summary.push(`🟡 EXIT PLANNED: ${exitSoon.map(r => r.ticker).join(', ')} — waiting for ${MIN_HOLD_DAYS}-day hold`);
    if (watching.length > 0) summary.push(`👀 WATCH: ${watching.map(r => r.ticker).join(', ')} — 1st day of laggard signal, confirm tomorrow`);
    if (holds.length > 0)    summary.push(`✅ HOLD: ${holds.map(r => r.ticker).join(', ')}`);
    if (entryOpportunities.length > 0) {
        summary.push(`💰 CASH AVAILABLE: $${state.cashAvailable.toLocaleString()} — ${entryOpportunities.length} entry opportunity/ies identified`);
        summary.push(`📋 POTENTIAL ENTRIES: ${entryOpportunities.map((e: any) => `${e.ticker} (${e.parentEtf})`).join(', ')}`);
    }
    if (!hasCapacity && state.cashAvailable <= 500) {
        summary.push(`ℹ️ Fully deployed — add cash to BrokerageLink to enable new positions`);
    }

    // ── Persist updated state ────────────────────────────────────────────────
    state.lastUpdated = runDate;
    state.recentActions = [
        `${runDate}: ${summary.join(' | ')}`,
        ...state.recentActions.slice(0, 9), // keep last 10
    ];
    saveState(state);

    return {
        date: runDate,
        cashAvailable: state.cashAvailable,
        cashNote: state.cashNote,
        totalPositions: state.positions.length,
        positions: state.positions,
        recommendations,
        entryOpportunities: entryOpportunities as any,
        summary,
    };
}
