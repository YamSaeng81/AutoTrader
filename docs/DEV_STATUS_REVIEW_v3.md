# CryptoAutoTrader - 개발 상태 검증 v3.0

## 문서 정보

- 최초 검증일: 2026-03-08 (v1.0)
- v2.0 갱신일: 2026-03-08
- v3.0 갱신일: 2026-03-12
- **v3.1 갱신일: 2026-03-15** — Phase 4 프론트엔드 완성 + 전략 고도화 Phase S1~S5 반영
- 검증 범위: Phase 1 ~ Phase 4 + Phase S1~S5 (전략 고도화) + DB 분리 아키텍처
- 기반 문서: PLAN.md, DESIGN.md v1.3, CHECK_RESULT.md v4.0, PROGRESS.md (2026-03-15)
- 목적: 전체 개발 상태 이력 관리 및 Phase별 완성도 추적

---

## 1. Phase별 개발 상태 종합

### 1.1 Phase 1 - 백테스팅 엔진 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Gradle 멀티모듈 (core-engine, strategy-lib, exchange-adapter, web-api) | 완료 | 의존성 방향 설계대로 구현 |
| Docker Compose (TimescaleDB + Redis) | 완료 | 개발용/운영용 분리 |
| Flyway 마이그레이션 V1~V13 | 완료 | hypertable + 압축 정책 + manual_override 컬럼 |
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
| 단위 테스트 (67개) | 완료 | Phase S3 완료 기준 (v3.1) |
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
| 추가 전략 6종 (RSI, MACD, Supertrend, ATR Breakout, Orderbook, StochasticRSI) | 완료 | |
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

### 1.5 Phase 4 - 실전매매 (완성도: 백엔드 95% / 프론트엔드 90%)

#### Phase 4 백엔드 (v3.1 기준)

| 항목 | 상태 | 비고 |
|------|------|------|
| LiveTradingService (다중 세션) | 완료 | orphan guard 추가, session 필터 카운트 (v3.1) |
| TradingController (세션 CRUD + 포지션/주문/리스크) | 완료 | 14개 API |
| OrderExecutionEngine (6단계 상태 머신) | 완료 | getOrders(Pageable) 추가 (v3.1) |
| PositionService | 완료 | getOpenPositions(), getPosition() |
| RiskManagementService | 완료 | @Transactional readOnly 수정, N+1 제거 (v3.1) |
| ExchangeHealthMonitor | 완료 | |
| TelegramNotificationService | 완료 | 즉시 알림 + 일별 요약, 빈 버퍼도 전송 (v3.1) |
| UpbitOrderClient | 완료 | char[] 기반 JWT, buildSecretKeySpec() 보안 강화 (v3.1) |
| UpbitWebSocketClient | 완료 | disconnect()/destroy() 분리, 자동 재연결 (v3.1) |
| UpbitRestClient Rate Limiting | 완료 | synchronized throttle() 110ms (v3.1) |
| GlobalExceptionHandler | 완료 | BAD_REQUEST/CONFLICT/INTERNAL_ERROR 범용화 (v3.1) |
| BacktestService @Transactional 제거 | 완료 | PostgreSQL cascade 실패 수정 (v3.1) |
| docker-compose.prod.yml healthcheck | 완료 | pg_isready, redis-cli ping (v3.1) |
| TimeframeUtils | 완료 | M15/M30/H4 타임프레임 누락 수정 (v3.1) |
| SecurityConfig | **미구현** | Spring Security / API 인증 미착수 (P0 보안 필수) |
| 주간 리포트 API | **미구현** | GET /api/v1/reports/weekly |

#### Phase 4 프론트엔드 (v3.1 완료)

| 항목 | 상태 | 비고 |
|------|------|------|
| 실전매매 세션 관리 (/trading) | 완료 | |
| 세션 상세 (/trading/[sessionId]) | 완료 | 포지션/주문 + 매매 요약 섹션 (v3.1) |
| 실전매매 이력 (/trading/history) | 완료 | 전체/운영중/종료 요약 카드 + 세션별 수익률 테이블 (v3.1) |
| 리스크 설정 (/trading/risk) | 완료 | |
| Sidebar "실전매매 이력" 메뉴 | 완료 | /trading/history 링크 (v3.1) |
| useTrading hook | 완료 | createSession, startSession, stopSession, emergencyStop |
| /positions, /orders 독립 페이지 | **미구현(허용)** | 세션별 포지션/주문으로 대체 |

### 1.6 Phase S1~S5 - 전략 고도화 (완성도: 100%) [v3.1 신규]

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

### 1.7 인프라 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| docker-compose.yml (개발용) | 완료 | DB + Redis |
| docker-compose.prod.yml (운영용) | 완료 | 4개 서비스, healthcheck (v3.1) |
| Dockerfile (Backend - multi-stage) | 완료 | eclipse-temurin:17 |
| Dockerfile (Frontend - multi-stage) | 완료 | node:20-alpine + standalone |
| .env.example | 완료 | |
| Flyway V1~V13 + 수동 DDL | 완료 | market_regime VARCHAR(20) 수정 |
| SwaggerConfig | 완료 | |
| GlobalExceptionHandler | 완료 | 에러코드 범용화 (v3.1) |

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

