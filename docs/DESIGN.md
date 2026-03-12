# CryptoAutoTrader - 기술 설계서

## 문서 정보
- 버전: 1.2
- 작성일: 2026-03-05
- 최종 수정: 2026-03-08 (설계-구현 불일치 수정: metrics 엔드포인트 통합, Phase 1 Config 클래스 명세 정정)
- 기반 문서: PLAN.md, IDEA.md
- 다음 단계: Do-Backend + Do-Frontend (병렬)

---

## 1. 시스템 아키텍처

### 1.1 전체 구조

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Client (Web Browser)                        │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ HTTPS
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  Next.js Frontend (crypto-trader-frontend)                     │
│            React 18 + TypeScript + Tailwind CSS (Dark Mode)          │
│                           Port: 3000                                 │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ REST API
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    Spring Boot Backend (web-api)                      │
│                        Java 17 + JPA                                 │
│                          Port: 8080                                  │
├──────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────────┐  │
│  │ core-engine │  │ strategy-lib │  │    exchange-adapter        │  │
│  │             │  │              │  │                            │  │
│  │ Backtest    │  │ VWAP         │  │ Upbit REST Client          │  │
│  │ WalkForward │  │ EMA Cross    │  │ Upbit WebSocket Client     │  │
│  │ Metrics     │  │ Bollinger    │  │ Order Execution Engine     │  │
│  │ Regime      │  │ Grid + 6종   │  │ Position Manager           │  │
│  │ Risk Engine │  │              │  │ Exchange Health Monitor    │  │
│  └──────┬──────┘  └──────┬───────┘  └──────────┬─────────────────┘  │
│         │                │                      │                    │
│         └────────────────┼──────────────────────┘                    │
│                          │                                           │
│                    Event Bus (Redis Pub/Sub)                          │
└──────────────────────────┬───────────────────────────────────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
┌──────────────────┐ ┌──────────┐ ┌──────────────────┐
│  PostgreSQL 15   │ │  Redis 7 │ │  Telegram Bot    │
│  + TimescaleDB   │ │          │ │  API             │
│  Port: 5432      │ │ Port:6379│ │                  │
└──────────────────┘ └──────────┘ └──────────────────┘
```

### 1.2 이벤트 기반 파이프라인 (Phase 4 실전 매매)

```
Upbit WebSocket ─→ Market Data Service ─→ Redis Cache + Event Publish
                                                    │
                                                    ▼
                                            Strategy Engine
                                          (전략별 신호 생성)
                                                    │
                                                    ▼
                                            Signal Engine
                                          (신호 집계/결정)
                                                    │
                                                    ▼
                                          Portfolio Manager
                                    (전체 자산/전략 충돌 관리)
                                                    │
                                                    ▼
                                             Risk Engine
                                       (리스크 체크 → 승인/거부)
                                                    │
                                                    ▼
                                       Order Execution Engine
                                      (상태 머신 → Upbit API)
                                                    │
                                                    ▼
                                          Position Manager
                                     (포지션 업데이트, PnL 계산)
```

### 1.3 기술 스택 상세

| 계층 | 기술 | 버전 | 비고 |
|------|------|------|------|
| Frontend | Next.js | 14.x | App Router |
| Frontend | React | 18.x | |
| Frontend | TypeScript | 5.x | strict mode |
| Frontend | Tailwind CSS | 3.x | 다크 모드 기본 |
| Frontend | Lightweight Charts | 4.x | 캔들 차트 / 수익률 차트 |
| Frontend | ApexCharts | 3.x | 히트맵 / 비교 차트 |
| Frontend | Zustand | 4.x | UI 상태 관리 (사이드바, 테마 등) |
| Frontend | TanStack Query | 5.x | 서버 상태 + 대용량 데이터 캐싱 |
| Backend | Spring Boot | 3.2.x | |
| Backend | Java | 17 | LTS |
| Backend | Gradle | 8.x | 멀티 모듈 |
| Backend | Spring Data JPA | - | |
| Backend | Spring WebSocket | - | Upbit 실시간 시세 |
| Database | PostgreSQL | 15.x | + TimescaleDB 확장 |
| Database | Flyway | 9.x | 마이그레이션 |
| Cache | Redis | 7.x | Pub/Sub + 캐싱 |
| Messaging | Telegram Bot API | - | 알림 |

---

## 2. Gradle 멀티 모듈 설계

### 2.1 모듈 구조 및 의존성

```
crypto-auto-trader/
├── settings.gradle          # 멀티 모듈 루트
├── build.gradle             # 공통 설정
├── core-engine/             # 백테스팅 엔진, 성과 지표, 리스크
│   └── build.gradle
├── strategy-lib/            # 전략 인터페이스 + 구현체
│   └── build.gradle
├── exchange-adapter/        # Upbit 연동, 주문 엔진
│   └── build.gradle
├── web-api/                 # REST API, Spring Boot 메인
│   └── build.gradle
└── crypto-trader-frontend/           # Next.js (Gradle 외 별도)
    └── package.json
```

### 2.2 모듈 의존성 방향

```
web-api ──→ core-engine ──→ strategy-lib
   │              │
   └──→ exchange-adapter
              │
              └──→ core-engine (공통 도메인 모델)
```

- `strategy-lib`: 의존성 없음 (순수 전략 로직)
- `core-engine`: `strategy-lib`에만 의존
- `exchange-adapter`: `core-engine`에 의존 (도메인 모델 공유)
- `web-api`: 모든 모듈에 의존 (조립 역할)

### 2.3 모듈별 패키지 구조

```
# core-engine
com.cryptoautotrader.core
├── backtest/               # 백테스팅 실행기
│   ├── BacktestEngine.java
│   ├── BacktestConfig.java
│   ├── BacktestResult.java
│   └── WalkForwardTestRunner.java
├── metrics/                # 성과 지표
│   ├── MetricsCalculator.java
│   └── PerformanceReport.java
├── regime/                 # 시장 상태 필터
│   ├── MarketRegimeDetector.java
│   └── MarketRegime.java   # enum: TREND, RANGE, VOLATILE
├── risk/                   # 리스크 관리
│   ├── RiskEngine.java
│   ├── RiskConfig.java
│   └── RiskCheckResult.java
├── signal/                 # 신호 엔진
│   ├── SignalEngine.java
│   └── TradingSignal.java
├── portfolio/              # 포트폴리오 관리
│   └── PortfolioManager.java  # 전체 자산 관리, 전략 간 충돌 방지
├── position/               # 포지션 관리
│   ├── PositionManager.java
│   └── Position.java
└── model/                  # 공통 도메인 모델
    ├── Candle.java
    ├── CoinPair.java
    ├── OrderSide.java      # enum: BUY, SELL
    ├── TimeFrame.java      # enum: MINUTE_1, MINUTE_5, HOUR_1, DAY_1
    └── TradeRecord.java

