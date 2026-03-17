'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { paperTradingApi, logApi } from '@/lib/api';
import { PaperPosition, PaperTradingBalance } from '@/lib/types';
import Link from 'next/link';
import { useRouter, useParams } from 'next/navigation';
import { Loader2, Square, TrendingUp, TrendingDown, Clock, Briefcase, ArrowLeft, Activity, ShoppingCart, DollarSign, Receipt, Trophy, ChevronDown, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';
import {
    ComposedChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';

function ChartTooltip({ active, payload, label }: any) {
    if (!active || !payload?.length) return null;
    const d = payload[0]?.payload;
    const order = d?.buyOrder || d?.sellOrder;
    const isBuy = !!d?.buyOrder;
    return (
        <div className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg p-3 text-xs min-w-[180px]">
            <div className="text-slate-400 dark:text-slate-500 mb-1">{label ? format(new Date(label), 'yyyy-MM-dd HH:mm') : ''}</div>
            <div className="font-semibold text-slate-700 dark:text-slate-200">종가: {Number(d?.close).toLocaleString()} KRW</div>
            {order && (
                <div className={cn('mt-2 pt-2 border-t border-slate-100 space-y-1', isBuy ? 'text-emerald-700' : 'text-rose-600')}>
                    <div className="font-bold">{isBuy ? '▲ 매수 체결' : '▼ 매도 체결'}</div>
                    <div>가격: <span className="font-semibold">{Number(order.price).toLocaleString()} KRW</span></div>
                    <div>수량: <span className="font-semibold">{Number(order.quantity).toFixed(6)}</span></div>
                    <div>금액: <span className="font-semibold">{(Number(order.price) * Number(order.quantity)).toLocaleString()} KRW</span></div>
                    {order.signalReason && (
                        <div className="text-slate-500 pt-1 border-t border-slate-100 leading-relaxed">{order.signalReason}</div>
                    )}
                </div>
            )}
        </div>
    );
}

const SIGNAL_STYLE: Record<string, string> = {
    BUY:  'bg-emerald-100 text-emerald-700',
    SELL: 'bg-rose-100 text-rose-700',
    HOLD: 'bg-slate-100 text-slate-500',
};

function StrategyLogAccordion({ logs }: { logs: any[] | undefined }) {
    const [openGroups, setOpenGroups] = useState<Set<string>>(new Set());

    if (!logs || logs.length === 0) {
        return (
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                    <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">전략 분석 로그</h2>
                </div>
                <div className="px-6 py-8 text-center text-slate-400 dark:text-slate-500 text-sm">전략 로그가 없습니다.</div>
            </div>
        );
    }

    // 전략명 기준으로 그룹화 (순서 유지)
    const groups: Record<string, any[]> = {};
    for (const log of logs) {
        const key = log.strategyName ?? '알 수 없음';
        if (!groups[key]) groups[key] = [];
        groups[key].push(log);
    }

    const toggle = (key: string) => {
        setOpenGroups(prev => {
            const next = new Set(prev);
            next.has(key) ? next.delete(key) : next.add(key);
            return next;
        });
    };

    return (
        <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex items-center justify-between">
                <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">전략 분석 로그</h2>
                <span className="text-xs text-slate-400">{Object.keys(groups).length}개 전략</span>
            </div>
            <div className="divide-y divide-slate-100 dark:divide-slate-800">
                {Object.entries(groups).map(([strategyName, groupLogs]) => {
                    const isOpen = openGroups.has(strategyName);
                    const latest = groupLogs[0];
                    const signalCounts = groupLogs.reduce((acc: Record<string, number>, l: any) => {
                        acc[l.signal] = (acc[l.signal] ?? 0) + 1;
                        return acc;
                    }, {});

                    return (
                        <div key={strategyName}>
                            {/* 그룹 헤더 (클릭하여 펼치기) */}
                            <button
                                onClick={() => toggle(strategyName)}
                                className="w-full flex items-center gap-3 px-6 py-4 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
                            >
                                <span className="shrink-0 text-slate-400 dark:text-slate-500">
                                    {isOpen ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                                </span>
                                <span className="font-semibold text-sm text-slate-700 dark:text-slate-200 flex-1 text-left">
                                    {strategyName}
                                </span>
                                <span className="flex items-center gap-1.5 text-xs">
                                    {Object.entries(signalCounts).map(([sig, cnt]) => (
                                        <span key={sig} className={cn('px-2 py-0.5 rounded-full font-bold', SIGNAL_STYLE[sig] ?? 'bg-slate-100 text-slate-500')}>
                                            {sig} {cnt}
                                        </span>
                                    ))}
                                </span>
                                <span className="text-xs text-slate-400 dark:text-slate-500 shrink-0 ml-2">
                                    {latest?.createdAt ? format(new Date(latest.createdAt), 'MM/dd HH:mm') : ''}
                                </span>
                                <span className="text-xs text-slate-400 dark:text-slate-500 shrink-0 ml-3 tabular-nums">
                                    총 {groupLogs.length}건
                                </span>
                            </button>

                            {/* 펼쳐진 로그 목록 */}
                            {isOpen && (
                                <div className="border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/30 overflow-x-auto">
                                    <table className="w-full text-left text-xs text-slate-600 dark:text-slate-300">
                                        <thead className="text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-700 uppercase tracking-wide">
                                            <tr>
                                                <th className="px-6 py-2">시간</th>
                                                <th className="px-6 py-2">신호</th>
                                                <th className="px-6 py-2">마켓 상태</th>
                                                <th className="px-6 py-2">판단 이유</th>
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-slate-100 dark:divide-slate-700/50">
                                            {groupLogs.map((log: any) => (
                                                <tr key={log.id} className="hover:bg-slate-100/50 dark:hover:bg-slate-700/30 transition-colors">
                                                    <td className="px-6 py-2.5 whitespace-nowrap text-slate-400">
                                                        {log.createdAt ? new Date(log.createdAt).toLocaleString('ko-KR') : '-'}
                                                    </td>
                                                    <td className="px-6 py-2.5">
                                                        <span className={cn('px-2 py-0.5 rounded-full font-bold', SIGNAL_STYLE[log.signal] ?? 'bg-slate-100 text-slate-500')}>
                                                            {log.signal}
                                                        </span>
                                                    </td>
                                                    <td className="px-6 py-2.5 text-slate-500 dark:text-slate-400">{log.marketRegime ?? '-'}</td>
                                                    <td className="px-6 py-2.5 text-slate-500 dark:text-slate-400 max-w-sm truncate" title={log.reason}>
                                                        {log.reason ?? '-'}
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

export default function SessionDetailPage() {
    const { sessionId } = useParams<{ sessionId: string }>();
    const router = useRouter();
    const queryClient = useQueryClient();

    const { data: balanceRes, isLoading: balanceLoading } = useQuery({
        queryKey: ['paper-trading', 'session', sessionId],
        queryFn: () => paperTradingApi.getSession(sessionId),
        refetchInterval: 5000,
    });

    const { data: positionsRes } = useQuery({
        queryKey: ['paper-trading', 'session', sessionId, 'positions'],
        queryFn: () => paperTradingApi.positions(sessionId, 'ALL'),
        refetchInterval: 5000,
    });

    const { data: ordersRes } = useQuery({
        queryKey: ['paper-trading', 'session', sessionId, 'orders'],
        queryFn: () => paperTradingApi.orders(sessionId, 0),
        refetchInterval: 10000,
    });

    const { data: chartRes } = useQuery({
        queryKey: ['paper-trading', 'session', sessionId, 'chart'],
        queryFn: () => paperTradingApi.chart(sessionId),
        refetchInterval: 60000,
    });

    const { data: strategyLogsRes } = useQuery({
        queryKey: ['logs', 'strategy', 'paper', sessionId],
        queryFn: () => logApi.strategyLogs(0, 20, 'PAPER', Number(sessionId)),
        refetchInterval: 10000,
    });

    const stopMutation = useMutation({
        mutationFn: () => paperTradingApi.stop(sessionId),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['paper-trading'] });
            router.push('/paper-trading');
        },
    });

    const balance = balanceRes?.data as unknown as PaperTradingBalance & { id: number; timeframe?: string };
    const allPositions = (positionsRes?.data as unknown as (PaperPosition & { status?: string; realizedPnl?: number; closedAt?: string; entryPrice?: number })[]) || [];
    const positions = allPositions.filter(p => !p.status || p.status === 'OPEN');
    const closedPositions = allPositions.filter(p => p.status === 'CLOSED');
    const orders = (ordersRes?.data as any);
    const isRunning = balance?.status === 'RUNNING';

    const chartCandles = (chartRes?.data as any)?.candles as any[] | undefined;
    const chartOrders = (chartRes?.data as any)?.orders as any[] | undefined;

    // 매매 요약 통계 (chartOrders = 전체 체결 내역)
    const tradeSummary = (() => {
        if (!chartOrders || chartOrders.length === 0) return null;
        const buys = chartOrders.filter((o: any) => o.side === 'BUY');
        const sells = chartOrders.filter((o: any) => o.side === 'SELL');
        const totalFee = chartOrders.reduce((sum: number, o: any) => sum + Number(o.fee ?? 0), 0);
        const totalRealizedPnl = sells.reduce((sum: number, o: any) => sum + Number(o.realizedPnl ?? 0), 0);
        const winCount = sells.filter((o: any) => Number(o.realizedPnl ?? 0) > 0).length;
        const winRate = sells.length > 0 ? (winCount / sells.length) * 100 : 0;
        return { buyCount: buys.length, sellCount: sells.length, totalFee, totalRealizedPnl, winCount, winRate };
    })();

    // Build chart data: merge order info into nearest candle point
    const toMs = (t: any) => t ? (typeof t === 'number' ? t : new Date(t).getTime()) : null;
    const chartData = (() => {
        const candles = chartCandles?.map((c: any) => ({
            time: c.time,
            close: Number(c.close),
            buyOrder: null as any,
            sellOrder: null as any,
        })) ?? [];
        if (!chartOrders || candles.length === 0) return candles;
        for (const o of chartOrders) {
            const ms = toMs(o.filledAt);
            if (!ms) continue;
            // find nearest candle index
            let best = 0, bestDiff = Infinity;
            for (let i = 0; i < candles.length; i++) {
                const d = Math.abs(candles[i].time - ms);
                if (d < bestDiff) { bestDiff = d; best = i; }
            }
            if (o.side === 'BUY') candles[best].buyOrder = o;
            else candles[best].sellOrder = o;
        }
        return candles;
    })();

    if (balanceLoading) {
        return (
            <div className="flex flex-col items-center justify-center p-20 text-slate-500 gap-4">
                <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
                <p>세션 정보 로딩 중...</p>
            </div>
        );
    }

    if (!balance) {
        return (
            <div className="p-8 text-center">
                <p className="text-slate-500">세션을 찾을 수 없습니다.</p>
                <Link href="/paper-trading" className="text-indigo-600 hover:underline text-sm mt-2 inline-block">
                    목록으로 돌아가기
                </Link>
            </div>
        );
    }

    const returnPct = Number(balance.totalReturnPct);
    const isPositive = returnPct >= 0;

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            {/* Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <Link
                        href="/paper-trading"
                        className="p-2 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
                    >
                        <ArrowLeft className="w-5 h-5" />
                    </Link>
                    <div>
                        <div className="flex items-center gap-2">
                            <Activity className="w-5 h-5 text-indigo-500" />
                            <h1 className="text-xl font-bold text-slate-800 dark:text-slate-100">
                                {balance.strategyName} · {balance.coinPair}
                            </h1>
                            <span className={cn(
                                'px-2 py-0.5 text-xs font-bold rounded-full border',
                                isRunning
                                    ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                    : 'bg-slate-100 text-slate-500 border-slate-200'
                            )}>
                                {isRunning ? '● 실행 중' : '○ 종료'}
                            </span>
                        </div>
                        <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 ml-7">
                            세션 #{sessionId} · {(balance as any).timeframe || '-'} ·{' '}
                            {balance.startedAt ? format(new Date(balance.startedAt), 'yyyy.MM.dd HH:mm') + ' 시작' : ''}
                        </p>
                    </div>
                </div>
                {isRunning && (
                    <button
                        onClick={() => {
                            if (confirm('이 세션을 중단하시겠습니까? 보유 포지션은 현재가로 청산됩니다.')) {
                                stopMutation.mutate();
                            }
                        }}
                        disabled={stopMutation.isPending}
                        className="flex items-center gap-2 px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white text-sm font-semibold rounded-xl shadow-sm transition-colors disabled:opacity-50"
                    >
                        {stopMutation.isPending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Square className="w-4 h-4" fill="currentColor" />}
                        세션 중단
                    </button>
                )}
            </div>

            {/* Balance cards */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-slate-100 dark:divide-slate-800">
                    <div className="p-6 md:p-8">
                        <div className="text-sm font-medium text-slate-500 dark:text-slate-400 mb-1">총 평가 자산</div>
                        <div className="text-3xl md:text-4xl font-extrabold text-slate-800 dark:text-slate-100 tracking-tight">
                            {Number(balance.totalAssetKrw).toLocaleString()}
                            <span className="text-lg md:text-2xl text-slate-400 dark:text-slate-500 font-bold ml-1">KRW</span>
                        </div>
                        <div className="flex items-center gap-2 mt-4 text-sm font-medium">
                            <span className={cn(
                                'px-2 py-0.5 rounded-full flex items-center gap-1',
                                isPositive ? 'bg-emerald-100 text-emerald-700' : 'bg-rose-100 text-rose-700'
                            )}>
                                {isPositive ? <TrendingUp className="w-3.5 h-3.5" /> : <TrendingDown className="w-3.5 h-3.5" />}
                                {isPositive ? '+' : ''}{returnPct.toFixed(2)}%
                            </span>
                            <span className="text-slate-400 dark:text-slate-500">
                                (수익금: {Number(balance.unrealizedPnl) > 0 ? '+' : ''}{Number(balance.unrealizedPnl).toLocaleString()} KRW)
                            </span>
                        </div>
                    </div>
                    <div className="p-6 md:p-8 bg-slate-50/50 dark:bg-slate-800/50 space-y-4">
                        <div className="grid grid-cols-2 gap-4">
                            <div>
                                <div className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1 flex items-center gap-1">
                                    <Briefcase className="w-3.5 h-3.5" /> 보유 자산 가치
                                </div>
                                <div className="text-lg font-bold text-slate-700 dark:text-slate-200">
                                    {Number(balance.positionValueKrw).toLocaleString()}
                                    <span className="text-xs text-slate-400 dark:text-slate-500 ml-1">KRW</span>
                                </div>
                            </div>
                            <div>
                                <div className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1 flex items-center gap-1">
                                    <Clock className="w-3.5 h-3.5" /> 가용 현금
                                </div>
                                <div className="text-lg font-bold text-slate-700 dark:text-slate-200">
                                    {Number(balance.availableKrw).toLocaleString()}
                                    <span className="text-xs text-slate-400 dark:text-slate-500 ml-1">KRW</span>
                                </div>
                            </div>
                        </div>
                        <div className="text-xs text-slate-400 dark:text-slate-500">
                            초기 자금: <strong className="text-slate-600 dark:text-slate-300">{Number(balance.initialCapital).toLocaleString()} KRW</strong>
                        </div>
                    </div>
                </div>
            </div>

            {/* Trade Summary */}
            {tradeSummary && (
                <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                    <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                        <h2 className="text-base font-bold text-slate-800 dark:text-slate-100">매매 요약</h2>
                    </div>
                    <div className="grid grid-cols-2 md:grid-cols-4 divide-y md:divide-y-0 md:divide-x divide-slate-100 dark:divide-slate-800">
                        {/* 매수/매도 횟수 */}
                        <div className="p-5 flex items-start gap-3">
                            <div className="mt-0.5 p-2 rounded-lg bg-indigo-50 dark:bg-indigo-900/30">
                                <ShoppingCart className="w-4 h-4 text-indigo-500" />
                            </div>
                            <div>
                                <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">매수 / 매도</div>
                                <div className="text-lg font-extrabold text-slate-800 dark:text-slate-100 leading-none">
                                    <span className="text-emerald-600">{tradeSummary.buyCount}</span>
                                    <span className="text-slate-300 dark:text-slate-600 mx-1">/</span>
                                    <span className="text-rose-500">{tradeSummary.sellCount}</span>
                                    <span className="text-xs font-medium text-slate-400 ml-1">회</span>
                                </div>
                                <div className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                                    총 {tradeSummary.buyCount + tradeSummary.sellCount}회 체결
                                </div>
                            </div>
                        </div>

                        {/* 누적 수수료 */}
                        <div className="p-5 flex items-start gap-3">
                            <div className="mt-0.5 p-2 rounded-lg bg-amber-50 dark:bg-amber-900/30">
                                <Receipt className="w-4 h-4 text-amber-500" />
                            </div>
                            <div>
                                <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">누적 수수료</div>
                                <div className="text-lg font-extrabold text-slate-800 dark:text-slate-100 leading-none">
                                    {tradeSummary.totalFee.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}
                                    <span className="text-xs font-medium text-slate-400 ml-1">KRW</span>
                                </div>
                                <div className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                                    거래당 평균 {tradeSummary.buyCount + tradeSummary.sellCount > 0
                                        ? Math.round(tradeSummary.totalFee / (tradeSummary.buyCount + tradeSummary.sellCount)).toLocaleString()
                                        : 0} KRW
                                </div>
                            </div>
                        </div>

                        {/* 실현 손익 */}
                        <div className="p-5 flex items-start gap-3">
                            <div className={cn(
                                'mt-0.5 p-2 rounded-lg',
                                tradeSummary.totalRealizedPnl >= 0
                                    ? 'bg-emerald-50 dark:bg-emerald-900/30'
                                    : 'bg-rose-50 dark:bg-rose-900/30'
                            )}>
                                <DollarSign className={cn(
                                    'w-4 h-4',
                                    tradeSummary.totalRealizedPnl >= 0 ? 'text-emerald-500' : 'text-rose-500'
                                )} />
                            </div>
                            <div>
                                <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">실현 손익 합계</div>
                                <div className={cn(
                                    'text-lg font-extrabold leading-none',
                                    tradeSummary.totalRealizedPnl >= 0 ? 'text-emerald-600' : 'text-rose-600'
                                )}>
                                    {tradeSummary.totalRealizedPnl >= 0 ? '+' : ''}
                                    {tradeSummary.totalRealizedPnl.toLocaleString('ko-KR', { maximumFractionDigits: 0 })}
                                    <span className="text-xs font-medium text-slate-400 ml-1">KRW</span>
                                </div>
                                <div className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                                    매도 {tradeSummary.sellCount}회 기준
                                </div>
                            </div>
                        </div>

                        {/* 승률 */}
                        <div className="p-5 flex items-start gap-3">
                            <div className={cn(
                                'mt-0.5 p-2 rounded-lg',
                                tradeSummary.winRate >= 50
                                    ? 'bg-emerald-50 dark:bg-emerald-900/30'
                                    : 'bg-slate-100 dark:bg-slate-800'
                            )}>
                                <Trophy className={cn(
                                    'w-4 h-4',
                                    tradeSummary.winRate >= 50 ? 'text-emerald-500' : 'text-slate-400'
                                )} />
                            </div>
                            <div className="flex-1 min-w-0">
                                <div className="text-xs text-slate-500 dark:text-slate-400 mb-1">승률</div>
                                <div className="text-lg font-extrabold text-slate-800 dark:text-slate-100 leading-none">
                                    {tradeSummary.winRate.toFixed(1)}
                                    <span className="text-xs font-medium text-slate-400 ml-0.5">%</span>
                                </div>
                                <div className="mt-2 h-1.5 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden">
                                    <div
                                        className={cn(
                                            'h-full rounded-full transition-all duration-500',
                                            tradeSummary.winRate >= 50 ? 'bg-emerald-400' : 'bg-rose-400'
                                        )}
                                        style={{ width: `${tradeSummary.winRate}%` }}
                                    />
                                </div>
                                <div className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                                    {tradeSummary.winCount}/{tradeSummary.sellCount}회 수익 실현
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Chart */}
            {chartData.length > 0 && (() => {
                const CHART_HEIGHT = 300;
                const PX_PER_POINT = 14;
                const SCROLL_THRESHOLD = 60;
                const MAX_WIDTH = 4000;
                const needsScroll = chartData.length > SCROLL_THRESHOLD;
                const fixedWidth = needsScroll
                    ? Math.min(MAX_WIDTH, Math.max(800, chartData.length * PX_PER_POINT))
                    : undefined;

                const chartInner = (w?: number) => (
                    <ComposedChart
                        width={w}
                        height={CHART_HEIGHT}
                        data={chartData}
                        margin={{ top: 10, right: 20, bottom: 0, left: 10 }}
                    >
                        <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                        <XAxis
                            dataKey="time"
                            type="number"
                            domain={['dataMin', 'dataMax']}
                            scale="time"
                            tickFormatter={(t) => format(new Date(t), 'MM/dd HH:mm')}
                            tick={{ fontSize: 11, fill: '#94a3b8' }}
                            tickCount={6}
                            minTickGap={40}
                        />
                        <YAxis
                            domain={['auto', 'auto']}
                            tickFormatter={(v) => Number(v).toLocaleString()}
                            tick={{ fontSize: 11, fill: '#94a3b8' }}
                            width={80}
                        />
                        <Tooltip content={<ChartTooltip />} />
                        <Line
                            type="monotone"
                            dataKey="close"
                            stroke="#6366f1"
                            strokeWidth={1.5}
                            dot={(props: any) => {
                                const { cx, cy, payload } = props;
                                if (payload.buyOrder) {
                                    return <circle key={`buy-dot-${cx}`} cx={cx} cy={cy} r={7} fill="#10b981" stroke="#fff" strokeWidth={2} />;
                                }
                                if (payload.sellOrder) {
                                    return <circle key={`sell-dot-${cx}`} cx={cx} cy={cy} r={7} fill="#f43f5e" stroke="#fff" strokeWidth={2} />;
                                }
                                return <g key={`empty-${cx}`} />;
                            }}
                            activeDot={{ r: 5, stroke: '#6366f1', strokeWidth: 2, fill: '#fff' }}
                            isAnimationActive={false}
                        />
                    </ComposedChart>
                );

                return (
                    <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
                        <div className="px-6 py-5 border-b border-slate-100 bg-slate-50/50 flex items-center justify-between">
                            <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">가격 차트 (매수/매도 시점)</h2>
                            {needsScroll && (
                                <span className="text-xs text-slate-400">← 좌우 스크롤 →</span>
                            )}
                        </div>
                        <div className="p-4">
                            {needsScroll ? (
                                // 데이터가 많으면 고정 픽셀 너비로 가로 스크롤
                                <div className="overflow-x-auto">
                                    <div style={{ width: fixedWidth }}>
                                        {chartInner(fixedWidth)}
                                    </div>
                                </div>
                            ) : (
                                // 데이터가 적으면 컨테이너에 맞게 반응형
                                <div style={{ height: CHART_HEIGHT }}>
                                    <ResponsiveContainer width="100%" height="100%">
                                        {chartInner() as any}
                                    </ResponsiveContainer>
                                </div>
                            )}
                        </div>
                    </div>
                );
            })()}

            {/* Positions */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex justify-between items-center">
                    <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">현재 보유 포지션</h2>
                    <span className="py-1 px-2.5 bg-indigo-50 text-indigo-600 rounded-md text-xs font-bold border border-indigo-100">
                        {positions.length}개
                    </span>
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full text-left text-sm text-slate-600 dark:text-slate-300">
                        <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                            <tr>
                                <th className="px-6 py-4">코인</th>
                                <th className="px-6 py-4 text-right">보유 수량</th>
                                <th className="px-6 py-4 text-right">평균매수단가</th>
                                <th className="px-6 py-4 text-right">평가손익 (KRW)</th>
                                <th className="px-6 py-4 text-right">수익률</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                            {positions.length === 0 ? (
                                <tr>
                                    <td colSpan={5} className="px-6 py-10 text-center text-slate-400 dark:text-slate-500 bg-slate-50/50 dark:bg-slate-800/30">
                                        현재 보유 중인 포지션이 없습니다.
                                    </td>
                                </tr>
                            ) : positions.map(pos => (
                                <tr key={pos.id} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50 transition-colors">
                                    <td className="px-6 py-4 font-bold text-slate-800 dark:text-slate-100">{pos.coinPair.replace('KRW-', '')}</td>
                                    <td className="px-6 py-4 text-right font-medium">{Number(pos.quantity).toFixed(8)}</td>
                                    <td className="px-6 py-4 text-right font-medium">{Number(pos.avgEntryPrice).toLocaleString()}</td>
                                    <td className="px-6 py-4 text-right font-medium">{Number(pos.unrealizedPnl).toLocaleString()}</td>
                                    <td className="px-6 py-4 text-right">
                                        <span className={cn(
                                            'inline-flex items-center gap-1 font-semibold px-2 py-0.5 rounded-full text-xs',
                                            Number(pos.unrealizedPnlPct) >= 0
                                                ? 'bg-emerald-50 text-emerald-600 border border-emerald-100'
                                                : 'bg-rose-50 text-rose-600 border border-rose-100'
                                        )}>
                                            {Number(pos.unrealizedPnlPct) > 0 ? '+' : ''}{Number(pos.unrealizedPnlPct).toFixed(2)}%
                                        </span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>

            {/* 종료된 거래 이력 */}
            {closedPositions.length > 0 && (
                <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                    <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex justify-between items-center">
                        <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">종료된 거래 이력</h2>
                        <span className="py-1 px-2.5 bg-slate-100 text-slate-600 rounded-md text-xs font-bold border border-slate-200">{closedPositions.length}건</span>
                    </div>
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm text-slate-600 dark:text-slate-300">
                            <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold uppercase tracking-wider text-slate-500">
                                <tr>
                                    <th className="px-6 py-4">코인</th>
                                    <th className="px-6 py-4 text-right">진입가</th>
                                    <th className="px-6 py-4 text-right">수량</th>
                                    <th className="px-6 py-4 text-right">실현 손익</th>
                                    <th className="px-6 py-4 text-right">수익률</th>
                                    <th className="px-6 py-4">진입 시각</th>
                                    <th className="px-6 py-4">청산 시각</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {closedPositions.map(pos => {
                                    const pnl = Number(pos.realizedPnl ?? 0);
                                    const avgPrice = Number(pos.avgEntryPrice ?? 0);
                                    const qty = Number(pos.quantity ?? 0);
                                    const costBasis = avgPrice * qty;
                                    const pnlPct = costBasis > 0 ? (pnl / costBasis * 100) : 0;
                                    return (
                                        <tr key={pos.id} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50 transition-colors">
                                            <td className="px-6 py-4 font-bold text-slate-800 dark:text-slate-100">{pos.coinPair.replace('KRW-', '')}</td>
                                            <td className="px-6 py-4 text-right font-medium">{avgPrice.toLocaleString()}</td>
                                            <td className="px-6 py-4 text-right font-medium">{qty.toFixed(8)}</td>
                                            <td className={cn('px-6 py-4 text-right font-bold', pnl >= 0 ? 'text-emerald-600' : 'text-rose-600')}>
                                                {pnl >= 0 ? '+' : ''}{pnl.toLocaleString(undefined, { maximumFractionDigits: 0 })}
                                            </td>
                                            <td className="px-6 py-4 text-right">
                                                <span className={cn('inline-flex items-center font-semibold px-2 py-0.5 rounded-full text-xs', pnlPct >= 0 ? 'bg-emerald-50 text-emerald-600 border border-emerald-100' : 'bg-rose-50 text-rose-600 border border-rose-100')}>
                                                    {pnlPct >= 0 ? '+' : ''}{pnlPct.toFixed(2)}%
                                                </span>
                                            </td>
                                            <td className="px-6 py-4 text-xs text-slate-400">{pos.openedAt ? new Date(pos.openedAt).toLocaleString() : '-'}</td>
                                            <td className="px-6 py-4 text-xs text-slate-400">{(pos as any).closedAt ? new Date((pos as any).closedAt).toLocaleString() : '-'}</td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {/* Orders */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                    <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">체결 내역</h2>
                </div>
                <div className="p-6">
                    {orders?.content?.length > 0 ? (
                        <ul className="divide-y divide-slate-100 dark:divide-slate-800 border border-slate-100 dark:border-slate-800 rounded-xl">
                            {orders.content.map((ord: any) => (
                                <li key={ord.id} className="p-4 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                                    <div className="flex items-center gap-4 min-w-0">
                                        <span className={cn(
                                            'w-12 h-12 shrink-0 flex items-center justify-center rounded-xl font-bold text-xs',
                                            ord.side === 'BUY' ? 'bg-emerald-100 text-emerald-700' : 'bg-rose-100 text-rose-700'
                                        )}>
                                            {ord.side === 'BUY' ? '매수' : '매도'}
                                        </span>
                                        <div className="min-w-0">
                                            <div className="font-bold text-slate-800 dark:text-slate-100">{ord.coinPair}</div>
                                            <div className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                                                {new Date(ord.filledAt ?? ord.createdAt).toLocaleString()} 체결
                                            </div>
                                            {ord.signalReason && (
                                                <div className="text-xs text-indigo-500 mt-0.5 truncate max-w-xs" title={ord.signalReason}>
                                                    {ord.signalReason}
                                                </div>
                                            )}
                                        </div>
                                    </div>
                                    <div className="text-right shrink-0 space-y-0.5">
                                        {ord.side === 'BUY' ? (
                                            <>
                                                <div className="font-bold text-slate-700 dark:text-slate-200">{Number(ord.price).toLocaleString()} KRW</div>
                                                <div className="text-xs text-slate-500 dark:text-slate-400">수량: {Number(ord.quantity).toFixed(6)}</div>
                                                <div className="text-xs text-slate-400 dark:text-slate-500">수수료: {Number(ord.fee ?? 0).toLocaleString()} KRW</div>
                                            </>
                                        ) : (
                                            <>
                                                <div className="text-xs text-slate-400 dark:text-slate-500">
                                                    매수가 <span className="font-medium text-slate-600 dark:text-slate-300">{Number(ord.buyPrice ?? 0).toLocaleString()}</span>
                                                    {' → '}
                                                    매도가 <span className="font-medium text-slate-600 dark:text-slate-300">{Number(ord.price).toLocaleString()}</span> KRW
                                                </div>
                                                <div className="text-xs text-slate-500 dark:text-slate-400">수량: {Number(ord.quantity).toFixed(6)} · 수수료: {Number(ord.fee ?? 0).toLocaleString()} KRW</div>
                                                {ord.realizedPnl != null && (
                                                    <div className={cn(
                                                        'text-sm font-bold',
                                                        Number(ord.realizedPnl) >= 0 ? 'text-emerald-600' : 'text-rose-600'
                                                    )}>
                                                        {Number(ord.realizedPnl) >= 0 ? '+' : ''}{Number(ord.realizedPnl).toLocaleString()} KRW
                                                        <span className="text-xs font-semibold ml-1">
                                                            ({Number(ord.realizedPnlPct) >= 0 ? '+' : ''}{Number(ord.realizedPnlPct ?? 0).toFixed(2)}%)
                                                        </span>
                                                    </div>
                                                )}
                                            </>
                                        )}
                                    </div>
                                </li>
                            ))}
                        </ul>
                    ) : (
                        <div className="text-center py-10 text-slate-400 dark:text-slate-500">체결된 내역이 없습니다.</div>
                    )}
                </div>
            </div>

            {/* Strategy Logs */}
            <StrategyLogAccordion logs={(strategyLogsRes?.data as any)?.content} />
        </div>
    );
}
