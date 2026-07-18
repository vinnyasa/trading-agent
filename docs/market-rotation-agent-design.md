# Market Rotation Agent - Focused V1 (Stocks + Macro + Pressure Points, Daily)

## Scope Decision

This version is intentionally narrow so it is practical to run now.

- Universe: US stocks and ETFs only
- Explicitly out of scope: all crypto assets and crypto signals
- Core intelligence: macro regime detection + stock/sector rotation + pressure point detection
- Run frequency: once per day (end-of-day batch)

## Why This Scope Works

| Decision | Benefit |
|---|---|
| Stocks only | Better data quality and fewer noise events |
| Daily cadence | Lower operational cost and easier reliability |
| Macro-first logic | Better context for sector/style moves |
| Pressure points retained | Higher-quality watchlist and clearer risk zones |

## V1 Product Goal

Build a daily agent that answers:

1. What macro regime are we in today?
2. Which sectors/styles are strengthening or weakening?
3. Which stocks/ETFs are at meaningful pressure points?
4. What are the top watchlist changes for tomorrow?

The agent is a signal summarizer, not an auto-trader.

## V1 Architecture (Minimal)

### Inputs

- Daily OHLCV for major ETFs/sectors and selected stocks
- Macro series (rates, inflation, labor, growth)
- Optional breadth/volatility proxies

### Core Engines

1. Macro Regime Detector
   - Classifies the environment into a compact state set, for example:
     - Risk-on growth
     - Risk-off slowdown
     - Inflation pressure
     - Disinflation recovery

2. Stock/Sector Rotation Detector
   - Relative strength and momentum ranking by sector/style buckets
   - Detects inflow/outflow shifts versus benchmark

3. Pressure Point Detector (Retained in V1)
   - Price levels: support/resistance, prior highs/lows, round-number zones
   - Volume levels: volume clusters and high-volume nodes
   - Momentum levels: RSI extremes and trend exhaustion zones
   - Macro event context: known calendar/event proximity (optional)

4. Daily Summary Generator
   - Produces one report with regime, rotations, pressure points, and top deltas

### Outputs

- One daily markdown or JSON report
- Optional one daily alert message (Slack/Discord/email)

## Pressure Point Detection in V1

Pressure points are kept, but simplified for daily data.

### Included in V1

| Category | Signals |
|---|---|
| Price-based | Support/resistance, previous high/low retests, round numbers |
| Volume-based | Volume clusters, high-volume nodes |
| Momentum-based | RSI extremes, momentum divergence checks |
| Macro-context | Event proximity flags (if calendar source available) |

### Deferred to V2+

- Intraday microstructure pressure zones
- Advanced options flows (gamma and max pain)
- Alternative sentiment composites

## Confluence Scoring (Rotation + Pressure + Macro)

The primary signal is confluence: rotation aligned with pressure points under a macro regime.

| Component | Weight |
|---|---|
| Macro regime alignment | 35% |
| Rotation strength | 30% |
| Pressure point quality | 25% |
| Momentum confirmation | 10% |

Interpretation:

- 70-100: high-priority signal
- 50-69: watchlist candidate
- 0-49: no action

## Daily Workflow

| Time | Step |
|---|---|
| After market close | Pull latest daily data |
| Batch step 1 | Detect macro regime |
| Batch step 2 | Compute sector/style rotation |
| Batch step 3 | Score pressure points |
| Batch step 4 | Compute confluence score and summary |
| Final | Store/send one daily report |

## Example Daily Alert (Stocks)

```text
HIGH CONVICTION ALERT
---------------------
Asset:      XLF
Regime:     Disinflation recovery
Rotation:   Financials strengthening vs SPY
Pressure:   Retest of prior breakout and high-volume node
Momentum:   RSI 58 and rising
Signal:     ROTATION + PRESSURE POINT CONFLUENCE
Score:      78/100
Confidence: HIGH
```

## Data Source Plan for V1

Required now:

- Stocks/ETF prices: choose one primary source already in project
- Macro data: FRED (or equivalent)

Optional now:

- Economic calendar feed for macro event context

Deferred:

- Options-derived and intraday datasets

## Robinhood MCP Evaluation Track

The Robinhood MCP (`https://agent.robinhood.com/mcp/trading`) connects an AI platform directly to your Robinhood account. It has two distinct capability groups that need to be treated very differently.

### Tool inventory relevant to this project

#### Market data tools — directly useful for v1

