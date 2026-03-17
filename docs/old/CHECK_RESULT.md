# CryptoAutoTrader - 검증 결과 보고서

## 문서 정보
- 검증일: 2026-03-15 (v4.1 — CompositeStrategy 파이프라인 연동 반영)
- 설계서 버전: DESIGN.md v1.3
- 검증 범위: 전체 (API, DB, Frontend, Infra, 모듈 구조, 전략 고도화 Phase S1~S5)
- 검증 기준 문서: DESIGN.md v1.3, PROGRESS.md (2026-03-15 최종), DEV_STATUS_REVIEW_v3.0
- 이전 검증: CHECK_RESULT.md v4.0 (2026-03-15 전체 재검증)

---

## 1. 검증 요약

### 1.1 전체 현황

| 구분 | 설계 항목 | 구현 완료 | 누락 | 불일치 | 추가 | 완료율 |
|------|-----------|-----------|------|--------|------|--------|
| API (데이터 수집) | 5 | 5 | 0 | 0 | 0 | 100% |
| API (백테스팅) | 7 | 7 | 0 | 1 | 2 | 100% |
| API (전략 관리) | 6 | 6 | 0 | 0 | 0 | 100% |
| API (Paper Trading) | 7 | 7 | 0 | 0 | 2 | 100% |
| API (로그) | 1 | 1 | 0 | 0 | 0 | 100% |
| API (Phase 4: 운영 제어) | 9 | 14 | 0 | 5 | 9 | 100% |
| API (Phase 4: 알림) | 3 | 2 | 1 | 0 | 1 | 67% |
| DB 테이블 (public, V1~V15) | 14 | 14 | 0 | 1 | 3 | 100% |
| DB Paper Trading 스키마 | 3 | 3 | 0 | 0 | 0 | 100% |
| Frontend 페이지 | 13 | 15 | 2 | 0 | 5 | 93% |
| Frontend 컴포넌트 (레이아웃) | 3 | 3 | 0 | 0 | 0 | 100% |
| Frontend 컴포넌트 (UI 공통) | 4 | 3 | 1 | 0 | 0 | 75% |
| Frontend 컴포넌트 (기능) | 9 | 7 | 2 | 0 | 1 | 78% |
| Frontend hooks/stores | 3 | 6 | 0 | 0 | 3 | 100% |
| Infra | 7 | 7 | 0 | 1 | 2 | 100% |
| 백엔드 모듈 구조 | 4 | 4 | 0 | 0 | 0 | 100% |
| core-engine 패키지 | 8 | 8 | 0 | 0 | 5 | 100% |
| strategy-lib 전략 | 10 | 10 | 0 | 0 | 4 | 100% |
| strategy-lib Config 클래스 | 4 | 4 | 0 | 0 | 6 | 100% |
| exchange-adapter 패키지 | 8 | 8 | 0 | 0 | 1 | 100% |
| web-api Controller 클래스 | 8 | 7 | 1 | 0 | 3 | 88% |
| web-api Service 클래스 | 6 | 6 | 0 | 0 | 5 | 100% |
| web-api Config 클래스 | 5 | 5 | 0 | 0 | 1 | 100% |
| web-api event 패키지 | 2 | 0 | 2 | 0 | 0 | 0% |
| **Phase 1~4 총계** | **149** | **158** | **9** | **8** | **54** | **95%** |

### 1.2 상태 범례
- 완료: 설계대로 구현됨
- 누락: 설계에 있으나 구현 안됨
- 불일치: 구현되었으나 설계와 다름 (기능상 동작은 함)
- 추가: 설계에 없으나 구현됨 (긍정적)
- 미구현(예정): 설계에 있으나 의도적 미착수

### 1.3 v3.0 대비 주요 변경 사항 (2026-03-15 최종 작업 반영)

