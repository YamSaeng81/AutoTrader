# Frontend 실서버 전환 가이드 (Gemini CLI용)

## 문서 정보
- 작성일: 2026-03-06
- 대상: Do-Frontend 에이전트 (Gemini CLI)
- 전제: Phase 2 + Phase 3 + Phase 3.5 MSW 개발 완료 상태

---

## 0. 현재 백엔드 구현 상태

Phase 3.5까지 백엔드 완료. 모든 API 실서버 연결 가능.

| Phase | 기능 | 백엔드 상태 | 전환 필요 여부 |
|-------|------|-----------|--------------|
| 2 | 백테스팅 (run/result/list/compare/trades) | ✅ 완료 | 전환 필요 |
| 2 | 데이터 수집 / 코인 목록 | ✅ 완료 | 전환 필요 |
| 2 | 시스템 헬스 / 전략 타입 | ✅ 완료 | 전환 필요 |
| 3 | 전략 목록·상세 조회 (`GET`) | ✅ 완료 | 전환 필요 |
| 3 | 전략 설정 저장/수정 (`POST/PUT/PATCH`) | ❌ 미구현 | MSW 유지 |
| 3.5 | Paper Trading 전체 API | ✅ 완료 | 전환 필요 |

---

## 1. 백엔드 실행

```bash
# 1. DB + Redis 기동
cd D:\Claude Code\projects\crypto-auto-trader
docker-compose up -d

# 2. 백엔드 기동 (포트 8080)
./gradlew :web-api:bootRun

# 3. 정상 기동 확인
curl http://localhost:8080/api/v1/health
```

---

## 2. MSW → 실서버 전환 방법

### 2-1. MSW 비활성화

```typescript
// src/app/layout.tsx 수정
// 기존: MSW 무조건 활성화
useEffect(() => {
  if (process.env.NODE_ENV === 'development') {
    import('@/mocks/browser').then(({ worker }) => worker.start(...));
  }
}, []);

// 변경: 환경변수로 제어
useEffect(() => {
  if (process.env.NODE_ENV === 'development' && process.env.NEXT_PUBLIC_USE_MOCK === 'true') {
    import('@/mocks/browser').then(({ worker }) => worker.start({ onUnhandledRequest: 'bypass' }));
  }
}, []);
```

### 2-2. 환경변수 설정

```bash
# .env.local
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_USE_MOCK=false   # 실서버 전환 시 false
```

---

## 3. 전환 후 제거할 MSW 핸들러

실서버로 전환되었으므로 아래 핸들러는 `src/mocks/handlers.ts`에서 **제거 또는 주석 처리**:

```typescript
// ── 제거 가능 (실서버 완료) ──────────────────────────────────
// Phase 2
http.post('/api/v1/backtest/run', ...)
http.get('/api/v1/backtest/:id', ...)
http.get('/api/v1/backtest/:id/trades', ...)
http.get('/api/v1/backtest/list', ...)
http.get('/api/v1/backtest/compare', ...)
http.get('/api/v1/strategies/types', ...)
http.get('/api/v1/data/coins', ...)

// Phase 3 (GET만)
http.get('/api/v1/strategies', ...)
http.get('/api/v1/strategies/:name', ...)

// Phase 3.5 (전체)
http.get('/api/v1/paper-trading/balance', ...)
http.get('/api/v1/paper-trading/positions', ...)
http.get('/api/v1/paper-trading/orders', ...)
http.post('/api/v1/paper-trading/start', ...)
http.post('/api/v1/paper-trading/stop', ...)
http.post('/api/v1/paper-trading/reset', ...)

// ── MSW 유지 (백엔드 미구현) ─────────────────────────────────
// Phase 3 전략 설정 저장/수정 (Phase 4에서 구현 예정)
http.post('/api/v1/strategies', ...)       // 유지
http.put('/api/v1/strategies/:id', ...)    // 유지
http.patch('/api/v1/strategies/:id/toggle', ...)  // 유지
```

---

## 4. Paper Trading API 실서버 스펙

MSW mock 데이터 타입을 실서버 응답에 맞게 교체:

### GET /api/v1/paper-trading/balance
```typescript
interface PaperTradingBalance {
  totalAssetKrw: number;       // 총 평가 자산
  availableKrw: number;        // 가용 KRW
  positionValueKrw: number;    // 포지션 평가금액
  unrealizedPnl: number;       // 미실현 손익 (원)
  totalReturnPct: number;      // 총 수익률 (%)
  initialCapital: number;      // 초기 자본
  status: 'RUNNING' | 'STOPPED';
  strategyName: string;        // "EMA_CROSS" 등
  coinPair: string;            // "KRW-BTC" 등
  startedAt: string | null;    // ISO-8601
}
```

