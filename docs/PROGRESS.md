# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 `## 다음 할 일`에서 해당 항목을 삭제하고, 완료 내용은 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-03-24 (P0 완료 — WebSocket 연결 버그 수정, 급등/급락 감지·SL 가속·TP 트레일링)

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
│   ├── strategy-lib/     # 전략 15종 (단일 11 + 복합 4)
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
| 인프라 | Docker, Flyway V1~V26, SchedulerConfig, RedisConfig, Spring Security, CI/CD, Prometheus/Grafana | **100%** |
| Phase 4 | **실전매매** (LiveTrading) | **~99%** — 장애 복구·수수료 추적·낙폭 경고·ORDERBOOK 호가창 REST 연동 완료 |
| Phase 5 | **손익 대시보드** (`/performance`) | **100%** — 실전/모의 탭, 요약 카드 7개, 세션별 테이블. 수수료 집계 정상화 완료 |

### 구현된 전략 15종

단일 전략 (11종): VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램+제로라인) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / **Volume Delta** (2026-03-24 신규)

복합 전략 (4종): **COMPOSITE** (Regime 자동 선택) / **COMPOSITE_BTC** (Grid×0.6 + Bollinger×0.4, EMA 방향 필터) / **COMPOSITE_ETH** (ATR_BT×0.5 + Orderbook×0.3 + EMA×0.2, 백테스트 가중치 분리) / **MACD_STOCH_BB** (MACD+StochRSI+볼린저 6조건 AND)

### 2025 H1 백테스트 결과 요약 (KRW-BTC / KRW-ETH)

| 전략 | BTC | ETH | 비고 |
|------|-----|-----|------|
| GRID | +8.4% | +1.4% | 양코인 안정 |
| ORDERBOOK_IMBALANCE | +0.8% | +30.6% | ETH 강세 |
| ATR_BREAKOUT | -29.8% | +39.0% | ETH 전용 |
| BOLLINGER | +3.2% | -37.0% | BTC 전용 |
| EMA_CROSS | -51.2% | +23.7% | BTC/ETH 역전 |
| STOCHASTIC_RSI | -70.4% | -67.6% | 구조적 결함 (BLOCKED) |
| MACD | -58.8% | -57.6% | 추가 개선 필요 (BLOCKED) |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 다음 할 일

### 🚨 P0 — 실전매매 즉시 개선

> 현재 P0 잔여 항목 없음. 완료 내역은 CHANGELOG.md 참조.

---

### 🔴 P1 — 전략 고도화 (즉시)

> STOCHASTIC_RSI·MACD는 `BLOCKED_LIVE_STRATEGIES`로 실전 차단 완료. 전략별로 순서대로 진행.

#### 1. MACD 파라미터 그리드 서치 백테스트
- [ ] BTC/ETH 각각 `fastPeriod`=8~15, `slowPeriod`=20~30 범위 벌크 실행. 현재 기본값 (12, 26, 9)

#### 2. STOCHASTIC RSI 구조적 개선
- [ ] **StochRSI + RSI 다이버전스 결합** — RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호 (구현 복잡도 높음)

#### 3. VOLUME_DELTA 후속 작업
- [ ] **`COMPOSITE_ETH` 편입 검토** — 후보 구성: `ATR_BREAKOUT×0.4 + ORDERBOOK_IMBALANCE×0.3 + VOLUME_DELTA×0.2 + EMA_CROSS×0.1`. 편입 전 백테스트로 신호 빈도 감소 여부 확인 필수
- [ ] **테스트 작성** — 연속 상승 delta BUY, 연속 하락 delta SELL, 증가율 미달 HOLD

#### 4. AI 리뷰 잔여 개선 항목 (2026-03-23 분석, 세부 내용은 CHANGELOG 참조)
- [ ] **`MACD_STOCH_BB` → COMPOSITE TREND 서브필터 편입** — 전략 활용도 향상 (복잡도: 중)
- [ ] **멀티 타임프레임** — 1H 방향 + 15M 진입 (복잡도: 고)
- [ ] **동적 가중치** — 100거래 이상 샘플 기반, 하한선 0.1, 변화 ≤ 10%/회 조건 필수. 오버피팅 주의 (복잡도: 고)

#### 5. 장기 검증
- [ ] **2023~2025 전체 기간 백테스트** — 강세장·약세장·횡보장 구간 포함. 현재 2025 H1 데이터만 존재

---

### 🟡 P2 — 성능 · 운영 (단기)

- [ ] **실전매매 금액 증액 판단** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 기준: 2주 이상 운영 + 승률 ≥ 50% + 최대 낙폭 < 10%

---

### 🟢 P3 — 보안 · 인프라 (중기)

- [ ] **API 토큰 기본값 제거** — `ApiTokenAuthFilter`의 `dev-token-change-me-in-production` 기본값 제거. 환경변수 미설정 시 서버 시작 실패하도록 처리
- [ ] **Redis `requirepass` 설정** — `docker-compose.prod.yml` Redis에 `--requirepass ${REDIS_PASSWORD}` 추가 및 backend 연동
- [ ] **Swagger 프로덕션 비활성화** — `@Profile("!prod")` 적용 또는 SecurityConfig 인증 요구
- [ ] **CORS 운영 도메인 한정** — `allowedOriginPatterns("*")` → 실제 운영 도메인만
- [ ] **TimescaleDB 일일 백업** — `pg_dump` 크론 컨테이너 추가

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
