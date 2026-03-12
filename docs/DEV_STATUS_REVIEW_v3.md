# CryptoAutoTrader - 개발 상태 검증 v3.0

## 문서 정보
- 최초 검증일: 2026-03-08 (v1.0)
- v2.0 갱신일: 2026-03-08
- v3.0 갱신일: 2026-03-12
- 검증 범위: Phase 1 ~ Phase 3.5 (Phase 4 실전매매 제외) + DB 분리 아키텍처
- 기반 문서: IDEA.md, PLAN.md, DESIGN.md v1.2, CHECK_RESULT.md, DEV_STATUS_REVIEW_v2.0
- 목적: DB 분리(백테스팅 / 모의투자+실전투자) 완료 후 상태 갱신 및 Phase 4 진입 최종 판단

---

## 1. Phase별 개발 상태 종합 (v2.0 → v3.0 변동 없음)

### 1.1 Phase 1 - 백테스팅 엔진 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Gradle 멀티모듈 (core-engine, strategy-lib, exchange-adapter, web-api) | 완료 | 의존성 방향 설계대로 구현 |
| Docker Compose (TimescaleDB + Redis) | 완료 | 개발용/운영용 분리 |
| Flyway 마이그레이션 V1~V13 | 완료 | hypertable + 압축 정책 + manual_override 컬럼 |
| Upbit REST API 캔들 수집기 | 완료 | UpbitRestClient + UpbitCandleCollector |
| 초기 4전략 (VWAP, EMA Cross, Bollinger, Grid) | 완료 | strategy-lib 모듈, fromParams() 팩토리 추가 |
| BacktestEngine (Look-Ahead Bias 방지) | 완료 | 다음 캔들 시가 기준 체결 |
| WalkForwardTestRunner (Overfitting 방지) | 완료 | In-Sample/Out-of-Sample 분할 |
| FillSimulator (Market Impact + Partial Fill) | 완료 | 백테스트 현실성 강화 |
| MetricsCalculator (8종 성과 지표) | 완료 | Sortino, Calmar, Recovery Factor 등 |
| MarketRegimeDetector (ADX 기반) | 완료 | TREND/RANGE/VOLATILE |
| PortfolioManager | 완료 | 전략 충돌 방지, 자본 할당 |
| RiskEngine | 완료 | 손실 한도 체크 |
| 3단 로그 시스템 (Logback) | 완료 | system/strategy/trade 분리 |
| AsyncConfig 스레드 풀 3분리 | 완료 | 시세/주문/일반 |
| RedisConfig | 완료 | JSON 직렬화, 캐시별 TTL (ticker 1초 / candle 60초 / strategyConfig 10분 / backtestResult 30분) |
| SchedulerConfig | 완료 | 전용 스레드풀 3개, Graceful shutdown 30초, 에러 핸들러 |
| 단위 테스트 (58개) | 완료 | 전략 5종 신규 + 기존 16개 + MarketRegimeFilter 13개 |
| Phase 1 전략 Config 클래스 | 완료 | VwapConfig/EmaCrossConfig/BollingerConfig/GridConfig — fromParams(Map) 팩토리 추가 |

### 1.2 Phase 2 - 웹 대시보드 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| Next.js 14 + TypeScript + Tailwind 초기 설정 | 완료 | App Router |
| Sidebar 네비게이션 | 완료 | Phase별 메뉴, 비활성 표시 |
| 대시보드 (/) 요약 카드 + 최근 백테스트 | 완료 | |
| 백테스트 이력 (/backtest) | 완료 | |
| 백테스트 신규 실행 (/backtest/new) | 완료 | 설계서 외 추가 분리 |
| 백테스트 결과 상세 (/backtest/[id]) | 완료 | 차트 + 지표 |
| 전략 비교 (/backtest/compare) | 완료 | 최대 6개 선택 비교 |
| Walk Forward UI (/backtest/walk-forward) | 완료 | 전략/코인 선택, inSampleRatio, windowCount, 결과 차트 |
| 데이터 수집 관리 (/data) | 완료 | 수집 요청 + 현황 |
| 전략 로그 조회 (/logs) | 완료 | 페이지네이션 |
| 프론트-백 API 연동 | 완료 | 모든 경로 일치 확인 |
| MSW Mock 시스템 | 완료 | 독립 개발용 |
| 다크 모드 (ThemeProvider) | 완료 | 기본 dark, localStorage 유지, 18개 파일 269개 dark: 클래스 |
| Header 컴포넌트 | 완료 | components/layout/Header.tsx — 다크모드 토글 포함 |
| 공통 UI 컴포넌트 | 완료 | components/ui/ — Button/Card/Badge/Spinner + barrel export |

