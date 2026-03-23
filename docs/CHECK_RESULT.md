# CryptoAutoTrader - 검증 결과 보고서 v6.0

## 문서 정보
- 검증일: 2026-03-24
- 설계서 버전: DESIGN.md v1.4
- 이전 버전: CHECK_RESULT.md v5.0 (2026-03-23)
- 검증 범위: PROGRESS.md 갱신 + DEV_STATUS_REVIEW_v3.3 반영 항목 재검증 (코드 직접 확인)
- 검증자: Check 에이전트 (자동 검증)

---

## 1. 검증 요약

### 1.1 전체 현황

| 구분 | 설계 항목 수 | 구현 완료 | 부분 구현 | 미구현 | 완료율 |
|------|------------|---------|---------|-------|-------|
| API 엔드포인트 | ~45개 | 43개 | 0개 | 2개 | 96% |
| DB / Flyway 마이그레이션 | V1~V26 | V1~V26 (26개) | 0 | 0 | 100% |
| Entity SL/TP 필드 | 4개 컬럼 | 4개 | 0 | 0 | 100% |
| 전략 (단일 10종) | 10종 | 10종 | 0 | 0 | 100% |
| 전략 (복합 4종) | 4종 | 3종 | 0 | 1종 | 75% |
| 유틸 클래스 | 2개 | 2개 | 0 | 0 | 100% |
| 프론트엔드 페이지 | 17개 | 16개 | 0 | 1개 | 94% |
| 인프라 (CI/CD + 모니터링 포함) | 3개 | 3개 | 0 | 0 | 100% |
| v3.2 신규 변경 10종 | 10종 | 9종 | 0 | 1종 | 90% |
| v3.3 신규 항목 (CI/CD·Prometheus·AsyncConfig) | 3종 | 3종 | 0 | 0 | 100% |

### 1.2 상태 범례

| 아이콘 | 의미 |
|-------|------|
| ✅ | 구현 완료 (설계 일치) |
| ⚠️ | 부분 구현 또는 설계와 경미한 차이 |
| ❌ | 미구현 또는 설계 불일치 (Action Required) |
| 📝 | 설계서에 명시되지 않은 추가 구현 |

---

## 2. API 검증 상세

### 2.1 Controller별 엔드포인트 비교 매트릭스

#### BacktestController (`/api/v1/backtest`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| POST | `/run` | ✓ (fillSimulation 포함) | ✅ |
| POST | `/walk-forward` | ✓ | ✅ |
| GET | `/{id}` | ✓ | ✅ |
| GET | `/{id}/trades` | ✓ (페이징) | ✅ |
| GET | `/compare` | ✓ | ✅ |
| GET | `/list` | ✓ | ✅ |
| DELETE | `/{id}` | ✓ (204 No Content) | 📝 설계 미명시, 구현됨 |
| POST | `/bulk-run` | ✓ | 📝 설계 미명시, 구현됨 |
| POST | `/multi-strategy` | ✓ | 📝 설계 미명시, 구현됨 |
| DELETE | `/bulk` | ✓ | 📝 설계 미명시, 구현됨 |

#### TradingController (`/api/v1/trading`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/status` | ✓ (`/status`) | ✅ |
| POST | `/sessions` | ✓ (세션 생성) | ✅ |
| POST | `/sessions/multi` | ✓ | ✅ |
| GET | `/sessions` | ✓ | ✅ |
| GET | `/sessions/{id}` | ✓ | ✅ |
| POST | `/sessions/{id}/start` | ✓ | ✅ |
| POST | `/sessions/{id}/stop` | ✓ | ✅ |
| POST | `/sessions/{id}/emergency-stop` | ✓ | ✅ |
| DELETE | `/sessions/{id}` | ✓ | ✅ |
| GET | `/sessions/{id}/positions` | ✓ | ✅ |
| GET | `/sessions/{id}/chart` | ✓ | ✅ |
| GET | `/sessions/{id}/orders` | ✓ | ✅ |
| POST | `/emergency-stop` | ✓ (전체 비상정지) | ✅ |
| GET | `/positions` | ✓ | ✅ |
| GET | `/positions/{id}` | ✓ | ✅ |
| GET | `/orders` | ✓ | ✅ |
| GET | `/orders/{id}` | ✓ | ✅ |
| DELETE | `/orders/{id}` | ✓ (주문 취소) | ✅ |
| GET | `/risk/config` | ✓ | ✅ |
| PUT | `/risk/config` | ✓ | ✅ |
| GET | `/health/exchange` | ✓ | ✅ |
| GET | `/performance` | ✓ | ✅ |
| POST | `/telegram/test` | ✓ | ✅ |
| POST | `/start` (설계) | 미구현 — 세션 기반으로 대체됨 | ⚠️ |
| POST | `/stop` (설계) | 미구현 — `/emergency-stop`으로 대체됨 | ⚠️ |

