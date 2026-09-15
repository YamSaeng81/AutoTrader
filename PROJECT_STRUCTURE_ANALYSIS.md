# Crypto Auto Trader 프로젝트 구성 분석

> 작성일: 2026-09-15  
> 범위: 저장소의 현재 파일 구조와 구현을 읽어 정리한 문서입니다. 분석 과정에서 애플리케이션 코드 및 설정은 변경하지 않았습니다.  
> 2026-09-15 검증: 모듈·클래스·설정 키·마이그레이션을 코드 기준으로 재확인하고 수치와 일부 서술을 정정했습니다.

## 1. 한눈에 보기

이 프로젝트는 업비트(Upbit) 현물 자동매매를 위한 **운영형 모니터링·검증·거래 시스템**이다. 단순 전략 백테스터가 아니라 다음 기능을 한 제품으로 묶고 있다.

- 캔들 수집과 시장 데이터 캐시
- 개별/복합 전략 및 시장 국면(regime) 기반 전략 선택
- 백테스트, 비동기 배치 작업, Walk-forward 검증
- 모의거래(PAPER), 실거래(LIVE), 동적 종목 스캔 기반 모의거래(DYN_PAPER) 세션
- 주문 상태 관리, 손절/익절/보유시간/서킷브레이커 등의 리스크 통제
- LLM 기반 세션 분석, 뉴스 수집, Telegram/Discord 알림, Notion 리포트 연동
- Next.js 대시보드, Prometheus/Grafana 모니터링, Docker 운영 배포

기술적으로는 Java 17 + Spring Boot 3.2의 Gradle 멀티모듈 백엔드와 Next.js 16 + React 19 프런트엔드가 결합된 구조다.

## 2. 최상위 구조

```text
crypto-auto-trader/
├─ core-engine/              순수 도메인 계산: 전략 선택, 백테스트, 위험/성과 계산
├─ strategy-lib/             전략 인터페이스, 지표 계산, 개별 전략 구현
├─ exchange-adapter/         업비트 REST/WebSocket/JWT 연동 계층
├─ web-api/                  Spring Boot 진입점, REST API, DB, 세션/주문 운영 로직
├─ crypto-trader-frontend/   Next.js 운영 대시보드
├─ docs/                     설계, 전략, 운영 진행 상태 및 과거 분석 문서
├─ scripts/                  운영 스크립트. 상시용(status/security-check/db-restore-drill)과 날짜가 박힌 1회성 운영·A/B 스크립트가 섞여 있다
├─ monitoring/               Prometheus 설정 + Grafana 데이터소스 프로비저닝 (대시보드 JSON 없음)
├─ docker-compose.yml        로컬 TimescaleDB/Redis
├─ docker-compose.prod.yml   운영 전체 스택
├─ graphify-out/            지식그래프 산출물(생성물). GRAPH_REPORT.md·graph.html 만 추적
└─ .github/workflows/ci.yml  백엔드·프런트엔드·Docker CI
```

`settings.gradle`에 등록된 백엔드 모듈은 `strategy-lib`, `core-engine`, `exchange-adapter`, `web-api`다. 프런트엔드는 Gradle 밖의 독립 Node.js 애플리케이션이다.

## 3. 계층과 의존성

```text
Browser
  │
  ▼
Next.js 16 Dashboard ── 인증 쿠키 검사 / API proxy
  │ REST (/api/proxy → /api/v1/...)
  ▼
web-api (Spring Boot)
  ├─ 세션·주문·리스크·백테스트·리포트·LLM·알림
  ├─ core-engine ───── 백테스트/국면/전략 선택/위험 계산
  │    └─ strategy-lib ─ 전략 인터페이스·지표·전략 구현
  └─ exchange-adapter ─ 업비트 REST·WebSocket·주문 API
       └─ core-engine

web-api ── PostgreSQL + TimescaleDB (영속 데이터)
web-api ── Redis (캐시/이벤트성 인프라)
web-api ── Upbit / Anthropic / Telegram / Discord
```

의존성의 중심은 `strategy-lib`다. 이 모듈은 프레임워크 의존 없이 전략 신호를 만들고, `core-engine`이 전략 조합·시장 필터·리스크·백테스트를 담당한다. `web-api`가 이들을 조립해 DB와 외부 거래소에 연결한다. 이 방향은 도메인 계산을 HTTP/DB 구현으로부터 비교적 잘 분리한다.

## 4. 모듈별 역할

| 모듈 | 주된 책임 | 핵심 구성 |
|---|---|---|
| `strategy-lib` | 전략 신호 계산 | `Strategy`, `StrategyConfig`, `StrategyRegistry`, `IndicatorUtils`, EMA/RSI/MACD/VWAP/Bollinger/Grid/Supertrend/FVG 등 |
| `core-engine` | 전략 조합 및 검증 계산 | `BacktestEngine`, `WalkForwardTestRunner`, `RiskEngine`, `MarketRegimeDetector`, `StrategySelector`, 복합·MTF 필터 |
| `exchange-adapter` | 업비트 통신 | `UpbitRestClient`, `UpbitWebSocketClient`, `UpbitOrderClient`, `UpbitCandleCollector`, JWT |
| `web-api` | 운영 애플리케이션 | REST Controller, Spring Data JPA, Flyway, 비동기 작업, 스케줄러, 주문/세션 서비스 |
| `crypto-trader-frontend` | 운영 화면 | App Router 페이지, React Query hooks, Axios API client, Zustand UI 상태, Recharts |

