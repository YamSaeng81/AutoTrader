import { http, HttpResponse } from 'msw';
import { dynamicSessionsMock, liveSessionsMock } from './data';

// ⚠️ 경로에 반드시 `/api/proxy` 접두사를 붙일 것.
// 클라이언트는 axios baseURL='/api/proxy'(lib/api.ts)를 거쳐 호출한다.
// 접두사가 빠지면 핸들러가 하나도 매칭되지 않아 전 화면이 조용히 빈 상태가 된다.

// MSW 핸들러 — 실서버 연동 완료된 엔드포인트는 제거됨
// POST /api/v1/strategies        → 실서버 직접 연결 (strategyApi.create)
// PUT /api/v1/strategies/:id     → 실서버 직접 연결 (strategyApi.update)
// PATCH /api/v1/strategies/:id/toggle → 실서버 직접 연결 (strategyApi.toggle)

// 삭제 API 핸들러 (개발 환경 모킹용)
export const handlers = [
  // DELETE /api/v1/backtest/:id — 백테스트 단건 삭제
  http.delete('/api/proxy/api/v1/backtest/:id', ({ params }) => {
    const { id } = params;
    if (!id) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'NOT_FOUND', message: '백테스트를 찾을 수 없습니다.' } },
        { status: 404 }
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // DELETE /api/v1/backtest/bulk — 백테스트 다건 삭제
  http.delete('/api/proxy/api/v1/backtest/bulk', async ({ request }) => {
    const body = await request.json() as { ids?: unknown[] };
    if (!body?.ids || body.ids.length === 0) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'BAD_REQUEST', message: '삭제할 ID 목록이 비어 있습니다.' } },
        { status: 400 }
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // DELETE /api/v1/paper-trading/history/:id — 모의투자 세션 단건 삭제
  http.delete('/api/proxy/api/v1/paper-trading/history/:id', ({ params }) => {
    const { id } = params;
    if (!id) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'NOT_FOUND', message: '세션을 찾을 수 없습니다.' } },
        { status: 404 }
      );
    }
    // RUNNING 세션 삭제 시도 시뮬레이션: id가 999이면 400 반환
    if (id === '999') {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'BAD_REQUEST', message: '실행 중인 세션은 삭제할 수 없습니다.' } },
        { status: 400 }
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // DELETE /api/v1/paper-trading/history/bulk — 모의투자 세션 다건 삭제 (RUNNING 자동 제외)
  http.delete('/api/proxy/api/v1/paper-trading/history/bulk', async ({ request }) => {
    const body = await request.json() as { ids?: unknown[] };
    if (!body?.ids || body.ids.length === 0) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'BAD_REQUEST', message: '삭제할 ID 목록이 비어 있습니다.' } },
        { status: 400 }
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // DELETE /api/v1/data/candles — 캔들 데이터 삭제
  http.delete('/api/proxy/api/v1/data/candles', ({ request }) => {
    const url = new URL(request.url);
    const coinPair = url.searchParams.get('coinPair') ?? '';
    const timeframe = url.searchParams.get('timeframe') ?? '';
    if (!coinPair) {
      return HttpResponse.json(
        { success: false, data: null, error: { code: 'BAD_REQUEST', message: 'coinPair가 필요합니다.' } },
        { status: 400 }
      );
    }
    return HttpResponse.json({
      success: true,
      data: { coinPair, timeframe: timeframe || 'ALL', deletedCount: 1000 },
      error: null,
    });
  }),

  // ─── Phase 4: Trading API Mocks (다중 세션) ─────────────────────────────────

  // 세션 목록
  http.get('/api/proxy/api/v1/trading/sessions', () => {
    return HttpResponse.json({ success: true, data: liveSessionsMock, error: null });
  }),

  // 동적 멀티코인 세션 목록
  http.get('/api/proxy/api/v1/dynamic-sessions', () => {
    return HttpResponse.json({ success: true, data: dynamicSessionsMock, error: null });
  }),
  http.get('/api/proxy/api/v1/dynamic-sessions/:id', ({ params }) => {
    const found = dynamicSessionsMock.find(s => String(s.id) === String(params.id));
    return HttpResponse.json({ success: true, data: found ?? dynamicSessionsMock[0], error: null });
  }),
  // 세션 생성
  http.post('/api/proxy/api/v1/trading/sessions', async ({ request }) => {
    const body = await request.json() as Record<string, unknown>;
    return HttpResponse.json({
      success: true, data: {
        id: Date.now(), strategyType: body.strategyType, coinPair: body.coinPair,
        timeframe: body.timeframe, initialCapital: body.initialCapital,
        availableKrw: body.initialCapital, totalAssetKrw: body.initialCapital,
        status: 'CREATED', stopLossPct: body.stopLossPct ?? 5,
        strategyParams: body.strategyParams ?? null,
        createdAt: new Date().toISOString(), startedAt: null, stoppedAt: null,
        updatedAt: new Date().toISOString(),
      }, error: null,
    });
  }),
  // 세션 상세
  http.get('/api/proxy/api/v1/trading/sessions/:id', ({ params }) => {
    return HttpResponse.json({
      success: true, data: {
        id: Number(params.id), strategyType: 'VWAP', coinPair: 'KRW-BTC',
        timeframe: 'M5', initialCapital: 1000000, availableKrw: 1000000,
        totalAssetKrw: 1000000, status: 'CREATED', stopLossPct: 5,
        strategyParams: null, createdAt: new Date().toISOString(),
        startedAt: null, stoppedAt: null, updatedAt: new Date().toISOString(),
      }, error: null,
    });
  }),
  // 세션 시작
  http.post('/api/proxy/api/v1/trading/sessions/:id/start', ({ params }) => {
    return HttpResponse.json({
      success: true, data: { id: Number(params.id), status: 'RUNNING', startedAt: new Date().toISOString() }, error: null,
    });
  }),
  // 세션 정지
  http.post('/api/proxy/api/v1/trading/sessions/:id/stop', ({ params }) => {
    return HttpResponse.json({
      success: true, data: { id: Number(params.id), status: 'STOPPED', stoppedAt: new Date().toISOString() }, error: null,
    });
  }),
  // 세션 비상 정지
  http.post('/api/proxy/api/v1/trading/sessions/:id/emergency-stop', ({ params }) => {
    return HttpResponse.json({
      success: true, data: { id: Number(params.id), status: 'EMERGENCY_STOPPED', stoppedAt: new Date().toISOString() }, error: null,
    });
  }),
  // 세션 삭제
  http.delete('/api/proxy/api/v1/trading/sessions/:id', () => {
    return HttpResponse.json({ success: true, data: null, error: null });
  }),
  // 세션 포지션
  http.get('/api/proxy/api/v1/trading/sessions/:id/positions', () => {
    return HttpResponse.json({ success: true, data: [], error: null });
  }),
  // 세션 주문
  http.get('/api/proxy/api/v1/trading/sessions/:id/orders', () => {
    return HttpResponse.json({
      success: true, data: { content: [], totalElements: 0, totalPages: 0, number: 0 }, error: null,
    });
  }),

  // 전체 상태
  http.get('/api/proxy/api/v1/trading/status', () => {
    return HttpResponse.json({
      success: true, data: {
        status: 'STOPPED', openPositions: 0, activeOrders: 0,
        totalPnl: 0, startedAt: null, exchangeHealth: 'UP',
        runningSessions: 0, totalSessions: 0,
      }, error: null,
    });
  }),
  // 전체 비상 정지
  http.post('/api/proxy/api/v1/trading/emergency-stop', () => {
    return HttpResponse.json({
      success: true, data: {
        status: 'EMERGENCY_STOPPED', openPositions: 0, activeOrders: 0,
        totalPnl: 0, startedAt: null, exchangeHealth: 'UP',
        runningSessions: 0, totalSessions: 0,
      }, error: null,
    });
  }),
  // 전체 포지션/주문
  http.get('/api/proxy/api/v1/trading/positions', () => {
    return HttpResponse.json({ success: true, data: [], error: null });
  }),
  http.get('/api/proxy/api/v1/trading/orders', () => {
    return HttpResponse.json({
      success: true, data: { content: [], totalElements: 0, totalPages: 0, number: 0 }, error: null,
    });
  }),
  http.delete('/api/proxy/api/v1/trading/orders/:id', () => {
    return HttpResponse.json({ success: true, data: null, error: null });
  }),
  // 리스크 설정
  http.get('/api/proxy/api/v1/trading/risk/config', () => {
    return HttpResponse.json({
      success: true, data: {
        id: 1, maxDailyLossPct: 3.0, maxWeeklyLossPct: 7.0,
        maxMonthlyLossPct: 15.0, maxPositions: 3, cooldownMinutes: 60, portfolioLimitKrw: 0,
      }, error: null,
    });
  }),
  http.put('/api/proxy/api/v1/trading/risk/config', async ({ request }) => {
    const body = await request.json();
    return HttpResponse.json({ success: true, data: { id: 1, ...body as object }, error: null });
  }),
  // 거래소 상태
  http.get('/api/proxy/api/v1/trading/health/exchange', () => {
    return HttpResponse.json({
      success: true, data: {
        status: 'UP', latencyMs: 45, webSocketConnected: false,
        lastCheckedAt: new Date().toISOString(), recentLatencies: [42, 38, 45, 50, 41],
      }, error: null,
    });
  }),
  // 운영 건전성 점검 이력 (2026-08-06 신규)
  http.get('/api/proxy/api/v1/admin/health-check/history', () => {
    const now = Date.now();
    return HttpResponse.json({
      success: true,
      data: [
        {
          id: 3, checkedAt: new Date(now).toISOString(),
          balanceMismatchCount: 0, balanceMismatchDetail: [],
          orderSequenceGap: 0, sequenceGapChecked: true,
          ghostPositionCount: 0, ghostPositionDetail: [],
          stuckPositionCount: 1,
          stuckPositionDetail: [{ sessionKind: 'LIVE', positionId: 2378, sessionId: 194, coinPair: 'KRW-BTC', heldHours: 136 }],
        },
        {
          id: 2, checkedAt: new Date(now - 86400000).toISOString(),
          balanceMismatchCount: 0, balanceMismatchDetail: [],
          orderSequenceGap: 0, sequenceGapChecked: true,
          ghostPositionCount: 0, ghostPositionDetail: [],
          stuckPositionCount: 0, stuckPositionDetail: [],
        },
      ],
      error: null,
    });
  }),
  http.post('/api/proxy/api/v1/admin/health-check/trigger', () => {
    return HttpResponse.json({ success: true, data: { triggered: true }, error: null });
  }),
];
