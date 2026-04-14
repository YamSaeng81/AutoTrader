'use client';

import { TradeRecord } from '@/lib/types';
import { cn, parseUtc } from '@/lib/utils';
import { format } from 'date-fns';

interface TradesTableProps {
    trades: TradeRecord[];
}

export function TradesTable({ trades }: TradesTableProps) {
    if (!trades || trades.length === 0) {
        return (
            <div className="w-full bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 p-8 text-center text-slate-500 dark:text-slate-400">
                <p className="font-medium">매매 기록이 없습니다.</p>
                <p className="text-sm mt-1 text-slate-400 dark:text-slate-500">백테스트 기간 동안 발생한 매매 신호가 없습니다.</p>
            </div>
        );
    }

    return (
        <div className="w-full bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
            <div className="p-5 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between bg-slate-50/50 dark:bg-slate-800/50">
                <h3 className="font-semibold text-slate-800 dark:text-slate-100">상세 매매 기록</h3>
                <span className="text-xs font-medium text-slate-500 dark:text-slate-400 bg-white dark:bg-slate-700 px-2.5 py-1 rounded-md border border-slate-200 dark:border-slate-600 shadow-sm">{trades.length}건의 거래</span>
            </div>
            <div className="overflow-x-auto">
                <table className="w-full text-sm text-left">
                    <thead className="bg-slate-50/80 dark:bg-slate-800 text-slate-500 dark:text-slate-400 font-medium border-b border-slate-200 dark:border-slate-700">
                        <tr>
                            <th className="px-5 py-3.5 whitespace-nowrap">시간</th>
                            <th className="px-5 py-3.5 whitespace-nowrap">포지션</th>
                            <th className="px-5 py-3.5 whitespace-nowrap text-right">체결가(원)</th>
                            <th className="px-5 py-3.5 whitespace-nowrap text-right">수량</th>
                            <th className="px-5 py-3.5 whitespace-nowrap text-right">수익금(원)</th>
                            <th className="px-5 py-3.5 whitespace-nowrap text-right">수익률</th>
                            <th className="px-5 py-3.5 whitespace-nowrap text-right">누적수익(원)</th>
                            <th className="px-5 py-3.5 whitespace-nowrap">신호 / 상태</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                        {trades.map((trade, i) => (
                            <tr key={i} className="hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors group">
                                <td className="px-5 py-4 text-slate-600 dark:text-slate-300 font-medium">
                                    {format(parseUtc(trade.executedAt)!, 'yyyy.MM.dd HH:mm')}
                                </td>
                                <td className="px-5 py-4">
                                    <span className={cn(
                                        "px-2.5 py-1 rounded-md text-[11px] font-bold tracking-wide",
                                        trade.side === 'BUY'
                                            ? "bg-rose-50 text-rose-600 border border-rose-100/50"
                                            : "bg-blue-50 text-blue-600 border border-blue-100/50"
                                    )}>
                                        {trade.side === 'BUY' ? '매수' : '매도'}
                                    </span>
                                </td>
                                <td className="px-5 py-4 text-right font-semibold text-slate-700 dark:text-slate-200">
                                    {trade.price.toLocaleString()}
                                </td>
                                <td className="px-5 py-4 text-right text-slate-500 dark:text-slate-400">
                                    {trade.quantity}
                                </td>
                                <td className="px-5 py-4 text-right">
                                    <span className={cn(
                                        "font-bold",
                                        trade.pnl > 0 ? "text-rose-600" : trade.pnl < 0 ? "text-blue-600" : "text-slate-400"
                                    )}>
                                        {trade.pnl > 0 ? '+' : ''}{trade.pnl.toLocaleString()}
                                    </span>
                                </td>
                                <td className="px-5 py-4 text-right">
                                    {trade.side === 'SELL' && trade.pnl !== 0 ? (() => {
                                        const cost = trade.price * trade.quantity - trade.pnl;
                                        const pct = cost !== 0 ? (trade.pnl / cost) * 100 : 0;
                                        return (
                                            <span className={cn("font-bold text-xs", pct > 0 ? "text-rose-600" : pct < 0 ? "text-blue-600" : "text-slate-400")}>
                                                {pct > 0 ? '+' : ''}{pct.toFixed(2)}%
                                            </span>
                                        );
                                    })() : <span className="text-slate-300 dark:text-slate-600">—</span>}
                                </td>
                                <td className="px-5 py-4 text-right font-semibold text-slate-900 dark:text-slate-100">
                                    {trade.cumulativePnl.toLocaleString()}
                                </td>
                                <td className="px-5 py-4 text-slate-500 dark:text-slate-400 max-w-[220px]">
                                    <div className="flex flex-col gap-0.5">
                                        <span className="text-[10px] uppercase font-bold text-slate-400 dark:text-slate-500 tracking-wider hidden group-hover:block transition-all">{trade.marketRegime}</span>
                                        <span className="text-xs font-medium text-slate-600 dark:text-slate-300 line-clamp-2" title={trade.signalReason}>{trade.signalReason}</span>
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
