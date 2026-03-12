'use client';

import { Database, Download, CheckCircle2, AlertCircle, RefreshCw, Trash2 } from 'lucide-react';
import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dataApi } from '@/lib/api';
import { format } from 'date-fns';

const TIMEFRAMES = [
    { value: 'M1', label: '1분봉' },
    { value: 'M5', label: '5분봉' },
    { value: 'H1', label: '1시간봉' },
    { value: 'D1', label: '일봉' },
];

const TF_LABEL: Record<string, string> = { M1: '1분', M5: '5분', H1: '1시간', D1: '일봉' };

export default function DataCollectionPage() {
    const [coins, setCoins] = useState<string[]>([]);
    const [coinPair, setCoinPair] = useState('KRW-BTC');
    const [timeframe, setTimeframe] = useState('H1');
    const [startDate, setStartDate] = useState(`${new Date().getFullYear() - 1}-01-01`);
    const [endDate, setEndDate] = useState(new Date().toISOString().slice(0, 10));
    const [collecting, setCollecting] = useState(false);
    const [collectResult, setCollectResult] = useState<{ ok: boolean; message: string } | null>(null);
    const [pollingEnabled, setPollingEnabled] = useState(false);
    const [deleting, setDeleting] = useState<string | null>(null); // "coinPair|timeframe" 형태

    const { data: summaryRes, refetch: refetchSummary, isFetching: summaryFetching } = useQuery({
        queryKey: ['data', 'summary'],
        queryFn: () => dataApi.summary(),
        refetchInterval: pollingEnabled ? 5000 : false,
    });
    const summary = summaryRes?.data || [];

    useEffect(() => {
        dataApi.coins()
            .then(res => { if (res.success && res.data) setCoins(res.data); })
            .catch(() => setCoins(['KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE']));
    }, []);

    const handleCollect = async () => {
        if (!coinPair || !timeframe || !startDate || !endDate) return;
        if (startDate >= endDate) {
            setCollectResult({ ok: false, message: '시작일이 종료일보다 이전이어야 합니다.' });
            return;
        }

        setCollecting(true);
        setCollectResult(null);
        try {
            const res = await dataApi.collect({ coinPair, timeframe, startDate, endDate });
            if (res.success) {
                setCollectResult({ ok: true, message: `${coinPair} ${TF_LABEL[timeframe]} 수집이 시작되었습니다. 완료되면 아래 목록이 자동 갱신됩니다.` });
                setPollingEnabled(true);
                setTimeout(() => setPollingEnabled(false), 30000);
            } else {
                setCollectResult({ ok: false, message: res.error?.message ?? '수집 요청 실패' });
            }
        } catch {
            setCollectResult({ ok: false, message: '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.' });
        } finally {
            setCollecting(false);
        }
    };

    const handleDelete = async (pair: string, tf: string) => {
        if (!confirm(`${pair} ${TF_LABEL[tf] ?? tf} 데이터를 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.`)) return;
        const key = `${pair}|${tf}`;
        setDeleting(key);
        try {
            const res = await dataApi.deleteCandles(pair, tf);
            if (res.success) {
                refetchSummary();
            } else {
                alert(res.error?.message ?? '삭제 실패');
            }
        } catch {
            alert('삭제 중 오류가 발생했습니다.');
        } finally {
            setDeleting(null);
        }
    };

    return (
        <div className="space-y-8 animate-in fade-in duration-500 max-w-3xl mx-auto py-6">
            <div className="text-center">
                <h1 className="text-3xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">데이터 수집 관리</h1>
                <p className="text-slate-500 dark:text-slate-400 mt-3 font-medium">백테스트에 필요한 과거 가격 데이터를 수집합니다.</p>
            </div>

            {/* 수집 설정 폼 */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 p-8 space-y-6">
                <div className="flex items-center gap-3 mb-2">
                    <div className="w-10 h-10 bg-blue-50 dark:bg-blue-900/30 text-blue-500 rounded-xl flex items-center justify-center">
                        <Database className="w-5 h-5" />
                    </div>
                    <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">수집 설정</h2>
                </div>

                <div className="space-y-1.5">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">코인 페어</label>
                    <select
                        value={coinPair}
                        onChange={e => setCoinPair(e.target.value)}
                        className="w-full border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 text-sm bg-slate-50 dark:bg-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    >
                        {coins.length > 0 ? coins.map(c => (
                            <option key={c} value={c}>{c}</option>
                        )) : <option value="KRW-BTC">KRW-BTC</option>}
                    </select>
                </div>

                <div className="space-y-1.5">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">타임프레임</label>
                    <div className="grid grid-cols-4 gap-2">
                        {TIMEFRAMES.map(tf => (
                            <button
                                key={tf.value}
                                onClick={() => setTimeframe(tf.value)}
                                className={`py-2 rounded-xl text-sm font-semibold border transition-all ${
                                    timeframe === tf.value
                                        ? 'bg-slate-900 dark:bg-indigo-600 text-white border-slate-900 dark:border-indigo-600'
                                        : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-slate-400'
                                }`}
                            >
                                {tf.label}
                            </button>
                        ))}
                    </div>
                </div>

                <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">시작일</label>
                        <input type="date" value={startDate} max={endDate}
                            onChange={e => setStartDate(e.target.value)}
                            className="w-full border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 text-sm bg-slate-50 dark:bg-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500" />
                    </div>
                    <div className="space-y-1.5">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">종료일</label>
                        <input type="date" value={endDate} min={startDate} max={new Date().toISOString().slice(0, 10)}
                            onChange={e => setEndDate(e.target.value)}
                            className="w-full border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 text-sm bg-slate-50 dark:bg-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500" />
                    </div>
                </div>

                {collectResult && (
                    <div className={`flex items-start gap-2.5 px-4 py-3 rounded-xl text-sm font-medium border ${
                        collectResult.ok ? 'text-emerald-700 bg-emerald-50 border-emerald-100' : 'text-red-700 bg-red-50 border-red-100'
                    }`}>
                        {collectResult.ok ? <CheckCircle2 className="w-4 h-4 mt-0.5 shrink-0" /> : <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />}
                        {collectResult.message}
                    </div>
                )}

                {pollingEnabled && (
                    <div className="flex items-center gap-2 text-sm text-blue-600 font-medium">
                        <div className="w-4 h-4 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
                        수집 진행 중... 완료 시 아래 목록이 자동으로 갱신됩니다.
                    </div>
                )}

                <button onClick={handleCollect} disabled={collecting}
                    className="w-full flex items-center justify-center gap-3 bg-slate-900 hover:bg-slate-800 text-white font-semibold py-3.5 rounded-xl shadow-lg transition-all active:scale-[0.98] disabled:opacity-70">
                    {collecting
                        ? <><div className="w-5 h-5 border-2 border-white border-b-transparent rounded-full animate-spin" /> 수집 요청 중...</>
                        : <><Download className="w-5 h-5" /> 데이터 수집 시작</>
                    }
                </button>
            </div>

            {/* 수집된 데이터 현황 */}
            <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50 flex items-center justify-between">
                    <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">수집된 데이터 현황</h2>
                    <button onClick={() => refetchSummary()} disabled={summaryFetching}
                        className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-indigo-600 font-medium transition-colors">
                        <RefreshCw className={`w-4 h-4 ${summaryFetching ? 'animate-spin' : ''}`} />
                        새로고침
                    </button>
                </div>

                {summary.length === 0 ? (
                    <div className="px-6 py-10 text-center text-slate-400">
                        아직 수집된 데이터가 없습니다. 위에서 수집을 시작해보세요.
                    </div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm text-left">
                            <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                                <tr>
                                    <th className="px-6 py-3">코인</th>
                                    <th className="px-6 py-3">타임프레임</th>
                                    <th className="px-6 py-3">시작일</th>
                                    <th className="px-6 py-3">종료일</th>
                                    <th className="px-6 py-3 text-right">캔들 수</th>
                                    <th className="px-6 py-3 text-center">삭제</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {summary.map((row, i) => {
                                    const key = `${row.coinPair}|${row.timeframe}`;
                                    const isDeleting = deleting === key;
                                    return (
                                        <tr key={i} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50 transition-colors">
                                            <td className="px-6 py-3 font-bold text-slate-800 dark:text-slate-100">{row.coinPair}</td>
                                            <td className="px-6 py-3">
                                                <span className="px-2 py-0.5 text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100 rounded">
                                                    {TF_LABEL[row.timeframe] ?? row.timeframe}
                                                </span>
                                            </td>
                                            <td className="px-6 py-3 text-slate-600 dark:text-slate-400">{row.from ? format(new Date(row.from), 'yyyy.MM.dd') : '-'}</td>
                                            <td className="px-6 py-3 text-slate-600 dark:text-slate-400">{row.to ? format(new Date(row.to), 'yyyy.MM.dd') : '-'}</td>
                                            <td className="px-6 py-3 text-right font-semibold text-slate-700 dark:text-slate-200">{Number(row.count).toLocaleString()}건</td>
                                            <td className="px-6 py-3 text-center">
                                                <button
                                                    onClick={() => handleDelete(row.coinPair, row.timeframe)}
                                                    disabled={isDeleting}
                                                    title={`${row.coinPair} ${TF_LABEL[row.timeframe]} 데이터 삭제`}
                                                    className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 hover:bg-red-100 dark:hover:bg-red-900/40 border border-red-100 dark:border-red-800 rounded-lg transition-colors disabled:opacity-50"
                                                >
                                                    {isDeleting
                                                        ? <div className="w-3 h-3 border border-red-400 border-t-transparent rounded-full animate-spin" />
                                                        : <Trash2 className="w-3 h-3" />
                                                    }
                                                    삭제
                                                </button>
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
}
