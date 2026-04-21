import axios from 'axios';
import type {
    ApiResponse, BacktestRequest, BacktestResult, TradeRecord, PageResponse, StrategyType,
    StrategyInfo, PaperTradingBalance, PaperPosition, PaperTradingStartRequest,
    MultiStrategyPaperRequest, MultiStrategyBacktestRequest,
    WalkForwardRequest, WalkForwardResult,
    TradingStatus, Position, LiveOrder, ExchangeHealth, RiskConfig,
    LiveTradingSession, LiveTradingStartRequest, PerformanceSummary, WsStatusResponse,
    SignalStatsResponse, NightlySchedulerConfig, BacktestJob,
} from './types';

// 클라이언트 컴포넌트에서 사용: /api/proxy 경유 → 서버사이드에서 API_TOKEN 주입
// NEXT_PUBLIC_API_TOKEN을 번들에 포함하지 않기 위해 Next.js API Route proxy 사용
const api = axios.create({
    baseURL: '/api/proxy',
    timeout: 30000,
});

export const backtestApi = {
    run: (req: BacktestRequest) =>
        api.post<ApiResponse<{ jobId: number; message: string }>>('/api/v1/backtest/run-async', req).then(r => r.data),
    runMultiStrategy: (req: MultiStrategyBacktestRequest) =>
        api.post<ApiResponse<{ jobId: number; message: string }>>('/api/v1/backtest/multi-strategy-async', req).then(r => r.data),
    runBatch: (req: { coinPairs: string[]; strategyTypes: string[]; timeframe: string; startDate: string; endDate: string; initialCapital: number }) =>
        api.post<ApiResponse<{ jobId: number; total: number; message: string }>>('/api/v1/backtest/batch-async', req).then(r => r.data),
    get: (id: string) =>
        api.get<ApiResponse<BacktestResult>>(`/api/v1/backtest/${id}`).then(r => r.data),
    list: (_page = 0) =>
        api.get<ApiResponse<BacktestResult[]>>('/api/v1/backtest/list').then(r => r.data),
    compare: (ids: string[]) =>
        api.get<ApiResponse<BacktestResult[]>>('/api/v1/backtest/compare', { params: { ids: ids.join(',') } }).then(r => r.data),
    trades: (id: string, page = 0, size = 10000) =>
        api.get<ApiResponse<PageResponse<TradeRecord>>>(`/api/v1/backtest/${id}/trades`, { params: { page, size } }).then(r => r.data),
    walkForward: (req: WalkForwardRequest) =>
        api.post<ApiResponse<WalkForwardResult>>('/api/v1/backtest/walk-forward', req, { timeout: 300000 }).then(r => r.data),
    walkForwardHistory: () =>
        api.get<ApiResponse<WalkForwardResult[]>>('/api/v1/backtest/walk-forward/history').then(r => r.data),
    delete: (id: string | number) =>
        api.delete<ApiResponse<null>>(`/api/v1/backtest/${id}`).then(r => r.data),
    bulkDelete: (ids: (string | number)[]) =>
        api.delete<ApiResponse<null>>('/api/v1/backtest/bulk', { data: { ids } }).then(r => r.data),
};

interface StrategyTypeOption {
    type: StrategyType;
    name: string;
    description: string;
    params: { name: string; type: string; default: number; description: string }[];
}

export const systemApi = {
    strategyTypes: () => api.get<ApiResponse<StrategyTypeOption[]>>('/api/v1/strategies/types').then(r => r.data),
    coins: () => api.get<ApiResponse<string[]>>('/api/v1/data/coins').then(r => r.data),
};

export const strategyApi = {
    list: () =>
        api.get<ApiResponse<StrategyInfo[]>>('/api/v1/strategies').then(r => r.data),
    get: (name: string) =>
        api.get<ApiResponse<StrategyInfo>>(`/api/v1/strategies/${name}`).then(r => r.data),
    create: (config: unknown) =>
        api.post<ApiResponse<unknown>>('/api/v1/strategies', config).then(r => r.data),
    update: (id: string, config: unknown) =>
        api.put<ApiResponse<unknown>>(`/api/v1/strategies/${id}`, config).then(r => r.data),
    toggle: (id: string) =>
        api.patch<ApiResponse<unknown>>(`/api/v1/strategies/${id}/toggle`).then(r => r.data),
    toggleActive: (name: string) =>
        api.patch<ApiResponse<StrategyInfo>>(`/api/v1/strategies/${name}/active`).then(r => r.data),
};