### 전략 라이브러리

전략 구현은 각 전략의 `Strategy`/`StrategyConfig` 쌍으로 나뉜다. `strategy-lib`에 실재하는 전략 구현은 **14개**다.

`EmaCrossStrategy`, `RsiStrategy`, `MacdStrategy`, `BollingerStrategy`, `VwapStrategy`, `GridStrategy`, `AtrBreakoutStrategy`, `SupertrendStrategy`, `StochasticRsiStrategy`, `HeikinAshiStochStrategy`, `FairValueGapStrategy`, `VolumeDeltaStrategy`, `OrderbookImbalanceStrategy`, `MacdStochBbStrategy`

같은 디렉터리의 `Strategy`(인터페이스), `StatefulStrategy`(내부 상태 유지 전략용 확장), `TestTimedStrategy`(테스트 전용)는 전략 수에 포함하지 않는다.

`core-engine/selector`는 이를 단순 나열하지 않고, 복합 전략 투표·가중치·시장 국면·EMA200·BTC 시장 조건·다중 시간 프레임·감쇠(dampen)·감시목록 품질 게이트로 조합한다.

### API 및 운영 서비스

`web-api`는 가장 큰 모듈이며, `@RestController` 19개·`@Service` 40개·`@Entity` 37개·Repository 37개가 확인된다(2026-09-15, commit `4437214` 기준).

> 측정 기준: `web-api/src/main/java` 하위에서 애너테이션이 줄 선두에 오는 라인만 센다(`grep -raE "^\s*@Service"`). 주석·문자열에 섞인 오탐은 0건이며, 애너테이션 보유 파일 수와 애너테이션 라인 수가 모두 40으로 일치한다. `grep`은 `StrategyWeightOptimizer.java`를 binary 로 판정해 기본 옵션에서 건너뛰므로(아래 참고) `-a` 없이 세면 결과가 달라진다.

주요 책임은 다음과 같다.

- `BacktestService`, `BacktestJobService`: 단일/다중/배치 백테스트와 비동기 작업 이력
- `PaperTradingService`: 고정 종목 기준 모의 세션
- `LiveTradingService`: 실거래 세션과 포지션/주문 lifecycle
- `DynamicTradingService`: watchlist를 주기적으로 재선정하는 동적 세션과 동적 청산 통제
- `OrderExecutionEngine`: 비동기 주문 제출 및 주문 상태 조정(reconcile)
- `RiskManagementService`, `StrategyKillCriteriaService`: 투자 한도, 손실 통제, 전략 중단 판단
- `SharedUniverseService`: 세션 간 감시 종목 universe 통일
- `SessionLlmAnalysisService`, `news/*`, `report/*`: Claude 분석, 뉴스, 정기 리포트/브리핑
- `PaperSessionPromotionService`: PAPER 성과 기준 승격 판단
- `PortfolioSyncService`: 업비트 실보유 잔고와 내부 포지션 대조
- `StrategyWeightOptimizer`, `SignalQualityService`: 전략 가중치 재조정 및 신호 품질 계측

## 5. 핵심 업무 흐름

### 5.1 데이터 및 전략 평가

```text
Upbit REST/WebSocket
  → Candle 수집·동기화 및 market-data cache
  → 시간 프레임별 Candle
  → 개별 전략의 BUY/SELL/HOLD 신호
  → 국면·BTC·MTF·품질 필터와 복합 전략 선택
  → 전략 로그 및 세션별 의사결정
```

### 5.2 검증과 세션 생성

```text
전략/파라미터 + 과거 Candle
  → BacktestEngine → 성과 지표·거래 내역
  → Walk-forward 검증 결과
  → (설정 시) walk-forward gate 통과 여부 검사
  → PAPER / LIVE / DYN_PAPER 세션 생성
```

`application.yml`의 `strategy-validation.require-walk-forward-gate`는 기본적으로 `false`지만, 활성화하면 out-of-sample 검증이 없는 전략의 LIVE/DYNAMIC 세션 생성을 차단하도록 설계되어 있다.

### 5.3 거래와 청산

```text
세션 tick / 스케줄러
  → 신규 진입 가능 여부(자본·중복·시장·위험) 판단
  → Position/Order 기록
  → commit 이후 비동기 주문 제출
  → 업비트 응답과 주문 상태 동기화
  → TP/SL/시간 제한/전략 신호/비상 중지 등의 청산 판단
  → 체결·수수료·사유·성과 기록 및 알림
```

세션 tick과 주문 동기화는 `@Scheduled`로 구동된다. 실제 `@Scheduled` **애너테이션은 34개**다. 소스에 등장하는 `@Scheduled` 문자열은 37회지만 그중 3회는 주석이다(`SchedulerConfig` 2회, `DynamicTradingService:306`의 self-invocation 설명 1회). 감사 시 문자열 단순 집계와 애너테이션 집계를 구분해야 한다.

분포는 `LiveTradingService` 6개·`DynamicTradingService` 6개가 최다이고, `TelegramNotificationService`와 `NewsAggregatorService`가 각 2개, 나머지 18개 클래스가 1개씩이다(`OrderExecutionEngine`, `PaperTradingService`, `PortfolioSyncService`, `StrategyWeightOptimizer`, `SignalQualityService`, `StrategyKillCriteriaService`, `StrategyDegradationWatchdog`, `ExecutionDriftTracker`, `ExchangeHealthMonitor`, `MarketDataSyncService`, `MarketRegimeAwareScheduler`, `CandleDataFreshnessScheduler`, `BacktestAutoSchedulerService`, `OperationalHealthCheckService`, `PaperSessionPromotionService`, `MorningBriefingScheduler`, `TelegramBriefingScheduler`, `AnalysisReportScheduler`). `core-engine`·`exchange-adapter`·`strategy-lib`에는 `@Scheduled`가 없다.

