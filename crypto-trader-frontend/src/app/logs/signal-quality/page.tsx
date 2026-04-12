'use client';

import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { logApi, logsApi } from '@/lib/api';
import type { SignalStatsByStrategy, SignalStatsByRegime, BlockedVsExecutedStats, BlockedReasonStat, FilterVerdict, SignalStatsByHour } from '@/lib/types';
import { Loader2, TrendingUp, Target, Activity, BarChart2, RefreshCw } from 'lucide-react';
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

const MIN_RELIABLE_SAMPLE = 15;

// ── 차단 신호 판정 배지 ───────────────────────────────────────────────────────

const VERDICT_META: Record<FilterVerdict, { label: string; className: string }> = {
    FILTER_HURTING:  { label: '⚠ 기회 손실',  className: 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-400' },
    FILTER_HELPING:  { label: '✅ 필터 효과',  className: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-400' },
    NEUTRAL:         { label: '— 중립',        className: 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400' },
    INSUFFICIENT:    { label: '샘플 부족',      className: 'bg-slate-100 text-slate-400 dark:bg-slate-800 dark:text-slate-500' },
};

function VerdictBadge({ verdict }: { verdict: FilterVerdict }) {
    const meta = VERDICT_META[verdict] ?? VERDICT_META.NEUTRAL;
    return (
        <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-[10px] font-semibold', meta.className)}>
            {meta.label}
        </span>
    );
}

// ── 차단 vs 실행 비교 섹션 ────────────────────────────────────────────────────

function CompareBar({ executedRate, blockedRate }: { executedRate: number; blockedRate: number }) {
    const execPct  = Math.round(executedRate * 100);
    const blockPct = Math.round(blockedRate  * 100);
    return (
        <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs">
                <span className="w-14 text-slate-500 text-right shrink-0">실행</span>
                <div className="flex-1 h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div className={cn('h-full rounded-full', execPct >= 50 ? 'bg-indigo-400' : 'bg-rose-400')}
                         style={{ width: `${execPct}%` }} />
                </div>
                <span className={cn('w-10 font-semibold tabular-nums', execPct >= 50 ? 'text-indigo-500' : 'text-rose-500')}>
                    {execPct}%
                </span>
            </div>
            <div className="flex items-center gap-2 text-xs">
                <span className="w-14 text-slate-500 text-right shrink-0">차단</span>
                <div className="flex-1 h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div className={cn('h-full rounded-full', blockPct >= 50 ? 'bg-amber-400' : 'bg-slate-300 dark:bg-slate-600')}
                         style={{ width: `${blockPct}%` }} />
                </div>
                <span className={cn('w-10 font-semibold tabular-nums', blockPct >= 50 ? 'text-amber-500' : 'text-slate-400')}>
                    {blockPct}%
                </span>
            </div>
        </div>
    );
}

function BlockedVsExecutedSection({ data }: { data: BlockedVsExecutedStats }) {
    const { executed, blocked, byBlockReason } = data;

    const hasData = blocked.totalSignals > 0;
    const blockedHigher = blocked.winRate4h > executed.winRate4h + 0.05;

    return (
        <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
            {/* 헤더 */}
            <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <div>
                    <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">차단 신호 사후 성과</h2>
                    <p className="text-[11px] text-slate-400 mt-0.5">리스크 필터가 막은 신호가 실제로 맞았는지 추적</p>
                </div>
                {hasData && blocked.evaluated4h >= 5 && (
                    <span className={cn(
                        'text-xs font-semibold px-2.5 py-1 rounded-full',
                        blockedHigher
                            ? 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-400'
                            : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-400'
                    )}>
                        {blockedHigher ? '⚠ 필터 조정 검토' : '✅ 필터 정상'}
                    </span>
                )}
            </div>

            {!hasData ? (
                <div className="px-6 py-8 text-center text-slate-400 text-xs">차단된 신호 데이터 없음</div>
            ) : (
                <div className="divide-y divide-slate-100 dark:divide-slate-800">
                    {/* 실행 vs 차단 4h 적중률 비교 */}
                    <div className="px-6 py-5 grid grid-cols-1 md:grid-cols-2 gap-6">
                        {/* 4h 비교 */}
                        <div>
                            <p className="text-xs font-medium text-slate-500 mb-3">4h 방향 적중률 비교</p>
                            <CompareBar executedRate={executed.winRate4h} blockedRate={blocked.winRate4h} />
                            <div className="mt-2 grid grid-cols-2 gap-2 text-[11px] text-slate-400">
                                <span>실행 {executed.evaluated4h}건 평가 · avg <span className={retClass(executed.avgReturn4h)}>{ret(executed.avgReturn4h)}</span></span>
                                <span>차단 {blocked.evaluated4h}건 평가 · avg <span className={retClass(blocked.avgReturn4h)}>{ret(blocked.avgReturn4h)}</span></span>
                            </div>
                        </div>
                        {/* 24h 비교 */}
                        <div>
                            <p className="text-xs font-medium text-slate-500 mb-3">24h 방향 적중률 비교</p>
                            <CompareBar executedRate={executed.winRate24h} blockedRate={blocked.winRate24h} />
                            <div className="mt-2 grid grid-cols-2 gap-2 text-[11px] text-slate-400">
                                <span>실행 {executed.evaluated24h}건 평가 · avg <span className={retClass(executed.avgReturn24h)}>{ret(executed.avgReturn24h)}</span></span>
                                <span>차단 {blocked.evaluated24h}건 평가 · avg <span className={retClass(blocked.avgReturn24h)}>{ret(blocked.avgReturn24h)}</span></span>
                            </div>
                        </div>
                    </div>

                    {/* 차단 사유별 상세 */}
                    {byBlockReason.length > 0 && (
                        <div className="overflow-x-auto">
                            <table className="w-full text-xs">
                                <thead className="text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-800 uppercase tracking-wide">
                                    <tr>
                                        <th className="px-5 py-3 text-left">차단 사유</th>
                                        <th className="px-3 py-3 text-right">총 차단</th>
                                        <th className="px-3 py-3 text-right">4h 평가</th>
                                        <th className="px-3 py-3 text-right">4h 적중률</th>
                                        <th className="px-3 py-3 text-right">4h 평균</th>
                                        <th className="px-3 py-3 text-right">24h 적중률</th>
                                        <th className="px-3 py-3 text-right">24h 평균</th>
                                        <th className="px-3 py-3 text-right">판정</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                    {byBlockReason.map((row: BlockedReasonStat) => (
                                        <tr key={row.reason}
                                            className="hover:bg-slate-50/50 dark:hover:bg-slate-800/30 transition-colors">
                                            <td className="px-5 py-3 font-medium text-slate-700 dark:text-slate-200 max-w-[200px] truncate"
                                                title={row.reason}>
                                                {row.reason}
                                            </td>
                                            <td className="px-3 py-3 text-right tabular-nums text-slate-500">{row.totalBlocked}</td>
                                            <td className="px-3 py-3 text-right tabular-nums text-slate-400">
                                                {row.evaluated4h}
                                                {row.evaluated4h < 5 && row.evaluated4h > 0 && (
                                                    <span className="ml-1 text-[9px] text-amber-500">n={row.evaluated4h}</span>
                                                )}
                                            </td>
                                            <td className={cn('px-3 py-3 text-right font-semibold tabular-nums',
                                                row.evaluated4h > 0 ? (row.winRate4h >= 0.5 ? 'text-emerald-500' : 'text-rose-500') : 'text-slate-300 dark:text-slate-600')}>
                                                {row.evaluated4h > 0 ? pct(row.winRate4h) : '-'}
                                            </td>
                                            <td className={cn('px-3 py-3 text-right tabular-nums', retClass(row.avgReturn4h))}>
                                                {row.evaluated4h > 0 ? ret(row.avgReturn4h) : '-'}
                                            </td>
                                            <td className={cn('px-3 py-3 text-right font-semibold tabular-nums',
                                                row.evaluated24h > 0 ? (row.winRate24h >= 0.5 ? 'text-emerald-500' : 'text-rose-500') : 'text-slate-300 dark:text-slate-600')}>
                                                {row.evaluated24h > 0 ? pct(row.winRate24h) : '-'}
                                            </td>
                                            <td className={cn('px-3 py-3 text-right tabular-nums', retClass(row.avgReturn24h))}>
                                                {row.evaluated24h > 0 ? ret(row.avgReturn24h) : '-'}
                                            </td>
                                            <td className="px-3 py-3 text-right">
                                                <VerdictBadge verdict={row.verdict} />
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

// ── 전략 가중치 패널 ──────────────────────────────────────────────────────────

const REGIME_LABEL: Record<string, string> = {
    TREND: '📈 추세 (TREND)',
    RANGE: '↔️ 횡보 (RANGE)',
    VOLATILITY: '⚡ 변동성 (VOLATILITY)',
};

function StrategyWeightsPanel() {
    const queryClient = useQueryClient();
    const { data: res, isLoading } = useQuery({
        queryKey: ['strategy-weights'],
        queryFn: () => logsApi.strategyWeights(),
        staleTime: 60_000,
    });

    const { mutate: optimize, isPending } = useMutation({
        mutationFn: () => logsApi.optimizeWeights(),
        onSuccess: () => queryClient.invalidateQueries({ queryKey: ['strategy-weights'] }),
    });

    const weights = res?.data as Record<string, {
        hasOverride: boolean;
        strategies: { name: string; weight: number; defaultWeight: number; overridden: boolean }[];
    }> | undefined;

    return (
        <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
            <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <div>
                    <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">전략 가중치 (자동 조정)</h2>
                    <p className="text-[11px] text-slate-400 mt-0.5">30일 신호 품질 기반 · 매일 06:00 자동 갱신 · 기본값 30% 혼합</p>
                </div>
                <button
                    onClick={() => optimize()}
                    disabled={isPending}
                    className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg bg-indigo-50 text-indigo-600 hover:bg-indigo-100 dark:bg-indigo-500/10 dark:text-indigo-400 dark:hover:bg-indigo-500/20 transition-colors disabled:opacity-50"
                >
                    <RefreshCw className={cn('w-3.5 h-3.5', isPending && 'animate-spin')} />
                    즉시 최적화
                </button>
            </div>

            {isLoading ? (
                <div className="flex items-center justify-center h-24">
                    <Loader2 className="w-5 h-5 animate-spin text-indigo-400" />
                </div>
            ) : !weights ? (
                <div className="px-6 py-6 text-center text-slate-400 text-xs">가중치 데이터 없음</div>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-3 divide-y md:divide-y-0 md:divide-x divide-slate-100 dark:divide-slate-800">
                    {Object.entries(weights).map(([regime, data]) => (
                        <div key={regime} className="px-5 py-4">
                            <div className="flex items-center justify-between mb-3">
                                <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
                                    {REGIME_LABEL[regime] ?? regime}
                                </span>
                                {data.hasOverride ? (
                                    <span className="text-[9px] px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-600 dark:bg-indigo-500/20 dark:text-indigo-400 font-semibold">
                                        최적화됨
                                    </span>
                                ) : (
                                    <span className="text-[9px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-400 dark:bg-slate-800 font-semibold">
                                        기본값
                                    </span>
                                )}
                            </div>
                            <div className="space-y-2.5">
                                {data.strategies.map(s => {
                                    const pctVal = Math.round(s.weight * 100);
                                    const defPct = Math.round(s.defaultWeight * 100);
                                    const diff   = pctVal - defPct;
                                    return (
                                        <div key={s.name}>
                                            <div className="flex items-center justify-between text-[11px] mb-1">
                                                <span className="font-medium text-slate-600 dark:text-slate-300">{s.name}</span>
                                                <div className="flex items-center gap-1.5">
                                                    {data.hasOverride && diff !== 0 && (
                                                        <span className={cn('text-[9px] font-semibold',
                                                            diff > 0 ? 'text-emerald-500' : 'text-rose-500')}>
                                                            {diff > 0 ? '+' : ''}{diff}%p
                                                        </span>
                                                    )}
                                                    <span className="font-bold text-slate-700 dark:text-slate-200 tabular-nums">
                                                        {pctVal}%
                                                    </span>
                                                </div>
                                            </div>
                                            <div className="h-1.5 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                                                <div
                                                    className="h-full bg-indigo-400 dark:bg-indigo-500 rounded-full transition-all duration-500"
                                                    style={{ width: `${pctVal}%` }}
                                                />
                                            </div>
                                            {data.hasOverride && (
                                                <div className="text-[9px] text-slate-400 mt-0.5">
                                                    기본 {defPct}%
                                                </div>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

// ── 시간대별 히트맵 ───────────────────────────────────────────────────────────

function HourlyHeatmap({ data }: { data: SignalStatsByHour[] }) {
    // winRate4h 기준으로 색상 계산 (샘플 없으면 회색)
    function cellColor(row: SignalStatsByHour): string {
        if (row.evaluated4h < 3) return 'bg-slate-100 dark:bg-slate-800';
        const rate = row.winRate4h;
        if (rate >= 0.65) return 'bg-emerald-500 dark:bg-emerald-500';
        if (rate >= 0.55) return 'bg-emerald-300 dark:bg-emerald-600';
        if (rate >= 0.45) return 'bg-slate-200 dark:bg-slate-600';
        if (rate >= 0.35) return 'bg-rose-300 dark:bg-rose-700';
        return 'bg-rose-500 dark:bg-rose-600';
    }

    function textColor(row: SignalStatsByHour): string {
        if (row.evaluated4h < 3) return 'text-slate-400 dark:text-slate-500';
        const rate = row.winRate4h;
        if (rate >= 0.55 || rate < 0.35) return 'text-white';
        return 'text-slate-700 dark:text-slate-200';
    }

    return (
        <div className="bg-white dark:bg-slate-900 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
            <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800">
                <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">시간대별 신호 품질 (KST)</h2>
                <p className="text-[11px] text-slate-400 mt-0.5">각 셀: 4h 적중률 · 진한 초록=좋음 / 진한 빨강=나쁨 / 회색=샘플 부족(3건 미만)</p>
            </div>
            <div className="p-5">
                {/* 24칸 히트맵 그리드 */}
                <div className="grid grid-cols-12 gap-1 mb-2">
                    {data.slice(0, 12).map(row => (
                        <div key={row.hour}
                            title={`${row.hour}시 | 신호 ${row.totalSignals}건 | 4h 적중 ${row.evaluated4h > 0 ? Math.round(row.winRate4h * 100) : '-'}% | avg ${ret(row.avgReturn4h)}`}
                            className={cn(
                                'rounded-md p-1 text-center cursor-default transition-transform hover:scale-105',
                                cellColor(row)
                            )}>
                            <div className={cn('text-[10px] font-bold tabular-nums', textColor(row))}>
                                {row.hour}시
                            </div>
                            <div className={cn('text-[9px] tabular-nums mt-0.5', textColor(row))}>
                                {row.evaluated4h >= 3 ? `${Math.round(row.winRate4h * 100)}%` : '—'}
                            </div>
                            <div className={cn('text-[9px] tabular-nums', textColor(row))}>
                                {row.totalSignals > 0 ? `n=${row.totalSignals}` : ''}
                            </div>
                        </div>
                    ))}
                </div>
                <div className="grid grid-cols-12 gap-1">
                    {data.slice(12, 24).map(row => (
                        <div key={row.hour}
                            title={`${row.hour}시 | 신호 ${row.totalSignals}건 | 4h 적중 ${row.evaluated4h > 0 ? Math.round(row.winRate4h * 100) : '-'}% | avg ${ret(row.avgReturn4h)}`}
                            className={cn(
                                'rounded-md p-1 text-center cursor-default transition-transform hover:scale-105',
                                cellColor(row)
                            )}>
                            <div className={cn('text-[10px] font-bold tabular-nums', textColor(row))}>
                                {row.hour}시
                            </div>
                            <div className={cn('text-[9px] tabular-nums mt-0.5', textColor(row))}>
                                {row.evaluated4h >= 3 ? `${Math.round(row.winRate4h * 100)}%` : '—'}
                            </div>
                            <div className={cn('text-[9px] tabular-nums', textColor(row))}>
                                {row.totalSignals > 0 ? `n=${row.totalSignals}` : ''}
                            </div>
                        </div>
                    ))}
                </div>

                {/* 범례 */}
                <div className="flex items-center gap-3 mt-4 text-[10px] text-slate-400">
                    <span>적중률:</span>
                    {[
                        { color: 'bg-emerald-500', label: '≥65%' },
                        { color: 'bg-emerald-300',  label: '55-65%' },
                        { color: 'bg-slate-200',    label: '45-55%' },
                        { color: 'bg-rose-300',     label: '35-45%' },
                        { color: 'bg-rose-500',     label: '<35%' },
                        { color: 'bg-slate-100',    label: '샘플부족' },
                    ].map(({ color, label }) => (
                        <div key={label} className="flex items-center gap-1">
                            <div className={cn('w-3 h-3 rounded-sm', color)} />
                            <span>{label}</span>
                        </div>
                    ))}
                </div>

                {/* 상위 3시간대 요약 */}
                {data.filter(r => r.evaluated4h >= 5).length > 0 && (() => {
                    const top3 = [...data]
                        .filter(r => r.evaluated4h >= 5)
                        .sort((a, b) => b.winRate4h - a.winRate4h)
                        .slice(0, 3);
                    const bot3 = [...data]
                        .filter(r => r.evaluated4h >= 5)
                        .sort((a, b) => a.winRate4h - b.winRate4h)
                        .slice(0, 3);
                    return (
                        <div className="mt-4 grid grid-cols-2 gap-4 text-xs">
                            <div>
                                <p className="text-slate-500 font-medium mb-1.5">📈 적중률 상위 시간대</p>
                                {top3.map(r => (
                                    <div key={r.hour} className="flex justify-between text-slate-600 dark:text-slate-300 py-0.5">
                                        <span>{r.hour}시 <span className="text-slate-400">(n={r.evaluated4h})</span></span>
                                        <span className="text-emerald-500 font-semibold">{Math.round(r.winRate4h * 100)}% · avg {ret(r.avgReturn4h)}</span>
                                    </div>
                                ))}
                            </div>
                            <div>
                                <p className="text-slate-500 font-medium mb-1.5">📉 적중률 하위 시간대</p>
                                {bot3.map(r => (
                                    <div key={r.hour} className="flex justify-between text-slate-600 dark:text-slate-300 py-0.5">
                                        <span>{r.hour}시 <span className="text-slate-400">(n={r.evaluated4h})</span></span>
                                        <span className="text-rose-500 font-semibold">{Math.round(r.winRate4h * 100)}% · avg {ret(r.avgReturn4h)}</span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    );
                })()}
            </div>
        </div>
    );
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

function EvalProgress({ evaluated, total }: { evaluated: number; total: number }) {
    if (total === 0) return null;
    const ratio = Math.round((evaluated / total) * 100);
    return (
        <span className="text-[10px] text-slate-400 tabular-nums">
            {ratio}% 평가완료
        </span>
    );
}

function LowSampleBadge({ n }: { n: number }) {
    if (n >= MIN_RELIABLE_SAMPLE) return null;
    return (
        <span className="ml-1 inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-semibold bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-400">
            n={n}
        </span>
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
    const blockedVsExecuted: BlockedVsExecutedStats | undefined = stats?.blockedVsExecuted;
    const byHour: SignalStatsByHour[] = stats?.byHour ?? [];

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
                            sub={`4h 평가: ${overall.evaluated4h}건 (${Math.round(overall.evaluated4h / overall.totalSignals * 100)}%)`}
                            icon={Activity}
                            accent="bg-indigo-50 text-indigo-500 dark:bg-indigo-500/10 dark:text-indigo-400"
                        />
                        <SummaryCard
                            label="4h 적중률"
                            value={overall.evaluated4h >= MIN_RELIABLE_SAMPLE ? pct(overall.winRate4h) : `${pct(overall.winRate4h)} ⚠`}
                            sub={`평균 수익 ${ret(overall.avgReturn4h)} · ${overall.evaluated4h}건`}
                            icon={Target}
                            accent={overall.winRate4h >= 0.5
                                ? 'bg-emerald-50 text-emerald-500 dark:bg-emerald-500/10 dark:text-emerald-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-500/10 dark:text-rose-400'}
                        />
                        <SummaryCard
                            label="24h 적중률"
                            value={overall.evaluated24h >= MIN_RELIABLE_SAMPLE ? pct(overall.winRate24h) : overall.evaluated24h > 0 ? `${pct(overall.winRate24h)} ⚠` : '집계 중'}
                            sub={`평균 수익 ${ret(overall.avgReturn24h)} · ${overall.evaluated24h}건`}
                            icon={TrendingUp}
                            accent={overall.winRate24h >= 0.5
                                ? 'bg-emerald-50 text-emerald-500 dark:bg-emerald-500/10 dark:text-emerald-400'
                                : 'bg-rose-50 text-rose-500 dark:bg-rose-500/10 dark:text-rose-400'}
                        />
                        <SummaryCard
                            label="24h 평균 수익"
                            value={ret(overall.avgReturn24h)}
                            sub={`24h 평가: ${overall.evaluated24h}건 (${Math.round(overall.evaluated24h / overall.totalSignals * 100)}%)`}
                            icon={BarChart2}
                            accent="bg-slate-50 text-slate-500 dark:bg-slate-700 dark:text-slate-400"
                        />
                    </div>

                    {/* 전략 가중치 */}
                    <StrategyWeightsPanel />

                    {/* 차단 신호 사후 성과 */}
                    {blockedVsExecuted && (
                        <BlockedVsExecutedSection data={blockedVsExecuted} />
                    )}

                    {/* 시간대별 히트맵 */}
                    {byHour.length > 0 && <HourlyHeatmap data={byHour} />}

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
                                                <td className="px-3 py-3 text-right text-slate-500 tabular-nums">
                                                    <div>{s.totalSignals}</div>
                                                    <EvalProgress evaluated={s.evaluated4h} total={s.totalSignals} />
                                                </td>
                                                <td className="px-3 py-3">
                                                    {s.evaluated4h > 0 ? (
                                                        <div className="space-y-1">
                                                            <WinRateBar rate={s.winRate4h} />
                                                            <LowSampleBadge n={s.evaluated4h} />
                                                        </div>
                                                    ) : <span className="text-slate-300 dark:text-slate-600">-</span>}
                                                </td>
                                                <td className={cn('px-3 py-3 text-right font-medium tabular-nums', retClass(s.avgReturn4h))}>
                                                    {s.evaluated4h > 0 ? ret(s.avgReturn4h) : '-'}
                                                </td>
                                                <td className="px-3 py-3">
                                                    {s.evaluated24h > 0 ? (
                                                        <div className="space-y-1">
                                                            <WinRateBar rate={s.winRate24h} />
                                                            <LowSampleBadge n={s.evaluated24h} />
                                                        </div>
                                                    ) : <span className="text-slate-300 dark:text-slate-600">-</span>}
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
                                            <div className="flex items-center gap-1.5">
                                                <span className={cn(
                                                    'px-2.5 py-1 rounded-full text-xs font-semibold',
                                                    REGIME_COLOR[r.regime] ?? REGIME_COLOR.UNKNOWN
                                                )}>
                                                    {r.regime}
                                                </span>
                                            </div>
                                            <span className="text-xs text-slate-400">{r.totalSignals}건</span>
                                        </div>
                                        <div className="space-y-1.5">
                                            <div className="flex items-center justify-between text-xs text-slate-500">
                                                <div className="flex items-center gap-1">
                                                    <span>4h 적중률</span>
                                                    <LowSampleBadge n={r.evaluated4h} />
                                                </div>
                                                <div className="flex items-center gap-1.5">
                                                    <EvalProgress evaluated={r.evaluated4h} total={r.totalSignals} />
                                                    <span className={cn('font-medium', retClass(r.avgReturn4h))}>
                                                        avg {ret(r.avgReturn4h)}
                                                    </span>
                                                </div>
                                            </div>
                                            {r.evaluated4h > 0
                                                ? <WinRateBar rate={r.winRate4h} />
                                                : <span className="text-xs text-slate-300 dark:text-slate-600">평가 데이터 없음</span>}
                                        </div>
                                        <div className="space-y-1.5">
                                            <div className="flex items-center justify-between text-xs text-slate-500">
                                                <div className="flex items-center gap-1">
                                                    <span>24h 적중률</span>
                                                    <LowSampleBadge n={r.evaluated24h} />
                                                </div>
                                                <div className="flex items-center gap-1.5">
                                                    <EvalProgress evaluated={r.evaluated24h} total={r.totalSignals} />
                                                    <span className={cn('font-medium', retClass(r.avgReturn24h))}>
                                                        avg {ret(r.avgReturn24h)}
                                                    </span>
                                                </div>
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
