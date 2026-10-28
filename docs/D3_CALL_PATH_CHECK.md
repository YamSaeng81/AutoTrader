# D3 호출 경로 확인 표 — 후보 풀 24개 (리뷰 대기, 2026-10-28)

> **상태**: 코드 확인 + 가짜 자료 신호 비교까지. 리뷰 11차 범위다. `NEXT_CANDIDATE_PLAN.md` §2-2 의 D3 를 닫기 위한 문서다.
> 🔴 성과는 재지 않았다. 실제 시세 자료를 쓰지 않았다. 선별 실행·자료 수집·서버 실행·배포 없음. 운영 `2eb4225`·STAGE_1 비활성 유지.
>
> **질문(리뷰 11차)**: 상태가 있는지가 아니라, **실제 호출 순서·인스턴스 수명·초기화 차이·운영 입력이 신호를 바꾸는가.**

---

## 1. 두 호출 경로 — 코드에서 확인한 것

| 항목 | 운영 (LIVE·PAPER·DYNAMIC) | 백테스트·WF | 같은가 |
|---|---|---|---|
| 평가 시점 | 60초 틱마다 돌지만 전략 평가는 **새 완료 봉에서 한 번**(`lastEvaluatedClosedCandle`: `PaperTradingService.java:744-758`, `LiveTradingService.java:1139-1163`, `DynamicTradingService.java:1031-1037`) | 봉마다 한 번(`BacktestEngine.java:102-288`) | **같다.** 초안 v1 의 "60초마다 호출"은 틀렸다 |
| 창 길이 | 최근 500봉(`TradingConstants.CANDLE_LOOKBACK = 500`, 시각 범위로 조회) | `[max(구간 시작, i−499), i]`(`MAX_LOOKBACK = 500`). **WF 구간마다 처음부터** 시작하므로 구간 첫 499봉은 운영보다 창이 짧다 | 구간 첫 499봉만 다르다 |
| 인스턴스 수명 | 팩토리 등록 전략: 세션별(DYNAMIC 은 세션·코인별) 인스턴스를 메모리에 유지. 서버·세션 재시작 때 새로 만든다. 팩토리 없는 전략: 레지스트리 공유 인스턴스 | 실행(WF 구간)마다 새 인스턴스(`BacktestEngine.java:65`) | 시작 시점이 다르다 |
| 평가 누락 | 서버 중단·자료 지연 때 완료 봉 평가를 건너뛸 수 있다 | 누락 없음 | 다를 수 있다 |
| 전략 입력 | `coinPair`·`timeframe`·`sessionStartedAt`·세션 오버라이드. `sessionStartedAt` 은 `TEST_TIMED` 만 읽는다(풀 밖) | `coinPair`·`timeframe`·설정 파라미터 | 풀 24개에는 같다 |
| 최소 캔들 | `TradingConstants.minimumCandlesFor` = 전략의 `getMinimumCandleCount()` | 같은 값부터 평가 | 같다 |

## 2. 전략별 코드 확인

