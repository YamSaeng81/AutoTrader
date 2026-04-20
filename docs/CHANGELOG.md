# CryptoAutoTrader — CHANGELOG.md

> **목적**: 완료된 작업의 상세 변경 이력. `PROGRESS.md`에서 할 일이 완료되면 이 파일에 추가한다.

---

### ✅ 완료 (2026-04-20) — 신호 품질 분석 고도화 ⑦⑧⑨

**⑦ 지수 가중 수익률 (StrategyWeightOptimizer)**
- `PositionRepository`에 개별 포지션 + `closed_at` 반환 쿼리 2종 추가 (`findClosedPositionsForWeighting`, `findClosedPositionsForWeightingByCoin`)
- `loadRealizedReturns()` / `optimizeCoinLevel()` 교체 — `w = exp(-days/14)` 가중 수익률로 전략 가중치 산출
- `toInstant(Object)` 헬퍼 추가 (Timestamp / LocalDateTime / Date / String 타입 통합 처리)

**⑧ 모의→실전 자동 승격 파이프라인**
- `PaperSessionPromotionService` 신규 생성
- 매일 08:00 KST (cron `0 0 23 * * *`) 스케줄 — PAPER RUNNING 세션 전체 점검
- 승격 기준 5개: 운영 ≥30d / 신호 ≥20건 / 4h 적중률 ≥60% / EV ≥0.1% / MDD ≤10%
- 조건 충족 시 Discord embed 알림 (`PAPER_PROMOTION`) + ConcurrentHashMap 중복 방지
- `LiveTradingSessionRepository.findBySessionTypeAndStatus`, `StrategyLogRepository.findEvaluatedSignalsBySessionId` 추가

**⑨ 전략 간 상관관계 분석**
- `AnalysisReport.StrategyCorrelationStats` 추가 (totalBuckets / consensusBuckets / divergentBuckets / 각 4h 적중률·평균수익)
- `LogAnalyzerService.buildCorrelationStats()`: 동일 코인·4h KST 버킷에서 ≥2 전략이 신호 낸 경우, 방향 일치=컨센서스 / 혼재=분산으로 분류 후 적중률 비교
- Notion 보고서에 "🔗 전략 간 상관관계 (컨센서스 vs 분산)" 테이블 추가
- LLM 분석 프롬프트에 컨센서스 유효성 해석 항목 추가 (800자로 증량)

| 파일 | 변경 내용 |
|------|-----------|
| `PositionRepository.java` | 개별 포지션 쿼리 2종 추가 |
| `LiveTradingSessionEntity.java` | `sessionType` 필드 추가 |
| `LiveTradingSessionRepository.java` | `findBySessionTypeAndStatus` 추가 |
| `StrategyLogRepository.java` | `findEvaluatedSignalsBySessionId` 추가 |
| `StrategyWeightOptimizer.java` | 지수 가중 수익률 교체, `toInstant()` 추가 |
| `PaperSessionPromotionService.java` | 신규 파일 |
| `AnalysisReport.java` | `StrategyCorrelationStats` 추가, `correlationStats` 필드 추가 |
| `LogAnalyzerService.java` | `buildCorrelationStats()` 추가 |
| `ReportComposer.java` | `correlationStatsSummary()`, `buildCorrelationStatsSection()` 추가, LLM 프롬프트 개선 |

---

### ✅ 완료 (2026-04-20) — 신호 품질 분석 고도화 Tier 1+2 (①~⑥)

**배경**: 분석 시스템을 단순 적중률 집계에서 EV·레짐별·시간대별 다차원 분석으로 고도화.

**① 코인×레짐 복합 가중치** — `WeightOverrideStore.coinStore` 추가, `StrategySelector.select(regime, coinPair)` 오버로드, `RegimeAdaptiveStrategy` coinPair 파라미터 연동.

**② Confidence Score** — `StrategyLogEntity.confidenceScore`, `LiveTradingService`·`PaperTradingService` signal.getStrength()/100 저장, `LogAnalyzerService.HIGH_CONF_THRESHOLD(0.7)` 필터링, `StrategySignalStat.highConfAccuracy4h/highConfCount` 추가.

**③ 성과 저하 워치독** — `StrategyDegradationWatchdog`: 6h 스케줄, 7d/30d 적중률 비교, WARN(15%p)·CRIT(25%p)·NEG_EV 경보, Discord embed 전송.

**④ EV 중심 평가** — `StrategySignalStat.expectedValue4h` 추가. `calcExpectedValue()` 구현. `StrategyWeightOptimizer.computeWeightsFromSignals()` 4h 적중률 → EV 교체. Notion·LLM 프롬프트 EV 포함.

**⑤ 레짐별 신호 품질** — `AnalysisReport.RegimeSignalQuality` (BUY/SELL 4h 적중률·평균수익·EV). `buildRegimeSignalStats()` 구현. Notion "📐 레짐별 신호 품질" 테이블. LLM "어떤 레짐에서 EV 양수/음수인지" 항목 추가.

**⑥ 시간대별 신호 품질** — `AnalysisReport.HourlySignalQuality` (KST 4h 버킷·신호수·적중률·평균수익). `buildHourlySignalStats()` 구현. Notion "🕐 시간대별 신호 품질" 테이블. LLM "좋은/나쁜 시간대 패턴" 분석 추가.

**실전/모의 보고서 분리** — `LogAnalyzerService.analyze(from, to, sessionType)`. `ReportComposer` REAL·PAPER 각각 분석 후 하나의 Notion 페이지에 섹션 분리. `AiPipelineTest` 3인자 mock 업데이트.

**H2 스키마 수정** — `live_trading_session.session_type` 컬럼 누락 추가 → `LiveTradingReliabilityTest` 6건 통과.

| 파일 | 변경 내용 |
|------|-----------|
| `WeightOverrideStore.java` | coinStore, updateForCoin, getForCoin |
| `StrategySelector.java` | select(regime, coinPair) 오버로드 |
| `RegimeAdaptiveStrategy.java` | coinPair params 연동 |
| `StrategyLogEntity.java` | confidence_score 컬럼 추가 |
| `LiveTradingService.java` / `PaperTradingService.java` | confidence 저장 |
| `StrategyWeightOptimizer.java` | optimizeCoinLevel, EV 기반 신호 폴백 |
| `PositionRepository.java` | session_type 필터 쿼리 2종 추가 |
| `StrategyLogRepository.java` | findByPeriodAndSessionType 추가 |
| `AnalysisReport.java` | RegimeSignalQuality, HourlySignalQuality, expectedValue4h |
| `LogAnalyzerService.java` | calcExpectedValue, buildRegimeSignalStats, buildHourlySignalStats |
| `ReportComposer.java` | REAL/PAPER 분리, 레짐·시간대 섹션, LLM 프롬프트 개선 |
| `StrategyDegradationWatchdog.java` | 신규 파일 |
| `schema-h2.sql` | session_type 컬럼 추가 |

---

### ✅ 완료 (2026-04-20) — Ichimoku 전략 구조 정리 및 코드 정확도 수정

**배경**: 신호 품질 대시보드 분석 과정에서 발견한 3가지 코드 오류 수정.

**수정 내용**:

1. **`StrategySelector` static 블록 — EMA·ADX 필터 누락 수정**
   - 테스트 폴백용 등록 코드가 필터 없는 버전 사용 → `emaFilter=true, adxFilter=true` 추가
   - Spring 환경에서는 `CompositePresetRegistrar.@PostConstruct`가 덮어씀 (정상)
   - 역할 명시: "Spring 없는 환경(단위 테스트) 폴백 등록" 주석 추가

2. **`StrategyWeightOptimizer` 주석 오류 수정**
   - "COMPOSITE_MOMENTUM_ICHIMOKU_V2 미구현 상태" → `CompositePresetRegistrar`에 완전 구현·등록됨
   - 실제 상태 명시: "StrategySelector 레짐 선택에 미연동 — 세션 직접 할당 방식으로 운영 중"

3. **`StrategyLiveStatusRegistry` — COMPOSITE_BREAKOUT_ICHIMOKU EXPERIMENTAL→BLOCKED**
   - `CompositePresetRegistrar` 코드 주석에 이미 명시: ADX(14)<20 필터가 횡보장 선차단 → Ichimoku 필터 발동 구간 없음
   - 백테스트 결과 COMPOSITE_BREAKOUT과 동일 성능 → 신규 세션 생성 차단
   - 기존 운영 세션은 만료 후 COMPOSITE_BREAKOUT 전환 권장

| 파일 | 변경 내용 |
|------|-----------|
| `StrategySelector.java` | static 블록 주석 명확화 + EMA·ADX 필터 플래그 추가 |
| `StrategyWeightOptimizer.java` | Ichimoku 전략 상태 주석 정정 |
| `StrategyLiveStatusRegistry.java` | COMPOSITE_BREAKOUT_ICHIMOKU: EXPERIMENTAL → BLOCKED |

---

### ✅ 완료 (2026-04-20) — StrategyWeightOptimizer DEFAULTS 동기화 (Option A)

**배경**: `StrategyWeightOptimizer.DEFAULTS`에 `COMPOSITE_MOMENTUM_ICHIMOKU_V2`가 포함되어 있었으나
`StrategySelector` 및 `StrategyRegistry`에 미등록 상태 → 3전략 기준으로 가중치 정규화 후
StrategySelector는 2전략만 조회, 합계가 1.0 미달로 왜곡되는 버그.

**수정 내용**:
- `StrategyWeightOptimizer.DEFAULTS`를 StrategySelector 실제 구성(2전략/레짐)과 동기화
- `COMPOSITE_MOMENTUM_ICHIMOKU_V2` 제거 및 주석으로 미구현 상태 명시
- `StrategyWeightOptimizerTest` 전면 재작성 (4건 → 5건): 2전략 구조 기준, MIN_REGIME_SAMPLE 충족 조건 명시

| 파일 | 변경 내용 |
|------|-----------|
| `StrategyWeightOptimizer.java` | DEFAULTS: 3전략 → 2전략(BREAKOUT+MOMENTUM), StrategySelector 기본값과 동기화 |
| `StrategyWeightOptimizerTest.java` | ICHIMOKU_V2 참조 제거, 2전략 구조 검증 5건으로 재작성 |

---

### ✅ 완료 (2026-04-20) — 신호품질 개선: 자본 사용률 기반 리스크 제어

**배경**: 대시보드 분석에서 "차단 신호 사후 성과 > 실행 신호"가 관찰됨.
원인: `maxPositions=3` 하드캡이 세션 수 기준으로 좋은 신호를 차단하던 문제.
근본 해결: 포지션 수 대신 **자본 사용률(%) 기반** 차단으로 전환.

**버그 수정**:
- `CompositeStrategy` 정규화 버그: `totalWeight < 1.0`일 때 점수 역부풀림 → `Math.max(totalWeight, 1.0)` 분모 사용
- `StrategySelectorTest`: Composite 구조로 리팩토링된 구현에 맞게 기댓값 업데이트
- `RiskEngineTest`: `maxPositions` 기본값 3→20 변경 후 기존 테스트가 실패 → 명시적 `maxPositions(3)` 설정으로 수정

| 파일 | 변경 내용 |
|------|-----------|
| `core-engine/.../RiskConfig.java` | `maxPositions` 기본값 3→20, `maxCapitalUtilizationPct` 필드(기본 80%) 추가 |
| `core-engine/.../RiskEngine.java` | `check(…, capitalUtilizationPct)` 5파라미터 오버로드 추가. 자본 사용률 한도 초과 시 reject, 포지션 수는 안전망으로 격하 |
| `core-engine/.../StrategySelector.java` | `COMPOSITE_BREAKOUT`·`COMPOSITE_MOMENTUM` 복합 전략을 static 초기화 블록에서 `StrategyRegistry`에 등록 |
| `core-engine/.../CompositeStrategy.java` | 정규화 분모를 `Math.max(totalWeight, 1.0)`으로 변경 — 단독 저가중 전략이 임계값을 왜곡하던 버그 수정 |
| `web-api/.../RiskManagementService.java` | `calculateCapitalUtilizationPct()` 추가 (RUNNING 세션 기반), `resolvePortfolioLimit()` — 소액 세션 gross loss 버그 수정, `checkRisk()`에 자본 사용률 전달 |
| `web-api/.../RiskConfigEntity.java` | `max_capital_utilization_pct` 컬럼 추가, `maxPositions` 기본값 3→20 |
| `web-api/…/V45__*.sql` (신규) | `max_capital_utilization_pct` 컬럼 추가, 기존 `max_positions=3` 레코드 → 20 업데이트 |
| `web-api/.../RiskManagementServiceTest.java` | 자본 사용률 차단/통과 테스트 2건, gross loss 회귀 방지 3건 (총 5건) |

---

### ✅ 완료 (2026-04-20) — Tier 4 §18: 보안 — Upbit API 키 관리 / Actuator 노출 검증

**목표**: 실전 서버의 보안 취약점(API 키 유출, 민감 엔드포인트 노출, CORS 설정 오류)을 자동으로 점검한다.

