# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝날 때마다 `## 다음 할 일`과 `## 최근 변경사항`을 반드시 업데이트한다.
> **마지막 갱신**: 2026-03-18 (실전매매 버그 3종 수정 + DB 초기화 페이지 + Upbit API 테스트 기능 추가)

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
| Phase 3 | 전략 10종 + MarketRegimeFilter + 자동 스위칭 | **100%** |
| Phase 3.5 | 모의투자 (PaperTrading) 멀티세션 | **100%** |
| Phase S1~S5 | 전략 고도화 (버그수정 → Regime/Risk → CompositeStrategy → 개별최적화 → MTF 백테스트) | **100%** |
| 인프라 | Docker, Flyway V1~V13, SchedulerConfig, RedisConfig, Spring Security | **100%** |
| Phase 4 | **실전매매** (LiveTrading) | **~95%** — 운영 기동 완료, 실거래 검증 미완 |

### 구현된 전략 10종

VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI

### 2025 H1 백테스트 결과 요약 (KRW-BTC / KRW-ETH)

| 전략 | BTC | ETH | 비고 |
|------|-----|-----|------|
| GRID | +8.4% | +1.4% | 양코인 안정 |
| ORDERBOOK_IMBALANCE | +0.8% | +30.6% | ETH 강세 |
| ATR_BREAKOUT | -29.8% | +39.0% | ETH 전용 |
| BOLLINGER | +3.2% | -37.0% | BTC 전용 |
| EMA_CROSS | -51.2% | +23.7% | BTC/ETH 역전 |
| STOCHASTIC_RSI | -70.4% | -67.6% | 구조적 결함 |
| MACD | -58.8% | -57.6% | 추가 개선 필요 |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 다음 할 일

### 즉시

- [ ] `🔴 HIGH` **실전매매 소액 테스트** — 서버에 최신 코드 배포 후 TEST_TIMED 세션 생성 → 매수 체결 확인 → 3분 후 자동 매도 확인
  - 배포 전 DB 정리: `public."order"` 테이블에서 `state IN ('PENDING','SUBMITTED')` + `coin_pair = 'KRW-ETH'` + `side = 'BUY'` 주문 있으면 FAILED 처리
- [ ] `🟡 MEDIUM` 텔레그램 수신 확인 (서버 재기동 후 12:00/00:00 정상 수신 여부)
- [ ] `🟡 MEDIUM` 모의투자 텔레그램 이력 확인 — 세션 생성/중단 시 텔레그램 이력 페이지에 SESSION_START/STOP 로그 표시되는지 검증

### 전략 고도화 후속 (백테스트 결과 기반)

- [ ] `🔴 HIGH` **STOCHASTIC_RSI 구조 재설계 또는 제거** — ADX 필터 후에도 BTC -70.4%, ETH -67.6%. 파라미터 문제가 아닌 구조적 결함
- [ ] `🔴 HIGH` **MACD 히스토그램 기울기 필터 추가** — ADX > 25 필터로도 BTC -58.8%, ETH -57.6%
- [ ] `🟡 MEDIUM` **VWAP 임계값 재조정** — BTC 승률 0% (거래 없음). thresholdPct 2.5% → 1.5% 재테스트
- [ ] `🟡 MEDIUM` **코인별 전략 선택 최적화** — BTC: GRID+BOLLINGER / ETH: ATR_BREAKOUT+EMA_CROSS+ORDERBOOK
- [ ] `🟢 LOW` 2023~2025 전체 기간 백테스트 (현재 2025년만 결과)


---

## 최근 변경사항

### 2026-03-18 — Upbit API 테스트 기능 (연동 상태 페이지 확장)

| 파일 | 변경 내용 |
|------|-----------|
| `UpbitOrderClient.java` | `getOrderChance()`, `createTestOrder()`, `getRecentOrders()` 메서드 추가 |
| `SettingsController.java` | `GET /upbit/order-chance`, `POST /upbit/test-order`, `GET /upbit/exchange-orders` 엔드포인트 추가 |
| `lib/api.ts` | `settingsApi.upbitOrderChance()`, `upbitTestOrder()`, `upbitExchangeOrders()` 추가 |
| `app/settings/upbit-status/page.tsx` | Upbit API 테스트 섹션 3종 추가: 주문 가능 정보 조회 / 주문 생성 테스트 / 최근 주문 이력 |

