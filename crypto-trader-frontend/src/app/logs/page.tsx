'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { logApi } from '@/lib/api';
import { Loader2, FileText, ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

export default function LogsPage() {
    const [page, setPage] = useState(0);

    const { data: logsRes, isLoading } = useQuery({
        queryKey: ['logs', 'strategy', page],
        queryFn: () => logApi.strategyLogs(page, 50),
    });

    const logs = (logsRes?.data as any);
    const content = logs?.content ?? [];
    const totalPages = logs?.totalPages ?? 0;

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            <div>
                <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">전략 로그</h1>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">전략 분석 신호 및 판단 이유를 확인합니다.</p>
            </div>

            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                {isLoading ? (
                    <div className="flex items-center justify-center p-20">
                        <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
                    </div>
                ) : content.length === 0 ? (
                    <div className="flex flex-col items-center justify-center p-20 text-slate-400">
                        <FileText className="w-12 h-12 mb-4 opacity-50" />
                        <p className="text-lg font-medium">로그가 없습니다</p>
                        <p className="text-sm mt-1">모의투자 세션이 실행되면 전략 분석 로그가 기록됩니다.</p>
                    </div>
                ) : (
                    <>
                        <div className="overflow-x-auto">
                            <table className="w-full text-left text-sm">
                                <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                                    <tr>
                                        <th className="px-5 py-4">시간</th>
                                        <th className="px-5 py-4">전략</th>
                                        <th className="px-5 py-4">코인</th>
                                        <th className="px-5 py-4">신호</th>
                                        <th className="px-5 py-4">마켓 상태</th>
                                        <th className="px-5 py-4">판단 이유</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                    {content.map((log: any) => (
                                        <tr key={log.id} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50 transition-colors">
                                            <td className="px-5 py-3.5 text-xs text-slate-400 dark:text-slate-500 whitespace-nowrap">
                                                {log.createdAt ? new Date(log.createdAt).toLocaleString() : '-'}
                                            </td>
                                            <td className="px-5 py-3.5 font-medium text-slate-700 dark:text-slate-200">{log.strategyName}</td>
                                            <td className="px-5 py-3.5 text-slate-600 dark:text-slate-300">{log.coinPair}</td>
                                            <td className="px-5 py-3.5">
                                                <span className={cn(
                                                    'px-2 py-0.5 rounded-full text-xs font-bold',
                                                    log.signal === 'BUY' && 'bg-emerald-50 text-emerald-600 border border-emerald-100',
                                                    log.signal === 'SELL' && 'bg-rose-50 text-rose-600 border border-rose-100',
                                                    log.signal === 'HOLD' && 'bg-slate-100 text-slate-500 border border-slate-200',
                                                    !['BUY', 'SELL', 'HOLD'].includes(log.signal) && 'bg-slate-50 text-slate-400'
                                                )}>
                                                    {log.signal || '-'}
                                                </span>
                                            </td>
                                            <td className="px-5 py-3.5 text-xs text-slate-500 dark:text-slate-400">{log.marketRegime || '-'}</td>
                                            <td className="px-5 py-3.5 text-xs text-slate-500 dark:text-slate-400 max-w-xs truncate" title={log.reason}>
                                                {log.reason}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                        {totalPages > 1 && (
                            <div className="flex items-center justify-between px-5 py-4 border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                                <span className="text-xs text-slate-400 dark:text-slate-500">
                                    페이지 {page + 1} / {totalPages} (총 {logs?.totalElements ?? 0}건)
                                </span>
                                <div className="flex gap-2">
                                    <button
                                        onClick={() => setPage(p => Math.max(0, p - 1))}
                                        disabled={page === 0}
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronLeft className="w-4 h-4" />
                                    </button>
                                    <button
                                        onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
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