## 3. Phase별 완성도 요약 (v3.1 최종)

| Phase | 설명 | v3.0 완성도 | v3.1 완성도 |
|-------|------|------------|------------|
| Phase 1 | 백테스팅 엔진 | 100% | **100%** (Calmar 수식 수정, 수수료 정확도 개선) |
| Phase 2 | 웹 대시보드 | 100% | **100%** |
| Phase 3 | 전략 관리 | 100% | **100%** |
| Phase 3.5 | Paper Trading | 100% | **100%** (매매 요약 섹션, 차트 스크롤 추가) |
| Phase 4 백엔드 | 실전 매매 백엔드 | 미착수 | **95%** (SecurityConfig 미구현) |
| Phase 4 프론트 | 실전 매매 프론트 | 미착수 | **90%** (/positions, /orders 독립 페이지 미구현) |
| Phase S1~S5 | 전략 고도화 | 미착수 | **100%** (전체 완료) |
| 인프라 | Docker/Flyway | 100% | **100%** (healthcheck 추가) |
| **전체** | Phase 1~4 + S1~S5 | 100% (1~3.5만) | **~95%** |

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

---

## 5. 서브에이전트 현황

| 에이전트 | 출력 문서 | 상태 |
|----------|-----------|------|
| SparkAI (01) | IDEA.md | 완료 |
| PLAN (02) | PLAN.md | 완료 |
| Design (03) | DESIGN.md v1.3 | 완료 (v1.4 갱신 권장) |
| Do-Backend (04a) | Phase 1~4 + S1~S5 전체 | 완료 |
| Do-Frontend (04b) | Phase 2~4 프론트엔드 전체 | 완료 |
| Check (05) | CHECK_RESULT.md v4.0 | 완료 (2026-03-15) |
| Report (06) | REPORT_EXECUTIVE.md, REPORT_TECHNICAL.md | **완료** (2026-03-15) |

---

## 6. 미완 항목 및 권장 다음 단계

### P0 (즉시 — 배포 전 필수)

| # | 항목 | 예상 작업 |
|---|------|----------|
| 1 | Spring Security / API 인증 구현 | 4~8h |
| 2 | UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수 설정 + 서버 재빌드 | 2h |

### P1 (단기 1~2주)

| # | 항목 | 예상 작업 |
|---|------|----------|
| 3 | STOCHASTIC_RSI 구조 재설계 또는 제거 | 4~8h |
| 4 | MACD 히스토그램 기울기 필터 추가 | 2h |
| 5 | TradingController 예외 처리 패턴 통일 | 3h |
| 6 | StrategyController DTO 전환 + Bean Validation | 4h |
| 7 | GET /api/v1/reports/weekly 구현 | 2h |
| 8 | 텔레그램 수신 확인 (12:00/00:00 KST) | 0.5h |

### P2 (중기 1개월)

| # | 항목 | 예상 작업 |
|---|------|----------|
| 9 | Redis Pub/Sub 이벤트 파이프라인 (EventPublisher/EventSubscriber) | 8h |
| 10 | core-engine signal/ 패키지 (SignalEngine, TradingSignal) | 4h |
| 11 | core-engine position/ 패키지 (PositionManager, Position) | 3h |
| 12 | Flyway V16 — backtest_trade.market_regime VARCHAR(20) 정합성 | 1h |
| 13 | VWAP 임계값 재조정 (2.5% → 1.5% 재테스트) | 1h |
| 14 | DESIGN.md v1.4 업데이트 | 2h |
| 15 | 2023~2025년 전체 기간 백테스트 | 2h |
| 16 | CompositeStrategy 백테스트 연동 | 2h |

### 진행 흐름

```
현재 상태: Phase 1~4 + S1~S5 전체 ~95% 완성
                   |
     [Step 1] SecurityConfig 구현 (P0 보안 필수)
     → Spring Security + API Key or Basic Auth
     → 실전매매 API 보호
                   |
     [Step 2] Phase 4 배포 및 실거래 검증
     → UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수 설정
     → docker-compose.prod.yml 재빌드
     → 소액 실거래 테스트 (1세션, GRID 전략, KRW-BTC)
     → 텔레그램 알림 정상 수신 확인
                   |
     [Step 3] 전략 개선 (P1)
     → STOCHASTIC_RSI 제거 또는 재설계
     → MACD 히스토그램 기울기 필터 추가
                   |
     [Step 4] DESIGN.md v1.4 업데이트
     → API 경로, 컨트롤러 명칭, DB 스키마 변경 반영
```

---

작성: 개발 상태 검증
기준: 2026-03-15 (v3.1)
최종 완성도: Phase 1~4 + S1~S5 전체 ~95%