> 설계서 4.4에는 `/api/v1/trading/start`, `/api/v1/trading/stop`이 단순 명령형으로 명시되어 있으나, 실제 구현은 세션 기반 멀티 모델로 전환됨. 기능적 동등성은 충족.

#### PaperTradingController (`/api/v1/paper-trading`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/sessions` | ✓ | ✅ |
| POST | `/sessions` | ✓ | ✅ |
| POST | `/sessions/multi` | ✓ | 📝 설계 미명시, 구현됨 |
| GET | `/sessions/{id}` | ✓ | ✅ |
| GET | `/sessions/{id}/positions` | ✓ | ✅ |
| GET | `/sessions/{id}/orders` | ✓ (페이징) | ✅ |
| POST | `/sessions/{id}/stop` | ✓ | ✅ |
| GET | `/sessions/{id}/chart` | ✓ | ✅ |
| POST | `/sessions/stop-all` | ✓ | 📝 설계 미명시, 구현됨 |
| DELETE | `/history/{id}` | ✓ | 📝 설계 미명시, 구현됨 |
| DELETE | `/history/bulk` | ✓ | 📝 설계 미명시, 구현됨 |
| GET | `/performance` | ✓ | ✅ |

#### StrategyController (`/api/v1/strategies`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/` | ✓ (전략 목록 + 상태) | ✅ |
| GET | `/{name}` | ✓ | ✅ |
| POST | `/` | ✓ (전략 설정 생성) | ✅ |
| PUT | `/{id}` | ✓ | ✅ |
| PATCH | `/{id}/toggle` | ✓ | ✅ |
| PATCH | `/{name}/active` | ✓ (타입 활성/비활성) | 📝 추가 구현 |
| PATCH | `/{id}/toggle-override` | ✓ (수동 오버라이드 해제) | 📝 추가 구현 |

#### DataController (`/api/v1/data`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| POST | `/collect` | ✓ | ✅ |
| GET | `/status` | ✓ | ✅ |
| GET | `/coins` | ✓ | ✅ |
| GET | `/candles` | ✓ | ✅ |
| GET | `/summary` | ✓ | ✅ |
| DELETE | `/candles` | ✓ | 📝 추가 구현 |

#### LogController (`/api/v1/logs`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/strategy` | ✓ (sessionType/sessionId 필터 포함) | ✅ |

#### SettingsController (`/api/v1/settings`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/telegram/logs` | ✓ | ✅ |
| POST | `/telegram/test` | ✓ | ✅ |
| GET | `/upbit/status` | ✓ | ✅ |
| GET | `/upbit/order-chance` | ✓ | 📝 추가 구현 |
| POST | `/upbit/test-order` | ✓ | 📝 추가 구현 |
| GET | `/upbit/exchange-orders` | ✓ | 📝 추가 구현 |
| GET | `/upbit/ticker` | ✓ | 📝 추가 구현 |
| GET | `/server-logs` | ✓ | 📝 추가 구현 |
| GET | `/db/stats` | ✓ | 📝 추가 구현 |
| POST | `/db/reset` | ✓ | 📝 추가 구현 |

#### SystemController (`/api/v1`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/health` | ✓ | ✅ |
| GET | `/strategies/types` | ✓ | ✅ |

#### AccountController (`/api/v1/account`)

| Method | 설계 엔드포인트 | 실제 구현 | 상태 |
|--------|--------------|---------|-----|
| GET | `/summary` | ✓ | ✅ |

#### PerformanceController

| 항목 | 설계 | 실제 | 상태 |
|------|------|------|-----|
| PerformanceController 클래스 | v3.2 신규 명시 | **미존재** | ❌ |

