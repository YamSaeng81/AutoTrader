# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 `## 다음 할 일`에서 해당 항목을 삭제하고, 완료 내용은 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-03-25 (PROGRESS 정리 — 완료 항목 CHANGELOG 이관, 멀티 타임프레임 장기 검토로 이동)

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
│   ├── strategy-lib/     # 전략 16종 (단일 11 + 복합 5)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis)
```

> Phase 1~5, S1~S5, 인프라 모두 완료. 상세 이력은 [CHANGELOG.md](CHANGELOG.md) 참조.

### 구현된 전략 16종

단일 전략 (11종): VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램+제로라인) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

복합 전략 (5종): **COMPOSITE** (Regime 자동 선택) / **COMPOSITE_BTC V2** (MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 방향 필터) / **COMPOSITE_ETH** (ATR_BT×0.5 + Orderbook×0.3 + EMA×0.2) / **COMPOSITE_ETH_VD** (ATR×0.4 + OB×0.3 + VD×0.2 + EMA×0.1) / **MACD_STOCH_BB** (MACD+StochRSI+볼린저 6조건 AND)

### 주요 백테스트 결과 (KRW-BTC / KRW-ETH H1)

| 전략 | BTC | ETH | 비고 |
|------|-----|-----|------|
| COMPOSITE_BTC V2 | **+58.83%** | — | 24~25년, MDD -25.62% |
| COMPOSITE_ETH_VD | — | **평균 +70.3%** | MDD -12~-17% (리스크 대비 최우수) |
| COMPOSITE_ETH | — | 평균 +48.7% | MDD -26~-28% |
| MACD (최적화) | +151.9% | +216.0% | BTC(14,22) / ETH(10,26) |
| VWAP | 평균 +23.2% | — | 승률 높음, MDD 낮음 |
| GRID | +8.4% | +1.4% | 안정, 낮음 |
| ATR_BREAKOUT | -29.8% | +39.0% | ETH 전용 |
| STOCHASTIC_RSI | — | — | BLOCKED (구조적 결함) |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 다음 할 일

### 🔴 P1 — 전략 고도화

#### 1. STOCHASTIC RSI 구조적 개선
- [ ] **StochRSI + RSI 다이버전스 결합** — RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호 (구현 복잡도 높음)

#### 2. VOLUME_DELTA 테스트 작성
- [ ] 연속 상승 delta BUY, 연속 하락 delta SELL, 증가율 미달 HOLD

#### 3. MACD_STOCH_BB 활용도 향상
- [ ] **`MACD_STOCH_BB` → COMPOSITE TREND 서브필터 편입** (복잡도: 중)

---

### 🟡 P2 — 성능 · 운영

- [ ] **실전매매 금액 증액 판단** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 기준: 2주 이상 운영 + 승률 ≥ 50% + 최대 낙폭 < 10%

---

### 🟢 P3 — 보안 · 인프라

- [ ] **API 토큰 기본값 제거** — `ApiTokenAuthFilter`의 `dev-token-change-me-in-production` 기본값 제거. 환경변수 미설정 시 서버 시작 실패하도록 처리
- [ ] **Redis `requirepass` 설정** — `docker-compose.prod.yml` Redis에 `--requirepass ${REDIS_PASSWORD}` 추가 및 backend 연동
- [ ] **Swagger 프로덕션 비활성화** — `@Profile("!prod")` 적용 또는 SecurityConfig 인증 요구
- [ ] **CORS 운영 도메인 한정** — `allowedOriginPatterns("*")` → 실제 운영 도메인만
- [ ] **TimescaleDB 일일 백업** — `pg_dump` 크론 컨테이너 추가

---

### ⏳ 장기 검토

- [ ] **멀티 타임프레임** — 1H 방향 + 15M 진입. WebSocket 급등락 대응으로 단기 커버 중, 아키텍처 변경이 크므로 나중에 재검토
- [ ] **동적 가중치** — 100거래 이상 샘플 기반, 하한선 0.1, 변화 ≤ 10%/회 조건 필수. 오버피팅 주의 (복잡도: 고)
- [ ] **2023~2025 전체 기간 백테스트** — 강세장·약세장·횡보장 구간 포함. 현재 2025 H1 데이터만 존재

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
