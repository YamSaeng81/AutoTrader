'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { logApi, tradingApi, csvExportApi } from '@/lib/api';
import type { SessionIndexEntry } from '@/lib/types';
import { Loader2, FileText, ChevronLeft, ChevronRight, ChevronDown, ChevronRight as ChevronRightIcon, Download, Brain } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

const SESSION_FILTERS = [
    { value: 'ALL', label: '전체' },
    { value: 'PAPER', label: '모의투자' },
    { value: 'LIVE', label: '실전매매' },
    { value: 'DYNAMIC', label: '동적(실전)' },
    // 모의 동적 세션은 session_type='DYN_PAPER' 로 저장된다. 이 항목이 없어서
    // 페이퍼 함대 9개의 로그를 탭으로 걸러볼 방법이 없었다 (2026-09-01).
    { value: 'DYN_PAPER', label: '동적(모의)' },
];

const SESSION_STATUS_LABEL: Record<string, string> = {
    RUNNING: '운영중', STOPPED: '정지', EMERGENCY_STOPPED: '비상정지',
    CREATED: '대기', DELETED: '삭제됨', PAPER: '모의',
};

const SIGNAL_STYLE: Record<string, string> = {
    BUY:  'bg-emerald-50 text-emerald-600 border border-emerald-100',
    SELL: 'bg-rose-50 text-rose-600 border border-rose-100',
    HOLD: 'bg-slate-100 text-slate-500 border border-slate-200',
};

const SESSION_TYPE_STYLE: Record<string, string> = {
    PAPER:   'bg-blue-50 text-blue-600 dark:bg-blue-500/15 dark:text-blue-400',
    LIVE:    'bg-orange-50 text-orange-600 dark:bg-orange-500/15 dark:text-orange-400',
    DYNAMIC:   'bg-purple-50 text-purple-600 dark:bg-purple-500/15 dark:text-purple-400',
    DYN_PAPER: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-500/15 dark:text-indigo-400',
};

const SESSION_TYPE_LABEL: Record<string, string> = {
    PAPER: '모의', LIVE: '실전', DYNAMIC: '동적', DYN_PAPER: '동적모의',
};

