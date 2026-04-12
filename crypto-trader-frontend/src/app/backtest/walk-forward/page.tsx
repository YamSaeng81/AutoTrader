'use client';

import { useState, useEffect, useMemo } from 'react';
import { strategyApi, backtestApi, systemApi } from '@/lib/api';
import { StrategyInfo, Timeframe, WalkForwardResult } from '@/lib/types';
import { Play, Loader2, AlertTriangle, CheckCircle, History, Trash2, ChevronUp, ChevronDown, ChevronsUpDown } from 'lucide-react';
import { cn } from '@/lib/utils';
import { format } from 'date-fns';
import {
    BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend,
} from 'recharts';

const today = new Date().toISOString().slice(0, 10);
const threeYearsAgo = `${new Date().getFullYear() - 3}-01-01`;

type WFSortKey = 'createdAt' | 'overfittingScore';
type SortDir = 'asc' | 'desc';

function SortIcon({ col, sortKey, sortDir }: { col: WFSortKey; sortKey: WFSortKey; sortDir: SortDir }) {
    if (col !== sortKey) return <ChevronsUpDown className="w-3 h-3 opacity-30 inline ml-0.5" />;
    return sortDir === 'asc'
        ? <ChevronUp className="w-3 h-3 inline ml-0.5" />
        : <ChevronDown className="w-3 h-3 inline ml-0.5" />;
}

