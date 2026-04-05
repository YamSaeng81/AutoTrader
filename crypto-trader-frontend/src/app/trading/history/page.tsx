'use client';

import { useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, History, TrendingUp, TrendingDown, Plus, Trash2, Loader2, AlertTriangle } from 'lucide-react';
import { format } from 'date-fns';
import { useTradingSessions, useDeleteTradingSession } from '@/hooks';
import type { LiveTradingSession } from '@/lib/types';

const sessionStatusLabel: Record<string, string> = {
  CREATED: '대기',
  RUNNING: '운영 중',
  STOPPED: '정지',
  EMERGENCY_STOPPED: '비상 정지',
};

const sessionStatusStyle: Record<string, string> = {
  CREATED: 'bg-blue-500/20 text-blue-300 border-blue-500/30',
  RUNNING: 'bg-green-500/20 text-green-300 border-green-500/30',
  STOPPED: 'bg-slate-500/20 text-slate-400 border-slate-500/30',
  EMERGENCY_STOPPED: 'bg-red-500/20 text-red-300 border-red-500/30',
};

export default function TradingHistoryPage() {
  const { data: sessions = [], isLoading } = useTradingSessions();
  const deleteSession = useDeleteTradingSession();
  const [deletingId, setDeletingId] = useState<number | null>(null);

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
          </div>
        </div>
        <Link
          href="/trading"
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg transition-colors"
        >
          <Plus className="w-4 h-4" />
          새 세션
        </Link>
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
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-700/50 bg-slate-900/30 text-xs uppercase tracking-wider text-slate-500">
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
                {sessions.map((session) => {
                  const returnPct = session.initialCapital > 0
                    ? ((session.totalAssetKrw - session.initialCapital) / session.initialCapital * 100)
                    : 0;
                  const isPositive = returnPct >= 0;
                  const isRunning = session.status === 'RUNNING';
                  const isDeleting = deletingId === session.id;

                  return (
                    <tr
                      key={session.id}
                      className="hover:bg-slate-700/20 transition-colors group text-slate-300"
                    >
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
                        {isRunning ? (
                          <button
                            disabled
                            title="운영 중인 세션은 삭제할 수 없습니다"
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