**점검 결과 (현재 상태)**:
- Git 히스토리: 실제 키 없음 — 플레이스홀더(`your_access_key`)만 존재
- `.env`: `.gitignore`에 포함, Git 미추적
- Actuator 노출: `prometheus`, `health` 만 공개. `env`·`beans`·`configprops`·`mappings` 미노출
- Spring Security: 모든 `/api/**` 엔드포인트 Bearer 토큰 필수 인증. prod 프로파일 Swagger 비활성화
- CORS: `CORS_ALLOWED_ORIGINS` 환경변수로 분리 (기본값 `localhost:3000`)

| 파일 | 변경 내용 |
|------|-----------|
| `scripts/security-check.sh` (신규) | 4단계 자동 점검: (1) Git 히스토리 키 유출 정규식 스캔, (2) `.env` 추적 여부, (3) 민감/안전 Actuator 엔드포인트 HTTP 응답 코드 확인, (4) 서버 외부 IP 조회 + Upbit IP 화이트리스팅 안내 |

---

### ✅ 완료 (2026-04-20) — Tier 4 §17: DB 백업·복구 드릴

**목표**: 복원 경험이 없는 백업은 백업이 아니다 — 자동화된 복원 드릴 스크립트로 월 1회 검증을 가능하게 한다.

| 파일 | 변경 내용 |
|------|-----------|
| `scripts/db-restore-drill.sh` (신규) | 최신 `backups/*.sql.gz` 선택 → 임시 Timescale 컨테이너(`crypto-restore-drill`) 시작 → pg_isready 대기 → gunzip + psql 복원 → 필수 테이블 7종(`live_trading_session`, `position`, `order_details`, `trade_log`, `backtest_run`, `candle_data`, `execution_drift_log`) 스키마 검증 → 주요 테이블 행 수 출력 → 컨테이너 자동 삭제. 특정 파일 지정 가능 (`./scripts/db-restore-drill.sh backups/backup_X.sql.gz`). 성공 시 `backups/drill-log.txt`에 이력 누적 |

**기존 백업 컨테이너 (`docker-compose.prod.yml`)**: 일 1회 pg_dump + gzip, 7일 보관 — 기존 설정 그대로 유지.

---

### ✅ 완료 (2026-04-20) — Tier 4 §16: Observability — Micrometer 메트릭 export

**목표**: 주문 실패율, WS 재연결, race condition 발생 빈도를 Prometheus로 가시화한다.

| 파일 | 변경 내용 |
|------|-----------|
| `OrderExecutionEngine.java` | `MeterRegistry` 주입(required=false). `transitionState()`에 `order.state.transition{from,to}` Counter 추가 — PENDING→FAILED, SUBMITTED→FILLED 등 모든 전이가 기록됨 |
| `ExchangeHealthMonitor.java` | `MeterRegistry` 주입. WS 재연결 시 `exchange.ws.reconnect` Counter 증가. 거래소 DOWN 선언 시 `exchange.down.event` Counter 증가 |
| `SessionBalanceUpdater.java` | `MeterRegistry` 주입. 낙관적 락 충돌 재시도마다 `session.balance.race.retry` Counter 증가 |
| `application.yml` | 이미 `/actuator/prometheus` 노출 확인 — 추가 설정 불필요 |

---

### ✅ 완료 (2026-04-20) — Tier 4 §15: 테스트 커버리지 — ExitRuleChecker / BacktestEngine / OrderExecutionEngine

**목표**: 리스크 규칙, 백테스트 결정론, 주문 상태머신의 핵심 경로를 테스트로 커버한다.

| 파일 | 변경 내용 |
|------|-----------|
| `ExitRuleCheckerTest.java` (신규) | 14건 — `calculateStopLevels`(기본값·시그널 우선·커스텀), `checkCandleExit`(SL만·TP만·동시 ambiguous·미터치·정확접촉), `checkPriceExit`(SL·TP·중간), `updateTrailingStops`(TP 래칫 상향·단방향·SL 조임·비활성화), `calculateInvestAmount`(80%·최솟값 미달·최솟값 충족) |
| `BacktestEngineTest.java` | 결정론 테스트 1건 추가 — 같은 입력으로 두 번 실행 시 트레이드 수·체결가·pnl·totalReturnPct 동일 |
| `OrderExecutionEngineTest.java` (신규) | 6건 — PENDING/SUBMITTED→CANCELLED 전이, FILLED/FAILED 취소불가(IllegalStateException), 미존재 ID(IllegalArgumentException), cancelAllActiveOrders 집계, 중복감지 쿼리 |

---

### ✅ 완료 (2026-04-20) — Tier 3 §14: 실전/백테스트 drift 트래커

**목표**: 백테스트가 가정한 체결가(신호 생성 시 종가)와 실전 체결가 간 편차(slippage)를 거래별로 기록·집계하여 백테스트-실전 갭을 조기 감지한다.

| 파일 | 변경 내용 |
|------|-----------|
| `ExecutionDriftLogEntity.java` (신규) | `execution_drift_log` JPA 엔티티 — sessionId, coinPair, strategyType, side, signalPrice, fillPrice, slippagePct, executedAt |
| `ExecutionDriftLogRepository.java` (신규) | `findTop20BySessionIdOrderByExecutedAtDesc`, `findTop50ByStrategyTypeOrderByExecutedAtDesc`, `avgSlippagePctSince` JPQL 쿼리 |
| `ExecutionDriftTracker.java` (신규) | `record()` — slippage(%) = (fillPrice - signalPrice) / signalPrice × 100 계산·저장. `checkDriftAlert()` @Scheduled(1hr) — 전략별 7일 평균 > 0.5% 시 Telegram 경고. `getRecentBySession()` / `getWeeklyAvgSlippage()` API 제공 |
| `LiveTradingService.java` | `finalizeSellPosition`에서 체결 완료 후 `executionDriftTracker.record()` 호출 |
| `V44__create_execution_drift_log.sql` (신규) | PostgreSQL DDL — BIGSERIAL PK + 인덱스(strategy_type, executed_at) |
| `schema-h2.sql` | `execution_drift_log` 테이블 추가 (H2 호환 `GENERATED BY DEFAULT AS IDENTITY`) |
| `ExecutionDriftTrackerTest.java` (신규) | 6건 — slippage 계산 정확도, 음수 slippage, 신호가 0 생략, 세션별 조회, 7일 평균 계산, 8일 전 기록 제외 |

---

### ✅ 완료 (2026-04-20) — Tier 3 §13: 데이터 스냅샷 편향 — 월별 수익률 분포 편향 감지

**목표**: 수익이 특정 달(예: 2024 급등장 1개월)에 집중되어 전략 성과가 과대평가되는 스냅샷 편향을 수치로 가시화한다.

| 파일 | 변경 내용 |
|------|-----------|
| `PerformanceReport.java` | `monthlyReturnStdDev`(월별 수익률 표준편차), `monthlyReturnSkewness`(왜도 — 양수면 우측 꼬리), `topMonthConcentrationPct`(최고 수익 달 집중도%) 필드 추가 |
| `MetricsCalculator.java` | `calculateMonthlyStdDev()` / `calculateMonthlySkewness()` / `calculateTopMonthConcentration()` 헬퍼 구현. `emptyReport()`에도 기본값(0) 추가. |
| `MetricsCalculatorTest.java` | 5건 추가 — 균등분포 stdDev≈0, 분산분포 stdDev>50, 집중도 80% 이상/이하, 오른쪽 꼬리 skewness>0 |

---

### ✅ 완료 (2026-04-20) — Tier 3 §12: 파라미터 튜닝 look-ahead 방지 — holdOutCutoff 가드

**목표**: 파라미터 선택에 사용한 동일 기간으로 백테스트를 평가하는 overfitting 위험을 Walk-Forward 수준에서 차단한다. IS 윈도우가 검증 기간 데이터를 침범하면 즉시 예외를 던진다.

| 파일 | 변경 내용 |
|------|-----------|
| `WalkForwardTestRunner.java` | `run(config, candles, ratio, count, mode, holdOutCutoff)` 오버로드 추가. `holdOutCutoff` 비-null 시: (1) cutoff 이전 캔들만 튜닝 WF 실행 (IS 절대 침범 불가). (2) cutoff 이후 캔들을 별도 홀드아웃 백테스트 실행 → `HOLD_OUT` 세그먼트 윈도우 추가. (3) `validateHoldOut()` — 전체 캔들이 cutoff 이후면 예외. 레거시 3-param·4-param 시그니처는 `holdOutCutoff=null` 위임으로 하위 호환 유지. |
| `WalkForwardTestRunnerTest.java` | 3건 추가 — IS 윈도우가 cutoff를 초과하지 않음, 홀드아웃 윈도우 추가 확인, 전체 캔들이 cutoff 이후이면 예외 |

---

### ✅ 완료 (2026-04-20) — Tier 3 §11: 전략 19종 중 실전 후보 — 운영 가능 여부 매트릭스

**목표**: "구현 완료"와 "실전 투입 가능"을 명시적으로 분리해 잘못된 전략 투입을 방지한다. 기존 `BLOCKED_LIVE_STRATEGIES` 하드코딩을 단일 진실 소스(SSoT)로 통합한다.

| 파일 | 변경 내용 |
|------|-----------|
| `StrategyLiveStatusRegistry.java` (신규) | 4단계 분류: **ENABLED**(4종 — COMPOSITE_BREAKOUT·COMPOSITE_MOMENTUM·COMPOSITE_MOMENTUM_ICHIMOKU·V2) / **BLOCKED**(3종 — STOCHASTIC_RSI·MACD·MACD_STOCH_BB) / **EXPERIMENTAL**(13종 — 단일전략 전체·COMPOSITE·COMPOSITE_ETH·COMPOSITE_BREAKOUT_ICHIMOKU) / **DEPRECATED**(1종 — TEST_TIMED). 각 항목에 차단/미권장 근거 텍스트 포함. `isBlocked()` 메서드(BLOCKED + DEPRECATED 포함). |
| `LiveTradingService.java` | `BLOCKED_LIVE_STRATEGIES` static 필드 제거 → `strategyLiveStatusRegistry.isBlocked()` 호출로 교체. 차단 메시지에 readiness 단계·근거 포함. |
| `StrategyController.java` | `buildStrategyInfo()` 에 `liveReadiness`·`liveReadinessReason` 필드 추가. `GET /api/v1/strategies/live-matrix` 엔드포인트 신규 추가 (전략별 운영 단계 일람). |
| `StrategyLiveStatusRegistryTest.java` (신규) | 6건 — ENABLED isBlocked=false, BLOCKED isBlocked=true, EXPERIMENTAL isBlocked=false, DEPRECATED isBlocked=true, 미등록 기본값 EXPERIMENTAL, getAll() BLOCKED 3건 이상 |

---

### ✅ 완료 (2026-04-20) — Tier 2 §10: emergencyStopAll 연쇄 충격 — rate limit + 우선순위 + dry-run

**목표**: 비상 청산 시 다수 SELL/CANCEL 이 동시 발사되면 Upbit rate limit(8 req/s)에 걸려 주문이 누락되던 문제를 제어한다. 손실이 큰 포지션을 먼저 청산하고, 실제 주문 없이 시뮬레이션만 수행하는 dry-run 모드를 제공한다.

| 파일 | 변경 내용 |
|------|-----------|
| `UpbitApiRateLimiter.java` (신규) | Semaphore 기반 7 permits/sec 슬라이딩 윈도우. 데몬 스레드가 1초마다 리필. `acquire()` 타임아웃 10초 + false 반환으로 호출부 재시도 위임 |
| `OrderExecutionEngine.java` | `submitOnExchange()` + `cancelOnExchange()` 진입 시 `rateLimiter.acquire()` 적용 |
| `LiveTradingService.java` | `emergencyStopAll(boolean dryRun)` — RUNNING 세션 `(totalAsset − initialCapital)` 오름차순 정렬(손실 큰 세션 우선). `dryRun=true` 시 실제 `emergencyStopSession()` 호출 없이 로그만 기록. 기존 `emergencyStopAll()` 는 `emergencyStopAll(false)` 위임 |
| `TradingController.java` | `POST /emergency-stop` — `emergencyStopAll(false)` 호출로 명시화. `POST /emergency-stop/dry-run` 엔드포인트 신규 추가 |
| `RateLimiterEmergencyStopTest.java` (신규) | 4건 — 초기 permit 검증, 소진→리필 확인, 동시 acquire 한도, 리필 누적 초과 방지 |

---

### ✅ 완료 (2026-04-19) — Tier 2 §9: WebSocket 단일 장애점 — REST ticker fallback

**목표**: WS 끊김 지속 시 실시간 손절(SL) 이 "조용히 멈추는" 문제를 REST ticker 폴링 자동 전환 + SL 미점검 경고로 해결한다.

| 파일 | 변경 내용 |
|------|-----------|
| `ExchangeHealthMonitor.java` | `wsDisconnectedSince` 타임스탬프 추적 + `isWsDownLongerThan(seconds)` 메서드 추가. 재연결 시 자동 리셋 + 중복 해제 시 최초 시점 유지. |
| `LiveTradingService.java` | `pollRestTickerFallback()` (5초 @Scheduled) — WS >30초 끊김 시 `UpbitRestClient.getTicker()` 로 RUNNING 세션 코인 폴링 → 기존 `RealtimePriceEvent` 발행. WS 복구 시 자동 비활성화 + 로그. `warnStaleSlCheck()` (1분 @Scheduled) — SL 3분 미점검 세션 Telegram 경고. `recordSlCheck()` — SL 점검 시 시각 기록 (`doOnRealtimePriceEvent` 내부). |
| `TelegramNotificationService.java` | `sendCustomNotification(String)` 범용 알림 메서드 추가 |
| `WsFallbackTest.java` (신규) | 3건 — 30초 끊김 감지, 재연결 리셋, 중복 해제 시 최초 시점 유지 |