const VERDICT_CONFIG = {
    ACCEPTABLE: { label: '통과', color: 'text-emerald-600 dark:text-emerald-400', bg: 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-200 dark:border-emerald-700', icon: CheckCircle },
    CAUTION: { label: '주의', color: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-50 dark:bg-amber-900/30 border-amber-200 dark:border-amber-700', icon: AlertTriangle },
    OVERFITTING: { label: '과적합 경고', color: 'text-rose-600 dark:text-rose-400', bg: 'bg-rose-50 dark:bg-rose-900/30 border-rose-200 dark:border-rose-700', icon: AlertTriangle },
};

export default function WalkForwardPage() {
    const [tab, setTab] = useState<'run' | 'history'>('run');
    const [loading, setLoading] = useState(false);
    const [historyLoading, setHistoryLoading] = useState(false);
    const [strategies, setStrategies] = useState<StrategyInfo[]>([]);
    const [coins, setCoins] = useState<string[]>([]);
    const [result, setResult] = useState<WalkForwardResult | null>(null);
    const [history, setHistory] = useState<WalkForwardResult[]>([]);
    const [selectedHistory, setSelectedHistory] = useState<WalkForwardResult | null>(null);
    const [deletingId, setDeletingId] = useState<number | null>(null);
    const [filterStrategy, setFilterStrategy] = useState('');
    const [filterCoin, setFilterCoin] = useState('');
    const [filterVerdict, setFilterVerdict] = useState('');
    const [sortKey, setSortKey] = useState<WFSortKey>('createdAt');
    const [sortDir, setSortDir] = useState<SortDir>('desc');

    const historyStrategies = useMemo(() => [...new Set(history.map(h => h.strategyType ?? ''))].filter(Boolean).sort(), [history]);
    const historyCoins = useMemo(() => [...new Set(history.map(h => h.coinPair ?? ''))].filter(Boolean).sort(), [history]);

    const filteredHistory = useMemo(() => {
        let list = history;
        if (filterStrategy) list = list.filter(h => h.strategyType === filterStrategy);
        if (filterCoin) list = list.filter(h => h.coinPair === filterCoin);
        if (filterVerdict) list = list.filter(h => h.verdict === filterVerdict);
        return [...list].sort((a, b) => {
            const av = sortKey === 'createdAt'
                ? new Date(a.createdAt ?? 0).getTime()
                : Number(a.overfittingScore ?? 0);
            const bv = sortKey === 'createdAt'
                ? new Date(b.createdAt ?? 0).getTime()
                : Number(b.overfittingScore ?? 0);
            return sortDir === 'asc' ? av - bv : bv - av;
        });
    }, [history, filterStrategy, filterCoin, filterVerdict, sortKey, sortDir]);

    function toggleSort(key: WFSortKey) {
        if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
        else { setSortKey(key); setSortDir('desc'); }
    }

    const [form, setForm] = useState({
        strategyType: 'EMA_CROSS',
        coinPair: 'KRW-BTC',
        timeframe: 'H1' as Timeframe,
        startDate: threeYearsAgo,
        endDate: today,
        inSampleRatio: 0.7,
        windowCount: 3,
    });

    useEffect(() => {
        Promise.all([strategyApi.list(), systemApi.coins()]).then(([stRes, cRes]) => {
            if (stRes.success && stRes.data) {
                const available = stRes.data.filter(s => s.status === 'AVAILABLE' && s.isActive);
                setStrategies(available);
                if (available.length > 0) setForm(f => ({ ...f, strategyType: available[0].name }));
            }
            if (cRes.success && cRes.data) setCoins(cRes.data);
        });
    }, []);

    useEffect(() => {
        if (tab === 'history') {
            setHistoryLoading(true);
            backtestApi.walkForwardHistory()
                .then(res => { if (res.success && res.data) setHistory(res.data); })
                .finally(() => setHistoryLoading(false));
        }
    }, [tab]);

    const handleDelete = async (id: number) => {
        if (!confirm('이 이력을 삭제하시겠습니까?')) return;
        setDeletingId(id);
        try {
            await backtestApi.delete(id);
            setHistory(prev => prev.filter(h => h.id !== id));
            if (selectedHistory?.id === id) setSelectedHistory(null);
        } catch {
            alert('삭제에 실패했습니다.');
        } finally {
            setDeletingId(null);
        }
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setResult(null);

        try {
            const res = await backtestApi.walkForward({
                ...form,
                startDate: new Date(form.startDate).toISOString(),
                endDate: new Date(form.endDate).toISOString(),
            });

            if (res.success && res.data) {
                setResult(res.data as WalkForwardResult);
            } else {
                alert(res.error?.message || '실행 실패');
            }
        } catch {
            alert('오류가 발생했습니다.');
        } finally {
            setLoading(false);
        }
    };

    const displayResult = selectedHistory ?? result;

    const buildChartData = (r: WalkForwardResult | null) =>
        r?.windows.map((w, i) => ({
            name: `Window ${i + 1}`,
            inSample: Number(w.inSample?.totalReturn ?? 0),
            outSample: Number(w.outSample?.totalReturn ?? 0),
        })) ?? [];

    const chartData = buildChartData(displayResult);
    const verdictCfg = displayResult ? VERDICT_CONFIG[displayResult.verdict] : null;
    const VerdictIcon = verdictCfg?.icon ?? CheckCircle;

    return (
        <div className="max-w-4xl mx-auto py-4 space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {/* 탭 */}
            <div className="flex gap-1 bg-slate-100 dark:bg-slate-800 p-1 rounded-xl w-fit">
                <button onClick={() => { setTab('run'); setSelectedHistory(null); }}
                    className={cn('px-5 py-2 rounded-lg text-sm font-semibold transition-all',
                        tab === 'run' ? 'bg-white dark:bg-slate-700 shadow-sm text-slate-800 dark:text-slate-100' : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300')}>
                    <span className="flex items-center gap-1.5"><Play className="w-3.5 h-3.5" /> 실행</span>
                </button>
                <button onClick={() => setTab('history')}
                    className={cn('px-5 py-2 rounded-lg text-sm font-semibold transition-all',
                        tab === 'history' ? 'bg-white dark:bg-slate-700 shadow-sm text-slate-800 dark:text-slate-100' : 'text-slate-500 hover:text-slate-700 dark:hover:text-slate-300')}>
                    <span className="flex items-center gap-1.5"><History className="w-3.5 h-3.5" /> 이력</span>
                </button>
            </div>

            {/* 이력 탭 */}
            {tab === 'history' && (
                <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                    <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                        <h2 className="text-sm font-bold text-slate-700 dark:text-slate-200">Walk Forward 실행 이력</h2>
                    </div>
                    {historyLoading ? (
                        <div className="p-10 flex justify-center"><Loader2 className="w-6 h-6 animate-spin text-indigo-500" /></div>
                    ) : history.length === 0 ? (
                        <div className="p-10 text-center text-slate-400 dark:text-slate-500 text-sm">이력이 없습니다.</div>
                    ) : (
                        <>
                        {/* 필터 바 */}
                        <div className="px-5 py-3 border-b border-slate-100 dark:border-slate-800 flex flex-wrap items-center gap-3 bg-slate-50/30 dark:bg-slate-800/30">
                            <select
                                value={filterStrategy}
                                onChange={e => setFilterStrategy(e.target.value)}
                                className="px-3 py-1.5 text-xs bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            >
                                <option value="">전체 전략</option>
                                {historyStrategies.map(s => <option key={s} value={s}>{s}</option>)}
                            </select>
                            <select
                                value={filterCoin}
                                onChange={e => setFilterCoin(e.target.value)}
                                className="px-3 py-1.5 text-xs bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            >
                                <option value="">전체 코인</option>
                                {historyCoins.map(c => <option key={c} value={c}>{c}</option>)}
                            </select>
                            <select
                                value={filterVerdict}
                                onChange={e => setFilterVerdict(e.target.value)}
                                className="px-3 py-1.5 text-xs bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-lg text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            >
                                <option value="">전체 판정</option>
                                <option value="ACCEPTABLE">통과</option>
                                <option value="CAUTION">주의</option>
                                <option value="OVERFITTING">과적합</option>
                            </select>
                            {(filterStrategy || filterCoin || filterVerdict) && (
                                <button
                                    onClick={() => { setFilterStrategy(''); setFilterCoin(''); setFilterVerdict(''); }}
                                    className="text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
                                >
                                    초기화
                                </button>
                            )}
                            <span className="ml-auto text-xs text-slate-400 dark:text-slate-500">
                                {filteredHistory.length} / {history.length}건
                            </span>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full text-sm">
                                <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                                    <tr>
                                        <th className="px-5 py-3 text-left">전략</th>
                                        <th className="px-5 py-3 text-left">코인</th>
                                        <th className="px-5 py-3 text-left">타임프레임</th>
                                        <th className="px-5 py-3 text-right cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200" onClick={() => toggleSort('overfittingScore')}>
                                            과적합 점수 <SortIcon col="overfittingScore" sortKey={sortKey} sortDir={sortDir} />
                                        </th>
                                        <th className="px-5 py-3 text-left">판정</th>
                                        <th className="px-5 py-3 text-left cursor-pointer select-none hover:text-slate-700 dark:hover:text-slate-200" onClick={() => toggleSort('createdAt')}>
                                            실행일 <SortIcon col="createdAt" sortKey={sortKey} sortDir={sortDir} />
                                        </th>
                                        <th className="px-5 py-3"></th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                    {filteredHistory.length === 0 ? (
                                        <tr><td colSpan={7} className="px-5 py-10 text-center text-slate-400 dark:text-slate-500 text-sm">필터 조건에 맞는 이력이 없습니다.</td></tr>
                                    ) : filteredHistory.map((h, i) => {
                                        const cfg = VERDICT_CONFIG[h.verdict];
                                        const Icon = cfg.icon;
                                        return (
                                            <tr key={i} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50">
                                                <td className="px-5 py-3 font-medium text-slate-700 dark:text-slate-200">{h.strategyType}</td>
                                                <td className="px-5 py-3 text-slate-600 dark:text-slate-300">{h.coinPair}</td>
                                                <td className="px-5 py-3 text-slate-600 dark:text-slate-300">{h.timeframe}</td>
                                                <td className="px-5 py-3 text-right font-mono font-bold text-slate-700 dark:text-slate-200">
                                                    {(Number(h.overfittingScore) * 100).toFixed(1)}%
                                                </td>
                                                <td className="px-5 py-3">
                                                    <span className={cn('flex items-center gap-1 text-xs font-bold', cfg.color)}>
                                                        <Icon className="w-3.5 h-3.5" />{cfg.label}
                                                    </span>
                                                </td>
                                                <td className="px-5 py-3 text-slate-500 dark:text-slate-400 text-xs">
                                                    {h.createdAt ? format(new Date(h.createdAt), 'yyyy.MM.dd HH:mm') : '-'}
                                                </td>
                                                <td className="px-5 py-3">
                                                    <div className="flex items-center gap-3">
                                                        <button onClick={() => { setSelectedHistory(h); setTab('run'); }}
                                                            className="text-xs text-indigo-500 hover:text-indigo-700 font-semibold">
                                                            상세보기
                                                        </button>
                                                        <button
                                                            onClick={() => h.id && handleDelete(h.id)}
                                                            disabled={deletingId === h.id}
                                                            className="text-slate-400 hover:text-rose-500 transition-colors disabled:opacity-40"
                                                        >
                                                            {deletingId === h.id
                                                                ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                                                : <Trash2 className="w-3.5 h-3.5" />}
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                        </>
                    )}
                </div>
            )}

            {/* Form */}
            {tab === 'run' && <form onSubmit={handleSubmit} className="bg-white dark:bg-slate-900 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 space-y-8">
                <div>
                    <h2 className="text-xl font-bold text-slate-800 dark:text-slate-100 tracking-tight">Walk Forward Test</h2>
                    <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">데이터를 학습/검증 구간으로 분할하여 과적합 여부를 검증합니다.</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-6">
                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">전략 유형</label>
                        <select
                            className="w-full p-3 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.strategyType}
                            onChange={(e) => setForm({ ...form, strategyType: e.target.value })}
                        >
                            {strategies.map(s => <option key={s.name} value={s.name}>{s.name}</option>)}
                        </select>
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">코인 페어</label>
                        <select
                            className="w-full p-3 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.coinPair}
                            onChange={(e) => setForm({ ...form, coinPair: e.target.value })}
                        >
                            {coins.map(c => <option key={c} value={c}>{c}</option>)}
                        </select>
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">타임프레임</label>
                        <select
                            className="w-full p-3 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.timeframe}
                            onChange={(e) => setForm({ ...form, timeframe: e.target.value as Timeframe })}
                        >
                            <option value="M1">1분 (M1)</option>
                            <option value="M5">5분 (M5)</option>
                            <option value="H1">1시간 (H1)</option>
                            <option value="D1">1일 (D1)</option>
                        </select>
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">윈도우 수</label>
                        <input
                            type="number"
                            min={2}
                            max={10}
                            className="w-full p-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.windowCount}
                            onChange={(e) => setForm({ ...form, windowCount: Number(e.target.value) })}
                        />
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">In-Sample 비율</label>
                        <input
                            type="number"
                            step={0.05}
                            min={0.5}
                            max={0.9}
                            className="w-full p-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.inSampleRatio}
                            onChange={(e) => setForm({ ...form, inSampleRatio: Number(e.target.value) })}
                        />
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">시작일</label>
                        <input
                            type="date"
                            className="w-full p-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.startDate}
                            onChange={(e) => setForm({ ...form, startDate: e.target.value })}
                        />
                    </div>

                    <div className="space-y-2">
                        <label className="text-sm font-semibold text-slate-700 dark:text-slate-200 ml-1">종료일</label>
                        <input
                            type="date"
                            className="w-full p-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl text-sm font-medium text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500/50"
                            value={form.endDate}
                            onChange={(e) => setForm({ ...form, endDate: e.target.value })}
                        />
                    </div>
                </div>

                <div className="pt-6 border-t border-slate-100 dark:border-slate-800 flex justify-end">
                    <button
                        type="submit"
                        disabled={loading}
                        className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 px-8 rounded-xl shadow-md shadow-indigo-600/20 transition-all active:scale-[0.98] disabled:opacity-70 disabled:pointer-events-none"
                    >
                        {loading ? (
                            <><Loader2 className="w-5 h-5 animate-spin" /> 분석 중...</>
                        ) : (
                            <><Play className="w-5 h-5 fill-current" /> Walk Forward 실행</>
                        )}
                    </button>
                </div>
            </form>}

            {/* Result (실행 결과 or 이력에서 선택한 결과) */}
            {tab === 'run' && displayResult && (
                <>
                    {selectedHistory && (
                        <div className="flex items-center gap-2 text-sm text-indigo-600 dark:text-indigo-400 font-medium">
                            <History className="w-4 h-4" />
                            이력에서 불러온 결과 — {selectedHistory.strategyType} / {selectedHistory.coinPair}
                            <button onClick={() => setSelectedHistory(null)} className="ml-2 text-xs text-slate-400 hover:text-slate-600">✕ 닫기</button>
                        </div>
                    )}
                    {/* Verdict */}
                    <div className={cn('rounded-2xl border p-6 flex items-center gap-4', verdictCfg?.bg)}>
                        <VerdictIcon className={cn('w-8 h-8', verdictCfg?.color)} />
                        <div>
                            <div className={cn('text-lg font-bold', verdictCfg?.color)}>
                                {verdictCfg?.label}
                            </div>
                            <div className="text-sm text-slate-600 dark:text-slate-300 mt-0.5">
                                과적합 점수: <span className="font-mono font-bold">{(Number(displayResult.overfittingScore) * 100).toFixed(1)}%</span>
                                <span className="text-slate-400 dark:text-slate-500 ml-2">
                                    (30% 미만: 통과, 30~50%: 주의, 50% 초과: 과적합)
                                </span>
                            </div>
                        </div>
                    </div>

                    {/* Chart */}
                    <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                        <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                            <h2 className="text-sm font-bold text-slate-700 dark:text-slate-200">In-Sample vs Out-Sample 수익률</h2>
                        </div>
                        <div className="p-4" style={{ height: 300 }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={chartData} margin={{ top: 10, right: 20, bottom: 0, left: 0 }}>
                                    <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                                    <XAxis dataKey="name" tick={{ fontSize: 12, fill: '#94a3b8' }} />
                                    <YAxis tickFormatter={v => `${v}%`} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                                    <Tooltip formatter={(v: number) => [`${Number(v ?? 0).toFixed(2)}%`]} />
                                    <Legend />
                                    <Bar dataKey="inSample" name="In-Sample (학습)" fill="#6366f1" radius={[4, 4, 0, 0]} />
                                    <Bar dataKey="outSample" name="Out-Sample (검증)" fill="#10b981" radius={[4, 4, 0, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                        </div>
                    </div>

                    {/* Window Details */}
                    <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden">
                        <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/50">
                            <h2 className="text-sm font-bold text-slate-700 dark:text-slate-200">윈도우 상세</h2>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full text-sm">
                                <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-100 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                                    <tr>
                                        <th className="px-5 py-3 text-left">윈도우</th>
                                        <th className="px-5 py-3 text-left">In-Sample 기간</th>
                                        <th className="px-5 py-3 text-right">In-Sample 수익률</th>
                                        <th className="px-5 py-3 text-left">Out-Sample 기간</th>
                                        <th className="px-5 py-3 text-right">Out-Sample 수익률</th>
                                        <th className="px-5 py-3 text-right">하락률</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                    {displayResult.windows.map((w, i) => {
                                        const inPct  = Number(w.inSample?.totalReturn  ?? 0);
                                        const outPct = Number(w.outSample?.totalReturn ?? 0);
                                        const dropoff = inPct !== 0
                                            ? ((inPct - outPct) / Math.abs(inPct)) * 100
                                            : 0;
                                        const fmtDate = (s?: string) => s ? format(new Date(s), 'yy.MM.dd') : '-';
                                        return (
                                            <tr key={i} className="hover:bg-slate-50/50 dark:hover:bg-slate-800/50">
                                                <td className="px-5 py-3 font-medium text-slate-700 dark:text-slate-200">Window {i + 1}</td>
                                                <td className="px-5 py-3 text-slate-600 dark:text-slate-300 text-xs">{fmtDate(w.inSample?.start)} ~ {fmtDate(w.inSample?.end)}</td>
                                                <td className={cn('px-5 py-3 text-right font-mono font-bold', inPct >= 0 ? 'text-emerald-600' : 'text-rose-600')}>
                                                    {inPct >= 0 ? '+' : ''}{inPct.toFixed(2)}%
                                                </td>
                                                <td className="px-5 py-3 text-slate-600 dark:text-slate-300 text-xs">{fmtDate(w.outSample?.start)} ~ {fmtDate(w.outSample?.end)}</td>
                                                <td className={cn('px-5 py-3 text-right font-mono font-bold', outPct >= 0 ? 'text-emerald-600' : 'text-rose-600')}>
                                                    {outPct >= 0 ? '+' : ''}{outPct.toFixed(2)}%
                                                </td>
                                                <td className={cn('px-5 py-3 text-right font-mono text-sm', dropoff > 50 ? 'text-rose-600 font-bold' : dropoff > 30 ? 'text-amber-600' : 'text-slate-500 dark:text-slate-400')}>
                                                    {dropoff.toFixed(1)}%
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
