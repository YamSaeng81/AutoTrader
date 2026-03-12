import { BacktestResult, TradeRecord, PageResponse } from '../lib/types';

export const backtestResultMock: BacktestResult = {
    id: '550e8400-e29b-41d4-a716-446655440000',
    strategyType: 'EMA_CROSS',
    coinPair: 'KRW-BTC',
    timeframe: 'H1',
    startDate: '2024-01-01T00:00:00Z',
    endDate: '2024-12-31T23:59:59Z',
    initialCapital: 10000000,
    status: 'COMPLETED',
    metrics: {
        totalReturn: 23.5,
        winRate: 58.3,
        maxDrawdown: -12.4,
        sharpeRatio: 1.85,
        sortinoRatio: 2.1,
        calmarRatio: 1.9,
        winLossRatio: 1.4,
        recoveryFactor: 1.9,
        totalTrades: 48,
        maxConsecutiveLoss: 3,
        monthlyReturns: {
            '2024-01': 3.2, '2024-02': -1.1, '2024-03': 5.8,
            '2024-04': 2.1, '2024-05': -0.8, '2024-06': 4.3,
            '2024-07': 1.9, '2024-08': -2.3, '2024-09': 3.5,
            '2024-10': 0.7, '2024-11': 4.1, '2024-12': 2.0,
        },
    },
    createdAt: '2024-03-01T12:00:00Z',
};

export const tradesMock: PageResponse<TradeRecord> = {
    content: [
        {
            side: 'BUY', price: 55000000, quantity: 0.1,
            fee: 2750, slippage: 275, pnl: 0, cumulativePnl: 0,
            signalReason: 'EMA 골든크로스 발생 (fast=9, slow=21)',
            marketRegime: 'TREND', executedAt: '2024-01-15T09:00:00Z',
        },
        {
            side: 'SELL', price: 58500000, quantity: 0.1,
            fee: 2925, slippage: 292, pnl: 347033, cumulativePnl: 347033,
            signalReason: 'EMA 데드크로스 발생',
            marketRegime: 'TREND', executedAt: '2024-01-22T14:00:00Z',
        },
    ],
    totalElements: 48,
    totalPages: 1,
    number: 0,
};

export const backtestListMock: PageResponse<BacktestResult> = {
    content: [backtestResultMock],
    totalElements: 1,
    totalPages: 1,
    number: 0,
};

export const strategyTypesMock = [
    {
        type: 'EMA_CROSS', name: 'EMA 크로스 전략',
        description: '단기/장기 EMA 골든·데드크로스 추세 추종',
        params: [
            { name: 'fastPeriod', type: 'integer', default: 9, description: '단기 EMA 기간' },
            { name: 'slowPeriod', type: 'integer', default: 21, description: '장기 EMA 기간' },
        ],
    },
    {
        type: 'VWAP', name: 'VWAP 역추세 전략',
        description: '거래량 가중 평균 가격 기반 역추세 매매',
        params: [
            { name: 'thresholdPercent', type: 'number', default: 0.5, description: 'VWAP 이탈 임계값 (%)' },
        ],
    },
    {
        type: 'BOLLINGER', name: '볼린저 밴드 전략',
        description: '볼린저 밴드 %B 기반 평균 회귀 매매',
        params: [
            { name: 'period', type: 'integer', default: 20, description: '볼린저 밴드 기간' },
            { name: 'stdDevMultiplier', type: 'number', default: 2.0, description: '표준편차 배수' },
        ],
    },
    {
        type: 'GRID', name: '그리드 트레이딩 전략',
        description: '가격 그리드 레벨 근접 시 매매',
        params: [
            { name: 'gridCount', type: 'integer', default: 10, description: '그리드 분할 수' },
            { name: 'gridRange', type: 'number', default: 0.1, description: '그리드 범위 (0.1 = ±10%)' },
        ],
    },
];

export const strategyInfosMock = [
    { name: 'VWAP', minimumCandleCount: 20, status: 'AVAILABLE', description: '거래량 가중 평균 가격 기반 역추세 매매' },
    { name: 'EMA_CROSS', minimumCandleCount: 21, status: 'AVAILABLE', description: '단기/장기 EMA 골든·데드크로스 추세 추종' },
    { name: 'BOLLINGER', minimumCandleCount: 20, status: 'AVAILABLE', description: '볼린저 밴드 %B 기반 평균 회귀 매매' },
    { name: 'GRID', minimumCandleCount: 1, status: 'AVAILABLE', description: '가격 그리드 레벨 근접 시 매매' },
    { name: 'RSI', minimumCandleCount: 15, status: 'SKELETON', description: 'RSI 과매수/과매도 기반 역추세 매매 (구현 예정)' },
    { name: 'MACD', minimumCandleCount: 35, status: 'SKELETON', description: 'MACD/Signal 크로스 기반 추세 추종 (구현 예정)' },
    { name: 'SUPERTREND', minimumCandleCount: 11, status: 'SKELETON', description: 'ATR 기반 동적 지지/저항 추세 추종 (구현 예정)' },
    { name: 'ATR_BREAKOUT', minimumCandleCount: 15, status: 'SKELETON', description: 'ATR 변동성 돌파 모멘텀 매매 (구현 예정)' },
    { name: 'ORDERBOOK_IMBALANCE', minimumCandleCount: 5, status: 'SKELETON', description: '호가 불균형 기반 단기 방향성 매매 (WebSocket 연동 후 구현 예정)' },
];