---

### ✅ 완료 (2026-04-19) — Tier 2 §8: 세션당 1코인 암묵 가정 — 자본 초과 배정 방지

**목표**: 다중 세션이 동일 코인에 동시 BUY 를 발사하면 Upbit 계좌 잔고를 초과할 수 있는 문제를 3중 가드로 차단하고, 주기적 drift 감지를 추가한다.

| 파일 | 변경 내용 |
|------|-----------|
| `LiveTradingSessionRepository.java` | `sumInitialCapitalByStatusIn` / `sumAvailableKrwByStatusIn` JPQL 집계 쿼리 추가 |
| `LiveTradingService.java` | `createSession`: 활성 세션 initialCapital 합 + 신규 > `PortfolioManager.totalCapital` 이면 `SessionStateException`. `startSession`: STOPPED→RUNNING 전환 시 동일 검증 (CREATED 중복 카운트 방지). `executeSessionBuy`: 전 세션 availableKrw 합 − 매수금 < 0 이면 BUY 차단. `PortfolioManager` 를 constructor DI 로 추가. |
| `PortfolioSyncService.java` | `detectCapitalDrift()` 추가 — 동기화 시 세션 initialCapital 합 > 계좌 × 1.05 이면 WARN 로그. `LiveTradingSessionRepository` DI 추가. |
| `SessionCapitalGuardTest.java` (신규) | 4건 — 초과 배정 거부, 잔고 내 동일코인 다중 세션 허용, `sumInitialCapital` 집계 정확성, `sumAvailableKrw` 집계 정확성 |

---

### ✅ 완료 (2026-04-16) — Tier 2 §7: LiveTradingService 동시성 race 차단

**목표**: 동일 `LiveTradingSession` 을 동시에 read-modify-write 하는 4개 경로(executeSessionBuy / reconcileOrphanBuyPositions / finalizeSellPosition / updateSessionUnrealizedPnl) 가 last-write-wins 덮어쓰기로 `availableKrw` 를 드리프트시키던 문제를 JPA `@Version` 낙관적 락 + 재시도 헬퍼로 차단.

| 파일 | 변경 내용 |
|------|-----------|
| `LiveTradingSessionEntity.java` | `@Version` 필드(`version`) 추가 |
| `V43__add_version_to_live_trading_session.sql` | 신규 Flyway 마이그레이션 — `version BIGINT NOT NULL DEFAULT 0` |
| `schema-h2.sql` | H2 테스트 스키마에 `version` 컬럼 동기화 + 기존 drift(`backtest_run.wf_result_json`) 함께 수복 |
| `SessionBalanceUpdater.java` (신규) | REQUIRES_NEW `TransactionTemplate` + `ConcurrencyFailureException`(낙관적/비관적 모두 포함) 12회 재시도 + jitter 지수 backoff. self-invocation 우회를 위해 `@Transactional` 대신 템플릿 사용 |
| `LiveTradingService.java` | 6개 read-modify-write 사이트(매수 차감, 매수취소 복원, unrealized PnL 재계산, reconcile orphan 2곳, finalize sell 원금+손익 입금) 를 `balanceUpdater.apply(...)` 람다로 리팩터링 |
| `SessionBalanceUpdaterTest.java` (신규) | 10-thread 동시 increment 가 모두 누적되는지 회귀 검증 (IntegrationTestBase 상속 + `@Transactional` 미사용 + 수동 정리) |
| `LiveTradingReliabilityTest.java` | 클래스 레벨 `@Transactional` 제거(REQUIRES_NEW 헬퍼가 커밋된 세션을 봐야 함) — `@AfterEach` 수동 정리로 대체. `closeIfOpen` 는 `TransactionTemplate` 으로 감싸 `@Modifying` 쿼리 요구사항 충족 |

**회귀 테스트**: `:web-api:test` 전체 그린.

---

### ✅ 완료 (2026-04-15) — 리팩토링: 효율성·코드 품질 개선 (04-11 ~ 04-14 변경분)

#### 1. LogAnalyzerService — buildSignalStats() 단일 순회 통합

| 파일 | 변경 내용 |
|------|-----------|
| `LogAnalyzerService.java` | `buildSignalStats()`: buy/sell/hold/executed/blocked 각각 별도 스트림 6회 순회 → 단일 for 루프 1회로 통합. 전략별 그룹 집계도 동일 루프에서 처리 |

---

#### 2. SignalQualityService — evaluateLoop() 배치 저장

| 파일 | 변경 내용 |
|------|-----------|
| `SignalQualityService.java` | `evaluateLoop()`: 평가 완료 엔티티를 `toSave` 리스트에 수집 후 배치당 `saveAll()` 1회 호출. 기존: 건당 `save()` (MAX_PER_RUN=500건 기준 500회 → 배치 수회로 감소) |

---

#### 3. BacktestJobService — Job 생성 중복 제거 + Walk-Forward 배치 저장 개선

| 파일 | 변경 내용 |
|------|-----------|
| `BacktestJobService.java` | `submitSingleJob()` / `submitMultiStrategyJob()` / `submitBulkJob()` / `submitBatchJob()` / `submitWalkForwardBatchJob()` — 5곳의 `BacktestJobEntity.builder()` 중복 → `createJob()` 헬퍼 메서드 1개로 통합 |
| `BacktestJobService.java` | `executeWalkForwardBatchAsync()`: 매 조합마다 `jobRepository.save()` → 10개마다 1회 저장으로 변경. 최종 `completedChunks` 는 루프 종료 후 저장 |

---

#### 4. TradingConstants — FEE_THRESHOLD 공용 상수 통합

| 파일 | 변경 내용 |
|------|-----------|
| `TradingConstants.java` (신규) | `com.cryptoautotrader.api.util` 패키지. `FEE_THRESHOLD = 0.10` (업비트 왕복 수수료 임계값) 공용 상수 정의 |
| `StrategyWeightOptimizer.java` | 로컬 `FEE_THRESHOLD` 선언 → `TradingConstants.FEE_THRESHOLD` 참조로 교체 |
| `LogAnalyzerService.java` | 동일하게 `TradingConstants.FEE_THRESHOLD` 참조로 교체 |

---

### ✅ 완료 (2026-04-14) — 모의투자 확장 + 야간 자동 스케줄러 + 리스크 지표 수정

#### 1. 모의투자 최대 세션 수 확장 (10 → 20)

| 파일 | 변경 내용 |
|------|-----------|
| `PaperTradingService.java` | `MAX_CONCURRENT_SESSIONS = 10` → `20` |

> 세션당 메모리·DB 부하가 경미하므로 무리 없음. 운영 코인(BTC·ETH·SOL·XRP·DOGE) + 신규 DOGE·XRP V2 병행 운영 대비.

---

#### 2. 야간 자동 스케줄러 — DB 기반 동적 관리 + 전용 웹 UI

**신규 파일**

| 파일 | 역할 |
|------|------|
| `V41__create_nightly_scheduler_config.sql` | `nightly_scheduler_config` 싱글톤 테이블 (id=1). 활성화 여부, 실행 시각(KST), 타임프레임, 기간, 코인·전략 목록 등 저장 |
| `V42__fix_scheduler_config_column_types.sql` | V41에서 SMALLINT로 생성된 run_hour/run_minute/window_count를 INTEGER로 변경 (Hibernate 타입 일치) |
| `NightlySchedulerConfigEntity.java` | JPA 엔티티. `coinPairList()` / `strategyTypeList()` 콤마 분리 헬퍼 포함 |
| `NightlySchedulerConfigRepository.java` | `JpaRepository<NightlySchedulerConfigEntity, Long>` |
| `NightlySchedulerConfigDto.java` | 요청/응답 DTO. 읽기 전용 필드: lastTriggeredAt, nextRunAt, lastBatchJobId, lastWfJobId |
| `NightlySchedulerConfigService.java` | `getConfig()` / `updateConfig()` / `triggerNow()` / `checkAndRun()`. KST 시각 비교 + 23시간 중복 실행 방지 가드. `calcNextRun()` 다음 실행 예정 시각 계산 |
| `NightlySchedulerController.java` | `GET/PUT /api/v1/scheduler/nightly`, `POST /api/v1/scheduler/nightly/trigger` |
| `WalkForwardBatchRequest.java` | Walk-Forward 배치 DTO |
| `crypto-trader-frontend/src/app/backtest/scheduler/page.tsx` | 스케줄러 전용 관리 페이지 |

**수정 파일**

| 파일 | 변경 내용 |
|------|-----------|
| `BacktestAutoSchedulerService.java` | 기존 하드코딩 `@Scheduled(cron)` → `NightlySchedulerConfigService.checkAndRun()` 1분 tick 위임 |
| `BacktestJobService.java` | `submitWalkForwardBatchJob()` + `@Async executeWalkForwardBatchAsync()` 추가 |
| `BacktestController.java` | `POST /api/v1/backtest/walk-forward-batch-async` 엔드포인트 추가 |
| `types.ts` | `NightlySchedulerConfig` 인터페이스 추가 |
| `api.ts` | `schedulerApi` (getConfig / updateConfig / triggerNow) 추가 |
| `Sidebar.tsx` | '전략관리' 그룹에 '자동 스케줄' 메뉴 추가 (`/backtest/scheduler`) |

**스케줄러 UI 기능**:
- ON/OFF 토글, KST 실행 시각 선택 (시:분)
- 타임프레임 드롭다운 (M1/M5/M15/M30/H1/H4/D1)
- 분석 기간 날짜 피커 (시작~종료)
- 코인 선택 (BTC·ETH·SOL·XRP·DOGE·ADA·DOT·AVAX 프리셋 + 직접 입력)
- 전략 선택 (복합 그룹 + 단일 그룹 칩)
- 고급 설정 (초기자금·슬리피지·수수료)
- 예상 작업량: N코인 × M전략 = K 조합 표시
- "지금 실행" 수동 트리거 (확인 다이얼로그 포함)

---

#### 3. 모의투자 리스크 지표 (MDD·Sharpe·Sortino) 계산 수정

| 파일 | 변경 내용 |
|------|-----------|
| `PaperTradingService.java` | `RiskMetrics` 내부 클래스 추가 (mddPct, sharpeRatio, sortinoRatio, calmarRatio, winLossRatio, avgProfitPct, avgLossPct, maxConsecutiveLoss). `computeRiskMetrics(closedPositions, initialCapital)` 메서드 추가 — 청산 포지션의 누적 손익 곡선으로 MDD, 수익률 시계열로 Sharpe/Sortino 계산 (최소 3거래). `getOverallPerformance()`에서 세션별 호출하여 대시보드에 반영 |

**원인**: 기존 코드는 리스크 지표 필드가 선언되어 있었으나 실제 계산 로직이 없어 항상 0.0 반환.

---

### ✅ 완료 (2026-04-13) — P2-1: 5코인 × 4전략 3년 배치 백테스트

**실행 조건**: KRW 마켓 H1, 2023-01-01 ~ 2025-12-31, 초기자금 1000만원, 슬리피지 0.1%, 수수료 0.05%

**핵심 발견**:

| 발견 | 내용 |
|------|------|
| SOL 전략 전환 권고 | V2(+131.1% MDD -12.0%) > BREAKOUT(+64.9% MDD -19.2%) — 수익률 2배·MDD 낮음 |
| XRP V2 확정 | V2(+49.9%) > V1(+36.5%) — 수익률·Sharpe 모두 우위 |
| DOGE V2 주목 | +134.4% 전 코인 최고 수익률. MDD -29.2% 주의 필요 |
| BTC BREAKOUT 독주 | +104.2% Sharpe 6.41. MOMENTUM 계열 전부 +1~13% 수준 |
| ETH MOMENTUM 유지 | +53.6%. V1/V2/BREAKOUT 모두 열위 |

**COMPOSITE_MOMENTUM_ICHIMOKU_V2 전체 결과**:

| 코인 | 수익률 | MDD | Sharpe | 거래수 |
|------|--------|-----|--------|--------|
| SOL | +131.07% | -11.95% | 2.72 | 167 |
| DOGE | +134.42% | -29.15% | 2.49 | 179 |
| XRP | +49.92% | -25.32% | 1.81 | 129 |
| ETH | +37.31% | -22.75% | 1.33 | 170 |
| BTC | +13.01% | -12.12% | 0.58 | 158 |

**후속 작업**: SOL Walk-Forward 검증 → 실전 전환 / DOGE 소액 투입 검토 / XRP V1→V2 전환

---

### ✅ 완료 (2026-04-13) — P1-0 방향A: StrategyWeightOptimizer Composite 단위 전환

#### 문제

`StrategyWeightOptimizer`의 `DEFAULTS`가 `SUPERTREND`, `EMA_CROSS` 등 컴포넌트 전략명을 기준으로 했으나,
`strategy_log.strategy_name`에는 `COMPOSITE_BREAKOUT`, `COMPOSITE_MOMENTUM` 등 Composite 전략명이 기록된다.
→ 항상 0건 매칭 → 가중치가 기본값에서 벗어나지 않는 구조적 버그.

#### 수정 내용

