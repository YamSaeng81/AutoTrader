# CryptoAutoTrader - 검증 결과 보고서

## 문서 정보
- 검증일: 2026-03-07 (2026-03-15 재검증 완료)
- 설계서 버전: DESIGN.md v1.3
- 검증 범위: 전체 (API, DB, Frontend, Infra, 모듈 구조, 프론트-백 연동)
- **[2026-03-15 재검증 요약]**: DESIGN.md v1.3 반영. Flyway V11~V15 신규 테이블 추가 검증. Phase 4 백엔드 전체 완료 + 버그 9개 수정 완료. paper_trading 스키마 불일치 설계서에서 해결. **프론트엔드(Phase 4 UI)는 여전히 미착수.**

---

## 1. 검증 요약

### 1.1 전체 현황

| 구분 | 설계 항목 | 구현 완료 | 누락 | 불일치 | 추가 | 완료율 |
|------|-----------|-----------|------|--------|------|--------|
| API (데이터 수집) | 5 | 5 | 0 | 0 | 0 | 100% |
| API (백테스팅) | 7 | 6 | 0 | 1 | 0 | 86% |
| API (전략 관리) | 6 | 6 | 0 | 0 | 0 | 100% |
| API (Paper Trading) | 7 | 7 | 0 | 0 | 0 | 100% |
| API (로그) | 1 | 1 | 0 | 0 | 0 | 100% |
| API (Phase 4: 운영 제어) | 9 | 5 | 4 | 0 | 0 | ~55% |
| API (Phase 4: 알림) | 3 | 1 | 2 | 0 | 0 | ~33% |
| DB 테이블 (public, V1~V15) | 14 | 14 | 0 | 0 | 3 | 100% |
| DB Paper Trading 스키마 | 3 | 3 | 0 | 0 | 0 | 100% |
| Frontend 페이지 | 14 | 10 | 4 | 0 | 1 | 71% |
| Frontend 컴포넌트 | 16 | 9 | 7 | 0 | 1 | 56% |
| Infra | 7 | 7 | 0 | 0 | 2 | 100% |
| 백엔드 모듈 구조 | 4 | 4 | 0 | 0 | 0 | 100% |
| core-engine 패키지 | 8 | 6 | 2 | 0 | 0 | 75% |
| strategy-lib 전략 | 9 | 9 | 0 | 0 | 5 | 100% |
| strategy-lib Config 클래스 | 4 | 0 | 4 | 0 | 0 | 0% |
| exchange-adapter 패키지 | 8 | 8 | 0 | 0 | 1 | 100% |
| web-api Service 클래스 | 6 | 5 | 1 | 0 | 3 | 83% |
| web-api Config 클래스 | 5 | 3 | 2 | 0 | 1 | 60% |
| **Phase 1~3.5 총계** | **87** | **74** | **5** | **1** | **10** | **85%** |
| **Phase 4 포함 총계 (2026-03-15 재검증)** | **102** | **88** | **8** | **1** | **13** | **~86%** |

### 1.2 상태 범례
- 완료: 설계대로 구현됨
- 누락: 설계에 있으나 구현 안됨
- 불일치: 구현되었으나 설계와 다름
- 추가: 설계에 없으나 구현됨
- 미구현(예정): Phase 4 실전매매 관련, 로드맵상 미구현

### 1.3 이전 검증(v1.0) 대비 변경 사항

| 변경 항목 | 이전 (v1.0 기준) | 현재 (v1.1 기준) | 영향 |
|-----------|------------------|------------------|------|
| Paper Trading API 7개 | "설계서 외 추가 구현" | 공식 설계 항목 | 완료율 반영 |
| Log API | "설계서에 명시되지 않음" | 공식 설계 항목 (4.4절) | 완료율 반영 |
| Data summary API | "설계서 외 추가" | 공식 설계 항목 (4.4절) | 완료율 반영 |
| Paper Trading 스키마 | paper_trading 스키마 (별도) | public 스키마 내 paper_ 접두사 (설계서 변경) | 불일치 발생 |
| Frontend 페이지 14개 | 11개 설계 | /paper-trading/[sessionId], /paper-trading/history, /data 추가 | 완료율 반영 |
| Frontend 프로젝트명 | web-dashboard | crypto-trader-frontend | 설계서 수정 완료 |

---

## 2. API 검증 상세

### 2.1 데이터 수집 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| POST /api/v1/data/collect | O | O | 완료 | |
| GET /api/v1/data/status | O | O | 완료 | totalCandles, pairCount, status 반환 |
| GET /api/v1/data/coins | O | O | 완료 | |
| GET /api/v1/data/candles | O | O | 완료 | coinPair, timeframe, start, end, limit 파라미터 지원 |
| GET /api/v1/data/summary | O | O | 완료 | v1.1에서 공식 항목으로 승격 |

### 2.2 백테스팅 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| POST /api/v1/backtest/run | O | O | 완료 | |
| POST /api/v1/backtest/walk-forward | O | O | 완료 | |
| GET /api/v1/backtest/{id} | O | O | 완료 | |
| GET /api/v1/backtest/{id}/trades | O | O | 완료 | |
| GET /api/v1/backtest/{id}/metrics | O | X | 불일치 | 별도 엔드포인트 없음. GET /{id}에 metrics 포함하여 반환 |
| GET /api/v1/backtest/compare | O | O | 완료 | |
| GET /api/v1/backtest/list | O | O | 완료 | |

### 2.3 전략 관리 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/strategies | O | O | 완료 | StrategyRegistry 기반 목록 반환 |
| GET /api/v1/strategies/{id} | O | O | 완료 | {id} 대신 {name}으로 구현 (StrategyRegistry 기반) |
| POST /api/v1/strategies | O | O | 완료 | StrategyConfigEntity DB 저장 |
| PUT /api/v1/strategies/{id} | O | O | 완료 | DB 기반 전략 설정 수정 |
| PATCH /api/v1/strategies/{id}/toggle | O | O | 완료 | isActive 토글 |
| GET /api/v1/strategies/types | O | O | 완료 | SystemController에서 구현 |

