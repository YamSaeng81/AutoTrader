'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { tradingApi, csvExportApi } from '@/lib/api';
import { LiveOrder, SessionIndexEntry } from '@/lib/types';
import {
    Loader2, Activity, ChevronLeft, ChevronRight,
    ChevronsLeft, ChevronsRight,
    ChevronDown, ChevronRight as ChevronRightIcon, RefreshCw, Download,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { format, subDays, startOfDay } from 'date-fns';

const STATE_STYLE: Record<string, { label: string; cls: string }> = {
    PENDING:        { label: 'PENDING',       cls: 'bg-amber-50 text-amber-600 border border-amber-100 dark:bg-amber-500/10 dark:text-amber-400 dark:border-amber-500/20' },
    SUBMITTED:      { label: 'SUBMITTED',     cls: 'bg-blue-50 text-blue-600 border border-blue-100 dark:bg-blue-500/10 dark:text-blue-400 dark:border-blue-500/20' },
    PARTIAL_FILLED: { label: 'PARTIAL',       cls: 'bg-indigo-50 text-indigo-600 border border-indigo-100 dark:bg-indigo-500/10 dark:text-indigo-400 dark:border-indigo-500/20' },
    FILLED:         { label: 'FILLED',        cls: 'bg-emerald-50 text-emerald-600 border border-emerald-100 dark:bg-emerald-500/10 dark:text-emerald-400 dark:border-emerald-500/20' },
    CANCELLED:      { label: 'CANCELLED',     cls: 'bg-slate-100 text-slate-500 border border-slate-200 dark:bg-slate-700/40 dark:text-slate-400 dark:border-slate-600/40' },
    FAILED:         { label: 'FAILED',        cls: 'bg-rose-50 text-rose-600 border border-rose-100 dark:bg-rose-500/10 dark:text-rose-400 dark:border-rose-500/20' },
};

const SIDE_STYLE: Record<string, string> = {
    BUY:  'bg-emerald-50 text-emerald-600 border border-emerald-100 dark:bg-emerald-500/10 dark:text-emerald-400 dark:border-emerald-500/20',
    SELL: 'bg-rose-50 text-rose-600 border border-rose-100 dark:bg-rose-500/10 dark:text-rose-400 dark:border-rose-500/20',
};

const STATE_FILTERS = [
    { value: 'ALL',    label: '전체' },
    { value: 'ACTIVE', label: '진행중' },
    { value: 'FILLED', label: 'FILLED' },
    { value: 'FAILED', label: 'FAILED' },
    { value: 'CANCELLED', label: 'CANCELLED' },
];

const SIDE_FILTERS = [
    { value: 'ALL',  label: '전체' },
    { value: 'BUY',  label: '매수' },
    { value: 'SELL', label: '매도' },
];

const SESSION_STATUS: Record<string, { label: string; cls: string }> = {
    CREATED:           { label: '대기',     cls: 'bg-blue-50 text-blue-600 dark:bg-blue-500/10 dark:text-blue-400' },
    RUNNING:           { label: '운영 중',  cls: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-500/10 dark:text-emerald-400' },
    STOPPED:           { label: '정지',     cls: 'bg-slate-100 text-slate-500 dark:bg-slate-700/40 dark:text-slate-400' },
    EMERGENCY_STOPPED: { label: '비상정지', cls: 'bg-rose-50 text-rose-600 dark:bg-rose-500/10 dark:text-rose-400' },
    DELETED:           { label: '삭제됨',   cls: 'bg-slate-100 text-slate-400 dark:bg-slate-700/30 dark:text-slate-500' },
};

const DATE_PRESETS = [
    { value: 'ALL',       label: '전체' },
    { value: 'TODAY',     label: '오늘' },
    { value: 'YESTERDAY', label: '어제' },
    { value: '7DAYS',     label: '7일' },
    { value: 'CUSTOM',    label: '직접 지정' },
];

function getDateRange(
    preset: string,
    custom?: { from: string; to: string },
): { dateFrom?: string; dateTo?: string } {
    const fmt = (d: Date) => format(d, 'yyyy-MM-dd');
    const today = new Date();
    if (preset === 'TODAY')     return { dateFrom: fmt(today), dateTo: fmt(today) };
    if (preset === 'YESTERDAY') { const y = subDays(today, 1); return { dateFrom: fmt(y), dateTo: fmt(y) }; }
    if (preset === '7DAYS')     return { dateFrom: fmt(subDays(today, 6)), dateTo: fmt(today) };
    if (preset === 'CUSTOM')    return { dateFrom: custom?.from || undefined, dateTo: custom?.to || custom?.from || undefined };
    return {};
}

export default function UpbitLogsPage() {
    const [page, setPage] = useState(0);
    const [stateFilter, setStateFilter] = useState('ALL');
    const [sideFilter, setSideFilter] = useState('ALL');
    const [datePreset, setDatePreset] = useState('ALL');
    const [customFrom, setCustomFrom] = useState(() => format(new Date(), 'yyyy-MM-dd'));
    const [customTo, setCustomTo] = useState(() => format(new Date(), 'yyyy-MM-dd'));
    const [selectedSessions, setSelectedSessions] = useState<Set<number>>(new Set());
    const [sessionMenuOpen, setSessionMenuOpen] = useState(false);
    const [openRows, setOpenRows] = useState<Set<number>>(new Set());

    // 세션 인덱스 (삭제 세션 포함, 주문로그용은 모의 제외)
    const { data: sessionsRes } = useQuery({
        queryKey: ['session-index'],
        queryFn: () => tradingApi.sessionIndex(),
        staleTime: 60_000,
    });
    const sessions: SessionIndexEntry[] = ((sessionsRes?.data as any) ?? [])
        .filter((s: SessionIndexEntry) => s.sessionType !== 'PAPER');

    const { dateFrom, dateTo } = getDateRange(datePreset, { from: customFrom, to: customTo });
    const sessionIds = selectedSessions.size > 0 ? [...selectedSessions] : undefined;
    const sessionKey = sessionIds ? [...sessionIds].sort((a, b) => a - b).join(',') : 'ALL';

    const { data: res, isLoading, refetch, isFetching } = useQuery({
        queryKey: ['upbit-logs', page, datePreset, dateFrom, dateTo, sessionKey],
        queryFn: () => tradingApi.getOrders(page, 50, sessionIds, dateFrom, dateTo),
        refetchInterval: 10_000,
    });

    const raw: LiveOrder[] = (res?.data as any)?.content ?? [];
    const totalPages: number = (res?.data as any)?.totalPages ?? 0;
    const totalElements: number = (res?.data as any)?.totalElements ?? 0;

    const filtered = raw.filter(o => {
        const stateOk = stateFilter === 'ALL'
            ? true
            : stateFilter === 'ACTIVE'
            ? ['PENDING', 'SUBMITTED', 'PARTIAL_FILLED'].includes(o.state)
            : o.state === stateFilter;
        const sideOk = sideFilter === 'ALL' || o.side === sideFilter;
        return stateOk && sideOk;
    });

    const toggleRow = (id: number) => {
        setOpenRows(prev => {
            const next = new Set(prev);
            next.has(id) ? next.delete(id) : next.add(id);
            return next;
        });
    };

    const handleStateFilter = (v: string) => {
        setStateFilter(v);
        setPage(0);
        setOpenRows(new Set());
    };

    const handleSideFilter = (v: string) => {
        setSideFilter(v);
        setOpenRows(new Set());
    };

    const handleDatePreset = (v: string) => {
        setDatePreset(v);
        setPage(0);
        setOpenRows(new Set());
    };

    const toggleSession = (id: number) => {
        setSelectedSessions(prev => {
            const next = new Set(prev);
            next.has(id) ? next.delete(id) : next.add(id);
            return next;
        });
        setPage(0);
        setOpenRows(new Set());
    };

    const clearSessions = () => {
        setSelectedSessions(new Set());
        setPage(0);
        setOpenRows(new Set());
    };

    const [exporting, setExporting] = useState(false);
    const handleExport = async () => {
        setExporting(true);
        try {
            await csvExportApi.liveTradingOrders(sessionIds, dateFrom, dateTo);
        } catch (e) {
            console.error('주문 로그 CSV 내보내기 실패', e);
            alert('CSV 내보내기에 실패했습니다.');
        } finally {
            setExporting(false);
        }
    };

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            {/* 헤더 */}
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">Upbit 주문 로그</h1>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
                        거래소 주문 제출 및 상태 변경 이력 (총 {totalElements.toLocaleString()}건)
                    </p>
                </div>
                <div className="flex items-center gap-2">
                    <button
                        onClick={handleExport}
                        disabled={exporting}
                        title="현재 날짜·세션 필터 기준으로 주문 로그를 CSV(Excel)로 내려받습니다"
                        className="flex items-center gap-2 px-3 py-2 rounded-lg border border-emerald-200 dark:border-emerald-700/50 bg-emerald-50 dark:bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-100 dark:hover:bg-emerald-500/20 text-sm transition-colors disabled:opacity-50"
                    >
                        {exporting
                            ? <Loader2 className="w-4 h-4 animate-spin" />
                            : <Download className="w-4 h-4" />}
                        엑셀로 받기
                    </button>
                    <button
                        onClick={() => refetch()}
                        disabled={isFetching}
                        className="flex items-center gap-2 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700 text-sm transition-colors disabled:opacity-50"
                    >
                        <RefreshCw className={cn('w-4 h-4', isFetching && 'animate-spin')} />
                        새로고침
                    </button>
                </div>
            </div>

            {/* 필터 */}
            <div className="flex flex-col gap-3">
                {/* 1행: 날짜 + 세션 */}
                <div className="flex items-center gap-4 flex-wrap">
                    {/* 날짜 필터 */}
                    <div className="flex items-center gap-1.5 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
                        {DATE_PRESETS.map(f => (
                            <button
                                key={f.value}
                                onClick={() => handleDatePreset(f.value)}
                                className={cn(
                                    'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                                    datePreset === f.value
                                        ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
                                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
                                )}
                            >
                                {f.label}
                            </button>
                        ))}
                    </div>

                    {/* 직접 지정 날짜 입력 */}
                    {datePreset === 'CUSTOM' && (
                        <div className="flex items-center gap-1.5">
                            <input
                                type="date"
                                value={customFrom}
                                max={customTo || undefined}
                                onChange={e => { setCustomFrom(e.target.value); setPage(0); setOpenRows(new Set()); }}
                                className="h-8 px-2 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-1 focus:ring-indigo-400"
                            />
                            <span className="text-xs text-slate-400">~</span>
                            <input
                                type="date"
                                value={customTo}
                                min={customFrom || undefined}
                                onChange={e => { setCustomTo(e.target.value); setPage(0); setOpenRows(new Set()); }}
                                className="h-8 px-2 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-1 focus:ring-indigo-400"
                            />
                        </div>
                    )}

                    {/* 세션 다중 선택 필터 */}
                    <div className="relative">
                        <button
                            onClick={() => setSessionMenuOpen(o => !o)}
                            className="flex items-center gap-2 h-8 px-3 rounded-lg text-xs font-medium border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 focus:outline-none focus:ring-1 focus:ring-indigo-400"
                        >
                            {selectedSessions.size === 0
                                ? '세션 전체'
                                : `${selectedSessions.size}개 세션 선택`}
                            <ChevronDown className="w-3.5 h-3.5 text-slate-400" />
                        </button>
                        {sessionMenuOpen && (
                            <>
                                {/* 바깥 클릭 닫기 */}
                                <div className="fixed inset-0 z-10" onClick={() => setSessionMenuOpen(false)} />
                                <div className="absolute z-20 mt-1 w-72 max-h-80 overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-lg p-1.5">
                                    <div className="flex items-center justify-between px-2 py-1.5 mb-1 border-b border-slate-100 dark:border-slate-700">
                                        <span className="text-xs text-slate-400">
                                            {selectedSessions.size > 0
                                                ? `${selectedSessions.size}개 선택됨`
                                                : `전체 ${sessions.length}개 (지난 세션 포함)`}
                                        </span>
                                        <button
                                            onClick={clearSessions}
                                            disabled={selectedSessions.size === 0}
                                            className="text-xs text-indigo-500 hover:text-indigo-600 disabled:text-slate-300 dark:disabled:text-slate-600"
                                        >
                                            전체 해제
                                        </button>
                                    </div>
                                    {sessions.length === 0 ? (
                                        <p className="px-2 py-3 text-xs text-slate-400 text-center">세션 없음</p>
                                    ) : (
                                        sessions.map(s => {
                                            const st = SESSION_STATUS[s.status] ?? SESSION_STATUS.STOPPED;
                                            return (
                                                <label
                                                    key={s.sessionId}
                                                    className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer"
                                                >
                                                    <input
                                                        type="checkbox"
                                                        checked={selectedSessions.has(s.sessionId)}
                                                        onChange={() => toggleSession(s.sessionId)}
                                                        className="w-3.5 h-3.5 rounded border-slate-300 dark:border-slate-600 text-indigo-500 focus:ring-indigo-400"
                                                    />
                                                    <span className="text-xs text-slate-700 dark:text-slate-300 truncate flex-1">
                                                        #{s.sessionId} {s.strategyType ?? '-'} · {s.coinPair ?? '-'}
                                                    </span>
                                                    <span className={cn('shrink-0 px-1.5 py-0.5 rounded text-[10px] font-bold', st.cls)}>
                                                        {st.label}
                                                    </span>
                                                </label>
                                            );
                                        })
                                    )}
                                </div>
                            </>
                        )}
                    </div>
                </div>

                {/* 2행: 상태 + 방향 */}
                <div className="flex items-center gap-4 flex-wrap">
                    {/* 상태 필터 */}
                    <div className="flex items-center gap-1.5 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
                        {STATE_FILTERS.map(f => (
                            <button
                                key={f.value}
                                onClick={() => handleStateFilter(f.value)}
                                className={cn(
                                    'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                                    stateFilter === f.value
                                        ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
                                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
                                )}
                            >
                                {f.label}
                            </button>
                        ))}
                    </div>

                    {/* 방향 필터 */}
                    <div className="flex items-center gap-1.5 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
                        {SIDE_FILTERS.map(f => (
                            <button
                                key={f.value}
                                onClick={() => handleSideFilter(f.value)}
                                className={cn(
                                    'px-3 py-1.5 rounded-md text-xs font-medium transition-colors',
                                    sideFilter === f.value
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
                ) : filtered.length === 0 ? (
                    <div className="flex flex-col items-center justify-center p-20 text-slate-400">
                        <Activity className="w-12 h-12 mb-4 opacity-50" />
                        <p className="text-lg font-medium">주문 내역이 없습니다</p>
                        <p className="text-sm mt-1">실전매매 세션이 실행되면 Upbit 주문 이력이 기록됩니다.</p>
                    </div>
                ) : (
                    <>
                        <div className="divide-y divide-slate-100 dark:divide-slate-800">
                            {filtered.map(order => {
                                const isOpen = openRows.has(order.id);
                                const stateInfo = STATE_STYLE[order.state] ?? STATE_STYLE.PENDING;
                                const hasDetail = order.signalReason || order.failedReason || order.exchangeOrderId || order.responseJson;

                                return (
                                    <div key={order.id}>
                                        {/* 행 헤더 */}
                                        <button
                                            onClick={() => hasDetail && toggleRow(order.id)}
                                            className={cn(
                                                'w-full flex items-center gap-3 px-6 py-4 text-left transition-colors',
                                                hasDetail
                                                    ? 'hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer'
                                                    : 'cursor-default'
                                            )}
                                        >
                                            {/* 펼치기 아이콘 */}
                                            <span className="shrink-0 text-slate-300 dark:text-slate-600">
                                                {hasDetail
                                                    ? isOpen
                                                        ? <ChevronDown className="w-4 h-4 text-slate-400" />
                                                        : <ChevronRightIcon className="w-4 h-4 text-slate-400" />
                                                    : <span className="w-4 h-4 inline-block" />
                                                }
                                            </span>

                                            {/* 상태 뱃지 */}
                                            <span className={cn('shrink-0 px-2 py-0.5 rounded-full text-xs font-bold', stateInfo.cls)}>
                                                {stateInfo.label}
                                            </span>

                                            {/* 방향 뱃지 */}
                                            <span className={cn('shrink-0 px-2 py-0.5 rounded-full text-xs font-bold', SIDE_STYLE[order.side] ?? '')}>
                                                {order.side}
                                            </span>

                                            {/* 코인 */}
                                            <span className="shrink-0 text-sm font-semibold text-slate-700 dark:text-slate-200 w-24">
                                                {order.coinPair}
                                            </span>

                                            {/* 수량 */}
                                            <span className="flex-1 text-xs font-mono text-slate-500 dark:text-slate-400 truncate">
                                                {order.orderType === 'MARKET' && order.side === 'BUY'
                                                    ? `${Number(order.quantity).toLocaleString()} KRW`
                                                    : `${Number(order.quantity).toFixed(6)}`
                                                }
                                                {order.filledQuantity > 0 && (
                                                    <span className="ml-2 text-emerald-500 dark:text-emerald-400">
                                                        → 체결 {Number(order.filledQuantity).toFixed(6)}
                                                    </span>
                                                )}
                                            </span>

                                            {/* 세션 ID */}
                                            {order.sessionId != null && (
                                                <span className="shrink-0 text-xs font-mono text-slate-400 dark:text-slate-500">
                                                    세션 #{order.sessionId}
                                                </span>
                                            )}

                                            {/* 주문 ID */}
                                            {order.exchangeOrderId && (
                                                <span className="shrink-0 text-xs font-mono text-slate-400 dark:text-slate-500 truncate max-w-[120px]" title={order.exchangeOrderId}>
                                                    {order.exchangeOrderId.slice(0, 8)}…
                                                </span>
                                            )}

                                            {/* 시간 */}
                                            <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500">
                                                {order.createdAt ? format(new Date(order.createdAt), 'MM/dd HH:mm:ss') : ''}
                                            </span>
                                        </button>

                                        {/* 펼쳐진 상세 */}
                                        {isOpen && (
                                            <div className="border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/30 px-6 py-4 space-y-3">
                                                <div className="grid grid-cols-2 gap-x-8 gap-y-2 text-xs">
                                                    <DetailRow label="주문 ID" value={String(order.id)} mono />
                                                    <DetailRow label="포지션 ID" value={order.positionId != null ? String(order.positionId) : '—'} mono />
                                                    <DetailRow label="거래소 주문 ID" value={order.exchangeOrderId ?? '—'} mono />
                                                    <DetailRow label="주문 유형" value={order.orderType} />
                                                    <DetailRow
                                                        label="제출 시각"
                                                        value={order.submittedAt ? new Date(order.submittedAt).toLocaleString('ko-KR') : '—'}
                                                    />
                                                    <DetailRow
                                                        label="체결 시각"
                                                        value={order.filledAt ? new Date(order.filledAt).toLocaleString('ko-KR') : '—'}
                                                    />
                                                    <DetailRow
                                                        label="취소 시각"
                                                        value={order.cancelledAt ? new Date(order.cancelledAt).toLocaleString('ko-KR') : '—'}
                                                    />
                                                </div>

                                                {order.signalReason && (
                                                    <div>
                                                        <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wide mb-1">신호 사유</p>
                                                        <p className="text-xs text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-900/60 rounded-lg px-3 py-2 border border-slate-200 dark:border-slate-700">
                                                            {order.signalReason}
                                                        </p>
                                                    </div>
                                                )}

                                                {order.failedReason && (
                                                    <div>
                                                        <p className="text-xs font-semibold text-rose-500 uppercase tracking-wide mb-1">실패 사유</p>
                                                        <p className="text-xs text-rose-600 dark:text-rose-400 bg-rose-50 dark:bg-rose-500/10 rounded-lg px-3 py-2 border border-rose-100 dark:border-rose-500/20">
                                                            {order.failedReason}
                                                        </p>
                                                    </div>
                                                )}

                                                {order.responseJson && (
                                                    <div>
                                                        <p className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wide mb-1">Upbit 응답</p>
                                                        <pre className="text-xs text-slate-600 dark:text-slate-300 bg-slate-100 dark:bg-slate-900/60 rounded-lg px-3 py-2 border border-slate-200 dark:border-slate-700 overflow-x-auto whitespace-pre-wrap break-all">
                                                            {(() => { try { return JSON.stringify(JSON.parse(order.responseJson), null, 2); } catch { return order.responseJson; } })()}
                                                        </pre>
                                                    </div>
                                                )}
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
                                    페이지 {page + 1} / {totalPages} (총 {totalElements.toLocaleString()}건)
                                </span>
                                <div className="flex items-center gap-2">
                                    <button
                                        onClick={() => { setPage(0); setOpenRows(new Set()); }}
                                        disabled={page === 0}
                                        title="처음"
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronsLeft className="w-4 h-4" />
                                    </button>
                                    <button
                                        onClick={() => { setPage(p => Math.max(0, p - 1)); setOpenRows(new Set()); }}
                                        disabled={page === 0}
                                        title="이전"
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronLeft className="w-4 h-4" />
                                    </button>
                                    {/* 페이지 직접 입력 */}
                                    <input
                                        type="number"
                                        min={1}
                                        max={totalPages}
                                        value={page + 1}
                                        onChange={e => {
                                            const v = Number(e.target.value);
                                            if (Number.isNaN(v)) return;
                                            const next = Math.min(totalPages, Math.max(1, v)) - 1;
                                            setPage(next);
                                            setOpenRows(new Set());
                                        }}
                                        className="w-14 h-8 px-2 text-center rounded-lg text-xs font-mono border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-1 focus:ring-indigo-400 [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                                    />
                                    <button
                                        onClick={() => { setPage(p => Math.min(totalPages - 1, p + 1)); setOpenRows(new Set()); }}
                                        disabled={page >= totalPages - 1}
                                        title="다음"
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronRight className="w-4 h-4" />
                                    </button>
                                    <button
                                        onClick={() => { setPage(totalPages - 1); setOpenRows(new Set()); }}
                                        disabled={page >= totalPages - 1}
                                        title="끝"
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronsRight className="w-4 h-4" />
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

function DetailRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
    return (
        <div className="flex gap-2">
            <span className="text-slate-400 dark:text-slate-500 shrink-0 w-28">{label}</span>
            <span className={cn(
                'text-slate-600 dark:text-slate-300 truncate',
                mono && 'font-mono'
            )}>
                {value}
            </span>
        </div>
    );
}