export interface StrategyParam {
    name: string;
    label: string;
    type: 'number' | 'integer';
    default: number;
    min: number;
    max: number;
}

export const strategyParamsMock: Record<string, StrategyParam[]> = {
    VWAP: [
        { name: 'thresholdPct', label: 'VWAP 이탈 임계값 (%)', type: 'number', default: 1.0, min: 0.1, max: 5.0 },
        { name: 'period', label: '계산 기간', type: 'integer', default: 20, min: 5, max: 100 },
    ],
    EMA_CROSS: [
        { name: 'fastPeriod', label: '단기 EMA 기간', type: 'integer', default: 9, min: 3, max: 50 },
        { name: 'slowPeriod', label: '장기 EMA 기간', type: 'integer', default: 21, min: 10, max: 200 },
    ],
    BOLLINGER: [
        { name: 'period', label: '볼린저 기간', type: 'integer', default: 20, min: 5, max: 100 },
        { name: 'stdDevMultiplier', label: '표준편차 배수', type: 'number', default: 2.0, min: 0.5, max: 4.0 },
    ],
    GRID: [
        { name: 'gridCount', label: '그리드 분할 수', type: 'integer', default: 10, min: 3, max: 50 },
        { name: 'gridRange', label: '그리드 범위', type: 'number', default: 0.1, min: 0.02, max: 0.5 },
    ],
    RSI: [
        { name: 'period', label: 'RSI 기간', type: 'integer', default: 14, min: 2, max: 100 },
        { name: 'oversoldLevel', label: '과매도 기준', type: 'number', default: 30, min: 10, max: 45 },
        { name: 'overboughtLevel', label: '과매수 기준', type: 'number', default: 70, min: 55, max: 90 },
    ],
    MACD: [
        { name: 'fastPeriod', label: '단기 EMA', type: 'integer', default: 12, min: 3, max: 50 },
        { name: 'slowPeriod', label: '장기 EMA', type: 'integer', default: 26, min: 10, max: 100 },
        { name: 'signalPeriod', label: '시그널 EMA', type: 'integer', default: 9, min: 3, max: 50 },
    ],
    SUPERTREND: [
        { name: 'atrPeriod', label: 'ATR 기간', type: 'integer', default: 10, min: 3, max: 50 },
        { name: 'multiplier', label: 'ATR 배수', type: 'number', default: 3.0, min: 0.5, max: 10.0 },
    ],
    ATR_BREAKOUT: [
        { name: 'atrPeriod', label: 'ATR 기간', type: 'integer', default: 14, min: 3, max: 50 },
        { name: 'multiplier', label: '돌파 임계값 배수', type: 'number', default: 1.5, min: 0.5, max: 5.0 },
    ],
    ORDERBOOK_IMBALANCE: [
        { name: 'imbalanceThreshold', label: '불균형 임계값', type: 'number', default: 0.65, min: 0.5, max: 0.9 },
        { name: 'lookback', label: '참조 캔들 수', type: 'integer', default: 5, min: 1, max: 20 },
    ],
};

export const paperTradingMock = {
    balance: {
        totalAssetKrw: 11250000,
        availableKrw: 5430000,
        positionValueKrw: 5820000,
        unrealizedPnl: 1250000,
        unrealizedPnlPct: 12.5,
        initialCapital: 10000000,
        updatedAt: '2024-03-06T09:00:00Z',
    },
    positions: [
        {
            id: 'pos-001',
            coinPair: 'KRW-BTC',
            side: 'LONG',
            quantity: 0.05,
            avgEntryPrice: 82000000,
            currentPrice: 85400000,
            positionValueKrw: 4270000,
            unrealizedPnl: 170000,
            unrealizedPnlPct: 4.15,
            strategyType: 'EMA_CROSS',
            enteredAt: '2024-03-04T14:00:00Z',
        },
        {
            id: 'pos-002',
            coinPair: 'KRW-ETH',
            side: 'LONG',
            quantity: 0.5,
            avgEntryPrice: 3080000,
            currentPrice: 3100000,
            positionValueKrw: 1550000,
            unrealizedPnl: 10000,
            unrealizedPnlPct: 0.65,
            strategyType: 'BOLLINGER',
            enteredAt: '2024-03-05T10:30:00Z',
        },
    ],
    orders: {
        content: [
            {
                id: 'ord-001',
                coinPair: 'KRW-BTC',
                side: 'BUY',
                price: 82000000,
                quantity: 0.05,
                status: 'FILLED',
                strategyType: 'EMA_CROSS',
                createdAt: '2024-03-04T14:00:00Z',
                filledAt: '2024-03-04T14:00:01Z',
            },
        ],
        totalElements: 12,
        totalPages: 1,
        number: 0,
    },
};
