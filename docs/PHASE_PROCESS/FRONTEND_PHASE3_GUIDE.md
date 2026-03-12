# Frontend Phase 3 & 3.5 개발 가이드 (Gemini CLI용)

## 문서 정보
- 작성일: 2026-03-06
- 대상: Do-Frontend 에이전트 (Gemini CLI)
- 전제: Phase 2 (백테스팅 대시보드) 완료 상태

---

## 0. 현재 구현 상태 및 주의사항

### 백엔드 준비 현황

| 기능 | 백엔드 상태 | 프론트 개발 방식 |
|------|-----------|----------------|
| 전략 목록/상세 조회 (`GET`) | ✅ 완료 | 실서버 or MSW |
| 전략 설정 생성/수정 (`POST/PUT/PATCH`) | ❌ 미구현 | MSW로만 개발 |
| Paper Trading API (잔고/포지션/주문) | ❌ 미구현 | MSW로만 개발 |

> **중요**: Phase 3.5 Paper Trading 화면은 백엔드 API가 아직 없습니다.
> MSW 핸들러로 UI를 완성해 두면, 백엔드 완성 후 MSW만 비활성화하면 됩니다.
> 백엔드 API가 확정되면 이 문서의 스펙이 업데이트됩니다.

---

## 1. Phase 3: 전략 관리 화면

### 1.1 구현할 화면

**`/strategies`** — 전략 목록 + 설정 관리

- 9개 전략 카드 목록 (VWAP, EMA_CROSS, BOLLINGER, GRID + 5개 스켈레톤)
- `AVAILABLE` / `SKELETON` 상태 배지
- 전략별 파라미터 설정 폼 (동적 렌더링)
- 전략 설정 저장/수정 (백엔드 구현 전까지 MSW)

### 1.2 현재 사용 가능한 API (백엔드 완료)

```
GET /api/v1/strategies         → 전략 목록 + 상태
GET /api/v1/strategies/{name}  → 전략 단건 상세
```

### 1.3 응답 스펙

```typescript
// GET /api/v1/strategies 응답
interface StrategyInfo {
  name: string;         // "EMA_CROSS"
  minimumCandleCount: number;
  status: 'AVAILABLE' | 'SKELETON';
  description: string;
}
```

### 1.4 미구현 백엔드 API (MSW로 처리)

DESIGN.md 기준 전략 설정 관리 API (백엔드 구현 예정):

```
POST   /api/v1/strategies          → 전략 설정 저장
PUT    /api/v1/strategies/{id}     → 전략 설정 수정
PATCH  /api/v1/strategies/{id}/toggle  → 전략 활성/비활성 토글
```

#### MSW 핸들러 예시

```typescript
// src/mocks/handlers.ts 에 추가
import { strategyConfigsMock } from './data';

// 전략 설정 목록 (저장된 설정들)
http.get('/api/v1/strategies', () =>
  HttpResponse.json({ success: true, data: strategyInfosMock, error: null })),

// 전략 설정 저장 (MSW - 백엔드 미구현)
http.post('/api/v1/strategies', async ({ request }) => {
  const body = await request.json();
  return HttpResponse.json({
    success: true,
    data: { id: 'mock-id-001', ...body as object, createdAt: new Date().toISOString() },
    error: null,
  });
}),

// 전략 활성/비활성 토글 (MSW - 백엔드 미구현)
http.patch('/api/v1/strategies/:id/toggle', ({ params }) =>
  HttpResponse.json({
    success: true,
    data: { id: params.id, enabled: true },
    error: null,
  })),
```

#### Mock 데이터

