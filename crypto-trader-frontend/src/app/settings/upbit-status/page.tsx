'use client';

import { useState, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { settingsApi, tradingApi, accountApi } from '@/lib/api';
import type { UpbitCandleSummary, WsTickerInfo } from '@/lib/types';
import {
    Loader2, CheckCircle2, XCircle, RefreshCw, Wifi,
    Database, Key, ShoppingCart, TestTube, ClipboardList,
    TrendingUp, Wallet, Radio, RotateCcw, Signal,
} from 'lucide-react';

const MARKETS = ['KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE'];

function Badge({ ok, label }: { ok: boolean; label: string }) {
    return (
        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${
            ok ? 'bg-green-500/15 text-green-400 border border-green-500/20'
               : 'bg-red-500/15 text-red-400 border border-red-500/20'
        }`}>
            {ok ? <CheckCircle2 className="w-3 h-3" /> : <XCircle className="w-3 h-3" />}
            {label}
        </span>
    );
}

function Section({ title, icon: Icon, badge, children }: {
    title: string;
    icon: React.ComponentType<{ className?: string }>;
    badge?: React.ReactNode;
    children: React.ReactNode;
}) {
    return (
        <div className="bg-slate-800 rounded-xl p-5 border border-slate-700/50 space-y-3">
            <div className="flex items-center gap-2">
                <Icon className="w-4 h-4 text-slate-400 shrink-0" />
                <span className="text-sm font-semibold text-slate-200">{title}</span>
                {badge}
            </div>
            {children}
        </div>
    );
}

export default function UpbitStatusPage() {
    const [refreshKey, setRefreshKey] = useState(0);

    // 주문 가능 정보
    const [chanceMarket, setChanceMarket] = useState('KRW-ETH');
    const [chanceResult, setChanceResult] = useState<Record<string, unknown> | null>(null);
    const [chanceLoading, setChanceLoading] = useState(false);
    const [chanceError, setChanceError] = useState('');

    // 주문 생성 테스트
    const [testMarket, setTestMarket] = useState('KRW-ETH');
    const [testSide, setTestSide] = useState('bid');
    const [testAmount, setTestAmount] = useState('5000');
    const [testResult, setTestResult] = useState<Record<string, unknown> | null>(null);
    const [testLoading, setTestLoading] = useState(false);
    const [testError, setTestError] = useState('');

    // 거래소 주문 이력
    const [ordersMarket, setOrdersMarket] = useState('KRW-ETH');
    const [ordersState, setOrdersState] = useState('done');
    const [ordersLimit, setOrdersLimit] = useState('10');
    const [ordersResult, setOrdersResult] = useState<Record<string, unknown>[] | null>(null);
    const [ordersLoading, setOrdersLoading] = useState(false);
    const [ordersError, setOrdersError] = useState('');

    // 현재가 조회
    const [tickerMarkets, setTickerMarkets] = useState('KRW-BTC,KRW-ETH,KRW-XRP,KRW-SOL,KRW-DOGE');
    const [tickerResult, setTickerResult] = useState<Record<string, unknown>[] | null>(null);
    const [tickerLoading, setTickerLoading] = useState(false);
    const [tickerError, setTickerError] = useState('');

    const { data, isLoading, error } = useQuery({
        queryKey: ['upbit-status', refreshKey],
        queryFn: () => settingsApi.upbitStatus(),
        refetchInterval: false,
    });
    const { data: healthData } = useQuery({
        queryKey: ['exchange-health', refreshKey],
        queryFn: () => tradingApi.getExchangeHealth(),
        refetchInterval: false,
    });
    const { data: accountData, isLoading: accountLoading } = useQuery({
        queryKey: ['account-summary', refreshKey],
        queryFn: () => accountApi.summary(),
        refetchInterval: false,
    });
    const { data: wsData, isLoading: wsLoading, refetch: refetchWs } = useQuery({
        queryKey: ['ws-status', refreshKey],
        queryFn: () => settingsApi.wsStatus(),
        refetchInterval: 5000,
    });

    const queryClient = useQueryClient();
    const [reconnectMsg, setReconnectMsg] = useState('');
    const reconnectMutation = useMutation({
        mutationFn: () => settingsApi.wsReconnect(),
        onSuccess: (res) => {
            setReconnectMsg(res.data?.message ?? '재연결 요청 완료');
            setTimeout(() => {
                setReconnectMsg('');
                queryClient.invalidateQueries({ queryKey: ['ws-status'] });
            }, 3000);
        },
    });

    const status = data?.data;
    const health = healthData?.data;
    const account = accountData?.data;
    const ws = wsData?.data;

    const refresh = useCallback(() => setRefreshKey(k => k + 1), []);

    async function fetchOrderChance() {
        setChanceLoading(true); setChanceError(''); setChanceResult(null);
        try {
            const res = await settingsApi.upbitOrderChance(chanceMarket);
            const d = res.data as Record<string, unknown>;
            if (d?.['error']) setChanceError(String(d['error']));
            else setChanceResult(d);
        } catch (e) { setChanceError(String(e)); }
        finally { setChanceLoading(false); }
    }

    async function runTestOrder() {
        setTestLoading(true); setTestError(''); setTestResult(null);
        try {
            const res = await settingsApi.upbitTestOrder(testMarket, testSide, Number(testAmount));
            const d = res.data as Record<string, unknown>;
            if (!d['success']) setTestError(String(d['error'] ?? '알 수 없는 오류'));
            else setTestResult(d);
        } catch (e) { setTestError(String(e)); }
        finally { setTestLoading(false); }
    }

    async function fetchOrders() {
        setOrdersLoading(true); setOrdersError(''); setOrdersResult(null);
        try {
            const res = await settingsApi.upbitExchangeOrders(ordersMarket, ordersState, Number(ordersLimit));
            const d = res.data as Record<string, unknown>;
            if (d['error']) setOrdersError(String(d['error']));
            else setOrdersResult((d['orders'] as Record<string, unknown>[]) ?? []);
        } catch (e) { setOrdersError(String(e)); }
        finally { setOrdersLoading(false); }
    }

    async function fetchTicker() {
        setTickerLoading(true); setTickerError(''); setTickerResult(null);
        try {
            const res = await settingsApi.upbitTicker(tickerMarkets);
            if (res.data) setTickerResult(res.data as Record<string, unknown>[]);
            else setTickerError('데이터 없음');
        } catch (e) { setTickerError(String(e)); }
        finally { setTickerLoading(false); }
    }

    const selectCls = "bg-slate-700 border border-slate-600 text-slate-200 text-sm rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-indigo-500";
    const inputCls  = "bg-slate-700 border border-slate-600 text-slate-200 text-sm rounded-lg px-2.5 py-1.5 w-32 focus:outline-none focus:ring-1 focus:ring-indigo-500";
    const btnCls    = "flex items-center gap-1.5 px-3.5 py-1.5 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 rounded-lg text-sm text-white font-medium transition-colors";
    const labelCls  = "text-xs text-slate-500 uppercase tracking-wide font-semibold";

    return (
        <div className="p-6 max-w-4xl space-y-6">
            {/* 헤더 */}
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-white">Upbit 연동 상태</h1>
                    <p className="text-sm text-slate-400 mt-1">API 키, 잔고, WebSocket, 캔들 캐시 상태를 확인합니다.</p>
                </div>
                <button onClick={refresh} disabled={isLoading}
                    className="flex items-center gap-2 px-3.5 py-2 bg-slate-700 hover:bg-slate-600 rounded-lg text-sm text-slate-200 transition-colors disabled:opacity-50">
                    {isLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
                    새로고침
                </button>
            </div>

            {error && (
                <div className="bg-red-900/30 border border-red-700/50 rounded-xl px-4 py-3 text-red-300 text-sm">
                    서버 연결 실패: {String(error)}
                </div>
            )}

            {/* ── 상태 카드 4개 ── */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
                {/* API 키 */}
                <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
                    <div className="flex items-center gap-2 mb-2">
                        <Key className="w-4 h-4 text-slate-400" />
                        <span className="text-xs text-slate-400 font-medium">API 키</span>
                    </div>
                    {isLoading ? <Loader2 className="w-4 h-4 animate-spin text-slate-500" /> : (
                        <Badge ok={!!status?.apiKeyConfigured} label={status?.apiKeyConfigured ? '설정됨' : '미설정'} />
                    )}
                </div>

                {/* 잔고 조회 */}
                <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
                    <div className="flex items-center gap-2 mb-2">
                        <Wifi className="w-4 h-4 text-slate-400" />
                        <span className="text-xs text-slate-400 font-medium">잔고 조회</span>
                    </div>
                    {isLoading ? <Loader2 className="w-4 h-4 animate-spin text-slate-500" /> : (
                        <>
                            <Badge ok={!!status?.accountQueryOk} label={status?.accountQueryOk ? '성공' : '실패'} />
                            {status?.accountQueryOk && status.totalAssetKrw !== undefined && (
                                <p className="text-xs text-slate-400 mt-1.5 font-mono">
                                    {Number(status.totalAssetKrw).toLocaleString()} KRW
                                </p>
                            )}
                        </>
                    )}
                </div>

                {/* WebSocket */}
                <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
                    <div className="flex items-center gap-2 mb-2">
                        <Radio className="w-4 h-4 text-slate-400" />
                        <span className="text-xs text-slate-400 font-medium">WebSocket</span>
                    </div>
                    {!health ? <Loader2 className="w-4 h-4 animate-spin text-slate-500" /> : (
                        <>
                            <Badge ok={health.webSocketConnected} label={health.webSocketConnected ? '연결됨' : '미연결'} />
                            <p className="text-xs text-slate-500 mt-1.5">지연 {health.latencyMs}ms · {health.status}</p>
                        </>
                    )}
                </div>

                {/* 캔들 캐시 */}
                <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
                    <div className="flex items-center gap-2 mb-2">
                        <Database className="w-4 h-4 text-slate-400" />
                        <span className="text-xs text-slate-400 font-medium">캔들 캐시</span>
                    </div>
                    {isLoading ? <Loader2 className="w-4 h-4 animate-spin text-slate-500" /> : (
                        <>
                            <Badge ok={!!status?.candleQueryOk} label={status?.candleQueryOk ? '정상' : '오류'} />
                            {status?.candleSummary && (
                                <p className="text-xs text-slate-500 mt-1.5">
                                    {(status.candleSummary as unknown[]).length}개 쌍 수집 중
                                </p>
                            )}
                        </>
                    )}
                </div>
            </div>

            {/* ── 진단 가이드 ── */}
            {status && (!status.apiKeyConfigured || !status.accountQueryOk ||
                (status.candleSummary && (status.candleSummary as unknown[]).length === 0)) && (
                <div className="bg-blue-900/20 border border-blue-700/50 rounded-xl p-4">
                    <p className="text-xs font-semibold text-blue-300 mb-2 uppercase tracking-wide">진단 가이드</p>
                    <ul className="text-xs text-blue-400 space-y-1 list-disc list-inside">
                        {!status.apiKeyConfigured && (
                            <li>docker-compose.prod.yml → backend environment에 UPBIT_ACCESS_KEY, UPBIT_SECRET_KEY 추가 후 재빌드</li>
                        )}
                        {status.apiKeyConfigured && !status.accountQueryOk && (
                            <li>API 키 등록됐지만 잔고 조회 실패 → 키 만료 또는 IP 허용 여부 확인</li>
                        )}
                        {status.apiKeyConfigured && (status.candleSummary as unknown[])?.length === 0 && (
                            <li>캔들 캐시 비어 있음 → 실전매매 세션이 RUNNING이어야 자동 동기화 (60초 간격)</li>
                        )}
                    </ul>
                </div>
            )}

            {/* ── 캔들 캐시 상세 ── */}
            {status?.candleQueryOk && status.candleSummary && (status.candleSummary as unknown[]).length > 0 && (
                <Section title="캔들 캐시 상세" icon={Database}>
                    <div className="overflow-x-auto">
                        <table className="w-full text-xs">
                            <thead>
                                <tr className="text-slate-400 border-b border-slate-700">
                                    <th className="text-left py-2">코인</th>
                                    <th className="text-left py-2">타임프레임</th>
                                    <th className="text-right py-2">캔들 수</th>
                                    <th className="text-right py-2">최신 시각</th>
                                </tr>
                            </thead>
                            <tbody>
                                {(status.candleSummary as UpbitCandleSummary[]).map(row => {
                                    const isRecent = row.to
                                        ? (Date.now() - new Date(row.to).getTime()) < 3 * 60 * 1000
                                        : false;
                                    return (
                                        <tr key={`${row.coinPair}-${row.timeframe}`} className="border-b border-slate-700/50">
                                            <td className="py-2 text-slate-200">{row.coinPair}</td>
                                            <td className="py-2 text-slate-400">{row.timeframe}</td>
                                            <td className={`py-2 text-right font-mono ${row.count >= 10 ? 'text-green-400' : 'text-red-400'}`}>{row.count}</td>
                                            <td className={`py-2 text-right font-mono ${isRecent ? 'text-green-400' : 'text-yellow-400'}`}>
                                                {row.to ? new Date(row.to).toLocaleTimeString('ko-KR') : '-'}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                </Section>
            )}

            {/* ── WebSocket 진단 ── */}
            <Section title="WebSocket 진단" icon={Signal}
                badge={
                    wsLoading ? <Loader2 className="w-3 h-3 animate-spin text-slate-500" /> :
                    ws?.connected ? <Badge ok={true} label="연결됨" /> :
                    <Badge ok={false} label="미연결" />
                }>
                {wsLoading ? (
                    <div className="flex items-center gap-2 text-slate-500 text-sm"><Loader2 className="w-4 h-4 animate-spin" />조회 중...</div>
                ) : !ws?.available ? (
                    <p className="text-xs text-red-400">{ws?.message ?? 'WebSocket 클라이언트 없음'}</p>
                ) : (
                    <div className="space-y-4">
                        {/* 상태 요약 */}
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">연결 상태</p>
                                <p className={`font-semibold ${ws.connected ? 'text-green-400' : 'text-red-400'}`}>
                                    {ws.connected ? '연결됨' : '미연결'}
                                </p>
                            </div>
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">구독 코인</p>
                                <p className="text-slate-200 font-mono">
                                    {ws.subscribedCoins.length > 0 ? ws.subscribedCoins.map(c => c.replace('KRW-', '')).join(', ') : '없음'}
                                </p>
                            </div>
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">마지막 Pong</p>
                                <p className={`font-mono ${ws.lastPongSecondsAgo < 0 ? 'text-slate-500' : ws.lastPongSecondsAgo < 300 ? 'text-green-400' : 'text-red-400'}`}>
                                    {ws.lastPongSecondsAgo < 0 ? '-' : `${ws.lastPongSecondsAgo}초 전`}
                                </p>
                            </div>
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">재연결 횟수</p>
                                <p className={`font-mono ${ws.reconnectCount > 0 ? 'text-yellow-400' : 'text-slate-200'}`}>
                                    {ws.reconnectCount}회
                                </p>
                            </div>
                        </div>

                        {/* 마지막 수신 ticker */}
                        {Object.keys(ws.lastTickers).length > 0 ? (
                            <div>
                                <p className="text-xs text-slate-500 uppercase tracking-wide font-semibold mb-2">마지막 수신 Ticker</p>
                                <div className="overflow-x-auto">
                                    <table className="w-full text-xs">
                                        <thead>
                                            <tr className="text-slate-400 border-b border-slate-700">
                                                <th className="text-left py-2">코인</th>
                                                <th className="text-right py-2">현재가</th>
                                                <th className="text-right py-2">등락률</th>
                                                <th className="text-right py-2">수신 시각</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {Object.entries(ws.lastTickers).map(([code, t]: [string, WsTickerInfo]) => {
                                                const chg = Number(t.signedChangeRate);
                                                const isStale = t.receivedSecondsAgo > 60;
                                                return (
                                                    <tr key={code} className="border-b border-slate-700/50">
                                                        <td className="py-2 font-semibold text-slate-200">{code}</td>
                                                        <td className="py-2 text-right font-mono text-white">{Number(t.tradePrice).toLocaleString()}</td>
                                                        <td className={`py-2 text-right font-mono font-semibold ${chg > 0 ? 'text-green-400' : chg < 0 ? 'text-red-400' : 'text-slate-400'}`}>
                                                            {chg > 0 ? '+' : ''}{chg}%
                                                        </td>
                                                        <td className={`py-2 text-right font-mono ${isStale ? 'text-red-400' : 'text-green-400'}`}>
                                                            {t.receivedSecondsAgo >= 0 ? `${t.receivedSecondsAgo}초 전` : '-'}
                                                        </td>
                                                    </tr>
                                                );
                                            })}
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        ) : (
                            <p className="text-xs text-slate-500">
                                {ws.connected ? '아직 수신된 ticker 없음 (잠시 대기)' : '미연결 상태 — ticker 수신 불가'}
                            </p>
                        )}

                        {/* 재연결 버튼 */}
                        <div className="flex items-center gap-3 pt-1">
                            <button
                                onClick={() => reconnectMutation.mutate()}
                                disabled={reconnectMutation.isPending}
                                className="flex items-center gap-1.5 px-3.5 py-1.5 bg-amber-600 hover:bg-amber-700 disabled:opacity-50 rounded-lg text-sm text-white font-medium transition-colors"
                            >
                                {reconnectMutation.isPending
                                    ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                    : <RotateCcw className="w-3.5 h-3.5" />}
                                강제 재연결
                            </button>
                            <button
                                onClick={() => refetchWs()}
                                className="flex items-center gap-1.5 px-3 py-1.5 bg-slate-700 hover:bg-slate-600 rounded-lg text-sm text-slate-300 transition-colors"
                            >
                                <RefreshCw className="w-3.5 h-3.5" />
                                새로고침
                            </button>
                            {reconnectMsg && (
                                <p className="text-xs text-amber-400">{reconnectMsg}</p>
                            )}
                        </div>
                    </div>
                )}
            </Section>

            {/* ── 잔고 상세 ── */}
            <Section title="잔고 상세" icon={Wallet}
                badge={account?.apiKeyConfigured === false
                    ? <Badge ok={false} label="API 키 없음" />
                    : account?.error
                    ? <Badge ok={false} label="조회 실패" />
                    : account?.totalAssetKrw !== undefined
                    ? <Badge ok={true} label="정상" />
                    : undefined}>
                {accountLoading ? (
                    <div className="flex items-center gap-2 text-slate-500 text-sm"><Loader2 className="w-4 h-4 animate-spin" /> 조회 중...</div>
                ) : !account || account.apiKeyConfigured === false ? (
                    <p className="text-xs text-slate-500">API 키 설정 후 조회 가능합니다.</p>
                ) : account.error ? (
                    <p className="text-xs text-red-400 font-mono break-all">{String(account.error)}</p>
                ) : (
                    <>
                        {/* 총 자산 요약 */}
                        <div className="grid grid-cols-3 gap-3 text-xs">
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">총 자산</p>
                                <p className="text-white font-mono font-semibold">{Number(account.totalAssetKrw).toLocaleString()} KRW</p>
                            </div>
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">사용 가능 KRW</p>
                                <p className="text-white font-mono">{Number(account.availableKrw).toLocaleString()} KRW</p>
                            </div>
                            <div className="bg-slate-700/50 rounded-lg p-3">
                                <p className="text-slate-400 mb-1">미실현 손익</p>
                                <p className={`font-mono font-semibold ${
                                    Number(account.totalUnrealizedPnl) > 0 ? 'text-green-400'
                                    : Number(account.totalUnrealizedPnl) < 0 ? 'text-red-400'
                                    : 'text-slate-400'
                                }`}>
                                    {Number(account.totalUnrealizedPnl) > 0 ? '+' : ''}
                                    {Number(account.totalUnrealizedPnl).toLocaleString()} KRW
                                    <span className="text-xs font-normal ml-1">({account.totalUnrealizedPnlPct}%)</span>
                                </p>
                            </div>
                        </div>

                        {/* 보유 코인 */}
                        {account.holdings && (account.holdings as unknown[]).length > 0 && (
                            <div className="overflow-x-auto mt-2">
                                <table className="w-full text-xs">
                                    <thead>
                                        <tr className="text-slate-400 border-b border-slate-700">
                                            <th className="text-left py-2">코인</th>
                                            <th className="text-right py-2">수량</th>
                                            <th className="text-right py-2">평균 매수가</th>
                                            <th className="text-right py-2">현재가</th>
                                            <th className="text-right py-2">평가금액</th>
                                            <th className="text-right py-2">수익률</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {(account.holdings as Record<string, unknown>[]).map((h) => {
                                            const pnlPct = Number(h['unrealizedPnlPct']);
                                            return (
                                                <tr key={String(h['currency'])} className="border-b border-slate-700/50">
                                                    <td className="py-2 font-semibold text-slate-200">{String(h['currency'])}</td>
                                                    <td className="py-2 text-right font-mono text-slate-300">{Number(h['totalQuantity']).toFixed(6)}</td>
                                                    <td className="py-2 text-right font-mono text-slate-400">{Number(h['avgBuyPrice']).toLocaleString()}</td>
                                                    <td className="py-2 text-right font-mono text-slate-300">{Number(h['currentPrice']).toLocaleString()}</td>
                                                    <td className="py-2 text-right font-mono text-slate-200">{Number(h['evalValue']).toLocaleString()}</td>
                                                    <td className={`py-2 text-right font-mono font-semibold ${pnlPct > 0 ? 'text-green-400' : pnlPct < 0 ? 'text-red-400' : 'text-slate-400'}`}>
                                                        {pnlPct > 0 ? '+' : ''}{pnlPct}%
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </>
                )}
            </Section>

            {/* ──────────── API 테스트 섹션 ──────────── */}
            <h2 className="text-base font-semibold text-slate-200 border-b border-slate-700 pb-2 pt-2">Upbit API 테스트</h2>

            {/* 현재가 조회 */}
            <Section title="현재가 조회" icon={TrendingUp}
                badge={<span className="text-xs text-slate-500 font-mono">GET /v1/ticker</span>}>
                <div className="flex flex-wrap items-center gap-2">
                    <input
                        value={tickerMarkets}
                        onChange={e => setTickerMarkets(e.target.value)}
                        placeholder="KRW-BTC,KRW-ETH,..."
                        className="bg-slate-700 border border-slate-600 text-slate-200 text-sm rounded-lg px-2.5 py-1.5 flex-1 min-w-[220px] focus:outline-none focus:ring-1 focus:ring-indigo-500"
                    />
                    <button onClick={fetchTicker} disabled={tickerLoading} className={btnCls}>
                        {tickerLoading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                        조회
                    </button>
                </div>
                <p className="text-xs text-slate-500">인증 불필요 공개 API. 쉼표로 여러 코인 입력 가능.</p>
                {tickerError && <p className="text-xs text-red-400 font-mono break-all">{tickerError}</p>}
                {tickerResult && tickerResult.length > 0 && (
                    <div className="overflow-x-auto">
                        <table className="w-full text-xs">
                            <thead>
                                <tr className="text-slate-400 border-b border-slate-700">
                                    <th className="text-left py-2">마켓</th>
                                    <th className="text-right py-2">현재가</th>
                                    <th className="text-right py-2">전일 대비</th>
                                    <th className="text-right py-2">변화율</th>
                                    <th className="text-right py-2">거래대금(24h)</th>
                                </tr>
                            </thead>
                            <tbody>
                                {tickerResult.map(t => {
                                    const chg = Number(t['signed_change_rate']) * 100;
                                    const isUp = chg > 0;
                                    return (
                                        <tr key={String(t['market'])} className="border-b border-slate-700/50">
                                            <td className="py-2 font-semibold text-slate-200">{String(t['market'])}</td>
                                            <td className="py-2 text-right font-mono text-white">{Number(t['trade_price']).toLocaleString()}</td>
                                            <td className={`py-2 text-right font-mono ${isUp ? 'text-green-400' : chg < 0 ? 'text-red-400' : 'text-slate-400'}`}>
                                                {isUp ? '+' : ''}{Number(t['signed_change_price']).toLocaleString()}
                                            </td>
                                            <td className={`py-2 text-right font-mono font-semibold ${isUp ? 'text-green-400' : chg < 0 ? 'text-red-400' : 'text-slate-400'}`}>
                                                {isUp ? '+' : ''}{chg.toFixed(2)}%
                                            </td>
                                            <td className="py-2 text-right font-mono text-slate-400">
                                                {(Number(t['acc_trade_price_24h']) / 1e8).toFixed(0)}억
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </Section>

            {/* 주문 가능 정보 */}
            <Section title="주문 가능 정보 조회" icon={ShoppingCart}
                badge={<span className="text-xs text-slate-500 font-mono">GET /v1/orders/chance</span>}>
                <div className="flex items-center gap-2">
                    <select value={chanceMarket} onChange={e => setChanceMarket(e.target.value)} className={selectCls}>
                        {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <button onClick={fetchOrderChance} disabled={chanceLoading} className={btnCls}>
                        {chanceLoading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                        조회
                    </button>
                </div>
                {chanceError && <p className="text-xs text-red-400 font-mono break-all">{chanceError}</p>}
                {chanceResult && (
                    <div className="grid grid-cols-3 gap-2 text-xs">
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-slate-400 mb-1">매수 수수료</p>
                            <p className="text-white font-mono">{chanceResult['bid_fee'] ? `${(Number(chanceResult['bid_fee']) * 100).toFixed(3)}%` : '-'}</p>
                        </div>
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-slate-400 mb-1">매도 수수료</p>
                            <p className="text-white font-mono">{chanceResult['ask_fee'] ? `${(Number(chanceResult['ask_fee']) * 100).toFixed(3)}%` : '-'}</p>
                        </div>
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-slate-400 mb-1">주문 가능 KRW</p>
                            <p className="text-white font-mono">
                                {Number((chanceResult['bid_account'] as Record<string, unknown>)?.['balance'] ?? 0).toLocaleString()}
                                {Number((chanceResult['bid_account'] as Record<string, unknown>)?.['locked'] ?? 0) > 0 && (
                                    <span className="text-yellow-400 ml-1 text-xs">
                                        (잠금 {Number((chanceResult['bid_account'] as Record<string, unknown>)['locked']).toLocaleString()})
                                    </span>
                                )}
                            </p>
                        </div>
                    </div>
                )}
            </Section>

            {/* 주문 생성 테스트 */}
            <Section title="주문 생성 테스트" icon={TestTube}
                badge={<span className="text-xs text-slate-500 font-mono">POST /v1/orders/test — 실거래 없음</span>}>
                <div className="flex flex-wrap items-center gap-2">
                    <select value={testMarket} onChange={e => setTestMarket(e.target.value)} className={selectCls}>
                        {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <select value={testSide} onChange={e => setTestSide(e.target.value)} className={selectCls}>
                        <option value="bid">매수 (bid)</option>
                        <option value="ask">매도 (ask)</option>
                    </select>
                    <input type="number" value={testAmount} onChange={e => setTestAmount(e.target.value)}
                        placeholder="금액 (KRW)" className={inputCls} />
                    <button onClick={runTestOrder} disabled={testLoading} className={btnCls}>
                        {testLoading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                        테스트 실행
                    </button>
                </div>
                <p className="text-xs text-slate-500">시장가 매수 기준 (ord_type: price). API 권한·형식 검증 전용이며 실제 체결되지 않습니다.</p>
                {testError && <p className="text-xs text-red-400 font-mono break-all">{testError}</p>}
                {testResult && (
                    <div className="bg-green-900/20 border border-green-700/50 rounded-lg p-3 text-xs">
                        <p className="text-green-300 font-semibold mb-2">테스트 주문 성공</p>
                        <div className="grid grid-cols-3 gap-x-6 gap-y-1.5 font-mono">
                            {[
                                ['UUID', String(testResult['uuid'] ?? '-')],
                                ['마켓', String(testResult['market'] ?? '-')],
                                ['방향', String(testResult['side'] ?? '-')],
                                ['타입', String(testResult['ordType'] ?? '-')],
                                ['금액', testResult['price'] ? `${Number(testResult['price']).toLocaleString()} KRW` : '-'],
                                ['상태', String(testResult['state'] ?? '-')],
                            ].map(([k, v]) => (
                                <div key={k}>
                                    <span className="text-slate-500">{k}: </span>
                                    <span className="text-slate-200">{v}</span>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
            </Section>

            {/* 거래소 최근 주문 이력 */}
            <Section title="거래소 최근 주문 이력" icon={ClipboardList}
                badge={<span className="text-xs text-slate-500 font-mono">GET /v1/orders — 거래소 직접 조회</span>}>
                <div className="flex flex-wrap items-center gap-2">
                    <select value={ordersMarket} onChange={e => setOrdersMarket(e.target.value)} className={selectCls}>
                        {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                    <select value={ordersState} onChange={e => setOrdersState(e.target.value)} className={selectCls}>
                        <option value="done">체결완료 (done)</option>
                        <option value="cancel">취소됨 (cancel)</option>
                        <option value="wait">대기중 (wait)</option>
                    </select>
                    <select value={ordersLimit} onChange={e => setOrdersLimit(e.target.value)} className={selectCls}>
                        {[5, 10, 20, 50].map(n => <option key={n} value={n}>{n}건</option>)}
                    </select>
                    <button onClick={fetchOrders} disabled={ordersLoading} className={btnCls}>
                        {ordersLoading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                        조회
                    </button>
                </div>
                {ordersError && <p className="text-xs text-red-400 font-mono break-all">{ordersError}</p>}
                {ordersResult && (
                    ordersResult.length === 0 ? (
                        <p className="text-xs text-slate-500">주문 이력 없음</p>
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="w-full text-xs">
                                <thead>
                                    <tr className="text-slate-400 border-b border-slate-700">
                                        <th className="text-left py-2">시각</th>
                                        <th className="text-left py-2">마켓</th>
                                        <th className="text-left py-2">방향</th>
                                        <th className="text-left py-2">타입</th>
                                        <th className="text-right py-2">금액/수량</th>
                                        <th className="text-left py-2">상태</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {ordersResult.map((o, i) => {
                                        const side = String(o['side'] ?? '');
                                        const ordType = String(o['ord_type'] ?? '');
                                        return (
                                            <tr key={i} className="border-b border-slate-700/50">
                                                <td className="py-2 text-slate-500 whitespace-nowrap text-xs">
                                                    {o['created_at'] ? new Date(String(o['created_at'])).toLocaleString('ko-KR') : '-'}
                                                </td>
                                                <td className="py-2 text-slate-200">{String(o['market'] ?? '-')}</td>
                                                <td className={`py-2 font-semibold ${side === 'bid' ? 'text-blue-400' : 'text-red-400'}`}>
                                                    {side === 'bid' ? '매수' : side === 'ask' ? '매도' : side}
                                                </td>
                                                <td className="py-2 text-slate-400">{ordType}</td>
                                                <td className="py-2 text-right font-mono text-slate-300">
                                                    {ordType === 'price'
                                                        ? `${Number(o['price'] ?? 0).toLocaleString()} KRW`
                                                        : String(o['volume'] ?? '-')}
                                                </td>
                                                <td className="py-2">
                                                    <span className={`px-2 py-0.5 rounded-full text-xs ${
                                                        o['state'] === 'done'   ? 'bg-green-500/15 text-green-400' :
                                                        o['state'] === 'cancel' ? 'bg-slate-600/50 text-slate-400' :
                                                                                  'bg-yellow-500/15 text-yellow-400'
                                                    }`}>{String(o['state'] ?? '-')}</span>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                    )
                )}
            </Section>
        </div>
    );
}
