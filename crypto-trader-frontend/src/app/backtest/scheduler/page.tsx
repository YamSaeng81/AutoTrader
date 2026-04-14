'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { schedulerApi } from '@/lib/api';
import type { NightlySchedulerConfig, BacktestJob } from '@/lib/types';
import { Play, Save, Clock, Calendar, BarChart2, FlaskConical, Activity, RefreshCw, AlertCircle, CheckCircle2, Loader2, X } from 'lucide-react';

// ── 상수 ────────────────────────────────────────────────────────────────────────

const TIMEFRAME_OPTIONS = ['M1', 'M5', 'M15', 'M30', 'H1', 'H4', 'D1'];

const PRESET_COINS = [
    { id: 'KRW-BTC',  label: 'BTC' },
    { id: 'KRW-ETH',  label: 'ETH' },
    { id: 'KRW-SOL',  label: 'SOL' },
    { id: 'KRW-XRP',  label: 'XRP' },
    { id: 'KRW-DOGE', label: 'DOGE' },
    { id: 'KRW-ADA',  label: 'ADA' },
    { id: 'KRW-DOT',  label: 'DOT' },
    { id: 'KRW-AVAX', label: 'AVAX' },
];

const PRESET_STRATEGIES = [
    { id: 'COMPOSITE_BREAKOUT',               label: 'COMPOSITE_BREAKOUT',         group: '복합' },
    { id: 'COMPOSITE_MOMENTUM',               label: 'COMPOSITE_MOMENTUM',         group: '복합' },
    { id: 'COMPOSITE_MOMENTUM_ICHIMOKU',      label: 'MOMENTUM_ICHIMOKU (V1)',     group: '복합' },
    { id: 'COMPOSITE_MOMENTUM_ICHIMOKU_V2',   label: 'MOMENTUM_ICHIMOKU_V2',       group: '복합' },
    { id: 'COMPOSITE_ETH',                    label: 'COMPOSITE_ETH',              group: '복합' },
    { id: 'COMPOSITE',                        label: 'COMPOSITE (레짐 자동)',       group: '복합' },
    { id: 'RSI',                              label: 'RSI',                        group: '단일' },
    { id: 'MACD',                             label: 'MACD',                       group: '단일' },
    { id: 'EMA_CROSS',                        label: 'EMA Cross',                  group: '단일' },
    { id: 'BOLLINGER',                        label: 'Bollinger Band',             group: '단일' },
    { id: 'SUPERTREND',                       label: 'Supertrend',                 group: '단일' },
    { id: 'ATR_BREAKOUT',                     label: 'ATR Breakout',               group: '단일' },
    { id: 'VWAP',                             label: 'VWAP',                       group: '단일' },
];

const JOB_TYPE_LABEL: Record<string, string> = {
    SINGLE: '백테스트',
    BULK: '배치',
    MULTI_STRATEGY: '멀티전략',
    WALK_FORWARD_BATCH: 'Walk-Forward',
};

// ── 공통 컴포넌트 ────────────────────────────────────────────────────────────────

function Section({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
    return (
        <div className="bg-slate-800 rounded-xl border border-slate-700/50">
            <div className="px-5 py-4 border-b border-slate-700/50 flex items-center gap-2">
                {icon}
                <h2 className="text-sm font-semibold text-white">{title}</h2>
            </div>
            <div className="p-5">{children}</div>
        </div>
    );
}

function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
    return (
        <button
            onClick={() => onChange(!checked)}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${checked ? 'bg-indigo-600' : 'bg-slate-600'}`}
            aria-label={label}
        >
            <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${checked ? 'translate-x-6' : 'translate-x-1'}`} />
        </button>
    );
}

