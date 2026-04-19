# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-04-20 (Tier 4 §18 완료 — 보안 점검 스크립트 추가. Tier 1~4 전 항목 완료.)

---

## 🔧 Self-Audit 개선 진행 현황 ([docs/20260415_analy.md](20260415_analy.md))

> `/clear` 후에도 이어서 작업할 수 있게 이 섹션을 먼저 읽을 것. 상세 근거는 `docs/20260415_analy.md`.

### Tier 1 — 결과 신뢰도 직결 (우선)
- ✅ **§1 Sharpe/Sortino/Calmar 연환산** — 일별 equity curve 기반 재계산. `MetricsCalculator` + 테스트 7건. 기존 백테스트 수치 재실행 대기 (사용자 담당).
- ✅ **§2 Walk-Forward 윈도우 겹침** — `step=windowSize` 고정, `ANCHORED` 모드 추가, OOS 거래 병합 집계 지표. 테스트 4건.
- ✅ **§3 백테스트 SL/TP 현실 체결** — gap-open 감지 + SELL 슬리피지 + ambiguous 플래그. [BacktestEngine.java:113](../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java#L113)
- ✅ **§4 Partial Fill 가중평균 진입가** — BUY 이월 시 `entryPrice` 재계산 + `entryFee` 누적. [BacktestEngine.java:76](../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java#L76)
- ✅ **§5 리스크 체크 자본 기준** — RUNNING 세션 `initialCapital` 합 + gross loss. `RiskManagementService` + 테스트 3건.
- ✅ **§6 StrategyLog 4h 적중률 → realizedPnl 전환** — `PositionRepository.aggregateRealizedReturnsByStrategyAndRegime` 추가, `StrategyWeightOptimizer.computeWeightsFromRealized` 가 `sum(realizedPnl)/sum(investedKrw)` 로 점수 산출. 실현 샘플 부족 시 4h 적중률 폴백. DEFAULTS 에 `COMPOSITE_MOMENTUM_ICHIMOKU_V2` 추가. 테스트 4건.

### Tier 2 — 운영 안전성 (Tier 1 후 진행)
- ✅ **§7 LiveTradingService race** — `LiveTradingSessionEntity.@Version` + V43 Flyway 마이그레이션, `SessionBalanceUpdater`(REQUIRES_NEW TransactionTemplate + `ConcurrencyFailureException` 재시도 + jitter backoff) 헬퍼 신설. LiveTradingService 6개 read-modify-write 사이트(executeSessionBuy / 매수취소 KRW 복원 / updateSessionUnrealizedPnl / reconcileOrphanBuy 2곳 / finalizeSellPosition) 리팩터링. 회귀 테스트: SessionBalanceUpdaterTest(동시 10-thread) + LiveTradingReliabilityTest(@Transactional 제거 + @AfterEach 수동 정리).
- ✅ **§8 세션당 1코인 암묵 가정** — 자본 초과 배정 방지 가드(createSession/startSession), 매수 시 cross-session 가용KRW 합산 초과 차단(executeSessionBuy), PortfolioSyncService drift 감지(5% 초과 경고). Repository에 `sumInitialCapitalByStatusIn` / `sumAvailableKrwByStatusIn` 집계 쿼리 추가. 테스트 4건(SessionCapitalGuardTest).
- ✅ **§9 WebSocket 단일 장애점** — ExchangeHealthMonitor에 `wsDisconnectedSince` + `isWsDownLongerThan()` 추가. LiveTradingService에 `pollRestTickerFallback()` (5초 주기, WS>30초 끊김 시 REST ticker→RealtimePriceEvent 대체), `warnStaleSlCheck()` (1분 주기, SL 3분 미점검 세션 Telegram 경고), `recordSlCheck()` 호출 삽입. TelegramNotificationService에 `sendCustomNotification()` 추가. 테스트 3건(WsFallbackTest).
- ✅ **§10 emergencyStopAll 연쇄 충격** — `UpbitApiRateLimiter`(Semaphore 7 permits/sec, 데몬 리필) 신설, `OrderExecutionEngine` submit/cancel 경로에 rate limit 통합, `emergencyStopAll(boolean dryRun)` 손실 내림차순 우선 청산 + dry-run 모드, `/emergency-stop/dry-run` 엔드포인트 추가. 테스트 4건(RateLimiterEmergencyStopTest).

### Tier 3 — 전략/데이터 품질
- ✅ **§11 전략 19종 중 실전 후보 3개** — `StrategyLiveStatusRegistry` 신설(ENABLED 4·BLOCKED 3·EXPERIMENTAL 13·DEPRECATED 1). `LiveTradingService.BLOCKED_LIVE_STRATEGIES` 하드코딩 흡수. `StrategyController`에 `liveReadiness` 노출 + `GET /api/v1/strategies/live-matrix` 추가. 테스트 6건.
- ✅ **§12 파라미터 튜닝 look-ahead** — `WalkForwardTestRunner.run(…, holdOutCutoff)` 오버로드 추가. cutoff 이후 캔들은 OOS 전용, IS 포함 시 `IllegalArgumentException`. 홀드아웃 윈도우를 별도 WF 결과에 포함. 테스트 3건.
- ✅ **§13 데이터 스냅샷 편향** — `PerformanceReport`에 `monthlyReturnStdDev`·`monthlyReturnSkewness`·`topMonthConcentrationPct` 추가. `MetricsCalculator`에 3개 헬퍼 메서드 구현. 집중도 80% 이상 시 스냅샷 편향 의심 가능. 테스트 5건.
- ✅ **§14 실전/백테스트 drift 트래커** — `ExecutionDriftLogEntity` + `ExecutionDriftLogRepository` + `ExecutionDriftTracker`(slippage 계산·7일 평균·Telegram 경고·@Scheduled). `LiveTradingService.finalizeSellPosition` 에서 record() 호출. V44 Flyway 마이그레이션. H2 스키마 동기화. 테스트 6건.

### Tier 4 — 테스트/관측/인프라
- ✅ **§15 테스트 커버리지** — ExitRuleChecker 14건(SL/TP/트레일링/포지션사이징), 백테스트 결정론 1건, OrderExecutionEngine 상태머신 6건(PENDING/SUBMITTED→CANCELLED, FILLED/FAILED 취소불가, 중복감지, 일괄취소) 추가.
- ✅ **§16 Observability** — Micrometer `Counter` 3종 추가: `order.state.transition`(OrderExecutionEngine), `exchange.ws.reconnect`·`exchange.down.event`(ExchangeHealthMonitor), `session.balance.race.retry`(SessionBalanceUpdater). `/actuator/prometheus` 기존 노출 확인.
- ✅ **§17 DB 백업·복구 드릴** — `scripts/db-restore-drill.sh` 신규 작성. 최신 `.sql.gz` → 임시 Timescale 컨테이너 복원 → 필수 테이블 7종 스키마 검증 → 행 수 요약 → 컨테이너 자동 정리. `backups/drill-log.txt`에 실행 이력 누적.
- ✅ **§18 Upbit API 키 관리 / Actuator 노출 검증** — 점검 결과: ① Git 히스토리 실제 키 없음(플레이스홀더만). ② `.env` Git 미추적 확인. ③ Actuator 노출: `prometheus/health` 만 공개, `env/beans` 차단. ④ `SecurityConfig`: 모든 API 엔드포인트 Bearer 토큰 인증. `scripts/security-check.sh` 신규 작성(Git 유출·Actuator 노출·IP 화이트리스팅 권고 자동 점검).

---

## 프로젝트 개요

- **서비스**: 업비트 기반 가상화폐 자동매매 시스템
- **운영 환경**: Ubuntu 서버, Docker Compose (`docker-compose.prod.yml`)
- **기술 스택**: Spring Boot 3.2 (Java 17) + Next.js 16.1.6 / React 19.2.3 (TypeScript) + TimescaleDB + Redis

### 모듈 구조

```
crypto-auto-trader/
├── web-api/          # Spring Boot 백엔드 (Gradle 멀티모듈)
│   ├── core-engine/      # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── strategy-lib/     # 전략 19종 (단일 11 + 복합 8)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis + db-backup)
```

### 구현된 전략 19종

**단일 전략 (11종)**: VWAP / EMA Cross / Bollinger Band / Grid / RSI / MACD / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

**복합 전략 (8종)**:

| 전략 | 구성 | 실적합 코인 (DB 검증) | 3년 백테스트 요약 |
|------|------|----------------------|-----------------|
| COMPOSITE | Regime 자동 선택 | 범용 | — |
| COMPOSITE_MOMENTUM | MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 필터 | ETH·SOL (BTC 비권장) | ETH +53.6%, SOL +59.8%, BTC +0.4% |
| COMPOSITE_ETH | ATR×0.5 + OB×0.3 + EMA×0.2 | ETH | 구버전 평균 +48.7% (재검증 필요) |
| COMPOSITE_BREAKOUT | ATR×0.4 + VD×0.3 + RSI×0.2 + EMA×0.1, EMA+ADX 필터 | **BTC·ETH** (SOL → V2 전환) | BTC +104.2%, ETH +38.9% |
| COMPOSITE_MOMENTUM_ICHIMOKU | COMPOSITE_MOMENTUM + Ichimoku 필터 | ETH·SOL·XRP | ETH +46.0%, SOL +62.9%, XRP +36.5% |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 | MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터 | **SOL·XRP·DOGE** (ETH는 V1 우위) | SOL +131.1% MDD -12%, DOGE +134.4%, XRP +49.9% |
| COMPOSITE_BREAKOUT_ICHIMOKU | COMPOSITE_BREAKOUT + Ichimoku 필터 | — | ⚠ BREAKOUT과 완전 동일 (ADX 필터 중복) |
| MACD_STOCH_BB | MACD + StochRSI + 볼린저 6조건 AND | ❌ 비활성화 | BTC -2.32% 17건, XRP -2.02% 3건, ETH -0.33% 1건 — 거래 극희소·수익성 없음 |

### 주요 백테스트 결과 요약 (KRW 마켓 H1, 2023-01 ~ 2025-12)

> DB 직접 조회 기준 (2026-04-13). 강세·약세·횡보 3년 포함. 5코인 × 4전략 배치 완료.

#### COMPOSITE_BREAKOUT

> ⚠ **Sharpe 재계산 대기 (2026-04-15)**: 아래 Sharpe 값은 per-trade × sqrt(365) 버그 하에서 산출된 값이며,
> 일별 equity curve 기반으로 재계산 시 약 4~5배 축소될 것으로 예상 (상세: [20260415_sharpe_audit.md](20260415_sharpe_audit.md)).
> 수익률·MDD·거래수는 영향 없음.

| 코인 | 수익률 | 승률 | MDD | Sharpe (구버그) | 거래수 |
|------|--------|------|-----|----------------|--------|
| **BTC** | **+104.24%** | 24.6% | -5.98% | ~~6.41~~ (재계산) | 61 |
| ETH | +38.92% | 14.8% | -8.90% | ~~3.16~~ (재계산) | 61 |
| SOL | +64.86% | 19.6% | -19.17% | ~~3.81~~ (재계산) | 46 |
| XRP | +10.64% | 10.6% | -17.14% | ~~0.85~~ (재계산) | 47 |
| DOGE | +17.69% | 16.3% | -16.77% | ~~1.84~~ (재계산) | 49 |

> ⚠ COMPOSITE_BREAKOUT_ICHIMOKU는 BTC/ETH/SOL/XRP 모두 BREAKOUT과 완전 동일 수치 → ADX 필터 중복 확인됨.

#### COMPOSITE_MOMENTUM / ICHIMOKU V1 / ICHIMOKU V2

| 코인 | MOMENTUM | ICHIMOKU V1 | ICHIMOKU V2 | 3년 권장 |
|------|----------|-------------|-------------|---------|
| BTC | +0.44% MDD -13.1% | +1.61% MDD -12.7% | +13.01% MDD -12.1% | ❌ BREAKOUT |
| ETH | +53.58% MDD -18.3% | +45.95% MDD -16.9% | +37.31% MDD -22.8% | **MOMENTUM** |
| SOL | +59.77% MDD -13.8% | +62.89% MDD -13.5% | **+131.07% MDD -12.0%** | **V2** 🔥 |
| XRP | +26.99% MDD -24.2% | +36.47% MDD -20.7% | +49.92% MDD -25.3% | **V2** |
| DOGE | +62.36% MDD -28.8% | +55.45% MDD -28.8% | **+134.42% MDD -29.2%** | V2 (MDD 주의) |

> BTC는 COMPOSITE_MOMENTUM 계열 모두 +1~13% 수준 → BREAKOUT 외 선택지 없음. 2023 약세장이 V2 수익률 소멸시킴.
> **SOL**: V2가 BREAKOUT 대비 수익률 2배(+131% vs +65%), MDD도 낮음(-12% vs -19%) → **V2 전환 권고**.
> **DOGE**: V2 +134%로 전 코인 최고 수익률. 단 MDD -29%로 실전 투입 시 리스크 관리 필수.

#### 단일 전략 참고 (파라미터 최적화)

| 전략 | 코인 | 수익률 | 비고 |
|------|------|--------|------|
| MACD (14,22,9) | BTC | +151.9% Sharpe 1.68 | 단독, 2024~2025 H1 |
| MACD (10,26,9) | ETH | +216.0% Sharpe 1.61 | 단독, 2024~2025 H1 |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 코인별 전략 매칭 (2026-04-14 기준)

> 3년 백테스트(2023-01 ~ 2025-12) + Walk-Forward 검증 결과 종합. 현재 운영 상태 및 전환 권고 포함.

| 코인 | 현재 전략 | 권장 전략 | 3년 백테스트 | Walk-Forward | 상태 |
|------|-----------|-----------|-------------|--------------|------|
| BTC | COMPOSITE_BREAKOUT | **COMPOSITE_BREAKOUT** | 완료 | 완료 | 확정 — 변경 불필요 |
| ETH | COMPOSITE_MOMENTUM | **COMPOSITE_MOMENTUM** | 완료 | 완료 | 확정 — 변경 불필요 |
| SOL | COMPOSITE_BREAKOUT | **COMPOSITE_MOMENTUM_ICHIMOKU_V2** | 완료 | 완료 | V2 전환 권고 — 배포 대기 |
| XRP | COMPOSITE_MOMENTUM_ICHIMOKU_V1 | 미결 (V2 유력) | 완료 | 완료 | OOS 전 윈도우 음수 — 소액 병행 후 재결정 |
| DOGE | 미운영 | **COMPOSITE_MOMENTUM_ICHIMOKU_V2** | 완료 | 완료 | V2 투입 권고 — 소액(1만원) 시작 |

> V2 = COMPOSITE_MOMENTUM_ICHIMOKU_V2 (MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터)

### BTC — COMPOSITE_BREAKOUT 확정

- **3년 성과**: +104.2%, Sharpe 6.41, MDD -5.98% — 전 전략·전 코인 통틀어 가장 안정적인 Sharpe
- **MOMENTUM 배제 이유**: 3년 BTC MOMENTUM +0.4% — 사실상 수익 없음. VWAP·Grid 포함 레인지 전략이 BTC 강한 추세장에서 역방향 포지션 잦음
- **V2 배제 이유**: BTC V2 +13.0% MDD -12.1% — BREAKOUT 대비 수익률 8분의 1 수준
- **Walk-Forward 결과**: BREAKOUT OOS 합계 **+25.54%** (score 0.6741) vs MOMENTUM OOS **+0.64%** (score 0.4528 CAUTION). MOMENTUM은 과적합 지수가 낮으나 OOS 실제 수익이 거의 없음. BREAKOUT W1 OOS +21.79%(7건) 강한 실증 근거.
- **참고**: 단일전략 MACD(14,22,9) +151.9% Sharpe 1.68 (2024~2025 H1 한정) — 향후 단독 전략 병행 검토 가능

### ETH — COMPOSITE_MOMENTUM 확정

- **3년 성과**: +53.6%, MDD -18.3%
- **MOMENTUM 선택 이유**: BREAKOUT +38.9%보다 수익률 우위. ETH는 BTC 대비 상대적으로 레인지 구간 비율 높아 MACD×VWAP×Grid 조합이 잘 맞음
- **V1·V2 배제 이유**: Ichimoku V1 +46.0%(V1이 MOMENTUM보다 낮음), V2 +37.3%(더 낮음) — ETH는 Ichimoku 필터 추가 시 오히려 성과 저하. 신호 과필터링 가능성
- **Walk-Forward 결과**: MOMENTUM OOS 합계 **+13.50%** (score 3.5595) vs BREAKOUT OOS **+8.44%** (score 0.5676). BREAKOUT 거래 수 극히 적음(2t·2t·2t·3t·1t)으로 통계 신뢰도 낮음. MOMENTUM W1 OOS +17.32%(13건)이 핵심 근거.

### SOL — V2 전환 권고 (배포 대기)

- **3년 성과 비교**: V2 +131.1% MDD -12.0% vs BREAKOUT +64.9% MDD -19.2% — 수익률 2배, MDD도 낮음
- **Walk-Forward 결과**: BREAKOUT OOS 합계 **+12.05%** (score 0.8442) vs V2 OOS **-4.35%** (score 2.2474). BREAKOUT OOS 합계 양수이나 W0 단일 윈도우(+16.57%) 의존 — 나머지 4개 모두 손실·거래 극히 적음(1건). V2 OOS -4%이나 거래 수 충분(8~13건/윈도우)하여 통계 신뢰도 높음.
- **결론**: 3년 시뮬레이션 압도적 우위 + Walk-Forward BREAKOUT 이상치 의존성 → V2 전환. 소액(1만원) 운영이므로 교체 리스크 낮음

### XRP — 전환 보류 (소액 병행 후 재결정)

- **3년 성과 비교**: V2 +49.9% MDD -25.3% vs V1 +36.5% MDD -20.7%
- **Walk-Forward 결과**: V1 OOS 합계 **-14.05%** (score 1.0172) vs V2 OOS **-14.40%** (score 0.7458). 두 전략 모두 OOS 전 윈도우 음수 — Walk-Forward로는 어느 쪽도 검증되지 않음. IS와 OOS 사이 괴리가 큰 구조적 불안정성 존재 (W3 IS: V2 +72.21% → OOS -3.66%)
- **현재 방향**: V2 overfitting score 낮음(0.74 vs 1.02) + 3년 수익률 우위. 단 OOS 실증 불충분. 소액으로 V2 병행 운영 후 실전 데이터 3개월 이상 확보 후 전환 여부 결정.
- **BREAKOUT 배제 이유**: XRP BREAKOUT +10.6% — V2 대비 수익률 5분의 1 수준

### DOGE — V2 소액 투입 권고

- **3년 성과**: V2 +134.4% MDD -29.2% — 5개 코인 전 전략 통틀어 최고 수익률
- **Walk-Forward 결과**: V2 OOS 합계 **+48.76%** (score 9.7151) vs V1 OOS **+16.50%** (score 0.5708). V2 OOS가 3배 높음. W1~W3 3개 연속 OOS 양수(+33.17%, +14.37%, +13.82%) — 강한 실증. overfitting score 높음(9.72)은 변동성 큼을 의미하므로 소액 원칙 필수.
- **주의사항**: MDD -29.2% — 실질적으로 자산의 약 30% 낙폭 허용. 1만원으로 시작하여 2주 이상 운영 후 MDD 실측치 확인
- **V1 대비 V2 우위 이유**: V2 W1 OOS +33.17%(9건) — 강한 실증 근거. SUPERTREND가 DOGE 급등 사이클 초입 포착에 더 강점

---

## 다음 할 일

### ~~🔴 P1-0 — 전략 가중치 최적화 구조적 한계 해결~~ ✅ 완료 (2026-04-13)

> **방향 A 완료**: `StrategyWeightOptimizer` + `StrategySelector` 모두 Composite 전략명 기준으로 전환.
> `strategy_log`에 실제로 기록되는 이름(`COMPOSITE_BREAKOUT`, `COMPOSITE_MOMENTUM`)과 일치시켜
> 가중치가 기본값에서 실제로 움직이도록 수정. CHANGELOG 참조.

---

### 🔴 P1-1 — 전략 고도화

- [x] **FVG (Fair Value Gap) 전략 — A단계 구현 완료**: `FairValueGapStrategy` + `FairValueGapConfig` 구현. EMA 필터·최소 공백 크기 필터 포함. `StrategyRegistry` 및 `StrategyController` 등록 완료.
- [x] **FVG A단계 — 5코인 × H1 3년 백테스트 (2023~2025)**

  | 코인 | 수익률 | 승률 | MDD | 판단 |
  |------|--------|------|-----|------|
  | SOL | +224.40% | 18.7% | -34.01% | 🔥 수익 압도적, MDD는 V2(-12%) 대비 3배 높음 |
  | BTC | +44.47% | 20.4% | -23.04% | ❌ BREAKOUT(+104% MDD -6%) 대비 열위 |
  | DOGE | +14.23% | 16.6% | -44.04% | ❌ V2(+134%) 대비 열위 |
  | XRP | +10.99% | 16.8% | -46.63% | ❌ V2(+50%) 대비 열위 |
  | ETH | -49.93% | 15.5% | -61.94% | ❌ 완전 부적합 (레인지 특성과 충돌) |

  > 전 코인 승률 15~20% — 모멘텀 방식 특성상 손절 빈도 높고 MDD 전반적으로 높음.
  > **단독 전략으로는 SOL만 의미 있으나**, MDD -34%가 V2 대비 너무 높아 단독 교체 부적절.
  > 향후 검토: ① SOL 파라미터 최적화 (MDD 개선 여지) ② COMPOSITE 서브전략 편입 ③ B단계(평균 회귀) 구현.

- [ ] **FVG 전략 — B단계 (추후 판단)**: 평균 회귀 방식으로 고도화. FVG 존(상·하한) 상태 관리 → 이후 가격이 공백 구간 재진입 시 신호 발생. 오래된 존 만료 처리 포함.
- [ ] **STOCHASTIC_RSI 구조적 개선** — StochRSI + RSI 다이버전스 결합. RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호 (구현 복잡도 높음)
- [x] **VOLUME_DELTA 테스트 작성** — BUY/SELL/HOLD 기본 케이스 + Delta 추세 미충족 HOLD + 강세/약세 다이버전스 HOLD + divergenceMode=false 검증 + 볼륨 0 HOLD (13개 전체 통과)
- [ ] ~~**MACD_STOCH_BB → COMPOSITE TREND 서브필터 편입**~~ — 서브필터로도 부적합 판정. 3년 H1 BTC 17건·XRP 3건 수준으로 거래 극희소. MACD>0(상승추세) + StochRSI<20(극단 과매도) 동시 조건이 구조적으로 충족 불가. **비활성화 처리** (실전매매 차단 목록 추가)

---

### 🟡 P2-0 — 실전 테스트 및 전략 검증

> Walk-Forward 전체 완료 기준 (2026-04-14): BTC·ETH 현재 전략 검증 완료. SOL V2 전환 권고. XRP 보류. DOGE V2 투입 권고.

- [x] **BTC·ETH 전략 Walk-Forward 검증** — BTC BREAKOUT OOS +25.54% (score 0.6741), ETH MOMENTUM OOS +13.50% (score 3.5595). 현재 전략 모두 실증 완료.
- [x] **SOL Walk-Forward 검증** — V2 OOS -4.35% vs BREAKOUT OOS +12.05%. BREAKOUT 이상치(W0 의존) 제외 시 V2 우위 + 3년 수익률 2배 → V2 전환 권고.
- [x] **XRP Walk-Forward 검증** — V1·V2 모두 OOS 전 윈도우 음수 (V1 -14.05%, V2 -14.40%). 전환 보류. 소액 병행 운영 후 3개월 실전 데이터 확보 후 재결정.
- [x] **DOGE Walk-Forward 검증** — V2 OOS +48.76% (V1 +16.50% 대비 3배). W1~W3 3개 연속 양수. V2 투입 권고.
- [ ] **SOL 전략 V2 전환 배포** — COMPOSITE_BREAKOUT → COMPOSITE_MOMENTUM_ICHIMOKU_V2. 배포 대기.
- [ ] **DOGE V2 소액 투입 배포** — 1만원으로 시작. 2주 운영 후 MDD 실측치 확인.
- [ ] **XRP 실전 병행 운영** — V1 유지하되 V2 소액 병행. 3개월 후 실전 수익률 비교 후 전환 결정.
- [ ] **실전매매 금액 증액** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 기준: 2주 이상 운영 + 승률 >= 50% + MDD < 10%

---

### ~~🟡 P2-2 — 운영 도구 개선~~ ✅ 완료 (2026-04-14)

- [x] **모의투자 최대 세션 수 확장** — 10 → 20 (`PaperTradingService.MAX_CONCURRENT_SESSIONS`)
- [x] **야간 자동 스케줄러 DB 기반 관리** — `nightly_scheduler_config` 테이블 + `NightlySchedulerConfigService`. KST 실행 시각 설정, 23시간 중복 방지, 1분 tick. `BacktestAutoSchedulerService` 위임 구조로 전환.
- [x] **스케줄러 웹 UI** — `/backtest/scheduler` 페이지. 코인·전략·타임프레임·기간 선택, ON/OFF 토글, 수동 트리거, 예상 작업량 표시.
- [x] **모의투자 리스크 지표 계산 수정** — `PaperTradingService.computeRiskMetrics()` 추가. MDD(누적 손익 곡선), Sharpe/Sortino(수익률 시계열) 실제 계산. 기존 항상 0.0 반환 버그 수정.
- [x] **V42 마이그레이션** — V41 SMALLINT → INTEGER 컬럼 타입 수정 (Hibernate 스키마 검증 오류 해결).

---

### ~~🟡 P2-1 — 운영 인프라~~ ✅ 완료 (2026-04-13)

- [x] **2023~2025 전체 기간 백테스트** — 5코인(BTC·ETH·SOL·XRP·DOGE) × 4전략(BREAKOUT·MOMENTUM·V1·V2) 배치 완료. 결과 위 요약 참조.

---

### ⏳ 장기 검토

- [ ] **멀티 타임프레임** — 1H 방향 + 15M 진입. WebSocket 급등락 대응으로 단기 커버 중. 아키텍처 변경이 크므로 나중에 재검토
- [ ] **동적 가중치 완성** — 인프라(`WeightOverrideStore` + `StrategySelector`)는 구축 완료. P1-0 방향 결정 후 Optimizer 로직 완성. 100거래 이상 샘플 기반, 하한선 0.05, 스무딩 70/30 적용 예정
- [ ] **칼만 필터 스캘핑 전략 (5m/15m)** — H1은 노이즈가 적어 효용 낮음. 분봉 스캘핑 전용으로 한정 검토. 선행 조건: ① 수수료 영향 시뮬레이션(업비트 0.05% × 거래 빈도) ② 5m/15m 캔들 데이터 충분성 확인 ③ FVG A/B 단계 완료 후 착수.

---

## 서버 명령어

### 로컬 (Windows)

```bash
docker compose up -d                                                       # DB + Redis 시작 (로컬은 비밀번호 없음)
./gradlew :web-api:bootRun --args='--spring.profiles.active=local'        # 백엔드 (포트 8080)
cd crypto-trader-frontend && npm run dev                                   # 프론트엔드 (포트 3000)
```

### 운영 (Ubuntu)

```bash
cd ~/crypto-auto-trader

# 재빌드 & 재시작
docker compose -f docker-compose.prod.yml up -d --build           # 전체
docker compose -f docker-compose.prod.yml up -d --build backend   # 백엔드만
docker compose -f docker-compose.prod.yml up -d --build frontend  # 프론트엔드만

# 로그 실시간 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f frontend

# 오류 원인 분석 (ERROR/Exception 필터링)
docker compose -f docker-compose.prod.yml logs backend > /tmp/backend.log 2>&1
grep -n "ERROR\|Caused by\|Exception" /tmp/backend.log | tail -30
```
