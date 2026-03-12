'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { backtestApi } from '@/lib/api';
import { BacktestResult } from '@/lib/types';
import { Loader2, GitCompare, Check } from 'lucide-react';
import { cn } from '@/lib/utils';
import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Cell,
} from 'recharts';

const COLORS = ['#6366f1', '#10b981', '#f59e0b', '#f43f5e', '#8b5cf6', '#06b6d4'];

export default function ComparePage() {
    const [selectedIds, setSelectedIds] = useState<string[]>([]);

    const { data: listRes, isLoading: listLoading } = useQuery({
        queryKey: ['backtests', 'list'],
        queryFn: () => backtestApi.list(),
    });

    const { data: compareRes, isLoading: compareLoading } = useQuery({
        queryKey: ['backtests', 'compare', selectedIds],
        queryFn: () => backtestApi.compare(selectedIds),
        enabled: selectedIds.length >= 2,
    });

    const allResults = (listRes?.data ?? []) as BacktestResult[];
    const compared = (compareRes?.data ?? []) as BacktestResult[];

    const toggle = (id: string) => {
        setSelectedIds(prev =>
            prev.includes(id) ? prev.filter(x => x !== id) : prev.length < 6 ? [...prev, id] : prev
        );
    };

    const chartData = compared.map((r, i) => ({
        name: `${r.strategyType} (${r.coinPair})`,
        totalReturn: r.metrics?.totalReturn ?? 0,
        color: COLORS[i % COLORS.length],
    }));

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            <div>
                <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">전략 비교 분석</h1>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">2~6개 백테스트 결과를 선택하여 성과를 비교합니다.</p>
            </div>

            {/* Selection */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex justify-between items-center">
                    <h2 className="text-sm font-bold text-slate-700 dark:text-slate-200">백테스트 선택 ({selectedIds.length}/6)</h2>
                    {selectedIds.length > 0 && (
                        <button onClick={() => setSelectedIds([])} className="text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300">
                            선택 초기화
                        </button>
                    )}
                </div>
                {listLoading ? (
                    <div className="flex items-center justify-center p-12">
                        <Loader2 className="w-6 h-6 animate-spin text-indigo-500" />
                    </div>
                ) : allResults.length === 0 ? (
                    <div className="p-12 text-center text-slate-400">
                        <GitCompare className="w-10 h-10 mx-auto mb-3 opacity-50" />
                        <p>비교할 백테스트 결과가 없습니다. 먼저 백테스트를 실행하세요.</p>
                    </div>
                ) : (
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 p-4">
                        {allResults.filter(r => r.status === 'COMPLETED').map(r => {
                            const sel = selectedIds.includes(r.id);
                            return (
                                <button
                                    key={r.id}
                                    onClick={() => toggle(r.id)}
                                    className={cn(
                                        'text-left p-3 rounded-xl border-2 transition-all text-sm',
                                        sel
                                            ? 'border-indigo-400 bg-indigo-50/50 dark:bg-indigo-900/20 shadow-sm'
                                            : 'border-slate-100 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-500 bg-white dark:bg-slate-800'
                                    )}
                                >
                                    <div className="flex justify-between items-start">
                                        <div>
                                            <div className="font-bold text-slate-700 dark:text-slate-200">{r.strategyType}</div>
                                            <div className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{r.coinPair} · {r.timeframe}</div>
                                        </div>
                                        {sel && (
                                            <div className="w-5 h-5 rounded-full bg-indigo-500 flex items-center justify-center">
                                                <Check className="w-3 h-3 text-white" />
                                            </div>
                                        )}
                                    </div>
                                    <div className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                                        수익률: <span className={cn('font-bold', (r.metrics?.totalReturn ?? 0) >= 0 ? 'text-emerald-600' : 'text-rose-600')}>
                                            {(r.metrics?.totalReturn ?? 0) >= 0 ? '+' : ''}{(r.metrics?.totalReturn ?? 0).toFixed(2)}%
                                        </span>
                                        {' · '}승률: {(r.metrics?.winRate ?? 0).toFixed(1)}%
                                    </div>
                                </button>
                            );
                        })}
                    </div>
                )}
            </div>

            {/* Comparison Results */}
            {selectedIds.length >= 2 && (
                <>
                    {compareLoading ? (
                        <div className="flex items-center justify-center p-12">
                            <Loader2 className="w-6 h-6 animate-spin text-indigo-500" />
                        </div>
                    ) : compared.length > 0 && (
                        <>
                            {/* Chart */}
                            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                                <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                                    <h2 className="text-sm font-bold text-slate-700 dark:text-slate-200">수익률 비교</h2>
                                </div>
                                <div className="p-4" style={{ height: 280 }}>
                                    <ResponsiveContainer width="100%" height="100%">
                                        <BarChart data={chartData} margin={{ top: 10, right: 20, bottom: 0, left: 0 }}>
                                            <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                                            <XAxis dataKey="name" tick={{ fontSize: 11, fill: '#94a3b8' }} />
                                            <YAxis tickFormatter={v => `${v}%`} tick={{ fontSize: 11, fill: '#94a3b8' }} />
                                            <Tooltip formatter={(v: any) => [`${Number(v).toFixed(2)}%`]} />
                                            <Bar dataKey="totalReturn" name="수익률" radius={[6, 6, 0, 0]}>
                                                {chartData.map((d, i) => (
                                                    <Cell key={i} fill={d.color} />
                                                ))}
                                            </Bar>
                                        </BarChart>
                                    </ResponsiveContainer>
                                </div>
                            </div>

                            {/* Table */}
                            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                                <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                                    <h2 className="text-sm font-bold text-slate-700 dark:text-slate-200">성과 지표 비교</h2>
                                </div>
                                <div className="overflow-x-auto">
                                    <table className="w-full text-sm">
                                        <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                                            <tr>
                                                <th className="px-5 py-3 text-left">지표</th>
                                                {compared.map((r, i) => (
                                                    <th key={r.id} className="px-5 py-3 text-right">
                                                        <span className="inline-block w-2 h-2 rounded-full mr-1.5" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                                                        {r.strategyType}
                                                    </th>
                                                ))}
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                            {[
                                                { label: '총 수익률 (%)', key: 'totalReturn', fmt: (v: number) => `${v >= 0 ? '+' : ''}${v.toFixed(2)}%` },
                                                { label: '승률 (%)', key: 'winRate', fmt: (v: number) => `${v.toFixed(1)}%` },
                                                { label: '최대 낙폭 (%)', key: 'maxDrawdown', fmt: (v: number) => `${v.toFixed(2)}%` },
                                                { label: '샤프 비율', key: 'sharpeRatio', fmt: (v: number) => v.toFixed(3) },
                                                { label: '소르티노 비율', key: 'sortinoRatio', fmt: (v: number) => v.toFixed(3) },
                                                { label: '총 거래 수', key: 'totalTrades', fmt: (v: number) => v.toLocaleString() },
                                                { label: '최대 연속 손실', key: 'maxConsecutiveLoss', fmt: (v: number) => `${v}회` },
                                            ].map(row => (
                                                <tr key={row.key} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50">
                                                    <td className="px-5 py-3 font-medium text-slate-600 dark:text-slate-300">{row.label}</td>
                                                    {compared.map(r => {
                                                        const val = (r.metrics as any)?.[row.key] ?? 0;
                                                        return (
                                                            <td key={r.id} className="px-5 py-3 text-right font-mono text-slate-700 dark:text-slate-200">
                                                                {row.fmt(val)}
                                                            </td>
                                                        );
                                                    })}
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        </>
                    )}
                </>
            )}

            {selectedIds.length > 0 && selectedIds.length < 2 && (
                <div className="text-center py-8 text-slate-400 text-sm">
                    비교하려면 최소 2개 이상 선택해 주세요.
                </div>
            )}
        </div>
    );
}
