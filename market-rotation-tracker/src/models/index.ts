export interface MarketData {
    symbol: string;
    price: number;
    volume: number;
    timestamp: Date;
}

export interface Rotation {
    sector: string;
    strength: number;
    timestamp: Date;
}