function CheckChip({ id, label, checked, onChange }: { id: string; label: string; checked: boolean; onChange: (id: string, v: boolean) => void }) {
    return (
        <button
            onClick={() => onChange(id, !checked)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-all ${
                checked
                    ? 'bg-indigo-600/30 border-indigo-500/60 text-indigo-300'
                    : 'bg-slate-700/50 border-slate-600/50 text-slate-400 hover:border-slate-500'
            }`}
        >
            {checked ? '✓ ' : ''}{label}
        </button>
    );
}

function StatusBadge({ status }: { status: string }) {
    const map: Record<string, { label: string; cls: string; icon: React.ReactNode }> = {
        PENDING:   { label: '대기',   cls: 'bg-yellow-900/40 text-yellow-300 border-yellow-700/50',  icon: <Clock className="w-3 h-3" /> },
        RUNNING:   { label: '실행 중', cls: 'bg-blue-900/40 text-blue-300 border-blue-700/50',       icon: <Loader2 className="w-3 h-3 animate-spin" /> },
        COMPLETED: { label: '완료',   cls: 'bg-emerald-900/40 text-emerald-300 border-emerald-700/50', icon: <CheckCircle2 className="w-3 h-3" /> },
        FAILED:    { label: '실패',   cls: 'bg-red-900/40 text-red-300 border-red-700/50',           icon: <AlertCircle className="w-3 h-3" /> },
        CANCELLED: { label: '취소됨', cls: 'bg-slate-700/60 text-slate-400 border-slate-600/50',     icon: <X className="w-3 h-3" /> },
    };
    const s = map[status] ?? { label: status, cls: 'bg-slate-700 text-slate-300 border-slate-600', icon: null };
    return (
        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs border font-medium ${s.cls}`}>
            {s.icon}{s.label}
        </span>
    );
}

// ── 기본값 ────────────────────────────────────────────────────────────────────────

const DEFAULT_CONFIG: NightlySchedulerConfig = {
    enabled: false,
    runHour: 0,
    runMinute: 0,
    timeframe: 'H1',
    startDate: '2023-01-01',
    endDate: '2025-12-31',
    coinPairs: ['KRW-BTC', 'KRW-ETH', 'KRW-SOL', 'KRW-XRP', 'KRW-DOGE'],
    strategyTypes: ['COMPOSITE_BREAKOUT', 'COMPOSITE_MOMENTUM', 'COMPOSITE_MOMENTUM_ICHIMOKU', 'COMPOSITE_MOMENTUM_ICHIMOKU_V2'],
    includeBacktest: true,
    includeWalkForward: true,
    inSampleRatio: 0.7,
    windowCount: 5,
    initialCapital: 1000000,
    slippagePct: 0.05,
    feePct: 0.05,
};

// ── 유틸 ─────────────────────────────────────────────────────────────────────────

function fmtDateTime(dt: string | undefined) {
    if (!dt) return '-';
    const d = new Date(dt);
    // UTC → KST (+9)
    const kst = new Date(d.getTime() + 9 * 60 * 60 * 1000);
    const mm  = String(kst.getUTCMonth() + 1).padStart(2, '0');
    const dd  = String(kst.getUTCDate()).padStart(2, '0');
    const hh  = String(kst.getUTCHours()).padStart(2, '0');
    const min = String(kst.getUTCMinutes()).padStart(2, '0');
    const ss  = String(kst.getUTCSeconds()).padStart(2, '0');
    return `${mm}/${dd} ${hh}:${min}:${ss}`;
}

function elapsed(createdAt: string, updatedAt: string) {
    const ms = new Date(updatedAt).getTime() - new Date(createdAt).getTime();
    if (ms < 1000) return '< 1초';
    if (ms < 60000) return `${Math.round(ms / 1000)}초`;
    return `${Math.round(ms / 60000)}분 ${Math.round((ms % 60000) / 1000)}초`;
}

// ── 실행 이력 테이블 ──────────────────────────────────────────────────────────────

