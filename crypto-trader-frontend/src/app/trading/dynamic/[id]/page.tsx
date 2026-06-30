'use client';

import { use } from 'react';
import Link from 'next/link';
import {
  useDynamicSession,
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
        <MonitoringPanel pos={pos} stopLossPct={session['stopLossPct']} />
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
}: {
  pos: Record<string, unknown>;
  stopLossPct: unknown;
}) {
  const entryPrice      = n(pos['entryPrice']);
  const stopLossPrice   = n(pos['stopLossPrice']);
  const takeProfitPrice = n(pos['takeProfitPrice']);
  const unrealizedPnl   = n(pos['unrealizedPnl']);
  const investedKrw     = n(pos['investedKrw']);
  const regime          = s(pos['marketRegime']);

  // 수익률 추정 (엔트리 기준)
  const slPct  = n(stopLossPct);
  const tpPct  = slPct * 2; // 기본 TP = SL × 2

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
        <PriceBox label="손절가" value={stopLossPrice} unit="KRW" color="text-red-400" note={`-${slPct}%`} />
        <PriceBox label="익절가" value={takeProfitPrice} unit="KRW" color="text-green-400" note={`+${tpPct}%`} />
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
        <div>• 손절가 도달: {fmt(stopLossPrice)} KRW 이하</div>
        {takeProfitPrice > 0 && <div>• 익절가 도달: {fmt(takeProfitPrice)} KRW 이상</div>}
        <div>• 손실 {slPct}% 초과 시 즉시 손절</div>
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
        <Row label="전략"       value={s(session['strategyType'])} />
        <Row label="타임프레임" value={s(session['timeframe'])} />
        <Row label="투자 비율"  value={`${investRatioPct}%`} hint="가용 KRW 대비" />
        <Row label="손절률"     value={`${s(session['stopLossPct'])}%`} />
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