### 2026-03-18 — DB 초기화 페이지 (설정 메뉴)

| 파일 | 변경 내용 |
|------|-----------|
| `DbResetService.java` | **신규** — 카테고리별(백테스트/모의투자/실전매매) DELETE + 건수 통계 조회. 비밀번호 `!Iloveyhde1` 서버 검증 |
| `SettingsController.java` | `GET /api/v1/settings/db/stats` + `POST /api/v1/settings/db/reset` 추가 |
| `app/settings/db-reset/page.tsx` | **신규** — 3개 카테고리 카드(테이블별 건수 미리보기) + 비밀번호 모달 + 삭제 결과 표시 |
| `lib/api.ts` | `settingsApi.dbStats()`, `settingsApi.dbReset()` 추가 |
| `Sidebar.tsx` | 설정 그룹에 "DB 초기화" 메뉴 추가 (`/settings/db-reset`) |

### 2026-03-18 — 실전매매 매수 미실행 + 데이터 혼재 + 이력 삭제 오류 수정

| 파일 | 변경 내용 |
|------|-----------|
| `OrderRepository.java` | `existsBySessionIdAndCoinPairAndSideAndStateIn` 추가 — 세션 단위 중복 주문 체크 |
| `OrderExecutionEngine.java` | 중복 주문 체크를 세션 단위로 변경 (기존: 전역 coinPair+side만 체크 → 다른 세션의 활성 주문이 신규 세션 매수 차단하는 버그). `@Async` 스레드에서 예외 대신 `null` 반환으로 변경 (예외가 조용히 사라지는 문제 제거). `handleBuyFill`/`handleSellFill`에서 `positionId` 직접 조회 (기존: coinPair만으로 전역 검색 → 세션간 포지션 데이터 혼재) |
| `LiveTradingService.java` | `executeSessionBuy` 앞에 세션 단위 중복 체크 추가 → 포지션 생성 전 차단 (orphan 포지션 방지). `deleteSession` OPEN 포지션 예외 대신 강제 종료 후 진행 (이전 실패한 테스트의 orphan 포지션으로 삭제가 막히던 버그) |

> **근본 원인**: `OrderExecutionEngine.submitOrder()`의 중복 주문 체크가 `sessionId`를 무시하고 전역으로 동작했음. 이전 세션의 KRW-ETH BUY 주문이 PENDING/SUBMITTED 상태로 남아있으면 모든 후속 세션의 매수가 차단됨. 게다가 `@Async`라서 예외가 사라지고 order 엔티티도 생성 안 됨. 동시에 `handleBuyFill`/`handleSellFill`이 `positionId` 대신 coinPair로 전역 검색해 세션간 데이터가 섞임.

### 2026-03-17 — 실전매매 캔들 데이터 버그 수정 + Upbit 연동 상태 페이지

| 파일 | 변경 내용 |
|------|-----------|
| `MarketDataSyncService.java` | **버그 수정**: `VirtualBalanceRepository`(모의투자)만 체크하던 것을 `LiveTradingSessionRepository`도 포함하도록 수정 — 실전매매 세션 캔들도 `market_data_cache`에 동기화됨 |
| `LiveTradingService.java` | **버그 수정**: `fetchRecentCandles()`가 `candle_data`(백테스트용 테이블) 읽던 것을 `market_data_cache`(실시간 캐시 테이블)로 교체. `getChartCandles()` 반환 타입도 `MarketDataCacheEntity`로 통일 |
| `TradingController.java` | `getChartCandles()` 반환 타입 변경에 따라 `CandleDataEntity` → `MarketDataCacheEntity` 교체 |
| `MarketDataCacheRepository.java` | `findDataSummary()` 쿼리 추가 (코인+타임프레임별 캔들 현황) |
| `SettingsController.java` | `GET /api/v1/settings/upbit/status` 추가 — API 키 설정 여부 + 잔고 조회 + 캔들 캐시 현황 종합 점검 |
| `lib/api.ts` | `settingsApi.upbitStatus()` 추가 |
| `lib/types.ts` | `UpbitStatusResponse`, `UpbitCandleSummary` 타입 추가 |
| `app/settings/upbit-status/page.tsx` | **신규** — Upbit 연동 상태 페이지. API 키/잔고/캔들 캐시 상태 + 진단 가이드 |
| `Sidebar.tsx` | "Upbit 연동 상태" 메뉴 추가 (`/settings/upbit-status`) |