> v3.2 변경 항목 11에 "PerformanceController 신규"가 명시되어 있으나, 실제 파일이 없음.
> 단, `/api/v1/trading/performance`는 TradingController에, `/api/v1/paper-trading/performance`는 PaperTradingController에 각각 구현되어 있음. 기능은 분산 구현됨.

---

## 3. DB/Entity 검증

### 3.1 Flyway 마이그레이션 현황

| 버전 | 파일명 | 상태 |
|------|--------|-----|
| V1 | `V1__create_candle_data.sql` | ✅ |
| V2 | `V2__create_backtest_tables.sql` | ✅ |
| V3 | `V3__create_strategy_config.sql` | ✅ |
| V4 | `V4__create_position_order.sql` | ✅ |
| V5 | `V5__create_risk_config.sql` | ✅ |
| V6 | `V6__create_log_tables.sql` | ✅ |
| V7 | `V7__create_strategy_signal.sql` | ✅ |
| V8 | `V8__create_paper_trading_schema.sql` | ✅ |
| V9 | `V9__enhance_paper_trading.sql` | ✅ |
| V10 | `V10__paper_trading_multi_session.sql` | ✅ |
| V11 | `V11__add_manual_override_to_strategy_config.sql` | ✅ |
| V12 | `V12__create_live_trading_session.sql` | ✅ |
| V13 | `V13__create_market_data_cache.sql` | ✅ |
| V14 | `V14__create_strategy_type_enabled.sql` | ✅ |
| V15 | `V15__add_telegram_to_paper_trading.sql` | ✅ |
| V16 | `V16__create_telegram_notification_log.sql` | ✅ |
| V17 | `V17__add_session_type_to_strategy_log.sql` | ✅ |
| V18 | `V18__add_pnl_fee_to_virtual_balance.sql` | ✅ |
| V19 | `V19__add_invest_ratio_to_live_trading_session.sql` | ✅ |
| V20 | `V20__add_position_fee_to_paper_position.sql` | ✅ |
| V21 | `V21__add_version_to_virtual_balance.sql` | ✅ |
| V22 | `V22__add_position_fee_to_position.sql` | ✅ |
| V23 | `V23__add_closing_at_to_position.sql` | ✅ |
| V24 | `V24__add_circuit_breaker.sql` | ✅ |
| V25 | `V25__fix_market_regime_varchar_length.sql` | ✅ |
| V26 | `V26__add_sl_tp_to_positions.sql` | ✅ |

**결론: V1~V26 전체 26개 파일 모두 존재. 100% 완료.**

### 3.2 V26 SL/TP 컬럼 검증

| 테이블 | 컬럼 | Entity 필드 | 상태 |
|--------|------|------------|-----|
| `position` | `stop_loss_price` | `PositionEntity.stopLossPrice` | ✅ |
| `position` | `take_profit_price` | `PositionEntity.takeProfitPrice` | ✅ |
| `paper_trading.position` | `stop_loss_price` | `PaperPositionEntity.stopLossPrice` | ✅ |
| `paper_trading.position` | `take_profit_price` | `PaperPositionEntity.takeProfitPrice` | ✅ |

V26 SQL: `ALTER TABLE position ADD COLUMN IF NOT EXISTS stop_loss_price NUMERIC(20, 8)` 등 4개 컬럼 모두 추가됨.

### 3.3 GlobalRiskManager 구현 여부

| 항목 | 설계 의도 | 실제 | 상태 |
|------|---------|------|-----|
| GlobalRiskManager SL/TP 절대가 저장 + 매 틱 체크 | SL/TP 퍼센트 비교 → 절대가 비교로 전환 | `LiveTradingService`, `PaperTradingService`에 통합 구현 | ✅ |

> CHANGELOG.md 확인: "GlobalRiskManager 분리"는 독립 클래스 생성이 아닌
> SL/TP 로직을 각 서비스 내에 통합한 것이 설계 의도이며 완료됨.
> `executeBuy()` 진입 시 절대가 저장, `runSessionStrategy()` 매 틱 O(1) 비교 모두 구현됨.

---

## 4. 전략 검증 (14종)

### 4.1 단일 전략 10종

