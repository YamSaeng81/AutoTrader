'use client';

import { useState, useEffect } from 'react';
import { strategyApi, backtestApi, systemApi } from '@/lib/api';
import { StrategyInfo, Timeframe, StrategyType } from '@/lib/types';
import { useRouter } from 'next/navigation';
import { Play, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';

const today = new Date().toISOString().slice(0, 10);
const yearStart = '2025-01-01';

const ALL_STRATEGIES = [
    'COMPOSITE', 'EMA_CROSS', 'BOLLINGER', 'RSI', 'MACD',
    'SUPERTREND', 'ATR_BREAKOUT', 'GRID', 'ORDERBOOK_IMBALANCE', 'STOCHASTIC_RSI', 'VWAP',
];
const STRATEGY_LABELS: Record<string, string> = {
    COMPOSITE: 'COMPOSITE (시장 국면 자동 선택)',
};

export function BacktestForm() {
    const router = useRouter();
    const [loading, setLoading] = useState(false);
    const [strategies, setStrategies] = useState<StrategyInfo[]>([]);
    const [coins, setCoins] = useState<string[]>([]);
    const [selectedStrategies, setSelectedStrategies] = useState<string[]>(['COMPOSITE']);

    const [form, setForm] = useState({
        coinPair: 'KRW-BTC',
        timeframe: 'H1' as Timeframe,
        startDate: yearStart,
        endDate: today,
        initialCapital: 10000000,
    });

    useEffect(() => {
        Promise.all([
            strategyApi.list(),
            systemApi.coins()
        ]).then(([stRes, cRes]) => {
            if (stRes.success && stRes.data) {
                const available = stRes.data.filter(s => s.status === 'AVAILABLE');
                setStrategies(available);
            }
            if (cRes.success && cRes.data) setCoins(cRes.data);
        });
    }, []);

    const strategyList = [
        { name: 'COMPOSITE', label: 'COMPOSITE (시장 국면 자동 선택)' },
        ...(strategies.length > 0
            ? strategies.map(s => ({ name: s.name, label: s.name }))
            : ALL_STRATEGIES.filter(s => s !== 'COMPOSITE').map(s => ({ name: s, label: STRATEGY_LABELS[s] ?? s }))
        ),
    ];

    const toggleStrategy = (name: string) => {
        setSelectedStrategies(prev =>
            prev.includes(name) ? prev.filter(s => s !== name) : [...prev, name]
        );
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (selectedStrategies.length === 0) return;
        setLoading(true);

        try {
            if (selectedStrategies.length === 1) {
                const res = await backtestApi.run({
                    ...form,
                    strategyType: selectedStrategies[0] as StrategyType,
                    startDate: new Date(form.startDate).toISOString(),
                    endDate: new Date(form.endDate).toISOString(),
                });
                if (res.success && res.data) {
                    router.push(`/backtest/${res.data.id}`);
                } else {
                    alert(res.error?.message || '실행 실패');
                }
            } else {
                const res = await backtestApi.runMultiStrategy({
                    strategyTypes: selectedStrategies,
                    coinPair: form.coinPair,
                    timeframe: form.timeframe,
                    startDate: new Date(form.startDate).toISOString(),
                    endDate: new Date(form.endDate).toISOString(),
                    initialCapital: form.initialCapital,
                });
                if (res.success) {
                    router.push('/backtest/list');
                } else {
                    alert(res.error?.message || '실행 실패');
                }
            }
        } catch {
            alert('오류가 발생했습니다.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <form onSubmit={handleSubmit} className="bg-white dark:bg-slate-900 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 space-y-8">
            <div>
                <h2 className="text-xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">새 백테스트 설정</h2>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">원하는 전략과 기간을 선택하여 과거 데이터 기반의 시뮬레이션을 실행하세요.</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-6">
                {/* 전략 선택 — 멀티 체크박스 */}
                <div className="md:col-span-2 space-y-2">
                    <div className="flex items-center justify-between">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">
                            전략 선택
                            <span className="ml-2 px-1.5 py-0.5 text-[11px] font-bold rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900 dark:text-indigo-300">
                                {selectedStrategies.length}개 선택
                            </span>
                            {selectedStrategies.length >= 2 && (
                                <span className="ml-1.5 text-[11px] text-indigo-500 font-normal">→ 전략 비교 결과 일람 표시</span>
                            )}
                        </label>
                        <div className="flex gap-2 text-xs">
                            <button type="button" onClick={() => setSelectedStrategies(strategyList.map(s => s.name))} className="text-indigo-500 hover:text-indigo-700 font-semibold">전체 선택</button>
                            <span className="text-slate-300">|</span>
                            <button type="button" onClick={() => setSelectedStrategies([])} className="text-slate-400 hover:text-slate-600 font-semibold">전체 해제</button>
                        </div>
                    </div>
                    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
                        {strategyList.map(s => {
                            const checked = selectedStrategies.includes(s.name);
                            return (
                                <button
                                    key={s.name}
                                    type="button"
                                    onClick={() => toggleStrategy(s.name)}
                                    className={cn(
                                        'flex items-center gap-2 px-3 py-2 rounded-xl border text-xs font-semibold transition-all text-left',
                                        checked
                                            ? 'bg-indigo-600 text-white border-indigo-600 shadow-sm'
                                            : 'bg-slate-50 dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-700 hover:border-indigo-300'
                                    )}
                                >
                                    <span className={cn(
                                        'w-3.5 h-3.5 rounded border-2 flex-shrink-0 flex items-center justify-center',
                                        checked ? 'bg-white border-white' : 'border-slate-400'
                                    )}>
                                        {checked && <span className="w-1.5 h-1.5 rounded-sm bg-indigo-600" />}
                                    </span>
                                    <span className="truncate">{s.label}</span>
                                </button>
                            );
                        })}
                    </div>
                </div>

                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">코인 페어</label>
                    <div className="relative">
                        <select
                            className="w-full p-3 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 appearance-none cursor-pointer"
                            value={form.coinPair}
                            onChange={(e) => setForm({ ...form, coinPair: e.target.value })}
                        >
                            <option disabled value="">로딩중...</option>
                            {coins.map(c => <option key={c} value={c}>{c}</option>)}
                        </select>
                        <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-slate-500 dark:text-slate-400">
                            <svg className="w-4 h-4" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" /></svg>
                        </div>
                    </div>
                </div>

                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">타임프레임</label>
                    <div className="relative">
                        <select
                            className="w-full p-3 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 appearance-none cursor-pointer"
                            value={form.timeframe}
                            onChange={(e) => setForm({ ...form, timeframe: e.target.value as Timeframe })}
                        >
                            <option value="M1">1분 (M1)</option>
                            <option value="M5">5분 (M5)</option>
                            <option value="H1">1시간 (H1)</option>
                            <option value="D1">1일 (D1)</option>
                        </select>
                        <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-slate-500 dark:text-slate-400">
                            <svg className="w-4 h-4" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" /></svg>
                        </div>
                    </div>
                </div>

                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">초기 자금 (원)</label>
                    <div className="relative">
                        <input
                            type="number"
                            className="w-full p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.initialCapital}
                            onChange={(e) => setForm({ ...form, initialCapital: Number(e.target.value) })}
                        />
                        <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none font-medium text-slate-400 dark:text-slate-500">KRW</div>
                    </div>
                </div>

                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">시작일</label>
                    <input
                        type="date"
                        className="w-full p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 cursor-pointer"
                        value={form.startDate}
                        onChange={(e) => setForm({ ...form, startDate: e.target.value })}
                    />
                </div>

                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">종료일</label>
                    <input
                        type="date"
                        className="w-full p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 cursor-pointer"
                        value={form.endDate}
                        onChange={(e) => setForm({ ...form, endDate: e.target.value })}
                    />
                </div>
            </div>

            <div className="pt-6 border-t border-slate-100 dark:border-slate-800 flex items-center justify-between">
                {selectedStrategies.length === 0 && (
                    <span className="text-sm text-amber-600 font-medium">전략을 1개 이상 선택하세요.</span>
                )}
                <div className="ml-auto">
                    <button
                        type="submit"
                        disabled={loading || selectedStrategies.length === 0}
                        className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 px-8 rounded-xl shadow-md shadow-indigo-600/20 transition-all active:scale-[0.98] disabled:opacity-70 disabled:pointer-events-none"
                    >
                        {loading ? (
                            <><Loader2 className="w-5 h-5 animate-spin" /> 실행 중...</>
                        ) : selectedStrategies.length >= 2 ? (
                            <><Play className="w-5 h-5 fill-current" /> {selectedStrategies.length}개 전략 비교 실행</>
                        ) : (
                            <><Play className="w-5 h-5 fill-current" /> 백테스트 실행</>
                        )}
                    </button>
                </div>
            </div>
        </form>
    );
}
