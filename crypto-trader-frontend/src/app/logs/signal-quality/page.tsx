'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { logApi } from '@/lib/api';
import type { SignalStatsByStrategy, SignalStatsByRegime } from '@/lib/types';
import { Loader2, TrendingUp, Target, Activity, BarChart2 } from 'lucide-react';
import { cn } from '@/lib/utils';

const DAYS_OPTIONS = [
    { value: 7,  label: '7일' },
    { value: 14, label: '14일' },
    { value: 30, label: '30일' },
    { value: 90, label: '90일' },
];

const SESSION_FILTERS = [
    { value: 'ALL',   label: '전체' },
    { value: 'LIVE',  label: '실전' },
    { value: 'PAPER', label: '모의' },
];

const REGIME_COLOR: Record<string, string> = {
    TREND:      'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-400',
    RANGE:      'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-400',
    VOLATILITY: 'bg-orange-100 text-orange-700 dark:bg-orange-500/20 dark:text-orange-400',
    UNKNOWN:    'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400',
};

function pct(v: number) {
    return `${(v * 100).toFixed(1)}%`;
}

function ret(v: number) {
    const sign = v >= 0 ? '+' : '';
    return `${sign}${v.toFixed(2)}%`;
}

function retClass(v: number) {
    return v > 0 ? 'text-emerald-500' : v < 0 ? 'text-rose-500' : 'text-slate-400';
}

function WinRateBar({ rate }: { rate: number }) {
    const pctVal = Math.round(rate * 100);
    return (
        <div className="flex items-center gap-2">
            <div className="flex-1 h-1.5 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                <div
                    className={cn('h-full rounded-full', pctVal >= 50 ? 'bg-emerald-400' : 'bg-rose-400')}
                    style={{ width: `${pctVal}%` }}
                />
            </div>
            <span className={cn('text-xs font-semibold w-10 text-right', pctVal >= 50 ? 'text-emerald-500' : 'text-rose-500')}>
                {pctVal}%
            </span>
        </div>
    );
}

function SummaryCard({ label, value, sub, icon: Icon, accent }: {
    label: string; value: string; sub?: string;
    icon: React.ComponentType<{ className?: string }>; accent: string;
}) {
    return (
        <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 p-5 shadow-sm">
            <div className="flex items-center justify-between mb-3">
                <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">{label}</span>
                <div className={cn('p-2 rounded-lg', accent)}>
                    <Icon className="w-4 h-4" />
                </div>
            </div>
            <p className="text-2xl font-bold text-slate-800 dark:text-slate-100">{value}</p>
            {sub && <p className="text-xs text-slate-400 mt-1">{sub}</p>}
        </div>
    );
}

