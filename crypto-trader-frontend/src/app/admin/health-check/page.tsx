'use client';

import { useState, useEffect, useCallback } from 'react';
import { adminHealthCheckApi } from '@/lib/api';
import { fmtKst } from '@/lib/utils';
import {
    HeartPulse, RefreshCw, PlayCircle, CheckCircle2, AlertTriangle,
    Wallet, Hash, Ghost, Clock, ChevronDown, ChevronUp,
} from 'lucide-react';

interface DetailRow {
    sessionKind?: string;
    sessionId?: number;
    positionId?: number;
    coinPair?: string;
    availableKrw?: number;
    totalAssetKrw?: number;
    heldHours?: number;
    [key: string]: unknown;
}

interface HealthSnapshot {
    id: number;
    checkedAt: string;
    balanceMismatchCount: number;
    balanceMismatchDetail: DetailRow[] | null;
    orderSequenceGap: number;
    sequenceGapChecked: boolean;
    ghostPositionCount: number;
    ghostPositionDetail: DetailRow[] | null;
    stuckPositionCount: number;
    stuckPositionDetail: DetailRow[] | null;
}

function Metric({
    icon: Icon, label, count, ok, unchecked,
}: {
    icon: React.ComponentType<{ className?: string }>;
    label: string;
    count: number;
    ok: boolean;
    unchecked?: boolean;
}) {
    const color = unchecked ? 'text-slate-500 bg-slate-800' : ok ? 'text-emerald-400 bg-emerald-500/10' : 'text-red-400 bg-red-500/10';
    return (
        <div className={`flex items-center gap-2 px-3 py-2 rounded-lg ${color}`}>
            <Icon className="w-4 h-4 shrink-0" />
            <span className="text-xs">{label}</span>
            <span className="ml-auto text-sm font-semibold">
                {unchecked ? '확인 불가' : count}
            </span>
        </div>
    );
}

