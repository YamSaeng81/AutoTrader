'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { accountApi } from '@/lib/api';
import type { AccountSummary, UpbitHolding } from '@/lib/types';
import { RefreshCw, Wallet, TrendingUp, TrendingDown, AlertCircle, Key, Lock, Coins } from 'lucide-react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from 'recharts';

const fmt = (n: number) => new Intl.NumberFormat('ko-KR').format(Math.round(n));
const fmtPct = (n: number) => (n >= 0 ? '+' : '') + n.toFixed(2) + '%';

const COLORS = ['#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];

function StatCard({ label, value, sub, color }: { label: string; value: string; sub?: string; color?: string }) {
    return (
        <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
            <p className="text-xs text-slate-400 mb-1">{label}</p>
            <p className={`text-2xl font-bold ${color ?? 'text-slate-100'}`}>{value}</p>
            {sub && <p className="text-xs text-slate-500 mt-1">{sub}</p>}
        </div>
    );
}

function HoldingRow({ h, index }: { h: UpbitHolding; index: number }) {
    const pnlColor = h.unrealizedPnl >= 0 ? 'text-emerald-400' : 'text-red-400';
    const pnlIcon = h.unrealizedPnl >= 0
        ? <TrendingUp className="w-3 h-3 inline mr-0.5" />
        : <TrendingDown className="w-3 h-3 inline mr-0.5" />;
    const dot = COLORS[index % COLORS.length];

    return (
        <tr className="border-b border-slate-700/50 hover:bg-slate-700/30 transition-colors">
            <td className="px-4 py-3">
                <div className="flex items-center gap-2">
                    <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ background: dot }} />
                    <span className="font-semibold text-slate-100">{h.currency}</span>
                    <span className="text-xs text-slate-500">{h.market}</span>
                </div>
            </td>
            <td className="px-4 py-3 text-right text-slate-300 font-mono text-sm">
                {h.totalQuantity.toLocaleString('ko-KR', { maximumFractionDigits: 8 })}
                {h.locked > 0 && (
                    <div className="text-xs text-amber-400 flex items-center justify-end gap-0.5">
                        <Lock className="w-3 h-3" /> {h.locked.toLocaleString('ko-KR', { maximumFractionDigits: 8 })} 묶임
                    </div>
                )}
            </td>
            <td className="px-4 py-3 text-right text-slate-300 font-mono text-sm">
                {fmt(h.avgBuyPrice)} <span className="text-slate-500 text-xs">KRW</span>
            </td>
            <td className="px-4 py-3 text-right text-slate-300 font-mono text-sm">
                {fmt(h.currentPrice)} <span className="text-slate-500 text-xs">KRW</span>
            </td>
            <td className="px-4 py-3 text-right">
                <p className="text-slate-100 font-semibold font-mono text-sm">{fmt(h.evalValue)} <span className="text-slate-500 text-xs">KRW</span></p>
                <p className="text-xs text-slate-500">(매수 {fmt(h.buyCost)})</p>
            </td>
            <td className="px-4 py-3 text-right">
                <p className={`font-semibold font-mono text-sm ${pnlColor}`}>
                    {pnlIcon}{fmt(Math.abs(h.unrealizedPnl))} KRW
                </p>
                <p className={`text-xs ${pnlColor}`}>{fmtPct(h.unrealizedPnlPct)}</p>
            </td>
        </tr>
    );
}