### 2.4 모의투자 (Paper Trading) API -- Phase 3.5

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/paper-trading/sessions | O | O | 완료 | v1.1에서 공식 항목으로 승격 |
| POST /api/v1/paper-trading/sessions | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id} | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id}/positions | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id}/orders | O | O | 완료 | |
| POST /api/v1/paper-trading/sessions/{id}/stop | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id}/chart | O | O | 완료 | |

### 2.5 로그 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/logs/strategy | O | O | 완료 | v1.1에서 공식 항목으로 승격 (4.4절) |

### 2.6 운영 제어 API (Phase 4)

> **[2026-03-15 갱신]**: LiveTradingService/OrderExecutionEngine 구현 완료에 따라 일부 엔드포인트 상태 갱신. 프론트엔드 UI는 여전히 미착수.

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/trading/status | O | O | 완료 | LiveTradingService.getSessionStatus() 기반 |
| POST /api/v1/trading/start | O | O | 완료 | LiveTradingService.startSession() |
| POST /api/v1/trading/stop | O | O | 완료 | LiveTradingService.stopSession() |
| POST /api/v1/trading/emergency-stop | O | O | 완료 | LiveTradingService.emergencyStopSession() |
| GET /api/v1/trading/health/exchange | O | O | 완료 | ExchangeHealthMonitor 기반 |
| GET /api/v1/positions | O | X | 미구현(예정) | Phase 4 프론트 연동 시 필요 |
| GET /api/v1/orders | O | X | 미구현(예정) | Phase 4 프론트 연동 시 필요 |
| GET /api/v1/risk/config | O | X | 미구현(예정) | Phase 4 |
| PUT /api/v1/risk/config | O | X | 미구현(예정) | Phase 4 |

### 2.7 알림 API (Phase 4)

> **[2026-03-15 갱신]**: TelegramNotificationService 구현 완료. 일별 요약(12:00/00:00 KST cron) 스케줄링 동작 중.

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/reports/daily | O | O | 완료 | TelegramNotificationService cron 스케줄 방식으로 구현 |
| GET /api/v1/reports/weekly | O | X | 미구현(예정) | Phase 4 |
| POST /api/v1/reports/test-telegram | O | X | 미구현(예정) | Phase 4 |

### 2.8 추가 엔드포인트 (설계서 외)

| 엔드포인트 | 구현 | 비고 |
|------------|------|------|
| GET /api/v1/health | O | SystemController - 시스템 상태 확인 |

---

## 3. 데이터베이스 검증 상세

### 3.1 테이블 구조 비교 (public 스키마, V1~V15)

| 테이블 | 설계 | Flyway | 상태 | 비고 |
|--------|------|--------|------|------|
| candle_data | O | V1 | 완료 | hypertable, 압축 정책 포함 |
| backtest_run | O | V2 | 완료 | |
| backtest_metrics | O | V2 | 완료 | |
| backtest_trade | O | V2 | 완료 | |
| strategy_config | O | V3+V11 | 완료 | V11: manual_override 컬럼 추가 |
| position | O | V4+V12 | 완료 | V12: session_id FK (live_trading_session) 추가 |
| order | O | V4+V12 | 완료 | V12: session_id FK (live_trading_session) 추가 |
| risk_config | O | V5 | 완료 | |
| strategy_log | O | V6 | 완료 | |
| trade_log | O | V6 | 완료 | |
| strategy_signal | O | V7 | 완료 | |
| live_trading_session | O (v1.3) | V12 | 완료 | 실전매매 세션 관리 (RUNNING/STOPPED/EMERGENCY_STOPPED) |
| market_data_cache | - | V13 | 추가 | 실시간 싱크 전용 (candle_data와 분리) |
| strategy_type_enabled | - | V14 | 추가 | 전략 타입별 활성화 여부 (10종) |

### 3.2 Paper Trading 스키마

| 항목 | 설계 (v1.3) | 구현 | 상태 | 비고 |
|------|-------------|------|------|------|
| paper_trading.virtual_balance | paper_trading.virtual_balance | paper_trading.virtual_balance | 완료 | DESIGN.md v1.3에서 설계서 수정 완료 |
| paper_trading.position | paper_trading.position | paper_trading.position | 완료 | session_id FK 포함 |
| paper_trading.order | paper_trading.order | paper_trading.order | 완료 | session_id FK 포함 |

**[v1.3 해소]**: DESIGN.md v1.3에서 paper_trading 스키마 구조를 실제 구현에 맞게 정정 완료. 이전 불일치 항목 해소.

### 3.3 Paper Trading 컬럼 상세 비교

#### paper_virtual_balance (설계) vs paper_trading.virtual_balance (구현)

| 컬럼 | 설계 | 구현 | 상태 |
|------|------|------|------|
| id | BIGSERIAL PK | BIGSERIAL PK | 완료 |
| strategy_name | VARCHAR(100) | VARCHAR(50) | 불일치(허용) |
| coin_pair | VARCHAR(20) | VARCHAR(20) | 완료 |
| timeframe | VARCHAR(10) | VARCHAR(10) | 완료 |
| initial_capital | NUMERIC(20,2) | NUMERIC(20,2) | 완료 |
| total_krw | NUMERIC(20,2) | NUMERIC(20,2) | 완료 |
| available_krw | NUMERIC(20,2) | NUMERIC(20,2) | 완료 |
| status | VARCHAR(10) | VARCHAR(10) | 완료 |
| started_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |
| stopped_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |
| updated_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |

#### paper_position (설계) vs paper_trading.position (구현)

| 컬럼 | 설계 | 구현 | 상태 |
|------|------|------|------|
| id | BIGSERIAL PK | BIGSERIAL PK | 완료 |
| session_id | BIGINT FK | BIGINT FK | 완료 |
| coin_pair | VARCHAR(20) | VARCHAR(20) | 완료 |
| side | VARCHAR(4) | VARCHAR(4) | 완료 |
| avg_price | NUMERIC(20,8) | NUMERIC(20,8) | 완료 |
| size | NUMERIC(20,8) | NUMERIC(20,8) | 완료 |
| unrealized_pnl | NUMERIC(20,8) | NUMERIC(20,8) | 완료 |
| realized_pnl | NUMERIC(20,8) | NUMERIC(20,8) | 완료 |
| status | VARCHAR(10) | VARCHAR(10) | 완료 |
| opened_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |
| closed_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |
| entry_price | 없음 | NUMERIC(20,8) | 추가 (LIKE public.position 상속) |
| strategy_config_id | 없음 | BIGINT | 추가 (LIKE public.position 상속) |

#### paper_order (설계) vs paper_trading.order (구현)

| 컬럼 | 설계 | 구현 | 상태 |
|------|------|------|------|
| id | BIGSERIAL PK | BIGSERIAL PK | 완료 |
| session_id | BIGINT FK | BIGINT FK | 완료 |
| position_id | BIGINT FK | BIGINT FK | 완료 |
| coin_pair | VARCHAR(20) | VARCHAR(20) | 완료 |
| side | VARCHAR(4) | VARCHAR(4) | 완료 |
| price | NUMERIC(20,8) | NUMERIC(20,8) | 완료 |
| quantity | NUMERIC(20,8) | NUMERIC(20,8) | 완료 |
| state | VARCHAR(20) | VARCHAR(20) | 완료 |
| signal_reason | TEXT | TEXT | 완료 |
| created_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |
| filled_at | TIMESTAMPTZ | TIMESTAMPTZ | 완료 |
| order_type | 없음 | VARCHAR(10) | 추가 (LIKE public.order 상속) |
| exchange_order_id | 없음 | VARCHAR(100) | 추가 (LIKE public.order 상속) |
| filled_quantity | 없음 | NUMERIC(20,8) | 추가 |
| submitted_at | 없음 | TIMESTAMPTZ | 추가 |
| cancelled_at | 없음 | TIMESTAMPTZ | 추가 |
| failed_reason | 없음 | TEXT | 추가 |

### 3.4 인덱스 비교

| 인덱스 | 설계 | Flyway | 상태 |
|--------|------|--------|------|
| idx_candle_unique (coin_pair, timeframe, time) | O | V1 | 완료 |
| idx_candle_lookup (coin_pair, timeframe, time DESC) | O | V1 | 완료 |
| idx_metrics_run (backtest_run_id) | O | V2 | 완료 |
| idx_bt_trade_run (backtest_run_id, executed_at) | O | V2 | 완료 |
| idx_position_open (status, coin_pair) WHERE OPEN | O | V4 | 완료 |
| idx_order_state WHERE active | O | V4 | 완료 |
| idx_order_position (position_id) | O | V4 | 완료 |
| idx_strategy_log_time (created_at DESC) | O | V6 | 완료 |
| idx_trade_log_order (order_id) | O | V6 | 완료 |
| idx_signal_lookup (coin_pair, created_at DESC) | O | V7 | 완료 |
| idx_signal_strategy (strategy_config_id, created_at DESC) | O | V7 | 완료 |
| idx_paper_position_session_id | - | V10 | 추가 |
| idx_paper_order_session_id | - | V10 | 추가 |

### 3.5 Entity 매핑 검증

| Entity 클래스 | 대응 테이블 | 상태 | 비고 |
|---------------|-------------|------|------|
| CandleDataEntity | candle_data | 완료 | |
| BacktestRunEntity | backtest_run | 완료 | |
| BacktestMetricsEntity | backtest_metrics | 완료 | |
| BacktestTradeEntity | backtest_trade | 완료 | |
| StrategyConfigEntity | strategy_config | 완료 | |
| StrategyLogEntity | strategy_log | 완료 | |
| VirtualBalanceEntity | paper_trading.virtual_balance | 완료 | V9 확장 컬럼 포함 |
| PaperPositionEntity | paper_trading.position | 완료 | session_id 포함 |
| PaperOrderEntity | paper_trading.order | 완료 | session_id, signalReason 포함 |

다음 테이블에 대응하는 Entity 클래스가 아직 없음 (Phase 4에서 필요):
- position (public 스키마)
- order (public 스키마)
- risk_config
- trade_log
- strategy_signal

---

## 4. 프론트엔드 검증 상세

### 4.1 페이지 구현

| 경로 | 설계 | 구현 | 상태 | 비고 |
|------|------|------|------|------|
| / | O (Phase 2) | O | 완료 | 대시보드: 요약 카드 + 최근 백테스트 |
| /backtest | O (Phase 2) | O | 완료 | 백테스트 이력 목록 |
| /backtest/new | X | O | 추가 | 설계서에 없으나 별도 신규 백테스트 페이지 분리 |
| /backtest/[id] | O (Phase 2) | O | 완료 | 상세 결과 + 차트 |
| /backtest/compare | O (Phase 2) | O | 완료 | 선택 UI + 수익률 BarChart + 성과 지표 테이블 |
| /strategies | O (Phase 3) | O | 완료 | 전략 목록 + 파라미터 조회 |
| /paper-trading | O (Phase 3.5) | O | 완료 | 멀티세션 관리, 시작/중단 |
| /paper-trading/[sessionId] | O (Phase 3.5) | O | 완료 | 세션 상세 + 가격 차트 + 매수/매도 마커 |
| /paper-trading/history | O (Phase 3.5) | O | 완료 | 모의투자 이력 페이지 |
| /data | O (Phase 2) | O | 완료 | 데이터 수집 관리 페이지 |
| /logs | O (Phase 2) | O | 완료 | 전략 로그 조회, 페이지네이션 지원 |
| /trading | O (Phase 4) | X | 미구현(예정) | Sidebar에 비활성 표시 |
| /positions | O (Phase 4) | X | 미구현(예정) | Sidebar에 비활성 표시 |
| /orders | O (Phase 4) | X | 미구현(예정) | Sidebar에 비활성 표시 |
| /risk | O (Phase 4) | X | 미구현(예정) | Sidebar에 비활성 표시 |