| 전략명 | 클래스 | 패키지 경로 | 상태 |
|--------|--------|-----------|-----|
| VWAP | `VwapStrategy.java` | `strategy/vwap/` | ✅ |
| EMA_CROSS | `EmaCrossStrategy.java` | `strategy/ema/` | ✅ |
| BOLLINGER | `BollingerStrategy.java` | `strategy/bollinger/` | ✅ |
| GRID | `GridStrategy.java` | `strategy/grid/` | ✅ |
| RSI | `RsiStrategy.java` | `strategy/rsi/` | ✅ |
| MACD | `MacdStrategy.java` | `strategy/macd/` | ✅ |
| SUPERTREND | `SupertrendStrategy.java` | `strategy/supertrend/` | ✅ |
| ATR_BREAKOUT | `AtrBreakoutStrategy.java` | `strategy/atrbreakout/` | ✅ |
| ORDERBOOK_IMBALANCE | `OrderbookImbalanceStrategy.java` | `strategy/orderbook/` | ✅ |
| STOCHASTIC_RSI | `StochasticRsiStrategy.java` | `strategy/stochasticrsi/` | ✅ |

### 4.2 복합 전략 4종

| 전략명 | 설계 위치 | 실제 위치 | 상태 |
|--------|---------|---------|-----|
| `CompositeStrategy` | `strategy-lib/composite/` | `core-engine/com.cryptoautotrader.core.selector/` | ✅ (위치 상이, 기능 구현) |
| `COMPOSITE_BTC` | CompositePresetRegistrar | `web-api/config/CompositePresetRegistrar.java` | ✅ |
| `COMPOSITE_ETH` | CompositePresetRegistrar | `web-api/config/CompositePresetRegistrar.java` | ✅ |
| `MACD_STOCH_BB` | `strategy-lib/macdstochbb/` | `strategy/macdstochbb/MacdStochBbStrategy.java` | ✅ |

> 설계서는 `CompositeStrategy`를 `strategy-lib/composite/` 패키지에 위치시키도록 했으나,
> 실제 구현은 `core-engine/com.cryptoautotrader.core.selector.CompositeStrategy`에 있음.
> `WeightedStrategy`, `StrategySelector`도 동일 위치. 기능 동등성 있음.
> `CompositePresetRegistrar`는 설계서의 `strategy-lib/composite/`가 아닌 `web-api/config/`에 위치.

추가 미구현:
- 설계서에 명시된 `COMPOSITE` (기본형 가중 투표 프리셋) — `COMPOSITE_BTC`, `COMPOSITE_ETH`는 있으나 `COMPOSITE` 범용 타입이 StrategyRegistry에 없음 (types.ts에는 `'COMPOSITE'` 타입이 존재하지만 실제 등록 안 됨)

### 4.3 공통 유틸 클래스

| 클래스 | 경로 | 상태 |
|--------|------|-----|
| `StrategyParamUtils` | `strategy-lib/.../StrategyParamUtils.java` | ✅ |
| `IndicatorUtils` | `strategy-lib/.../IndicatorUtils.java` | ✅ |

---

## 5. 프론트엔드 검증

### 5.1 페이지 라우트

| 설계 경로 | 실제 경로 | 상태 |
|---------|---------|-----|
| `/` (대시보드) | `app/page.tsx` | ✅ |
| `/backtest` | `app/backtest/page.tsx` | ✅ |
| `/backtest/[id]` | `app/backtest/[id]/page.tsx` | ✅ |
| `/backtest/compare` | `app/backtest/compare/page.tsx` | ✅ |
| `/strategies` | `app/strategies/page.tsx` | ✅ |
| `/paper-trading` | `app/paper-trading/page.tsx` | ✅ |
| `/paper-trading/[sessionId]` | `app/paper-trading/[sessionId]/page.tsx` | ✅ |
| `/paper-trading/history` | `app/paper-trading/history/page.tsx` | ✅ |
| `/data` | `app/data/` | ❌ 미확인 (디렉토리 없음) |
| `/trading` | `app/trading/page.tsx` | ✅ |
| `/trading/[sessionId]` | `app/trading/[sessionId]/page.tsx` | ✅ |
| `/trading/history` | `app/trading/history/page.tsx` | ✅ |
| `/trading/risk` | `app/trading/risk/` | ✅ |
| `/logs` | `app/logs/page.tsx` | ✅ |
| `/settings` | `app/settings/page.tsx` | ✅ |
| `/account` | `app/account/page.tsx` | ✅ |
| `/performance` (Phase 5) | `app/performance/page.tsx` | ✅ |
| `/login` | `app/login/` | 📝 설계 미명시, 구현됨 |
| `/api` | `app/api/` (Next.js API Route) | 📝 설계 미명시, 구현됨 |