export const dataApi = {
    collect: (req: { coinPair: string; timeframe: string; startDate: string; endDate: string }) =>
        api.post<ApiResponse<{ status: string; coinPair: string; timeframe: string }>>('/api/v1/data/collect', req).then(r => r.data),
    batchCollect: (req: { coinPairs: string[]; timeframe: string; startDate: string; endDate: string }) =>
        api.post<ApiResponse<{ status: string; coinCount: number; coinPairs: string[]; timeframe: string }>>('/api/v1/data/collect/batch', req).then(r => r.data),
    coins: () =>
        api.get<ApiResponse<string[]>>('/api/v1/data/coins').then(r => r.data),
    markets: () =>
        api.get<ApiResponse<{ market: string; koreanName: string; englishName: string }[]>>('/api/v1/data/markets').then(r => r.data),
    summary: () =>
        api.get<ApiResponse<{ coinPair: string; timeframe: string; from: string; to: string; count: number }[]>>('/api/v1/data/summary').then(r => r.data),
    deleteCandles: (coinPair: string, timeframe?: string) =>
        api.delete<ApiResponse<{ coinPair: string; timeframe: string; deletedCount: number }>>(
            '/api/v1/data/candles',
            { params: timeframe ? { coinPair, timeframe } : { coinPair } }
        ).then(r => r.data),
};

export const logApi = {
    strategyLogs: (page = 0, size = 50, sessionType = 'ALL', sessionId?: number) =>
        api.get<ApiResponse<{ content: unknown[]; totalElements: number; totalPages: number; number: number }>>('/api/v1/logs/strategy', {
            params: {
                page,
                size,
                sessionType: sessionType === 'ALL' ? undefined : sessionType,
                sessionId: sessionId ?? undefined,
            }
        }).then(r => r.data),

    signalStats: (days = 30, sessionType = 'ALL') =>
        api.get<ApiResponse<SignalStatsResponse>>('/api/v1/logs/signal-stats', {
            params: {
                days,
                sessionType: sessionType === 'ALL' ? undefined : sessionType,
            }
        }).then(r => r.data),
};

export const tradingApi = {
    // 세션 관리
    createSession: (req: LiveTradingStartRequest) =>
        api.post<ApiResponse<LiveTradingSession>>('/api/v1/trading/sessions', req).then(r => r.data),
    createMulti: (req: import('./types').MultiStrategyLiveRequest) =>
        api.post<ApiResponse<LiveTradingSession[]>>('/api/v1/trading/sessions/multi', req).then(r => r.data),
    listSessions: () =>
        api.get<ApiResponse<LiveTradingSession[]>>('/api/v1/trading/sessions').then(r => r.data),
    getSession: (id: number) =>
        api.get<ApiResponse<LiveTradingSession>>(`/api/v1/trading/sessions/${id}`).then(r => r.data),
    startSession: (id: number) =>
        api.post<ApiResponse<LiveTradingSession>>(`/api/v1/trading/sessions/${id}/start`).then(r => r.data),
    stopSession: (id: number) =>
        api.post<ApiResponse<LiveTradingSession>>(`/api/v1/trading/sessions/${id}/stop`).then(r => r.data),
    emergencyStopSession: (id: number) =>
        api.post<ApiResponse<LiveTradingSession>>(`/api/v1/trading/sessions/${id}/emergency-stop`).then(r => r.data),
    deleteSession: (id: number) =>
        api.delete<ApiResponse<null>>(`/api/v1/trading/sessions/${id}`).then(r => r.data),
    getSessionPositions: (id: number) =>
        api.get<ApiResponse<Position[]>>(`/api/v1/trading/sessions/${id}/positions`).then(r => r.data),
    getSessionOrders: (id: number, page = 0, size = 20) =>
        api.get<ApiResponse<PageResponse<LiveOrder>>>(`/api/v1/trading/sessions/${id}/orders`, { params: { page, size } }).then(r => r.data),
    getSessionChart: (id: number) =>
        api.get<ApiResponse<{ candles: unknown[]; orders: unknown[] }>>(`/api/v1/trading/sessions/${id}/chart`).then(r => r.data),

    // 전체 상태
    getStatus: () =>
        api.get<ApiResponse<TradingStatus>>('/api/v1/trading/status').then(r => r.data),
    emergencyStopAll: () =>
        api.post<ApiResponse<TradingStatus>>('/api/v1/trading/emergency-stop').then(r => r.data),

    // 전체 포지션/주문
    getPositions: () =>
        api.get<ApiResponse<Position[]>>('/api/v1/trading/positions').then(r => r.data),
    getOrders: (page = 0, size = 20, sessionId?: number, dateFrom?: string, dateTo?: string) =>
        api.get<ApiResponse<PageResponse<LiveOrder>>>('/api/v1/trading/orders', {
            params: { page, size, sessionId, dateFrom, dateTo },
        }).then(r => r.data),
    cancelOrder: (id: number) =>
        api.delete<ApiResponse<null>>(`/api/v1/trading/orders/${id}`).then(r => r.data),

    // 리스크 / 거래소
    getRiskConfig: () =>
        api.get<ApiResponse<RiskConfig>>('/api/v1/trading/risk/config').then(r => r.data),
    updateRiskConfig: (config: Omit<RiskConfig, 'id'>) =>
        api.put<ApiResponse<RiskConfig>>('/api/v1/trading/risk/config', config).then(r => r.data),
    getExchangeHealth: () =>
        api.get<ApiResponse<ExchangeHealth>>('/api/v1/trading/health/exchange').then(r => r.data),

    // 성과 통계
    getPerformance: () =>
        api.get<ApiResponse<PerformanceSummary>>('/api/v1/trading/performance').then(r => r.data),
};

