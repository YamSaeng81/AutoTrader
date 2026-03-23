# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 `## 다음 할 일`에서 해당 항목을 삭제하고, 완료 내용은 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-03-23 (COMPOSITE_ETH 모드별 가중치 분리)

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

### 구현된 전략 11종

VWAP / EMA Cross / Bollinger Band / Grid / RSI(다이버전스) / MACD(히스토그램) / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / **MACD_STOCH_BB** (MACD+StochRSI+볼린저 복합 추세)

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

## AI 복합 전략 리뷰 분석 (2026-03-23)

> 제미나이 · OpenAI · 퍼플렉시티 3개 AI에게 `COMPOSITE_STRATEGIES_GUIDE.md`를 보여주고 받은 피드백 요약 및 내 반박/동의/우선순위 판단.

---

### 🟢 3개 AI 모두 동의 — 높은 신뢰도

#### 1. 글로벌 리스크 매니저 분리 (최우선)

**AI 공통 지적**: `MACD_STOCH_BB`에만 SL/TP가 있고, `COMPOSITE`/`COMPOSITE_BTC`/`COMPOSITE_ETH`에는 명시적 손절이 없다.

**내 판단**: **완전 동의. 가장 시급한 구조적 결함이다.**
현재 구조에서 `COMPOSITE_BTC`가 강한 하락장에 진입하면 그리드 하단에서 무한 물타기가 발생할 수 있다. 전략 레벨에 SL/TP를 두는 게 아니라 코어 엔진 상위에서 계좌 전체 리스크를 통제해야 한다.

```
제안 구조:
RiskManager (코어 엔진 상위)
  ├── globalStopLoss: 총 자본 대비 -5% 시 전 포지션 청산
  ├── maxPositionSize: 단일 코인 최대 노출 20%
  └── perTradeStopLoss: 진입가 대비 -3% (전략 오버라이드 가능)
```

#### 2. `COMPOSITE_BTC` 추세장 취약점 — EMA 방향 필터 추가

**AI 공통 지적**: GRID + BOLLINGER 둘 다 역추세 전략이라, 강한 상승/하락 추세에서 계속 역방향 포지션이 쌓인다.

**내 판단**: **동의. 퍼플렉시티 제안이 가장 구체적이다.**
- 일봉 200EMA 위 + 4H ADX > 25 → `COMPOSITE_BTC` 비활성화, 추세전략 우선 전환
- 단기적으로는 OpenAI 제안처럼 `if (EMA20 > EMA50) SELL 신호 억제` 정도만 추가해도 큰 개선

#### 3. `MACD_STOCH_BB` 조건 완화 — 6 AND → 가중 점수

**AI 공통 지적**: 6개 동시 충족은 너무 희소하여 실질 신호가 거의 없다.

**내 판단**: **부분 동의.**
보수성 자체는 설계 의도이므로 무조건 나쁜 건 아니다. 다만 퍼플렉시티 제안처럼 이 전략을 `COMPOSITE`의 "TREND 국면 전용 서브 필터"로 편입하면, 이미 추세 국면이 선별된 상황에서 눌림목만 잡는 역할로 매우 적합해진다.

#### 4. 동적 가중치 (Adaptive Weight)

**AI 공통 제안**: 최근 N번 승률로 하위 전략 가중치 자동 조정.

**내 판단**: **신중하게 접근해야 한다.** 과거 성과 기반 가중치 조정은 **오버피팅 위험**이 크다. 암호화폐 시장은 국면이 급변하므로 최근 10~20거래 승률로 가중치를 바꾸면 오히려 "방금 잘 됐던 전략에 몰빵" 하는 역효과가 생길 수 있다. 구현한다면 최소 100거래 이상의 샘플 + 하향 속도 제한(weight 변화 ≤ 10%/회) + 최저 하한선(min weight 0.1) 조건이 필수.

---

### 🟡 AI별 고유 지적 — 선별적 채택

#### OpenAI: 충돌 판단 로직 변경

**제안**: `buyScore > 0.4 AND sellScore > 0.4 → HOLD` 대신
`abs(buyScore - sellScore) < 0.1 → HOLD`로 변경.

**내 반박**: **현재 로직이 더 낫다.** OpenAI 제안대로라면 `buy=0.55, sell=0.50`일 때 차이가 0.05라 HOLD지만, `buy=0.80, sell=0.75`이면 차이 0.05라 HOLD가 된다 — 두 신호가 모두 강하게 상충하는데도 방치된다. 현재의 "양쪽 모두 0.4 이상이면 HOLD" 로직이 실제 상충 상황을 더 정확하게 잡아낸다.

#### OpenAI: `COMPOSITE` TRANSITIONAL 구간 "관망 모드" 추가

