# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-04-21 (Self-Audit Tier1~4 CHANGELOG 이관 / analy.md 미완 서브항목 추가 / Tier5 장기 과제 정리)

---

## 📊 graphify 코드베이스 분석 결과 (2026-04-21)

> `graphify` 지식 그래프 파이프라인으로 프로젝트 전체를 분석한 결과. 산출물: `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.html`, `graphify-out/obsidian/`

### 분석 규모

| 항목 | 수치 |
|------|------|
| 전체 파일 | 414개 |
| 전체 단어 | ~283,501 words |
| 추출 노드 | 2,518개 |
| 추출 엣지 | 5,700개 |
| 커뮤니티 | 228개 |

### ⚠️ God Node — 단일 장애점 식별

| 노드 | 엣지 수 | 의미 | 권고 |
|------|---------|------|------|
| `of()` (CoinPair.of) | 206개 | 전체 시스템이 단 하나의 팩토리 메서드에 집중 | 불변 값 객체로 충분하나 테스트/모킹 시 취약. 향후 코인 추가 시 파급력 큼 |
| `LiveTradingService` | 48개 | 실전매매 로직 전체 집중 | OrderExecution / SessionLifecycle / RiskMonitor 3분리 권고 (§7로 부분 완화됨) |
| `BacktestEngine` | ~40개 | 백테스트 핵심 엔진 | Tier1-3 수정으로 개선 완료. 현재 수준 수용 가능 |

### 🏘️ 주요 커뮤니티 현황