| 파일 | 변경 내용 |
|------|-----------|
| `StrategyWeightOptimizer.java` | `DEFAULTS` 맵을 Composite 전략명(`COMPOSITE_BREAKOUT`, `COMPOSITE_MOMENTUM`) 기준으로 전환. Javadoc에 방향A 이력 추가 |
| `StrategySelector.java` | `trend()`, `range()`, `volatility()` 3개 메서드 전략명을 동일하게 Composite 기준으로 통일 |

**기본 가중치** (3년 백테스트 근거):
- TREND: COMPOSITE_BREAKOUT 0.65 / COMPOSITE_MOMENTUM 0.35
- RANGE: COMPOSITE_MOMENTUM 0.60 / COMPOSITE_BREAKOUT 0.40
- VOLATILITY: COMPOSITE_BREAKOUT 0.70 / COMPOSITE_MOMENTUM 0.30

**보호 장치**: 레짐별 최소 20건 / 전략별 최소 5건 미만이면 기본값 유지. 최소 가중치 0.05. 스무딩 70/30.

#### MACD_STOCH_BB 영구 비활성화

3년 H1 백테스트: BTC -2.32% 17건, XRP -2.02% 3건, ETH -0.33% 1건.
MACD>0(상승추세) + StochRSI<20(극단 과매도) 동시 충족 구조적 불가 → 영구 비활성화.
`BLOCKED_LIVE_STRATEGIES`에 `MACD_STOCH_BB` 추가. 서브필터 편입도 부적합 판정.

---

### ✅ 완료 (2026-04-13) — AI 분석 고도화 + 거래소 DOWN 자동 재시작

#### AI 분석 데이터 고도화 (세션·코인·가격 컨텍스트 추가)

| 파일 | 변경 내용 |
|------|-----------|
| `AnalysisReport.java` | `ActiveSessionInfo`(세션ID·전략·코인·타임프레임·수익률), `CoinPositionStat`(코인별 승률·손익) 내부 클래스 추가. `activeSessions`, `coinPriceChanges`, `coinPositionStats` 필드 추가 |
| `LogAnalyzerService.java` | `LiveTradingSessionRepository` 주입. `buildRunningSessions()`, `buildActiveSessions()`, `buildCoinPriceChanges()`(RUNNING 코인 전체 + BTC/ETH 기본 보장), `buildCoinPositionStats()` 추가. `buildPriceContext()` 인라인화 |
| `ReportComposer.java` | LLM 요약·분석 프롬프트에 `[실행 중 세션]`, `[코인별 포지션 성과]` 컨텍스트 추가. Notion `buildActiveSessionsSection()` 추가 |
| `MorningBriefingComposer.java` | `buildTradingUserPrompt()`에 `[실행 중 세션 N개]`(전략·코인·수익률·12h 가격변화), `[코인별 포지션 성과]` 섹션 추가 |

**효과**: LLM이 "어떤 전략이 어느 코인에서 현재 얼마나 벌고 있는지"와 "해당 코인 시세 흐름"을 함께 참고해 분석 품질 향상.

#### 거래소 DOWN 비상 정지 후 자동 재시작

| 파일 | 변경 내용 |
|------|-----------|
| `ExchangeRecoveredEvent.java` | 신규. 거래소 DOWN→UP 복구 시 발행되는 `ApplicationEvent` |
| `ExchangeHealthMonitor.java` | `updateStatus()`에 UP+이전DOWN 조합 감지 → `ExchangeRecoveredEvent` 발행 |
| `LiveTradingService.java` | `exchangeStoppedSessionIds`(ConcurrentHashSet) 추가. `onExchangeDown()`: DOWN 시 RUNNING 세션 ID 저장 + Discord ALERT 전송. `onExchangeRecovered()`: EMERGENCY_STOPPED → STOPPED 전환 후 `startSession()` 재호출, Telegram+Discord ALERT 전송. `DiscordWebhookClient` optional 주입. `sendDiscordEmergencyStopAlert()`, `sendDiscordRecoveryAlert()` 헬퍼 추가 |
| `TelegramNotificationService.java` | `notifyExchangeRecovered(restarted, failed)` 추가 — 재시작 완료/실패 목록 포함 |

**동작 흐름**:
1. Upbit 3회 연속 실패 → `ExchangeDownEvent` → `onExchangeDown()` → RUNNING 세션 ID 기억 + 전체 비상 정지 + Telegram/Discord ALERT(사유·정지 세션 목록)
2. 다음 30초 헬스체크에서 UP 복구 → `ExchangeRecoveredEvent` → `onExchangeRecovered()` → 세션 재시작 + Telegram/Discord ALERT(재시작 완료/실패 목록)

---

### ✅ 완료 (2026-04-13) — 신규 전략 + Upbit 동적 코인 목록 + 보안 강화

#### COMPOSITE_MOMENTUM_ICHIMOKU_V2 신규 전략

| 파일 | 변경 내용 |
|------|-----------|
| `CompositePresetRegistrar.java` | `COMPOSITE_MOMENTUM_ICHIMOKU_V2` 등록: MACD×0.5 + SUPERTREND×0.3 + GRID×0.2, EMA 방향 필터 + Ichimoku 구름 필터. `registerStateful` (GRID stateful) |
| `StrategyController.java` | `isCompositeStrategy()` / `isStrategyImplemented()` / `getDescription()` 3개 switch에 V2 추가 |
| `types.ts` | `StrategyType`에 `COMPOSITE_MOMENTUM_ICHIMOKU` / `COMPOSITE_MOMENTUM_ICHIMOKU_V2` / `COMPOSITE_BREAKOUT_ICHIMOKU` 추가 |
| `CompositePresetRegistrar.java` | `COMPOSITE_BREAKOUT_ICHIMOKU` 설명에 ⚠ 백테스트 결과 무의미 주석 추가 (ADX 필터가 Ichimoku 조건 선점) |

**설계 근거**: V1(COMPOSITE_MOMENTUM_ICHIMOKU)은 MACD(추세추종)와 VWAP(역추세)가 ADX 25~35 구간에서 동시 활성화 → buyScore/sellScore 둘 다 0.4 초과 → HOLD 남발. VWAP를 SUPERTREND(추세추종)로 교체하여 세 전략 방향 일치.

**Walk-Forward 비교 결과**:
- ETH: V1(53% 경고) vs V2(84.4% 경고) → **ETH는 V1 유지** (VWAP 역추세가 ETH 변동성 안전망 역할)
- XRP: V1(0.0% 통과) vs V2(62.0% 경고) → 인샘플 수익률 자체가 0 근처. 샘플 부족. **4~6주 실전 후 재판단**

#### Upbit 동적 코인 목록

| 파일 | 변경 내용 |
|------|-----------|
| `UpbitRestClient.java` | `getMarkets()` 추가: `GET /v1/market/all` → `List<Map<String, Object>>` 반환 |
| `UpbitCandleCollector.java` | `getSupportedCoins()` 재구현: KRW 마켓 전체 조회 → 24h 거래대금 상위 20개 동적 반환. API 실패 시 10종 하드코딩 폴백 |
| `BacktestForm.tsx` | 코인 선택 `<select>` → 검색 가능한 콤보박스 (클릭 외부 닫힘, 빈 결과 처리, `useRef` 활용) |

#### 보안 강화 (P3 전체 완료)

| 파일 | 변경 내용 |
|------|-----------|
| `application.yml` | `api.auth.token` 기본값 완전 제거. `redis.data.password: ${REDIS_PASSWORD:}` 추가. `cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}` 추가 |
| `ApiTokenAuthFilter.java` | 토큰 공백·미설정 시 `IllegalStateException` throw → 서버 시작 자체 차단 |
| `SecurityConfig.java` | `prod` 프로파일 시 `/swagger-ui/**`, `/v3/api-docs/**` 차단. CORS origin `*` 하드코딩 → `cors.allowed-origins` 프로퍼티 콤마 분리 처리. `Environment` 주입으로 활성 프로파일 판별 |
| `docker-compose.prod.yml` | Redis `--requirepass ${REDIS_PASSWORD}` + healthcheck `-a ${REDIS_PASSWORD}`. backend `REDIS_PASSWORD` 환경변수 추가. `db-backup` 컨테이너 추가 (24h 주기 pg_dump, 7일 보관). Grafana `${GRAFANA_PASSWORD:-admin}` → `${GRAFANA_PASSWORD}` |
| `application-local.yml` | 로컬 개발용 `api.auth.token: dev-token-change-me-in-production` 추가 |
| `.env.example` | `GRAFANA_PASSWORD`, `DISCORD_BOT_TOKEN`, 프론트엔드 인증 변수 문서화 |

**로컬 실행 명령어 변경**: `./gradlew :web-api:bootRun --args='--spring.profiles.active=local'`

---

### ✅ 완료 (2026-04-13) — LLM 호출 로그 저장 + 토큰 관리

| 파일 | 변경 내용 |
|------|-----------|
| `V40__llm_call_log.sql` (신규) | `llm_call_log` 테이블: task/provider/model/프롬프트 전문/응답 전문/토큰/소요시간/성공여부 |
| `LlmCallLogEntity.java` / `LlmCallLogRepository.java` | 기간별 호출 수, 토큰 합계, task별/provider별 집계 쿼리 포함 |
| `LlmTaskRouter.java` | `saveLog()` — 모든 LLM 호출 완료 후 자동 저장 (저장 실패 시 원래 응답 영향 없음) |
| `LogController.java` | `GET /api/v1/admin/llm/logs` (페이지네이션·필터), `/{id}` (전문), `/stats` (토큰 통계) |
| `/logs/llm` 프론트엔드 | 통계 카드 + task별/provider별 토큰 breakdown + 로그 테이블 + 상세 모달 |
| `Sidebar.tsx` | "LLM 호출 로그" 메뉴 추가. `excludePrefix` 다중값(`|`) 처리 지원 |

---

### ✅ 완료 (2026-04-11) — 신호 품질 분석 고도화 + 전략 가중치 자동 조정 인프라

| 항목 | 내용 |
|------|------|
| 수수료 공제 실질 적중률 | 승/패 판정 기준을 업비트 왕복 수수료(0.10%) 초과로 변경. HOLD 신호 제외 |
| 차단 신호 사후 성과 비교 | `wasExecuted=false` 신호의 4h/24h 수익률 집계. 차단 사유별 필터 효과 판정(FILTER_HURTING/HELPING/NEUTRAL) |
| 시간대별 신호 품질 | KST 0~23시 히트맵. 4h 적중률 색상, 상위/하위 3시간대 요약 |
| `SignalQualityService` 병목 해소 | BATCH_SIZE 20→100, MAX_PER_RUN=500, 시작 시 `@Async` Catchup으로 미평가 신호 전량 처리 |
| 전략 가중치 자동 조정 인프라 | `WeightOverrideStore`(core-engine) + `StrategySelector` 동적 가중치 읽기 + `StrategyWeightOptimizer`(daily 06:00). 70% 계산값 + 30% 기본값 스무딩. 최소 가중치 5% 보장 |
| 가중치 API | `GET /api/v1/logs/strategy-weights`, `POST /api/v1/logs/strategy-weights/optimize` |
| 신호 품질 페이지 개선 | 가중치 패널(레짐별 전략 바 + ±%p 변화량) + 히트맵 + 차단 신호 비교 섹션 |

> ⚠ **가중치 최적화 구조적 한계**: `StrategyWeightOptimizer` 조정 대상이 컴포넌트 전략명(`SUPERTREND` 등)인데, `strategy_log`에는 복합 전략명(`COMPOSITE_MOMENTUM` 등)만 기록됨 → 가중치가 기본값에서 벗어나지 않음. P1-0에서 해결 예정.

---

### ✅ 완료 (2026-04-10) — AI 파이프라인 버그 수정 + 리팩토링

**버그 수정**

| 파일 | 변경 내용 |
|------|-----------|
| `MorningBriefingComposer.java` | 뉴스 카테고리 대소문자 불일치 (`"CRYPTO"` → `"crypto"`) — 항상 빈 결과 반환 수정 |
| `api.ts` | `adminNewsApi.getCache()` 위치 인수 → 객체 파라미터 수정 |
| `NewsAggregatorService.java` | `news_item_cache` 7일 이상 자동 삭제 스케줄러 추가 (매일 03:00 KST) |
| `ReportComposer.java` | 외부 HTTP 호출 중 트랜잭션 점유 — `@Transactional` 제거, `saveInitial()`/`saveFinal()` `REQUIRES_NEW` 분리 |
| `DiscordWebhookClient.java` | embed 글자수 초과 방지 — `truncate()` 적용 (description 4096자, field 1024자) |

**리팩토링**

| 파일 | 변경 내용 |
|------|-----------|
| `LogAnalyzerService.java` | 5000건 메모리 로드 → DB 기간 필터 쿼리로 개선. `buildSignalStats()` / `buildPositionStats()` / `buildRegimeStats()` 분리 |
| `ReportComposer.java` | `buildBlocks()` 93줄 → 섹션별 메서드 7개 분리 |
| `MorningBriefingComposer.java` | CRYPTO/ECONOMY 뉴스 채널 중복 코드 → `sendNewsChannel()` 공통화 |
| `DiscordWebhookClient.java` | `STATUS_SUCCESS/FAILED/PENDING` 상수 추가. `truncate()` public static 변경 |

