import { BacktestResult, TradeRecord, PageResponse, StrategyInfo } from '../lib/types';

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

// GET /api/v1/strategies 응답. `StrategyInfo` 로 타입을 박아두면 백엔드 스키마가
// 바뀔 때(예전에 isActive·isComposite 가 추가됐을 때처럼) 목이 조용히 낡지 않고
// 컴파일 단계에서 걸린다.
export const strategyInfosMock: StrategyInfo[] = [
    // 단일 전략
    { name: 'VWAP', minimumCandleCount: 20, status: 'AVAILABLE', description: '거래량 가중 평균 가격 기반 역추세 매매', isActive: true, isComposite: false },
    { name: 'EMA_CROSS', minimumCandleCount: 21, status: 'AVAILABLE', description: '단기/장기 EMA 골든·데드크로스 추세 추종', isActive: true, isComposite: false },
    { name: 'BOLLINGER', minimumCandleCount: 20, status: 'AVAILABLE', description: '볼린저 밴드 %B 기반 평균 회귀 매매', isActive: false, isComposite: false },
    { name: 'GRID', minimumCandleCount: 1, status: 'AVAILABLE', description: '가격 그리드 레벨 근접 시 매매', isActive: false, isComposite: false },
    { name: 'RSI', minimumCandleCount: 15, status: 'SKELETON', description: 'RSI 과매수/과매도 기반 역추세 매매 (구현 예정)', isActive: false, isComposite: false },
    { name: 'MACD', minimumCandleCount: 35, status: 'SKELETON', description: 'MACD/Signal 크로스 기반 추세 추종 (구현 예정)', isActive: false, isComposite: false },
    { name: 'SUPERTREND', minimumCandleCount: 11, status: 'SKELETON', description: 'ATR 기반 동적 지지/저항 추세 추종 (구현 예정)', isActive: false, isComposite: false },
    { name: 'ATR_BREAKOUT', minimumCandleCount: 15, status: 'SKELETON', description: 'ATR 변동성 돌파 모멘텀 매매 (구현 예정)', isActive: false, isComposite: false },
    { name: 'ORDERBOOK_IMBALANCE', minimumCandleCount: 5, status: 'SKELETON', description: '호가 불균형 기반 단기 방향성 매매 (WebSocket 연동 후 구현 예정)', isActive: false, isComposite: false },
    // 복합 전략 — 목록 화면의 "복합 전략" 탭이 비지 않도록 최소 구성을 채운다
    { name: 'COMPOSITE_BREAKOUT', minimumCandleCount: 35, status: 'AVAILABLE', description: 'ATR×0.5 + VolumeDelta×0.3 + MACD×0.2, EMA·ADX·RSI Veto 필터', isActive: true, isComposite: true, recommendedCoins: ['KRW-BTC', 'KRW-ADA'] },
    { name: 'COMPOSITE_MOMENTUM_ICHIMOKU_V2', minimumCandleCount: 52, status: 'AVAILABLE', description: 'MACD×0.5 + Supertrend×0.3 + Grid×0.2, Ichimoku 필터', isActive: true, isComposite: true, recommendedCoins: ['KRW-DOGE'] },
    { name: 'COMPOSITE_REGIME_ROUTER', minimumCandleCount: 52, status: 'AVAILABLE', description: 'ADX/ATR 레짐 판정 후 CB·V1·V2 로 자동 위임', isActive: false, isComposite: true, recommendedCoins: ['KRW-SOL', 'KRW-ETH'] },
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

// ─── 세션 목록 픽스처 ─────────────────────────────────────────────────────────
// 운영 DB의 실제 세션 구성을 본떴다. 전략명이 길고(COMPOSITE_*) 세션 수가 많아야
// 카드 레이아웃이 좁은 화면에서 깨지는지 확인할 수 있다 — 빈 배열이면 검증이 무의미하다.
const DYNAMIC_STRATEGIES = [
    'COMPOSITE_MOMENTUM_ICHIMOKU_V2',
    'COMPOSITE_MEANREV_BB',
    'COMPOSITE_MTF_BTC_STRICT',
    'COMPOSITE_MTF_CONFIRMED',
    'COMPOSITE_MTF_BTC',
    'COMPOSITE_PULLBACK_MTF',
    'COMPOSITE_MOMENTUM_ICHIMOKU',
];

export const dynamicSessionsMock = DYNAMIC_STRATEGIES.map((strategyType, i) => ({
    id: 39 + i,
    strategyType,
    timeframe: 'H1',
    status: 'RUNNING',
    scanState: i === 0 || i === 6 ? 'POSITION_MONITORING' : 'SCANNING',
    currentCoinPair: i === 0 || i === 6 ? 'KRW-DOGE' : null,
    currentPositionId: i === 0 || i === 6 ? 2400 + i : null,
    initialCapital: 10000,
    availableKrw: [2000, 9813.4, 9436.1, 10000, 8805.5, 10087.78, 2000][i],
    totalAssetKrw: [9817.82, 9813.4, 9436.1, 10000, 8805.5, 10087.78, 9873.2][i],
    returnPct: [-1.82, -1.87, -5.64, 0, -11.95, 0.88, -1.27][i],
    maxCandidateSize: 30,
    targetWatchSize: 10,
    maxHoldHours: i === 1 ? 36 : 0,
    watchlistJson: JSON.stringify([
        'KRW-BTC', 'KRW-XRP', 'KRW-ETH', 'KRW-META2', 'KRW-EUL', 'KRW-SOL', 'KRW-DOGE', 'KRW-AKT',
    ]),
    createdAt: '2026-07-31T01:45:00Z',
    startedAt: '2026-07-31T01:49:52Z',
    stoppedAt: null,
}));

export const liveSessionsMock = [
    {
        id: 194, coinPair: 'KRW-BTC', strategyType: 'COMPOSITE_MEANREV_BB', timeframe: 'M15',
        status: 'RUNNING', initialCapital: 10000, availableKrw: 2000, totalAssetKrw: 10052.3,
        stopLossPct: 5, investRatio: 0.8, strategyParams: null,
        createdAt: '2026-07-31T07:55:00Z', startedAt: '2026-07-31T08:00:00Z', stoppedAt: null,
        updatedAt: '2026-08-06T01:00:00Z',
    },
    {
        id: 195, coinPair: 'KRW-ETH', strategyType: 'COMPOSITE_MOMENTUM_ICHIMOKU_V2', timeframe: 'M15',
        status: 'RUNNING', initialCapital: 10000, availableKrw: 10000, totalAssetKrw: 10000,
        stopLossPct: 5, investRatio: 0.8, strategyParams: null,
        createdAt: '2026-07-31T07:55:00Z', startedAt: '2026-07-31T08:00:00Z', stoppedAt: null,
        updatedAt: '2026-08-06T01:00:00Z',
    },
];
