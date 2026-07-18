# Market Rotation Agent - Setup Checklist (Stocks + Macro, Daily)

## 1) Runtime Environment

- [ ] Node.js 20+ installed
- [ ] npm available
- [ ] Install project dependencies:

```bash
cd market-rotation-tracker
npm install
```

## 2) Stocks Data Providers (Recommended: Use Both)

Recommended setup for v1:

- Primary provider: Massive (formerly Polygon.io)
- Secondary provider: Alpha Vantage

This gives us better resilience (fallback if one provider is rate-limited or unavailable) while keeping costs at $0 to start.

Option A: Massive (formerly Polygon.io)

- [ ] Create account: https://polygon.io/
- [ ] Copy API key
- [ ] Validate daily bars coverage for your stock/ETF universe
- [ ] Free tier check (as of your screenshot): 5 API calls/minute, end-of-day data, up to 2 years historical data

Option B: Alpha Vantage

- [ ] Create account: https://www.alphavantage.co/support/#api-key
- [ ] Copy API key
- [ ] Confirm request limits work for once-daily batch
- [ ] Free tier check (as of your screenshot): 25 requests/day and 5 requests/minute

How to leverage both in v1:

- Use Massive as the default source for daily bars and broad ticker coverage.
- Use Alpha Vantage as backup source and optional technical-indicator source.
- Keep Alpha Vantage request usage low and targeted due to daily cap.

## 3) Macro Data Provider

FRED API (recommended)

- [ ] Create account: https://fred.stlouisfed.org/docs/api/api_key.html
- [ ] Copy API key
- [ ] Confirm required series availability (rates, inflation, labor, growth)

## 4) Optional Calendar / Events Feed

Finnhub (optional for v1)

- [ ] Create account: https://finnhub.io/
- [ ] Copy API key
- [ ] Use only if you want economic calendar context in daily summary

## 5) AI/LLM Provider (for Daily Narrative Summary)

OpenAI

- [ ] Create account: https://platform.openai.com/
- [ ] Create API key: https://platform.openai.com/api-keys
- [ ] Add small billing credit

Alternative local option

- [ ] Install Ollama: https://ollama.com/
- [ ] Pull a local model if preferred

## 6) Alerts (Optional for v1)

Choose one if you want a daily push message:

- [ ] Discord webhook
- [ ] Slack webhook
- [ ] Skip alerts and write only local daily report file

## 7) Robinhood MCP Evaluation (Parallel Track)

The Robinhood MCP has two distinct capability groups. Market data tools are useful for the daily batch. Trading tools must remain interactive and human-confirmed only.

Market data evaluation:

- [ ] Connect MCP and test `get_equity_historicals` for daily OHLCV bars
- [ ] Test `get_equity_technical_indicators` for RSI, MACD, Bollinger Bands output
- [ ] Test `get_earnings_calendar` for macro event context
- [ ] Test `get_watchlist_items` to use your Robinhood watchlist as the stock universe
- [ ] Test `run_scan` for custom sector/pressure point screening
- [ ] Confirm auth token works in unattended/non-interactive runs (key open question)
- [ ] Compare data quality and coverage vs Massive and Alpha Vantage

Trading tools (interactive only — never automated):

- [ ] Only use `place_equity_order` interactively, after reviewing the daily report
- [ ] Use `review_equity_order` to simulate before placing any real order
- [ ] Never wire order placement tools to the scheduled batch workflow

## 8) Environment Variables to Collect

| Key | Required | Purpose |
|---|---|---|
| `POLYGON_API_KEY` | Recommended | Primary stock/ETF daily bars (Massive/Polygon) |
| `ALPHA_VANTAGE_API_KEY` | Recommended | Backup stock bars and optional indicator endpoints |
| `FRED_API_KEY` | Yes | Macro regime inputs |
| `FINNHUB_API_KEY` | Optional | Economic calendar context |
| `OPENAI_API_KEY` | Optional | AI-generated daily narrative |
| `DISCORD_WEBHOOK_SUMMARY` | Optional | One daily summary alert |
| `SLACK_WEBHOOK_SUMMARY` | Optional | One daily summary alert |

Note: for best reliability, collect both keys and run dual-provider mode.

## 9) Local Project Setup

```bash
cd market-rotation-tracker
npm install
copy .env.example .env
```

Then add only the keys you are using for v1.

## 10) Security Checklist

- [ ] Never commit `.env`
- [ ] Keep API keys private
- [ ] Add spending limits for paid APIs

## 11) Priority Order

Priority 1 (required to start)

- [ ] Massive/Polygon API key
- [ ] FRED API key

Priority 1.5 (strongly recommended)

- [ ] Alpha Vantage API key for fallback

Priority 2 (improves output)

- [ ] OpenAI key (or local LLM)
- [ ] One alert channel (Discord/Slack)

Priority 3 (evaluation)

- [ ] Robinhood MCP feasibility test
- [ ] Finnhub calendar integration

## Related Files

- [market-rotation-agent-design.md](./market-rotation-agent-design.md)
- [README.md](./README.md)
- [insights.md](./insights.md)

Last updated: June 23, 2026