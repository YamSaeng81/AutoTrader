# CryptoAutoTrader - 개발 상태 검증 v3.0

## 문서 정보

- 최초 검증일: 2026-03-08 (v1.0)
- v2.0 갱신일: 2026-03-08
- v3.0 갱신일: 2026-03-12
- v3.1 갱신일: 2026-03-15 — Phase 4 프론트엔드 완성 + 전략 고도화 Phase S1~S5 반영
- **v3.2 갱신일: 2026-03-23** — Phase 5 손익 대시보드 완료, 전략 14종, GlobalRiskManager 분리, COMPOSITE 고도화, MACD/StochRSI/VWAP 코드 개선, Flyway V1~V26
- **v3.3 갱신일: 2026-03-23** — CHANGELOG.md 기반 재검증: SecurityConfig 완료 확인, Circuit Breaker/WebSocket 실시간 손절/PortfolioSyncService/GridStrategy 세션 격리 등 Phase 4 누락 항목 18건 추가, CI/CD + Prometheus/Grafana 인프라 추가
- 검증 범위: Phase 1 ~ Phase 5 + Phase S1~S5 (전략 고도화) + DB 분리 아키텍처
- 기반 문서: PLAN.md, DESIGN.md v1.4, PROGRESS.md (2026-03-23)
- 목적: 전체 개발 상태 이력 관리 및 Phase별 완성도 추적

---

## 1. Phase별 개발 상태 종합

### 1.1 Phase 1 - 백테스팅 엔진 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Gradle 멀티모듈 (core-engine, strategy-lib, exchange-adapter, web-api) | 완료 | 의존성 방향 설계대로 구현 |
| Docker Compose (TimescaleDB + Redis) | 완료 | 개발용/운영용 분리 |
| Flyway 마이그레이션 V1~V26 | 완료 | V26: position/paper_trading.position에 stop_loss_price, take_profit_price 컬럼 추가 |
| Upbit REST API 캔들 수집기 | 완료 | UpbitRestClient + UpbitCandleCollector (Rate Limiting 110ms) |
| 초기 4전략 (VWAP, EMA Cross, Bollinger, Grid) | 완료 | strategy-lib 모듈, fromParams() 팩토리 추가 |
| BacktestEngine (Look-Ahead Bias 방지) | 완료 | 다음 캔들 시가 기준 체결, 매수 수수료 PnL 반영, Partial Fill continue 제거 |
| WalkForwardTestRunner (Overfitting 방지) | 완료 | In-Sample/Out-of-Sample 분할 |
| FillSimulator (Market Impact + Partial Fill) | 완료 | 백테스트 현실성 강화 |
| MetricsCalculator (8종 성과 지표) | 완료 | Calmar Ratio 수식 수정, Recovery Factor 분리 |
| MarketRegimeDetector (ADX 기반) | 완료 | TREND/RANGE/VOLATILITY/TRANSITIONAL (v3.1에서 고도화) |
| PortfolioManager | 완료 | 전략 충돌 방지, 자본 할당 |
| RiskEngine | 완료 | Fixed Fractional Position Sizing, Correlation Risk 추가 (v3.1) |
| 3단 로그 시스템 (Logback) | 완료 | system/strategy/trade 분리 |
| AsyncConfig 스레드 풀 3분리 | 완료 | 시세/주문/일반 |
| RedisConfig | 완료 | JSON 직렬화, 캐시별 TTL |
| SchedulerConfig | 완료 | 전용 스레드풀 3개, Graceful shutdown 30초, 에러 핸들러 |
| 단위 테스트 (67개+) | 완료 | Phase S3 완료 기준 (v3.1), 이후 전략 개선 코드 반영 |
| Phase 1 전략 Config 클래스 | 완료 | VwapConfig/EmaCrossConfig/BollingerConfig/GridConfig |

