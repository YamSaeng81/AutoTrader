# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝날 때마다 `## 최근 변경사항`과 `## 다음 할 일`을 반드시 업데이트한다.
> **마지막 갱신**: 2026-03-15 (CompositeStrategy 파이프라인 PaperTrading/LiveTrading 실전 연동 완료)

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
│   ├── strategy-lib/     # 전략 10종
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis)
```

---

## 개발 완료 현황

| Phase | 내용 | 완성도 |
|-------|------|--------|
| Phase 1 | 백테스팅 엔진 (BacktestEngine, WalkForward, FillSimulator, MetricsCalculator) | **100%** |
| Phase 2 | 웹 대시보드 (Next.js, 백테스트/전략/로그/데이터 UI) | **100%** |
| Phase 3 | 전략 추가 10종 + MarketRegimeFilter + 자동 스위칭 | **100%** |
| Phase 3.5 | 모의투자 (PaperTrading) 멀티세션 | **100%** |
| 인프라 | Docker, Flyway V1~V13, SchedulerConfig, RedisConfig | **100%** |
| Phase 4 | **실전매매** (LiveTrading) | **~90%** — 백엔드/프론트엔드 구현 완료, 배포 및 실거래 검증 미완 |

### 구현된 전략 10종

VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI

---

## 최근 변경사항

### 2026-03-15 작업 (CompositeStrategy 파이프라인 PaperTrading/LiveTrading 실전 연동)

#### 배경
Phase S3에서 `MarketRegimeDetector → StrategySelector → CompositeStrategy` 3단계 파이프라인이
`core-engine` 라이브러리 코드로 완성되었으나, `PaperTradingService`와 `LiveTradingService`가
`StrategyRegistry.get(strategyName)` 경로만 사용하여 COMPOSITE 전략이 실제 매매에 전혀 활용되지 않던 문제 수정.

#### 변경 내용

| 파일 | 변경 내용 |
|------|-----------|
| `web-api/.../service/PaperTradingService.java` | **COMPOSITE 분기 추가** — `runSessionStrategy()`에서 strategyName=`"COMPOSITE"`이면 `sessionDetectors.computeIfAbsent()` → `MarketRegimeDetector.detect()` → `StrategySelector.select()` → `CompositeStrategy.evaluate()` 파이프라인 실행; `stop()`, `deleteSession()`에 `sessionDetectors.remove()` 추가 (메모리 누수 방지) |
| `web-api/.../service/LiveTradingService.java` | **imports 추가** (MarketRegimeDetector, CompositeStrategy, StrategySelector, WeightedStrategy, ConcurrentHashMap) + **`sessionDetectors` 필드** 추가 + **`createSession()` 유효성 검증 우회** (COMPOSITE은 StrategyRegistry 외부 처리) + **`evaluateAndExecuteSession()`에 COMPOSITE 분기** 추가; `stopSession()`, `emergencyStopSession()`, `deleteSession()`에 `sessionDetectors.remove()` 추가 |
| `crypto-trader-frontend/src/lib/types.ts` | `StrategyType`에 `'COMPOSITE'` 추가 |
| `crypto-trader-frontend/src/app/paper-trading/page.tsx` | 전략 드롭다운 첫 번째 옵션으로 `COMPOSITE (시장 국면 자동 선택)` 하드코딩 추가 |
| `crypto-trader-frontend/src/app/trading/page.tsx` | 실전매매 전략 드롭다운 첫 번째 옵션으로 `COMPOSITE (시장 국면 자동 선택)` 하드코딩 추가 |

#### 핵심 구현 포인트

- **per-session Hysteresis 상태 유지**: `Map<Long, MarketRegimeDetector> sessionDetectors = new ConcurrentHashMap<>()` — 세션마다 독립 Detector 인스턴스 유지. Regime 전환 시 3캔들 연속 확인 상태가 세션 간 섞이지 않음.
- **StrategyRegistry 우회**: COMPOSITE은 Registry에 등록된 단일 전략이 아닌 동적 조합이므로, `"COMPOSITE"` 이름 체크 → StrategyRegistry 호출 없이 직접 파이프라인 실행.
- **메모리 누수 방지**: 세션 중단(stop/emergencyStop)/삭제(delete) 시 `sessionDetectors.remove(sessionId)` 호출하여 Detector 인스턴스 정리.

---

### 2026-03-15 작업 (전략 파라미터 최적화 + 백테스트)

#### 배경
이전 3년(2023-2025) 백테스트에서 STOCHASTIC_RSI(-96%), MACD(-82%), ORDERBOOK_IMBALANCE 등이
과도한 거래(H1 기준 1,000+ 거래)로 폭락. 오버트레이딩 억제를 위해 ADX 필터 추가 및 파라미터 강화.

#### 전략별 변경 내용

| 전략 | 변경 내용 |
|------|-----------|
| `MacdStrategy.java` | **ADX > 25 필터 추가** — 횡보장 크로스 억제. `getDouble()` 헬퍼 메서드 추가 (컴파일 에러 수정) |
| `StochasticRsiStrategy.java` | **ADX < 30 필터 추가** — 강한 추세 구간 진입 회피. 임계값 강화: 과매도 20→15, 과매수 80→85 |
| `EmaCrossStrategy.java` | **기간 슬로우화** — fast 9→20, slow 21→50. `getMinimumCandleCount()` 22→51 |
| `VwapStrategy.java` | **ADX < 25 필터 추가** — 추세장 역추세 매매 억제. 임계값 강화: 1.0%→2.5% |
| `BollingerStrategy.java` | **ADX < 25 필터 추가** — 추세장 평균회귀 억제 (Squeeze 감지보다 먼저 적용) |
| `RsiStrategy.java` | **임계값 강화** — 과매도 30→25, 과매수 70→60 (신호 발생 빈도 감소) |
| `OrderbookImbalanceStrategy.java` | **임계값·lookback 강화** — imbalanceThreshold 0.65→0.70, lookback 5→15. `getMinimumCandleCount()` 5→15 |

#### 인프라 버그 수정

| 파일 | 변경 내용 |
|------|-----------|
| `BacktestService.java` | **`@Transactional` 제거** — 단일 트랜잭션에서 한 전략 실패 시 PostgreSQL "current transaction is aborted"로 전체 실패하던 문제 수정 |
| `BacktestTradeEntity.java` | **`market_regime` 컬럼 길이** VARCHAR(10)→VARCHAR(20) — "TRANSITIONAL"(12자) 저장 오류 수정 |
| DB (수동 ALTER) | `ALTER TABLE backtest_trade ALTER COLUMN market_regime TYPE VARCHAR(20)` |

#### 테스트 수정

| 파일 | 변경 내용 |
|------|-----------|
| `EmaCrossStrategyTest.java` | `getMinimumCandleCount()` 기대값 22→51 |
| `OrderbookImbalanceStrategyTest.java` | 실시간 모드 테스트 캔들 수 5→15 (새 lookback 기본값 반영) |
| `ConflictingSignalTest.java` | Bollinger evaluate 호출에 `adxMaxThreshold=0` 추가 (ADX 필터가 상충 시나리오 자체를 막는 문제 수정) |

#### 2025 H1 백테스트 결과 요약 (KRW-BTC / KRW-ETH)

| 전략 | BTC 수익률 | ETH 수익률 | 비고 |
|------|-----------|-----------|------|
| GRID | +8.4% | +1.4% | 양코인 안정 |
| ORDERBOOK_IMBALANCE | +0.8% | +30.6% | ETH 강세 |
| ATR_BREAKOUT | -29.8% | +39.0% | ETH 전용 |
| BOLLINGER | +3.2% | -37.0% | BTC 전용 |
| EMA_CROSS | -51.2% | +23.7% | BTC/ETH 역전 |
| STOCHASTIC_RSI | -70.4% | -67.6% | ADX 필터 후에도 최악 |
| MACD | -58.8% | -57.6% | 추가 개선 필요 |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

### 2026-03-15 작업 (Gemini 3차 분석 반영)

#### 수정 항목 (🔴 Critical → 🟡 Medium 순)

| 우선순위 | 파일 | 변경 내용 | 상태 |
|----------|------|-----------|------|
| 🔴 CRITICAL | `BacktestControllerIntegrationTest.java` | 에러코드 `BACKTEST_001` → `BAD_REQUEST` (GEH 변경 반영) | ✅ 완료 |
| 🟡 MEDIUM | `MarketDataSyncService.java` | `new UpbitRestClient()` → `@Autowired` DI 주입 (Rate Limiting 복원) | ✅ 완료 |
| 🟡 MEDIUM | `MarketDataSyncService.java` | `timeframeMinutes()` 제거 → `TimeframeUtils.toMinutes()` 사용 (M15/M30/H4 누락 수정) | ✅ 완료 |
| 🟡 MEDIUM | `crypto-trader-frontend/src/lib/types.ts` | `Timeframe` 타입에 `M15 \| M30 \| H4` 추가 | ✅ 완료 |

#### 미적용 항목 (향후 작업)

| 우선순위 | 항목 | 비고 |
|----------|------|------|
| 🟡 MEDIUM | TradingController 이중 예외 처리 패턴 통일 | 커스텀 예외 클래스 도입 필요 |
| 🟡 MEDIUM | StrategyController DTO 전환 + Bean Validation | 1차부터 지속 미적용 |
| 🟢 LOW | EngineConfig `@ConditionalOnProperty` 전환 | null Bean 방지 |
| 🟢 LOW | PaperTradingService 다중 포지션 totalKrw 계산 | 현재 1세션=1코인이라 실질 영향 없음 |

---

### 2026-03-15 작업 (테이블 격리 검증 및 수정)

#### 발견된 문제

| 위치 | 문제 | 심각도 |
|------|------|--------|
| `getGlobalStatus()` | `countByStatus("OPEN")` — session_id 없는 orphan 포지션까지 합산 | 🟡 |
| `getGlobalStatus()` | `findByStateIn(ACTIVE_ORDER_STATES).size()` — 전체 주문 엔티티 로드 후 카운트 | 🟡 |
| `deleteSession()` | OPEN 포지션이 남은 세션 삭제 시 orphan 포지션 발생 | 🟡 |

#### 수정 내용

| 파일 | 변경 내용 |
|------|-----------|
| `PositionRepository.java` | `countBySessionIdIsNotNullAndStatus(String)` 추가 |
| `OrderRepository.java` | `countBySessionIdIsNotNullAndStateIn(List<String>)` 추가 |
| `LiveTradingService.java` | `getGlobalStatus()` — session 연결된 포지션/주문만 카운트 |
| `LiveTradingService.java` | `deleteSession()` — OPEN 포지션 있으면 삭제 거부 (guard 추가) |

#### 스키마 격리 결론

- **paper_trading 스키마 격리**: ✅ Entity `@Table(schema="paper_trading")` + 전용 Repository로 완전 분리
- **LiveTrading session 격리**: ✅ 수정 완료 (orphan guard + session 필터 카운트)
- **BacktestService**: ✅ `backtest_*` 전용 테이블만 사용, position/order 미접촉

---

### 2026-03-15 작업 (Gemini 2차 분석 반영)

| 파일 | 변경 내용 |
|------|-----------|
| `web-api/.../service/LiveTradingService.java` | **`closeSessionPositions()` @Async 잔여 패턴 수정** — `submitOrder()` 전에 `sellOrder.setSessionId()` / `sellOrder.setPositionId()` 사전 주입; 리턴값 사용 및 `orderRepository.save()` 제거 |
| `web-api/.../controller/GlobalExceptionHandler.java` | **에러코드 범용화** — `BACKTEST_001/002` → `BAD_REQUEST` / `CONFLICT` / `INTERNAL_ERROR`; `IllegalStateException` 핸들러 추가 (409 CONFLICT) |
| `exchange-adapter/.../UpbitRestClient.java` | **`throttle()` Race Condition 수정** — `synchronized` 추가로 다중 스레드 동시 호출 시 원자성 보장 |
| `docker-compose.prod.yml` | **healthcheck 추가** — db: `pg_isready` (10s/5s/5회), redis: `redis-cli ping` (10s/3s/3회); backend `depends_on` → `condition: service_healthy` 로 변경 |

---

### 2026-03-15 작업 (리팩토링 2차)

| 파일 | 변경 내용 |
|------|-----------|
| `web-api/.../entity/LiveTradingSessionEntity.java` | **`@PrePersist` 기본 status 수정** — `"STOPPED"` → `"CREATED"` (프론트엔드 SessionCard 버튼 로직과 일치) |
| `web-api/.../service/LiveTradingService.java` | **`createSession()` status 수정** — `.status("STOPPED")` → `.status("CREATED")` |
| `web-api/.../service/RiskManagementService.java` | **`@Transactional(readOnly=true)` 수정** — `getRiskConfig()`에서 readOnly 제거 (save() 호출 가능하게); `private createDefaultConfig()`의 `@Transactional` 제거 (AOP 프록시 불가); N+1 제거 — `checkRisk()`에서 config 1회 로딩 후 `portfolioLimit` 파라미터 전달 |
| `web-api/.../service/OrderExecutionEngine.java` | **`getOrders(Pageable)` 추가** — 컨트롤러 서비스 레이어 준수; `Page`/`Pageable` import 추가 |
| `web-api/.../controller/TradingController.java` | **`OrderRepository` 직접 의존 제거** — import/필드 삭제; `GET /orders`에서 `orderRepository.findAll()` → `orderExecutionEngine.getOrders()` |
| `crypto-trader-frontend/.../Sidebar.tsx` | **TypeScript `as any` 제거** — `NavItem` 인터페이스 추가 (`excludePrefix?`, `disabled?`); `navItems: NavItem[]` 타입 선언 |

---

### 2026-03-15 작업 (버그 수정)

#### Critical + Medium 버그 수정

| 파일 | 변경 내용 |
|------|-----------|
| `core-engine/.../metrics/MetricsCalculator.java` | **Calmar Ratio 수식 수정** — 연환산 수익률(평균 거래 수익률 × 365) / MDD. Recovery Factor는 총 수익률 / MDD로 분리 |
| `core-engine/.../backtest/BacktestEngine.java` | **매수 수수료 PnL 반영** — `entryFee` 변수 추가, BUY 시 저장 → SELL PnL 계산에서 차감. SELL 후 `entryFee` 리셋 |
| `core-engine/.../backtest/BacktestEngine.java` | **Partial Fill continue 제거** — SELL 신호 무시 방지. BUY 조건에 `pendingQuantity == 0` 추가, SELL 시 pending BUY 취소 |
| `web-api/.../dto/OrderRequest.java` | **sessionId/positionId 필드 추가** — `@Async` 리턴값 의존 해소 |
| `web-api/.../service/OrderExecutionEngine.java` | **entity 생성 시 sessionId/positionId 반영** — request에서 직접 주입 |
| `web-api/.../service/LiveTradingService.java` | **@Async 리턴값 제거** — BUY/SELL 양쪽에서 `submitted != null` 블록 제거, request에 ID 선설정으로 대체 |
| `web-api/.../service/LiveTradingService.java` | **totalAssetKrw 오계산 수정** — `availableKrw` 단독 할당 → `totalAssetKrw - fee` 로 변경 |
| `crypto-trader-frontend/src/lib/types.ts` | **StrategyType 4→10개 확장** — RSI, MACD, SUPERTREND, ATR_BREAKOUT, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI 추가 |

---

### 2026-03-15 작업 (리팩토링)

#### 백엔드 리팩토링

| 파일 | 변경 내용 |
|------|-----------|
| `web-api/.../util/TimeframeUtils.java` | **신규 생성** — M1/M5/M15/M30/H1/H4/D1 타임프레임 → 분 변환 공통 유틸 (DRY) |
| `web-api/.../service/PaperTradingService.java` | `timeframeMinutes()` → `TimeframeUtils.toMinutes()` 교체; `fetchCurrentPrice()`에서 `new UpbitRestClient()` 직접 생성 → `@Autowired(required=false)` DI 주입으로 변경 |
| `web-api/.../service/LiveTradingService.java` | 수동 생성자 → `@RequiredArgsConstructor` 교체; `TelegramNotificationService` 필드 추가; `timeframeMinutes()` → `TimeframeUtils.toMinutes()` 교체; `startSession`/`stopSession`/`emergencyStopSession`/손절 시 Telegram 알림 추가 |
| `web-api/.../service/OrderExecutionEngine.java` | `UpbitOrderClient` `@Autowired(required=false)` 주입; `submitToExchange()`/`queryExchangeOrder()`/`cancelOnExchange()` stub → 실제 구현 (BUY: price타입, SELL: market타입, 지정가: limit타입) |
| `web-api/.../config/EngineConfig.java` | `UpbitRestClient` Bean 등록; `UpbitOrderClient` Bean 등록 (`upbit.access-key/secret-key` 없으면 null 반환) |
| `web-api/src/main/resources/application.yml` | `upbit.access-key/secret-key` 환경변수 설정 추가 |

### 2026-03-15 작업 (Low 버그 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `exchange-adapter/.../UpbitWebSocketClient.java` | **`disconnect()` scheduler 분리** — `disconnect()`에서 `scheduler.shutdown()` 제거 (재연결 가능), `destroy()` 메서드 신설 (@PreDestroy용 완전 종료) |
| `exchange-adapter/.../UpbitRestClient.java` | **Rate Limiting 추가** — `AtomicLong lastRequestTime`, `MIN_INTERVAL_MS=110` 상수, `throttle()` 메서드 추가. `getCandles()` 호출 전 `throttle()` 실행 |
| `exchange-adapter/.../UpbitOrderClient.java` | **JWT secretKey 메모리 보안 강화** — `buildSecretKeySpec()` helper 추가 (CharBuffer/ByteBuffer → byte[] → 사용 후 zero-fill). `generateJwtWithQuery()`, `generateJwtWithoutQuery()` 양쪽의 `new String(secretKey)` 패턴 제거 |

### 2026-03-15 이전 작업

#### 프론트엔드

| 파일 | 변경 내용 |
|------|-----------|
| `src/app/paper-trading/[sessionId]/page.tsx` | **매매 요약 섹션 추가** — 총 평가자산과 가격 차트 사이에 매수/매도 횟수, 누적 수수료, 실현 손익, 승률 표시 (4개 카드) |
| `src/app/paper-trading/[sessionId]/page.tsx` | **차트 가로 스크롤 수정** — 60개 초과 시 포인트당 14px 고정 너비 + `overflow-x-auto`, 최대 4000px 캡 |
| `src/components/charts/CumulativePnlChart.tsx` | **차트 가로 스크롤 동일하게 수정** — 80개 초과 시 포인트당 12px, 최대 4000px |

#### 백엔드

| 파일 | 변경 내용 |
|------|-----------|
| `web-api/.../service/TelegramNotificationService.java` | **빈 버퍼에도 요약 전송** — 거래 없을 때 `return` 조기 종료 제거 → "해당 시간대 매매 없음" 메시지 전송 (12:00 / 00:00 KST 스케줄) |

---

## 다음 할 일

### 즉시 해야 할 것

- [ ] `🔴 CRITICAL` **Phase 4 실전매매 배포** — `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY` 환경변수 설정 후 서버 재빌드
- [ ] `🔴 CRITICAL` Spring Security / API 인증 추가 — 실전매매 API 현재 무방비 (1차 분석부터 미적용)
- [ ] `🟡 MEDIUM` 텔레그램 수신 확인 (서버 재기동 후 12:00/00:00 정상 수신 여부)

### Phase 4 프론트엔드 추가 개발 (2026-03-15 완료)

| 파일 | 변경 내용 |
|------|-----------|
| `crypto-trader-frontend/src/app/trading/history/page.tsx` | **신규 생성** — 실전매매 이력 페이지: 전체/운영중/종료 요약 카드, 세션별 수익률 테이블, hover 삭제 버튼 (운영 중 비활성) |
| `crypto-trader-frontend/src/app/trading/[sessionId]/page.tsx` | **매매 요약 섹션 추가** — 매수/매도 횟수(FILLED), 실현 손익, 승률(progress bar + 승/패 카운트) |
| `crypto-trader-frontend/src/components/layout/Sidebar.tsx` | **"실전매매 이력" 메뉴 추가** — `/trading/history` 링크; `/trading` active 상태 excludePrefix 처리 |

### 전략 고도화 후속 (백테스트 결과 기반)

> 근거: `docs/BACKTEST_RESULTS.md` — 2025 H1 KRW-BTC/ETH 결과

- [ ] `🔴 HIGH` **STOCHASTIC_RSI 구조 재설계 또는 제거** — ADX 필터 추가 후에도 BTC -70.4%, ETH -67.6%. 파라미터 문제가 아닌 구조적 결함. 제거 또는 완전 재설계 고려
- [ ] `🔴 HIGH` **MACD 히스토그램 기울기 필터 추가** — ADX > 25 필터로도 BTC -58.8%, ETH -57.6%. 히스토그램 기울기(momentum) 추가 확인 필요
- [ ] `🟡 MEDIUM` **VWAP 임계값 재조정** — BTC 승률 0% (거래 발생 없음). thresholdPct 2.5% → 1.5% 재테스트
- [ ] `🟡 MEDIUM` **코인별 전략 선택 최적화** — BTC: GRID+BOLLINGER 조합 / ETH: ATR_BREAKOUT+EMA_CROSS+ORDERBOOK 조합 고려
- [ ] `🟢 LOW` 2023~2025 전체 기간 백테스트 — 2025년만 결과이므로 장기 성과 검증 필요
- [ ] `🟢 LOW` CompositeStrategy 백테스트 연동 — 현재 단일 전략만 백테스트, 복합 전략 결과 측정 필요 (PaperTrading/LiveTrading 연동은 완료, 백테스트 UI 연동만 미완)

### 단기 (1~2주)

- [ ] `🟡 MEDIUM` TradingController 예외 처리 패턴 통일 (커스텀 예외 클래스 도입 — 프론트엔드 에러 응답 불일치)
- [ ] `🟡 MEDIUM` StrategyController DTO 전환 + Bean Validation (현재 `Map<String, Object>` — 타입 안전성 부재)
- [ ] `🟢 LOW` Report 에이전트 실행 (REPORT.md 최종 갱신)
- [ ] `🟢 LOW` EngineConfig `@ConditionalOnProperty` 전환 (null Bean 방지)
- [ ] `🟢 LOW` PaperTradingService 다중 포지션 totalKrw 계산 (다중 코인 지원 시)

### 완료

- [x] CompositeStrategy 파이프라인 PaperTrading/LiveTrading 실전 연동 (2026-03-15) — MarketRegimeDetector(per-session) + StrategySelector + CompositeStrategy 3단계 파이프라인, 프론트엔드 COMPOSITE 옵션 추가
- [x] Phase 4 프론트엔드 추가 개발 — 이력 페이지, 매매 요약 섹션, 사이드바 메뉴 (2026-03-15)
- [x] 전략 파라미터 최적화 (ADX 필터 7개 전략 + 임계값 강화) (2026-03-15)
- [x] 2025 H1 KRW-BTC/ETH 벌크 백테스트 실행 + 결과 문서화 (2026-03-15)
- [x] BacktestService @Transactional 제거 (PostgreSQL cascade 실패 수정) (2026-03-15)
- [x] BacktestTradeEntity market_regime VARCHAR 10→20 (2026-03-15)
- [x] BacktestService / PaperTradingService / LiveTradingService 테이블 격리 검증 (2026-03-15)
- [x] BacktestControllerIntegrationTest 에러코드 불일치 수정 (2026-03-15)
- [x] MarketDataSyncService Rate Limiting + Timeframe 수정 (2026-03-15)
- [x] Phase S1~S5 전략 고도화 로드맵 완료 (2026-03-15)

---

## 핵심 아키텍처 포인트

### DB 테이블 소유권 (Flyway V1~V13)

```
crypto_auto_trader (단일 DB, TimescaleDB)
├── [백테스팅 전용]      backtest_run / backtest_metrics / backtest_trade
├── [모의투자 전용]      paper_trading.virtual_balance / position / order / strategy_log
├── [실전투자 전용]      live_trading_session / public.position / public.order (session_id FK)
└── [공통 인프라]        candle_data(hypertable) / strategy_config / strategy_log / risk_config
```

### 텔레그램 알림 구조

- **즉시 전송**: 세션 시작/종료, 손절, 거래소 장애
- **일별 요약**: 12:00 KST + 00:00 KST (거래 없어도 전송)
- **버퍼 방식**: `bufferTradeEvent()` → `tradeBuffer` (CopyOnWriteArrayList) → 스케줄 시 일괄 전송
- **주의**: 서버 재시작 시 인메모리 버퍼 초기화됨 (재시작 전 이벤트 유실)

### 스케줄러

| 스케줄러 | 주기 | 내용 |
|----------|------|------|
| MarketDataSyncService | 60초 fixedDelay | 캔들 데이터 동기화 |
| PaperTradingService | 60초 fixedDelay (초기 35초 지연) | 모의투자 전략 실행 |
| MarketRegimeAwareScheduler | 1시간 fixedDelay | 시장 상태 감지 + 전략 자동 스위칭 |
| TelegramNotificationService | 12:00 / 00:00 KST cron | 일별 매매 요약 전송 |

### 차트 스크롤 구현 방식

recharts의 `ResponsiveContainer`는 `overflow-x: auto` 컨테이너 내부에서 너비를 측정할 수 없음.
**해결책**: 데이터 수 초과 시 직접 `width` prop 사용 + `overflow-x-auto` 래퍼.

```tsx
// 60개 초과면 고정 px 너비 + overflow-x-auto
// 60개 이하면 ResponsiveContainer 사용
const needsScroll = chartData.length > 60;
const fixedWidth = Math.min(4000, Math.max(800, chartData.length * 14));
```

---

## 운영 서버 명령어 (Ubuntu)

```bash
# 프로젝트 루트에서
cd ~/crypto-auto-trader   # 또는 실제 경로

