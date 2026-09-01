'use client';

import { use } from 'react';
import Link from 'next/link';
import {
  useDynamicSession,
  useDynamicSessionPositions,
  useStartDynamicSession,
  useStopDynamicSession,
  useEmergencyStopDynamicSession,
} from '@/hooks';

// ── 상수 ──────────────────────────────────────────────────────────────────────

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
const regimeLabel: Record<string, string> = {
  TREND:        '추세',
  RANGE:        '횡보',
  VOLATILITY:   '변동성',
  TRANSITIONAL: '전환 중',
};

// ── 유틸 ──────────────────────────────────────────────────────────────────────

function n(v: unknown, fallback = 0): number   { return Number(v ?? fallback); }
function s(v: unknown, fallback = ''): string   { return String(v ?? fallback); }

function fmt(v: unknown): string {
  const num = Number(v ?? 0);
  return num.toLocaleString('ko-KR');
}

function fmtPct(v: unknown, decimals = 2): string {
  const num = Number(v ?? 0);
  return (num >= 0 ? '+' : '') + num.toFixed(decimals) + '%';
}

function fmtKst(iso: unknown): string {
  if (!iso) return '—';
  return new Date(String(iso)).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
}

function holdDuration(openedAt: unknown): string {
  if (!openedAt) return '—';
  const ms  = Date.now() - new Date(String(openedAt)).getTime();
  const min = Math.floor(ms / 60000);
  if (min < 60)   return `${min}분`;
  const h   = Math.floor(min / 60);
  const m   = min % 60;
  return `${h}시간 ${m}분`;
}

function nextRefreshLabel(refreshedAt: unknown, refreshMin: unknown): string {
  if (!refreshedAt) return '미갱신';
  const elapsed  = (Date.now() - new Date(String(refreshedAt)).getTime()) / 60000;
  const remaining = n(refreshMin) - elapsed;
  if (remaining <= 0) return '갱신 대기 중';
  const mins = Math.floor(remaining);
  if (mins < 60) return `${mins}분 후`;
  return `${Math.floor(mins / 60)}시간 ${mins % 60}분 후`;
}

function pnlColor(v: unknown): string {
  const num = Number(v ?? 0);
  return num > 0 ? 'text-green-400' : num < 0 ? 'text-red-400' : 'text-slate-300';
}

// ── 컴포넌트 ─────────────────────────────────────────────────────────────────

