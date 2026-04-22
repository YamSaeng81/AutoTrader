'use client';

import { useState, useEffect, useRef } from 'react';
import { strategyApi, backtestApi, systemApi } from '@/lib/api';
import { StrategyInfo, Timeframe, StrategyType } from '@/lib/types';
import { useRouter } from 'next/navigation';
import { Play, Loader2, ChevronDown, ChevronUp } from 'lucide-react';
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

type Mode = 'single-coin' | 'batch';

export function BacktestForm() {
    const router = useRouter();
    const [loading, setLoading] = useState(false);
    const [mode, setMode] = useState<Mode>('single-coin');
    const [strategies, setStrategies] = useState<StrategyInfo[]>([]);
    const [coins, setCoins] = useState<string[]>([]);
    const [selectedStrategies, setSelectedStrategies] = useState<string[]>([]);
    const [selectedCoins, setSelectedCoins] = useState<string[]>([]);
    const [coinSearchOpen, setCoinSearchOpen] = useState(false);
    const [coinFilter, setCoinFilter] = useState('');
    const [singleCoinOpen, setSingleCoinOpen] = useState(false);
    const [singleCoinSearch, setSingleCoinSearch] = useState('');
    const singleCoinRef = useRef<HTMLDivElement>(null);

    const [form, setForm] = useState({
        coinPair: 'KRW-BTC',
        timeframe: 'H1' as Timeframe,
        startDate: yearStart,
        endDate: today,
        initialCapital: 10000000,
    });

    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (singleCoinRef.current && !singleCoinRef.current.contains(e.target as Node)) {
                setSingleCoinOpen(false);
                setSingleCoinSearch('');
            }
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, []);

    useEffect(() => {
        Promise.all([strategyApi.list(), systemApi.coins()]).then(([stRes, cRes]) => {
            if (stRes.success && stRes.data) {
                const available = stRes.data.filter(s => s.status === 'AVAILABLE' && s.isActive);
                setStrategies(available);
            }
            if (cRes.success && cRes.data) setCoins(cRes.data);
        });
    }, []);

    const strategyList = strategies.length > 0
        ? strategies.map(s => ({ name: s.name, label: STRATEGY_LABELS[s.name] ?? s.name }))
        : ALL_STRATEGIES.map(s => ({ name: s, label: STRATEGY_LABELS[s] ?? s }));

    const filteredCoins = coins.filter(c => c.toLowerCase().includes(coinFilter.toLowerCase()));
    const filteredSingleCoins = coins.filter(c => c.toLowerCase().includes(singleCoinSearch.toLowerCase()));

    const toggleStrategy = (name: string) =>
        setSelectedStrategies(prev => prev.includes(name) ? prev.filter(s => s !== name) : [...prev, name]);

    const toggleCoin = (coin: string) =>
        setSelectedCoins(prev => prev.includes(coin) ? prev.filter(c => c !== coin) : [...prev, coin]);

    // 배치 모드: 예상 조합 수
    const batchTotal = selectedCoins.length * selectedStrategies.length;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (selectedStrategies.length === 0) return;
        setLoading(true);

        try {
            let res;

            if (mode === 'batch') {
                if (selectedCoins.length === 0) { alert('코인을 1개 이상 선택하세요.'); setLoading(false); return; }
                res = await backtestApi.runBatch({
                    coinPairs: selectedCoins,
                    strategyTypes: selectedStrategies,
                    timeframe: form.timeframe,
                    startDate: form.startDate,
                    endDate: form.endDate,
                    initialCapital: form.initialCapital,
                });
            } else if (selectedStrategies.length === 1) {
                res = await backtestApi.run({
                    ...form,
                    strategyType: selectedStrategies[0] as StrategyType,
                });
            } else {
                res = await backtestApi.runMultiStrategy({
                    strategyTypes: selectedStrategies,
                    coinPair: form.coinPair,
                    timeframe: form.timeframe,
                    startDate: form.startDate,
                    endDate: form.endDate,
                    initialCapital: form.initialCapital,
                });
            }

            if (res.success) {
                router.push('/backtest');
            } else {
                alert(res.error?.message || '실행 실패');
            }
        } catch {
            alert('오류가 발생했습니다.');
        } finally {
            setLoading(false);
        }
    };

    const selectInput = 'w-full p-3 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 appearance-none cursor-pointer';

    return (
        <form onSubmit={handleSubmit} className="bg-white dark:bg-slate-900 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 space-y-8">
            <div>
                <h2 className="text-xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">새 백테스트 설정</h2>
                <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">전략과 기간을 선택하여 과거 데이터 기반 시뮬레이션을 실행하세요.</p>
            </div>

            {/* ── 모드 선택 탭 ── */}
            <div className="flex gap-2 p-1 bg-slate-100 dark:bg-slate-800 rounded-xl w-fit">
                {([['single-coin', '단일 코인'], ['batch', '다중 코인 × 다중 전략 배치']] as [Mode, string][]).map(([m, label]) => (
                    <button
                        key={m}
                        type="button"
                        onClick={() => setMode(m)}
                        className={cn(
                            'px-4 py-2 rounded-lg text-sm font-semibold transition-all',
                            mode === m
                                ? 'bg-white dark:bg-slate-700 text-indigo-700 dark:text-indigo-300 shadow-sm'
                                : 'text-slate-500 dark:text-slate-400 hover:text-slate-700'
                        )}
                    >
                        {label}
                    </button>
                ))}
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-6">

                {/* ── 전략 선택 ── */}
                <div className="md:col-span-2 space-y-2">
                    <div className="flex items-center justify-between">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">
                            전략 선택
                            <span className="ml-2 px-1.5 py-0.5 text-[11px] font-bold rounded bg-indigo-100 text-indigo-700 dark:bg-indigo-900 dark:text-indigo-300">
                                {selectedStrategies.length}개 선택
                            </span>
                            {mode === 'single-coin' && selectedStrategies.length >= 2 && (
                                <span className="ml-1.5 text-[11px] text-indigo-500 font-normal">→ 전략 비교 결과 표시</span>
                            )}
                        </label>
                        <div className="flex gap-2 text-xs">
                            <button type="button" onClick={() => setSelectedStrategies(strategyList.map(s => s.name))} className="text-indigo-500 hover:text-indigo-700 font-semibold">전체 선택</button>
                            <span className="text-slate-300">|</span>
                            <button type="button" onClick={() => setSelectedStrategies([])} className="text-slate-400 hover:text-slate-600 font-semibold">전체 해제</button>
                        </div>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
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

                {/* ── 단일 코인 모드: 검색 콤보박스 ── */}
                {mode === 'single-coin' && (
                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">코인 페어</label>
                        <div className="relative" ref={singleCoinRef}>
                            <input
                                type="text"
                                value={singleCoinOpen ? singleCoinSearch : form.coinPair}
                                onChange={e => { setSingleCoinSearch(e.target.value); setSingleCoinOpen(true); }}
                                onFocus={() => { setSingleCoinOpen(true); setSingleCoinSearch(''); }}
                                placeholder={coins.length === 0 ? '로딩중...' : '코인 검색...'}
                                className="w-full p-3 pr-10 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            />
                            <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-slate-500">
                                <svg className="w-4 h-4" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" /></svg>
                            </div>
                            {singleCoinOpen && filteredSingleCoins.length > 0 && (
                                <div className="absolute z-20 mt-1 w-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg max-h-56 overflow-y-auto">
                                    {filteredSingleCoins.map(c => (
                                        <button
                                            key={c}
                                            type="button"
                                            onClick={() => { setForm({ ...form, coinPair: c }); setSingleCoinOpen(false); setSingleCoinSearch(''); }}
                                            className={cn(
                                                'w-full text-left px-3 py-2 text-sm font-medium transition-colors hover:bg-indigo-50 dark:hover:bg-slate-700',
                                                form.coinPair === c
                                                    ? 'text-indigo-600 dark:text-indigo-400 bg-indigo-50/50 dark:bg-slate-700/50'
                                                    : 'text-slate-700 dark:text-slate-200'
                                            )}
                                        >
                                            {c}
                                        </button>
                                    ))}
                                </div>
                            )}
                            {singleCoinOpen && filteredSingleCoins.length === 0 && (
                                <div className="absolute z-20 mt-1 w-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg px-3 py-2 text-sm text-slate-400">
                                    검색 결과 없음
                                </div>
                            )}
                        </div>
                    </div>
                )}

                {/* ── 배치 모드: 코인 다중 선택 ── */}
                {mode === 'batch' && (
                    <div className="md:col-span-2 space-y-2">
                        <div className="flex items-center justify-between">
                            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">
                                코인 선택
                                <span className="ml-2 px-1.5 py-0.5 text-[11px] font-bold rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300">
                                    {selectedCoins.length}개 선택
                                </span>
                                {batchTotal > 0 && (
                                    <span className="ml-2 text-[11px] text-slate-500 font-normal">
                                        → 총 {batchTotal}개 조합 백테스트
                                    </span>
                                )}
                            </label>
                            <div className="flex gap-2 text-xs">
                                <button type="button" onClick={() => setSelectedCoins([...coins])} className="text-emerald-500 hover:text-emerald-700 font-semibold">전체 선택</button>
                                <span className="text-slate-300">|</span>
                                <button type="button" onClick={() => setSelectedCoins([])} className="text-slate-400 hover:text-slate-600 font-semibold">전체 해제</button>
                                <span className="text-slate-300">|</span>
                                <button
                                    type="button"
                                    onClick={() => setCoinSearchOpen(v => !v)}
                                    className="flex items-center gap-1 text-slate-500 hover:text-slate-700 font-semibold"
                                >
                                    {coinSearchOpen ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                                    {coinSearchOpen ? '접기' : '펼치기'}
                                </button>
                            </div>
                        </div>

                        {/* 선택된 코인 태그 */}
                        {selectedCoins.length > 0 && (
                            <div className="flex flex-wrap gap-1.5">
                                {selectedCoins.map(c => (
                                    <span
                                        key={c}
                                        onClick={() => toggleCoin(c)}
                                        className="flex items-center gap-1 px-2 py-0.5 bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200 text-xs font-semibold rounded-full cursor-pointer hover:bg-emerald-200 transition-colors"
                                    >
                                        {c}
                                        <span className="text-emerald-500">×</span>
                                    </span>
                                ))}
                            </div>
                        )}

                        {/* 코인 목록 (접기/펼치기) */}
                        {coinSearchOpen && (
                            <div className="border border-slate-200 dark:border-slate-700 rounded-xl p-3 space-y-2 bg-slate-50 dark:bg-slate-800/50">
                                <input
                                    type="text"
                                    placeholder="코인 검색..."
                                    value={coinFilter}
                                    onChange={e => setCoinFilter(e.target.value)}
                                    className="w-full px-3 py-1.5 text-sm bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg focus:outline-none focus:ring-1 focus:ring-indigo-500"
                                />
                                <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-1.5 max-h-48 overflow-y-auto">
                                    {filteredCoins.map(coin => {
                                        const checked = selectedCoins.includes(coin);
                                        return (
                                            <button
                                                key={coin}
                                                type="button"
                                                onClick={() => toggleCoin(coin)}
                                                className={cn(
                                                    'px-2 py-1.5 rounded-lg border text-xs font-semibold transition-all text-center',
                                                    checked
                                                        ? 'bg-emerald-600 text-white border-emerald-600'
                                                        : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-emerald-300'
                                                )}
                                            >
                                                {coin.replace('KRW-', '')}
                                            </button>
                                        );
                                    })}
                                </div>
                            </div>
                        )}
                    </div>
                )}

                {/* ── 타임프레임 ── */}
                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">타임프레임</label>
                    <div className="relative">
                        <select className={selectInput} value={form.timeframe} onChange={(e) => setForm({ ...form, timeframe: e.target.value as Timeframe })}>
                            <option value="M1">1분 (M1)</option>
                            <option value="M5">5분 (M5)</option>
                            <option value="M15">15분 (M15)</option>
                            <option value="M30">30분 (M30)</option>
                            <option value="H1">1시간 (H1)</option>
                            <option value="H4">4시간 (H4)</option>
                            <option value="D1">1일 (D1)</option>
                        </select>
                        <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none text-slate-500">
                            <svg className="w-4 h-4" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" /></svg>
                        </div>
                    </div>
                </div>

                {/* ── 초기 자금 ── */}
                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">초기 자금 (원)</label>
                    <div className="relative">
                        <input
                            type="number"
                            className="w-full p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.initialCapital}
                            onChange={(e) => setForm({ ...form, initialCapital: Number(e.target.value) })}
                        />
                        <div className="absolute inset-y-0 right-0 flex items-center px-4 pointer-events-none font-medium text-slate-400">KRW</div>
                    </div>
                </div>

                {/* ── 기간 ── */}
                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">시작일</label>
                    <input type="date" className="w-full p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 cursor-pointer" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} />
                </div>
                <div className="space-y-2">
                    <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">종료일</label>
                    <input type="date" className="w-full p-3 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 transition-shadow focus:outline-none focus:ring-2 focus:ring-indigo-500/50 cursor-pointer" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} />
                </div>
            </div>

            {/* ── 제출 ── */}
            <div className="pt-6 border-t border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <div className="text-sm text-slate-500">
                    {mode === 'batch' && batchTotal > 0 && (
                        <span className="font-medium text-indigo-600">
                            {selectedCoins.length}개 코인 × {selectedStrategies.length}개 전략 = {batchTotal}개 조합
                        </span>
                    )}
                    {selectedStrategies.length === 0 && (
                        <span className="text-amber-600 font-medium">전략을 1개 이상 선택하세요.</span>
                    )}
                    {mode === 'batch' && selectedStrategies.length > 0 && selectedCoins.length === 0 && (
                        <span className="text-amber-600 font-medium">코인을 1개 이상 선택하세요.</span>
                    )}
                </div>
                <button
                    type="submit"
                    disabled={loading || selectedStrategies.length === 0 || (mode === 'batch' && selectedCoins.length === 0)}
                    className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 px-8 rounded-xl shadow-md shadow-indigo-600/20 transition-all active:scale-[0.98] disabled:opacity-70 disabled:pointer-events-none"
                >
                    {loading ? (
                        <><Loader2 className="w-5 h-5 animate-spin" /> 제출 중...</>
                    ) : mode === 'batch' ? (
                        <><Play className="w-5 h-5 fill-current" /> {batchTotal}개 배치 실행</>
                    ) : selectedStrategies.length >= 2 ? (
                        <><Play className="w-5 h-5 fill-current" /> {selectedStrategies.length}개 전략 비교 실행</>
                    ) : (
                        <><Play className="w-5 h-5 fill-current" /> 백테스트 실행</>
                    )}
                </button>
            </div>
        </form>
    );
}
