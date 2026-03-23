# CompositeStrategy 상세 문서

## 개요

CompositeStrategy는 **시장 국면(Regime)에 따라 다른 전략 그룹을 동적으로 선택**하고, 선택된 전략들의 신호를 **가중 투표(Weighted Voting)**로 합산하여 최종 매매 신호를 결정하는 메타 전략이다.

전체 흐름:
```
[캔들 데이터]
     │
     ▼
MarketRegimeDetector         ← ADX / ATR / BB Bandwidth 기반 시장 상태 판단
     │ TREND / RANGE / VOLATILITY / TRANSITIONAL
     ▼
StrategySelector             ← Regime별 전략 그룹 + 가중치 배정
     │ List<WeightedStrategy>
     ▼
CompositeStrategy.evaluate() ← 각 전략 신호 × 가중치 합산 → 최종 BUY/SELL/HOLD
```

---

## 1단계: MarketRegimeDetector — 시장 국면 감지

최소 50개 캔들을 사용하며, 아래 우선순위로 Regime을 판단한다.

| 우선순위 | 조건 | Regime |
|----------|------|--------|
| 1 | ADX(14) > 25 | **TREND** (강한 추세) |
| 2 | ATR(14) > ATR_SMA(20) × 1.5 (단, ADX < 25) | **VOLATILITY** (변동성 급등) |
| 3 | ADX < 20 **AND** BB Bandwidth ≤ 하위 20% 퍼센타일 | **RANGE** (횡보) |
| 4 | 그 외 (ADX 20~25 구간) | **TRANSITIONAL** (전환 중) |

### 사용 지표 계산식

**ADX (Average Directional Index, 기간 14)**
```
TR  = max(High−Low, |High−PrevClose|, |Low−PrevClose|)
+DM = High−PrevHigh  (> 0 이고 +DM > -DM 일 때)
-DM = PrevLow−Low    (> 0 이고 -DM > +DM 일 때)

ATR14 = Wilder EMA(TR, 14)
+DI14 = 100 × Wilder EMA(+DM, 14) / ATR14
-DI14 = 100 × Wilder EMA(-DM, 14) / ATR14
DX    = 100 × |+DI14 − -DI14| / (+DI14 + -DI14)
ADX   = Wilder EMA(DX, 14)
```

**ATR Spike 판단**
```
currentATR = ATR 시리즈의 마지막 값
atrSMA     = ATR 시리즈 최근 20개의 단순 평균
spike      = currentATR > atrSMA × 1.5
```

**BB Bandwidth (기간 20, 2σ)**
```
SMA20      = 최근 20 종가 평균
stdDev     = 표준편차 (모집단)
upperBand  = SMA20 + 2 × stdDev
lowerBand  = SMA20 − 2 × stdDev
bandwidth  = (upperBand − lowerBand) / SMA20

좁음 기준  = 직전 30개 bandwidth 값 중 하위 20% 퍼센타일 이하
```

### Hysteresis (오진 방지)

Regime 전환 오신호를 막기 위해 **새 Regime이 3회 연속 감지되어야** 실제 전환된다. 중간에 다른 Regime이 끼어들면 카운터가 리셋된다.

```
연속 감지 횟수 < 3 → 이전 Regime 유지
연속 감지 횟수 ≥ 3 → 새 Regime으로 전환
```

---

## 2단계: StrategySelector — 전략 그룹 선택

Regime별로 활성화할 전략과 가중치가 고정 배정된다.

| Regime | 전략 | 가중치 | 선택 이유 |
|--------|------|--------|-----------|
| **TREND** | SUPERTREND | 0.5 | 추세 방향 + 손절선 명확 |
| | EMA_CROSS | 0.3 | 골든/데드크로스 추세 확인 |
| | ATR_BREAKOUT | 0.2 | 변동성 돌파로 진입 타이밍 보조 |
| **RANGE** | BOLLINGER | 0.4 | 밴드 이탈 후 평균회귀 |
| | RSI | 0.4 | 과매수/과매도 역추세 |
| | GRID | 0.2 | 일정 구간 격자 매매 |
| **VOLATILITY** | ATR_BREAKOUT | 0.6 | 변동성 급등 시 돌파 매매 |
| | STOCHASTIC_RSI | 0.4 | 단기 모멘텀 확인 |
| **TRANSITIONAL** | 직전 Regime 전략 그룹 × 0.5 | (축소) | 전환 불확실 구간 — 포지션 크기 절반 축소 |

> TRANSITIONAL 시 직전 Regime이 다시 TRANSITIONAL이면 RANGE 그룹을 기본으로 사용한다.

---

## 3단계: CompositeStrategy — Weighted Voting

각 전략의 신호에 `weight × confidence`를 곱해 buyScore / sellScore를 합산한다.

### 계산식

```
buyScore  = Σ (weight_i × confidence_i)   // BUY 신호를 낸 전략만 합산
sellScore = Σ (weight_i × confidence_i)   // SELL 신호를 낸 전략만 합산

confidence_i ∈ [0.0, 1.0]  — 각 전략이 자체 계산한 신호 강도
```

### 최종 신호 결정 로직

```
if (buyScore > 0.4 AND sellScore > 0.4)    → HOLD  (상충 신호)

else if (buyScore  > 0.6)                  → BUY   (강한 매수, strength = buyScore × 100)
else if (sellScore > 0.6)                  → SELL  (강한 매도, strength = sellScore × 100)
else if (buyScore  > 0.4)                  → BUY   (약한 매수)
else if (sellScore > 0.4)                  → SELL  (약한 매도)
else                                       → HOLD  (점수 미달)
```

### 계산 예시 (TREND Regime)

| 전략 | 신호 | 가중치 | Confidence | 기여 점수 |
|------|------|--------|------------|-----------|
| SUPERTREND | BUY | 0.5 | 0.85 | 0.425 |
| EMA_CROSS | BUY | 0.3 | 0.70 | 0.210 |
| ATR_BREAKOUT | HOLD | 0.2 | — | 0 |

```
buyScore  = 0.425 + 0.210 = 0.635  → STRONG_BUY (strength = 63.5)
sellScore = 0
```
→ `STRONG_BUY score=0.64 [SUPERTREND:BUY(43) EMA_CROSS:BUY(21) ATR_BREAKOUT:HOLD(0)]`

---

## 전략별 Confidence 계산 요약

각 개별 전략이 `StrategySignal`에 담아 반환하는 confidence 값의 기준:

| 전략 | Confidence 결정 기준 |
|------|---------------------|
| SUPERTREND | 현재가와 Supertrend 선의 이격률 (정규화) |
| EMA_CROSS | EMA9 / EMA21 이격률 (크로스 직후 높음) |
| ATR_BREAKOUT | 돌파 크기 / ATR 배수 |
| BOLLINGER | %B 값 (0 또는 1에 근접할수록 높음) |
| RSI | 과매수(>70) / 과매도(<30) 진입 깊이 |
| GRID | 현재 그리드 레벨 이탈 폭 |
| STOCHASTIC_RSI | %K / %D 교차 강도 |

---

## 구현 파일 위치

| 역할 | 파일 |
|------|------|
| Regime 감지 | [MarketRegimeDetector.java](../core-engine/src/main/java/com/cryptoautotrader/core/regime/MarketRegimeDetector.java) |
| Regime 열거형 | [MarketRegime.java](../core-engine/src/main/java/com/cryptoautotrader/core/regime/MarketRegime.java) |
| 전략 선택 | [StrategySelector.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/StrategySelector.java) |
| 가중 전략 래퍼 | [WeightedStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WeightedStrategy.java) |
| 복합 신호 결합 | [CompositeStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
