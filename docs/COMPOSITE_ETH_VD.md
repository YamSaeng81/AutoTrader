# COMPOSITE_ETH_VD 전략 가이드

> ETH 최적화 복합 전략에 **Volume Delta**를 편입한 개선 버전.
> 추세 돌파 진입 + 볼륨 압력 확인 + EMA 방향 필터로 구성된 **추세장 특화 전략**입니다.

---

## 목차

1. [전략 개요](#1-전략-개요)
2. [가중 투표 엔진](#2-가중-투표-엔진)
3. [EMA 방향 필터](#3-ema-방향-필터)
4. [하위 전략 — ATR_BREAKOUT (0.4)](#4-하위-전략--atr_breakout-04)
5. [하위 전략 — VOLUME_DELTA (0.3)](#5-하위-전략--volume_delta-03)
6. [하위 전략 — RSI (0.2)](#6-하위-전략--rsi-02)
7. [하위 전략 — EMA_CROSS (0.1)](#7-하위-전략--ema_cross-01)
8. [매수 / 매도 예시](#8-매수--매도-예시)
9. [적합한 시장 환경](#9-적합한-시장-환경)
10. [COMPOSITE_ETH와의 차이점](#10-composite_eth와의-차이점)
11. [파라미터 참조](#11-파라미터-참조)

---

## 1. 전략 개요

### 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **ATR_BREAKOUT** | **0.4** | 변동성 돌파 진입 신호 |
| **VOLUME_DELTA** | **0.3** | 누적 볼륨 압력 방향 확인 |
| **RSI** | **0.2** | 과매수/과매도 브레이크 |
| **EMA_CROSS** | **0.1** | EMA 크로스 추세 확인 |
| **EMA 방향 필터** | — | 추세 역행 신호 억제 (활성화) |
| **ADX 횡보장 필터** | — | ADX < 20이면 즉시 HOLD (활성화) |

### 설계 원칙

```
ADX 횡보장 필터 = ADX < 20이면 하위 전략 평가 없이 즉시 차단 (최우선)
ATR 돌파 = 진입 트리거
Volume Delta = 볼륨 압력으로 신호 신뢰도 보강
RSI = 과매수 구간 진입 차단 (브레이크)
EMA Cross = 중기 추세 방향 보조 확인
EMA 방향 필터 = 하락 추세에서 BUY / 상승 추세에서 SELL 억제
```

이 전략은 **추세장과 변동성 장세**에 최적화되어 있습니다.
ATR 돌파만으로 진입하면 가짜 돌파에 취약하지만, Volume Delta가 볼륨 압력 심화 여부를 추가로 검증하여 신호 품질을 높입니다.

> **Stateless 전략**: 구성 전략이 모두 상태를 보유하지 않으므로 세션 간 인스턴스를 공유합니다.
> EMA 방향 필터: `emaFilterEnabled = true` (활성화)
> ADX 횡보장 필터: `adxFilterEnabled = true` (활성화, ADX < 20이면 즉시 HOLD)

---

## 2. 가중 투표 엔진

각 하위 전략이 BUY / SELL / HOLD 신호와 **신호 강도(confidence, 0.0~1.0)**를 반환하면,
엔진이 가중치와 곱하여 점수를 합산합니다.

```
buyScore  = Σ(weight_i × confidence_i)   ← BUY 신호 전략만 합산
sellScore = Σ(weight_i × confidence_i)   ← SELL 신호 전략만 합산
```

가중치 총합(1.0)으로 정규화하여 점수 범위를 [0, 1]로 통일합니다.

### 최종 신호 판단 기준

| 조건 | 결과 |
|------|------|
| buyScore > 0.4 AND sellScore > 0.4 | **HOLD** (상충) |
| buyScore > **0.6** | **STRONG BUY** |
| sellScore > **0.6** | **STRONG SELL** |
| buyScore > **0.4** | **BUY** |
| sellScore > **0.4** | **SELL** |
| 그 외 | **HOLD** |

---

## 3. EMA 방향 필터

추세 역행 신호를 억제하는 사후 필터입니다.

```
EMA(20) > EMA(50) → 상승 추세 → SELL 신호를 HOLD로 억제
EMA(20) < EMA(50) → 하락 추세 → BUY  신호를 HOLD로 억제
```

ATR 돌파 + Volume Delta가 매수 신호를 내더라도,
**EMA(20) < EMA(50)인 하락 추세 구간에서는 진입하지 않습니다.**

> 최소 캔들 수: EMA(50) 계산을 위해 캔들 50개 이상이 필요합니다.

---

## 4. 하위 전략 — ATR_BREAKOUT (0.4)

**변동성 돌파 진입 — 가중치 0.4 (핵심 신호원)**

### 동작 원리

래리 윌리엄스 변동성 돌파 전략을 ATR 기반으로 구현합니다.

```
ATR = 최근 14캔들의 True Range 평균

매수 기준선 = 현재 캔들 시가 + ATR × 1.5
매도 기준선 = 현재 캔들 시가 − ATR × 1.5
```

### 매수 조건

```
현재 종가 > 매수 기준선 (상방 변동성 돌파)
AND 현재 거래량 > 최근 14캔들 평균 거래량 × 1.5 (거래량 필터)
```

### 매도 조건

```
현재 종가 < 매도 기준선 (하방 변동성 돌파 — 손절)
AND 현재 거래량 > 최근 14캔들 평균 거래량 × 1.5 (거래량 필터)
```

> **거래량 필터**: 저유동성 가짜 돌파를 걸러냅니다. 거래량 미달이면 HOLD.

### 신호 강도

```
돌파 폭% = (종가 - 기준선) / ATR × 100
strength = 50 + 돌파 폭% / 2   (최대 100)
```

돌파 폭이 클수록 강한 신호이며, 최솟값이 50으로 설정되어 있어 돌파 시 항상 중간 이상의 강도를 냅니다.

---

## 5. 하위 전략 — VOLUME_DELTA (0.3)

**누적 볼륨 델타 — 가중치 0.3 (신호 신뢰도 보강)**

### 동작 원리

각 캔들의 매수/매도 주도 볼륨 차이(Delta)를 누적하여 시장 압력의 방향과 강도를 측정합니다.

```
볼륨 분해 (Tick Rule 근사):
  상승비율 = (close - low) / (high - low + ε)
  매수 볼륨 = volume × 상승비율
  매도 볼륨 = volume × (1 - 상승비율)
  캔들 Delta = 매수 볼륨 - 매도 볼륨
```

최근 20캔들의 누적 Delta를 정규화하여 신호를 결정합니다.

```
누적Delta비율 = sum(Delta) / sum(volume)   ← -1.0 ~ +1.0

누적Delta비율 > +0.10 → BUY  (매수 압력 우세)
누적Delta비율 < -0.10 → SELL (매도 압력 우세)
그 외              → HOLD
```

### Delta 추세 확인 필터

lookback 구간(20캔들)을 전반부/후반부로 나눠 평균 Delta를 비교합니다.

```
BUY  발동: 후반부 평균 Delta > 전반부 평균 Delta (매수 압력이 강화되는 추세)
SELL 발동: 후반부 평균 Delta < 전반부 평균 Delta (매도 압력이 강화되는 추세)
미충족 시 → HOLD (압력이 감소하는 방향의 신호 억제)
```

### 다이버전스 필터 (divergenceMode=true)

가격 방향과 Delta 방향이 반대인 경우 허위 신호로 판단하여 억제합니다.

```
가격 하락 + 누적Delta 양수 → 강세 다이버전스 → BUY를 HOLD로 격하
가격 상승 + 누적Delta 음수 → 약세 다이버전스 → SELL을 HOLD로 격하
```

### ORDERBOOK_IMBALANCE와의 차이점

COMPOSITE_ETH에 있던 ORDERBOOK_IMBALANCE 전략도 동일한 Tick Rule 근사를 사용합니다.
VOLUME_DELTA는 여기에 **추세 확인 필터**와 **다이버전스 필터**를 추가하여 신호 품질을 높인 상위 호환 전략입니다.
두 전략을 함께 사용하면 같은 데이터를 중복 계산하는 문제가 생기므로 COMPOSITE_ETH_VD에서는 ORDERBOOK_IMBALANCE를 제거했습니다.

---

## 6. 하위 전략 — RSI (0.2)

**과매수/과매도 브레이크 — 가중치 0.2**

### 역할 — 트리거가 아닌 브레이크

RSI는 이 전략에서 **진입을 막는 역할**이 주목적입니다.

```
ATR BUY 발동 + RSI > 70 (과매수) → RSI SELL 신호 → 상충 → HOLD
  → 이미 과매수 구간에 진입한 돌파는 가짜 돌파일 가능성이 높음

RSI 단독 BUY (RSI < 30) → buyScore = 0.2 → 0.4 미달 → 단독 진입 불가
  → RSI 과매도만으로는 진입하지 않음, ATR 돌파 함께 필요
```

### 매수 조건

```
RSI(14) < 30 → BUY (과매도 — 반등 기대)
```

### 매도 조건

```
RSI(14) > 70 → SELL (과매수 — 하락 기대)
```

### 피봇 기반 다이버전스 감지

```
가격 신저점(Lower Low) + RSI 고점(Higher Low) → 강세 다이버전스 → BUY
가격 신고점(Higher High) + RSI 저점(Lower High) → 약세 다이버전스 → SELL
```

### 신호 강도

```
BUY  강도 = (oversold - RSI) / oversold × 100   (RSI가 낮을수록 강함)
SELL 강도 = (RSI - overbought) / (100 - overbought) × 100   (RSI가 높을수록 강함)
```

---

## 7. 하위 전략 — EMA_CROSS (0.1)

**추세 추종 — EMA 골든/데드크로스 (가중치 0.1)**

### 동작 원리

빠른 EMA(20)와 느린 EMA(50)의 교차로 추세 전환을 감지합니다.

```
직전: EMA(20) ≤ EMA(50)  →  현재: EMA(20) > EMA(50)  →  BUY  (골든크로스)
직전: EMA(20) ≥ EMA(50)  →  현재: EMA(20) < EMA(50)  →  SELL (데드크로스)
교차 없음 → HOLD
```

### ADX 필터

```
ADX(14) < 25 → HOLD (추세가 약하면 크로스 신호 억제 — Whipsaw 방지)
```

### 신호 강도

```
EMA 갭 비율 = |EMA(20) - EMA(50)| / EMA(50) × 1000
strength = min(갭 비율, 100)
```

크로스 직후(갭이 적을 때) 강도가 낮고, 추세가 진행될수록 강도가 높아집니다.

> EMA_CROSS의 가중치는 0.2이며 신호 빈도도 낮기 때문에 단독으로 최종 신호를 결정하지 않습니다.
> ATR_BREAKOUT 또는 VOLUME_DELTA의 신호를 보강하는 역할입니다.

---

## 8. 매수 / 매도 예시

### 매수 예시 (BUY)

```
ATR_BREAKOUT  → BUY  (ATR 상방 돌파, 거래량 확인, strength=72)      → 0.4 × 0.72 = 0.288
VOLUME_DELTA  → BUY  (누적Delta 매수 우세, Delta 가속, strength=55)  → 0.3 × 0.55 = 0.165
RSI           → HOLD (RSI=54, 중립 구간)                             → 0
EMA_CROSS     → HOLD (크로스 없음)                                   → 0

buyScore  = (0.288 + 0.165) / 1.0 = 0.453  →  BUY
sellScore = 0

EMA 방향 필터: EMA(20)=52,300 > EMA(50)=51,100 → 상승 추세 → BUY 통과

최종: BUY (score=0.453)
```

### RSI 과매수 브레이크 예시

```
ATR_BREAKOUT  → BUY  (ATR 상방 돌파, strength=70)                    → 0.4 × 0.70 = 0.280
VOLUME_DELTA  → BUY  (누적Delta 매수 우세, strength=50)               → 0.3 × 0.50 = 0.150
RSI           → SELL (RSI=76 > 70, 과매수, strength=20)              → 0.2 × 0.20 = 0.040
EMA_CROSS     → HOLD                                                 → 0

buyScore  = (0.280 + 0.150) / 1.0 = 0.430
sellScore = 0.040 / 1.0 = 0.040

buyScore(0.430) > 0.4 AND sellScore(0.040) < 0.4 → BUY 통과

※ RSI의 SELL 강도가 약해 상충이 아닌 BUY 통과. RSI=85 수준이면:
   RSI strength=(85-70)/30×100=50 → sellScore=0.2×0.50=0.100
   buyScore(0.430) > 0.4 AND sellScore(0.100) < 0.4 → 여전히 BUY

   RSI 단독으로 상충을 만들려면 sellScore > 0.4 → RSI strength > 2.0 (불가)
   → RSI는 투표 불참이 아닌 sellScore를 올려 buyScore와 격차를 줄이는 역할
```

### SELL 억제 예시 (EMA 방향 필터 동작)

```
ATR_BREAKOUT  → SELL (ATR 하방 돌파, strength=60)               → 0.4 × 0.60 = 0.240
VOLUME_DELTA  → SELL (누적Delta 매도 우세, strength=45)          → 0.3 × 0.45 = 0.135
RSI           → HOLD (RSI=48, 중립)                             → 0
EMA_CROSS     → HOLD                                            → 0

sellScore = 0.375  →  원래 HOLD (0.4 미달)

※ RSI=20 (과매도) 이었다면:
   RSI → BUY (strength=33) → 0.2 × 0.33 = 0.066
   buyScore=0.066, sellScore=0.375 → SELL (약한 매도)

   EMA 방향 필터: EMA(20)=52,300 > EMA(50)=51,100 → 상승 추세 → SELL 억제
   최종: HOLD
```

### 매수 억제 예시 (EMA 방향 필터 동작)

```
ATR_BREAKOUT  → BUY  (ATR 상방 돌파, strength=65)               → 0.4 × 0.65 = 0.260
VOLUME_DELTA  → BUY  (누적Delta 매수 우세, strength=50)          → 0.3 × 0.50 = 0.150
RSI           → HOLD (RSI=52)                                   → 0
EMA_CROSS     → HOLD                                            → 0

buyScore = 0.410  →  원래 BUY

EMA 방향 필터: EMA(20)=48,200 < EMA(50)=51,100 → 하락 추세 → BUY 억제

최종: HOLD (EMA필터 BUY억제 — 하락추세)
```

---

## 9. 적합한 시장 환경

| 시장 환경 | 성과 예상 | 이유 |
|----------|----------|------|
| 추세 상승장 | **우수** | ATR 돌파 + VD 매수 압력 + EMA 필터 삼중 확인 |
| 추세 하락장 | **우수** | 하락 방향 돌파 감지 / EMA 필터로 역행 BUY 억제 |
| 변동성 급등 | **양호** | ATR 기반이라 변동성 확대 시 기준선 자동 조정 |
| 횡보장 | **주의** | 가짜 돌파 신호 위험 — 거래량 필터·Delta 필터로 일부 방어 |
| 저유동성 | **비권장** | 거래량 필터가 대부분 HOLD 처리하여 신호 빈도 매우 낮음 |

> 횡보장 방어를 강화하려면 RSI 전략 추가를 검토하세요.

### 다양한 코인 적용 가능성

ETH 전용으로 설계되었으나, ATR 기반 동적 기준선 덕분에 **변동성이 다른 코인에도 안정적으로 동작**합니다.
다만 변동성이 매우 높은 코인(SOL, DOGE 등)에서는 ATR multiplier를 1.5보다 높게(1.8~2.0) 설정하는 것을 권장합니다.

---

## 10. COMPOSITE_ETH와의 차이점

| 항목 | COMPOSITE_ETH | COMPOSITE_ETH_VD |
|------|--------------|-----------------|
| 구성 | ATR(0.5) + OB(0.3) + EMA(0.2) | ATR(0.4) + VD(0.3) + RSI(0.2) + EMA(0.1) |
| 볼륨 신호 | ORDERBOOK_IMBALANCE | VOLUME_DELTA (추세/다이버전스 필터 추가) |
| 과매수 방어 | 없음 | **RSI 브레이크 추가** |
| 신호 중복 | OB와 VD 동시 사용 시 중복 | 단일 볼륨 신호원으로 정리 |
| Delta 추세 필터 | 없음 | 있음 |
| 다이버전스 감지 | 없음 | 가격-Delta 다이버전스 필터 |
| EMA 방향 필터 | **비활성화** | **활성화** |
| 적합성 | ETH H1 2025 백테스트 기반 | VD 편입 + RSI 브레이크 + 역방향 신호 억제 |

---

## 11. 파라미터 참조

### ATR_BREAKOUT 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `atrPeriod` | 14 | ATR 계산 기간 |
| `multiplier` | 1.5 | 돌파 기준선 배수 (변동성 낮은 코인: 1.2~1.3 / 높은 코인: 1.8~2.0) |
| `volumeFilterEnabled` | true | 거래량 필터 활성화 |
| `volumeMultiplier` | 1.5 | 평균 거래량 대비 최소 배수 |
| `useStopLoss` | true | 하방 돌파 시 SELL 신호 발생 |

### VOLUME_DELTA 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `lookback` | 20 | 누적 Delta 계산 기간 (캔들 수) |
| `signalThreshold` | 0.10 | 신호 발동 최소 Delta 비율 (±10%) |
| `divergenceMode` | true | 다이버전스 필터 활성화 |

### RSI 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `period` | 14 | RSI 계산 기간 |
| `oversoldLevel` | 30.0 | 과매도 기준 (미달 시 BUY) |
| `overboughtLevel` | 70.0 | 과매수 기준 (초과 시 SELL) |
| `useDivergence` | true | 피봇 기반 다이버전스 감지 활성화 |
| `pivotWindow` | 10 | 스윙 고점/저점 탐색 범위 (캔들 수) |

### EMA_CROSS 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `fastPeriod` | 20 | 단기 EMA 기간 |
| `slowPeriod` | 50 | 장기 EMA 기간 |
| `adxPeriod` | 14 | ADX 기간 |
| `adxThreshold` | 25 | ADX 최소 임계값 (미달 시 HOLD) |

### EMA 방향 필터 파라미터 (CompositeStrategy 레벨)

| 파라미터 | 값 | 설명 |
|---------|-----|------|
| `emaFilterEnabled` | **true** | 방향 필터 활성화 |
| `DEFAULT_EMA_SHORT` | 20 | 단기 EMA |
| `DEFAULT_EMA_LONG` | 50 | 장기 EMA (최소 캔들 50개 필요) |

---

## 구현 파일 위치

| 항목 | 파일 |
|------|------|
| 전략 등록 | [CompositePresetRegistrar.java](../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) |
| 가중 투표 엔진 + EMA 필터 | [CompositeStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
| ATR_BREAKOUT 전략 | [AtrBreakoutStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java) |
| VOLUME_DELTA 전략 | [VolumeDeltaStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java) |
| RSI 전략 | [RsiStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java) |
| EMA_CROSS 전략 | [EmaCrossStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java) |
| 복합 전략 전체 가이드 | [COMPOSITE_STRATEGIES_GUIDE.md](./COMPOSITE_STRATEGIES_GUIDE.md) |