---

### ✅ 완료 (2026-04-09) — AI 보고서 · 뉴스 파이프라인

| Phase | 내용 |
|-------|------|
| Phase 1 — LLM 추상화 | `LlmProvider` 인터페이스 + OpenAI/Ollama/Claude/Mock 구현체. `LlmTaskRouter` — task별 provider 라우팅, DB 설정 런타임 반영. 관리 API + 연결 테스트 API |
| Phase 2 — 뉴스 소스 | `NewsSource` 인터페이스 + `CryptoPanicSource` / `RssNewsSource` / `CoinGeckoTrendingSource`. 15분 스케줄러 수집. 관리 API (CRUD + 수동 fetch) |
| Phase 3 — Notion 보고서 | `LogAnalyzerService` 12h 집계 + `NotionApiClient` 블록 빌더 + `ReportComposer` LLM 요약 → Notion 페이지. cron 0시/12시(KST) |
| Phase 4 — Discord 브리핑 | `DiscordWebhookClient` 채널별 Webhook. 4채널(TRADING_REPORT/CRYPTO_NEWS/ECONOMY_NEWS/ALERT). `MorningBriefingScheduler` cron 07:00(KST) |
| Phase 5 — 관리 UI | `/admin/llm-config`, `/admin/news-sources`, `/admin/reports`, `/admin/discord` 4개 페이지 |

DB migrations: V34(llm), V35(news), V36(notion), V37(discord)

---

### ✅ 완료 (2026-04-09) — 운영 분석 강화

| 항목 | 내용 |
|------|------|
| 실전매매 리스크 지표 | `MetricsCalculator`(Sharpe·MDD·Sortino·Calmar)를 `/api/v1/trading/performance`에 연동 |
| 신호 품질 로깅 | `strategy_log`에 신호 발생가·실행 여부·차단 사유 기록. 30분 스케줄러로 4h/24h 사후 수익률 평가 |
| 레짐별 성과 분리 | `position.market_regime` 필드 추가. 성과 API에서 TREND/RANGE/VOLATILITY별 승률·손익 집계 |
| 레짐 전환 이력 | `regime_change_log` 테이블(V33) + `GET /api/v1/logs/regime-history`. 성과 페이지에 타임라인 UI |

---

### ✅ 완료 (2026-04) — 실전매매 시스템 신뢰도 검토 (P0 6단계)

| 단계 | 내용 |
|------|------|
| 1단계 — 주문 상태 머신 | FAILED 주문→포지션 고착 수정: `reconcileOrphanBuyPositions` 30초 스케줄러. 리스크 카운팅 오류: size>0만 카운팅. SELL 리스크 체크 스킵. CANCELLED 부분체결 KRW 복원 (`executedFunds` 필드 V28) |
| 2단계 — 잔고 정합성 | `availableKrw` Race Condition: `investedKrw` 필드(V29) + reconcile 폴백 로직. `stopSession()` KRW 복원 누락: `cancelSessionActiveOrders()` 선행 호출 추가 |
| 3단계 — PnL 계산 | 시장가 매수 평균 단가 수수료 내포 확인. `finalizeSellPosition()` 수수료 반영 검증. `handleSellFill()` 비세션 경로 매도 수수료 0.05% 반영 |
| 4단계 — 스케줄러 동시성 | `fixedDelay` 전수 확인(6개). `closeIfOpen()` 원자적 처리(WHERE status='OPEN' UPDATE)로 KRW 이중 복원 방지 |
| 5단계 — 프론트엔드 | size=0 고스트 포지션 UI 필터링. Upbit 실계좌 잔고 비교 표시(30초 polling). 주문 FAILED 사유 인라인 표시 |
| 6단계 — 통합 테스트 | E2E 5건: FAILED 매수 KRW 복원, 리스크 카운팅 정확성, maxPositions 차단/허용, closeIfOpen 멱등성, 이중 복원 방지 |

---

### ✅ 완료 (2026-04-06) — 백테스트 비동기 전환 + 트랜잭션 레이스 컨디션 수정

| 파일 | 변경 내용 |
|------|-----------|
| `crypto-trader-frontend/src/lib/api.ts` | `backtestApi.run` → `/run-async`, `backtestApi.runMultiStrategy` → `/multi-strategy-async` 호출로 변경. 타임아웃 제거 |
| `crypto-trader-frontend/src/components/backtest/BacktestForm.tsx` | 비동기 응답(`jobId`) 처리로 변경 — 즉시 목록 페이지로 이동, 버튼 텍스트 "백그라운드 제출 중..." |
| `BacktestJobService.java` | `submitSingleJob`, `submitMultiStrategyJob`, `submitBulkJob` 에서 `@Transactional` 제거 |

**문제 원인**:
- 프론트엔드가 동기 `/run` 엔드포인트를 5분 타임아웃으로 호출 → 장시간 백테스트 시 타임아웃 오류 발생, 텔레그램 알림 미전송
- `@Transactional` submit 메서드 내에서 `@Async` 메서드 호출 시 트랜잭션 커밋 전에 비동기 스레드가 DB 조회 → `NoSuchElementException` 발생

**수정 내용**:
- 프론트엔드: 비동기 엔드포인트(`/run-async`, `/multi-strategy-async`) 호출로 변경 → 즉시 `jobId` 반환, 완료 시 텔레그램 알림 수신
- 백엔드: submit 메서드 `@Transactional` 제거 → `save()` 즉시 커밋 후 비동기 스레드 실행으로 레이스 컨디션 해소

---

### ✅ 완료 (2026-03-25) — COMPOSITE 개선: 전략 교체 + ADX 게이트

| 파일 | 변경 내용 |
|------|-----------|
| `StrategySelector.java` | RANGE: `RSI(0.4)` → `VWAP(0.4)` 교체 (RSI 일관 마이너스) |
| `StrategySelector.java` | VOLATILITY: `STOCHASTIC_RSI(0.4)` → `VOLUME_DELTA(0.4)` 교체 (StochRSI BLOCKED) |
| `RegimeAdaptiveStrategy.java` | ADX 게이트 추가 — BUY 신호 발생 시 `ADX(14) < 20`이면 HOLD 반환 |

**문제 원인**: COMPOSITE의 고승률(-14% BTC / -21% ETH) 패턴 분석
- VOLATILITY 레짐에 BLOCKED 전략(STOCHASTIC_RSI, -70%)이 40% 가중치로 작동 중 → 큰 손실 원인
- RANGE 레짐에서 RSI(일관 마이너스) 사용 → 횡보장 진입 손실
- ADX < 20(횡보장)에서도 BUY 진입 허용 → 가짜 신호 다수

**ADX 게이트 원리 (DMI 원칙 적용)**:
- ADX > 20: 추세 존재 확인 → 정상 진입
- ADX < 20: 횡보장 → BUY 차단, SELL은 허용 (포지션 정리는 가능)
- `IndicatorUtils.adx(candles, 14)` 사용 (기존 구현 재사용)

---

### ✅ 완료 (2026-03-25) — COMPOSITE_BTC V2 재구성 (Grid+Bollinger → MACD+VWAP+Grid)

| 파일 | 변경 내용 |
|------|-----------|
| `CompositePresetRegistrar.java` | COMPOSITE_BTC 구성 변경: `Grid×0.6 + Bollinger×0.4` → `MACD×0.5 + VWAP×0.3 + Grid×0.2`. import BollingerStrategy 제거, MacdStrategy·VwapStrategy 추가 |
| `StrategyController.java` | COMPOSITE_BTC 설명 업데이트 |

**재구성 근거**: KRW-BTC H1 백테스트 결과 분석
- VWAP / SUPERTREND / RSI_DIVERGENCE 신규 BTC 백테스트 실시
- VWAP: 2024 +53.38% / 2025 -6.97% → 평균 +23.2%, 승률 64.8%, MDD -15~-19% (채택)
- SUPERTREND: 2024 +68.97% / 2025 -39.45% → 불안정, MDD -45% (기각)
- RSI: 일관 마이너스 (기각)
- 기존 Grid(+8.4%), Bollinger(+3.2%) 대비 MACD 최적화(+151.9%) 압도적

**백테스트 검증 결과** (KRW-BTC H1, 24~25년): **+58.83%**, 승률 37.5%, MDD -25.62%
- 구 Grid+Bollinger 추정치 ~+6% 대비 압도적 개선
- 승률 낮음은 MACD 추세 전략 특성 (진입 빈도↓, 홀딩 기간↑)

---

### ✅ 완료 (2026-03-25) — COMPOSITE_ETH_VD 추가 및 백테스트 채택

| 파일 | 변경 내용 |
|------|-----------|
| `CompositePresetRegistrar.java` | `COMPOSITE_ETH_VD` 등록: `ATR×0.4 + OB×0.3 + VD×0.2 + EMA×0.1` |
| `BacktestService.java` | `compositeEthVdBt()` 헬퍼 추가 — 백테스트 전용 가중치 분리 |
| `StrategyController.java` | COMPOSITE_ETH_VD 설명·구현 여부 추가 |

**백테스트 비교 결과** (KRW-ETH H1, 2회):

| 전략 | 결과 1 | 결과 2 | 평균 | MDD |
|------|--------|--------|------|-----|
| COMPOSITE_ETH_VD | +92.54% | +48.10% | **+70.3%** | -12~-17% |
| COMPOSITE_ETH | +47.05% | +50.37% | +48.7% | -26~-28% |
| VOLUME_DELTA 단독 | +94.04% | -22.11% | +36% | -32~-54% |

**결론**: VD 편입 시 MDD 대폭 개선. VOLUME_DELTA 단독은 불안정하여 복합 편입 전략으로 채택.

---

### ✅ 완료 (2026-03-24) — MACD coinDefaults 코인별 분리

| 파일 | 변경 내용 |
|------|-----------|
| `MacdStrategy.java` | `coinDefaults(String coinPair)` 정적 메서드 추가. BTC → (14,22,9), ETH → (10,26,9), 그외 → (12,24,9) 자동 선택 |
| `BacktestEngine.java` | `coinPair` 파라미터 주입 → MacdStrategy 초기화 시 coinDefaults 적용 |
| `LiveTradingService.java` | 세션 시작 시 coinPair 전달 → MACD 코인별 최적 파라미터 자동 적용 |

---

### ✅ 완료 (2026-03-24) — MACD 파라미터 그리드 서치 백테스트

| 파일 | 변경 내용 |
|------|-----------|
| `MacdGridSearchRequest.java` | 그리드 서치 전용 DTO 신규 (fastMin/Max, slowMin/Max, signalPeriod) |
| `BacktestService.java` | `runMacdGridSearch()` 추가 — O(n) 고속 버전 (primitive double, MACD 시리즈 1회 계산) |
| `BacktestController.java` | `POST /api/v1/backtest/macd-grid-search` 엔드포인트 추가 |
| `MacdConfig.java` | 기본값 변경: (12,26,9) → (14,22,9) (BTC 그리드 서치 최적값) |

**그리드 서치 결과 (2024~2025 H1, 176조합)**:

| 코인 | 최적 파라미터 | 수익률 | Sharpe | MDD | 기본값 대비 |
|------|--------------|--------|--------|-----|------------|
| KRW-BTC | fast=14, slow=22 | +151.9% | 1.68 | 18.5% | +63.8%p |
| KRW-ETH | fast=10, slow=26 | +216.0% | 1.61 | 49.5% | +41.4%p |

> 성능 이슈: BacktestEngine이 O(n²)이라 그리드 서치에 부적합 → 경량 O(n) 루프로 직접 구현

---

### ✅ 완료 (2026-03-24) — 급등/급락 실시간 감지 (손절 가속 + 트레일링 스탑)

| 파일 | 변경 내용 |
|------|-----------|
| `LiveTradingService.java` | `PriceSnapshot` 내부 클래스 추가. `priceHistory` / `spikeCheckLastMs` 필드 추가. 상수 6개 추가 (SPIKE_WINDOW_MS, SPIKE_DOWN/UP_THRESHOLD, SL_TIGHTEN/TRAILING_STOP_MARGIN 등). `onRealtimePriceEvent()` 재작성. `updatePriceHistory()` / `calcSpikeRate()` 헬퍼 추가. import `ArrayDeque`, `Deque` 추가 |

**동작 흐름**:
1. WebSocket ticker 수신마다 `priceHistory`에 (timestamp, price) 저장 (throttle 전, 항상 기록)
2. 30초 윈도우 내 변동률 계산 → 급락(-1.5%) / 급등(+2.0%) 여부 판단
3. **급등락 감지 시**: 1초 throttle (평상시 5초) + 세션 루프 진입
4. **기존 손절 체크 강화**: `stopLossPrice` 절대가 우선 → 없으면 `stopLossPct %` (기존 로직이 `stopLossPrice` 를 무시하던 버그 함께 수정)
5. **급락 + 손실 중 포지션**: SL을 `현재가 × 0.997`로 조임 (단방향 ratchet — 한번 조이면 완화 안 됨)
6. **급등 + 수익 중 포지션**: TP를 `현재가 × 0.995`로 트레일링 업데이트 (단방향 ratchet — 고점 추적)