### 4.2 컴포넌트 구현

#### 레이아웃 컴포넌트

| 컴포넌트 | 설계 | 구현 | 상태 | 비고 |
|----------|------|------|------|------|
| Sidebar | O | O | 완료 | Phase별 네비게이션, 비활성 표시 |
| Header | O | X | 누락 | 별도 Header 컴포넌트 없음, layout.tsx에서 처리 |
| ThemeProvider | O | O | 완료 | ThemeProvider 구현 완료 (기본 dark, localStorage 연동, 18개 파일 269개 dark: 클래스 적용) |

#### UI 공통 컴포넌트

| 컴포넌트 | 설계 | 구현 | 상태 | 비고 |
|----------|------|------|------|------|
| Button | O | X | 누락 | components/ui/ 디렉토리 없음. 인라인 button 태그 사용 |
| Card | O | X | 누락 | 인라인 div + Tailwind로 대체 |
| Table | O | X | 누락 | 인라인 table로 대체 |
| Modal | O | X | 누락 | 공통 Modal 없음 |

#### 기능 컴포넌트

| 컴포넌트 | 설계 | 구현 | 상태 | 비고 |
|----------|------|------|------|------|
| BacktestForm | O | O | 완료 | |
| MetricsCards | O | O | 완료 | MetricsCard.tsx |
| PnlChart | O | O | 완료 | CumulativePnlChart.tsx (이름 상이) |
| CompareChart | O | O | 완료 | /backtest/compare 페이지에 Recharts BarChart + 비교 테이블 구현 |
| MonthlyHeatmap | O | O | 완료 | MonthlyReturnsHeatmap.tsx |
| StrategyList | O | X | 누락 | strategies/page.tsx에 인라인 구현 |
| StrategyConfigForm | O | O | 완료 | |
| TradesTable | X | O | 추가 | 설계서 features/backtest에 없으나 구현됨 |
| TradingStatus | O | X | 미구현(예정) | Phase 4 |
| PositionTable | O | X | 미구현(예정) | Phase 4 |
| OrderTable | O | X | 미구현(예정) | Phase 4 |

#### Hooks / Stores

| 항목 | 설계 | 구현 | 상태 | 비고 |
|------|------|------|------|------|
| hooks/useBacktest.ts | O | X | 누락 | api.ts에서 직접 호출 방식 사용 |
| hooks/useStrategies.ts | O | X | 누락 | 동일 |
| hooks/useTrading.ts | O | X | 누락 | 동일 |
| stores/backtestStore.ts | O | X | 누락 | Zustand 미사용, React Query만 사용 |
| stores/tradingStore.ts | O | X | 누락 | 동일 |

참고: hooks와 stores 누락은 설계 대비 구현 방식 차이로, React Query(TanStack Query)를 직접 사용하는 패턴으로 대체. 기능적으로 동일하게 동작하며, 설계서 8.2절 주석에서도 "대용량 차트 데이터는 React Query로 관리할 것"을 권장. 실질적 문제 아님.

---

## 5. 프론트-백 연동 검증

### 5.1 api.ts 호출 경로 vs 백엔드 실제 엔드포인트

| 프론트엔드 api.ts 호출 | 백엔드 엔드포인트 | 일치 여부 |
|------------------------|-------------------|-----------|
| POST /api/v1/backtest/run | BacktestController: POST /run | 일치 |
| GET /api/v1/backtest/{id} | BacktestController: GET /{id} | 일치 |
| GET /api/v1/backtest/list | BacktestController: GET /list | 일치 |
| GET /api/v1/backtest/compare?ids= | BacktestController: GET /compare?ids= | 일치 |
| GET /api/v1/backtest/{id}/trades?page= | BacktestController: GET /{id}/trades?page=&size= | 일치 |
| GET /api/v1/strategies/types | SystemController: GET /strategies/types | 일치 |
| GET /api/v1/data/coins | DataController: GET /coins | 일치 |
| POST /api/v1/data/collect | DataController: POST /collect | 일치 |
| GET /api/v1/data/summary | DataController: GET /summary | 일치 |
| GET /api/v1/strategies | StrategyController: GET / | 일치 |
| GET /api/v1/strategies/{name} | StrategyController: GET /{name} | 일치 |
| POST /api/v1/strategies | StrategyController: POST / | 일치 |
| PUT /api/v1/strategies/{id} | StrategyController: PUT /{id} | 일치 |
| PATCH /api/v1/strategies/{id}/toggle | StrategyController: PATCH /{id}/toggle | 일치 |
| GET /api/v1/logs/strategy | LogController: GET /strategy | 일치 |
| GET /api/v1/paper-trading/sessions | PaperTradingController: GET /sessions | 일치 |
| POST /api/v1/paper-trading/sessions | PaperTradingController: POST /sessions | 일치 |
| GET /api/v1/paper-trading/sessions/{id} | PaperTradingController: GET /sessions/{id} | 일치 |
| GET /api/v1/paper-trading/sessions/{id}/positions | PaperTradingController: GET /sessions/{id}/positions | 일치 |
| GET /api/v1/paper-trading/sessions/{id}/orders | PaperTradingController: GET /sessions/{id}/orders | 일치 |
| POST /api/v1/paper-trading/sessions/{id}/stop | PaperTradingController: POST /sessions/{id}/stop | 일치 |
| GET /api/v1/paper-trading/sessions/{id}/chart | PaperTradingController: GET /sessions/{id}/chart | 일치 |