# strategy-lib
com.cryptoautotrader.strategy
├── Strategy.java           # 전략 인터페이스
├── StrategyConfig.java     # 전략 파라미터 기본 클래스
├── StrategySignal.java     # 전략 신호 (BUY/SELL/HOLD + 강도)
├── vwap/
│   └── VwapStrategy.java      # StrategyConfig.getParams() Map으로 파라미터 관리
├── ema/
│   └── EmaCrossStrategy.java   # 동일
├── bollinger/
│   └── BollingerStrategy.java  # 동일
├── grid/
│   └── GridStrategy.java       # 동일
└── ... (추가 6종)

# exchange-adapter
com.cryptoautotrader.exchange
├── upbit/
│   ├── UpbitRestClient.java
│   ├── UpbitWebSocketClient.java
│   ├── UpbitCandleCollector.java   # 과거 데이터 수집
│   └── dto/                        # Upbit API 응답 DTO
├── order/
│   ├── OrderExecutionEngine.java
│   ├── OrderState.java             # enum: PENDING, SUBMITTED, ...
│   └── OrderStateMachine.java
├── health/
│   └── ExchangeHealthMonitor.java
└── adapter/
    └── ExchangeAdapter.java        # 거래소 추상화 인터페이스
    # 확장 규칙: 타 거래소 추가 시 upbit/ 패키지와 동일 구조로 binance/ 등 생성,
    # ExchangeAdapter 구현체만 추가. core-engine은 ExchangeAdapter에만 의존하므로 변경 없음.

# web-api
com.cryptoautotrader.api
├── CryptoAutoTraderApplication.java
├── config/
│   ├── RedisConfig.java
│   ├── WebConfig.java
│   ├── SchedulerConfig.java
│   ├── AsyncConfig.java            # 스레드 풀 분리: 시세 수신 / 주문 실행 / 일반 작업
│   └── SecurityConfig.java         # API Key 암호화 설정
├── controller/
│   ├── BacktestController.java
│   ├── StrategyController.java
│   ├── DataController.java
│   ├── PaperTradingController.java
│   ├── LogController.java
│   ├── TradeController.java          # Phase 4
│   ├── RiskController.java           # Phase 4
│   └── SystemController.java
├── service/
│   ├── BacktestService.java
│   ├── DataCollectionService.java
│   ├── TelegramNotificationService.java
│   └── ScheduledTasks.java         # 일일/주간 리포트 스케줄러
└── event/
    ├── EventPublisher.java
    └── EventSubscriber.java
```

---

## 3. 데이터베이스 설계

### 3.1 ERD

```
┌────────────────────┐     ┌────────────────────┐     ┌──────────────────────┐
│   candle_data      │     │  backtest_run       │     │  backtest_trade      │
│   (TimescaleDB)    │     │                     │     │                      │
├────────────────────┤     ├────────────────────┤     ├──────────────────────┤
│ time (PK)          │     │ id (PK)             │──┐  │ id (PK)              │
│ coin_pair          │     │ strategy_name       │  │  │ backtest_run_id (FK) │
│ timeframe          │     │ coin_pair           │  └─>│ side (BUY/SELL)      │
│ open               │     │ timeframe           │     │ price                │
│ high               │     │ start_date          │     │ quantity             │
│ low                │     │ end_date            │     │ fee                  │
│ close              │     │ config_json         │     │ slippage             │
│ volume             │     │ created_at          │     │ signal_reason        │
└────────────────────┘     └────────────────────┘     │ executed_at          │
                                    │                  └──────────────────────┘
                                    │
                                    ▼
                           ┌────────────────────┐
                           │ backtest_metrics    │
                           ├────────────────────┤
                           │ id (PK)             │
                           │ backtest_run_id (FK)│
                           │ total_return        │
                           │ win_rate            │
                           │ mdd                 │
                           │ sharpe_ratio        │
                           │ sortino_ratio       │
                           │ calmar_ratio        │
                           │ win_loss_ratio      │
                           │ recovery_factor     │
                           │ total_trades        │
                           │ monthly_returns_json│
                           └────────────────────┘

┌────────────────────┐     ┌────────────────────┐     ┌──────────────────────┐
│ strategy_config    │     │     position        │     │      order           │
├────────────────────┤     ├────────────────────┤     ├──────────────────────┤
│ id (PK)            │     │ id (PK)             │     │ id (PK)              │
│ name               │     │ coin_pair           │     │ position_id (FK)     │
│ strategy_type      │     │ side                │     │ coin_pair            │
│ coin_pair          │     │ entry_price         │     │ side                 │
│ timeframe          │     │ avg_price           │     │ order_type           │
│ config_json        │     │ size                │     │ price                │
│ is_active          │     │ unrealized_pnl      │     │ quantity             │
│ max_investment     │     │ realized_pnl        │     │ state                │
│ stop_loss_pct      │     │ strategy_config_id  │     │ exchange_order_id    │
│ reinvest_pct       │     │ opened_at           │     │ filled_quantity      │
│ created_at         │     │ closed_at           │     │ signal_reason        │
│ updated_at         │     │ status              │     │ created_at           │
└────────────────────┘     └────────────────────┘     │ submitted_at         │
                                                       │ filled_at            │
                                                       │ cancelled_at         │
                                                       │ failed_reason        │
                                                       └──────────────────────┘

┌────────────────────┐     ┌────────────────────┐     ┌──────────────────────┐
│ risk_config        │     │ strategy_log        │     │ strategy_signal      │
├────────────────────┤     ├────────────────────┤     ├──────────────────────┤
│ id (PK)            │     │ id (PK)             │     │ id (PK)              │
│ max_daily_loss     │     │ strategy_name       │     │ strategy_config_id(FK)│
│ max_weekly_loss    │     │ coin_pair           │     │ coin_pair            │
│ max_monthly_loss   │     │ signal              │     │ timeframe            │
│ max_positions      │     │ reason              │     │ signal               │
│ cooldown_minutes   │     │ indicators_json     │     │ strength             │
│ portfolio_limit    │     │ market_regime       │     │ reason               │
│ updated_at         │     │ created_at          │     │ indicators_json      │
└────────────────────┘     └────────────────────┘     │ market_regime        │
                                                       │ candle_time          │