```typescript
// src/mocks/data.ts 에 추가
export const strategyInfosMock = [
  { name: 'VWAP',       minimumCandleCount: 20, status: 'AVAILABLE', description: '거래량 가중 평균 가격 기반 역추세 매매' },
  { name: 'EMA_CROSS',  minimumCandleCount: 21, status: 'AVAILABLE', description: '단기/장기 EMA 골든·데드크로스 추세 추종' },
  { name: 'BOLLINGER',  minimumCandleCount: 20, status: 'AVAILABLE', description: '볼린저 밴드 %B 기반 평균 회귀 매매' },
  { name: 'GRID',       minimumCandleCount: 1,  status: 'AVAILABLE', description: '가격 그리드 레벨 근접 시 매매' },
  { name: 'RSI',        minimumCandleCount: 15, status: 'SKELETON',  description: 'RSI 과매수/과매도 기반 역추세 매매 (구현 예정)' },
  { name: 'MACD',       minimumCandleCount: 35, status: 'SKELETON',  description: 'MACD/Signal 크로스 기반 추세 추종 (구현 예정)' },
  { name: 'SUPERTREND', minimumCandleCount: 11, status: 'SKELETON',  description: 'ATR 기반 동적 지지/저항 추세 추종 (구현 예정)' },
  { name: 'ATR_BREAKOUT', minimumCandleCount: 15, status: 'SKELETON', description: 'ATR 변동성 돌파 모멘텀 매매 (구현 예정)' },
  { name: 'ORDERBOOK_IMBALANCE', minimumCandleCount: 5, status: 'SKELETON', description: '호가 불균형 기반 단기 방향성 매매 (WebSocket 연동 후 구현 예정)' },
];

// 전략 파라미터 기본값 (BacktestForm 동적 렌더링에도 사용)
export const strategyParamsMock: Record<string, StrategyParam[]> = {
  VWAP: [
    { name: 'thresholdPct', label: 'VWAP 이탈 임계값 (%)', type: 'number', default: 1.0, min: 0.1, max: 5.0 },
    { name: 'period',       label: '계산 기간',           type: 'integer', default: 20,  min: 5,   max: 100 },
  ],
  EMA_CROSS: [
    { name: 'fastPeriod', label: '단기 EMA 기간', type: 'integer', default: 9,  min: 3,  max: 50 },
    { name: 'slowPeriod', label: '장기 EMA 기간', type: 'integer', default: 21, min: 10, max: 200 },
  ],
  BOLLINGER: [
    { name: 'period',          label: '볼린저 기간',    type: 'integer', default: 20,  min: 5,   max: 100 },
    { name: 'stdDevMultiplier', label: '표준편차 배수', type: 'number',  default: 2.0, min: 0.5, max: 4.0 },
  ],
  GRID: [
    { name: 'gridCount', label: '그리드 분할 수', type: 'integer', default: 10,  min: 3,   max: 50 },
    { name: 'gridRange', label: '그리드 범위',   type: 'number',  default: 0.1, min: 0.02, max: 0.5 },
  ],
  RSI: [
    { name: 'period',          label: 'RSI 기간',       type: 'integer', default: 14, min: 2,  max: 100 },
    { name: 'oversoldLevel',   label: '과매도 기준',    type: 'number',  default: 30, min: 10, max: 45 },
    { name: 'overboughtLevel', label: '과매수 기준',    type: 'number',  default: 70, min: 55, max: 90 },
  ],
  MACD: [
    { name: 'fastPeriod',   label: '단기 EMA',   type: 'integer', default: 12, min: 3,  max: 50 },
    { name: 'slowPeriod',   label: '장기 EMA',   type: 'integer', default: 26, min: 10, max: 100 },
    { name: 'signalPeriod', label: '시그널 EMA', type: 'integer', default: 9,  min: 3,  max: 50 },
  ],
  SUPERTREND: [
    { name: 'atrPeriod',  label: 'ATR 기간', type: 'integer', default: 10, min: 3,  max: 50 },
    { name: 'multiplier', label: 'ATR 배수', type: 'number',  default: 3.0, min: 0.5, max: 10.0 },
  ],
  ATR_BREAKOUT: [
    { name: 'atrPeriod',  label: 'ATR 기간',       type: 'integer', default: 14,  min: 3,  max: 50 },
    { name: 'multiplier', label: '돌파 임계값 배수', type: 'number',  default: 1.5, min: 0.5, max: 5.0 },
  ],
  ORDERBOOK_IMBALANCE: [
    { name: 'imbalanceThreshold', label: '불균형 임계값', type: 'number',  default: 0.65, min: 0.5, max: 0.9 },
    { name: 'lookback',           label: '참조 캔들 수', type: 'integer', default: 5,    min: 1,   max: 20 },
  ],
};

interface StrategyParam {
  name: string;
  label: string;
  type: 'number' | 'integer';
  default: number;
  min: number;
  max: number;
}
```

### 1.5 파라미터 폼 동적 렌더링 예시

```typescript
// components/features/strategy/StrategyConfigForm.tsx
function StrategyConfigForm({ strategyName }: { strategyName: string }) {
  const params = strategyParamsMock[strategyName] ?? [];

  return (
    <form>
      {params.map(param => (
        <div key={param.name}>
          <label>{param.label}</label>
          <input
            type="number"
            defaultValue={param.default}
            min={param.min}
            max={param.max}
            step={param.type === 'integer' ? 1 : 0.1}
            name={param.name}
          />
        </div>
      ))}
    </form>
  );
}
```

---

## 2. Phase 3.5: Paper Trading (모의투자) 화면

> **⚠️ 백엔드 미구현 — MSW 전용으로 개발**
> Paper Trading 백엔드 API는 Phase 3.5 백엔드 작업 완료 후 연결

### 2.1 구현할 화면

**`/paper-trading`** — 모의투자 현황 대시보드

- 가상 잔고 카드 (총 자산, 가용 KRW, 평가손익)
- 보유 포지션 테이블
- 최근 모의 주문/체결 내역
- 실제 가격과의 괴리율 표시