스케줄 자체의 활성/주기는 `SchedulerConfig`와 `application.yml`에서 통제한다. `SchedulerConfig` 주석은 풀 크기 8의 근거를 "작업 최소 5개 + 여유분 3"으로 적어 두었는데, 실제 `@Scheduled`는 34개다. 동시 실행 작업 수와는 다른 값이지만 주석과 현실의 간극은 확인해 둘 가치가 있다.

이 흐름에서 특히 중요한 설계 포인트는 주문 제출을 `@Async`로 분리하면서, 미커밋 포지션을 다른 트랜잭션에서 읽는 문제를 피하기 위해 commit 이후 제출을 의식적으로 처리한 점이다. 관련 설명과 방어 코드가 `LiveTradingService`, `DynamicTradingService`, `OrderExecutionEngine`에 남아 있다.

### 5.4 4엔진 정합성 — 이 저장소의 핵심 제약

이 프로젝트에서 가장 중요한 구조적 규칙은 모듈 경계가 아니라 **청산·리스크 규칙을 구현한 곳이 넷이라는 사실**이다.

| 엔진 | 전체 줄 | 공백 제외 | 세션/포지션 저장소 |
|---|---|---|---|
| `LiveTradingService` | 2,833 | 2,540 | `live_trading_session` / `public.position` |
| `DynamicTradingService` | 2,930 | 2,672 | `dynamic_session` / `public.position` |
| `PaperTradingService` | 1,084 | 966 | `paper_trading.virtual_balance` / `paper_trading.position` |
| `BacktestEngine` | 488 | 435 | `backtest_run` (영속 포지션 없음) |

> 두 열을 함께 적는 이유는 이 수치가 리뷰에서 실제로 어긋났기 때문이다. `wc -l`(전체 줄)과 공백 제외 집계가 300줄 가까이 차이 나므로, 규모를 인용할 때는 기준을 함께 밝혀야 한다. `docs/ENGINE_PARITY.md`와 `EngineParityTest` Javadoc에 적힌 수치(2,787 / 2,246 / 1,032 등)는 2026-08-19 시점 값으로 **현재 파일과 맞지 않는다** — 주석의 줄 수는 갱신되지 않으므로 근거로 삼지 말 것.

체결 오차와 저장소가 다를 뿐, TP/SL/시간 제한/전략 폐기 판정 같은 규칙은 네 엔진에서 동일해야 한다. 과거 결함이 거의 전부 "한 엔진에만 규칙을 적용하고 나머지를 잊음" 형태였기 때문에, 이 제약에는 기계 감사 장치가 붙어 있다.

**`EngineParityTest`가 무엇을 보장하고 무엇을 보장하지 않는가.** 이 테스트는 엔진 소스 파일을 텍스트로 읽어, 교차 규칙별로 "어느 엔진에 해당 토큰·호출이 존재해야 하는가"를 선언적으로 검사한다. 즉 **네 엔진의 동작 결과가 동일함을 증명하지 않는다.** 새 규칙이 한 엔진에만 들어가는 회귀를 잡아내는 정적 감사에 가깝다. 테스트 자신의 Javadoc도 한계를 명시한다 — "소스 문자열 검사라 '호출된다'까지만 보고 '올바르게 호출된다'는 못 본다."

파라미터 값 수준의 동등성은 같은 디렉터리의 `PaperLiveAlignmentTest`가 맡는다. 둘은 보완 관계이고 어느 쪽도 다른 쪽을 대체하지 않으므로, 규칙을 바꿀 때는 두 테스트를 함께 봐야 한다.

한 가지 주의: `EngineParityTest`의 Javadoc 머리말은 여전히 "3엔진 정합성 감사"라고 적혀 있지만, 본문 코드는 상대 경로로 `core-engine`의 `BacktestEngine.java`까지 읽는다. 커버리지는 4엔진이고 머리말이 갱신되지 않은 것이다.

**따라서 청산·리스크 규칙을 건드리는 변경은 네 곳을 함께 고치고 `EngineParityTest`·`PaperLiveAlignmentTest`를 갱신해야 한다.** 배경은 `docs/ENGINE_PARITY.md`에 있다. 이 문서의 4절~5절에서 본 모듈 분리보다 이 제약이 실제 작업에 더 자주 영향을 준다.

## 6. 데이터 저장 구조

PostgreSQL 15 + TimescaleDB를 사용하고 Flyway migration은 현재 `V80__create_strategy_timeframe_enabled.sql`까지 존재한다.

주요 데이터 영역은 다음과 같다.

