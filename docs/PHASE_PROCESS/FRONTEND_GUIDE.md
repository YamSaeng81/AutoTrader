# Frontend 개발 가이드 (Gemini CLI용)

## 문서 정보
- 작성일: 2026-03-06
- 대상: Do-Frontend 에이전트 (Gemini CLI)
- 역할: Phase 2 백테스팅 대시보드 프론트엔드 구현

---

## 1. 프로젝트 컨텍스트

업비트 기반 암호화폐 자동매매 시스템의 백테스팅 대시보드입니다.
백엔드(Spring Boot)는 Phase 1에서 완료되었으며, 프론트엔드는 독립적으로 개발합니다.

**기술 스택 (고정)**
- **Framework**: Next.js 14 (App Router)
- **Language**: TypeScript
- **Styling**: Tailwind CSS
- **차트**: Recharts
- **HTTP Client**: axios
- **상태관리**: TanStack Query (React Query v5)
- **Mock**: MSW (Mock Service Worker) v2

---

## 2. API 스펙 파일

**정적 OpenAPI 스펙**: `docs/api-spec.yaml`

백엔드 서버 없이 이 파일을 기준으로 개발합니다.
스펙 파일에는 모든 엔드포인트, 요청/응답 스키마, 실제 예시 데이터가 포함되어 있습니다.

---

## 3. 개발 전략: MSW Mock → 실서버 연결

### 단계별 개발 흐름

```
1단계: MSW 핸들러 작성 (api-spec.yaml 기반)
       ↓
2단계: MSW로 UI 완성 (백엔드 불필요)
       ↓
3단계: 백엔드 실행 후 MSW 비활성화 → 실서버 연결
```

### MSW 설치 및 설정

```bash
npx create-next-app@latest crypto-trader-frontend \
  --typescript --tailwind --app --no-src-dir
cd crypto-trader-frontend
npm install axios @tanstack/react-query msw@2 recharts
npx msw init public/ --save
```

### MSW 핸들러 구조

```typescript
// src/mocks/handlers.ts
import { http, HttpResponse } from 'msw';
import { backtestListMock, backtestResultMock, tradesMock, strategyTypesMock } from './data';

export const handlers = [
  // 백테스트 실행
  http.post('/api/v1/backtest/run', async ({ request }) => {
    const body = await request.json() as Record<string, unknown>;
    return HttpResponse.json({
      success: true,
      data: { ...backtestResultMock, strategyType: body.strategyType, coinPair: body.coinPair },
      error: null,
    });
  }),

  // 결과 조회
  http.get('/api/v1/backtest/:id', () =>
    HttpResponse.json({ success: true, data: backtestResultMock, error: null })),

  // 매매 기록
  http.get('/api/v1/backtest/:id/trades', () =>
    HttpResponse.json({ success: true, data: tradesMock, error: null })),

  // 목록
  http.get('/api/v1/backtest/list', () =>
    HttpResponse.json({ success: true, data: backtestListMock, error: null })),

  // 비교
  http.get('/api/v1/backtest/compare', () =>
    HttpResponse.json({ success: true, data: [backtestResultMock], error: null })),

  // 전략 타입
  http.get('/api/v1/strategies/types', () =>
    HttpResponse.json({ success: true, data: strategyTypesMock, error: null })),

  // 코인 목록
  http.get('/api/v1/data/coins', () =>
    HttpResponse.json({ success: true, data: ['KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-DOGE'], error: null })),
];
```

```typescript
// src/mocks/browser.ts
import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);
```