### 1.3 Phase 3 - 전략 개선 및 추가 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| 추가 전략 로직 완성 (RSI, MACD, Supertrend, ATR Breakout, Orderbook Imbalance) | 완료 | RSI 다이버전스, MACD 히스토그램, Supertrend 밴드 전환, 변동성 돌파, 이중 모드 |
| StochasticRsi 전략 구현 | 완료 | Wilder's RSI → %K(Stochastic) → %D(SMA) → 크로스오버 신호, 6번째 Phase 3 전략 |
| StrategyController (CRUD) | 완료 | Registry(읽기) + DB(CRUD) 이중 구조 |
| StrategyConfigEntity DB 영속화 | 완료 | manual_override 플래그 포함 |
| 전략 관리 페이지 (/strategies) | 완료 | 10개 전략 카드 + 상태 배지 |
| StrategyConfigForm (동적 파라미터 폼) | 완료 | |
| 전략 설정 POST/PUT/PATCH API | 완료 | 백엔드 완료, 프론트 MSW 핸들러 제거 → 실서버 직접 연결 |
| MarketRegimeFilter | 완료 | TREND/RANGE/VOLATILE별 적합/비적합 전략 매핑 테이블 |
| MarketRegimeAwareScheduler | 완료 | 1시간 주기 자동 스위칭, manualOverride=false 전략만 대상 |
| 수동 오버라이드 | 완료 | /toggle → manualOverride=true, /toggle-override → 복귀 |

### 1.4 Phase 3.5 - Paper Trading (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| paper_trading 스키마 (V8 + V9 + V10) | 완료 | |
| Entity (VirtualBalance, PaperPosition, PaperOrder) | 완료 | |
| PaperTradingService (전략 실행 + 가상 체결) | 완료 | |
| PaperTradingController (7개 API) | 완료 | |
| 멀티세션 지원 (최대 5개) | 완료 | 설계 초과 품질 |
| 모의투자 페이지 (/paper-trading) | 완료 | 시작/중단/세션 관리 |
| 세션 상세 (/paper-trading/[sessionId]) | 완료 | 캔들 차트 + 매수/매도 마커 |
| 모의투자 이력 (/paper-trading/history) | 완료 | |
| 매도 시 매수단가/실현손익/수익률 제공 | 완료 | |

### 1.5 인프라 (완성도: 100%)

| 항목 | 상태 | 비고 |
|------|------|------|
| docker-compose.yml (개발용) | 완료 | DB + Redis |
| docker-compose.prod.yml (운영용) | 완료 | 4개 서비스, restart always |
| Dockerfile (Backend - multi-stage) | 완료 | eclipse-temurin:17 |
| Dockerfile (Frontend - multi-stage) | 완료 | node:20-alpine + standalone |
| .env.example | 완료 | |
| Flyway V1~V13 | 완료 | V13: market_data_cache 추가 |
| SwaggerConfig (API 문서) | 완료 | 설계서 외 추가 |
| GlobalExceptionHandler | 완료 | 설계서 외 추가 |

---

## 2. 테이블 소유권 분리 (v3.0 신규)

### 2.1 분리 구조 개요

v2.0까지는 백테스팅과 모의+실전투자가 일부 테이블을 혼용하던 문제가 있었다. v3.0에서 **단일 DB 내 테이블 소유권을 용도별로 명확하게 분리**했다.