| Tool | What it gives us |
|---|---|
| `get_equity_historicals` | OHLCV daily bars — replaces or supplements Massive/Alpha Vantage |
| `get_equity_technical_indicators` | RSI, MACD, Bollinger Bands, moving averages — directly feeds pressure point engine |
| `get_equity_fundamentals` | Valuation ratios, 52-week range, market cap, dividend info |
| `get_earnings_results` | Upcoming earnings dates — macro event context |
| `get_earnings_calendar` | Market-wide earnings schedule — sector event timing |
| `get_indexes` / `get_index_quotes` | Real-time index values for regime context |

#### Watchlist / scanner tools — useful for universe management

| Tool | What it gives us |
|---|---|
| `get_watchlists` / `get_watchlist_items` | Pull your existing Robinhood watchlist as the stock universe |
| `get_popular_watchlists` | Discover Robinhood curated lists (e.g. 100 most popular) |
| `run_scan` | Run custom scans with live market results — potential pressure point screening |
| `create_scan` / `update_scan_filters` | Build custom sector/style screens |

#### Trading tools — keep human-confirmed only, never automated

| Tool | Risk level |
|---|---|
| `place_equity_order` | Real money — human confirmation required |
| `place_option_order` | Real money — human confirmation required |
| `review_equity_order` / `review_option_order` | Safe to call — simulation only, no execution |

### Revised architecture fit

This changes the evaluation significantly. The market data tools cover most of what we planned to source from Massive and Alpha Vantage, and the technical indicator tools directly overlap with the pressure point engine.

| Use case | Verdict |
|---|---|
| Daily OHLCV bars | Covered by `get_equity_historicals` |
| RSI, MACD, Bollinger Bands | Covered by `get_equity_technical_indicators` |
| Earnings/event calendar context | Covered by `get_earnings_calendar` |
| Stock universe from your watchlist | Covered by `get_watchlist_items` |
| Sector scanning | Covered by `run_scan` |
| Automated trade execution | Do NOT wire to batch agent |
| Interactive trade review | Use `review_equity_order` then human confirms |

### Authentication and automation question

The MCP uses HTTP transport (`https://agent.robinhood.com/mcp/trading`), which means it is technically callable from code, not just interactive chat sessions. The key open question is whether the auth token can be used in unattended (non-interactive) runs. This needs testing during evaluation.

### Robinhood Agentic platform capabilities

Robinhood's own agentic platform supports AI-driven investing flows natively. These are examples of what the platform enables when an AI agent is connected to your Agentic account:

| Example | What it means for this project |
|---|---|
| "Build a portfolio across the AI supply chain" | Agent researches and proposes new positions — useful for universe discovery |
| "Buy $100 of X every time price drops 2% in a day" | Rule-based automated execution — powerful but requires careful safeguards |
| "Rebalance my portfolio to X% allocation" | Portfolio adjustment on demand — v2+ territory |
| "Look at my portfolio and tell me what risks I'm exposed to" | Portfolio risk analysis — complements the daily rotation report |
| "Why is ROAR up today?" / "Build a bull and bear thesis" | On-demand market research — augments the LLM summary layer |

Key distinction: these capabilities run inside Robinhood's own agentic environment, not in your code. The MCP is how your AI platform (Claude, ChatGPT) reaches into that environment.

### Safe integration pattern — three layers

**Layer 1 — Daily batch (automated, read-only, your code):**
1. Call market data and technical indicator tools to feed the signal engines.
2. Produce the daily report.
3. No order-related tools called at all.

**Layer 2 — Interactive analysis (human-initiated, AI-assisted):**
1. You review the daily report.
2. You open a Claude Desktop or ChatGPT session with the Robinhood MCP connected.
3. You ask it to analyze a specific candidate: risk exposure, bull/bear thesis, portfolio fit.
4. You use `review_equity_order` to simulate before committing.

**Layer 3 — Automated rule execution (v2+ only, after proven signal accuracy):**
1. Only after 4+ weeks of paper mode with validated signal quality.
2. Start with small fixed-dollar rules (e.g. "$50 if confluence score > 80").
3. Always set stop-loss and position size limits.
4. Review every automated execution in the Activity feed.

### Critical rule

Never connect `place_equity_order` or `place_option_order` to any automated scheduled workflow in v1. Only promote to Layer 3 after signal accuracy is validated and you have explicit position size and loss limits defined.

## Roadmap

### V1 — Read-only signal engine (current focus)