┌────────────────────┐                                 │ was_executed         │
│ trade_log          │                                 │ created_at           │
├────────────────────┤                                 └──────────────────────┘
│ id (PK)            │
│ order_id (FK)      │
│ event_type         │
│ old_state          │
│ new_state          │
│ detail_json        │
│ created_at         │
└────────────────────┘
```

### 3.2 테이블 스키마

#### candle_data (TimescaleDB hypertable)
```sql
CREATE TABLE candle_data (
    time        TIMESTAMPTZ NOT NULL,
    coin_pair   VARCHAR(20) NOT NULL,    -- 'KRW-BTC', 'KRW-ETH'
    timeframe   VARCHAR(10) NOT NULL,    -- 'M1', 'M5', 'H1', 'D1'
    open        NUMERIC(20,8) NOT NULL,
    high        NUMERIC(20,8) NOT NULL,
    low         NUMERIC(20,8) NOT NULL,
    close       NUMERIC(20,8) NOT NULL,
    volume      NUMERIC(20,8) NOT NULL
);

SELECT create_hypertable('candle_data', 'time');

-- 동일 캔들 중복 삽입 방지 (UPSERT용)
CREATE UNIQUE INDEX idx_candle_unique ON candle_data (coin_pair, timeframe, time);

-- 복합 인덱스: 코인+타임프레임+시간 조회 최적화
CREATE INDEX idx_candle_lookup ON candle_data (coin_pair, timeframe, time DESC);

-- 90일 이상 된 분봉 데이터 압축
ALTER TABLE candle_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'coin_pair, timeframe'
);
SELECT add_compression_policy('candle_data', INTERVAL '90 days');
```

#### backtest_run
```sql
CREATE TABLE backtest_run (
    id              BIGSERIAL PRIMARY KEY,
    strategy_name   VARCHAR(50) NOT NULL,
    coin_pair       VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL,
    start_date      TIMESTAMPTZ NOT NULL,
    end_date        TIMESTAMPTZ NOT NULL,
    initial_capital NUMERIC(20,2) NOT NULL DEFAULT 10000000, -- 초기 자본금 (KRW)
    slippage_pct    NUMERIC(5,3) DEFAULT 0.1,
    fee_pct         NUMERIC(5,3) DEFAULT 0.05,
    config_json     JSONB NOT NULL,            -- 전략 파라미터
    fill_simulation_json JSONB,               -- Fill Simulation 설정 (7단원)
    is_walk_forward BOOLEAN DEFAULT FALSE,
    wf_in_sample    TIMESTAMPTZ,               -- Walk Forward 학습 구간 끝
    wf_out_sample   TIMESTAMPTZ,               -- Walk Forward 검증 구간 끝
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
```

#### backtest_metrics
```sql
CREATE TABLE backtest_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    backtest_run_id     BIGINT NOT NULL REFERENCES backtest_run(id),
    total_return_pct    NUMERIC(10,4),
    win_rate_pct        NUMERIC(5,2),
    mdd_pct             NUMERIC(10,4),
    sharpe_ratio        NUMERIC(8,4),
    sortino_ratio       NUMERIC(8,4),
    calmar_ratio        NUMERIC(8,4),
    win_loss_ratio      NUMERIC(8,4),
    recovery_factor     NUMERIC(8,4),
    total_trades        INTEGER,
    winning_trades      INTEGER,
    losing_trades       INTEGER,
    avg_profit_pct      NUMERIC(8,4),
    avg_loss_pct        NUMERIC(8,4),
    max_consecutive_loss INTEGER,
    monthly_returns_json JSONB,       -- {"2023-01": 2.3, "2023-02": -1.1, ...}
    segment             VARCHAR(20) DEFAULT 'FULL'  -- 'FULL', 'IN_SAMPLE', 'OUT_SAMPLE'
);