### 2.2 예상 백엔드 API (스펙 확정 후 업데이트 예정)

```
GET  /api/v1/paper-trading/balance      → 가상 잔고
GET  /api/v1/paper-trading/positions    → 보유 포지션
GET  /api/v1/paper-trading/orders       → 주문 내역
POST /api/v1/paper-trading/start        → 모의투자 시작
POST /api/v1/paper-trading/stop         → 모의투자 중단
POST /api/v1/paper-trading/reset        → 가상 잔고 초기화
```

### 2.3 MSW 핸들러 (전체 MSW로 구현)

```typescript
// src/mocks/handlers.ts 에 추가
import { paperTradingMock } from './data';

http.get('/api/v1/paper-trading/balance', () =>
  HttpResponse.json({ success: true, data: paperTradingMock.balance, error: null })),

http.get('/api/v1/paper-trading/positions', () =>
  HttpResponse.json({ success: true, data: paperTradingMock.positions, error: null })),

http.get('/api/v1/paper-trading/orders', () =>
  HttpResponse.json({ success: true, data: paperTradingMock.orders, error: null })),

http.post('/api/v1/paper-trading/start', () =>
  HttpResponse.json({ success: true, data: { status: 'RUNNING', startedAt: new Date().toISOString() }, error: null })),

http.post('/api/v1/paper-trading/stop', () =>
  HttpResponse.json({ success: true, data: { status: 'STOPPED', stoppedAt: new Date().toISOString() }, error: null })),

http.post('/api/v1/paper-trading/reset', () =>
  HttpResponse.json({ success: true, data: { message: '가상 잔고가 초기화되었습니다', balance: paperTradingMock.balance }, error: null })),
```

### 2.4 Mock 데이터

```typescript
// src/mocks/data.ts 에 추가
export const paperTradingMock = {
  balance: {
    totalAssetKrw: 11_250_000,    // 총 평가 자산 (원)
    availableKrw: 5_430_000,      // 가용 KRW
    positionValueKrw: 5_820_000,  // 보유 포지션 평가금액
    unrealizedPnl: 1_250_000,     // 미실현 손익
    unrealizedPnlPct: 12.5,       // 미실현 수익률 (%)
    initialCapital: 10_000_000,   // 초기 자본
    updatedAt: '2024-03-06T09:00:00Z',
  },
  positions: [
    {
      id: 'pos-001',
      coinPair: 'KRW-BTC',
      side: 'LONG',
      quantity: 0.05,
      avgEntryPrice: 82_000_000,
      currentPrice: 85_400_000,
      positionValueKrw: 4_270_000,
      unrealizedPnl: 170_000,
      unrealizedPnlPct: 4.15,
      strategyType: 'EMA_CROSS',
      enteredAt: '2024-03-04T14:00:00Z',
    },
    {
      id: 'pos-002',
      coinPair: 'KRW-ETH',
      side: 'LONG',
      quantity: 0.5,
      avgEntryPrice: 3_080_000,
      currentPrice: 3_100_000,
      positionValueKrw: 1_550_000,
      unrealizedPnl: 10_000,
      unrealizedPnlPct: 0.65,
      strategyType: 'BOLLINGER',
      enteredAt: '2024-03-05T10:30:00Z',
    },
  ],
  orders: {
    content: [
      {
        id: 'ord-001',
        coinPair: 'KRW-BTC',
        side: 'BUY',
        price: 82_000_000,
        quantity: 0.05,
        status: 'FILLED',
        strategyType: 'EMA_CROSS',
        createdAt: '2024-03-04T14:00:00Z',
        filledAt: '2024-03-04T14:00:01Z',
      },
    ],
    totalElements: 12,
    totalPages: 1,
    number: 0,
  },
};
```

### 2.5 Paper Trading 화면 구성 요소

```typescript
// /paper-trading 페이지 레이아웃
/*
┌─────────────────────────────────────────────┐
│  모의투자 현황                [시작] [중단] [초기화] │
├─────────┬─────────┬─────────┬───────────────┤
│ 총 자산  │ 가용 KRW │ 미실현손익│ 수익률         │
│ 1125만  │ 543만   │ +125만  │ +12.5%        │
├─────────────────────────────────────────────┤
│  보유 포지션                                  │
│  코인  │ 수량 │ 평균단가 │ 현재가 │ 수익률      │
│  BTC   │ 0.05 │ 8200만  │ 8540만 │ +4.15%     │
│  ETH   │ 0.5  │ 308만   │ 310만  │ +0.65%     │
├─────────────────────────────────────────────┤
│  최근 주문/체결 내역                            │
└─────────────────────────────────────────────┘
*/
```

---

## 3. 사이드바 메뉴 구조 (전체 로드맵 반영)

Phase 2에서 만든 사이드바에 아래 메뉴를 추가해야 합니다:

