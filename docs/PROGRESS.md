# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 `## 다음 할 일`에서 해당 항목을 삭제하고, 완료 내용은 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-03-23 (비상정지 텔레그램 사유 전송, emergencyStopAll 세션별 알림, 12/24시 요약 버그 수정 — LiveTradingService SELL/BUY bufferTradeEvent 추가)

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
| 인프라 | Docker, Flyway V1~V23, SchedulerConfig, RedisConfig, Spring Security | **100%** |
| Phase 4 | **실전매매** (LiveTrading) | **~99%** — 장애 복구·수수료 추적·낙폭 경고·ORDERBOOK 호가창 REST 연동 완료 |
| Phase 5 | **손익 대시보드** (`/performance`) | **100%** — 실전/모의 탭, 요약 카드 7개, 세션별 테이블. 수수료 집계 정상화 완료 |

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

---

### 🔴 P1 — 즉시 (보안 · 데이터 보호)

> 운영 서버에서 즉시 적용해야 할 항목. 방치 시 자산 탈취 또는 데이터 전체 손실 가능.

- [ ] **API 토큰 기본값 제거** — `ApiTokenAuthFilter`의 `dev-token-change-me-in-production` 기본값 제거. 환경변수 미설정 시 서버 시작 실패하도록 `@Value` 필수 처리
- [ ] **Redis `requirepass` 설정** — 현재 인증 없는 Redis가 외부 노출 시 즉시 탈취 가능. `docker-compose.prod.yml` Redis 서비스에 `command: redis-server --requirepass ${REDIS_PASSWORD}` 추가 및 backend `SPRING_REDIS_PASSWORD` 환경변수 연동
- [ ] **Swagger 프로덕션 비활성화** — `/swagger-ui/**`, `/v3/api-docs/**` 인증 없이 공개. `SwaggerConfig.java`에 `@Profile("!prod")` 적용 또는 `SecurityConfig`에서 해당 경로 인증 요구
- [ ] **CORS 운영 도메인 한정** — `WebConfig.java` `allowedOriginPatterns("*")` → 실제 운영 도메인만 허용
- [ ] **TimescaleDB 백업 설정** — 실전 거래 기록이 `pgdata` 볼륨에만 존재. 디스크 손상 시 전체 손실. `docker-compose.prod.yml`에 `pg_dump` 크론 컨테이너 추가 또는 서버 crontab으로 일일 백업

---

### 🟡 P2 — 단기 (성능 · 코드 품질)

- [ ] **실전매매 금액 증액 판단** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 판단 기준: 2주 이상 운영 + 승률 ≥ 50% + 최대 낙폭 < 10%

---

### 🟢 P3 — 중기 (인프라 · 안정성) ✅ 완료

