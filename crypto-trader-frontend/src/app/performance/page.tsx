'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { tradingApi, paperTradingApi, logsApi } from '@/lib/api';
import type { PerformanceSummary, SessionPerformance, RegimeStat, RegimeChangeLog } from '@/lib/types';

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

function ratioClass(n: number | null | undefined): string {
    if (n == null) return 'text-slate-400';
    if (n >= 1) return 'text-green-400';
    if (n >= 0) return 'text-yellow-400';
    return 'text-red-400';
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

const REGIME_COLOR: Record<string, string> = {
    TREND:        'bg-blue-500/20 text-blue-300 border-blue-500/30',
    RANGE:        'bg-purple-500/20 text-purple-300 border-purple-500/30',
    VOLATILITY:   'bg-orange-500/20 text-orange-300 border-orange-500/30',
    TRANSITIONAL: 'bg-slate-600/40 text-slate-400 border-slate-600/50',
    UNKNOWN:      'bg-slate-700/40 text-slate-500 border-slate-700/50',
};
const REGIME_LABEL: Record<string, string> = {
    TREND: '추세', RANGE: '횡보', VOLATILITY: '변동성', TRANSITIONAL: '전환', UNKNOWN: '미감지',
};

function RegimeBreakdownSection({ data }: { data: Record<string, RegimeStat> }) {
    const entries = Object.entries(data).filter(([, v]) => v.trades > 0);
    if (entries.length === 0) return null;
    return (
        <div className="bg-slate-800 rounded-xl border border-slate-700/50">
            <div className="px-5 py-4 border-b border-slate-700/50">
                <h2 className="text-sm font-semibold text-white">레짐별 성과</h2>
                <p className="text-xs text-slate-500 mt-0.5">포지션 진입 시점 시장 상태별 승률·손익 분리</p>
            </div>
            <div className="p-4 grid grid-cols-2 md:grid-cols-4 gap-3">
                {entries.map(([regime, stat]) => (
                    <div key={regime} className={`rounded-xl p-4 border ${REGIME_COLOR[regime] ?? REGIME_COLOR['UNKNOWN']}`}>
                        <p className="text-xs font-semibold uppercase tracking-wider mb-2">
                            {REGIME_LABEL[regime] ?? regime}
                        </p>
                        <div className="text-lg font-bold">
                            <span className={pnlClass(stat.totalPnl)}>
                                {stat.totalPnl > 0 ? '+' : ''}{fmt(stat.totalPnl)}원
                            </span>
                        </div>
                        <div className="text-xs mt-1 space-y-0.5">
                            <div>거래: {stat.trades}건</div>
                            <div>승률: {stat.trades > 0 ? `${fmt(stat.winRatePct, 1)}%` : '-'}
                                <span className="text-slate-400 ml-1">({stat.wins}승 {stat.trades - stat.wins}패)</span>
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
}

const REGIME_ARROW: Record<string, string> = {
    TREND: '↗', RANGE: '↔', VOLATILITY: '↕', TRANSITIONAL: '~', UNKNOWN: '?',
};

function RegimeHistorySection({ history }: { history: RegimeChangeLog[] }) {
    if (history.length === 0) return null;
    return (
        <div className="bg-slate-800 rounded-xl border border-slate-700/50">
            <div className="px-5 py-4 border-b border-slate-700/50">
                <h2 className="text-sm font-semibold text-white">레짐 전환 이력</h2>
                <p className="text-xs text-slate-500 mt-0.5">KRW-BTC H1 기준 시장 상태 변화 기록 (최근 {history.length}건)</p>
            </div>
            <div className="p-4 space-y-2 max-h-80 overflow-y-auto">
                {history.map((r) => {
                    const toColor = REGIME_COLOR[r.toRegime] ?? REGIME_COLOR['UNKNOWN'];
                    const fromColor = r.fromRegime
                        ? REGIME_COLOR[r.fromRegime] ?? REGIME_COLOR['UNKNOWN']
                        : 'bg-slate-700/40 text-slate-500 border-slate-700/50';
                    const changes: string[] = r.strategyChangesJson
                        ? (() => { try { return JSON.parse(r.strategyChangesJson); } catch { return []; } })()
                        : [];
                    return (
                        <div key={r.id} className="flex items-center gap-3 p-3 rounded-lg bg-slate-700/30 border border-slate-700/50">
                            {/* 타임스탬프 */}
                            <span className="text-xs text-slate-500 w-36 shrink-0">
                                {r.detectedAt.substring(0, 16).replace('T', ' ')}
                            </span>
                            {/* from → to */}
                            <div className="flex items-center gap-2 flex-1">
                                {r.fromRegime ? (
                                    <span className={`text-xs px-2 py-0.5 rounded border font-medium ${fromColor}`}>
                                        {REGIME_ARROW[r.fromRegime] ?? ''} {REGIME_LABEL[r.fromRegime] ?? r.fromRegime}
                                    </span>
                                ) : (
                                    <span className="text-xs text-slate-500 italic">초기</span>
                                )}
                                <span className="text-slate-500 text-xs">→</span>
                                <span className={`text-xs px-2 py-0.5 rounded border font-semibold ${toColor}`}>
                                    {REGIME_ARROW[r.toRegime] ?? ''} {REGIME_LABEL[r.toRegime] ?? r.toRegime}
                                </span>
                            </div>
                            {/* 전략 변경 요약 */}
                            {changes.length > 0 && (
                                <div className="flex gap-1 flex-wrap justify-end max-w-xs">
                                    {changes.slice(0, 3).map((c, i) => {
                                        const isActivated = c.startsWith('ACTIVATED:');
                                        const name = c.replace(/^(ACTIVATED|DEACTIVATED):/, '');
                                        return (
                                            <span key={i} className={`text-xs px-1.5 py-0.5 rounded ${isActivated ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'}`}>
                                                {isActivated ? '+' : '-'}{name}
                                            </span>
                                        );
                                    })}
                                    {changes.length > 3 && (
                                        <span className="text-xs text-slate-500">+{changes.length - 3}개</span>
                                    )}
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

function MonthlyReturnsTable({ data }: { data: Record<string, number> }) {
    const entries = Object.entries(data).sort(([a], [b]) => a.localeCompare(b));
    if (entries.length === 0) return null;

    return (
        <div className="bg-slate-800 rounded-xl border border-slate-700/50">
            <div className="px-5 py-4 border-b border-slate-700/50">
                <h2 className="text-sm font-semibold text-white">월별 수익률</h2>
            </div>
            <div className="p-4 flex flex-wrap gap-2">
                {entries.map(([month, ret]) => (
                    <div key={month} className="flex flex-col items-center bg-slate-700/50 rounded-lg px-3 py-2 min-w-[80px]">
                        <span className="text-xs text-slate-400">{month}</span>
                        <span className={`text-sm font-bold ${pnlClass(ret)}`}>
                            {ret > 0 ? '+' : ''}{fmt(ret, 2)}%
                        </span>
                    </div>
                ))}
            </div>
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
                        <th className="text-left py-3 px-3">전략</th>
                        <th className="text-left py-3 px-3">코인</th>
                        <th className="text-left py-3 px-3">상태</th>
                        <th className="text-right py-3 px-3">투자원금</th>
                        <th className="text-right py-3 px-3">실현손익</th>
                        <th className="text-right py-3 px-3">수익률</th>
                        <th className="text-right py-3 px-3">거래/승률</th>
                        <th className="text-right py-3 px-3 text-orange-400">MDD</th>
                        <th className="text-right py-3 px-3 text-sky-400">Sharpe</th>
                        <th className="text-right py-3 px-3 text-sky-400">Sortino</th>
                        <th className="text-right py-3 px-3 text-purple-400">승패비</th>
                        <th className="text-right py-3 px-3 text-slate-400">연속손실</th>
                        <th className="text-left py-3 px-3">시작일</th>
                    </tr>
                </thead>
                <tbody>
                    {sessions.map((s) => (
                        <tr key={s.sessionId} className="border-b border-slate-700/50 hover:bg-slate-700/30 transition-colors">
                            <td className="py-3 px-3 font-medium text-slate-200">{s.strategyType}</td>
                            <td className="py-3 px-3 text-slate-300">{s.coinPair}<span className="text-slate-500 ml-1 text-xs">{s.timeframe}</span></td>
                            <td className="py-3 px-3">
                                <span className={`text-xs px-2 py-0.5 rounded-full ${
                                    s.status === 'RUNNING' ? 'bg-green-500/20 text-green-400' : 'bg-slate-600/50 text-slate-400'
                                }`}>
                                    {s.status === 'RUNNING' ? '실행중' : '중단'}
                                </span>
                            </td>
                            <td className="py-3 px-3 text-right text-slate-300">{fmt(s.initialCapital)}원</td>
                            <td className={`py-3 px-3 text-right font-medium ${pnlClass(s.realizedPnl)}`}>
                                {s.realizedPnl > 0 ? '+' : ''}{fmt(s.realizedPnl)}원
                            </td>
                            <td className={`py-3 px-3 text-right font-medium ${pnlClass(s.returnRatePct)}`}>
                                {s.returnRatePct > 0 ? '+' : ''}{fmt(s.returnRatePct, 2)}%
                            </td>
                            <td className="py-3 px-3 text-right text-slate-300">
                                {s.totalTrades}건
                                <span className="text-slate-400 ml-1">
                                    {s.totalTrades > 0 ? `${fmt(s.winRatePct, 1)}%` : '-'}
                                </span>
                            </td>
                            {/* 리스크 지표 */}
                            <td className={`py-3 px-3 text-right font-medium ${s.mddPct != null && s.mddPct < -10 ? 'text-red-400' : s.mddPct != null && s.mddPct < -5 ? 'text-yellow-400' : 'text-slate-300'}`}>
                                {s.mddPct != null ? `${fmt(s.mddPct, 2)}%` : '-'}
                            </td>
                            <td className={`py-3 px-3 text-right ${ratioClass(s.sharpeRatio)}`}>
                                {s.sharpeRatio != null ? fmt(s.sharpeRatio, 2) : '-'}
                            </td>
                            <td className={`py-3 px-3 text-right ${ratioClass(s.sortinoRatio)}`}>
                                {s.sortinoRatio != null ? fmt(s.sortinoRatio, 2) : '-'}
                            </td>
                            <td className={`py-3 px-3 text-right ${ratioClass(s.winLossRatio)}`}>
                                {s.winLossRatio != null ? fmt(s.winLossRatio, 2) : '-'}
                            </td>
                            <td className={`py-3 px-3 text-right ${s.maxConsecutiveLoss != null && s.maxConsecutiveLoss >= 5 ? 'text-red-400' : s.maxConsecutiveLoss != null && s.maxConsecutiveLoss >= 3 ? 'text-yellow-400' : 'text-slate-300'}`}>
                                {s.maxConsecutiveLoss != null ? `${s.maxConsecutiveLoss}연` : '-'}
                            </td>
                            <td className="py-3 px-3 text-slate-400 text-xs">
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
    const [regimeHistory, setRegimeHistory] = useState<RegimeChangeLog[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const [live, paper, history] = await Promise.all([
                tradingApi.getPerformance(),
                paperTradingApi.getPerformance(),
                logsApi.regimeHistory(50),
            ]);
            if (live.data) setLiveData(live.data);
            if (paper.data) setPaperData(paper.data);
            if (history.data) setRegimeHistory(history.data);
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
                    {/* 기본 손익 요약 */}
                    <div>
                        <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">손익 요약</h2>
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
                    </div>

                    {/* 리스크 조정 지표 */}
                    {data.totalTrades > 0 && (
                        <div>
                            <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">리스크 조정 지표</h2>
                            <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-6 gap-3">
                                <SummaryCard
                                    label="MDD (최대 낙폭)"
                                    value={
                                        <span className={data.mddPct != null && data.mddPct < -10 ? 'text-red-400' : data.mddPct != null && data.mddPct < -5 ? 'text-yellow-400' : 'text-slate-200'}>
                                            {data.mddPct != null ? `${fmt(data.mddPct, 2)}%` : '-'}
                                        </span>
                                    }
                                    sub="낮을수록 안전"
                                />
                                <SummaryCard
                                    label="Sharpe Ratio"
                                    value={
                                        <span className={ratioClass(data.sharpeRatio)}>
                                            {data.sharpeRatio != null ? fmt(data.sharpeRatio, 2) : '-'}
                                        </span>
                                    }
                                    sub="1 이상 양호"
                                />
                                <SummaryCard
                                    label="Sortino Ratio"
                                    value={
                                        <span className={ratioClass(data.sortinoRatio)}>
                                            {data.sortinoRatio != null ? fmt(data.sortinoRatio, 2) : '-'}
                                        </span>
                                    }
                                    sub="하방 리스크 기준"
                                />
                                <SummaryCard
                                    label="Calmar Ratio"
                                    value={
                                        <span className={ratioClass(data.calmarRatio)}>
                                            {data.calmarRatio != null ? fmt(data.calmarRatio, 2) : '-'}
                                        </span>
                                    }
                                    sub="수익/MDD 비율"
                                />
                                <SummaryCard
                                    label="승패비"
                                    value={
                                        <span className={ratioClass(data.winLossRatio)}>
                                            {data.winLossRatio != null ? fmt(data.winLossRatio, 2) : '-'}
                                        </span>
                                    }
                                    sub={`평균 수익 ${fmt(data.avgProfitPct, 2)}% / 손실 ${fmt(data.avgLossPct, 2)}%`}
                                />
                                <SummaryCard
                                    label="최대 연속 손실"
                                    value={
                                        <span className={data.maxConsecutiveLoss != null && data.maxConsecutiveLoss >= 5 ? 'text-red-400' : data.maxConsecutiveLoss != null && data.maxConsecutiveLoss >= 3 ? 'text-yellow-400' : 'text-slate-200'}>
                                            {data.maxConsecutiveLoss != null ? `${data.maxConsecutiveLoss}연패` : '-'}
                                        </span>
                                    }
                                    sub="연속 손실 횟수"
                                />
                            </div>
                        </div>
                    )}

                    {/* 레짐별 성과 */}
                    {data.regimeBreakdown && Object.keys(data.regimeBreakdown).length > 0 && (
                        <RegimeBreakdownSection data={data.regimeBreakdown} />
                    )}

                    {/* 월별 수익률 */}
                    {data.monthlyReturns && Object.keys(data.monthlyReturns).length > 0 && (
                        <MonthlyReturnsTable data={data.monthlyReturns} />
                    )}

                    {/* 레짐 전환 이력 */}
                    {regimeHistory.length > 0 && (
                        <RegimeHistorySection history={regimeHistory} />
                    )}

                    {/* 세션별 테이블 */}
                    <div className="bg-slate-800 rounded-xl border border-slate-700/50">
                        <div className="px-5 py-4 border-b border-slate-700/50">
                            <h2 className="text-sm font-semibold text-white">
                                세션별 성과 ({data.sessions.length}개)
                            </h2>
                            <p className="text-xs text-slate-500 mt-0.5">MDD·Sharpe·Sortino는 실현된 거래 기준 계산</p>
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