> `/data` 라우트는 `app/` 하위에서 확인되지 않음. 별도 확인 필요.

### 5.2 types.ts 전략 타입 반영

```typescript
export type StrategyType = 'VWAP' | 'EMA_CROSS' | 'BOLLINGER' | 'GRID'
    | 'RSI' | 'MACD' | 'SUPERTREND' | 'ATR_BREAKOUT' | 'ORDERBOOK_IMBALANCE' | 'STOCHASTIC_RSI'
    | 'COMPOSITE' | 'COMPOSITE_BTC' | 'COMPOSITE_ETH' | 'MACD_STOCH_BB';
```

| 타입 | types.ts 반영 | 백엔드 등록 | 상태 |
|------|-------------|----------|-----|
| VWAP | ✓ | ✓ | ✅ |
| EMA_CROSS | ✓ | ✓ | ✅ |
| BOLLINGER | ✓ | ✓ | ✅ |
| GRID | ✓ | ✓ | ✅ |
| RSI | ✓ | ✓ | ✅ |
| MACD | ✓ | ✓ | ✅ |
| SUPERTREND | ✓ | ✓ | ✅ |
| ATR_BREAKOUT | ✓ | ✓ | ✅ |
| ORDERBOOK_IMBALANCE | ✓ | ✓ | ✅ |
| STOCHASTIC_RSI | ✓ | ✓ | ✅ |
| COMPOSITE | ✓ | ❌ 미등록 | ⚠️ |
| COMPOSITE_BTC | ✓ | ✓ (CompositePresetRegistrar) | ✅ |
| COMPOSITE_ETH | ✓ | ✓ (CompositePresetRegistrar) | ✅ |
| MACD_STOCH_BB | ✓ | ✓ | ✅ |

> `COMPOSITE` 범용 타입이 types.ts에는 선언되어 있으나, StrategyController의 `isCompositeStrategy()` switch문에 없고 StrategyRegistry에도 등록되지 않음. 프론트엔드에서 선택 가능한 타입으로 노출될 경우 오류 발생 가능.

### 5.3 Phase 5 손익 대시보드

| 항목 | 상태 |
|------|-----|
| `/performance` 페이지 존재 | ✅ |
| `PerformanceSummary` 타입 정의 (types.ts) | ✅ |
| `SessionPerformance` 타입 정의 (types.ts) | ✅ |
| `ExchangeHealthStatus: 'UP' | 'DEGRADED' | 'DOWN'` (types.ts) | ✅ |
| 백엔드 `/api/v1/trading/performance` | ✅ (TradingController) |
| 백엔드 `/api/v1/paper-trading/performance` | ✅ (PaperTradingController) |

---

## 6. 인프라 검증

### 6.1 SecurityConfig

| 항목 | 설계 | 실제 | 상태 |
|------|------|------|-----|
| `SecurityConfig.java` | `web-api/config/` | `web-api/config/SecurityConfig.java` | ✅ |
| `@Configuration @EnableWebSecurity` | 명시 | ✓ | ✅ |
| `ApiTokenAuthFilter` | 명시 | ✓ (`ApiTokenAuthFilter.java`) | ✅ |

> 이전 v4.0에서 P0 미구현으로 표시되었던 SecurityConfig가 구현 완료됨.

### 6.2 Docker Compose Healthcheck

| 서비스 | 설계 | 실제 (docker-compose.prod.yml) | 상태 |
|--------|------|------|-----|
| PostgreSQL | healthcheck 권장 | `pg_isready -U trader -d crypto_auto_trader` | ✅ |
| Redis | healthcheck 권장 | `redis-cli ping` | ✅ |

### 6.3 설정 파일 목록

