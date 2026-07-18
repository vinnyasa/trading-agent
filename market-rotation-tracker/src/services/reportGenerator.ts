import * as fs from 'fs';
import * as path from 'path';
import { DailyReport, ConfluenceSignal, StockSignal, MacroRegime } from '../types';

function convictionEmoji(c: string): string {
    return c === 'high' ? '🟢' : c === 'medium' ? '🟡' : c === 'low' ? '🔴' : '⚪';
}

// ── Rules-based action summary ────────────────────────────────────────────────
function generateActionSummary(report: DailyReport): string[] {
    const lines: string[] = [];
    const regime = report.macroRegime.regime as MacroRegime;
    const leaders = report.rotation.leaders;
    const laggards = report.rotation.laggards;
    const topSignals = report.topSignals;
    const stockSignals = report.stockSignals ?? [];

    // ── Macro read ────────────────────────────────────────────────────────────
    const macroRead: Record<string, string> = {
        'risk-on-growth':       'Macro is risk-on growth — favor cyclicals, tech, financials. Reduce defensive exposure.',
        'risk-off-slowdown':    'Macro is risk-off — favor defensives (XLV, XLP, XLU). Reduce cyclical and growth exposure.',
        'inflation-pressure':   'Inflation-pressure regime — favor energy (XLE), materials (XLB), short-duration assets. Reduce rate-sensitive sectors.',
        'disinflation-recovery':'Disinflation in progress — favor rate-sensitive sectors (XLRE, XLU, XLV). Bond proxies re-rating.',
        'unknown':              'Macro regime unclear — reduce position sizing, wait for confirmation before new entries.',
    };
    lines.push(`**Macro:** ${macroRead[regime] ?? macroRead['unknown']}`);
    lines.push('');

    // ── Rotation reads ────────────────────────────────────────────────────────
    if (leaders.length > 0) {
        const leaderNames = leaders.map(l => `${l.symbol} (${l.label}, RS ${l.relativeStrength > 0 ? '+' : ''}${l.relativeStrength}%)`).join(', ');
        lines.push(`**Rotation leaders:** ${leaderNames}`);
    }
    if (laggards.length > 0) {
        const laggardNames = laggards.map(l => `${l.symbol} (RS ${l.relativeStrength}%)`).join(', ');
        lines.push(`**Avoid / underweight:** ${laggardNames}`);
    }
    lines.push('');

    // ── Top actionable ETF signals ────────────────────────────────────────────
    const highEtf    = topSignals.filter(s => s.conviction === 'high');
    const mediumEtf  = topSignals.filter(s => s.conviction === 'medium');

    if (highEtf.length > 0) {
        highEtf.forEach(s => {
            const squeeze = s.pressurePoints.find(p => p.type.startsWith('bollinger-squeeze'));
            const overbought = s.pressurePoints.find(p => p.type === 'rsi-overbought');
            const resistance = s.pressurePoints.find(p => p.type === 'resistance');
            const support = s.pressurePoints.find(p => p.type === 'support');

            let action = `**${s.symbol} — HIGH conviction (${s.score}/100):** `;
            if (overbought && resistance) {
                action += `RSI extended and near resistance — do not chase. Wait for pullback or resistance break with volume.`;
            } else if (overbought) {
                action += `RSI overbought at ${s.rsi.toFixed(0)} — rotation is strong but price may need to rest. Tighten stops if already long.`;
            } else if (resistance) {
                action += `Near resistance — watch for breakout. Entry on close above resistance with volume confirmation.`;
            } else if (squeeze?.type === 'bollinger-squeeze-up') {
                action += `Squeeze with upward bias — volatility coiling. Watch for breakout candle above recent range. RSI ${s.rsi.toFixed(0)}.`;
            } else if (squeeze?.type === 'bollinger-squeeze-down') {
                action += `Squeeze with downward bias — wait. Do not enter long until direction clears.`;
            } else if (support) {
                action += `Near support with rotation strength — potential long entry with stop below $${s.pressurePoints.find(p => p.type === 'support')?.level?.toFixed(2)}.`;
            } else {
                action += `Clean rotation signal, RSI ${s.rsi.toFixed(0)} — trend entry. No major overhead resistance.`;
            }
            lines.push(action);
        });
    }

    if (mediumEtf.length > 0) {
        mediumEtf.forEach(s => {
            lines.push(`**${s.symbol} — MEDIUM conviction (${s.score}/100):** Monitor. RS ${s.rotationTrend}, RSI ${s.rsi.toFixed(0)}.`);
        });
    }

    if (highEtf.length === 0 && mediumEtf.length === 0) {
        lines.push('**No high or medium conviction ETF setups today.** Reduce exposure, wait for cleaner signals.');
    }
    lines.push('');

    // ── Top stock picks ───────────────────────────────────────────────────────
    const highStocks = stockSignals.filter(s => s.conviction === 'high');
    if (highStocks.length > 0) {
        lines.push('**Top stock setups:**');
        highStocks.forEach(s => {
            const squeeze = s.pressurePoints.find(p => p.type.startsWith('bollinger-squeeze'));
            const overbought = s.pressurePoints.find(p => p.type === 'rsi-overbought');
            const oversold = s.pressurePoints.find(p => p.type === 'rsi-oversold');

            let note = '';
            if (overbought) note = 'RSI extended — wait for pullback';
            else if (oversold) note = 'RSI oversold in a leading sector — potential bounce entry';
            else if (squeeze?.type === 'bollinger-squeeze-up') note = 'Squeeze-up in strong sector — watch for breakout';
            else if (squeeze?.type === 'bollinger-squeeze-down') note = 'Squeeze-down — hold off';
            else note = `RSI ${s.rsi.toFixed(0)}, sector rotation supporting`;

            lines.push(`- **${s.symbol}** (${s.label}, ${s.parentSector}): ${note} — Score ${s.score}/100`);
        });
    }

    return lines;
}