| 영역 | 대표 엔티티/테이블 |
|---|---|
| 시장 데이터 | `CandleDataEntity`, `MarketDataCacheEntity` |
| 백테스트 | `BacktestRunEntity`, `BacktestTradeEntity`, `BacktestMetricsEntity`, `BacktestJobEntity` |
| 모의거래 | `VirtualBalanceEntity`, `PaperPositionEntity`, `PaperOrderEntity` |
| 실거래 | `LiveTradingSessionEntity`, `PositionEntity`, `OrderEntity` |
| 동적 세션 | `DynamicSessionEntity`, `DynamicSellSettlementEntity` |
| 전략/위험 | `StrategyConfigEntity`, `StrategyLogEntity`, `RiskConfigEntity`, `RulesetSnapshotEntity` |
| 운영 감사 | `ExecutionDriftLogEntity`, `KillCriteriaJudgmentEntity`, `DailyHealthSnapshotEntity` |
| 전략 활성화 | `StrategyTypeEnabledEntity`, `StrategyTimeframeEnabledEntity`, `WeightOptimizerSnapshotEntity` |
| 국면/거래 로그 | `RegimeChangeLogEntity`, `TradeLogEntity` |
| AI/알림 | `LlmProviderConfigEntity`, `LlmTaskConfigEntity`, `LlmCallLogEntity`, `TelegramNotificationLogEntity`, `DiscordChannelConfigEntity`, `DiscordSendLogEntity` |
| 뉴스/리포트 | `NewsSourceConfigEntity`, `NewsItemCacheEntity`, `NotionReportConfigEntity`, `NotionReportLogEntity`, `NightlySchedulerConfigEntity` |

모의거래와 실거래의 스키마 분리는 설계 의도에 그치지 않고 **현재 코드에 실제로 적용되어 있다**. `PaperPositionEntity`, `PaperOrderEntity`, `VirtualBalanceEntity`는 `@Table(schema = "paper_trading")`을 명시하고, 마이그레이션 80개 중 14개가 `paper_trading` 스키마를 다룬다. 실거래 계열(`PositionEntity`, `OrderEntity`, `LiveTradingSessionEntity`)은 `public`에 남는다.

주의할 점은 `PaperOrderEntity`가 `paper_trading."order"`처럼 예약어를 따옴표로 감싼 테이블명을 쓴다는 것이다. 수기 SQL이나 새 마이그레이션 작성 시 이 인용 규칙을 그대로 지켜야 한다.

## 7. 프런트엔드 구성

프런트엔드는 `src/app` 기반 App Router다. 화면은 대시보드 외에 전략 설정, 데이터 수집, 백테스트(비교/스케줄러/워크포워드 포함), 모의거래, 실거래, 동적 세션, 로그, 운영 설정, LLM/뉴스/리포트 관리로 나뉜다.

- `src/lib/api.ts`: 백엔드 API의 Axios 클라이언트 집합
- `src/hooks/`: React Query 기반 도메인별 데이터 조회/변경 hook
- `src/components/`: 레이아웃, 전략 폼, 백테스트 표/차트
- `src/app/api/proxy/[...path]/route.ts`: 브라우저에 API 토큰을 노출하지 않기 위한 서버 측 프록시
- `src/proxy.ts`: `auth_session` 쿠키와 `AUTH_SECRET`을 비교하는 화면 접근 제어. Next.js 16에서 `middleware.ts` → `proxy.ts`(export `proxy`)로 이름이 바뀐 규약을 따른 파일이므로, 예전 문서를 보고 `middleware.ts`를 새로 만들면 안 된다
- `e2e/`: Playwright 스펙 4종(navigation / backtest / strategies / theme)과 `auth-fixtures.ts`, `global-setup.ts` — 인증 쿠키를 주입한 상태로 화면을 검증한다

클라이언트는 API를 직접 호출하지 않고 `/api/proxy`를 거친다. 따라서 API 토큰은 Next.js 서버 환경변수로 보관하는 구조다.

## 8. 실행·배포·관측성

### 로컬 개발

- `docker-compose.yml`: TimescaleDB와 Redis만 제공
- 백엔드: Gradle wrapper 기반 Spring Boot 빌드/테스트
- 프런트엔드: `npm run dev`, `npm run lint`, `npm run build`, `npm run test:e2e`
- `docker-compose.yml`에는 backend/frontend 서비스가 없다. 로컬에서는 DB·Redis만 컨테이너로 띄우고 애플리케이션은 호스트에서 직접 실행하는 전제다

### 운영 배포

`docker-compose.prod.yml`은 DB, Redis(비밀번호 필수), backend, frontend, 일일 DB 백업 컨테이너, Prometheus, Grafana를 함께 올린다.

Grafana 대시보드는 2026-09-15부터 저장소에서 프로비저닝된다(`monitoring/grafana/provisioning/dashboards/`). 그전에는 데이터소스만 프로비저닝되고 대시보드는 컨테이너 볼륨 안에만 있어 볼륨 유실 시 복구 불가였다. 대시보드 최상단 행은 **스케줄러 풀 포화**를 감시한다 — `executor_queued_tasks{name="taskScheduler"}`가 지속적으로 1 이상이면 5초 주기 손절 reconcile이 굶고 있다는 뜻이다(9절 참고). 백업은 1일 주기로 실행되고 7일보다 오래된 압축 SQL 백업을 삭제하도록 설정되어 있다.

환경변수는 `.env.example`에 정리되어 있다. DB/Redis/API 인증, DB 초기화 비밀번호, 업비트 키와 AES 키, Telegram/Discord, Anthropic, 프런트엔드 인증 정보를 별도로 주입해야 한다.

### CI

GitHub Actions는 `main`/`develop` push와 `main` PR에서 다음을 실행한다.

1. TimescaleDB·Redis 서비스 컨테이너를 띄운 상태에서 JDK 17(temurin) + Gradle 빌드·테스트, 테스트 리포트 아티팩트 업로드
2. Node.js 20에서 Next.js의 `npm ci`, `eslint`, production build
3. `main` push 시 backend/frontend Docker 이미지 빌드 검증(푸시는 하지 않음)