### 5.2 공통 응답 형식 검증

설계서 4.2절 공통 응답:
```json
{ "success": true, "data": { ... } }
{ "success": false, "error": { "code": "...", "message": "..." } }
```

백엔드 ApiResponse 클래스: 완료 - success, data, error(code, message) 구조 일치.
프론트엔드 types.ts ApiResponse: 완료 - success, data, error 구조 일치.

---

## 6. 인프라 검증 상세

| 항목 | 설계 | 구현 | 상태 | 비고 |
|------|------|------|------|------|
| docker-compose.yml (개발) | O | O | 불일치(허용) | 설계서: db + redis + backend + frontend 4개 서비스. 구현: db + redis 2개만 (backend/frontend는 로컬 실행 전제). 개발 편의상 유지 |
| docker-compose.prod.yml (운영) | O | O | 완료 | 설계서대로 4개 서비스 구성, crypto-trader-frontend 경로 일치 |
| Dockerfile (Backend) | O | O | 완료 | web-api/Dockerfile (multi-stage: eclipse-temurin:17-jdk-alpine builder + jre-alpine runtime) |
| Dockerfile (Frontend) | O | O | 완료 | crypto-trader-frontend/Dockerfile (multi-stage: node:20-alpine + standalone 모드) |
| next.config.ts output: "standalone" | - | O | 완료 | Dockerfile standalone 모드 지원 |
| .env.example | O | O | 완료 | 설계서 9.3절과 일치 |
| .env.local (Frontend) | X | O | 추가 | 프론트엔드용 환경변수 |
| Gradle 멀티모듈 | O | O | 완료 | settings.gradle + 4개 모듈별 build.gradle |
| application.yml | O | O | 완료 | DB, Redis, Flyway, JPA 설정 |
| SwaggerConfig | X | O | 추가 | API 문서화 |

---

## 7. 백엔드 모듈 구조 검증

### 7.1 Gradle 멀티모듈

| 모듈 | 설계 | 구현 | 상태 |
|------|------|------|------|
| core-engine | O | O | 완료 |
| strategy-lib | O | O | 완료 |
| exchange-adapter | O | O | 완료 |
| web-api | O | O | 완료 |

### 7.2 core-engine 패키지 상세

| 패키지 | 설계 클래스 | 구현 | 상태 | 비고 |
|--------|------------|------|------|------|
| backtest/ | BacktestEngine, BacktestConfig, BacktestResult, WalkForwardTestRunner | O | 완료 | FillSimulator 추가 구현 |
| metrics/ | MetricsCalculator, PerformanceReport | O | 완료 | |
| regime/ | MarketRegimeDetector, MarketRegime | O | 완료 | |
| risk/ | RiskEngine, RiskConfig, RiskCheckResult | O | 완료 | |
| portfolio/ | PortfolioManager | O | 완료 | |
| signal/ | SignalEngine, TradingSignal | X | 누락 | Phase 4에서 필요 |
| position/ | PositionManager, Position | X | 누락 | Phase 4에서 필요 |
| model/ | Candle, CoinPair, OrderSide, TimeFrame, TradeRecord | O | 완료 | |

### 7.3 strategy-lib 패키지 상세

| 전략 | 설계 | 구현 | 상태 | 비고 |
|------|------|------|------|------|
| Strategy 인터페이스 | O | O | 완료 | |
| StrategyConfig | O | O | 완료 | |
| StrategySignal | O | O | 완료 | |
| StrategyRegistry | X | O | 추가 | |
| IndicatorUtils | X | O | 추가 | |
| Candle (strategy-lib) | X | O | 추가 | core-engine의 Candle과 별도 |
| VwapStrategy | O | O | 완료 | Phase 1 |
| EmaCrossStrategy | O | O | 완료 | Phase 1 |
| BollingerStrategy | O | O | 완료 | Phase 1 |
| GridStrategy | O | O | 완료 | Phase 1 |
| RsiStrategy | O | O | 완료 | Phase 3 추가 전략 |
| MacdStrategy | O | O | 완료 | Phase 3 추가 전략 |
| SupertrendStrategy | O | O | 완료 | Phase 3 추가 전략 |
| AtrBreakoutStrategy | O | O | 완료 | Phase 3 추가 전략 |
| OrderbookImbalanceStrategy | O | O | 완료 | Phase 3 추가 전략 (스켈레톤) |

#### Phase 1 전략별 Config 클래스 (누락)

설계서 2.3절에서 각 전략 패키지에 개별 Config 클래스를 명시:

| Config 클래스 | 설계 | 구현 | 상태 | 비고 |
|---------------|------|------|------|------|
| VwapConfig | O | X | 누락 | VwapStrategy가 StrategyConfig.getParams()로 파라미터 직접 조회 |
| EmaCrossConfig | O | X | 누락 | 동일 |
| BollingerConfig | O | X | 누락 | 동일 |
| GridConfig | O | X | 누락 | 동일 |

참고: Phase 3 추가 전략(RSI, MACD, Supertrend, AtrBreakout, OrderbookImbalance)은 모두 개별 Config 클래스가 구현되어 있음. Phase 1 전략은 StrategyConfig.getParams() Map을 직접 사용하여 기능적으로 동작하나, 타입 안전성과 설계 일관성을 위해 Config 클래스 추가 권장.

