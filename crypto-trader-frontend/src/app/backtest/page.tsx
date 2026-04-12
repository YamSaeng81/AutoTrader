'use client';

import { useState, useMemo } from 'react';
import { useBacktests, useDeleteBacktest, useBulkDeleteBacktests } from '@/hooks';
import Link from 'next/link';
import { Plus, Trash2, ChevronUp, ChevronDown, ChevronsUpDown } from 'lucide-react';
import { format } from 'date-fns';

type SortKey = 'createdAt' | 'totalReturn' | 'winRate' | 'maxDrawdown';
type SortDir = 'asc' | 'desc';

function SortIcon({ col, sortKey, sortDir }: { col: SortKey; sortKey: SortKey; sortDir: SortDir }) {
    if (col !== sortKey) return <ChevronsUpDown className="w-3 h-3 opacity-30 inline ml-0.5" />;
    return sortDir === 'asc'
        ? <ChevronUp className="w-3 h-3 inline ml-0.5" />
        : <ChevronDown className="w-3 h-3 inline ml-0.5" />;
}

export default function BacktestListPage() {
    const { data: backtests = [], isLoading } = useBacktests();
    const deleteBacktest = useDeleteBacktest();
    const bulkDeleteBacktests = useBulkDeleteBacktests();

    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [filterStrategy, setFilterStrategy] = useState('');
    const [filterCoin, setFilterCoin] = useState('');
    const [sortKey, setSortKey] = useState<SortKey>('createdAt');
    const [sortDir, setSortDir] = useState<SortDir>('desc');

    const strategies = useMemo(() => [...new Set(backtests.map(b => b.strategyType))].sort(), [backtests]);
    const coins = useMemo(() => [...new Set(backtests.map(b => b.coinPair))].sort(), [backtests]);

    const filtered = useMemo(() => {
        let list = backtests;
        if (filterStrategy) list = list.filter(b => b.strategyType === filterStrategy);
        if (filterCoin) list = list.filter(b => b.coinPair === filterCoin);
        return [...list].sort((a, b) => {
            let av: number, bv: number;
            if (sortKey === 'createdAt') {
                av = new Date(a.createdAt).getTime();
                bv = new Date(b.createdAt).getTime();
            } else {
                av = Number(a.metrics?.[sortKey] ?? 0);
                bv = Number(b.metrics?.[sortKey] ?? 0);
            }
            return sortDir === 'asc' ? av - bv : bv - av;
        });
    }, [backtests, filterStrategy, filterCoin, sortKey, sortDir]);

    function toggleSort(key: SortKey) {
        if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
        else { setSortKey(key); setSortDir('desc'); }
    }

    const allIds = filtered.map((bt) => bt.id);
    const isAllSelected = allIds.length > 0 && allIds.every((id) => selectedIds.has(id));
    const isSomeSelected = selectedIds.size > 0;

    function toggleSelectAll() {
        if (isAllSelected) {
            setSelectedIds(new Set());
        } else {
            setSelectedIds(new Set(allIds));
        }
    }

    function toggleSelect(id: string) {
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

    function handleDelete(id: string) {
        if (!confirm('이 백테스트를 삭제하시겠습니까?')) return;
        deleteBacktest.mutate(id, {
            onError: () => alert('삭제 중 오류가 발생했습니다. 다시 시도해주세요.'),
        });
    }

    function handleBulkDelete() {
        if (selectedIds.size === 0) return;
        if (!confirm(`선택한 ${selectedIds.size}개의 백테스트를 삭제하시겠습니까?`)) return;
        bulkDeleteBacktests.mutate([...selectedIds], {
            onSuccess: () => setSelectedIds(new Set()),
            onError: () => alert('일괄 삭제 중 오류가 발생했습니다. 다시 시도해주세요.'),
        });
    }

    const isMutating = deleteBacktest.isPending || bulkDeleteBacktests.isPending;

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">백테스트 이력</h1>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">과거 시뮬레이션 결과 목록입니다.</p>
                </div>
                <div className="flex items-center gap-3">
                    {isSomeSelected && (
                        <button
                            onClick={handleBulkDelete}
                            disabled={isMutating}
                            className="flex items-center gap-2 bg-red-600 hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed text-white font-semibold py-2.5 px-4 rounded-xl shadow-sm hover:shadow-md transition-all active:scale-[0.98] text-sm"
                        >
                            <Trash2 className="w-4 h-4" />
                            선택 삭제 ({selectedIds.size})
                        </button>
                    )}
                    <Link
                        href="/backtest/new"
                        className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-2.5 px-5 rounded-xl shadow-sm hover:shadow-md transition-all active:scale-[0.98]"
                    >
                        <Plus className="w-4 h-4" />
                        새 백테스트
                    </Link>
                </div>
            </div>

            {/* 필터 바 */}
            {!isLoading && backtests.length > 0 && (
                <div className="flex flex-wrap items-center gap-3">
                    <select
                        value={filterStrategy}
                        onChange={e => setFilterStrategy(e.target.value)}
                        className="px-3 py-2 text-sm bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                    >
                        <option value="">전체 전략</option>
                        {strategies.map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                    <select
                        value={filterCoin}
                        onChange={e => setFilterCoin(e.target.value)}
                        className="px-3 py-2 text-sm bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                    >
                        <option value="">전체 코인</option>
                        {coins.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                    {(filterStrategy || filterCoin) && (
                        <button
                            onClick={() => { setFilterStrategy(''); setFilterCoin(''); }}
                            className="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
                        >
                            필터 초기화
                        </button>
                    )}
                    <span className="ml-auto text-xs text-slate-400 dark:text-slate-500">
                        {filtered.length} / {backtests.length}건
                    </span>
                </div>
            )}

            <div className="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                {isLoading ? (
                    <div className="p-12 text-center text-slate-500 dark:text-slate-400 flex flex-col items-center gap-3">
                        <div className="w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin"></div>
                        <span>목록을 불러오는 중...</span>
                    </div>
                ) : backtests.length === 0 ? (
                    <div className="p-12 text-center text-slate-500 dark:text-slate-400 bg-slate-50/30 dark:bg-slate-800/30">
                        기록이 없습니다. 첫 백테스트를 실행해보세요.
                    </div>
                ) : filtered.length === 0 ? (
                    <div className="p-12 text-center text-slate-500 dark:text-slate-400 bg-slate-50/30 dark:bg-slate-800/30">
                        필터 조건에 맞는 이력이 없습니다.
                    </div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm text-left">
                            <thead className="bg-slate-50/80 dark:bg-slate-800/80 text-slate-500 dark:text-slate-400 font-semibold border-b border-slate-200 dark:border-slate-700 uppercase text-xs tracking-wider">
                                <tr>
                                    <th className="px-4 py-4">
                                        <input
                                            type="checkbox"
                                            checked={isAllSelected}
                                            onChange={toggleSelectAll}
                                            className="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-indigo-600 cursor-pointer"
                                            aria-label="전체 선택"
                                        />
                                    </th>
                                    <th className="px-6 py-4">상태</th>
                                    <th className="px-6 py-4">전략 유형</th>
                                    <th className="px-6 py-4">페어 / 타임프레임</th>
                                    <th className="px-6 py-4 text-right cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200" onClick={() => toggleSort('totalReturn')}>
                                        수익률 <SortIcon col="totalReturn" sortKey={sortKey} sortDir={sortDir} />
                                    </th>
                                    <th className="px-6 py-4 text-right cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200" onClick={() => toggleSort('winRate')}>
                                        승률 <SortIcon col="winRate" sortKey={sortKey} sortDir={sortDir} />
                                    </th>
                                    <th className="px-6 py-4 text-right cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200" onClick={() => toggleSort('maxDrawdown')}>
                                        MDD <SortIcon col="maxDrawdown" sortKey={sortKey} sortDir={sortDir} />
                                    </th>
                                    <th className="px-6 py-4 cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200" onClick={() => toggleSort('createdAt')}>
                                        실행 일시 <SortIcon col="createdAt" sortKey={sortKey} sortDir={sortDir} />
                                    </th>
                                    <th className="px-4 py-4"></th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {filtered.map((bt) => (
                                    <tr
                                        key={bt.id}
                                        className="hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors group"
                                    >
                                        <td className="px-4 py-4">
                                            <input
                                                type="checkbox"
                                                checked={selectedIds.has(bt.id)}
                                                onChange={() => toggleSelect(bt.id)}
                                                className="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-indigo-600 cursor-pointer"
                                                aria-label={`${bt.strategyType} 선택`}
                                            />
                                        </td>
                                        <td className="px-6 py-4">
                                            <span className="px-2.5 py-1 text-[10px] font-bold tracking-widest uppercase rounded-full border border-emerald-200 bg-emerald-50 text-emerald-700">
                                                {bt.status}
                                            </span>
                                        </td>
                                        <td className="px-6 py-4 font-bold text-slate-800 dark:text-slate-100">
                                            <Link href={`/backtest/${bt.id}`} className="hover:text-indigo-600 transition-colors flex items-center gap-1.5">
                                                {bt.strategyType}
                                            </Link>
                                        </td>
                                        <td className="px-6 py-4">
                                            <div className="font-semibold text-slate-700 dark:text-slate-200">{bt.coinPair}</div>
                                            <div className="text-[11px] font-medium text-slate-500 dark:text-slate-400 mt-1 px-1.5 py-0.5 bg-slate-100 dark:bg-slate-800 rounded inline-block">
                                                {bt.timeframe}
                                            </div>
                                        </td>
                                        <td className="px-6 py-4 text-right">
                                            {bt.metrics ? (
                                                <span className={`font-bold text-base tracking-tight ${bt.metrics.totalReturn > 0 ? "text-rose-600" : "text-blue-600"}`}>
                                                    {bt.metrics.totalReturn > 0 ? '+' : ''}{Number(bt.metrics.totalReturn).toFixed(2)}%
                                                </span>
                                            ) : <span className="text-slate-400 text-sm">-</span>}
                                        </td>
                                        <td className="px-6 py-4 text-right text-slate-600 font-semibold">
                                            {bt.metrics ? `${Number(bt.metrics.winRate).toFixed(1)}%` : '-'}
                                        </td>
                                        <td className="px-6 py-4 text-right text-slate-600 font-semibold">
                                            {bt.metrics ? `${Number(bt.metrics.maxDrawdown).toFixed(2)}%` : '-'}
                                        </td>
                                        <td className="px-6 py-4 font-medium text-slate-500 text-[13px]">
                                            {format(new Date(bt.createdAt), 'yyyy.MM.dd HH:mm')}
                                        </td>
                                        <td className="px-4 py-4">
                                            <button
                                                onClick={() => handleDelete(bt.id)}
                                                disabled={isMutating}
                                                title="삭제"
                                                className="p-1.5 rounded-lg text-slate-400 hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 disabled:opacity-40 disabled:cursor-not-allowed transition-colors opacity-0 group-hover:opacity-100"
                                            >
                                                <Trash2 className="w-4 h-4" />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
}
