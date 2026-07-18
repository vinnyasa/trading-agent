# Plan: Systematic Strategy Layer (Trend + Mean-Reversion)

## Decisions from alignment
- Automation: SEMI-automated — report generates order-ticket-style suggestions (symbol, direction, entry, stop, target, size). User manually places the trade. No auto order placement (matches existing design doc rule: never auto-wire place_equity_order).
- Instruments: SPY, QQQ, IWM (already in SECTOR_UNIVERSE) + TQQQ, SQQQ (new) + existing sector ETFs (XLK, XLF, XLE, XLV, etc. — already scored, reused as-is).
- Direction: Long-only via inverse ETF proxy. QQQ bearish view → long SQQQ (not real short-selling). Only QQQ has a leveraged/inverse pair configured initially (TQQQ=bull, SQQQ=bear). SPY/IWM/sector ETFs are long-only candidates for now (no inverse proxy) — flagged as open scope item below.
- Trend entry logic: RS/momentum breakout + Bollinger squeeze release (reuse SectorScore.trend + PressurePoint bollinger-squeeze-up/down). MA crossover and 20-day high/low breakout noted for later discussion, not in v1.
- Mean-reversion entry logic: RSI oversold/overbought + support/resistance bounce + Bollinger Band extreme + momentum divergence (all reuse existing PressurePoint types).
- Risk management: stop-loss placed just beyond nearest support/resistance PressurePoint level (not ATR-based).
- Hold duration: swing trade, days to ~2 weeks. Daily-bar cadence, no intraday data needed — fits existing once/day batch architecture.
- Backtest REQUIRED before signals appear live in the daily report.

## Existing reusable building blocks (confirmed via code exploration)
- `SectorScore.trend` ('strengthening'/'weakening'/'neutral') — src/services/rotationTracker.ts
- `RotationResult.allScores` — full ranked list incl. non-leader/laggard symbols
- `PressurePoint` types: support/resistance/prior-high/prior-low/high-volume-node/rsi-overbought/rsi-oversold/bollinger-squeeze(-up/-down)/momentum-divergence, with strength weak/moderate/strong and a `level` price — src/services/pressurePoints.ts `scorePressurePoints(symbol, bars)`
- `ConfluenceSignal`/`scoreConfluence()` — existing 35/30/25/10 weighted scorer, conviction thresholds (noise<30, low 30-49, medium 50-69, high 70-100) — src/services/confluenceScorer.ts
- `marketData.ts` `fetchUniverseBars(symbols, days)` — Massive/Polygon supports arbitrary historical range (not just 60d), suitable for backtest history fetch (~500-750 days for 2-3yr backtest)
- `reports/401k-state.json` pattern — existing precedent for a persisted position-state JSON file we can mirror for tracking open systematic-strategy positions

## Gaps to fill
- No backtesting engine yet — must build one
- No direction/long-short field on any signal type yet
- No leveraged/inverse ETF tickers configured (TQQQ, SQQQ)
- No stop-loss/target price computation
- No position sizing / account-equity config for this strategy book
- No order-ticket report section

## Steps

**Phase 1 — Backtest engine (gates everything else)**
1. New script `src/backtest.ts` (or `scripts/backtest.ts`) — fetches ~2-3 years of daily bars per instrument via existing `fetchUniverseBars`, then walks forward bar-by-bar (no lookahead) applying the trend and mean-reversion entry/exit rules below. Computes trades log (entry/exit date+price, direction, stop, target, P&L, days held) and summary stats (win rate, avg win/loss, expectancy, max drawdown, trade count).
2. Reuse `scorePressurePoints()` and a rolling-window version of the RS/trend logic from `rotationTracker.ts` computed on trailing windows only (must not leak future bars).
3. Output backtest report (console + saved JSON) per instrument/strategy combo for review before going live.