### 1.2 Phase 2 - 웹 대시보드 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Next.js 16.1.6 + TypeScript + Tailwind 초기 설정 | 완료 | App Router |
| Sidebar 네비게이션 | 완료 | Phase별 메뉴, 비활성 표시, 실전매매 이력 메뉴 추가 (v3.1) |
| 대시보드 (/) | 완료 | 요약 카드 + 최근 백테스트 |
| 백테스트 이력 (/backtest) | 완료 | |
| 백테스트 신규 실행 (/backtest/new) | 완료 | 설계서 외 추가 분리 |
| 백테스트 결과 상세 (/backtest/[id]) | 완료 | 차트 + 지표 |
| 전략 비교 (/backtest/compare) | 완료 | 최대 6개 선택 비교 |
| Walk Forward UI (/backtest/walk-forward) | 완료 | 전략/코인 선택, inSampleRatio, windowCount, 결과 차트 |
| 데이터 수집 관리 (/data) | 완료 | 수집 요청 + 현황 |
| 전략 로그 조회 (/logs) | 완료 | 페이지네이션 |
| 프론트-백 API 연동 | 완료 | 모든 경로 일치 확인 |
| 다크 모드 (ThemeProvider) | 완료 | 기본 dark, localStorage 유지 |
| Header 컴포넌트 | 완료 | 다크모드 토글 포함 |
| 공통 UI 컴포넌트 | 완료 | Button/Card/Badge/Spinner |

### 1.3 Phase 3 - 전략 개선 및 추가 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| 추가 전략 6종 (RSI, MACD, Supertrend, ATR Breakout, Orderbook, StochasticRSI) | 완료 | v3.2: 코드 품질 개선 (StrategyParamUtils, IndicatorUtils 공통화) |
| StrategyController (CRUD) | 완료 | Registry(읽기) + DB(CRUD) 이중 구조 |
| StrategyConfigEntity DB 영속화 | 완료 | manual_override 플래그 포함 |
| 전략 관리 페이지 (/strategies) | 완료 | 10개 전략 카드 + 상태 배지 |
| StrategyConfigForm (동적 파라미터 폼) | 완료 | |
| MarketRegimeFilter | 완료 | TREND/RANGE/VOLATILITY/TRANSITIONAL별 매핑 |
| MarketRegimeAwareScheduler | 완료 | 1시간 주기 자동 스위칭 |
| 수동 오버라이드 | 완료 | /toggle → manualOverride=true |

### 1.4 Phase 3.5 - Paper Trading (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| paper_trading 스키마 (V8 + V9 + V10) | 완료 | |
| Entity (VirtualBalance, PaperPosition, PaperOrder) | 완료 | |
| PaperTradingService (전략 실행 + 가상 체결) | 완료 | TimeframeUtils DI 수정 (v3.1) |
| PaperTradingController (9개 API) | 완료 | 이력 삭제 엔드포인트 추가 (v3.1) |
| 멀티세션 지원 (최대 5개) | 완료 | 설계 초과 품질 |
| 모의투자 페이지 (/paper-trading) | 완료 | 시작/중단/세션 관리 |
| 세션 상세 (/paper-trading/[sessionId]) | 완료 | 캔들 차트 + 매수/매도 마커 + 매매 요약 섹션 (v3.1) |
| 모의투자 이력 (/paper-trading/history) | 완료 | |
| 차트 가로 스크롤 | 완료 | 60개 초과 시 포인트당 14px 고정 너비 (v3.1) |

### 1.5 Phase 4 - 실전매매 (완성도: 백엔드 99% / 프론트엔드 95%)

#### Phase 4 백엔드 (v3.2 기준)

