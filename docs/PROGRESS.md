# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-04-27 (라이브 30일 분석 후 청산/ADX 핫픽스 적용)

---

## 🔧 2026-04-27 라이브 분석 기반 핫픽스

30일 라이브 데이터 분석 결과 승률 1.63%, 121건이 "동가 청산 → 수수료만 손실" 패턴. 다음 3건 적용:

1. **SELL 신호 최소 보유시간 가드** (`LiveTradingService`)
   - `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT = 30분`
   - 진입 30분 이내의 전략 SELL 신호는 차단 (SL/TP는 항상 작동)
   - 신호품질 로그에 차단 사유 기록
2. **CompositeStrategy ADX 필터 파라미터화** (`CompositeStrategy`)
   - `adxThreshold`, `adxPeriod`, `skipAdxFilter` params로 override 가능
   - LiveTradingService가 RANGE 레짐 자동 감지 시 `adxThreshold=15.0`으로 완화
   - 4월 24일 시작 BREAKOUT 세션 4개(SOL/XRP/ETH/BTC)가 ADX(16~18)<20 으로 100% 차단되던 문제 해소
3. **BUY 차단(이미 보유) 시 신호 강도/보유 손익 비교 로깅**
   - 향후 피라미딩/교체 정책 설계용 데이터 수집
   - blockedReason: "이미 포지션 보유 중 (신규신호강도=X, 보유포지션 pnl=Y%, 보유시간=Z분)"

후순위(미적용): MACD_STOCH_BB SELL 조건(72% SELL 편향) — 현재 미사용 전략이라 보류.

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

### 2026-04-24 백테스트 & Walk-Forward 재실행

> **소스**: `docs/backtest_history_20260424.csv` (H1 FULL, 필터 없음), `docs/backtest_history_20260424_local.csv` (H1 FULL, **EMA200 필터 적용**), `docs/walk_forward_history_20260424_local.csv` + `(3).csv` (EMA200 필터 WF).
> **공통 조건**: 2022-01-01 ~ 2026-04-24, 초기자금 1,000만 (WF는 100만), 슬리피지 0.1% + 수수료 0.05%.
> **선행 조치**: M15 결과는 전면 폐기 (오버트레이딩으로 -99% 속출). 모든 후속 분석은 H1 기준.
> **EMA200 레짐 필터**: `BacktestEngine.isAboveEma200()` 구현 완료. 현재가 > EMA200일 때만 BUY 진입 허용. SELL(청산)은 레짐 무관.

#### H1 FULL 백테스트 — EMA200 필터 적용 후 코인별 최고 성과

| 코인 | 최고 전략 | 수익률 | MDD | Sharpe | 거래수 | 변화 |
|------|-----------|--------|-----|--------|--------|------|
| **BTC** | COMPOSITE_BREAKOUT | **+106.71%** | -8.88% | 1.24 | 79 | ↑ (+7%, MDD 개선) |
| **ETH** | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | +58.00% | -13.31% | 0.75 | 150 | ↑ 소폭 개선 |
| **SOL** | COMPOSITE_BREAKOUT | +62.79% | -20.45% | 0.66 | 58 | ↑ (전략 교체) |
| **XRP** | COMPOSITE_MOMENTUM_ICHIMOKU | +1.04% | -24.22% | 0.09 | 104 | ↓ **EMA200 역효과** |
| **DOGE** | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | +124.77% | -30.75% | 0.87 | 173 | ↓ 소폭 감소 |
| **ADA** | COMPOSITE_BREAKOUT | **+86.98%** | -14.14% | 0.96 | 46 | 🆕 신규 발굴 |

> FAIR_VALUE_GAP은 H1에서도 BTC -69%, ETH -82%, ADA -77% 등 메이저 코인 전부 대파. **전략 자체 구조 문제로 판단, 배포 금지.**
> XRP는 EMA200 아래 구간에서도 수익 패턴이 존재 → EMA200 필터가 역효과. XRP는 CB 전략 자체 엣지로 운영.

#### Walk-Forward AGG_OUT — EMA200 필터 적용 (2022-01-01 ~ 2026-04-24)