CREATE INDEX idx_metrics_run ON backtest_metrics(backtest_run_id);
```

#### backtest_trade
```sql
CREATE TABLE backtest_trade (
    id              BIGSERIAL PRIMARY KEY,
    backtest_run_id BIGINT NOT NULL REFERENCES backtest_run(id),
    side            VARCHAR(4) NOT NULL,        -- 'BUY', 'SELL'
    price           NUMERIC(20,8) NOT NULL,
    quantity        NUMERIC(20,8) NOT NULL,
    fee             NUMERIC(20,8),
    slippage        NUMERIC(20,8),
    pnl             NUMERIC(20,8),
    cumulative_pnl  NUMERIC(20,8),
    signal_reason   TEXT,                        -- 전략 로그: 왜 이 신호를 냈는지
    market_regime   VARCHAR(10),                 -- 'TREND', 'RANGE'
    executed_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_bt_trade_run ON backtest_trade(backtest_run_id, executed_at);
```

#### strategy_config
```sql
CREATE TABLE strategy_config (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    strategy_type   VARCHAR(50) NOT NULL,       -- 'VWAP', 'EMA_CROSS', ...
    coin_pair       VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL,
    config_json     JSONB NOT NULL,
    is_active       BOOLEAN DEFAULT TRUE,
    max_investment  NUMERIC(20,2),              -- 최대 투자금 (KRW)
    stop_loss_pct   NUMERIC(5,2),               -- 손절 퍼센트
    reinvest_pct    NUMERIC(5,2) DEFAULT 0,     -- 재투자 비율
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
```

#### position
```sql
CREATE TABLE position (
    id                  BIGSERIAL PRIMARY KEY,
    coin_pair           VARCHAR(20) NOT NULL,
    side                VARCHAR(4) NOT NULL,
    entry_price         NUMERIC(20,8) NOT NULL,
    avg_price           NUMERIC(20,8) NOT NULL,
    size                NUMERIC(20,8) NOT NULL,
    unrealized_pnl      NUMERIC(20,8) DEFAULT 0,
    realized_pnl        NUMERIC(20,8) DEFAULT 0,
    strategy_config_id  BIGINT REFERENCES strategy_config(id),
    status              VARCHAR(10) DEFAULT 'OPEN',  -- 'OPEN', 'CLOSED'
    opened_at           TIMESTAMPTZ DEFAULT NOW(),
    closed_at           TIMESTAMPTZ
);

CREATE INDEX idx_position_open ON position(status, coin_pair) WHERE status = 'OPEN';
```

#### order
```sql
CREATE TABLE "order" (
    id                  BIGSERIAL PRIMARY KEY,
    position_id         BIGINT REFERENCES position(id),
    coin_pair           VARCHAR(20) NOT NULL,
    side                VARCHAR(4) NOT NULL,       -- 'BUY', 'SELL'
    order_type          VARCHAR(10) NOT NULL,      -- 'LIMIT', 'MARKET'
    price               NUMERIC(20,8),
    quantity            NUMERIC(20,8) NOT NULL,
    state               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    exchange_order_id   VARCHAR(100),              -- Upbit 주문 UUID
    filled_quantity     NUMERIC(20,8) DEFAULT 0,
    signal_reason       TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    submitted_at        TIMESTAMPTZ,
    filled_at           TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    failed_reason       TEXT
);

CREATE INDEX idx_order_state ON "order"(state) WHERE state IN ('PENDING', 'SUBMITTED', 'PARTIAL_FILLED');
CREATE INDEX idx_order_position ON "order"(position_id);
```

#### risk_config
```sql
CREATE TABLE risk_config (
    id                  BIGSERIAL PRIMARY KEY,
    max_daily_loss_pct  NUMERIC(5,2) DEFAULT 3.0,
    max_weekly_loss_pct NUMERIC(5,2) DEFAULT 7.0,
    max_monthly_loss_pct NUMERIC(5,2) DEFAULT 15.0,
    max_positions       INTEGER DEFAULT 3,
    cooldown_minutes    INTEGER DEFAULT 60,
    portfolio_limit_krw NUMERIC(20,2),
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);
```

#### strategy_log, trade_log
```sql
CREATE TABLE strategy_log (
    id              BIGSERIAL PRIMARY KEY,
    strategy_name   VARCHAR(50) NOT NULL,
    coin_pair       VARCHAR(20) NOT NULL,
    signal          VARCHAR(10),                -- 'BUY', 'SELL', 'HOLD'
    reason          TEXT NOT NULL,              -- 왜 이 신호를 냈는가
    indicators_json JSONB,                      -- 지표 스냅샷
    market_regime   VARCHAR(10),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE trade_log (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT REFERENCES "order"(id),
    event_type      VARCHAR(30) NOT NULL,       -- 'STATE_CHANGE', 'FILL', 'TIMEOUT'
    old_state       VARCHAR(20),
    new_state       VARCHAR(20),
    detail_json     JSONB,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- 로그는 시간 기반 조회가 많으므로 인덱스
CREATE INDEX idx_strategy_log_time ON strategy_log(created_at DESC);
CREATE INDEX idx_trade_log_order ON trade_log(order_id);
```

#### Paper Trading 스키마 (멀티세션 — `paper_trading` 스키마)

> **구현**: `paper_trading` 별도 스키마를 사용하며, `position`/`order` 테이블에
> `session_id` FK를 추가하여 멀티세션을 지원합니다.
> 최대 5개 동시 세션, 세션별 독립 잔고/포지션/주문 관리.

```sql
CREATE SCHEMA paper_trading;

-- 실전과 동일한 구조를 paper_trading 스키마에 복제 (V8)
CREATE TABLE paper_trading.position (LIKE public.position INCLUDING ALL);
CREATE TABLE paper_trading."order" (LIKE public."order" INCLUDING ALL);
CREATE TABLE paper_trading.strategy_log (LIKE public.strategy_log INCLUDING ALL);
CREATE TABLE paper_trading.trade_log (LIKE public.trade_log INCLUDING ALL);

-- 가상 잔고 (V8 + V9 확장)
CREATE TABLE paper_trading.virtual_balance (
    id              BIGSERIAL PRIMARY KEY,
    total_krw       NUMERIC(20,2) NOT NULL,
    available_krw   NUMERIC(20,2) NOT NULL,
    initial_capital NUMERIC(20,2),
    strategy_name   VARCHAR(50),
    coin_pair       VARCHAR(20),
    timeframe       VARCHAR(10),
    status          VARCHAR(10) NOT NULL DEFAULT 'STOPPED',  -- 'RUNNING', 'STOPPED'
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- 다중 세션 지원: position/order에 session_id 추가 (V10)
ALTER TABLE paper_trading.position ADD COLUMN session_id BIGINT REFERENCES paper_trading.virtual_balance(id);
ALTER TABLE paper_trading."order" ADD COLUMN session_id BIGINT REFERENCES paper_trading.virtual_balance(id);
```

### 3.4 Redis 키(Key) 설계

```
# 실시간 시세 캐싱 (Upbit API Rate Limit 방어)
cache:upbit:ticker:{coin}              TTL 1초     # 실시간 시세
cache:upbit:orderbook:{coin}           TTL 1초     # 호가창
cache:upbit:candle:{coin}:{timeframe}  TTL 60초    # 최근 캔들

# Rate Limit 제어
ratelimit:upbit:api                    TTL 1초     # 초당 API 호출 횟수 카운터
ratelimit:upbit:order                  TTL 1초     # 초당 주문 호출 제한

# Event Bus (Pub/Sub 채널)
event:market:ticker                                # 시세 업데이트 이벤트
event:signal:generated                             # 전략 신호 생성
event:order:status                                 # 주문 상태 변경
event:risk:alert                                   # 리스크 알림

# 시스템 상태
system:trading:active                              # 자동매매 ON/OFF 플래그
system:health:exchange                             # 거래소 상태
```

### 3.5 strategy_signal 테이블

```sql
-- 전략 신호 이력: 왜 매매했는지 분석 가능
CREATE TABLE strategy_signal (
    id              BIGSERIAL PRIMARY KEY,
    strategy_config_id BIGINT REFERENCES strategy_config(id),
    coin_pair       VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL,
    signal          VARCHAR(10) NOT NULL,        -- 'BUY', 'SELL', 'HOLD'
    strength        NUMERIC(5,2),                -- 신호 강도 (0~100)
    reason          TEXT NOT NULL,               -- 왜 이 신호를 냈는가
    indicators_json JSONB,                       -- 지표 스냅샷 (EMA값, RSI값 등)
    market_regime   VARCHAR(10),                 -- 'TREND', 'RANGE', 'VOLATILE'
    candle_time     TIMESTAMPTZ NOT NULL,        -- 어떤 캔들에서 발생했는지
    was_executed    BOOLEAN DEFAULT FALSE,       -- 실제 주문으로 이어졌는지
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_signal_lookup ON strategy_signal(coin_pair, created_at DESC);
CREATE INDEX idx_signal_strategy ON strategy_signal(strategy_config_id, created_at DESC);
```

### 3.6 인덱스 전략 요약

| 테이블 | 인덱스 | 타입 | 사유 |
|--------|--------|------|------|
| candle_data | (coin_pair, timeframe, time DESC) | B-tree | 백테스팅 시 코인별 캔들 조회 |
| backtest_trade | (backtest_run_id, executed_at) | B-tree | 매매 기록 시간순 조회 |
| position | (status, coin_pair) WHERE OPEN | Partial | 활성 포지션만 빠르게 조회 |
| order | (state) WHERE active | Partial | 진행 중 주문만 조회 |
| strategy_log | (created_at DESC) | B-tree | 최근 전략 로그 조회 |
| strategy_signal | (coin_pair, created_at DESC) | B-tree | 신호 이력 조회 |
| strategy_signal | (strategy_config_id, created_at DESC) | B-tree | 전략별 신호 조회 |

---

## 4. API 설계

### 4.1 API 개요
- Base URL: `/api/v1`
- 인증: 불필요 (1인 전용 시스템, 로컬/VPN 접근)
- Content-Type: `application/json`

### 4.2 공통 응답 형식
```json
// 성공
{
  "success": true,
  "data": { ... }
}

// 실패
{
  "success": false,
  "error": {
    "code": "BACKTEST_001",
    "message": "데이터가 부족합니다. 최소 30일 이상의 캔들 데이터가 필요합니다."
  }
}
```

### 4.3 에러 코드 체계

| 코드 | HTTP | 설명 |
|------|------|------|
| DATA_001 | 404 | 캔들 데이터 없음 |
| DATA_002 | 400 | 데이터 기간 부족 |
| BACKTEST_001 | 400 | 백테스팅 파라미터 오류 |
| BACKTEST_002 | 500 | 백테스팅 실행 실패 |
| STRATEGY_001 | 404 | 전략 설정 없음 |
| STRATEGY_002 | 400 | 잘못된 전략 파라미터 |
| ORDER_001 | 400 | 주문 검증 실패 |
| ORDER_002 | 500 | 거래소 API 오류 |
| RISK_001 | 403 | 리스크 한도 초과 |
| EXCHANGE_001 | 503 | 거래소 장애 감지 |

### 4.4 API 엔드포인트

#### 데이터 수집 API
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/data/collect` | 과거 데이터 수집 시작 |
| GET | `/api/v1/data/status` | 수집 상태 조회 |
| GET | `/api/v1/data/coins` | 지원 코인 목록 (거래량 상위 20) |
| GET | `/api/v1/data/candles` | 캔들 데이터 조회 |
| GET | `/api/v1/data/summary` | 수집된 데이터 요약 (코인별 기간/건수) |

##### POST /api/v1/data/collect
```json
// Request
{
  "coinPair": "KRW-BTC",
  "timeframe": "H1",
  "startDate": "2023-01-01",
  "endDate": "2026-01-01"
}

// Response 202
{
  "success": true,
  "data": {
    "jobId": "collect-001",
    "status": "STARTED",
    "estimatedMinutes": 15
  }
}
```

#### 백테스팅 API
| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/v1/backtest/run` | 백테스팅 실행 |
| POST | `/api/v1/backtest/walk-forward` | Walk Forward Test 실행 |
| GET | `/api/v1/backtest/{id}` | 백테스팅 결과 조회 (metrics 포함) |
| GET | `/api/v1/backtest/{id}/trades` | 매매 기록 조회 |
| GET | `/api/v1/backtest/compare` | 전략 비교 |
| GET | `/api/v1/backtest/list` | 백테스팅 이력 목록 |

##### POST /api/v1/backtest/run
```json
// Request
{
  "strategyType": "EMA_CROSS",
  "coinPair": "KRW-BTC",
  "timeframe": "H1",
  "startDate": "2023-01-01",
  "endDate": "2025-12-31",
  "slippagePct": 0.1,
  "feePct": 0.05,
  "config": {
    "fastPeriod": 9,
    "slowPeriod": 21
  }
}

// Response 200
{
  "success": true,
  "data": {
    "id": 42,
    "status": "COMPLETED",
    "metrics": {
      "totalReturnPct": 23.45,
      "winRatePct": 58.3,
      "mddPct": -12.7,
      "sharpeRatio": 1.42,
      "sortinoRatio": 1.89,
      "calmarRatio": 1.85,
      "winLossRatio": 1.62,
      "recoveryFactor": 1.85,
      "totalTrades": 156,
      "monthlyReturns": {"2023-01": 2.3, "2023-02": -1.1}
    }
  }
}
```

##### POST /api/v1/backtest/walk-forward
```json
// Request
{
  "strategyType": "VWAP",
  "coinPair": "KRW-BTC",
  "timeframe": "H1",
  "startDate": "2023-01-01",
  "endDate": "2025-12-31",
  "inSampleRatio": 0.7,
  "windowCount": 3,
  "config": { "thresholdPct": 1.0 }
}

// Response 200
{
  "success": true,
  "data": {
    "windows": [
      {
        "inSample": {"start": "2023-01", "end": "2024-02", "returnPct": 18.2},
        "outSample": {"start": "2024-03", "end": "2024-08", "returnPct": 12.1}
      },
      ...
    ],
    "overfittingScore": 0.33,
    "verdict": "ACCEPTABLE"
  }
}
```

##### GET /api/v1/backtest/compare?ids=42,43,44
```json
// Response 200
{
  "success": true,
  "data": {
    "runs": [
      {"id": 42, "strategyType": "EMA_CROSS", "totalReturnPct": 23.45, "mddPct": -12.7, ...},
      {"id": 43, "strategyType": "VWAP", "totalReturnPct": 18.90, "mddPct": -8.3, ...},
      {"id": 44, "strategyType": "GRID", "totalReturnPct": 15.20, "mddPct": -5.1, ...}
    ]
  }
}
```

#### 전략 관리 API
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/strategies` | 전략 설정 목록 |
| GET | `/api/v1/strategies/{id}` | 전략 설정 상세 |
| POST | `/api/v1/strategies` | 전략 설정 생성 |
| PUT | `/api/v1/strategies/{id}` | 전략 설정 수정 |
| PATCH | `/api/v1/strategies/{id}/toggle` | 전략 활성/비활성 |
| GET | `/api/v1/strategies/types` | 지원 전략 타입 목록 + 기본 파라미터 |

#### 모의투자 (Paper Trading) API — Phase 3.5
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/paper-trading/sessions` | 세션 목록 조회 |
| POST | `/api/v1/paper-trading/sessions` | 새 모의투자 세션 시작 |
| GET | `/api/v1/paper-trading/sessions/{id}` | 세션 잔고 조회 |
| GET | `/api/v1/paper-trading/sessions/{id}/positions` | 세션 포지션 조회 |
| GET | `/api/v1/paper-trading/sessions/{id}/orders` | 세션 주문 내역 (페이징) |
| POST | `/api/v1/paper-trading/sessions/{id}/stop` | 세션 중단 |
| GET | `/api/v1/paper-trading/sessions/{id}/chart` | 세션 가격 차트 (캔들 + 매매 시점) |

##### POST /api/v1/paper-trading/sessions
```json
// Request
{
  "strategyName": "EMA_CROSS",
  "coinPair": "KRW-BTC",
  "timeframe": "H1",
  "initialCapital": 10000000
}

// Response 200
{
  "success": true,
  "data": {
    "id": 1,
    "strategyName": "EMA_CROSS",
    "coinPair": "KRW-BTC",
    "timeframe": "H1",
    "status": "RUNNING",
    "totalAssetKrw": 10000000,
    "availableKrw": 10000000,
    "initialCapital": 10000000,
    "totalReturnPct": 0,
    "startedAt": "2026-03-07T12:00:00Z"
  }
}
```

##### GET /api/v1/paper-trading/sessions/{id}/chart
```json
// Response 200
{
  "success": true,
  "data": {
    "candles": [
      {"time": 1709827200000, "open": 85000000, "high": 86000000, "low": 84500000, "close": 85500000, "volume": 12.5}
    ],
    "orders": [
      {"id": 1, "side": "BUY", "price": 85000000, "quantity": 0.001, "fee": 42500, "signalReason": "EMA 골든크로스", "filledAt": "2026-03-07T12:05:00Z"}
    ]
  }
}
```

#### 로그 API
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/logs/strategy` | 전략 분석 로그 (페이징: page, size) |

##### GET /api/v1/logs/strategy?page=0&size=50
```json
// Response 200
{
  "success": true,
  "data": {
    "content": [
      {"id": 1, "strategyName": "EMA_CROSS", "coinPair": "KRW-BTC", "signal": "BUY", "reason": "...", "indicatorsJson": "{}", "marketRegime": "TREND", "createdAt": "2026-03-07T12:00:00Z"}
    ],
    "totalElements": 150,
    "totalPages": 3,
    "number": 0
  }
}
```

#### 운영 제어 API (Phase 4)
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/trading/status` | 자동매매 상태 조회 |
| POST | `/api/v1/trading/start` | 자동매매 시작 |
| POST | `/api/v1/trading/stop` | 자동매매 전체 정지 |
| POST | `/api/v1/trading/stop/{coinPair}` | 특정 코인 매매 중단 |
| GET | `/api/v1/positions` | 현재 포지션 목록 |
| GET | `/api/v1/orders` | 주문 내역 |
| GET | `/api/v1/risk/config` | 리스크 설정 조회 |
| PUT | `/api/v1/risk/config` | 리스크 설정 수정 |
| GET | `/api/v1/health/exchange` | 거래소 상태 |

#### 알림 API (Phase 4)
| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/api/v1/reports/daily` | 일일 리포트 조회 |
| GET | `/api/v1/reports/weekly` | 주간 리포트 조회 |
| POST | `/api/v1/reports/test-telegram` | 텔레그램 연동 테스트 |

---

## 5. 주문 실행 엔진 상태 다이어그램

```
                    ┌─────────┐
                    │ PENDING │  (전략 신호 수신, Risk Engine 승인 대기)
                    └────┬────┘
                         │ Risk Engine 승인
                         ▼
                    ┌───────────┐
         ┌─────────│ SUBMITTED │──────────────────┐
         │         └─────┬─────┘                  │
         │               │                        │
    API 오류         체결량 > 0              체결량 = 주문량
    잔고 부족       미체결 잔량 > 0
         │               │                        │
         ▼               ▼                        ▼
    ┌────────┐   ┌────────────────┐          ┌────────┐
    │ FAILED │   │ PARTIAL_FILLED │─────────→│ FILLED │
    └────────┘   └───────┬────────┘  잔량    └────────┘
                         │           체결완료
                    타임아웃 or
                    수동 취소
                         │
                         ▼
                    ┌───────────┐
                    │ CANCELLED │  (미체결 잔량 → 시장가 전환 or 포기)
                    └───────────┘
```

**전이 규칙:**
- PENDING → SUBMITTED: Risk Engine 승인 + API 호출 성공
- PENDING → FAILED: Risk Engine 거부
- SUBMITTED → FILLED: 전량 체결
- SUBMITTED → PARTIAL_FILLED: 부분 체결
- SUBMITTED → CANCELLED: N분 타임아웃 or 사용자 수동 취소
- SUBMITTED → FAILED: Upbit API 오류, 잔고 부족
- PARTIAL_FILLED → FILLED: 잔량 체결 완료
- PARTIAL_FILLED → CANCELLED: 타임아웃 (잔량은 시장가 전환 시도)

### 5.1 장애 시나리오 및 동작 정책

| 장애 유형 | 감지 방법 | 동작 정책 |
|-----------|----------|----------|
| **Upbit WebSocket 끊김** | 30초 이상 시세 미수신 | 재연결 3회 시도 → 실패 시 `system:health:exchange = DOWN`, 자동매매 stop, 텔레그램 알림 |
| **Upbit REST API 오류** | HTTP 5xx / 타임아웃 | 지수 백오프 재시도 (1s→2s→4s, 최대 3회) → 실패 시 해당 주문 FAILED 처리 |
| **Redis 장애** | 연결 실패 / 타임아웃 | 캐시 우회(직접 DB/API 조회), Pub/Sub 중단 시 자동매매 stop |
| **DB 연결 실패** | Connection Pool 소진 | 백테스팅/실전 매매 중단, 텔레그램 알림 발송, 시스템 상태 DEGRADED |
| **시세 지연** | 캔들 타임스탬프 > 허용 지연(60초) | 해당 코인 매매 일시 중단, 로그 기록 |
| **주문 체결 타임아웃** | N분(설정 가능) 미체결 | 미체결 잔량 시장가 전환 시도 → 실패 시 CANCELLED + 알림 |

> 모든 장애 발생 시 `strategy_log`에 기록하고, 심각도 HIGH 이상은 텔레그램 즉시 알림.

---

## 6. Walk Forward Test 설계

### 6.1 윈도우 분할 방식

```
전체 데이터: 2023-01 ──────────────────────────── 2025-12

Window 1:  [====== In-Sample ======][= Out-Sample =]
           2023-01 ~ 2024-06        2024-07 ~ 2024-12

Window 2:  [====== In-Sample ======][= Out-Sample =]
           2023-07 ~ 2025-01        2025-02 ~ 2025-06

Window 3:  [====== In-Sample ======][= Out-Sample =]
           2024-01 ~ 2025-06        2025-07 ~ 2025-12
```

### 6.2 Overfitting 판정 기준
- In-Sample 대비 Out-of-Sample 수익률 하락률 계산
- 하락률 > 50%: **OVERFITTING** (경고)
- 하락률 30~50%: **CAUTION** (주의)
- 하락률 < 30%: **ACCEPTABLE** (통과)
- 전체 Window의 평균으로 최종 판정

---

## 7. 백테스트 현실성 강화 — Fill Simulation

기존 Slippage만으로는 실전과 백테스트 괴리가 큽니다. 다음 3가지를 추가합니다.

### Fill Simulation (체결 시뮬레이션)
- **Market Impact**: 주문량이 해당 캔들 거래량의 N% 이상이면 추가 슬리피지 적용
  - `impact = orderVolume / candleVolume × impactFactor`
  - impactFactor 기본값: 0.1 (10% 점유 시 1% 추가 슬리피지)
- **Partial Fill**: 주문량 > 캔들 거래량 × fillRatio일 경우 부분 체결 시뮬레이션
  - fillRatio 기본값: 0.3 (캔들 거래량의 30%까지만 한 캔들에서 체결)
  - 미체결 잔량은 다음 캔들로 이월
- **설정 가능 파라미터** (BacktestConfig에 추가):
  ```json
  {
    "slippagePct": 0.1,
    "feePct": 0.05,
    "fillSimulation": {
      "enabled": true,
      "impactFactor": 0.1,
      "fillRatio": 0.3
    }
  }
  ```

---

## 8. 프론트엔드 설계

### 8.1 페이지 구조

| 경로 | 페이지 | Phase | 설명 |
|------|--------|-------|------|
| `/` | 대시보드 | 2 | 요약 현황 |
| `/backtest` | 백테스팅 | 2 | 설정 + 실행 |
| `/backtest/[id]` | 백테스팅 결과 | 2 | 상세 결과 + 차트 |
| `/backtest/compare` | 전략 비교 | 2 | 다중 전략 비교 |
| `/strategies` | 전략 관리 | 3 | 전략 목록 + 설정 |
| `/paper-trading` | Paper Trading | 3.5 | 모의투자 세션 관리 (최대 5개 멀티세션) |
| `/paper-trading/[sessionId]` | 세션 상세 | 3.5 | 잔고, 포지션, 가격 차트, 체결 내역 |
| `/paper-trading/history` | 모의투자 이력 | 3.5 | 전체 세션 이력 (RUNNING + STOPPED) |
| `/data` | 데이터 수집 | 2 | 캔들 데이터 수집/관리 |
| `/trading` | 실전 매매 | 4 | 실시간 현황 + 운영 제어 |
| `/positions` | 포지션 | 4 | 현재 포지션 목록 |
| `/orders` | 주문 내역 | 4 | 주문 이력 |
| `/risk` | 리스크 설정 | 4 | 리스크 파라미터 관리 |
| `/logs` | 로그 | 2 | 전략 로그 / 거래 로그 |

### 8.2 프로젝트 구조

```
crypto-trader-frontend/
├── app/
│   ├── layout.tsx                # 전체 레이아웃 (Sidebar + Dark Mode)
│   ├── page.tsx                  # 대시보드 (/)
│   ├── backtest/
│   │   ├── page.tsx              # 백테스팅 설정/실행
│   │   ├── [id]/page.tsx         # 결과 상세
│   │   └── compare/page.tsx      # 전략 비교
│   ├── strategies/page.tsx       # 전략 관리
│   ├── paper-trading/
│   │   ├── page.tsx              # 모의투자 세션 관리
│   │   ├── [sessionId]/page.tsx  # 세션 상세 (차트, 체결내역)
│   │   └── history/page.tsx      # 모의투자 이력
│   ├── trading/page.tsx          # 실전 매매 현황
│   ├── positions/page.tsx
│   ├── orders/page.tsx
│   ├── risk/page.tsx
│   └── logs/page.tsx
├── components/
│   ├── ui/                       # 공통 UI (Button, Card, Table, Modal)
│   ├── layout/
│   │   ├── Sidebar.tsx
│   │   ├── Header.tsx
│   │   └── ThemeProvider.tsx      # 다크 모드
│   └── features/
│       ├── backtest/
│       │   ├── BacktestForm.tsx
│       │   ├── MetricsCards.tsx
│       │   ├── PnlChart.tsx       # Lightweight Charts
│       │   ├── CompareChart.tsx    # ApexCharts
│       │   └── MonthlyHeatmap.tsx  # ApexCharts
│       ├── strategy/
│       │   ├── StrategyList.tsx
│       │   └── StrategyConfigForm.tsx
│       └── trading/
│           ├── TradingStatus.tsx
│           ├── PositionTable.tsx
│           └── OrderTable.tsx
├── hooks/
│   ├── useBacktest.ts
│   ├── useStrategies.ts
│   └── useTrading.ts
├── lib/
│   ├── api.ts                    # API 클라이언트 (fetch wrapper)
│   └── utils.ts
├── stores/
│   ├── backtestStore.ts          # 주의: 대용량 차트 데이터(backtest_trade 수만 건)는
│   └── tradingStore.ts           #   Zustand 전역 상태에 넣지 말고 React Query로 관리할 것
└── types/
    └── index.ts
```

---

## 9. 인프라 & 배포 설계

### 9.1 Docker Compose (개발)

```yaml
version: '3.8'

services:
  db:
    image: timescale/timescaledb:latest-pg15
    environment:
      POSTGRES_DB: crypto_auto_trader
      POSTGRES_USER: trader
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  backend:
    build:
      context: .
      dockerfile: web-api/Dockerfile
    ports:
      - "8080:8080"
    depends_on:
      - db
      - redis
    environment:
      SPRING_PROFILES_ACTIVE: dev
      DB_URL: jdbc:postgresql://db:5432/crypto_auto_trader
      REDIS_HOST: redis

  frontend:
    build: ./crypto-trader-frontend
    ports:
      - "3000:3000"
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080/api/v1

volumes:
  pgdata:
```

### 9.2 Docker Compose (운영 — Ubuntu)

```yaml
version: '3.8'

services:
  db:
    image: timescale/timescaledb:latest-pg15
    restart: always
    environment:
      POSTGRES_DB: crypto_auto_trader
      POSTGRES_USER: trader
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    # 포트 외부 노출 안 함 (내부 네트워크만)

  redis:
    image: redis:7-alpine
    restart: always

  backend:
    build:
      context: .
      dockerfile: web-api/Dockerfile
    restart: always
    depends_on:
      - db
      - redis
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: jdbc:postgresql://db:5432/crypto_auto_trader
      REDIS_HOST: redis
      UPBIT_ACCESS_KEY: ${UPBIT_ACCESS_KEY_ENCRYPTED}
      UPBIT_SECRET_KEY: ${UPBIT_SECRET_KEY_ENCRYPTED}
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
      TELEGRAM_CHAT_ID: ${TELEGRAM_CHAT_ID}
      AES_KEY: ${AES_KEY}
    ports:
      - "8080:8080"

  frontend:
    build:
      context: ./crypto-trader-frontend
      dockerfile: Dockerfile
    restart: always
    ports:
      - "3000:3000"
    environment:
      # NEXT_PUBLIC_*는 빌드 타임 주입 (브라우저에서 실행)
      # Docker 내부 네트워크가 아닌 호스트 IP/도메인 사용
      NEXT_PUBLIC_API_URL: http://${HOST_IP:-localhost}:8080/api/v1

volumes:
  pgdata:
```

### 9.3 .env.example

```env
# Database
DB_PASSWORD=your_secure_password

# Upbit API (AES-256 암호화된 값)
UPBIT_ACCESS_KEY_ENCRYPTED=encrypted_value_here
UPBIT_SECRET_KEY_ENCRYPTED=encrypted_value_here

# AES-256 암호화 키 (32 bytes)
AES_KEY=your-256-bit-secret-key-minimum-32-chars
# Do 에이전트 참고: API Key 복호화 후 메모리 적재 시 String 대신 char[]를 사용하고, 사용 직후 배열을 덮어씌울 것

# Telegram
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
TELEGRAM_CHAT_ID=your_chat_id

# Host (운영 서버 IP — Frontend에서 Backend 접근용)
HOST_IP=your_server_ip_or_domain
```

---

## 10. Do 에이전트 전달사항

이 설계서를 기반으로 **Do-Backend**와 **Do-Frontend**가 병렬로 구현합니다.
두 에이전트는 **4단원 API 명세**를 계약(Contract)으로 공유하므로 독립 개발이 가능합니다.

```
Design ─┬→ Do-Backend (Spring Boot)  ─┬→ Check (통합 검증)
        └→ Do-Frontend (Next.js)      ─┘
             (병렬 실행)
```

### 권장 모델
| 단계 | 모델 | 이유 |
|------|------|------|
| 설계 리뷰 / 구현 계획 | `claude-opus-4-6` | 복잡한 아키텍처 추론 |
| 코드 구현 | `claude-sonnet-4-6` | 코딩 속도/비용 효율 (항상 최신 버전 사용) |

---

### 10.1 Do-Backend 구현 순서 (`04a-do-backend`)

#### Phase 1: 백테스팅 시스템
1. Gradle 멀티모듈 프로젝트 초기 구조 (core-engine, strategy-lib, exchange-adapter, web-api)
2. Docker Compose 설정 (TimescaleDB + Redis)
3. Flyway 마이그레이션 (candle_data, backtest_run, backtest_metrics, backtest_trade, strategy_signal)
4. exchange-adapter: UpbitRestClient + UpbitCandleCollector
5. strategy-lib: Strategy 인터페이스 + 4개 전략 구현 (VWAP, EMA, Bollinger, Grid)
6. core-engine: BacktestEngine + MetricsCalculator + MarketRegimeDetector
7. core-engine: WalkForwardTestRunner + Fill Simulation (Market Impact, Partial Fill)
8. core-engine: PortfolioManager (전략 충돌 방지, 전체 자산 관리)
9. web-api: AsyncConfig (스레드 풀 분리) + BacktestController + REST API
10. 3단 로그 시스템 (Logback 설정)
11. 단위 테스트

#### Phase 3: 전략 개선
12. strategy-lib: 추가 6종 전략 구현
13. web-api: StrategyController

#### Phase 3.5: Paper Trading
14. paper_trading 스키마 활성화 + PaperTradingService

#### Phase 4: 실전 매매
15. exchange-adapter: OrderExecutionEngine + OrderStateMachine + ExchangeHealthMonitor
16. web-api: TradeController + RiskController + 이벤트 파이프라인
17. TelegramNotificationService + ScheduledTasks

---

### 10.2 Do-Frontend 구현 순서 (`04b-do-frontend`)

#### Phase 2: 대시보드
1. Next.js 프로젝트 초기 설정 (TypeScript + Tailwind + 다크 모드)
2. 레이아웃 (Sidebar + Header + ThemeProvider)
3. API 클라이언트 + React Query 설정
4. MSW 모킹 (Backend 미완성 시 독립 개발용)
5. 대시보드 페이지 (요약 카드, 최근 백테스트 리스트)
6. 백테스팅 설정/실행 페이지 (BacktestForm + Walk Forward 토글)
7. 결과 상세 페이지 (MetricsCards + PnlChart + CandleChart + MonthlyHeatmap)
8. 전략 비교 페이지 (CompareChart)
9. 매매 기록 테이블 + 전략 로그 조회 (React Query useInfiniteQuery)

#### Phase 3: 전략 관리
10. 전략 목록 + 설정 폼 (동적 파라미터)

#### Phase 3.5: Paper Trading
11. 모의투자 현황 페이지 (가상 잔고, 포지션, 괴리율)

#### Phase 4: 실전 매매
12. 자동매매 현황 + 시작/중단 제어
13. 포지션 / 주문 내역 페이지
14. 리스크 설정 페이지

---

### 10.3 통합 계약 (API Contract)
Do-Backend와 Do-Frontend가 반드시 일치시켜야 할 항목:
- **4.2 공통 응답 형식**: `{ success, data, error: { code, message } }`
- **4.3 에러 코드 체계**: 10개 에러 코드
- **4.4 API 엔드포인트**: URL, Method, Request/Response 스키마
- **7. Fill Simulation**: 백테스트 요청 시 fillSimulation 파라미터 일치
- Check 단계에서 API 계약 불일치 여부를 통합 검증

---
작성: Design 에이전트
다음: @Do-Backend + @Do-Frontend (병렬)