### GET /api/v1/paper-trading/positions
```typescript
interface PaperPosition {
  id: number;
  coinPair: string;
  side: 'BUY' | 'SELL';
  quantity: number;           // size (코인 수량)
  avgEntryPrice: number;      // avgPrice
  unrealizedPnl: number;
  unrealizedPnlPct: number;   // 프론트에서 계산 필요
  openedAt: string;
}
```

### GET /api/v1/paper-trading/orders
```typescript
// PageResponse<PaperOrder> 형태
interface PaperOrder {
  id: number;
  coinPair: string;
  side: 'BUY' | 'SELL';
  price: number;
  quantity: number;
  state: 'PENDING' | 'FILLED' | 'CANCELLED';
  signalReason: string;
  createdAt: string;
  filledAt: string | null;
}
```

### POST /api/v1/paper-trading/start
```typescript
// 요청
interface PaperTradingStartRequest {
  strategyType: string;       // "EMA_CROSS"
  coinPair: string;           // "KRW-BTC"
  timeframe: string;          // "H1"
  initialCapital: number;     // 최소 100,000원
  strategyParams?: Record<string, number>;
}
// 응답: { status, strategyName, coinPair, initialCapital, startedAt }
```

---

## 5. 전환 테스트 체크리스트

```
Phase 2 백테스팅
  [ ] 백테스트 실행 (POST /run)
  [ ] 결과 조회 (GET /{id})
  [ ] 매매 기록 (GET /{id}/trades)
  [ ] 목록 조회 (GET /list)
  [ ] 전략 비교 (GET /compare)
  [ ] 데이터 수집 (POST /data/collect)

Phase 3 전략 관리
  [ ] 전략 목록 조회 (GET /strategies)
  [ ] AVAILABLE/SKELETON 상태 표시 확인

Phase 3.5 모의투자
  [ ] 잔고 조회 (GET /balance) — status: STOPPED 기본값
  [ ] 모의투자 시작 (POST /start)
  [ ] 1분 후 주문 내역에 자동 체결 여부 확인
  [ ] 포지션 조회 (GET /positions)
  [ ] 모의투자 중단 (POST /stop)
  [ ] 잔고 초기화 (POST /reset)
```

---

## 6. 주의사항

- `SKELETON` 전략(RSI, MACD 등)으로 시작 시 항상 HOLD → 매매 발생 안 함 (정상)
- Paper Trading은 **1분마다** 전략 신호를 확인 (`@Scheduled(fixedDelay=60000)`)
- 캔들 데이터가 DB에 없으면 Upbit API 직접 조회 → 첫 실행 전 `/api/v1/data/collect` 권장
- `stop()` 호출 시 열린 포지션은 **현재가로 강제 청산**

---

## 7. 작업 완료 후 필수 문서 작성

**전환 완료 및 Phase 3.5 Frontend 구현이 완료되면 아래 형식으로 완료 문서를 작성해주세요.**

파일 경로: `D:\Claude Code\projects\crypto-auto-trader\docs\PHASE3_5_FRONTEND.md`

작성 형식은 아래 `docs/PHASE3_5_BACKEND.md`와 동일한 구조로:

```markdown
# Phase 3.5: Paper Trading - 프론트엔드 구현 완료

## 문서 정보
- 완료일: YYYY-MM-DD
- 대상: Do-Frontend Phase 3 + 3.5

---

## 1. 구현 범위

| # | 항목 | 상태 |
|---|------|------|
| Phase 3 | 전략 관리 페이지 (/strategies) | 완료 |
| Phase 3.5 | 모의투자 현황 페이지 (/paper-trading) | 완료 |

---

## 2. 추가된 파일 (경로 목록)

...

## 3. 구현된 화면 및 컴포넌트

...

## 4. MSW → 실서버 전환 완료 항목

...

## 5. 특이사항 / 설계 결정

...

## 6. 다음 단계 (Phase 4 Frontend 준비사항)

...
```

---

작성: Do-Backend 에이전트 (Claude Code)
대상: Do-Frontend 에이전트 (Gemini CLI)
참고: `docs/PHASE3_5_BACKEND.md`, `docs/FRONTEND_PHASE3_GUIDE.md`