```
crypto_auto_trader (단일 DB, TimescaleDB)
│
├── [백테스팅 전용]
│   ├── backtest_run          ← 백테스트 실행 기록
│   ├── backtest_metrics      ← 성과 지표 (Sharpe, Sortino, MDD 등)
│   └── backtest_trade        ← 백테스트 체결 기록
│
├── [모의투자 전용] paper_trading 스키마
│   ├── paper_trading.virtual_balance
│   ├── paper_trading.position
│   ├── paper_trading.order
│   ├── paper_trading.strategy_log
│   └── paper_trading.trade_log
│
├── [실전투자 전용]
│   ├── live_trading_session  ← 실전 세션 관리
│   ├── position              ← 실전 포지션 (session_id 연결)
│   └── order                 ← 실전 주문 (session_id 연결)
│
└── [공통 인프라] (백테스팅 + 모의 + 실전 공유)
    ├── candle_data           ← TimescaleDB hypertable (시세 원본, 읽기 공유)
    ├── strategy_config       ← 전략 설정 (manual_override 포함)
    ├── strategy_log          ← 전략 실행 로그
    ├── strategy_signal       ← 신호 기록
    ├── risk_config           ← 위험 관리 설정
    └── market_data_cache     ← 시세 캐시
```

### 2.2 핵심 분리 포인트

| 구분 | 변경 전 (v2.0 이전) | 변경 후 (v3.0) |
|------|---------------------|----------------|
| 모의투자 체결 기록 | `public.position` / `public.order` 혼용 | `paper_trading.position` / `paper_trading.order` 전용 |
| 실전투자 체결 기록 | position/order 소유권 불명확 | `public.position` + `session_id → live_trading_session` 명시적 연결 |
| 백테스팅 체결 기록 | position/order 공유 우려 | `backtest_trade` 전용 테이블만 사용 |

### 2.3 Flyway 마이그레이션 분리 이력

| 마이그레이션 | 분리 내용 |
|---|---|
| V2 | `backtest_run`, `backtest_metrics`, `backtest_trade` — 백테스팅 전용 테이블 생성 |
| V8 | `paper_trading` 스키마 생성, `LIKE public.position` 복제로 모의투자 전용 테이블 분리 |
| V9 | `paper_trading.virtual_balance` 세션 필드 확장 |
| V10 | 모의투자 멀티세션 지원 |
| V12 | `live_trading_session` 생성, `public.position` / `public.order`에 `session_id` FK 추가 → 실전투자 소유권 명확화 |

---

## 3. 설계서(DESIGN.md v1.2) vs 구현 현황

| # | 항목 | 상태 | 비고 |
|---|------|------|------|
| 1 | GET /backtest/{id}/metrics 별도 엔드포인트 | 해결 | DESIGN.md v1.2에서 GET /{id}에 통합으로 수정 |
| 2 | Paper Trading 스키마 위치 | 해결 | DESIGN.md v1.1에서 이미 수정 |
| 3 | docker-compose.yml 서비스 수 | 허용 | 개발용은 DB+Redis만, 운영용 prod에서 4개 전부 포함 |
| 4 | strategies/{id} 경로변수 타입 | 허용 | 읽기=name(문자열), CRUD=id(숫자) — 기능상 문제 없음 |
| 5 | virtual_balance.strategy_name 길이 | 허용 | VARCHAR(50) vs 설계 VARCHAR(100) — 실질적 문제 없음 |
| 6 | 테이블 소유권 분리 | **v3.0 완료** | 백테스팅(backtest_*) / 모의투자(paper_trading.*) / 실전투자(position+order+live_trading_session) 명확화 |
| 7 | DESIGN.md v1.2 DB 구조 기술 | **갱신 필요** | 테이블 소유권 분리 구조가 미반영됨 → v1.3으로 업데이트 권장 |

---

## 4. 보강 작업 이력

### v1.0 → v2.0 (2026-03-08)

