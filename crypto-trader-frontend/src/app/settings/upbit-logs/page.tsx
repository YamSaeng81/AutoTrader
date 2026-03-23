'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { tradingApi } from '@/lib/api';
import { LiveOrder } from '@/lib/types';
import {
    Loader2, Activity, ChevronLeft, ChevronRight,
    ChevronDown, ChevronRight as ChevronRightIcon, RefreshCw,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

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

export default function UpbitLogsPage() {
    const [page, setPage] = useState(0);
    const [stateFilter, setStateFilter] = useState('ALL');
    const [sideFilter, setSideFilter] = useState('ALL');
    const [openRows, setOpenRows] = useState<Set<number>>(new Set());

    const { data: res, isLoading, refetch, isFetching } = useQuery({
        queryKey: ['upbit-logs', page],
        queryFn: () => tradingApi.getOrders(page, 50),
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
                <button
                    onClick={() => refetch()}
                    disabled={isFetching}
                    className="flex items-center gap-2 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700 text-sm transition-colors disabled:opacity-50"
                >
                    <RefreshCw className={cn('w-4 h-4', isFetching && 'animate-spin')} />
                    새로고침
                </button>
            </div>

            {/* 필터 */}
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
                                <div className="flex gap-2">
                                    <button
                                        onClick={() => { setPage(p => Math.max(0, p - 1)); setOpenRows(new Set()); }}
                                        disabled={page === 0}
                                        className="p-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed"
                                    >
                                        <ChevronLeft className="w-4 h-4" />
                                    </button>
                                    <button
                                        onClick={() => { setPage(p => Math.min(totalPages - 1, p + 1)); setOpenRows(new Set()); }}
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
