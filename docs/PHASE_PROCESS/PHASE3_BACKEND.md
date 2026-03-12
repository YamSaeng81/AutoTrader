# Phase 3: 전략 개선 - 백엔드 구현 완료

## 문서 정보
- 완료일: 2026-03-06
- 대상: Do-Backend Phase 3 (DESIGN.md 10.1 항목 12~13)
- 빌드 상태: 컴파일 통과 (IDE Lombok LSP 경고는 Gradle 빌드와 무관)

---

## 1. 구현 범위

| # | 항목 | 상태 |
|---|------|------|
| 12 | strategy-lib: 추가 5종 전략 스켈레톤 | 완료 |
| 13 | web-api: StrategyController | 완료 |

> DESIGN.md에는 6종 추가 명시. 실제 구현은 사용자 확정 5종 (RSI, MACD, Supertrend, ATR Breakout, Orderbook Imbalance).
> 로직 구현은 Paper Trading/백테스팅 단계에서 완성 예정.

---

## 2. 추가된 파일

### strategy-lib
```
strategy-lib/src/main/java/com/cryptoautotrader/strategy/
├── rsi/
│   ├── RsiStrategy.java          # RSI 과매수/과매도 전략 (스켈레톤)
│   └── RsiConfig.java            # period=14, oversold=30, overbought=70
├── macd/
│   ├── MacdStrategy.java         # MACD/Signal 크로스 전략 (스켈레톤)
│   └── MacdConfig.java           # fast=12, slow=26, signal=9
├── supertrend/
│   ├── SupertrendStrategy.java   # ATR 기반 추세 추종 전략 (스켈레톤)
│   └── SupertrendConfig.java     # atrPeriod=10, multiplier=3.0
├── atrbreakout/
│   ├── AtrBreakoutStrategy.java  # ATR 변동성 돌파 전략 (스켈레톤)
│   └── AtrBreakoutConfig.java    # atrPeriod=14, multiplier=1.5
└── orderbook/
    ├── OrderbookImbalanceStrategy.java  # 호가 불균형 전략 (스켈레톤)
    └── OrderbookImbalanceConfig.java    # imbalanceThreshold=0.65
```

### web-api
```
web-api/src/main/java/com/cryptoautotrader/api/controller/
└── StrategyController.java       # GET /api/v1/strategies, GET /api/v1/strategies/{name}
```

### 수정된 파일
- `StrategyRegistry.java`: 5개 신규 전략 등록 추가

---

## 3. 추가된 API 엔드포인트

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/strategies` | 전체 전략 목록 + 상태 |
| GET | `/api/v1/strategies/{name}` | 전략 단건 상세 |

### 응답 예시 (GET /api/v1/strategies)
```json
{
  "success": true,
  "data": [
    { "name": "VWAP",       "minimumCandleCount": 20, "status": "AVAILABLE", "description": "..." },
    { "name": "EMA_CROSS",  "minimumCandleCount": 21, "status": "AVAILABLE", "description": "..." },
    { "name": "BOLLINGER",  "minimumCandleCount": 20, "status": "AVAILABLE", "description": "..." },
    { "name": "GRID",       "minimumCandleCount": 1,  "status": "AVAILABLE", "description": "..." },
    { "name": "RSI",        "minimumCandleCount": 15, "status": "SKELETON",  "description": "..." },
    { "name": "MACD",       "minimumCandleCount": 35, "status": "SKELETON",  "description": "..." },
    { "name": "SUPERTREND", "minimumCandleCount": 11, "status": "SKELETON",  "description": "..." },
    { "name": "ATR_BREAKOUT","minimumCandleCount": 15, "status": "SKELETON", "description": "..." },
    { "name": "ORDERBOOK_IMBALANCE", "minimumCandleCount": 5, "status": "SKELETON", "description": "..." }
  ]
}
```

---

## 4. 전략 상태 구분

| 상태 | 의미 |
|------|------|
| `AVAILABLE` | 로직 구현 완료, 백테스팅 가능 |
| `SKELETON` | 인터페이스/파라미터 정의만, `evaluate()`는 항상 HOLD 반환 |

---

## 5. 스켈레톤 전략 설계 의도

| 전략 | 핵심 로직 (TODO) | 의존 데이터 |
|------|----------------|------------|
| RSI | RSI 계산 → 과매수/과매도 임계값 비교 | 캔들 close |
| MACD | EMA(fast) - EMA(slow) → Signal EMA → 크로스 감지 | 캔들 close |
| Supertrend | ATR 계산 → Upper/Lower Band → 방향 전환 감지 | 캔들 OHLC |
| ATR Breakout | ATR × multiplier → 당일 시가 ± 돌파 레벨 | 캔들 OHLC |
| Orderbook Imbalance | 매수/매도 호가 비율 계산 | **WebSocket 실시간 호가** (Phase 4) |

> `IndicatorUtils`에 이미 ATR, EMA 계산 유틸이 있어 RSI/MACD/Supertrend/ATR Breakout은 구현 공수 낮음

---

## 6. 다음 단계

- **Phase 3.5 (Paper Trading)**: paper_trading 스키마 활성화 + PaperTradingService
  - V8 마이그레이션은 Phase 1에서 이미 작성 완료
- **스켈레톤 전략 완성**: Paper Trading 백테스팅 결과 보면서 순차 구현
  - RSI → MACD → Supertrend → ATR Breakout 순 (캔들 데이터만 필요)
  - Orderbook Imbalance는 Phase 4 WebSocket 구현 후

---

작성: Do-Backend 에이전트
기반: DESIGN.md 10.1 Phase 3
