'use client';

import { useState, useEffect, useCallback } from 'react';
import { settingsApi } from '@/lib/api';
import { TelegramNotificationLog } from '@/lib/types';
import { MessageSquare, CheckCircle, XCircle, ChevronLeft, ChevronRight, Send, RefreshCw } from 'lucide-react';

const TYPE_LABELS: Record<string, { label: string; color: string }> = {
    TRADE_SUMMARY:  { label: '거래 요약',    color: 'bg-blue-500/15 text-blue-400' },
    SESSION_START:  { label: '세션 시작',    color: 'bg-green-500/15 text-green-400' },
    SESSION_STOP:   { label: '세션 종료',    color: 'bg-slate-500/15 text-slate-400' },
    STOP_LOSS:      { label: '손절',         color: 'bg-red-500/15 text-red-400' },
    EXCHANGE_DOWN:  { label: '거래소 장애',  color: 'bg-orange-500/15 text-orange-400' },
    RISK_LIMIT:     { label: '리스크 한도',  color: 'bg-yellow-500/15 text-yellow-400' },
    TEST:           { label: '테스트',       color: 'bg-purple-500/15 text-purple-400' },
};

const PAGE_SIZE = 50;

export default function TelegramHistoryPage() {
    const [logs, setLogs] = useState<TelegramNotificationLog[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [testSending, setTestSending] = useState(false);
    const [testResult, setTestResult] = useState<boolean | null>(null);
    const [expandedId, setExpandedId] = useState<number | null>(null);
    const [typeFilter, setTypeFilter] = useState<string>('ALL');

    const fetchLogs = useCallback(async (p: number) => {
        setLoading(true);
        try {
            const res = await settingsApi.telegramLogs(p, PAGE_SIZE);
            if (res.success && res.data) {
                setLogs(res.data.items);
                setTotalCount(res.data.totalCount);
                setTotalPages(res.data.totalPages);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { fetchLogs(page); }, [page, fetchLogs]);

    const handleTest = async () => {
        setTestSending(true);
        setTestResult(null);
        try {
            const res = await settingsApi.telegramTest();
            setTestResult(res.data?.success ?? false);
            setTimeout(() => fetchLogs(0), 1000);
        } catch {
            setTestResult(false);
        } finally {
            setTestSending(false);
        }
    };

    const filteredLogs = typeFilter === 'ALL'
        ? logs
        : logs.filter(l => l.type === typeFilter);

    return (
        <div className="p-6 space-y-6">
            {/* 헤더 */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <MessageSquare className="w-6 h-6 text-indigo-400" />
                    <div>
                        <h1 className="text-2xl font-bold text-slate-100">텔레그램 전송 이력</h1>
                        <p className="text-sm text-slate-400 mt-0.5">총 {totalCount.toLocaleString()}건</p>
                    </div>
                </div>
                <div className="flex items-center gap-2">
                    <button
                        onClick={() => fetchLogs(page)}
                        className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-sm transition-colors"
                    >
                        <RefreshCw className="w-4 h-4" />
                        새로고침
                    </button>
                    <button
                        onClick={handleTest}
                        disabled={testSending}
                        className="flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium transition-colors"
                    >
                        <Send className="w-4 h-4" />
                        {testSending ? '전송 중...' : '테스트 전송'}
                    </button>
                    {testResult !== null && (
                        <span className={`text-sm font-medium ${testResult ? 'text-green-400' : 'text-red-400'}`}>
                            {testResult ? '✓ 전송 성공' : '✗ 전송 실패'}
                        </span>
                    )}
                </div>
            </div>

            {/* 타입 필터 */}
            <div className="flex items-center gap-2 flex-wrap">
                {['ALL', ...Object.keys(TYPE_LABELS)].map(t => (
                    <button
                        key={t}
                        onClick={() => setTypeFilter(t)}
                        className={`px-3 py-1.5 rounded-full text-xs font-medium transition-colors border ${
                            typeFilter === t
                                ? 'bg-indigo-600 text-white border-indigo-500'
                                : 'bg-slate-800 text-slate-400 border-slate-700 hover:border-slate-500'
                        }`}
                    >
                        {t === 'ALL' ? '전체' : TYPE_LABELS[t]?.label ?? t}
                    </button>
                ))}
            </div>

            {/* 테이블 */}
            <div className="bg-slate-800/50 rounded-xl border border-slate-700 overflow-hidden">
                {loading ? (
                    <div className="flex items-center justify-center h-48 text-slate-400">
                        <RefreshCw className="w-5 h-5 animate-spin mr-2" />
                        불러오는 중...
                    </div>
                ) : filteredLogs.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-48 text-slate-500">
                        <MessageSquare className="w-10 h-10 mb-2 opacity-30" />
                        전송 이력이 없습니다
                    </div>
                ) : (
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-slate-700 bg-slate-800/80">
                                <th className="text-left px-4 py-3 text-slate-400 font-medium w-40">전송 시각</th>
                                <th className="text-left px-4 py-3 text-slate-400 font-medium w-28">유형</th>
                                <th className="text-left px-4 py-3 text-slate-400 font-medium w-32">세션</th>
                                <th className="text-left px-4 py-3 text-slate-400 font-medium">내용</th>
                                <th className="text-center px-4 py-3 text-slate-400 font-medium w-16">결과</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredLogs.map(log => {
                                const typeInfo = TYPE_LABELS[log.type] ?? { label: log.type, color: 'bg-slate-500/15 text-slate-400' };
                                const isExpanded = expandedId === log.id;
                                // 첫 줄만 미리보기
                                const preview = log.messageText.split('\n').find(l => l.trim())?.replace(/[*`\\]/g, '').trim() ?? '';

                                return (
                                    <tr
                                        key={log.id}
                                        className="border-b border-slate-700/50 hover:bg-slate-700/30 cursor-pointer transition-colors"
                                        onClick={() => setExpandedId(isExpanded ? null : log.id)}
                                    >
                                        <td className="px-4 py-3 text-slate-300 font-mono text-xs whitespace-nowrap">
                                            {log.sentAt}
                                        </td>
                                        <td className="px-4 py-3">
                                            <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium ${typeInfo.color}`}>
                                                {typeInfo.label}
                                            </span>
                                        </td>
                                        <td className="px-4 py-3 text-slate-400 text-xs truncate max-w-[120px]">
                                            {log.sessionLabel || '—'}
                                        </td>
                                        <td className="px-4 py-3 text-slate-300">
                                            {isExpanded ? (
                                                <pre className="whitespace-pre-wrap text-xs font-mono bg-slate-900/60 rounded p-2 mt-1 text-slate-300">
                                                    {log.messageText.replace(/\\/g, '')}
                                                </pre>
                                            ) : (
                                                <span className="text-slate-400 text-xs truncate block max-w-md">{preview}</span>
                                            )}
                                        </td>
                                        <td className="px-4 py-3 text-center">
                                            {log.success
                                                ? <CheckCircle className="w-4 h-4 text-green-400 mx-auto" />
                                                : <XCircle    className="w-4 h-4 text-red-400 mx-auto" />
                                            }
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                )}
            </div>

            {/* 페이지네이션 */}
            {totalPages > 1 && (
                <div className="flex items-center justify-center gap-2">
                    <button
                        onClick={() => setPage(p => Math.max(0, p - 1))}
                        disabled={page === 0}
                        className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 disabled:opacity-40 text-slate-300 transition-colors"
                    >
                        <ChevronLeft className="w-4 h-4" />
                    </button>
                    <span className="text-sm text-slate-400">
                        {page + 1} / {totalPages}
                    </span>
                    <button
                        onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                        disabled={page >= totalPages - 1}
                        className="p-2 rounded-lg bg-slate-800 hover:bg-slate-700 disabled:opacity-40 text-slate-300 transition-colors"
                    >
                        <ChevronRight className="w-4 h-4" />
                    </button>
                </div>
            )}
        </div>
    );
}
