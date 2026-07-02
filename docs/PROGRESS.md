# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-07-02 (실전 4대 전략 검토 + ATR 거래량 필터 수정 — CHANGELOG 참조)

---

## 🔍 2026-07-02 실전 4대 전략 검토 (CRR / CB / HAS / CMI_V2) — 반영 완료

> ATR 거래량 필터 결함 수정 + 관찰 항목 일괄 반영 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 항목 2건).
> A/B 백테스트 러너: [`StrategyReviewAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/StrategyReviewAbBacktestRunner.java)
> (`-Dreview.backtest.dir=d:/tmp`, 100일 H1 × BTC/ETH/SOL/XRP). **미커밋 / 운영 미배포.**

- [ ] **CRR RANGE/TRANSITIONAL(비화이트리스트) 진입 수학적 희소 — 모니터링 유지** — RANGE(ADX<20)에서 V1 위임 시 MACD(0.5)는 자체 ADX(25) 필터로 항상 HOLD → 진입은 VWAP(0.3)+GRID(0.2) 동시 고신뢰 필요. 90일 분석의 RANGE WR 66~69%가 이 희소·이중확인 구조 덕일 수 있으므로 **완화는 백테스트 검증 전 금지**. 발화 빈도만 모니터링.
- [ ] **CMI_V2 존속 판단 (운영 결정 필요)** — 2026-06-30 90일 분석에서 V1이 전 레짐 V2 압도 확인(CRR도 V1로 개편됨). V1 병행 비교 목적이 끝났으면 V2 단독 실전 세션을 V1 또는 CRR로 교체 권장 — 세션 교체는 운영자 판단 사항.
- [ ] **배포 후 관찰** — HEIKIN_ASHI_STOCH 강도 게이트 해제(기본 0)로 신호 빈도가 늘어난다. 실전 신호품질 로그로 승률·빈도 재확인 (구 기본 70은 `strategyParams.minStrengthPct=70`으로 복원 가능).

---

## 🔄 2026-07-02 동적 멀티코인 시스템 보완 — 후속 관찰/과제

> 결함 6건 수정 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02). 컴파일·mock 테스트 통과, **미커밋 / 운영 미배포**.

- [ ] **배포 후 관찰**: 매도 FAILED 시 "매도 롤백 포지션 재결속" 로그 + 세션이 POSITION_MONITORING으로 복귀해 재매도하는지. 텔레그램 "재결속 불가" 경고가 오면 수동 청산.
- [ ] **동적 세션 전용 테스트 부재** — `DynamicTradingService` reconcile/재결속/이중매도 가드 단위 테스트 작성 (mock 기반, DB 불필요하게).
- [ ] (기존 설계 한계, 미변경) 보유 중 `totalAssetKrw`가 미실현 손익을 반영하지 않아 MDD 피크가 실현 기준으로만 추적됨 — 필요 시 모니터링 tick에서 시가평가 갱신 검토.
- [ ] (기존 설계 한계, 미변경) SCANNING 60초 tick마다 워치리스트 10코인 × 캔들 250개 REST 조회 — Upbit rate limit 여유 모니터링, 필요 시 캔들 캐시 도입.

---

## 🚨 2026-05-31 실전 로그 분석 (docs/logs/ 3종 교차 분석)

> `live_trading_sessions/positions_20260531.csv` + `signal_quality_30d_20260531.csv` 분석.
> LIVE 세션 4개(143 ETH / 144 SOL / 145 XRP / 148 BTC) 모두 ~2개월 운영했으나 사실상 본전.
> **P0는 코드 수정 적용 + 테스트 통과. P1은 조사만 완료(코드 변경은 백테스트 검증 후).**

### 현황 수치
- **세션 수익률**: BTC -0.04% / ETH -0.12% / SOL -0.16% / XRP +0.65% → 전부 본전권.
- **포지션 192건**: 실이익 2건 / 수수료만 손실(-4원≈-0.05%) 146건 / 미체결·size0 44건.
- **신호 568,338건/30일**: HOLD 99.6%(566,027) / SELL 1,351 / BUY 960 / **실제 체결 42건**.

### 🔴 P0 — 청산/PnL 정확성 (돈 직결) — ✅ 핵심 수정 완료 (2026-06-23, CHANGELOG 참조)
근본 원인 확정: 매도 체결가 미산출 → `realizedPnl`이 -매도수수료로만 기록되던 "가짜 본전".
당초 가설(ord_type/side 문자열 매칭 실패)이 아니라, **Upbit `GET /v1/order` 응답이 체결 금액을
최상위 `executed_funds`가 아닌 `trades[]` 배열로 내려주는데 DTO가 이를 파싱하지 않아
`executedFunds`가 영원히 null**이던 것이 진짜 원인. 결과적으로 시장가 매도가 `FILLED`로
전이되지 못하고 `SUBMITTED`에 무한 정체(실전 로그 다수 주문 확인).
- [x] **수정 완료** — `OrderResponse.resolveExecutedFunds()`(trades 합산 폴백) 추가 + `applyFillPrice`/`syncOrderState`에서 사용. 회귀 테스트 통과. 상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-06-23 항목.
- [ ] **운영 관찰** — 배포 후 정체됐던 매도 주문들이 FILLED로 정리되고 신규 매도가 정상 체결·기록되는지 확인. PnL 재수집.
- [ ] **청산 정책 표준화 검증** — SL/TP는 DB(`ExitRuleConfig`) 동적 설정(현 SL 5%/TP +10%). P0 수정 반영된 실거래 재수집 후 조정.

### 🟠 P1 — 신호 발생률 (⚠️ 실거래 진입 빈도 변경 = 백테스트 검증 필수)

#### ✅ 죽은 하위지표 조사 완료 (2026-05-31, 읽기 전용)
결론: **MACD/VWAP/GRID는 "죽은" 게 아니라, 합산 임계와 가중치·필터가 수학적으로 어긋나
"거의 항상 HOLD"가 강제되는 구조다.** 핵심 메커니즘 3가지:

1. **단일 보조지표로는 임계 돌파가 수학적으로 불가능.**
   [`CompositeStrategy.finalSignal`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java#L182) 임계 `WEAK=0.4`. `score=Σ(weight×confidence)`, `confidence=strength/100` ([StrategySignal](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategySignal.java#L46)).
   - SUPERTREND 가중 0.3 → 추세전환 strength 70(conf 0.7)이어도 기여 **0.3×0.7=0.21 < 0.4**. 지속 신호(≤50)면 ≤0.15.
   - VWAP 0.3 / GRID 0.2 도 동일 — **단독 최대 기여가 임계 미만.**
   - 따라서 진입하려면 **반드시 MACD(0.5)가 동반 발화**해야 함 = 사실상 MACD 단일 의존.

2. **그 MACD가 4중 필터로 거의 침묵한다.** [`MacdStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java#L66): (a) 골든/데드 **크로스 순간**에만, (b) ADX≥25, (c) 제로라인, (d) 히스토그램 확대 — 4조건 동시 충족 캔들만 발화. 그 외 전부 HOLD(0).

3. **레짐↔임계 불일치 (설계 결함, 가장 치명적).**
   [`CompositeRegimeRouter`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeRegimeRouter.java#L100)는 **TRANSITIONAL(ADX 20~25)** 구간을 CMI_V1(MACD0.5+VWAP0.3+GRID0.2)에 위임한다. 그런데 MACD의 ADX 필터 임계가 **25.0** → TRANSITIONAL에서는 **주력 MACD가 100% 차단됨.** 남은 VWAP+GRID=0.5는 둘 다 풀강도 동방향이어야만 0.4 돌파인데 (역추세+평균회귀라) 거의 불가 → **TRANSITIONAL은 구조적으로 진입 거의 불가능.** 로그의 `[TRANSITIONAL] buy=0.00 sell=0.00 [MACD:HOLD(0) VWAP:HOLD(0) GRID:HOLD(0)]` 14.8만 건이 이 경로. `[TREND] sell=0.15 [SUPERTREND:SELL(50)]` 5.4만 건은 메커니즘 1(보조지표 단독 0.4 미달).

#### 권고 (백테스트 검증 후 적용 — 코드 미변경)
- [ ] **MACD ADX 임계를 레짐별로 정합화** — TRANSITIONAL 위임 시 MACD `adxThreshold`를 20 이하로 내리거나(params override), TRANSITIONAL→CMI_V1 위임 자체 재고. (라우터가 params로 MACD adxThreshold를 낮춰 주입하는 방식이 영향 최소)
- [ ] **보조지표 단독 진입 가능하도록 가중치 또는 임계 조정** — 예: WEAK_THRESHOLD 0.4→0.3, 또는 SUPERTREND/VWAP 가중 0.3→0.4. 단 오탐↑ 위험 → 반드시 `BacktestEngine` 다코인 검증.
- [ ] **SUPERTREND 추세지속 strength 상향 검토** — 현재 지속 신호 ≤50(conf≤0.5)이라 단독 기여 미미.
- [ ] **SELL/RANGE 편중 점검** — 롱 전용 구조, RANGE(ADX<20) 무조건 HOLD 분류 비율 점검.

### 🟡 P2 — 측정 인프라 (재진단: 일부는 이미 정상)
- [x] ~~4h/24h 백필 / CSV 따옴표~~ — 확인 결과 [`SignalQualityService`](../web-api/src/main/java/com/cryptoautotrader/api/service/SignalQualityService.java)는 이미 과거 시점 캔들(`getCandles(targetTime)`)로 정확히 백필하고, [`CsvExportService`](../web-api/src/main/java/com/cryptoautotrader/api/service/CsvExportService.java)도 이미 `q()`로 RFC4180 인용 처리 중. **기존 115MB 파일의 손상 행은 과거 버전 산출물**로 추정(현재 코드 버그 아님).
- [ ] **HOLD 로그 비대 — 실제 원인** = [`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L804) 가 매 평가마다 `StrategyLogEntity`를 HOLD 포함 전량 저장(30일 56만 행). HOLD 제외/요약 적재 검토(단, HOLD 비율 리포트 의존성 확인 후).
- [ ] **미라벨 신호 재조사** — PAPER 신호 `signalPrice=null`이면 백필 스킵되는 점 등.

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

---

## 🔬 2026-06-01 전략 전체 분석 (Strategy-wide Audit)

> 범위: 기본 지표 14종 + 복합 프리셋 11종 + 라이브 신호 파이프라인 전체.
> 목적: 실전 ~본전/약손실 + 99.6% HOLD의 구조적 원인 규명 및 우선순위 도출.

### 1. 인벤토리 (실제 등록 기준)
- **기본 지표 14종** (`StrategyRegistry`): VWAP, EMA_CROSS, BOLLINGER, GRID*, RSI, MACD, SUPERTREND, ATR_BREAKOUT, ORDERBOOK_IMBALANCE, VOLUME_DELTA, STOCHASTIC_RSI, FAIR_VALUE_GAP, MACD_STOCH_BB*, TEST_TIMED  (*=stateful)
- **복합 프리셋 11종** (`CompositePresetRegistrar` @PostConstruct): COMPOSITE, COMPOSITE_REGIME_ROUTER, COMPOSITE_MOMENTUM, COMPOSITE_ETH, COMPOSITE_BREAKOUT, COMPOSITE_MOMENTUM_ICHIMOKU(_V2), COMPOSITE_BREAKOUT_ICHIMOKU, COMPOSITE_MTF_CONFIRMED/_BTC/_MOMENTUM
- ⚠️ 이전 메모상의 `StrategyFactory`/`CMI_V1`/`TADA`는 **실제 코드에 없음** (오기억 정정). CompositeRegimeRouter는 내부에서 delegate를 직접 생성.

### 2. 거버넌스 갭 (StrategyLiveStatusRegistry)
- ENABLED(4): COMPOSITE_BREAKOUT, COMPOSITE_MOMENTUM, COMPOSITE_MOMENTUM_ICHIMOKU, _V2
- BLOCKED(4): STOCHASTIC_RSI, MACD, MACD_STOCH_BB, COMPOSITE_BREAKOUT_ICHIMOKU / DEPRECATED(1): TEST_TIMED
- **갭1**: `isBlocked()=BLOCKED||DEPRECATED` 만 검사 → "단독 미검증(EXPERIMENTAL)" 단일지표(VWAP·RSI·BOLLINGER·GRID 등)도 라이브 세션 생성 **그대로 허용**. 라벨이 강제력 없음.
- **갭2**: 주력 메타전략 `COMPOSITE_REGIME_ROUTER` + MTF 3종이 매트릭스 **미등록** → 기본 EXPERIMENTAL.

### 3. 신호 파이프라인 — 앙상블 아님
- 라이브(`LiveTradingService.evaluateAndExecuteSession` ~775행)는 세션의 단일 `strategyType` 하나만 `evaluate()`.
- **`StrategySelector`의 레짐별 가중 앙상블(BREAKOUT 0.65+MOMENTUM 0.35 등)은 라이브 실행 경로에서 호출되지 않음** (가중치 최적화용일 뿐). 문서/설계 ↔ 실행 불일치.

### 4. 구조적 HOLD 편향 (처리량 킬러)
라이브 BUY 1건에 필요한 직렬 게이트(곱셈적 누적):
1. 레짐: RANGE→즉시 HOLD / TRANSITIONAL→V1인데 MACD adxThreshold=25라 MACD 무조건 침묵 → 남은 0.5로 0.4 임계 돌파 불가
2. CompositeStrategy 동적 ADX 필터(15~25)
3. 하위지표 합산 score>0.4 (단일 보조지표 단독 돌파 수학적 불가 — P1 기확인)
4. EMA20/50 방향 필터
5. Ichimoku 구름 필터(V1/V2)
6. RSI Veto(>75, BREAKOUT)
7. LiveTradingService EMA200 필터 (**BUY만** 차단 → 매수 비대칭)

중복: **EMA 3중**(EMA_CROSS 하위 / EMA20-50 / EMA200), **ADX 2중**(Composite / MACD내부).

### 5. 죽은/휴면 코드
- 단일지표 5종(BOLLINGER, STOCHASTIC_RSI, FVG, MACD_STOCH_BB, ORDERBOOK_IMBALANCE)은 어떤 ENABLED 복합의 하위지표도 아님 → 실질 휴면
- `SignalEvaluationService` 참조 0건 → 데드코드 후보
- StrategySelector(RANGE 매매) ↔ CompositeRegimeRouter(RANGE 진입금지) 모순 공존

### 6. 작업 우선순위 (BacktestEngine 검증 후 적용 원칙)
- [x] **P1-A**: TRANSITIONAL 위임 시 MACD adxThreshold를 **코인 선택적(BTC/SOL만 25→20)** 으로 주입(CompositeRegimeRouter, putIfAbsent·원본 불변). 전역 20은 XRP를 망가뜨려(검증), 검증서 개선 확인된 코인만 화이트리스트. 3년 H1 다코인 검증 통과 — BTC·SOL 개선·나머지 무변동(§7). ✅ 라이브 반영 가능.
- [x] **P1-B**: EMA200 게이트를 core-engine `Ema200RegimeGate` 단일 진실 소스로 통합. LiveTradingService·BacktestEngine 중복 제거, DOGE 예외를 게이트에 명문화 → 백테스트↔라이브 정합(이전엔 라이브에만 DOGE 예외 존재). 회귀 테스트 5건 통과, 전체 빌드 그린(2026-06-01). ⚠️ 백테스트에 DOGE 예외가 새로 반영되므로 DOGE 백테스트 수치 재확인 필요
- [x] **P2-A** (등재만): ROUTER/MTF_BTC/MTF_MOMENTUM를 배포 티어1 근거로 ENABLED 등재, MTF_CONFIRMED는 티어2라 EXPERIMENTAL 명시. isBlocked()는 현행 유지(EXPERIMENTAL 미차단 → 운영 리스크 회피). 회귀테스트 보강·전체 빌드 그린(2026-06-01)
- [x] **P2-B** (문서화): StrategySelector는 데드코드 아님 — `COMPOSITE`(RegimeAdaptiveStrategy) 전략·COMPOSITE 백테스트(BacktestService)가 실사용. 실제 사실은 **레짐 앙상블 2중 구현 공존**: ① StrategySelector 기반 `COMPOSITE`(가중 투표, WeightOverrideStore 동적가중) ② `CompositeRegimeRouter`(레짐별 단일 delegate 위임). 세션 단일 strategyType 평가 경로는 어느 앙상블도 안 거치고 지정 전략만 evaluate. 삭제는 빌드 파손 → 보류. 향후 통합 과제로 남김(범위 큼, 백테스트 재검증 필요).
- [x] **P3** (기록만, 코드 미변경): 휴면 단일지표 5종(BOLLINGER·STOCHASTIC_RSI·FVG·MACD_STOCH_BB·ORDERBOOK_IMBALANCE)은 어떤 ENABLED 복합의 하위지표도 아님 = 실질 휴면이나, 단독 EXPERIMENTAL로 라이브·백테스트 생성은 가능. 향후 복합전략 재료가 될 수 있어 **제거하지 않고 보존**. `SignalEvaluationService`는 코드 전체 참조 0건 확인 — 별도 데드코드 정리 후보로 기록(이번엔 미변경).

### 7. 백테스트 검증 결과 (2026-06-01, 실DB H1 2023~2025, 5코인)
> 하니스: `web-api/.../backtest/P1ChangesBacktestVerification.java` (JDBC 직접 로드, DB 미접속 시 자동 skip). CompositeRegimeRouter를 adxThreshold=25 명시(=변경전) vs 미주입(=변경후 자동20)으로 비교.

**P1-A (TRANSITIONAL adxThreshold 25→20) — 코인 선택적 효과, 전역 적용 부적절:**
| 코인 | 변경전(25) | 변경후(20) | 판정 |
|---|---|---|---|
| BTC | +13.7% T72 MDD-12.7% | +20.0% T96 MDD-13.0% | ✅ 개선 |
| ETH | +42.3% T81 MDD-13.9% | +45.3% T94 MDD-17.0% | ⚠️ 수익↑/MDD악화 |
| SOL | +70.5% T77 MDD-13.6% | +91.3% T91 MDD-12.1% | ✅ 수익↑+MDD↓ |
| XRP | +0.6% T56 MDD-14.3% | **-14.4%** T73 MDD-18.5% | ❌ 명확 악화 |
| DOGE | +54.5% T106 MDD-25.3% | +52.2% T116 MDD-26.5% | ⚠️ 소폭 악화 |
- 진입 빈도는 전 코인 증가(예상대로). BTC·SOL 명확 개선, **XRP는 망가짐**(추세 모호 코인).
- → **코인별 차등 적용으로 수정 완료** (`ADX_RELAX_COINS = [BTC, SOL]` 화이트리스트). 화이트리스트만 20, 그 외 25 유지.

**P1-A 차등 재검증 (2026-06-01, 동일 하니스):**
| 코인 | 변경전(25) | 차등적용후 | 적용 |
|---|---|---|---|
| BTC | +13.7% T72 MDD-12.7% | +20.0% T96 MDD-13.0% | 20(완화) ✅개선 |
| SOL | +70.5% T77 MDD-13.6% | +91.3% T91 MDD-12.1% | 20(완화) ✅수익↑MDD↓ |
| ETH | +42.3% T81 MDD-13.9% | +42.3% T81 MDD-13.9% | 25(유지) =무변동 |
| XRP | +0.6% T56 MDD-14.3% | +0.6% T56 MDD-14.3% | 25(유지) =무변동(보호) |
| DOGE | +54.5% T106 MDD-25.3% | +54.5% T106 MDD-25.3% | 25(유지) =무변동 |
- BTC·SOL 개선 유지 + ETH/XRP/DOGE는 변경전과 **완전 동일**(XRP -15% 악화 차단 확인). 순개선만 남고 악화 0. ✅ **라이브 반영 가능 상태.**

**P1-B (EMA200 게이트 DOGE 예외) — 정합 달성, 회귀 없음:**
- DOGE: BUY허용 26006/26006(100%) vs 순수규칙 11336(43.6%) → 예외 +56.4%p 정상 작동.
- BTC/ETH/SOL/XRP: 적용=면제 완전 동일 → 비-DOGE **무영향(회귀 없음)** 확인. ✅ **그대로 유지 권장.**

### 8. 🟢 운영 반영 & 관찰 중 (2026-06-01 ~, 2~3주)
> 코드 운영서버 빌드·반영 완료. 사용자가 5코인 소액 실거래 세션을 수동 생성·가동. H1 고정.

**가동 라인업:**
| 코인 | 전략 | 원금 | 이번 변경 발동 |
|---|---|---|---|
| KRW-BTC | COMPOSITE_BREAKOUT | 10만 | — (ROUTER 미사용) |
| KRW-ETH | COMPOSITE_MTF_BTC | 10만 | — |
| KRW-SOL | COMPOSITE_REGIME_ROUTER | 10만 | ✅ **P1-A 발동** (SOL 화이트리스트) |
| KRW-DOGE | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | 5만 | ✅ **P1-B 발동** (EMA200 예외) |
| KRW-ADA | COMPOSITE_BREAKOUT | 10만 | — |
- XRP는 의도적 제외(P1-A 검증서 -15% 악화). DOGE는 MDD-30.8% 최악 → 원금 절반.

**3주 후 판단 체크포인트:**
- [ ] **P0 체결가 재발 점검** — 청산 PnL이 또 -4원(수수료만)이면 P0 수정 미반영. 청산 1건이라도 나오면 즉시 확인.
- [ ] **SOL P1-A 작동 증거** — 신호 로그 `[TRANSITIONAL]` BUY가 이전보다 발생하는지.
- [ ] **DOGE P1-B 작동 증거** — EMA200 아래 구간 BUY 진입 + MDD 추이.
- [ ] **백테스트↔실거래 괴리** — 승률·평균손익 (과거 13배 괴리 이력).
- 기준 충족 시 → 나머지(BTC/ETH/ADA) 확대. `docs/logs/` CSV 수집해두면 교차분석 가능.

**미커밋:** 이번 작업분(P1-A/P1-B/P2-A + 하니스 + 이 문서)은 운영 반영됐으나 **git 미커밋** 상태. 작업 브랜치 생성 후 커밋 권장.

**후속 과제 (이번 미적용):**
- P1-A 화이트리스트 ETH 추가 검토(수익↑/MDD악화 트레이드오프).
- P2-B StrategySelector↔CompositeRegimeRouter 레짐 앙상블 2중 구현 통합.
- `SignalEvaluationService` 데드코드(참조 0건) 정리.

### 9. 🔎 실전 이력 분석 + 주문 로그 조회 개선 (2026-06-15)

**분석 (docs/anal_data CSV 3종):**
- **KRW-ADA(포지션879, 세션153) 허위 미실현 손익**: 6/9 매도가 **계속 FAILED** → `reconcileClosingPositions`가 OPEN 롤백 → 무한 재시도. 포지션이 OPEN으로 남아 `updateSessionUnrealizedPnl` 시가평가가 멈추지 않음(세션 허위 +5.31%). **실제 FAILED 사유는 주문 `failedReason`/Upbit 응답 확인 필요** (유력: `resolveAskVolume` 잔고 잠김 / invalid_volume_ask).
- **손익 데이터 오염**: CLOSED 193건 중 143건 realizedPnl = -4원(매도수수료만) "가짜 본전", 44건 0원 → 성과지표 신뢰 불가. (P0 방어코드는 추가됐으나 과거 오염 레코드 미복구.)
- **신호 품질**: 561,800건 중 99.6% HOLD, LIVE 실체결 BUY 15·SELL 9건뿐. 4h/24h 사후수익률 컬럼 거의 null(백필 미동작).

**구현 — Upbit 주문 로그 화면(`settings/upbit-logs`) 조회 개선:**
- 날짜 **"직접 지정" 프리셋 + from~to 날짜 입력** (특정일 조회).
- 페이지네이션 **처음/끝 버튼 + 페이지 번호 직접 입력** 점프.
- **CSV(Excel) 내보내기** "엑셀로 받기" 버튼 — 현재 날짜·세션 필터 반영.
  - BE: `GET /api/v1/export/csv/live-trading/orders?sessionId&dateFrom&dateTo`, `CsvExportService.exportLiveTradingOrders`, `OrderRepository` non-paged 조회 3종. FE: `csvExportApi.liveTradingOrders`.
- web-api 컴파일 ✅ / 프론트 tsc(변경파일) ✅.

**후속:**
- [x] ADA FAILED 사유 확인 후 매도 실패 근본 원인 수정. → §10
- [ ] 상태/방향 필터 **서버측 쿼리화**(현재 클라이언트 측 = 현재 페이지 내에서만 필터).
- [ ] 오염된 CLOSED 손익 1회성 보정 스크립트.

### 10. 🛠 ADA 팬텀 포지션(체결을 취소로 오기록) 버그 수정 — 3중 방어 (2026-06-15)

**근본 원인 (주문 3803 기준 확정):** 시장가 손절 매도가 거래소에서 실제 체결됐는데, **주문 5분 타임아웃 자동취소**가 동작 → 이미 `done`이라 거래소 취소 API가 실패하지만 `cancelOrder`가 **로컬 상태를 무조건 CANCELLED로 박음** → `reconcileClosingPositions`가 포지션 OPEN 롤백 → DB는 보유 중인데 거래소엔 코인 없음 → 무한 재매도(잔고없음 FAILED) + 허위 미실현 손익.

**수정 (3중 방어):**
- **(A) 취소 직전 체결 재확인** — `OrderExecutionEngine.pollActiveOrders` 타임아웃 분기에서 취소 전 거래소 상태 재조회, `done`이면 취소 대신 `syncOrderState`로 체결 처리.
- **(B) 취소 실패 시 체결 의심** — `OrderExecutionEngine.cancelOrder` catch에서 거래소 재조회, FILLED/executed>0이면 CANCELLED 대신 체결 처리(체결을 취소로 오기록하는 경로 차단).
- **(C) 팬텀 포지션 안전망** — `LiveTradingService.reconcilePhantomPositions`(60초): OPEN인데 거래소 보유량(free+locked)이 DB 기대량의 5% 미만이면 팬텀으로 간주, **3회 연속(≈3분)·보유 10분↑** 확인 후 CLOSED 확정 + 세션 KRW 복원 + 텔레그램 경고. 추정 체결가 = 최근 FILLED 매도가 → 손절가 → 최신 캔들 종가 → 평균단가 순. **현재 멈춰있는 ADA 879도 가동 시 자동 정리됨.**
- **(C-역) 추적 안 되는 잔고 감지(경고만)** — `detectUntrackedBalances`: 거래소 보유량이 DB 추적량(OPEN size>0 + CLOSING)의 110% 초과 + **최근 24h FAILED/CANCELLED 매수 주문 존재**(dust·수동입금 구분)일 때, 3회 연속·6시간 쿨다운으로 텔레그램 경고. **매수 체결이 실패로 오기록된 거울 케이스 대응. 자동 청산/매도/포지션 생성은 안 함**(사용자 선택: 경고만).
- 진행 중 매도는 코인이 locked → 보유량>0 이라 (C)에서 자연 제외. 매수/매도 양방향 = A·B(주문 단위) 대칭 + C(잔고 대조)는 매도방향 자동청산·매수방향 경고. web-api 컴파일 ✅.

**후속:**
- [ ] 배포 후 ADA 879 자동 청산 로그/텔레그램 확인 (추정 손익 실제값 대조).
- [ ] 시장가 청산(SELL) 주문을 5분 타임아웃 자동취소 대상에서 제외하는 옵션 검토(추가 안전).
- [ ] (C-역) 경고 빈발 시 → 포지션 자동 복구 또는 자동 청산으로 승격 검토.

### 11. 📥 실전매매 세션/포지션 CSV — 세션별·다중 선택 다운로드 (2026-06-15)

분석용으로 **운영 여부 무관 과거 세션 포함** 세션별/다중 선택 export 지원.
- BE: `exportLiveTradingSessions(Collection<Long>)` / `exportLiveTradingPositions(Collection<Long>)` — `sessionIds` 지정 시 해당 세션만, 미지정 시 전체(기존 동작). 컨트롤러 `?sessionIds=1&sessionIds=2` 반복 파라미터. (세션 export는 원래도 전 상태 포함이었음 — 빠진 건 **선택 필터**였음.)
- FE: `trading/history` 테이블에 **체크박스 컬럼 + 전체선택** 추가. "세션 CSV (전체/N)" · "포지션 CSV (전체/N)" 버튼이 선택분만/전체 다운로드. `csvExportApi.liveTradingSessions/Positions(sessionIds?)`.
- **Upbit 주문 로그(`settings/upbit-logs`)도 다중 세션화**: 세션 필터를 단일 `<select>` → **체크박스 다중 선택 팝오버**로 교체. 선택분이 목록 조회·CSV 양쪽에 반영(미선택=전체). BE `getOrders`/`exportLiveTradingOrders`가 `sessionIds`(List) 수용, `OrderRepository`에 `...SessionIdIn...` 페이징/비페이징 쿼리 4종 추가. FE `tradingApi.getOrders(…, sessionIds[], …)`·`csvExportApi.liveTradingOrders(sessionIds[], …)`는 `sessionId=3&sessionId=5` 반복 파라미터로 직렬화.
- web-api 컴파일 ✅ / 변경 파일 tsc ✅ (그 외 tsc 에러는 기존 무관 파일).

### 12. 🗂 세션 soft-delete + 통합 세션 인덱스 + 전략로그 CSV/콤보박스 (2026-06-15)

**근본 원인:** `deleteSession`이 행을 hard-delete하고 **주문·포지션의 session_id를 NULL로** 만들어, 삭제된 세션이 이력·선택지에서 사라지고 주문이 미귀속됐음. (전략로그 session_id는 보존되고 있었음.)

- **soft-delete 전환** — `LiveTradingService.deleteSession`: `deleteById`+session_id NULL 처리 제거 → `status="DELETED"`로만 표시(링크 보존). 앞으로 삭제 세션도 이력·주문로그·전략로그에서 번호로 선택·조회·CSV 가능. 리컨실러는 RUNNING/CREATED/OPEN만 조회하므로 DELETED 무시(안전). **이미 hard-delete된 과거 세션은 주문이 NULL이라 주문로그엔 안 뜨지만, 전략로그는 session_id 보존돼 /logs·콤보박스에 DELETED로 노출됨.**
- **통합 세션 인덱스** — `LiveTradingService.getSessionIndex()` → `GET /api/v1/trading/sessions/index`. 라이브 세션 테이블(DELETED 포함) + `StrategyLogRepository.findDistinctSessionRefs()`(로그에만 있는 삭제/모의 세션) 병합, sessionId 내림차순. 항목: `{sessionId,strategyType,coinPair,status,sessionType}`.
- **upbit-logs**: 세션 팝오버 소스를 `listSessions` → `sessionIndex`(모의 제외)로 교체. `삭제됨` 배지 추가.
- **/logs(전략로그)**: 세션ID 텍스트 입력 → **콤보박스**(`#156 STRATEGY(COIN) 운영중` 형식, sessionIndex 기반, 구분 필터 연동) + **CSV 다운로드** 버튼 추가. BE `CsvExportService.exportStrategyLogs(sessionType, sessionId)` + `GET /api/v1/export/csv/strategy-logs`, `StrategyLogRepository` 비페이징 finder 3종.
- **trading/history**: `DELETED` 상태 라벨/스타일 추가, 삭제 세션은 재삭제 버튼 비활성. (soft-delete 후 종료 세션이 이력에 잔존.)
- web-api 컴파일 ✅ / 변경 파일 tsc ✅.

**한계/후속:**
- [ ] 이미 hard-delete된 과거 세션의 **주문 로그**는 session_id가 NULL이라 세션별 복구 불가(전략로그는 조회 가능). 필요 시 1회성 보정 검토.