- [x] **`NEXT_PUBLIC_` 토큰 클라이언트 번들 노출** — `src/app/api/proxy/[...path]/route.ts` API proxy 생성. `NEXT_PUBLIC_API_TOKEN` → `API_TOKEN`(서버사이드 전용)으로 전환. `api.ts` baseURL을 `/api/proxy`로 변경
- [x] **`.env.example` 파일 추가** — 백엔드 `.env.example`에 `API_AUTH_TOKEN`, `REDIS_PASSWORD` 추가. 프론트엔드 `crypto-trader-frontend/.env.example` 신규 생성
- [x] **세션 생성 동시성** — `createSession()`에 `synchronized` 추가 (UI 버튼 중복 클릭 수준 방어)
- [x] **`AsyncConfig` 셧다운 미정리** — `orderExecutor`에 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)` 적용. `marketDataExecutor`(10s), `taskExecutor`(15s)도 동일 처리
- [x] **CI/CD 파이프라인 구성** — `.github/workflows/ci.yml` 추가. 백엔드(Gradle + TimescaleDB + Redis 서비스 컨테이너) / 프론트엔드(npm lint + build) / Docker 이미지 빌드(main 브랜치 push 시)
- [x] **`Map<String, Object>` 파라미터 타입 안전성** — `RsiConfig`, `MacdConfig`, `AtrBreakoutConfig`, `SupertrendConfig`, `OrderbookImbalanceConfig`, `StochasticRsiConfig`에 `fromParams()` 추가 (BollingerConfig, VwapConfig, EmaCrossConfig, GridConfig는 기존에 완료)

---

### ⏳ P4 — 전략 고도화 (차례대로)

> 현재 STOCHASTIC_RSI·MACD는 `BLOCKED_LIVE_STRATEGIES`로 실전 차단 완료. 전략별로 순서대로 진행.

#### 1단계. MACD 개선 (BTC -58.8% / ETH -57.6%)

현재 구현: ADX ≥ 25 추세 필터 존재하지만 손실 지속. 문제 원인:
1. 골든/데드크로스 발생 시 **히스토그램 방향** 미확인 — 크로스 직후 히스토그램이 이미 역전 중이면 가짜 신호
2. **제로라인(0) 위/아래 위치** 미필터링 — MACD 선이 0선 아래에서 골든크로스면 약세 구간 매수 위험
3. 손절선 없이 전략 청산 의존 — 강한 추세 역행 시 낙폭 큼

- [ ] **MACD 히스토그램 연속 확대 조건 추가** — 크로스 시점에 `현재 histogram > 이전 histogram` (방향 확인). `MacdStrategy.evaluate()` L73~88. 이 조건만으로 가짜 크로스 약 30% 필터링 예상
- [ ] **MACD 제로라인 필터** — BUY 신호: MACD 선이 0선 위에서 크로스할 때만 진입 (`currentMacd > 0`). SELL 신호: 0선 아래 크로스만. 횡보장 노이즈 감소
- [ ] **MACD 파라미터 최적화 백테스트** — 현재 (12, 26, 9) 기본값. BTC/ETH 각각 `fastPeriod`=8~15, `slowPeriod`=20~30 그리드 서치. 백테스트 UI의 벌크 실행 기능 활용

#### 2단계. STOCHASTIC RSI 재설계 (BTC -70.4% / ETH -67.6%)

현재 구현: ADX 상한선(≤30) 레인지 필터 존재. 문제 원인:
1. **신호 빈도 과다** — `oversoldLevel=15`, `overboughtLevel=85`는 임계값이 너무 낮아 횡보장에서도 빈번히 발동
2. **확인 조건 없음** — %K > %D 단순 크로스만으로 진입, 가격 모멘텀 미확인
3. **다이버전스 미활용** — RSI 다이버전스와 결합 시 정확도 향상 가능
4. 백테스트 결과 기준 **구조적 손실** → 개선 전까지 실전 차단 유지가 적절

- [ ] **StochRSI oversold/overbought 임계값 조정** — `oversoldLevel 15→20`, `overboughtLevel 85→80`으로 완화. 신호 발생 빈도 줄여 노이즈 감소. `StochasticRsiStrategy.java` L58~59 파라미터 기본값 변경
- [ ] **StochRSI %K-%D 크로스 연속 확인 조건** — 1캔들 크로스 즉시 진입 대신, 2캔들 연속으로 %K > %D 유지 시에만 매수. `kSeries.size() >= 3` 조건으로 구현
- [ ] **StochRSI 거래량 확인 조건 추가** — 진입 시 현재 캔들 거래량이 최근 20캔들 평균 이상일 때만 신호 발동. `IndicatorUtils.sma()` 활용
- [ ] **StochRSI + RSI 다이버전스 결합** — RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호. 구현 복잡도 높음 (Phase 3.5 수준)

#### 3단계. VWAP 개선 (BTC 거래 0건)

현재 구현: `thresholdPct=2.5%`, `adxMaxThreshold=25`. BTC에서 거래가 전혀 발생 안 한 원인 — ADX 25 필터로 대부분 HOLD. BTC는 추세장이 잦아 ADX≥25 구간이 많음.

- [ ] **VWAP 임계값 완화 + ADX 상한 상향** — `thresholdPct 2.5→1.5%`, `adxMaxThreshold 25→35`. 백테스트로 검증 후 적용. 단, 임계값을 너무 낮추면 노이즈 신호 증가 위험
- [ ] **VWAP 앵커 방식 개선** — 현재 최근 20캔들 rolling VWAP. 일봉/주봉 세션 시작점 앵커 VWAP 적용 시 BTC 단기 변동에 더 정확. `period` 파라미터 대신 캔들 타임스탬프 기반 세션 감지 필요

#### 4단계. 코인별 최적 전략 조합 적용

- [ ] **코인별 전략 프리셋 UI 추가** — 백테스트 결과 기반 추천 조합을 세션 생성 폼에 "빠른 선택" 버튼으로 제공. BTC 추천: `GRID + BOLLINGER`, ETH 추천: `ATR_BREAKOUT + EMA_CROSS + ORDERBOOK_IMBALANCE`
- [ ] **2023~2025 전체 기간 백테스트** — 현재 2025 H1 결과만 있음. 2023~2024 데이터 수집 후 전략별 장기 성과 검증 (강세장·약세장·횡보장 구간 포함)

---

## 서버 명령어

### 로컬 (Windows)

```bash
docker compose up -d                              # DB + Redis 시작
./gradlew :web-api:bootRun                        # 백엔드 (포트 8080)
cd crypto-trader-frontend && npm run dev          # 프론트엔드 (포트 3000)
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