export default function DynamicSessionDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const sessionId = Number(id);

  const { data: session, isLoading } = useDynamicSession(sessionId);
  const { data: history = [] }       = useDynamicSessionPositions(sessionId);
  const startSession   = useStartDynamicSession();
  const stopSession    = useStopDynamicSession();
  const emergencyStop  = useEmergencyStopDynamicSession();

  if (isLoading || !session) {
    return (
      <div className="flex items-center justify-center h-64 text-slate-400">
        {isLoading ? '불러오는 중...' : '세션을 찾을 수 없습니다.'}
      </div>
    );
  }

  const status    = s(session['status']);
  const scanState = s(session['scanState']);
  const isRunning = status === 'RUNNING';

  const watchlist = (() => {
    try { return JSON.parse(s(session['watchlistJson'], '[]')) as string[]; }
    catch { return [] as string[]; }
  })();

  const pos = session['currentPosition'] as Record<string, unknown> | undefined;
  const returnPct = n(session['returnPct']);

  return (
    <div className="space-y-5">

      {/* ── 헤더 ── */}
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div className="flex items-center gap-3 min-w-0">
          <Link
            href="/trading/dynamic"
            className="text-slate-400 hover:text-white transition-colors shrink-0 text-sm"
          >
            ← 목록
          </Link>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              {s(session['tradingMode'] || 'REAL') === 'PAPER' && (
                <span className="text-xs px-2 py-0.5 rounded bg-blue-500/20 text-blue-300 border border-blue-500/40 font-bold">
                  모의
                </span>
              )}
              <h1 className="text-xl font-bold text-white">{s(session['strategyType'])}</h1>
              <span className="text-xs px-2 py-0.5 rounded bg-slate-700 text-slate-300">
                {s(session['timeframe'])}
              </span>
              <span className="text-xs px-2 py-0.5 rounded bg-slate-700 text-slate-400">
                #{sessionId}
              </span>
            </div>
            <div className="flex items-center gap-2 mt-1">
              <div className={`w-2 h-2 rounded-full ${statusColor[status] ?? 'bg-gray-500'} ${isRunning ? 'animate-pulse' : ''}`} />
              <span className="text-xs text-slate-400">{statusLabel[status] ?? status}</span>
              {isRunning && (
                <span className={`text-xs px-2 py-0.5 rounded border ${
                  scanState === 'SCANNING'
                    ? 'bg-blue-500/20 text-blue-300 border-blue-500/30'
                    : 'bg-yellow-500/20 text-yellow-300 border-yellow-500/30'
                }`}>
                  {scanState === 'SCANNING' ? '🔍 스캔 중' : '📊 포지션 감시'}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* 액션 버튼 */}
        <div className="flex gap-2 shrink-0">
          {status === 'CREATED' && (
            <button
              onClick={() => startSession.mutate(sessionId)}
              className="px-4 py-2 bg-green-600 hover:bg-green-700 text-white text-sm rounded-lg transition-colors"
            >
              시작
            </button>
          )}
          {isRunning && (
            <>
              <button
                onClick={() => stopSession.mutate(sessionId)}
                className="px-4 py-2 bg-slate-600 hover:bg-slate-500 text-white text-sm rounded-lg transition-colors"
              >
                정지
              </button>
              <button
                onClick={() => { if (confirm('비상 정지하시겠습니까?')) emergencyStop.mutate(sessionId); }}
                className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-sm rounded-lg transition-colors"
              >
                비상정지
              </button>
            </>
          )}
          {(status === 'STOPPED' || status === 'EMERGENCY_STOPPED') && (
            <button
              onClick={() => startSession.mutate(sessionId)}
              className="px-4 py-2 bg-green-600 hover:bg-green-700 text-white text-sm rounded-lg transition-colors"
            >
              재시작
            </button>
          )}
        </div>
      </div>

      {/* ── 성과 요약 ── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <PerfCard label="초기 자본" value={`${fmt(session['initialCapital'])} KRW`} />
        <PerfCard label="현재 자산" value={`${fmt(session['totalAssetKrw'])} KRW`} />
        <PerfCard label="가용 KRW"  value={`${fmt(session['availableKrw'])} KRW`} />
        <PerfCard
          label="수익률"
          value={fmtPct(returnPct)}
          valueColor={returnPct > 0 ? 'text-green-400' : returnPct < 0 ? 'text-red-400' : 'text-slate-300'}
        />
      </div>

      {/* ── 손익 분해 (이 세션의 포지션만 집계) ── */}
      <PnlBreakdownPanel session={session} />

      {/* ── 보유 코인 이력 ── */}
      <PositionHistoryPanel history={history} />

      {/* ── 현재 단계 ── */}
      {isRunning && scanState === 'SCANNING' && (
        <ScanningPanel
          watchlist={watchlist}
          currentCoinPair={s(session['currentCoinPair'])}
          watchlistRefreshedAt={session['watchlistRefreshedAt']}
          watchlistRefreshMin={session['watchlistRefreshMin']}
        />
      )}

      {isRunning && scanState === 'POSITION_MONITORING' && pos && (
        <MonitoringPanel pos={pos} stopLossPct={session['stopLossPct']} maxHoldHours={session['maxHoldHours']} />
      )}

      {!isRunning && watchlist.length > 0 && (
        <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
          <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-3">
            마지막 감시목록
          </h2>
          <div className="flex flex-wrap gap-1.5">
            {watchlist.map(coin => (
              <span key={coin} className="text-xs px-2 py-1 rounded bg-slate-700/80 text-slate-400">
                {coin.replace('KRW-', '')}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* ── 필터 설정 + 세션 설정 ── */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <FilterSettingsPanel session={session} />
        <SessionSettingsPanel session={session} />
      </div>

      {/* ── 시각 정보 ── */}
      <div className="text-xs text-slate-600 space-y-0.5">
        {!!session['startedAt'] && <div>시작: {fmtKst(session['startedAt'])}</div>}
        {!!session['stoppedAt'] && <div>정지: {fmtKst(session['stoppedAt'])}</div>}
        <div>생성: {fmtKst(session['createdAt'])}</div>
      </div>
    </div>
  );
}

// ── PerfCard ──────────────────────────────────────────────────────────────────

function PerfCard({
  label,
  value,
  valueColor = 'text-white',
}: {
  label: string;
  value: string;
  valueColor?: string;
}) {
  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-4">
      <div className="text-xs text-slate-400 mb-1">{label}</div>
      <div className={`text-base font-bold ${valueColor} break-all`}>{value}</div>
    </div>
  );
}

// ── 청산 사유 어휘 ────────────────────────────────────────────────────────────
//
// ExitReason enum (V73)과 1:1. 자유 텍스트 사유와 달리 집계 가능한 축이라, 이걸 보면
// "손절에 몰려 있는가 / 시간초과로 끌려나가는가"를 한눈에 읽을 수 있다.

const exitReasonLabel: Record<string, string> = {
  STOP_LOSS:       '손절',
  TAKE_PROFIT:     '익절',
  TRAILING_STOP:   '트레일링',
  TIME_STOP:       '시간초과',
  STRATEGY_SIGNAL: '전략신호',
  BLACK_SWAN:      '급락가드',
  FORCED_STOP:     '강제정지',
  UNKNOWN:         '미분류',
};

const exitReasonStyle: Record<string, string> = {
  STOP_LOSS:       'bg-red-500/20 text-red-300 border-red-500/30',
  TAKE_PROFIT:     'bg-green-500/20 text-green-300 border-green-500/30',
  TRAILING_STOP:   'bg-green-500/15 text-green-400 border-green-500/25',
  TIME_STOP:       'bg-amber-500/20 text-amber-300 border-amber-500/30',
  STRATEGY_SIGNAL: 'bg-blue-500/20 text-blue-300 border-blue-500/30',
  BLACK_SWAN:      'bg-orange-500/20 text-orange-300 border-orange-500/30',
  FORCED_STOP:     'bg-slate-600/40 text-slate-300 border-slate-500/30',
  UNKNOWN:         'bg-slate-700/50 text-slate-400 border-slate-600/30',
};

function holdLabel(minutes: unknown): string {
  const m = Number(minutes ?? 0);
  if (m < 60) return `${m}분`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

// ── PnlBreakdownPanel ─────────────────────────────────────────────────────────
//
// `수익률`(위 카드)은 total_asset_krw 기준이라 보유 중 시세 변동을 반영하지 않는다.
// 여기서는 이 세션의 포지션만 집계한 실현/미실현을 분리해 보여줘서, 위 수치와
// 어긋날 때 원인(미실현 반영 안 됨)을 바로 읽을 수 있게 한다.
//
// ⚠️ 2026-09-01 이전에는 이 패널이 모의(PAPER) 세션에서 **항상 0**이었다. 백엔드가
// session_kind 를 'DYNAMIC' 으로 하드코딩해 'DYN_PAPER' 로 저장된 페이퍼 포지션을 한
// 건도 못 찾았기 때문이다. 아래 '보유 코인 이력'도 같은 이유로 통째로 비어 있었다.

function PnlBreakdownPanel({ session }: { session: Record<string, unknown> }) {
  const realized   = n(session['realizedPnl']);
  const unrealized = n(session['unrealizedPnl']);
  const total      = n(session['totalPnl']);
  const totalFee   = n(session['totalFee']);
  const grossPnl   = n(session['grossPnl']);
  const closed     = n(session['closedTradeCount']);
  const openCount  = n(session['openPositionCount']);
  const wins       = n(session['winCount']);
  const winRate    = session['winRatePct'];
  const avgPnl     = session['avgPnl'];
  const avgHold    = session['avgHoldMinutes'];
  const exitCounts = (session['exitReasonCounts'] ?? {}) as Record<string, number>;
  const exitTotal  = Object.values(exitCounts).reduce((a, b) => a + Number(b ?? 0), 0);

  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5 space-y-4">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider">손익 분해</h2>
        <span className="text-xs text-slate-600">이 세션의 포지션만 집계</span>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <MiniStat label="실현 손익"   value={signed(realized)}   unit="KRW · 수수료 차감 후" color={pnlColor(realized)} />
        <MiniStat label="미실현 손익" value={signed(unrealized)} unit={openCount > 0 ? `보유 ${openCount}건` : 'KRW'} color={pnlColor(unrealized)} />
        <MiniStat label="합계"        value={signed(total)}      unit="KRW" color={pnlColor(total)} />
        <MiniStat
          label="지불 수수료"
          value={`-${fmt(totalFee)}`}
          unit={grossPnl !== 0 ? `수수료 전 ${signed(grossPnl)}` : 'KRW'}
          color="text-slate-400"
        />
      </div>

      {/* 실현손익은 이미 순손익이다 — 화면에서 또 빼면 이중 차감이 된다. */}
      <p className="text-xs text-slate-600">
        실현 손익은 매수·매도 수수료(0.05% × 2회)를 <b className="text-slate-500">이미 뺀 순손익</b>입니다.
        &lsquo;수수료 전&rsquo;은 마찰비용이 성과를 얼마나 갉아먹었는지 보기 위한 가정값입니다.
      </p>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <MiniStat label="청산 거래" value={String(closed)} unit="건" />
        <MiniStat
          label="승률"
          value={winRate === null || winRate === undefined ? '—' : `${n(winRate).toFixed(1)}%`}
          unit={closed > 0 ? `${wins}승 ${closed - wins}패` : '표본 없음'}
        />
        <MiniStat
          label="건당 평균"
          value={avgPnl === null || avgPnl === undefined ? '—' : signed(avgPnl)}
          unit="KRW"
          color={avgPnl === null || avgPnl === undefined ? undefined : pnlColor(avgPnl)}
        />
        <MiniStat
          label="평균 보유"
          value={avgHold === null || avgHold === undefined ? '—' : holdLabel(avgHold)}
          unit="청산 기준"
        />
      </div>

      {/* 최고/최저 — 손익이 한두 건에 쏠려 있는지 확인용 */}
      {(!!session['bestCoin'] || !!session['worstCoin']) && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
          {!!session['bestCoin'] && (
            <div className="bg-slate-900/40 rounded-lg p-3 flex items-center justify-between">
              <span className="text-slate-500">최고 수익</span>
              <span className="text-slate-200">
                {s(session['bestCoin']).replace('KRW-', '')}
                <span className={`ml-2 font-bold ${pnlColor(session['bestPnl'])}`}>{signed(session['bestPnl'])} KRW</span>
              </span>
            </div>
          )}
          {!!session['worstCoin'] && (
            <div className="bg-slate-900/40 rounded-lg p-3 flex items-center justify-between">
              <span className="text-slate-500">최대 손실</span>
              <span className="text-slate-200">
                {s(session['worstCoin']).replace('KRW-', '')}
                <span className={`ml-2 font-bold ${pnlColor(session['worstPnl'])}`}>{signed(session['worstPnl'])} KRW</span>
              </span>
            </div>
          )}
        </div>
      )}

      {/* 청산 사유 분포 — "왜 나왔는가"가 성과보다 먼저 읽혀야 한다 */}
      {exitTotal > 0 && (
        <div className="bg-slate-900/40 rounded-lg p-3">
          <div className="text-xs text-slate-500 mb-2">청산 사유 분포</div>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(exitCounts)
              .sort((a, b) => Number(b[1]) - Number(a[1]))
              .map(([reason, count]) => (
                <span
                  key={reason}
                  className={`text-xs px-2 py-1 rounded border ${exitReasonStyle[reason] ?? exitReasonStyle.UNKNOWN}`}
                >
                  {exitReasonLabel[reason] ?? reason} {String(count)}건
                  <span className="opacity-60 ml-1">
                    ({Math.round((Number(count) / exitTotal) * 100)}%)
                  </span>
                </span>
              ))}
          </div>
          {Number(exitCounts['FORCED_STOP'] ?? 0) > 0 && (
            <p className="text-xs text-slate-600 mt-2">
              강제정지 건은 청산가가 시장이 아니라 개입 시각으로 정해집니다 — 전략 성과로 읽지 마세요.
            </p>
          )}
        </div>
      )}

      {closed > 0 && closed < 10 && (
        <p className="text-xs text-slate-600">
          표본 {closed}건 — 승률·수익률은 아직 통계적 의미가 없습니다. 부호와 사유를 보세요.
        </p>
      )}
    </div>
  );
}

function signed(v: unknown): string {
  const num = Number(v ?? 0);
  return (num >= 0 ? '+' : '') + fmt(num);
}

function MiniStat({
  label, value, unit, color = 'text-slate-200',
}: {
  label: string; value: string; unit?: string; color?: string;
}) {
  return (
    <div className="bg-slate-900/40 rounded-lg p-3">
      <div className="text-xs text-slate-500 mb-1">{label}</div>
      <div className={`text-sm font-bold ${color} break-all`}>{value}</div>
      {unit && <div className="text-xs text-slate-600 mt-0.5">{unit}</div>}
    </div>
  );
}

// ── PositionHistoryPanel ──────────────────────────────────────────────────────
//
// 동적 세션은 워치리스트를 돌며 보유 코인이 계속 바뀌므로, 현재 포지션만으로는
// 세션이 무엇을 왜 사고팔았는지 알 수 없다. 매수/매도 사유와 손익을 함께 나열한다.

function PositionHistoryPanel({ history }: { history: Record<string, unknown>[] }) {
  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
      <div className="flex items-center gap-2 mb-4">
        <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider">보유 코인 이력</h2>
        <span className="text-xs px-2 py-0.5 rounded bg-slate-700 text-slate-300">{history.length}건</span>
        <span className="text-xs text-slate-600 ml-auto">최신순 · 매수/매도 사유와 수수료 포함</span>
      </div>

      {history.length === 0 ? (
        <p className="text-xs text-slate-500">아직 진입한 포지션이 없습니다.</p>
      ) : (
        <div className="space-y-2">
          {history.map(h => <HistoryRow key={String(h['id'])} h={h} />)}
        </div>
      )}
    </div>
  );
}

function HistoryRow({ h }: { h: Record<string, unknown> }) {
  const status     = s(h['status']);
  const isClosed   = status === 'CLOSED';
  const pnl        = n(isClosed ? h['realizedPnl'] : h['unrealizedPnl']);
  const returnPct  = h['returnPct'];
  const size       = n(h['size']);
  const entryPrice = n(h['avgPrice'] ?? h['entryPrice']);
  const exitPrice  = n(h['exitPrice']);
  const totalFee   = n(h['totalFee']);
  const grossPnl   = h['grossPnl'];
  const exitReason = s(h['exitReason']);
  const buyReason  = s(h['buyReason']);
  const sellReason = s(h['sellReason']);

  // size=0 인 채로 CLOSED = 주문이 체결되지 않고 정리된 고아 포지션.
  // 손익 0건이라 성과로 읽으면 안 되므로 따로 표시한다.
  const isOrphan = isClosed && size === 0;

  return (
    <div className="bg-slate-900/40 rounded-lg p-3 space-y-2">
      {/* 헤더 줄 */}
      <div className="flex items-center gap-2 flex-wrap text-sm">
        <span className="font-bold text-white">{s(h['coinPair']).replace('KRW-', '')}</span>
        <span className={`text-xs px-2 py-0.5 rounded ${
          isOrphan  ? 'bg-slate-700 text-slate-400'
          : isClosed ? 'bg-slate-700 text-slate-300'
          : 'bg-yellow-500/20 text-yellow-300'
        }`}>
          {isOrphan ? '미체결 정리' : isClosed ? '청산됨' : status === 'CLOSING' ? '청산 중' : '보유 중'}
        </span>
        {!!exitReason && (
          <span className={`text-xs px-2 py-0.5 rounded border ${exitReasonStyle[exitReason] ?? exitReasonStyle.UNKNOWN}`}>
            {exitReasonLabel[exitReason] ?? exitReason}
          </span>
        )}
        {!!h['marketRegime'] && (
          <span className="text-xs px-2 py-0.5 rounded bg-slate-700/60 text-slate-400">
            {regimeLabel[s(h['marketRegime'])] ?? s(h['marketRegime'])}
          </span>
        )}
        {!isOrphan && (
          <span className={`ml-auto font-bold ${pnlColor(pnl)}`}>
            {signed(pnl)} KRW
            {returnPct !== null && returnPct !== undefined && (
              <span className="ml-1.5 text-xs">({fmtPct(returnPct)})</span>
            )}
          </span>
        )}
      </div>

      {/* 가격·수량 줄 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs text-slate-400">
        <div><span className="text-slate-600">진입가</span> {entryPrice > 0 ? fmt(entryPrice) : '—'}</div>
        <div>
          <span className="text-slate-600">청산가</span>{' '}
          {exitPrice > 0 ? fmt(exitPrice) : isClosed ? '기록 없음' : '—'}
        </div>
        <div><span className="text-slate-600">투자금</span> {fmt(h['investedKrw'])} KRW</div>
        <div><span className="text-slate-600">수량</span> {size > 0 ? size.toFixed(8) : '—'}</div>
      </div>

      {/* 손익·수수료 줄 — 실현손익은 순손익이므로 수수료는 '이미 빠진 금액'으로 표기한다 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs text-slate-400">
        <div>
          <span className="text-slate-600">보유</span> {holdLabel(h['holdMinutes'])}
        </div>
        <div>
          <span className="text-slate-600">수수료</span>{' '}
          <span className="text-slate-300">-{fmt(totalFee)}</span>
          <span className="text-slate-600"> (매수 {fmt(h['entryFee'])} + 매도 {fmt(h['exitFee'])})</span>
        </div>
        {grossPnl !== null && grossPnl !== undefined && (
          <div>
            <span className="text-slate-600">수수료 전</span>{' '}
            <span className={pnlColor(grossPnl)}>{signed(grossPnl)}</span>
          </div>
        )}
        {!!h['rulesetHash'] && (
          <div title="이 포지션을 만든 매매 규칙 지문 — 지문이 다르면 다른 규칙의 결과라 합산하면 안 됩니다">
            <span className="text-slate-600">규칙</span>{' '}
            <span className="font-mono text-slate-500">{s(h['rulesetHash'])}</span>
          </div>
        )}
      </div>

      {/* 손절/익절 설정값 — 실제 청산가와 비교해 "얼마나 지나쳐서 체결됐는지" 읽는다 */}
      {(n(h['stopLossPrice']) > 0 || n(h['takeProfitPrice']) > 0) && (
        <div className="grid grid-cols-2 gap-2 text-xs text-slate-500">
          {n(h['stopLossPrice']) > 0 && (
            <div><span className="text-slate-600">손절가</span> {fmt(h['stopLossPrice'])}</div>
          )}
          {n(h['takeProfitPrice']) > 0 && (
            <div><span className="text-slate-600">익절가</span> {fmt(h['takeProfitPrice'])}</div>
          )}
        </div>
      )}

      {/* 사유 줄 */}
      <div className="space-y-1 text-xs border-t border-slate-700/40 pt-2">
        <ReasonLine icon="🟢" label="매수" reason={buyReason} at={h['buyAt'] ?? h['openedAt']} />
        {(isClosed || status === 'CLOSING') && (
          <ReasonLine icon="🔴" label="매도" reason={sellReason} at={h['sellAt'] ?? h['closedAt']} />
        )}
      </div>
    </div>
  );
}

function ReasonLine({
  icon, label, reason, at,
}: {
  icon: string; label: string; reason: string; at: unknown;
}) {
  return (
    <div className="flex gap-2">
      <span className="shrink-0">{icon}</span>
      <span className="text-slate-500 shrink-0 w-8">{label}</span>
      <span className="text-slate-300 break-all flex-1">{reason || '사유 기록 없음'}</span>
      <span className="text-slate-600 shrink-0 hidden md:block">{fmtKst(at)}</span>
    </div>
  );
}

// ── ScanningPanel ─────────────────────────────────────────────────────────────

function ScanningPanel({
  watchlist,
  currentCoinPair,
  watchlistRefreshedAt,
  watchlistRefreshMin,
}: {
  watchlist: string[];
  currentCoinPair: string;
  watchlistRefreshedAt: unknown;
  watchlistRefreshMin: unknown;
}) {
  return (
    <div className="bg-slate-800/50 border border-blue-500/20 rounded-xl p-5 space-y-4">
      <div className="flex items-center gap-2">
        <div className="w-2 h-2 rounded-full bg-blue-400 animate-pulse" />
        <h2 className="text-sm font-semibold text-blue-300">매수 기회 스캔 중</h2>
        <span className="text-xs text-slate-500 ml-auto">감시 {watchlist.length}개 종목</span>
      </div>

      {/* 감시목록 */}
      {watchlist.length > 0 ? (
        <div className="flex flex-wrap gap-1.5">
          {watchlist.map(coin => (
            <span
              key={coin}
              className={`text-xs px-2.5 py-1 rounded-lg font-medium ${
                coin === currentCoinPair
                  ? 'bg-yellow-500/30 text-yellow-200 border border-yellow-500/40'
                  : 'bg-slate-700/80 text-slate-300 border border-slate-600/40'
              }`}
            >
              {coin.replace('KRW-', '')}
            </span>
          ))}
        </div>
      ) : (
        <p className="text-xs text-slate-500">감시목록 구성 중 (다음 틱에 갱신됩니다)</p>
      )}

      {/* 갱신 타이머 */}
      <div className="flex items-center gap-6 text-xs border-t border-slate-700/50 pt-3">
        <div>
          <span className="text-slate-500">다음 갱신</span>
          <span className="ml-2 text-slate-200 font-medium">
            {nextRefreshLabel(watchlistRefreshedAt, watchlistRefreshMin)}
          </span>
        </div>
        {!!watchlistRefreshedAt && (
          <div>
            <span className="text-slate-500">마지막 갱신</span>
            <span className="ml-2 text-slate-400">{fmtKst(watchlistRefreshedAt)}</span>
          </div>
        )}
      </div>

      {/* 진입 조건 */}
      <div className="bg-slate-900/40 rounded-lg p-3 text-xs space-y-1 text-slate-400">
        <div className="text-slate-300 font-medium mb-1.5">진입 조건 (AND)</div>
        <div>• 전략 신호 BUY</div>
        <div>• EMA200 상향 — 가격이 200 이동평균선 위 (상승 추세)</div>
        <div>• 레인지 마켓 제외 — 횡보 구간에서 매수 차단</div>
        <div>• 순차 단일 포지션 — 매수 후 POSITION_MONITORING으로 전환</div>
      </div>
    </div>
  );
}

// ── MonitoringPanel ───────────────────────────────────────────────────────────

function MonitoringPanel({
  pos,
  stopLossPct,
  maxHoldHours,
}: {
  pos: Record<string, unknown>;
  stopLossPct: unknown;
  maxHoldHours: unknown;
}) {
  const entryPrice      = n(pos['entryPrice']);
  const stopLossPrice   = n(pos['stopLossPrice']);
  const takeProfitPrice = n(pos['takeProfitPrice']);
  const unrealizedPnl   = n(pos['unrealizedPnl']);
  const investedKrw     = n(pos['investedKrw']);
  const regime          = s(pos['marketRegime']);

  // 실제 적용된 SL/TP 폭은 진입 시점 ATR로 정해지므로, 세션 설정값이 아니라
  // 포지션에 박힌 가격에서 역산해야 맞다 (설정값은 하한일 뿐).
  const slPct = entryPrice > 0 && stopLossPrice > 0
    ? (1 - stopLossPrice / entryPrice) * 100
    : n(stopLossPct);
  const tpPct = entryPrice > 0 && takeProfitPrice > 0
    ? (takeProfitPrice / entryPrice - 1) * 100
    : slPct * 2;
  const maxHold = n(maxHoldHours);

  return (
    <div className="bg-slate-800/50 border border-yellow-500/20 rounded-xl p-5 space-y-4">
      <div className="flex items-center gap-2">
        <div className="w-2 h-2 rounded-full bg-yellow-400 animate-pulse" />
        <h2 className="text-sm font-semibold text-yellow-300">포지션 감시 중</h2>
        <span className="text-lg font-bold text-white ml-2">
          {s(pos['coinPair']).replace('KRW-', '')}
        </span>
        {regime && (
          <span className="text-xs px-2 py-0.5 rounded bg-slate-700 text-slate-300 ml-auto">
            {regimeLabel[regime] ?? regime} 레짐
          </span>
        )}
      </div>

      {/* 가격 그리드 */}
      <div className="grid grid-cols-3 gap-3">
        <PriceBox label="진입가" value={entryPrice} unit="KRW" />
        <PriceBox label="손절가" value={stopLossPrice} unit="KRW" color="text-red-400" note={`-${slPct.toFixed(2)}%`} />
        <PriceBox label="익절가" value={takeProfitPrice} unit="KRW" color="text-green-400" note={`+${tpPct.toFixed(2)}%`} />
      </div>

      {/* 메타 정보 */}
      <div className="grid grid-cols-3 gap-3 text-xs">
        <div className="bg-slate-900/40 rounded-lg p-3">
          <div className="text-slate-500 mb-1">투자금액</div>
          <div className="text-slate-200 font-medium">{fmt(investedKrw)} KRW</div>
        </div>
        <div className="bg-slate-900/40 rounded-lg p-3">
          <div className="text-slate-500 mb-1">미실현 손익</div>
          <div className={`font-bold ${pnlColor(unrealizedPnl)}`}>
            {unrealizedPnl >= 0 ? '+' : ''}{fmt(unrealizedPnl)} KRW
          </div>
        </div>
        <div className="bg-slate-900/40 rounded-lg p-3">
          <div className="text-slate-500 mb-1">보유 시간</div>
          <div className="text-slate-200 font-medium">{holdDuration(pos['openedAt'])}</div>
        </div>
      </div>

      {/* 청산 조건 */}
      <div className="bg-slate-900/40 rounded-lg p-3 text-xs space-y-1 text-slate-400">
        <div className="text-slate-300 font-medium mb-1.5">청산 조건 (OR)</div>
        <div>• 전략 신호 SELL (최소 보유 180분 + 수익 0.3% 이상)</div>
        <div>• 손절가 도달: {fmt(stopLossPrice)} KRW 이하 (진입가 −{slPct.toFixed(2)}%, ATR 2배 기준)</div>
        {takeProfitPrice > 0 && <div>• 익절가 도달: {fmt(takeProfitPrice)} KRW 이상 (+{tpPct.toFixed(2)}%)</div>}
        {maxHold > 0 && <div>• 보유 {maxHold}시간 초과 시 손익 무관 청산 (time stop)</div>}
      </div>
    </div>
  );
}

function PriceBox({
  label,
  value,
  unit,
  color = 'text-white',
  note,
}: {
  label: string;
  value: number;
  unit: string;
  color?: string;
  note?: string;
}) {
  return (
    <div className="bg-slate-900/40 rounded-lg p-3 text-center">
      <div className="text-xs text-slate-500 mb-1">{label}</div>
      <div className={`text-sm font-bold ${color} break-all`}>
        {value > 0 ? fmt(value) : '—'}
      </div>
      <div className="text-xs text-slate-600">{unit}{note ? ` (${note})` : ''}</div>
    </div>
  );
}

// ── FilterSettingsPanel ───────────────────────────────────────────────────────

function FilterSettingsPanel({ session }: { session: Record<string, unknown> }) {
  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
      <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-4">
        워치리스트 필터
      </h2>

      {/* 후보 → 감시 흐름 */}
      <div className="flex items-center gap-2 mb-4">
        <div className="flex-1 bg-slate-900/60 rounded-lg p-3 text-center">
          <div className="text-xs text-slate-500">거래량 상위 후보</div>
          <div className="text-2xl font-bold text-white">{s(session['maxCandidateSize'])}</div>
          <div className="text-xs text-slate-600">개</div>
        </div>
        <div className="text-slate-600 text-lg">→</div>
        <div className="text-xs text-slate-500 text-center leading-relaxed">
          <div>ATR ≥ {s(session['minAtrPct'])}%</div>
          <div>스프레드 ≤ {s(session['maxSpreadPct'])}%</div>
        </div>
        <div className="text-slate-600 text-lg">→</div>
        <div className="flex-1 bg-blue-900/30 border border-blue-500/20 rounded-lg p-3 text-center">
          <div className="text-xs text-blue-400">최종 감시목록</div>
          <div className="text-2xl font-bold text-blue-300">{s(session['targetWatchSize'])}</div>
          <div className="text-xs text-blue-600">개</div>
        </div>
      </div>

      <div className="space-y-2 text-xs text-slate-400">
        <Row label="최소 ATR(14)%"  value={`${s(session['minAtrPct'])}% 이상`} hint="변동성 부족 종목 제외" />
        <Row label="최대 스프레드%" value={`${s(session['maxSpreadPct'])}% 이하`} hint="유동성 낮은 종목 제외" />
        <Row label="감시목록 갱신"  value={`${s(session['watchlistRefreshMin'])}분마다`} />
      </div>
    </div>
  );
}

