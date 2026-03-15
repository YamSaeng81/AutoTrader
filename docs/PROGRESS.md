# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝날 때마다 `## 최근 변경사항`과 `## 다음 할 일`을 반드시 업데이트한다.
> **마지막 갱신**: 2026-03-15

---

## 프로젝트 개요

- **서비스**: 업비트 기반 가상화폐 자동매매 시스템
- **운영 환경**: Ubuntu 서버, Docker Compose (`docker-compose.prod.yml`)
- **기술 스택**: Spring Boot 3.2 (Java 17) + Next.js 14 (TypeScript) + TimescaleDB + Redis

### 모듈 구조

```
crypto-auto-trader/
├── web-api/          # Spring Boot 백엔드 (Gradle 멀티모듈)
│   ├── core-engine/      # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── strategy-lib/     # 전략 10종
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 14 프론트엔드
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
| Phase 4 | **실전매매** (LiveTrading) | **미착수** |

### 구현된 전략 10종

VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI

---

## 최근 변경사항

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

### 보류/나중에

- [ ] DESIGN.md v1.3 갱신 (테이블 소유권 분리 구조 반영)
- [ ] Report 에이전트 실행 (REPORT.md 최종 갱신)
- [ ] BacktestService / PaperTradingService / LiveTradingService 테이블 격리 검증

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
