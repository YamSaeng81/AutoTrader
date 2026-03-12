'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import {
  useTradingStatus, useTradingSessions, useCreateTradingSession,
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
  strategyType: 'VWAP',
  coinPair: 'KRW-BTC',
  timeframe: 'M5',
  initialCapital: 1000000,
  stopLossPct: 5,
};

export default function TradingPage() {
  const { data: status } = useTradingStatus();
  const { data: sessions } = useTradingSessions();
  const { data: health } = useExchangeHealth();
  const createSession = useCreateTradingSession();
  const startSession = useStartTradingSession();
  const stopSession = useStopTradingSession();
  const emergencyStopSession = useEmergencyStopSession();
  const deleteSession = useDeleteTradingSession();
  const emergencyStopAll = useEmergencyStopAll();

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [showEmergencyConfirm, setShowEmergencyConfirm] = useState(false);
  const [form, setForm] = useState<LiveTradingStartRequest>({ ...defaultForm });
  const [activeStrategies, setActiveStrategies] = useState<StrategyInfo[]>([]);

  useEffect(() => {
    strategyApi.list().then(res => {
      if (res.success && res.data) {
        setActiveStrategies(res.data.filter(s => s.status === 'AVAILABLE' && s.isActive));
      }
    }).catch(() => {});
  }, []);

  const runningSessions = (sessions ?? []).filter(s => s.status === 'RUNNING');

  const handleCreate = () => {
    createSession.mutate(form, {
      onSuccess: () => {
        setShowCreateForm(false);
        setForm({ ...defaultForm });
      },
    });
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
            onClick={() => setShowCreateForm(true)}
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
            <h2 className="text-xl font-bold text-white mb-6">새 매매 세션 생성</h2>
            <div className="space-y-4">
              {/* 전략 선택 */}
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
                    : <option value="">활성화된 전략이 없습니다</option>
                  }
                </select>
                {activeStrategies.length === 0 && (
                  <p className="text-xs text-amber-400 mt-1">전략 관리 페이지에서 전략을 활성화하세요.</p>
                )}
              </div>

              {/* 코인 선택 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">코인</label>
                <select
                  value={form.coinPair}
                  onChange={e => setForm({ ...form, coinPair: e.target.value })}
                  className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                >
                  {COIN_OPTIONS.map(c => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </div>

              {/* 타임프레임 */}
              <div>
                <label className="block text-sm text-slate-400 mb-1">타임프레임</label>
                <select
                  value={form.timeframe}
                  onChange={e => setForm({ ...form, timeframe: e.target.value })}
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
                  min={100000}
                  step={100000}
                  className="w-full bg-slate-700 border border-slate-600 text-white rounded-lg px-3 py-2 focus:outline-none focus:border-blue-500"
                />
                <p className="text-xs text-slate-500 mt-1">최소 100,000 KRW</p>
              </div>

              {/* 손절률 */}
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
            </div>

            <div className="flex gap-3 mt-6">
              <button
                onClick={() => { setShowCreateForm(false); setForm({ ...defaultForm }); }}
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
