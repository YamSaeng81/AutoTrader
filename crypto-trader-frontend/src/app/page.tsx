'use client';

import { useBacktests, useStrategies, useDataStatus } from '@/hooks';
import { MetricsCard } from '@/components/backtest/MetricsCard';
import { Activity, Target, TrendingUp, Database, ChevronRight } from 'lucide-react';
import Link from 'next/link';
import { format } from 'date-fns';

export default function DashboardPage() {
    const { data: backtests = [] } = useBacktests();
    const { data: strategies = [] } = useStrategies();
    const { data: summary = [] } = useDataStatus();

    const recentBacktests = backtests.slice(0, 3);
    const availableStrategies = strategies.filter(s => s.status === 'AVAILABLE');
    const dataCount = summary.length;

    const bestBt = backtests
        .filter(bt => bt.metrics != null)
        .sort((a, b) => (Number(b.metrics?.totalReturn) || 0) - (Number(a.metrics?.totalReturn) || 0))[0];

    return (
        <div className="space-y-8 animate-in fade-in duration-500">
            <div>
                <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">대시보드</h1>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">시스템 상태 및 최근 백테스트 요약을 제공합니다.</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <MetricsCard
                    title="사용 가능한 전략"
                    value={`${availableStrategies.length}개`}
                    icon={<Target className="w-5 h-5" />}
                    subtitle={`전체 ${strategies.length}개 중`}
                />
                <MetricsCard
                    title="총 백테스트"
                    value={`${backtests.length}회`}
                    icon={<Activity className="w-5 h-5" />}
                    subtitle="누적 시뮬레이션"
                />
                <MetricsCard
                    title="최고 수익률"
                    value={bestBt?.metrics ? `${Number(bestBt.metrics.totalReturn) > 0 ? '+' : ''}${Number(bestBt.metrics.totalReturn).toFixed(2)}%` : '-'}
                    icon={<TrendingUp className="w-5 h-5" />}
                    trend={bestBt?.metrics && Number(bestBt.metrics.totalReturn) > 0 ? 'up' : undefined}
                    subtitle={bestBt ? `${bestBt.strategyType} / ${bestBt.coinPair}` : '백테스트를 실행해보세요'}
                />
                <MetricsCard
                    title="수집된 데이터"
                    value={`${dataCount}종`}
                    icon={<Database className="w-5 h-5" />}
                    subtitle="코인×타임프레임 조합"
                />
            </div>

            <div className="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="p-5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between bg-slate-50/50 dark:bg-slate-800/50">
                    <h2 className="font-semibold text-slate-800 dark:text-slate-100">최근 백테스트 내역</h2>
                    <Link href="/backtest" className="text-sm font-medium text-indigo-600 hover:text-indigo-700 flex items-center gap-0.5">
                        모두 보기 <ChevronRight className="w-4 h-4" />
                    </Link>
                </div>

                {recentBacktests.length === 0 ? (
                    <div className="p-12 text-center text-slate-500 dark:text-slate-400 bg-slate-50/30 dark:bg-slate-800/30">
                        최근 기록이 없습니다.{' '}
                        <Link href="/backtest/new" className="text-indigo-600 font-semibold hover:underline">첫 백테스트 실행하기</Link>
                    </div>
                ) : (
                    <div className="divide-y divide-slate-100 dark:divide-slate-800">
                        {recentBacktests.map((bt) => (
                            <Link key={bt.id} href={`/backtest/${bt.id}`} className="flex items-center justify-between p-5 hover:bg-slate-50/80 dark:hover:bg-slate-800/50 transition-colors group">
                                <div className="flex flex-col gap-1.5">
                                    <div className="flex items-center gap-2">
                                        <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
                                        <h3 className="font-semibold text-slate-800 dark:text-slate-100 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">{bt.strategyType}</h3>
                                        <span className="px-2 py-0.5 text-[10px] font-bold tracking-wider rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 shadow-sm">{bt.coinPair}</span>
                                    </div>
                                    <div className="text-xs text-slate-500 font-medium ml-4">
                                        {bt.startDate ? format(new Date(bt.startDate), 'yyyy.MM.dd') : '-'} ~ {bt.endDate ? format(new Date(bt.endDate), 'yyyy.MM.dd') : '-'}
                                    </div>
                                </div>
                                <div className="text-right flex flex-col items-end gap-1">
                                    {bt.metrics ? (
                                        <>
                                            <div className={`font-bold text-lg tracking-tight ${Number(bt.metrics.totalReturn) > 0 ? 'text-rose-600' : 'text-blue-600'}`}>
                                                {Number(bt.metrics.totalReturn) > 0 ? '+' : ''}{Number(bt.metrics.totalReturn).toFixed(2)}%
                                            </div>
                                            <div className="text-[11px] font-medium text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-800 px-2 py-0.5 rounded-md">
                                                MDD {Number(bt.metrics.maxDrawdown).toFixed(2)}%
                                            </div>
                                        </>
                                    ) : (
                                        <span className="text-slate-400 text-sm">-</span>
                                    )}
                                </div>
                            </Link>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
}
