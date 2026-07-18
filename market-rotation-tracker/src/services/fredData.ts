import axios from 'axios';
import { MacroSeries, MacroDataPoint } from '../types';

const FRED_BASE = 'https://api.stlouisfed.org/fred/series/observations';

// FRED series used for macro regime detection
export const MACRO_SERIES: { id: string; label: string }[] = [
    { id: 'DFF',      label: 'Fed Funds Rate' },
    { id: 'T10Y2Y',   label: '10Y-2Y Yield Spread' },
    { id: 'CPIAUCSL', label: 'CPI Inflation (YoY)' },
    { id: 'UNRATE',   label: 'Unemployment Rate' },
    { id: 'INDPRO',   label: 'Industrial Production' },
];

async function fetchSeries(seriesId: string, observations = 6): Promise<MacroDataPoint[]> {
    const apiKey = process.env.FRED_API_KEY;
    if (!apiKey) throw new Error('FRED_API_KEY not set');

    const resp = await axios.get(FRED_BASE, {
        params: {
            series_id: seriesId,
            api_key: apiKey,
            file_type: 'json',
            sort_order: 'desc',
            limit: observations,
        },
        timeout: 10000,
    });

    const obs: any[] = resp.data?.observations ?? [];
    return obs
        .filter((o: any) => o.value !== '.')
        .map((o: any) => ({ date: o.date, value: parseFloat(o.value) }))
        .reverse();
}

function computeTrend(data: MacroDataPoint[]): 'rising' | 'falling' | 'flat' {
    if (data.length < 2) return 'flat';
    // Use last vs 3-periods-ago when possible for a smoother signal
    const latest = data[data.length - 1].value;
    const anchor = data[Math.max(0, data.length - 3)].value;
    const pct = Math.abs(anchor) > 0 ? (latest - anchor) / Math.abs(anchor) : 0;
    // 0.1% threshold — CPIAUCSL monthly moves are 0.1–0.4%, 0.5% was too tight
    if (pct > 0.001) return 'rising';
    if (pct < -0.001) return 'falling';
    return 'flat';
}

export async function fetchMacroSeries(): Promise<MacroSeries[]> {
    const results: MacroSeries[] = [];

    for (const s of MACRO_SERIES) {
        try {
            const data = await fetchSeries(s.id);
            if (data.length === 0) continue;
            results.push({
                seriesId: s.id,
                label: s.label,
                data,
                latestValue: data[data.length - 1].value,
                previousValue: data.length > 1 ? data[data.length - 2].value : data[0].value,
                trend: computeTrend(data),
            });
        } catch (e) {
            console.warn(`[FRED] Failed to fetch ${s.id}:`, (e as Error).message);
        }
    }

    return results;
}