export default function AccountPage() {
    const [summary, setSummary] = useState<AccountSummary | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [refreshing, setRefreshing] = useState(false);
    const [lastFetch, setLastFetch] = useState<Date | null>(null);

    const load = useCallback(async (silent = false) => {
        if (!silent) setLoading(true);
        else setRefreshing(true);
        setError(null);
        try {
            const res = await accountApi.summary();
            if (res.success && res.data) {
                setSummary(res.data);
                setLastFetch(new Date());
            } else {
                setError(res.error?.message ?? '데이터 로드 실패');
            }
        } catch (e: unknown) {
            setError(e instanceof Error ? e.message : '네트워크 오류');
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    // 파이 차트 데이터
    const pieData = React.useMemo(() => {
        if (!summary?.apiKeyConfigured || !summary.totalAssetKrw) return [];
        const data: { name: string; value: number }[] = [];
        if (summary.totalKrwBalance && summary.totalKrwBalance > 0) {
            data.push({ name: 'KRW', value: Number(summary.totalKrwBalance) });
        }
        (summary.holdings ?? []).forEach(h => {
            if (h.evalValue > 0) data.push({ name: h.currency, value: h.evalValue });
        });
        return data;
    }, [summary]);

    if (loading) {
        return (
            <div className="flex items-center justify-center h-64 text-slate-400">
                <RefreshCw className="w-6 h-6 animate-spin mr-2" /> 계좌 정보 조회 중...
            </div>
        );
    }

    if (error) {
        return (
            <div className="max-w-2xl mx-auto mt-16 bg-red-900/20 border border-red-500/30 rounded-xl p-6 text-center">
                <AlertCircle className="w-8 h-8 text-red-400 mx-auto mb-2" />
                <p className="text-red-400 font-medium">{error}</p>
                <button onClick={() => load()} className="mt-4 px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded-lg text-sm transition-colors">
                    다시 시도
                </button>
            </div>
        );
    }

    if (!summary?.apiKeyConfigured) {
        return (
            <div className="max-w-xl mx-auto mt-16">
                <div className="bg-amber-900/20 border border-amber-500/30 rounded-xl p-8 text-center">
                    <Key className="w-10 h-10 text-amber-400 mx-auto mb-3" />
                    <h2 className="text-lg font-semibold text-slate-100 mb-2">API Key 미설정</h2>
                    <p className="text-slate-400 text-sm leading-relaxed">{summary?.message ?? 'Upbit API Key가 설정되지 않았습니다.'}</p>
                    <div className="mt-4 bg-slate-800 rounded-lg p-4 text-left text-xs text-slate-400 font-mono">
                        <p className="text-slate-300 mb-1">환경변수 설정 방법:</p>
                        <p>UPBIT_ACCESS_KEY=your_access_key</p>
                        <p>UPBIT_SECRET_KEY=your_secret_key</p>
                    </div>
                </div>
            </div>
        );
    }

    if (summary.error) {
        return (
            <div className="max-w-2xl mx-auto mt-16 bg-red-900/20 border border-red-500/30 rounded-xl p-6 text-center">
                <AlertCircle className="w-8 h-8 text-red-400 mx-auto mb-2" />
                <p className="text-red-400">{summary.error}</p>
            </div>
        );
    }

    const pnlColor = (summary.totalUnrealizedPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-red-400';
    const krwRatio = summary.totalAssetKrw && summary.totalAssetKrw > 0
        ? ((summary.totalKrwBalance ?? 0) / summary.totalAssetKrw * 100).toFixed(1)
        : '0';
    const coinRatio = summary.totalAssetKrw && summary.totalAssetKrw > 0
        ? ((summary.totalCoinValueKrw ?? 0) / summary.totalAssetKrw * 100).toFixed(1)
        : '0';

    return (
        <div className="max-w-7xl mx-auto space-y-6">
            {/* 헤더 */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-indigo-500/20 flex items-center justify-center">
                        <Wallet className="w-5 h-5 text-indigo-400" />
                    </div>
                    <div>
                        <h1 className="text-xl font-bold text-slate-100">Upbit 계좌 현황</h1>
                        {lastFetch && (
                            <p className="text-xs text-slate-500">
                                마지막 업데이트: {lastFetch.toLocaleTimeString('ko-KR')}
                            </p>
                        )}
                    </div>
                </div>
                <button
                    onClick={() => load(true)}
                    disabled={refreshing}
                    className="flex items-center gap-2 px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-200 rounded-lg text-sm transition-colors disabled:opacity-50"
                >
                    <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
                    새로고침
                </button>
            </div>

            {/* 요약 카드 4개 */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                <StatCard
                    label="총 보유자산"
                    value={`${fmt(summary.totalAssetKrw ?? 0)} KRW`}
                    sub={`KRW ${krwRatio}% / 코인 ${coinRatio}%`}
                />
                <StatCard
                    label="KRW 잔고"
                    value={`${fmt(summary.availableKrw ?? 0)} KRW`}
                    sub={summary.lockedKrw && summary.lockedKrw > 0 ? `묶인 금액: ${fmt(summary.lockedKrw)} KRW` : '주문 가능 잔고'}
                />
                <StatCard
                    label="코인 평가금액"
                    value={`${fmt(summary.totalCoinValueKrw ?? 0)} KRW`}
                    sub={`매수금액: ${fmt(summary.totalBuyCostKrw ?? 0)} KRW`}
                />
                <StatCard
                    label="총 평가손익"
                    value={`${(summary.totalUnrealizedPnl ?? 0) >= 0 ? '+' : ''}${fmt(summary.totalUnrealizedPnl ?? 0)} KRW`}
                    sub={fmtPct(summary.totalUnrealizedPnlPct ?? 0)}
                    color={pnlColor}
                />
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                {/* 자산 구성 파이 차트 */}
                {pieData.length > 0 && (
                    <div className="bg-slate-800 border border-slate-700 rounded-xl p-5">
                        <div className="flex items-center gap-2 mb-4">
                            <Coins className="w-4 h-4 text-indigo-400" />
                            <h2 className="text-sm font-semibold text-slate-200">자산 구성</h2>
                        </div>
                        <ResponsiveContainer width="100%" height={220}>
                            <PieChart>
                                <Pie
                                    data={pieData}
                                    cx="50%"
                                    cy="50%"
                                    innerRadius={55}
                                    outerRadius={85}
                                    paddingAngle={2}
                                    dataKey="value"
                                >
                                    {pieData.map((_, i) => (
                                        <Cell key={i} fill={COLORS[i % COLORS.length]} />
                                    ))}
                                </Pie>
                                <Tooltip
                                    formatter={(v: number) => [`${fmt(v)} KRW`, '']}
                                    contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }}
                                    labelStyle={{ color: '#94a3b8' }}
                                />
                                <Legend
                                    formatter={(value) => <span style={{ color: '#94a3b8', fontSize: 11 }}>{value}</span>}
                                />
                            </PieChart>
                        </ResponsiveContainer>
                        {/* 구성비 목록 */}
                        <div className="mt-2 space-y-1.5">
                            {pieData.map((d, i) => (
                                <div key={d.name} className="flex items-center justify-between text-xs">
                                    <div className="flex items-center gap-1.5">
                                        <span className="w-2 h-2 rounded-full" style={{ background: COLORS[i % COLORS.length] }} />
                                        <span className="text-slate-300">{d.name}</span>
                                    </div>
                                    <span className="text-slate-400">
                                        {((d.value / (summary.totalAssetKrw ?? 1)) * 100).toFixed(1)}%
                                    </span>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                {/* 빠른 현황 요약 */}
                <div className="lg:col-span-2 bg-slate-800 border border-slate-700 rounded-xl p-5">
                    <div className="flex items-center gap-2 mb-4">
                        <TrendingUp className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">보유 현황 요약</h2>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-xs text-slate-400">보유 코인 수</p>
                            <p className="text-lg font-bold text-slate-100 mt-0.5">{(summary.holdings ?? []).length}종</p>
                        </div>
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-xs text-slate-400">수익 코인 / 손실 코인</p>
                            <p className="text-lg font-bold text-slate-100 mt-0.5">
                                <span className="text-emerald-400">{(summary.holdings ?? []).filter(h => h.unrealizedPnl >= 0).length}</span>
                                <span className="text-slate-500 mx-1">/</span>
                                <span className="text-red-400">{(summary.holdings ?? []).filter(h => h.unrealizedPnl < 0).length}</span>
                            </p>
                        </div>
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-xs text-slate-400">주문 가능 KRW</p>
                            <p className="text-base font-bold text-slate-100 mt-0.5">{fmt(summary.availableKrw ?? 0)}</p>
                        </div>
                        <div className="bg-slate-700/50 rounded-lg p-3">
                            <p className="text-xs text-slate-400">총 매수금액</p>
                            <p className="text-base font-bold text-slate-100 mt-0.5">{fmt(summary.totalBuyCostKrw ?? 0)}</p>
                        </div>
                        {/* 수익률 프로그레스 바 */}
                        <div className="col-span-2 bg-slate-700/50 rounded-lg p-3">
                            <div className="flex justify-between items-center mb-1">
                                <p className="text-xs text-slate-400">전체 코인 평가손익</p>
                                <p className={`text-sm font-bold ${pnlColor}`}>{fmtPct(summary.totalUnrealizedPnlPct ?? 0)}</p>
                            </div>
                            <div className="w-full bg-slate-600 rounded-full h-2">
                                <div
                                    className={`h-2 rounded-full transition-all ${(summary.totalUnrealizedPnl ?? 0) >= 0 ? 'bg-emerald-500' : 'bg-red-500'}`}
                                    style={{ width: `${Math.min(100, Math.abs(summary.totalUnrealizedPnlPct ?? 0))}%` }}
                                />
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* 보유 코인 상세 테이블 */}
            {(summary.holdings ?? []).length > 0 ? (
                <div className="bg-slate-800 border border-slate-700 rounded-xl overflow-hidden">
                    <div className="px-5 py-4 border-b border-slate-700 flex items-center gap-2">
                        <Coins className="w-4 h-4 text-indigo-400" />
                        <h2 className="text-sm font-semibold text-slate-200">보유 코인 상세</h2>
                        <span className="ml-auto text-xs text-slate-500">{(summary.holdings ?? []).length}종 · 평가금액 순</span>
                    </div>
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="text-xs text-slate-400 border-b border-slate-700 bg-slate-800/80">
                                    <th className="px-4 py-3 text-left">코인</th>
                                    <th className="px-4 py-3 text-right">보유수량</th>
                                    <th className="px-4 py-3 text-right">매수평균가</th>
                                    <th className="px-4 py-3 text-right">현재가</th>
                                    <th className="px-4 py-3 text-right">평가금액</th>
                                    <th className="px-4 py-3 text-right">평가손익</th>
                                </tr>
                            </thead>
                            <tbody>
                                {(summary.holdings ?? []).map((h, i) => (
                                    <HoldingRow key={h.currency} h={h} index={i} />
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            ) : (
                <div className="bg-slate-800 border border-slate-700 rounded-xl p-10 text-center">
                    <Coins className="w-8 h-8 text-slate-600 mx-auto mb-2" />
                    <p className="text-slate-500">보유 중인 코인이 없습니다.</p>
                    <p className="text-xs text-slate-600 mt-1">KRW 잔고: {fmt(summary.totalKrwBalance ?? 0)} KRW</p>
                </div>
            )}
        </div>
    );
}