> **근본 원인**: `MarketDataSyncService`가 모의투자 세션만 캔들 동기화 대상으로 포함하고 실전매매 세션은 무시했음. 동시에 `LiveTradingService`는 실시간 캐시(`market_data_cache`) 대신 백테스트 데이터(`candle_data`)를 읽고 있었음. 결과적으로 실전매매 스케줄러가 항상 "캔들 부족" 경고와 함께 전략 실행을 건너뛰었음.

### 2026-03-17 — 시장가 매수 버그 수정 + Upbit 주문 로그 페이지

| 파일 | 변경 내용 |
|------|-----------|
| `service/LiveTradingService.java` | `executeSessionBuy()` 버그 수정 — `order.setQuantity(quantity)` (코인 수량) → `order.setQuantity(investAmount)` (KRW 금액). Upbit price 타입은 총 KRW 금액이 필요한데 코인 수량을 넣어 최소 주문 미달로 주문 거부됨 |
| `lib/types.ts` | `LiveOrder`에 누락 필드 추가 — `sessionId`, `failedReason`, `submittedAt`, `cancelledAt` |
| `app/settings/upbit-logs/page.tsx` | **신규** — Upbit 주문 로그 페이지. 상태/방향 필터, 클릭 펼치기(거래소 주문ID·신호사유·실패사유), 10초 자동갱신, 페이지네이션 |
| `components/layout/Sidebar.tsx` | 설정 그룹에 "Upbit 주문 로그" 메뉴 추가 (`/settings/upbit-logs`) |

### 2026-03-17 — TEST_TIMED 테스트 전략 추가 (실전매매 동작 검증용)

| 파일 | 변경 내용 |
|------|-----------|
| `strategy-lib/.../testtraded/TestTimedStrategy.java` | **신규** — 세션 시작 직후 즉시 매수, 3분 경과 후 강제 매도. `sessionStartedAt` 파라미터로 경과 시간 판단 |
| `strategy-lib/.../StrategyRegistry.java` | `TEST_TIMED` 전략 등록 |
| `service/LiveTradingService.java` | TEST_TIMED 세션 생성 시 coinPair=KRW-ETH / timeframe=M1 / initialCapital=10,000 강제 고정. 전략 실행 시 `sessionStartedAt` epoch millis를 params에 주입 |
| `app/trading/page.tsx` | 🧪 테스트 세션 버튼 추가. 모달에서 TEST_TIMED 선택 시 코인/타임프레임/원금 읽기 전용 + 안내 배너 표시 |

> **동작**: 스케줄러 첫 틱(~45초)에 매수 → 3~4분 후 다음 틱에 자동 매도. 전략 로그로 조건 판단 과정 확인 가능.

### 2026-03-17 — 전략 로그 아코디언 UI + RuntimeError 버그 수정

| 파일 | 변경 내용 |
|------|-----------|
| `paper-trading/[sessionId]/page.tsx` | 전략 분석 로그 섹션 → `StrategyLogAccordion` 컴포넌트로 교체. strategyName 기준 그룹화, 신호 카운트 뱃지, 클릭 펼치기 |
| `trading/[sessionId]/page.tsx` | 동일한 아코디언 방식 적용 (다크 테마) |
| `logs/page.tsx` | sessionType + sessionId 조합 그룹화 아코디언. 헤더에 구분/세션ID/전략명/코인/신호카운트/최근시간/총건수 표시. 페이지 이동 시 열린 그룹 초기화 |
| `paper-trading/[sessionId]/page.tsx` | **버그 수정**: chartCandles가 빈 배열일 때 `candles[0]` undefined → `buyOrder` 설정 시 `TypeError` 발생 → `candles.length === 0` 가드 추가 |
| `trading/[sessionId]/page.tsx` | 동일한 TypeError 버그 수정 |

### 2026-03-17 — 모의투자 텔레그램 이력 누락 버그 수정 + 전략 로그 세션별 분리