export const paperTradingApi = {
    sessions: () =>
        api.get<ApiResponse<import('./types').PaperSession[]>>('/api/v1/paper-trading/sessions').then(r => r.data),
    start: (req: PaperTradingStartRequest) =>
        api.post<ApiResponse<import('./types').PaperSession>>('/api/v1/paper-trading/sessions', req).then(r => r.data),
    startMulti: (req: MultiStrategyPaperRequest) =>
        api.post<ApiResponse<import('./types').PaperSession[]>>('/api/v1/paper-trading/sessions/multi', req).then(r => r.data),
    getSession: (id: string | number) =>
        api.get<ApiResponse<PaperTradingBalance>>(`/api/v1/paper-trading/sessions/${id}`).then(r => r.data),
    positions: (id: string | number, status = 'OPEN') =>
        api.get<ApiResponse<PaperPosition[]>>(`/api/v1/paper-trading/sessions/${id}/positions`, { params: { status } }).then(r => r.data),
    orders: (id: string | number, page = 0) =>
        api.get<ApiResponse<PageResponse<unknown>>>(`/api/v1/paper-trading/sessions/${id}/orders`, { params: { page } }).then(r => r.data),
    stop: (id: string | number) =>
        api.post<ApiResponse<unknown>>(`/api/v1/paper-trading/sessions/${id}/stop`).then(r => r.data),
    stopAll: () =>
        api.post<ApiResponse<{ stoppedCount: number }>>('/api/v1/paper-trading/sessions/stop-all').then(r => r.data),
    chart: (id: string | number) =>
        api.get<ApiResponse<{ candles: unknown[]; orders: unknown[] }>>(`/api/v1/paper-trading/sessions/${id}/chart`).then(r => r.data),
    deleteHistory: (id: string | number) =>
        api.delete<ApiResponse<null>>(`/api/v1/paper-trading/history/${id}`).then(r => r.data),
    bulkDeleteHistory: (ids: (string | number)[]) =>
        api.delete<ApiResponse<null>>('/api/v1/paper-trading/history/bulk', { data: { ids } }).then(r => r.data),

    // 성과 통계
    getPerformance: () =>
        api.get<ApiResponse<PerformanceSummary>>('/api/v1/paper-trading/performance').then(r => r.data),
};

export const accountApi = {
    summary: () =>
        api.get<ApiResponse<import('./types').AccountSummary>>('/api/v1/account/summary').then(r => r.data),
};