export default function LogsPage() {
    const [page, setPage] = useState(0);
    const [sessionType, setSessionType] = useState('ALL');
    // 'TYPE:ID' 형태의 합성 키 — live_trading_session 과 dynamic_session 은 별도 BIGSERIAL 이라
    // sessionId 만으로 선택하면 서로 다른 구분의 동일 ID 세션이 섞일 수 있다.
    const [sessionSel, setSessionSel] = useState('');   // '' = 전체
    const [openGroups, setOpenGroups] = useState<Set<string>>(new Set());
    const [exporting, setExporting] = useState(false);
    const [analyzing, setAnalyzing] = useState(false);
    // 분석은 비동기라 결과가 텔레그램으로 간다. 화면에는 접수 결과만 남긴다.
    const [analyzeMsg, setAnalyzeMsg] = useState<{ ok: boolean; text: string } | null>(null);
    const [analyzeHours, setAnalyzeHours] = useState(24);

    // 세션 인덱스 (삭제·모의·동적 세션 포함)
    const { data: sessionIdxRes } = useQuery({
        queryKey: ['session-index'],
        queryFn: () => tradingApi.sessionIndex(),
        staleTime: 60_000,
    });
    const sessionIndex: SessionIndexEntry[] = (sessionIdxRes?.data as any) ?? [];
    const sessionOptions = sessionType === 'ALL'
        ? sessionIndex
        : sessionIndex.filter(s => s.sessionType === sessionType);

    const selectedSession = sessionSel !== ''
        ? sessionIndex.find(s => `${s.sessionType}:${s.sessionId}` === sessionSel)
        : undefined;
    const sessionId = selectedSession?.sessionId;
    // 특정 세션이 선택되면 그 세션의 실제 구분을 우선 사용 — 탭이 '전체'여도 sessionId 단독
    // 조회로 인한 구분 간 ID 충돌(예: LIVE #3 vs DYNAMIC #3)을 막는다.
    const effectiveSessionType = selectedSession?.sessionType ?? sessionType;

    const { data: logsRes, isLoading } = useQuery({
        queryKey: ['logs', 'strategy', page, effectiveSessionType, sessionId],
        queryFn: () => logApi.strategyLogs(page, 50, effectiveSessionType, sessionId),
    });

    const logs = (logsRes?.data as any);
    const content: any[] = logs?.content ?? [];
    const totalPages = logs?.totalPages ?? 0;

    const handleFilterChange = (value: string) => {
        setSessionType(value);
        setSessionSel('');   // 구분 변경 시 세션 선택 초기화
        setPage(0);
        setOpenGroups(new Set());
    };

    const handleExport = async () => {
        setExporting(true);
        try {
            await csvExportApi.strategyLogs(effectiveSessionType, sessionId);
        } catch {
            alert('CSV 내보내기에 실패했습니다.');
        } finally {
            setExporting(false);
        }
    };

    /**
     * 선택한 세션 하나를 LLM 에 보내 전략 분석을 요청한다.
     *
     * 세션을 고르지 않으면 동작하지 않는다 — 여러 세션을 섞어 분석하면 서로 다른 전략·규칙의
     * 표본이 합쳐져 결론이 무의미해진다. sessionType 은 반드시 선택된 세션의 실제 구분을 보낸다.
     */
    const handleAnalyze = async () => {
        if (!selectedSession) return;
        setAnalyzing(true);
        setAnalyzeMsg(null);
        try {
            const res = await logApi.llmAnalysis(
                selectedSession.sessionType, selectedSession.sessionId, analyzeHours);
            const d = res?.data as { accepted: boolean; message: string } | undefined;
            setAnalyzeMsg({ ok: !!d?.accepted, text: d?.message ?? '응답을 해석하지 못했습니다.' });
        } catch {
            setAnalyzeMsg({ ok: false, text: 'LLM 분석 요청에 실패했습니다.' });
        } finally {
            setAnalyzing(false);
        }
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
            <div className="flex items-center justify-between flex-wrap gap-3">
                <div>
                    <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">전략 로그</h1>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">전략 분석 신호 및 판단 이유를 확인합니다.</p>
                </div>
                <div className="flex items-center gap-3 flex-wrap">
                    {/* 세션 콤보박스 (삭제·모의 세션 포함) */}
                    <select
                        value={sessionSel}
                        onChange={e => { setSessionSel(e.target.value); setPage(0); setOpenGroups(new Set()); }}
                        className="max-w-[320px] px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-xs text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-300"
                    >
                        <option value="">세션 전체</option>
                        {sessionOptions.map(s => (
                            <option key={`${s.sessionType}:${s.sessionId}`} value={`${s.sessionType}:${s.sessionId}`}>
                                [{SESSION_TYPE_LABEL[s.sessionType] ?? s.sessionType}] #{s.sessionId} {s.strategyType ?? '-'}({s.coinPair ?? '-'}) {SESSION_STATUS_LABEL[s.status] ?? s.status}
                            </option>
                        ))}
                    </select>
                    {/* LLM 분석 — 세션을 하나 골랐을 때만 활성화된다 */}
                    <div className="flex items-center gap-1.5">
                        <select
                            value={analyzeHours}
                            onChange={e => setAnalyzeHours(Number(e.target.value))}
                            disabled={!selectedSession}
                            title="로그 집계 구간 (포지션은 세션 전체 기간을 봅니다)"
                            className="px-2 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-xs text-slate-700 dark:text-slate-300 disabled:opacity-40"
                        >
                            <option value={6}>6시간</option>
                            <option value={24}>24시간</option>
                            <option value={72}>3일</option>
                            <option value={168}>7일</option>
                        </select>
                        <button
                            onClick={handleAnalyze}
                            disabled={!selectedSession || analyzing}
                            title={selectedSession
                                ? '이 세션의 누적 로그를 LLM 에 보내 전략 분석을 받습니다. 결과는 텔레그램으로 전송됩니다.'
                                : '먼저 분석할 세션을 하나 선택하세요'}
                            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-violet-200 dark:border-violet-700/50 bg-violet-50 dark:bg-violet-500/10 text-violet-600 dark:text-violet-400 hover:bg-violet-100 dark:hover:bg-violet-500/20 text-xs transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                            {analyzing ? <Loader2 className="w-4 h-4 animate-spin" /> : <Brain className="w-4 h-4" />}
                            LLM 분석
                        </button>
                    </div>
                    <button
                        onClick={handleExport}
                        disabled={exporting}
                        title="현재 구분·세션 필터 기준으로 전략 로그를 CSV(Excel)로 내려받습니다"
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-200 dark:border-emerald-700/50 bg-emerald-50 dark:bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-100 dark:hover:bg-emerald-500/20 text-xs transition-colors disabled:opacity-50"
                    >
                        {exporting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
                        CSV
                    </button>
                    <div className="flex flex-wrap items-center gap-1.5 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
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

            {/* LLM 분석 접수 결과 — 분석 내용 자체는 텔레그램과 /logs/llm 에서 볼 수 있다 */}
            {analyzeMsg && (
                <div className={cn(
                    'flex items-start gap-2 px-4 py-3 rounded-xl border text-sm',
                    analyzeMsg.ok
                        ? 'border-violet-200 dark:border-violet-700/50 bg-violet-50 dark:bg-violet-500/10 text-violet-700 dark:text-violet-300'
                        : 'border-amber-200 dark:border-amber-700/50 bg-amber-50 dark:bg-amber-500/10 text-amber-700 dark:text-amber-300'
                )}>
                    <Brain className="w-4 h-4 mt-0.5 shrink-0" />
                    <span className="flex-1">{analyzeMsg.text}</span>
                    <button onClick={() => setAnalyzeMsg(null)} className="text-xs opacity-60 hover:opacity-100">✕</button>
                </div>
            )}

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
                                                {SESSION_TYPE_LABEL[group.sessionType] ?? group.sessionType ?? '-'}
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