| Phase | Description | Status |
|---|---|---|
| Phase 1 | Lock scope: stocks + macro + pressure points, daily | Done |
| Phase 2 | Implement macro regime detector | Next |
| Phase 3 | Implement stock/sector rotation scoring | Pending |
| Phase 4 | Implement daily pressure point scoring | Pending |
| Phase 5 | Generate daily report and alert output | Pending |
| Phase 6 | Add once-daily scheduler (GitHub Actions or local) | Pending |
| Phase 7 | Evaluate Robinhood MCP as data source (market data tools only) | Parallel track |

Gate to V2: run paper mode for 4+ weeks, validate signal accuracy, define position size and loss limits.

### V2 — Interactive analysis + rule-based execution

| Phase | Description | Status |
|---|---|---|
| Phase 8 | Connect Robinhood MCP to Claude Desktop/ChatGPT for interactive analysis | V2 |
| Phase 9 | Add portfolio risk analysis against daily report (Layer 2) | V2 |
| Phase 10 | Define small rule-based execution triggers with hard position/loss limits | V2 |
| Phase 11 | Enable rule execution via Robinhood Agentic platform (Layer 3) | V2 |

V2 execution rules (non-negotiable before Phase 11):
- Signal accuracy validated over 4+ weeks of paper mode
- Maximum position size per trade defined and enforced
- Maximum daily loss limit defined and enforced
- All executions reviewed in Robinhood Activity feed daily

## Signal Design Principles (Inspired by Renaissance Technologies)

Renaissance's edge was not any single algorithm — it was signal diversity, ensemble scoring, and extreme discipline. The key lesson: stack uncorrelated signals, measure each one independently, and tune weights based on real outcome history.

### What we use in V1

| Technique | How it applies | Status |
|---|---|---|
| Time series analysis | Trend and momentum detection on daily OHLCV bars | V1 core |
| Volatility regime (GARCH concept) | ATR expansion and Bollinger Band squeeze as pressure point inputs | V1 core |
| Relative strength ranking | Sector/style rotation scoring vs benchmark | V1 core |
| Signal confluence scoring | Weighted ensemble of macro + rotation + pressure signals | V1 core |
| Independent signal logging | Each signal logged separately so accuracy can be measured over time | V1 core |

### What we add in V2

| Technique | How it applies | Status |
|---|---|---|
| Ensemble weight tuning | Replace hardcoded weights with data-driven weights based on outcome history | V2 |
| NLP sentiment layer | News/earnings call sentiment as an optional enrichment signal | V2 |
| Portfolio optimization (Markowitz) | Classical mean-variance optimization: use confluence scores as expected returns (μ), compute covariance matrix (Σ) from daily price history, solve for optimal allocation weights — replaces equal-weight top-picks logic with mathematically optimal position sizing | V2 |
| ARIMA forecasting | Short-horizon price/volume forecasts to augment pressure point scoring | V2 |

### What we deliberately skip

| Technique | Why |
|---|---|
| Deep learning (LSTM, ANN) | Requires years of labeled outcome data we do not have yet; high overfitting risk on 2 years of free-tier data |
| High-frequency trading | Completely out of scope — requires co-location and microsecond infrastructure |
| Redundant momentum indicators (RSI + Stochastic + CCI together) | These measure the same thing — adding them is noise amplification, not signal diversity |

### V1.5 candidate — Statistical arbitrage / pair trading

Pair trading works at daily timeframe and does not require HFT speed. It is a realistic addition once the core signal engine is stable.

**What it involves:**
- Cointegration testing on historical daily price series (e.g. XLF vs XLK, XLE vs XLU, QQQ vs SPY)
- Z-score calculation on the spread between two assets
- Signal: go long the underperformer and short the outperformer when spread is statistically extreme
- Exit when spread mean-reverts

**What it needs:**
- 2 years of daily data is marginal — 5+ years is ideal for robust cointegration testing
- Short selling access or inverse ETFs to execute both legs
- Pair breakdown detection — your macro regime detector is actually useful here since regime changes are the most common cause of pair breakdown

**Best candidate pairs from your universe:**

| Pair | Relationship |
|---|---|
| XLF vs XLK | Financials vs Tech — classic rotation pair |
| XLE vs XLU | Energy vs Utilities — risk-on/risk-off proxy |
| QQQ vs SPY | Growth vs broad market |
| IWM vs SPY | Small cap vs large cap |

**When to add it:**
After the core macro + rotation + pressure point engine has run for 4+ weeks and daily data collection is stable. Add cointegration scoring as an additional confluence input, not a replacement for existing signals.

### V1.5 candidate — Short interest signal

Short interest adds a sentiment/positioning layer that is uncorrelated with price and volume signals already in V1.

