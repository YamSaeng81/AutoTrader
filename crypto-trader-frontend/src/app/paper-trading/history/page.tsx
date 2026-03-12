'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { paperTradingApi } from '@/lib/api';
import { useDeletePaperSession, useBulkDeletePaperSessions } from '@/hooks';
import { PaperSession } from '@/lib/types';
import Link from 'next/link';
import { Loader2, History, TrendingUp, TrendingDown, ArrowLeft, Plus, Trash2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

export default function PaperTradingHistoryPage() {
    const { data: sessionsRes, isLoading } = useQuery({
        queryKey: ['paper-trading', 'sessions'],
        queryFn: () => paperTradingApi.sessions(),
    });

    const deletePaperSession = useDeletePaperSession();
    const bulkDeletePaperSessions = useBulkDeletePaperSessions();

    const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

    const sessions = (sessionsRes?.data as unknown as PaperSession[]) || [];
    const runningSessions = sessions.filter(s => s.status === 'RUNNING').length;

    // RUNNING 상태가 아닌 세션만 선택 가능
    const selectableIds = sessions
        .filter(s => s.status !== 'RUNNING')
        .map(s => s.id);

    const isAllSelected =
        selectableIds.length > 0 && selectableIds.every(id => selectedIds.has(id));
    const isSomeSelected = selectedIds.size > 0;

    function toggleSelectAll() {
        if (isAllSelected) {
            setSelectedIds(new Set());
        } else {
            setSelectedIds(new Set(selectableIds));
        }
    }

    function toggleSelect(id: number) {
        setSelectedIds((prev) => {
            const next = new Set(prev);
            if (next.has(id)) {
                next.delete(id);
            } else {
                next.add(id);
            }
            return next;
        });
    }

    function handleDelete(id: number) {
        if (!confirm('이 모의투자 세션을 삭제하시겠습니까?')) return;
        deletePaperSession.mutate(id, {
            onError: (err: unknown) => {
                const msg = err instanceof Error ? err.message : '삭제 중 오류가 발생했습니다.';
                alert(msg);
            },
        });
    }

    function handleBulkDelete() {
        if (selectedIds.size === 0) return;
        if (!confirm(`선택한 ${selectedIds.size}개의 세션을 삭제하시겠습니까?\n(실행 중인 세션은 자동으로 제외됩니다)`)) return;
        bulkDeletePaperSessions.mutate([...selectedIds], {
            onSuccess: () => setSelectedIds(new Set()),
            onError: () => alert('일괄 삭제 중 오류가 발생했습니다. 다시 시도해주세요.'),
        });
    }

    const isMutating = deletePaperSession.isPending || bulkDeletePaperSessions.isPending;

    if (isLoading) {
        return (
            <div className="flex flex-col items-center justify-center p-20 text-slate-500 gap-4">
                <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
                <p>이력 불러오는 중...</p>
            </div>
        );
    }

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            {/* Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <Link
                        href="/paper-trading"
                        className="p-2 rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                    >
                        <ArrowLeft className="w-5 h-5" />
                    </Link>
                    <div>
                        <div className="flex items-center gap-2">
                            <History className="w-5 h-5 text-indigo-500" />
                            <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">모의투자 이력</h1>
                        </div>
                        <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5 ml-7">
                            전체 {sessions.length}개 세션 · 실행 중 {runningSessions}개
                        </p>
                    </div>
                </div>
                <div className="flex items-center gap-3">
                    {isSomeSelected && (
                        <button
                            onClick={handleBulkDelete}
                            disabled={isMutating}
                            className="flex items-center gap-2 px-4 py-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed text-white text-sm font-semibold rounded-xl shadow-sm transition-colors"
                        >
                            <Trash2 className="w-4 h-4" />
                            선택 삭제 ({selectedIds.size})
                        </button>
                    )}
                    <Link
                        href="/paper-trading"
                        className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-xl shadow-sm transition-colors"
                    >
                        <Plus className="w-4 h-4" />
                        새 세션
                    </Link>
                </div>
            </div>

            {/* Sessions table */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                {sessions.length === 0 ? (
                    <div className="p-16 text-center">
                        <History className="w-12 h-12 text-slate-300 mx-auto mb-4" />
                        <p className="text-slate-500 font-medium">모의투자 이력이 없습니다.</p>
                        <Link
                            href="/paper-trading"
                            className="inline-flex items-center gap-1.5 mt-4 text-sm text-indigo-600 hover:underline"
                        >
                            <Plus className="w-4 h-4" /> 첫 번째 세션 시작하기
                        </Link>
                    </div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                                <tr>
                                    <th className="px-4 py-4">
                                        <input
                                            type="checkbox"
                                            checked={isAllSelected}
                                            onChange={toggleSelectAll}
                                            disabled={selectableIds.length === 0}
                                            className="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-indigo-600 cursor-pointer disabled:cursor-not-allowed disabled:opacity-40"
                                            aria-label="삭제 가능한 세션 전체 선택"
                                        />
                                    </th>
                                    <th className="px-5 py-4">#</th>
                                    <th className="px-5 py-4">전략</th>
                                    <th className="px-5 py-4">코인</th>
                                    <th className="px-5 py-4">타임프레임</th>
                                    <th className="px-5 py-4 text-right">초기 자금</th>
                                    <th className="px-5 py-4 text-right">최종 자산</th>
                                    <th className="px-5 py-4 text-right">수익률</th>
                                    <th className="px-5 py-4">시작일</th>
                                    <th className="px-5 py-4">종료일</th>
                                    <th className="px-5 py-4">상태</th>
                                    <th className="px-5 py-4"></th>
                                    <th className="px-4 py-4"></th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {sessions.map(session => {
                                    const returnPct = Number(session.totalReturnPct);
                                    const isPositive = returnPct >= 0;
                                    const isRunning = session.status === 'RUNNING';
                                    return (
                                        <tr key={session.id} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50 transition-colors text-slate-700 dark:text-slate-300 group">
                                            <td className="px-4 py-4">
                                                <input
                                                    type="checkbox"
                                                    checked={selectedIds.has(session.id)}
                                                    onChange={() => toggleSelect(session.id)}
                                                    disabled={isRunning}
                                                    title={isRunning ? '실행 중인 세션은 삭제할 수 없습니다' : undefined}
                                                    className="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-indigo-600 cursor-pointer disabled:cursor-not-allowed disabled:opacity-30"
                                                    aria-label={`세션 #${session.id} 선택`}
                                                />
                                            </td>
                                            <td className="px-5 py-4 font-medium text-slate-400 dark:text-slate-500">#{session.id}</td>
                                            <td className="px-5 py-4 font-semibold text-slate-800 dark:text-slate-100">{session.strategyName}</td>
                                            <td className="px-5 py-4">{session.coinPair}</td>
                                            <td className="px-5 py-4">
                                                <span className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-xs font-medium">
                                                    {session.timeframe}
                                                </span>
                                            </td>
                                            <td className="px-5 py-4 text-right font-medium">
                                                {Number(session.initialCapital).toLocaleString()}
                                            </td>
                                            <td className="px-5 py-4 text-right font-medium">
                                                {Number(session.totalAssetKrw).toLocaleString()}
                                            </td>
                                            <td className="px-5 py-4 text-right">
                                                <span className={cn(
                                                    'inline-flex items-center gap-1 font-semibold px-2 py-0.5 rounded-full text-xs',
                                                    isPositive
                                                        ? 'bg-emerald-50 text-emerald-700 border border-emerald-100'
                                                        : 'bg-rose-50 text-rose-700 border border-rose-100'
                                                )}>
                                                    {isPositive ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                                                    {isPositive ? '+' : ''}{returnPct.toFixed(2)}%
                                                </span>
                                            </td>
                                            <td className="px-5 py-4 text-slate-500 dark:text-slate-400 text-xs">
                                                {session.startedAt
                                                    ? format(new Date(session.startedAt), 'yyyy.MM.dd HH:mm')
                                                    : '-'}
                                            </td>
                                            <td className="px-5 py-4 text-slate-500 dark:text-slate-400 text-xs">
                                                {session.stoppedAt
                                                    ? format(new Date(session.stoppedAt), 'yyyy.MM.dd HH:mm')
                                                    : '-'}
                                            </td>
                                            <td className="px-5 py-4">
                                                <span className={cn(
                                                    'px-2 py-0.5 text-xs font-bold rounded-full border',
                                                    isRunning
                                                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                                        : 'bg-slate-100 text-slate-500 border-slate-200 dark:bg-slate-800 dark:border-slate-600'
                                                )}>
                                                    {isRunning ? '● 실행 중' : '○ 종료'}
                                                </span>
                                            </td>
                                            <td className="px-5 py-4">
                                                <Link
                                                    href={`/paper-trading/${session.id}`}
                                                    className="text-xs font-semibold text-indigo-600 hover:text-indigo-800 hover:underline"
                                                >
                                                    상세
                                                </Link>
                                            </td>
                                            <td className="px-4 py-4">
                                                {isRunning ? (
                                                    <button
                                                        disabled
                                                        title="실행 중인 세션은 삭제할 수 없습니다"
                                                        className="p-1.5 rounded-lg text-slate-300 dark:text-slate-600 cursor-not-allowed"
                                                    >
                                                        <Trash2 className="w-4 h-4" />
                                                    </button>
                                                ) : (
                                                    <button
                                                        onClick={() => handleDelete(session.id)}
                                                        disabled={isMutating}
                                                        title="삭제"
                                                        className="p-1.5 rounded-lg text-slate-400 hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 disabled:opacity-40 disabled:cursor-not-allowed transition-colors opacity-0 group-hover:opacity-100"
                                                    >
                                                        <Trash2 className="w-4 h-4" />
                                                    </button>
                                                )}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
}
