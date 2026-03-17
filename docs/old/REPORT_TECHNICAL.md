# CryptoAutoTrader - Technical Report

## 문서 정보

| 항목 | 내용 |
|------|------|
| 버전 | 1.0 |
| 작성일 | 2026-03-15 |
| 기준 문서 | DESIGN.md v1.3, CHECK_RESULT.md v4.0, PROGRESS.md (2026-03-15) |
| 대상 | 개발팀, 운영팀 |

---

## 1. 시스템 아키텍처

### 1.1 전체 구조

```
[Web Browser]
     |  HTTPS
     v
[Next.js Frontend :3000]
     |  REST API
     v
[Spring Boot Backend :8080]
     |
     +-- [core-engine]       백테스팅, 성과 지표, Regime 감지, Risk Engine
     +-- [strategy-lib]      전략 10종 + CompositeStrategy
     +-- [exchange-adapter]  Upbit REST/WebSocket/Order
     |
     +-- [TimescaleDB :5432] 시계열 데이터, 백테스팅, 실전 포지션/주문
     +-- [Redis :6379]       실시간 시세 캐시, Rate Limit
```

### 1.2 기술 스택

| 계층 | 기술 | 버전 |
|------|------|------|
| 백엔드 프레임워크 | Spring Boot | 3.2.x |
| 언어 | Java | 17 (LTS) |
| 빌드 | Gradle | 8.x (멀티모듈) |
| ORM | Spring Data JPA | |
| 프론트엔드 | Next.js | 16.1.6 |
| UI 라이브러리 | React | 19.2.3 |
| 언어 | TypeScript | 5.x |
| 스타일 | Tailwind CSS | v4 (다크모드 기본) |
| 차트 | Recharts | |
| DB | TimescaleDB (PostgreSQL 확장) | PostgreSQL 15.x 기반 |
| 캐시 | Redis | 7.x |
| DB 마이그레이션 | Flyway | V1~V13 (+ 수동 V15 DDL) |
| 컨테이너 | Docker + Docker Compose | 24.x |
| 알림 | Telegram Bot API | |
| API 문서화 | Springdoc (Swagger UI) | |

---

## 2. 모듈 구조 (Gradle 멀티모듈)

### 2.1 백엔드 모듈 의존 관계

```
web-api
  └── depends on: core-engine, strategy-lib, exchange-adapter

exchange-adapter
  └── depends on: core-engine

strategy-lib
  └── depends on: core-engine

core-engine
  └── (최상위 — 외부 의존 없음)
```

### 2.2 core-engine 패키지 구성

```
core-engine/src/main/java/com/crypto/core/
├── backtest/
│   ├── BacktestEngine.java         # 백테스팅 실행 (Look-Ahead Bias 차단, 수수료 반영)
│   ├── BacktestConfig.java         # 백테스팅 설정 (코인, 기간, 타임프레임, 전략)
│   ├── BacktestResult.java         # 백테스팅 결과 (거래 내역, 성과 지표)
│   ├── WalkForwardTestRunner.java  # In-Sample/Out-of-Sample 분할 검증
│   └── FillSimulator.java          # Market Impact + Partial Fill 시뮬레이션
├── metrics/
│   ├── MetricsCalculator.java      # 8종 성과 지표 계산 (Calmar Ratio 수식 수정됨)
│   └── PerformanceReport.java      # 지표 집계 보고
├── regime/
│   ├── MarketRegime.java           # enum: TREND / RANGE / VOLATILITY / TRANSITIONAL
│   ├── MarketRegimeDetector.java   # ADX + BB Bandwidth + ATR Spike + Hysteresis
│   └── MarketRegimeFilter.java     # Regime별 적합 전략 매핑 테이블
├── risk/
│   ├── RiskEngine.java             # Fixed Fractional 포지션 사이징, Correlation Risk
│   ├── RiskConfig.java             # 최대 레버리지, 상관관계 임계값, 기본 위험 비율
│   └── RiskCheckResult.java        # 리스크 체크 결과
├── portfolio/
│   └── PortfolioManager.java       # 전략 충돌 방지, 자본 할당
├── selector/
│   ├── StrategySelector.java       # Regime별 전략 그룹 + 가중치 반환
│   ├── CompositeStrategy.java      # Weighted Voting 복합 신호 생성
│   ├── WeightedStrategy.java       # 전략 + 가중치 래퍼
│   ├── MultiTimeframeFilter.java   # HTF+LTF 역추세 억제
│   ├── CandleDownsampler.java      # LTF 캔들 → HTF 다운샘플
│   └── TimeframePreset.java        # M1~D1 × 7개 전략 파라미터 프리셋
└── model/
    ├── CoinPair.java / OrderSide.java / TimeFrame.java / TradeRecord.java
    └── ...
```