# 프론트엔드만 재빌드
docker compose -f docker-compose.prod.yml up -d --build frontend

# 백엔드만 재빌드
docker compose -f docker-compose.prod.yml up -d --build backend

# 둘 다
docker compose -f docker-compose.prod.yml up -d --build frontend backend

# 로그 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f frontend
```

---

## 참고 문서

| 문서 | 위치 | 내용 |
|------|------|------|
| 전체 개발 상태 | `docs/DEV_STATUS_REVIEW_v3.md` | Phase별 완성도, 보강 이력 |
| 설계서 | `docs/DESIGN.md` | API, DB 스키마, UI 설계 |
| 계획서 | `docs/PLAN.md` | Phase별 개발 계획 |
| 검증 결과 | `docs/CHECK_RESULT.md` | 설계-구현 갭 분석 |
| 전략 개선 분석 | `docs/strategy_analysis_v3.md` | 10개 전략 상세 분석 + 거시 구조 개선 로드맵 |
| 백테스트 결과 | `docs/BACKTEST_RESULTS.md` | 실행별 수익률·승률·MDD 기록 (2025-03-15~ 누적) |

---

## 전략 고도화 로드맵 (strategy_analysis_v3 기반)

> **기준 문서**: `docs/strategy_analysis_v3.md`
> **추가일**: 2026-03-15
> **목표**: 개인 프로젝트 수준 → 실전 자동매매 시스템 (Market Regime + Risk Engine + Composite Strategy)

### 현재 구현 상태 (전략 고도화 관점)

| 모듈 | 파일 | 상태 | 부족한 부분 |
|------|------|------|------------|
| MarketRegimeDetector | `core-engine/.../regime/MarketRegimeDetector.java` | ⚠️ 부분 구현 | TRANSITIONAL 상태 없음, Hysteresis 없음, BB Bandwidth 조건 없음 |
| RiskEngine | `core-engine/.../risk/RiskEngine.java` | ⚠️ 부분 구현 | 손실 한도만 있음, Fixed Fractional Position Sizing 없음, Correlation Risk 없음 |
| StrategySignal | `strategy-lib/.../StrategySignal.java` | ⚠️ 부분 구현 | `confidence` / `stopLoss` / `takeProfit` 필드 없음 |
| 10개 전략 | `strategy-lib/.../` | ✅ 구현됨 | 개별 버그 및 개선 필요 (아래 Phase별 항목) |
| StrategySelector | 미존재 | ❌ 미구현 | Regime별 전략 그룹 선택 로직 |
| CompositeStrategy | 미존재 | ❌ 미구현 | Weighted Voting (가중 투표) |
| StatefulStrategy | 미존재 | ❌ 미구현 | Grid 중복 매매 방지용 상태 추적 인터페이스 |

---

### Phase S1 — 즉시 버그 수정

**우선순위**: 🔴 최우선

- [x] **S1-1. Supertrend 코드 버그** ✅ 2026-03-15
  - `SupertrendResult`에 `upperBand`/`lowerBand` 분리 추가
  - Line 62: `band = currentUptrend ? result.lowerBand : result.upperBand` 로 수정
  - 기존 테스트 8개 전부 통과

- [x] **S1-2. Grid 하드코딩 제거** ✅ 2026-03-15
  - `lowest = new BigDecimal("999999999999")` → `candles.get(start).getLow()` 초기화로 변경
  - `highest` 도 동일하게 첫 캔들 값 사용, loop는 `start+1`부터 시작

- [x] **S1-3. Grid 상태 추적 구현** ✅ 2026-03-15
  - `StatefulStrategy` 인터페이스 신설 (`strategy-lib/.../StatefulStrategy.java`)
  - `GridStrategy`가 `StatefulStrategy` 구현
  - `Set<Integer> activeLevels` — 진입 레벨 추적, 동일 레벨 중복 BUY 차단
  - 그리드 범위 1% 이상 변경 시 상태 자동 초기화 (`isRangeChanged()`)

- [x] **S1-4. 상충 신호 테스트 케이스** ✅ 2026-03-15
  - `ConflictingSignalTest.java` 신규 생성
  - 강한 상승장: Supertrend=BUY + Bollinger=SELL 동시 발생 확인 (2개 테스트)
  - 테스트 2/2 통과 — Phase S3 CompositeStrategy 구현의 근거로 활용

---

### Phase S2 — Market Regime + Risk Engine 강화

**우선순위**: 🔴 매우 높음

#### S2-1. MarketRegimeDetector 개선 ✅ 2026-03-15

- [x] `TRANSITIONAL` 상태 추가 (`MarketRegime.java` + `MarketRegimeDetector.java`) ✅
  - ADX 20~25 구간 → TRANSITIONAL (직전 Regime 유지)
  - 기존 `VOLATILE` → `VOLATILITY`로 이름 통일 (분석 문서 기준)
- [x] Hysteresis 로직 구현 — Regime 전환 시 3캔들 연속 유지 확인 후 전환 ✅
  - `previousRegime`, `candidateRegime`, `holdCount` 필드 (stateful)
- [x] Bollinger Bandwidth 기반 RANGE 감지 추가 ✅
  - 조건: ADX < 20 **AND** BB Bandwidth < 최근 30기간 하위 20%
  - `IndicatorUtils`에 `bollingerBandwidth()`, `bollingerBandwidths()` 추가
- [x] 동적 ATR Spike 기반 VOLATILITY 감지 ✅
  - 조건: ATR > ATR 20기간 이동평균 × 1.5 **AND** ADX < 25
  - `IndicatorUtils`에 `atrList()` 추가
- [x] `MarketRegimeFilter` VOLATILE→VOLATILITY + TRANSITIONAL(빈 집합) 추가 ✅
- [x] 테스트 파일 `VOLATILE`→`VOLATILITY` 전면 치환 + Detector 테스트 확장 ✅
  - Hysteresis 차단/확정, TRANSITIONAL 필터 검증 등 (52 tests, 0 failures)

#### S2-2. RiskEngine 강화 ✅ 2026-03-15

- [x] Fixed Fractional Position Sizing 추가 ✅
  ```
  Position = Account × Risk% / StopDistance%
  예: 잔고 10,000,000, Risk 1%, 손절 2% → 5,000,000
  ```
- [x] Correlation Risk 관리 ✅
  - `effectiveSlots()`: 상관계수 > 0.7 쌍마다 슬롯 +1 패널티
  - BTC/ETH=0.85, BTC/BNB=0.78, ETH/BNB=0.80 하드코딩
- [x] `RiskConfig`에 `maxLeverage`(기본 3.0), `correlationThreshold`(기본 0.7), `defaultRiskPercentage`(기본 0.01) 추가 ✅
- [x] 단위 테스트 — Fixed Fractional 계산, Correlation-adjusted slot 검증 ✅

---

### Phase S3 — Strategy Selector & Composite Strategy ✅ 2026-03-15

**우선순위**: 🟠 높음

- [x] **`StrategySignal.getConfidence()`** 추가 — `strength / 100` (0.0~1.0) ✅
- [x] **`WeightedStrategy` 래퍼 클래스** (`strategy`, `weight` 보유, `withReducedWeight()` 메서드) ✅
- [x] **`StrategySelector` 구현** — Regime별 전략 그룹 + 가중치 반환 ✅
  - TREND: SUPERTREND(0.5) + EMA_CROSS(0.3) + ATR_BREAKOUT(0.2)
  - RANGE: BOLLINGER(0.4) + RSI(0.4) + GRID(0.2)
  - VOLATILITY: ATR_BREAKOUT(0.6) + STOCHASTIC_RSI(0.4)
  - TRANSITIONAL: 직전 전략 그룹 × 0.5 가중치 (TRANSITIONAL→RANGE 폴백으로 무한재귀 방지)
- [x] **`CompositeStrategy` 구현** — Weighted Voting ✅
  - `buyScore = Σ(weight × confidence)`, `sellScore` 동일
  - buyScore > 0.6 → STRONG_BUY / > 0.4 → BUY(weak)
  - 양쪽 모두 > 0.4 (상충) → HOLD
- [x] 신호 조합 임계값(0.4/0.6) 테스트, TRANSITIONAL 50% 축소, 상충 감지 검증 ✅
  - 67 tests, 0 failures

---

### Phase S4 — 개별 전략 고도화

**우선순위**: 🟠 높음 | 각 항목은 독립적이므로 병렬 개발 가능

| # | 전략 | 작업 내용 | 파일 |
|---|------|----------|------|
| S4-1 | **Supertrend** | ATR O(n²) → O(n): `calculateSupertrend()`에서 매 반복 `atr(subList)` 재계산 제거, ATR 배열 사전 계산 | `SupertrendStrategy.java` |
| S4-2 | **EMA Cross** | ADX > 25 필터: 낮은 ADX 환경 Whipsaw 방지, `params`에 `adxThreshold` 추가 | `EmaCrossStrategy.java` |
| S4-3 | **Bollinger** | Squeeze 감지: `bandwidth < 최근 30캔들 최저값` → 브레이크아웃 대기 모드 | `BollingerStrategy.java` |
| S4-4 | **RSI** | 피봇 기반 다이버전스: fixed lookback → 스윙 고점/저점 기반 감지 | `RsiStrategy.java` |
| S4-5 | **ATR Breakout** | 거래량 필터: 돌파 시 평균 거래량 × N배 이상일 때만 유효 신호 | `AtrBreakoutStrategy.java` |
| S4-6 | **Orderbook** | 호가 Delta 추적: 연속 스냅샷 간 취소 패턴 분석으로 스푸핑 필터링 | `OrderbookImbalanceStrategy.java` |

- [x] S4-1 Supertrend ATR 최적화 ✅ 2026-03-15
- [x] S4-2 EMA Cross ADX 필터 ✅ 2026-03-15
- [x] S4-3 Bollinger Squeeze 감지 ✅ 2026-03-15
- [x] S4-4 RSI 피봇 다이버전스 ✅ 2026-03-15
- [x] S4-5 ATR Breakout 거래량 필터 ✅ 2026-03-15
- [x] S4-6 Orderbook 호가 Delta 추적 ✅ 2026-03-15

---

### Phase S5 — 신호 확장 & Multi-Timeframe & 통합 백테스트 ✅ 완료 2026-03-15

**우선순위**: 🟠 중간 | Phase S1~S4 완료 후 진입

- [x] `StrategySignal`에 `suggestedStopLoss`, `suggestedTakeProfit` 필드 추가 ✅
  - `@Builder` 기본값 null, `buy(strength, reason, stopLoss, takeProfit)` / `sell(...)` 오버로드 추가
- [x] Multi-Timeframe 파이프라인 구현 ✅
  - `MultiTimeframeFilter` — HTF BUY+LTF SELL (또는 역) 시 역추세 억제 → HOLD
  - `CandleDownsampler.downsample(ltfCandles, factor)` — LTF→HTF 다운샘플 (OHLCV 집계)
  - HTF 데이터 부족 시 LTF 신호 그대로 통과
- [x] 타임프레임별 파라미터 프리셋 ✅
  - `TimeframePreset.forStrategy(strategyName, timeframe)` — M1/M5/M15/M30/H1/H4/D1 × 7개 전략
- [x] `BacktestEngine.run(config, candles, Strategy)` 오버로드 추가 ✅
  - `CompositeStrategy`, `MultiTimeframeFilter`-래핑 전략을 직접 전달 가능
  - 내부 `runWithStrategy()` 메서드로 리팩토링
- [x] 2025 BTC/ETH H1 백테스트 실행 완료 ✅ 2026-03-15 — 결과: `docs/BACKTEST_RESULTS.md`
- [x] **수치 목표 도출 완료** ✅ 2026-03-15 — GRID/ORDERBOOK 안정, STOCHASTIC_RSI 구조적 결함 확인

---

### 전략 고도화 검증 기준

| Phase | 검증 항목 | 기준 |
|-------|----------|------|
| Phase S1 | Supertrend 버그 수정, Grid 중복 매매 방지 | 단위 테스트 통과 |
| Phase S2 | Regime 분류 정확도 | 수동 라벨 대비 70%+ 일치 |
| Phase S3 | 상충 신호 비율 | 기존 대비 80%↓ |
| Phase S4 | 개별 전략 성능/정확도 | 단위 테스트 + 단독 백테스트 성과 |
| Phase S5 | 통합 시스템 | 2023~2025 BTC/ETH 백테스트 수치 목표 도출 |

### 개발 순서 (의존 관계)

```
S1 (버그) → S2 (Regime + Risk) → S3 (Composite + Voting) → S4 (병렬 가능) → S5 (통합 백테스트)
```

S3의 Weighted Voting은 S2의 MarketRegimeDetector에 의존.
S4는 S1 완료 후 S2/S3와 병렬 진행 가능.
S5는 S1~S4 모두 완료 후 진입.