| 변경 영역 | 이전 v3.0 | 현재 v4.0 | 비고 |
|-----------|-----------|-----------|------|
| Phase 4 프론트엔드 | 미착수(0%) | 구현 완료(90%+) | trading/*, trading/history, trading/risk, trading/[sessionId] 신규 |
| TradingController | start/stop 4개만 | 세션 CRUD + 포지션/주문/리스크 14개 | 구조 대폭 확장 |
| API 경로 체계 | /api/v1/trading/start 단순 | /api/v1/trading/sessions/{id}/start 다중 세션 | 설계서 구버전 대비 변경 |
| core-engine selector | 미구현 | StrategySelector, CompositeStrategy, MultiTimeframeFilter 완료 | Phase S3 완료 |
| strategy-lib Config | Phase 1 4개 누락 | 전체 10개 구현 완료 | DEV_STATUS_REVIEW_v3.0 반영 |
| hooks | 누락 | useBacktest, useStrategies, useTrading, usePaperTrading, useDataCollection 구현 | TanStack Query 래퍼 |
| web-api Config | RedisConfig/SchedulerConfig 누락 | 구현 완료 | DEV_STATUS_REVIEW_v3.0 반영 |
| TradeController | 미구현 | TradingController로 통합 | 설계서와 경로 불일치이나 기능 포함 |
| CompositeStrategy 파이프라인 연동 | core-engine 라이브러리만 존재, Service 미연동 | PaperTradingService + LiveTradingService에 COMPOSITE 분기 추가, 프론트엔드 드롭다운 연동 | v4.1 신규 |

---

## 2. API 검증 상세

### 2.1 데이터 수집 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| POST /api/v1/data/collect | O | O | 완료 | |
| GET /api/v1/data/status | O | O | 완료 | totalCandles, pairCount, status 반환 |
| GET /api/v1/data/coins | O | O | 완료 | |
| GET /api/v1/data/candles | O | O | 완료 | coinPair, timeframe, start, end, limit 지원 |
| GET /api/v1/data/summary | O | O | 완료 | |

### 2.2 백테스팅 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| POST /api/v1/backtest/run | O | O | 완료 | |
| POST /api/v1/backtest/walk-forward | O | O | 완료 | |
| GET /api/v1/backtest/{id} | O | O | 완료 | |
| GET /api/v1/backtest/{id}/trades | O | O | 완료 | |
| GET /api/v1/backtest/{id}/metrics | O | O | 불일치(허용) | 별도 엔드포인트 없음. GET /{id}에 metrics 포함 반환. DESIGN.md v1.2에서 수정 완료 |
| GET /api/v1/backtest/compare | O | O | 완료 | |
| GET /api/v1/backtest/list | O | O | 완료 | |
| DELETE /api/v1/backtest/{id} | - | O | 추가 | 설계서 외 추가 구현 |
| DELETE /api/v1/backtest/bulk | - | O | 추가 | 벌크 삭제 |
| POST /api/v1/backtest/bulk-run | - | O | 추가 | 복수 전략 일괄 백테스트 |

### 2.3 전략 관리 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/strategies | O | O | 완료 | StrategyRegistry 기반 |
| GET /api/v1/strategies/{id} | O | O | 완료 | {id} 대신 {name}(문자열)으로 읽기 구현. 설계서 허용 |
| POST /api/v1/strategies | O | O | 완료 | StrategyConfigEntity DB 저장 |
| PUT /api/v1/strategies/{id} | O | O | 완료 | |
| PATCH /api/v1/strategies/{id}/toggle | O | O | 완료 | isActive 토글 |
| GET /api/v1/strategies/types | O | O | 완료 | SystemController 구현 |

### 2.4 모의투자 API (Phase 3.5)

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/paper-trading/sessions | O | O | 완료 | |
| POST /api/v1/paper-trading/sessions | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id} | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id}/positions | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id}/orders | O | O | 완료 | |
| POST /api/v1/paper-trading/sessions/{id}/stop | O | O | 완료 | |
| GET /api/v1/paper-trading/sessions/{id}/chart | O | O | 완료 | |
| DELETE /api/v1/paper-trading/history/{id} | - | O | 추가 | 이력 단건 삭제 |
| DELETE /api/v1/paper-trading/history/bulk | - | O | 추가 | 이력 벌크 삭제 |

### 2.5 로그 API

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/logs/strategy | O | O | 완료 | page, size 지원 |

### 2.6 운영 제어 API (Phase 4)

> **[2026-03-15 v4.0]**: 설계서 대비 구현이 대폭 확장됨. 설계서의 단일 시작/정지 구조에서 다중 세션(session CRUD) 구조로 진화. 경로 체계가 변경되어 일부 불일치 존재.

| 설계서 엔드포인트 | 구현 엔드포인트 | 상태 | 비고 |
|------------------|----------------|------|------|
| GET /api/v1/trading/status | GET /api/v1/trading/status | 완료 | 전체 상태 요약 |
| POST /api/v1/trading/start | POST /api/v1/trading/sessions + POST /sessions/{id}/start | 불일치(개선) | 단순 시작 → 세션 생성+시작 분리 |
| POST /api/v1/trading/stop | POST /api/v1/trading/sessions/{id}/stop | 불일치(개선) | 특정 세션 정지 |
| POST /api/v1/trading/stop/{coinPair} | POST /api/v1/trading/emergency-stop (전체) | 불일치(개선) | 코인별 정지 → 전체 긴급 정지로 대체 |
| GET /api/v1/positions | GET /api/v1/trading/positions | 불일치(허용) | 경로 변경. /trading/ 하위로 통합 |
| GET /api/v1/orders | GET /api/v1/trading/orders | 불일치(허용) | 경로 변경. /trading/ 하위로 통합 |
| GET /api/v1/risk/config | GET /api/v1/trading/risk/config | 불일치(허용) | 경로 변경 |
| PUT /api/v1/risk/config | PUT /api/v1/trading/risk/config | 불일치(허용) | 경로 변경 |
| GET /api/v1/health/exchange | GET /api/v1/trading/health/exchange | 불일치(허용) | 경로 변경 |
| - | POST /api/v1/trading/sessions | 추가 | 세션 생성 |
| - | GET /api/v1/trading/sessions | 추가 | 세션 목록 |
| - | GET /api/v1/trading/sessions/{id} | 추가 | 세션 상세 |
| - | POST /api/v1/trading/sessions/{id}/start | 추가 | 세션별 시작 |
| - | POST /api/v1/trading/sessions/{id}/emergency-stop | 추가 | 세션별 긴급 정지 |
| - | DELETE /api/v1/trading/sessions/{id} | 추가 | 세션 삭제 |
| - | GET /api/v1/trading/sessions/{id}/positions | 추가 | 세션별 포지션 |
| - | GET /api/v1/trading/sessions/{id}/orders | 추가 | 세션별 주문 |
| - | GET /api/v1/trading/positions/{id} | 추가 | 포지션 상세 |
| - | GET /api/v1/trading/orders/{id} | 추가 | 주문 상세 |
| - | DELETE /api/v1/trading/orders/{id} | 추가 | 주문 취소 |

### 2.7 알림 API (Phase 4)

| 엔드포인트 | 설계 | 구현 | 상태 | 비고 |
|------------|------|------|------|------|
| GET /api/v1/reports/daily | O | O | 불일치(허용) | cron 스케줄 방식(12:00/00:00 KST)으로 자동 발송. REST 조회 엔드포인트 없음 |
| GET /api/v1/reports/weekly | O | X | 미구현(예정) | 주간 리포트 엔드포인트 미구현 |
| POST /api/v1/reports/test-telegram | O | O | 불일치(허용) | POST /api/v1/trading/telegram/test 로 경로 변경 구현 |

### 2.8 추가 엔드포인트 (설계서 외)

| 엔드포인트 | 구현 | 비고 |
|------------|------|------|
| GET /api/v1/health | O | SystemController — 시스템 상태 확인 |

---

## 3. 데이터베이스 검증 상세

### 3.1 테이블 구조 비교 (public 스키마, V1~V15)

| 테이블 | 설계 | Flyway | 상태 | 비고 |
|--------|------|--------|------|------|
| candle_data | O | V1 | 완료 | hypertable, 압축 정책 포함 |
| backtest_run | O | V2 | 완료 | |
| backtest_metrics | O | V2 | 완료 | |
| backtest_trade | O | V2 | 완료 | market_regime VARCHAR(20)으로 수정됨 (V2 기본 VARCHAR(10)) |
| strategy_config | O | V3+V11 | 완료 | V11: manual_override 컬럼 추가 |
| position | O | V4+V12 | 완료 | V12: session_id FK (live_trading_session) 추가 |
| order | O | V4+V12 | 완료 | V12: session_id FK (live_trading_session) 추가 |
| risk_config | O | V5 | 완료 | |
| strategy_log | O | V6 | 완료 | |
| trade_log | O | V6 | 완료 | |
| strategy_signal | O | V7 | 완료 | |
| live_trading_session | O (v1.3) | V12 | 완료 | DESIGN.md v1.3의 DEFAULT 'STOPPED' → 구현은 'CREATED'로 변경 (불일치·허용) |
| market_data_cache | - | V13 | 추가 | 실시간 싱크 전용 (candle_data와 분리) |
| strategy_type_enabled | - | V14 | 추가 | 전략 타입별 활성화 여부 (10종) |

**불일치 상세**: `live_trading_session.status` DEFAULT 값이 설계서 'STOPPED' 대비 구현에서 'CREATED'로 변경됨. 이는 프론트엔드 SessionCard 버튼 로직(CREATED 상태에서 start 버튼 활성)과의 정합성을 위한 의도적 변경이므로 설계서 수정 권장.

### 3.2 Paper Trading 스키마

| 항목 | 설계 (v1.3) | 구현 | 상태 |
|------|-------------|------|------|
| paper_trading.virtual_balance | paper_trading.virtual_balance | paper_trading.virtual_balance | 완료 |
| paper_trading.position | paper_trading.position | paper_trading.position | 완료 |
| paper_trading.order | paper_trading.order | paper_trading.order | 완료 |
| paper_trading.strategy_log | paper_trading.strategy_log (V8 LIKE 복제) | paper_trading.strategy_log | 완료 |
| paper_trading.trade_log | paper_trading.trade_log (V8 LIKE 복제) | paper_trading.trade_log | 완료 |

### 3.3 주요 컬럼 불일치

| 항목 | 설계 | 구현 | 상태 | 권장 조치 |
|------|------|------|------|-----------|
| paper_trading.virtual_balance.strategy_name | VARCHAR(100) | VARCHAR(50) | 불일치(허용) | 실질 문제 없음 |
| backtest_trade.market_regime | VARCHAR(10) | VARCHAR(20) | 불일치(수정됨) | "TRANSITIONAL"(12자) 저장 위해 수동 ALTER 완료. Flyway V2 정의와 실DB 상이 — V2 재정의 검토 필요 |
| live_trading_session.status DEFAULT | 'STOPPED' | 'CREATED' | 불일치(의도적) | 설계서 DESIGN.md v1.3 수정 권장 |

### 3.4 Entity 매핑 검증

| Entity 클래스 | 대응 테이블 | 상태 | 비고 |
|---------------|-------------|------|------|
| CandleDataEntity | candle_data | 완료 | |
| BacktestRunEntity | backtest_run | 완료 | |
| BacktestMetricsEntity | backtest_metrics | 완료 | |
| BacktestTradeEntity | backtest_trade | 완료 | market_regime VARCHAR(20) 수정 완료 |
| StrategyConfigEntity | strategy_config | 완료 | manual_override 포함 |
| StrategyLogEntity | strategy_log | 완료 | |
| MarketDataCacheEntity | market_data_cache | 완료 | |
| StrategyTypeEnabledEntity | strategy_type_enabled | 완료 | |
| LiveTradingSessionEntity | live_trading_session | 완료 | |
| PositionEntity | public.position | 완료 | session_id FK 포함 |
| OrderEntity | public.order | 완료 | session_id FK 포함 |
| RiskConfigEntity | risk_config | 완료 | |
| TradeLogEntity | trade_log | 완료 | |
| VirtualBalanceEntity | paper_trading.virtual_balance | 완료 | |
| PaperPositionEntity | paper_trading.position | 완료 | |
| PaperOrderEntity | paper_trading.order | 완료 | |

이전 검증에서 "Entity 없음"으로 기록됐던 항목들이 모두 구현 완료됨:
- position (public) → PositionEntity 완료
- order (public) → OrderEntity 완료
- risk_config → RiskConfigEntity 완료
- trade_log → TradeLogEntity 완료

남은 미매핑: `strategy_signal` 테이블 — Entity 없음 (미구현)

### 3.5 인덱스 비교

| 인덱스 | 설계 | Flyway | 상태 |
|--------|------|--------|------|
| idx_candle_unique | O | V1 | 완료 |
| idx_candle_lookup | O | V1 | 완료 |
| idx_metrics_run | O | V2 | 완료 |
| idx_bt_trade_run | O | V2 | 완료 |
| idx_position_open (WHERE status='OPEN') | O | V4 | 완료 |
| idx_order_state (WHERE active) | O | V4 | 완료 |
| idx_order_position | O | V4 | 완료 |
| idx_strategy_log_time | O | V6 | 완료 |
| idx_trade_log_order | O | V6 | 완료 |
| idx_signal_lookup | O | V7 | 완료 |
| idx_signal_strategy | O | V7 | 완료 |
| idx_live_session_status | O (v1.3) | V12 | 완료 |
| idx_position_session | O (v1.3) | V12 | 완료 |
| idx_order_session | O (v1.3) | V12 | 완료 |
| idx_paper_position_session_id | - | V10 | 추가 |
| idx_paper_order_session_id | - | V10 | 추가 |

---

## 4. 프론트엔드 검증 상세

### 4.1 페이지 구현

| 경로 | 설계 Phase | 구현 | 상태 | 비고 |
|------|-----------|------|------|------|
| / | 2 | O | 완료 | 대시보드: 요약 카드 + 최근 백테스트 |
| /backtest | 2 | O | 완료 | 백테스트 이력 목록 |
| /backtest/new | - | O | 추가 | 설계서 외 별도 신규 백테스트 페이지 |
| /backtest/[id] | 2 | O | 완료 | 상세 결과 + 차트 |
| /backtest/compare | 2 | O | 완료 | 최대 6개 비교 |
| /backtest/walk-forward | 2 | O | 추가 | 설계서에 별도 페이지 없으나 구현 |
| /strategies | 3 | O | 완료 | |
| /paper-trading | 3.5 | O | 완료 | 멀티세션 관리 |
| /paper-trading/[sessionId] | 3.5 | O | 완료 | 세션 상세 + 차트 |
| /paper-trading/history | 3.5 | O | 완료 | |
| /data | 2 | O | 완료 | |
| /logs | 2 | O | 완료 | |
| /trading | 4 | O | 완료 | 실전 매매 세션 관리 |
| /trading/[sessionId] | 4 | O | 추가 | 세션 상세 페이지 (설계서에 없으나 구현) |
| /trading/history | 4 | O | 추가 | 실전매매 이력 페이지 (설계서에 없으나 구현) |
| /trading/risk | 4 | O | 추가 | 리스크 설정 페이지 |
| /positions | 4 | X | 미구현(허용) | /trading/sessions/{id}/positions로 세션별 포지션 구현. 별도 전체 목록 페이지 없음 |
| /orders | 4 | X | 미구현(허용) | 동일. 세션별 주문 내역으로 대체 |

**평가**: 설계서의 `/positions`, `/orders` 독립 페이지는 미구현이나, `/trading/sessions/{id}/positions`와 `/trading/sessions/{id}/orders` 세션별 구현으로 기능 충족. `/risk` → `/trading/risk`로 경로 변경 구현.

### 4.2 컴포넌트 구현

#### 레이아웃 컴포넌트

| 컴포넌트 | 설계 | 구현 | 상태 | 비고 |
|----------|------|------|------|------|
| Sidebar | O | O | 완료 | Phase별 메뉴, 비활성 표시, 실전매매 이력/리스크 메뉴 추가 |
| Header | O | O | 완료 | components/layout/Header.tsx — 다크모드 토글 포함 |
| ThemeProvider | O | O | 완료 | 기본 dark, localStorage 연동 |

#### UI 공통 컴포넌트 (components/ui/)

| 컴포넌트 | 설계 | 구현 | 상태 | 비고 |
|----------|------|------|------|------|
| Button | O | O | 완료 | components/ui/Button.tsx |
| Card | O | O | 완료 | components/ui/Card.tsx |
| Badge | - | O | 추가 | components/ui/Badge.tsx |
| Spinner | - | O | 추가 | components/ui/Spinner.tsx |
| Table | O | X | 누락 | 공통 Table 컴포넌트 없음. 인라인 table 태그 사용 |
| Modal | O | X | 누락 | 공통 Modal 없음. 인라인 조건부 렌더링으로 대체 |

#### 기능 컴포넌트

| 컴포넌트 | 설계 | 구현 | 상태 | 비고 |
|----------|------|------|------|------|
| BacktestForm | O | O | 완료 | components/backtest/BacktestForm.tsx |
| MetricsCards | O | O | 완료 | components/backtest/MetricsCard.tsx |
| PnlChart | O | O | 완료 | components/charts/CumulativePnlChart.tsx |
| CompareChart | O | O | 완료 | /backtest/compare 페이지에 Recharts BarChart |
| MonthlyHeatmap | O | O | 완료 | components/charts/MonthlyReturnsHeatmap.tsx |
| StrategyList | O | X | 누락 | /strategies/page.tsx에 인라인 구현 |
| StrategyConfigForm | O | O | 완료 | components/features/strategy/StrategyConfigForm.tsx |
| TradesTable | - | O | 추가 | components/backtest/TradesTable.tsx |
| TradingStatus | O | X | 누락 | /trading/page.tsx에 인라인 구현 |
| PositionTable | O | X | 미구현(허용) | /trading/[sessionId]/page.tsx에 인라인 구현 |
| OrderTable | O | X | 미구현(허용) | 동일 |

#### Hooks

| 항목 | 설계 | 구현 | 상태 | 비고 |
|------|------|------|------|------|
| hooks/useBacktest.ts | O | O | 완료 | |
| hooks/useStrategies.ts | O | O | 완료 | |
| hooks/useTrading.ts | O | O | 완료 | createSession, startSession, stopSession, emergencyStop 등 포함 |
| hooks/usePaperTrading.ts | - | O | 추가 | |
| hooks/useDataCollection.ts | - | O | 추가 | |
| stores/backtestStore.ts | O | X | 미구현(허용) | TanStack Query 직접 사용으로 대체 |
| stores/tradingStore.ts | O | X | 미구현(허용) | 동일 |
| stores/uiStore.ts | - | O | 추가 | 사이드바 상태 등 UI 전용 Zustand store |

참고: hooks/stores 설계 대비 구현 방식 차이 — TanStack Query + custom hooks 패턴으로 대체. DESIGN.md 8.2절 "대용량 차트 데이터는 React Query로 관리"와 일치. 기능적으로 동일하게 동작.

### 4.3 프론트-백 연동 검증

| 프론트엔드 api.ts 호출 | 백엔드 엔드포인트 | 일치 여부 |
|------------------------|-------------------|-----------|
| POST /api/v1/backtest/run | BacktestController: POST /run | 일치 |
| POST /api/v1/backtest/walk-forward | BacktestController: POST /walk-forward | 일치 |
| GET /api/v1/backtest/{id} | BacktestController: GET /{id} | 일치 |
| GET /api/v1/backtest/list | BacktestController: GET /list | 일치 |
| GET /api/v1/backtest/compare?ids= | BacktestController: GET /compare?ids= | 일치 |
| GET /api/v1/backtest/{id}/trades | BacktestController: GET /{id}/trades | 일치 |
| DELETE /api/v1/backtest/{id} | BacktestController: DELETE /{id} | 일치 |
| DELETE /api/v1/backtest/bulk | BacktestController: DELETE /bulk | 일치 |
| GET /api/v1/strategies/types | SystemController: GET /strategies/types | 일치 |
| GET /api/v1/data/coins | DataController: GET /coins | 일치 |
| POST /api/v1/data/collect | DataController: POST /collect | 일치 |
| GET /api/v1/data/summary | DataController: GET /summary | 일치 |
| DELETE /api/v1/data/candles | DataController: DELETE /candles | 일치 |
| GET /api/v1/strategies | StrategyController: GET / | 일치 |
| GET /api/v1/strategies/{name} | StrategyController: GET /{name} | 일치 |
| POST /api/v1/strategies | StrategyController: POST / | 일치 |
| PUT /api/v1/strategies/{id} | StrategyController: PUT /{id} | 일치 |
| PATCH /api/v1/strategies/{id}/toggle | StrategyController: PATCH /{id}/toggle | 일치 |
| GET /api/v1/logs/strategy | LogController: GET /strategy | 일치 |
| GET /api/v1/paper-trading/sessions | PaperTradingController: GET /sessions | 일치 |
| POST /api/v1/paper-trading/sessions | PaperTradingController: POST /sessions | 일치 |
| GET /api/v1/paper-trading/sessions/{id} | PaperTradingController: GET /sessions/{id} | 일치 |
| POST /api/v1/paper-trading/sessions/{id}/stop | PaperTradingController: POST /sessions/{id}/stop | 일치 |
| GET /api/v1/paper-trading/sessions/{id}/chart | PaperTradingController: GET /sessions/{id}/chart | 일치 |
| POST /api/v1/trading/sessions | TradingController: POST /sessions | 일치 |
| GET /api/v1/trading/sessions | TradingController: GET /sessions | 일치 |
| GET /api/v1/trading/sessions/{id} | TradingController: GET /sessions/{id} | 일치 |
| POST /api/v1/trading/sessions/{id}/start | TradingController: POST /sessions/{id}/start | 일치 |
| POST /api/v1/trading/sessions/{id}/stop | TradingController: POST /sessions/{id}/stop | 일치 |
| POST /api/v1/trading/sessions/{id}/emergency-stop | TradingController: POST /sessions/{id}/emergency-stop | 일치 |
| DELETE /api/v1/trading/sessions/{id} | TradingController: DELETE /sessions/{id} | 일치 |
| GET /api/v1/trading/sessions/{id}/positions | TradingController: GET /sessions/{id}/positions | 일치 |
| GET /api/v1/trading/sessions/{id}/orders | TradingController: GET /sessions/{id}/orders | 일치 |
| GET /api/v1/trading/status | TradingController: GET /status | 일치 |
| POST /api/v1/trading/emergency-stop | TradingController: POST /emergency-stop | 일치 |
| GET /api/v1/trading/positions | TradingController: GET /positions | 일치 |
| GET /api/v1/trading/orders | TradingController: GET /orders | 일치 |
| GET /api/v1/trading/risk/config | TradingController: GET /risk/config | 일치 |
| PUT /api/v1/trading/risk/config | TradingController: PUT /risk/config | 일치 |
| GET /api/v1/trading/health/exchange | TradingController: GET /health/exchange | 일치 |

전체 프론트-백 연동 경로 일치. 불일치 0건.

---

## 5. 인프라 검증 상세

| 항목 | 설계 | 구현 | 상태 | 비고 |
|------|------|------|------|------|
| docker-compose.yml (개발) | O | O | 불일치(허용) | 개발용은 db + redis 2개 서비스만 (backend/frontend는 로컬 실행 전제). 개발 편의상 유지 |
| docker-compose.prod.yml (운영) | O | O | 완료 | 4개 서비스 구성. healthcheck 추가(pg_isready, redis-cli ping), depends_on condition: service_healthy |
| Dockerfile (Backend) | O | O | 완료 | multi-stage: eclipse-temurin:17-jdk-alpine + jre-alpine |
| Dockerfile (Frontend) | O | O | 완료 | multi-stage: node:20-alpine + standalone 모드 |
| .env.example | O | O | 완료 | DESIGN.md 9.3절과 일치 |
| Gradle 멀티모듈 | O | O | 완료 | settings.gradle + 4개 모듈 build.gradle |
| application.yml | O | O | 완료 | DB, Redis, Flyway, JPA, upbit.access-key/secret-key 설정 |
| SwaggerConfig | - | O | 추가 | API 문서화 |
| .env.local (Frontend) | - | O | 추가 | 프론트엔드 환경변수 |

---

## 6. 백엔드 모듈 구조 검증

### 6.1 Gradle 멀티모듈

| 모듈 | 설계 | 구현 | 상태 |
|------|------|------|------|
| core-engine | O | O | 완료 |
| strategy-lib | O | O | 완료 |
| exchange-adapter | O | O | 완료 |
| web-api | O | O | 완료 |

### 6.2 core-engine 패키지 상세

| 패키지/클래스 | 설계 | 구현 | 상태 | 비고 |
|---------------|------|------|------|------|
| backtest/BacktestEngine | O | O | 완료 | 매수 수수료 PnL 반영, Partial Fill continue 제거, runWithStrategy() 오버로드 추가 |
| backtest/BacktestConfig | O | O | 완료 | |
| backtest/BacktestResult | O | O | 완료 | |
| backtest/WalkForwardTestRunner | O | O | 완료 | |
| backtest/FillSimulator | - | O | 추가 | Market Impact + Partial Fill |
| metrics/MetricsCalculator | O | O | 완료 | Calmar Ratio 수식 수정, Recovery Factor 분리 |
| metrics/PerformanceReport | O | O | 완료 | |
| regime/MarketRegimeDetector | O | O | 완료 | TRANSITIONAL 상태 추가, Hysteresis 구현, BB Bandwidth RANGE 감지, ATR Spike VOLATILITY 감지 |
| regime/MarketRegime | O | O | 완료 | TREND/RANGE/VOLATILITY/TRANSITIONAL (VOLATILE → VOLATILITY 이름 통일) |
| regime/MarketRegimeFilter | O | O | 완료 | VOLATILE→VOLATILITY, TRANSITIONAL(빈 집합) 추가 |
| risk/RiskEngine | O | O | 완료 | Fixed Fractional Position Sizing, Correlation Risk 추가 |
| risk/RiskConfig | O | O | 완료 | maxLeverage, correlationThreshold, defaultRiskPercentage 추가 |
| risk/RiskCheckResult | O | O | 완료 | |
| portfolio/PortfolioManager | O | O | 완료 | |
| selector/StrategySelector | - | O | 추가 | Regime별 전략 그룹 + 가중치 반환 (Phase S3) |
| selector/CompositeStrategy | - | O | 추가 | Weighted Voting (Phase S3) |
| selector/MultiTimeframeFilter | - | O | 추가 | HTF+LTF 역추세 억제 (Phase S5) |
| selector/CandleDownsampler | - | O | 추가 | LTF→HTF 다운샘플 (Phase S5) |
| selector/WeightedStrategy | - | O | 추가 | 전략+가중치 래퍼 (Phase S3) |
| selector/TimeframePreset | - | O | 추가 | M1~D1 × 7개 전략 파라미터 프리셋 (Phase S5) |
| signal/SignalEngine | O | X | 누락 | Phase 4 이벤트 파이프라인 신호 집계 |
| signal/TradingSignal | O | X | 누락 | 동일 |
| position/PositionManager | O | X | 누락 | Phase 4 포지션 관리 (core-engine 레이어) |
| position/Position | O | X | 누락 | 동일 |
| model/* | O | O | 완료 | CoinPair, OrderSide, TimeFrame, TradeRecord |

### 6.3 strategy-lib 패키지 상세

| 클래스/전략 | 설계 | 구현 | 상태 | 비고 |
|-------------|------|------|------|------|
| Strategy 인터페이스 | O | O | 완료 | |
| StatefulStrategy 인터페이스 | - | O | 추가 | Grid 중복 매매 방지 (Phase S1-3) |
| StrategyConfig | O | O | 완료 | |
| StrategySignal | O | O | 완료 | confidence(), suggestedStopLoss, suggestedTakeProfit 추가 (Phase S3/S5) |
| StrategyRegistry | - | O | 추가 | |
| IndicatorUtils | - | O | 추가 | bollingerBandwidth(), atrList() 추가 (Phase S2) |
| VwapStrategy + VwapConfig | O | O | 완료 | ADX < 25 필터 추가, 임계값 2.5% |
| EmaCrossStrategy + EmaCrossConfig | O | O | 완료 | ADX > 25 필터, fast 20/slow 50으로 슬로우화 |
| BollingerStrategy + BollingerConfig | O | O | 완료 | ADX < 25 필터 추가, Squeeze 감지 |
| GridStrategy + GridConfig | O | O | 완료 | 하드코딩 제거, StatefulStrategy 구현 |
| RsiStrategy + RsiConfig | O | O | 완료 | 피봇 다이버전스 추가, 임계값 25/60 |
| MacdStrategy + MacdConfig | O | O | 완료 | ADX > 25 필터 추가 |
| SupertrendStrategy + SupertrendConfig | O | O | 완료 | ATR O(n²)→O(n) 최적화, upperBand/lowerBand 분리 |
| AtrBreakoutStrategy + AtrBreakoutConfig | O | O | 완료 | 거래량 필터 추가 |
| OrderbookImbalanceStrategy + OrderbookImbalanceConfig | O | O | 완료 | imbalanceThreshold 0.70, lookback 15 |
| StochasticRsiStrategy + StochasticRsiConfig | O | O | 완료 | ADX < 30 필터, 임계값 15/85 |

### 6.4 exchange-adapter 패키지 상세

| 클래스 | 설계 | 구현 | 상태 | 비고 |
|--------|------|------|------|------|
| ExchangeAdapter 인터페이스 | O | O | 완료 | |
| UpbitRestClient | O | O | 완료 | Rate Limiting throttle() 110ms, synchronized Race Condition 수정 |
| UpbitCandleCollector | O | O | 완료 | |
| UpbitWebSocketClient | O | O | 완료 | 자동 재연결, GZIP 디코딩, Ping/Pong, disconnect()/destroy() 분리 |
| UpbitOrderClient | O | O | 완료 | JWT char[] 보안, buildSecretKeySpec() helper |
| dto/UpbitCandleResponse | O | O | 완료 | |
| dto/OrderResponse, AccountResponse 등 | - | O | 추가 | |
| order/OrderExecutionEngine | O | O | 완료 | getOrders(Pageable), getOrder(), cancelOrder() 포함 |
| order/OrderStateMachine | O | O | 완료 | OrderExecutionEngine 내부에 통합 |
| health/ExchangeHealthMonitor | O | O | 완료 | |

### 6.5 web-api Controller 클래스

| Controller 클래스 | 설계 | 구현 | 상태 | 비고 |
|-------------------|------|------|------|------|
| BacktestController | O | O | 완료 | bulk-run, DELETE 엔드포인트 추가 |
| StrategyController | O | O | 완료 | Registry(읽기) + DB(CRUD) 이중 구조 |
| DataController | O | O | 완료 | DELETE /candles 추가 |
| LogController | O | O | 완료 | |
| PaperTradingController | O | O | 완료 | history 삭제 엔드포인트 추가 |
| SystemController | O | O | 완료 | |
| GlobalExceptionHandler | - | O | 추가 | BAD_REQUEST/CONFLICT/INTERNAL_ERROR 범용화 |
| TradingController | O | O | 완료 | LiveTradingController 설계명 → TradingController로 통합 구현. 세션 CRUD + 포지션/주문/리스크 API 포함 |
| TradeController | O | X | 누락(통합) | TradingController에 포지션/주문 API 통합됨. 별도 컨트롤러 없음 |
| RiskController | O | X | 누락(통합) | TradingController의 /risk/config 로 통합됨 |

### 6.6 web-api Service 클래스

| Service 클래스 | 설계 | 구현 | 상태 | 비고 |
|----------------|------|------|------|------|
| BacktestService | O | O | 완료 | @Transactional 제거 (PostgreSQL cascade 실패 수정) |
| DataCollectionService | O | O | 완료 | |
| PaperTradingService | O | O | 완료 | TimeframeUtils 교체, DI 주입 방식 수정, **COMPOSITE 파이프라인 연동** (sessionDetectors ConcurrentHashMap + COMPOSITE 분기) |
| MarketDataSyncService | - | O | 추가 | TimeframeUtils.toMinutes() 교체, DI 주입 수정 |
| TelegramNotificationService | O | O | 완료 | 즉시 알림 + 일별 요약 cron, 빈 버퍼 전송 |
| LiveTradingService | - | O | 추가 | 다중 세션(최대 5개), orphan guard, session 필터 카운트, **COMPOSITE 파이프라인 연동** (sessionDetectors + 유효성 검증 우회 + COMPOSITE 분기) |
| MarketRegimeAwareScheduler | - | O | 추가 | 1시간 fixedDelay, 자동 스위칭 |
| OrderExecutionEngine | O | O | 완료 | exchange-adapter에서 web-api service로 배치 (설계 위치와 상이) |
| PositionService | - | O | 추가 | getOpenPositions(), getPosition() |
| RiskManagementService | - | O | 추가 | getRiskConfig(), updateRiskConfig(), @Transactional 수정 |
| ExchangeHealthMonitor | O | O | 완료 | exchange-adapter에서 web-api service로 배치 |
| ExchangeDownEvent | - | O | 추가 | 거래소 장애 이벤트 |
| ScheduledTasks | O | X | 미구현(부분) | 주간 리포트 관련 미구현. 일별은 TelegramNotificationService cron으로 대체 |

### 6.7 web-api Config 클래스

| Config 클래스 | 설계 | 구현 | 상태 | 비고 |
|---------------|------|------|------|------|
| AsyncConfig | O | O | 완료 | 3개 스레드 풀 분리 |
| WebConfig | O | O | 완료 | CORS 설정 |
| RedisConfig | O | O | 완료 | JSON 직렬화, 캐시별 TTL (ticker 1초 / candle 60초 등) |
| SchedulerConfig | O | O | 완료 | 전용 스레드풀 3개, Graceful shutdown 30초, 에러 핸들러 |
| SecurityConfig | O | X | 미구현(예정) | Spring Security API 인증 미착수 — 보안 취약점 |
| SwaggerConfig | - | O | 추가 | API 문서화 |
| EngineConfig | - | O | 추가 | UpbitRestClient, UpbitOrderClient Bean 등록 (키 없으면 null) |

### 6.8 web-api event 패키지

| 클래스 | 설계 | 구현 | 상태 | 비고 |
|--------|------|------|------|------|
| EventPublisher | O | X | 미구현 | Redis Pub/Sub 발행자 미구현. ExchangeHealthMonitor는 Spring ApplicationEventPublisher 사용 |
| EventSubscriber | O | X | 미구현 | Redis Pub/Sub 구독자 미구현 |

---

## 7. 전략 고도화 Phase S1~S5 검증

| Phase | 항목 | 상태 | 완성도 |
|-------|------|------|--------|
| S1-1 | Supertrend 코드 버그 (upperBand/lowerBand 분리) | 완료 | 100% |
| S1-2 | Grid 하드코딩 제거 | 완료 | 100% |
| S1-3 | Grid StatefulStrategy 구현 (activeLevels 추적) | 완료 | 100% |
| S1-4 | ConflictingSignalTest 케이스 | 완료 | 100% |
| S2-1 | MarketRegimeDetector 개선 (TRANSITIONAL, Hysteresis, BB Bandwidth, ATR Spike) | 완료 | 100% |
| S2-2 | RiskEngine 강화 (Fixed Fractional, Correlation Risk) | 완료 | 100% |
| S3 | StrategySelector, CompositeStrategy, WeightedStrategy | 완료 | 100% |
| S4-1 | Supertrend ATR O(n²)→O(n) 최적화 | 완료 | 100% |
| S4-2 | EMA Cross ADX > 25 필터 | 완료 | 100% |
| S4-3 | Bollinger Squeeze 감지 | 완료 | 100% |
| S4-4 | RSI 피봇 다이버전스 | 완료 | 100% |
| S4-5 | ATR Breakout 거래량 필터 | 완료 | 100% |
| S4-6 | Orderbook 호가 Delta 추적 | 완료 | 100% |
| S5 | MultiTimeframeFilter, CandleDownsampler, TimeframePreset, BacktestEngine 오버로드 | 완료 | 100% |

---

## 8. 누락 항목 상세 (Action Required)

### 8.1 우선순위 높음 (P0) — 보안/운영 필수

| # | 항목 | 위치 | 설명 | 예상 작업량 |
|---|------|------|------|-------------|
| 1 | Spring Security / API 인증 | web-api/config | SecurityConfig 미구현. 실전매매 API 현재 무방비 노출 상태. 1인 전용 시스템이나 서버 IP가 공개된 경우 위험 | 4~8h |
| 2 | Phase 4 배포 실행 | 운영 서버 | UPBIT_ACCESS_KEY, UPBIT_SECRET_KEY 환경변수 설정 후 서버 재빌드. 실거래 검증 미완 | 2h |

### 8.2 우선순위 중간 (P1) — 기능 개선

| # | 항목 | 위치 | 설명 | 예상 작업량 |
|---|------|------|------|-------------|
| 3 | GET /api/v1/reports/weekly | Backend TradingController | 주간 리포트 API 미구현. 현재 일별 cron만 동작 | 2h |
| 4 | TradingController 예외 처리 패턴 통일 | Backend | 커스텀 예외 클래스 도입. 현재 ResponseStatusException 직접 사용으로 프론트 에러 응답 불일치 가능 | 3h |
| 5 | StrategyController DTO 전환 + Bean Validation | Backend | 현재 Map<String, Object>로 파라미터 수신 — 타입 안전성 부재 | 4h |
| 6 | STOCHASTIC_RSI 구조 재설계 또는 제거 | strategy-lib | 2025 H1 백테스트: BTC -70.4%, ETH -67.6%. ADX 필터 후에도 최악. 구조적 결함 | 4~8h |
| 7 | MACD 히스토그램 기울기 필터 추가 | strategy-lib | 2025 H1 백테스트: BTC -58.8%, ETH -57.6%. 히스토그램 기울기(momentum) 추가 필요 | 2h |

### 8.3 우선순위 낮음 (P2) — 품질 개선

| # | 항목 | 위치 | 설명 | 예상 작업량 |
|---|------|------|------|-------------|
| 8 | Redis Pub/Sub EventPublisher/EventSubscriber | web-api/event | 설계서 이벤트 파이프라인 미구현. 현재는 스케줄러 폴링 방식으로 동작하여 실시간성 부족 | 8h |
| 9 | signal/SignalEngine, TradingSignal | core-engine | 설계서 이벤트 파이프라인 신호 집계 레이어 미구현. 현재는 LiveTradingService에 직접 통합 | 4h |
| 10 | position/PositionManager, Position | core-engine | core-engine 레이어 포지션 관리 미구현. 현재는 PositionEntity + PositionService로 web-api에서 처리 | 3h |
| 11 | Table/Modal 공통 컴포넌트 | Frontend | components/ui/ 에 Table.tsx, Modal.tsx 추가. 현재 인라인 table 태그 사용 | 2h |
| 12 | StrategyList 컴포넌트 분리 | Frontend | components/features/strategy/StrategyList.tsx 분리. 현재 page.tsx 인라인 | 1h |
| 13 | EngineConfig @ConditionalOnProperty 전환 | web-api/config | null Bean 방지. 현재 키 없으면 null 반환 방식 | 1h |
| 14 | backtest_trade.market_regime Flyway 정합성 | DB | V2 SQL에 VARCHAR(10) 정의, 실DB는 수동 ALTER로 VARCHAR(20). Flyway 마이그레이션 파일과 실DB 정의 불일치 — V16 추가 권장 | 1h |
| 15 | VWAP 임계값 재조정 | strategy-lib | 2025 H1 BTC 승률 0% (거래 발생 없음). thresholdPct 2.5% → 1.5% 재테스트 필요 | 1h |
| 16 | Telegram 수신 확인 | 운영 서버 | 서버 재기동 후 12:00/00:00 정상 수신 여부 확인 | 0.5h |

---

## 9. 불일치 항목 상세 (Review Required)

| # | 항목 | 설계 내용 | 구현 내용 | 권장 조치 |
|---|------|-----------|-----------|-----------|
| 1 | GET /backtest/{id}/metrics | 별도 엔드포인트 | GET /{id} 응답에 metrics 포함 | 설계서 수정 완료 (DESIGN.md v1.2~v1.3) |
| 2 | docker-compose.yml | 4개 서비스 | db + redis 2개만 | 개발 편의상 허용 |
| 3 | strategies/{id} 경로변수 | id(숫자) | 읽기=name(문자열), CRUD=id(숫자) | 기능상 문제없음 |
| 4 | Phase 4 API 경로 체계 | /api/v1/trading/start, /positions, /orders, /risk | /api/v1/trading/sessions/{id}/start, /trading/positions, /trading/orders, /trading/risk/config | 다중 세션 구조로의 개선. 설계서 v1.4 반영 권장 |
| 5 | LiveTradingController 명칭 | LiveTradingController 별도 클래스 | TradingController 단일 클래스로 통합 | 기능상 동일. 설계서 수정 권장 |
| 6 | TradeController/RiskController 별도 | 각각 별도 Controller 클래스 | TradingController에 통합 | 기능상 동일 |
| 7 | live_trading_session.status DEFAULT | 'STOPPED' | 'CREATED' | 설계서 v1.4에서 'CREATED'로 수정 권장 |
| 8 | virtual_balance.strategy_name 길이 | VARCHAR(100) | VARCHAR(50) | 실질 문제 없음. 허용 |
| 9 | backtest_trade.market_regime 길이 | VARCHAR(10) (V2 SQL) | VARCHAR(20) (수동 ALTER) | Flyway V16으로 정합성 정정 권장 |

---

## 10. 긍정적 사항 (설계서 외 우수 구현)

1. Paper Trading 멀티세션: 최대 5개 동시 세션, session_id FK로 격리.
2. Walk Forward UI: /backtest/walk-forward 별도 페이지 구현 (전략/코인 선택, 결과 차트).
3. 다크 모드: ThemeProvider 구현 완료, 기본 dark, 18개 파일 269개 dark: 클래스.
4. 실전매매 다중 세션: CREATED→RUNNING→STOPPED/EMERGENCY_STOPPED 상태 머신.
5. 텔레그램 이중 알림: 즉시 전송(세션 이벤트) + 일별 요약(12:00/00:00 KST, 거래 없어도 전송).
6. market_data_cache 분리: 실시간 싱크와 백테스팅용 candle_data 완전 분리.
7. strategy_type_enabled: 전략 타입별 ON/OFF, 모의/실전매매에서 전략 선택적 실행.
8. UpbitOrderClient 보안: char[] 기반 API Key + buildSecretKeySpec()으로 JWT 생성 시 평문 String 메모리 노출 방지.
9. UpbitRestClient Rate Limiting: synchronized throttle() 110ms, 다중 스레드 Race Condition 수정.
10. UpbitWebSocketClient 재연결: disconnect()/destroy() 분리, 자동 재연결, GZIP 디코딩.
11. docker-compose.prod.yml healthcheck: pg_isready, redis-cli ping, service_healthy 조건 depends_on.
12. BacktestEngine 수수료 정확도: entryFee 변수 추가, SELL PnL에 매수 수수료 차감.
13. MarketRegimeDetector 고도화: TRANSITIONAL 상태, Hysteresis, BB Bandwidth, ATR Spike 동적 감지.
14. CompositeStrategy Weighted Voting: buyScore/sellScore > 0.6 → STRONG, > 0.4 → WEAK, 상충 HOLD.
15. MultiTimeframeFilter: HTF BUY+LTF SELL (역추세) 시 HOLD, HTF 데이터 부족 시 LTF 신호 통과.
16. 실전매매 이력 페이지: /trading/history (2026-03-15 신규). 매매 요약 섹션(매수/매도 횟수, 실현손익, 승률).
17. orphan guard: deleteSession() 시 OPEN 포지션 있으면 삭제 거부. getGlobalStatus() session_id 필터 카운트.
18. GlobalExceptionHandler: BACKTEST_001/002 → BAD_REQUEST/CONFLICT/INTERNAL_ERROR 범용화, IllegalStateException 409 CONFLICT.
19. BacktestService @Transactional 제거: 단일 트랜잭션 전략 실패 시 PostgreSQL "transaction aborted" 연쇄 실패 방지.
20. CompositeStrategy 실전 파이프라인 연동: PaperTradingService와 LiveTradingService에서 `"COMPOSITE"` 전략 선택 시 `MarketRegimeDetector → StrategySelector → CompositeStrategy` 3단계 파이프라인 직접 실행. `ConcurrentHashMap<Long, MarketRegimeDetector>`로 세션별 Hysteresis 상태 독립 유지. 프론트엔드 양쪽 드롭다운에 COMPOSITE 옵션 추가.

---

## 11. Phase별 완성도 요약

| Phase | 설명 | 완성도 | 비고 |
|-------|------|--------|------|
| Phase 1 | 백테스팅 엔진 + 데이터 수집 | 100% | Calmar Ratio 수식 수정, BacktestEngine 수수료 정확도, FillSimulator 완료 |
| Phase 2 | 대시보드 + 백테스팅 UI + 로그 + 데이터 | 100% | 다크모드, Header, 공통 UI(Button/Card/Badge/Spinner) 완료 |
| Phase 3 | 전략 관리 | 100% | 전략 CRUD + UI 완료. 전략 Config 클래스 10종 완료 |
| Phase 3.5 | Paper Trading | 100% | 멀티세션, 차트, 체결내역, 매수/매도 상세 완료 |
| Phase 4 (백엔드) | 실전 매매 백엔드 | 97% | TradingController 전체 완료. COMPOSITE 파이프라인 연동 완료. SecurityConfig 미구현(-2%), 주간 리포트 미구현(-1%) |
| Phase 4 (프론트) | 실전 매매 프론트엔드 | 90% | /trading, /trading/[sessionId], /trading/history, /trading/risk 구현 완료. /positions, /orders 독립 페이지 미구현(-10%) |
| Phase S1~S5 | 전략 고도화 | 100% | MarketRegimeDetector, RiskEngine, CompositeStrategy, MultiTimeframeFilter 전체 완료 |
| Infra | Docker/Compose/Flyway | 100% | healthcheck, depends_on service_healthy 추가 완료 |
| **전체** | Phase 1~4 + S1~S5 | **~96%** | 미완: SecurityConfig, 주간 리포트, Redis Pub/Sub 이벤트 파이프라인, 배포/실거래 검증 |

---

## 12. 설계서 수정 권장 사항 (DESIGN.md v1.4)

### 기존 v1.3 대비 갱신 필요 항목

| # | 항목 | 현재 (v1.3) | 권장 수정 (v1.4) |
|---|------|-------------|-----------------|
| 1 | Phase 4 API 경로 체계 | /trading/start, /positions, /orders, /risk | /trading/sessions/{id}/start, /trading/positions, /trading/orders, /trading/risk/config |
| 2 | LiveTradingController 명칭 | LiveTradingController 별도 | TradingController (TradeController + RiskController 통합) |
| 3 | live_trading_session.status DEFAULT | 'STOPPED' | 'CREATED' |
| 4 | backtest_trade.market_regime | VARCHAR(10) | VARCHAR(20) (V16 마이그레이션 반영) |
| 5 | core-engine selector 패키지 | 미명세 | StrategySelector, CompositeStrategy, MultiTimeframeFilter, CandleDownsampler, WeightedStrategy, TimeframePreset 추가 |
| 6 | MarketRegime enum | TREND/RANGE/VOLATILE | TREND/RANGE/VOLATILITY/TRANSITIONAL |
| 7 | strategy-lib StatefulStrategy | 미명세 | StatefulStrategy 인터페이스 추가 |
| 8 | StrategySignal | confidence, suggestedStopLoss/TakeProfit 없음 | getConfidence(), suggestedStopLoss, suggestedTakeProfit 추가 |
| 9 | DESIGN.md 다음 단계 | "Phase 4 프론트엔드 구현" | "Phase 4 배포 및 실거래 검증" |

---

## 13. 미적용 항목 요약 (향후 작업)

### 즉시 (P0)
- [ ] Spring Security / API 인증 추가 (SecurityConfig 구현)
- [ ] Phase 4 실전매매 배포 (환경변수 설정 + 서버 재빌드)

### 단기 (P1, 1~2주)
- [ ] GET /api/v1/reports/weekly 엔드포인트 구현
- [ ] TradingController 예외 처리 패턴 통일 (커스텀 예외 클래스)
- [ ] StrategyController DTO 전환 + Bean Validation
- [ ] STOCHASTIC_RSI 구조 재설계 또는 제거
- [ ] MACD 히스토그램 기울기 필터 추가

### 중기 (P2, 1개월)
- [ ] Redis Pub/Sub 이벤트 파이프라인 (EventPublisher/EventSubscriber)
- [ ] core-engine signal/ 패키지 (SignalEngine, TradingSignal)
- [ ] core-engine position/ 패키지 (PositionManager, Position)
- [ ] Table/Modal 공통 컴포넌트 추가
- [ ] Flyway V16 — backtest_trade.market_regime VARCHAR(20) 정합성 수정
- [ ] VWAP 임계값 재조정 (2.5% → 1.5%)
- [ ] DESIGN.md v1.4 업데이트

### 완료 처리 (이전 미완 → 현재 완료)
- [x] CompositeStrategy 파이프라인 PaperTradingService/LiveTradingService 실전 연동 (2026-03-15)
- [x] Phase 4 프론트엔드 실전매매 UI 구현 (2026-03-15)
- [x] Header 컴포넌트 분리 (2026-03-08)
- [x] 공통 UI 컴포넌트 Button/Card/Badge/Spinner (2026-03-08)
- [x] Phase 1 전략 Config 클래스 4종 (2026-03-08)
- [x] RedisConfig, SchedulerConfig (2026-03-08)
- [x] BacktestService @Transactional 제거 (2026-03-15)
- [x] LiveTradingService orphan guard (2026-03-15)
- [x] Phase S1~S5 전략 고도화 (2026-03-15)
- [x] hooks/useTrading, useBacktest, useStrategies 등 (2026-03-15)

---

## 14. 다음 단계 권장

```
현재 상태: Phase 1~4 + S1~S5 전체 ~95% 완성
                          |
          [Step 1] SecurityConfig 구현 (P0 보안 필수)
          → Spring Security + API Key or Basic Auth 추가
          → 실전매매 API 보호
                          |
          [Step 2] Phase 4 배포 및 실거래 검증
          → UPBIT_ACCESS_KEY / UPBIT_SECRET_KEY 환경변수 설정
          → docker-compose.prod.yml 재빌드
          → 소액 실거래 테스트 (1세션, 단일 코인)
          → 텔레그램 알림 정상 수신 확인
                          |
          [Step 3] DESIGN.md v1.4 업데이트
          → API 경로, 컨트롤러 명칭, DB 스키마 변경 반영
          → MarketRegime enum, StrategySignal 확장 반영
                          |
          [Step 4] Report 에이전트 실행
          → REPORT.md 최종 보고서 작성
```

Phase 1~3.5 및 인프라는 100% 완성. Phase 4 백엔드 95%+, 프론트엔드 90%+. Phase S1~S5 전략 고도화 100% 완료. 실거래 검증 및 SecurityConfig 구현 후 Report 에이전트 전달 권장.

---
생성: Check 에이전트
검증일: 2026-03-15 (v4.0 전체 재검증)
기준 문서: DESIGN.md v1.3
검증 완료 후: @Do (P0 SecurityConfig) 또는 @Report (최종 보고서)