### 2.3 strategy-lib 패키지 구성

```
strategy-lib/src/main/java/com/crypto/strategy/
├── Strategy.java                   # 전략 인터페이스 (evaluate, getMinimumCandleCount)
├── StatefulStrategy.java           # 상태 추적 인터페이스 (Grid 중복 매매 방지용)
├── StrategySignal.java             # 신호 (BUY/SELL/HOLD + confidence + stopLoss/takeProfit)
├── StrategyConfig.java             # 전략 공통 설정
├── StrategyRegistry.java           # 전략 등록 및 조회
├── IndicatorUtils.java             # 공통 기술 지표 (EMA, ATR, ADX, BB Bandwidth 등)
└── impl/
    ├── VwapStrategy.java + VwapConfig.java
    ├── EmaCrossStrategy.java + EmaCrossConfig.java
    ├── BollingerStrategy.java + BollingerConfig.java
    ├── GridStrategy.java + GridConfig.java
    ├── RsiStrategy.java + RsiConfig.java
    ├── MacdStrategy.java + MacdConfig.java
    ├── SupertrendStrategy.java + SupertrendConfig.java
    ├── AtrBreakoutStrategy.java + AtrBreakoutConfig.java
    ├── OrderbookImbalanceStrategy.java + OrderbookImbalanceConfig.java
    └── StochasticRsiStrategy.java + StochasticRsiConfig.java
```

### 2.4 exchange-adapter 패키지 구성

```
exchange-adapter/src/main/java/com/crypto/exchange/
├── ExchangeAdapter.java            # 거래소 추상화 인터페이스
├── upbit/
│   ├── UpbitRestClient.java        # REST API (Rate Limiting 110ms, synchronized throttle)
│   ├── UpbitCandleCollector.java   # 과거 캔들 수집기
│   ├── UpbitWebSocketClient.java   # 실시간 시세 WebSocket (GZIP 디코딩, 자동 재연결)
│   └── UpbitOrderClient.java       # 주문 실행 (char[] 기반 JWT 보안)
├── dto/
│   ├── UpbitCandleResponse.java
│   ├── OrderResponse.java
│   └── AccountResponse.java
└── order/
    ├── OrderExecutionEngine.java   # 6단계 주문 상태 머신
    └── (OrderStateMachine 내장)
```

### 2.5 web-api 패키지 구성

```
web-api/src/main/java/com/crypto/api/
├── controller/
│   ├── BacktestController.java     # 백테스팅 CRUD + bulk-run
│   ├── StrategyController.java     # 전략 관리 (Registry 읽기 + DB CRUD)
│   ├── DataController.java         # 캔들 데이터 수집/조회
│   ├── LogController.java          # 전략 로그 조회
│   ├── PaperTradingController.java # 모의투자 세션 관리
│   ├── TradingController.java      # 실전매매 세션 + 포지션 + 주문 + 리스크
│   ├── SystemController.java       # 시스템 상태, 전략 타입 목록
│   └── GlobalExceptionHandler.java # BAD_REQUEST / CONFLICT / INTERNAL_ERROR 범용 처리
├── service/
│   ├── BacktestService.java        # 백테스팅 실행 서비스 (@Transactional 제거됨)
│   ├── DataCollectionService.java  # 캔들 수집 관리
│   ├── PaperTradingService.java    # 모의투자 전략 실행 + 가상 체결 (COMPOSITE 파이프라인 연동)
│   ├── LiveTradingService.java     # 실전매매 세션 관리 (orphan guard, COMPOSITE 파이프라인 연동)
│   ├── MarketDataSyncService.java  # 60초 주기 캔들 동기화
│   ├── MarketRegimeAwareScheduler.java # 1시간 주기 Regime 감지 + 전략 자동 스위칭
│   ├── OrderExecutionEngine.java   # 주문 실행 (exchange-adapter 위임)
│   ├── PositionService.java        # 포지션 조회
│   ├── RiskManagementService.java  # 리스크 설정 관리
│   ├── ExchangeHealthMonitor.java  # 거래소 헬스 체크
│   └── TelegramNotificationService.java # 즉시 알림 + 일별 요약 cron
└── config/
    ├── AsyncConfig.java            # 스레드 풀 3개 분리 (시세/주문/일반)
    ├── WebConfig.java              # CORS 설정
    ├── RedisConfig.java            # JSON 직렬화, 캐시별 TTL
    ├── SchedulerConfig.java        # 전용 스레드풀, Graceful shutdown 30초
    ├── EngineConfig.java           # UpbitRestClient, UpbitOrderClient Bean 등록
    ├── SwaggerConfig.java          # API 문서화
    └── SecurityConfig.java         # [미구현] Spring Security API 인증
```

