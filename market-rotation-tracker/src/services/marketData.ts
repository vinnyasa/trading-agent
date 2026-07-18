import axios from 'axios';
import { DailyBar } from '../types';

const MASSIVE_BASE = 'https://api.polygon.io/v2';
const AV_BASE = 'https://www.alphavantage.co/query';

// Fetch daily bars from Massive (Polygon.io). Returns null on failure.
async function fetchFromMassive(symbol: string, days: number): Promise<DailyBar[] | null> {
    const apiKey = process.env.MASSIVE_API_KEY;
    if (!apiKey) return null;

    const to = new Date();
    const from = new Date();
    from.setDate(from.getDate() - days - 10); // buffer for weekends/holidays
    const fromStr = from.toISOString().split('T')[0];
    const toStr = to.toISOString().split('T')[0];

    try {
        const url = `${MASSIVE_BASE}/aggs/ticker/${symbol}/range/1/day/${fromStr}/${toStr}`;
        const resp = await axios.get(url, {
            params: { adjusted: true, sort: 'asc', limit: days + 10, apiKey },
            timeout: 10000,
        });

        const results = resp.data?.results;
        if (!results || results.length === 0) return null;

        return results.map((r: any) => ({
            symbol,
            date: new Date(r.t).toISOString().split('T')[0],
            open: r.o,
            high: r.h,
            low: r.l,
            close: r.c,
            volume: r.v,
        }));
    } catch {
        return null;
    }
}

// Fetch daily bars from Alpha Vantage. Returns null on failure.
async function fetchFromAlphaVantage(symbol: string): Promise<DailyBar[] | null> {
    const apiKey = process.env.ALPHA_VANTAGE_API_KEY;
    if (!apiKey) return null;

    try {
        const resp = await axios.get(AV_BASE, {
            params: {
                function: 'TIME_SERIES_DAILY_ADJUSTED',
                symbol,
                outputsize: 'compact', // last 100 trading days
                apikey: apiKey,
            },
            timeout: 15000,
        });

        const ts = resp.data?.['Time Series (Daily)'];
        if (!ts) return null;

        return Object.entries(ts)
            .map(([date, v]: [string, any]) => ({
                symbol,
                date,
                open: parseFloat(v['1. open']),
                high: parseFloat(v['2. high']),
                low: parseFloat(v['3. low']),
                close: parseFloat(v['5. adjusted close']),
                volume: parseInt(v['6. volume']),
            }))
            .sort((a, b) => a.date.localeCompare(b.date));
    } catch {
        return null;
    }
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
// 5 calls/min on Massive free tier → 13 s between calls
const MASSIVE_RATE_DELAY_MS = 13000;

// Public: fetch daily bars with Massive primary, Alpha Vantage fallback.
export async function fetchDailyBars(symbol: string, days = 60): Promise<{
    bars: DailyBar[];
    source: 'massive' | 'alpha-vantage';
} | null> {
    const massive = await fetchFromMassive(symbol, days);
    if (massive && massive.length > 0) {
        return { bars: massive.slice(-days), source: 'massive' };
    }

    const av = await fetchFromAlphaVantage(symbol);
    if (av && av.length > 0) {
        return { bars: av.slice(-days), source: 'alpha-vantage' };
    }

    return null;
}

// Fetch multiple symbols with per-symbol failover. Respects Alpha Vantage
// 25/day cap by tracking call count and skipping AV if budget exceeded.
export async function fetchUniverseBars(
    symbols: string[],
    days = 60
): Promise<{
    results: Map<string, DailyBar[]>;
    failed: string[];
    primarySource: 'massive' | 'alpha-vantage' | 'none';
}> {
    const results = new Map<string, DailyBar[]>();
    const failed: string[] = [];
    let avCallsUsed = 0;
    const AV_DAILY_CAP = 20; // stay under 25/day limit with safety margin
    let primarySource: 'massive' | 'alpha-vantage' | 'none' = 'none';

    for (let i = 0; i < symbols.length; i++) {
        const symbol = symbols[i];
        if (i > 0) {
            console.log(`[market] Waiting ${MASSIVE_RATE_DELAY_MS / 1000}s for rate limit...`);
            await sleep(MASSIVE_RATE_DELAY_MS);
        }

        const massive = await fetchFromMassive(symbol, days);
        if (massive && massive.length > 0) {
            results.set(symbol, massive.slice(-days));
            primarySource = 'massive';
            continue;
        }

        if (avCallsUsed < AV_DAILY_CAP) {
            const av = await fetchFromAlphaVantage(symbol);
            avCallsUsed++;
            if (av && av.length > 0) {
                results.set(symbol, av.slice(-days));
                if (primarySource === 'none') primarySource = 'alpha-vantage';
                continue;
            }
        }

        failed.push(symbol);
    }

    return { results, failed, primarySource };
}