**설계 결정**:
- spike threshold는 감지 트리거일 뿐, 실제 손절/익절 기준은 세션 설정(`stopLossPct`, `takeProfitPrice`) 유지
- SL 조임은 손실 중 포지션에만 적용 (수익 중엔 불필요)
- TP 트레일링은 수익 중 포지션에만 적용 (손실 중엔 의미 없음)
- `priceHistory` 는 전략과 무관하게 모든 RUNNING 세션에 공통 적용

---

### ✅ 완료 (2026-03-24) — WebSocket 연결 상태 표시 버그 수정

| 파일 | 변경 내용 |
|------|-----------|
| `UpbitWebSocketClient.java` | `connectionStateListener` (`Consumer<Boolean>`) 필드 추가. `setConnectionStateListener()` 공개 메서드 추가. `notifyConnectionState(boolean)` 내부 헬퍼 추가. `onOpen()` → `notifyConnectionState(true)` 호출. `onClose()` / `onError()` / `exceptionally()` / `disconnectInternal()` → `notifyConnectionState(false)` 호출 |
| `LiveTradingService.java` | `reconcileOnStartup()` 내 WebSocket 리스너 등록 블록에 `wsClient.setConnectionStateListener(exchangeHealthMonitor::setWebSocketConnected)` 한 줄 추가 |

**수정 전 동작**: `ExchangeHealthMonitor.webSocketConnected` 필드가 항상 `false` → Upbit 연동 상태 화면에서 WebSocket이 항상 "미연결" 표시

**수정 후 동작**: WebSocket `onOpen` 시 `true`, 연결 종료/오류/명시적 disconnect 시 `false`로 실시간 갱신 → 화면에 실제 연결 상태 반영

**설계 결정**: `UpbitWebSocketClient`는 `exchange-adapter` 모듈, `ExchangeHealthMonitor`는 `web-api` 모듈이므로 직접 의존성 주입 대신 콜백(`Consumer<Boolean>`) 패턴 사용. 기존 `tickerListeners` / `tradeListeners` 리스너 패턴과 동일한 방식.

---

### 📋 참고 (2026-03-23) — AI 복합 전략 리뷰 분석 및 개선 우선순위 도출

> 제미나이 · OpenAI · 퍼플렉시티 3개 AI에게 `COMPOSITE_STRATEGIES_GUIDE.md`를 보여주고 받은 피드백 요약.

#### 3개 AI 모두 동의 — 완료된 개선 항목

| 순위 | 항목 | 상태 |
|------|------|------|
| 1 | **글로벌 RiskManager 분리** — 전략 외부 SL/TP/최대노출 통합 관리 | ✅ 완료 (V26, PaperTradingService, LiveTradingService) |
| 2 | **`COMPOSITE_BTC` EMA 방향 필터** — 추세 감지 시 역추세 신호 억제 | ✅ 완료 (CompositeStrategy.java) |
| 3 | **`COMPOSITE` TRANSITIONAL → 신규 진입 금지** | ✅ 완료 (PaperTradingService, LiveTradingService) |
| 4 | **`COMPOSITE_ETH` 모드별 가중치 분리** — 백테스트/실시간 프리셋 구분 | ✅ 완료 (BacktestService.java) |

#### 잔여 개선 항목 (PROGRESS.md P1 추적 중)

| 순위 | 항목 | 비고 |
|------|------|------|
| 5 | `MACD_STOCH_BB` → COMPOSITE TREND 서브필터 편입 | 복잡도: 중 |
| 6 | 멀티 타임프레임 (1H 방향 + 15M 진입) | 복잡도: 고 |
| 7 | 동적 가중치 (100거래 이상 샘플, 오버피팅 주의) | 복잡도: 고 |

#### 주요 판단 메모

- **OpenAI 충돌 판단 로직 변경 제안 반박**: `abs(buyScore-sellScore)<0.1→HOLD` 방식은 두 신호 모두 강할 때(예: buy=0.80, sell=0.75) 상충을 방치함. 현재 "양쪽 모두 0.4 이상이면 HOLD" 로직이 우월.
- **동적 가중치 오버피팅 위험**: 최근 10~20거래 승률로 가중치 조정 시 "방금 잘 됐던 전략에 몰빵" 역효과. 최소 100거래 + 변화 ≤ 10%/회 + 최저 하한선 0.1 조건 필수.
- **하이퍼 파라미터 자산별 최적화(제미나이 제안)**: BTC/ETH 이외 알트코인 지원 시점에 검토. 현재는 파라미터 폭발 위험.

---

### ✅ 완료 (2026-03-24) — VolumeDeltaStrategy 신규 구현 및 StrategyRegistry 등록

| 파일 | 변경 내용 |
|------|-----------|
| `volumedelta/VolumeDeltaStrategy.java` (신규) | `Strategy` 인터페이스 구현. Tick Rule 근사로 캔들별 Delta(매수볼륨 − 매도볼륨) 계산. `누적Delta비율 = sum(Delta) / sum(volume)` 로 정규화. Delta 추세 확인 필터(전반부 vs 후반부 평균 비교), 다이버전스 필터(가격 방향 vs Delta 방향 역전 시 신호 억제) 포함 |
| `volumedelta/VolumeDeltaConfig.java` (신규) | `StrategyConfig` 구현. 파라미터: `lookback`(기본 20), `signalThreshold`(기본 0.10), `divergenceMode`(기본 true). `fromParams()` 정적 팩토리 메서드 포함 |
| `volumedelta/VolumeDeltaStrategyTest.java` (신규) | 8개 테스트: 이름/최소캔들수 확인, 데이터 부족 HOLD, 매수 압력 강화 BUY, 매도 압력 강화 SELL, 임계값 미만 HOLD, Delta 강화 없을 시 HOLD 격하, 약세 다이버전스 HOLD, 신호 강도 범위 검증 |
| `StrategyRegistry.java` | `VolumeDeltaStrategy` import 추가. `register(new VolumeDeltaStrategy())` 등록 (`OrderbookImbalanceStrategy` 바로 다음) |

**신호 로직 요약**:
- BUY: `누적Delta비율 > signalThreshold` AND `후반부 평균Delta > 전반부 평균Delta` AND (divergenceMode OFF 또는 가격↑ 아닐 것)
- SELL: `누적Delta비율 < -signalThreshold` AND `후반부 평균Delta < 전반부 평균Delta` AND (divergenceMode OFF 또는 가격↓ 아닐 것)

**설계 근거**: ORDERBOOK_IMBALANCE의 캔들 근사 볼륨 분해 방식을 재사용하되, 호가 불균형 비율 대신 "누적 Delta 방향 + 가속 추세"를 신호 기준으로 삼는다. 다이버전스 필터는 가격과 매수/매도 압력이 반대 방향으로 움직이는 국면(압력이 실제로 소진됐을 가능성)에서의 허위 신호를 억제한다.

---

### ✅ 완료 (2026-03-24) — ORDERBOOK_IMBALANCE Delta 가속도 필터 추가 (Option A)

| 파일 | 변경 내용 |
|------|-----------|
| `OrderbookImbalanceStrategy.java` | `evaluateWithCandleApproximation()` 내 per-candle delta 배열 저장 추가. lookback 구간을 전반부/후반부로 분할하여 캔들당 평균 Delta 비교. BUY: `후반부Avg > 전반부Avg` 필수, SELL: `후반부Avg < 전반부Avg` 필수. 조건 미충족 시 HOLD 격하 + reason에 전반부/후반부 Δavg 값 표기 |
| `OrderbookImbalanceStrategyTest.java` | 기존 평탄 상승/하락 테스트 → 가속 패턴 테스트(전반부 1% / 후반부 5%)로 교체. `캔들_매수_우세이나_Delta_감속시_HOLD` 신규 테스트 추가 (동일 3% 반복 → HOLD 격하 검증) |

**적용 범위**: 캔들 근사 모드(백테스팅)에만 적용. 실시간 모드(`bidVolume`/`askVolume` 파라미터 제공 시)는 호가창 데이터 자체가 정확하므로 필터 미적용.

**설계 근거**: 호가 불균형 비율이 임계값을 넘더라도 Delta가 평탄하거나 감소 중이면 이미 압력이 소진된 국면일 수 있다. 후반부 평균이 전반부 평균을 상회(BUY)/하회(SELL)하는 경우만 통과시켜 허위 신호를 억제.

**기존 S4-6 Delta 일관성 필터와의 관계**: 일관성 필터는 "마지막 캔들 vs 이전 누적"의 방향 역전 시 강도 50% 할인. 가속도 필터는 "전반부 vs 후반부 추세"가 진행 방향인지 확인. 두 필터는 독립적으로 동작하며 중복 없음.

---

### ✅ 완료 (2026-03-23) — COMPOSITE_ETH 모드별 가중치 분리 (백테스트 vs 실시간)

| 파일 | 변경 내용 |
|------|-----------|
| `BacktestService.java` | 3곳의 전략 분기(`runBacktest`, `runMultiStrategyBacktest`, `runBulkBacktest`)에 `else if ("COMPOSITE_ETH".equals(...))` 블록 추가. `compositeEthBt()` 헬퍼 메서드 신설 |
| 동일 파일 | 임포트 추가: `AtrBreakoutStrategy`, `EmaCrossStrategy`, `OrderbookImbalanceStrategy` |

**가중치 변화**:

| 구분 | ATR_BREAKOUT | ORDERBOOK_IMBALANCE | EMA_CROSS |
|------|-------------|---------------------|-----------|
| **Live** (기존) | 0.5 | 0.3 | 0.2 |
| **Backtest** (신규) | 0.7 | 0.1 | 0.2 |

**설계 근거**: 백테스트에서 `ORDERBOOK_IMBALANCE`는 실시간 호가창 대신 캔들 근사값(시가·종가 스프레드)을 사용하므로 실효 신뢰도가 낮다. 가중치를 0.3→0.1로 축소하고 그 0.2를 검증된 `ATR_BREAKOUT`으로 이동. 사용자는 전략명 `COMPOSITE_ETH`만 선택하면 되며, 백테스트/라이브 모드 자동 전환으로 UI 변경 없음.

---


### ✅ 완료 (2026-03-23) — COMPOSITE TRANSITIONAL 신규 진입 금지

| 파일 | 변경 내용 |
|------|-----------|
| `PaperTradingService.java` | COMPOSITE 전략 신호 평가 후 `regime == TRANSITIONAL && action == BUY` → `StrategySignal.hold(...)` 로 억제. 원신호 이유를 메시지에 포함하여 로그 추적 용이. `finalSignal` final 변수로 람다 캡처 문제 해결 |
| `LiveTradingService.java` | 동일 패턴 적용 |

**동작**: COMPOSITE(시장 국면 자동 선택) 전략 세션에서 TRANSITIONAL 국면 감지 시 BUY 신호 차단 → 기존 포지션은 SELL 신호로 정상 청산 가능. COMPOSITE_BTC/ETH 프리셋은 regime을 사용하지 않으므로 영향 없음.

---

### ✅ 완료 (2026-03-23) — COMPOSITE_BTC EMA 방향 필터 추가 (추세 역행 신호 억제)

| 파일 | 변경 내용 |
|------|-----------|
| `CompositeStrategy.java` | `emaFilterEnabled` 생성자 플래그 추가. `getMinimumCandleCount()` — EMA 필터 활성화 시 최소 50 캔들 보장. `evaluate()` → `finalSignal()` + `applyEmaFilter()` 로 분리. EMA20 > EMA50 상승추세 → SELL 억제 HOLD, EMA20 < EMA50 하락추세 → BUY 억제 HOLD |
| `CompositePresetRegistrar.java` | `COMPOSITE_BTC` 등록 시 `emaFilterEnabled=true` 전달 (COMPOSITE_ETH는 미적용 — 추세추종 전략 포함으로 억제 불필요) |

**설계 결정**: EMA20/50 파라미터 하드코딩 (일봉 기준 합리적 기본값). `emaFilterEnabled=false` 기본값 유지로 기존 전략 영향 없음.

---

### ✅ 완료 (2026-03-23) — 글로벌 리스크 매니저 분리 (포지션별 SL/TP 절대가 저장 및 매 틱 체크)

| 파일 | 변경 내용 |
|------|-----------|
| `V26__add_sl_tp_to_positions.sql` (신규) | `position` 및 `paper_trading.position` 테이블에 `stop_loss_price`, `take_profit_price` 컬럼 추가 (NUMERIC 20,8, nullable — 기존 포지션 하위 호환) |
| `PositionEntity.java` | `stopLossPrice`, `takeProfitPrice` 필드 추가 |
| `PaperPositionEntity.java` | `stopLossPrice`, `takeProfitPrice` 필드 추가 |
| `PaperTradingService.java` | `executeBuy()` — 매수 시 전략 제시 SL/TP 우선, 없으면 기본 3%/6% 적용하여 포지션에 저장. `runSessionStrategy()` — 전략 평가 전 SL/TP 가격 비교 체크 추가 (손절: currentPrice ≤ stopLossPrice, 익절: currentPrice ≥ takeProfitPrice) |
| `LiveTradingService.java` | `executeSessionBuy()` — 매수 시 전략 제시 SL/TP 우선, 없으면 세션 `stopLossPct` 기반(SL×2 = TP) 기본값 적용. SL/TP 체크 블록 리팩터링: 기존 SL(%-비교)에 TP 체크 추가, `pos.stopLossPrice != null`이면 절대가 비교, null이면 pnlPct 비교(하위 호환) |