---

## 3. 데이터베이스 스키마

### 3.1 테이블 소유권 구조

```
crypto_auto_trader (단일 DB, TimescaleDB)

[백테스팅 전용]
├── backtest_run          — 백테스트 실행 기록 (전략, 코인, 기간, 상태)
├── backtest_metrics      — 성과 지표 (Sharpe, Sortino, MDD, Calmar 등)
└── backtest_trade        — 백테스트 체결 기록 (market_regime VARCHAR(20))

[모의투자 전용] paper_trading 스키마
├── paper_trading.virtual_balance   — 가상 잔고 + 세션 정보
├── paper_trading.position          — 모의 포지션 (평균단가, 미실현 PnL)
├── paper_trading.order             — 모의 주문
├── paper_trading.strategy_log      — 모의 전략 실행 로그
└── paper_trading.trade_log         — 모의 체결 로그

[실전투자 전용] public 스키마
├── live_trading_session  — 실전 세션 (상태: CREATED/RUNNING/STOPPED/EMERGENCY_STOPPED)
├── position              — 실전 포지션 (session_id FK)
└── order                 — 실전 주문 (session_id FK)

[공통 인프라]
├── candle_data           — TimescaleDB hypertable (OHLCV, 압축 정책)
├── strategy_config       — 전략 설정 (manual_override 플래그)
├── strategy_log          — 전략 실행 로그
├── trade_log             — 체결 로그
├── strategy_signal       — 신호 기록
├── risk_config           — 리스크 설정
├── market_data_cache     — 실시간 시세 캐시 (candle_data와 분리)
└── strategy_type_enabled — 전략 타입별 ON/OFF
```

### 3.2 Flyway 마이그레이션 이력

| 버전 | 주요 내용 |
|------|----------|
| V1 | candle_data hypertable + 인덱스 + 압축 정책 |
| V2 | backtest_run / backtest_metrics / backtest_trade |
| V3~V7 | strategy_config, position, order, risk_config, strategy_log, strategy_signal |
| V8~V10 | paper_trading 스키마 + 멀티세션 지원 |
| V11 | strategy_config.manual_override 컬럼 추가 |
| V12 | live_trading_session + position/order session_id FK |
| V13 | market_data_cache (실시간 싱크 전용) |
| V14 | strategy_type_enabled (전략 10종 ON/OFF) |
| (수동 ALTER) | backtest_trade.market_regime VARCHAR(10) → VARCHAR(20) ("TRANSITIONAL" 저장용) |

> Flyway V16으로 backtest_trade.market_regime VARCHAR(20) 정합성 수정 권장 (현재 실DB와 V2 SQL 불일치)

### 3.3 주요 인덱스

```sql
-- candle_data
idx_candle_unique    (coin_pair, timeframe, open_time)
idx_candle_lookup    (coin_pair, timeframe, open_time DESC)

-- backtest
idx_metrics_run      (backtest_run_id)
idx_bt_trade_run     (backtest_run_id)

-- live trading
idx_live_session_status   (status)
idx_position_session      (session_id)
idx_order_session         (session_id)
idx_position_open         (WHERE status='OPEN')
idx_order_state           (WHERE state IN (...active states...))
```

---

## 4. API 목록

### 4.1 데이터 수집 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | /api/v1/data/collect | 캔들 데이터 수집 요청 |
| GET | /api/v1/data/status | 수집 현황 (totalCandles, pairCount) |
| GET | /api/v1/data/coins | 지원 코인 목록 |
| GET | /api/v1/data/candles | 캔들 조회 (coinPair, timeframe, start, end, limit) |
| GET | /api/v1/data/summary | 수집 요약 |
| DELETE | /api/v1/data/candles | 캔들 데이터 삭제 |

### 4.2 백테스팅 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | /api/v1/backtest/run | 단일 전략 백테스트 실행 |
| POST | /api/v1/backtest/bulk-run | 복수 전략 일괄 백테스트 |
| POST | /api/v1/backtest/walk-forward | Walk Forward Test 실행 |
| GET | /api/v1/backtest/list | 백테스트 이력 목록 |
| GET | /api/v1/backtest/{id} | 상세 결과 (metrics 포함) |
| GET | /api/v1/backtest/{id}/trades | 체결 내역 |
| GET | /api/v1/backtest/compare?ids= | 복수 백테스트 비교 |
| DELETE | /api/v1/backtest/{id} | 단건 삭제 |
| DELETE | /api/v1/backtest/bulk | 벌크 삭제 |

