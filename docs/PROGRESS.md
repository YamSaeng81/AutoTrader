# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 `## 다음 할 일`에서 해당 항목을 삭제하고, 완료 내용은 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-04-03 (백테스트 비동기 처리 + 텔레그램 완료/실패 알림 + 청크 안정성 개선)

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
| COMPOSITE_BTC V2 | **+58.83%** | — | 24~25년, MDD -25.62% ★주 전략 |
| COMPOSITE_ETH_VD | — | **평균 +70.3%** | MDD -12~-17% ★주 전략 |
| COMPOSITE (개선후) | +28.72% | -40.53% | BTC 플러스 전환, ETH 악화. 범용 한계로 주 전략 미채택 |
| COMPOSITE_ETH | — | 평균 +48.7% | MDD -26~-28% |
| MACD (최적화) | +151.9% | +216.0% | BTC(14,22) / ETH(10,26) |
| VWAP | 평균 +23.2% | — | 승률 높음, MDD 낮음 |
| GRID | +8.4% | +1.4% | 안정, 낮음 |
| ATR_BREAKOUT | -29.8% | +39.0% | ETH 전용 |
| STOCHASTIC_RSI | — | — | BLOCKED (구조적 결함) |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 다음 할 일

### 🚨 P0 — 실전매매 시스템 신뢰도 검토 (우선 완료 필요)

> 오늘 발견된 버그 2건(FAILED 주문 → 포지션 고착 / 리스크 카운팅 오류)을 계기로  
> 주문·포지션·손익 전체 흐름을 순서대로 점검한다.  
> **전략 손실은 검증 문제, 주문·손익 오류는 시스템 신뢰 문제 — 반드시 먼저 해결.**

---

#### 1단계 — 주문 상태 머신 (OrderExecutionEngine) ✅ 완료

- [x] **FAILED 주문 → 포지션 OPEN 고착 + KRW 미복원** — `reconcileOrphanBuyPositions` 30초 주기 스케줄러 추가
- [x] **고아 포지션이 리스크 한도 카운팅 포함** — `countRealPositionsByStatus` size>0만 카운팅으로 변경
- [x] **SELL 주문이 리스크 체크에 막혀 손절 불가** — `submitOrder()`에서 SELL 및 세션 BUY(positionId!=null)는 리스크 체크 스킵. 비세션 BUY만 체크
- [x] **PARTIAL_FILLED 상태** — `mapExchangeState()`에서 반환하지 않음. ACTIVE_STATES 정의만 있고 실제 할당 없음. Upbit 시장가 주문 특성상 문제 없음 (조사 결과 이슈 없음)
- [x] **CANCELLED + 부분 체결 후 미사용 KRW 미복원** — `OrderEntity.executedFunds` 필드 추가(V28 migration), `syncOrderState()` partialFill 경로에서 저장, `handleBuyFill()`에서 `executedFunds < quantity` 시 차액을 `session.availableKrw` 복원

---

#### 2단계 — 포지션·잔고 정합성 (LiveTradingService) ✅ 완료

- [x] **availableKrw Race Condition (async 스레드 DB 오류 시 KRW 복원 불가)** — `PositionEntity.investedKrw` 필드 추가(V29 migration) + `executeSessionBuy()`에서 저장. `reconcileOrphanBuyPositions()`에서 ① 주문 quantity 우선 ② investedKrw 폴백 ③ 주문 엔티티 없는 고아(5분 경과)도 investedKrw로 복원
- [x] **멀티 세션 포지션 카운팅 범위** — 설계 결정: 전역 포트폴리오 리스크 한도로 의도적 설계. 현재 단일 세션 운영에서 문제 없음. 멀티 세션 확장 시 재검토 필요
- [x] **totalAssetKrw 계산 정확성** — `availableKrw + posValue(size>0)` 방식으로 정확. CLOSING 중 일시적 과소평가는 수 초 내 해소되며 실질 문제 없음. `finalizeSellPosition()`에서 청산 후 `availableKrw`로 정확히 동기화됨
- [x] **stopSession() KRW 복원 누락 (높은 우선순위)** — `emergencyStopSession()`은 올바른 순서였지만 `stopSession()`은 `cancelSessionActiveOrders()` 없이 `closeSessionPositions()` 직접 호출 → PENDING 매수 주문 KRW 소실. `cancelSessionActiveOrders(sessionId)` 호출을 `closeSessionPositions()` 앞에 추가

---

#### 3단계 — 손익(PnL) 계산 정확성 ✅ 완료

