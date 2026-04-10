'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { logApi } from '@/lib/api';
import { Loader2, FileText, ChevronLeft, ChevronRight, ChevronDown, ChevronRight as ChevronRightIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

const SESSION_FILTERS = [
    { value: 'ALL', label: '전체' },
    { value: 'PAPER', label: '모의투자' },
    { value: 'LIVE', label: '실전매매' },
];

const SIGNAL_STYLE: Record<string, string> = {
    BUY:  'bg-emerald-50 text-emerald-600 border border-emerald-100',
    SELL: 'bg-rose-50 text-rose-600 border border-rose-100',
    HOLD: 'bg-slate-100 text-slate-500 border border-slate-200',
};

const SESSION_TYPE_STYLE: Record<string, string> = {
    PAPER: 'bg-blue-50 text-blue-600 dark:bg-blue-500/15 dark:text-blue-400',
    LIVE:  'bg-orange-50 text-orange-600 dark:bg-orange-500/15 dark:text-orange-400',
};

export default function LogsPage() {
    const [page, setPage] = useState(0);
    const [sessionType, setSessionType] = useState('ALL');
    const [sessionIdInput, setSessionIdInput] = useState('');
    const [openGroups, setOpenGroups] = useState<Set<string>>(new Set());
    const sessionId = sessionIdInput.trim() !== '' ? Number(sessionIdInput) : undefined;

    const { data: logsRes, isLoading } = useQuery({
        queryKey: ['logs', 'strategy', page, sessionType, sessionId],
        queryFn: () => logApi.strategyLogs(page, 50, sessionType, sessionId),
    });

    const logs = (logsRes?.data as any);
    const content: any[] = logs?.content ?? [];
    const totalPages = logs?.totalPages ?? 0;

    const handleFilterChange = (value: string) => {
        setSessionType(value);
        setPage(0);
        setOpenGroups(new Set());
    };

    // 구분 + 세션ID 기준 그룹화
    const groups: { key: string; sessionType: string; sessionId: any; logs: any[] }[] = [];
    const groupMap: Record<string, number> = {};
    for (const log of content) {
        const key = `${log.sessionType ?? 'UNKNOWN'}-${log.sessionId ?? 'none'}`;
        if (groupMap[key] === undefined) {
            groupMap[key] = groups.length;
            groups.push({ key, sessionType: log.sessionType, sessionId: log.sessionId, logs: [] });
        }
        groups[groupMap[key]].logs.push(log);
    }

    const toggle = (key: string) => {
        setOpenGroups(prev => {
            const next = new Set(prev);
            next.has(key) ? next.delete(key) : next.add(key);
            return next;
        });
    };

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            {/* 헤더 */}
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">전략 로그</h1>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">전략 분석 신호 및 판단 이유를 확인합니다.</p>
                </div>
                <div className="flex items-center gap-3">
                    <input
                        type="number"
                        placeholder="세션 ID"
                        value={sessionIdInput}
                        onChange={e => { setSessionIdInput(e.target.value); setPage(0); setOpenGroups(new Set()); }}
                        className="w-24 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-xs text-slate-700 dark:text-slate-300 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-300"
                    />
                    <div className="flex items-center gap-1.5 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
                        {SESSION_FILTERS.map(f => (
                            <button
                                key={f.value}
                                onClick={() => handleFilterChange(f.value)}
                                className={cn(
                                    'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                                    sessionType === f.value
                                        ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
                                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
                                )}
                            >
                                {f.label}
                            </button>
                        ))}
                    </div>
                </div>
            </div>

            {/* 본문 */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                {isLoading ? (
                    <div className="flex items-center justify-center p-20">
                        <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
                    </div>
                ) : content.length === 0 ? (
                    <div className="flex flex-col items-center justify-center p-20 text-slate-400">
                        <FileText className="w-12 h-12 mb-4 opacity-50" />
                        <p className="text-lg font-medium">로그가 없습니다</p>
                        <p className="text-sm mt-1">모의투자 또는 실전매매 세션이 실행되면 전략 분석 로그가 기록됩니다.</p>
                    </div>
                ) : (
                    <>
                        <div className="divide-y divide-slate-100 dark:divide-slate-800">
                            {groups.map(group => {
                                const isOpen = openGroups.has(group.key);
                                const latest = group.logs[0];
                                const strategies = [...new Set(group.logs.map((l: any) => l.strategyName).filter(Boolean))];
                                const signalCounts = group.logs.reduce((acc: Record<string, number>, l: any) => {
                                    if (l.signal) acc[l.signal] = (acc[l.signal] ?? 0) + 1;
                                    return acc;
                                }, {});
                                const coinPairs = [...new Set(group.logs.map((l: any) => l.coinPair).filter(Boolean))];

                                return (
                                    <div key={group.key}>
                                        {/* 그룹 헤더 */}
                                        <button
                                            onClick={() => toggle(group.key)}
                                            className="w-full flex items-center gap-3 px-6 py-4 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
                                        >
                                            <span className="shrink-0 text-slate-400 dark:text-slate-500">
                                                {isOpen
                                                    ? <ChevronDown className="w-4 h-4" />
                                                    : <ChevronRightIcon className="w-4 h-4" />
                                                }
                                            </span>

                                            {/* 구분 뱃지 */}
                                            <span className={cn(
                                                'shrink-0 px-2 py-0.5 rounded-full text-xs font-medium',
                                                SESSION_TYPE_STYLE[group.sessionType] ?? 'bg-slate-100 text-slate-500'
                                            )}>
                                                {group.sessionType === 'PAPER' ? '모의' : group.sessionType === 'LIVE' ? '실전' : group.sessionType ?? '-'}
                                            </span>

                                            {/* 세션 ID */}
                                            <span className="shrink-0 text-xs font-mono text-slate-500 dark:text-slate-400">
                                                #{group.sessionId ?? '-'}
                                            </span>

                                            {/* 전략명 목록 */}
                                            <span className="flex-1 text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">
                                                {strategies.join(' · ') || '-'}
                                            </span>

                                            {/* 코인 */}
                                            {coinPairs.length > 0 && (
                                                <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500">
                                                    {coinPairs.join(', ')}
                                                </span>
                                            )}

                                            {/* 신호 카운트 뱃지 */}
                                            <span className="flex items-center gap-1.5 shrink-0">
                                                {Object.entries(signalCounts).map(([sig, cnt]) => (
                                                    <span key={sig} className={cn('px-2 py-0.5 rounded-full text-xs font-bold', SIGNAL_STYLE[sig] ?? 'bg-slate-100 text-slate-500')}>
                                                        {sig} {cnt}
                                                    </span>
                                                ))}
                                            </span>

                                            {/* 최근 시간 */}
                                            <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500 ml-1">
                                                {latest?.createdAt ? format(new Date(latest.createdAt), 'MM/dd HH:mm') : ''}
                                            </span>

                                            {/* 총 건수 */}
                                            <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500 ml-2 tabular-nums">
                                                {group.logs.length}건
                                            </span>
                                        </button>

                                        {/* 펼쳐진 로그 목록 */}
                                        {isOpen && (
                                            <div className="border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/30 overflow-x-auto">
                                                <table className="w-full text-left text-xs text-slate-600 dark:text-slate-300">
                                                    <thead className="text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-700 uppercase tracking-wide">
                                                        <tr>
                                                            <th className="px-4 py-2">시간</th>
                                                            <th className="px-4 py-2">전략</th>
                                                            <th className="px-4 py-2">신호</th>
                                                            <th className="px-4 py-2">실행</th>
                                                            <th className="px-4 py-2 text-right">신호가</th>
                                                            <th className="px-4 py-2 text-right">4h 성과</th>
                                                            <th className="px-4 py-2 text-right">24h 성과</th>
                                                            <th className="px-4 py-2">판단 이유 / 차단 사유</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody className="divide-y divide-slate-100 dark:divide-slate-700/50">
                                                        {group.logs.map((log: any) => {
                                                            const isBuySell = log.signal === 'BUY' || log.signal === 'SELL';
                                                            const ret4h: number | null = log.return4hPct ?? null;
                                                            const ret24h: number | null = log.return24hPct ?? null;
                                                            const retClass = (v: number | null) =>
                                                                v == null ? 'text-slate-400' : v > 0 ? 'text-emerald-500' : v < 0 ? 'text-rose-500' : 'text-slate-400';
                                                            return (
                                                            <tr key={log.id} className="hover:bg-slate-100/50 dark:hover:bg-slate-700/30 transition-colors">
                                                                <td className="px-4 py-2.5 whitespace-nowrap text-slate-400 text-xs">
                                                                    {log.createdAt ? new Date(log.createdAt).toLocaleString('ko-KR') : '-'}
                                                                </td>
                                                                <td className="px-4 py-2.5 font-medium text-slate-700 dark:text-slate-300 whitespace-nowrap text-xs">
                                                                    {log.strategyName ?? '-'}
                                                                </td>
                                                                <td className="px-4 py-2.5">
                                                                    <span className={cn('px-2 py-0.5 rounded-full font-bold text-xs', SIGNAL_STYLE[log.signal] ?? 'bg-slate-100 text-slate-500')}>
                                                                        {log.signal || '-'}
                                                                    </span>
                                                                </td>
                                                                <td className="px-4 py-2.5 text-xs">
                                                                    {!isBuySell ? (
                                                                        <span className="text-slate-400">-</span>
                                                                    ) : log.wasExecuted ? (
                                                                        <span className="text-emerald-500 font-medium">실행</span>
                                                                    ) : (
                                                                        <span className="text-rose-400" title={log.blockedReason ?? ''}>
                                                                            차단{log.blockedReason ? ' ⓘ' : ''}
                                                                        </span>
                                                                    )}
                                                                </td>
                                                                <td className="px-4 py-2.5 text-right text-xs text-slate-400 whitespace-nowrap">
                                                                    {log.signalPrice != null
                                                                        ? Number(log.signalPrice).toLocaleString('ko-KR', { maximumFractionDigits: 0 })
                                                                        : '-'}
                                                                </td>
                                                                <td className={cn('px-4 py-2.5 text-right text-xs font-medium whitespace-nowrap', retClass(ret4h))}>
                                                                    {ret4h != null ? `${ret4h > 0 ? '+' : ''}${ret4h.toFixed(2)}%` : '-'}
                                                                </td>
                                                                <td className={cn('px-4 py-2.5 text-right text-xs font-medium whitespace-nowrap', retClass(ret24h))}>
                                                                    {ret24h != null ? `${ret24h > 0 ? '+' : ''}${ret24h.toFixed(2)}%` : '-'}
                                                                </td>
                                                                <td className="px-4 py-2.5 text-slate-500 dark:text-slate-400 text-xs max-w-xs truncate"
                                                                    title={log.blockedReason || log.reason}>
                                                                    {log.blockedReason
                                                                        ? <span className="text-rose-400">{log.blockedReason}</span>
                                                                        : log.reason ?? '-'}
                                                                </td>
                                                            </tr>
                                                            );
                                                        })}
                                                    </tbody>
                                                </table>
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>

                        {/* 페이지네이션 */}
                        {totalPages > 1 && (
                            <div className="flex items-center justify-between px-5 py-4 border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                                <span className="text-xs text-slate-400 dark:text-slate-500">
                                    페이지 {page + 1} / {totalPages} (총 {logs?.totalElements ?? 0}건)
                                </span>
                                <div className="flex gap-2">
                                    <button
                                        onClick={() => { setPage(p => Math.max(0, p - 1)); setOpenGroups(new Set()); }}
                                        disabled={page === 0}
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronLeft className="w-4 h-4" />
                                    </button>
                                    <button
                                        onClick={() => { setPage(p => Math.min(totalPages - 1, p + 1)); setOpenGroups(new Set()); }}
                                        disabled={page >= totalPages - 1}
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronRight className="w-4 h-4" />
                                    </button>
                                </div>
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}
