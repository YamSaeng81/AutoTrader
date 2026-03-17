'use client';

import { useState, useEffect, useCallback } from 'react';
import { settingsApi, DbStats } from '@/lib/api';
import {
    Trash2, Database, RefreshCw, AlertTriangle,
    CheckCircle, XCircle, Eye, EyeOff, Lock,
} from 'lucide-react';

type Target = 'BACKTEST' | 'PAPER_TRADING' | 'LIVE_TRADING';

const CATEGORIES: { target: Target; label: string; color: string; borderColor: string; tables: string[] }[] = [
    {
        target: 'BACKTEST',
        label: '백테스트',
        color: 'text-blue-400',
        borderColor: 'border-blue-500/30 hover:border-blue-500/60',
        tables: ['backtest_run', 'backtest_metrics', 'backtest_trade'],
    },
    {
        target: 'PAPER_TRADING',
        label: '모의투자',
        color: 'text-emerald-400',
        borderColor: 'border-emerald-500/30 hover:border-emerald-500/60',
        tables: ['virtual_balance', 'position', 'order', 'strategy_log', 'trade_log'],
    },
    {
        target: 'LIVE_TRADING',
        label: '실전매매',
        color: 'text-rose-400',
        borderColor: 'border-rose-500/30 hover:border-rose-500/60',
        tables: ['live_trading_session', 'position', 'order', 'strategy_log', 'trade_log'],
    },
];

function totalCount(stats: Record<string, number> | undefined): number {
    if (!stats) return 0;
    return Object.values(stats).reduce((a, b) => a + (b > 0 ? b : 0), 0);
}