export function generateMarkdownReport(report: DailyReport): string {
    const lines: string[] = [];

    lines.push(`# Market Rotation Daily Report — ${report.runDate}`);
    lines.push('');
    lines.push(`Run: ${report.runTimestamp}`);
    lines.push(`Data source: ${report.dataSourceStatus.primary} | Symbols loaded: ${report.dataSourceStatus.symbolsLoaded}`);
    if (report.dataSourceStatus.symbolsFailed.length > 0) {
        lines.push(`⚠ Failed symbols: ${report.dataSourceStatus.symbolsFailed.join(', ')}`);
    }
    lines.push('');

    // Action summary
    lines.push('## Today\'s Action Summary');
    lines.push('');
    generateActionSummary(report).forEach(l => lines.push(l));

    // Macro regime
    lines.push('## Macro Regime');
    lines.push('');
    lines.push(`**${report.macroRegime.label}** (${report.macroRegime.confidence} confidence)`);
    lines.push('');
    report.macroRegime.factors.forEach(f => lines.push(`- ${f}`));
    lines.push('');

    // Rotation
    lines.push('## Sector Rotation');
    lines.push('');
    lines.push(`**${report.rotation.topRotationMove}**`);
    lines.push('');
    lines.push('| Rank | Symbol | Sector | RS vs SPY | Momentum | Score | Trend |');
    lines.push('|---|---|---|---|---|---|---|');
    [...report.rotation.leaders, ...report.rotation.laggards].forEach((s, i) => {
        const tag = i < 3 ? '▲ Leader' : '▼ Laggard';
        lines.push(`| ${tag} | ${s.symbol} | ${s.label} | ${s.relativeStrength > 0 ? '+' : ''}${s.relativeStrength}% | ${s.momentum > 0 ? '+' : ''}${s.momentum}% | ${s.score} | ${s.trend} |`);
    });
    lines.push('');

    // Top confluence signals
    lines.push('## Top Confluence Signals');
    lines.push('');

    if (report.topSignals.length === 0) {
        lines.push('No high or medium conviction signals today.');
    } else {
        report.topSignals.forEach(sig => {
            lines.push(`### ${convictionEmoji(sig.conviction)} ${sig.symbol} — Score: ${sig.score}/100 (${sig.conviction.toUpperCase()})`);
            lines.push('');
            lines.push(sig.reasoning);
            if (sig.pressurePoints.length > 0) {
                lines.push('');
                lines.push('**Pressure points:**');
                sig.pressurePoints.forEach(p => lines.push(`- [${p.strength}] ${p.description}`));
            }
            lines.push('');
        });
    }

    // Stock watchlist signals
    if (report.stockSignals && report.stockSignals.length > 0) {
        lines.push('## Stock Signals (Leader Sectors)');
        lines.push('');
        lines.push('> Stocks within today\'s leading sectors, scored for entry timing.');
        lines.push('');

        let lastSector = '';
        for (const sig of report.stockSignals) {
            if (sig.parentSector !== lastSector) {
                lines.push(`### ${sig.parentSectorLabel} (${sig.parentSector})`);
                lines.push('');
                lastSector = sig.parentSector;
            }
            lines.push(`#### ${convictionEmoji(sig.conviction)} ${sig.symbol} — ${sig.label} — Score: ${sig.score}/100 (${sig.conviction.toUpperCase()})`);
            lines.push('');
            lines.push(sig.reasoning);
            if (sig.pressurePoints.length > 0) {
                lines.push('');
                lines.push('**Pressure points:**');
                sig.pressurePoints.forEach(p => lines.push(`- [${p.strength}] ${p.description}`));
            }
            lines.push('');
        }
    }

    // Watchlist changes
    if (report.watchlistChanges.length > 0) {
        lines.push('## Watchlist Changes');
        lines.push('');
        report.watchlistChanges.forEach(w => lines.push(`- ${w}`));
        lines.push('');
    }

    // 401k tracker
    if (report.fourOhOneK) {
        const k = report.fourOhOneK;
        lines.push('## 401k / BrokerageLink Tracker');
        lines.push('');
        lines.push(`**Cash available:** $${k.cashAvailable.toLocaleString()}`);
        if (k.cashNote) lines.push(`> ${k.cashNote}`);
        lines.push('');

        // Summary flags
        k.summary.forEach(s => lines.push(s));
        lines.push('');

        // Current positions
        if (k.positions.length > 0) {
            lines.push('### Current Positions');
            lines.push('');
            lines.push('| Fund | ETF Signal | Days Held | Fee-Free? | Leader Days | Laggard Days |');
            lines.push('|---|---|---|---|---|---|');
            k.positions.forEach(p => {
                const feeFree = p.canSellWithoutFee ? '✅ Yes' : `⏳ ${Math.max(0, 30 - p.daysHeld)}d`;
                lines.push(`| ${p.ticker} — ${p.fundName} | ${p.parentEtf} | ${p.daysHeld} | ${feeFree} | ${p.consecutiveLeaderDays} | ${p.consecutiveLaggardDays} |`);
            });
            lines.push('');
        }

        // Recommendations
        if (k.recommendations.length > 0) {
            lines.push('### Recommendations');
            lines.push('');
            k.recommendations.forEach(r => {
                const icon = r.action === 'exit-now' ? '🔴' :
                             r.action === 'exit-when-eligible' ? '🟠' :
                             r.action === 'consider-exit' ? '🟡' : '✅';
                lines.push(`${icon} **${r.ticker}** (${r.action.toUpperCase()}): ${r.reason}`);
            });
            lines.push('');
        }

        // Entry opportunities
        if (k.entryOpportunities.length > 0) {
            lines.push('### Entry Opportunities');
            lines.push('');
            lines.push('> Confirm 2 consecutive leader days before entering. Check RSI not overbought.');
            lines.push('');
            k.entryOpportunities.forEach((e: any) => {
                lines.push(`- **${e.ticker}** — ${e.fundName} (${e.parentEtf}, score ${e.parentEtfScore}): ${e.reason}`);
            });
            lines.push('');
        }
    }

    // Warnings
    if (report.warnings.length > 0) {
        lines.push('## Warnings');
        lines.push('');
        report.warnings.forEach(w => lines.push(`⚠ ${w}`));
        lines.push('');
    }

    lines.push('---');
    lines.push('*Signal summarizer only. Not financial advice. Final decisions are yours.*');

    return lines.join('\n');
}

export function saveReport(report: DailyReport, outputDir: string): string {
    fs.mkdirSync(outputDir, { recursive: true });
    const mdPath = path.join(outputDir, `${report.runDate}.md`);
    const jsonPath = path.join(outputDir, `${report.runDate}.json`);
    fs.writeFileSync(mdPath, generateMarkdownReport(report), 'utf-8');
    fs.writeFileSync(jsonPath, JSON.stringify(report, null, 2), 'utf-8');
    return mdPath;
}

export function buildWatchlistChanges(signals: ConfluenceSignal[]): string[] {
    const changes: string[] = [];
    signals
        .filter(s => s.conviction === 'high' || s.conviction === 'medium')
        .slice(0, 5)
        .forEach(s => {
            changes.push(
                `${s.symbol}: ${s.conviction === 'high' ? 'ADD to watchlist' : 'Monitor'} — score ${s.score}/100`
            );
        });
    return changes;
}