### 4.3 전략 관리 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | /api/v1/strategies | 전략 목록 |
| GET | /api/v1/strategies/{name} | 전략 상세 |
| POST | /api/v1/strategies | 전략 설정 저장 |
| PUT | /api/v1/strategies/{id} | 전략 설정 수정 |
| PATCH | /api/v1/strategies/{id}/toggle | 활성화/비활성화 |
| GET | /api/v1/strategies/types | 지원 전략 타입 목록 |

### 4.4 모의투자 API

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | /api/v1/paper-trading/sessions | 세션 목록 |
| POST | /api/v1/paper-trading/sessions | 세션 생성 + 시작 |
| GET | /api/v1/paper-trading/sessions/{id} | 세션 상세 |
| GET | /api/v1/paper-trading/sessions/{id}/positions | 포지션 목록 |
| GET | /api/v1/paper-trading/sessions/{id}/orders | 주문 내역 |
| POST | /api/v1/paper-trading/sessions/{id}/stop | 세션 정지 |
| GET | /api/v1/paper-trading/sessions/{id}/chart | 차트 데이터 |
| DELETE | /api/v1/paper-trading/history/{id} | 이력 단건 삭제 |
| DELETE | /api/v1/paper-trading/history/bulk | 이력 벌크 삭제 |

### 4.5 실전매매 API (Phase 4)

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | /api/v1/trading/status | 전체 상태 요약 |
| POST | /api/v1/trading/sessions | 세션 생성 |
| GET | /api/v1/trading/sessions | 세션 목록 |
| GET | /api/v1/trading/sessions/{id} | 세션 상세 |
| POST | /api/v1/trading/sessions/{id}/start | 세션 시작 |
| POST | /api/v1/trading/sessions/{id}/stop | 세션 정지 |
| POST | /api/v1/trading/sessions/{id}/emergency-stop | 긴급 정지 |
| DELETE | /api/v1/trading/sessions/{id} | 세션 삭제 (OPEN 포지션 있으면 거부) |
| GET | /api/v1/trading/sessions/{id}/positions | 세션별 포지션 |
| GET | /api/v1/trading/sessions/{id}/orders | 세션별 주문 |
| POST | /api/v1/trading/emergency-stop | 전체 긴급 정지 |
| GET | /api/v1/trading/positions | 전체 포지션 |
| GET | /api/v1/trading/orders | 전체 주문 |
| GET | /api/v1/trading/risk/config | 리스크 설정 조회 |
| PUT | /api/v1/trading/risk/config | 리스크 설정 수정 |
| GET | /api/v1/trading/health/exchange | 거래소 상태 확인 |
| POST | /api/v1/trading/telegram/test | 텔레그램 알림 테스트 |

---

## 5. 전략 고도화 Phase S1~S5

### 5.1 Phase S1 — 즉시 버그 수정 (완료)

| 항목 | 내용 |
|------|------|
| S1-1 Supertrend 버그 | SupertrendResult에 upperBand/lowerBand 분리, 밴드 선택 로직 수정 |
| S1-2 Grid 하드코딩 제거 | `lowest = new BigDecimal("999999999999")` → 첫 캔들 low 값 초기화 |
| S1-3 Grid StatefulStrategy | Set<Integer> activeLevels — 동일 레벨 중복 BUY 차단, 범위 1% 변경 시 자동 초기화 |
| S1-4 상충 신호 테스트 | ConflictingSignalTest.java — Supertrend=BUY + Bollinger=SELL 동시 발생 확인 |

### 5.2 Phase S2 — Market Regime + Risk Engine 강화 (완료)

**MarketRegimeDetector 개선**
- `TRANSITIONAL` 상태 추가 (ADX 20~25 구간 → 직전 Regime 유지)
- Hysteresis: 새 Regime이 3캔들 연속 유지되어야 전환 (`previousRegime`, `holdCount` 상태 보존)
- Bollinger Bandwidth 기반 RANGE 감지: ADX < 20 AND BB Bandwidth < 최근 30기간 하위 20%
- 동적 ATR Spike 기반 VOLATILITY 감지: ATR > ATR 20기간 이동평균 × 1.5 AND ADX < 25
- `IndicatorUtils`에 `bollingerBandwidth()`, `bollingerBandwidths()`, `atrList()` 추가

**RiskEngine 강화**
- Fixed Fractional Position Sizing: `Position = Account × Risk% / StopDistance%`
- Correlation Risk 관리: BTC/ETH=0.85, BTC/BNB=0.78, ETH/BNB=0.80 하드코딩 (상관계수 > 0.7 쌍은 슬롯 추가 소모)
- RiskConfig에 `maxLeverage`(기본 3.0), `correlationThreshold`(기본 0.7), `defaultRiskPercentage`(기본 0.01) 추가

### 5.3 Phase S3 — Strategy Selector & Composite Strategy (완료)

**StrategySelector** — Regime별 전략 그룹 + 가중치 반환

