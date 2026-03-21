'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { tradingApi, paperTradingApi } from '@/lib/api';
import type { PerformanceSummary, SessionPerformance } from '@/lib/types';

type Tab = 'live' | 'paper';

function fmt(n: number | null | undefined, decimals = 0): string {
    if (n == null) return '-';
    return n.toLocaleString('ko-KR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

function pnlClass(n: number | null | undefined): string {
    if (n == null) return 'text-slate-400';
    if (n > 0) return 'text-green-400';
    if (n < 0) return 'text-red-400';
    return 'text-slate-400';
}

function SummaryCard({ label, value, sub }: { label: string; value: React.ReactNode; sub?: React.ReactNode }) {
    return (
        <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
            <p className="text-xs text-slate-400 mb-1">{label}</p>
            <div className="text-lg font-bold text-white">{value}</div>
            {sub && <div className="text-xs text-slate-400 mt-0.5">{sub}</div>}
        </div>
    );
}

function SessionTable({ sessions }: { sessions: SessionPerformance[] }) {
    if (sessions.length === 0) {
        return <p className="text-center text-slate-500 py-12">데이터가 없습니다.</p>;
    }

    return (
        <div className="overflow-x-auto">
            <table className="w-full text-sm">
                <thead>
                    <tr className="text-xs text-slate-400 border-b border-slate-700">
                        <th className="text-left py-3 px-4">전략</th>
                        <th className="text-left py-3 px-4">코인</th>
                        <th className="text-left py-3 px-4">타임프레임</th>
                        <th className="text-left py-3 px-4">상태</th>
                        <th className="text-right py-3 px-4">투자원금</th>
                        <th className="text-right py-3 px-4">실현손익</th>
                        <th className="text-right py-3 px-4">미실현손익</th>
                        <th className="text-right py-3 px-4">수익률</th>
                        <th className="text-right py-3 px-4">거래수</th>
                        <th className="text-right py-3 px-4">승률</th>
                        <th className="text-right py-3 px-4">수수료</th>
                        <th className="text-left py-3 px-4">시작일</th>
                    </tr>
                </thead>
                <tbody>
                    {sessions.map((s) => (
                        <tr key={s.sessionId} className="border-b border-slate-700/50 hover:bg-slate-700/30 transition-colors">
                            <td className="py-3 px-4 font-medium text-slate-200">{s.strategyType}</td>
                            <td className="py-3 px-4 text-slate-300">{s.coinPair}</td>
                            <td className="py-3 px-4 text-slate-400">{s.timeframe}</td>
                            <td className="py-3 px-4">
                                <span className={`text-xs px-2 py-0.5 rounded-full ${
                                    s.status === 'RUNNING' ? 'bg-green-500/20 text-green-400' : 'bg-slate-600/50 text-slate-400'
                                }`}>
                                    {s.status === 'RUNNING' ? '실행중' : '중단'}
                                </span>
                            </td>
                            <td className="py-3 px-4 text-right text-slate-300">{fmt(s.initialCapital)}원</td>
                            <td className={`py-3 px-4 text-right font-medium ${pnlClass(s.realizedPnl)}`}>
                                {s.realizedPnl > 0 ? '+' : ''}{fmt(s.realizedPnl)}원
                            </td>
                            <td className={`py-3 px-4 text-right ${pnlClass(s.unrealizedPnl)}`}>
                                {s.unrealizedPnl > 0 ? '+' : ''}{fmt(s.unrealizedPnl)}원
                            </td>
                            <td className={`py-3 px-4 text-right font-medium ${pnlClass(s.returnRatePct)}`}>
                                {s.returnRatePct > 0 ? '+' : ''}{fmt(s.returnRatePct, 2)}%
                            </td>
                            <td className="py-3 px-4 text-right text-slate-300">{s.totalTrades}</td>
                            <td className="py-3 px-4 text-right text-slate-300">
                                {s.totalTrades > 0 ? `${fmt(s.winRatePct, 1)}%` : '-'}
                            </td>
                            <td className="py-3 px-4 text-right text-slate-400">{fmt(s.totalFee)}원</td>
                            <td className="py-3 px-4 text-slate-400 text-xs">
                                {s.startedAt ? s.startedAt.substring(0, 10) : '-'}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

export default function PerformancePage() {
    const [tab, setTab] = useState<Tab>('live');
    const [liveData, setLiveData] = useState<PerformanceSummary | null>(null);
    const [paperData, setPaperData] = useState<PerformanceSummary | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const [live, paper] = await Promise.all([
                tradingApi.getPerformance(),
                paperTradingApi.getPerformance(),
            ]);
            if (live.data) setLiveData(live.data);
            if (paper.data) setPaperData(paper.data);
        } catch {
            setError('데이터를 불러오는 중 오류가 발생했습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    const data = tab === 'live' ? liveData : paperData;

    return (
        <div className="p-6 space-y-6 max-w-screen-xl">
            <div className="flex items-center justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-white">손익 대시보드</h1>
                    <p className="text-sm text-slate-400 mt-1">전체 매매 성과 통계</p>
                </div>
                <button
                    onClick={load}
                    disabled={loading}
                    className="px-4 py-2 text-sm bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 text-white rounded-lg transition-colors"
                >
                    {loading ? '로딩...' : '새로고침'}
                </button>
            </div>

            {/* 탭 */}
            <div className="flex gap-1 bg-slate-800 rounded-lg p-1 w-fit">
                {(['live', 'paper'] as Tab[]).map(t => (
                    <button
                        key={t}
                        onClick={() => setTab(t)}
                        className={`px-5 py-2 rounded-md text-sm font-medium transition-colors ${
                            tab === t
                                ? 'bg-indigo-600 text-white'
                                : 'text-slate-400 hover:text-slate-200'
                        }`}
                    >
                        {t === 'live' ? '실전매매' : '모의투자'}
                    </button>
                ))}
            </div>

            {error && (
                <div className="bg-red-900/30 border border-red-700/50 text-red-300 rounded-lg px-4 py-3 text-sm">
                    {error}
                </div>
            )}

            {loading && !data ? (
                <div className="text-center text-slate-400 py-20">로딩 중...</div>
            ) : data ? (
                <>
                    {/* 요약 카드 */}
                    <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-3">
                        <SummaryCard
                            label="총 투자원금"
                            value={`${fmt(data.totalInitialCapital)}원`}
                        />
                        <SummaryCard
                            label="실현손익"
                            value={
                                <span className={pnlClass(data.totalRealizedPnl)}>
                                    {data.totalRealizedPnl > 0 ? '+' : ''}{fmt(data.totalRealizedPnl)}원
                                </span>
                            }
                        />
                        <SummaryCard
                            label="미실현손익"
                            value={
                                <span className={pnlClass(data.totalUnrealizedPnl)}>
                                    {data.totalUnrealizedPnl > 0 ? '+' : ''}{fmt(data.totalUnrealizedPnl)}원
                                </span>
                            }
                        />
                        <SummaryCard
                            label="총 손익"
                            value={
                                <span className={pnlClass(data.totalPnl)}>
                                    {data.totalPnl > 0 ? '+' : ''}{fmt(data.totalPnl)}원
                                </span>
                            }
                        />
                        <SummaryCard
                            label="수익률"
                            value={
                                <span className={pnlClass(data.returnRatePct)}>
                                    {data.returnRatePct > 0 ? '+' : ''}{fmt(data.returnRatePct, 2)}%
                                </span>
                            }
                        />
                        <SummaryCard
                            label="승률"
                            value={`${fmt(data.winRatePct, 1)}%`}
                            sub={`${data.winCount}승 ${data.lossCount}패`}
                        />
                        <SummaryCard
                            label="총 거래수 / 수수료"
                            value={`${data.totalTrades}건`}
                            sub={`수수료 ${fmt(data.totalFee)}원`}
                        />
                    </div>

                    {/* 세션별 테이블 */}
                    <div className="bg-slate-800 rounded-xl border border-slate-700/50">
                        <div className="px-5 py-4 border-b border-slate-700/50">
                            <h2 className="text-sm font-semibold text-white">
                                세션별 성과 ({data.sessions.length}개)
                            </h2>
                        </div>
                        <SessionTable sessions={data.sessions} />
                    </div>
                </>
            ) : (
                <div className="text-center text-slate-400 py-20">데이터가 없습니다.</div>
            )}
        </div>
    );
}
