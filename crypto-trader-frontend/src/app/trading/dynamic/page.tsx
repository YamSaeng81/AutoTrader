'use client';

import Link from 'next/link';
import { useState, useEffect } from 'react';
import {
  useDynamicSessions,
  useCreateDynamicSession,
  useStartDynamicSession,
  useStopDynamicSession,
  useEmergencyStopDynamicSession,
  useDeleteDynamicSession,
} from '@/hooks';
import { strategyApi } from '@/lib/api';
import type { StrategyInfo } from '@/lib/types';

type DynamicSession = Record<string, unknown>;

const TIMEFRAME_OPTIONS = [
  { value: 'M5',  label: '5분' },
  { value: 'M15', label: '15분' },
  { value: 'H1',  label: '1시간' },
  { value: 'H4',  label: '4시간' },
  { value: 'D1',  label: '1일' },
];

const statusColor: Record<string, string> = {
  CREATED:           'bg-blue-500',
  RUNNING:           'bg-green-500',
  STOPPED:           'bg-gray-500',
  EMERGENCY_STOPPED: 'bg-red-600',
};
const statusLabel: Record<string, string> = {
  CREATED:           '대기',
  RUNNING:           '운영 중',
  STOPPED:           '정지',
  EMERGENCY_STOPPED: '비상 정지됨',
};
const scanStateLabel: Record<string, string> = {
  SCANNING:             '스캔 중',
  POSITION_MONITORING:  '포지션 감시',
};
const scanStateBadge: Record<string, string> = {
  SCANNING:             'bg-blue-500/20 text-blue-300 border-blue-500/30',
  POSITION_MONITORING:  'bg-yellow-500/20 text-yellow-300 border-yellow-500/30',
};

interface CreateForm {
  strategyType: string;
  timeframe: string;
  initialCapital: number;
  stopLossPct: number;
  investRatio: number;
  maxCandidateSize: number;
  targetWatchSize: number;
  minAtrPct: number;
  maxSpreadPct: number;
  watchlistRefreshMin: number;
}

const WATCHLIST_REFRESH_DEFAULTS: Record<string, number> = {
  M5:  10,
  M15: 30,
  H1:  60,
  H4:  240,
  D1:  1440,
};

const defaultForm: CreateForm = {
  strategyType: 'COMPOSITE_REGIME_ROUTER',
  timeframe: 'H1',
  initialCapital: 10000,
  stopLossPct: 5,
  investRatio: 80,
  maxCandidateSize: 30,
  targetWatchSize: 10,
  minAtrPct: 0.5,
  maxSpreadPct: 0.1,
  watchlistRefreshMin: 60,
};