| Regime | 전략 구성 |
|--------|----------|
| TREND | Supertrend(0.5) + EMA_CROSS(0.3) + ATR_BREAKOUT(0.2) |
| RANGE | Bollinger(0.4) + RSI(0.4) + Grid(0.2) |
| VOLATILITY | ATR_BREAKOUT(0.6) + StochasticRSI(0.4) |
| TRANSITIONAL | 직전 전략 그룹 × 0.5 가중치 (무한재귀 방지: TRANSITIONAL → RANGE 폴백) |

**CompositeStrategy** — Weighted Voting

```
buyScore = Σ(weight × confidence)  [전략별 가중치 × 신뢰도]
sellScore = 동일 방식

buyScore > 0.6 → STRONG_BUY
buyScore > 0.4 → BUY (weak)
양쪽 모두 > 0.4 (상충) → HOLD
```

**CompositeStrategy 실전 연동** (2026-03-15 추가)

`MarketRegimeDetector → StrategySelector → CompositeStrategy` 파이프라인을 `PaperTradingService`와 `LiveTradingService`에 직접 연동. 전략 이름 `"COMPOSITE"` 선택 시 `StrategyRegistry` 경로 대신 파이프라인을 직접 실행.

```java
// PaperTradingService / LiveTradingService (공통 패턴)
if ("COMPOSITE".equals(strategyName)) {
    MarketRegimeDetector detector = sessionDetectors.computeIfAbsent(
            sessionId, id -> new MarketRegimeDetector());
    MarketRegime regime = detector.detect(candles);
    List<WeightedStrategy> weighted = StrategySelector.select(regime);
    signal = new CompositeStrategy(weighted).evaluate(candles, Collections.emptyMap());
} else {
    signal = StrategyRegistry.get(strategyName).evaluate(candles, params);
}
```

- `sessionDetectors`: `ConcurrentHashMap<Long, MarketRegimeDetector>` — 세션마다 독립 Detector 인스턴스 (Hysteresis 상태 격리)
- 세션 중단/삭제 시 `sessionDetectors.remove(sessionId)` 호출로 메모리 누수 방지
- 프론트엔드 모의투자 및 실전매매 세션 생성 폼에 `COMPOSITE` 옵션 추가

### 5.4 Phase S4 — 개별 전략 고도화 (완료)

| 전략 | 개선 내용 |
|------|----------|
| Supertrend | ATR O(n²) → O(n): ATR 배열 사전 계산 후 재사용 |
| EMA Cross | ADX > 25 필터 추가 (횡보장 Whipsaw 억제) + fast 9→20, slow 21→50 슬로우화 |
| Bollinger | ADX < 25 필터 + Squeeze 감지 (bandwidth < 최근 30캔들 최저) |
| RSI | 피봇 기반 다이버전스 (스윙 고점/저점 감지) + 임계값 25/60 강화 |
| ATR Breakout | 거래량 필터: 돌파 시 평균 거래량 × N배 이상일 때만 유효 신호 |
| Orderbook | 호가 Delta 추적: 연속 스냅샷 간 취소 패턴 분석으로 스푸핑 필터링 |
| MACD | ADX > 25 필터 추가 (횡보장 크로스 억제) |
| StochasticRSI | ADX < 30 필터 + 임계값 강화 (과매도 20→15, 과매수 80→85) |
| VWAP | ADX < 25 필터 + 임계값 1.0%→2.5% 강화 |

### 5.5 Phase S5 — 신호 확장 & Multi-Timeframe (완료)

- `StrategySignal`에 `suggestedStopLoss`, `suggestedTakeProfit` 필드 추가
- `MultiTimeframeFilter`: HTF BUY + LTF SELL (역추세) 시 HOLD, HTF 데이터 부족 시 LTF 신호 통과
- `CandleDownsampler.downsample(ltfCandles, factor)`: LTF → HTF OHLCV 집계
- `TimeframePreset.forStrategy(strategyName, timeframe)`: M1/M5/M15/M30/H1/H4/D1 × 7개 전략 파라미터 프리셋
- `BacktestEngine.run(config, candles, Strategy)` 오버로드 추가 (CompositeStrategy 직접 전달 가능)

---

## 6. 전략 파라미터 최적화 내역 (2026-03-15)

### 6.1 배경

이전 3년(2023~2025) 백테스트에서 STOCHASTIC_RSI(-96%), MACD(-82%), ORDERBOOK_IMBALANCE 등이 H1 기준 1,000+ 거래(오버트레이딩)로 폭락. ADX 필터 추가 및 파라미터 강화 후 재테스트.

### 6.2 전략별 변경 사항