```typescript
// src/mocks/data.ts  — api-spec.yaml의 example 값 그대로 사용
export const backtestResultMock = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  strategyType: 'EMA_CROSS',
  coinPair: 'KRW-BTC',
  timeframe: 'H1',
  startDate: '2024-01-01T00:00:00Z',
  endDate: '2024-12-31T23:59:59Z',
  initialCapital: 10000000,
  status: 'COMPLETED',
  metrics: {
    totalReturn: 23.5,
    winRate: 58.3,
    maxDrawdown: -12.4,
    sharpeRatio: 1.85,
    sortinoRatio: 2.1,
    calmarRatio: 1.9,
    winLossRatio: 1.4,
    recoveryFactor: 1.9,
    totalTrades: 48,
    maxConsecutiveLoss: 3,
    monthlyReturns: {
      '2024-01': 3.2, '2024-02': -1.1, '2024-03': 5.8,
      '2024-04': 2.1, '2024-05': -0.8, '2024-06': 4.3,
      '2024-07': 1.9, '2024-08': -2.3, '2024-09': 3.5,
      '2024-10': 0.7, '2024-11': 4.1, '2024-12': 2.0,
    },
  },
  createdAt: '2024-03-01T12:00:00Z',
};

export const tradesMock = {
  content: [
    {
      side: 'BUY', price: 55000000, quantity: 0.1,
      fee: 2750, slippage: 275, pnl: 0, cumulativePnl: 0,
      signalReason: 'EMA 골든크로스 발생 (fast=9, slow=21)',
      marketRegime: 'TREND', executedAt: '2024-01-15T09:00:00Z',
    },
    {
      side: 'SELL', price: 58500000, quantity: 0.1,
      fee: 2925, slippage: 292, pnl: 347033, cumulativePnl: 347033,
      signalReason: 'EMA 데드크로스 발생',
      marketRegime: 'TREND', executedAt: '2024-01-22T14:00:00Z',
    },
  ],
  totalElements: 48,
  totalPages: 1,
  number: 0,
};

export const backtestListMock = {
  content: [backtestResultMock],
  totalElements: 1,
  totalPages: 1,
  number: 0,
};

export const strategyTypesMock = [
  {
    type: 'EMA_CROSS', name: 'EMA 크로스 전략',
    description: '단기/장기 EMA 골든·데드크로스 추세 추종',
    params: [
      { name: 'fastPeriod', type: 'integer', default: 9, description: '단기 EMA 기간' },
      { name: 'slowPeriod', type: 'integer', default: 21, description: '장기 EMA 기간' },
    ],
  },
  {
    type: 'VWAP', name: 'VWAP 역추세 전략',
    description: '거래량 가중 평균 가격 기반 역추세 매매',
    params: [
      { name: 'thresholdPercent', type: 'number', default: 0.5, description: 'VWAP 이탈 임계값 (%)' },
    ],
  },
  {
    type: 'BOLLINGER', name: '볼린저 밴드 전략',
    description: '볼린저 밴드 %B 기반 평균 회귀 매매',
    params: [
      { name: 'period', type: 'integer', default: 20, description: '볼린저 밴드 기간' },
      { name: 'stdDevMultiplier', type: 'number', default: 2.0, description: '표준편차 배수' },
    ],
  },
  {
    type: 'GRID', name: '그리드 트레이딩 전략',
    description: '가격 그리드 레벨 근접 시 매매',
    params: [
      { name: 'gridCount', type: 'integer', default: 10, description: '그리드 분할 수' },
      { name: 'gridRange', type: 'number', default: 0.1, description: '그리드 범위 (0.1 = ±10%)' },
    ],
  },
];
```

### MSW 활성화 (layout.tsx)

```typescript
// src/app/layout.tsx
'use client';
import { useEffect } from 'react';

export default function RootLayout({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    if (process.env.NODE_ENV === 'development') {
      import('@/mocks/browser').then(({ worker }) =>
        worker.start({ onUnhandledRequest: 'bypass' })
      );
    }
  }, []);

  return <html><body>{children}</body></html>;
}
```

---

## 4. 공통 타입 정의 (lib/types.ts)

```typescript
export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: { code: string; message: string } | null;
}

export type StrategyType = 'VWAP' | 'EMA_CROSS' | 'BOLLINGER' | 'GRID';
export type Timeframe = 'M1' | 'M5' | 'H1' | 'D1';
export type OrderSide = 'BUY' | 'SELL';
export type MarketRegime = 'TREND' | 'RANGE' | 'VOLATILE';
export type BacktestStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface PerformanceMetrics {
  totalReturn: number;      // 퍼센트 (23.5 = 23.5%)
  winRate: number;          // 퍼센트
  maxDrawdown: number;      // 퍼센트, 음수 (-12.4 = -12.4%)
  sharpeRatio: number;
  sortinoRatio: number;
  calmarRatio: number;
  winLossRatio: number;
  recoveryFactor: number;
  totalTrades: number;
  maxConsecutiveLoss: number;
  monthlyReturns: Record<string, number>;  // "YYYY-MM" -> 퍼센트
}

export interface BacktestResult {
  id: string;
  strategyType: StrategyType;
  coinPair: string;
  timeframe: Timeframe;
  startDate: string;
  endDate: string;
  initialCapital: number;  // 원화 단위
  status: BacktestStatus;
  metrics: PerformanceMetrics;
  createdAt: string;
}

export interface TradeRecord {
  side: OrderSide;
  price: number;        // 원화
  quantity: number;     // 코인 수량
  fee: number;          // 원화
  slippage: number;     // 원화
  pnl: number;          // 원화
  cumulativePnl: number;
  signalReason: string;
  marketRegime: MarketRegime;
  executedAt: string;
}

export interface BacktestRequest {
  strategyType: StrategyType;
  coinPair: string;
  timeframe: Timeframe;
  startDate: string;
  endDate: string;
  initialCapital?: number;
  slippageRate?: number;
  feeRate?: number;
  strategyParams?: Record<string, number>;
  fillSimulation?: { enabled: boolean; impactFactor: number; fillRatio: number };
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;  // 0-based
}
```

