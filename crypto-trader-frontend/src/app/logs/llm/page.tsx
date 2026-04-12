'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminLlmApi } from '@/lib/api';
import { Loader2, ChevronLeft, ChevronRight, Bot, BarChart2, Clock, Coins } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

const TASK_OPTIONS = ['전체', 'LOG_SUMMARY', 'SIGNAL_ANALYSIS', 'NEWS_SUMMARY', 'REPORT_NARRATION'];
const PROVIDER_OPTIONS = ['전체', 'CLAUDE', 'OPENAI', 'OLLAMA', 'MOCK'];

const TASK_STYLE: Record<string, string> = {
    LOG_SUMMARY:      'bg-blue-50 text-blue-600 border border-blue-100',
    SIGNAL_ANALYSIS:  'bg-purple-50 text-purple-600 border border-purple-100',
    NEWS_SUMMARY:     'bg-amber-50 text-amber-600 border border-amber-100',
    REPORT_NARRATION: 'bg-emerald-50 text-emerald-600 border border-emerald-100',
};

const PROVIDER_STYLE: Record<string, string> = {
    CLAUDE: 'bg-orange-50 text-orange-600 border border-orange-100',
    OPENAI: 'bg-green-50 text-green-600 border border-green-100',
    OLLAMA: 'bg-slate-50 text-slate-600 border border-slate-200',
    MOCK:   'bg-gray-50 text-gray-500 border border-gray-200',
};

function StatCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
    return (
        <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4">
            <div className="text-xs text-slate-500 mb-1">{label}</div>
            <div className="text-xl font-bold text-slate-800 dark:text-slate-100">{value}</div>
            {sub && <div className="text-xs text-slate-400 mt-0.5">{sub}</div>}
        </div>
    );
}

function TokenBreakdown({ rows, keyLabel }: { rows: any[]; keyLabel: string }) {
    if (!rows?.length) return <span className="text-slate-400 text-xs">데이터 없음</span>;
    return (
        <div className="space-y-1">
            {rows.map((r: any, i: number) => (
                <div key={i} className="flex items-center gap-3 text-xs">
                    <span className="w-32 font-mono truncate text-slate-600 dark:text-slate-300">{r[keyLabel]}</span>
                    <span className="text-slate-400">입력 {(r.promptTokens ?? 0).toLocaleString()}</span>
                    <span className="text-slate-400">출력 {(r.completionTokens ?? 0).toLocaleString()}</span>
                    <span className="font-medium text-slate-700 dark:text-slate-200">
                        합계 {((r.promptTokens ?? 0) + (r.completionTokens ?? 0)).toLocaleString()}
                    </span>
                </div>
            ))}
        </div>
    );
}