| 파일 | 상태 |
|------|-----|
| `RedisConfig.java` | ✅ |
| `WebConfig.java` | ✅ |
| `SchedulerConfig.java` | ✅ |
| `AsyncConfig.java` | ✅ (graceful shutdown: orderExecutor=30s, marketDataExecutor=10s, taskExecutor=15s) |
| `SecurityConfig.java` | ✅ |
| `SwaggerConfig.java` | 📝 설계 미명시, 추가 구현 |
| `EngineConfig.java` | 📝 설계 미명시, 추가 구현 |

> `telegramExecutor`는 `waitForTasksToCompleteOnShutdown` 미설정 — 의도적 정책 (큐 포화 시 메시지 드롭 허용, 텔레그램 전송은 비핵심 작업).

### 6.4 CI/CD 및 모니터링 인프라

| 항목 | 경로 | 상태 |
|------|------|-----|
| CI/CD GitHub Actions | `.github/workflows/ci.yml` | ✅ 존재 확인 (backend Gradle+TimescaleDB / frontend lint+build / Docker 이미지 빌드) |
| Prometheus 설정 | `monitoring/prometheus.yml` | ✅ 존재 확인 |
| Grafana 프로비저닝 | `monitoring/grafana/provisioning/datasources/prometheus.yml` | ✅ 존재 확인 |

---

## 7. 누락 항목 상세 (Action Required)

### 7.1 PerformanceController 미분리

- **설계**: v3.2 변경 항목 11 — "PerformanceController 신규"
- **실제**: 독립 PerformanceController 클래스 미존재. `/api/v1/trading/performance`는 TradingController 내에, `/api/v1/paper-trading/performance`는 PaperTradingController 내에 각각 구현됨.
- **영향**: 기능 동작에는 문제 없음. 단, 설계 의도인 `/performance` 통합 단일 컨트롤러가 없으므로 향후 성과 분석 API 확장 시 분산 관리 문제 발생 가능.
- **권고**: 설계 의도가 독립 컨트롤러라면 `PerformanceController.java` 신규 생성. 아니라면 설계서 v1.5에서 해당 항목을 현재 구현 방식으로 업데이트할 것.

### 7.3 COMPOSITE 범용 타입 불일치

- **설계**: types.ts에 `'COMPOSITE'` 타입 선언됨.
- **실제**: StrategyRegistry에 `COMPOSITE` 키로 등록되지 않음. StrategyController의 `isCompositeStrategy()` switch문에도 없음.
- **영향**: 프론트엔드에서 `COMPOSITE`를 전략 타입으로 선택하면 백엔드 404/오류 발생.
- **권고**: types.ts에서 `'COMPOSITE'` 제거하거나, 백엔드에서 `COMPOSITE` 타입을 CompositePresetRegistrar에 등록할 것.

---

## 8. 불일치 항목 상세

### 8.1 CompositeStrategy 패키지 위치 불일치

| 항목 | 설계 | 실제 |
|------|------|------|
| `CompositeStrategy.java` | `strategy-lib/.../composite/` | `core-engine/.../selector/` |
| `WeightedStrategy.java` | `strategy-lib/.../composite/` | `core-engine/.../selector/` |
| `StrategySelector.java` | `strategy-lib/.../composite/` | `core-engine/.../selector/` |
| `CompositePresetRegistrar.java` | `strategy-lib/.../composite/` | `web-api/.../config/` |
| `MacdStochBbStrategy.java` | `strategy-lib/.../composite/` | `strategy-lib/.../macdstochbb/` |

> 기능 동작은 동일하나 설계서의 패키지 구조와 다름. 설계서 업데이트 권고.

### 8.2 설계서 Phase 4 API 명세 불일치

| 설계 엔드포인트 | 실제 | 비고 |
|--------------|------|-----|
| `POST /api/v1/trading/start` | 미존재 | 세션 기반 `POST /sessions`로 대체 |
| `POST /api/v1/trading/stop` | 미존재 | `POST /emergency-stop`으로 대체 |
| `POST /api/v1/trading/stop/{coinPair}` | 미존재 | 코인별 정지 미구현 |
| `GET /api/v1/positions` | `GET /api/v1/trading/positions` | 접두사 경로 상이 |
| `GET /api/v1/orders` | `GET /api/v1/trading/orders` | 접두사 경로 상이 |
| `GET /api/v1/risk/config` | `GET /api/v1/trading/risk/config` | 접두사 경로 상이 |
| `GET /api/v1/health/exchange` | `GET /api/v1/trading/health/exchange` | 접두사 경로 상이 |