| 항목 | 상태 | 비고 |
|------|------|------|
| LiveTradingService (다중 세션) | 완료 | orphan guard, TRANSITIONAL 신규 진입 금지 (v3.2), N+1 제거 |
| TradingController (세션 CRUD + 포지션/주문/리스크) | 완료 | 14개 API |
| OrderExecutionEngine (6단계 상태 머신) | 완료 | getOrders(Pageable) 추가 |
| PositionService | 완료 | getOpenPositions(), getPosition() |
| RiskManagementService | 완료 | @Transactional readOnly, 절대가 SL/TP 체크 (v3.2) |
| ExchangeHealthMonitor | 완료 | 연속 3회 실패 → DOWN, DEGRADED 중간 상태 추가 (v3.2) |
| TelegramNotificationService | 완료 | 즉시 알림 + 일별 요약, 비상정지 사유 전송 (v3.2), 버그 수정 |
| UpbitOrderClient | 완료 | char[] 기반 JWT, buildSecretKeySpec() 보안 강화 |
| UpbitWebSocketClient | 완료 | disconnect()/destroy() 분리, 자동 재연결 |
| UpbitRestClient Rate Limiting | 완료 | synchronized throttle() 110ms |
| GlobalExceptionHandler | 완료 | BAD_REQUEST/CONFLICT/INTERNAL_ERROR 범용화 |
| BacktestService @Transactional 제거 | 완료 | PostgreSQL cascade 실패 수정 |
| docker-compose.prod.yml healthcheck | 완료 | pg_isready, redis-cli ping |
| TimeframeUtils | 완료 | M15/M30/H4 타임프레임 누락 수정 |
| SecurityConfig (ApiTokenAuthFilter) | 완료 | API 토큰 인증 필터 구현. 기본값 제거 + 환경변수 필수 처리는 P0 잔여 |
| Circuit Breaker (Auto Kill-Switch) | 완료 | MDD/연속손실 임계값 초과 시 세션 강제 비상정지, RiskManagementService + Flyway V24 (2026-03-22) |
| WebSocket 실시간 손절 통합 | 완료 | RealtimePriceEvent + UpbitWebSocketClient @Component, 5초 throttle 손절 체크 (2026-03-22) |
| PortfolioSyncService | 완료 | 거래소 잔고 5분 주기 동기화, ApplicationReadyEvent 1회 즉시 동기화 (2026-03-22) |
| GridStrategy 세션별 격리 | 완료 | StrategyRegistry 팩토리 패턴, 다중 세션 상태 오염 방지 (2026-03-22) |
| CLOSING 포지션 5분 타임아웃 롤백 | 완료 | V23 (position.closing_at), 미체결 고착 시 OPEN 롤백 (2026-03-22) |
| reconcileOnStartup() 장애 복구 | 완료 | 서버 재시작 시 PENDING/OPEN 미완 주문 자동 복구 (2026-03-21) |
| ORDERBOOK REST 호가창 연동 | 완료 | UpbitRestClient.getOrderbook(), 실값 주입 (실패 시 캔들 근사 폴백) (2026-03-21) |
| 텔레그램 낙폭 경고 | 완료 | DRAWDOWN_WARNING, 손절 한도 50% 이상 손실 시 30분 쿨다운 (2026-03-21) |
| 수수료율 getOrderChance() 연동 | 완료 | 하드코딩 0.0005 제거, API 실패 시 폴백 (2026-03-23) |
| 실전매매 다중전략 | 완료 | MultiStrategyLiveTradingRequest + POST /sessions/multi (2026-03-20) |
| 세션별 투자비율 investRatio | 완료 | V19, 기본 80%, executeSessionBuy() 연동 (2026-03-20) |
| PortfolioManager race condition 수정 | 완료 | canAllocate()/getAvailableCapital() synchronized (2026-03-22) |
| 주간 리포트 API | **미구현** | GET /api/v1/reports/weekly |

#### Phase 4 프론트엔드 (v3.2 완료)

| 항목 | 상태 | 비고 |
|------|------|------|
| 실전매매 세션 관리 (/trading) | 완료 | |
| 세션 상세 (/trading/[sessionId]) | 완료 | 포지션/주문 + 매매 요약 섹션 |
| 실전매매 이력 (/trading/history) | 완료 | 전체/운영중/종료 요약 카드 + 세션별 수익률 테이블 |
| 리스크 설정 (/trading/risk) | 완료 | |
| Sidebar "실전매매 이력" 메뉴 | 완료 | /trading/history 링크 |
| useTrading hook | 완료 | createSession, startSession, stopSession, emergencyStop |
| 프론트엔드 로그인 인증 | 완료 | Next.js middleware + auth_session 쿠키, AUTH_PASSWORD/AUTH_SECRET 환경변수 (2026-03-20) |
| API proxy 패턴 (서버사이드 토큰) | 완료 | NEXT_PUBLIC_API_TOKEN → 서버사이드 API_TOKEN, /api/proxy/[...path]/route.ts (2026-03-23) |
| Upbit API 상태 페이지 보강 | 완료 | upbit-status/page.tsx 전면 재작성, 잔고·WebSocket·캔들·현재가 섹션 (2026-03-21) |
| 실전매매 다중전략 체크박스 UI | 완료 | 2개 이상 선택 시 /sessions/multi 일괄 생성 (2026-03-20) |
| /positions, /orders 독립 페이지 | **미구현(허용)** | 세션별 포지션/주문으로 대체 |

