// Fidelity Select fund → parent ETF signal mapping.
// Used by the 401k tracker to translate rotation signals into fund recommendations.
// BrokerageLink accounts can also trade ETFs directly — ETF column is the signal source.
export interface FidelityFund {
    ticker: string;
    name: string;
    parentEtf: string;       // ETF we use as the signal proxy
    shortTermFee: boolean;   // true = 0.75% redemption fee if sold within 30 days
    minHoldDays: number;     // minimum hold to avoid short-term fee
}

export const FIDELITY_FUNDS: FidelityFund[] = [
    { ticker: 'FSPTX',  name: 'Fidelity Select Technology',            parentEtf: 'XLK',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSELX',  name: 'Fidelity Select Semiconductors',        parentEtf: 'SMH',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FDCPX',  name: 'Fidelity Select Tech Hardware',         parentEtf: 'XLK',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FIDSX',  name: 'Fidelity Select Financial Services',    parentEtf: 'XLF',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSRBX',  name: 'Fidelity Select Banking',               parentEtf: 'XLF',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSPHX',  name: 'Fidelity Select Health Care',           parentEtf: 'XLV',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FIDRX',  name: 'Fidelity Select Industrials',           parentEtf: 'XLI',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSDAX',  name: 'Fidelity Select Defense & Aerospace',   parentEtf: 'XLI',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSUTX',  name: 'Fidelity Select Utilities',             parentEtf: 'XLU',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSENX',  name: 'Fidelity Select Energy',                parentEtf: 'XLE',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSLEX',  name: 'Fidelity Environment & Alt Energy',     parentEtf: 'XLU',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FRESX',  name: 'Fidelity Real Estate Fund',             parentEtf: 'XLRE', shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSDPX',  name: 'Fidelity Select Materials',             parentEtf: 'XLB',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FSCSX',  name: 'Fidelity Select IT Services',           parentEtf: 'XLK',  shortTermFee: true,  minHoldDays: 30 },
    { ticker: 'FBGRX',  name: 'Fidelity Blue Chip Growth',             parentEtf: 'QQQ',  shortTermFee: false, minHoldDays: 0  },
];

// Look up fund by ticker
export function getFundByTicker(ticker: string): FidelityFund | undefined {
    return FIDELITY_FUNDS.find(f => f.ticker === ticker);
}

// Get all funds that map to a given ETF signal
export function getFundsByEtf(etf: string): FidelityFund[] {
    return FIDELITY_FUNDS.filter(f => f.parentEtf === etf);
}
