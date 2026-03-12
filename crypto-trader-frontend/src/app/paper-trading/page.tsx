'use client';

import { usePaperSessions, useStartPaperSession, useStopPaperSession } from '@/hooks';
import { PaperSession, PaperTradingStartRequest, StrategyInfo } from '@/lib/types';
import { strategyApi, systemApi } from '@/lib/api';
import { useState, useEffect } from 'react';
import Link from 'next/link';
import { Loader2, Play, Square, Plus, TrendingUp, TrendingDown, Activity, Settings, History } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';

const TIMEFRAMES = [
    { value: 'M1', label: '1분' },
    { value: 'M5', label: '5분' },
    { value: 'H1', label: '1시간' },
    { value: 'D1', label: '일봉' },
];

const MAX_SESSIONS = 5;

export default function PaperTradingPage() {
    const [showNewForm, setShowNewForm] = useState(false);
    const [availableStrategies, setAvailableStrategies] = useState<StrategyInfo[]>([]);
    const [coins, setCoins] = useState<string[]>([]);
    const [config, setConfig] = useState<PaperTradingStartRequest>({
        strategyType: 'EMA_CROSS',
        coinPair: 'KRW-BTC',
        timeframe: 'H1',
        initialCapital: 10000000,
    });

    useEffect(() => {
        Promise.all([strategyApi.list(), systemApi.coins()]).then(([stRes, cRes]) => {
            if (stRes.success && stRes.data) {
                const available = stRes.data.filter(s => s.status === 'AVAILABLE' && s.isActive);
                setAvailableStrategies(available);
                if (available.length > 0) setConfig(c => ({ ...c, strategyType: available[0].name }));
            }
            if (cRes.success && cRes.data) {
                const coins = cRes.data;
                setCoins(coins);
                if (coins.length > 0) setConfig(c => ({ ...c, coinPair: coins[0] }));
            }
        }).catch(() => {});
    }, []);

    const { data: sessions = [], isLoading } = usePaperSessions();

    const startMutation = useStartPaperSession();
    const stopMutation = useStopPaperSession();
    const runningSessions = sessions.filter(s => s.status === 'RUNNING');
    const canAddMore = runningSessions.length < MAX_SESSIONS;

    if (isLoading) {
        return (
            <div className="flex flex-col items-center justify-center p-20 text-slate-500 gap-4">
                <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
                <p>세션 목록 로딩 중...</p>
            </div>
        );
    }

    return (
        <div className="space-y-6 animate-in fade-in duration-500">
            {/* Header */}
            <div className="flex items-center justify-between">
                <div>
                    <div className="flex items-center gap-2">
                        <Activity className="w-6 h-6 text-indigo-500" />
                        <h1 className="text-2xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">모의투자</h1>
                        <span className="ml-1 px-2.5 py-0.5 text-xs font-bold rounded-full bg-indigo-50 text-indigo-600 border border-indigo-100">
                            실행 중 {runningSessions.length}/{MAX_SESSIONS}
                        </span>
                    </div>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">최대 5개 세션을 동시에 운영할 수 있습니다.</p>
                </div>
                <div className="flex gap-2">
                    <Link
                        href="/paper-trading/history"
                        className="flex items-center gap-2 px-4 py-2 text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700 text-sm font-semibold rounded-xl transition-colors"
                    >
                        <History className="w-4 h-4" />
                        이력
                    </Link>
                    {canAddMore && (
                        <button
                            onClick={() => setShowNewForm(v => !v)}
                            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-semibold rounded-xl shadow-sm transition-colors"
                        >
                            <Plus className="w-4 h-4" />
                            새 세션
                        </button>
                    )}
                </div>
            </div>

            {/* New session form */}
            {showNewForm && (
                <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-indigo-100 dark:border-slate-700 p-6 space-y-5">
                    <div className="flex items-center gap-2">
                        <Settings className="w-5 h-5 text-slate-500" />
                        <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">새 모의투자 세션</h2>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                        <div className="space-y-1.5">
                            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">전략 유형</label>
                            <select
                                value={config.strategyType}
                                onChange={e => setConfig(c => ({ ...c, strategyType: e.target.value }))}
                                className="w-full border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 text-sm bg-slate-50 dark:bg-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                            >
                                {availableStrategies.map(s => (
                                    <option key={s.name} value={s.name}>{s.name}</option>
                                ))}
                                {availableStrategies.length === 0 && <option value="EMA_CROSS">EMA_CROSS</option>}
                            </select>
                        </div>
                        <div className="space-y-1.5">
                            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">코인 페어</label>
                            <select
                                value={config.coinPair}
                                onChange={e => setConfig(c => ({ ...c, coinPair: e.target.value }))}
                                className="w-full border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 text-sm bg-slate-50 dark:bg-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                            >
                                {coins.length > 0
                                    ? coins.map(coin => <option key={coin} value={coin}>{coin}</option>)
                                    : <option value="KRW-BTC">KRW-BTC</option>
                                }
                            </select>
                        </div>
                        <div className="space-y-1.5">
                            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">타임프레임</label>
                            <div className="grid grid-cols-4 gap-2">
                                {TIMEFRAMES.map(tf => (
                                    <button
                                        key={tf.value}
                                        type="button"
                                        onClick={() => setConfig(c => ({ ...c, timeframe: tf.value }))}
                                        className={cn(
                                            'py-2 rounded-xl text-sm font-semibold border transition-all',
                                            config.timeframe === tf.value
                                                ? 'bg-slate-900 text-white border-slate-900'
                                                : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-slate-400 dark:hover:border-slate-500'
                                        )}
                                    >
                                        {tf.label}
                                    </button>
                                ))}
                            </div>
                        </div>
                        <div className="space-y-1.5">
                            <label className="text-sm font-semibold text-slate-700 dark:text-slate-200">초기 자금 (원)</label>
                            <div className="relative">
                                <input
                                    type="number"
                                    min={100000}
                                    step={100000}
                                    value={config.initialCapital}
                                    onChange={e => setConfig(c => ({ ...c, initialCapital: Number(e.target.value) }))}
                                    className="w-full border border-slate-200 rounded-xl px-4 py-2.5 text-sm bg-slate-50 focus:outline-none focus:ring-2 focus:ring-indigo-500 pr-16"
                                />
                                <span className="absolute inset-y-0 right-4 flex items-center text-xs font-medium text-slate-400">KRW</span>
                            </div>
                        </div>
                    </div>

                    <div className="flex items-center gap-3 pt-2">
                        <button
                            onClick={() => startMutation.mutate(config, { onSuccess: () => setShowNewForm(false) })}
                            disabled={startMutation.isPending}
                            className="flex items-center gap-2 px-6 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold rounded-xl shadow-sm transition-colors disabled:opacity-50"
                        >
                            {startMutation.isPending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" fill="currentColor" />}
                            시작
                        </button>
                        <button
                            onClick={() => setShowNewForm(false)}
                            className="px-4 py-2.5 text-sm font-semibold text-slate-600 hover:text-slate-800 transition-colors"
                        >
                            취소
                        </button>
                        {startMutation.isError && (
                            <span className="text-sm text-rose-600">시작 실패. 백엔드 연결을 확인하세요.</span>
                        )}
                    </div>
                </div>
            )}

            {/* Running sessions */}
            {runningSessions.length === 0 && !showNewForm ? (
                <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 p-16 text-center">
                    <Activity className="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
                    <p className="text-slate-500 dark:text-slate-400 font-medium">실행 중인 세션이 없습니다.</p>
                    <p className="text-slate-400 dark:text-slate-500 text-sm mt-1">상단의 &quot;새 세션&quot; 버튼으로 모의투자를 시작하세요.</p>
                </div>
            ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
                    {runningSessions.map(session => (
                        <SessionCard
                            key={session.id}
                            session={session}
                            onStop={() => {
                                if (confirm(`세션 #${session.id}을 중단하시겠습니까?`)) {
                                    stopMutation.mutate(session.id);
                                }
                            }}
                            isStopping={stopMutation.isPending}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}

function SessionCard({ session, onStop, isStopping }: {
    session: PaperSession;
    onStop: () => void;
    isStopping: boolean;
}) {
    const returnPct = Number(session.totalReturnPct);
    const isPositive = returnPct >= 0;

    return (
        <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden flex flex-col">
            {/* Card header */}
            <div className="px-5 py-4 bg-slate-50/80 dark:bg-slate-800/50 border-b border-slate-100 dark:border-slate-800 flex items-center justify-between">
                <div>
                    <div className="font-bold text-slate-800 dark:text-slate-100 text-sm">{session.strategyName}</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                        {session.coinPair} · {session.timeframe}
                    </div>
                </div>
                <span className="px-2 py-0.5 text-[10px] font-bold tracking-wider uppercase rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                    ● 실행 중
                </span>
            </div>

            {/* Metrics */}
            <div className="p-5 flex-1 space-y-3">
                <div>
                    <div className="text-xs text-slate-500 dark:text-slate-400 mb-0.5">총 평가 자산</div>
                    <div className="text-xl font-extrabold text-slate-800 dark:text-slate-100">
                        {Number(session.totalAssetKrw).toLocaleString()}
                        <span className="text-sm font-bold text-slate-400 dark:text-slate-500 ml-1">KRW</span>
                    </div>
                </div>
                <div className="flex items-center gap-2">
                    <span className={cn(
                        'flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-bold',
                        isPositive ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'
                    )}>
                        {isPositive ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                        {isPositive ? '+' : ''}{returnPct.toFixed(2)}%
                    </span>
                    <span className="text-xs text-slate-400 dark:text-slate-500">
                        초기 {Number(session.initialCapital).toLocaleString()}원
                    </span>
                </div>
                {session.startedAt && (
                    <div className="text-xs text-slate-400 dark:text-slate-500">
                        시작: {format(new Date(session.startedAt), 'yyyy.MM.dd HH:mm')}
                    </div>
                )}
            </div>

            {/* Actions */}
            <div className="px-5 py-4 border-t border-slate-100 dark:border-slate-800 flex gap-2">
                <Link
                    href={`/paper-trading/${session.id}`}
                    className="flex-1 text-center py-2 text-sm font-semibold text-indigo-600 bg-indigo-50 hover:bg-indigo-100 rounded-lg transition-colors"
                >
                    상세보기
                </Link>
                <button
                    onClick={onStop}
                    disabled={isStopping}
                    className="flex items-center justify-center gap-1.5 px-3 py-2 text-sm font-semibold text-rose-600 bg-rose-50 hover:bg-rose-100 rounded-lg transition-colors disabled:opacity-50"
                >
                    {isStopping ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Square className="w-3.5 h-3.5" fill="currentColor" />}
                    중단
                </button>
            </div>
        </div>
    );
}
