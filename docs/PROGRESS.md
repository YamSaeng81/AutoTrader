# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 `## 다음 할 일`에서 해당 항목을 삭제하고, 완료 내용은 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-03-22 (Circuit Breaker · Prometheus/Grafana · 텔레그램 비동기화 완료)

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

### ✅ 완료 (2026-03-22)

- [x] **Circuit Breaker (Auto Kill-Switch)** — MDD 임계값(기본 20%) 또는 연속 손실 한도(기본 5회) 초과 시 세션 강제 비상 정지. `RiskManagementService.checkCircuitBreaker()` + Flyway V24
- [x] **Prometheus/Grafana 모니터링** — Spring Actuator + Micrometer 추가, `docker-compose.prod.yml`에 prometheus(9090) + grafana(3001) 서비스 추가. `monitoring/` 디렉터리
- [x] **텔레그램 알림 비동기화** — `telegramExecutor` 전용 스레드풀 분리, 텔레그램 HTTP 호출이 매매 루프를 블로킹하지 않도록 수정

---

### 🔴 즉시 (실전매매 안정화)

- [ ] `🟡 MEDIUM` **실전매매 금액 증액 계획** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 판단 기준: 2주 이상 운영 + 승률 ≥ 50% + 최대 낙폭 < 10%

### 🟡 전략 고도화 (백테스트 결과 기반)

> 현재 STOCHASTIC_RSI·MACD는 `BLOCKED_LIVE_STRATEGIES`로 실전 차단 완료. 아래는 전략 구조 개선 과제.

#### MACD 개선 (BTC -58.8% / ETH -57.6%)

현재 구현: ADX ≥ 25 추세 필터 존재하지만 손실 지속. 문제 원인:
1. 골든/데드크로스 발생 시 **히스토그램 방향** 미확인 — 크로스 직후 히스토그램이 이미 역전 중이면 가짜 신호
2. **제로라인(0) 위/아래 위치** 미필터링 — MACD 선이 0선 아래에서 골든크로스면 약세 구간 매수 위험
3. 손절선 없이 전략 청산 의존 — 강한 추세 역행 시 낙폭 큼

- [ ] `🟡 MEDIUM` **MACD 히스토그램 연속 확대 조건 추가** — 크로스 시점에 `현재 histogram > 이전 histogram` (방향 확인). `MacdStrategy.evaluate()` L73~88. 이 조건만으로 가짜 크로스 약 30% 필터링 예상
- [ ] `🟡 MEDIUM` **MACD 제로라인 필터** — BUY 신호: MACD 선이 0선 위에서 크로스할 때만 진입 (`currentMacd > 0`). SELL 신호: 0선 아래 크로스만. 횡보장 노이즈 감소
- [ ] `🟡 MEDIUM` **MACD 파라미터 최적화 백테스트** — 현재 (12, 26, 9) 기본값. BTC/ETH 각각 `fastPeriod`=8~15, `slowPeriod`=20~30 그리드 서치. 백테스트 UI의 벌크 실행 기능 활용

#### STOCHASTIC RSI 재설계 (BTC -70.4% / ETH -67.6%)

현재 구현: ADX 상한선(≤30) 레인지 필터 존재. 문제 원인:
1. **신호 빈도 과다** — `oversoldLevel=15`, `overboughtLevel=85`는 임계값이 너무 낮아 횡보장에서도 빈번히 발동
2. **확인 조건 없음** — %K > %D 단순 크로스만으로 진입, 가격 모멘텀 미확인
3. **다이버전스 미활용** — RSI 다이버전스와 결합 시 정확도 향상 가능
4. 백테스트 결과 기준 **구조적 손실** → 개선 전까지 실전 차단 유지가 적절

- [ ] `🟡 MEDIUM` **StochRSI oversold/overbought 임계값 조정** — `oversoldLevel 15→20`, `overboughtLevel 85→80`으로 완화. 신호 발생 빈도 줄여 노이즈 감소. `StochasticRsiStrategy.java` L58~59 파라미터 기본값 변경
- [ ] `🟡 MEDIUM` **StochRSI %K-%D 크로스 연속 확인 조건** — 1캔들 크로스 즉시 진입 대신, 2캔들 연속으로 %K > %D 유지 시에만 매수. `kSeries.size() >= 3` 조건으로 구현
- [ ] `🟡 MEDIUM` **StochRSI 거래량 확인 조건 추가** — 진입 시 현재 캔들 거래량이 최근 20캔들 평균 이상일 때만 신호 발동. `IndicatorUtils.sma()` 활용
- [ ] `🟢 LOW` **StochRSI + RSI 다이버전스 결합** — RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호. 구현 복잡도 높음 (Phase 3.5 수준)

#### VWAP 개선 (BTC 거래 0건)

현재 구현: `thresholdPct=2.5%`, `adxMaxThreshold=25`. BTC에서 거래가 전혀 발생 안 한 원인 — ADX 25 필터로 대부분 HOLD. BTC는 추세장이 잦아 ADX≥25 구간이 많음.

- [ ] `🟡 MEDIUM` **VWAP 임계값 완화 + ADX 상한 상향** — `thresholdPct 2.5→1.5%`, `adxMaxThreshold 25→35`. 백테스트로 검증 후 적용. 단, 임계값을 너무 낮추면 노이즈 신호 증가 위험
- [ ] `🟡 MEDIUM` **VWAP 앵커 방식 개선** — 현재 최근 20캔들 rolling VWAP. 일봉/주봉 세션 시작점 앵커 VWAP 적용 시 BTC 단기 변동에 더 정확. `period` 파라미터 대신 캔들 타임스탬프 기반 세션 감지 필요

