# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝날 때마다 `## 다음 할 일`과 `## 최근 변경사항`을 반드시 업데이트한다.
> **마지막 갱신**: 2026-03-17 (Spring Security API 인증 추가)

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

- [ ] `🔴 HIGH` **실전매매 소액 테스트** — 세션 생성 → 1건 체결 확인 → 즉시 종료
- [ ] `🟡 MEDIUM` 텔레그램 수신 확인 (서버 재기동 후 12:00/00:00 정상 수신 여부)

### 전략 고도화 후속 (백테스트 결과 기반)

- [ ] `🔴 HIGH` **STOCHASTIC_RSI 구조 재설계 또는 제거** — ADX 필터 후에도 BTC -70.4%, ETH -67.6%. 파라미터 문제가 아닌 구조적 결함
- [ ] `🔴 HIGH` **MACD 히스토그램 기울기 필터 추가** — ADX > 25 필터로도 BTC -58.8%, ETH -57.6%
- [ ] `🟡 MEDIUM` **VWAP 임계값 재조정** — BTC 승률 0% (거래 없음). thresholdPct 2.5% → 1.5% 재테스트
- [ ] `🟡 MEDIUM` **코인별 전략 선택 최적화** — BTC: GRID+BOLLINGER / ETH: ATR_BREAKOUT+EMA_CROSS+ORDERBOOK
- [ ] `🟢 LOW` 2023~2025 전체 기간 백테스트 (현재 2025년만 결과)

### 단기 (1~2주)

- [ ] `🟡 MEDIUM` TradingController 예외 처리 패턴 통일 (커스텀 예외 클래스 도입)
- [ ] `🟡 MEDIUM` StrategyController DTO 전환 + Bean Validation (현재 `Map<String, Object>`)
- [ ] `🟢 LOW` EngineConfig `@ConditionalOnProperty` 전환 (null Bean 방지)
- [ ] `🟢 LOW` PaperTradingService 다중 포지션 totalKrw 계산 (다중 코인 지원 시)

---

## 최근 변경사항

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
| 전략 개선 분석 | `docs/strategy_analysis_v3.md` | 10개 전략 상세 분석 + 고도화 로드맵 |
| 백테스트 결과 | `docs/BACKTEST_RESULTS.md` | 실행별 수익률·승률·MDD 기록 |