| ID | 이름 | 핵심 노드 | 상태 |
|----|------|----------|------|
| C0 | Walk-Forward Validation | WalkForwardTestRunner, OOS metrics | ✅ §2 완료 |
| C1 | Live Trading Core | LiveTradingService, SessionBalanceUpdater | ✅ §7-10 완료 |
| C2 | Strategy Registry & Routing | StrategyLiveStatusRegistry, StrategySelector | ✅ §11 완료 |
| C3 | Risk Management | RiskManagementService, PortfolioSyncService | ✅ §5,8 완료 |
| C4 | Exchange Adapter | UpbitWebSocketClient, ExchangeHealthMonitor | ✅ §9 완료 |
| C5 | Backtest Metrics | MetricsCalculator, PerformanceReport | ✅ §1,13 완료 |
| C14 | Backtest Performance Results | 복합전략 5코인 × 8전략 백테스트 결과 | ✅ DB 저장 완료 |
| C18 | AI Pipeline & News Feed | LLM Task Router, NewsCollector | 🔵 구현 확인됨 |
| C21 | Discord Morning Briefing | DiscordNotificationService, MorningBriefingScheduler | 🔵 구현 확인됨 |
| C25 | Spring Security Config | SecurityConfig, JWT Filter | ⚠️ Bearer 토큰 인증 있으나 완전한 Security 구성 미검증 |

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
│   ├── strategy-lib/     # 전략 19종 (단일 11 + 복합 8)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis + db-backup)
```

### 구현된 전략 19종

**단일 전략 (11종)**: VWAP / EMA Cross / Bollinger Band / Grid / RSI / MACD / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

**복합 전략 (8종)**:

| 전략 | 구성 | 실적합 코인 (DB 검증) | 3년 백테스트 요약 |
|------|------|----------------------|-----------------|
| COMPOSITE | Regime 자동 선택 | 범용 | — |
| COMPOSITE_MOMENTUM | MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 필터 | ETH·SOL (BTC 비권장) | ETH +53.6%, SOL +59.8%, BTC +0.4% |
| COMPOSITE_ETH | ATR×0.5 + OB×0.3 + EMA×0.2 | ETH | 구버전 평균 +48.7% (재검증 필요) |
| COMPOSITE_BREAKOUT | ATR×0.4 + VD×0.3 + RSI×0.2 + EMA×0.1, EMA+ADX 필터 | **BTC·ETH** (SOL → V2 전환) | BTC +104.2%, ETH +38.9% |
| COMPOSITE_MOMENTUM_ICHIMOKU | COMPOSITE_MOMENTUM + Ichimoku 필터 | ETH·SOL·XRP | ETH +46.0%, SOL +62.9%, XRP +36.5% |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 | MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터 | **SOL·XRP·DOGE** (ETH는 V1 우위) | SOL +131.1% MDD -12%, DOGE +134.4%, XRP +49.9% |
| COMPOSITE_BREAKOUT_ICHIMOKU | COMPOSITE_BREAKOUT + Ichimoku 필터 | — | ⚠ BREAKOUT과 완전 동일 (ADX 필터 중복) |
| MACD_STOCH_BB | MACD + StochRSI + 볼린저 6조건 AND | ❌ 비활성화 | BTC -2.32% 17건, XRP -2.02% 3건 — 거래 극희소·수익성 없음 |

### 주요 백테스트 결과 요약 (KRW 마켓 H1, 2023-01 ~ 2025-12)

> DB 직접 조회 기준 (2026-04-13). 강세·약세·횡보 3년 포함. 5코인 × 4전략 배치 완료.

#### COMPOSITE_BREAKOUT

> ⚠ **Sharpe 재계산 대기 (2026-04-15)**: 아래 Sharpe 값은 per-trade × sqrt(365) 버그 하에서 산출된 값이며,
> 일별 equity curve 기반으로 재계산 시 약 4~5배 축소될 것으로 예상 (상세: [20260415_sharpe_audit.md](20260415_sharpe_audit.md)).
> 수익률·MDD·거래수는 영향 없음.

| 코인 | 수익률 | 승률 | MDD | Sharpe (구버그) | 거래수 |
|------|--------|------|-----|----------------|--------|
| **BTC** | **+104.24%** | 24.6% | -5.98% | ~~6.41~~ (재계산) | 61 |
| ETH | +38.92% | 14.8% | -8.90% | ~~3.16~~ (재계산) | 61 |
| SOL | +64.86% | 19.6% | -19.17% | ~~3.81~~ (재계산) | 46 |
| XRP | +10.64% | 10.6% | -17.14% | ~~0.85~~ (재계산) | 47 |
| DOGE | +17.69% | 16.3% | -16.77% | ~~1.84~~ (재계산) | 49 |

> ⚠ COMPOSITE_BREAKOUT_ICHIMOKU는 BTC/ETH/SOL/XRP 모두 BREAKOUT과 완전 동일 수치 → ADX 필터 중복 확인됨.

#### COMPOSITE_MOMENTUM / ICHIMOKU V1 / ICHIMOKU V2

| 코인 | MOMENTUM | ICHIMOKU V1 | ICHIMOKU V2 | 3년 권장 |
|------|----------|-------------|-------------|---------|
| BTC | +0.44% MDD -13.1% | +1.61% MDD -12.7% | +13.01% MDD -12.1% | ❌ BREAKOUT |
| ETH | +53.58% MDD -18.3% | +45.95% MDD -16.9% | +37.31% MDD -22.8% | **MOMENTUM** |
| SOL | +59.77% MDD -13.8% | +62.89% MDD -13.5% | **+131.07% MDD -12.0%** | **V2** 🔥 |
| XRP | +26.99% MDD -24.2% | +36.47% MDD -20.7% | +49.92% MDD -25.3% | **V2** |
| DOGE | +62.36% MDD -28.8% | +55.45% MDD -28.8% | **+134.42% MDD -29.2%** | V2 (MDD 주의) |

> **SOL**: V2가 BREAKOUT 대비 수익률 2배(+131% vs +65%), MDD도 낮음(-12% vs -19%) → **V2 전환 권고**.
> **DOGE**: V2 +134%로 전 코인 최고 수익률. 단 MDD -29%로 실전 투입 시 리스크 관리 필수.

#### 단일 전략 참고 (파라미터 최적화)

| 전략 | 코인 | 수익률 | 비고 |
|------|------|--------|------|
| MACD (14,22,9) | BTC | +151.9% Sharpe 1.68 | 단독, 2024~2025 H1 |
| MACD (10,26,9) | ETH | +216.0% Sharpe 1.61 | 단독, 2024~2025 H1 |

> 전체 결과: `docs/BACKTEST_RESULTS.md`

---

## 코인별 전략 매칭 (2026-04-20 갱신)

> **데이터 2중 근거**: ① 3년 백테스트(2023-01 ~ 2025-12) + Walk-Forward 검증 ② 실전 신호 품질 대시보드 (2026-04-20 기준)
> 실전 데이터 우선 — 백테스트와 실전이 충돌하면 실전을 따른다.

| 코인 | 권장 전략 | 백테스트 | 실전 4H | 실전 24H | 상태 |
|------|-----------|---------|---------|---------|------|
| BTC | **COMPOSITE_BREAKOUT** | +104.2% | 63% +0.11% | 61% -0.04% | ✅ 확정 |
| ETH | **COMPOSITE_MOMENTUM** | +53.6% | 43% -0.01% | **71% +0.06%** | ✅ 확정 (장기보유 강점) |
| SOL | **COMPOSITE_MOMENTUM_ICHIMOKU_V2** | +131.1% | 49% +0.05% | 52% -0.02% | ⏳ V2 전환 배포 대기 |
| XRP | **COMPOSITE_MOMENTUM_ICHIMOKU** | +36.5% | 41% -0.00% | 52% +0.01% | ✅ V1 유지 — V2 즉시 중단 ⚠️ |
| DOGE | **COMPOSITE_MOMENTUM_ICHIMOKU_V2** | +134.4% | 22% -0.58% | 56% +0.04% | ⏳ 소액 운영 중 — 모니터링 |

> ⚠️ **XRP V2 긴급 경고**: `COMPOSITE_MOMENTUM_ICHIMOKU_V2` + KRW-XRP 조합 실전 24H 적중률 **9%, 평균 -2.20%** — 즉시 세션 종료 검토 필요.
> ⚠️ **DOGE MOMENTUM 금지**: `COMPOSITE_MOMENTUM` + KRW-DOGE 실전 4H/24H 모두 **0%** — 절대 사용 불가.

### 실전 신호 품질 현황 (2026-04-20 대시보드 기준, 최근 30일)

| 전략 | 코인 | 신호수 | 4H 적중률 | 4H 평균 | 24H 적중률 | 24H 평균 | 판정 |
|------|------|-------|----------|--------|-----------|--------|------|
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 | DOGE | 263 | 22% | -0.58% | 56% | +0.04% | 🟡 단기 약함, 장기 회복 |
| COMPOSITE_MOMENTUM_ICHIMOKU | XRP | 63 | 41% | -0.00% | 52% | +0.01% | ✅ 안정 |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 | SOL | 60 | 49% | +0.05% | 52% | -0.02% | 🟡 보통 |
| COMPOSITE_MOMENTUM_ICHIMOKU | ETH | 60 | 43% | -0.01% | **71%** | **+0.06%** | ✅ 장기보유 강점 |
| COMPOSITE_BREAKOUT | BTC | 49 | **63%** | **+0.11%** | 61% | -0.04% | ✅ 단기 강함 |
| COMPOSITE_MOMENTUM | BTC | 20+14 | 45%/43% | +0.16%/-0.0% | 65%/71% | **+0.32%/+0.49%** | ✅ 장기보유 탁월 |
| COMPOSITE_MOMENTUM | DOGE | 17 | **0%** | -0.69% | **0%** | -0.06% | 🚨 즉시 세션 중단 |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 | XRP | 15 | 38% | -0.04% | **9%** | **-2.20%** | 🚨 즉시 세션 종료 |
| COMPOSITE_BREAKOUT | ETH | 15 | 67% | +0.41% | 47% | -0.38% | 🟡 단기만 유효, 24H 역전 |
| COMPOSITE_BREAKOUT_ICHIMOKU | ETH | 10+15 | 63%/67% | +0.19%/+0.41% | 44%/53% | -0.41%/-0.37% | ❌ BLOCKED (BREAKOUT과 동일) |

### 전략×코인별 홀딩 전략

| 코인 | 전략 | 권장 홀딩 | 이유 |
|------|------|----------|------|
| BTC | COMPOSITE_BREAKOUT | **4H 내 익절** 우선 | 4H→24H 수익 역전(+0.11%→-0.04%). 빠른 익절이 유리 |
| BTC | COMPOSITE_MOMENTUM | **장기 홀딩** | 24H 65~71%, +0.32~+0.49%. 보유 기간이 길수록 좋아짐 |
| ETH | COMPOSITE_MOMENTUM_ICHIMOKU | **장기 홀딩** | 24H 71% +0.06%. 4H(43%)보다 24H가 훨씬 우수 |
| ETH | COMPOSITE_BREAKOUT | **4H 내 익절** | 4H 67%→24H 47%로 급락. 빠른 수익실현 필수 |
| XRP | COMPOSITE_MOMENTUM_ICHIMOKU | **중기 홀딩** | 4H 41%→24H 52%. 시간이 지날수록 확률 개선 |
| DOGE | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | **24H 이상 홀딩** | 4H 22% 불안정 → 24H 56%로 회복. 조기 손절 주의 |

### BTC — COMPOSITE_BREAKOUT 확정

- **3년 성과**: +104.2%, Sharpe 6.41(버그), MDD -5.98% — 전 전략·전 코인 통틀어 가장 안정적인 MDD
- **Walk-Forward**: BREAKOUT OOS 합계 **+25.54%** (score 0.6741). W1 OOS +21.79%(7건) 강한 실증.
- **MOMENTUM 배제**: 3년 BTC MOMENTUM +0.4% — 사실상 수익 없음. OOS **+0.64%** (score 0.4528 CAUTION).

### ETH — COMPOSITE_MOMENTUM 확정

- **3년 성과**: +53.6%, MDD -18.3%
- **Walk-Forward**: MOMENTUM OOS 합계 **+13.50%** (score 3.5595). W1 OOS +17.32%(13건) 핵심 근거.
- **V1·V2 배제**: Ichimoku 필터 추가 시 ETH 오히려 성과 저하 (V1 +46.0%, V2 +37.3%).

### SOL — V2 전환 권고 (배포 대기)

- **3년 성과 비교**: V2 +131.1% MDD -12.0% vs BREAKOUT +64.9% MDD -19.2% — 수익률 2배, MDD도 낮음
- **Walk-Forward**: BREAKOUT OOS +12.05%이나 W0 단일 윈도우(+16.57%) 의존. V2 OOS -4.35%이나 거래 수 충분(8~13건/윈도우)하여 통계 신뢰도 높음.
- **결론**: 3년 시뮬레이션 압도적 우위 + WF BREAKOUT 이상치 의존성 → V2 전환. 소액(1만원) 교체 리스크 낮음.

### XRP — 전환 보류 (소액 병행 후 재결정)

- **3년 성과 비교**: V2 +49.9% MDD -25.3% vs V1 +36.5% MDD -20.7%
- **Walk-Forward**: V1 OOS **-14.05%** (score 1.0172) vs V2 OOS **-14.40%** (score 0.7458). 두 전략 모두 OOS 전 윈도우 음수 — 어느 쪽도 검증 미완.
- **현재 방향**: V2 소액 병행 운영 후 실전 데이터 3개월 이상 확보 후 전환 결정.

### DOGE — V2 소액 투입 권고

- **3년 성과**: V2 +134.4% MDD -29.2% — 5개 코인 전 전략 통틀어 최고 수익률
- **Walk-Forward**: V2 OOS 합계 **+48.76%** (score 9.7151). W1~W3 3개 연속 OOS 양수(+33.17%, +14.37%, +13.82%).
- **주의**: MDD -29.2%. 1만원으로 시작하여 2주 이상 운영 후 MDD 실측치 확인.

---

## 다음 할 일

### 🔴 P1-1 — 전략 고도화

- [x] **FVG A단계 구현 완료**: `FairValueGapStrategy` + `FairValueGapConfig`. EMA 필터·최소 공백 크기 필터 포함.
- [x] **FVG A단계 5코인 × H1 3년 백테스트**: SOL +224% MDD -34% 유일 유의미. BTC/DOGE/XRP/ETH 모두 현재 전략 대비 열위.
- [ ] **FVG 전략 — B단계**: 평균 회귀 방식. FVG 존(상·하한) 상태 관리 → 이후 가격이 공백 구간 재진입 시 신호 발생. 오래된 존 만료 처리 포함.
- [ ] **STOCHASTIC_RSI 구조적 개선** — StochRSI + RSI 다이버전스 결합. RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호.
- [x] **VOLUME_DELTA 테스트 작성** (13개 전체 통과)

---

### 🔴 P1-2 — Self-Audit 미완 서브항목 (`docs/20260415_analy.md` 기반)

> Tier1~4 구현은 완료됐으나 각 항목의 세부 서브태스크 중 미구현 항목.

- [x] **SL/TP intra-H1 path 정확도 향상** (§3) — `ExitRuleChecker.checkCandleExitWithPath()` 신규 구현. OHLC 4-point 경로 재구성으로 H1 캔들 내 SL/TP 도달 순서를 결정. BacktestEngine에서 `checkCandleExit` 대체.
- [x] **SL/TP 동시 터치 Monte Carlo** (§3) — `resolveByMonteCarlo()` 구현. 경로 재구성으로도 순서 불확정 시(Doji 등) Monte Carlo 200회 시뮬레이션으로 SL/TP 선도 확률 결정.
- [x] **리스크 구간 손실 재정의** (§5) — 글로벌 포트폴리오 드로우다운 체크 추가. `RiskEngine.check()` 6-파라미터 오버로드, `RiskManagementService.calculatePortfolioDrawdownPct()`, V48 마이그레이션, `RiskConfigEntity` 필드 추가.
- [x] **WeightOverrideStore DB 이력 저장** (§6) — `weight_optimizer_snapshot` 테이블(V49), `WeightOptimizerSnapshotEntity`, `WeightOptimizerSnapshotRepository`, `StrategyWeightOptimizer.saveSnapshot()` + `restoreFromSnapshot()` 구현.
- [ ] **단일 전략 백테스트 기간 분리 문서화** (§12) — 파라미터 탐색 2023~2024 / 검증 2025 독립 분리. MACD(14,22,9) +151.9% 등 단일 전략 결과의 과적합 여부 재검증.
- [ ] **2022 약세장 데이터 수집 + 재백테스트** (§13) — BTC·ETH 최소. 크립토 Winter 구간 포함 여부로 전략 견고성 재검증.
- [ ] **테스트 커버리지 보강** (§15) — `BacktestJobService` · `PaperTradingService` · `SignalQualityService` 전용 테스트 작성.
- [ ] **세션별 에러 카운트 대시보드** (§16) — Prometheus Counter 기존 구성됨. Grafana 대시보드 패널 추가 필요.
- [ ] **로그 중앙화** (§16) — Loki 또는 CloudWatch Logs 연동. 현재 Docker logs grep 수준 → 운영 스케일 부족.
- [ ] **API key rotation 정책 수립** (§18) — Upbit Access/Secret Key 주기적 교체 프로세스 + IP 화이트리스팅 적용 여부 재확인.

---

### 🟡 P2-0 — 실전 테스트 및 전략 검증

> Walk-Forward 전체 완료 (2026-04-14): BTC·ETH 현재 전략 검증 완료. SOL V2 전환 권고. XRP 보류. DOGE V2 투입 권고.

- [ ] **SOL 전략 V2 전환 배포** — COMPOSITE_BREAKOUT → COMPOSITE_MOMENTUM_ICHIMOKU_V2. 배포 대기.
- [ ] **DOGE V2 소액 투입 배포** — 1만원으로 시작. 2주 운영 후 MDD 실측치 확인.
- [ ] **XRP 실전 병행 운영** — V1 유지하되 V2 소액 병행. 3개월 후 실전 수익률 비교 후 전환 결정.
- [ ] **실전매매 금액 증액** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 기준: 2주 이상 운영 + 승률 ≥ 50% + MDD < 10%

---

### ⏳ 장기 검토

**전략·엔진 고도화**
- [ ] **멀티 타임프레임** — 1H 방향 + 15M 진입. 아키텍처 변경 큰 편.
- [ ] **동적 가중치 완성** — 인프라(`WeightOverrideStore` + `StrategySelector`) 구축 완료. 100거래 이상 샘플 기반, 하한선 0.05, 스무딩 70/30 적용 예정.
- [ ] **칼만 필터 스캘핑 전략 (5m/15m)** — H1은 노이즈 적어 효용 낮음. 선행 조건: 수수료 시뮬레이션 + FVG A/B 완료 후.
- [ ] **LiveTradingService 분리** (graphify God Node) — OrderExecutionService / SessionLifecycleService / RiskMonitorService 3분리.

**통계·검증 고도화** (analy.md Tier5-A)
- [ ] **Deflated Sharpe / PBO** — 다전략 튜닝 선택 편향 보정.
- [ ] **Bootstrap 신뢰구간** — 3년 백테스트 결과 95% CI 산출.
- [ ] **Benchmark 비교** — HODL BTC·ETH 대비 alpha·beta 분리.

**포트폴리오 확장** (analy.md Tier5-B)
- [ ] **포트폴리오 알로케이션** — 다중 코인/전략 correlation 기반 자금 분배 (현재 코인당 독립).
- [ ] **Risk Parity / Kelly Fractional** — 현재 고정 `investRatio` 개선.
- [ ] **라이브 A/B 테스트 프레임워크** — 새 전략 소액 병행 + 통계적 차이 자동 판정.

**데이터·인프라** (analy.md Tier5-C/D)
- [ ] **Historical data 2018~2022** — 약세장 데이터 적재 (최소 BTC·ETH).
- [ ] **Auto re-optimization** — 주간 walk-forward 재실행 → StrategyRegistry 자동 업데이트 제안.
- [ ] **Telegram/Discord 명령어** — `/stop ETH`, `/pnl today`, `/emergency` 원격 제어.

---

## 서버 명령어

### 로컬 (Windows)

```bash
docker compose up -d                                                       # DB + Redis 시작 (로컬은 비밀번호 없음)
./gradlew :web-api:bootRun --args='--spring.profiles.active=local'        # 백엔드 (포트 8080)
cd crypto-trader-frontend && npm run dev                                   # 프론트엔드 (포트 3000)
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
