# CryptoAutoTrader — CHANGELOG.md

> **목적**: 완료된 작업의 상세 변경 이력. `PROGRESS.md`에서 할 일이 완료되면 이 파일에 추가한다.

---

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
