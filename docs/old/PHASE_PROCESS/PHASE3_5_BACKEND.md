# Phase 3.5: Paper Trading - 백엔드 구현 완료

## 문서 정보
- 완료일: 2026-03-06
- 대상: Do-Backend Phase 3.5 (DESIGN.md 10.1 항목 14)

---

## 1. 구현 범위

| # | 항목 | 상태 |
|---|------|------|
| 14 | paper_trading 스키마 활성화 | 완료 (V8 기존 + V9 추가) |
| 14 | VirtualBalanceEntity + PaperPositionEntity + PaperOrderEntity | 완료 |
| 14 | PaperTradingService (전략 실행 + 가상 체결) | 완료 |
| 14 | PaperTradingController (REST API 6개) | 완료 |

---

## 2. 추가된 파일

```
web-api/src/main/resources/db/migration/
└── V9__enhance_paper_trading.sql          # virtual_balance 컬럼 추가 + 초기 레코드

web-api/src/main/java/com/cryptoautotrader/api/
├── entity/paper/
│   ├── VirtualBalanceEntity.java          # paper_trading.virtual_balance
│   ├── PaperPositionEntity.java           # paper_trading.position
│   └── PaperOrderEntity.java             # paper_trading."order"
├── repository/paper/
│   ├── VirtualBalanceRepository.java
│   ├── PaperPositionRepository.java
│   └── PaperOrderRepository.java
├── dto/
│   └── PaperTradingStartRequest.java
├── service/
│   └── PaperTradingService.java
└── controller/
    └── PaperTradingController.java
```

---

## 3. API 엔드포인트

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/paper-trading/balance` | 가상 잔고 + 세션 상태 |
| GET | `/api/v1/paper-trading/positions` | 보유 포지션 (OPEN) |
| GET | `/api/v1/paper-trading/orders` | 주문 내역 (페이지네이션) |
| POST | `/api/v1/paper-trading/start` | 모의투자 시작 |
| POST | `/api/v1/paper-trading/stop` | 모의투자 중단 (포지션 강제 청산) |
| POST | `/api/v1/paper-trading/reset` | 가상 잔고 초기화 |

### 시작 요청 예시
```json
POST /api/v1/paper-trading/start
{
  "strategyType": "EMA_CROSS",
  "coinPair": "KRW-BTC",
  "timeframe": "H1",
  "initialCapital": 10000000,
  "strategyParams": { "fastPeriod": 9, "slowPeriod": 21 }
}
```

### 잔고 응답 예시
```json
GET /api/v1/paper-trading/balance
{
  "success": true,
  "data": {
    "totalAssetKrw": 10250000,
    "availableKrw": 5430000,
    "positionValueKrw": 4820000,
    "unrealizedPnl": 250000,
    "totalReturnPct": 2.50,
    "initialCapital": 10000000,
    "status": "RUNNING",
    "strategyName": "EMA_CROSS",
    "coinPair": "KRW-BTC",
    "startedAt": "2026-03-06T09:00:00Z"
  }
}
```

---

## 4. 핵심 설계 결정

### 싱글톤 잔고 (id=1 고정)
- V9 마이그레이션에서 초기 레코드 INSERT (total=available=10,000,000 KRW)
- `getOrCreateBalance()`로 항상 동일 레코드 업데이트
- 한 번에 하나의 코인/전략만 모의투자 가능

### @Scheduled 전략 실행
- 1분마다 `runStrategy()` 호출
- 캔들 데이터: DB 우선 → 없으면 Upbit API 직접 조회
- `SKELETON` 상태 전략은 항상 HOLD 반환 → 매매 없음 (안전)

### 체결 수수료
- 매수/매도 모두 0.05% (FEE_RATE = 0.0005)
- 투자 비율: 가용 자금의 95% (INVEST_RATIO = 0.95)

### 포지션 청산 시점
- SELL 신호 수신 시 즉시 시장가 청산
- `stop()` 호출 시 열린 포지션 전량 강제 청산 (현재가 조회)

---

## 5. V9 마이그레이션

```sql
ALTER TABLE paper_trading.virtual_balance
    ADD COLUMN initial_capital  NUMERIC(20,2),
    ADD COLUMN strategy_name    VARCHAR(50),
    ADD COLUMN coin_pair        VARCHAR(20),
    ADD COLUMN timeframe        VARCHAR(10),
    ADD COLUMN status           VARCHAR(10) NOT NULL DEFAULT 'STOPPED',
    ADD COLUMN started_at       TIMESTAMPTZ,
    ADD COLUMN stopped_at       TIMESTAMPTZ;

INSERT INTO paper_trading.virtual_balance (total_krw, available_krw, initial_capital, status)
VALUES (10000000, 10000000, 10000000, 'STOPPED');
```

---

## 6. 프론트엔드 연동 가이드 업데이트

`docs/FRONTEND_PHASE3_GUIDE.md`의 Paper Trading MSW 핸들러를 실서버로 전환:
1. MSW handlers에서 `/api/v1/paper-trading/*` 핸들러 제거
2. `NEXT_PUBLIC_API_URL=http://localhost:8080` 설정 확인
3. 백엔드 기동 후 연동 테스트

---

## 7. 다음 단계

- **Phase 4 (실전 매매)**
  - `OrderExecutionEngine` + `OrderStateMachine`
  - Upbit WebSocket 실시간 시세 수신
  - `TradeController` + `RiskController`
  - `TelegramNotificationService`

---

작성: Do-Backend 에이전트
기반: DESIGN.md 10.1 Phase 3.5