4번째 job으로 **E2E(Playwright)**가 2026-09-15에 추가됐다. 스펙은 `NEXT_PUBLIC_USE_MOCK=true`로 목 API를 쓰고 `playwright.config.ts`의 `webServer`가 `next dev`를 직접 띄우므로 백엔드·DB 없이 단독으로 돈다. `docker` job이 이 job을 게이트로 받는다. 백엔드 테스트 소스는 4개 모듈 합계 124개 파일이다.

## 9. 현재 구조의 강점과 관리 포인트

### 강점

- 전략 계산, 거래소 I/O, 운영 API가 모듈로 분리되어 전략 단위 테스트와 거래소 교체가 상대적으로 쉽다.
- 백테스트→Walk-forward→세션 생성이라는 검증 경로와 세션 종류별 데이터 모델이 존재한다.
- 주문/포지션/리스크 문제를 운영 문제로 다루며, 비상 중지·드리프트·헬스 체크·알림·백업·메트릭을 갖추고 있다.
- 프런트엔드 API proxy와 백엔드 API 토큰 인증으로 브라우저에 서버 토큰을 직접 노출하지 않는다.
- 엔진 간 규칙 누락을 `EngineParityTest`(정적 감사) + `PaperLiveAlignmentTest`(값 정합)로 기계 검사한다. 중복 구현이 4벌이라는 약점 자체는 남아 있지만, 최소한 감지 가능한 형태로 묶어 두었다.

### 주의할 점

- `LiveTradingService`(2,833줄)와 `DynamicTradingService`(2,930줄)는 각 파일이 매우 큰 orchestration 계층이고, 둘 사이 로직 중복도 크다. 기능 추가 시 세션 상태 전이, 트랜잭션 경계, 스케줄러 간 상호작용을 함께 검토해야 한다.
- 여러 `@Scheduled` 작업이 5초~수시간 단위로 병렬 실행된다. 주문 상태 동기화·손절 감시·세션 tick의 실행 순서와 중복 실행 방지가 운영 안정성의 핵심이다.
- `strategy-validation.require-walk-forward-gate`(기본 `false`)와 `strategy-signal-exit.enabled`(기본 `false`)는 둘 다 환경변수로 덮어쓰는 구조다. 다만 성격이 반대다. 전자는 **꺼져 있는 것이 느슨한 쪽**(검증 없는 전략의 LIVE 세션 생성을 허용)이고, 후자는 **꺼져 있는 것이 안전한 쪽**이다. 후자의 기본값 `false`는 임의 선택이 아니라 `application.yml` 주석에 남은 실측 근거(DYN_PAPER 청산 335건 중 전략 SELL 경로 110건이 -10,298원으로 적자 전액을 차지)에 따른 결정이므로, 근거 없이 켜면 안 된다.
- ~~`.env.example`에 `REQUIRE_WALK_FORWARD_GATE` 누락~~ → **2026-09-15 해소.** 두 안전장치 변수를 주석과 함께 추가했고, `.env.example` ↔ `docker-compose.prod.yml` 환경변수 집합이 양방향 차집합 0건으로 일치한다. 앞으로도 이 일치를 유지할 것.
- `StrategyWeightOptimizer.java`는 복합 맵 키(regime + timeframe 등)의 구분자로 **NUL 문자 리터럴**을 소스에 직접 박아 쓴다(148·152행). 의도된 코드지만 NUL 바이트 때문에 `grep`이 이 파일을 binary 로 판정해 **기본 옵션에서 조용히 건너뛴다.** 이 저장소에서 코드 전수 조사를 할 때는 `grep -a`를 써야 하며, 그렇지 않으면 집계가 조용히 어긋난다.
- `docs/`에는 과거 분석 문서가 다수 있고, 기존 graphify 결과는 2026-04-21 기준이다. 현재 구현은 V80 migration 및 2026-09 변경을 포함하므로, 과거 문서는 역사적 참고 자료로 보고 코드/최신 migration을 우선해야 한다.
- README는 매우 짧다. 새 개발자가 시작할 때는 이 문서와 `docs/PROGRESS.md`, `docs/DESIGN.md`, `.env.example`, `docker-compose.prod.yml`을 함께 보는 편이 좋다.

## 10. 추천 탐색 순서

1. `settings.gradle`, 각 모듈 `build.gradle`로 모듈 경계를 이해한다.
2. `strategy-lib`의 `Strategy`, `StrategyRegistry`와 원하는 전략 구현을 읽는다.
3. `core-engine`의 `StrategySelector`, `RiskEngine`, `BacktestEngine`, `WalkForwardTestRunner`를 읽는다.
4. `web-api`의 Controller → 해당 Service → Entity/Repository 순으로 요청 흐름을 추적한다.
5. 실거래 변경 전에는 `docs/ENGINE_PARITY.md`와 `EngineParityTest`를 먼저 읽고, `LiveTradingService`, `DynamicTradingService`, `PaperTradingService`, `BacktestEngine`, `OrderExecutionEngine`을 함께 확인한다.
6. 배포/운영 작업은 `.env.example`, `docker-compose.prod.yml`, `monitoring/`, `scripts/`를 기준으로 확인한다.

## 11. 관련 문서