| 전략 | 변경 내용 |
|------|-----------|
| MACD | ADX > 25 필터 추가, `getDouble()` 헬퍼 메서드 추가 (컴파일 에러 수정) |
| StochasticRSI | ADX < 30 필터, 임계값 강화 (과매도 20→15, 과매수 80→85) |
| EMA Cross | fast 9→20, slow 21→50, `getMinimumCandleCount()` 22→51 |
| VWAP | ADX < 25 필터, 임계값 1.0%→2.5% |
| Bollinger | ADX < 25 필터 (Squeeze 감지보다 먼저 적용) |
| RSI | 임계값 강화 (과매도 30→25, 과매수 70→60) |
| Orderbook | imbalanceThreshold 0.65→0.70, lookback 5→15 |

---

## 7. 인프라 구성

### 7.1 Docker Compose 서비스 (운영용)

```yaml
services:
  db:
    image: timescale/timescaledb:latest-pg15
    healthcheck: pg_isready (10s interval, 5s timeout, 5 retries)

  redis:
    image: redis:7-alpine
    healthcheck: redis-cli ping (10s interval, 3s timeout, 3 retries)

  backend:
    build: ./web-api
    depends_on:
      db: { condition: service_healthy }
      redis: { condition: service_healthy }
    ports: ["8080:8080"]

  frontend:
    build: ./crypto-trader-frontend
    ports: ["3000:3000"]
```

### 7.2 스케줄러 구성

| 스케줄러 | 주기 | 역할 |
|----------|------|------|
| MarketDataSyncService | 60초 fixedDelay | Upbit 최신 캔들 DB 동기화 |
| PaperTradingService | 60초 fixedDelay (초기 35초 지연) | 모의투자 전략 실행 |
| MarketRegimeAwareScheduler | 1시간 fixedDelay | Regime 감지 + 전략 자동 스위칭 |
| TelegramNotificationService | 12:00 / 00:00 KST cron | 일별 매매 요약 전송 |

### 7.3 텔레그램 알림 구조

| 알림 유형 | 트리거 | 방식 |
|----------|--------|------|
| 즉시 알림 | 세션 시작/종료/손절/거래소 장애 | 즉시 전송 |
| 일별 요약 | 12:00 KST, 00:00 KST | cron 스케줄 |
| 거래 없을 때 | 동일 시간 | "해당 시간대 매매 없음" 메시지 전송 |

> 주의: 서버 재시작 시 인메모리 버퍼(CopyOnWriteArrayList) 초기화 → 재시작 전 이벤트 유실

### 7.4 Redis 캐시 TTL 설정

| 키 | TTL |
|---|---|
| ticker (실시간 시세) | 1초 |
| candle (캔들 데이터) | 60초 |
| strategyConfig | 10분 |
| backtestResult | 30분 |

---

## 8. 백테스트 결과 요약 (2025 H1, H1 타임프레임)

### KRW-BTC

| 전략 | 수익률 | 승률 | MDD | 평가 |
|------|--------|------|-----|------|
| GRID | +8.42% | 57.14% | -11.99% | 안정 (추천) |
| BOLLINGER | +3.16% | 73.91% | -11.19% | 안정 (추천) |
| ORDERBOOK_IMBALANCE | +0.79% | 40.00% | -14.78% | 보합 |
| RSI | -8.57% | 41.03% | -14.87% | 소폭 손실 |
| VWAP | -7.39% | 0.00% | -7.39% | 거래 없음 |
| ATR_BREAKOUT | -29.75% | 27.87% | -29.89% | 손실 |
| SUPERTREND | -39.45% | 27.18% | -45.36% | 손실 |
| EMA_CROSS | -51.15% | 20.29% | -51.15% | 손실 |
| MACD | -58.81% | 23.35% | -58.81% | 손실 |
| STOCHASTIC_RSI | -70.36% | 34.64% | -71.34% | 구조적 결함 |

### KRW-ETH

| 전략 | 수익률 | 승률 | MDD | 평가 |
|------|--------|------|-----|------|
| ATR_BREAKOUT | +39.01% | 45.65% | -35.25% | ETH 전용 |
| ORDERBOOK_IMBALANCE | +30.55% | 27.27% | -20.71% | 안정 (추천) |
| EMA_CROSS | +23.73% | 25.76% | -30.14% | ETH 전용 |
| GRID | +1.38% | 75.00% | -29.98% | 안정 |
| SUPERTREND | -7.57% | 33.33% | -51.80% | MDD 심각 |
| VWAP | -27.05% | 50.00% | -32.95% | 손실 |
| RSI | -30.97% | 58.33% | -37.79% | 손실 |
| BOLLINGER | -36.97% | 60.00% | -38.21% | 손실 |
| MACD | -57.63% | 28.85% | -60.69% | 손실 |
| STOCHASTIC_RSI | -67.60% | 48.39% | -71.00% | 구조적 결함 |

