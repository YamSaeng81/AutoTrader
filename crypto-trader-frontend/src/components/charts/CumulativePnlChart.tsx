'use client';

import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { TradeRecord } from '@/lib/types';
import { format } from 'date-fns';

interface CumulativePnlChartProps {
    data: TradeRecord[];
}

export function CumulativePnlChart({ data }: CumulativePnlChartProps) {
    if (!data || data.length === 0) {
        return (
            <div className="w-full h-72 bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-4 flex items-center justify-center">
                <p className="text-slate-400 dark:text-slate-500 text-sm">표시할 데이터가 없습니다.</p>
            </div>
        );
    }

    const chartData = data.map((trade) => ({
        date: new Date(trade.executedAt).getTime(),
        pnl: trade.cumulativePnl,
    }));

    const CHART_HEIGHT = 240;
    const PX_PER_POINT = 12;
    const SCROLL_THRESHOLD = 80;
    const MAX_WIDTH = 4000;
    const needsScroll = chartData.length > SCROLL_THRESHOLD;
    const fixedWidth = needsScroll
        ? Math.min(MAX_WIDTH, Math.max(800, chartData.length * PX_PER_POINT))
        : undefined;

    const chartInner = (w?: number) => (
        <LineChart
            width={w}
            height={CHART_HEIGHT}
            data={chartData}
            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
        >
            <CartesianGrid strokeDasharray="4 4" stroke="#f1f5f9" vertical={false} />
            <XAxis
                dataKey="date"
                type="number"
                domain={['dataMin', 'dataMax']}
                tickFormatter={(val) => format(val, 'MM/dd HH:mm')}
                stroke="#64748b"
                fontSize={11}
                tickLine={false}
                axisLine={false}
                dy={10}
                minTickGap={30}
            />
            <YAxis
                stroke="#64748b"
                fontSize={11}
                tickLine={false}
                axisLine={false}
                tickFormatter={(val) => `${(val / 10000).toFixed(0)}만`}
                dx={-10}
            />
            <Tooltip
                labelFormatter={(val) => format(val as number, 'yyyy.MM.dd HH:mm:ss')}
                formatter={(val: any) => [`${Number(val).toLocaleString()}원`, '누적 수익']}
                contentStyle={{ borderRadius: '12px', border: '1px solid #e2e8f0', boxShadow: '0 10px 15px -3px rgb(0 0 0 / 0.1)', fontSize: '12px' }}
            />
            <Line
                type="stepAfter"
                dataKey="pnl"
                stroke="#4f46e5"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 5, fill: '#4f46e5', stroke: '#fff', strokeWidth: 2 }}
            />
        </LineChart>
    );

    return (
        <div className="w-full bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-5">
            <div className="flex items-center justify-between mb-4 px-1">
                <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">누적 수익 곡선</h3>
                {needsScroll && (
                    <span className="text-xs text-slate-400">← 좌우 스크롤 →</span>
                )}
            </div>
            {needsScroll ? (
                <div className="overflow-x-auto">
                    <div style={{ width: fixedWidth }}>
                        {chartInner(fixedWidth)}
                    </div>
                </div>
            ) : (
                <div style={{ height: CHART_HEIGHT }}>
                    <ResponsiveContainer width="100%" height="100%">
                        {chartInner() as any}
                    </ResponsiveContainer>
                </div>
            )}
        </div>
    );
}