- `docs/DESIGN.md`: 초기 아키텍처와 설계 의도
- `docs/PROGRESS.md`: 작업 진행 및 운영 맥락
- `docs/KILL_CRITERIA.md`: 전략 중단 기준
- `docs/ENGINE_PARITY.md`: 백테스트/실거래 엔진 동등성 관련 문서
- `docs/Strategy/`: 복합 및 단일 전략 안내
- `docs/CHANGELOG.md`: 변경 이력
- `docs/NEXT.md`: 다음 작업 후보
- `docs/SINGLE_STRATEGIES_GUIDE.md`: 단일 전략 사용 안내
- `docs/DESIGN-short-futures.md`: 숏/선물 확장 설계 초안 (현재 구현은 현물 전용)
- `docs/Deploying operating servers.md`: 운영 서버 배포 참고

**2026-09-15 문서 정리**로 `docs/`는 셋으로 나뉘었다 — 현행 / 시점 한계가 있는 참조 / 이력. 분류와 각 문서의 한계는 [`docs/README.md`](docs/README.md)에 있다. 과거 분석·리뷰 9건은 [`docs/archive/`](docs/archive/README.md)로, 초기 개발 산출물 34건은 [`docs/old/`](docs/old/README.md)로 모았고, 낡은 문서는 열면 최상단에 경고 배너가 보인다.

`20260415_analy.md`와 `20260415_sharpe_audit.md`는 낡았지만 **소스 20개 파일이 `Tier N §M` 형식으로 인용**하고 있어 제자리에 두었다. 경로와 번호가 사실상 API이므로 옮기거나 이름을 바꾸지 말 것.

## 12. 검증 이력

### 12.1 1차 검증 — 원 문서 대비 정정 (2026-09-15)

| 항목 | 기존 서술 | 실제 |
|---|---|---|
| `web-api` 규모 | Service 46개, Entity 39개 | `@Service` 40개, `@Entity` 37개 (Controller 19·Repository 37은 일치) |
| `paper_trading` 스키마 | "과거 설계 문서의 의도" | 현재 코드에 실제 적용됨(엔티티 3종 + 마이그레이션 14개) |
| 설정 기본값 | "기본값이 보수적이지 않을 수 있음" | 두 키 모두 기본 `false`. `strategy-signal-exit`는 실측 근거에 따른 의도적 off이며, 느슨한 쪽은 `require-walk-forward-gate` 하나다 |
| 전략 수 | "전략군 ... 등" | 구현 14개 (`Strategy`/`StatefulStrategy`/`TestTimedStrategy` 제외) |

보강 항목: 4엔진 정합성 제약(5.4절), `@Scheduled` 분포, `src/proxy.ts`의 Next.js 16 규약, Notion 리포트·뉴스·전략 활성화 엔티티군, CI에 E2E가 없다는 점, Grafana 대시보드 미프로비저닝, `.env.example`의 `REQUIRE_WALK_FORWARD_GATE` 누락.

### 12.2 2차 교차검증 — 재측정 (2026-09-15, commit `4437214`)

숫자 3건에 이견이 제기되어 재측정했다. 결론은 셋 다 성격이 다르다.

| 항목 | 1차 | 이견 | 재측정 확정 |
|---|---|---|---|
| `@Service` | 40 | 39 | **40**. 파일 수·애너테이션 라인 수가 모두 40으로 일치하고 주석/문자열 오탐은 0건. 39는 `grep`의 binary 스킵(아래)으로 1개가 누락된 값으로 보인다 |
| `@Scheduled` | 37 | 36 | **34**(애너테이션 기준). 문자열 등장은 37회이고 그중 3회가 주석이다. 1차의 37과 이견의 36 모두 주석을 일부 이상 포함한 값이다. `DynamicTradingService`는 7개가 아니라 **6개**가 맞다 |
| 4엔진 줄 수 | 2,833 / 2,930 / 1,084 / 488 | 2,540 / 2,672 / 966 / 435 | **둘 다 맞다.** 앞은 `wc -l`(전체 줄), 뒤는 공백 제외. 5.4절에 두 기준을 병기했다 |

서술 정정 1건: `EngineParityTest`를 "동등성을 기계 검증"으로 적었던 것은 과장이다. 이 테스트는 소스 문자열 기반 정적 감사이며 동작 결과의 동일성을 증명하지 않는다. 5.4절을 테스트 자신의 Javadoc 한계 서술에 맞춰 고치고, 값 정합을 담당하는 `PaperLiveAlignmentTest`를 함께 명시했다.

이 과정에서 드러난 부수 사실 둘을 본문에 반영했다. (1) `StrategyWeightOptimizer.java`의 NUL 리터럴 때문에 `grep`이 기본 옵션에서 이 파일을 건너뛴다 — 이 저장소의 전수 집계는 `-a`가 필수다. (2) `EngineParityTest`의 Javadoc 머리말("3엔진")과 인용된 줄 수는 2026-08-19 시점 값으로 현재와 맞지 않으나, 코드 자체는 `BacktestEngine`까지 4엔진을 읽는다.

**집계 수치를 인용할 때의 규칙**: 이 문서의 모든 개수는 `grep -a` + 애너테이션 라인 기준이며, 줄 수는 `wc -l` 기준이다. 다른 기준으로 센 값과 비교할 때는 기준부터 맞출 것.

## 13. 2026-09-15 구조 점검에서 적용한 조치

구조 분석에서 드러난 항목을 우선순위대로 처리했다. **P1(스케줄러 풀)은 매매 동작에 직접
닿으므로 마지막으로 미뤘고, 나머지는 적용 완료다.**

