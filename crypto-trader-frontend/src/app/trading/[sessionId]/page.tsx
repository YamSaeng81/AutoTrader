'use client';

import { use, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { tradingApi, logApi, accountApi } from '@/lib/api';
import type { LiveTradingSession, Position, LiveOrder } from '@/lib/types';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { format } from 'date-fns';
import { parseUtc, fmtKstLocale } from '@/lib/utils';
import {
  ComposedChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import { ChevronDown, ChevronRight } from 'lucide-react';

const sessionStatusLabel: Record<string, string> = {
  CREATED: '대기',
  RUNNING: '운영 중',
  STOPPED: '정지',
  EMERGENCY_STOPPED: '비상 정지됨',
};

const sessionStatusBadge: Record<string, string> = {
  CREATED: 'bg-blue-500/20 text-blue-300 border-blue-500/40',
  RUNNING: 'bg-green-500/20 text-green-300 border-green-500/40',
  STOPPED: 'bg-gray-500/20 text-gray-400 border-gray-500/40',
  EMERGENCY_STOPPED: 'bg-red-500/20 text-red-300 border-red-500/40',
};

const SIGNAL_STYLE: Record<string, string> = {
  BUY:  'bg-green-500/20 text-green-300',
  SELL: 'bg-red-500/20 text-red-300',
  HOLD: 'bg-slate-700 text-slate-400',
};

function StrategyLogAccordion({ logs }: { logs: any[] | undefined }) {
  const [openGroups, setOpenGroups] = useState<Set<string>>(new Set());

  if (!logs || logs.length === 0) {
    return (
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-700/50">
          <h2 className="text-base font-semibold text-white">전략 분석 로그</h2>
        </div>
        <div className="py-10 text-center text-slate-500 text-sm">전략 로그가 없습니다.</div>
      </div>
    );
  }

  const groups: Record<string, any[]> = {};
  for (const log of logs) {
    const key = log.strategyName ?? '알 수 없음';
    if (!groups[key]) groups[key] = [];
    groups[key].push(log);
  }

  const toggle = (key: string) => {
    setOpenGroups(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };

  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
      <div className="px-5 py-4 border-b border-slate-700/50 flex items-center justify-between">
        <h2 className="text-base font-semibold text-white">전략 분석 로그</h2>
        <span className="text-xs text-slate-500">{Object.keys(groups).length}개 전략</span>
      </div>
      <div className="divide-y divide-slate-700/30">
        {Object.entries(groups).map(([strategyName, groupLogs]) => {
          const isOpen = openGroups.has(strategyName);
          const latest = groupLogs[0];
          const signalCounts = groupLogs.reduce((acc: Record<string, number>, l: any) => {
            acc[l.signal] = (acc[l.signal] ?? 0) + 1;
            return acc;
          }, {});

          return (
            <div key={strategyName}>
              <button
                onClick={() => toggle(strategyName)}
                className="w-full flex items-center gap-3 px-5 py-4 text-left hover:bg-slate-700/20 transition-colors"
              >
                <span className="shrink-0 text-slate-500">
                  {isOpen ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                </span>
                <span className="font-semibold text-sm text-slate-200 flex-1 text-left">
                  {strategyName}
                </span>
                <span className="flex items-center gap-1.5 text-xs">
                  {Object.entries(signalCounts).map(([sig, cnt]) => (
                    <span key={sig} className={`px-2 py-0.5 rounded-full font-bold ${SIGNAL_STYLE[sig] ?? 'bg-slate-700 text-slate-400'}`}>
                      {sig} {cnt}
                    </span>
                  ))}
                </span>
                <span className="text-xs text-slate-500 shrink-0 ml-2">
                  {latest?.createdAt ? format(parseUtc(latest.createdAt)!, 'MM/dd HH:mm') : ''}
                </span>
                <span className="text-xs text-slate-500 shrink-0 ml-3 tabular-nums">
                  총 {groupLogs.length}건
                </span>
              </button>

              {isOpen && (
                <div className="border-t border-slate-700/50 overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-slate-500 border-b border-slate-700/50 uppercase tracking-wide">
                        <th className="text-left py-2 px-5">시간</th>
                        <th className="text-left py-2 px-5">신호</th>
                        <th className="text-left py-2 px-5">마켓 상태</th>
                        <th className="text-left py-2 px-5">판단 이유</th>
                      </tr>
                    </thead>
                    <tbody>
                      {groupLogs.map((log: any) => (
                        <tr key={log.id} className="border-b border-slate-700/30 hover:bg-slate-700/20 transition-colors">
                          <td className="py-2.5 px-5 text-slate-400 whitespace-nowrap">
                            {fmtKstLocale(log.createdAt)}
                          </td>
                          <td className="py-2.5 px-5">
                            <span className={`px-2 py-0.5 rounded font-bold ${SIGNAL_STYLE[log.signal] ?? 'bg-slate-700 text-slate-400'}`}>
                              {log.signal}
                            </span>
                          </td>
                          <td className="py-2.5 px-5 text-slate-400">{log.marketRegime ?? '-'}</td>
                          <td className="py-2.5 px-5 text-slate-400 max-w-sm truncate" title={log.reason}>
                            {log.reason ?? '-'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

const orderStateBadge: Record<string, string> = {
  PENDING: 'bg-yellow-500/20 text-yellow-300',
  SUBMITTED: 'bg-blue-500/20 text-blue-300',
  PARTIAL_FILLED: 'bg-cyan-500/20 text-cyan-300',
  FILLED: 'bg-green-500/20 text-green-300',
  CANCELLED: 'bg-gray-500/20 text-gray-400',
  FAILED: 'bg-red-500/20 text-red-300',
};

export default function LiveSessionDetailPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const { sessionId } = use(params);
  const sessionIdNum = Number(sessionId);
  const router = useRouter();
  const queryClient = useQueryClient();

  const { data: sessionRes, isLoading } = useQuery({
    queryKey: ['trading', 'session', sessionId],
    queryFn: () => tradingApi.getSession(sessionIdNum),
    refetchInterval: 5000,
  });

  const { data: positionsRes } = useQuery({
    queryKey: ['trading', 'session', sessionId, 'positions'],
    queryFn: () => tradingApi.getSessionPositions(sessionIdNum),
    refetchInterval: 5000,
  });

  const { data: ordersRes } = useQuery({
    queryKey: ['trading', 'session', sessionId, 'orders'],
    queryFn: () => tradingApi.getSessionOrders(sessionIdNum, 0, 50),
    refetchInterval: 10000,
  });

  const { data: chartRes } = useQuery({
    queryKey: ['trading', 'session', sessionId, 'chart'],
    queryFn: () => tradingApi.getSessionChart(sessionIdNum),
    refetchInterval: 60000,
  });

  const { data: strategyLogsRes } = useQuery({
    queryKey: ['logs', 'strategy', 'live', sessionId],
    queryFn: () => logApi.strategyLogs(0, 20, 'LIVE', sessionIdNum),
    refetchInterval: 10000,
  });

  const { data: accountRes } = useQuery({
    queryKey: ['account', 'summary'],
    queryFn: () => accountApi.summary(),
    refetchInterval: 30000,
  });

  const stopMutation = useMutation({
    mutationFn: () => tradingApi.stopSession(sessionIdNum),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['trading'] });
    },
  });

  const emergencyMutation = useMutation({
    mutationFn: () => tradingApi.emergencyStopSession(sessionIdNum),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['trading'] });
    },
  });

  const session = sessionRes?.data as unknown as LiveTradingSession | undefined;
  const positions = (positionsRes?.data as unknown as Position[]) ?? [];
  const ordersData = ordersRes?.data as unknown as { content: LiveOrder[]; totalElements: number } | undefined;
  const orders = ordersData?.content ?? [];
  const accountSummary = accountRes?.data as import('@/lib/types').AccountSummary | undefined;

  const openPositions = positions.filter(p => p.status === 'OPEN' && Number(p.size) > 0);
  const closedPositions = positions.filter(p => p.status === 'CLOSED');

  const chartCandles = (chartRes?.data as any)?.candles as any[] | undefined;
  const chartOrders = (chartRes?.data as any)?.orders as any[] | undefined;

  const toMs = (t: any) => t ? (typeof t === 'number' ? t : parseUtc(t)!.getTime()) : null;
  const chartData = (() => {
    const candles = chartCandles?.map((c: any) => ({
      time: c.time,
      close: Number(c.close),
      buyOrder: null as any,
      sellOrder: null as any,
    })) ?? [];
    if (!chartOrders || candles.length === 0) return candles;
    for (const o of chartOrders) {
      const ms = toMs(o.filledAt);
      if (!ms) continue;
      let best = 0, bestDiff = Infinity;
      for (let i = 0; i < candles.length; i++) {
        const d = Math.abs(candles[i].time - ms);
        if (d < bestDiff) { bestDiff = d; best = i; }
      }
      if (o.side === 'BUY') candles[best].buyOrder = o;
      else candles[best].sellOrder = o;
    }
    return candles;
  })();
  const isRunning = session?.status === 'RUNNING';

  const filledOrders = orders.filter(o => o.state === 'FILLED');
  const buyCount = filledOrders.filter(o => o.side === 'BUY').length;
  const sellCount = filledOrders.filter(o => o.side === 'SELL').length;
  const totalRealizedPnl = positions.reduce((sum, p) => sum + Number(p.realizedPnl), 0);
  const winRate = closedPositions.length > 0
    ? (closedPositions.filter(p => Number(p.realizedPnl) > 0).length / closedPositions.length * 100)
    : null;

  const returnPct = session && session.initialCapital > 0
    ? ((session.totalAssetKrw - session.initialCapital) / session.initialCapital * 100)
    : 0;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20 text-slate-400">
        <div className="text-center">
          <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mx-auto mb-3" />
          <p>세션 정보 로딩 중...</p>
        </div>
      </div>
    );
  }

  if (!session) {
    return (
      <div className="p-8 text-center">
        <p className="text-slate-500 mb-4">세션을 찾을 수 없습니다.</p>
        <Link href="/trading" className="text-blue-400 hover:underline text-sm">
          목록으로 돌아가기
        </Link>
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
            ← 목록
          </Link>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-bold text-white">
                {session.strategyType} · {session.coinPair}
              </h1>
              <span className={`px-2 py-0.5 text-xs font-bold rounded-full border ${sessionStatusBadge[session.status]}`}>
                {session.status === 'RUNNING' && <span className="mr-1">●</span>}
                {sessionStatusLabel[session.status]}
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-0.5">
              세션 #{session.id} · {session.timeframe}
              {session.startedAt && ` · ${fmtKstLocale(session.startedAt)} 시작`}
            </p>
          </div>
        </div>

        <div className="flex gap-2">
          {isRunning && (
            <>
              <button
                onClick={() => {
                  if (confirm('세션을 정지하시겠습니까?')) stopMutation.mutate();
                }}
                disabled={stopMutation.isPending}
                className="px-4 py-2 bg-slate-600 hover:bg-slate-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50"
              >
                {stopMutation.isPending ? '정지 중...' : '정지'}
              </button>
              <button
                onClick={() => {
                  if (confirm('비상 정지하시겠습니까? 모든 포지션이 시장가로 청산됩니다.')) {
                    emergencyMutation.mutate();
                  }
                }}
                disabled={emergencyMutation.isPending}
                className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-sm font-bold rounded-lg transition-colors disabled:opacity-50"
              >
                {emergencyMutation.isPending ? '처리 중...' : '비상정지'}
              </button>
            </>
          )}
        </div>
      </div>

      {/* 자산 요약 카드 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5 md:col-span-2">
          <div className="text-xs text-slate-400 mb-1">총 평가 자산</div>
          <div className="text-3xl font-extrabold text-white">
            {session.totalAssetKrw.toLocaleString()}
            <span className="text-lg text-slate-400 font-normal ml-1">KRW</span>
          </div>
          <div className="flex items-center gap-2 mt-3">
            <span className={`px-2 py-0.5 rounded-full text-sm font-bold ${returnPct >= 0 ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'}`}>
              {returnPct >= 0 ? '+' : ''}{returnPct.toFixed(2)}%
            </span>
            <span className="text-xs text-slate-500">
              초기 자금: {session.initialCapital.toLocaleString()} KRW
            </span>
          </div>
        </div>

        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">가용 현금 (내부)</div>
          <div className="text-xl font-bold text-white">{session.availableKrw.toLocaleString()}</div>
          <div className="text-xs text-slate-500 mt-1">KRW</div>
          {accountSummary?.apiKeyConfigured && accountSummary.availableKrw != null && (
            <div className="mt-2 pt-2 border-t border-slate-700/50">
              <div className="text-xs text-slate-500">Upbit 실계좌</div>
              <div className="text-sm font-semibold mt-0.5 text-slate-300">
                {accountSummary.availableKrw.toLocaleString()} KRW
              </div>
            </div>
          )}
        </div>

        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">손절률</div>
          <div className="text-xl font-bold text-red-400">
            {session.stopLossPct != null ? `${session.stopLossPct}%` : '-'}
          </div>
          <div className="text-xs text-slate-500 mt-1">Stop Loss</div>
        </div>
      </div>

      {/* 매매 요약 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">매수 횟수</div>
          <div className="text-2xl font-bold text-green-400">{buyCount}<span className="text-sm font-normal text-slate-500 ml-1">회</span></div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">매도 횟수</div>
          <div className="text-2xl font-bold text-red-400">{sellCount}<span className="text-sm font-normal text-slate-500 ml-1">회</span></div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">실현 손익</div>
          <div className={`text-2xl font-bold ${totalRealizedPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
            {totalRealizedPnl >= 0 ? '+' : ''}{totalRealizedPnl.toLocaleString()}
            <span className="text-xs font-normal text-slate-500 ml-1">KRW</span>
          </div>
        </div>
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <div className="text-xs text-slate-400 mb-2">승률</div>
          {winRate !== null ? (
            <>
              <div className={`text-2xl font-bold ${winRate >= 50 ? 'text-green-400' : 'text-red-400'}`}>
                {winRate.toFixed(1)}<span className="text-sm font-normal text-slate-500 ml-0.5">%</span>
              </div>
              <div className="mt-2 h-1.5 bg-slate-700 rounded-full overflow-hidden">
                <div
                  className={`h-full rounded-full transition-all ${winRate >= 50 ? 'bg-green-500' : 'bg-red-500'}`}
                  style={{ width: `${winRate}%` }}
                />
              </div>
              <div className="text-xs text-slate-500 mt-1">{closedPositions.filter(p => Number(p.realizedPnl) > 0).length}승 {closedPositions.filter(p => Number(p.realizedPnl) <= 0).length}패</div>
            </>
          ) : (
            <div className="text-xl font-bold text-slate-500">-</div>
          )}
        </div>
      </div>

      {/* 가격 차트 */}
      {chartData.length > 0 && (() => {
        const CHART_HEIGHT = 300;
        const PX_PER_POINT = 14;
        const SCROLL_THRESHOLD = 60;
        const MAX_WIDTH = 4000;
        const needsScroll = chartData.length > SCROLL_THRESHOLD;
        const fixedWidth = needsScroll
          ? Math.min(MAX_WIDTH, Math.max(800, chartData.length * PX_PER_POINT))
          : undefined;

        const ChartTooltipContent = ({ active, payload, label }: any) => {
          if (!active || !payload?.length) return null;
          const d = payload[0]?.payload;
          const order = d?.buyOrder || d?.sellOrder;
          const isBuy = !!d?.buyOrder;
          return (
            <div className="bg-slate-800 border border-slate-600 rounded-xl shadow-lg p-3 text-xs min-w-[180px]">
              <div className="text-slate-400 mb-1">{label ? format(new Date(label), 'yyyy-MM-dd HH:mm') : ''}</div>
              <div className="font-semibold text-slate-200">종가: {Number(d?.close).toLocaleString()} KRW</div>
              {order && (
                <div className={`mt-2 pt-2 border-t border-slate-700 space-y-1 ${isBuy ? 'text-green-400' : 'text-red-400'}`}>
                  <div className="font-bold">{isBuy ? '▲ 매수 체결' : '▼ 매도 체결'}</div>
                  <div>가격: <span className="font-semibold">{Number(order.price).toLocaleString()} KRW</span></div>
                  <div>수량: <span className="font-semibold">{Number(order.quantity).toFixed(6)}</span></div>
                  {order.signalReason && (
                    <div className="text-slate-400 pt-1 border-t border-slate-700 leading-relaxed">{order.signalReason}</div>
                  )}
                </div>
              )}
            </div>
          );
        };

        const chartInner = (w?: number) => (
          <ComposedChart width={w} height={CHART_HEIGHT} data={chartData} margin={{ top: 10, right: 20, bottom: 0, left: 10 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
            <XAxis
              dataKey="time" type="number" domain={['dataMin', 'dataMax']} scale="time"
              tickFormatter={(t) => format(new Date(t), 'MM/dd HH:mm')}
              tick={{ fontSize: 11, fill: '#94a3b8' }} tickCount={6} minTickGap={40}
            />
            <YAxis
              domain={['auto', 'auto']} tickFormatter={(v) => Number(v).toLocaleString()}
              tick={{ fontSize: 11, fill: '#94a3b8' }} width={80}
            />
            <Tooltip content={<ChartTooltipContent />} />
            <Line
              type="monotone" dataKey="close" stroke="#6366f1" strokeWidth={1.5}
              dot={(props: any) => {
                const { cx, cy, payload } = props;
                if (payload.buyOrder) return <circle key={`b-${cx}`} cx={cx} cy={cy} r={7} fill="#22c55e" stroke="#fff" strokeWidth={2} />;
                if (payload.sellOrder) return <circle key={`s-${cx}`} cx={cx} cy={cy} r={7} fill="#ef4444" stroke="#fff" strokeWidth={2} />;
                return <g key={`e-${cx}`} />;
              }}
              activeDot={{ r: 5, stroke: '#6366f1', strokeWidth: 2, fill: '#fff' }}
              isAnimationActive={false}
            />
          </ComposedChart>
        );

        return (
          <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-700/50 flex items-center justify-between">
              <h2 className="text-base font-semibold text-white">가격 차트 (매수/매도 시점)</h2>
              {needsScroll && <span className="text-xs text-slate-500">← 좌우 스크롤 →</span>}
            </div>
            <div className="p-4">
              {needsScroll ? (
                <div className="overflow-x-auto">
                  <div style={{ width: fixedWidth }}>{chartInner(fixedWidth)}</div>
                </div>
              ) : (
                <div style={{ height: CHART_HEIGHT }}>
                  <ResponsiveContainer width="100%" height="100%">{chartInner() as any}</ResponsiveContainer>
                </div>
              )}
            </div>
          </div>
        );
      })()}

      {/* 종료된 거래 이력 */}
      {closedPositions.length > 0 && (
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
          <div className="px-5 py-4 border-b border-slate-700/50 flex items-center justify-between">
            <h2 className="text-base font-semibold text-white">종료된 거래 이력</h2>
            <span className="px-2 py-0.5 bg-slate-700 text-slate-300 rounded text-xs font-bold">{closedPositions.length}건</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-slate-400 border-b border-slate-700/50 text-xs uppercase">
                  <th className="text-left py-3 px-5">코인</th>
                  <th className="text-right py-3 px-5">진입가</th>
                  <th className="text-right py-3 px-5">수량</th>
                  <th className="text-right py-3 px-5">실현 손익</th>
                  <th className="text-right py-3 px-5">수익률</th>
                  <th className="text-left py-3 px-5">진입 시각</th>
                  <th className="text-left py-3 px-5">청산 시각</th>
                </tr>
              </thead>
              <tbody>
                {closedPositions.map((pos: Position) => {
                  const pnl = Number(pos.realizedPnl);
                  const costBasis = Number(pos.avgPrice) * Number(pos.size);
                  const pnlPct = costBasis > 0 ? (pnl / costBasis * 100) : 0;
                  return (
                    <tr key={pos.id} className="border-b border-slate-700/30 hover:bg-slate-700/20">
                      <td className="py-3 px-5 font-medium text-white">{pos.coinPair}</td>
                      <td className="py-3 px-5 text-right text-slate-300">{Number(pos.avgPrice).toLocaleString()}</td>
                      <td className="py-3 px-5 text-right text-slate-300">{Number(pos.size).toFixed(6)}</td>
                      <td className={`py-3 px-5 text-right font-bold ${pnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {pnl >= 0 ? '+' : ''}{pnl.toLocaleString()}
                      </td>
                      <td className={`py-3 px-5 text-right text-xs font-semibold ${pnlPct >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {pnlPct >= 0 ? '+' : ''}{pnlPct.toFixed(2)}%
                      </td>
                      <td className="py-3 px-5 text-slate-400 text-xs">{fmtKstLocale(pos.openedAt)}</td>
                      <td className="py-3 px-5 text-slate-400 text-xs">{fmtKstLocale(pos.closedAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 포지션 섹션 */}
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-700/50 flex items-center justify-between">
          <h2 className="text-base font-semibold text-white">열린 포지션</h2>
          <span className="px-2 py-0.5 bg-blue-500/20 text-blue-300 rounded text-xs font-bold">{openPositions.length}개</span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-slate-400 border-b border-slate-700/50 text-xs uppercase">
                <th className="text-left py-3 px-5">코인</th>
                <th className="text-left py-3 px-5">방향</th>
                <th className="text-right py-3 px-5">수량</th>
                <th className="text-right py-3 px-5">평균 진입가</th>
                <th className="text-right py-3 px-5">미실현 손익</th>
                <th className="text-right py-3 px-5">실현 손익</th>
                <th className="text-left py-3 px-5">진입 시각</th>
              </tr>
            </thead>
            <tbody>
              {openPositions.length === 0 ? (
                <tr>
                  <td colSpan={7} className="py-10 text-center text-slate-500">열린 포지션이 없습니다.</td>
                </tr>
              ) : openPositions.map((pos: Position) => (
                <tr key={pos.id} className="border-b border-slate-700/30 hover:bg-slate-700/20">
                  <td className="py-3 px-5 font-medium text-white">{pos.coinPair}</td>
                  <td className="py-3 px-5">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${pos.side === 'LONG' ? 'bg-green-500/20 text-green-300' : 'bg-red-500/20 text-red-300'}`}>
                      {pos.side}
                    </span>
                  </td>
                  <td className="py-3 px-5 text-right text-slate-300">{pos.size}</td>
                  <td className="py-3 px-5 text-right text-slate-300">{Number(pos.avgPrice).toLocaleString()}</td>
                  <td className={`py-3 px-5 text-right font-medium ${pos.unrealizedPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                    {pos.unrealizedPnl >= 0 ? '+' : ''}{Number(pos.unrealizedPnl).toLocaleString()}
                  </td>
                  <td className={`py-3 px-5 text-right ${pos.realizedPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                    {pos.realizedPnl >= 0 ? '+' : ''}{Number(pos.realizedPnl).toLocaleString()}
                  </td>
                  <td className="py-3 px-5 text-slate-400 text-xs">{new Date(pos.openedAt).toLocaleString('ko-KR')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* 주문 내역 섹션 */}
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-700/50 flex items-center justify-between">
          <h2 className="text-base font-semibold text-white">주문 내역</h2>
          <span className="text-xs text-slate-500">총 {ordersData?.totalElements ?? 0}건</span>
        </div>
        {orders.length === 0 ? (
          <div className="py-10 text-center text-slate-500 text-sm">주문 내역이 없습니다.</div>
        ) : (
          <div className="divide-y divide-slate-700/30">
            {orders.map((order: LiveOrder) => (
              <div key={order.id} className="px-5 py-4 flex items-center justify-between hover:bg-slate-700/20 transition-colors">
                <div className="flex items-center gap-4 min-w-0">
                  <span className={`w-12 h-12 shrink-0 flex items-center justify-center rounded-xl font-bold text-xs ${order.side === 'BUY' ? 'bg-green-500/20 text-green-300' : 'bg-red-500/20 text-red-300'}`}>
                    {order.side === 'BUY' ? '매수' : '매도'}
                  </span>
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-white text-sm">{order.coinPair}</span>
                      <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${orderStateBadge[order.state] ?? ''}`}>
                        {order.state}
                      </span>
                      <span className="text-xs text-slate-500">{order.orderType}</span>
                    </div>
                    <div className="text-xs text-slate-400 mt-0.5">
                      {new Date(order.filledAt ?? order.createdAt).toLocaleString('ko-KR')} 체결
                    </div>
                    {order.signalReason && (
                      <div className="text-xs text-blue-400 mt-0.5 truncate max-w-xs" title={order.signalReason}>
                        {order.signalReason}
                      </div>
                    )}
                    {order.failedReason && (
                      <div className="text-xs text-red-400 mt-0.5 truncate max-w-xs" title={order.failedReason}>
                        실패: {order.failedReason}
                      </div>
                    )}
                  </div>
                </div>
                <div className="text-right shrink-0 space-y-0.5">
                  <div className="font-medium text-white text-sm">
                    {order.price ? Number(order.price).toLocaleString() : '-'} KRW
                  </div>
                  <div className="text-xs text-slate-400">
                    수량: {order.quantity} · 체결: {order.filledQuantity}
                  </div>
                  {order.exchangeOrderId && (
                    <div className="text-xs text-slate-600 font-mono">{order.exchangeOrderId}</div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 전략 분석 로그 */}
      <StrategyLogAccordion logs={(strategyLogsRes?.data as any)?.content} />

      {/* 세션 정보 */}
      <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
        <h2 className="text-base font-semibold text-white mb-4">세션 정보</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
          <div>
            <div className="text-xs text-slate-500 mb-1">생성일시</div>
            <div className="text-slate-300">{new Date(session.createdAt).toLocaleString('ko-KR')}</div>
          </div>
          <div>
            <div className="text-xs text-slate-500 mb-1">시작일시</div>
            <div className="text-slate-300">{session.startedAt ? new Date(session.startedAt).toLocaleString('ko-KR') : '-'}</div>
          </div>
          <div>
            <div className="text-xs text-slate-500 mb-1">종료일시</div>
            <div className="text-slate-300">{session.stoppedAt ? new Date(session.stoppedAt).toLocaleString('ko-KR') : '-'}</div>
          </div>
          <div>
            <div className="text-xs text-slate-500 mb-1">마지막 업데이트</div>
            <div className="text-slate-300">{new Date(session.updatedAt).toLocaleString('ko-KR')}</div>
          </div>
        </div>
      </div>
    </div>
  );
}