| 코인 | CB | CM | CMI | CMI_V2 | 최고 | 비고 |
|------|-----|-----|------|--------|------|------|
| **BTC** | +3.68% | +1.86% | +1.99% | +1.99% | CB | 필터 후 WF 감소 (2026 기간 차이) |
| **ETH** | **+4.17%** | -4.63% | -4.63% | -5.64% | CB | CB만 양수 |
| **SOL** | +24.30% (MDD -8.2%) | +26.25% | **+26.64%** | +20.30% | CMI | 전략 모두 양수 ✅ |
| **XRP** | **+25.98%** | +1.37% | -5.70% | -7.97% | CB | CB만 양수 |
| **DOGE** | -22.44% | -5.19% | -11.96% | **+2.57%** | CMI_V2 | 필터 역효과 전반적 |
| **ADA** | **+34.76%** (MDD -4.0%) | -8.32% | -8.32% | -12.54% | CB | ⚠️ 거래수 12건, 신뢰성 낮음 |

#### 시장 레짐별 윈도우 패턴 (전 전략 공통)

| 윈도우 | 기간 | Out-Sample 경향 |
|--------|------|----------------|
| W0 | 2022 하반기 (하락장 끝) | 대부분 손실 (-5~-15%) |
| W1 | 2023 여름~가을 (횡보·약세) | **전 전략 손실** (-2~-10%) |
| W2 | 2024 여름 (회복 초입) | 혼재 |
| W3 | 2025 상반기 (강세장) | **전 전략 수익** (+5~+29%) |
| W4 | 2025 Q4~2026 Q1 (변동성 확대) | 코인별 혼재 |

> EMA200 필터로 SOL은 전 전략 WF 양수 전환. DOGE·XRP 일부 전략은 필터 역효과 — 코인별 특성 고려 필요.

---

## 🟢 배포 권고 / 🚨 배포 금지 (2026-04-24 EMA200 필터 기준)

> EMA200 레짐 필터 적용 후 WF 재검증 결과 기준. 이전(필터 없음) 가이드는 폐기.

### Tier 1 — 즉시 소액 투입 가능 (WF 검증 통과)

| 코인 | 권장 전략 | WF OOS | MDD | Sharpe | 근거 |
|------|-----------|--------|-----|--------|------|
| **BTC** | **COMPOSITE_BREAKOUT** | +3.68% | -8.72% | 0.19 | FULL +106.7%. MDD 안정. WF 낮지만 필터로 손실 구간 차단 |
| **ETH** | **COMPOSITE_BREAKOUT** | +4.17% | -6.22% | 0.21 | 4전략 중 CB만 양수. MDD 최저 |
| **SOL** | **COMPOSITE_BREAKOUT** | +24.30% | **-8.24%** | 0.54 | MDD 최저 우선. CMI(+26.6%)와 수익 차이 근소 |
| **XRP** | **COMPOSITE_BREAKOUT** | +25.98% | -13.18% | 0.42 | EMA200 필터에도 CB만 양수 유지 — 가장 robust |

### Tier 2 — 관찰 후 투입

| 코인 | 권장 전략 | WF OOS | 판단 |
|------|-----------|--------|------|
| **DOGE** | CMI_V2 | +2.57% (MDD -13.5%) | EMA200 필터 역효과로 전략 전반 부진. CMI_V2만 근소 양수. 현행 유지하되 **EMA200 예외 처리 코드 검토 필요** |
| **ADA** | **COMPOSITE_BREAKOUT** | +34.76% (MDD -4.0%) | FULL +87%, Sharpe 0.96 우수. **단, WF 거래수 12건으로 통계 신뢰성 부족** — 소액 관찰 후 판단 |

### 🚨 배포 금지

| 조합 | 사유 |
|------|------|
| **전 코인 × M15 타임프레임** | 오버트레이딩 + 수수료 잠식으로 -99% 속출. M15 전면 비활성화 |
| **전 코인 × FAIR_VALUE_GAP** | H1에서도 메이저 코인 -69~-82%. 전략 로직 자체 구조 문제 |
| **ETH × CM / CMI / CMI_V2** | EMA200 필터 후 WF 모두 음수 (-4.6~-5.6%) |
| **XRP × CM / CMI / CMI_V2** | EMA200 필터 후 WF 음수 또는 근0 |
| **DOGE × CB / CM / CMI** | EMA200 필터 역효과로 WF -5~-22% |
| **BTC × CM / CMI / CMI_V2** | WF 모두 2% 미만, CB 대비 열위 |

### 운영 세션 조치 사항