---

## 5. API 클라이언트 (lib/api.ts)

```typescript
import axios from 'axios';
import type { ApiResponse, BacktestRequest, BacktestResult, TradeRecord, PageResponse } from './types';

const api = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || '',
  timeout: 30000,
});

export const backtestApi = {
  run: (req: BacktestRequest) =>
    api.post<ApiResponse<BacktestResult>>('/api/v1/backtest/run', req).then(r => r.data),
  get: (id: string) =>
    api.get<ApiResponse<BacktestResult>>(`/api/v1/backtest/${id}`).then(r => r.data),
  list: (page = 0) =>
    api.get<ApiResponse<PageResponse<BacktestResult>>>('/api/v1/backtest/list', { params: { page } }).then(r => r.data),
  compare: (ids: string[]) =>
    api.get<ApiResponse<BacktestResult[]>>('/api/v1/backtest/compare', { params: { ids: ids.join(',') } }).then(r => r.data),
  trades: (id: string, page = 0) =>
    api.get<ApiResponse<PageResponse<TradeRecord>>>(`/api/v1/backtest/${id}/trades`, { params: { page } }).then(r => r.data),
};

export const systemApi = {
  strategyTypes: () => api.get<ApiResponse<unknown[]>>('/api/v1/strategies/types').then(r => r.data),
  coins: () => api.get<ApiResponse<string[]>>('/api/v1/data/coins').then(r => r.data),
};
```

---

## 6. 구현 화면 목록

| 경로 | 화면 | 주요 컴포넌트 |
|------|------|--------------|
| `/` | 대시보드 | 최근 백테스트 3개, 시스템 상태 |
| `/backtest` | 이력 목록 | 페이지네이션 테이블 |
| `/backtest/new` | 실행 폼 | 전략/코인/기간 선택 |
| `/backtest/[id]` | 결과 상세 | 지표 카드 + 차트 + 매매 기록 |
| `/backtest/compare` | 비교 | 다중 전략 오버레이 차트 |
| `/data` | 데이터 수집 | 수집 트리거 폼 |

### 결과 상세 차트 요구사항

- **누적 수익 곡선**: `cumulativePnl` 기준 라인 차트 (Recharts LineChart)
- **월별 수익률 히트맵**: `monthlyReturns` 기준 색상 그리드
- **MDD 시각화**: 낙폭 구간 음영 영역 차트

---

## 7. 프로젝트 구조

```
crypto-trader-frontend/
├── public/mockServiceWorker.js   # MSW 서비스 워커 (npx msw init으로 생성)
├── src/
│   ├── app/
│   │   ├── layout.tsx            # MSW 초기화 포함
│   │   ├── page.tsx              # 대시보드
│   │   ├── backtest/
│   │   │   ├── page.tsx
│   │   │   ├── new/page.tsx
│   │   │   ├── compare/page.tsx
│   │   │   └── [id]/page.tsx
│   │   └── data/page.tsx
│   ├── components/
│   │   ├── charts/
│   │   │   ├── CumulativePnlChart.tsx
│   │   │   ├── MonthlyReturnsHeatmap.tsx
│   │   │   └── CompareChart.tsx
│   │   ├── backtest/
│   │   │   ├── MetricsCard.tsx
│   │   │   ├── BacktestForm.tsx
│   │   │   └── TradesTable.tsx
│   │   └── ui/
│   ├── lib/
│   │   ├── api.ts
│   │   └── types.ts
│   └── mocks/
│       ├── browser.ts
│       ├── handlers.ts
│       └── data.ts
```

---

## 8. 실서버 전환 방법

MSW 개발 완료 후 실서버 연결 시:

1. `layout.tsx`에서 MSW 초기화 제거 (또는 `NEXT_PUBLIC_USE_MOCK=false` env 플래그)
2. `.env.local`에 `NEXT_PUBLIC_API_URL=http://localhost:8080` 설정
3. 백엔드 실행:
   ```bash
   cd crypto-auto-trader
   docker-compose up -d
   ./gradlew :web-api:bootRun
   ```

---

## 9. 주의사항

- 모든 금액은 **원화(KRW)** 단위
- `totalReturn`, `winRate` 등은 **퍼센트 값** (23.5 = 23.5%)
- `maxDrawdown`은 **음수** (-12.4 = -12.4% 낙폭)
- 페이지네이션은 **0-based** (`page=0`이 첫 페이지)
- `monthlyReturns` 키 형식: `"YYYY-MM"`

---

작성: Do-Backend 에이전트 (Claude Code)
대상: Do-Frontend 에이전트 (Gemini CLI)
스펙 파일: `docs/api-spec.yaml`
