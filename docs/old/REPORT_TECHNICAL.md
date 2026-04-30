# CryptoAutoTrader — 기술 상세 보고서 v3.0

> **작성일**: 2026-03-24
> **대상 독자**: 개발팀 / 운영팀
> **기준 문서**: DEV_STATUS_REVIEW_v3.3, CHECK_RESULT.md v6.0, PROGRESS.md (2026-03-23)
> **작성자**: Report 에이전트 (06)

---

## 1. 시스템 개요

### 1.1 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────┐
│                         클라이언트                            │
│            Next.js 16.1.6 / React 19.2.3 (포트 3000)        │
│  /, /backtest, /strategies, /paper-trading, /trading,       │
│  /performance, /logs, /settings, /account, /login 등        │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTPS (API proxy /api/proxy/*)
                             ▼
┌─────────────────────────────────────────────────────────────┐
│              Spring Boot 3.2 백엔드 (포트 8080)              │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ core-engine  │  │ strategy-lib │  │ exchange-adapter  │  │
│  │ BacktestEng  │  │ 전략 14종    │  │ UpbitRestClient   │  │
│  │ RiskEngine   │  │ StrategyParam│  │ UpbitWebSocket    │  │
│  │ PortfolioMgr │  │ IndicatorUtil│  │ UpbitOrderClient  │  │
│  └──────────────┘  └──────────────┘  └───────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐    │
│  │                     web-api                           │    │
│  │  TradingController / PaperTradingController           │    │
│  │  BacktestController / StrategyController              │    │
│  │  LiveTradingService / PaperTradingService             │    │
│  │  SecurityConfig (ApiTokenAuthFilter)                  │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────┬──────────────────┬───────────────────────────┘
               │                  │
      ┌────────▼──────┐  ┌───────▼───────┐
      │ TimescaleDB   │  │     Redis      │
      │ (포트 5432)   │  │   (포트 6379)  │
      │ V1~V26 Flyway │  │ JSON 직렬화    │
      └───────────────┘  └───────────────┘
               │
      ┌────────▼──────────┐
      │ Prometheus (9090) │
      │ Grafana    (3001) │
      └───────────────────┘
```

### 1.2 기술 스택 버전

| 구분 | 기술 | 버전 |
|------|------|------|
| 언어 | Java | 17 |
| 백엔드 프레임워크 | Spring Boot | 3.2 |
| 빌드 도구 | Gradle | 멀티모듈 |
| 프론트엔드 | Next.js | 16.1.6 |
| UI 라이브러리 | React | 19.2.3 |
| 타입 시스템 | TypeScript | - |
| CSS 프레임워크 | Tailwind CSS | - |
| 데이터베이스 | TimescaleDB (PostgreSQL) | - |
| 인메모리 캐시 | Redis | - |
| DB 마이그레이션 | Flyway | V1 ~ V26 |
| 컨테이너 | Docker Compose | prod 4개 서비스 |
| CI/CD | GitHub Actions | `.github/workflows/ci.yml` |
| 모니터링 | Prometheus + Grafana | 9090 / 3001 |
| 알림 | Telegram Bot API | - |
| 거래소 | Upbit REST + WebSocket | - |

---

## 2. 백엔드 구조

### 2.1 Gradle 멀티모듈 구성

```
web-api/ (루트 프로젝트)
├── core-engine/          # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── BacktestEngine    — 룩어헤드 바이어스 방지, 다음 캔들 시가 기준 체결
│   ├── WalkForwardTestRunner — In-Sample/Out-of-Sample 분할
│   ├── FillSimulator     — 시장 충격(Market Impact) + 부분 체결(Partial Fill)
│   ├── MetricsCalculator — 8종 성과 지표 (Calmar, Sharpe, MDD, Recovery Factor 등)
│   ├── MarketRegimeDetector — ADX 기반 4국면 감지
│   ├── RiskEngine        — Fixed Fractional Sizing + Correlation Risk
│   ├── PortfolioManager  — 전략 충돌 방지, 자본 할당 (synchronized)
│   └── CompositeStrategy + StrategySelector + WeightedStrategy
│
├── strategy-lib/         # 전략 14종 구현체
│   ├── vwap/VwapStrategy.java
│   ├── ema/EmaCrossStrategy.java
│   ├── bollinger/BollingerStrategy.java
│   ├── grid/GridStrategy.java
│   ├── rsi/RsiStrategy.java
│   ├── macd/MacdStrategy.java
│   ├── supertrend/SupertrendStrategy.java
│   ├── atrbreakout/AtrBreakoutStrategy.java
│   ├── orderbook/OrderbookImbalanceStrategy.java
│   ├── stochasticrsi/StochasticRsiStrategy.java
│   ├── macdstochbb/MacdStochBbStrategy.java
│   ├── utils/StrategyParamUtils.java   — 공통 파라미터 파싱 (~250줄 중복 제거)
│   └── utils/IndicatorUtils.java       — 공통 지표 계산
│
├── exchange-adapter/     # 업비트 외부 연동
│   ├── UpbitRestClient   — Rate Limiting 110ms, getOrderbook() 구현
│   ├── UpbitWebSocketClient — disconnect()/destroy() 분리, 자동 재연결
│   └── UpbitOrderClient  — char[] 기반 JWT (보안 강화)
│
└── web-api/              # REST API 레이어
    ├── controller/       — 8개 Controller
    ├── service/          — LiveTradingService, PaperTradingService 등
    ├── config/           — SecurityConfig, RedisConfig, SchedulerConfig, AsyncConfig 등
    └── scheduler/        — MarketRegimeAwareScheduler, PortfolioSyncService
```

### 2.2 주요 서비스 설명

| 서비스 | 역할 | 핵심 구현 |
|--------|------|---------|
| `LiveTradingService` | 실전매매 세션 실행 | 다중 세션, orphan guard, TRANSITIONAL 신규 진입 금지 |
| `PaperTradingService` | 모의투자 실행 | 최대 5세션, 가상 체결 시뮬레이션 |
| `OrderExecutionEngine` | 주문 상태 머신 | 6단계 상태 관리, getOrders(Pageable) |
| `RiskManagementService` | 리스크 관리 | 절대가 SL/TP 체크 (@Transactional readOnly) |
| `ExchangeHealthMonitor` | 거래소 상태 감시 | UP / DEGRADED / DOWN 3단계, 연속 3회 실패 → DOWN |
| `TelegramNotificationService` | 알림 발송 | 즉시 알림 + 12/24시 일별 요약, bufferTradeEvent() |
| `PortfolioSyncService` | 잔고 동기화 | 5분 주기, ApplicationReadyEvent 1회 즉시 동기화 |
| `MarketRegimeAwareScheduler` | 전략 자동 스위칭 | 1시간 주기, Regime별 전략 활성화 |
| `BacktestService` | 백테스트 실행 | @Transactional 제거 (PostgreSQL cascade 수정) |

### 2.3 AsyncConfig 스레드 풀 (Graceful Shutdown)

| 풀 이름 | 용도 | Graceful 대기 |
|---------|------|-------------|
| `orderExecutor` | 주문 처리 | 30초 (핵심 자산 처리) |
| `marketDataExecutor` | 시세 수신 | 10초 |
| `taskExecutor` | 일반 비동기 | 15초 |
| `telegramExecutor` | 텔레그램 발송 | 미설정 (큐 포화 시 드롭 허용) |

---

## 3. 전략 시스템 상세

### 3.1 단일 전략 10종

| 전략명 | 클래스 | 핵심 알고리즘 | 주요 개선 (S6) |
|--------|--------|------------|-------------|
| VWAP | `VwapStrategy` | 거래량 가중 평균 가격 대비 이탈률 | 임계값 2.5%→1.5%, ADX 상한 25→35, anchorSession(UTC 00:00) |
| EMA_CROSS | `EmaCrossStrategy` | EMA(9)/EMA(21) 골든/데드 크로스 | ADX > 25 필터, 기간 슬로우화 |
| BOLLINGER | `BollingerStrategy` | %B 이탈 후 복귀 신호 | ADX < 25 필터, Squeeze 감지 |
| GRID | `GridStrategy` | 고정 가격 격자 매수/매도 | StatefulStrategy (activeLevels 중복 방지), 세션별 격리 |
| RSI | `RsiStrategy` | 피봇 다이버전스 감지 | 임계값 강화, 피봇 탐지 로직 |
| MACD | `MacdStrategy` | 히스토그램 크로스 | 기울기 필터 + 제로라인 필터 추가 (가짜 크로스 ~30% 감소) |
| SUPERTREND | `SupertrendStrategy` | ATR 기반 추세 밴드 | upperBand/lowerBand 분리, O(n²)→O(n) 최적화 |
| ATR_BREAKOUT | `AtrBreakoutStrategy` | ATR 변동성 돌파 | 거래량 확인 필터 추가 |
| ORDERBOOK_IMBALANCE | `OrderbookImbalanceStrategy` | 매수/매도 호가 불균형 | 호가 Delta 추적, UpbitRestClient 실값 주입 |
| STOCHASTIC_RSI | `StochasticRsiStrategy` | %K/%D 크로스 | 임계값 15→20/85→80, 2캔들 연속 확인, 거래량 필터 |

> STOCHASTIC_RSI, MACD는 `BLOCKED_LIVE_STRATEGIES`로 실전 차단 중. 파라미터 재최적화 후 해제 예정.

### 3.2 복합 전략 4종

#### COMPOSITE_BTC

```
구성: GRID × 0.6 + BOLLINGER × 0.4
필터: EMA20 > EMA50 (상승추세) → SELL 신호 억제
      EMA20 < EMA50 (하락추세) → BUY  신호 억제
적합: BTC, 횡보~약추세 구간
```

#### COMPOSITE_ETH

```
실시간 가중치: ATR_BREAKOUT × 0.5 + ORDERBOOK_IMBALANCE × 0.3 + EMA_CROSS × 0.2
백테스트 가중치: ATR_BREAKOUT × 0.7 + ORDERBOOK_IMBALANCE × 0.1 + EMA_CROSS × 0.2
이유: 백테스트에서 ORDERBOOK은 캔들 근사값 사용 → 오더북 가중치 축소
적합: ETH, 변동성 구간
```

#### MACD_STOCH_BB

```
조건: MACD 히스토그램 + 제로라인 필터 AND StochRSI 크로스 AND 볼린저 %B 위치
      6개 조건 동시 충족 시 신호 발동 (보수적 설계)
적합: 강한 추세 국면 눌림목 진입
```

#### COMPOSITE (범용 — 백엔드 미등록 주의)

```
설계: Regime 감지 결과에 따라 전략 그룹 자동 선택
  TREND → EMA_CROSS, SUPERTREND, ATR_BREAKOUT
  RANGE → GRID, BOLLINGER, VWAP
  VOLATILITY → ATR_BREAKOUT, ORDERBOOK_IMBALANCE
  TRANSITIONAL → 신규 진입 금지 (기존 포지션 유지만 허용)
현황: types.ts에 선언됨. StrategyRegistry에 미등록 → 프론트에서 선택 시 오류 발생 가능
```

### 3.3 MarketRegimeDetector (ADX 기반 4국면)

```java
// 국면 판별 로직 (요약)
TREND:       ADX > 25 (추세 강도 강함)
RANGE:       ADX < 20 AND BB Bandwidth 낮음
VOLATILITY:  ATR Spike 감지 (단기 ATR > 장기 ATR × 1.5)
TRANSITIONAL: 위 조건 미해당 OR 직전 국면 변경 구간
              → Hysteresis(이력현상) 적용: ADX ±2 버퍼로 잦은 전환 방지
```

### 3.4 GlobalRiskManager (SL/TP 절대가 관리)

- 진입 시 `stopLossPrice`, `takeProfitPrice` 절대가를 DB(`position`, `paper_trading.position`)에 저장 (Flyway V26)
- 매 틱(WebSocket 가격 수신) O(1) 비교로 손절/익절 체크
- `RiskManagementService` + `WebSocket 실시간 손절 통합` (RealtimePriceEvent, 5초 throttle)

---

## 4. API 목록

### 4.1 Controller별 엔드포인트 (구현 완료 기준)

#### BacktestController — `/api/v1/backtest`

| Method | 경로 | 설명 |
|--------|------|------|
| POST | `/run` | 단일 백테스트 실행 (fillSimulation 포함) |
| POST | `/walk-forward` | Walk-Forward 테스트 |
| POST | `/bulk-run` | 벌크 백테스트 (파라미터 그리드 서치용) |
| POST | `/multi-strategy` | 다중 전략 일괄 백테스트 |
| GET | `/{id}` | 결과 조회 |
| GET | `/{id}/trades` | 체결 내역 (페이징) |
| GET | `/compare` | 전략 비교 |
| GET | `/list` | 이력 목록 |
| DELETE | `/{id}` | 삭제 |
| DELETE | `/bulk` | 일괄 삭제 |

#### TradingController — `/api/v1/trading` (완성도 99%)

| Method | 경로 | 설명 |
|--------|------|------|
| GET | `/status` | 실전매매 전체 상태 |
| POST | `/sessions` | 세션 생성 |
| POST | `/sessions/multi` | 다중 전략 세션 일괄 생성 |
| GET | `/sessions` | 세션 목록 |
| GET | `/sessions/{id}` | 세션 상세 |
| POST | `/sessions/{id}/start` | 세션 시작 |
| POST | `/sessions/{id}/stop` | 세션 정지 |
| POST | `/sessions/{id}/emergency-stop` | 세션 비상정지 |
| DELETE | `/sessions/{id}` | 세션 삭제 |
| GET | `/sessions/{id}/positions` | 세션 포지션 |
| GET | `/sessions/{id}/orders` | 세션 주문 |
| GET | `/sessions/{id}/chart` | 세션 차트 데이터 |
| POST | `/emergency-stop` | 전체 비상정지 |
| GET | `/positions` | 전체 포지션 |
| GET | `/positions/{id}` | 포지션 상세 |
| GET | `/orders` | 전체 주문 |
| GET | `/orders/{id}` | 주문 상세 |
| DELETE | `/orders/{id}` | 주문 취소 |
| GET | `/risk/config` | 리스크 설정 조회 |
| PUT | `/risk/config` | 리스크 설정 변경 |
| GET | `/health/exchange` | 거래소 헬스 |
| GET | `/performance` | 실전매매 성과 집계 |
| POST | `/telegram/test` | 텔레그램 테스트 발송 |
| **❌ 미구현** | `GET /api/v1/reports/weekly` | 주간 리포트 API |

#### PaperTradingController — `/api/v1/paper-trading` (완성도 100%)

| Method | 경로 | 설명 |
|--------|------|------|
| GET | `/sessions` | 세션 목록 |
| POST | `/sessions` | 세션 생성 |
| POST | `/sessions/multi` | 다중 세션 일괄 생성 |
| GET | `/sessions/{id}` | 세션 상세 |
| GET | `/sessions/{id}/positions` | 세션 포지션 |
| GET | `/sessions/{id}/orders` | 세션 주문 (페이징) |
| POST | `/sessions/{id}/stop` | 세션 정지 |
| GET | `/sessions/{id}/chart` | 세션 차트 데이터 |
| POST | `/sessions/stop-all` | 전체 세션 일괄 정지 |
| DELETE | `/history/{id}` | 이력 삭제 |
| DELETE | `/history/bulk` | 이력 일괄 삭제 |
| GET | `/performance` | 모의투자 성과 집계 |

#### 기타 Controller

| Controller | 기본 경로 | 주요 엔드포인트 |
|-----------|---------|------------|
| StrategyController | `/api/v1/strategies` | CRUD, PATCH 토글, 타입 활성/비활성 |
| DataController | `/api/v1/data` | 캔들 수집 요청, 상태, 코인 목록 |
| LogController | `/api/v1/logs` | `/strategy` (sessionType/sessionId 필터) |
| SettingsController | `/api/v1/settings` | 텔레그램 로그·테스트, Upbit 상태, DB 통계 |
| AccountController | `/api/v1/account` | `/summary` — 업비트 잔고 조회 |
| SystemController | `/api/v1` | `/health`, `/strategies/types` |

---

## 5. DB 스키마

### 5.1 Flyway 마이그레이션 V1~V26 요약

| 버전 | 파일명 | 핵심 내용 |
|------|--------|---------|
| V1 | `create_candle_data` | TimescaleDB hypertable |
| V2 | `create_backtest_tables` | backtest_run, backtest_metrics, backtest_trade |
| V3 | `create_strategy_config` | 전략 설정 영속화 |
| V4 | `create_position_order` | 실전 포지션/주문 테이블 |
| V5 | `create_risk_config` | 리스크 설정 |
| V6 | `create_log_tables` | strategy_log, trade_log |
| V7 | `create_strategy_signal` | 전략 신호 기록 |
| V8~V10 | `create/enhance_paper_trading` | 모의투자 스키마 (멀티세션) |
| V11 | `add_manual_override` | strategy_config.manual_override |
| V12 | `create_live_trading_session` | 실전매매 세션 테이블 |
| V13 | `create_market_data_cache` | 실시간 싱크 캐시 |
| V14 | `create_strategy_type_enabled` | 전략 10종 ON/OFF |
| V15~V16 | Telegram 관련 | 텔레그램 로그, 알림 로그 |
| V17 | `add_session_type_to_strategy_log` | 로그 세션 타입 구분 |
| V18 | `add_pnl_fee_to_virtual_balance` | 모의투자 PnL/수수료 |
| V19 | `add_invest_ratio` | 세션별 투자비율 (기본 80%) |
| V20~V22 | `add_position_fee` | 포지션 수수료 추적 (모의/실전) |
| V23 | `add_closing_at_to_position` | CLOSING 타임아웃 롤백 |
| V24 | `add_circuit_breaker` | MDD/연속손실 임계값 |
| V25 | `fix_market_regime_varchar_length` | VARCHAR 길이 수정 |
| V26 | `add_sl_tp_to_positions` | SL/TP 절대가 컬럼 (4개) |

### 5.2 테이블 소유권 분리 구조

```
crypto_auto_trader (단일 DB, TimescaleDB)

[백테스팅 전용]
├── backtest_run, backtest_metrics, backtest_trade

[모의투자 전용] — paper_trading 스키마
├── paper_trading.virtual_balance
├── paper_trading.position         (stop_loss_price, take_profit_price 포함)
├── paper_trading.order
├── paper_trading.strategy_log
└── paper_trading.trade_log

[실전투자 전용] — public 스키마
├── live_trading_session           (invest_ratio, circuit_breaker 포함)
├── position                       (closing_at, stop_loss_price, take_profit_price 포함)
└── order

[공통 인프라]
├── candle_data                    (TimescaleDB hypertable)
├── strategy_config                (manual_override)
├── strategy_log, trade_log, strategy_signal
├── risk_config
├── market_data_cache
└── strategy_type_enabled
```

---

## 6. 프론트엔드 구조

### 6.1 페이지 라우트 (App Router, Next.js 16.1.6)

| 경로 | 파일 | 설명 | 완성도 |
|------|------|------|--------|
| `/` | `app/page.tsx` | 대시보드 (요약 카드 + 최근 백테스트) | ✅ |
| `/backtest` | `app/backtest/page.tsx` | 백테스트 이력 목록 | ✅ |
| `/backtest/new` | `app/backtest/new/page.tsx` | 신규 백테스트 실행 | ✅ |
| `/backtest/[id]` | `app/backtest/[id]/page.tsx` | 결과 상세 (차트 + 지표) | ✅ |
| `/backtest/compare` | `app/backtest/compare/page.tsx` | 최대 6개 전략 비교 | ✅ |
| `/backtest/walk-forward` | `app/backtest/walk-forward/page.tsx` | Walk-Forward 실행 UI | ✅ |
| `/strategies` | `app/strategies/page.tsx` | 전략 관리 (14종 카드) | ✅ |
| `/paper-trading` | `app/paper-trading/page.tsx` | 모의투자 세션 관리 | ✅ |
| `/paper-trading/[sessionId]` | `app/paper-trading/[sessionId]/page.tsx` | 세션 상세 (차트 + 매매 요약) | ✅ |
| `/paper-trading/history` | `app/paper-trading/history/page.tsx` | 모의투자 이력 | ✅ |
| `/trading` | `app/trading/page.tsx` | 실전매매 세션 관리 | ✅ |
| `/trading/[sessionId]` | `app/trading/[sessionId]/page.tsx` | 세션 상세 + 포지션/주문 | ✅ |
| `/trading/history` | `app/trading/history/page.tsx` | 실전매매 이력 | ✅ |
| `/trading/risk` | `app/trading/risk/page.tsx` | 리스크 설정 | ✅ |
| `/performance` | `app/performance/page.tsx` | 손익 대시보드 (실전/모의 탭, 7개 카드) | ✅ |
| `/logs` | `app/logs/page.tsx` | 전략 로그 조회 (페이지네이션) | ✅ |
| `/settings` | `app/settings/page.tsx` | 텔레그램·Upbit 설정 | ✅ |
| `/account` | `app/account/page.tsx` | 업비트 잔고 | ✅ |
| `/login` | `app/login/page.tsx` | 로그인 (설계 외 추가) | ✅ |
| `/data` | - | 데이터 수집 관리 | ⚠️ 확인 필요 |

### 6.2 주요 컴포넌트 및 훅

| 파일/디렉터리 | 역할 |
|-------------|------|
| `components/ui/Button, Card, Badge, Spinner` | 공통 UI 컴포넌트 |
| `components/layout/Sidebar, Header` | 네비게이션, 다크모드 토글 |
| `hooks/useTrading` | createSession, startSession, stopSession, emergencyStop |
| `app/api/proxy/[...path]/route.ts` | 서버사이드 API 토큰 프록시 |
| `middleware.ts` | Next.js 인증 미들웨어 (auth_session 쿠키) |
| `types.ts` | 전략 타입 + PerformanceSummary + SessionPerformance 등 |

### 6.3 인증 아키텍처

```
사용자 → /login 페이지 → AUTH_PASSWORD 검증
       → auth_session 쿠키 발급
       → Next.js middleware 보호

프론트엔드 API 호출:
  /api/proxy/* (Next.js API Route)
    → 서버사이드에서 API_TOKEN 헤더 추가
    → 백엔드 /api/v1/* 전달

보안 강화 이유:
  NEXT_PUBLIC_API_TOKEN → 클라이언트 번들 노출 위험
  API_TOKEN (서버사이드) → 브라우저에 토큰 미노출
```

---

## 7. 인프라

### 7.1 Docker Compose 서비스 구성

#### 개발용 (`docker-compose.yml`)

```yaml
services:
  db:    # TimescaleDB (포트 5432)
  redis: # Redis (포트 6379)
```

#### 운영용 (`docker-compose.prod.yml`)

```yaml
services:
  backend:    # Spring Boot (포트 8080), eclipse-temurin:17 multi-stage
  frontend:   # Next.js (포트 3000), node:20-alpine standalone
  db:         # TimescaleDB, healthcheck: pg_isready
  redis:      # Redis, healthcheck: redis-cli ping
  prometheus: # 포트 9090, monitoring/prometheus.yml
  grafana:    # 포트 3001, monitoring/grafana/provisioning/
```

### 7.2 CI/CD GitHub Actions (`.github/workflows/ci.yml`)

```yaml
jobs:
  backend:
    # Gradle 빌드 + 단위 테스트
    # TimescaleDB 서비스 컨테이너 포함
  frontend:
    # lint + build (Next.js)
  docker:
    # Docker 이미지 빌드 (backend + frontend)
```

### 7.3 Prometheus / Grafana

```
monitoring/
├── prometheus.yml              — 스크랩 설정
└── grafana/
    └── provisioning/
        └── datasources/
            └── prometheus.yml  — Grafana 데이터소스 자동 프로비저닝
```

---

## 8. 보안

### 8.1 SecurityConfig 구조

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // ApiTokenAuthFilter — 요청 헤더 X-API-Token 검증
    // 인증 제외 경로: /api/v1/health, /actuator/**
    // 모든 /api/v1/** 경로 인증 필요
}
```

### 8.2 ApiTokenAuthFilter

- HTTP 요청 헤더 `X-API-Token` 값 검증
- 환경변수 `API_TOKEN`과 일치하지 않으면 401 반환
- UpbitOrderClient: char[] 기반 JWT 서명 (String 잔류 방지)

### 8.3 P0 잔여 보안 항목 (운영 서버 즉시 조치 필요)

#### 1. API 토큰 기본값 제거

현재 `ApiTokenAuthFilter`에 `dev-token-change-me-in-production` 기본값이 존재.

```java
// 수정 전
@Value("${app.api-token:dev-token-change-me-in-production}")
private String apiToken;

// 수정 후
@Value("${app.api-token}")  // 기본값 제거 → 미설정 시 서버 시작 실패
private String apiToken;
```

#### 2. Redis requirepass 설정

```yaml
# docker-compose.prod.yml Redis 서비스 수정
services:
  redis:
    command: redis-server --requirepass ${REDIS_PASSWORD}
    environment:
      - REDIS_PASSWORD=${REDIS_PASSWORD}

  backend:
    environment:
      - SPRING_REDIS_PASSWORD=${REDIS_PASSWORD}
```

#### 3. Swagger 프로덕션 비활성화

```java
@Configuration
@Profile("!prod")  // 추가
public class SwaggerConfig { ... }
```

또는 `SecurityConfig`에서 `/swagger-ui/**`, `/v3/api-docs/**` 인증 요구 추가.

#### 4. CORS 운영 도메인 한정

```java
// WebConfig.java
.allowedOriginPatterns("*")  // 제거
.allowedOrigins("https://your-production-domain.com")  // 변경
```

#### 5. TimescaleDB 일일 백업

```bash
# crontab 또는 docker-compose.prod.yml 크론 컨테이너
0 2 * * * docker exec crypto-auto-trader-db-1 \
  pg_dump -U trader crypto_auto_trader | \
  gzip > /backup/crypto_$(date +%Y%m%d).sql.gz
```

---

## 9. 배포 가이드

### 9.1 로컬 실행 (Windows 개발 환경)

```bash
# 1. DB + Redis 시작
docker compose up -d

# 2. 백엔드 실행 (포트 8080)
./gradlew :web-api:bootRun

# 3. 프론트엔드 실행 (포트 3000)
cd crypto-trader-frontend
npm run dev
```

### 9.2 운영 서버 배포 (Ubuntu)

#### 환경 변수 설정 (`.env` 파일)

```bash
# 업비트 API
UPBIT_ACCESS_KEY=your_access_key
UPBIT_SECRET_KEY=your_secret_key

# 보안
API_TOKEN=your-strong-random-token
REDIS_PASSWORD=your-redis-password
AUTH_PASSWORD=your-dashboard-password
AUTH_SECRET=your-jwt-secret-32chars+

# 텔레그램
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_CHAT_ID=your_chat_id

# DB
POSTGRES_USER=trader
POSTGRES_PASSWORD=your-db-password
POSTGRES_DB=crypto_auto_trader
```

#### 배포 명령어

```bash
cd ~/crypto-auto-trader

# 전체 재빌드 & 재시작
docker compose -f docker-compose.prod.yml up -d --build

# 백엔드만 재빌드
docker compose -f docker-compose.prod.yml up -d --build backend

# 프론트엔드만 재빌드
docker compose -f docker-compose.prod.yml up -d --build frontend
```

---

## 10. 운영 가이드

### 10.1 로그 확인

```bash
# 실시간 로그 (백엔드)
docker compose -f docker-compose.prod.yml logs -f backend

# 에러 필터링
docker compose -f docker-compose.prod.yml logs backend > /tmp/backend.log 2>&1
grep -n "ERROR\|Caused by\|Exception" /tmp/backend.log | tail -30

# 프론트엔드 로그
docker compose -f docker-compose.prod.yml logs -f frontend
```

### 10.2 헬스체크

```bash
# 백엔드 헬스
curl http://localhost:8080/api/v1/health

# 거래소 연결 상태
curl -H "X-API-Token: $API_TOKEN" \
  http://localhost:8080/api/v1/trading/health/exchange

# 모든 컨테이너 상태
docker compose -f docker-compose.prod.yml ps
```

### 10.3 텔레그램 알림 확인

- 매매 체결 시: 즉시 알림 (코인, 수량, 가격, 수익률)
- 비상정지 시: 사유 포함 즉시 알림
- 낙폭 경고: 손절 한도 50% 이상 손실 시, 30분 쿨다운
- 일별 요약: 12시 / 24시 (당일 체결 수, 실현손익, 승률)

```bash
# 텔레그램 테스트 발송
curl -X POST \
  -H "X-API-Token: $API_TOKEN" \
  http://localhost:8080/api/v1/trading/telegram/test
```

### 10.4 Circuit Breaker (자동 비상정지)

Flyway V24로 추가된 circuit_breaker 설정 기반:
- MDD(최대 낙폭) 임계값 초과 시 세션 강제 비상정지
- 연속 손실 횟수 임계값 초과 시 세션 강제 비상정지
- 비상정지 사유가 텔레그램으로 즉시 전송됨

### 10.5 DB 백업 및 복구

```bash
# 수동 백업
docker exec crypto-auto-trader-db-1 \
  pg_dump -U trader crypto_auto_trader > backup_$(date +%Y%m%d).sql

# 복구
docker exec -i crypto-auto-trader-db-1 \
  psql -U trader crypto_auto_trader < backup_YYYYMMDD.sql
```

---

## 11. 트러블슈팅

### 11.1 백엔드 시작 실패

```bash
# Flyway 마이그레이션 충돌 확인
docker compose -f docker-compose.prod.yml logs backend | grep -i flyway

# DB 접속 확인
docker exec crypto-auto-trader-db-1 pg_isready -U trader -d crypto_auto_trader
```

### 11.2 WebSocket 재연결 안 됨

`UpbitWebSocketClient`는 자동 재연결 로직 포함. 수동 재시작:

```bash
docker compose -f docker-compose.prod.yml restart backend
```

### 11.3 CLOSING 상태 포지션 고착

Flyway V23의 `closing_at` 컬럼 기반으로 5분 타임아웃 후 OPEN 자동 롤백.
타임아웃이 작동하지 않을 경우 DB 직접 확인:

```sql
SELECT id, status, closing_at FROM position
WHERE status = 'CLOSING' AND closing_at < NOW() - INTERVAL '10 minutes';
```

### 11.4 COMPOSITE 전략 선택 시 오류

`COMPOSITE` 범용 타입은 현재 StrategyRegistry에 미등록. 프론트엔드에서 선택 시 404 발생.
`COMPOSITE_BTC` 또는 `COMPOSITE_ETH`를 사용하거나, types.ts에서 `'COMPOSITE'` 제거 후 재빌드.

### 11.5 수수료율 적용 문제

`getOrderChance()` API로 실제 수수료율을 조회. API 실패 시 기본값 0.0005(0.05%) 폴백 적용.

---

## 12. 미완료 항목 및 권장 다음 단계

### P0 — 즉시 (운영 배포 전 필수)

| # | 항목 | 예상 시간 | 파일 |
|---|------|---------|------|
| 1 | API 토큰 기본값 제거 + 환경변수 필수 처리 | 1h | `ApiTokenAuthFilter.java` |
| 2 | Redis `requirepass` 설정 | 1h | `docker-compose.prod.yml` |
| 3 | Swagger `@Profile("!prod")` 적용 | 0.5h | `SwaggerConfig.java` |
| 4 | CORS `allowedOriginPatterns("*")` 제거 | 0.5h | `WebConfig.java` |
| 5 | TimescaleDB `pg_dump` 일일 크론 | 2h | `docker-compose.prod.yml` |

### P1 — 단기 (1~2주)

| # | 항목 | 예상 시간 | 비고 |
|---|------|---------|------|
| 6 | MACD 파라미터 그리드 서치 | 2h | fastPeriod=8~15, slowPeriod=20~30 |
| 7 | StochRSI + RSI 다이버전스 결합 | 4~8h | 고신뢰 복합 신호 |
| 8 | 2023~2025 장기 백테스트 | 2h | 강세장·약세장 포함 |
| 9 | `GET /api/v1/reports/weekly` 구현 | 2h | `ReportController.java` 신규 |
| 10 | `COMPOSITE` 타입 정리 | 1h | StrategyRegistry 등록 또는 types.ts 제거 |
| 11 | TradingController 예외 처리 패턴 통일 | 3h | GlobalExceptionHandler 활용 |
| 12 | StrategyController DTO 전환 + Bean Validation | 4h | 코드 품질 |

### P2 — 중기 (1개월)

| # | 항목 | 예상 시간 |
|---|------|---------|
| 13 | Redis Pub/Sub 이벤트 파이프라인 | 8h |
| 14 | 다중 거래소 지원 (Binance, Bybit) | 20h |
| 15 | Python ML 통합 (gRPC FastAPI 브리지) | 40h |
| 16 | 멀티 타임프레임 전략 (1H 방향 + 15M 진입) | 고복잡도 |
| 17 | 무중단 배포 (Blue-Green/Rolling) | 12h |

---

*CryptoAutoTrader REPORT_TECHNICAL v3.0 | 2026-03-24 | 기준: DEV_STATUS_REVIEW_v3.3, CHECK_RESULT.md v6.0*