---

## 9. 배포 가이드

### 9.1 사전 요구사항

- Ubuntu Server 22.04+ (또는 동급 Linux 서버)
- Docker 24.x + Docker Compose 2.x
- Git
- Upbit OpenAPI Key (실전매매 필요)
- Telegram Bot Token (알림 필요)

### 9.2 환경변수 목록 (.env)

| 변수명 | 설명 | 예시 |
|--------|------|------|
| `POSTGRES_DB` | DB 이름 | crypto_auto_trader |
| `POSTGRES_USER` | DB 사용자 | postgres |
| `POSTGRES_PASSWORD` | DB 비밀번호 | (강력한 비밀번호) |
| `REDIS_PASSWORD` | Redis 비밀번호 | (강력한 비밀번호) |
| `UPBIT_ACCESS_KEY` | Upbit API Access Key | (발급 후 입력) |
| `UPBIT_SECRET_KEY` | Upbit API Secret Key | (발급 후 입력) |
| `TELEGRAM_BOT_TOKEN` | 텔레그램 봇 토큰 | (BotFather에서 발급) |
| `TELEGRAM_CHAT_ID` | 텔레그램 채팅 ID | (봇 테스트 후 확인) |

### 9.3 개발 환경 실행

```bash
# 저장소 클론
git clone {repository}
cd crypto-auto-trader

# 환경변수 설정
cp .env.example .env
# .env 수정 (DB, Redis 비밀번호 설정)

# DB + Redis만 실행 (개발용)
docker compose up -d

# 백엔드 실행 (로컬)
cd web-api
./gradlew bootRun

# 프론트엔드 실행 (로컬)
cd crypto-trader-frontend
npm install
npm run dev

# 접속
# Frontend: http://localhost:3000
# Backend API: http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### 9.4 운영 환경 배포

```bash
# Ubuntu 서버에서
git clone {repository}
cd crypto-auto-trader

# 환경변수 설정
cp .env.example .env
nano .env  # 운영 값 입력 (UPBIT_ACCESS_KEY, UPBIT_SECRET_KEY 포함)

# 전체 서비스 빌드 및 실행
docker compose -f docker-compose.prod.yml up -d --build

# 상태 확인
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

### 9.5 운영 명령어

```bash
# 프론트엔드만 재빌드
docker compose -f docker-compose.prod.yml up -d --build frontend

# 백엔드만 재빌드
docker compose -f docker-compose.prod.yml up -d --build backend

# 로그 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f frontend

# DB 백업
docker exec crypto-db pg_dump -U postgres crypto_auto_trader > backup_$(date +%Y%m%d).sql

# DB 복구
cat backup_20260315.sql | docker exec -i crypto-db psql -U postgres crypto_auto_trader
```

---

## 10. 운영 가이드

### 10.1 헬스 체크

```bash
# Backend 상태
curl http://localhost:8080/api/v1/health

# 거래소 연결 상태
curl http://localhost:8080/api/v1/trading/health/exchange

# 컨테이너 상태
docker compose -f docker-compose.prod.yml ps
```

### 10.2 실전매매 시작 절차

1. 전략 설정 확인 (웹 UI `/strategies`)
2. 백테스팅으로 전략 사전 검증 (웹 UI `/backtest/new`)
3. 리스크 설정 확인 (웹 UI `/trading/risk`)
   - 일일 손실 한도, 최대 동시 포지션 수 설정
4. 세션 생성 (웹 UI `/trading` → 세션 생성)
   - 코인 쌍, 타임프레임, 초기 자본 설정
5. 세션 시작
6. 텔레그램 알림 수신 확인
7. 포지션/주문 모니터링 (웹 UI `/trading/{sessionId}`)

### 10.3 긴급 정지

```bash
# API 직접 호출 (서버 접근 시)
curl -X POST http://localhost:8080/api/v1/trading/emergency-stop

# 또는 웹 UI 상단 긴급 정지 버튼 사용
```

### 10.4 일반적인 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| Backend 시작 실패 | DB 컨테이너 미준비 | `docker compose restart backend` |
| 텔레그램 알림 미수신 | TELEGRAM_BOT_TOKEN 설정 오류 | `.env` 값 확인 후 재빌드 |
| Upbit API 429 Too Many Requests | Rate Limit 초과 | UpbitRestClient throttle() 자동 처리, 로그 확인 |
| 포지션 조회 시 데이터 없음 | UPBIT_ACCESS_KEY 미설정 | `.env`에 키 추가 후 backend 재빌드 |
| 백테스트 결과 저장 실패 | market_regime 길이 초과 | `ALTER TABLE backtest_trade ALTER COLUMN market_regime TYPE VARCHAR(20)` 실행 |

