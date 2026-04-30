# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-04-30 (MTF 3종 구현 완료 + 17코인 × 7전략 H1 FULL 백테스트 비교 갱신)

---

## 📝 2026-04-30 주요 전략 분석 문서 작성

[docs/주요전략분석_v20260430.md](./주요전략분석_v20260430.md) — `COMPOSITE_BREAKOUT`,
`COMPOSITE_MOMENTUM_ICHIMOKU` (V1), `COMPOSITE_MOMENTUM_ICHIMOKU_V2` 3종 종합 분석.
구조·필터(ADX/EMA/Ichimoku)·하위 전략 가중치·V1↔V2 차이(VWAP→SUPERTREND)·
2026-04-24 백테스트 비교(KRW H1, ETH/SOL/XRP/MOVE/USDT/IP/FLOCK)·
코인별 전략 선택 의사결정 트리 포함.

---

## 🧨 2026-04-30 전략 분석 비판 기반 개선 로드맵

> 분석 문서([docs/주요전략분석_v20260430.md](./주요전략분석_v20260430.md))의 한계를
> 신랄하게 재검토한 결과, 다음 갭들이 식별됨. 우선순위별로 정리.

### 식별된 핵심 갭

1. **승률 11~19%, 백테스트 vs WF 13배 격차** → 사실상 long-tail 운에 베팅하는 lottery 구조. 통계적 검증 부재.
2. **MDD 미개선 (V1 = base, -25.62%)** → Ichimoku 필터는 위험관리가 아닌 노이즈 필터에 불과. 문서가 이 한계를 약하게 다룸.
3. **V1→V2 동기 미검증** → 거래수↑ + 손실 코인↑ 패턴이 "구조적 개선"이 아닌 단순 진입 빈도 증가일 가능성. HOLD 비율, 평균 보유시간, 익/손 분포 비교 미수행.
4. **RSI(0.2) 수학적 무의미** → 단독 sellScore>0.4 만들려면 confidence>2.0 필요(불가). "반쯤 죽은 가중치"를 그대로 둠.
5. **EMA 이중 카운팅** → EMA 방향 필터(EMA20/50) ↔ 하위 EMA_CROSS(EMA20/50) 동일 지표 중복. 가중치 0.1이 사실상 더 큰 영향.
6. **청산 정책 통째로 누락** → 14% 승률이면 SL/TP·trailing이 PnL 거의 전부를 결정하나 분석에 빠짐.
7. **ADX 필터의 자기모순** → 4/27 핫픽스 기록상 BREAKOUT 4개 세션 100% 차단됨. 그런데 분석은 ADX를 핵심 무기로 칭송.
8. **Ichimoku 절반만 사용** → 가격↔구름만 사용. Tenkan/Kijun 크로스, **Chikou Span**, 구름 두께/twist 모두 미사용.
9. **Regime 엔진 3중화** → BREAKOUT(자체 ADX), V1/V2(Ichimoku), `RegimeAdaptiveStrategy` 따로 작동. 통합 평가 부재.
10. **통계 유의성 검정 부재** — Sharpe CI, Profit Factor CI, t-test 한 줄도 없이 거래수 6~10건짜리를 결론에 사용.

### 🔴 P0 — 즉시 (검증 데이터 보강, 결론 재해석)

- [ ] **거래수 30건 미만 결과 본문 결론에서 분리** — 분석 문서 v2026-04-30 의 KRW-SUPER(6건), KRW-IP(7건) 등을 "참고" 섹션으로 이동. v2 개정.
- [ ] **MDD / Sharpe / Profit Factor / Calmar 컬럼 추가** — 수익률 단일 지표 결론 탈피. backtest_history 컬럼은 이미 존재 → 분석 문서 표 보강만 필요.
- [ ] **연도별 분리 백테스트** (2022/2023/2024/2025) — 어느 해에 어느 전략이 실제로 망하는지 노출. 현재 시장 사이클 통합 수치만 존재.
- [ ] **HOLD 비율 / 평균 보유시간 / 평균 익절·손절 비율 측정** — V1 vs V2 의 "진짜" 차이 정량화. BacktestEngine 결과 객체에 해당 메트릭 추가 또는 trade-level CSV로 후처리.
- [ ] **백테스트-WF 격차 95% CI 산출** — Bootstrap 1000회로 격차 신뢰구간 제시. 13배 격차가 우연 가능성 평가.