### 1.5.1 Phase 5 - 손익 대시보드 (완성도: 100%) [v3.2 신규]

| 항목 | 상태 | 비고 |
|------|------|------|
| 손익 대시보드 (/performance) | 완료 | 실전/모의 탭, 요약 카드 7개, 세션별 테이블 |
| 수수료 집계 정상화 | 완료 | `getPerformanceSummary()` N+1 제거, PositionRepository.findBySessionIdIn() |
| PerformanceController | 완료 | 실전/모의 성과 통합 집계 API |

### 1.6 Phase S1~S5 - 전략 고도화 (완성도: 100%) [v3.1 완료 / v3.2 추가 개선]

| Phase | 항목 | 상태 |
|-------|------|------|
| S1 | Supertrend upperBand/lowerBand 분리 | 완료 |
| S1 | Grid 하드코딩 제거 | 완료 |
| S1 | Grid StatefulStrategy (activeLevels 중복 매매 방지) | 완료 |
| S1 | ConflictingSignalTest 케이스 | 완료 |
| S2 | MarketRegimeDetector TRANSITIONAL + Hysteresis + BB Bandwidth + ATR Spike | 완료 |
| S2 | RiskEngine Fixed Fractional + Correlation Risk | 완료 |
| S3 | StrategySelector (Regime별 전략 그룹 + 가중치) | 완료 |
| S3 | CompositeStrategy (Weighted Voting) | 완료 |
| S3 | WeightedStrategy 래퍼 | 완료 |
| S4 | Supertrend ATR O(n²)→O(n) 최적화 | 완료 |
| S4 | EMA Cross ADX > 25 필터 + 기간 슬로우화 | 완료 |
| S4 | Bollinger ADX < 25 필터 + Squeeze 감지 | 완료 |
| S4 | RSI 피봇 다이버전스 + 임계값 강화 | 완료 |
| S4 | ATR Breakout 거래량 필터 | 완료 |
| S4 | Orderbook 호가 Delta 추적 | 완료 |
| S5 | StrategySignal suggestedStopLoss/TakeProfit 확장 | 완료 |
| S5 | MultiTimeframeFilter | 완료 |
| S5 | CandleDownsampler | 완료 |
| S5 | TimeframePreset | 완료 |
| S5 | BacktestEngine Strategy 오버로드 | 완료 |
| S5 | 2025 H1 BTC/ETH 백테스트 실행 및 결과 문서화 | 완료 |
| S6 (v3.2) | MACD 히스토그램 기울기 필터 + 제로라인 필터 추가 | 완료 |
| S6 (v3.2) | StochRSI 임계값 완화(15→20/85→80) + 2캔들 연속 + 거래량 확인 | 완료 |
| S6 (v3.2) | VWAP 임계값 완화(2.5→1.5%) + ADX 상한 25→35 + 앵커 방식 개선 | 완료 |
| S6 (v3.2) | GlobalRiskManager 분리 — 포지션별 SL/TP 절대가 저장 + 매 틱 체크 | 완료 |
| S6 (v3.2) | COMPOSITE_BTC EMA20/50 방향 필터 (추세 역행 신호 억제) | 완료 |
| S6 (v3.2) | COMPOSITE TRANSITIONAL 신규 진입 금지 (기존 포지션 유지만 허용) | 완료 |
| S6 (v3.2) | COMPOSITE_ETH 백테스트/실시간 가중치 분리 | 완료 |
| S6 (v3.2) | StrategyParamUtils, IndicatorUtils 공통화 (중복 코드 ~250줄 제거) | 완료 |

