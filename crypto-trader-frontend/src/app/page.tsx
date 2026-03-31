'use client';

import { useQuery } from '@tanstack/react-query';
import {
    useTradingStatus, useTradingSessions, useExchangeHealth,
    usePositions, useOrders, useStrategies, usePaperSessions,
} from '@/hooks';
import { accountApi, tradingApi, paperTradingApi, settingsApi } from '@/lib/api';
import type { SystemMetrics, PerformanceSummary, AccountSummary } from '@/lib/types';
import {
    Activity, AlertTriangle, CheckCircle, Cpu, Database,
    DollarSign, HardDrive, MemoryStick, Server, TrendingDown,
    TrendingUp, Zap,
} from 'lucide-react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip, ResponsiveContainer, PieChart, Pie, Cell,
} from 'recharts';

// ─── 포맷 헬퍼 ───────────────────────────────────────────────────────────────
const fmt = (n: number) => new Intl.NumberFormat('ko-KR').format(Math.round(n));
const fmtPct = (n: number) => `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;
const fmtTime = (iso: string) =>
    new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

const PIE_COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899'];

// ─── 신호등 색상 ──────────────────────────────────────────────────────────────
function gaugeColor(pct: number) {
    if (pct < 60) return { bar: 'bg-emerald-500', text: 'text-emerald-400', label: 'text-emerald-400', ring: 'border-emerald-500/40' };
    if (pct < 85) return { bar: 'bg-amber-400',   text: 'text-amber-400',   label: 'text-amber-400',   ring: 'border-amber-400/40' };
    return              { bar: 'bg-red-500',       text: 'text-red-400',     label: 'text-red-400',     ring: 'border-red-500/40' };
}

// ─── 퀵 서머리 카드 ───────────────────────────────────────────────────────────
function SummaryCard({
    title, value, sub, icon, colorClass = 'text-slate-100',
}: {
    title: string; value: string; sub?: string; icon: React.ReactNode; colorClass?: string;
}) {
    return (
        <div className="bg-slate-800 border border-slate-700 rounded-xl p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
                <p className="text-xs font-medium text-slate-400">{title}</p>
                <span className="text-slate-500">{icon}</span>
            </div>
            <p className={`text-2xl font-bold tracking-tight ${colorClass}`}>{value}</p>
            {sub && <p className="text-xs text-slate-500">{sub}</p>}
        </div>
    );
}

// ─── 리소스 게이지 바 ─────────────────────────────────────────────────────────
function ResourceGauge({ label, pct, detail, icon }: { label: string; pct: number; detail: string; icon: React.ReactNode }) {
    const c = gaugeColor(pct);
    return (
        <div className={`bg-slate-800/60 border ${c.ring} rounded-lg p-4 space-y-2`}>
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-slate-300">
                    <span className={c.text}>{icon}</span>
                    <span className="text-xs font-semibold">{label}</span>
                </div>
                <span className={`text-sm font-bold ${c.label}`}>
                    {pct < 0 ? 'N/A' : `${pct.toFixed(1)}%`}
                </span>
            </div>
            <div className="w-full bg-slate-700 rounded-full h-2">
                <div
                    className={`h-2 rounded-full transition-all duration-500 ${c.bar}`}
                    style={{ width: pct < 0 ? '0%' : `${Math.min(100, pct)}%` }}
                />
            </div>
            <p className="text-[11px] text-slate-500">{detail}</p>
        </div>
    );
}

// ─── 시스템 상태 배지 ─────────────────────────────────────────────────────────
function StatusBadge({ health, metrics }: { health: string | undefined; metrics: SystemMetrics | null }) {
    const isWarning =
        health === 'DEGRADED' || health === 'DOWN' ||
        (metrics && (metrics.cpuUsagePct > 85 || metrics.memUsagePct > 90 || metrics.diskUsagePct > 90));

    if (isWarning) {
        return (
            <div className="flex items-center gap-1.5 px-2.5 py-1 bg-amber-500/10 border border-amber-500/30 rounded-lg">
                <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />
                <span className="text-xs font-semibold text-amber-400">Warning</span>
            </div>
        );
    }
    return (
        <div className="flex items-center gap-1.5 px-2.5 py-1 bg-emerald-500/10 border border-emerald-500/30 rounded-lg">
            <CheckCircle className="w-3.5 h-3.5 text-emerald-400" />
            <span className="text-xs font-semibold text-emerald-400">Normal</span>
        </div>
    );
}

// ─── 메인 ────────────────────────────────────────────────────────────────────
export default function DashboardPage() {
    // ── 데이터 페칭 ──────────────────────────────────────────────────────────
    const { data: tradingStatus }    = useTradingStatus();
    const { data: liveSessions = [] } = useTradingSessions();
    const { data: paperSessions = [] } = usePaperSessions();
    const { data: health }           = useExchangeHealth();
    const { data: positions = [] }   = usePositions();
    const { data: ordersPage }       = useOrders(0, 8);
    const { data: strategies = [] }  = useStrategies();

    const { data: account } = useQuery<AccountSummary | null>({
        queryKey: ['account-summary'],
        queryFn: async () => {
            const res = await accountApi.summary();
            return (res.success && res.data) ? res.data : null;
        },
        refetchInterval: 30000,
    });

    const { data: livePerfRaw } = useQuery<PerformanceSummary | null>({
        queryKey: ['live-performance'],
        queryFn: async () => {
            const res = await tradingApi.getPerformance();
            return (res.success && res.data) ? res.data : null;
        },
        refetchInterval: 10000,
    });

    const { data: paperPerfRaw } = useQuery<PerformanceSummary | null>({
        queryKey: ['paper-performance'],
        queryFn: async () => {
            const res = await paperTradingApi.getPerformance();
            return (res.success && res.data) ? res.data : null;
        },
        refetchInterval: 10000,
    });

    const { data: metrics = null } = useQuery<SystemMetrics | null>({
        queryKey: ['system-metrics'],
        queryFn: async () => {
            const res = await settingsApi.systemMetrics();
            return (res.success && res.data) ? res.data : null;
        },
        refetchInterval: 10000,
    });

    // ── 계산 ─────────────────────────────────────────────────────────────────
    const runningLive  = liveSessions.filter(s => s.status === 'RUNNING').length;
    const runningPaper = paperSessions.filter(s => s.status === 'RUNNING').length;
    const activeCount  = runningLive + runningPaper;

    const totalPnl        = tradingStatus?.totalPnl ?? 0;
    const liveReturnPct   = livePerfRaw?.returnRatePct ?? 0;

    const totalAsset = account?.totalAssetKrw ?? 0;

    // 실전 세션별 수익률 바 차트
    const perfChartData = (livePerfRaw?.sessions ?? []).map(s => ({
        name: `${s.strategyType} / ${s.coinPair}`,
        returnPct: Number(s.returnRatePct.toFixed(2)),
        status: s.status,
    }));
    const CHART_BAR_WIDTH = 72; // 세션 1개당 픽셀 폭
    const chartScrollWidth = Math.max(500, perfChartData.length * CHART_BAR_WIDTH);

    // 자산 배분 파이
    const pieData = (() => {
        if (!account?.totalAssetKrw) return [];
        const data: { name: string; value: number }[] = [];
        if ((account.totalKrwBalance ?? 0) > 0)
            data.push({ name: 'KRW', value: account.totalKrwBalance! });
        (account.holdings ?? []).forEach(h => {
            if (h.evalValue > 0) data.push({ name: h.currency, value: h.evalValue });
        });
        return data;
    })();

    const recentOrders = ordersPage?.content ?? [];

    // ── 렌더 ─────────────────────────────────────────────────────────────────
    return (
        <div className="space-y-8 animate-in fade-in duration-500 max-w-[1600px]">

            {/* ━━━ 페이지 헤더 ━━━ */}
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-slate-100 tracking-tight">대시보드</h1>
                    <p className="text-sm text-slate-500 mt-0.5">실시간 운용 현황 · 자동 갱신</p>
                </div>
                <StatusBadge health={health?.status} metrics={metrics} />
            </div>

            {/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ① 상단: 퀵 서머리 카드 (4개)
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                {/* 운용 자산 */}
                <SummaryCard
                    title="운용 자산 총액"
                    value={totalAsset > 0 ? `${fmt(totalAsset)} KRW` : '-'}
                    sub={account?.totalUnrealizedPnlPct != null
                        ? `평가손익 ${fmtPct(account.totalUnrealizedPnlPct)}`
                        : '계좌 미연동'}
                    icon={<DollarSign className="w-4 h-4" />}
                    colorClass="text-slate-100"
                />

                {/* 금일 실적 */}
                <SummaryCard
                    title="실전 총 손익"
                    value={`${totalPnl >= 0 ? '+' : ''}${fmt(totalPnl)} KRW`}
                    sub={liveReturnPct !== 0
                        ? `수익률 ${fmtPct(liveReturnPct)}`
                        : '진행 중인 세션 없음'}
                    icon={totalPnl >= 0
                        ? <TrendingUp className="w-4 h-4" />
                        : <TrendingDown className="w-4 h-4" />}
                    colorClass={totalPnl >= 0 ? 'text-emerald-400' : 'text-red-400'}
                />

                {/* 활성 전략 수 */}
                <SummaryCard
                    title="활성 전략 수"
                    value={`${activeCount}개 구동 중`}
                    sub={`실전 ${runningLive} · 모의 ${runningPaper} · 전략 ${strategies.filter(s => s.isActive).length}종 활성`}
                    icon={<Activity className="w-4 h-4" />}
                    colorClass={activeCount > 0 ? 'text-indigo-400' : 'text-slate-400'}
                />

                {/* 시스템 상태 */}
                <SummaryCard
                    title="시스템 상태"
                    value={health?.status === 'UP' ? 'Normal' : health?.status === 'DEGRADED' ? 'Warning' : health?.status ?? '-'}
                    sub={health
                        ? `거래소 응답 ${health.latencyMs}ms · WS ${health.webSocketConnected ? '연결됨' : '끊김'}`
                        : '정보 수집 중'}
                    icon={<Zap className="w-4 h-4" />}
                    colorClass={
                        health?.status === 'UP' ? 'text-emerald-400' :
                        health?.status === 'DEGRADED' ? 'text-amber-400' : 'text-red-400'
                    }
                />
            </div>

            {/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ② 중앙: 성과 시각화 (차트)
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

                {/* 실전 세션별 수익률 바 차트 (2/3 너비, 좌우 스크롤) */}
                <div className="lg:col-span-2 bg-slate-800 border border-slate-700 rounded-xl p-5">
                    <div className="flex items-center gap-2 mb-5">
                        <TrendingUp className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">실전 세션별 수익률</h2>
                        {perfChartData.length > 0 && (
                            <span className="ml-auto text-xs text-slate-500">
                                {perfChartData.length}개 세션
                                {perfChartData.length > 6 && ' · 좌우 스크롤'}
                            </span>
                        )}
                    </div>

                    {perfChartData.length === 0 ? (
                        <div className="h-52 flex items-center justify-center text-slate-500 text-sm">
                            실전 세션이 없습니다.
                        </div>
                    ) : (
                        <div className="overflow-x-auto">
                            <div style={{ width: chartScrollWidth, minWidth: '100%' }}>
                                <BarChart
                                    width={chartScrollWidth}
                                    height={220}
                                    data={perfChartData}
                                    margin={{ top: 8, right: 16, left: 8, bottom: 36 }}
                                >
                                    <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                                    <XAxis
                                        dataKey="name"
                                        tick={{ fontSize: 10, fill: '#64748b' }}
                                        tickLine={false} axisLine={false}
                                        angle={-25} textAnchor="end" interval={0}
                                    />
                                    <YAxis
                                        tickFormatter={v => `${v}%`}
                                        tick={{ fontSize: 10, fill: '#64748b' }}
                                        tickLine={false} axisLine={false}
                                    />
                                    <Tooltip
                                        formatter={(v) => [`${(v as number) >= 0 ? '+' : ''}${v}%`, '수익률']}
                                        contentStyle={{
                                            background: '#1e293b', border: '1px solid #334155',
                                            borderRadius: 8, fontSize: 12,
                                        }}
                                    />
                                    <Bar
                                        dataKey="returnPct"
                                        name="수익률"
                                        radius={[4, 4, 0, 0]}
                                        maxBarSize={52}
                                    >
                                        {perfChartData.map((entry, i) => (
                                            <Cell
                                                key={i}
                                                fill={entry.returnPct >= 0 ? '#6366f1' : '#ef4444'}
                                            />
                                        ))}
                                    </Bar>
                                </BarChart>
                            </div>
                        </div>
                    )}
                </div>

                {/* 자산 배분 파이 차트 (1/3 너비) */}
                <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
                    <div className="flex items-center gap-2 mb-4">
                        <Database className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">자산 배분 현황</h2>
                    </div>

                    {pieData.length === 0 ? (
                        <div className="h-48 flex items-center justify-center text-slate-500 text-sm">
                            계좌 정보 없음
                        </div>
                    ) : (
                        <>
                            <ResponsiveContainer width="100%" height={180}>
                                <PieChart>
                                    <Pie
                                        data={pieData} cx="50%" cy="50%"
                                        innerRadius={48} outerRadius={72}
                                        paddingAngle={2} dataKey="value"
                                    >
                                        {pieData.map((_, i) => (
                                            <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                                        ))}
                                    </Pie>
                                    <Tooltip
                                        formatter={(v) => [`${fmt(v as number)} KRW`]}
                                        contentStyle={{
                                            background: '#1e293b', border: '1px solid #334155',
                                            borderRadius: 8, fontSize: 12,
                                        }}
                                    />
                                </PieChart>
                            </ResponsiveContainer>
                            <div className="space-y-1.5 mt-1">
                                {pieData.map((d, i) => (
                                    <div key={d.name} className="flex items-center justify-between text-xs">
                                        <span className="flex items-center gap-1.5">
                                            <span className="w-2 h-2 rounded-full" style={{ background: PIE_COLORS[i % PIE_COLORS.length] }} />
                                            <span className="text-slate-300">{d.name}</span>
                                        </span>
                                        <span className="text-slate-400">
                                            {((d.value / (account?.totalAssetKrw ?? 1)) * 100).toFixed(1)}%
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </>
                    )}
                </div>
            </div>

            {/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ③ 하단: 실시간 모니터링
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

                {/* 최근 체결 내역 */}
                <div className="bg-slate-800 border border-slate-700 rounded-xl overflow-hidden">
                    <div className="px-5 py-4 border-b border-slate-700 flex items-center gap-2">
                        <Activity className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">최근 체결 내역</h2>
                        <span className="ml-auto text-xs text-slate-500">{recentOrders.length}건</span>
                    </div>
                    {recentOrders.length === 0 ? (
                        <div className="p-8 text-center text-slate-500 text-sm">
                            체결 내역이 없습니다.
                        </div>
                    ) : (
                        <div className="divide-y divide-slate-700/50">
                            {recentOrders.map(order => {
                                const isBuy = order.side === 'BUY';
                                return (
                                    <div key={order.id} className="px-5 py-3 flex items-center justify-between hover:bg-slate-700/30 transition-colors">
                                        <div className="flex flex-col gap-0.5">
                                            <div className="flex items-center gap-2">
                                                <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${isBuy ? 'bg-indigo-500/20 text-indigo-400' : 'bg-rose-500/20 text-rose-400'}`}>
                                                    {isBuy ? '매수' : '매도'}
                                                </span>
                                                <span className="text-xs font-semibold text-slate-200">{order.coinPair}</span>
                                            </div>
                                            <span className="text-[11px] text-slate-500">
                                                {order.filledAt ? fmtTime(order.filledAt) : '-'}
                                            </span>
                                        </div>
                                        <div className="text-right">
                                            <p className="text-xs font-semibold text-slate-200">{fmt(order.price)} KRW</p>
                                            <p className="text-[11px] text-slate-500">{order.quantity.toFixed(6)}</p>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>

                {/* 진행 중인 포지션 */}
                <div className="bg-slate-800 border border-slate-700 rounded-xl overflow-hidden">
                    <div className="px-5 py-4 border-b border-slate-700 flex items-center gap-2">
                        <TrendingUp className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">진행 중인 포지션</h2>
                        <span className="ml-auto text-xs text-slate-500">{positions.length}건</span>
                    </div>
                    {positions.length === 0 ? (
                        <div className="p-8 text-center text-slate-500 text-sm">
                            보유 포지션이 없습니다.
                        </div>
                    ) : (
                        <div className="divide-y divide-slate-700/50">
                            {positions.slice(0, 6).map(pos => {
                                const pnlColor = pos.unrealizedPnl >= 0 ? 'text-emerald-400' : 'text-red-400';
                                return (
                                    <div key={pos.id} className="px-5 py-3 flex items-center justify-between hover:bg-slate-700/30 transition-colors">
                                        <div className="flex flex-col gap-0.5">
                                            <span className="text-xs font-semibold text-slate-200">{pos.coinPair}</span>
                                            <span className="text-[11px] text-slate-500">
                                                진입 {fmt(pos.entryPrice)} · {pos.size.toFixed(6)}
                                            </span>
                                        </div>
                                        <div className="text-right">
                                            <p className={`text-xs font-bold ${pnlColor}`}>
                                                {pos.unrealizedPnl >= 0 ? '+' : ''}{fmt(pos.unrealizedPnl)} KRW
                                            </p>
                                            <p className="text-[11px] text-slate-500">미실현 손익</p>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>

                {/* 서버 리소스 상태 */}
                <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
                    <div className="flex items-center gap-2 mb-4">
                        <Server className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">서버 리소스 상태</h2>
                    </div>

                    {!metrics ? (
                        <div className="h-48 flex items-center justify-center text-slate-500 text-sm">
                            메트릭 수집 중...
                        </div>
                    ) : (
                        <div className="space-y-3">
                            <ResourceGauge
                                label="CPU"
                                pct={metrics.cpuUsagePct}
                                detail={metrics.cpuUsagePct < 0 ? '측정 불가' : `${metrics.cpuUsagePct.toFixed(1)}% 사용 중`}
                                icon={<Cpu className="w-3.5 h-3.5" />}
                            />
                            <ResourceGauge
                                label="RAM (시스템)"
                                pct={metrics.memUsagePct}
                                detail={`${metrics.memUsedMb.toLocaleString()} MB / ${metrics.memTotalMb.toLocaleString()} MB`}
                                icon={<MemoryStick className="w-3.5 h-3.5" />}
                            />
                            <ResourceGauge
                                label="JVM Heap"
                                pct={metrics.heapUsagePct}
                                detail={`${metrics.heapUsedMb} MB / ${metrics.heapMaxMb} MB`}
                                icon={<Activity className="w-3.5 h-3.5" />}
                            />
                            <ResourceGauge
                                label="Disk"
                                pct={metrics.diskUsagePct}
                                detail={`${metrics.diskUsedGb} GB / ${metrics.diskTotalGb} GB`}
                                icon={<HardDrive className="w-3.5 h-3.5" />}
                            />
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
