'use client';

import { cn } from '@/lib/utils';
import { ReactNode } from 'react';

interface MetricsCardProps {
    title: string;
    value: string | number;
    subtitle?: string;
    icon?: ReactNode;
    trend?: 'up' | 'down' | 'neutral';
    trendValue?: string;
}

export function MetricsCard({ title, value, subtitle, icon, trend, trendValue }: MetricsCardProps) {
    return (
        <div className="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-5 flex flex-col justify-between hover:shadow-md transition-shadow">
            <div className="flex justify-between items-start mb-3">
                <h3 className="text-sm font-medium text-slate-500 dark:text-slate-400">{title}</h3>
                {icon && <div className="text-slate-400 bg-slate-50 dark:bg-slate-800 p-1.5 rounded-lg">{icon}</div>}
            </div>
            <div>
                <div className="text-2xl font-bold tracking-tight text-slate-900 dark:text-slate-100">{value}</div>
                {(subtitle || trendValue) && (
                    <div className="flex items-center gap-2 mt-2">
                        {trendValue && (
                            <span className={cn(
                                "text-[11px] font-medium px-2 py-0.5 rounded-md",
                                trend === 'up' && "text-emerald-700 bg-emerald-50 border border-emerald-100",
                                trend === 'down' && "text-rose-700 bg-rose-50 border border-rose-100",
                                trend === 'neutral' && "text-slate-600 bg-slate-100 border border-slate-200"
                            )}>
                                {trend === 'up' && '↑ '}{trend === 'down' && '↓ '}{trendValue}
                            </span>
                        )}
                        {subtitle && <span className="text-xs text-slate-500 font-medium">{subtitle}</span>}
                    </div>
                )}
            </div>
        </div>
    );
}