```typescript
// components/layout/Sidebar.tsx
const menuItems = [
  // Phase 2 (완료)
  { path: '/',                  label: '대시보드',    icon: 'LayoutDashboard', phase: 2 },
  { path: '/backtest',          label: '백테스팅',    icon: 'BarChart2',       phase: 2 },
  { path: '/backtest/compare',  label: '전략 비교',   icon: 'GitCompare',      phase: 2 },
  { path: '/logs',              label: '로그',        icon: 'FileText',        phase: 2 },
  // Phase 3 (이번 작업)
  { path: '/strategies',        label: '전략 관리',   icon: 'Settings',        phase: 3 },
  // Phase 3.5 (이번 작업 - MSW)
  { path: '/paper-trading',     label: '모의투자',    icon: 'TrendingUp',      phase: 3.5 },
  // Phase 4 (향후 - disabled or hidden)
  { path: '/trading',           label: '실전 매매',   icon: 'Zap',             phase: 4, disabled: true },
  { path: '/positions',         label: '포지션',      icon: 'Briefcase',       phase: 4, disabled: true },
  { path: '/orders',            label: '주문 내역',   icon: 'List',            phase: 4, disabled: true },
  { path: '/risk',              label: '리스크 설정', icon: 'Shield',          phase: 4, disabled: true },
];
```

> Phase 4 메뉴는 `disabled: true`로 잠금 표시하거나 숨김 처리 권장

---

## 4. 타입 추가 (lib/types.ts)

```typescript
// Phase 3 추가 타입
export type StrategyStatus = 'AVAILABLE' | 'SKELETON';

export interface StrategyInfo {
  name: string;
  minimumCandleCount: number;
  status: StrategyStatus;
  description: string;
}

// Phase 3.5 추가 타입
export interface PaperTradingBalance {
  totalAssetKrw: number;
  availableKrw: number;
  positionValueKrw: number;
  unrealizedPnl: number;
  unrealizedPnlPct: number;
  initialCapital: number;
  updatedAt: string;
}

export interface PaperPosition {
  id: string;
  coinPair: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  avgEntryPrice: number;
  currentPrice: number;
  positionValueKrw: number;
  unrealizedPnl: number;
  unrealizedPnlPct: number;
  strategyType: StrategyType;
  enteredAt: string;
}
```

---

## 5. API 클라이언트 추가 (lib/api.ts)

```typescript
// lib/api.ts 에 추가
export const strategyApi = {
  list: () =>
    api.get<ApiResponse<StrategyInfo[]>>('/api/v1/strategies').then(r => r.data),
  get: (name: string) =>
    api.get<ApiResponse<StrategyInfo>>(`/api/v1/strategies/${name}`).then(r => r.data),
  // 아래는 백엔드 구현 후 활성화
  // create: (config: StrategyConfigPayload) => ...
  // update: (id: string, config: StrategyConfigPayload) => ...
  // toggle: (id: string) => ...
};

export const paperTradingApi = {
  balance: () =>
    api.get<ApiResponse<PaperTradingBalance>>('/api/v1/paper-trading/balance').then(r => r.data),
  positions: () =>
    api.get<ApiResponse<PaperPosition[]>>('/api/v1/paper-trading/positions').then(r => r.data),
  orders: (page = 0) =>
    api.get<ApiResponse<PageResponse<unknown>>>('/api/v1/paper-trading/orders', { params: { page } }).then(r => r.data),
  start: () =>
    api.post<ApiResponse<unknown>>('/api/v1/paper-trading/start').then(r => r.data),
  stop: () =>
    api.post<ApiResponse<unknown>>('/api/v1/paper-trading/stop').then(r => r.data),
  reset: () =>
    api.post<ApiResponse<unknown>>('/api/v1/paper-trading/reset').then(r => r.data),
};
```

---

## 6. 작업 순서 권장

```
1. lib/types.ts 타입 추가
2. src/mocks/data.ts 모의 데이터 추가
3. src/mocks/handlers.ts 핸들러 추가
4. lib/api.ts 클라이언트 추가
5. Sidebar 메뉴 업데이트 (Phase 4 비활성화 포함)
6. /strategies 페이지 + StrategyConfigForm 구현
7. /paper-trading 페이지 구현
```

---

## 7. 백엔드 연결 전환 체크리스트

Phase 3.5 백엔드 완성 후:
- [ ] `/api/v1/paper-trading/*` MSW 핸들러 제거
- [ ] `/api/v1/strategies` POST/PUT/PATCH MSW 핸들러 제거
- [ ] `paperTradingApi` 타입 파라미터 정확한 타입으로 교체 (`unknown` → 실제 타입)

---

작성: Do-Backend 에이전트 (Claude Code)
대상: Do-Frontend 에이전트 (Gemini CLI)
전체 스펙: `docs/api-spec.yaml`
