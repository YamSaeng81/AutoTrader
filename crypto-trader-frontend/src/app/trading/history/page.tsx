'use client';

import { useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, History, TrendingUp, TrendingDown, Plus, Trash2, Loader2, AlertTriangle, Download } from 'lucide-react';
import { format } from 'date-fns';
import { useTradingSessions, useDeleteTradingSession } from '@/hooks';
import type { LiveTradingSession } from '@/lib/types';
import { csvExportApi } from '@/lib/api';

const sessionStatusLabel: Record<string, string> = {
  CREATED: '대기',
  RUNNING: '운영 중',
  STOPPED: '정지',
  EMERGENCY_STOPPED: '비상 정지',
  DELETED: '삭제됨',
};

const sessionStatusStyle: Record<string, string> = {
  CREATED: 'bg-blue-500/20 text-blue-300 border-blue-500/30',
  RUNNING: 'bg-green-500/20 text-green-300 border-green-500/30',
  STOPPED: 'bg-slate-500/20 text-slate-400 border-slate-500/30',
  EMERGENCY_STOPPED: 'bg-red-500/20 text-red-300 border-red-500/30',
  DELETED: 'bg-slate-700/40 text-slate-500 border-slate-600/40',
};

export default function TradingHistoryPage() {
  const { data: sessions = [], isLoading } = useTradingSessions();
  const deleteSession = useDeleteTradingSession();
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [csvLoading, setCsvLoading] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [showRunningOnly, setShowRunningOnly] = useState(true);

  const displayedSessions = showRunningOnly
    ? sessions.filter((s) => s.status === 'RUNNING')
    : sessions;

  const toggleSelect = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const allSelected = displayedSessions.length > 0
    && displayedSessions.every((s) => selectedIds.has(s.id));
  const toggleSelectAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(displayedSessions.map((s) => s.id)));
  };

  async function handleCsvDownload(type: 'sessions' | 'positions') {
    setCsvLoading(type);
    // 선택된 세션이 있으면 해당 세션만(운영 여부 무관), 없으면 전체
    const ids = selectedIds.size > 0 ? [...selectedIds] : undefined;
    try {
      if (type === 'sessions') await csvExportApi.liveTradingSessions(ids);
      else await csvExportApi.liveTradingPositions(ids);
    } catch {
      alert('CSV 다운로드 중 오류가 발생했습니다.');
    } finally {
      setCsvLoading(null);
    }
  }

  const runningSessions = sessions.filter(s => s.status === 'RUNNING').length;
  const stoppedSessions = sessions.filter(s => s.status === 'STOPPED' || s.status === 'EMERGENCY_STOPPED').length;
  const totalPnl = sessions.reduce((sum, s) => sum + (s.totalAssetKrw - s.initialCapital), 0);

  const handleDelete = (session: LiveTradingSession) => {
    if (!confirm(`세션 #${session.id} (${session.strategyType} · ${session.coinPair})을 삭제하시겠습니까?`)) return;
    setDeletingId(session.id);
    deleteSession.mutate(session.id, {
      onSettled: () => setDeletingId(null),
      onError: () => alert('삭제 중 오류가 발생했습니다.'),
    });
  };

  if (isLoading) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-slate-500 gap-4">
        <Loader2 className="w-8 h-8 animate-spin text-indigo-500" />
        <p>이력 불러오는 중...</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Link
            href="/trading"
            className="p-2 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-700/50 transition-colors"
          >
            <ArrowLeft className="w-5 h-5" />
          </Link>
          <div>
            <div className="flex items-center gap-2">
              <History className="w-5 h-5 text-indigo-400" />
              <h1 className="text-2xl font-bold text-white">실전매매 이력</h1>
            </div>
            <p className="text-sm text-slate-500 mt-0.5 ml-7">
              전체 {sessions.length}개 세션 · 운영 중 {runningSessions}개 · 종료 {stoppedSessions}개
            </p>
            <label className="flex items-center gap-1.5 text-xs text-slate-400 mt-1.5 ml-7 cursor-pointer select-none w-fit">
              <input
                type="checkbox"
                checked={showRunningOnly}
                onChange={(e) => setShowRunningOnly(e.target.checked)}
                className="w-3.5 h-3.5 rounded border-slate-600 bg-slate-800 text-green-500 focus:ring-green-500 cursor-pointer"
              />
              운영 중만 보기
              <span className="text-slate-500">({runningSessions})</span>
            </label>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {selectedIds.size > 0 ? (
            <span className="flex items-center gap-2 text-xs text-slate-400 mr-1">
              <span className="px-2 py-1 bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 rounded-md font-medium">
                {selectedIds.size}개 선택됨
              </span>
              <button
                onClick={() => setSelectedIds(new Set())}
                className="text-slate-500 hover:text-slate-300 underline"
              >
                선택 해제
              </button>
            </span>
          ) : (
            <span className="text-xs text-slate-600 mr-1 hidden md:inline">
              체크박스로 세션 선택 시 해당 세션만 받습니다
            </span>
          )}
          <button
            onClick={() => handleCsvDownload('sessions')}
            disabled={csvLoading !== null}
            className="flex items-center gap-1.5 px-3 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
            title={selectedIds.size > 0 ? '선택 세션 CSV 다운로드' : '전체 세션 CSV 다운로드'}
          >
            <Download className="w-4 h-4" />
            {csvLoading === 'sessions'
              ? '...'
              : `세션 CSV${selectedIds.size > 0 ? ` (${selectedIds.size})` : ' (전체)'}`}
          </button>
          <button
            onClick={() => handleCsvDownload('positions')}
            disabled={csvLoading !== null}
            className="flex items-center gap-1.5 px-3 py-2 bg-teal-600 hover:bg-teal-700 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
            title={selectedIds.size > 0 ? '선택 세션 포지션 CSV 다운로드' : '전체 포지션 CSV 다운로드'}
          >
            <Download className="w-4 h-4" />
            {csvLoading === 'positions'
              ? '...'
              : `포지션 CSV${selectedIds.size > 0 ? ` (${selectedIds.size})` : ' (전체)'}`}
          </button>
          <Link
            href="/trading"
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" />
            새 세션
          </Link>
        </div>
      </div>

      {/* 요약 카드 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4">
          <div className="text-xs text-slate-400 mb-1">전체 세션</div>
          <div className="text-2xl font-bold text-white">{sessions.length}</div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4">
          <div className="text-xs text-slate-400 mb-1">운영 중</div>
          <div className="text-2xl font-bold text-green-400">{runningSessions}</div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4">
          <div className="text-xs text-slate-400 mb-1">종료</div>
          <div className="text-2xl font-bold text-slate-300">{stoppedSessions}</div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4">
          <div className="text-xs text-slate-400 mb-1">전체 누적 손익</div>
          <div className={`text-2xl font-bold ${totalPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
            {totalPnl >= 0 ? '+' : ''}{totalPnl.toLocaleString()}
          </div>
        </div>
      </div>

      {/* 세션 테이블 */}
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
        {sessions.length === 0 ? (
          <div className="py-16 text-center">
            <History className="w-12 h-12 text-slate-600 mx-auto mb-4" />
            <p className="text-slate-500 font-medium">실전매매 이력이 없습니다.</p>
            <Link
              href="/trading"
              className="inline-flex items-center gap-1.5 mt-4 text-sm text-blue-400 hover:underline"
            >
              <Plus className="w-4 h-4" /> 첫 번째 세션 시작하기
            </Link>
          </div>
        ) : displayedSessions.length === 0 ? (
          <div className="py-16 text-center">
            <History className="w-12 h-12 text-slate-600 mx-auto mb-4" />
            <p className="text-slate-500 font-medium">운영 중인 세션이 없습니다.</p>
            <button
              onClick={() => setShowRunningOnly(false)}
              className="mt-3 text-sm text-blue-400 hover:underline"
            >
              전체 세션 보기
            </button>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-700/50 bg-slate-900/30 text-xs uppercase tracking-wider text-slate-500">
                  <th className="px-4 py-4">
                    <input
                      type="checkbox"
                      checked={allSelected}
                      onChange={toggleSelectAll}
                      title="전체 선택/해제"
                      className="w-4 h-4 rounded border-slate-600 bg-slate-800 text-indigo-500 focus:ring-indigo-500 cursor-pointer"
                    />
                  </th>
                  <th className="text-left px-5 py-4">#</th>
                  <th className="text-left px-5 py-4">전략</th>
                  <th className="text-left px-5 py-4">코인</th>
                  <th className="text-left px-5 py-4">타임프레임</th>
                  <th className="text-right px-5 py-4">초기 자금</th>
                  <th className="text-right px-5 py-4">현재 자산</th>
                  <th className="text-right px-5 py-4">수익률</th>
                  <th className="text-left px-5 py-4">시작일</th>
                  <th className="text-left px-5 py-4">종료일</th>
                  <th className="text-left px-5 py-4">상태</th>
                  <th className="text-left px-5 py-4">정지 사유</th>
                  <th className="px-5 py-4" />
                  <th className="px-4 py-4" />
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/30">
                {displayedSessions.map((session) => {
                  const returnPct = session.initialCapital > 0
                    ? ((session.totalAssetKrw - session.initialCapital) / session.initialCapital * 100)
                    : 0;
                  const isPositive = returnPct >= 0;
                  const isRunning = session.status === 'RUNNING';
                  const isDeleting = deletingId === session.id;

                  return (
                    <tr
                      key={session.id}
                      className={`hover:bg-slate-700/20 transition-colors group text-slate-300 ${
                        selectedIds.has(session.id) ? 'bg-indigo-500/5' : ''
                      }`}
                    >
                      <td className="px-4 py-4">
                        <input
                          type="checkbox"
                          checked={selectedIds.has(session.id)}
                          onChange={() => toggleSelect(session.id)}
                          className="w-4 h-4 rounded border-slate-600 bg-slate-800 text-indigo-500 focus:ring-indigo-500 cursor-pointer"
                        />
                      </td>
                      <td className="px-5 py-4 text-slate-500 font-medium">#{session.id}</td>
                      <td className="px-5 py-4 font-semibold text-white">{session.strategyType}</td>
                      <td className="px-5 py-4">{session.coinPair}</td>
                      <td className="px-5 py-4">
                        <span className="px-2 py-0.5 bg-slate-700 text-slate-300 rounded text-xs font-medium">
                          {session.timeframe}
                        </span>
                      </td>
                      <td className="px-5 py-4 text-right font-medium">
                        {session.initialCapital.toLocaleString()}
                      </td>
                      <td className="px-5 py-4 text-right font-medium">
                        {session.totalAssetKrw.toLocaleString()}
                      </td>
                      <td className="px-5 py-4 text-right">
                        <span className={`inline-flex items-center gap-1 font-semibold px-2 py-0.5 rounded-full text-xs border ${
                          isPositive
                            ? 'bg-green-500/10 text-green-400 border-green-500/20'
                            : 'bg-red-500/10 text-red-400 border-red-500/20'
                        }`}>
                          {isPositive ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                          {isPositive ? '+' : ''}{returnPct.toFixed(2)}%
                        </span>
                      </td>
                      <td className="px-5 py-4 text-slate-500 text-xs">
                        {session.startedAt
                          ? format(new Date(session.startedAt), 'yyyy.MM.dd HH:mm')
                          : '-'}
                      </td>
                      <td className="px-5 py-4 text-slate-500 text-xs">
                        {session.stoppedAt
                          ? format(new Date(session.stoppedAt), 'yyyy.MM.dd HH:mm')
                          : '-'}
                      </td>
                      <td className="px-5 py-4">
                        <span className={`px-2 py-0.5 text-xs font-bold rounded-full border ${sessionStatusStyle[session.status]}`}>
                          {isRunning && <span className="mr-1">●</span>}
                          {sessionStatusLabel[session.status]}
                        </span>
                      </td>
                      <td className="px-5 py-4 max-w-xs">
                        {session.circuitBreakerReason ? (
                          <span className="flex items-center gap-1 text-xs text-red-400">
                            <AlertTriangle className="w-3 h-3 shrink-0" />
                            <span className="truncate" title={session.circuitBreakerReason}>
                              {session.circuitBreakerReason}
                            </span>
                          </span>
                        ) : (
                          <span className="text-slate-600 text-xs">—</span>
                        )}
                      </td>
                      <td className="px-5 py-4">
                        <Link
                          href={`/trading/${session.id}`}
                          className="text-xs font-semibold text-blue-400 hover:text-blue-300 hover:underline"
                        >
                          상세
                        </Link>
                      </td>
                      <td className="px-4 py-4">
                        {isRunning || session.status === 'DELETED' ? (
                          <button
                            disabled
                            title={session.status === 'DELETED' ? '이미 삭제된 세션입니다' : '운영 중인 세션은 삭제할 수 없습니다'}
                            className="p-1.5 rounded-lg text-slate-600 cursor-not-allowed"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        ) : (
                          <button
                            onClick={() => handleDelete(session)}
                            disabled={isDeleting || deleteSession.isPending}
                            title="삭제"
                            className="p-1.5 rounded-lg text-slate-500 hover:text-red-400 hover:bg-red-500/10 disabled:opacity-40 disabled:cursor-not-allowed transition-colors opacity-0 group-hover:opacity-100"
                          >
                            {isDeleting ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Trash2 className="w-4 h-4" />
                            )}
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
      </div>
    </div>
  );
}