export const logsApi = {
    regimeHistory: (size = 100) =>
        api.get<ApiResponse<import('./types').RegimeChangeLog[]>>(`/api/v1/logs/regime-history?size=${size}`).then(r => r.data),
    strategyWeights: () =>
        api.get<ApiResponse<Record<string, unknown>>>('/api/v1/logs/strategy-weights').then(r => r.data),
    optimizeWeights: () =>
        api.post<ApiResponse<Record<string, unknown>>>('/api/v1/logs/strategy-weights/optimize').then(r => r.data),
};

// ── Admin API ────────────────────────────────────────────────────────────────

export const adminLlmApi = {
    getProviders: () =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/admin/llm/providers').then(r => r.data),
    updateProvider: (providerName: string, body: Record<string, unknown>) =>
        api.put<ApiResponse<string>>(`/api/v1/admin/llm/providers/${providerName}`, body).then(r => r.data),
    getTasks: () =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/admin/llm/tasks').then(r => r.data),
    updateTask: (taskName: string, body: Record<string, unknown>) =>
        api.put<ApiResponse<string>>(`/api/v1/admin/llm/tasks/${taskName}`, body).then(r => r.data),
    testProvider: (providerName: string, prompt: string) =>
        api.post<ApiResponse<Record<string, unknown>>>('/api/v1/admin/llm/test/provider', { providerName, prompt }).then(r => r.data),
    testTask: (task: string, systemPrompt: string, userPrompt: string) =>
        api.post<ApiResponse<Record<string, unknown>>>('/api/v1/admin/llm/test/task', { task, systemPrompt, userPrompt }).then(r => r.data),
    getLogs: (params: { page?: number; size?: number; task?: string; provider?: string }) =>
        api.get<ApiResponse<Record<string, unknown>>>('/api/v1/admin/llm/logs', { params }).then(r => r.data),
    getLogDetail: (id: number) =>
        api.get<ApiResponse<Record<string, unknown>>>(`/api/v1/admin/llm/logs/${id}`).then(r => r.data),
    getTokenStats: () =>
        api.get<ApiResponse<Record<string, unknown>>>('/api/v1/admin/llm/logs/stats').then(r => r.data),
};

export const adminNewsApi = {
    getSources: () =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/admin/news-sources').then(r => r.data),
    createSource: (body: Record<string, unknown>) =>
        api.post<ApiResponse<string>>('/api/v1/admin/news-sources', body).then(r => r.data),
    updateSource: (sourceId: string, body: Record<string, unknown>) =>
        api.put<ApiResponse<string>>(`/api/v1/admin/news-sources/${sourceId}`, body).then(r => r.data),
    deleteSource: (sourceId: string) =>
        api.delete<ApiResponse<string>>(`/api/v1/admin/news-sources/${sourceId}`).then(r => r.data),
    fetchNow: (sourceId: string) =>
        api.post<ApiResponse<Record<string, unknown>>>(`/api/v1/admin/news-sources/${sourceId}/fetch`).then(r => r.data),
    getCache: (params?: { size?: number; category?: string }) =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/admin/news-sources/cache', { params }).then(r => r.data),
};

export const adminReportApi = {
    getConfig: () =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/admin/reports/config').then(r => r.data),
    updateConfig: (updates: Record<string, string>) =>
        api.put<ApiResponse<string>>('/api/v1/admin/reports/config', updates).then(r => r.data),
    trigger: (hours = 12) =>
        api.post<ApiResponse<Record<string, unknown>>>('/api/v1/admin/reports/trigger', { hours }).then(r => r.data),
    getHistory: (size = 20) =>
        api.get<ApiResponse<Record<string, unknown>[]>>(`/api/v1/admin/reports/history?size=${size}`).then(r => r.data),
};

export const adminDiscordApi = {
    getChannels: () =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/admin/discord/channels').then(r => r.data),
    updateChannel: (channelType: string, body: Record<string, unknown>) =>
        api.put<ApiResponse<string>>(`/api/v1/admin/discord/channels/${channelType}`, body).then(r => r.data),
    testChannel: (channelType: string) =>
        api.post<ApiResponse<Record<string, unknown>>>(`/api/v1/admin/discord/test/${channelType}`).then(r => r.data),
    sendBriefing: (channels?: string[]) =>
        api.post<ApiResponse<string>>('/api/v1/admin/discord/briefing',
            channels ? { channels } : undefined).then(r => r.data),
    getLogs: (size = 50) =>
        api.get<ApiResponse<Record<string, unknown>[]>>(`/api/v1/admin/discord/logs?size=${size}`).then(r => r.data),
};