- ⚠️ **SOL 전환**: CMI_V2 → **COMPOSITE_BREAKOUT** (EMA200 필터 후 재검증에서도 CB MDD 최저)
- ⚠️ **ETH 전환**: CMI → **COMPOSITE_BREAKOUT** (EMA200 필터 후 CB만 WF 양수)
- 🟢 **BTC / XRP**: CB 유지 — 변경 없음
- 🔵 **DOGE**: CMI_V2 유지. EMA200 예외 처리 후 재검증 필요
- 🔵 **ADA**: 소액 관찰 시작 가능. 거래수 누적 후 증액 판단

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
- [~] **단일 전략 백테스트 기간 분리 문서화** (§12) — 복합 전략은 2026-04-24 Walk-Forward(In-Sample 학습/Out-of-Sample 검증 5윈도우)로 과적합 여부 정량 평가 완료 (SOL/CMI_V2 -98% 저하 등 식별). **단일 전략 11종에 대한 동일 WF 실행은 미진행** — 필요 시 별도 태스크.
- [x] **2022 약세장 데이터 수집 + 재백테스트** (§13) — 2026-04-24 FULL 백테스트 및 WF 모두 **2022-01-01 시작**. W0(2022 하반기 하락장 말미) OOS 구간에서 전 전략 손실(-5~-15%) 확인 → 레짐 필터 필요성으로 연결. 크립토 Winter 견고성 평가 완료.
- [ ] **테스트 커버리지 보강** (§15) — `BacktestJobService` · `PaperTradingService` · `SignalQualityService` 전용 테스트 작성.
- [ ] **세션별 에러 카운트 대시보드** (§16) — Prometheus Counter 기존 구성됨. Grafana 대시보드 패널 추가 필요.
- [ ] **로그 중앙화** (§16) — Loki 또는 CloudWatch Logs 연동. 현재 Docker logs grep 수준 → 운영 스케일 부족.
- [ ] **API key rotation 정책 수립** (§18) — Upbit Access/Secret Key 주기적 교체 프로세스 + IP 화이트리스팅 적용 여부 재확인.

---

### 🟡 P2-0 — 실전 테스트 및 전략 검증 (2026-04-24 EMA200 필터 WF 재검증 반영)

> EMA200 레짐 필터 적용 후 WF 재검증 결과 기반. 이전 가이드 폐기.

- [ ] **SOL 전략 전환: CMI_V2 → COMPOSITE_BREAKOUT** — EMA200 필터 후 WF OOS CB +24.30% (MDD -8.24%). CB가 4전략 중 MDD 최저.
- [ ] **ETH 전략 전환: CMI → COMPOSITE_BREAKOUT** — EMA200 필터 후 CMI WF 음수(-4.6%). CB +4.17%로 유일 양수.
- [x] **XRP COMPOSITE_BREAKOUT 유지** — 필터 후에도 CB +25.98% 유지. 운영 변경 없음.
- [ ] **DOGE CMI_V2 유지 + EMA200 예외 처리 검토** — DOGE는 EMA200 아래에서도 수익 패턴 존재. 코인별 필터 on/off 설정 기능 또는 DOGE 전용 예외 로직 필요.
- [ ] **ADA COMPOSITE_BREAKOUT 소액 관찰** — FULL +87%, WF +34.76% 우수하나 WF 거래수 12건으로 신뢰성 부족. 소액 세션 시작 후 거래 누적 관찰.
- [ ] **FAIR_VALUE_GAP 전략 코드 리뷰 또는 폐기 결정** — 모든 타임프레임 × 모든 메이저 코인에서 구조적 손실. B단계 구현 전에 A단계 로직 방향성 재검증 필수.
- [ ] **M15 타임프레임 전 세션 비활성화** — H1 전용으로 운영 표준화.
- [x] **EMA200 레짐 필터 PoC (백테스트)** — `BacktestEngine.isAboveEma200()` 구현 완료. SOL 전 전략 WF 양수 전환 확인. DOGE 역효과 확인 → 코인별 예외 처리 과제로 분리.
- [x] **EMA200 레짐 필터 실전 적용** — `LiveTradingService.isAboveEma200Live()` 구현 완료. CANDLE_LOOKBACK 100→250 증가. DOGE 예외 처리 포함 (coinPair.contains("DOGE") 조건). SELL 신호 영향 없음.
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