function JobHistorySection({ jobs, loading, onRefresh, onCancel }: {
    jobs: BacktestJob[];
    loading: boolean;
    onRefresh: () => void;
    onCancel: (jobId: number) => void;
}) {
    const hasActive = jobs.some(j => j.status === 'PENDING' || j.status === 'RUNNING');

    return (
        <Section
            title="실행 이력"
            icon={<Activity size={16} className="text-slate-400" />}
        >
            <div className="space-y-3">
                {/* 상단 툴바 */}
                <div className="flex items-center justify-between">
                    <p className="text-xs text-slate-500">최근 30개 · 스케줄러 실행 포함 전체 백테스트 Job</p>
                    <button
                        onClick={onRefresh}
                        disabled={loading}
                        className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors disabled:opacity-40"
                    >
                        <RefreshCw size={12} className={loading ? 'animate-spin' : ''} />
                        새로고침
                    </button>
                </div>

                {hasActive && (
                    <p className="text-xs text-blue-400 flex items-center gap-1">
                        <Loader2 size={12} className="animate-spin" />
                        실행 중인 작업이 있습니다. 5초마다 자동 갱신 중...
                    </p>
                )}

                {jobs.length === 0 ? (
                    <p className="text-sm text-slate-500 py-4 text-center">실행 이력이 없습니다.</p>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-xs">
                            <thead>
                                <tr className="text-slate-500 border-b border-slate-700/50">
                                    <th className="text-left py-2 pr-3 font-medium">ID</th>
                                    <th className="text-left py-2 pr-3 font-medium">유형</th>
                                    <th className="text-left py-2 pr-3 font-medium">코인</th>
                                    <th className="text-left py-2 pr-3 font-medium">전략</th>
                                    <th className="text-left py-2 pr-3 font-medium">상태</th>
                                    <th className="text-left py-2 pr-3 font-medium">진행</th>
                                    <th className="text-left py-2 pr-3 font-medium">실행 시각 (KST)</th>
                                    <th className="text-left py-2 pr-3 font-medium">소요</th>
                                    <th className="py-2 font-medium"></th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-700/30">
                                {jobs.map(job => {
                                    const cancellable = job.status === 'PENDING' || job.status === 'RUNNING';
                                    return (
                                    <tr key={job.id} className="hover:bg-slate-700/20 transition-colors">
                                        <td className="py-2 pr-3 text-slate-400">#{job.id}</td>
                                        <td className="py-2 pr-3 text-slate-300">{JOB_TYPE_LABEL[job.jobType] ?? job.jobType}</td>
                                        <td className="py-2 pr-3 text-slate-300">
                                            {job.coinPair ? job.coinPair.replace('KRW-', '') : '—'}
                                        </td>
                                        <td className="py-2 pr-3 text-slate-400 max-w-[160px] truncate" title={job.strategyName ?? ''}>
                                            {job.strategyName
                                                ? job.strategyName.length > 22
                                                    ? job.strategyName.slice(0, 20) + '…'
                                                    : job.strategyName
                                                : '—'}
                                        </td>
                                        <td className="py-2 pr-3">
                                            <StatusBadge status={job.status} />
                                        </td>
                                        <td className="py-2 pr-3">
                                            {job.totalChunks != null && job.totalChunks > 0 ? (
                                                <div className="flex items-center gap-1.5">
                                                    <div className="w-16 h-1.5 bg-slate-700 rounded-full overflow-hidden">
                                                        <div
                                                            className="h-full bg-indigo-500 rounded-full transition-all"
                                                            style={{ width: `${job.progressPct ?? 0}%` }}
                                                        />
                                                    </div>
                                                    <span className="text-slate-400">{job.progressPct ?? 0}%</span>
                                                </div>
                                            ) : (
                                                <span className="text-slate-600">—</span>
                                            )}
                                        </td>
                                        <td className="py-2 pr-3 text-slate-500">{fmtDateTime(job.createdAt)}</td>
                                        <td className="py-2 pr-3 text-slate-500">
                                            {job.status === 'PENDING' ? '-' : elapsed(job.createdAt, job.updatedAt)}
                                        </td>
                                        <td className="py-2">
                                            {cancellable && (
                                                <button
                                                    onClick={() => onCancel(job.id)}
                                                    title="취소"
                                                    className="p-1 rounded text-slate-500 hover:bg-red-900/40 hover:text-red-400 transition-colors"
                                                >
                                                    <X size={13} />
                                                </button>
                                            )}
                                        </td>
                                    </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}

                {/* FAILED 항목 에러 메시지 */}
                {jobs.filter(j => j.status === 'FAILED' && j.errorMessage).slice(0, 3).map(job => (
                    <div key={`err-${job.id}`} className="bg-red-900/20 border border-red-700/30 rounded-lg px-3 py-2">
                        <p className="text-xs text-red-400 font-medium mb-0.5">#{job.id} 오류</p>
                        <p className="text-xs text-red-300/80 break-all">{job.errorMessage}</p>
                    </div>
                ))}
            </div>
        </Section>
    );
}

// ── 메인 페이지 ──────────────────────────────────────────────────────────────────

export default function SchedulerPage() {
    const [config, setConfig]         = useState<NightlySchedulerConfig>(DEFAULT_CONFIG);
    const [saving, setSaving]         = useState(false);
    const [triggering, setTriggering] = useState(false);
    const [loading, setLoading]       = useState(true);
    const [jobsLoading, setJobsLoading] = useState(false);
    const [jobs, setJobs]             = useState<BacktestJob[]>([]);
    const [toast, setToast]           = useState<{ msg: string; ok: boolean } | null>(null);
    const [customCoin, setCustomCoin] = useState('');
    const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

    const showToast = (msg: string, ok: boolean) => {
        setToast({ msg, ok });
        setTimeout(() => setToast(null), 4000);
    };

    const loadJobs = useCallback(async () => {
        setJobsLoading(true);
        try {
            const res = await schedulerApi.listJobs();
            if (res.data) setJobs(res.data.slice(0, 30));
        } finally {
            setJobsLoading(false);
        }
    }, []);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const res = await schedulerApi.getConfig();
            if (res.data) setConfig(res.data);
        } catch {
            showToast('설정 조회 실패', false);
        } finally {
            setLoading(false);
        }
    }, []);

    // 초기 로드
    useEffect(() => {
        load();
        loadJobs();
    }, [load, loadJobs]);

    // PENDING/RUNNING 있으면 5초 폴링
    useEffect(() => {
        const hasActive = jobs.some(j => j.status === 'PENDING' || j.status === 'RUNNING');
        if (hasActive) {
            pollRef.current = setInterval(loadJobs, 5000);
        } else {
            if (pollRef.current) clearInterval(pollRef.current);
        }
        return () => { if (pollRef.current) clearInterval(pollRef.current); };
    }, [jobs, loadJobs]);

    const set = <K extends keyof NightlySchedulerConfig>(key: K, value: NightlySchedulerConfig[K]) =>
        setConfig(prev => ({ ...prev, [key]: value }));

    const toggleCoin = (id: string, checked: boolean) => {
        set('coinPairs', checked
            ? [...config.coinPairs, id]
            : config.coinPairs.filter(c => c !== id));
    };

    const toggleStrategy = (id: string, checked: boolean) => {
        set('strategyTypes', checked
            ? [...config.strategyTypes, id]
            : config.strategyTypes.filter(s => s !== id));
    };

    const addCustomCoin = () => {
        const coin = customCoin.trim().toUpperCase();
        if (!coin) return;
        const full = coin.startsWith('KRW-') ? coin : `KRW-${coin}`;
        if (!config.coinPairs.includes(full)) set('coinPairs', [...config.coinPairs, full]);
        setCustomCoin('');
    };

    const handleSave = async () => {
        setSaving(true);
        try {
            const res = await schedulerApi.updateConfig(config);
            if (res.data) setConfig(res.data);
            showToast('설정이 저장되었습니다', true);
        } catch {
            showToast('저장 실패', false);
        } finally {
            setSaving(false);
        }
    };

    const handleCancel = async (jobId: number) => {
        const job = jobs.find(j => j.id === jobId);
        const label = job?.status === 'RUNNING' ? '실행 중' : '대기 중';
        if (!window.confirm(`Job #${jobId} (${label})을 취소하시겠습니까?\n\n실행 중인 작업은 현재 조합이 끝난 뒤 중단됩니다.`)) return;
        try {
            await schedulerApi.cancelJob(jobId);
            // 목록에서 즉시 상태 반영 (낙관적 업데이트)
            setJobs(prev => prev.map(j => j.id === jobId ? { ...j, status: 'CANCELLED' } : j));
            await loadJobs();
        } catch {
            showToast(`Job #${jobId} 취소 실패`, false);
        }
    };

    // 지금 실행: 현재 폼 설정을 먼저 저장한 뒤 트리거
    const handleTrigger = async () => {
        if (!window.confirm(
            `현재 설정을 저장하고 즉시 실행합니다.\n\n코인 ${config.coinPairs.length}개 × 전략 ${config.strategyTypes.length}개 = ${config.coinPairs.length * config.strategyTypes.length}개 조합\n\n계속하시겠습니까?`
        )) return;

        setTriggering(true);
        try {
            // 1단계: 현재 폼 설정 저장
            const saveRes = await schedulerApi.updateConfig(config);
            if (saveRes.data) setConfig(saveRes.data);

            // 2단계: 트리거
            await schedulerApi.triggerNow();
            showToast('실행이 시작되었습니다. 완료 시 텔레그램으로 알림이 전송됩니다.', true);

            // 설정 + 이력 갱신
            await Promise.all([load(), loadJobs()]);
        } catch {
            showToast('실행 실패', false);
        } finally {
            setTriggering(false);
        }
    };

    const compositeStrategies = PRESET_STRATEGIES.filter(s => s.group === '복합');
    const singleStrategies    = PRESET_STRATEGIES.filter(s => s.group === '단일');
    const customCoins = config.coinPairs.filter(c => !PRESET_COINS.some(p => p.id === c));

    if (loading) return <div className="p-6 text-slate-400">로딩 중...</div>;

    return (
        <div className="p-6 space-y-6 max-w-3xl">

            {/* 헤더 */}
            <div className="flex items-start justify-between">
                <div>
                    <h1 className="text-2xl font-bold text-white">자동 백테스트 스케줄</h1>
                    <p className="text-sm text-slate-400 mt-1">
                        매일 지정 시각에 자동으로 백테스트 + Walk-Forward를 실행합니다
                    </p>
                </div>
                <div className="flex gap-2">
                    <button
                        onClick={handleTrigger}
                        disabled={triggering || config.coinPairs.length === 0 || config.strategyTypes.length === 0}
                        className="flex items-center gap-2 px-4 py-2 text-sm bg-emerald-600 hover:bg-emerald-700 disabled:opacity-40 text-white rounded-lg transition-colors"
                        title="현재 설정을 저장하고 즉시 실행"
                    >
                        <Play size={14} />
                        {triggering ? '실행 중...' : '지금 실행'}
                    </button>
                    <button
                        onClick={handleSave}
                        disabled={saving}
                        className="flex items-center gap-2 px-4 py-2 text-sm bg-indigo-600 hover:bg-indigo-700 disabled:opacity-40 text-white rounded-lg transition-colors"
                        title="실행 없이 설정만 저장"
                    >
                        <Save size={14} />
                        {saving ? '저장 중...' : '저장'}
                    </button>
                </div>
            </div>

            {/* 버튼 안내 */}
            <div className="grid grid-cols-2 gap-2 text-xs text-slate-500">
                <div className="bg-slate-800/40 rounded-lg px-3 py-2 border border-slate-700/30">
                    <span className="text-emerald-400 font-medium">지금 실행</span> — 현재 폼 설정을 저장한 뒤 즉시 백테스트/WF 실행
                </div>
                <div className="bg-slate-800/40 rounded-lg px-3 py-2 border border-slate-700/30">
                    <span className="text-indigo-400 font-medium">저장</span> — 실행 없이 설정만 저장 (다음 예약 시각에 자동 실행)
                </div>
            </div>

            {/* Toast */}
            {toast && (
                <div className={`px-4 py-3 rounded-lg text-sm ${toast.ok ? 'bg-emerald-900/40 border border-emerald-700/50 text-emerald-300' : 'bg-red-900/40 border border-red-700/50 text-red-300'}`}>
                    {toast.msg}
                </div>
            )}

            {/* 마지막 / 다음 실행 */}
            <div className="grid grid-cols-2 gap-3">
                <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
                    <p className="text-xs text-slate-400 mb-1">마지막 실행</p>
                    <p className="text-sm font-medium text-white">{config.lastTriggeredAt ? fmtDateTime(config.lastTriggeredAt) : '없음'}</p>
                    {(config.lastBatchJobId || config.lastWfJobId) && (
                        <p className="text-xs text-slate-500 mt-0.5">
                            {config.lastBatchJobId && `배치 #${config.lastBatchJobId}`}
                            {config.lastBatchJobId && config.lastWfJobId && '  '}
                            {config.lastWfJobId && `WF #${config.lastWfJobId}`}
                        </p>
                    )}
                </div>
                <div className="bg-slate-800 rounded-xl p-4 border border-slate-700/50">
                    <p className="text-xs text-slate-400 mb-1">다음 실행 예정</p>
                    <p className="text-sm font-medium text-white">
                        {config.enabled ? (config.nextRunAt ? fmtDateTime(config.nextRunAt) : '-') : '비활성화됨'}
                    </p>
                </div>
            </div>

            {/* 실행 이력 */}
            <JobHistorySection jobs={jobs} loading={jobsLoading} onRefresh={loadJobs} onCancel={handleCancel} />

            {/* 스케줄 설정 */}
            <Section title="스케줄 설정" icon={<Clock size={16} className="text-slate-400" />}>
                <div className="space-y-4">
                    <div className="flex items-center justify-between">
                        <div>
                            <p className="text-sm text-white font-medium">자동 실행 활성화</p>
                            <p className="text-xs text-slate-500 mt-0.5">비활성화 시 수동 실행만 가능</p>
                        </div>
                        <Toggle checked={config.enabled} onChange={v => set('enabled', v)} label="활성화" />
                    </div>
                    <div className="flex items-center gap-3">
                        <label className="text-sm text-slate-300 w-20">실행 시각</label>
                        <div className="flex items-center gap-2">
                            <select
                                value={config.runHour}
                                onChange={e => set('runHour', Number(e.target.value))}
                                className="bg-slate-700 border border-slate-600 rounded-lg px-3 py-1.5 text-sm text-white"
                            >
                                {Array.from({ length: 24 }, (_, i) => (
                                    <option key={i} value={i}>{String(i).padStart(2, '0')}시</option>
                                ))}
                            </select>
                            <span className="text-slate-400">:</span>
                            <select
                                value={config.runMinute}
                                onChange={e => set('runMinute', Number(e.target.value))}
                                className="bg-slate-700 border border-slate-600 rounded-lg px-3 py-1.5 text-sm text-white"
                            >
                                {[0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55].map(m => (
                                    <option key={m} value={m}>{String(m).padStart(2, '0')}분</option>
                                ))}
                            </select>
                            <span className="text-xs text-slate-500">KST</span>
                        </div>
                    </div>
                </div>
            </Section>

            {/* 분석 설정 */}
            <Section title="분석 설정" icon={<Calendar size={16} className="text-slate-400" />}>
                <div className="space-y-4">
                    <div className="grid grid-cols-2 gap-4">
                        <div>
                            <label className="text-xs text-slate-400 mb-1.5 block">타임프레임</label>
                            <select
                                value={config.timeframe}
                                onChange={e => set('timeframe', e.target.value)}
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                            >
                                {TIMEFRAME_OPTIONS.map(tf => (
                                    <option key={tf} value={tf}>{tf}</option>
                                ))}
                            </select>
                        </div>
                        <div />
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                        <div>
                            <label className="text-xs text-slate-400 mb-1.5 block">시작일</label>
                            <input
                                type="date"
                                value={config.startDate}
                                onChange={e => set('startDate', e.target.value)}
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                            />
                        </div>
                        <div>
                            <label className="text-xs text-slate-400 mb-1.5 block">종료일</label>
                            <input
                                type="date"
                                value={config.endDate}
                                onChange={e => set('endDate', e.target.value)}
                                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                            />
                        </div>
                    </div>
                    <div className="flex gap-6">
                        <div className="flex items-center gap-2">
                            <Toggle checked={config.includeBacktest} onChange={v => set('includeBacktest', v)} label="백테스트" />
                            <span className="text-sm text-slate-300">백테스트 포함</span>
                        </div>
                        <div className="flex items-center gap-2">
                            <Toggle checked={config.includeWalkForward} onChange={v => set('includeWalkForward', v)} label="Walk-Forward" />
                            <span className="text-sm text-slate-300">Walk-Forward 포함</span>
                        </div>
                    </div>
                    {config.includeWalkForward && (
                        <div className="grid grid-cols-2 gap-4 pl-1 border-l-2 border-indigo-600/30">
                            <div>
                                <label className="text-xs text-slate-400 mb-1.5 block">학습 비율 (inSampleRatio)</label>
                                <input
                                    type="number" min={0.5} max={0.9} step={0.05}
                                    value={config.inSampleRatio}
                                    onChange={e => set('inSampleRatio', Number(e.target.value))}
                                    className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                                />
                            </div>
                            <div>
                                <label className="text-xs text-slate-400 mb-1.5 block">윈도우 수</label>
                                <input
                                    type="number" min={3} max={10}
                                    value={config.windowCount}
                                    onChange={e => set('windowCount', Number(e.target.value))}
                                    className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                                />
                            </div>
                        </div>
                    )}
                </div>
            </Section>

            {/* 코인 선택 */}
            <Section
                title={`코인 선택 (${config.coinPairs.length}개 선택)`}
                icon={<Activity size={16} className="text-slate-400" />}
            >
                <div className="space-y-3">
                    <div className="flex flex-wrap gap-2">
                        {PRESET_COINS.map(coin => (
                            <CheckChip
                                key={coin.id}
                                id={coin.id}
                                label={coin.label}
                                checked={config.coinPairs.includes(coin.id)}
                                onChange={toggleCoin}
                            />
                        ))}
                    </div>
                    {customCoins.length > 0 && (
                        <div className="flex flex-wrap gap-2">
                            {customCoins.map(coin => (
                                <span key={coin} className="flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs bg-indigo-600/30 border border-indigo-500/60 text-indigo-300">
                                    {coin}
                                    <button onClick={() => toggleCoin(coin, false)} className="text-indigo-400 hover:text-white ml-1">×</button>
                                </span>
                            ))}
                        </div>
                    )}
                    <div className="flex gap-2">
                        <input
                            type="text"
                            value={customCoin}
                            onChange={e => setCustomCoin(e.target.value)}
                            onKeyDown={e => e.key === 'Enter' && addCustomCoin()}
                            placeholder="코인 추가 (예: LINK → KRW-LINK)"
                            className="flex-1 bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white placeholder-slate-500"
                        />
                        <button
                            onClick={addCustomCoin}
                            className="px-3 py-2 text-sm bg-slate-600 hover:bg-slate-500 text-white rounded-lg transition-colors"
                        >추가</button>
                    </div>
                </div>
            </Section>

            {/* 전략 선택 */}
            <Section
                title={`전략 선택 (${config.strategyTypes.length}개 선택)`}
                icon={<BarChart2 size={16} className="text-slate-400" />}
            >
                <div className="space-y-4">
                    <div>
                        <p className="text-xs text-slate-500 mb-2">복합 전략</p>
                        <div className="flex flex-wrap gap-2">
                            {compositeStrategies.map(s => (
                                <CheckChip
                                    key={s.id} id={s.id} label={s.label}
                                    checked={config.strategyTypes.includes(s.id)}
                                    onChange={toggleStrategy}
                                />
                            ))}
                        </div>
                    </div>
                    <div>
                        <p className="text-xs text-slate-500 mb-2">단일 전략</p>
                        <div className="flex flex-wrap gap-2">
                            {singleStrategies.map(s => (
                                <CheckChip
                                    key={s.id} id={s.id} label={s.label}
                                    checked={config.strategyTypes.includes(s.id)}
                                    onChange={toggleStrategy}
                                />
                            ))}
                        </div>
                    </div>
                </div>
            </Section>

            {/* 고급 설정 */}
            <Section title="고급 설정" icon={<FlaskConical size={16} className="text-slate-400" />}>
                <div className="grid grid-cols-3 gap-4">
                    <div>
                        <label className="text-xs text-slate-400 mb-1.5 block">초기 자본 (원)</label>
                        <input
                            type="number"
                            value={config.initialCapital}
                            onChange={e => set('initialCapital', Number(e.target.value))}
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                        />
                    </div>
                    <div>
                        <label className="text-xs text-slate-400 mb-1.5 block">슬리피지 (%)</label>
                        <input
                            type="number" min={0} max={1} step={0.01}
                            value={config.slippagePct}
                            onChange={e => set('slippagePct', Number(e.target.value))}
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                        />
                    </div>
                    <div>
                        <label className="text-xs text-slate-400 mb-1.5 block">수수료 (%)</label>
                        <input
                            type="number" min={0} max={1} step={0.01}
                            value={config.feePct}
                            onChange={e => set('feePct', Number(e.target.value))}
                            className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white"
                        />
                    </div>
                </div>
            </Section>

            {/* 예상 작업량 */}
            <div className="bg-slate-800/50 rounded-xl p-4 border border-slate-700/30">
                <p className="text-xs text-slate-400">
                    예상 작업량:&nbsp;
                    <span className="text-white font-medium">
                        {config.coinPairs.length}개 코인 × {config.strategyTypes.length}개 전략 = {config.coinPairs.length * config.strategyTypes.length}개 조합
                    </span>
                    {config.includeBacktest && config.includeWalkForward && (
                        <span className="text-slate-400"> (백테스트 {config.coinPairs.length * config.strategyTypes.length}개 + Walk-Forward {config.coinPairs.length * config.strategyTypes.length}개)</span>
                    )}
                </p>
            </div>

        </div>
    );
}