function DetailModal({ log, onClose }: { log: any; onClose: () => void }) {
    const { data, isLoading } = useQuery({
        queryKey: ['llm-log-detail', log.id],
        queryFn: () => adminLlmApi.getLogDetail(log.id),
    });
    const detail = data?.data as any;

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4" onClick={onClose}>
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-2xl max-w-3xl w-full max-h-[90vh] overflow-y-auto p-6" onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between mb-4">
                    <div className="flex items-center gap-2">
                        <Bot className="w-5 h-5 text-slate-500" />
                        <span className="font-semibold text-slate-800 dark:text-slate-100">LLM 호출 상세 #{log.id}</span>
                    </div>
                    <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">✕</button>
                </div>

                {isLoading ? (
                    <div className="flex justify-center py-10"><Loader2 className="w-6 h-6 animate-spin text-slate-400" /></div>
                ) : (
                    <div className="space-y-4 text-sm">
                        {/* 메타 정보 */}
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                            <div className="bg-slate-50 dark:bg-slate-800 rounded-lg p-3">
                                <div className="text-xs text-slate-400 mb-0.5">Task</div>
                                <div className="font-medium text-slate-700 dark:text-slate-200">{detail?.taskName}</div>
                            </div>
                            <div className="bg-slate-50 dark:bg-slate-800 rounded-lg p-3">
                                <div className="text-xs text-slate-400 mb-0.5">Provider / Model</div>
                                <div className="font-medium text-slate-700 dark:text-slate-200">{detail?.providerName} / {detail?.modelUsed ?? '-'}</div>
                            </div>
                            <div className="bg-slate-50 dark:bg-slate-800 rounded-lg p-3">
                                <div className="text-xs text-slate-400 mb-0.5">토큰 (입력+출력)</div>
                                <div className="font-medium text-slate-700 dark:text-slate-200">
                                    {(detail?.promptTokens ?? 0).toLocaleString()} + {(detail?.completionTokens ?? 0).toLocaleString()}
                                </div>
                            </div>
                            <div className="bg-slate-50 dark:bg-slate-800 rounded-lg p-3">
                                <div className="text-xs text-slate-400 mb-0.5">소요 시간</div>
                                <div className="font-medium text-slate-700 dark:text-slate-200">{detail?.durationMs?.toLocaleString() ?? '-'} ms</div>
                            </div>
                        </div>

                        {/* 시스템 프롬프트 */}
                        {detail?.systemPrompt && (
                            <div>
                                <div className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-1">System Prompt</div>
                                <pre className="bg-blue-50 dark:bg-blue-950/30 text-blue-800 dark:text-blue-300 rounded-lg p-3 text-xs whitespace-pre-wrap break-all max-h-40 overflow-y-auto font-mono">
                                    {detail.systemPrompt}
                                </pre>
                            </div>
                        )}

                        {/* 유저 프롬프트 */}
                        <div>
                            <div className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-1">User Prompt</div>
                            <pre className="bg-slate-50 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-lg p-3 text-xs whitespace-pre-wrap break-all max-h-60 overflow-y-auto font-mono">
                                {detail?.userPrompt ?? '-'}
                            </pre>
                        </div>

                        {/* 응답 */}
                        <div>
                            <div className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-1">
                                Response {!detail?.success && <span className="text-rose-500 ml-1">(실패)</span>}
                            </div>
                            {detail?.success ? (
                                <pre className="bg-emerald-50 dark:bg-emerald-950/30 text-emerald-800 dark:text-emerald-300 rounded-lg p-3 text-xs whitespace-pre-wrap break-all max-h-60 overflow-y-auto font-mono">
                                    {detail?.responseContent ?? '-'}
                                </pre>
                            ) : (
                                <pre className="bg-rose-50 dark:bg-rose-950/30 text-rose-700 dark:text-rose-400 rounded-lg p-3 text-xs whitespace-pre-wrap font-mono">
                                    {detail?.errorMessage ?? '-'}
                                </pre>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}

export default function LlmLogPage() {
    const [page, setPage] = useState(0);
    const [taskFilter, setTaskFilter] = useState('전체');
    const [providerFilter, setProviderFilter] = useState('전체');
    const [selectedLog, setSelectedLog] = useState<any>(null);

    const taskParam = taskFilter === '전체' ? undefined : taskFilter;
    const providerParam = providerFilter === '전체' ? undefined : providerFilter;

    const { data: logsRes, isLoading } = useQuery({
        queryKey: ['llm-logs', page, taskFilter, providerFilter],
        queryFn: () => adminLlmApi.getLogs({ page, size: 20, task: taskParam, provider: providerParam }),
    });

    const { data: statsRes } = useQuery({
        queryKey: ['llm-log-stats'],
        queryFn: () => adminLlmApi.getTokenStats(),
        refetchInterval: 60000,
    });

    const logsData = logsRes?.data as any;
    const items: any[] = logsData?.items ?? [];
    const totalPages: number = logsData?.totalPages ?? 0;
    const stats = statsRes?.data as any;

    const handleFilterChange = (type: 'task' | 'provider', value: string) => {
        if (type === 'task') setTaskFilter(value);
        else setProviderFilter(value);
        setPage(0);
    };

    return (
        <div className="min-h-screen bg-slate-50 dark:bg-slate-950 p-4 sm:p-6">
            <div className="max-w-6xl mx-auto space-y-6">

                {/* 헤더 */}
                <div className="flex items-center gap-3">
                    <Bot className="w-6 h-6 text-slate-600 dark:text-slate-400" />
                    <h1 className="text-xl font-bold text-slate-800 dark:text-slate-100">LLM 호출 로그</h1>
                </div>

                {/* 통계 카드 */}
                {stats && (
                    <div className="space-y-4">
                        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
                            <StatCard label="오늘 호출" value={stats.todayCallCount?.toLocaleString() ?? 0} />
                            <StatCard label="오늘 토큰" value={(stats.todayTotalTokens ?? 0).toLocaleString()} />
                            <StatCard label="7일 호출" value={stats.weekCallCount?.toLocaleString() ?? 0} />
                            <StatCard label="7일 토큰" value={(stats.weekTotalTokens ?? 0).toLocaleString()} />
                            <StatCard label="전체 호출" value={(stats.totalCallCount ?? 0).toLocaleString()} />
                        </div>
                        <div className="grid sm:grid-cols-2 gap-4">
                            <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4">
                                <div className="flex items-center gap-2 mb-3">
                                    <BarChart2 className="w-4 h-4 text-slate-400" />
                                    <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Task별 토큰</span>
                                </div>
                                <TokenBreakdown rows={stats.byTask} keyLabel="task" />
                            </div>
                            <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4">
                                <div className="flex items-center gap-2 mb-3">
                                    <Coins className="w-4 h-4 text-slate-400" />
                                    <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Provider별 토큰</span>
                                </div>
                                <TokenBreakdown rows={stats.byProvider} keyLabel="provider" />
                            </div>
                        </div>
                    </div>
                )}

                {/* 필터 */}
                <div className="flex flex-wrap gap-3">
                    <div className="flex items-center gap-1 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-1">
                        {TASK_OPTIONS.map(t => (
                            <button
                                key={t}
                                onClick={() => handleFilterChange('task', t)}
                                className={cn(
                                    'px-3 py-1.5 rounded-lg text-xs font-medium transition-colors',
                                    taskFilter === t
                                        ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900'
                                        : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'
                                )}
                            >{t}</button>
                        ))}
                    </div>
                    <div className="flex items-center gap-1 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-1">
                        {PROVIDER_OPTIONS.map(p => (
                            <button
                                key={p}
                                onClick={() => handleFilterChange('provider', p)}
                                className={cn(
                                    'px-3 py-1.5 rounded-lg text-xs font-medium transition-colors',
                                    providerFilter === p
                                        ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900'
                                        : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'
                                )}
                            >{p}</button>
                        ))}
                    </div>
                </div>

                {/* 로그 테이블 */}
                <div className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
                    {isLoading ? (
                        <div className="flex justify-center items-center py-20">
                            <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
                        </div>
                    ) : items.length === 0 ? (
                        <div className="text-center py-16 text-slate-400 text-sm">호출 로그가 없습니다</div>
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="w-full text-sm">
                                <thead className="border-b border-slate-100 dark:border-slate-700">
                                    <tr className="text-xs text-slate-400 uppercase tracking-wide">
                                        <th className="px-4 py-3 text-left w-14">ID</th>
                                        <th className="px-4 py-3 text-left">Task</th>
                                        <th className="px-4 py-3 text-left">Provider</th>
                                        <th className="px-4 py-3 text-left">Model</th>
                                        <th className="px-4 py-3 text-right">입력 토큰</th>
                                        <th className="px-4 py-3 text-right">출력 토큰</th>
                                        <th className="px-4 py-3 text-right">합계</th>
                                        <th className="px-4 py-3 text-right">소요(ms)</th>
                                        <th className="px-4 py-3 text-center">상태</th>
                                        <th className="px-4 py-3 text-left">호출 시각</th>
                                        <th className="px-4 py-3 text-left">응답 미리보기</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-50 dark:divide-slate-700/50">
                                    {items.map((item: any) => (
                                        <tr
                                            key={item.id}
                                            onClick={() => setSelectedLog(item)}
                                            className="hover:bg-slate-50 dark:hover:bg-slate-700/30 cursor-pointer transition-colors"
                                        >
                                            <td className="px-4 py-3 text-slate-400 font-mono text-xs">{item.id}</td>
                                            <td className="px-4 py-3">
                                                <span className={cn('px-2 py-0.5 rounded-md text-xs font-medium', TASK_STYLE[item.taskName] ?? 'bg-slate-100 text-slate-500')}>
                                                    {item.taskName}
                                                </span>
                                            </td>
                                            <td className="px-4 py-3">
                                                <span className={cn('px-2 py-0.5 rounded-md text-xs font-medium', PROVIDER_STYLE[item.providerName] ?? 'bg-slate-100 text-slate-500')}>
                                                    {item.providerName}
                                                </span>
                                            </td>
                                            <td className="px-4 py-3 text-xs text-slate-500 font-mono max-w-28 truncate">{item.modelUsed ?? '-'}</td>
                                            <td className="px-4 py-3 text-right text-xs text-slate-600 dark:text-slate-300 font-mono">{(item.promptTokens ?? 0).toLocaleString()}</td>
                                            <td className="px-4 py-3 text-right text-xs text-slate-600 dark:text-slate-300 font-mono">{(item.completionTokens ?? 0).toLocaleString()}</td>
                                            <td className="px-4 py-3 text-right text-xs font-semibold text-slate-700 dark:text-slate-200 font-mono">{(item.totalTokens ?? 0).toLocaleString()}</td>
                                            <td className="px-4 py-3 text-right text-xs text-slate-500 font-mono">
                                                <span className="flex items-center justify-end gap-1">
                                                    <Clock className="w-3 h-3" />
                                                    {item.durationMs?.toLocaleString() ?? '-'}
                                                </span>
                                            </td>
                                            <td className="px-4 py-3 text-center">
                                                <span className={cn('px-2 py-0.5 rounded-full text-xs font-medium',
                                                    item.success
                                                        ? 'bg-emerald-50 text-emerald-600 border border-emerald-100'
                                                        : 'bg-rose-50 text-rose-600 border border-rose-100'
                                                )}>
                                                    {item.success ? '성공' : '실패'}
                                                </span>
                                            </td>
                                            <td className="px-4 py-3 text-xs text-slate-400 whitespace-nowrap">
                                                {item.calledAt ? format(new Date(item.calledAt), 'MM-dd HH:mm:ss') : '-'}
                                            </td>
                                            <td className="px-4 py-3 text-xs text-slate-400 max-w-xs truncate">
                                                {item.responsePreview ?? item.errorMessage ?? '-'}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>

                {/* 페이지네이션 */}
                {totalPages > 1 && (
                    <div className="flex items-center justify-center gap-3">
                        <button
                            disabled={page === 0}
                            onClick={() => setPage(p => p - 1)}
                            className="p-2 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-700 disabled:opacity-30 transition-colors"
                        >
                            <ChevronLeft className="w-4 h-4" />
                        </button>
                        <span className="text-sm text-slate-600 dark:text-slate-300">
                            {page + 1} / {totalPages}
                        </span>
                        <button
                            disabled={page >= totalPages - 1}
                            onClick={() => setPage(p => p + 1)}
                            className="p-2 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-700 disabled:opacity-30 transition-colors"
                        >
                            <ChevronRight className="w-4 h-4" />
                        </button>
                    </div>
                )}
            </div>

            {/* 상세 모달 */}
            {selectedLog && <DetailModal log={selectedLog} onClose={() => setSelectedLog(null)} />}
        </div>
    );
}