**설계 결정**:
- 전략이 `StrategySignal.suggestedStopLoss/TakeProfit`을 제공하면 그 값 우선 사용
- 없으면 세션/기본 비율로 진입가 기준 절대가를 계산하여 저장 → 매 틱 O(1) 비교
- 기존 포지션(`stopLossPrice == null`)은 기존 %-비교 방식 유지 (하위 호환)

---

### ✅ 완료 (2026-03-23) — 전략 코드 리팩토링 (기능 변경 없음)

| 파일 | 변경 내용 |
|------|-----------|
| `StrategyParamUtils.java` (신규) | `getInt / getDouble / getBoolean` 공용 파라미터 파싱 헬퍼. `Number` 외에 `String` → 숫자 변환 fallback 포함 (JSON 역직렬화 타입 불일치 방어). 기존 4개 전략에 각각 중복 정의되어 있던 코드 제거 |
| `IndicatorUtils.java` | `rsiSeries()` — Wilder's Smoothing RSI 시계열 / `rsiFromAvg()` — RS → RSI 변환 헬퍼 / `stochasticKSeries()` — RSI 시계열 → Stochastic %K 시계열 / `smaList()` — 롤링 SMA 시계열 (기존 `sma()`는 단일 BigDecimal 반환) 추가 |
| `MacdStrategy.java` | `calculateEmaFromList()` 제거 → `IndicatorUtils.ema()` 직접 호출 / `getInt / getDouble` 제거 → `StrategyParamUtils` 사용 / `calculateMacd()` 이중 호출 제거: 단일 패스에서 MACD 라인 시계열 빌드 후 `prev`·`current` signal을 한 번에 산출 (이전: O(2n), 이후: O(n) + 1 EMA step) |
| `MacdStochBbStrategy.java` | `calcEmaFromList / calcRsiSeries / rsiFromAvg / calcStochK / calcSma` 제거 → `IndicatorUtils` 위임 / `getInt / getDouble` 제거 → `StrategyParamUtils` 사용 / `calcMacd()` 이중 호출 → 단일 패스로 개선 |
| `StochasticRsiStrategy.java` | `computeRsiSeries / rsiFromAvg / computeStochasticK / computeSma` 제거 → `IndicatorUtils` 위임 / `parseIntParam / parseDoubleParam` 제거 → `StrategyParamUtils` 사용 |
| `VwapStrategy.java` | `getInt / getDouble / getBool` 제거 → `StrategyParamUtils` 사용 |
| `CompositeStrategy.java` | `totalWeight` 필드 추가, 생성자에서 1회 계산 → `evaluate()` 호출마다 `stream().sum()` 재산정하던 낭비 제거 |
| `types.ts` | `StrategyType` union에 `COMPOSITE_BTC`, `COMPOSITE_ETH`, `MACD_STOCH_BB` 추가 (등록된 전략인데 타입 미포함으로 컴파일 타임 오류 불가 상태였음) |
| `page.tsx` (strategies) | 탭 버튼 `className` 계산 로직 → `tabClass(active: boolean)` 헬퍼 추출, 중복 제거 |

> **제거된 중복 코드 규모**: 파라미터 파싱 ~40줄 (4개 파일), 지표 계산(RSI 시계열·StochK·SMA 시계열·EMA 리스트) ~210줄 (2개 파일) — 총 약 250줄

---

### ✅ 완료 (2026-03-23) — P4 전략 고도화

- [x] **MACD 히스토그램 연속 확대 조건 추가** — 크로스 시점에 `현재 histogram > 이전 histogram` 방향 확인 조건 추가. `MacdStrategy.evaluate()` 적용. 가짜 크로스 약 30% 필터링
- [x] **MACD 제로라인 필터** — BUY: MACD 선이 0선 위에서 크로스할 때만 진입 (`currentMacd > 0`). SELL: 0선 아래 크로스만. 횡보장 노이즈 감소
- [x] **StochRSI oversold/overbought 임계값 조정** — `oversoldLevel 15→20`, `overboughtLevel 85→80`. 신호 발생 빈도 줄여 노이즈 감소. `StochasticRsiStrategy.java` 기본값 변경
- [x] **StochRSI %K-%D 크로스 연속 확인 조건** — 1캔들 즉시 진입 대신 2캔들 연속 %K > %D 유지 시만 매수. `kSeries.size() >= 3` 조건으로 구현
- [x] **StochRSI 거래량 확인 조건 추가** — 진입 시 현재 캔들 거래량 ≥ 최근 20캔들 평균일 때만 신호 발동. `IndicatorUtils.sma()` 활용
- [x] **VWAP 임계값 완화 + ADX 상한 상향** — `thresholdPct 2.5→1.5%`, `adxMaxThreshold 25→35`. BTC 거래 0건 문제 해소
- [x] **VWAP 앵커 방식 개선** — UTC 00:00 기점 캔들부터 당일 VWAP 누적 (`anchorSession=true`). 당일 캔들 3개 미만이면 rolling VWAP fallback. `period` 파라미터로 rolling 기간 조정 가능
- [x] **코인별 복합 전략 프리셋 추가** — `COMPOSITE_BTC` (GRID×0.6 + BOLLINGER×0.4), `COMPOSITE_ETH` (ATR_BREAKOUT×0.5 + ORDERBOOK_IMBALANCE×0.3 + EMA_CROSS×0.2). `CompositePresetRegistrar` @PostConstruct 등록. 전략 관리 탭에서 단일/복합 분리 표시
- [x] **MACD_STOCH_BB 신규 복합 추세 전략** — MACD(추세) + StochRSI(타이밍) + 볼린저밴드(지지선) 결합. 1시간봉 최적화. 매수 6조건 AND, 매도: Hist↓ OR K>80. 손절-2%/익절+4% 자동 첨부, 쿨다운 3캔들. `StatefulStrategy` 구현 (세션별 쿨다운 상태 격리)

### ✅ 완료 (2026-03-23)

- [x] **비상정지 텔레그램 사유 전송** — `ExchangeDownEvent`에 `reason` 필드 추가. `ExchangeHealthMonitor`가 연속 3회 실패 시 사유 포함 이벤트 발행. `LiveTradingService.onExchangeDown()`에서 `notifyExchangeDown(reason)` 전달. `emergencyStopAll()`에서 세션별 `notifySessionStopped(..., isEmergency=true)` 호출
- [x] **12/24시 요약 텔레그램 "매매 없음" 버그 수정** — `LiveTradingService`에서 `bufferTradeEvent()` 미호출이 원인. `finalizeSellPosition()` SELL 체결 확정 시 + `OrderExecutionEngine.handleBuyFill()` BUY 체결 시 각각 `telegramService.bufferTradeEvent()` 추가
- [x] **`ExchangeHealthMonitor` 연속 실패 카운터 추가** — `consecutiveFailCount` 필드 + `DOWN_THRESHOLD=3`. 1회 실패 즉시 DOWN → 3회 연속 실패 시 DOWN으로 완화. 중간은 DEGRADED
- [x] **`getPerformanceSummary()` N+1 쿼리 제거** — `PositionRepository.findBySessionIdIn()` 추가, 세션 수와 무관하게 단 1회 쿼리로 전체 포지션 로드 후 메모리 그루핑. `LiveTradingService:884`
- [x] **수수료율 하드코딩 제거** — `TradingController`의 고정 `FEE_RATE = 0.0005` → `UpbitOrderClient.getOrderChance()` 1회 호출 후 `ask_fee` 사용. API 실패 시 0.0005 폴백
- [x] **`RsiStrategy` RSI 중복 계산 제거** — `calculateRsi()` 3회 호출 → `calculateRsiSeries()` 1-pass 계산 후 인덱스 룩업으로 교체. `rsiFromAvgs()` 헬퍼 분리

### ✅ 완료 (2026-03-23) — P3 인프라·안정성