| # | 항목 | 조치 |
|---|---|---|
| P2 | `.env.example` 누락 변수 | `REQUIRE_WALK_FORWARD_GATE`·`STRATEGY_SIGNAL_EXIT_ENABLED` 추가(근거 주석 포함). 후자는 `application.yml`이 읽는데 prod compose에 아예 없어 운영에서 켤 방법이 없던 것을 함께 통과시켰다. 두 파일의 환경변수 집합이 양방향 차집합 0건 |
| P3 | Grafana 대시보드 미프로비저닝 | `dashboards.yml` provider + 15패널 대시보드 JSON 신설. 커스텀 메트릭 6종 전부 연결. compose 마운트는 기존 것으로 충분 |
| P4 | CI에 E2E 없음 | `e2e` job 추가, `docker` job이 게이트로 받음. `playwright.config.ts`에 CI 전용 retries/workers/reporter 추가 |
| P5 | 주석 속 수치 부패 | `EngineParityTest` Javadoc("3엔진"→4엔진, 줄 수 제거, 정적 감사임을 명시), `SchedulerConfig`(6개→실측 분포), `ENGINE_PARITY.md`(줄 수 열 삭제) |
| P6 | `graphify-out/` 22MB 커밋 | 3,117개 추적 해제(디스크는 보존). 사람이 여는 `GRAPH_REPORT.md`·`graph.html`만 유지 |
| P7 | `.gitignore` 사문화 경로 | `web-dashboard/*` → 실제 경로로 교체, `playwright-report/`·`graphify-out` 규칙 추가 |
| P8 | README 1줄 | 진입점 문서로 재작성. 4엔진 제약을 최상단에 배치 |
| P9 | `scripts/` 혼재 | `scripts/README.md` 색인 신설 — 상시용 3 / 1회성 28 분류. **파일은 옮기지 않았다**: `docs/NEXT.md`가 1회성 2개를 현재 작업으로 참조 중이라 이동 시 살아있는 참조가 깨진다 |

검증: `:web-api:compileTestJava` 통과, `EngineParityTest`·`PaperLiveAlignmentTest` 통과,
README 링크 7개·`scripts/README.md` 링크 31개 실존 확인, 대시보드 JSON 패널 격자 겹침 0.

### 문서 노후화 정리 (2026-09-15, 같은 세션)

낡은 문서가 현행처럼 읽혀 잘못된 근거가 인용되는 문제를 함께 정리했다. 이 리뷰 자체가
그 피해 사례였다 — 문서에 박힌 오래된 줄 수 때문에 서로 다른 숫자로 논쟁이 벌어졌다.

| 조치 | 내용 |
|---|---|
| 분류 | `docs/README.md` 신설 — 현행 / 시점 한계가 있는 참조 / 이력 3단 분류 |
| 이동 | 과거 분석·리뷰 9건 → `docs/archive/` (`git mv`, 히스토리 보존) |
| 배너 | 낡은 문서 15건 최상단에 경고 배너. 열면 바로 보인다 |
| 색인 | `docs/archive/README.md`, `docs/old/README.md`(34건, 그동안 설명 없이 방치) |
| 링크 | 상대 링크 406개 전수 검사 → 깨진 27개 복구, 잔여 1개(존재한 적 없는 CSV) |
| 인코딩 | `graphify-out/GRAPH_REPORT.md` cp949 → UTF-8 (UTF-8 도구에서 깨져 읽히고 있었다) |
| 제거 | 빈 디렉터리 3개(`docs/anal_data`, `docs/backtest`, `docs/logs`) |

**옮기지 않은 것**과 그 이유:

- `20260415_analy.md`·`20260415_sharpe_audit.md` — **소스 20개 파일이 `Tier N §M` 형식으로
  인용**한다. 경로와 번호가 사실상 API다. 대신 "지적 18건은 전부 해소됨" 배너를 달았다.
- `old_progress.md` — 본문이 스스로를 현행 PROGRESS 라 칭해 가장 위험했다. 제자리에 두되
  최상단에 정정 배너를 달았다. 파일명만 바꾸면 본문 첫 문단이 이긴다.

### 전략 가이드 보강 (2026-09-15, 같은 세션)

정리 중 드러난 문서-코드 불일치 2건을 본문 보강까지 마쳤다.

| 문서 | 이전 | 이후 |
|---|---|---|
| `SINGLE_STRATEGIES_GUIDE.md` | 14종 중 11종 | **14종 전부** — `FAIR_VALUE_GAP`·`HEIKIN_ASHI_STOCH`·`MACD_STOCH_BB` 추가 (736→958줄) |
| `Strategy/COMPOSITE_STRATEGIES_GUIDE.md` | 6종 | **등록 프리셋 14종 전부** — MTF 계열·REGIME_ROUTER·PULLBACK_MTF·MEANREV_BB 등 8절 추가 (521→749줄) |

**집계 정정**: 처음에 "복합전략이 코드에 19종 더 있다"고 적었으나 **틀렸다.** 두 가지 오류가
겹쳤다 — (1) 정규식 `COMPOSITE_[A-Z_]+` 가 `_V2` 의 숫자에서 끊겨 `_V` 로 잘렸고,
(2) `_BASE`·`_CB`·`_CORE` 처럼 **래퍼 안쪽 인스턴스에 붙은 내부 조립 이름**을 등록 전략으로
셌다. `StrategyRegistry` 에 실제 등록되는 프리셋은 **14종**이고, 가이드에 없던 것은 8종이었다.
이 함정 자체를 가이드 13절(래퍼 계층)에 문서화했다.