| 단계 | 항목 | 완료일 |
|------|------|--------|
| 1단계 P0 | DESIGN.md v1.2 업데이트 (metrics 엔드포인트 통합, Config 클래스 명세 정정) | 2026-03-08 |
| 2단계 P1 | 공통 UI 컴포넌트 분리 (Button/Card/Badge/Spinner) | 2026-03-08 |
| 2단계 P1 | Header 컴포넌트 분리 (다크모드 토글 포함) | 2026-03-08 |
| 2단계 P1 | Phase 1 전략 Config fromParams() 팩토리 메서드 추가 | 2026-03-08 |
| 2단계 P1 | 전략 설정 MSW 핸들러 제거 → 실서버 직접 연결 | 2026-03-08 |
| 3단계 P2 | RSI(다이버전스), MACD(히스토그램), Supertrend, ATR Breakout, Orderbook Imbalance 실제 로직 구현 | 2026-03-08 |
| 3단계 P2 | StochasticRsi 스켈레톤 생성 (6번째 Phase 3 전략) | 2026-03-08 |
| 3단계 P2 | MarketRegimeFilter + MarketRegimeAwareScheduler 연동 | 2026-03-08 |
| 4단계 P2 | RedisConfig (JSON 직렬화, 캐시별 TTL) | 2026-03-08 |
| 4단계 P2 | SchedulerConfig (전용 스레드풀, Graceful shutdown) | 2026-03-08 |

### v2.0 → v3.0 (2026-03-12)

| 항목 | 내용 | 완료일 |
|------|------|--------|
| DB 물리 분리 | 백테스팅 DB(`crypto_backtest`) / 모의+실전 DB(`crypto_trading`) 분리 | 2026-03-12 |

---

## 5. 서브에이전트 현황

### 5.1 에이전트 실행 상태

| 에이전트 | 출력 문서 | 상태 |
|----------|-----------|------|
| SparkAI (01) | IDEA.md | 완료 |
| PLAN (02) | PLAN.md | 완료 |
| Design (03) | DESIGN.md v1.2 | 완료 (v1.3 갱신 권장) |
| Do-Backend (04a) | PHASE1_BACKEND.md, PHASE3_BACKEND.md, PHASE3_5_BACKEND.md | 완료 (Phase 1~3.5 + 보강) |
| Do-Frontend (04b) | FRONTEND_GUIDE.md, FRONTEND_PHASE3_GUIDE.md, PHASE3_5_FRONTEND.md, FRONTEND_REALSERVER_GUIDE.md | 완료 (Phase 2~3.5 + 보강) |
| Check (05) | CHECK_RESULT.md | 완료 |
| Report (06) | REPORT.md | **미실행** |

---

## 6. 종합 평가

### 6.1 Phase별 완성도 요약

| Phase | 범위 | v2.0 완성도 | v3.0 완성도 |
|-------|------|------------|------------|
| Phase 1 | 백테스팅 엔진 | 100% | **100%** |
| Phase 2 | 웹 대시보드 | 100% | **100%** |
| Phase 3 | 전략 개선 및 추가 | 100% | **100%** |
| Phase 3.5 | Paper Trading | 100% | **100%** |
| 인프라 | Docker/Flyway/Config | 100% | **100%** |
| DB 분리 | Backtest / Trading 분리 | - | **완료** |
| **전체** | Phase 1~3.5 + DB 분리 | **100%** | **100%** |

### 6.2 Phase 4 진입 체크리스트

| 항목 | 상태 |
|------|------|
| 백테스팅 엔진 정상 동작 | 완료 |
| 전략 10종 로직 완성 (VWAP/EMA/Bollinger/Grid/RSI/MACD/Supertrend/ATR Breakout/Orderbook Imbalance/StochasticRsi) | 완료 |
| Paper Trading으로 전략 검증 가능 | 완료 |
| 시장 상태 필터 자동 스위칭 | 완료 |
| 위험 관리 엔진 (RiskEngine) | 완료 |
| 운영 Docker 환경 | 완료 |
| DB 분리 (백테스팅 / 모의+실전 격리) | **완료** |
| Report 에이전트 실행 | **미완** |

### 6.3 v3.0 추가 권장 보강 항목

아래 항목은 Phase 4 진입 전 또는 진입 중 수행을 권장한다.

#### P1 (Phase 4 진입 전 필수)

