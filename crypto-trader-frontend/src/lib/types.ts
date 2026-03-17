export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: { code: string; message: string } | null;
}

export interface TelegramNotificationLog {
  id: number;
  type: string;
  sessionLabel: string;
  messageText: string;
  success: boolean;
  sentAt: string;
}

export interface TelegramLogsResponse {
  items: TelegramNotificationLog[];
  totalCount: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface UpbitCandleSummary {
  coinPair: string;
  timeframe: string;
  from: string | null;
  to: string | null;
  count: number;
}

export interface UpbitStatusResponse {
  apiKeyConfigured: boolean;
  accountQueryOk: boolean;
  totalAssetKrw?: number;
  accountError?: string;
  candleQueryOk: boolean;
  candleSummary?: UpbitCandleSummary[];
  candleError?: string;
}

export type StrategyType = 'VWAP' | 'EMA_CROSS' | 'BOLLINGER' | 'GRID'
    | 'RSI' | 'MACD' | 'SUPERTREND' | 'ATR_BREAKOUT' | 'ORDERBOOK_IMBALANCE' | 'STOCHASTIC_RSI'
    | 'COMPOSITE';
export type Timeframe = 'M1' | 'M5' | 'M15' | 'M30' | 'H1' | 'H4' | 'D1';
export type OrderSide = 'BUY' | 'SELL';
export type MarketRegime = 'TREND' | 'RANGE' | 'VOLATILE';
export type BacktestStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface PerformanceMetrics {
  totalReturn: number;      // 퍼센트 (23.5 = 23.5%)
  winRate: number;          // 퍼센트
  maxDrawdown: number;      // 퍼센트, 음수 (-12.4 = -12.4%)
  sharpeRatio: number;
  sortinoRatio: number;
  calmarRatio: number;
  winLossRatio: number;
  recoveryFactor: number;
  totalTrades: number;
  maxConsecutiveLoss: number;
  monthlyReturns: Record<string, number>;  // "YYYY-MM" -> 퍼센트
}

export interface BacktestResult {
  id: string;
  strategyType: StrategyType;
  coinPair: string;
  timeframe: Timeframe;
  startDate: string;
  endDate: string;
  initialCapital: number;  // 원화 단위
  status: BacktestStatus;
  metrics: PerformanceMetrics;
  createdAt: string;
}

export interface TradeRecord {
  side: OrderSide;
  price: number;        // 원화
  quantity: number;     // 코인 수량
  fee: number;          // 원화
  slippage: number;     // 원화
  pnl: number;          // 원화
  cumulativePnl: number;
  signalReason: string;
  marketRegime: MarketRegime;
  executedAt: string;
}

export interface BacktestRequest {
  strategyType: StrategyType;
  coinPair: string;
  timeframe: Timeframe;
  startDate: string;
  endDate: string;
  initialCapital?: number;
  slippageRate?: number;
  feeRate?: number;
  strategyParams?: Record<string, number>;
  fillSimulation?: { enabled: boolean; impactFactor: number; fillRatio: number };
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;  // 0-based
}

// Phase 3 추가 타입
export type StrategyStatus = 'AVAILABLE' | 'SKELETON';

export interface StrategyInfo {
  name: string;
  minimumCandleCount: number;
  status: StrategyStatus;
  description: string;
  isActive: boolean;
}

// Phase 3.5 추가 타입
// Walk Forward 타입
export interface WalkForwardRequest {
  strategyType: string;
  coinPair: string;
  timeframe: Timeframe;
  startDate: string;
  endDate: string;
  inSampleRatio?: number;
  windowCount?: number;
  initialCapital?: number;
  slippagePct?: number;
  feePct?: number;
  config?: Record<string, number>;
}

export interface WalkForwardWindow {
  inSample: { start: string; end: string; returnPct: number };
  outSample: { start: string; end: string; returnPct: number };
}

export interface WalkForwardResult {
  windows: WalkForwardWindow[];
  overfittingScore: number;
  verdict: 'ACCEPTABLE' | 'CAUTION' | 'OVERFITTING';
}

export interface PaperTradingBalance {
  totalAssetKrw: number;
  availableKrw: number;
  positionValueKrw: number;
  unrealizedPnl: number;
  totalReturnPct: number;
  initialCapital: number;
  status: 'RUNNING' | 'STOPPED';
  strategyName: string;
  coinPair: string;
  startedAt: string | null;
}

export interface PaperPosition {
  id: number;
  coinPair: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  avgEntryPrice: number;
  unrealizedPnl: number;
  unrealizedPnlPct: number;
  openedAt: string;
}

export interface PaperOrder {
  id: number;
  coinPair: string;
  side: 'BUY' | 'SELL';
  price: number;
  quantity: number;
  state: 'PENDING' | 'FILLED' | 'CANCELLED';
  signalReason: string;
  createdAt: string;
  filledAt: string | null;
}

export interface PaperTradingStartRequest {
  strategyType: string;
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  strategyParams?: Record<string, number>;
  enableTelegram?: boolean;
}

export interface PaperSession {
  id: number;
  strategyName: string;
  coinPair: string;
  timeframe: string;
  status: 'RUNNING' | 'STOPPED';
  totalAssetKrw: number;
  availableKrw: number;
  initialCapital: number;
  totalReturnPct: number;
  startedAt: string | null;
  stoppedAt: string | null;
}

// ─── Phase 4: 실전 매매 타입 ────────────────────────────────────────────────

export type TradingStatusType = 'RUNNING' | 'STOPPED' | 'EMERGENCY_STOPPED';
export type ExchangeHealthStatus = 'UP' | 'DEGRADED' | 'DOWN';
export type PositionSide = 'LONG' | 'SHORT';
export type PositionStatus = 'OPEN' | 'CLOSED';
export type LiveOrderSide = 'BUY' | 'SELL';
export type LiveOrderType = 'MARKET' | 'LIMIT';
export type LiveOrderState = 'PENDING' | 'SUBMITTED' | 'PARTIAL_FILLED' | 'FILLED' | 'CANCELLED' | 'FAILED';

export interface TradingStatus {
  status: TradingStatusType;
  openPositions: number;
  activeOrders: number;
  totalPnl: number;
  startedAt: string | null;
  exchangeHealth: ExchangeHealthStatus;
  runningSessions: number;
  totalSessions: number;
}

export type LiveSessionStatus = 'CREATED' | 'RUNNING' | 'STOPPED' | 'EMERGENCY_STOPPED';

export interface LiveTradingSession {
  id: number;
  strategyType: string;
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  availableKrw: number;
  totalAssetKrw: number;
  status: LiveSessionStatus;
  stopLossPct: number | null;
  strategyParams: Record<string, unknown> | null;
  createdAt: string;
  startedAt: string | null;
  stoppedAt: string | null;
  updatedAt: string;
}

export interface LiveTradingStartRequest {
  strategyType: string;
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  stopLossPct?: number;
  strategyParams?: Record<string, unknown>;
}

export interface Position {
  id: number;
  coinPair: string;
  side: PositionSide;
  entryPrice: number;
  avgPrice: number;
  size: number;
  unrealizedPnl: number;
  realizedPnl: number;
  status: PositionStatus;
  openedAt: string;
  closedAt: string | null;
}

export interface LiveOrder {
  id: number;
  positionId: number | null;
  sessionId: number | null;
  coinPair: string;
  side: LiveOrderSide;
  orderType: LiveOrderType;
  price: number;
  quantity: number;
  state: LiveOrderState;
  exchangeOrderId: string | null;
  filledQuantity: number;
  signalReason: string;
  failedReason: string | null;
  createdAt: string;
  submittedAt: string | null;
  filledAt: string | null;
  cancelledAt: string | null;
}

export interface ExchangeHealth {
  status: ExchangeHealthStatus;
  latencyMs: number;
  webSocketConnected: boolean;
  lastCheckedAt: string;
  recentLatencies: number[];
}

export interface RiskConfig {
  id: number;
  maxDailyLossPct: number;
  maxWeeklyLossPct: number;
  maxMonthlyLossPct: number;
  maxPositions: number;
  cooldownMinutes: number;
  portfolioLimitKrw: number;
}

// ─── Upbit 계좌 현황 타입 ─────────────────────────────────────────────────

export interface UpbitHolding {
  currency: string;
  market: string;
  balance: number;
  locked: number;
  totalQuantity: number;
  avgBuyPrice: number;
  currentPrice: number;
  evalValue: number;
  buyCost: number;
  unrealizedPnl: number;
  unrealizedPnlPct: number;
}

export interface AccountSummary {
  apiKeyConfigured: boolean;
  message?: string;
  error?: string;
  totalAssetKrw?: number;
  availableKrw?: number;
  lockedKrw?: number;
  totalKrwBalance?: number;
  totalCoinValueKrw?: number;
  totalBuyCostKrw?: number;
  totalUnrealizedPnl?: number;
  totalUnrealizedPnlPct?: number;
  holdings?: UpbitHolding[];
  fetchedAt?: string;
}