| 파일 | 변경 내용 |
|------|-----------|
| `TelegramNotificationService.java` | `notifyPaperSessionStarted()` / `notifyPaperSessionStopped()` 추가 — `sendMarkdownAndLog()` 호출로 DB 저장 포함 |
| `PaperTradingService.java` | `sendMarkdown(...)` → `notifyPaperSessionStarted/Stopped(...)` 교체 (버그 수정: 텔레그램 전송은 됐지만 이력 DB 미저장) |
| `StrategyLogRepository.java` | `findAllBySessionIdOrderByCreatedAtDesc`, `findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc` 쿼리 추가 |
| `LogController.java` | `GET /api/v1/logs/strategy`에 `sessionId` 쿼리 파라미터 추가 (sessionType + sessionId 4가지 조합 처리) |
| `api.ts` | `logApi.strategyLogs()`에 `sessionId?: number` 파라미터 추가 |
| `paper-trading/[sessionId]/page.tsx` | 체결 내역 아래 **전략 분석 로그** 섹션 추가 (해당 세션 PAPER 로그만, 10초 갱신) |
| `trading/[sessionId]/page.tsx` | 세션 정보 위 **전략 분석 로그** 섹션 추가 (해당 세션 LIVE 로그만, 10초 갱신) |
| `logs/page.tsx` | 세션 타입 필터 옆 세션 ID 입력 필드 추가 (숫자 입력 시 해당 세션 로그만 표시) |

> **참고**: 실전매매는 원래부터 `notifySessionStarted/Stopped()` 사용 → DB 저장 정상. 버그는 모의투자 전용.

### 2026-03-17 — 텔레그램 세션별 분리 + 사이드바 카테고리 + 설정 페이지

| 파일 | 변경 내용 |
|------|-----------|
| `V16__create_telegram_notification_log.sql` | **신규** — telegram_notification_log 테이블 (type / session_label / message_text / success / sent_at) |
| `TelegramNotificationLogEntity.java` | **신규** — 순수 Java (Lombok 없음), 4-arg 생성자 |
| `TelegramNotificationLogRepository.java` | **신규** — findAllByOrderBySentAtDesc (페이지네이션) |
| `TelegramNotificationService.java` | `sendDailySummary` → sessionLabel 기준 그룹화, 세션별 개별 메시지 전송. 모든 전송 이력 DB 저장. `@Slf4j`/`@RequiredArgsConstructor` 제거 → 순수 Java Logger + `@Autowired` |
| `SettingsController.java` | **신규** — `GET /api/v1/settings/telegram/logs` (페이지네이션), `POST /api/v1/settings/telegram/test` |
| `Sidebar.tsx` | 카테고리 그룹 구조로 전면 개편 (대시보드/백테스트/전략관리/모의투자/실전매매/설정), 접기/펼치기 토글 |
| `app/settings/telegram/page.tsx` | **신규** — 텔레그램 전송 이력 테이블 (타입 필터, 내용 펼치기, 테스트 전송 버튼) |
| `lib/types.ts` | `TelegramNotificationLog`, `TelegramLogsResponse` 타입 추가 |

### 2026-03-17 — EngineConfig ConditionalOnProperty + PaperTradingService totalKrw 수정

| 파일 | 변경 내용 |
|------|-----------|
| `config/EngineConfig.java` | `upbitOrderClient()` null 반환 제거 → `@ConditionalOnProperty(name = {"upbit.access-key", "upbit.secret-key"})` 전환. 키 미설정 시 Bean 자체를 등록하지 않음 |
| `service/PaperTradingService.java` | `updateUnrealizedPnl()` — 미실현손익 갱신과 totalKrw 계산 분리. totalKrw = availableKrw + 세션 내 모든 OPEN 포지션 평가금액 합산 (다중 코인 지원 대비) |

### 2026-03-17 — 예외 처리 패턴 통일 + StrategyController DTO 전환

| 파일 | 변경 내용 |
|------|-----------|
| `exception/SessionNotFoundException.java` | **신규** — 세션 미존재 시 404 커스텀 예외 |
| `exception/SessionStateException.java` | **신규** — 세션 상태 충돌 시 409 커스텀 예외 |
| `controller/GlobalExceptionHandler.java` | `SessionNotFoundException` → 404, `SessionStateException` → 409, `MethodArgumentNotValidException` → 400 필드별 메시지 핸들러 추가 |
| `service/LiveTradingService.java` | `IllegalArgumentException` / `IllegalStateException` → 커스텀 예외로 교체 |
| `controller/TradingController.java` | 세션/포지션/주문 관련 try-catch 전면 제거 (GlobalExceptionHandler 위임) |
| `dto/StrategyConfigCreateRequest.java` | **신규** — `@NotBlank` + `@DecimalMin` Bean Validation 적용 |
| `dto/StrategyConfigUpdateRequest.java` | **신규** — 선택적 필드 + `@DecimalMin` 검증 |
| `controller/StrategyController.java` | `createConfig` / `updateConfig` Raw Map → `@Valid @RequestBody` DTO로 전환 |