보강 과정에서 확인한 것 하나 — `COMPOSITE_BREAKOUT_VD` 는 `COMPOSITE_BREAKOUT.md` 가
비교 대상으로 설명하지만 **코드 어디에도 등록되어 있지 않다**(출현 0건). 해당 문서 최상단에
경고를 달았다.

### 🔴 미해소 — 프런트엔드 lint 가 CI 를 막고 있다 (기존 문제)

`npm run lint` 가 **종료코드 1** 로 실패한다(2026-09-15 기준 88 errors / 20 warnings,
`src/app/**` 의 페이지 컴포넌트 다수 + `mockServiceWorker.js`). `npm run build` 는 성공하므로
기능 문제가 아니라 규칙 위반이 쌓인 것이다.

**영향**: CI 의 `frontend` job 이 lint 단계에서 실패 → `docker` job 이 `needs` 로 묶여 있어
**이미지 빌드 검증까지 도달하지 못한다.** 2026-09-15 에 추가한 `e2e` job 도 같은 게이트 뒤에 있다.
즉 현재 `main` push 는 CI 를 통과하지 못하는 상태다.

이번 정리에서 손대지 않았다 — 88건을 고치는 것은 별도 작업이고, 페이지 컴포넌트의
동작을 바꿀 수 있어 매매 화면 회귀 위험이 있다. **다음 작업 후보 1순위로 기록한다.**

### P1 실측 결과 — 현재 미발생 (2026-09-15, 운영 DB 조회)

풀 분리를 설계하기 전에 운영 DB로 실측했고, **조치 불필요로 판정**했다.

| 관측 | 값 | 해석 |
|---|---|---|
| RUNNING 세션 | 동적 7(전부 PAPER, 워치리스트 70) + 고정 PAPER 40 | 평가 단위 110 |
| 실거래 포지션 | 마지막이 2026-08-16 **1건** | **5초 reconcile 경로가 비어 있다** |
| 캔들 닫힘→평가 지연 (PAPER M15) | p95 **57초** | 60초 tick 주기 안 — 정상 |
| 〃 (DYN_PAPER M15) | p95 **71초** | 워치리스트 70코인 순회 비용. 허용 범위 |

**판별 논리**: `DYN_PAPER` 의 M15 와 H1 은 같은 `tick()`·같은 스레드·같은 60초 스케줄에서 돈다.
풀이 막혔다면 타임프레임과 무관하게 함께 늦어야 하는데 **M15 만 건강**했다. 따라서 풀 경합이 아니다.

무거운 지표 계산은 tick 마다가 아니라 **닫힌 캔들이 갱신될 때만** 일어난다
(`lastEvaluatedClosedCandle` 스킵). 그 사이 tick 은 캐시 조회와 SL/TP 확인만 하므로
현재 부하에서 풀 8칸은 충분하다.

**재검토 조건**: 실거래를 재개하거나, 동적 세션·워치리스트를 크게 늘릴 때.
그때는 Grafana `executor_queued_tasks{name="taskScheduler"}` 로 먼저 확인할 것.

#### 조사 중 기각한 가설 — "H1 캔들 지연"

H1 평가가 캔들 닫힘 후 평균 6.4분·p95 31분 뒤에 이뤄지는 것으로 측정됐으나,
**측정 방법의 한계였다.** 동적 세션은 포지션 보유 중 `POSITION_MONITORING` 상태로
보유 코인만 평가하고, 청산 후 `SCANNING` 복귀 시점에 나머지 워치리스트를 한꺼번에 평가한다.
그 시점은 캔들 닫힘과 무관하므로 `created_at % 3600` 측정에 섞인다. 캔들은 매 tick
`MarketDataSyncService.fetchWithCache` 로 갱신되고 있어 도착 지연이 아니다.

다만 그 과정에서 확인된 **설계 특성**은 기록해 둔다 — 포지션 보유 중에는 워치리스트의
나머지 코인 진입 신호를 관측하지 않는다(세션당 포지션 1개 설계). 보유가 긴 H1 세션일수록
신호 표본이 M15 보다 구조적으로 적게 쌓인다. 전략 비교 실험에서 고려할 것.

### 참고 — P1 조치가 필요해질 때의 방향

`SchedulerConfig`의 풀 크기 8은 `@Scheduled`가 6개이던 시절 산정치이고 현재는 34개다.
주기 60초 이하만 17개이며, 그중 60초 tick 9개는 워치리스트 전체를 도는 네트워크 I/O다.
이들이 겹치면 5초 주기 손절 reconcile·주문 폴링이 큐에서 대기한다 — `SchedulerConfig` 주석이
스스로 경고한 실패 모드다.

**조치 전 실측할 것.** P3에서 추가한 대시보드가 이 판정을 그대로 해준다. 스케줄러 풀은
`TaskExecutorMetricsAutoConfiguration`이 이미 계측하고 있어(Spring Boot 3.2의
`safeGetThreadPoolExecutor(ThreadPoolTaskScheduler)`) 별도 코드 없이
`executor_queued_tasks{name="taskScheduler"}`를 볼 수 있다. 지속적으로 1 이상이면 확정이다.

유력한 해법은 풀 크기 상향이 아니라 **5초 크리티컬 작업(손절 reconcile 2종, 주문 폴링,
티커 폴백)의 전용 스케줄러 분리**다. tick이 아무리 길어져도 손절이 굶지 않는다.