### 🟠 P1 — 단기 (전략 자체 개선)

- [x] **EMA 이중 카운팅 제거** — EMA_CROSS(0.1) → MACD(0.2) 교체. 가중치 ATR 0.5 / VD 0.3 / MACD 0.2 재조정. `CompositePresetRegistrar` 반영 완료.
- [x] **RSI(0.2) 재설계** — RSI 가중치 제거 + `RsiVetoStrategy` 래퍼 신규 구현. RSI>75 BUY 강제차단 / RSI<25 SELL 강제차단. `COMPOSITE_BREAKOUT` 및 `COMPOSITE_BREAKOUT_ICHIMOKU` 적용 완료.
- [x] **ADX 임계값 동적화** — `IndicatorUtils.adxList()` + `adxPercentileThreshold()` 신규. 최근 60캔들 ADX 30th percentile, [15, 25] 클램프. `CompositeStrategy` 적용 완료.
- [x] **Ichimoku 5요소 사용 확장** — `IchimokuFilteredStrategy` 3-레이어로 확장: (1) 구름 내부 차단, (2) Chikou Span vs 26봉전 가격, (3) Tenkan/Kijun 방향. 최소 캔들 52→78. 완료.
- [ ] **청산 정책 표준화** — 진입가 -3% 손절 / +6% 익절 후 ATR×2 trailing stop 으로 통일. 현재 `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT=30분` 만 존재 → 분석 문서·실전 모두 명시.
- [x] **분석 문서 v2 개정** — P0 결과 반영, "Ichimoku = 노이즈 필터 (위험관리 아님)" 명시, MDD 미개선을 메인 평가 섹션에 못박기. 완료.

### 🟡 P2 — 중기 (구조 통합)

- [ ] **Regime 엔진 통합** — `MarketRegimeDetector` 를 단일 진입점으로 만들어 BREAKOUT / V1 / V2 모두 동일 regime 신호를 입력으로 사용. 3중화 해소.
- [ ] **Walk-Forward 자동 재최적화 활성화** — `StrategyWeightOptimizer` 인프라 이미 구축됨 ([WeightOptimizerSnapshotEntity](../web-api/src/main/java/com/cryptoautotrader/api/entity/WeightOptimizerSnapshotEntity.java)). 90일마다 가중치 자동 재조정 스케줄러 활성화.
- [ ] **앙상블 메타 전략** — BREAKOUT / V1 / V2 출력 시그널을 voter 로 묶어 majority + confidence-weighted 최종 신호. (§ 새 전략 §1 `COMPOSITE_REGIME_ROUTER` 또는 그 상위 ensemble.)
- [ ] **Deflated Sharpe / PBO** — 다전략 튜닝 선택 편향 보정 (장기 검토 항목 승격).

---

## 🆕 2026-04-30 새로운 전략 / 기능 제안

> 비판 분석에서 도출된 신규 전략 7종 + 보호 메커니즘. ROI 우선순위 ★표시.

### ★ 1. `COMPOSITE_REGIME_ROUTER` (메타 전략) ✅ 구현 완료

단일 시점에서 ADX/ATR 변동성에 따라 BREAKOUT vs MOMENTUM 자동 위임.

```
VOLATILITY  (ATR > SMA×1.5, ADX < 25) → COMPOSITE_BREAKOUT  (ATR spike 돌파)
TREND       (ADX > 25)                 → CMI_V2              (강한 추세 모멘텀)
TRANSITIONAL (ADX 20~25)               → CMI_V1              (전환 구간 보수적)
RANGE       (ADX < 20)                 → HOLD                (횡보 진입 금지)
```

- Hysteresis 3회 연속 감지 시 전환 (MarketRegimeDetector 재사용).
- GRID stateful + RegimeDetector stateful → `registerStateful` 등록.
- 구현: [CompositeRegimeRouter.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeRegimeRouter.java)