export const settingsApi = {
    systemMetrics: () =>
        api.get<ApiResponse<import('./types').SystemMetrics>>('/api/v1/settings/system-metrics').then(r => r.data),
    telegramLogs: (page = 0, size = 50) =>
        api.get<ApiResponse<import('./types').TelegramLogsResponse>>('/api/v1/settings/telegram/logs', { params: { page, size } }).then(r => r.data),
    telegramTest: () =>
        api.post<ApiResponse<{ success: boolean }>>('/api/v1/settings/telegram/test', {}).then(r => r.data),
    upbitStatus: () =>
        api.get<ApiResponse<import('./types').UpbitStatusResponse>>('/api/v1/settings/upbit/status').then(r => r.data),
    upbitOrderChance: (market = 'KRW-ETH') =>
        api.get<ApiResponse<Record<string, unknown>>>('/api/v1/settings/upbit/order-chance', { params: { market } }).then(r => r.data),
    upbitTestOrder: (market: string, side: string, amount: number) =>
        api.post<ApiResponse<Record<string, unknown>>>('/api/v1/settings/upbit/test-order', { market, side, amount }).then(r => r.data),
    upbitExchangeOrders: (market = 'KRW-ETH', state = 'done', limit = 10) =>
        api.get<ApiResponse<{ orders: Record<string, unknown>[]; count: number; error?: string }>>(
            '/api/v1/settings/upbit/exchange-orders', { params: { market, state, limit } }
        ).then(r => r.data),
    upbitTicker: (markets = 'KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE') =>
        api.get<ApiResponse<Record<string, unknown>[]>>('/api/v1/settings/upbit/ticker', { params: { markets } }).then(r => r.data),
    wsStatus: () =>
        api.get<ApiResponse<WsStatusResponse>>('/api/v1/settings/websocket/status').then(r => r.data),
    wsReconnect: () =>
        api.post<ApiResponse<{ success: boolean; message: string }>>('/api/v1/settings/websocket/reconnect', {}).then(r => r.data),
    serverLogs: (levels: string[] = ['ALL'], keyword = '', lines = 200) =>
        api.get<ApiResponse<{ entries: ServerLogEntry[]; total: number; filtered: number; returned: number }>>(
            '/api/v1/settings/server-logs', { params: { level: levels.join(','), keyword, lines } }
        ).then(r => r.data),
    dbStats: () =>
        api.get<ApiResponse<DbStats>>('/api/v1/settings/db/stats').then(r => r.data),
    dbReset: (target: 'BACKTEST' | 'PAPER_TRADING' | 'LIVE_TRADING', password: string) =>
        api.post<ApiResponse<{ target: string; deleted: Record<string, number>; total: number }>>(
            '/api/v1/settings/db/reset', { target, password }
        ).then(r => r.data),
};

export interface ServerLogEntry {
    timestamp: string;
    level: string;
    logger: string;
    message: string;
}

export interface DbStats {
    backtest:     Record<string, number>;
    paperTrading: Record<string, number>;
    liveTrading:  Record<string, number>;
}

export const schedulerApi = {
    getConfig: () =>
        api.get<ApiResponse<NightlySchedulerConfig>>('/api/v1/scheduler/nightly').then(r => r.data),
    updateConfig: (config: Partial<NightlySchedulerConfig>) =>
        api.put<ApiResponse<NightlySchedulerConfig>>('/api/v1/scheduler/nightly', config).then(r => r.data),
    triggerNow: () =>
        api.post<ApiResponse<Record<string, unknown>>>('/api/v1/scheduler/nightly/trigger').then(r => r.data),
    listJobs: () =>
        api.get<ApiResponse<BacktestJob[]>>('/api/v1/backtest/jobs').then(r => r.data),
    cancelJob: (jobId: number) =>
        api.post<ApiResponse<BacktestJob>>(`/api/v1/backtest/job/${jobId}/cancel`).then(r => r.data),
};