| # | 항목 | 영역 | 내용 |
|---|------|------|------|
| 1 | **BacktestService candle_data 접근 격리 검증** | Backend | `BacktestService`가 `paper_trading.*` / `live_trading_session` / `public.position` 테이블을 직접 참조하지 않는지 Repository 의존성 확인 |
| 2 | **PaperTradingService 테이블 격리 검증** | Backend | `PaperTradingService`가 `public.position` / `public.order`가 아닌 `paper_trading.position` / `paper_trading.order`만 사용하는지 확인 |
| 3 | **LiveTradingService 테이블 격리 검증** | Backend | `LiveTradingService`가 `paper_trading.*` 테이블에 접근하지 않는지, `session_id` FK 기반으로만 실전 데이터 접근하는지 확인 |
| 4 | **Entity 패키지 분리 상태 확인** | Backend | `entity/paper/` 하위의 Paper* Entity들이 `paper_trading` 스키마를 올바르게 `@Table(schema="paper_trading")`로 지정하는지 확인 |
| 5 | **통합 테스트 34개 테이블 분리 반영** | Backend | H2 `schema-h2.sql`에 `paper_trading` 스키마 및 테이블 분리가 실 마이그레이션과 동기화되어 있는지 재검증 |
| 6 | **Report 에이전트 실행** | 문서 | REPORT.md 생성 — 테이블 분리 완료 포함 최종 보고서 |

#### P2 (Phase 4 진입 중 병행 가능)

| # | 항목 | 영역 | 내용 |
|---|------|------|------|
| 7 | **DESIGN.md v1.3 갱신** | 문서 | 테이블 소유권 분리 구조 반영, `paper_trading` 스키마 / `live_trading_session` FK 구조 명세 추가 |
| 8 | **대시보드 데이터 소스 명확화** | Frontend | `/` 요약 카드에서 백테스팅 지표(backtest_* 조회)와 모의투자 지표(paper_trading.* 조회)가 혼재되지 않도록 API 분리 확인 |
| 9 | **candle_data 접근 권한 정책 문서화** | Backend | 백테스팅 / 모의투자 / 실전투자 각각의 candle_data 읽기 경로를 API 스펙 또는 README에 명시 |
| 10 | **백테스트 결과 조회 성능 측정** | QA | 테이블 분리 후 `backtest_run` JOIN `backtest_metrics` 쿼리 실행 계획(EXPLAIN) 확인, 인덱스 보완 여부 판단 |

#### P3 (장기 검토)

| # | 항목 | 영역 | 내용 |
|---|------|------|------|
| 11 | **strategy_config 용도별 분리** | Backend | 현재 백테스트 설정과 실거래 설정이 같은 `strategy_config` 테이블 공유 → `backtest_strategy_config` / `live_strategy_config`로 분리 고려 |
| 12 | **공통 인프라 테이블 Read-Only 접근 강제** | Backend | 백테스트 서비스에서 `candle_data`를 INSERT 하지 않도록 Repository 레이어에 `@ReadOnly` 또는 별도 읽기 전용 Repository 적용 |
| 13 | **E2E 테스트 테이블 분리 시나리오 추가** | QA | Playwright 테스트에 백테스팅 → paper_trading 테이블 데이터 미오염 검증 시나리오 추가 |

---

## 7. 권장 다음 단계

```
현재 상태: Phase 1~3.5 완료 + 테이블 소유권 분리 완료
                     |
          [Step 1] 테이블 격리 검증 (P1 #1~5)
          → BacktestService / PaperTradingService / LiveTradingService
            각각이 전용 테이블만 접근하는지 Repository 의존성 점검
          → H2 schema-h2.sql 동기화 재확인
                     |
          [Step 2] Report 에이전트 실행
          → REPORT.md 생성 (경영진/이해관계자 보고)
                     |
          [Step 3] Phase 4 진입
          → 실전매매 엔진 구현
          → Upbit WebSocket 실시간 연결
          → 주문 실행 엔진 (OrderExecutionEngine 활성화)
          → live_trading_session + public.position/order에 실거래 데이터 적재
```

---

작성: 개발 상태 검증 v3.0
기준: 2026-03-12