function DetailList({ rows }: { rows: DetailRow[] }) {
    return (
        <div className="mt-2 space-y-1">
            {rows.map((r, i) => (
                <div key={i} className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-slate-400 font-mono bg-slate-800/60 rounded px-2 py-1.5">
                    {r.sessionKind && <span>{r.sessionKind}#{r.sessionId}</span>}
                    {r.positionId != null && <span>posId={r.positionId}</span>}
                    {r.coinPair && <span>{r.coinPair}</span>}
                    {r.availableKrw != null && <span>available={r.availableKrw}</span>}
                    {r.totalAssetKrw != null && <span>total={r.totalAssetKrw}</span>}
                    {r.heldHours != null && <span>보유 {r.heldHours}시간</span>}
                </div>
            ))}
        </div>
    );
}

export default function HealthCheckPage() {
    const [snapshots, setSnapshots] = useState<HealthSnapshot[]>([]);
    const [loading, setLoading] = useState(true);
    const [triggering, setTriggering] = useState(false);
    const [error, setError] = useState('');
    const [expanded, setExpanded] = useState<Set<number>>(new Set());

    const fetchHistory = useCallback(async () => {
        setLoading(true);
        setError('');
        try {
            const res = await adminHealthCheckApi.history(20);
            if (res.success && res.data) {
                setSnapshots(res.data as unknown as HealthSnapshot[]);
            } else {
                setError('이력 조회 실패');
            }
        } catch {
            setError('서버 오류가 발생했습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { fetchHistory(); }, [fetchHistory]);

    const handleTrigger = async () => {
        setTriggering(true);
        try {
            await adminHealthCheckApi.trigger();
            await fetchHistory();
        } catch {
            setError('점검 실행 실패');
        } finally {
            setTriggering(false);
        }
    };

    const toggle = (id: number) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    return (
        <div className="max-w-4xl">
            {/* 헤더 */}
            <div className="flex items-center justify-between mb-6 flex-wrap gap-3">
                <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-rose-500/10 flex items-center justify-center">
                        <HeartPulse className="w-5 h-5 text-rose-400" />
                    </div>
                    <div>
                        <h1 className="text-xl font-bold text-slate-100">운영 건전성 점검</h1>
                        <p className="text-xs text-slate-500 mt-0.5">
                            세션 잔고 정합성 · 주문 시퀀스 갭 · 유령 포지션 · 무출구 고착 포지션. 매일 08:30 KST 자동 실행.
                        </p>
                    </div>
                </div>
                <div className="flex items-center gap-2">
                    <button
                        onClick={handleTrigger}
                        disabled={triggering}
                        className="flex items-center gap-2 px-3 py-2 rounded-lg bg-rose-500/10 text-rose-400 hover:bg-rose-500/20 text-sm font-medium transition-colors disabled:opacity-50"
                    >
                        <PlayCircle className={`w-4 h-4 ${triggering ? 'animate-pulse' : ''}`} />
                        {triggering ? '실행 중...' : '지금 점검 실행'}
                    </button>
                    <button
                        onClick={fetchHistory}
                        disabled={loading}
                        className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-800 text-slate-400 hover:text-slate-200 text-sm transition-colors disabled:opacity-50"
                    >
                        <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
                        새로고침
                    </button>
                </div>
            </div>

            {error && (
                <div className="flex items-center gap-3 p-4 rounded-xl bg-red-500/10 border border-red-500/20 mb-6 text-sm text-red-400">
                    <AlertTriangle className="w-4 h-4 shrink-0" />
                    {error}
                </div>
            )}

            {loading ? (
                <div className="h-32 flex items-center justify-center">
                    <RefreshCw className="w-5 h-5 text-slate-600 animate-spin" />
                </div>
            ) : snapshots.length === 0 ? (
                <div className="text-center py-12 text-sm text-slate-500">
                    점검 이력이 아직 없습니다. &ldquo;지금 점검 실행&rdquo;으로 첫 점검을 실행하세요.
                </div>
            ) : (
                <div className="space-y-3">
                    {snapshots.map(s => {
                        const anomalyCount =
                            s.balanceMismatchCount + s.ghostPositionCount + s.stuckPositionCount +
                            (s.sequenceGapChecked && s.orderSequenceGap > 0 ? 1 : 0);
                        const isOpen = expanded.has(s.id);
                        const hasDetail =
                            (s.balanceMismatchDetail?.length ?? 0) > 0 ||
                            (s.ghostPositionDetail?.length ?? 0) > 0 ||
                            (s.stuckPositionDetail?.length ?? 0) > 0;

                        return (
                            <div key={s.id} className="bg-slate-900/60 border border-slate-800 rounded-xl p-4">
                                <div className="flex items-center justify-between mb-3">
                                    <span className="text-xs text-slate-500 font-mono">{fmtKst(s.checkedAt)}</span>
                                    {anomalyCount === 0 ? (
                                        <span className="flex items-center gap-1 text-xs text-emerald-400">
                                            <CheckCircle2 className="w-3.5 h-3.5" /> 이상 없음
                                        </span>
                                    ) : (
                                        <span className="flex items-center gap-1 text-xs text-red-400 font-semibold">
                                            <AlertTriangle className="w-3.5 h-3.5" /> 이상 {anomalyCount}건
                                        </span>
                                    )}
                                </div>

                                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                                    <Metric icon={Wallet} label="잔고 정합성" count={s.balanceMismatchCount} ok={s.balanceMismatchCount === 0} />
                                    <Metric icon={Hash} label="시퀀스 갭" count={s.orderSequenceGap} ok={s.orderSequenceGap === 0} unchecked={!s.sequenceGapChecked} />
                                    <Metric icon={Ghost} label="유령 포지션" count={s.ghostPositionCount} ok={s.ghostPositionCount === 0} />
                                    <Metric icon={Clock} label="무출구 고착" count={s.stuckPositionCount} ok={s.stuckPositionCount === 0} />
                                </div>

                                {hasDetail && (
                                    <button
                                        onClick={() => toggle(s.id)}
                                        className="flex items-center gap-1 mt-3 text-xs text-slate-500 hover:text-slate-300"
                                    >
                                        {isOpen ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                                        상세 {isOpen ? '접기' : '보기'}
                                    </button>
                                )}

                                {isOpen && (
                                    <>
                                        {(s.balanceMismatchDetail?.length ?? 0) > 0 && <DetailList rows={s.balanceMismatchDetail!} />}
                                        {(s.ghostPositionDetail?.length ?? 0) > 0 && <DetailList rows={s.ghostPositionDetail!} />}
                                        {(s.stuckPositionDetail?.length ?? 0) > 0 && <DetailList rows={s.stuckPositionDetail!} />}
                                    </>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
}