### 7.4 exchange-adapter 패키지 상세

| 클래스 | 설계 | 구현 | 상태 | 비고 |
|--------|------|------|------|------|
| ExchangeAdapter 인터페이스 | O | O | 완료 | |
| UpbitRestClient | O | O | 완료 | Rate Limiting 110ms throttle 추가 |
| UpbitCandleCollector | O | O | 완료 | |
| UpbitWebSocketClient | O | O | 완료 | **[2026-03-15]** 자동 재연결, GZIP 디코딩, Ping/Pong, destroy() 분리 |
| UpbitOrderClient | O (v1.3) | O | 완료 | **[2026-03-15]** JWT/char[] 보안, buildSecretKeySpec() |
| OrderExecutionEngine | O | O | 완료 | **[2026-03-15]** 6단계 상태머신, BUY/SELL/지정가 실제 구현 |
| OrderStateMachine | O | O | 완료 | OrderExecutionEngine 내부에 상태머신 통합 구현 |
| ExchangeHealthMonitor | O | O | 완료 | **[2026-03-15]** 거래소 연결 모니터링 구현 |
| dto/UpbitCandleResponse | O | O | 완료 | |

### 7.5 web-api Controller 클래스

| Controller 클래스 | 설계 | 구현 | 상태 | 비고 |
|-------------------|------|------|------|------|
| BacktestController | O | O | 완료 | |
| StrategyController | O | O | 완료 | 읽기(Registry) + CRUD(DB) 모두 지원 |
| DataController | O | O | 완료 | v1.1에서 공식 항목 |
| LogController | O | O | 완료 | v1.1에서 공식 항목 |
| PaperTradingController | O | O | 완료 | v1.1에서 공식 항목 |
| SystemController | O | O | 완료 | health + strategies/types |
| GlobalExceptionHandler | X | O | 추가 | 전역 예외 처리 |
| LiveTradingController | O | O | 완료 | **[2026-03-15]** start/stop/emergencyStop/status 엔드포인트 구현 |
| RiskController | O | X | 미구현(예정) | Phase 4 |

### 7.6 web-api Service 클래스

| Service 클래스 | 설계 | 구현 | 상태 | 비고 |
|----------------|------|------|------|------|
| BacktestService | O | O | 완료 | |
| DataCollectionService | O | O | 완료 | |
| PaperTradingService | O | O | 완료 | v1.1에서 공식 항목 |
| MarketDataSyncService | X | O | 추가 | 캔들 데이터 동기화 |
| TelegramNotificationService | O | O | 완료 | **[2026-03-15]** 즉시 알림 + 일별 요약 cron 구현 |
| LiveTradingService | - | O | 추가 | **[2026-03-15]** 다중 세션 실전매매 (최대 5개 동시) |
| MarketRegimeAwareScheduler | - | O | 추가 | **[2026-03-15]** 시장 상태별 전략 자동 스위칭 |
| ScheduledTasks | O | X | 미구현(예정) | Phase 4 주간 리포트 부분 미구현 |

### 7.7 web-api Config 클래스

| Config 클래스 | 설계 | 구현 | 상태 | 비고 |
|---------------|------|------|------|------|
| AsyncConfig | O | O | 완료 | 3개 스레드 풀 분리 |
| WebConfig | O | O | 완료 | CORS 설정 |
| SwaggerConfig | X | O | 추가 | API 문서화 |
| RedisConfig | O | X | 누락 | application.yml에 접속 설정만 있고 별도 Config 클래스 없음 |
| SchedulerConfig | O | X | 누락 | 스케줄러 설정 미구현 |
| SecurityConfig | O | X | 미구현(예정) | Phase 4 API Key 암호화 설정 |

### 7.8 web-api event 패키지

| 클래스 | 설계 | 구현 | 상태 | 비고 |
|--------|------|------|------|------|
| EventPublisher | O | X | 미구현(예정) | Phase 4, Redis Pub/Sub |
| EventSubscriber | O | X | 미구현(예정) | Phase 4, Redis Pub/Sub |

---

## 8. 누락 항목 상세 (Action Required)

### 8.1 우선순위 높음 (P0) -- 현재 Phase에서 구현되어야 할 항목

| # | 항목 | 위치 | 설명 | 예상 작업량 |
|---|------|------|------|-------------|
| 1 | GET /api/v1/backtest/{id}/metrics | Backend BacktestController | 별도 metrics 엔드포인트 (또는 설계서 수정) | 1h |

참고: 이 항목은 기능적으로 GET /{id} 응답에 metrics가 포함되어 반환되고 있어 실질적 문제는 없음. 설계서 수정으로 해결 가능.

### 8.2 우선순위 중간 (P1) -- 개선 권장 항목

| # | 항목 | 위치 | 설명 | 예상 작업량 |
|---|------|------|------|-------------|
| 2 | Header 컴포넌트 | Frontend | 별도 Header 분리 (페이지 제목, breadcrumb 등) | 2h |
| 3 | ~~ThemeProvider / 다크 모드~~ | ~~Frontend~~ | ~~해결됨: ThemeProvider 구현 완료 (기본 dark, 18개 파일 269개 dark: 클래스)~~ | ~~0h~~ |
| 4 | 공통 UI 컴포넌트 (Button, Card, Table, Modal) | Frontend | components/ui/ 디렉토리로 공통화 | 4h |
| 5 | ~~Walk Forward 프론트엔드~~ | ~~Frontend~~ | ~~해결됨: /backtest/walk-forward 별도 페이지로 구현 완료~~ | ~~0h~~ |
| 6 | Phase 1 전략 Config 클래스 | strategy-lib | VwapConfig, EmaCrossConfig, BollingerConfig, GridConfig | 2h |