**Phase 2 — Strategy config & types** *(depends on Phase 1 validating rules are sound)*
4. New `src/config/strategyConfig.ts`: tradable instrument list, inverse-proxy map (`{ QQQ: { long: 'TQQQ', short: 'SQQQ' } }`), risk-per-trade %, account equity (mirror `401k-state.json` pattern — new `reports/strategy-state.json` with `accountEquity` field user edits manually).
5. Add `StrategySignal` type to `src/types/index.ts`: symbol, tradableSymbol (proxy-resolved), strategyType ('trend'|'mean-reversion'), direction ('long'|'short'), entryPrice, stopLossPrice, targetPrice, positionSize (shares + $), riskAmount, conviction, reasoning.

**Phase 3 — Strategy scorer service** *(depends on Phase 2)*
6. New `src/services/strategyScorer.ts`: `scoreStrategySignals(barsBySymbol, rotation, macroRegime): StrategySignal[]` — for each tradable instrument, evaluates trend rule and mean-reversion rule independently, resolves direction + inverse-proxy substitution, computes stop (nearest support/resistance PressurePoint) and target (next resistance/support or fixed R-multiple), computes position size from `strategyConfig` risk %.

**Phase 4 — Batch + report integration** *(depends on Phase 3)*
7. Wire into `src/batch.ts` after existing confluence scoring — call `scoreStrategySignals()`, attach to `DailyReport.strategySignals`.
8. New "## Systematic Strategy Signals" section in `src/services/reportGenerator.ts` rendering order-ticket-style rows (symbol, direction, strategy type, entry/stop/target, size, risk $, reasoning) — clearly labeled "Manual execution required — not auto-placed."
9. Track open positions in `reports/strategy-state.json` (mirrors `fourOhOneKTracker.ts` pattern) so the report can show "still open" vs "new signal today".

**Phase 5 — Design doc**
10. Add a new section to `docs/market-rotation-agent-design.md` documenting scope decisions above (semi-automated order tickets, long-only via inverse ETF, swing hold, backtest-gated), consistent with the existing "never auto-wire order placement" principle already in the doc.

## Relevant files
- `market-rotation-tracker/src/services/rotationTracker.ts` — reuse SectorScore.trend, allScores
- `market-rotation-tracker/src/services/pressurePoints.ts` — reuse PressurePoint types/levels for stops/targets
- `market-rotation-tracker/src/services/confluenceScorer.ts` — reference for scoring pattern, not directly reused
- `market-rotation-tracker/src/services/marketData.ts` — fetchUniverseBars for backtest history
- `market-rotation-tracker/src/services/fourOhOneKTracker.ts` — pattern reference for new strategy-state tracker
- `market-rotation-tracker/reports/401k-state.json` — pattern reference for new `reports/strategy-state.json`
- `market-rotation-tracker/src/batch.ts` — integration point
- `market-rotation-tracker/src/services/reportGenerator.ts` — new report section
- `market-rotation-tracker/src/types/index.ts` — new StrategySignal type
- `docs/market-rotation-agent-design.md` — new design section
- NEW: `market-rotation-tracker/src/backtest.ts`, `src/config/strategyConfig.ts`, `src/services/strategyScorer.ts`

## Verification
1. Backtest script run via `docker-compose run --rm batch node_modules/.bin/ts-node src/backtest.ts` (or new compose service) — review win rate/expectancy per instrument before trusting live signals.
2. `docker-compose run --rm typecheck` passes after each phase.
3. Manual review of a sample "Systematic Strategy Signals" report section for one real trading day — confirm stop/target/size math is sane vs actual price levels.
4. Confirm `reports/strategy-state.json` correctly rolls open positions forward day to day (no duplicate entries, exits clear the position).

## Further Considerations
1. SPY/IWM/sector ETFs currently have no inverse-proxy ticker mapped — bearish signals on those would have nowhere to go (no trade) unless we add proxies (e.g. SH for SPY, RWM for IWM). Recommend: start QQQ/TQQQ/SQQQ only for short expression in v1, expand later.
2. Account equity for position sizing isn't defined anywhere yet — recommend a simple manually-edited field in new `reports/strategy-state.json` (same pattern as `cashAvailable` in `401k-state.json`), unless there's a specific account/brokerage this should pull from.
3. Backtest data depth: Massive/Polygon should support 2-3 years of daily bars in one call; Alpha Vantage fallback only returns ~100 days compact (would need 'full' outputsize param added if Massive is unavailable during backtest).