### 2026-03-17 — Spring Security API 인증

| 파일 | 변경 내용 |
|------|-----------|
| `web-api/build.gradle` | `spring-boot-starter-security` 추가 |
| `config/ApiTokenAuthFilter.java` | **신규** — `Authorization: Bearer <token>` 검증. 기본 토큰 사용 시 기동 시 경고 로그 출력 |
| `config/SecurityConfig.java` | **신규** — CSRF 비활성, Stateless, CORS 통합. 공개: `/api/v1/health`, Swagger. 나머지 전체 인증. 401/403 JSON 응답 |
| `config/WebConfig.java` | CORS 설정 제거 (SecurityConfig으로 일원화, 충돌 방지) |
| `application.yml` | `api.auth.token: ${API_AUTH_TOKEN:dev-token-...}` 추가 |
| `src/lib/api.ts` | `NEXT_PUBLIC_API_TOKEN` 환경변수를 모든 Axios 요청에 자동 주입 |
| `crypto-trader-frontend/Dockerfile` | `ARG NEXT_PUBLIC_API_TOKEN` + `ENV` 추가 (`npm run build` 전 선언 필수 — `NEXT_PUBLIC_*`는 빌드 타임 번들링) |
| `docker-compose.prod.yml` | `NEXT_PUBLIC_API_TOKEN` → `build.args`로 이동. `API_AUTH_TOKEN` 백엔드 env 추가. **운영 정상 기동 ✅** |

### 2026-03-17 — Upbit 계좌 현황 페이지

| 파일 | 변경 내용 |
|------|-----------|
| `UpbitRestClient.java` | `getTicker(String markets)` 추가 (공개 API) |
| `AccountService.java` | **신규** — 잔고 조회 → 현재가 → 평가금액/손익 계산 집계 |
| `AccountController.java` | **신규** — `GET /api/v1/account/summary` |
| `types.ts` | `UpbitHolding`, `AccountSummary` 타입 추가 |
| `app/account/page.tsx` | **신규** — 총자산 카드, 도넛 차트(자산 구성), 보유 코인 테이블 |
| `Sidebar.tsx` | "계좌 현황" 메뉴 추가 |

### 2026-03-17 — 종료 세션 분석 화면 + 차트 스크롤

| 파일 | 변경 내용 |
|------|-----------|
| `MainContent.tsx` | 차트 가로 스크롤 수정 — `min-w-0 overflow-hidden` 추가 |
| `LiveTradingService.java` | `getChartCandles()`, `getAllSessionOrders()` 추가 |
| `TradingController.java` | `GET /sessions/{id}/chart` 엔드포인트 추가 |
| `trading/[sessionId]/page.tsx` | 가격 차트(매수/매도 마커) + 종료 거래 이력 테이블 추가 |
| `paper-trading/[sessionId]/page.tsx` | `status=ALL` 포지션 조회 + 종료 거래 이력 테이블 추가 |

### 2026-03-15 — Phase 4 실전매매 + 전략 고도화 (대규모)

- CompositeStrategy 파이프라인 PaperTrading/LiveTrading 실전 연동 (per-session MarketRegimeDetector)
- 전략 파라미터 최적화: ADX 필터 7개 전략 적용, 임계값 강화
- Phase S1~S5 전략 고도화 로드맵 전체 완료 (67 tests, 0 failures)
- Phase 4 프론트엔드: 실전매매 이력 페이지, 매매 요약 섹션, 사이드바 메뉴
- 인프라: BacktestService @Transactional 제거, 테이블 격리 검증, healthcheck, Rate Limiting 등

---

## 핵심 아키텍처 포인트

### DB 테이블 소유권 (Flyway V1~V13)

