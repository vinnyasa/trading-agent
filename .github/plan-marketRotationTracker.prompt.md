# Cloud Run Plan - Market Rotation Tracker (Stocks + Macro + Pressure Points)

## Objective
Set up a once-daily cloud execution workflow for the market rotation agent with dual-provider resilience (Massive primary, Alpha Vantage fallback), macro regime detection, pressure point scoring, and daily summary output.

## Scope
- Stocks/ETFs only
- Macro regime detection included
- Pressure point detection included
- Daily cadence only (after market close)
- No auto-trading or order execution

## Platform Choice
Primary recommendation: GitHub Actions scheduled workflow.

Why:
- Fastest to implement
- Very low cost for daily batch jobs
- Native secret management via GitHub Secrets
- Built-in run logs and artifact history

## Execution Schedule
- Frequency: once per day
- Trigger: scheduled after market close (for example, 22:15 UTC)
- Optional: manual trigger for testing via workflow_dispatch

## Workflow Steps
1. Checkout repository
2. Set up Node runtime
3. Install dependencies
4. Load environment from GitHub Secrets
5. Build/validate TypeScript code
6. Run daily batch command
7. Save daily report artifact
8. Optionally send Slack/Discord summary alert

## Required Secrets
- POLYGON_API_KEY
- ALPHA_VANTAGE_API_KEY
- FRED_API_KEY

## Optional Secrets
- FINNHUB_API_KEY
- OPENAI_API_KEY
- DISCORD_WEBHOOK_SUMMARY
- SLACK_WEBHOOK_SUMMARY

## Provider Strategy (Failover + Budgeting)
### Provider order
1. Massive (primary)
2. Alpha Vantage (fallback)

### Call budgeting rules
- Keep Massive requests paced to free-tier per-minute constraints
- Keep Alpha Vantage usage capped to avoid exceeding 25/day free-tier quota
- Use Alpha Vantage only for failed/missing symbols or targeted indicators

### Retry/fallback behavior
- Retry transient HTTP/network errors with short exponential backoff
- On persistent failure/rate-limit from Massive, fail over symbol-by-symbol to Alpha Vantage
- If both fail for a symbol, mark data status as partial and continue run
- Daily run should complete even with partial data

## Output Contract
Generate one daily report file containing:
- Run date/time
- Data source status (primary/fallback usage)
- Macro regime label and supporting factors
- Sector/style rotation ranking
- Pressure point list (price, volume, momentum)
- Top confluence signals with scores
- Warnings (rate-limit, missing symbols, partial data)

Suggested output paths:
- reports/daily/YYYY-MM-DD.md
- reports/daily/YYYY-MM-DD.json

## Alert Format
Send one optional summary alert containing:
- Regime of the day
- Top 3 rotation moves
- Top 3 pressure-point confluence candidates
- Any degraded-data warnings

## Reliability Rules
- Continue on partial failures
- Emit clear status in report for every failed symbol/provider call
- Exit non-zero only if macro + all stock providers fail completely

## Security Rules
- Do not commit .env
- Use GitHub Secrets in cloud runs
- Mask secrets in logs
- Keep webhook URLs and API keys out of artifacts

## Implementation Checklist
1. Create .github/workflows/daily-rotation.yml
2. Add npm scripts for batch run and report generation
3. Implement provider adapter abstraction (Massive + Alpha Vantage)
4. Implement failover and rate-limit budgeting layer
5. Implement report writers (md/json)
6. Implement optional alert sender
7. Add manual run path for testing
8. Validate 7-day dry run in paper mode

## Validation Criteria
- Scheduled run executes daily without manual intervention
- Report artifact exists for each run
- Fallback path is exercised in at least one test run
- Daily call volumes stay within free-tier limits
- Output includes macro, rotation, and pressure point sections

## Refinement Notes
- Adjust schedule time to your timezone and data availability window
- Tune symbol universe size to fit free-tier quotas
- Add Robinhood MCP adapter later only after reliability validation