### 1.7 인프라 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| docker-compose.yml (개발용) | 완료 | DB + Redis |
| docker-compose.prod.yml (운영용) | 완료 | 4개 서비스, healthcheck |
| Dockerfile (Backend - multi-stage) | 완료 | eclipse-temurin:17 |
| Dockerfile (Frontend - multi-stage) | 완료 | node:20-alpine + standalone |
| .env.example | 완료 | |
| Flyway V1~V26 | 완료 | V19(investRatio) V22(position_fee) V23(closing_at) V24(circuit_breaker) V26(SL/TP 절대가) |
| SwaggerConfig | 완료 | |
| GlobalExceptionHandler | 완료 | 에러코드 범용화 |
| Prometheus/Grafana 모니터링 | 완료 | docker-compose.prod.yml에 prometheus:9090 + grafana:3001, monitoring/ 디렉터리 (2026-03-22) |
| CI/CD GitHub Actions | 완료 | .github/workflows/ci.yml — backend(Gradle+TimescaleDB) / frontend(lint+build) / Docker 이미지 빌드 (2026-03-23) |
| AsyncConfig graceful shutdown | 완료 | orderExecutor(30s) / marketDataExecutor(10s) / taskExecutor(15s) 각각 waitForTasks 설정 (2026-03-23) |

---

## 2. 테이블 소유권 분리 구조

### 2.1 분리 구조 개요

```
crypto_auto_trader (단일 DB, TimescaleDB)

[백테스팅 전용]
├── backtest_run          ← 백테스트 실행 기록
├── backtest_metrics      ← 성과 지표
└── backtest_trade        ← 체결 기록 (market_regime VARCHAR(20))

[모의투자 전용] paper_trading 스키마
├── paper_trading.virtual_balance
├── paper_trading.position
├── paper_trading.order
├── paper_trading.strategy_log
└── paper_trading.trade_log

[실전투자 전용] public 스키마
├── live_trading_session  ← 세션 (DEFAULT status='CREATED')
├── position              ← session_id FK
└── order                 ← session_id FK

[공통 인프라]
├── candle_data           ← TimescaleDB hypertable
├── strategy_config       ← manual_override 플래그
├── strategy_log / trade_log / strategy_signal
├── risk_config
├── market_data_cache     ← 실시간 싱크 전용
└── strategy_type_enabled ← 전략 10종 ON/OFF
```

### 2.2 테이블 격리 검증 결과 (v3.1)

| 서비스 | 격리 상태 | 비고 |
|--------|----------|------|
| BacktestService | 완료 | backtest_* 전용 테이블만 사용 |
| PaperTradingService | 완료 | paper_trading.* 스키마만 접근 |
| LiveTradingService | 완료 | orphan guard + session_id 필터 카운트 추가 |

---

## 3. Phase별 완성도 요약 (v3.2 최종)

| Phase | 설명 | v3.1 완성도 | v3.2 완성도 |
|-------|------|------------|------------|
| Phase 1 | 백테스팅 엔진 | 100% | **100%** |
| Phase 2 | 웹 대시보드 | 100% | **100%** |
| Phase 3 | 전략 관리 | 100% | **100%** (전략 14종으로 확장) |
| Phase 3.5 | Paper Trading | 100% | **100%** |
| Phase 4 백엔드 | 실전 매매 백엔드 | 95% | **~99%** (주간 리포트 API만 미구현) |
| Phase 4 프론트 | 실전 매매 프론트 | 90% | **~97%** (/positions, /orders 독립 페이지 미구현(허용)) |
| Phase 5 | 손익 대시보드 | 미착수 | **100%** (실전/모의 탭, 7개 요약 카드) |
| Phase S1~S5 | 전략 고도화 | 100% | **100%** (S6 추가 개선 완료) |
| 인프라 | Docker/Flyway | 100% | **100%** (Flyway V1~V26) |
| **전체** | Phase 1~5 + S1~S5 | ~95% | **~98%** |

---

## 4. 보강 작업 이력

### v1.0 → v2.0 (2026-03-08)

| 단계 | 항목 | 완료일 |
|------|------|--------|
| 1단계 P0 | DESIGN.md v1.2 업데이트 | 2026-03-08 |
| 2단계 P1 | 공통 UI 컴포넌트 분리 (Button/Card/Badge/Spinner) | 2026-03-08 |
| 2단계 P1 | Header 컴포넌트 분리 (다크모드 토글) | 2026-03-08 |
| 2단계 P1 | Phase 1 전략 Config fromParams() 팩토리 메서드 | 2026-03-08 |
| 3단계 P2 | RSI/MACD/Supertrend/ATR Breakout/Orderbook 로직 구현 | 2026-03-08 |
| 3단계 P2 | StochasticRsi 구현 | 2026-03-08 |
| 3단계 P2 | MarketRegimeFilter + MarketRegimeAwareScheduler 연동 | 2026-03-08 |
| 4단계 P2 | RedisConfig, SchedulerConfig | 2026-03-08 |