### ★ 2. `COMPOSITE_MTF_CONFIRMED` / `COMPOSITE_MTF_BTC` / `COMPOSITE_MTF_MOMENTUM` (멀티 타임프레임) ✅ 구현 완료

H1 진입 신호 + H4 Supertrend 추세 동의 시에만 진입. `CandleDownsampler.java` 재사용.
- `COMPOSITE_MTF_BTC`: CB(H1) + Supertrend(H4) — **ETH +127.70%**, DOGE +82%, AAVE/CHZ 흑자 전환
- `COMPOSITE_MTF_MOMENTUM`: CMI_V2(H1) + Supertrend(H4) — **BLUR +48.06%**, DOGE +83%
- `COMPOSITE_MTF_CONFIRMED`: CRR(H1) + Supertrend(H4) — 범용, **XRP +3.37%** (유일 흑자)
- 구현: [MtfConfirmedStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/MtfConfirmedStrategy.java)

### ★ 3. `BLACK_SWAN_GUARD` (전 전략 공통 서킷 브레이커)

1시간 내 -5% 하락 또는 거래량 평균×5 초과 시 **전 신규 진입 차단 + 보유 trailing stop 0.3% 강화**.
LUNA/FTX 류 사건 방어 — 어떤 모멘텀/돌파 전략도 단독 방어 불가.

### 4. `COMPOSITE_BREAKOUT_VOL_ADAPTIVE`

ATR multiplier 1.5 고정 → 코인별 변동성 분포로 적응:
```
multiplier = 1.0 + (현재 ATR / ATR 90일 평균)
ADX threshold = ADX 90일 30th percentile
```
4/27 핫픽스(ADX 20→15) 의 영구 자동화.

### 5. `BAYESIAN_WEIGHT_TUNER`

정적 0.5/0.3/0.2 → 베이지안 사후확률 갱신:
```
매 100거래마다: weight_i ← weight_i × (실제 승률_i) / (예측 confidence 평균_i)
                재정규화 (합 1.0)
```
[WeightOverrideStore](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WeightOverrideStore.java) 인프라 활용. 코인별 자동 가중치 분화.

### 6. `CVD_DIVERGENCE`

기존 VolumeDeltaStrategy 의 다이버전스 모드를 *진입 신호 격하* → *역방향 진입 신호로 승격*.
가격 신고점 + CVD 신저점 = 약세 다이버전스 → 적극적 SELL. 횡보장에서도 매매 가능.

### 7. `KELLY_SIZED_COMPOSITE`

전략 신호 동일, 포지션 크기를 Kelly Criterion 으로:
```
Kelly% = W − (1−W)/R    (W=최근 30거래 승률, R=평균 익/손 비율)
실제 베팅 = Kelly% × 0.25  (Half-Kelly)
```
14% 승률 + R=8 이면 Half-Kelly ≈ 1.6%. 현재 동일 비중 베팅의 통계적 비효율 해소.

### 우선순위 권고