export default function DynamicTradingPage() {
  const { data: sessions } = useDynamicSessions();
  const createSession = useCreateDynamicSession();
  const startSession  = useStartDynamicSession();
  const stopSession   = useStopDynamicSession();
  const emergencyStop = useEmergencyStopDynamicSession();
  const deleteSession = useDeleteDynamicSession();

  const [showForm, setShowForm]       = useState(false);
  const [form, setForm]               = useState<CreateForm>({ ...defaultForm });
  const [createError, setCreateError] = useState<string | null>(null);
  const [activeStrategies, setActiveStrategies] = useState<StrategyInfo[]>([]);
  const [showAllSessions, setShowAllSessions] = useState(false);

  useEffect(() => {
    strategyApi.list().then(res => {
      if (res.success && res.data) {
        const actives = res.data.filter(s => s.status === 'AVAILABLE' && s.isActive);
        setActiveStrategies(actives);
        // 폼의 전략이 활성 목록에 없으면 첫 항목으로 보정 — select가 첫 옵션을 "표시만" 하고
        // 상태는 목록에 없는 옛 기본값을 유지해 엉뚱한 전략으로 생성되는 문제 방지
        setForm(prev => actives.some(s => s.name === prev.strategyType)
          ? prev
          : { ...prev, strategyType: actives[0]?.name ?? prev.strategyType });
      }
    }).catch(() => {});
  }, []);

  const runningSessions = (sessions ?? []).filter(s => s['status'] === 'RUNNING');
  // 기본은 운영 중(RUNNING) + 대기(CREATED) 세션만 노출 — 정지된 세션이 쌓여 목록을 가리는 문제 방지
  const visibleSessions = showAllSessions
    ? (sessions ?? [])
    : (sessions ?? []).filter(s => s['status'] === 'RUNNING' || s['status'] === 'CREATED');

  const handleCreate = () => {
    setCreateError(null);
    createSession.mutate(
      {
        strategyType:        form.strategyType,
        timeframe:           form.timeframe,
        initialCapital:      form.initialCapital,
        stopLossPct:         form.stopLossPct,
        investRatio:         form.investRatio / 100,
        maxCandidateSize:    form.maxCandidateSize,
        targetWatchSize:     form.targetWatchSize,
        minAtrPct:           form.minAtrPct,
        maxSpreadPct:        form.maxSpreadPct,
        watchlistRefreshMin: form.watchlistRefreshMin,
      },
      {
        onSuccess: () => { setShowForm(false); setForm({ ...defaultForm }); },
        onError: (err: unknown) => {
          const msg = (err as { response?: { data?: { message?: string } }; message?: string })
            ?.response?.data?.message ?? (err as { message?: string })?.message ?? '세션 생성 실패';
          setCreateError(msg);
        },
      },
    );
  };

  const n = (v: unknown) => Number(v ?? 0);
  const s = (v: unknown) => String(v ?? '');

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">동적 멀티코인</h1>
          <p className="text-sm text-slate-400 mt-1">
            거래량 상위 코인을 실시간 필터링해 선택한 전략으로 자동 매매
          </p>
        </div>
        <button
          onClick={() => {
            // 활성 전략 첫 항목을 기본 선택 — defaultForm의 전략이 비활성이면 select 표시/상태 불일치 발생
            setForm({ ...defaultForm, strategyType: activeStrategies[0]?.name ?? defaultForm.strategyType });
            setCreateError(null);
            setShowForm(true);
          }}
          className="px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors"
        >
          + 새 세션
        </button>
      </div>

      {/* 요약 카드 */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">전체 세션</div>
          <div className="text-2xl font-bold text-white">
            {(sessions ?? []).length}
            <span className="text-sm font-normal text-slate-400 ml-1">개</span>
          </div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">운영 중</div>
          <div className="text-2xl font-bold text-green-400">{runningSessions.length}</div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">포지션 보유</div>
          <div className="text-2xl font-bold text-yellow-400">
            {runningSessions.filter(s => s['scanState'] === 'POSITION_MONITORING').length}
          </div>
        </div>
      </div>

      {/* 세션 목록 */}
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-white">동적 세션 목록</h2>
          <div className="flex rounded-lg overflow-hidden border border-slate-600/50 text-xs">
            <button
              onClick={() => setShowAllSessions(false)}
              className={`px-3 py-1.5 transition-colors ${!showAllSessions ? 'bg-blue-600 text-white' : 'bg-slate-700/50 text-slate-400 hover:text-white'}`}
            >
              운영 중만
            </button>
            <button
              onClick={() => setShowAllSessions(true)}
              className={`px-3 py-1.5 transition-colors ${showAllSessions ? 'bg-blue-600 text-white' : 'bg-slate-700/50 text-slate-400 hover:text-white'}`}
            >
              전체 ({(sessions ?? []).length})
            </button>
          </div>
        </div>

        {(sessions ?? []).length === 0 ? (
          <div className="text-center py-12">
            <p className="text-slate-500 mb-4">등록된 동적 세션이 없습니다.</p>
            <button
              onClick={() => setShowForm(true)}
              className="px-5 py-2 bg-blue-600/20 text-blue-400 hover:bg-blue-600/30 rounded-lg transition-colors text-sm"
            >
              첫 번째 세션 만들기
            </button>
          </div>
        ) : visibleSessions.length === 0 ? (
          <div className="text-center py-12">
            <p className="text-slate-500 mb-4">운영 중인 세션이 없습니다.</p>
            <button
              onClick={() => setShowAllSessions(true)}
              className="px-5 py-2 bg-slate-700/50 text-slate-300 hover:bg-slate-600/50 rounded-lg transition-colors text-sm"
            >
              전체 세션 보기 ({(sessions ?? []).length}개)
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            {(visibleSessions as DynamicSession[]).map(session => (
              <DynamicSessionCard
                key={s(session['id'])}
                session={session}
                onStart={() => startSession.mutate(n(session['id']))}
                onStop={() => stopSession.mutate(n(session['id']))}
                onEmergencyStop={() => {
                  if (confirm('비상 정지하시겠습니까?')) emergencyStop.mutate(n(session['id']));
                }}
                onDelete={() => {
                  if (confirm(`세션 #${s(session['id'])} (${s(session['strategyType'])})을 삭제하시겠습니까?\n삭제 후 목록에서 사라지며 전략/주문 로그는 보존됩니다.`)) {
                    deleteSession.mutate(n(session['id']));
                  }
                }}
              />
            ))}
          </div>
        )}
      </div>

      {/* 세션 생성 모달 */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 overflow-y-auto py-8">
          <div className="bg-slate-800 border border-slate-600/50 rounded-xl p-8 max-w-lg w-full mx-4 shadow-2xl">
            <h2 className="text-xl font-bold text-white mb-6">동적 멀티코인 세션 생성</h2>

            <div className="space-y-4">
              {/* 전략 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">전략</label>
                <select
                  value={form.strategyType}
                  onChange={e => setForm({ ...form, strategyType: e.target.value })}
                  className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                >
                  {activeStrategies.length > 0
                    ? activeStrategies.map(s => (
                        <option key={s.name} value={s.name}>{s.name}</option>
                      ))
                    : <option value="COMPOSITE_REGIME_ROUTER">COMPOSITE_REGIME_ROUTER</option>
                  }
                </select>
                <p className="text-xs text-slate-500 mt-1">전략 관리에서 활성화된 전략만 표시됩니다.</p>
              </div>

              {/* 타임프레임 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">타임프레임</label>
                <select
                  value={form.timeframe}
                  onChange={e => {
                    const tf = e.target.value;
                    setForm(prev => ({
                      ...prev,
                      timeframe: tf,
                      watchlistRefreshMin: WATCHLIST_REFRESH_DEFAULTS[tf] ?? prev.watchlistRefreshMin,
                    }));
                  }}
                  className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                >
                  {TIMEFRAME_OPTIONS.map(t => (
                    <option key={t.value} value={t.value}>{t.label}</option>
                  ))}
                </select>
              </div>

              {/* 투자금 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">투자 원금 (KRW)</label>
                <input
                  type="number"
                  value={form.initialCapital}
                  onChange={e => setForm({ ...form, initialCapital: Number(e.target.value) })}
                  min={10000}
                  step={10000}
                  className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                />
              </div>

              {/* 손절률 / 투자비율 */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-sm text-slate-400 mb-1">손절률 (%)</label>
                  <input
                    type="number"
                    value={form.stopLossPct}
                    onChange={e => setForm({ ...form, stopLossPct: Number(e.target.value) })}
                    min={1} max={50} step={0.5}
                    className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-sm text-slate-400 mb-1">투자 비율 (%)</label>
                  <input
                    type="number"
                    value={form.investRatio}
                    onChange={e => setForm({ ...form, investRatio: Number(e.target.value) })}
                    min={1} max={100} step={1}
                    className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                  />
                </div>
              </div>

              {/* 워치리스트 필터 설정 */}
              <div className="border border-slate-600/50 rounded-lg p-4 space-y-3">
                <p className="text-xs font-semibold text-slate-300 uppercase tracking-wider">워치리스트 필터</p>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">후보 수 (거래량 상위)</label>
                    <input
                      type="number"
                      value={form.maxCandidateSize}
                      onChange={e => setForm({ ...form, maxCandidateSize: Number(e.target.value) })}
                      min={10} max={100} step={5}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">최종 감시 종목 수</label>
                    <input
                      type="number"
                      value={form.targetWatchSize}
                      onChange={e => setForm({ ...form, targetWatchSize: Number(e.target.value) })}
                      min={3} max={30} step={1}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">최소 ATR% (변동성)</label>
                    <input
                      type="number"
                      value={form.minAtrPct}
                      onChange={e => setForm({ ...form, minAtrPct: Number(e.target.value) })}
                      min={0.1} max={10} step={0.1}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">최대 스프레드%</label>
                    <input
                      type="number"
                      value={form.maxSpreadPct}
                      onChange={e => setForm({ ...form, maxSpreadPct: Number(e.target.value) })}
                      min={0.01} max={1} step={0.01}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500"
                    />
                  </div>
                  <div className="col-span-2">
                    <label className="block text-xs text-slate-400 mb-1">
                      감시목록 갱신 주기 (분)
                      <span className="ml-2 text-slate-500">
                        — {form.timeframe} 추천: {WATCHLIST_REFRESH_DEFAULTS[form.timeframe] ?? '—'}분
                      </span>
                    </label>
                    <input
                      type="number"
                      value={form.watchlistRefreshMin}
                      onChange={e => setForm({ ...form, watchlistRefreshMin: Number(e.target.value) })}
                      min={5} max={1440} step={5}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-blue-500"
                    />
                    <p className="text-xs text-slate-600 mt-1">
                      짧을수록 시장 변화에 빠르게 반응하지만 API 호출 증가
                    </p>
                  </div>
                </div>
              </div>
            </div>

            {createError && (
              <div className="mt-4 px-4 py-3 bg-red-500/10 border border-red-500/40 rounded-lg text-sm text-red-400">
                {createError}
              </div>
            )}

            <div className="flex gap-3 mt-6">
              <button
                onClick={() => { setShowForm(false); setCreateError(null); }}
                className="flex-1 px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleCreate}
                disabled={createSession.isPending}
                className="flex-1 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded-lg transition-colors disabled:opacity-50"
              >
                {createSession.isPending ? '생성 중...' : '세션 생성'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function DynamicSessionCard({
  session,
  onStart,
  onStop,
  onEmergencyStop,
  onDelete,
}: {
  session: DynamicSession;
  onStart: () => void;
  onStop: () => void;
  onEmergencyStop: () => void;
  onDelete: () => void;
}) {
  const status    = String(session['status'] ?? '');
  const scanState = String(session['scanState'] ?? '');
  const returnPct = Number(session['returnPct'] ?? 0);
  const watchlist = (() => {
    try { return JSON.parse(String(session['watchlistJson'] ?? '[]')) as string[]; }
    catch { return []; }
  })();

  return (
    <div className="bg-slate-900/50 border border-slate-700/30 rounded-lg p-4 hover:border-slate-600/50 transition-colors">
      <div className="flex items-center justify-between">
        {/* 왼쪽: 세션 정보 */}
        <div className="flex items-center gap-4 flex-1 min-w-0">
          <div className="flex items-center gap-2 shrink-0">
            <div className={`w-2.5 h-2.5 rounded-full ${statusColor[status] ?? 'bg-gray-500'} ${status === 'RUNNING' ? 'animate-pulse' : ''}`} />
            <span className="text-xs text-slate-400 w-16">{statusLabel[status] ?? status}</span>
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-bold text-white">{String(session['strategyType'] ?? '')}</span>
              <span className="text-xs px-2 py-0.5 rounded bg-slate-700 text-slate-300">{String(session['timeframe'] ?? '')}</span>
              {status === 'RUNNING' && (
                <span className={`text-xs px-2 py-0.5 rounded border ${scanStateBadge[scanState] ?? 'bg-slate-700 text-slate-300 border-slate-600'}`}>
                  {scanStateLabel[scanState] ?? scanState}
                </span>
              )}
              {!!session['currentCoinPair'] && (
                <span className="text-xs px-2 py-0.5 rounded bg-yellow-500/20 text-yellow-300 border border-yellow-500/30 font-bold">
                  {String(session['currentCoinPair'])}
                </span>
              )}
            </div>
            <div className="text-xs text-slate-500 mt-0.5">
              원금: {Number(session['initialCapital'] ?? 0).toLocaleString()} KRW
              <span className="ml-2">후보 {String(session['maxCandidateSize'] ?? 30)}→감시 {String(session['targetWatchSize'] ?? 10)}</span>
            </div>
          </div>
        </div>

        {/* 중앙: 자산 */}
        <div className="flex items-center gap-6 mx-4">
          <div className="text-right">
            <div className="text-xs text-slate-500">총 자산</div>
            <div className="text-sm font-medium text-white">
              {Number(session['totalAssetKrw'] ?? 0).toLocaleString()} KRW
            </div>
          </div>
          <div className="text-right">
            <div className="text-xs text-slate-500">수익률</div>
            <div className={`text-sm font-bold ${returnPct >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              {returnPct >= 0 ? '+' : ''}{returnPct.toFixed(2)}%
            </div>
          </div>
        </div>

        {/* 오른쪽: 버튼 */}
        <div className="flex items-center gap-2 shrink-0">
          <Link
            href={`/trading/dynamic/${String(session['id'])}`}
            className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 hover:text-white text-xs rounded-lg transition-colors"
          >
            상세보기
          </Link>
          {status === 'CREATED' && (
            <button onClick={onStart} className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white text-xs rounded-lg transition-colors">시작</button>
          )}
          {status === 'RUNNING' && (
            <>
              <button onClick={onStop} className="px-3 py-1.5 bg-slate-600 hover:bg-slate-500 text-white text-xs rounded-lg transition-colors">정지</button>
              <button onClick={onEmergencyStop} className="px-3 py-1.5 bg-red-600 hover:bg-red-700 text-white text-xs rounded-lg transition-colors">비상정지</button>
            </>
          )}
          {(status === 'STOPPED' || status === 'EMERGENCY_STOPPED') && (
            <>
              <button onClick={onStart} className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white text-xs rounded-lg transition-colors">재시작</button>
              <button onClick={onDelete} className="px-3 py-1.5 bg-red-600/20 text-red-400 hover:bg-red-600 hover:text-white text-xs rounded-lg transition-colors">삭제</button>
            </>
          )}
          {status === 'CREATED' && (
            <button onClick={onDelete} className="px-3 py-1.5 bg-red-600/20 text-red-400 hover:bg-red-600 hover:text-white text-xs rounded-lg transition-colors">삭제</button>
          )}
        </div>
      </div>

      {/* 감시목록 */}
      {watchlist.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          <span className="text-xs text-slate-500 mr-1">감시목록:</span>
          {watchlist.map(coin => (
            <span
              key={coin}
              className={`text-xs px-1.5 py-0.5 rounded ${coin === session['currentCoinPair'] ? 'bg-yellow-500/30 text-yellow-200 font-bold' : 'bg-slate-700/80 text-slate-400'}`}
            >
              {coin.replace('KRW-', '')}
            </span>
          ))}
        </div>
      )}

      {/* 시작/정지 시각 */}
      {!!session['startedAt'] && (
        <div className="text-xs text-slate-600 mt-2">
          시작: {new Date(String(session['startedAt'])).toLocaleString('ko-KR')}
          {!!session['stoppedAt'] && (
            <span className="ml-3">정지: {new Date(String(session['stoppedAt'])).toLocaleString('ko-KR')}</span>
          )}
        </div>
      )}
    </div>
  );
}
