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

    return (
        <div className="w-full h-80 bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-5 flex flex-col">
            <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-4 px-1">누적 수익 곡선</h3>
            <div className="flex-1 w-full min-h-0">
                <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={chartData} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
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
                </ResponsiveContainer>
            </div>
        </div>
    );
}