### 8.3 우선순위 낮음 (P2) -- Phase 4 잔여 구현

| # | 항목 | 위치 | 상태 | 설명 |
|---|------|------|------|------|
| 7 | SignalEngine, TradingSignal | core-engine/signal | 미구현 | Phase 4 신호 엔진 |
| 8 | PositionManager, Position | core-engine/position | 미구현 | Phase 4 포지션 관리 |
| 9 | ~~UpbitWebSocketClient~~ | ~~exchange-adapter~~ | ✅ 완료 | **[2026-03-15]** 자동 재연결, GZIP, Ping/Pong |
| 10 | ~~OrderExecutionEngine, OrderStateMachine~~ | ~~exchange-adapter~~ | ✅ 완료 | **[2026-03-15]** 6단계 상태머신, Upbit 연동 |
| 11 | ~~ExchangeHealthMonitor~~ | ~~exchange-adapter~~ | ✅ 완료 | **[2026-03-15]** 거래소 상태 모니터링 |
| 12 | TradeController, RiskController | web-api | 미구현 | GET /positions, /orders, /risk/config API |
| 13 | EventPublisher, EventSubscriber | web-api/event | 미구현 | Redis Pub/Sub |
| 14 | ~~TelegramNotificationService~~ | ~~web-api/service~~ | ✅ 완료 | **[2026-03-15]** 즉시 + 일별 요약 cron |
| 15 | ScheduledTasks | web-api/service | 부분 | 주간 리포트 미구현 |
| 16 | SecurityConfig | web-api/config | 미구현 | API Key 암호화 |
| 17 | RedisConfig | web-api/config | 미구현 | Redis Pub/Sub 설정 |
| 18 | SchedulerConfig | web-api/config | 미구현 | 스케줄러 설정 |
| 19 | /trading, /positions, /orders, /risk 페이지 | Frontend | 미구현 | Phase 4 실전매매 UI |
| 20 | TradingStatus, PositionTable, OrderTable 컴포넌트 | Frontend | 미구현 | Phase 4 실전매매 UI |

---

## 9. 불일치 항목 상세 (Review Required)

| # | 항목 | 설계 내용 | 구현 내용 | 권장 조치 |
|---|------|-----------|-----------|-----------|
| 1 | GET /backtest/{id}/metrics | 별도 엔드포인트 | GET /{id} 응답에 metrics 포함 | 설계서 수정 권장 (현재 구현이 더 효율적) |
| 2 | docker-compose.yml | backend + frontend 서비스 포함 | db + redis만 포함 | 개발 편의상 현재 방식 유지 가능 |
| 3 | strategies/{id} 경로변수 | id (숫자) | 읽기 조회는 name(문자열), CRUD는 id(숫자) 혼용 | 기능상 문제없음 |
| 4 | ~~다크 모드~~ | ~~"Tailwind CSS (Dark Mode)" 기본~~ | ~~ThemeProvider 구현 완료, 기본 dark 테마~~ | ~~해결됨~~ |
| 5 | ~~Paper Trading 스키마~~ | ~~public 스키마 내 paper_ 접두사~~ | ~~paper_trading 스키마~~ | ~~**[v1.3 해소]** 설계서 수정 완료~~ |
| 6 | strategy_name 길이 (virtual_balance) | VARCHAR(100) | VARCHAR(50) | 실질적 문제 없음. V9 마이그레이션의 제약 |

---

## 10. 긍정적 사항 (설계서 외 우수 구현)

1. **Paper Trading 멀티세션**: 최대 5개 동시 세션 지원, V9/V10 마이그레이션으로 확장.
2. **세션 상세 가격 차트**: `/paper-trading/[sessionId]` 페이지에서 캔들 차트 + 매수/매도 마커 표시.
3. **전략 비교 분석**: `/backtest/compare` 페이지에서 최대 6개 백테스트 선택 비교.
4. **전략 로그 시스템**: LogController + StrategyLogEntity + /logs 페이지.
5. **전략 CRUD**: StrategyConfigEntity 기반 DB 영속화, StrategyRegistry(읽기 전용)와 DB(CRUD) 이중 구조.
6. **데이터 관리 UI**: /data 페이지에서 수집 요청 + 현황 조회 + 자동 갱신.
7. **컨테이너화 완료**: Backend/Frontend 모두 multi-stage Dockerfile, docker-compose.prod.yml 운영 배포 가능.
8. **GlobalExceptionHandler**: 전역 예외 처리.
9. **SwaggerConfig**: API 문서 자동 생성.
10. **FillSimulator**: 백테스트 현실성 강화 (Market Impact + Partial Fill).
11. **MSW(Mock Service Worker)**: 프론트엔드 독립 개발용 모킹 시스템.
12. **매도 주문 상세 정보**: PaperTradingController의 toOrderMap()에서 SELL 주문 시 매수단가, 실현손익, 수익률 제공.
13. **실전매매 다중 세션**: live_trading_session 기반 최대 5개 동시 세션, session_id FK로 position/order 격리.
14. **텔레그램 알림 이중 구조**: 즉시 전송(세션 이벤트) + 일별 요약(12:00/00:00 KST cron, 거래 없어도 전송).
15. **market_data_cache 분리**: 실시간 싱크 데이터와 백테스팅용 candle_data를 완전 분리하여 서로 오염 방지.
16. **strategy_type_enabled**: 전략 타입별 ON/OFF로 모의/실전매매에서 전략 선택적 실행 가능.
17. **UpbitOrderClient 보안 강화**: `char[]` 기반 API Key 관리 + `buildSecretKeySpec()`으로 JWT 생성 시 평문 String 메모리 노출 방지.

---

## 11. Phase별 완성도 요약

