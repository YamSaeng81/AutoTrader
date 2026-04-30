'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import {
  useTradingStatus, useTradingSessions, useCreateTradingSession,
  useCreateMultipleTradingSessions,
  useStartTradingSession, useStopTradingSession, useEmergencyStopSession,
  useDeleteTradingSession, useEmergencyStopAll, useExchangeHealth,
} from '@/hooks';
import { strategyApi } from '@/lib/api';
import type { LiveTradingSession, LiveTradingStartRequest, StrategyInfo } from '@/lib/types';

const sessionStatusColor: Record<string, string> = {
  CREATED: 'bg-blue-500',
  RUNNING: 'bg-green-500',
  STOPPED: 'bg-gray-500',
  EMERGENCY_STOPPED: 'bg-red-600',
};

const sessionStatusLabel: Record<string, string> = {
  CREATED: '대기',
  RUNNING: '운영 중',
  STOPPED: '정지',
  EMERGENCY_STOPPED: '비상 정지됨',
};

const healthColor: Record<string, string> = {
  UP: 'text-green-400',
  DEGRADED: 'text-yellow-400',
  DOWN: 'text-red-400',
};

const healthBg: Record<string, string> = {
  UP: 'bg-green-500/10 border-green-500/30',
  DEGRADED: 'bg-yellow-500/10 border-yellow-500/30',
  DOWN: 'bg-red-500/10 border-red-500/30',
};


const COIN_OPTIONS = [
  'KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE',
  'KRW-ADA', 'KRW-AVAX', 'KRW-DOT', 'KRW-MATIC', 'KRW-LINK',
];

const TIMEFRAME_OPTIONS = [
  { value: 'M1', label: '1분' },
  { value: 'M5', label: '5분' },
  { value: 'M15', label: '15분' },
  { value: 'H1', label: '1시간' },
  { value: 'H4', label: '4시간' },
  { value: 'D1', label: '1일' },
];

const defaultForm: LiveTradingStartRequest = {
  strategyType: 'COMPOSITE',
  coinPair: 'KRW-BTC',
  timeframe: 'M5',
  initialCapital: 10000,
  stopLossPct: 5,
  investRatio: 80,
};

const TEST_TIMED_FORM: LiveTradingStartRequest = {
  strategyType: 'TEST_TIMED',
  coinPair: 'KRW-ETH',
  timeframe: 'M1',
  initialCapital: 10000,
  stopLossPct: 100,
};

const isTestTimed = (form: LiveTradingStartRequest) => form.strategyType === 'TEST_TIMED';

