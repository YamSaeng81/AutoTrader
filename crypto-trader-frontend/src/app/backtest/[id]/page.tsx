'use client';

import { use } from 'react';
import { useBacktestDetail, useBacktestTrades } from '@/hooks';
import { MetricsCard } from '@/components/backtest/MetricsCard';
import { CumulativePnlChart } from '@/components/charts/CumulativePnlChart';
import { MonthlyReturnsHeatmap } from '@/components/charts/MonthlyReturnsHeatmap';
import { TradesTable } from '@/components/backtest/TradesTable';
import { ArrowLeft, RefreshCw, Clock, Calendar, Target, TrendingUp, AlertTriangle } from 'lucide-react';
import Link from 'next/link';
import { format } from 'date-fns';

export default function BacktestDetailPage({ params }: { params: Promise<{ id: string }> }) {
    const { id } = use(params);

    const { data: result, isLoading: resLoading } = useBacktestDetail(id);
    const { data: tradesPage, isLoading: tradesLoading } = useBacktestTrades(id, 0);

    if (resLoading) {
        return (
            <div className="flex h-[80vh] items-center justify-center">
                <div className="flex flex-col items-center gap-5 text-slate-500">
                    <div className="w-10 h-10 border-4 border-indigo-500 border-t-transparent rounded-full animate-spin"></div>
                    <p className="font-semibold text-lg tracking-tight">결과 데이터를 불러오는 중...</p>
                </div>
            </div>
        );
    }

    const trades = tradesPage?.content || [];

    if (!result) {
        return (
            <div className="text-center py-24 bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700">
                <AlertTriangle className="w-16 h-16 text-rose-500 mx-auto mb-5" />
                <h2 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">백테스트 결과를 찾을 수 없습니다</h2>
                <p className="text-slate-500 dark:text-slate-400 mt-2">삭제되었거나 유효하지 않은 ID입니다.</p>
                <Link href="/backtest" className="mt-8 inline-block font-semibold bg-indigo-600 text-white px-8 py-3 rounded-xl shadow-md hover:bg-indigo-700 hover:-translate-y-0.5 transition-all">목록으로 돌아가기</Link>
            </div>
        );
    }

    return (
        <div className="space-y-8 animate-in fade-in duration-500 pb-12">
            {/* Header */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-4">
                    <Link href="/backtest" className="p-2.5 -ml-2 text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 dark:hover:bg-indigo-900/20 hover:shadow-sm rounded-xl transition-all">
                        <ArrowLeft className="w-6 h-6" />
                    </Link>
                    <div>
                        <div className="flex items-center gap-3">
                            <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">{result.strategyType}</h1>
                            <span className={`px-3 py-1 text-[11px] font-bold tracking-widest uppercase rounded-md border ${result.status === 'COMPLETED' ? 'border-emerald-200 bg-emerald-50 text-emerald-700' :
                                    result.status === 'RUNNING' ? 'border-blue-200 bg-blue-50 text-blue-700' : 'border-rose-200 bg-rose-50 text-rose-700'
                                }`}>
                                {result.status}
                            </span>
                        </div>
                        <p className="text-sm font-medium text-slate-500 dark:text-slate-400 mt-2 flex flex-wrap items-center gap-3">
                            <span className="flex items-center gap-1.5"><Target className="w-3.5 h-3.5" /> {result.coinPair}</span>
                            <span className="w-1.5 h-1.5 rounded-full bg-slate-300"></span>
                            <span className="flex items-center gap-1.5"><Clock className="w-3.5 h-3.5" /> {result.timeframe}</span>
                            <span className="w-1.5 h-1.5 rounded-full bg-slate-300"></span>
                            <span className="flex items-center gap-1.5"><Calendar className="w-3.5 h-3.5" /> {format(new Date(result.startDate), 'yyyy.MM.dd')} ~ {format(new Date(result.endDate), 'yyyy.MM.dd')}</span>
                            <span className="w-1.5 h-1.5 rounded-full bg-slate-300"></span>
                            {result.initialCapital != null && <span className="font-semibold text-indigo-900 bg-indigo-50 px-2 py-0.5 rounded-md border border-indigo-100">초기 {result.initialCapital.toLocaleString()}원</span>}
                        </p>
                    </div>
                </div>
                <div className="flex gap-2">
                    <button className="p-2.5 text-slate-500 dark:text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 dark:hover:bg-indigo-900/20 rounded-xl transition-all border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:shadow-sm shadow-sm" title="새고로침">
                        <RefreshCw className="w-5 h-5" />
                    </button>
                </div>
            </div>

            {/* Metrics Row */}
            {result.metrics ? (
                <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
                    <MetricsCard title="총 수익률" value={`${result.metrics.totalReturn > 0 ? '+' : ''}${result.metrics.totalReturn.toFixed(2)}%`} trend={result.metrics.totalReturn > 0 ? 'up' : 'down'} icon={<TrendingUp className="w-4 h-4" />} />
                    <MetricsCard title="승률" value={`${result.metrics.winRate.toFixed(1)}%`} subtitle={`총 ${result.metrics.totalTrades}회 거래`} />
                    <MetricsCard title="MDD" value={`${result.metrics.maxDrawdown.toFixed(2)}%`} trend="down" subtitle={`최대연속손실 ${result.metrics.maxConsecutiveLoss}회`} />
                    <MetricsCard title="Sharpe Ratio" value={result.metrics.sharpeRatio.toFixed(2)} />
                    <MetricsCard title="Sortino Ratio" value={result.metrics.sortinoRatio.toFixed(2)} />
                    <MetricsCard title="손익비" value={result.metrics.winLossRatio.toFixed(2)} />
                </div>
            ) : (
                <div className="bg-amber-50 border border-amber-200 rounded-xl px-5 py-4 text-sm text-amber-700 font-medium flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 shrink-0" />
                    {result.status === 'RUNNING' ? '백테스트가 아직 진행 중입니다. 잠시 후 새로고침 하세요.' : '결과 지표를 계산할 수 없습니다.'}
                </div>
            )}

            {/* Charts */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                <div className="lg:col-span-2 flex flex-col min-h-0 h-[400px]">
                    {tradesLoading ? (
                        <div className="h-full bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 flex items-center justify-center text-slate-400 dark:text-slate-500">
                            <div className="flex flex-col items-center gap-3">
                                <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin"></div>
                                차트 데이터 로딩...
                            </div>
                        </div>
                    ) : (
                        <CumulativePnlChart data={trades} />
                    )}
                </div>
                <div className="lg:col-span-1 h-full">
                    <MonthlyReturnsHeatmap monthlyReturns={result.metrics?.monthlyReturns ?? {}} />
                </div>
            </div>

            {/* Trades Table */}
            <div>
                <TradesTable trades={trades} />
            </div>
        </div>
    );
}