| 우선순위 | 전략 | 사유 |
|---------|------|------|
| ⭐⭐⭐ | `COMPOSITE_REGIME_ROUTER` | 기존 3전략 자산 재활용, 코인별 분리 운영 단순화 |
| ⭐⭐⭐ | `COMPOSITE_MTF_CONFIRMED` | 14% 승률 → 25%+ 가능, 인프라 존재 |
| ⭐⭐⭐ | `BLACK_SWAN_GUARD` | 모든 전략 공통 안전망. 비용 낮고 효과 큼 |
| ⭐⭐ | `COMPOSITE_BREAKOUT_VOL_ADAPTIVE` | 핫픽스 영구화 |
| ⭐⭐ | `KELLY_SIZED_COMPOSITE` | 자금 효율 개선 |
| ⭐ | `BAYESIAN_WEIGHT_TUNER` | 인프라 있으나 검증 필요 |
| ⭐ | `CVD_DIVERGENCE` | 기존 VD 보강 수준 |

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
│   ├── strategy-lib/     # 전략 22종 (단일 11 + 복합 11)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis + db-backup)
```

### 구현된 전략 22종

**단일 전략 (11종)**: VWAP / EMA Cross / Bollinger Band / Grid / RSI / MACD / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

**복합 전략 (11종)**:

| 전략 | 구성 | 실적합 코인 | 요약 |
|------|------|------------|------|
| COMPOSITE | Regime 자동 선택 | 범용 | — |
| COMPOSITE_MOMENTUM | MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 필터 | ETH·SOL | ETH +53.6%, SOL +59.8% |
| COMPOSITE_ETH | ATR×0.5 + OB×0.3 + EMA×0.2 | ETH | 구버전 평균 +48.7% (재검증 필요) |
| COMPOSITE_BREAKOUT (CB) | ATR×0.5 + VD×0.3 + MACD×0.2, EMA+ADX+RSI Veto 필터 | **BTC·ADA** | BTC **+106.71%**, ADA **+86.98%** |
| COMPOSITE_MOMENTUM_ICHIMOKU (CMI_V1) | CB_MOMENTUM + Ichimoku 필터 | XRP | XRP +1.04% (유일 양수) |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 (CMI_V2) | MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터 | **DOGE** | DOGE **+124.77%** |
| COMPOSITE_BREAKOUT_ICHIMOKU | CB + Ichimoku 필터 | — | ⚠ CB와 동일 (ADX 중복) |
| COMPOSITE_REGIME_ROUTER (CRR) | ADX/ATR 레짐 → CB/V1/V2 자동 위임 | **SOL·ETH** | SOL **+65.38%**, ETH +65.09% |
| COMPOSITE_MTF_BTC | CB(H1) + Supertrend(H4) | **ETH·AAVE·CHZ** | ETH **+127.70%**, AAVE +28.15% |
| COMPOSITE_MTF_MOMENTUM | CMI_V2(H1) + Supertrend(H4) | **BLUR·DOGE** | BLUR **+48.06%**, DOGE +83.40% |
| COMPOSITE_MTF_CONFIRMED | CRR(H1) + Supertrend(H4) | **XRP** 범용 | XRP **+3.37%** (유일 흑자) |
| MACD_STOCH_BB | MACD + StochRSI + 볼린저 6조건 AND | ❌ 비활성화 | BTC -2.32%, 거래 극희소 |

### 2026-04-30 신규 전략 H1 FULL 백테스트 비교 (7전략 × 17코인)

> **조건**: 2022-01-01 ~ 2026-04-30, 초기자금 1,000만, 슬리피지 0.1% + 수수료 0.05%, H1.
> CB=COMPOSITE_BREAKOUT, V1=CMI_V1, V2=CMI_V2, CRR=COMPOSITE_REGIME_ROUTER,
> MTF_B=COMPOSITE_MTF_BTC, MTF_M=COMPOSITE_MTF_MOMENTUM, MTF_C=COMPOSITE_MTF_CONFIRMED.
> **굵게** = 코인별 1위 전략.

| 코인 | CB | V1 | V2 | CRR | MTF_B | MTF_M | MTF_C |
|------|-----|-----|-----|-----|-------|-------|-------|
| **BTC** | **+106.71%** | +5.68% | +13.80% | +14.33% | +29.66% | +14.26% | +14.26% |
| **ETH** | +30.90% | +50.73% | +58.00% | +65.09% | **+127.70%** | +75.79% | +75.79% |
| **SOL** | +62.79% | +17.30% | +42.32% | **+65.38%** | +43.40% | +60.20% | +60.92% |
| **XRP** | -1.60% | +1.04% | -10.35% | -0.48% | -21.74% | +2.71% | **+3.37%** |
| **DOGE** | +17.75% | +48.86% | **+124.77%** | +59.89% | +82.06% | +83.40% | +83.40% |
| **ADA** | **+86.98%** | +5.89% | +12.52% | +8.85% | -25.78% | +10.03% | +11.05% |
| **AAVE** | -24.52% | -48.90% | -40.71% | -3.80% | **+28.15%** | +11.34% | +12.10% |
| **BLUR** | -17.23% | +23.24% | +10.52% | +33.19% | +38.69%⚠ | **+48.06%** | **+48.06%** |
| **CHZ** | -23.77% | -12.77% | -18.38% | -26.94% | **+14.09%** | -23.28% | -23.28% |
| MOVE | -2.88% | — | — | -2.88% | -2.18% | -7.19% | -7.19% |
| SUPER | — | — | — | -4.16% | +5.61%⚠ | -4.16% | -4.16% |
| IP | — | — | — | +12.99%⚠ | +11.77%⚠ | +13.94%⚠ | +13.94%⚠ |
| FLOCK | — | — | — | -8.49% | -9.18% | -8.49% | -8.49% |
| AXL | — | — | — | -11.37% | -13.41% | -8.18% | -8.18% |
| BIO | — | — | — | -3.49% | -4.00% | -3.49% | -3.49% |
| KERNEL | — | — | — | -5.78% | -8.62% | -4.38% | -4.38% |
| USDT | — | — | — | +0.55% | -6.73% | +1.10% | +1.10% |

> ⚠ 거래 수 15건 미만 — 통계적 신뢰성 부족.
> SUPER/IP/FLOCK/AXL/BIO/KERNEL: 모든 전략 거래수 1~6건으로 결론 도출 불가 (참고만).

### 2026-04-30 신규 MTF 전략 — 코인별 MDD 비교

| 코인 | 1위 전략 | 수익률 | MDD | Sharpe | 거래수 | 이전 대비 |
|------|---------|--------|-----|--------|--------|---------|
| **BTC** | COMPOSITE_BREAKOUT | **+106.71%** | -8.88% | 1.24 | 79 | 유지 |
| **ETH** | COMPOSITE_MTF_BTC | **+127.70%** | **-7.24%** | 1.35 | 61 | ↑ 대폭 개선 (CB +30% → MTF_B +127%) |
| **SOL** | COMPOSITE_REGIME_ROUTER | **+65.38%** | -14.93% | 0.76 | 101 | ↑ 소폭 개선 (CB → CRR) |
| **XRP** | COMPOSITE_MTF_CONFIRMED | **+3.37%** | -15.67% | 0.13 | 62 | ↑ 유일 흑자 코인 |
| **DOGE** | CMI_V2 | **+124.77%** | -30.75% | 0.87 | 173 | 유지 (MTF 근접하나 MDD 더 나쁨) |
| **ADA** | COMPOSITE_BREAKOUT | **+86.98%** | -14.14% | 0.96 | 46 | 유지 (MTF_BTC -25.78%로 역효과) |
| **AAVE** | COMPOSITE_MTF_BTC | **+28.15%** | -31.01% | 0.48 | 62 | ↑ 흑자 전환 (기존 -24.52%) |
| **BLUR** | MTF_MOMENTUM/CONFIRMED | **+48.06%** | -11.91% | 0.91 | 39 | ↑ CRR +33% → MTF_M +48% |
| **CHZ** | COMPOSITE_MTF_BTC | **+14.09%** | -14.88% | 0.32 | 48 | ↑ 흑자 전환 (기존 -23.77%) |

---

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

## 🟢 배포 권고 / 🚨 배포 금지 (2026-04-30 MTF 백테스트 기준)

> H1 FULL 2022~2026-04-30 백테스트 결과 기반 (WF 재검증 미수행). MTF 3종 신규 전략 반영.

### Tier 1 — 백테스트 검증 통과, 소액 투입 가능

| 코인 | 권장 전략 | FULL 수익률 | MDD | Sharpe | 근거 |
|------|-----------|------------|-----|--------|------|
| **BTC** | **COMPOSITE_BREAKOUT** | **+106.71%** | -8.88% | 1.24 | 7전략 중 압도적 1위. MDD 최저 수준. |
| **ETH** | **COMPOSITE_MTF_BTC** | **+127.70%** | -7.24% | 1.35 | 7전략 중 1위 + MDD 최저. 기존 CB +30%에서 대폭 개선. |
| **SOL** | **COMPOSITE_REGIME_ROUTER** | **+65.38%** | -14.93% | 0.76 | CB +62.79%를 근소 상회, 레짐 자동 적응. |
| **DOGE** | **CMI_V2** | **+124.77%** | -30.75% | 0.87 | MTF 근접(+83%)하나 MDD 더 나쁨. 기존 전략 유지. |
| **ADA** | **COMPOSITE_BREAKOUT** | **+86.98%** | -14.14% | 0.96 | MTF_BTC -25%로 역효과. CB 압도적. |

### Tier 2 — 흑자 전환·신규 발굴, 관찰 후 투입

| 코인 | 권장 전략 | FULL 수익률 | MDD | 판단 |
|------|-----------|------------|-----|------|
| **XRP** | **COMPOSITE_MTF_CONFIRMED** | **+3.37%** | -15.67% | 모든 전략 손실 또는 근0 중 유일 흑자. 소액 관찰. |
| **AAVE** | **COMPOSITE_MTF_BTC** | **+28.15%** | -31.01% | 기존 -24.52% → 흑자 전환. MDD -31% 주의. |
| **BLUR** | **COMPOSITE_MTF_MOMENTUM** | **+48.06%** | -11.91% | Sharpe 0.91 양호. 거래수 39건 수용 수준. |
| **CHZ** | **COMPOSITE_MTF_BTC** | **+14.09%** | -14.88% | 기존 -23.77% → 흑자 전환. 소액 관찰. |

### 🚨 배포 금지

| 조합 | 사유 |
|------|------|
| **전 코인 × M15 타임프레임** | 오버트레이딩 + 수수료 잠식으로 -99% 속출. M15 전면 비활성화 |
| **전 코인 × FAIR_VALUE_GAP** | H1에서도 메이저 코인 -69~-82%. 전략 로직 자체 구조 문제 |
| **ETH × CB / V1 / V2** | CB +30%, V1/V2 +50~58%. MTF_BTC +127%에 크게 열위 |
| **XRP × MTF_BTC** | -21.74% — 가장 나쁜 조합. 절대 금지 |
| **ADA × MTF_BTC** | -25.78% — ADA에는 역효과 큼 |
| **CHZ × CRR / MTF_M / MTF_C** | -23~-27%. MTF_BTC만 흑자 |
| **AAVE × CB / V1 / V2** | -24~-48%. MTF_BTC만 흑자 전환 |
| **MOVE/SUPER/FLOCK/AXL/BIO/KERNEL** | 거래수 1~17건으로 통계 신뢰성 없음. 배포 금지 |

### 운영 세션 조치 사항 (2026-04-30 갱신)

- 🆙 **ETH 전환**: CB → **COMPOSITE_MTF_BTC** (+127.70%, MDD -7.24% — 7전략 최고)
- 🆙 **SOL 전환**: CB → **COMPOSITE_REGIME_ROUTER** (+65.38%, 자동 레짐 적응)
- 🟢 **BTC**: CB 유지 (+106.71%)
- 🟢 **DOGE**: CMI_V2 유지 (+124.77%)
- 🟢 **ADA**: CB 유지 (+86.98%)
- 🆕 **XRP**: MTF_CONFIRMED 소액 시작 (+3.37%, 유일 흑자)
- 🆕 **AAVE**: MTF_BTC 소액 시작 (+28.15%, 흑자 전환)
- 🆕 **BLUR**: MTF_MOMENTUM 소액 시작 (+48.06%, Sharpe 0.91)
- 🆕 **CHZ**: MTF_BTC 소액 관찰 (+14.09%, 흑자 전환)

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

- [ ] **ETH 전략 전환: CB → COMPOSITE_MTF_BTC** — FULL +127.70%, MDD -7.24%, Sharpe 1.35. 7전략 중 압도적 1위.
- [ ] **SOL 전략 전환: CB → COMPOSITE_REGIME_ROUTER** — FULL +65.38%, 레짐 자동 적응. CB +62.79% 근소 상회.
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