export default function SignalQualityPage() {
    const [days, setDays] = useState(30);
    const [sessionType, setSessionType] = useState('ALL');

    const { data: res, isLoading } = useQuery({
        queryKey: ['signal-stats', days, sessionType],
        queryFn: () => logApi.signalStats(days, sessionType),
    });

    const stats = res?.data;
    const overall = stats?.overall;
    const byStrategy: SignalStatsByStrategy[] = stats?.byStrategy ?? [];
    const byRegime: SignalStatsByRegime[] = stats?.byRegime ?? [];

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            {/* 헤더 */}
            <div className="flex items-center justify-between flex-wrap gap-3">
                <div>
                    <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">신호 품질 분석</h1>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
                        BUY/SELL 신호 발생 후 4h·24h 실제 수익률 기반 적중률 집계
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    {/* 세션 필터 */}
                    <div className="flex items-center gap-1 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
                        {SESSION_FILTERS.map(f => (
                            <button
                                key={f.value}
                                onClick={() => setSessionType(f.value)}
                                className={cn(
                                    'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                                    sessionType === f.value
                                        ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
                                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-700'
                                )}
                            >
                                {f.label}
                            </button>
                        ))}
                    </div>
                    {/* 기간 필터 */}
                    <div className="flex items-center gap-1 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
                        {DAYS_OPTIONS.map(d => (
                            <button
                                key={d.value}
                                onClick={() => setDays(d.value)}
                                className={cn(
                                    'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                                    days === d.value
                                        ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
                                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-700'
                                )}
                            >
                                {d.label}
                            </button>
                        ))}
                    </div>
                </div>
            </div>

            {isLoading ? (
                <div className="flex items-center justify-center h-64">
                    <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
                </div>
            ) : !overall || overall.totalSignals === 0 ? (
                <div className="flex flex-col items-center justify-center h-64 text-slate-400">
                    <BarChart2 className="w-12 h-12 mb-4 opacity-40" />
                    <p className="text-lg font-medium">평가된 신호 없음</p>
                    <p className="text-sm mt-1">신호 발생 후 4시간 이상 지나야 평가 데이터가 쌓입니다.</p>
                </div>
            ) : (
                <>
                    {/* 전체 요약 카드 */}
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        <SummaryCard
                            label="총 평가 신호"
                            value={String(overall.totalSignals)}
                            sub={`4h 평가: ${overall.evaluated4h}건`}
                            icon={Activity}
                            accent="bg-indigo-50 text-indigo-500 dark:bg-indigo-500/10 dark:text-indigo-400"
                        />
                        <SummaryCard
                            label="4h 적중률"
                            value={pct(overall.winRate4h)}
                            sub={`평균 수익 ${ret(overall.avgReturn4h)}`}
                            icon={Target}
                            accent={overall.winRate4h >= 0.5
                                ? 'bg-emerald-50 text-emerald-500 dark:bg-emerald-500/10 dark:text-emerald-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-500/10 dark:text-rose-400'}
                        />
                        <SummaryCard
                            label="24h 적중률"
                            value={pct(overall.winRate24h)}
                            sub={`평균 수익 ${ret(overall.avgReturn24h)}`}
                            icon={TrendingUp}
                            accent={overall.winRate24h >= 0.5
                                ? 'bg-emerald-50 text-emerald-500 dark:bg-emerald-500/10 dark:text-emerald-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-500/10 dark:text-rose-400'}
                        />
                        <SummaryCard
                            label="24h 평균 수익"
                            value={ret(overall.avgReturn24h)}
                            sub={`24h 평가: ${overall.evaluated24h}건`}
                            icon={BarChart2}
                            accent="bg-slate-50 text-slate-500 dark:bg-slate-700 dark:text-slate-400"
                        />
                    </div>

                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                        {/* 전략별 성과 테이블 */}
                        <div className="lg:col-span-2 bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
                            <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800">
                                <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">전략별 신호 품질</h2>
                            </div>
                            <div className="overflow-x-auto">
                                <table className="w-full text-xs">
                                    <thead className="text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-800 uppercase tracking-wide">
                                        <tr>
                                            <th className="px-5 py-3 text-left">전략</th>
                                            <th className="px-3 py-3 text-right">신호</th>
                                            <th className="px-3 py-3 text-left min-w-[120px]">4h 적중률</th>
                                            <th className="px-3 py-3 text-right">4h 평균</th>
                                            <th className="px-3 py-3 text-left min-w-[120px]">24h 적중률</th>
                                            <th className="px-3 py-3 text-right">24h 평균</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                        {byStrategy.length === 0 ? (
                                            <tr>
                                                <td colSpan={6} className="px-5 py-8 text-center text-slate-400">데이터 없음</td>
                                            </tr>
                                        ) : byStrategy.map(s => (
                                            <tr key={`${s.strategyName}-${s.coinPair}`}
                                                className="hover:bg-slate-50/50 dark:hover:bg-slate-800/30 transition-colors">
                                                <td className="px-5 py-3">
                                                    <div className="font-semibold text-slate-700 dark:text-slate-200">{s.strategyName}</div>
                                                    <div className="text-slate-400 text-[10px] mt-0.5">{s.coinPair}</div>
                                                </td>
                                                <td className="px-3 py-3 text-right text-slate-500 tabular-nums">{s.totalSignals}</td>
                                                <td className="px-3 py-3">
                                                    {s.evaluated4h > 0
                                                        ? <WinRateBar rate={s.winRate4h} />
                                                        : <span className="text-slate-300 dark:text-slate-600">-</span>}
                                                </td>
                                                <td className={cn('px-3 py-3 text-right font-medium tabular-nums', retClass(s.avgReturn4h))}>
                                                    {s.evaluated4h > 0 ? ret(s.avgReturn4h) : '-'}
                                                </td>
                                                <td className="px-3 py-3">
                                                    {s.evaluated24h > 0
                                                        ? <WinRateBar rate={s.winRate24h} />
                                                        : <span className="text-slate-300 dark:text-slate-600">-</span>}
                                                </td>
                                                <td className={cn('px-3 py-3 text-right font-medium tabular-nums', retClass(s.avgReturn24h))}>
                                                    {s.evaluated24h > 0 ? ret(s.avgReturn24h) : '-'}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>

                        {/* 레짐별 성과 */}
                        <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
                            <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800">
                                <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">레짐별 신호 품질</h2>
                            </div>
                            <div className="divide-y divide-slate-100 dark:divide-slate-800">
                                {byRegime.length === 0 ? (
                                    <div className="px-6 py-8 text-center text-slate-400 text-xs">데이터 없음</div>
                                ) : byRegime.map(r => (
                                    <div key={r.regime} className="px-6 py-4 space-y-3">
                                        <div className="flex items-center justify-between">
                                            <span className={cn(
                                                'px-2.5 py-1 rounded-full text-xs font-semibold',
                                                REGIME_COLOR[r.regime] ?? REGIME_COLOR.UNKNOWN
                                            )}>
                                                {r.regime}
                                            </span>
                                            <span className="text-xs text-slate-400">{r.totalSignals}건</span>
                                        </div>
                                        <div className="space-y-1.5">
                                            <div className="flex items-center justify-between text-xs text-slate-500">
                                                <span>4h 적중률</span>
                                                <span className={cn('font-medium', retClass(r.avgReturn4h))}>
                                                    avg {ret(r.avgReturn4h)}
                                                </span>
                                            </div>
                                            {r.evaluated4h > 0
                                                ? <WinRateBar rate={r.winRate4h} />
                                                : <span className="text-xs text-slate-300 dark:text-slate-600">평가 데이터 없음</span>}
                                        </div>
                                        <div className="space-y-1.5">
                                            <div className="flex items-center justify-between text-xs text-slate-500">
                                                <span>24h 적중률</span>
                                                <span className={cn('font-medium', retClass(r.avgReturn24h))}>
                                                    avg {ret(r.avgReturn24h)}
                                                </span>
                                            </div>
                                            {r.evaluated24h > 0
                                                ? <WinRateBar rate={r.winRate24h} />
                                                : <span className="text-xs text-slate-300 dark:text-slate-600">평가 데이터 없음</span>}
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
