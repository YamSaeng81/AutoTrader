'use client';

import { cn } from '@/lib/utils';

interface MonthlyReturnsHeatmapProps {
    monthlyReturns: Record<string, number>;
}

export function MonthlyReturnsHeatmap({ monthlyReturns }: MonthlyReturnsHeatmapProps) {
    if (!monthlyReturns || Object.keys(monthlyReturns).length === 0) {
        return (
            <div className="w-full h-32 bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-4 flex items-center justify-center">
                <p className="text-slate-400 dark:text-slate-500 text-sm">표시할 데이터가 없습니다.</p>
            </div>
        );
    }

    const entries = Object.entries(monthlyReturns).sort(([a], [b]) => a.localeCompare(b));

    const getColor = (val: number) => {
        if (val === 0) return 'bg-slate-50 text-slate-500';
        if (val > 0) {
            if (val >= 10) return 'bg-emerald-500 text-white shadow-sm';
            if (val >= 5) return 'bg-emerald-400 text-emerald-950 shadow-sm';
            if (val >= 2) return 'bg-emerald-200 text-emerald-800';
            return 'bg-emerald-50 text-emerald-700';
        }
        if (val <= -10) return 'bg-rose-500 text-white shadow-sm';
        if (val <= -5) return 'bg-rose-400 text-rose-950 shadow-sm';
        if (val <= -2) return 'bg-rose-200 text-rose-800';
        return 'bg-rose-50 text-rose-700';
    };

    return (
        <div className="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-5">
            <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-4 px-1">월별 수익률 히트맵</h3>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2.5">
                {entries.map(([month, val]) => (
                    <div
                        key={month}
                        className={cn(
                            "flex flex-col items-center justify-center p-3 rounded-xl border transition-all hover:scale-105",
                            getColor(val),
                            val === 0 ? "border-slate-200" : "border-transparent"
                        )}
                    >
                        <span className="text-[10px] font-medium opacity-80 mb-1 tracking-wider">{month.split('-').join('.')}</span>
                        <span className="text-sm font-bold tracking-tight">{val > 0 ? '+' : ''}{val.toFixed(1)}%</span>
                    </div>
                ))}
            </div>
        </div>
    );
}