#### 코인별 최적 전략 조합 적용

- [ ] `🟡 MEDIUM` **코인별 전략 프리셋 UI 추가** — 백테스트 결과 기반 추천 조합을 세션 생성 폼에 "빠른 선택" 버튼으로 제공. BTC 추천: `GRID + BOLLINGER`, ETH 추천: `ATR_BREAKOUT + EMA_CROSS + ORDERBOOK_IMBALANCE`
- [ ] `🟢 LOW` **2023~2025 전체 기간 백테스트** — 현재 2025 H1 결과만 있음. 2023~2024 데이터 수집 후 전략별 장기 성과 검증 (강세장·약세장·횡보장 구간 포함)

### 🟡 안정성

- [ ] `🟢 LOW` **세션 생성 동시성** — UI 버튼 중복 클릭 시 세션 하나 더 생기는 정도. `createSession()`에 DB 레벨 유니크 제약 또는 `@Lock` 추가 고려
- [ ] `🟢 LOW` **`AsyncConfig` 셧다운 미정리** — 재시작 시 진행 중 주문 강제 종료. `reconcileOnStartup()`이 복구하므로 실질 위험 낮음

### 🟡 성능

- [ ] `🟡 MEDIUM` **`getPerformanceSummary()` N+1 쿼리 제거** — `listSessions()` 후 각 세션마다 `positionRepository.findBySessionId()` 개별 호출. 100+ 세션 시 응답 3초+ 지연. `@Query("SELECT p FROM PositionEntity p WHERE p.sessionId IN :ids")` 로 세션 IDs 일괄 조회 후 메모리 그루핑으로 변경 (`LiveTradingService:798-887`)

### 🟡 보안

- [ ] `🟡 MEDIUM` **Swagger 프로덕션 비활성화** — `/swagger-ui/**`, `/v3/api-docs/**` 인증 없이 공개. `SwaggerConfig.java`에 `@Profile("!prod")` 적용 또는 `SecurityConfig`에서 해당 경로 인증 요구
- [ ] `🟡 MEDIUM` **CORS 운영 도메인 한정** — `WebConfig.java` `allowedOriginPatterns("*")` → 실제 운영 도메인만 허용
- [ ] `🟡 MEDIUM` **API 토큰 기본값 제거** — `ApiTokenAuthFilter`의 `dev-token-change-me-in-production` 기본값 제거. 환경변수 미설정 시 서버 시작 실패하도록 `@Value` 필수 처리
- [ ] `🟡 MEDIUM` **`NEXT_PUBLIC_` 토큰 클라이언트 번들 노출** — 프론트 빌드에 평문 포함. 쿠키 기반 인증으로 전환 고려
- [ ] `🟡 MEDIUM` **Redis `requirepass` 설정** — 현재 인증 없는 Redis가 외부 노출 시 즉시 탈취 가능. `docker-compose.prod.yml` Redis 서비스에 `command: redis-server --requirepass ${REDIS_PASSWORD}` 추가 및 backend `SPRING_REDIS_PASSWORD` 환경변수 연동

### 🟢 인프라

- [ ] `🟡 MEDIUM` **TimescaleDB 백업 설정** — 실전 거래 기록이 `pgdata` 볼륨에만 존재. 디스크 손상 시 전체 손실. `docker-compose.prod.yml`에 `pg_dump` 크론 컨테이너 추가 또는 서버 crontab으로 일일 백업
- [ ] `🟢 LOW` **`.env.example` 파일 추가** — `API_AUTH_TOKEN`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `AUTH_PASSWORD`, `AUTH_SECRET`, `UPBIT_ACCESS_KEY`, `UPBIT_SECRET_KEY`, `REDIS_PASSWORD` 목록 문서화. 신규 서버 배포 가이드
- [ ] `🟢 LOW` **CI/CD 파이프라인 구성** — `.github/workflows/` 없음. `gradle build` + Docker 이미지 빌드 + SSH 배포 단계로 구성

### 🟡 코드 품질

- [ ] `🟡 MEDIUM` **수수료율 하드코딩 제거** — `TradingController` (line 217 추정)의 `FEE_RATE = new BigDecimal("0.0005")` 제거. Upbit `getOrderChance()` API가 실시간 수수료를 반환하므로 해당 값으로 교체. VIP 등급·마켓 종류별 수수료 차이 반영 가능
- [ ] `🟡 MEDIUM` **`RsiStrategy` RSI 중복 계산 제거** — 단일 `evaluate()` 호출 시 RSI를 3회 계산 (현재값 + 다이버전스 2회). 200캔들 × 10전략 × 분당 1회 = 6000번 중복. 계산 결과 캐싱 또는 한 번 계산 후 재사용으로 변경 (`RsiStrategy.evaluate()`)
- [ ] `🟢 LOW` **`Map<String, Object>` 파라미터 타입 안전성** — 전략 `evaluate()` 파라미터가 전부 `Map<String, Object>`로 런타임 ClassCastException 위험. 이미 존재하는 `RsiConfig`, `GridConfig` 등 전략별 Config 클래스 활용. P3 수준 리팩토링

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
