'use client';

import { Database, Download, CheckCircle2, AlertCircle, RefreshCw, Trash2, ChevronDown, ChevronUp } from 'lucide-react';
import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dataApi } from '@/lib/api';
import { format } from 'date-fns';

const TIMEFRAMES = [
    { value: 'M5',  label: '5분봉' },
    { value: 'M15', label: '15분봉' },
    { value: 'M30', label: '30분봉' },
    { value: 'H1',  label: '1시간봉' },
    { value: 'H4',  label: '4시간봉' },
    { value: 'D1',  label: '일봉' },
];

const TF_LABEL: Record<string, string> = {
    M1: '1분', M5: '5분', M15: '15분', M30: '30분', H1: '1시간', H4: '4시간', D1: '일봉',
};

const THREE_YEARS_AGO = (() => {
    const d = new Date();
    d.setFullYear(d.getFullYear() - 3);
    return d.toISOString().slice(0, 10);
})();

interface MarketInfo { market: string; koreanName: string; englishName: string; }

function coinLabel(info: MarketInfo): string {
    const code = info.market.replace('KRW-', '');
    return info.koreanName ? `${code} · ${info.koreanName}` : code;
}

export default function DataCollectionPage() {
    const [allMarkets, setAllMarkets] = useState<MarketInfo[]>([]);
    const [selectedCoins, setSelectedCoins] = useState<Set<string>>(new Set());
    const [coinListOpen, setCoinListOpen] = useState(false);
    const [timeframe, setTimeframe] = useState('H1');
    const [startDate, setStartDate] = useState(THREE_YEARS_AGO);
    const [endDate, setEndDate] = useState(new Date().toISOString().slice(0, 10));
    const [collecting, setCollecting] = useState(false);
    const [collectResult, setCollectResult] = useState<{ ok: boolean; message: string } | null>(null);
    const [pollingEnabled, setPollingEnabled] = useState(false);
    const [deleting, setDeleting] = useState<string | null>(null);

    const { data: summaryRes, refetch: refetchSummary, isFetching: summaryFetching } = useQuery({
        queryKey: ['data', 'summary'],
        queryFn: () => dataApi.summary(),
        refetchInterval: pollingEnabled ? 5000 : false,
    });
    const summary = summaryRes?.data || [];

    useEffect(() => {
        dataApi.markets()
            .then(res => {
                if (res.success && res.data && res.data.length > 0) {
                    setAllMarkets(res.data);
                } else {
                    throw new Error('empty');
                }
            })
            .catch(() => setAllMarkets([
                'KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE',
                'KRW-ADA', 'KRW-AVAX', 'KRW-DOT', 'KRW-LINK', 'KRW-MATIC',
            ].map(m => ({ market: m, koreanName: '', englishName: '' }))));
    }, []);

    const toggleCoin = (coin: string) => {
        setSelectedCoins(prev => {
            const next = new Set(prev);
            if (next.has(coin)) next.delete(coin); else next.add(coin);
            return next;
        });
    };

    const selectAll = () => setSelectedCoins(new Set(allMarkets.map(m => m.market)));
    const clearAll  = () => setSelectedCoins(new Set());

    const estimatedMinutes = (() => {
        const n = selectedCoins.size;
        if (n === 0) return null;
        const tfMultiplier: Record<string, number> = {
            M5: 12, M15: 4, M30: 2, H1: 1, H4: 0.25, D1: 0.04,
        };
        const days = Math.round((new Date(endDate).getTime() - new Date(startDate).getTime()) / 86400000);
        const candlesPerCoin = days * 24 * (tfMultiplier[timeframe] ?? 1);
        const apiCallsPerCoin = Math.ceil(candlesPerCoin / 200);
        const secsPerCoin = apiCallsPerCoin * 0.11 + 2; // 110ms/call + 2s 딜레이
        return Math.ceil(n * secsPerCoin / 60);
    })();

    const handleCollect = async () => {
        const coins = Array.from(selectedCoins);
        if (coins.length === 0) { setCollectResult({ ok: false, message: '코인을 1개 이상 선택해주세요.' }); return; }
        if (!timeframe || !startDate || !endDate || startDate >= endDate) {
            setCollectResult({ ok: false, message: '기간 설정을 확인해주세요.' }); return;
        }

        setCollecting(true);
        setCollectResult(null);
        try {
            const res = await dataApi.batchCollect({ coinPairs: coins, timeframe, startDate, endDate });
            if (res.success) {
                setCollectResult({
                    ok: true,
                    message: `${coins.length}개 코인 배치 수집이 시작되었습니다. 완료 시 텔레그램으로 결과가 전송됩니다.`,
                });
                setPollingEnabled(true);
                setTimeout(() => setPollingEnabled(false), 120000); // 2분 폴링
            } else {
                setCollectResult({ ok: false, message: res.error?.message ?? '수집 요청 실패' });
            }
        } catch {
            setCollectResult({ ok: false, message: '서버에 연결할 수 없습니다.' });
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
            if (res.success) refetchSummary();
            else alert(res.error?.message ?? '삭제 실패');
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

                {/* 코인 멀티 선택 */}
                <div className="space-y-2">
                    <div className="flex items-center justify-between">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                            코인 선택
                            {selectedCoins.size > 0 && (
                                <span className="ml-2 px-2 py-0.5 text-xs font-bold bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300 rounded-full">
                                    {selectedCoins.size}개 선택됨
                                </span>
                            )}
                        </label>
                        <div className="flex gap-2">
                            <button onClick={selectAll} className="text-xs text-blue-600 dark:text-blue-400 hover:underline font-medium">전체 선택</button>
                            <span className="text-slate-300 dark:text-slate-600">|</span>
                            <button onClick={clearAll} className="text-xs text-slate-500 dark:text-slate-400 hover:underline font-medium">전체 해제</button>
                        </div>
                    </div>

                    {/* 토글 가능한 코인 목록 */}
                    <button
                        onClick={() => setCoinListOpen(v => !v)}
                        className="w-full flex items-center justify-between px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl bg-slate-50 dark:bg-slate-800 text-sm text-slate-700 dark:text-slate-200 hover:border-blue-400 transition-colors"
                    >
                        <span className="truncate">
                            {selectedCoins.size === 0
                                ? '코인을 선택하세요'
                                : (() => {
                                    const selected = allMarkets.filter(m => selectedCoins.has(m.market));
                                    const preview = selected.slice(0, 3).map(m => m.market.replace('KRW-', '')).join(', ');
                                    return selected.length > 3 ? `${preview} 외 ${selected.length - 3}개` : preview;
                                })()}
                        </span>
                        {coinListOpen ? <ChevronUp className="w-4 h-4 text-slate-400 shrink-0" /> : <ChevronDown className="w-4 h-4 text-slate-400 shrink-0" />}
                    </button>

                    {coinListOpen && (
                        <div className="border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-800 p-3 max-h-64 overflow-y-auto">
                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
                                {allMarkets.map(info => {
                                    const checked = selectedCoins.has(info.market);
                                    return (
                                        <label
                                            key={info.market}
                                            className={`flex items-center gap-2.5 px-3 py-2 rounded-lg cursor-pointer transition-colors select-none
                                                ${checked
                                                    ? 'bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-700'
                                                    : 'hover:bg-slate-50 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300 border border-transparent'
                                                }`}
                                        >
                                            <input
                                                type="checkbox"
                                                checked={checked}
                                                onChange={() => toggleCoin(info.market)}
                                                className="w-3.5 h-3.5 accent-blue-500 shrink-0"
                                            />
                                            <span className="flex flex-col min-w-0">
                                                <span className="text-sm font-semibold leading-tight">{info.market.replace('KRW-', '')}</span>
                                                {info.koreanName && (
                                                    <span className="text-xs text-slate-400 dark:text-slate-500 truncate">{info.koreanName}</span>
                                                )}
                                            </span>
                                        </label>
                                    );
                                })}
                            </div>
                        </div>
                    )}
                </div>

                {/* 타임프레임 */}
                <div className="space-y-1.5">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">타임프레임</label>
                    <div className="grid grid-cols-3 sm:grid-cols-6 gap-2">
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

                {/* 기간 */}
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

                {/* 예상 시간 */}
                {estimatedMinutes !== null && selectedCoins.size > 0 && (
                    <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400 bg-slate-50 dark:bg-slate-800/60 rounded-lg px-3 py-2">
                        <span>⏱</span>
                        <span>
                            예상 소요시간: <strong className="text-slate-700 dark:text-slate-200">약 {estimatedMinutes < 1 ? '1분 미만' : `${estimatedMinutes}분`}</strong>
                            {' '}— 완료 시 텔레그램으로 알림이 전송됩니다.
                        </span>
                    </div>
                )}

                {collectResult && (
                    <div className={`flex items-start gap-2.5 px-4 py-3 rounded-xl text-sm font-medium border ${
                        collectResult.ok
                            ? 'text-emerald-700 bg-emerald-50 border-emerald-100 dark:text-emerald-300 dark:bg-emerald-900/20 dark:border-emerald-800'
                            : 'text-red-700 bg-red-50 border-red-100 dark:text-red-300 dark:bg-red-900/20 dark:border-red-800'
                    }`}>
                        {collectResult.ok
                            ? <CheckCircle2 className="w-4 h-4 mt-0.5 shrink-0" />
                            : <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />}
                        {collectResult.message}
                    </div>
                )}

                {pollingEnabled && (
                    <div className="flex items-center gap-2 text-sm text-blue-600 dark:text-blue-400 font-medium">
                        <div className="w-4 h-4 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
                        수집 진행 중... 완료 시 텔레그램 알림이 전송됩니다.
                    </div>
                )}

                <button
                    onClick={handleCollect}
                    disabled={collecting || selectedCoins.size === 0}
                    className="w-full flex items-center justify-center gap-3 bg-slate-900 hover:bg-slate-800 text-white font-semibold py-3.5 rounded-xl shadow-lg transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {collecting
                        ? <><div className="w-5 h-5 border-2 border-white border-b-transparent rounded-full animate-spin" /> 수집 요청 중...</>
                        : <><Download className="w-5 h-5" />
                            {selectedCoins.size === 0
                                ? '코인을 선택해주세요'
                                : `${selectedCoins.size}개 코인 배치 수집 시작`}
                          </>
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
                                                <span className="px-2 py-0.5 text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100 rounded dark:bg-indigo-900/30 dark:text-indigo-300 dark:border-indigo-800">
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
                                                    className="inline-flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 hover:bg-red-100 dark:hover:bg-red-900/40 border border-red-100 dark:border-red-800 rounded-lg transition-colors disabled:opacity-50"
                                                >
                                                    {isDeleting
                                                        ? <div className="w-3 h-3 border border-red-400 border-t-transparent rounded-full animate-spin" />
                                                        : <Trash2 className="w-3 h-3" />}
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
