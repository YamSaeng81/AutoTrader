# 복합 전략 사용자 가이드

> 복합 전략이란 여러 개의 기술적 분석 지표/전략을 조합하여 신호를 내는 전략입니다.
> 단일 지표보다 오신호가 적고 시장 상황에 더 유연하게 대응할 수 있습니다.

---

## 목차

1. [공통 — 가중 투표(Weighted Voting) 엔진](#1-공통--가중-투표weighted-voting-엔진)
2. [COMPOSITE — 시장 국면 자동 감지 복합 전략](#2-composite--시장-국면-자동-감지-복합-전략)
3. [COMPOSITE_BTC — BTC 최적화 복합 전략](#3-composite_btc--btc-최적화-복합-전략)
4. [COMPOSITE_ETH — ETH 최적화 복합 전략](#4-composite_eth--eth-최적화-복합-전략)
5. [MACD_STOCH_BB — 추세 확인형 복합 전략](#5-macd_stoch_bb--추세-확인형-복합-전략)
6. [전략 선택 가이드](#6-전략-선택-가이드)

---

## 1. 공통 — 가중 투표(Weighted Voting) 엔진

COMPOSITE_BTC와 COMPOSITE_ETH는 모두 동일한 **가중 투표 엔진** 위에서 동작합니다.

### 신호 결정 방식

각 하위 전략이 BUY / SELL / HOLD 신호와 함께 **신호 강도(confidence, 0.0~1.0)**를 반환하면,
엔진이 이를 가중치와 곱하여 BUY 점수 / SELL 점수를 합산합니다.

```
buyScore  = Σ (weight_i × confidence_i)   ← BUY 신호를 낸 전략만 합산
sellScore = Σ (weight_i × confidence_i)   ← SELL 신호를 낸 전략만 합산
```

합산 후 가중치 총합으로 **정규화(÷ totalWeight)** 하여 점수 범위를 [0, 1]로 통일합니다.

### 최종 신호 판단 기준

| 조건 | 결과 |
|------|------|
| buyScore > 0.4 AND sellScore > 0.4 | **HOLD** (상충 — 방향 불분명) |
| buyScore > **0.6** | **BUY** (강한 매수, strength = score×100) |
| sellScore > **0.6** | **SELL** (강한 매도) |
| buyScore > **0.4** | **BUY** (약한 매수) |
| sellScore > **0.4** | **SELL** (약한 매도) |
| 그 외 | **HOLD** (점수 미달) |

> **임계값 요약**: 0.6 이상 → 강한 신호, 0.4~0.6 → 약한 신호, 0.4 미만 → 관망

### EMA 방향 필터 (선택적)

일부 복합 전략은 **EMA 방향 필터**를 활성화하여 추세 역행 신호를 억제합니다.

```
EMA(20) > EMA(50) → 상승 추세 → SELL 신호 억제 (HOLD로 변환)
EMA(20) < EMA(50) → 하락 추세 → BUY  신호 억제 (HOLD로 변환)
```

역추세 하위 전략(GRID, BOLLINGER)이 포함된 경우 강한 추세에서의 역방향 신호를 걸러냅니다.

---

## 2. COMPOSITE — 시장 국면 자동 감지 복합 전략

> 자세한 내용: [CompositeStrategy.md](./CompositeStrategy.md)

### 한줄 요약

ADX / ATR / 볼린저 밴드 폭을 측정하여 현재 시장 국면(추세·횡보·변동성)을 자동으로 판별하고,
**국면에 최적화된 하위 전략 그룹**을 동적으로 선택하여 가중 투표합니다.

내부 구현: `RegimeAdaptiveStrategy` (상태 보유 — 세션마다 새 인스턴스 생성)

### 시장 국면(Regime) 판별

| 우선순위 | 조건 | 국면 | 선택 전략 그룹 |
|----------|------|------|---------------|
| 1 | ADX(14) > 25 | **TREND** (강한 추세) | SUPERTREND 0.5 + EMA_CROSS 0.3 + ATR_BREAKOUT 0.2 |
| 2 | ATR > ATR_SMA×1.5 (ADX<25) | **VOLATILITY** (급등 변동성) | ATR_BREAKOUT 0.6 + STOCHASTIC_RSI 0.4 |
| 3 | ADX < 20 AND 밴드 폭 ≤ 하위 20% | **RANGE** (횡보) | BOLLINGER 0.4 + RSI 0.4 + GRID 0.2 |
| 4 | ADX 20~25 구간 | **TRANSITIONAL** (전환 중) | 직전 국면 전략 × 가중치 50% |

### 오신호 방지 — Hysteresis

새 국면이 **3회 연속 감지**되어야 실제로 전환됩니다.
중간에 다른 국면이 나오면 카운터가 리셋되어 급격한 전환을 억제합니다.

### RANGE 국면 하위 전략 파라미터

RANGE 국면(BOLLINGER 0.4 + RSI 0.4 + GRID 0.2)의 기본 파라미터:

| 전략 | 파라미터 | 기본값 | 설명 |
|------|---------|--------|------|
| **BOLLINGER** | buyThreshold | **0.2** | %B < 0.2이면 BUY (하단 밴드 20% 이내) |
| **BOLLINGER** | sellThreshold | **0.8** | %B > 0.8이면 SELL (상단 밴드 20% 이내) |
| **RSI** | oversoldLevel | **30.0** | RSI < 30이면 BUY (표준 과매도 기준) |
| **RSI** | overboughtLevel | **70.0** | RSI > 70이면 SELL (표준 과매수 기준) |
| **GRID** | triggerPct | **5.0** | 그리드 레벨 1칸의 5% 이내 근접 시 신호 |

### 매수 / 매도 조건 (예시: TREND 국면)

```
SUPERTREND → BUY(85%) + EMA_CROSS → BUY(70%) + ATR_BREAKOUT → HOLD

buyScore = (0.5×0.85 + 0.3×0.70) / 1.0 = 0.635  →  STRONG BUY
```

---

## 3. COMPOSITE_BTC — BTC 최적화 복합 전략

### 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **GRID** | **0.6** | 가격 격자 기반 분할 매수/매도 |
| **BOLLINGER** | **0.4** | 볼린저 밴드 이탈 시 평균 회귀 매매 |

> BTC 2025년 상반기 백테스트 결과 기반으로 선택된 조합입니다.

> **EMA 방향 필터 활성화**: GRID·BOLLINGER 모두 역추세 전략이므로 추세 방향 역행 신호를 자동 억제합니다.
> - 상승 추세(EMA20 > EMA50): SELL 신호 억제
> - 하락 추세(EMA20 < EMA50): BUY 신호 억제

> **Stateful 전략**: GridStrategy가 상태를 보유하므로 세션마다 새 인스턴스를 생성합니다.

---

### 3-1. GRID 전략 (가중치 0.6)

**횡보장 특화 — 가격 격자(Grid)에서의 분할 매수/매도**

#### 동작 원리

1. 최근 100개 캔들의 **최고가(High) ~ 최저가(Low)**를 10등분하여 그리드 레벨을 설정합니다.
2. 현재가의 그리드 위치를 계산합니다.

#### 매수 조건

```
현재가 위치 ≤ 하위 30% (그리드 하단 근접)
AND 가장 가까운 그리드 레벨과의 거리 ≤ 5%   ← (변경: 0.5% → 5%)
AND 해당 레벨에 이미 진입하지 않은 경우 (중복 방지)
```

- **신호 강도**: 하단에 가까울수록 강함 `(1 - 위치비율) × 100`

> **triggerPct 기준**: 그리드 1칸(레인지/10) 대비 비율. 5%는 1칸의 5% 이내를 의미하며,
> 가격이 그리드 레벨에 어느 정도 근접하면 신호를 내도록 현실적인 수준으로 완화한 값입니다.

#### 매도 조건

```
현재가 위치 ≥ 상위 30% (그리드 상단 근접)
AND 가장 가까운 그리드 레벨과의 거리 ≤ 5%   ← (변경: 0.5% → 5%)
```

- **신호 강도**: 상단에 가까울수록 강함 `위치비율 × 100`

#### 상태 관리 (Stateful)

- 진입한 그리드 레벨을 기억하여 **같은 레벨에 중복 매수를 방지**합니다.
- 그리드 범위가 1% 이상 변경되면 상태를 초기화하고 새 그리드를 시작합니다.

---

### 3-2. BOLLINGER 전략 (가중치 0.4)

**횡보장 특화 — 볼린저 밴드 이탈 후 평균 회귀**

#### 동작 원리

20기간 SMA ± 2σ(표준편차)로 상·하단 밴드를 계산하고,
현재가의 밴드 내 상대 위치인 **%B** 값으로 신호를 결정합니다.

```
%B = (현재가 − 하단밴드) / (상단밴드 − 하단밴드)

%B < 0.2  → 하단 밴드 근접 → BUY  (평균으로 회귀 예상)  ← (변경: 0 → 0.2)
%B > 0.8  → 상단 밴드 근접 → SELL (평균으로 회귀 예상)  ← (변경: 1 → 0.8)
0.2 ≤ %B ≤ 0.8 → HOLD
```

> **buyThreshold / sellThreshold 파라미터**로 조정 가능합니다.
> 기존 조건(`%B < 0`, `%B > 1`)은 밴드를 완전히 이탈해야 신호가 발생해 매우 드물었으나,
> 완화된 조건은 밴드 내 하단/상단 20% 구간에서 신호를 냅니다.

#### 필터 1 — ADX 상한선

```
ADX(14) ≥ 25 → HOLD (추세장에서 역추세 매매 억제)
```

볼린저는 **평균 회귀 전략**이므로 강한 추세장에서는 신호를 내지 않습니다.

#### 필터 2 — Squeeze 감지

```
현재 밴드 폭(Bandwidth) ≤ 최근 30캔들 밴드 폭의 최솟값 → HOLD
```

밴드가 압축(Squeeze) 중일 때는 브레이크아웃이 임박한 상황이므로 평균 회귀 신호를 억제합니다.

#### 신호 강도

- **BUY**: `(buyThreshold - %B) × 100` (%B가 낮을수록 강함, 최대 100)
- **SELL**: `(%B - sellThreshold) × 100` (%B가 높을수록 강함, 최대 100)

---

### COMPOSITE_BTC 매수 예시

```
GRID      → BUY (하단 레벨 근접, strength=72)        → 기여: 0.6 × 0.72 = 0.432
BOLLINGER → BUY (%B=0.12 < 0.2 하단 근접, strength=8) → 기여: 0.4 × 0.08 = 0.032

buyScore = (0.432 + 0.032) / 1.0 = 0.464  →  BUY (약한 매수)

※ EMA 필터: EMA(20) > EMA(50)인 상승 추세라면 이 BUY 신호는 그대로 통과
   EMA(20) < EMA(50)인 하락 추세라면 HOLD로 억제
```

---

## 4. COMPOSITE_ETH — ETH 최적화 복합 전략

### 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **ATR_BREAKOUT** | **0.5** | ATR 기반 변동성 돌파 진입 |
| **ORDERBOOK_IMBALANCE** | **0.3** | 호가 불균형 매수/매도 압력 감지 |
| **EMA_CROSS** | **0.2** | EMA 골든/데드크로스 추세 확인 |

> ETH 2025년 상반기 백테스트 결과 기반으로 선택된 조합입니다.

> **Stateless 전략**: 구성 전략이 모두 상태를 보유하지 않으므로 세션 간 인스턴스를 공유합니다.

---

### 4-1. ATR_BREAKOUT 전략 (가중치 0.5)

**추세장 특화 — ATR 기반 동적 돌파 레벨 계산**

#### 동작 원리

래리 윌리엄스 변동성 돌파 전략을 ATR(평균 실제 범위)로 구현합니다.

```
ATR = 최근 14캔들의 True Range 평균

매수 기준선 = 현재 캔들 시가 + ATR × 1.5
매도 기준선 = 현재 캔들 시가 − ATR × 1.5
```

#### 매수 조건

```
현재 종가 > 매수 기준선 (상방 변동성 돌파)
AND 현재 거래량 > 최근 14캔들 평균 거래량 × 1.5 (거래량 필터)
```

#### 매도 조건

```
현재 종가 < 매도 기준선 (하방 변동성 돌파 — 손절)
AND 현재 거래량 > 최근 14캔들 평균 거래량 × 1.5 (거래량 필터)
```

> 거래량 필터: 저유동성 돌파(가짜 돌파)를 걸러냅니다. 거래량이 부족하면 HOLD.

#### 신호 강도

```
돌파 폭% = (종가 - 기준선) / ATR × 100
strength = 50 + 돌파 폭% / 2   (최대 100)
```

돌파 폭이 클수록 강한 신호입니다.

---

### 4-2. ORDERBOOK_IMBALANCE 전략 (가중치 0.3)

**호가 불균형 기반 매수/매도 압력 감지**

#### 동작 원리

매수 주문량과 매도 주문량의 비율로 시장의 방향을 예측합니다.

```
불균형 비율 = 매수량 / (매수량 + 매도량)

불균형 비율 > 0.70 → BUY  (매수 압력 우세)
불균형 비율 < 0.30 → SELL (매도 압력 우세)
0.30 ≤ 비율 ≤ 0.70 → HOLD
```

#### 실시간 모드 vs 캔들 근사 모드

| 모드 | 데이터 출처 | 정확도 |
|------|-----------|--------|
| **실시간 모드** | WebSocket 실시간 호가창 (bidVolume/askVolume 파라미터 제공 시) | 높음 |
| **캔들 근사 모드** | 백테스팅 — 캔들 OHLCV로 매수/매도량 추정 | 보통 |

**캔들 근사 공식 (Tick Rule 기반)**:
```
상승비율 = (종가 − 저가) / (고가 − 저가 + ε)
추정 매수량 = 거래량 × 상승비율
추정 매도량 = 거래량 × (1 - 상승비율)
```
최근 15캔들을 누적하여 불균형 비율을 계산합니다.

#### Delta 일관성 필터

```
마지막 캔들의 압력 방향 ≠ 이전 누적 방향 → 신호 강도 50% 할인
```

급격한 방향 전환은 스푸핑(허위 주문) 등일 수 있으므로 신호 강도를 낮춥니다.

---

### 4-3. EMA_CROSS 전략 (가중치 0.2)

**추세 추종 — EMA 골든/데드크로스**

#### 동작 원리

빠른 EMA(20)와 느린 EMA(50)의 교차로 추세 전환을 감지합니다.

```
직전: EMA(20) ≤ EMA(50)  →  현재: EMA(20) > EMA(50)  →  BUY  (골든크로스)
직전: EMA(20) ≥ EMA(50)  →  현재: EMA(20) < EMA(50)  →  SELL (데드크로스)
교차 없음 → HOLD
```

#### ADX 필터

```
ADX(14) < 25 → HOLD (추세가 약하면 크로스 신호 억제 — Whipsaw 방지)
```

추세가 충분히 강할 때만 크로스 신호를 인정합니다.

#### 신호 강도

```
EMA 갭 비율 = |EMA(20) - EMA(50)| / EMA(50) × 1000
strength = min(갭 비율, 100)
```

크로스 직후(갭이 적을 때) 강도가 낮고, 추세가 진행될수록 강도가 높아집니다.

---

### COMPOSITE_ETH 매수 예시

```
ATR_BREAKOUT     → BUY (ATR 상방 돌파, strength=65)  → 기여: 0.5 × 0.65 = 0.325
ORDERBOOK_IMBALANCE → BUY (매수 우세 72%, strength=40)  → 기여: 0.3 × 0.40 = 0.120
EMA_CROSS        → HOLD (크로스 없음)                 → 기여: 0

buyScore = (0.325 + 0.120) / 1.0 = 0.445  →  BUY (약한 매수)
```

---

## 5. MACD_STOCH_BB — 추세 확인형 복합 전략

**1시간봉 최적화 — MACD 추세 + StochRSI 타이밍 + 볼린저밴드 지지 확인**

가중 투표 방식이 아닌, 단일 전략 내에서 **3가지 지표를 모두 동시에 확인**하는 AND 조건 방식입니다.

> **Stateful 전략**: 쿨다운 상태를 보유하므로 세션마다 새 인스턴스를 생성합니다.

### 사용 지표 요약

| 지표 | 파라미터 | 역할 |
|------|----------|------|
| MACD | 12 / 26 / 9 | 중기 추세 방향 + 모멘텀 |
| StochRSI | RSI 14, Stoch 14, Signal 3 | 단기 과매도/과매수 타이밍 |
| 볼린저밴드 %B | 기간 20, 2σ | 하단 지지선 근접 확인 |
| 거래량 | 이동평균 20 | 신호 신뢰도 보조 |

---

### 매수 조건 (6가지 모두 충족)

```
1. MACD > 0                          → 중기 상승 추세
2. MACD 히스토그램 증가 (현재 > 이전)  → 상승 힘이 확대되는 중
3. StochRSI %K < 20                  → 단기 과매도 구간 (저점 매수 타이밍)
4. StochRSI %K > %D                  → %K가 %D를 상향 돌파 (골든크로스)
5. 볼린저밴드 %B ≤ 0.35              → 하단 밴드 근처 (지지선 근접)
6. 현재 거래량 ≥ 최근 20캔들 평균    → 거래량 확인
```

> 6가지 중 하나라도 빠지면 매수 신호를 내지 않습니다. **매우 보수적인 매수 조건**입니다.

### 횡보장 필터

```
|MACD| < 0.0005 → HOLD (MACD가 0 근처면 횡보장으로 판단 — 진입 금지)
```

### 쿨다운 (재진입 방지)

```
BUY 신호 발생 후 3캔들 동안 추가 매수 신호 차단
```

같은 구간에서 연속으로 매수하는 것을 방지합니다.

---

### 매도 조건 (하나라도 충족)

```
조건 A: MACD 히스토그램 감소 (현재 < 이전)  → 상승 힘 약화
조건 B: StochRSI %K > 80                    → 단기 과매수 구간
```

- **A만 충족**: `Hist 감소폭 / 이전 Hist × 50` → 강도 계산
- **B만 충족**: `(%K - 80) / 20 × 100` → 강도 계산
- **A + B 동시**: 두 조건 합산 → 강한 매도 신호

---

### 리스크 관리 (매수 신호에 포함)

| 항목 | 기본값 | 설명 |
|------|--------|------|
| 손절(Stop Loss) | **-2%** | 진입가 대비 2% 하락 시 손절 |
| 익절(Take Profit) | **+4%** | 진입가 대비 4% 상승 시 익절 |

매수 신호에 `suggestedStopLoss`, `suggestedTakeProfit` 값이 자동으로 포함됩니다.

---

### 신호 강도 계산

```
BUY  강도 = (20 - %K) / 20 × 100   ← %K가 0에 가까울수록 강함
SELL 강도 = (%K - 80) / 20 × 100   ← %K가 100에 가까울수록 강함
```

---

### MACD_STOCH_BB 매수 신호 예시

```
조건 확인:
  MACD      = 0.000312 > 0           ✅
  Hist      = 0.000089 > 이전 0.000045  ✅
  StochRSI %K = 14.3 < 20            ✅
  %K(14.3) > %D(11.8)                ✅
  %B        = 0.28 ≤ 0.35            ✅
  거래량     = 1250 > 평균 980        ✅

→ MACD_STOCH_BB 매수: MACD=0.000312(>0), Hist=0.000089↑, K=14.3<20, K>D(11.8), %B=0.280≤0.35, 거래량OK
→ strength = (20 - 14.3) / 20 × 100 = 28.5
→ stopLoss   = 진입가 × 0.98
→ takeProfit = 진입가 × 1.04
```

---

## 6. 전략 선택 가이드

| 전략 | 적합한 시장 | 특징 | 리스크 |
|------|------------|------|--------|
| **COMPOSITE** | 모든 시장 (자동 판별) | 시장 국면에 따라 자동으로 전략 교체 | 국면 판별 오류 시 부적절한 전략 사용 |
| **COMPOSITE_BTC** | 횡보장 (레인지 마켓) | 그리드 분할 매수 + 밴드 이탈 역추세 + EMA 필터 | 강한 상승/하락 추세에서 손실 위험 |
| **COMPOSITE_ETH** | 추세장 + 변동성 장세 | ATR 돌파 + 호가 분석 + EMA 추세 | 횡보장에서 가짜 돌파 신호 위험 |
| **MACD_STOCH_BB** | 1시간봉 상승 추세 내 눌림목 | 6가지 조건 AND → 매우 보수적 | 신호 빈도 낮음, 기회 놓칠 수 있음 |

### 주요 차이점

```
COMPOSITE_BTC   : 격자 매수 + 역추세 + EMA 필터 →  횡보 구간에서 반복 매매 (추세 역행 억제)
COMPOSITE_ETH   : 돌파 진입 + 호가 분석        →  추세 초입에 진입
MACD_STOCH_BB   : 눌림목 매수                  →  추세 중 조정 시 저점 매수
COMPOSITE       : 자동 국면 판별               →  시장 상황에 맞는 전략 자동 선택
```

### Stateful vs Stateless

| 전략 | 상태 | 세션 인스턴스 |
|------|------|--------------|
| COMPOSITE | Stateful (국면 감지 상태) | 세션마다 새 인스턴스 |
| COMPOSITE_BTC | Stateful (GridStrategy 레벨 상태) | 세션마다 새 인스턴스 |
| COMPOSITE_ETH | Stateless | 공유 인스턴스 재사용 |
| MACD_STOCH_BB | Stateful (쿨다운 상태) | 세션마다 새 인스턴스 |

---

## 구현 파일 위치

| 전략 | 파일 |
|------|------|
| 가중 투표 엔진 | [CompositeStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
| COMPOSITE 국면 감지 | [RegimeAdaptiveStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/RegimeAdaptiveStrategy.java) |
| COMPOSITE_BTC/ETH 등록 | [CompositePresetRegistrar.java](../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) |
| GRID 전략 | [GridStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java) |
| BOLLINGER 전략 | [BollingerStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerStrategy.java) |
| ATR_BREAKOUT 전략 | [AtrBreakoutStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java) |
| ORDERBOOK_IMBALANCE 전략 | [OrderbookImbalanceStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/orderbook/OrderbookImbalanceStrategy.java) |
| EMA_CROSS 전략 | [EmaCrossStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java) |
| MACD_STOCH_BB 전략 | [MacdStochBbStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macdstochbb/MacdStochBbStrategy.java) |
| 전략 레지스트리 | [StrategyRegistry.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategyRegistry.java) |
| COMPOSITE 상세 문서 | [CompositeStrategy.md](./CompositeStrategy.md) |