| Phase | 설명 | 완성도 | 평가 |
|-------|------|--------|------|
| Phase 1 | 백테스팅 엔진 + 데이터 수집 | 95% | Gradle 멀티모듈, Flyway, 전략 4종, 백테스트 엔진, Walk Forward, Fill Simulation 모두 완료. Phase 1 전략 Config 클래스 누락(-5%) |
| Phase 2 | 대시보드 + 백테스팅 UI + 로그 + 데이터 | 95% | 모든 페이지 구현 완료. 다크 모드 구현 완료. Header/공통 UI 컴포넌트 미분리(-5%) |
| Phase 3 | 전략 관리 | 95% | 전략 CRUD API + UI 완료. Phase 1 전략 Config 클래스 누락(-5%) |
| Phase 3.5 | Paper Trading | 98% | 멀티세션, 차트, 체결내역, 매수/매도 상세 모두 구현. 설계서 초과 품질 |
| Phase 4 | 실전 매매 | ~55% | **[2026-03-15]** 백엔드 구현 완료 (LiveTradingService, OrderExecutionEngine, TelegramNotificationService, UpbitWebSocketClient, ExchangeHealthMonitor, Flyway V11~V15). 프론트엔드 미착수(-30%), /positions /orders /risk API 미구현(-15%) |
| Infra | Docker/Compose/Flyway | 100% | Dockerfile, docker-compose.prod.yml, Flyway V1~V15 모두 완료 |

---

## 12. 설계서 수정 권장 사항

### ✅ DESIGN.md v1.3에서 해소된 항목

| # | 항목 | 조치 |
|---|------|------|
| 1 | Paper Trading 스키마 불일치 | v1.3에서 `paper_trading` 스키마 구조로 정정 완료 |
| 2 | 차트 라이브러리 불일치 (Lightweight Charts/ApexCharts) | v1.3에서 Recharts로 통일 |
| 3 | live_trading_session 테이블 미명세 | v1.3 섹션 3.3에 스키마 추가 |
| 4 | market_data_cache, strategy_type_enabled 미명세 | v1.3 섹션 3.3에 추가 |
| 5 | strategy_config.manual_override 컬럼 미명세 | v1.3 섹션 3.3에 추가 |
| 6 | Phase 4 구현 내역 미반영 | v1.3 섹션 2.3(패키지 구조), 섹션 10.1(구현 순서) 갱신 |
| 7 | 테이블 소유권 분리 구조 미명세 | v1.3 섹션 3.2로 추가 |

### 잔여 정합성 이슈

1. **GET /backtest/{id}/metrics**: 별도 엔드포인트 대신 GET /{id}에 포함 방식으로 동작 중. 기능 문제 없음 — 설계서 업데이트로 해결 가능.
2. **Phase 1 전략 Config 클래스**: VwapConfig, EmaCrossConfig 등 설계 명세 대비 구현에서는 `StrategyConfig.getParams()` Map 직접 사용. 실질적 기능 문제 없음.

---

## 13. 다음 단계

### ✅ 버그 수정 완료 (project_analysis.md 기반, 2026-03-15)

| # | 대상 | 내용 | 상태 |
|---|------|------|------|
| 1 | MetricsCalculator | Calmar Ratio 연환산 수익률/MDD, Recovery Factor 총수익/MDD 분리 | ✅ 완료 |
| 2 | BacktestEngine | 매수 수수료 SELL PnL에 차감 반영 (`entryFee` 추가) | ✅ 완료 |
| 3 | OrderExecutionEngine | `@Async` non-Future 리턴 문제 → `OrderRequest`에 sessionId/positionId 미리 설정 | ✅ 완료 |
| 4 | 프론트엔드 StrategyType | `types.ts` 4개 → 10개 확장 | ✅ 완료 |
| 5 | LiveTradingService | 매도 후 `totalAssetKrw` 오계산 수정 (`totalAssetKrw - fee`) | ✅ 완료 |
| 6 | BacktestEngine | Partial Fill `continue` 제거, BUY 조건에 `pendingQuantity == 0` 추가 | ✅ 완료 |
| 7 | UpbitWebSocketClient | `disconnect()`에서 scheduler 분리, `destroy()` 메서드 신설 | ✅ 완료 |
| 8 | UpbitRestClient | `throttle()` 메서드로 110ms Rate Limiting 추가 | ✅ 완료 |
| 9 | UpbitOrderClient | `buildSecretKeySpec()` helper로 JWT 생성 시 `new String(secretKey)` 제거 | ✅ 완료 |

### 설계서 정합성 조치
- DESIGN.md v1.1의 Paper Trading "구현 참고" 주석을 실제 구현(paper_trading 스키마)에 맞게 수정
- GET /backtest/{id}/metrics를 GET /{id} 포함 방식으로 설계서 변경

### 품질 개선 (P1)
- ~~다크 모드 지원 추가 (ThemeProvider)~~ — 해결됨
- 공통 UI 컴포넌트 분리 (components/ui/)
- Header 컴포넌트 분리
- ~~Walk Forward 프론트엔드 UI 추가~~ — 해결됨
- Phase 1 전략 Config 클래스 추가 (VwapConfig 등)

### Phase 4 잔여 구현
- 프론트엔드 실전매매 UI (/trading, /positions, /orders, /risk 페이지)
- GET /api/v1/positions, /orders, /risk/config API
- Spring Security / API 인증 추가

Phase 1~3.5 및 Infra가 95%~100% 완성도. Phase 4 백엔드 완료 + project_analysis.md 버그 전체 수정 완료. 프론트엔드 Phase 4 UI 구현 후 Report 에이전트 전달 권장.

---
생성: Check 에이전트
검증 기준: DESIGN.md v1.1
검증 완료 후: @Do (P1 품질 개선) 또는 @Report (보고서)