| 전략 | 운영 인스턴스 | 호출 사이 상태 | 운영 입력 |
|---|---|---|---|
| `ATR_BREAKOUT` `BOLLINGER` `EMA_CROSS` `FAIR_VALUE_GAP` `HEIKIN_ASHI_STOCH` `MACD` `RSI` `STOCHASTIC_RSI` `SUPERTREND` `VOLUME_DELTA` `VWAP` | 공유(팩토리 없음) | 없음(인스턴스 필드 없음) | 없음. `MACD` 는 `coinPair` 별 기본값 — 두 경로 모두 넘긴다 |
| `GRID` | 세션별 | `activeLevels`·`lastHighest/Lowest`. 그리드 범위가 1% 넘게 바뀌면 초기화 | 없음 |
| `MACD_STOCH_BB` | 세션별 | `lastBuyCandleTime` 쿨다운 3봉 — 창 안 봉 시각으로 센다(평가 누락에 강함, 새 인스턴스는 쿨다운을 잊음) | 없음 |
| `COMPOSITE` | 세션별 | 레짐 감지기 히스테리시스(3봉). 하위 전략은 호출마다 새로 만든다(`StrategySelector.java:137-149`) | 🔴 **동적 가중치** `WeightOverrideStore` — 운영은 `StrategyWeightOptimizer` 가 매일 21:00(UTC) 최근 청산 성과(지수 감쇠)로 갱신한다. 백테스트는 기본 가중치. **과거 구간에서 재현할 수 없다** |
| `COMPOSITE_REGIME_ROUTER` · `COMPOSITE_MTF_CONFIRMED`(안에 CRR) | 세션별 | 레짐 감지기 히스테리시스 + 모멘텀 성분 안의 `GRID` | 없음 |
| `COMPOSITE_MOMENTUM` · `COMPOSITE_MOMENTUM_ICHIMOKU` | 세션별 | 성분 안의 `GRID` | 없음 |
| `COMPOSITE_ETH` | 세션별 | 없음 | 호가 성분은 **운영도 캔들 근사**다 — 실호가 주입은 단독 `ORDERBOOK_IMBALANCE` 세션에만 있다(`LiveTradingService.java:1103`). 두 경로가 같다 |
| `COMPOSITE_BREAKOUT` `COMPOSITE_BREAKOUT_ICHIMOKU` `COMPOSITE_MTF_BTC` `COMPOSITE_PULLBACK_MTF` `COMPOSITE_MEANREV_BB` | 세션별 | 없음(복합 필터의 레짐 판정은 상태 없는 `detectRaw`) | 없음 |

- MTF 계열의 상위봉 합성은 epoch 기준 정렬(`CandleDownsampler.bucketOf`)이라 창 시작 위치에 따라 바뀌지 않는다.

## 3. 가짜 자료 신호 비교

[`StrategyCallPathDivergenceRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/StrategyCallPathDivergenceRunner.java) — 가짜 H1 2,600봉(추세 상승·횡보·급변동·추세 하락 구간 교대) × 시드 2개. 같은 시각의 신호 동작(BUY/SELL/HOLD)만 비교한다.
결과: `scripts/d3_callpath/results_2026-10-28/callpath.csv` (SHA-256 `50100b08…9f02`), 실행 로그 `callpath_run_cp949.log` (`e847a8a6…533c`).

| 시나리오 | 무엇을 바꿨나 | 비교 봉 (전략·시드당) |
|---|---|---|
| BT 짧은 창 | 백테스트식: 구간 시작에 새 인스턴스, 구간 안에서 자라는 창 — 구간 첫 499봉 | 294~487 |
| BT 창 같아진 뒤 | 같은 실행의 구간 500봉째부터 | 1,000 |
| 재시작 | 10개 지점에서 새 인스턴스(500봉 창), 지점마다 300봉 | 3,000 |
| 평가 누락 | 한 인스턴스가 세 지점에서 2봉씩 평가를 건너뜀 | 1,494 |
| 공유 인스턴스 | 팩토리 없는 11개: 한 객체를 두 코인이 번갈아 호출 | 2,099 |

**대조(시험이 차이를 잡는지)**: 호출 횟수 대조는 모든 시나리오에서 차이가 났다(재시작 687 · 누락 426 · BT 창 같아진 뒤 286). 히스테리시스 대조는 재시작 직후 **2봉 안에서만** 20건, BT 짧은 창 2·106건. 시험은 상태 차이를 잡는다.

### 결과 — 후보 풀 24개 (시드 2개 합)

| 시나리오 | 차이가 난 전략 | 차이 |
|---|---|---|
| 재시작 | 없음 | 24개 모두 0 / 6,000 |
| 평가 누락 | `GRID` | 1건(BUY, 누락 2봉 뒤). 나머지 23개 0 |
| 공유 인스턴스 | 없음 | 11개 모두 0 |
| BT 창 같아진 뒤 | 없음 | 24개 모두 0 / 2,000 |
| **BT 짧은 창 (구간 첫 499봉)** | `BOLLINGER` 57(BUY 12) · `VWAP` 13 · `COMPOSITE_MEANREV_BB` 15(BUY 2) · `MACD` 5(BUY 4) · `HEIKIN_ASHI_STOCH` 3 · `RSI` 2 · `FAIR_VALUE_GAP` 1 · `STOCHASTIC_RSI` 1(BUY 1) · `COMPOSITE_REGIME_ROUTER`·`COMPOSITE_MOMENTUM`·`COMPOSITE_MOMENTUM_ICHIMOKU`·`COMPOSITE_MTF_CONFIRMED` 각 1(BUY 1) | 최대 `BOLLINGER` 시드2 49/479(10%). 마지막 차이는 구간 시작 뒤 278봉 이내 |

## 4. 해석 — 이 근거로 말할 수 있는 것만

1. **호출 순서·인스턴스 수명·공유 인스턴스는 24개 신호를 거의 바꾸지 않았다.** 재시작·공유 0건, 평가 누락은 `GRID` 1건이다. 상태가 있는 전략(`GRID`·`MACD_STOCH_BB`·레짐 히스테리시스 계열)도 같았다 — 히스테리시스 대조가 보여 주듯 감지기 상태는 2봉 안에 수렴한다.
2. **실제로 신호를 바꾸는 차이는 초기화(창 길이)다.** 이것은 상태 있는 전략의 문제가 아니라 **백테스트·WF 공통**이다. WF 구간마다 창이 처음부터 자라서, 구간 첫 499봉 동안 운영(항상 500봉)과 지표값이 다르다. B 설정(구간 2023-12-31~2026-09-22, 창 5)에서 OOS 구간은 약 1,430봉이라 **OOS 의 약 3분의 1이 이 영향 안에 있다.**
3. **`COMPOSITE` 는 운영 입력(동적 가중치)에 의존한다.** 운영은 매일 바뀌는 가중치로, 백테스트는 기본 가중치로 평가한다. WF 가 운영 동작을 대리하지 못한다 — `ORDERBOOK_IMBALANCE` 를 뺀 규칙과 같은 사유다.

**한계**
- 가짜 자료·시드 2개다. 실제 시세에서 차이의 크기는 다를 수 있다.
- 신호 일치만 봤다. 거래·성과 차이로 옮겨 재지 않았다.
- 신호가 드문 전략은 검출력이 낮다: `COMPOSITE`(BUY 1·0), `COMPOSITE_ETH`(1·2), `MACD_STOCH_BB`(5·1), `BOLLINGER` 시드1(BUY 0), `COMPOSITE_BREAKOUT`·`_ICHIMOKU`·`MTF_BTC`(1~4).
- 운영의 시각 범위 조회는 자료가 빠지면 500봉보다 적을 수 있다. 이 경우는 재현하지 않았다.

## 5. 제안 (리뷰 결정)

| # | 결정 | 제안 |
|---|---|---|
| G1 | `COMPOSITE` | **풀에서 뺀다** — 운영 입력(동적 가중치)을 과거 구간에서 재현할 수 없다. 풀 24 → 23 |
| G2 | 나머지 상태 보유 전략(`GRID`·`MACD_STOCH_BB`·CRR·MOMENTUM 계열·`MTF_CONFIRMED`) | 남긴다. 호출 순서·수명 차이가 신호를 바꾼 근거가 없다(누락 1건만) |
| G3 | WF 구간 초기화(짧은 창) | 1단계 전에 **구간 예열**을 넣는다: 각 IS·OOS 구간을 평가할 때 구간 직전 최대 499봉을 지표 계산용 이력으로만 주고, 거래는 구간 안에서만 한다(운영과 같은 500봉 창). 미래 자료가 아니라 과거 자료라 엿보기가 아니다. `WF_PERIOD_BIAS_FIX.md` 와 같은 절차(설계 → 가짜 자료 시험 → 리뷰 → 구현)로 따로 다룬다. 대안: 바꾸지 않고 "OOS 약 3분의 1은 짧은 창" 한계로 기록 |
| G4 | 결과 보존 | CSV·로그·해시를 커밋으로 보존 |

- G3 를 넣으면 준비 봉(`minCandles`)이 거래 불가 구간에서 빠지므로 `WF_PERIOD_BIAS_FIX.md` (가) 의 `n_eff` 계산도 함께 맞춘다(`n_eff = 구간 봉 수 − 1`).

## 6. 개정 기록

| 날짜 | 무엇 | 사유 |
|---|---|---|
| 2026-10-28 | 초안 — 코드 확인 표, 가짜 자료 비교(대조 2 · 시드 2), 제안 G1~G4 | 리뷰 11차 (D3 전제 수정) |