---

## 11. 알려진 이슈 및 미완 항목

### 11.1 P0 — 즉시 해결 필요

| 항목 | 설명 | 예상 작업 |
|------|------|----------|
| Spring Security 미구현 | SecurityConfig 미착수. 실전매매 API 현재 무방비 | 4~8h |
| 실거래 검증 미완 | 환경변수 설정 후 서버 재빌드만 하면 가동 가능 | 2h |

### 11.2 P1 — 단기 개선

| 항목 | 설명 | 예상 작업 |
|------|------|----------|
| STOCHASTIC_RSI 구조 재설계 | 2025 H1: BTC -70.4%, ETH -67.6% — 파라미터가 아닌 구조적 결함 | 4~8h |
| MACD 히스토그램 기울기 필터 | ADX 필터 후에도 -58%. 모멘텀 확인 추가 필요 | 2h |
| TradingController 예외 처리 통일 | ResponseStatusException 직접 사용 → 커스텀 예외 클래스 도입 | 3h |
| StrategyController DTO 전환 | Map<String, Object> 파라미터 → DTO + Bean Validation | 4h |
| 주간 리포트 API | GET /api/v1/reports/weekly 미구현 | 2h |

### 11.3 P2 — 중기 개선

| 항목 | 설명 |
|------|------|
| Redis Pub/Sub 이벤트 파이프라인 | EventPublisher/EventSubscriber 미구현. 현재 스케줄러 폴링 방식 |
| core-engine signal 패키지 | SignalEngine, TradingSignal 미구현 (LiveTradingService에 직접 통합) |
| core-engine position 패키지 | PositionManager, Position 미구현 (web-api PositionService로 대체) |
| Table/Modal 공통 컴포넌트 | components/ui/Table.tsx, Modal.tsx 미구현 (인라인 table 태그 사용) |
| Flyway V16 | backtest_trade.market_regime VARCHAR(20) 정합성 정정 |
| VWAP 임계값 재조정 | 2.5% → 1.5% 재테스트 (BTC 승률 0% 문제) |

---

## 12. 테스트 현황

### 12.1 단위 테스트

- 전체 테스트 수: 67개 (Phase S3 완료 기준)
- 주요 테스트 파일:
  - 전략별 단위 테스트 (EmaCrossStrategyTest, OrderbookImbalanceStrategyTest 등)
  - MarketRegimeFilter 13개 테스트
  - ConflictingSignalTest (상충 신호 2개 케이스)
  - RiskEngine Fixed Fractional 계산, Correlation-adjusted slot 검증

### 12.2 통합 테스트

- BacktestControllerIntegrationTest: 에러코드 BAD_REQUEST/CONFLICT 검증

---

## 부록

### A. 핵심 설계 결정

| 결정 | 사유 |
|------|------|
| Gradle 멀티모듈 | exchange-adapter 교체만으로 타 거래소 연동 가능하도록 의존성 격리 |
| TimescaleDB | 수억 건 분봉 데이터를 hypertable로 고성능 처리, 압축 정책으로 디스크 절감 |
| paper_trading 스키마 분리 | 모의투자 데이터가 실전 포지션/주문 테이블을 오염시키는 사고 원천 차단 |
| @Transactional 제거 (BacktestService) | 단일 트랜잭션에서 한 전략 실패 시 PostgreSQL "transaction aborted"로 전체 실패하는 문제 수정 |
| char[] 기반 JWT 서명 (UpbitOrderClient) | String은 GC 전까지 메모리에 잔류 → API Key 평문 노출 방지 |
| docker-compose healthcheck | DB/Redis 준비 전 backend 기동 시 Flyway 실패 방지 |

### B. 참고 문서

| 문서 | 위치 | 내용 |
|------|------|------|
| 개발 현황 | `docs/PROGRESS.md` | 최신 작업 이력 및 다음 할 일 |
| 설계서 | `docs/DESIGN.md` | API, DB 스키마, UI 설계 (v1.3) |
| 계획서 | `docs/PLAN.md` | Phase별 개발 계획 |
| 검증 결과 | `docs/CHECK_RESULT.md` | 설계-구현 갭 분석 (v4.0) |
| 전략 분석 | `docs/strategy_analysis_v3.md` | 10개 전략 상세 분석 + 개선 로드맵 |
| 백테스트 결과 | `docs/BACKTEST_RESULTS.md` | 2025 H1 BTC/ETH 결과 |
| 경영진 보고 | `docs/REPORT_EXECUTIVE.md` | 비기술 요약 |

---

작성: Report 에이전트
작성일: 2026-03-15
기준 문서: DESIGN.md v1.3, CHECK_RESULT.md v4.0, PROGRESS.md (2026-03-15)