**What it catches:**
- High short interest + sector rotating in = potential short squeeze setup (one of the strongest momentum catalysts)
- Declining short interest = shorts covering, sentiment improving — bullish confirmation
- Rising short interest in a leader sector = caution flag, smart money betting against

**How it slots into the engine:**

| Signal | Strength | Confluence impact |
|---|---|---|
| >20% float short + sector rotating in | Strong | Pressure point: squeeze setup |
| Short interest declining rapidly | Moderate | Bullish confirmation |
| >10% float short + Bollinger squeeze up | Moderate | Compound squeeze signal |
| Rising short interest in leader sector | Weak | Caution flag |

**Data source:**
- FINRA short interest feed — free, updates twice a month (sufficient for daily context)
- Polygon.io paid tier — real-time short interest if upgraded later
- Robinhood MCP — worth testing during evaluation track

**When to add:** After pair trading cointegration is evaluated. Add as an additional pressure point type in `pressurePoints.ts`, log it independently in the signal log, and tune its confluence weight after 4+ weeks of observations.

### Signal discipline rules

1. Every signal must be logged independently so its predictive value can be measured.
2. Do not add a new signal unless it is meaningfully uncorrelated with existing ones.
3. Tune confluence weights based on observed outcome data, not intuition.
4. A higher score only means higher historical pattern match — not a guaranteed outcome.

---

## Potential Future Projects

Ideas that build on the infrastructure and learnings from this project. None of these are in scope now but are worth capturing.

### Options flow scanner
Use the Robinhood MCP `get_option_chains` and `get_option_quotes` tools combined with the pressure point engine to detect unusual options activity at key levels. Gamma and max pain already identified as V2+ signals.

### Earnings rotation strategy
Use `get_earnings_calendar` to pre-position sector rotation plays around earnings seasons. Combine with the existing macro regime and rotation engine to score pre-earnings setups.

### Portfolio rebalancer
After V2 execution is live and validated, build an automated rebalancer that uses the confluence score to shift allocations between sector ETFs. Uses `review_equity_order` → human confirmation → `place_equity_order`.

### Macro regime alert service
Decouple the macro regime detector as a standalone service that monitors FRED series for regime transitions and sends alerts independent of the full daily report. Useful as an early warning layer.

### Pair trading module
Add cointegration analysis on sector ETF pairs (XLF/XLK, XLE/XLU, QQQ/SPY, IWM/SPY) as an additional confluence signal. Works at daily cadence — no HFT infrastructure needed. Promoted to V1.5 candidate in Signal Design Principles section.

### Markowitz portfolio optimizer
Once the execution layer is live (V2+), replace the current "top picks by score" output with optimal allocation weights via classical Markowitz mean-variance optimization.

How it works:
- μ (expected returns): use daily confluence scores as the return estimate per asset
- Σ (covariance matrix): computed from daily OHLCV price history already collected in V1
- q (risk aversion): tunable dial — higher q = fewer safer positions, lower q = more aggressive
- Objective: minimize `C(x) = -μᵀx + q · xᵀΣx` to find optimal allocation weights

What changes in the output: instead of "XLF score 60, XLU score 50" → "allocate 65% XLF, 35% XLU, 0% everything else". The covariance matrix also catches when two high-scoring assets are highly correlated and reduces the allocation to both.

Implementation: solvable in TypeScript using `mathjs` for matrix ops — no new APIs needed. Slots in as a post-scoring step in the daily batch. Add after V2 execution is live and signal accuracy validated over 4+ weeks.

### Sentiment enrichment layer
Add NLP sentiment analysis on earnings call transcripts and major financial news headlines as a non-correlated signal to complement the technical and macro signals. Candidate signal for ensemble weight tuning.

### Multi-asset expansion (V3+)
Re-evaluate crypto after the stock signal engine has proven accuracy. Apply the same macro + rotation + pressure framework to BTC/ETH only, using lessons learned from V1/V2 stock performance.

---

## Immediate Next Steps

1. Freeze the v1 universe (sector ETFs + initial stock list).
2. Confirm data source: test Robinhood MCP market data tools vs Massive/Alpha Vantage.
3. Implement macro regime detector.
4. Implement daily batch command and output schema.
5. Run paper mode and track signal quality before any v2 work begins.

## Related Files

- [setup-checklist.md](./setup-checklist.md) - API keys and setup
- [README.md](./README.md) - project overview
- [insights.md](./insights.md) - research notes

Last updated: July 11, 2026
Current focus: V1 — stocks + macro regime + pressure points, daily cadence, read-only