```
crypto_auto_trader (단일 DB, TimescaleDB)
├── [백테스팅 전용]  backtest_run / backtest_metrics / backtest_trade
├── [모의투자 전용]  paper_trading.virtual_balance / position / order / strategy_log
├── [실전투자 전용]  live_trading_session / public.position / public.order (session_id FK)
└── [공통 인프라]   candle_data(hypertable) / strategy_config / strategy_log / risk_config
```

### Spring Security 구조

- **인증 방식**: Static Bearer Token (`Authorization: Bearer <token>`)
- **공개 엔드포인트**: `/api/v1/health`, `/api/v1/trading/health/exchange`, `/swagger-ui/**`, `/v3/api-docs/**`
- **토큰 설정**: 백엔드 `API_AUTH_TOKEN` env / 프론트엔드 `build.args.NEXT_PUBLIC_API_TOKEN`
- **주의**: `NEXT_PUBLIC_*`는 `npm run build` 시 번들에 하드코딩 → Docker `environment`가 아닌 `build.args`로 전달해야 함

### CompositeStrategy 파이프라인

```
MarketRegimeDetector → StrategySelector → CompositeStrategy(Weighted Voting)
```
- per-session `ConcurrentHashMap<Long, MarketRegimeDetector>` — 세션 간 Hysteresis 상태 독립 유지
- Regime별 전략: TREND(Supertrend/EMA/ATR) / RANGE(Bollinger/RSI/Grid) / VOLATILITY(ATR/StochRSI)
- 상세 문서: [docs/CompositeStrategy.md](CompositeStrategy.md)

### 텔레그램 알림 구조

- **즉시**: 세션 시작/종료, 손절, 거래소 장애
- **일별 요약**: 12:00 + 00:00 KST (거래 없어도 전송)
- **주의**: 서버 재시작 시 인메모리 버퍼 초기화됨

### 스케줄러

| 스케줄러 | 주기 | 내용 |
|----------|------|------|
| MarketDataSyncService | 60초 fixedDelay | 캔들 데이터 동기화 |
| PaperTradingService | 60초 fixedDelay (초기 35초 지연) | 모의투자 전략 실행 |
| MarketRegimeAwareScheduler | 1시간 fixedDelay | 시장 상태 감지 + 전략 자동 스위칭 |
| TelegramNotificationService | 12:00 / 00:00 KST cron | 일별 매매 요약 전송 |

### 차트 스크롤

recharts `ResponsiveContainer`는 `overflow-x: auto` 내부에서 너비 측정 불가.
데이터 60개 초과 시 고정 px 너비 + `overflow-x-auto` 래퍼 직접 사용.

---

## 서버 명령어

### 로컬 (Windows)

```bash
docker compose up -d                    # DB + Redis 시작
./gradlew :web-api:bootRun              # 백엔드 (포트 8080)
cd crypto-trader-frontend && npm run dev  # 프론트엔드 (포트 3000)
```

### 운영 (Ubuntu)

```bash
cd ~/crypto-auto-trader

docker compose -f docker-compose.prod.yml up -d --build frontend backend  # 전체 재빌드
docker compose -f docker-compose.prod.yml up -d --build backend           # 백엔드만
docker compose -f docker-compose.prod.yml up -d --build frontend          # 프론트엔드만

docker compose -f docker-compose.prod.yml logs -f backend   # 로그 확인
docker compose -f docker-compose.prod.yml logs -f frontend

# 에러 원인 확인 (500 등 오류 발생 시)
docker compose -f docker-compose.prod.yml logs backend > /tmp/backend.log 2>&1
grep -n "ERROR\|Caused by\|Exception" /tmp/backend.log | tail -30
```

---

## 참고 문서

| 문서 | 위치 | 내용 |
|------|------|------|
| 전체 개발 상태 | `docs/DEV_STATUS_REVIEW_v3.md` | Phase별 완성도, 보강 이력 |
| 설계서 | `docs/DESIGN.md` | API, DB 스키마, UI 설계 |
| 계획서 | `docs/PLAN.md` | Phase별 개발 계획 |
| 검증 결과 | `docs/CHECK_RESULT.md` | 설계-구현 갭 분석 |
| 전략 개선 분석 | `docs/strategy_analysis_v4.md` | 10개 전략 상세 분석 + 고도화 로드맵 |
| 백테스트 결과 | `docs/BACKTEST_RESULTS.md` | 실행별 수익률·승률·MDD 기록 |