- [x] **시장가 매수 평균 단가 계산** — `avgFillPrice = 8,000 / executed_volume`. Upbit이 수수료 차감 후 코인을 지급하므로 avgFillPrice에 매수 수수료(0.05%)가 정확히 내포됨. `soldQty × avgFillPrice = 8,000 (전액 비용기준)` — 정확 ✓
- [x] **finalizeSellPosition() 수수료 반영 (세션 경로)** — `proceeds = executedFunds(gross)`, `fee = proceeds × 0.0005 ≈ paid_fee`, `realizedPnl = netProceeds - costBasis` — 정확 ✓. 실제 계좌 변동과 수식 일치 확인
- [x] **handleSellFill() 수수료 미반영 (비세션 경로)** — 매도 수수료 0.05% 미차감으로 PnL 과다 계상. `grossProceeds - sellFee - costBasis`로 수정. (세션 거래에는 영향 없으나 정확성을 위해 수정)
- [x] **미실현 손익** — `(currentPrice - avgFillPrice) × size`. size=0이면 0 ✓. 매도 수수료 미선반영은 업계 표준으로 허용 가능

---

#### 4단계 — 스케줄러 동시성·중복 처리 ✅ 완료

- [x] **`pollActiveOrders` + `reconcileClosingPositions` 이중 처리** — `handleSellFill()`이 세션 SELL을 명시적 skip → `finalizeSellPosition()`은 `reconcileClosingPositions()`만 호출. `fixedDelay`로 같은 메서드 동시 실행 없음. `finalizeSellPosition()` 내 멱등성 guard("CLOSED" 체크)로 이중 처리 방어 ✓ 이슈 없음
- [x] **fixedDelay vs fixedRate 전수 확인** — 6개 전체 스케줄러 (`executeStrategies`, `reconcileClosingPositions`, `reconcileOrphanBuyPositions`, `pollActiveOrders`, `MarketDataSyncService`, `PortfolioSyncService`) 모두 `fixedDelay` ✓ `fixedRate` 없음
- [x] **`reconcileOrphanBuyPositions` + `executeSessionSell()` KRW 이중 복원** — WebSocket SL/TP(`marketDataExecutor` 스레드)와 30초 스케줄러가 동시에 size=0 OPEN 포지션 처리 시 이중 복원 가능. `PositionRepository.closeIfOpen()` (WHERE status='OPEN' 조건부 UPDATE) 추가 → 두 경로 모두 closeIfOpen()으로 원자적 처리. 반환 0이면 KRW 복원 스킵

---

#### 5단계 — 프론트엔드 UI 정합성 ✅

- [x] **열린 포지션 목록에서 size=0 포지션 필터링** — `openPositions` 필터에 `&& Number(p.size) > 0` 추가. 고스트 포지션(매수 실패 후 30초 대기 중)이 UI에 표시되지 않음
- [x] **가용현금 표시 vs 실제 업비트 잔고 비교 지표** — `accountApi.summary()` 30초 polling 추가. 내부 `availableKrw` 아래 "Upbit 실계좌" 실잔고 표시; 5,000원 이상 차이 시 노란색 경고 강조
- [x] **주문 FAILED 사유 표시** — `order.failedReason` 존재 시 주문 목록 각 행에 "실패: {reason}" 빨간색으로 인라인 표시

---

#### 6단계 — 통합 테스트 (검증) ✅

- [x] **FAILED 매수 → KRW 복원 E2E 테스트** — `reconcile_failedBuy_restoresKrw`: FAILED 주문 + size=0 포지션 세팅 후 `reconcileOrphanBuyPositions()` 호출 → KRW 복원 + 포지션 CLOSED 확인
- [x] **리스크 카운팅 정확성 테스트** — `countRealPositions_excludesGhostPositions`: size=0 2개 + size>0 2개 → `countRealPositionsByStatus` = 2, `countByStatus` = 4 확인
- [x] **maxPositions 한도 차단/허용 테스트** — 고아 포지션 2개는 한도 차지 안 함(승인), 실 포지션 2개는 maxPositions=2 차단 확인
- [x] **closeIfOpen() 원자적 멱등성 테스트** — 첫 호출 1 반환, 두 번째 0 반환 → 이중 KRW 복원 방지 검증
- [x] **이중 복원 방지 E2E 테스트** — `reconcile_alreadyClosedPosition_skipsKrwRestore`: 이미 CLOSED된 포지션 reconcile 재호출 시 KRW 불변 확인

**부대 수정 사항:**
- `PositionRepository.closeIfOpen()`: `@Modifying(clearAutomatically=true, flushAutomatically=true)` 추가 — JPA L1 캐시 오염 방지 (운영 안정성 개선)
- `PositionRepository.closeIfOpen()`: `@Modifying` import 누락 수정
- `schema-h2.sql`: V28(`executed_funds`), V29(`invested_krw`) 컬럼 추가 — H2 테스트 스키마가 운영 스키마와 동기화됨

---

### 🔴 P1 — 전략 고도화

#### 1. STOCHASTIC RSI 구조적 개선
- [ ] **StochRSI + RSI 다이버전스 결합** — RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호 (구현 복잡도 높음)

#### 2. VOLUME_DELTA 테스트 작성
- [ ] 연속 상승 delta BUY, 연속 하락 delta SELL, 증가율 미달 HOLD

#### 3. MACD_STOCH_BB 활용도 향상
- [ ] **`MACD_STOCH_BB` → COMPOSITE TREND 서브필터 편입** (복잡도: 중)

---

### 🟡 P2 — 성능 · 운영ㅈㅈ

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