> 설계서 4.4절 운영 제어 API의 경로 기본값(`/api/v1/positions` 등)과 실제 구현 경로(`/api/v1/trading/positions`)가 다름. 프론트엔드가 실제 구현 경로에 맞춰 구현되어 있다면 기능 문제 없음.

### 8.3 알림 API 미구현

| 설계 엔드포인트 | 실제 | 상태 |
|--------------|------|-----|
| `GET /api/v1/reports/daily` | 미존재 | ❌ |
| `GET /api/v1/reports/weekly` | 미존재 | ❌ |
| `POST /api/v1/reports/test-telegram` | `POST /api/v1/settings/telegram/test`, `POST /api/v1/trading/telegram/test` 로 분산 구현 | ⚠️ |

---

## 9. v3.2 신규 항목 검증 결과

| # | v3.2 변경 항목 | 구현 여부 | 검증 결과 |
|---|--------------|---------|---------|
| 1 | GlobalRiskManager 분리 — SL/TP 절대가 저장 (V26) | 완료 | ✅ V26 + Entity 필드 + 서비스 내 매 틱 절대가 체크 모두 완료 |
| 2 | COMPOSITE_BTC EMA20/50 방향 필터 | 완료 | ✅ `CompositeStrategy.emaFilterEnabled=true` 로 COMPOSITE_BTC 등록, EMA20/50 필터 로직 구현됨 |
| 3 | COMPOSITE TRANSITIONAL 신규 진입 금지 | 완료 | ✅ `PaperTradingService`, `LiveTradingService` 양쪽에 `MarketRegime.TRANSITIONAL && BUY → HOLD` 로직 구현됨 |
| 4 | MACD 히스토그램 기울기 + 제로라인 필터 | 완료 | ✅ `MacdStrategy`에 제로라인 필터 + 히스토그램 확대 필터(기울기 방향) 구현됨 |
| 5 | StochRSI 임계값 완화 + 2캔들 연속 크로스 + 거래량 확인 | 완료 | ✅ oversoldLevel=20/overboughtLevel=80 기본값, `prevK > prevD` 조건으로 2캔들 연속, `volumeOk` 거래량 확인 구현됨 |
| 6 | VWAP 임계값 1.5%, ADX 상한 35, anchorSession | 완료 | ✅ `thresholdPct` 기본값 1.5, `adxMaxThreshold` 기본값 35.0, `anchorSession=true` 기본값 구현됨 |
| 7 | Phase 5 손익 대시보드 (`/performance`) | 완료 | ✅ `/performance` 프론트엔드 페이지 존재, 백엔드 API 양쪽 Controller에 구현됨 |
| 8 | 전략 14종 (COMPOSITE_BTC, COMPOSITE_ETH, MACD_STOCH_BB 추가) | 완료 | ✅ 14종 모두 구현됨 (`COMPOSITE` 범용만 백엔드 미등록) |
| 9 | StrategyParamUtils, IndicatorUtils 공통 유틸 | 완료 | ✅ 두 클래스 모두 `strategy-lib`에 존재 |
| 10 | ExchangeHealthMonitor DEGRADED 중간 상태 | 완료 | ✅ `DEGRADED_THRESHOLD_MS=3000`, UP/DEGRADED/DOWN 3단계 구현됨 |
| 11 | PerformanceController 신규 | 미완료 | ❌ 독립 클래스 미존재 (TradingController, PaperTradingController에 분산 구현) |

---

## 10. v3.3 신규 항목 검증 결과 (2026-03-24)

> DEV_STATUS_REVIEW_v3.3 및 PROGRESS.md 갱신 내용 기반으로 **코드를 직접 열어** 검증한 결과.

### 10.1 MACD 코드 개선 직접 확인