### v2.0 → v3.0 (2026-03-12)

| 항목 | 내용 | 완료일 |
|------|------|--------|
| DB 물리 분리 완료 | 백테스팅 / 모의+실전 분리 | 2026-03-12 |
| 테이블 소유권 명확화 | paper_trading.* / live_trading_session + session_id FK | 2026-03-12 |

### v3.0 → v3.1 (2026-03-15)

| 항목 | 내용 | 완료일 |
|------|------|--------|
| Phase 4 백엔드 전체 구현 | TradingController, LiveTradingService, OrderExecutionEngine, UpbitOrderClient 등 | 2026-03-15 |
| Phase 4 프론트엔드 구현 | /trading, /trading/[sessionId], /trading/history, /trading/risk | 2026-03-15 |
| Phase S1~S5 전략 고도화 | MarketRegimeDetector, RiskEngine, CompositeStrategy, MultiTimeframeFilter 전체 | 2026-03-15 |
| 전략 파라미터 최적화 | ADX 필터 7개 전략 + 임계값 강화 | 2026-03-15 |
| 2025 H1 백테스트 | KRW-BTC/ETH 10개 전략 결과 문서화 | 2026-03-15 |
| 버그 수정 다수 | BacktestService @Transactional, market_regime 길이, orphan guard 등 | 2026-03-15 |
| 보안 강화 | UpbitOrderClient char[] JWT, UpbitRestClient Race Condition 수정 | 2026-03-15 |
| docker-compose healthcheck | pg_isready, redis-cli ping, service_healthy depends_on | 2026-03-15 |
| 실전매매 이력 페이지 | /trading/history 신규 구현 | 2026-03-15 |
| 매매 요약 섹션 | 모의/실전매매 세션 상세 페이지에 매수/매도 횟수, 실현손익, 승률 추가 | 2026-03-15 |

### v3.1 → v3.2 (2026-03-23)

| 항목 | 내용 | 완료일 |
|------|------|--------|
| GlobalRiskManager 분리 | position/paper_trading.position에 stop_loss_price, take_profit_price 절대가 저장 (Flyway V26) | 2026-03-23 |
| COMPOSITE_BTC EMA 방향 필터 | EMA20>EMA50 상승추세 시 SELL 억제, EMA20<EMA50 하락추세 시 BUY 억제 | 2026-03-23 |
| COMPOSITE TRANSITIONAL 신규 진입 금지 | BUY 신호 차단 → 기존 포지션 SELL만 허용. PaperTradingService + LiveTradingService 동일 적용 | 2026-03-23 |
| COMPOSITE_ETH 백테스트/실시간 가중치 분리 | 백테스트: ATR_BREAKOUT 0.7 / ORDERBOOK 0.1 / EMA 0.2 vs 실시간: 0.5/0.3/0.2 | 2026-03-23 |
| MACD 코드 개선 | 히스토그램 기울기 필터 + 제로라인 필터 추가. 가짜 크로스 ~30% 감소 | 2026-03-23 |
| StochRSI 코드 개선 | 임계값 완화(15→20/85→80) + 2캔들 연속 크로스 확인 + 거래량 확인 조건 추가 | 2026-03-23 |
| VWAP 코드 개선 | 임계값 2.5→1.5%, ADX 상한 25→35, anchorSession=true (UTC 00:00 기점) | 2026-03-23 |
| Phase 5 손익 대시보드 | /performance 실전/모의 탭, 요약 카드 7개, 세션별 수익률 테이블, 수수료 집계 정상화 | 2026-03-23 |
| 전략 14종 확장 | COMPOSITE_BTC, COMPOSITE_ETH, MACD_STOCH_BB 복합 전략 + types.ts 동기화 | 2026-03-23 |
| 공통 유틸 추가 | StrategyParamUtils, IndicatorUtils — 중복 코드 ~250줄 제거 | 2026-03-23 |
| 비상정지 텔레그램 사유 전송 | ExchangeDownEvent에 reason 필드 추가, 연속 3회 실패 → DOWN (DEGRADED 중간 상태) | 2026-03-23 |
| 텔레그램 요약 버그 수정 | BUY/SELL 체결 시 bufferTradeEvent() 누락 수정 → 12/24시 요약 정상화 | 2026-03-23 |

