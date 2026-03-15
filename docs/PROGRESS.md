# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝날 때마다 `## 최근 변경사항`과 `## 다음 할 일`을 반드시 업데이트한다.
> **마지막 갱신**: 2026-03-15 (리팩토링 완료 — Phase 4 프론트엔드 구현 완료, 백엔드 버그 수정)

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
| Phase 4 | **실전매매** (LiveTrading) | **~85%** — 백엔드/프론트엔드 구현 완료, 배포 및 실거래 검증 미완 |

### 구현된 전략 10종

VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI

---

## 최근 변경사항

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

- [ ] 텔레그램 수신 확인 (서버 재기동 후 12:00/00:00 정상 수신 여부)
- [ ] **Phase 4 실전매매 배포** — `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY` 환경변수 설정 후 서버 재빌드

### 단기 (1~2주)

- [ ] Report 에이전트 실행 (REPORT.md 최종 갱신)

### 보류/나중에

- [x] BacktestService / PaperTradingService / LiveTradingService 테이블 격리 검증 (2026-03-15 완료)
- [ ] Spring Security / API 인증 추가
- [ ] StrategyController DTO 전환 + Bean Validation (현재 `Map<String, Object>` 사용 — NPE 위험, 타입 안전성 부재)

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
