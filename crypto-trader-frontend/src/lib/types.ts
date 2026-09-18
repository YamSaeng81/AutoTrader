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

export interface WsTickerInfo {
  tradePrice: number;
  change: string;
  signedChangeRate: number;
  receivedAt: string | null;
  receivedSecondsAgo: number;
}

export interface WsStatusResponse {
  available: boolean;
  message?: string;
  connected: boolean;
  subscribedCoins: string[];
  reconnectCount: number;
  lastPongMs: number;
  lastPongSecondsAgo: number;
  lastTickers: Record<string, WsTickerInfo>;
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
    | 'RSI' | 'MACD' | 'SUPERTREND' | 'ATR_BREAKOUT' | 'ORDERBOOK_IMBALANCE' | 'STOCHASTIC_RSI' | 'HEIKIN_ASHI_STOCH'
    | 'COMPOSITE' | 'COMPOSITE_MOMENTUM' | 'COMPOSITE_ETH' | 'COMPOSITE_BREAKOUT' | 'MACD_STOCH_BB'
    | 'COMPOSITE_MOMENTUM_ICHIMOKU' | 'COMPOSITE_MOMENTUM_ICHIMOKU_V2' | 'COMPOSITE_BREAKOUT_ICHIMOKU';
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
  isComposite: boolean;
  recommendedCoins?: string[];
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

export interface WalkForwardWindowMetrics {
  totalReturn: number;
  winRate: number;
  maxDrawdown: number;
  sharpeRatio: number;
  totalTrades: number;
  start: string;
  end: string;
}

export interface WalkForwardWindow {
  windowIndex: number;
  inSample: WalkForwardWindowMetrics;
  outSample: WalkForwardWindowMetrics;
}

export interface WalkForwardResult {
  id?: number;
  windows: WalkForwardWindow[];
  overfittingScore: number;
  /**
   * 백엔드 `WalkForwardTestRunner` 의 판정값. **5종 전부 적어야 한다** —
   * 2026-09-18 에 이 타입이 3종만 선언하고 있어서 `INSUFFICIENT_DATA`(09-09 신설) 행 하나가
   * WF 이력 화면 전체를 흰 화면으로 만들었다. 타입이 좁으면 컴파일러가 누락을 잡아 주지 못한다.
   * 판정값을 추가하면 여기와 화면의 VERDICT_CONFIG 를 함께 갱신할 것.
   */
  verdict: 'ACCEPTABLE' | 'CAUTION' | 'OVERFITTING' | 'INSUFFICIENT_DATA' | 'HOLD_OUT_FAILED';
  strategyType?: string;
  coinPair?: string;
  timeframe?: string;
  inSampleRatio?: number;
  windowCount?: number;
  createdAt?: string;
}

export interface PaperTradingBalance {
  totalAssetKrw: number;
  availableKrw: number;
  positionValueKrw: number;
  unrealizedPnl: number;
  totalReturnPct: number;
  realizedPnl: number;
  totalFee: number;
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
  // 2026-09-15: 아래 넷은 백엔드가 실제로 내려주는데 선언이 빠져 있었다.
  // 화면이 `as any` 로 우회해 쓰고 있었으므로 누락이 드러나지 않았다.
  fee?: number | null;
  /** 매도 행에만 있다 — 대응 매수 체결가 */
  buyPrice?: number | null;
  realizedPnl?: number | null;
  realizedPnlPct?: number | null;
}

export interface PaperTradingStartRequest {
  strategyType: string;
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  strategyParams?: Record<string, number>;
  enableTelegram?: boolean;
}

export interface MultiStrategyPaperRequest {
  strategyTypes: string[];
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  enableTelegram?: boolean;
}

export interface MultiStrategyBacktestRequest {
  strategyTypes: string[];
  coinPair: string;
  timeframe: string;
  startDate: string;
  endDate: string;
  initialCapital?: number;
  slippagePct?: number;
  feePct?: number;
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
  realizedPnl: number;
  totalFee: number;
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

export type LiveSessionStatus = 'CREATED' | 'RUNNING' | 'STOPPED' | 'EMERGENCY_STOPPED' | 'DELETED';

/** 세션 선택 UI용 통합 인덱스 항목 (삭제/모의 세션 포함) */
export interface SessionIndexEntry {
  sessionId: number;
  strategyType: string | null;
  coinPair: string | null;
  status: string;       // RUNNING | STOPPED | EMERGENCY_STOPPED | CREATED | DELETED | PAPER
  sessionType: string;  // LIVE | PAPER
}

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
  investRatio: number;
  strategyParams: Record<string, unknown> | null;
  createdAt: string;
  startedAt: string | null;
  stoppedAt: string | null;
  updatedAt: string;
  circuitBreakerReason: string | null;
  circuitBreakerTriggeredAt: string | null;
}

export interface MultiStrategyLiveRequest {
  strategyTypes: string[];
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  stopLossPct?: number;
  investRatio?: number;
}

export interface LiveTradingStartRequest {
  strategyType: string;
  coinPair: string;
  timeframe: string;
  initialCapital: number;
  stopLossPct?: number;
  investRatio?: number;
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
  responseJson: string | null;
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
  mddThresholdPct: number;
  consecutiveLossLimit: number;
  circuitBreakerEnabled: boolean;
  // 포지션 수준 리스크 (ExitRuleConfig)
  stopLossPct: number;
  takeProfitMultiplier: number;
  trailingEnabled: boolean;
  trailingTpMarginPct: number;
  trailingSlMarginPct: number;
  investRatioPct: number;
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

// ─── 성과 통계 타입 ──────────────────────────────────────────────────────────

export interface SessionPerformance {
  sessionId: number;
  strategyType: string;
  coinPair: string;
  timeframe: string;
  status: string;
  initialCapital: number;
  currentAsset: number;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  returnRatePct: number;
  totalFee: number;
  totalTrades: number;
  winCount: number;
  winRatePct: number;
  startedAt: string | null;
  stoppedAt: string | null;
  // 리스크 조정 지표
  mddPct: number | null;
  sharpeRatio: number | null;
  sortinoRatio: number | null;
  winLossRatio: number | null;
  avgProfitPct: number | null;
  avgLossPct: number | null;
  maxConsecutiveLoss: number | null;
  monthlyReturns: Record<string, number> | null;
  // 세션 내 레짐별 성과
  regimeBreakdown: Record<string, RegimeStat> | null;
  // 세션 내 청산 경로별 성과
  exitReasonBreakdown: Record<string, RegimeStat> | null;
}

export interface RegimeStat {
  trades: number;
  wins: number;
  winRatePct: number;
  totalPnl: number;
}

export interface PerformanceSummary {
  totalRealizedPnl: number;
  totalUnrealizedPnl: number;
  totalPnl: number;
  totalInitialCapital: number;
  returnRatePct: number;
  totalFee: number;
  totalTrades: number;
  winCount: number;
  lossCount: number;
  winRatePct: number;
  // 리스크 조정 지표
  mddPct: number | null;
  sharpeRatio: number | null;
  sortinoRatio: number | null;
  calmarRatio: number | null;
  winLossRatio: number | null;
  recoveryFactor: number | null;
  avgProfitPct: number | null;
  avgLossPct: number | null;
  maxConsecutiveLoss: number | null;
  monthlyReturns: Record<string, number> | null;
  // 레짐별 성과
  regimeBreakdown: Record<string, RegimeStat> | null;
  // 청산 경로별 성과 — STOP_LOSS/TAKE_PROFIT/STRATEGY_SELL/FORCED_STOP/PHANTOM
  exitReasonBreakdown: Record<string, RegimeStat> | null;
  sessions: SessionPerformance[];
}

// ─── 서버 리소스 메트릭 ──────────────────────────────────────────────────────

export interface SystemMetrics {
  cpuUsagePct: number;   // -1 이면 측정 불가
  memUsedMb: number;
  memTotalMb: number;
  memUsagePct: number;
  heapUsedMb: number;
  heapMaxMb: number;
  heapUsagePct: number;
  diskUsedGb: number;
  diskTotalGb: number;
  diskUsagePct: number;
}

export interface RegimeChangeLog {
  id: number;
  coinPair: string;
  timeframe: string;
  fromRegime: string | null;
  toRegime: string;
  strategyChangesJson: string | null;
  detectedAt: string;
}

export interface SignalStatsBucket {
  evaluated4h: number;
  winRate4h: number;
  avgReturn4h: number;
  evaluated24h: number;
  winRate24h: number;
  avgReturn24h: number;
}

export interface SignalStatsOverall extends SignalStatsBucket {
  totalSignals: number;
}

export interface SignalStatsByStrategy extends SignalStatsBucket {
  strategyName: string;
  coinPair: string;
  totalSignals: number;
}

export interface SignalStatsByRegime extends SignalStatsBucket {
  regime: string;
  totalSignals: number;
}

export interface BlockedSignalBucket extends SignalStatsBucket {
  totalSignals: number;
}

export type FilterVerdict = 'FILTER_HURTING' | 'FILTER_HELPING' | 'NEUTRAL' | 'INSUFFICIENT';

export interface BlockedReasonStat extends SignalStatsBucket {
  reason: string;
  totalBlocked: number;
  verdict: FilterVerdict;
}

export interface BlockedVsExecutedStats {
  executed: BlockedSignalBucket;
  blocked: BlockedSignalBucket;
  byBlockReason: BlockedReasonStat[];
}

export interface SignalStatsByHour extends SignalStatsBucket {
  hour: number;        // 0-23 (KST)
  totalSignals: number;
}

export interface SignalStatsResponse {
  overall: SignalStatsOverall;
  byStrategy: SignalStatsByStrategy[];
  byRegime: SignalStatsByRegime[];
  blockedVsExecuted: BlockedVsExecutedStats;
  byHour: SignalStatsByHour[];
}

export interface NightlySchedulerConfig {
  enabled: boolean;
  runHour: number;
  runMinute: number;
  timeframe: string;
  startDate: string;
  endDate: string;
  coinPairs: string[];
  strategyTypes: string[];
  includeBacktest: boolean;
  includeWalkForward: boolean;
  inSampleRatio: number;
  windowCount: number;
  initialCapital: number;
  slippagePct: number;
  feePct: number;
  // 읽기 전용
  lastTriggeredAt?: string;
  lastBatchJobId?: number;
  lastWfJobId?: number;
  nextRunAt?: string;
}

export interface BacktestJob {
  id: number;
  jobType: string;            // SINGLE | BULK | MULTI_STRATEGY | WALK_FORWARD_BATCH
  status: string;             // PENDING | RUNNING | COMPLETED | FAILED
  coinPair?: string;
  strategyName?: string;
  timeframe?: string;
  totalChunks?: number;
  completedChunks?: number;
  progressPct?: number;
  backtestRunId?: number;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
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

// ─────────────────────────────────────────────────────────────────────────────
// 전략 로그 · 세션 차트 (2026-09-15)
//
// 이전에는 이 응답들을 `as any` 로 받아 쓰고 있었다. 백엔드가 실제로 내려주는 필드
// 중 **화면이 읽는 것만** 선언한다 — 없는 필드를 상상해 넣지 않는다.
// 새 필드를 쓰게 되면 그때 여기에 추가할 것.
// ─────────────────────────────────────────────────────────────────────────────

/** `strategy_log` 한 행. 화면은 판단 근거 표시에만 쓴다. */
export interface StrategyLogEntry {
  id: number;
  strategyName: string | null;
  coinPair: string | null;
  signal: string | null;
  reason: string | null;
  marketRegime: string | null;
  timeframe: string | null;
  sessionType: string | null;
  sessionId: number | null;
  createdAt: string | null;
  /** 실제로 주문이 나갔는지. false 면 `blockedReason` 에 차단 사유가 있다. */
  wasExecuted?: boolean | null;
  blockedReason?: string | null;
  /** 사후수익 백필 결과 (2026-09-07 이후) */
  return4hPct?: number | null;
  return24hPct?: number | null;
  /** 신호 발생 시점의 가격 */
  signalPrice?: number | null;
}

/** 세션 차트용 캔들. 종가만 그리므로 최소 필드다. */
export interface SessionChartCandle {
  time: number;
  close: number | string;
}

/**
 * 세션 차트에 겹쳐 그리는 체결.
 *
 * `fmtOrderQuantity`(lib/utils)가 시장가 매수의 KRW/수량 구분을 위해 `orderType`·
 * `filledQuantity`를 읽으므로 함께 선언한다.
 */
export interface SessionChartOrder {
  side: OrderSide;
  orderType?: string | null;
  price?: number | null;
  quantity?: number | null;
  filledQuantity?: number | null;
  fee?: number | null;
  realizedPnl?: number | null;
  signalReason?: string | null;
  filledAt?: string | number | null;
}

/** `GET .../chart` 응답 */
export interface SessionChartResponse {
  candles?: SessionChartCandle[];
  orders?: SessionChartOrder[];
}

/** 차트에 주입하는 한 점 — 캔들에 해당 시각 체결을 붙인 형태. */
export interface SessionChartPoint {
  time: number;
  close: number;
  buyOrder: SessionChartOrder | null;
  sellOrder: SessionChartOrder | null;
}

/**
 * Recharts Tooltip 커스텀 컴포넌트 props.
 *
 * recharts 가 내보내는 `TooltipProps` 는 제네릭 제약이 버전마다 달라 화면 코드에서
 * 그대로 쓰기 번거롭다. 여기서는 **우리가 실제로 읽는 세 가지**만 선언한다.
 */
export interface ChartTooltipProps<T = SessionChartPoint> {
  active?: boolean;
  payload?: Array<{ payload?: T }>;
  label?: string | number;
}

/** Recharts 커스텀 dot 렌더러가 받는 props 중 우리가 쓰는 부분. */
export interface ChartDotProps<T = SessionChartPoint> {
  cx?: number;
  cy?: number;
  payload?: T;
  index?: number;
}

// ─── LLM 호출 로그 (2026-09-15) ──────────────────────────────────────────────

/** `llm_call_log` 목록 행. */
export interface LlmCallLogItem {
  id: number;
  taskName: string | null;
  providerName: string | null;
  modelUsed: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  durationMs: number | null;
  success: boolean | null;
  errorMessage: string | null;
  responsePreview: string | null;
  calledAt: string | null;
}

/** 상세 조회 — 목록 행에 프롬프트·응답 전문이 더해진다. */
export interface LlmCallLogDetail extends LlmCallLogItem {
  systemPrompt: string | null;
  userPrompt: string | null;
  responseContent: string | null;
}

/**
 * 토큰 사용량 집계 한 줄.
 *
 * 집계 축에 따라 `task` 또는 `provider` 중 하나가 채워진다 — 화면은 `keyLabel` 로
 * 어느 쪽을 읽을지 고른다. (목록 행의 `taskName`/`providerName` 과 키 이름이 다르다.)
 */
export interface LlmTokenBreakdownRow {
  task?: string | null;
  provider?: string | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
}

/** LLM 사용량 통계 응답. */
export interface LlmUsageStats {
  todayCallCount?: number;
  todayTotalTokens?: number;
  weekCallCount?: number;
  weekTotalTokens?: number;
  totalCallCount?: number;
  byTask?: LlmTokenBreakdownRow[];
  byProvider?: LlmTokenBreakdownRow[];
}

/** LLM 로그 목록 응답 (Spring Page 가 아니라 items/totalPages 형태다). */
export interface LlmCallLogPage {
  items?: LlmCallLogItem[];
  totalPages?: number;
}