| 개선 항목 | 검증 위치 | 코드 근거 | 상태 |
|---------|---------|---------|-----|
| 히스토그램 기울기 필터 | `MacdStrategy.java:84-87` | `currentHistogram.compareTo(prevHistogram) <= 0 → HOLD` | ✅ |
| 제로라인 필터 (BUY) | `MacdStrategy.java:79-82` | `current.macdLine <= 0 → HOLD` (골든크로스 억제) | ✅ |
| 제로라인 필터 (SELL) | `MacdStrategy.java:99-102` | `current.macdLine >= 0 → HOLD` (데드크로스 억제) | ✅ |

### 10.2 StochRSI 코드 개선 직접 확인

| 개선 항목 | 검증 위치 | 코드 근거 | 상태 |
|---------|---------|---------|-----|
| oversoldLevel 기본값 20 | `StochasticRsiStrategy.java:60` | `oversoldLevel 20.0` | ✅ |
| overboughtLevel 기본값 80 | `StochasticRsiStrategy.java:61` | `overboughtLevel 80.0` | ✅ |
| 2캔들 연속 %K > %D | `StochasticRsiStrategy.java:121-125` | `prevK > prevD && currentK > currentD` 동시 조건 | ✅ |
| 거래량 확인 조건 | `StochasticRsiStrategy.java:85-90` | `currentVolume >= avgVolume(20)` 필터 | ✅ |

### 10.3 COMPOSITE_ETH 백테스트/실시간 가중치 분리 직접 확인

| 모드 | 가중치 | 코드 위치 | 상태 |
|-----|--------|---------|-----|
| 실시간 | ATR(0.5) + OB(0.3) + EMA(0.2) | `CompositePresetRegistrar.java:41-45` | ✅ |
| 백테스트 | ATR(0.7) + OB(0.1) + EMA(0.2) | `BacktestService.java:compositeEthBt()` | ✅ |

### 10.4 CI/CD + Prometheus/Grafana + AsyncConfig 인프라 직접 확인

| 항목 | 상태 |
|------|-----|
| `.github/workflows/ci.yml` 존재 | ✅ |
| `monitoring/prometheus.yml` 존재 | ✅ |
| `monitoring/grafana/provisioning/datasources/prometheus.yml` 존재 | ✅ |
| `AsyncConfig.orderExecutor` graceful 30s | ✅ |
| `AsyncConfig.marketDataExecutor` graceful 10s | ✅ |
| `AsyncConfig.taskExecutor` graceful 15s | ✅ |
| `AsyncConfig.telegramExecutor` graceful 미설정 (드롭 정책) | ✅ (의도적) |

---

## 11. 다음 단계

### 우선순위 High (기능 영향)

1. **`COMPOSITE` 범용 타입 정리**
   - `types.ts`에서 `'COMPOSITE'` 제거하거나 백엔드 StrategyRegistry에 등록
   - 영향: 프론트엔드에서 `COMPOSITE` 선택 시 백엔드 오류 발생 가능


### 우선순위 Medium (설계 일관성)

3. **`PerformanceController` 분리 검토**
   - 현재 분산 구현은 기능상 문제없으나 설계서 v1.4 의도와 다름
   - 독립 컨트롤러로 분리하거나 설계서 업데이트

4. **알림 API (`/api/v1/reports/*`) 미구현**
   - `GET /api/v1/reports/daily`, `GET /api/v1/reports/weekly` 미구현
   - 텔레그램 서비스는 있으나 REST API 노출 없음

5. **설계서 경로 불일치 업데이트**
   - DESIGN.md 4.4절의 `/api/v1/positions`, `/api/v1/orders`, `/api/v1/risk/*`, `/api/v1/health/*` → 실제 경로로 업데이트

### 우선순위 Low (문서 정합성)

6. **패키지 위치 불일치 문서 업데이트**
   - CompositeStrategy, WeightedStrategy, StrategySelector의 실제 패키지(`core-engine/selector/`)를 DESIGN.md 2.3절에 반영
   - CompositePresetRegistrar 위치(`web-api/config/`)도 반영

7. **`/data` 프론트엔드 페이지 확인**
   - `app/` 디렉토리에서 `data/` 라우트 확인 필요

---

*v6.0 갱신: Check 에이전트 | 검증일: 2026-03-24 | 기준 설계서: DESIGN.md v1.4*
*v5.0 작성: 2026-03-23 | v6.0 재검증: MACD/StochRSI 코드 직접 확인, CI/CD·Prometheus/Grafana·AsyncConfig 인프라 추가 검증*