---

## 5. 서브에이전트 현황

| 에이전트 | 출력 문서 | 상태 |
|----------|-----------|------|
| SparkAI (01) | IDEA.md | 완료 |
| PLAN (02) | PLAN.md | 완료 |
| Design (03) | DESIGN.md v1.4 | **완료** (2026-03-23 갱신) |
| Do-Backend (04a) | Phase 1~5 + S1~S6 전체 | 완료 |
| Do-Frontend (04b) | Phase 2~5 프론트엔드 전체 | 완료 |
| Check (05) | CHECK_RESULT.md v5.0 | **완료 (2026-03-23 재검증)** — SecurityConfig 완료 확인, 누락 3건 식별 |
| Report (06) | REPORT_EXECUTIVE.md v2.0, REPORT_TECHNICAL.md v2.0 | **완료 (2026-03-23 갱신)** — v3.2 기준 전면 재작성 |

---

## 6. 미완 항목 및 권장 다음 단계

### P0 (즉시 — 배포 전 필수)

| # | 항목 | 예상 작업 |
|---|------|----------|
| 1 | Spring Security / API 인증 구현 | 4~8h |
| 2 | API 토큰 기본값 제거 — 환경변수 미설정 시 서버 시작 실패 | 1h |
| 3 | Redis requirepass 설정 — docker-compose.prod.yml 인증 추가 | 1h |
| 4 | Swagger 프로덕션 비활성화 — @Profile("!prod") 적용 | 0.5h |
| 5 | CORS 운영 도메인 한정 — allowedOriginPatterns("*") 제거 | 0.5h |
| 6 | TimescaleDB 백업 설정 — pg_dump 크론 또는 자동 백업 | 2h |

### P1 (단기 1~2주)

| # | 항목 | 예상 작업 |
|---|------|----------|
| 7 | MACD 파라미터 그리드 서치 백테스트 — (12,26,9) 외 최적값 탐색 | 2h |
| 8 | StochRSI + RSI 다이버전스 결합 — 고신뢰 복합 신호 | 4~8h |
| 9 | 2023~2025년 전체 기간 백테스트 — 장기 성과 검증 | 2h |
| 10 | TradingController 예외 처리 패턴 통일 | 3h |
| 11 | StrategyController DTO 전환 + Bean Validation | 4h |
| 12 | GET /api/v1/reports/weekly 구현 | 2h |

### P2 (중기 1개월)

| # | 항목 | 예상 작업 |
|---|------|----------|
| 13 | Redis Pub/Sub 이벤트 파이프라인 (EventPublisher/EventSubscriber) | 8h |
| 14 | 다중 거래소 지원 — Binance, Bybit ExchangeAdapter 확장 | 20h |
| 15 | Python ML 통합 — gRPC FastAPI 브릿지 | 40h |
| 16 | 동적 슬리피지 모델링 — 호가창 깊이 기반 현실성 강화 | 8h |
| 17 | 무중단 배포 (Blue-Green/Rolling) — Nginx/Traefik 도입 | 12h |

### 진행 흐름

```
현재 상태: Phase 1~5 + S1~S6 전체 ~97% 완성
                   |
     [Step 1] 보안 강화 (P0 필수)
     → SecurityConfig 구현 (Spring Security + API Key)
     → Redis requirepass, Swagger 비활성화, CORS 한정
                   |
     [Step 2] 실거래 검증 (소액)
     → UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수 설정
     → docker-compose.prod.yml 재빌드
     → 소액 실거래 테스트 (1세션, GRID 전략, KRW-BTC)
     → 텔레그램 알림 정상 수신 확인
                   |
     [Step 3] 전략 추가 개선 (P1)
     → MACD 파라미터 그리드 서치 백테스트
     → StochRSI + RSI 다이버전스 결합 (고복잡도)
     → 2023~2025 장기 백테스트
```

---

작성: 개발 상태 검증
기준: 2026-03-23 (v3.3)
최종 완성도: Phase 1~5 + S1~S6 전체 ~98%