// ── SessionSettingsPanel ──────────────────────────────────────────────────────

function SessionSettingsPanel({ session }: { session: Record<string, unknown> }) {
  const investRatioPct = (n(session['investRatio']) * 100).toFixed(0);
  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
      <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-4">
        세션 설정
      </h2>
      <div className="space-y-2 text-xs text-slate-400">
        <Row label="매매 모드"  value={s(session['tradingMode'] || 'REAL') === 'PAPER' ? '모의 (PAPER)' : '실전 (REAL)'} />
        <Row label="전략"       value={s(session['strategyType'])} />
        <Row label="타임프레임" value={s(session['timeframe'])} />
        <Row label="투자 비율"  value={`${investRatioPct}%`} hint="가용 KRW 대비" />
        <Row label="손절률"     value={`${s(session['stopLossPct'])}% 이상`} hint="ATR 2배와 비교해 넓은 쪽" />
        <Row label="최대 보유"  value={n(session['maxHoldHours']) > 0 ? `${s(session['maxHoldHours'])}시간` : '무제한'} hint="초과 시 손익 무관 청산" />
        <Row label="초기 자본"  value={`${fmt(session['initialCapital'])} KRW`} />
      </div>
    </div>
  );
}

function Row({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div className="flex items-center justify-between py-1.5 border-b border-slate-700/30">
      <span className="text-slate-500">{label}</span>
      <span className="text-slate-200 text-right">
        {value}
        {hint && <span className="text-slate-600 ml-1">({hint})</span>}
      </span>
    </div>
  );
}