export default function DbResetPage() {
    const [stats, setStats] = useState<DbStats | null>(null);
    const [loading, setLoading] = useState(true);

    // 모달 상태
    const [modalTarget, setModalTarget] = useState<Target | null>(null);
    const [password, setPassword] = useState('');
    const [showPw, setShowPw] = useState(false);
    const [resetting, setResetting] = useState(false);
    const [pwError, setPwError] = useState('');

    // 결과
    const [result, setResult] = useState<{ target: string; deleted: Record<string, number>; total: number } | null>(null);
    const [error, setError] = useState('');

    const fetchStats = useCallback(async () => {
        setLoading(true);
        try {
            const res = await settingsApi.dbStats();
            if (res.success && res.data) setStats(res.data);
        } catch {
            // ignore
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { fetchStats(); }, [fetchStats]);

    const openModal = (target: Target) => {
        setModalTarget(target);
        setPassword('');
        setPwError('');
        setShowPw(false);
        setResult(null);
        setError('');
    };

    const closeModal = () => {
        setModalTarget(null);
        setPassword('');
        setPwError('');
    };

    const handleReset = async () => {
        if (!modalTarget || !password) { setPwError('비밀번호를 입력하세요.'); return; }
        setResetting(true);
        setPwError('');
        try {
            const res = await settingsApi.dbReset(modalTarget, password);
            if (res.success && res.data) {
                setResult(res.data);
                closeModal();
                fetchStats();
            } else {
                setPwError('초기화 실패');
            }
        } catch (e: unknown) {
            const status = (e as { response?: { status?: number } })?.response?.status;
            if (status === 401) {
                setPwError('비밀번호가 올바르지 않습니다.');
            } else {
                setPwError('서버 오류가 발생했습니다.');
            }
        } finally {
            setResetting(false);
        }
    };

    const category = CATEGORIES.find(c => c.target === modalTarget);
    const statsKey: Record<Target, keyof DbStats> = {
        BACKTEST: 'backtest',
        PAPER_TRADING: 'paperTrading',
        LIVE_TRADING: 'liveTrading',
    };

    return (
        <div className="p-6 max-w-4xl">
            {/* 헤더 */}
            <div className="flex items-center justify-between mb-6">
                <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-red-500/10 flex items-center justify-center">
                        <Trash2 className="w-5 h-5 text-red-400" />
                    </div>
                    <div>
                        <h1 className="text-xl font-bold text-slate-100">DB 초기화</h1>
                        <p className="text-xs text-slate-500 mt-0.5">카테고리별 데이터를 삭제합니다. 되돌릴 수 없습니다.</p>
                    </div>
                </div>
                <button
                    onClick={fetchStats}
                    disabled={loading}
                    className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-800 text-slate-400 hover:text-slate-200 text-sm transition-colors disabled:opacity-50"
                >
                    <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
                    새로고침
                </button>
            </div>

            {/* 경고 배너 */}
            <div className="flex items-start gap-3 p-4 rounded-xl bg-amber-500/10 border border-amber-500/20 mb-6">
                <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
                <div className="text-sm text-amber-300">
                    <p className="font-semibold mb-0.5">주의 — 삭제된 데이터는 복구할 수 없습니다</p>
                    <p className="text-amber-400/80">초기화 전 반드시 백업을 확인하세요. 전략 설정(strategy_config)과 캔들 데이터(candle_data, market_data_cache)는 초기화되지 않습니다.</p>
                </div>
            </div>

            {/* 결과 알림 */}
            {result && (
                <div className="flex items-start gap-3 p-4 rounded-xl bg-green-500/10 border border-green-500/20 mb-6">
                    <CheckCircle className="w-5 h-5 text-green-400 shrink-0 mt-0.5" />
                    <div className="text-sm text-green-300">
                        <p className="font-semibold mb-1">{result.target} 초기화 완료 — 총 {result.total.toLocaleString()}건 삭제</p>
                        <div className="flex flex-wrap gap-2 mt-1">
                            {Object.entries(result.deleted).map(([table, cnt]) => (
                                <span key={table} className="px-2 py-0.5 rounded bg-green-500/20 text-green-300 text-xs">
                                    {table}: {cnt}건
                                </span>
                            ))}
                        </div>
                    </div>
                    <button onClick={() => setResult(null)} className="ml-auto text-green-500 hover:text-green-300">
                        <XCircle className="w-4 h-4" />
                    </button>
                </div>
            )}

            {error && (
                <div className="flex items-center gap-3 p-4 rounded-xl bg-red-500/10 border border-red-500/20 mb-6 text-sm text-red-400">
                    <XCircle className="w-4 h-4 shrink-0" />
                    {error}
                </div>
            )}

            {/* 카테고리 카드 */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {CATEGORIES.map(cat => {
                    const catStats = stats?.[statsKey[cat.target]];
                    const total = totalCount(catStats);
                    return (
                        <div
                            key={cat.target}
                            className={`bg-slate-900/60 border rounded-xl p-5 transition-colors ${cat.borderColor}`}
                        >
                            <div className="flex items-center gap-2 mb-4">
                                <Database className={`w-4 h-4 ${cat.color}`} />
                                <span className={`font-semibold text-sm ${cat.color}`}>{cat.label}</span>
                            </div>

                            {/* 테이블별 건수 */}
                            <div className="space-y-1.5 mb-5">
                                {loading ? (
                                    <div className="h-24 flex items-center justify-center">
                                        <RefreshCw className="w-4 h-4 text-slate-600 animate-spin" />
                                    </div>
                                ) : catStats ? (
                                    Object.entries(catStats).map(([table, cnt]) => (
                                        <div key={table} className="flex items-center justify-between text-xs">
                                            <span className="text-slate-500 font-mono">{table}</span>
                                            <span className={cnt > 0 ? 'text-slate-300 font-semibold' : 'text-slate-600'}>
                                                {cnt >= 0 ? cnt.toLocaleString() : '—'}건
                                            </span>
                                        </div>
                                    ))
                                ) : (
                                    <p className="text-xs text-slate-600">조회 실패</p>
                                )}
                            </div>

                            <div className="border-t border-slate-800 pt-4 flex items-center justify-between">
                                <span className="text-xs text-slate-500">
                                    총 <span className="text-slate-300 font-semibold">{total.toLocaleString()}</span>건
                                </span>
                                <button
                                    onClick={() => openModal(cat.target)}
                                    disabled={total === 0 && !loading}
                                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-500/10 text-red-400 hover:bg-red-500/20 text-xs font-medium transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                                >
                                    <Trash2 className="w-3 h-3" />
                                    초기화
                                </button>
                            </div>
                        </div>
                    );
                })}
            </div>

            {/* 비밀번호 확인 모달 */}
            {modalTarget && category && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
                    <div className="bg-slate-900 border border-slate-700 rounded-2xl p-6 w-full max-w-sm shadow-2xl">
                        <div className="flex items-center gap-3 mb-4">
                            <div className="w-9 h-9 rounded-lg bg-red-500/10 flex items-center justify-center">
                                <Lock className="w-5 h-5 text-red-400" />
                            </div>
                            <div>
                                <h2 className="font-bold text-slate-100">초기화 확인</h2>
                                <p className={`text-xs mt-0.5 ${category.color}`}>{category.label} 데이터 삭제</p>
                            </div>
                        </div>

                        <p className="text-sm text-slate-400 mb-4">
                            <span className={`font-semibold ${category.color}`}>{category.label}</span>의 모든 데이터가 삭제됩니다.
                            계속하려면 비밀번호를 입력하세요.
                        </p>

                        {/* 삭제될 테이블 목록 */}
                        <div className="bg-slate-800/60 rounded-lg p-3 mb-4">
                            <p className="text-xs text-slate-500 mb-1.5">삭제될 테이블</p>
                            <div className="flex flex-wrap gap-1.5">
                                {category.tables.map(t => (
                                    <span key={t} className="px-2 py-0.5 rounded bg-slate-700 text-slate-400 text-xs font-mono">{t}</span>
                                ))}
                            </div>
                        </div>

                        {/* 비밀번호 입력 */}
                        <div className="relative mb-2">
                            <input
                                type={showPw ? 'text' : 'password'}
                                value={password}
                                onChange={e => { setPassword(e.target.value); setPwError(''); }}
                                onKeyDown={e => e.key === 'Enter' && handleReset()}
                                placeholder="비밀번호 입력"
                                autoFocus
                                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2.5 pr-10 text-sm text-slate-200 placeholder-slate-600 focus:outline-none focus:border-red-500/50 transition-colors"
                            />
                            <button
                                type="button"
                                onClick={() => setShowPw(v => !v)}
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-300"
                            >
                                {showPw ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                            </button>
                        </div>
                        {pwError && (
                            <p className="text-xs text-red-400 mb-3 flex items-center gap-1">
                                <XCircle className="w-3 h-3" /> {pwError}
                            </p>
                        )}

                        <div className="flex gap-2 mt-4">
                            <button
                                onClick={closeModal}
                                disabled={resetting}
                                className="flex-1 py-2 rounded-lg bg-slate-800 text-slate-400 hover:text-slate-200 text-sm transition-colors disabled:opacity-50"
                            >
                                취소
                            </button>
                            <button
                                onClick={handleReset}
                                disabled={resetting || !password}
                                className="flex-1 py-2 rounded-lg bg-red-500/20 text-red-400 hover:bg-red-500/30 text-sm font-semibold transition-colors disabled:opacity-50 flex items-center justify-center gap-2"
                            >
                                {resetting
                                    ? <><RefreshCw className="w-3.5 h-3.5 animate-spin" /> 처리 중...</>
                                    : <><Trash2 className="w-3.5 h-3.5" /> 초기화</>
                                }
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