export default function TradingPage() {
  const { data: status } = useTradingStatus();
  const { data: sessions } = useTradingSessions();
  const { data: health } = useExchangeHealth();
  const createSession = useCreateTradingSession();
  const createMulti = useCreateMultipleTradingSessions();
  const startSession = useStartTradingSession();
  const stopSession = useStopTradingSession();
  const emergencyStopSession = useEmergencyStopSession();
  const deleteSession = useDeleteTradingSession();
  const emergencyStopAll = useEmergencyStopAll();

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [showEmergencyConfirm, setShowEmergencyConfirm] = useState(false);
  const [form, setForm] = useState<LiveTradingStartRequest>({ ...defaultForm });
  const [selectedStrategies, setSelectedStrategies] = useState<string[]>([]);
  const [createError, setCreateError] = useState<string | null>(null);
  const [activeStrategies, setActiveStrategies] = useState<StrategyInfo[]>([]);
  const [strategyInfoMap, setStrategyInfoMap] = useState<Record<string, StrategyInfo>>({});

  useEffect(() => {
    strategyApi.list().then(res => {
      if (res.success && res.data) {
        setActiveStrategies(res.data.filter(s => s.status === 'AVAILABLE' && s.isActive));
        setStrategyInfoMap(Object.fromEntries(res.data.map(s => [s.name, s])));
      }
    }).catch(() => {});
  }, []);

  const runningSessions = (sessions ?? []).filter(s => s.status === 'RUNNING');

  const handleCreate = () => {
    setCreateError(null);
    const onError = (err: unknown) => {
      const msg = (err as { response?: { data?: { message?: string } }; message?: string })
        ?.response?.data?.message ?? (err as { message?: string })?.message ?? '세션 생성에 실패했습니다.';
      setCreateError(msg);
    };
    const onSuccess = () => { setShowCreateForm(false); setForm({ ...defaultForm }); setSelectedStrategies([]); };

    if (!isTestTimed(form) && selectedStrategies.length >= 2) {
      createMulti.mutate({
        strategyTypes: selectedStrategies,
        coinPair: form.coinPair,
        timeframe: form.timeframe,
        initialCapital: form.initialCapital,
        stopLossPct: form.stopLossPct,
        investRatio: form.investRatio,
      }, { onSuccess, onError });
    } else {
      const req = isTestTimed(form) ? form : { ...form, strategyType: selectedStrategies[0] ?? form.strategyType };
      createSession.mutate(req, { onSuccess, onError });
    }
  };

  const handleEmergencyStopAll = () => {
    emergencyStopAll.mutate(undefined, { onSettled: () => setShowEmergencyConfirm(false) });
  };

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">실전 매매</h1>
          <p className="text-sm text-slate-400 mt-1">다중 세션 자동매매 운영 및 모니터링</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={() => { setForm({ ...TEST_TIMED_FORM }); setSelectedStrategies(['TEST_TIMED']); setShowCreateForm(true); }}
            className="px-5 py-2.5 bg-amber-600 hover:bg-amber-700 text-white font-medium rounded-lg transition-colors"
          >
            🧪 테스트 세션
          </button>
          <button
            onClick={() => { setForm({ ...defaultForm }); setSelectedStrategies(['COMPOSITE']); setShowCreateForm(true); }}
            className="px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors"
          >
            + 새 세션
          </button>
          <button
            onClick={() => setShowEmergencyConfirm(true)}
            disabled={runningSessions.length === 0}
            className="px-6 py-2.5 bg-red-600 hover:bg-red-700 text-white font-bold rounded-lg shadow-lg shadow-red-600/30 transition-all disabled:opacity-40 disabled:cursor-not-allowed"
          >
            EMERGENCY STOP ALL
          </button>
        </div>
      </div>

      {/* 전체 비상 정지 확인 모달 */}
      {showEmergencyConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="bg-slate-800 border border-red-500/50 rounded-xl p-8 max-w-md w-full mx-4 shadow-2xl">
            <h2 className="text-xl font-bold text-red-400 mb-4">전체 비상 정지 확인</h2>
            <p className="text-slate-300 mb-2">모든 세션에 대해 다음 작업이 즉시 실행됩니다:</p>
            <ul className="text-slate-400 text-sm mb-6 space-y-1 list-disc list-inside">
              <li>실행 중인 모든 세션 비상 정지</li>
              <li>모든 대기/진행 중인 주문 취소</li>
              <li>자동매매 즉시 중단</li>
            </ul>
            <div className="flex gap-3">
              <button
                onClick={() => setShowEmergencyConfirm(false)}
                className="flex-1 px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleEmergencyStopAll}
                disabled={emergencyStopAll.isPending}
                className="flex-1 px-4 py-2 bg-red-600 hover:bg-red-700 text-white font-bold rounded-lg transition-colors disabled:opacity-50"
              >
                {emergencyStopAll.isPending ? '처리 중...' : '전체 비상 정지'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 새 세션 생성 모달 */}
      {showCreateForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="bg-slate-800 border border-slate-600/50 rounded-xl p-8 max-w-lg w-full mx-4 shadow-2xl">
            <h2 className="text-xl font-bold text-white mb-6">
              {isTestTimed(form) ? '🧪 테스트 세션 생성' : '새 매매 세션 생성'}
            </h2>

            {/* TEST_TIMED 안내 배너 */}
            {isTestTimed(form) && (
              <div className="mb-4 px-4 py-3 bg-amber-500/10 border border-amber-500/40 rounded-lg text-sm text-amber-300 space-y-1">
                <p className="font-semibold">실전매매 동작 검증용 테스트 전략</p>
                <ul className="text-xs text-amber-400 list-disc list-inside space-y-0.5">
                  <li>세션 시작 직후 KRW-ETH 즉시 매수</li>
                  <li>3분 경과 후 무조건 매도</li>
                  <li>코인 / 타임프레임 / 원금 고정 (변경 불가)</li>
                </ul>
              </div>
            )}

            <div className="space-y-4">
              {/* 전략 선택 (체크박스 멀티) */}
              {!isTestTimed(form) && (
                <div>
                  <div className="flex items-center justify-between mb-1">
                    <label className="text-sm text-slate-400">
                      전략
                      <span className="ml-2 px-1.5 py-0.5 bg-blue-600 text-white text-xs rounded">
                        {selectedStrategies.length}개 선택
                      </span>
                    </label>
                    <div className="flex gap-2 text-xs">
                      <button
                        type="button"
                        onClick={() => setSelectedStrategies(activeStrategies.map(s => s.name))}
                        className="text-slate-400 hover:text-blue-400 transition-colors"
                      >전체 선택</button>
                      <span className="text-slate-600">|</span>
                      <button
                        type="button"
                        onClick={() => setSelectedStrategies([])}
                        className="text-slate-400 hover:text-slate-300 transition-colors"
                      >전체 해제</button>
                    </div>
                  </div>
                  <div className="bg-slate-700/50 border border-slate-600 rounded-lg p-2 space-y-1 max-h-48 overflow-y-auto">
                    {activeStrategies.map(s => {
                      const recCoins = strategyInfoMap[s.name]?.recommendedCoins ?? [];
                      return (
                        <label key={s.name} className="flex items-center gap-2 px-2 py-1.5 hover:bg-slate-700 rounded cursor-pointer">
                          <input
                            type="checkbox"
                            checked={selectedStrategies.includes(s.name)}
                            onChange={e => {
                              if (e.target.checked) setSelectedStrategies(prev => [...prev, s.name]);
                              else setSelectedStrategies(prev => prev.filter(x => x !== s.name));
                            }}
                            className="accent-blue-500 flex-shrink-0"
                          />
                          <span className="text-sm text-white flex-1">{s.name}</span>
                          {recCoins.length > 0 && (
                            <span className="flex gap-1 flex-shrink-0">
                              {recCoins.map(coin => (
                                <span key={coin} className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-blue-500/20 text-blue-300 border border-blue-500/30">
                                  {coin}
                                </span>
                              ))}
                            </span>
                          )}
                        </label>
                      );
                    })}
                    {activeStrategies.length === 0 && (
                      <p className="text-xs text-slate-500 px-2 py-1">전략 관리 페이지에서 전략을 활성화하세요.</p>
                    )}
                  </div>
                  {selectedStrategies.length === 0 && (
                    <p className="text-xs text-red-400 mt-1">전략을 최소 1개 선택하세요.</p>
                  )}
                  {selectedStrategies.length >= 2 && (
                    <p className="text-xs text-blue-400 mt-1">{selectedStrategies.length}개 세션이 동시에 생성됩니다.</p>
                  )}
                </div>
              )}

              {/* 코인 선택 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">코인</label>
                {isTestTimed(form) ? (
                  <div className="w-full bg-slate-700/50 border border-slate-600/50 text-slate-400 rounded-lg px-3 py-2 cursor-not-allowed">
                    KRW-ETH <span className="text-xs text-slate-500 ml-1">(고정)</span>
                  </div>
                ) : (
                  <select
                    value={form.coinPair}
                    onChange={e => setForm({ ...form, coinPair: e.target.value })}
                    className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                  >
                    {COIN_OPTIONS.map(c => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                )}
              </div>

              {/* 타임프레임 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">타임프레임</label>
                {isTestTimed(form) ? (
                  <div className="w-full bg-slate-700/50 border border-slate-600/50 text-slate-400 rounded-lg px-3 py-2 cursor-not-allowed">
                    1분 (M1) <span className="text-xs text-slate-500 ml-1">(고정)</span>
                  </div>
                ) : (
                  <select
                    value={form.timeframe}
                    onChange={e => setForm({ ...form, timeframe: e.target.value })}
                    className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                  >
                    {TIMEFRAME_OPTIONS.map(t => (
                      <option key={t.value} value={t.value}>{t.label}</option>
                    ))}
                  </select>
                )}
              </div>

              {/* 투자금 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">투자 원금 (KRW)</label>
                {isTestTimed(form) ? (
                  <div className="w-full bg-slate-700/50 border border-slate-600/50 text-slate-400 rounded-lg px-3 py-2 cursor-not-allowed">
                    10,000 KRW <span className="text-xs text-slate-500 ml-1">(고정)</span>
                  </div>
                ) : (
                  <>
                    <input
                      type="number"
                      value={form.initialCapital}
                      onChange={e => setForm({ ...form, initialCapital: Number(e.target.value) })}
                      min={10000}
                      step={10000}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                    />
                    <p className="text-xs text-slate-500 mt-1">최소 10,000 KRW</p>
                  </>
                )}
              </div>

              {/* 손절률 / 투자비율 (테스트 전략은 숨김) */}
              {!isTestTimed(form) && (
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-sm text-slate-400 mb-1">손절률 (%)</label>
                    <input
                      type="number"
                      value={form.stopLossPct ?? 5}
                      onChange={e => setForm({ ...form, stopLossPct: Number(e.target.value) })}
                      min={1}
                      max={50}
                      step={0.5}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                    />
                  </div>
                  <div>
                    <label className="block text-sm text-slate-400 mb-1">투자 비율 (%)</label>
                    <input
                      type="number"
                      value={form.investRatio ?? 80}
                      onChange={e => setForm({ ...form, investRatio: Number(e.target.value) })}
                      min={1}
                      max={100}
                      step={1}
                      className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                    />
                    <p className="text-xs text-slate-500 mt-1">가용금액의 {form.investRatio ?? 80}% 매수</p>
                  </div>
                </div>
              )}
            </div>

            {createError && (
              <div className="mt-4 px-4 py-3 bg-red-500/10 border border-red-500/40 rounded-lg text-sm text-red-400">
                {createError}
              </div>
            )}

            <div className="flex gap-3 mt-6">
              <button
                onClick={() => { setShowCreateForm(false); setForm({ ...defaultForm }); setSelectedStrategies(['COMPOSITE']); setCreateError(null); }}
                className="flex-1 px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded-lg transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleCreate}
                disabled={createSession.isPending || createMulti.isPending || (!isTestTimed(form) && selectedStrategies.length === 0)}
                className={`flex-1 px-4 py-2 text-white font-bold rounded-lg transition-colors disabled:opacity-50 ${
                  isTestTimed(form)
                    ? 'bg-amber-600 hover:bg-amber-700'
                    : 'bg-blue-600 hover:bg-blue-700'
                }`}
              >
                {(createSession.isPending || createMulti.isPending)
                  ? '생성 중...'
                  : !isTestTimed(form) && selectedStrategies.length >= 2
                    ? `${selectedStrategies.length}개 세션 생성`
                    : '세션 생성'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 상태 카드 */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* 운영 세션 */}
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">운영 세션</div>
          <div className="text-2xl font-bold text-white">
            {status?.runningSessions ?? 0}
            <span className="text-sm font-normal text-slate-400 ml-1">/ {status?.totalSessions ?? 0}</span>
          </div>
        </div>

        {/* 거래소 상태 */}
        <div className={`border rounded-xl p-5 ${healthBg[health?.status ?? 'DOWN']}`}>
          <div className="text-xs text-slate-400 mb-2">거래소 상태</div>
          <div className="flex items-center gap-2 mb-1">
            <span className={`text-lg font-bold ${healthColor[health?.status ?? 'DOWN']}`}>
              {health?.status ?? 'N/A'}
            </span>
          </div>
          <div className="text-xs text-slate-400">
            Latency: {health?.latencyMs ?? '-'}ms
            {health?.webSocketConnected !== undefined && (
              <span className="ml-2">WS: {health.webSocketConnected ? 'Connected' : 'Disconnected'}</span>
            )}
          </div>
        </div>

        {/* 열린 포지션 */}
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">열린 포지션</div>
          <div className="text-2xl font-bold text-white">{status?.openPositions ?? 0}</div>
        </div>

        {/* 총 손익 */}
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">총 손익</div>
          <div className={`text-2xl font-bold ${(status?.totalPnl ?? 0) >= 0 ? 'text-green-400' : 'text-red-400'}`}>
            {(status?.totalPnl ?? 0) >= 0 ? '+' : ''}{(status?.totalPnl ?? 0).toLocaleString()} KRW
          </div>
        </div>
      </div>

      {/* 세션 목록 */}
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-white">매매 세션</h2>
          <span className="text-xs text-slate-500">최대 5개 동시 운영</span>
        </div>

        {(sessions ?? []).length === 0 ? (
          <div className="text-center py-12">
            <p className="text-slate-500 mb-4">등록된 매매 세션이 없습니다.</p>
            <button
              onClick={() => setShowCreateForm(true)}
              className="px-5 py-2 bg-blue-600/20 text-blue-400 hover:bg-blue-600/30 rounded-lg transition-colors text-sm"
            >
              첫 번째 세션 만들기
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            {(sessions ?? []).map((session: LiveTradingSession) => (
              <SessionCard
                key={session.id}
                session={session}
                onStart={() => startSession.mutate(session.id)}
                onStop={() => stopSession.mutate(session.id)}
                onEmergencyStop={() => emergencyStopSession.mutate(session.id)}
                onDelete={() => {
                  if (confirm('이 세션을 삭제하시겠습니까?')) {
                    deleteSession.mutate(session.id);
                  }
                }}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function SessionCard({
  session,
  onStart,
  onStop,
  onEmergencyStop,
  onDelete,
}: {
  session: LiveTradingSession;
  onStart: () => void;
  onStop: () => void;
  onEmergencyStop: () => void;
  onDelete: () => void;
}) {
  const returnPct = session.initialCapital > 0
    ? ((session.totalAssetKrw - session.initialCapital) / session.initialCapital * 100)
    : 0;

  return (
    <div className="bg-slate-900/50 border border-slate-700/30 rounded-lg p-4 hover:border-slate-600/50 transition-colors">
      <div className="flex items-center justify-between">
        {/* 왼쪽: 세션 정보 */}
        <div className="flex items-center gap-4 flex-1 min-w-0">
          {/* 상태 표시 */}
          <div className="flex items-center gap-2 shrink-0">
            <div className={`w-2.5 h-2.5 rounded-full ${sessionStatusColor[session.status]} ${session.status === 'RUNNING' ? 'animate-pulse' : ''}`} />
            <span className="text-xs text-slate-400 w-16">{sessionStatusLabel[session.status]}</span>
          </div>

          {/* 코인 + 전략 */}
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="font-bold text-white">{session.coinPair}</span>
              <span className="text-xs px-2 py-0.5 rounded bg-slate-700 text-slate-300">{session.strategyType}</span>
              <span className="text-xs text-slate-500">{session.timeframe}</span>
            </div>
            <div className="text-xs text-slate-500 mt-0.5">
              투자금: {session.initialCapital.toLocaleString()} KRW
              {session.stopLossPct && <span className="ml-2">손절: {session.stopLossPct}%</span>}
              {session.investRatio && <span className="ml-2">투자비율: {Math.round(session.investRatio * 100)}%</span>}
            </div>
          </div>
        </div>

        {/* 중앙: 자산 정보 */}
        <div className="flex items-center gap-6 mx-4">
          <div className="text-right">
            <div className="text-xs text-slate-500">총 자산</div>
            <div className="text-sm font-medium text-white">{session.totalAssetKrw.toLocaleString()} KRW</div>
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
            href={`/trading/${session.id}`}
            className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 text-xs rounded-lg transition-colors"
          >
            상세보기
          </Link>
          {session.status === 'CREATED' && (
            <>
              <button onClick={onStart} className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white text-xs rounded-lg transition-colors">시작</button>
              <button onClick={onDelete} className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 text-xs rounded-lg transition-colors">삭제</button>
            </>
          )}
          {session.status === 'RUNNING' && (
            <>
              <button onClick={onStop} className="px-3 py-1.5 bg-slate-600 hover:bg-slate-500 text-white text-xs rounded-lg transition-colors">정지</button>
              <button onClick={onEmergencyStop} className="px-3 py-1.5 bg-red-600 hover:bg-red-700 text-white text-xs rounded-lg transition-colors">비상정지</button>
            </>
          )}
          {(session.status === 'STOPPED' || session.status === 'EMERGENCY_STOPPED') && (
            <>
              <button onClick={onStart} className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white text-xs rounded-lg transition-colors">재시작</button>
              <button onClick={onDelete} className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 text-xs rounded-lg transition-colors">삭제</button>
            </>
          )}
        </div>
      </div>

      {/* 시작 시각 */}
      {session.startedAt && (
        <div className="text-xs text-slate-600 mt-2">
          시작: {new Date(session.startedAt).toLocaleString('ko-KR')}
          {session.stoppedAt && <span className="ml-3">정지: {new Date(session.stoppedAt).toLocaleString('ko-KR')}</span>}
        </div>
      )}
    </div>
  );
}