- [x] **`.env.example` 파일 추가** — 백엔드 `.env.example`에 `API_AUTH_TOKEN`, `REDIS_PASSWORD` 항목 추가. 프론트엔드 `crypto-trader-frontend/.env.example` 신규 생성 (`API_TOKEN`, `AUTH_PASSWORD`, `AUTH_SECRET`, `INTERNAL_BACKEND_URL`)
- [x] **API proxy 토큰 노출 제거** — `src/app/api/proxy/[...path]/route.ts` 신규 생성. `NEXT_PUBLIC_API_TOKEN` → 서버사이드 전용 `API_TOKEN`으로 전환. `api.ts` baseURL을 `/api/proxy`로 변경. `docker-compose.prod.yml` frontend 서비스 환경변수 반영
- [x] **`createSession()` 동시성 보호** — `synchronized` 추가 (UI 버튼 중복 클릭 수준 방어)
- [x] **`AsyncConfig` graceful shutdown** — `orderExecutor`(30s), `marketDataExecutor`(10s), `taskExecutor`(15s) 각각 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds()` 적용
- [x] **CI/CD GitHub Actions** — `.github/workflows/ci.yml` 추가. backend(Gradle + TimescaleDB + Redis 서비스 컨테이너) / frontend(npm lint + build) / Docker 이미지 빌드(main push 시)
- [x] **Config `fromParams()` 타입 안전성** — `RsiConfig`, `MacdConfig`, `AtrBreakoutConfig`, `SupertrendConfig`, `OrderbookImbalanceConfig`, `StochasticRsiConfig`에 `fromParams(Map<String,Object>)` 추가

### ✅ 완료 (2026-03-22)

- [x] **Circuit Breaker (Auto Kill-Switch)** — MDD 임계값(기본 20%) 또는 연속 손실 한도(기본 5회) 초과 시 세션 강제 비상 정지. `RiskManagementService.checkCircuitBreaker()` + Flyway V24
- [x] **Prometheus/Grafana 모니터링** — Spring Actuator + Micrometer 추가, `docker-compose.prod.yml`에 prometheus(9090) + grafana(3001) 서비스 추가. `monitoring/` 디렉터리
- [x] **텔레그램 알림 비동기화** — `telegramExecutor` 전용 스레드풀 분리, 텔레그램 HTTP 호출이 매매 루프를 블로킹하지 않도록 수정


## 2026-03-22 — PortfolioManager.totalCapital 거래소 잔고 동기화

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`PortfolioManager.syncTotalCapital()` 추가** — 거래소 잔고를 받아 `totalCapital` 갱신. `allocatedCapital`이 새 `totalCapital`을 초과하면 안전하게 조정 | `PortfolioManager.java` |
| 2 | **`EngineConfig` — `PortfolioManager` Spring Bean 등록** — 초기값 `ZERO`, 기동 직후 `PortfolioSyncService`가 동기화 | `EngineConfig.java` |
| 3 | **`PortfolioSyncService` 신규 추가** — `ApplicationReadyEvent` 시 1회 즉시 동기화 + `@Scheduled(fixedDelay=300_000)` 5분 주기 반복. KRW 가용+잠금 합계를 `totalCapital`로 갱신. API Key 미설정 시 건너뜀 | `PortfolioSyncService.java` |
| 4 | **`SchedulerConfig` 주석 업데이트** — `PortfolioSyncService.scheduledSync()` 목록 추가 | `SchedulerConfig.java` |

## 2026-03-22 — WebSocket 실시간 손절 통합

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`UpbitWebSocketClient` `@Component` + `@PreDestroy`** — Spring Bean으로 전환, `destroy()` 소멸 훅 추가 | `UpbitWebSocketClient.java` |
| 2 | **`RealtimePriceEvent` 추가** — WS 콜백 → Spring 이벤트 디커플링용 단순 POJO | `RealtimePriceEvent.java` |
| 3 | **`LiveTradingService` WS 통합** — `ApplicationEventPublisher` 주입, `UpbitWebSocketClient` optional 주입, `reconcileOnStartup()`에서 ticker 리스너 등록 + 초기 구독, `onRealtimePriceEvent()` (`@Async("marketDataExecutor")`, 5초 throttle 손절 체크), `refreshWsSubscription()` (세션 lifecycle 전체 호출) | `LiveTradingService.java` |

## 2026-03-22 — GridStrategy 세션별 격리 + CompositeStrategy 정규화

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`StrategyRegistry` 팩토리 패턴 추가** — `FACTORIES` 맵, `registerStateful()` / `isStateful()` / `createNew()` 메서드 추가. `GridStrategy`를 `registerStateful("GRID", GridStrategy::new)`로 등록 | `StrategyRegistry.java` |
| 2 | **`LiveTradingService` 세션별 GridStrategy 인스턴스 관리** — `sessionStatefulStrategies: Map<Long, Strategy>` 필드 추가. `statefulStrategy`면 세션별 인스턴스, 아니면 공유 인스턴스 사용. `stopSession` / `emergencyStop` / `deleteSession` 정리 코드 추가 | `LiveTradingService.java` |
| 3 | **`CompositeStrategy` 가중치 정규화** — 루프 후 `totalWeight`로 `buyScore` / `sellScore` 나눔. 임계값(STRONG=0.6, WEAK=0.4) 신뢰도 복원 | `CompositeStrategy.java` |

## 2026-03-22 — PortfolioManager race condition 수정

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`canAllocate()` synchronized 추가** — `allocatedCapital` 메모리 가시성 보장. 멀티 전략 동시 실행 시 두 스레드가 동시에 `true`를 받아 잔고 초과 할당하는 TOCTOU 버그 수정 | `PortfolioManager.java` |
| 2 | **`getAvailableCapital()` synchronized 추가** — 동일하게 `allocatedCapital` 비동기 읽기 가능성 차단 | `PortfolioManager.java` |

## 2026-03-22 — 제미나이 비판적 분석 반영 (태스크 추가)

| 구분 | 추가된 태스크 |
|------|-------------|
| 🔴 CRITICAL | `PortfolioManager.canAllocate()` synchronized 누락 (자본 초과 할당 race condition) |
| 🔴 CRITICAL | `docker-compose.prod.yml` DB 포트 5432 외부 노출 차단 |
| 🟡 안정성 | `GridStrategy` 세션별 인스턴스 격리 (다중 세션 상태 오염) |
| 🟡 안정성 | `CompositeStrategy` 가중치 정규화 (임계값 신뢰도 복원) |
| 🟡 안정성 | `PortfolioManager.totalCapital` 거래소 잔고 주기 동기화 |
| 🟡 보안 | Redis `requirepass` 미설정 |
| 🟡 코드 품질 | 수수료율 0.0005 하드코딩 → `getOrderChance()` API 활용 |
| 🟡 코드 품질 | `RsiStrategy` RSI 3중 중복 계산 제거 |
| 🟢 코드 품질 | `Map<String, Object>` 파라미터 타입 안전성 (P3 수준) |

> 분석 원본: `docs/crypto_autotrader_critical_analysis.md`

## 2026-03-22 — 실전매매 안정화 HIGH 2종

| # | 항목 | 파일 |
|---|------|------|
| 1 | **SchedulerConfig 스레드 풀 3→8 확장** — `@Scheduled` 작업 최소 5개(executeStrategies / reconcileClosingPositions / syncMarketData / runStrategy(Paper) / checkExchangeHealth)인데 풀이 3이어서 손절·reconcile 지연 위험. 풀 크기 8로 증가, 주석 현행화 | `SchedulerConfig.java` |
| 2 | **CLOSING 포지션 5분 타임아웃 롤백** — 매도 주문 미체결로 CLOSING 상태가 고착되면 세션 BUY 영구 차단. `closing_at` 기록 후 5분 초과 시 OPEN 롤백. V23 마이그레이션(`position.closing_at TIMESTAMPTZ`) + `PositionEntity.closingAt` 필드 + `executeSessionSell`·`closeSessionPositions`에서 closingAt 설정 + `reconcileClosingPositions()` 타임아웃 분기 추가 | `V23__add_closing_at_to_position.sql`, `PositionEntity`, `LiveTradingService` |

## 2026-03-21 — HIGH 버그 4종 수정

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`API_AUTH_TOKEN` 백엔드 환경변수 추가** — `docker-compose.prod.yml` backend 서비스에 `API_AUTH_TOKEN: ${API_AUTH_TOKEN}` 주입. 미설정 시 기본값으로 운영되던 보안 취약점 해소 | `docker-compose.prod.yml` |
| 2 | **세션 평가 race condition 수정** — `evaluateAndExecuteSession()` 진입 시 DB에서 세션 상태 재확인. `stopSession()` 동시 호출 시 STOPPED 세션에 매수/매도 실행되던 문제 차단 | `LiveTradingService` |
| 3 | **세션 종료 시 size=0 포지션 KRW 복원** — `closeSessionPositions()`에서 미체결(size=0) 포지션 감지 시 FAILED BUY 주문 확인 후 KRW 복원 + CLOSED 처리. 기존엔 수량=0 SELL 주문 실패 후 KRW 영구 손실 | `LiveTradingService` |
| 4 | **`finalizeSellPosition()` 멱등성 보장** — CLOSED 상태 사전 체크 추가. `reconcileOnStartup()` + `reconcileClosingPositions()` 동시 실행 시 fee/KRW 이중 계상 방지 | `LiveTradingService` |

## 2026-03-21 — 긴급 안정화 5종

| # | 항목 | 파일 |
|---|------|------|
| 1 | **Telegram 토큰 하드코딩 제거** — `application.yml` 기본값 `${TELEGRAM_BOT_TOKEN:}` (빈 문자열). git history 노출 이력 있음 — 봇 토큰 재발급 필요 | `application.yml` |
| 2 | **CLOSING 중간 상태 + 롤백 로직** — `executeSessionSell()`·`closeSessionPositions()`에서 포지션을 CLOSING으로 표시 후 주문 제출. `reconcileClosingPositions()` (@Scheduled 5s) + `reconcileOnStartup()`에서 FILLED→CLOSED / FAILED→OPEN 롤백 처리 | `LiveTradingService` |
| 3 | **실제 체결가 기반 손익 계산** — `finalizeSellPosition()`에서 `filledOrder.getPrice()` 사용. `OrderExecutionEngine.handleSellFill()` 세션 주문 skip (세션 주문 처리는 reconcile에 위임) | `LiveTradingService`, `OrderExecutionEngine` |
| 4 | **손실 전략 차단** — `BLOCKED_LIVE_STRATEGIES = [STOCHASTIC_RSI, MACD]` 상수 추가, `createSession()`에서 검증 후 `IllegalArgumentException` 발생 | `LiveTradingService` |
| 5 | **V22 마이그레이션 커밋** — `position_fee NUMERIC(20,2) NOT NULL DEFAULT 0` 컬럼 추가 SQL 파일 커밋 | `V22__add_position_fee_to_position.sql` |

## 2026-03-21 — 리팩토링 (코드 품질 개선, 기능 변경 없음)

| 파일 | 변경 내용 |
|------|-----------|
| `PositionEntity` | `@Getter` `@Setter` 추가 → 명시적 getter/setter 40줄 제거 |
| `UpbitRestClient` | `buildGetRequest(url)` 헬퍼 추출 → GET 요청 빌드 3곳 중복 제거, `getTickerIndividually()` 루프 변수 재할당 제거 |
| `LiveTradingService` | `java.util.ArrayList<>()` → `ArrayList<>()`, `java.time.Instant` 로컬 변수 → `Instant` (FQN 제거) |
| `TelegramNotificationService` | `@Autowired` 필드 주입 → `final` + `@RequiredArgsConstructor` 생성자 주입, 파라미터 FQN(`java.math.BigDecimal`) → `BigDecimal` |

## 2026-03-21 — 전체 시스템 비판적 분석

| 구분 | 발견 내용 |
|------|-----------|
| 🔴 보안 | `application.yml`에 Telegram 봇 토큰 평문 하드코딩 (git 노출) |
| 🔴 버그 | `executeSessionSell()` — @Async 주문 실패해도 포지션 CLOSED + KRW 즉시 복원 (이중 계상) |
| 🔴 버그 | 손익 계산이 실제 체결가 아닌 캔들 종가 추정값 기반 |
| 🔴 배포 | `V22__add_position_fee_to_position.sql` untracked (미커밋) |
| 🔴 운영 | 백테스트 손실 전략(STOCHASTIC_RSI, MACD) 실전매매에서 선택 가능 |
| 🟡 보안 | Swagger 인증 없이 공개 / CORS 전체 허용 / API 토큰 기본값 취약 |
| 🟡 안정성 | 손절 체크 60초 지연 / 세션 생성 race condition / 매도 reconcile 없음 |
| 🟡 안정성 | MarketRegimeDetector 재시작 시 상태 초기화 |
| 🟢 인프라 | CI/CD 없음 / DB 백업 없음 / SchedulerConfig 주석 오래됨 |
| 🟢 테스트 | web-api 테스트가 H2 기반 + Happy Path 없음. LiveTradingService 테스트 전무 |

## 2026-03-21 — 실전매매 안정화 4종

| # | 항목 | 파일 |
|---|------|------|
| 1 | **장애 복구** — `reconcileOnStartup()` (`@EventListener(ApplicationReadyEvent)`) 추가: PENDING+exchangeOrderId=null → FAILED / OPEN+size=0 → 포지션 강제 종료+KRW 복원 | `LiveTradingService` |
| 2 | **실전매매 수수료 추적** — V22 migration (`position_fee NUMERIC(20,2)`) + `PositionEntity.positionFee` + `executeSessionSell()`에서 fee 저장 + `getPerformanceSummary()`에서 정상 집계 | `PositionEntity`, `V22__*.sql`, `LiveTradingService` |
| 3 | **텔레그램 낙폭 경고** — 손절 한도 50% 이상 손실 시 `DRAWDOWN_WARNING` 알림 (30분 쿨다운, `lastDrawdownWarning` ConcurrentHashMap). stopSession/emergencyStop/deleteSession 시 cleanup | `LiveTradingService`, `TelegramNotificationService` |
| 4 | **ORDERBOOK REST 호가창 연동** — `UpbitRestClient.getOrderbook()` 추가 (`GET /v1/orderbook`). `LiveTradingService`에 `@Autowired(required=false) UpbitRestClient` 주입 → ORDERBOOK_IMBALANCE 전략 평가 전 `bidVolume`/`askVolume` 실값 주입 (실패 시 캔들 근사 폴백) | `UpbitRestClient`, `LiveTradingService` |

## 2026-03-21 — Upbit API 테스트 페이지 보강

| 항목 | 내용 |
|------|------|
| `GET /api/v1/settings/upbit/ticker` | 현재가 조회 엔드포인트 추가 (공개 API, 인증 불필요) |
| `settingsApi.upbitTicker()` | 프론트엔드 API 함수 추가 |
| `upbit-status/page.tsx` 전면 재작성 | slate- 계열로 디자인 통일, 상태 카드 4개(API키·잔고·WebSocket·캔들), 잔고 상세(보유코인 테이블), 현재가 조회 섹션 신규 추가 |

## 2026-03-21 — 성과 대시보드 코드 검증

| 항목 | 결과 |
|------|------|
| API 연결 (`tradingApi.getPerformance` / `paperTradingApi.getPerformance`) | ✅ 정상 |
| 타입 매핑 (`PerformanceSummary` ↔ `PerformanceSummaryResponse`) | ✅ 정상 |
| 세션 0건 처리 | ✅ 빈 리스트 → "데이터가 없습니다" |
| 실전매매 수수료 집계 | ❌ `BigDecimal.ZERO` 하드코딩 (V22 TODO) |

## 2026-03-21 — 손익 대시보드 및 성과 통계 구현

| 항목 | 내용 |
|------|------|
| `PerformanceSummaryResponse` DTO | 전체 집계 + 세션별 성과 내역 (세션 수 / 승률 / 수익률 / 수수료 등) |
| `LiveTradingService.getPerformanceSummary()` | 실전매매 전 세션 PositionEntity 기반 집계 |
| `PaperTradingService.getOverallPerformance()` | 모의투자 전 세션 VirtualBalanceEntity 기반 집계 |
| `GET /api/v1/trading/performance` | 실전매매 성과 API |
| `GET /api/v1/paper-trading/performance` | 모의투자 성과 API |
| `/performance` 페이지 | 실전/모의 탭 전환, 요약 카드 7개, 세션별 테이블 |
| 사이드바 | 실전매매 그룹에 "손익 대시보드" 항목 추가 (PieChart 아이콘) |

## 2026-03-20 — 코드 보완 전체 완료

| 항목 | 내용 |
|------|------|
| null 안전 | `evaluateAndExecuteSession()` — `stopLossPct` null 시 기본값 5.0 (NPE 방어) |
| 실전매매 다중전략 | `MultiStrategyLiveTradingRequest` DTO + `POST /api/v1/trading/sessions/multi` + 프론트 체크박스 UI (2개 이상 선택 시 일괄 생성) |
| position_fee | V20 migration + `PaperPositionEntity.positionFee` + `executeBuy()`·`closePosition()` 누적 |
| version 낙관적 락 | V21 migration + `VirtualBalanceEntity.@Version` JPA 낙관적 락 |

## 2026-03-20 — 실전매매 세션별 투자비율(investRatio) 설정

- `live_trading_session.invest_ratio` 컬럼 추가 (V19, 기본 0.8000)
- `executeSessionBuy()` — 하드코딩 80% → `session.getInvestRatio()` + `maxInvestment` 절대 상한
- 세션 생성 폼에 투자비율 입력 추가 (1~100%, 기본 80%, TEST_TIMED 숨김)

**동작**: `availableKrw × investRatio`가 매수금액. `maxInvestment` 설정 시 그 값이 절대 상한.

## 2026-03-20 — 모의투자 실현손익·누적수수료 추적

- `virtual_balance`에 `realized_pnl`, `total_fee` 컬럼 추가 (V18)
- 매수/매도 체결 시마다 누적 저장 → 세션 종료 후에도 조회 가능
- 프론트엔드 SessionCard + 잔고 카드에 표시

## 2026-03-20 — 프론트엔드 로그인 인증

- Next.js middleware → `auth_session` 쿠키 미존재 시 `/login` 리다이렉트
- `AUTH_PASSWORD` / `AUTH_SECRET` 환경변수로 비밀번호 관리
- **운영서버 배포 시**: `docker-compose.prod.yml` frontend 서비스에 두 환경변수 추가 필요

## 2026-03-19~20 — 실전매매 소액 테스트 검증 완료

- TEST_TIMED / ORDERBOOK_IMBALANCE / COMPOSITE 각 1만원 매수·매도 사이클 ✅ 확인
- 2026-03-19 07:00 ~ 지속 운영 중
- 리스크 한도(`riskManagementService.checkRisk()`) BUY 진입 전 연동 완료