**내 판단**: **타당하다.** 현재 TRANSITIONAL에서 "직전 국면 전략 × 50%" 적용은 어중간하다. 직전 국면이 TREND였으면 추세 전략 50% 강도로 역추세 포지션을 잡을 수 있다. TRANSITIONAL에서는 포지션 축소(신규 진입 금지, 기존 포지션 유지) 정책이 더 안전하다.

#### 퍼플렉시티: `COMPOSITE_ETH` 백테스트/실시간 파라미터 분리

**내 판단**: **실용적이고 즉시 적용 가능하다.** 현재 백테스트 모드에서 오더북을 캔들 근사로 추정하는데, 이 경우 `ORDERBOOK_IMBALANCE` 가중치를 0.3 → 0.1로 낮추고 `ATR_BREAKOUT`을 0.5 → 0.7로 올리는 식의 모드별 가중치 프리셋이 있어야 한다.

#### 제미나이: 하이퍼 파라미터 자산별 최적화

**내 판단**: **장기 과제. 당장은 불필요.** ADX 임계값 25, ATR 배수 1.5 등을 각 자산 분포 기반으로 동적 결정하자는 제안인데, 현재 11종 전략에 이걸 다 적용하면 파라미터 폭발이 온다. BTC/ETH 이외 알트코인 지원 시점에 검토.

---

### 📋 도출된 개선 항목 (우선순위 순)

| 순위 | 항목 | 근거 | 복잡도 |
|------|------|------|--------|
| ~~1~~ | ~~**글로벌 RiskManager 분리** — 전략 외부에서 SL/TP/최대노출 통합 관리~~ | ~~3개 AI 공통, 자산 보호 직결~~ | ~~중~~ ✅ |
| ~~2~~ | ~~**`COMPOSITE_BTC` EMA 방향 필터** — 추세 감지 시 역추세 신호 억제~~ | ~~3개 AI 공통~~ | ~~저~~ ✅ |
| ~~3~~ | ~~**`COMPOSITE` TRANSITIONAL → 신규 진입 금지** — 기존 포지션 유지만 허용~~ | ~~OpenAI, 논리적 타당성~~ | ~~저~~ ✅ |
| ~~4~~ | ~~**`COMPOSITE_ETH` 모드별 가중치 분리** — 백테스트/실시간 가중치 프리셋 구분~~ | ~~퍼플렉시티, 즉시 적용 가능~~ | ~~저~~ ✅ |
| 5 | **`MACD_STOCH_BB` → COMPOSITE TREND 서브필터 편입** | 퍼플렉시티, 전략 활용도 향상 | 중 |
| 6 | **멀티 타임프레임** — 1H 방향 + 15M 진입 | 3개 AI 모두 언급, 수익률 개선 | 고 |
| 7 | **동적 가중치** — 100거래 이상 샘플 기반, 하한선 0.1 이상 유지 조건 필수 | 3개 AI 제안, 오버피팅 주의 | 고 |

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

### ⏳ P4 — 전략 고도화 (차례대로)

> 현재 STOCHASTIC_RSI·MACD는 `BLOCKED_LIVE_STRATEGIES`로 실전 차단 완료. 전략별로 순서대로 진행.

#### 1단계. MACD 개선 (BTC -58.8% / ETH -57.6%)

현재 구현: ADX ≥ 25 추세 필터 존재하지만 손실 지속. 문제 원인:
1. 골든/데드크로스 발생 시 **히스토그램 방향** 미확인 — 크로스 직후 히스토그램이 이미 역전 중이면 가짜 신호
2. **제로라인(0) 위/아래 위치** 미필터링 — MACD 선이 0선 아래에서 골든크로스면 약세 구간 매수 위험
3. 손절선 없이 전략 청산 의존 — 강한 추세 역행 시 낙폭 큼

- [ ] **MACD 파라미터 최적화 백테스트** — 현재 (12, 26, 9) 기본값. BTC/ETH 각각 `fastPeriod`=8~15, `slowPeriod`=20~30 그리드 서치. 백테스트 UI의 벌크 실행 기능 활용

#### 2단계. STOCHASTIC RSI 재설계 (BTC -70.4% / ETH -67.6%)

현재 구현: ADX 상한선(≤30) 레인지 필터 존재. 문제 원인:
1. **신호 빈도 과다** — `oversoldLevel=15`, `overboughtLevel=85`는 임계값이 너무 낮아 횡보장에서도 빈번히 발동
2. **확인 조건 없음** — %K > %D 단순 크로스만으로 진입, 가격 모멘텀 미확인
3. **다이버전스 미활용** — RSI 다이버전스와 결합 시 정확도 향상 가능
4. 백테스트 결과 기준 **구조적 손실** → 개선 전까지 실전 차단 유지가 적절

- [ ] **StochRSI + RSI 다이버전스 결합** — RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호. 구현 복잡도 높음 (Phase 3.5 수준)

#### 3단계. 코인별 최적 전략 조합 적용

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
