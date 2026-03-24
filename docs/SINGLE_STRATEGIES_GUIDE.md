# 단일 전략 사용자 가이드

> 단일 전략은 하나의 기술적 지표를 기반으로 독립적으로 동작하는 전략입니다.
> 복합 전략의 하위 전략으로 조합하거나 단독으로 사용할 수 있습니다.

---

## 목차

1. [전략 목록 요약](#1-전략-목록-요약)
2. [VWAP — 거래량 가중 평균 가격 역추세](#2-vwap--거래량-가중-평균-가격-역추세)
3. [RSI — 상대강도지수 + 다이버전스](#3-rsi--상대강도지수--다이버전스)
4. [MACD — 이동평균 수렴·발산 크로스](#4-macd--이동평균-수렴발산-크로스)
5. [SUPERTREND — ATR 기반 동적 추세선](#5-supertrend--atr-기반-동적-추세선)
6. [ATR_BREAKOUT — 변동성 돌파 진입](#6-atr_breakout--변동성-돌파-진입)
7. [EMA_CROSS — EMA 골든/데드크로스](#7-ema_cross--ema-골든데드크로스)
8. [BOLLINGER — 볼린저 밴드 평균 회귀](#8-bollinger--볼린저-밴드-평균-회귀)
9. [ORDERBOOK_IMBALANCE — 호가 불균형 압력 감지](#9-orderbook_imbalance--호가-불균형-압력-감지)
10. [VOLUME_DELTA — 누적 볼륨 델타](#10-volume_delta--누적-볼륨-델타)
11. [STOCHASTIC_RSI — 스토캐스틱 RSI 크로스](#11-stochastic_rsi--스토캐스틱-rsi-크로스)
12. [GRID — 격자 기반 분할 매매 (Stateful)](#12-grid--격자-기반-분할-매매-stateful)

---

## 1. 전략 목록 요약

| 전략 이름 | 유형 | 적합한 시장 | ADX 필터 | Stateful |
|-----------|------|------------|----------|----------|
| **VWAP** | 역추세 | 횡보 | ADX < 35 | 아니오 |
| **RSI** | 오실레이터 + 다이버전스 | 횡보 / 추세 초입 | 없음 | 아니오 |
| **MACD** | 추세 추종 | 추세장 | ADX > 25 | 아니오 |
| **SUPERTREND** | 추세 추종 | 추세장 | 없음 | 아니오 |
| **ATR_BREAKOUT** | 변동성 돌파 | 변동성 장세 | 없음 | 아니오 |
| **EMA_CROSS** | 추세 추종 | 추세장 | ADX > 25 | 아니오 |
| **BOLLINGER** | 역추세 | 횡보 | ADX < 25 | 아니오 |
| **ORDERBOOK_IMBALANCE** | 호가 분석 | 모든 시장 | 없음 | 아니오 |
| **VOLUME_DELTA** | 볼륨 분석 | 모든 시장 | 없음 | 아니오 |
| **STOCHASTIC_RSI** | 오실레이터 | 횡보 / 변동성 | ADX < 30 | 아니오 |
| **GRID** | 격자 매매 | 횡보 | 없음 | **예** |

> **ADX(14) 기준**: < 20 횡보, 20~25 전환, > 25 추세, > 35 강한 추세

---

## 2. VWAP — 거래량 가중 평균 가격 역추세

**역추세 전략 — 현재가가 VWAP 대비 크게 할인/프리미엄 상태일 때 진입**

### 동작 원리

VWAP(Volume Weighted Average Price)는 거래량으로 가중된 평균 가격입니다.
현재가가 VWAP에서 일정 비율 이상 벗어나면 평균으로 회귀할 것을 기대하고 매매합니다.

```
VWAP = Σ(전형가격 × 거래량) / Σ(거래량)
전형가격(Typical Price) = (고가 + 저가 + 종가) / 3

편차% = (현재가 - VWAP) / VWAP × 100
```

### VWAP 계산 모드

| 모드 | 설명 | 기본값 |
|------|------|--------|
| **세션 앵커 모드** (`anchorSession=true`) | 오늘 UTC 00:00 기점 캔들부터 누적 계산 | **기본** |
| **롤링 모드** (`anchorSession=false`) | 최근 `period`개 캔들 기준 계산 | - |

> 당일 캔들이 3개 미만이면 세션 모드에서도 롤링 모드로 자동 대체합니다.

### 매수 / 매도 조건

```
편차% ≤ -thresholdPct  → BUY  (VWAP 대비 N% 이상 할인)
편차% ≥ +thresholdPct  → SELL (VWAP 대비 N% 이상 프리미엄)
그 외                  → HOLD
```

### ADX 상한선 필터

```
ADX(14) ≥ 35 → HOLD (강한 추세장에서 역추세 매매 억제)
```

VWAP는 **평균 회귀 전략**이므로 강한 추세가 형성된 구간에서는 신호를 내지 않습니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `thresholdPct` | `1.5` | 신호 발동 편차 임계값 (%) |
| `period` | `20` | 롤링 VWAP 계산 기간 |
| `adxPeriod` | `14` | ADX 계산 기간 |
| `adxMaxThreshold` | `35.0` | ADX 상한선 (초과 시 HOLD) |
| `anchorSession` | `true` | 세션 앵커 모드 활성화 |

### 신호 강도

```
strength = min(|편차%| / thresholdPct × 50, 100)
```

임계값 대비 편차가 클수록 강한 신호입니다.

### 매수 예시

```
VWAP = 50,000 / 현재가 = 49,200
편차% = (49200 - 50000) / 50000 × 100 = -1.6%
임계값 = -1.5%

→ BUY: VWAP 할인 -1.6% (임계값 -1.5%)
→ strength = min(1.6 / 1.5 × 50, 100) = 53.3
```

---

## 3. RSI — 상대강도지수 + 다이버전스

**오실레이터 전략 — 과매도/과매수 + 피봇 기반 다이버전스 감지**

### 동작 원리

RSI(Relative Strength Index)는 최근 N기간의 상승/하락 비율로 시장의 모멘텀을 측정합니다.
Wilder's Smoothing 방식으로 계산하며, 과매도(낮은 RSI)에서 매수, 과매수(높은 RSI)에서 매도합니다.

```
RS  = 평균 상승폭 / 평균 하락폭   (Wilder's Smoothing)
RSI = 100 - (100 / (1 + RS))
```

### 신호 1 — 기본 과매도/과매수

```
RSI < oversoldLevel (기본 25) → BUY  (과매도 — 반등 기대)
RSI > overboughtLevel (기본 60) → SELL (과매수 — 하락 기대)
```

> 과매도 기준 25, 과매수 기준 60은 일반적인 30/70보다 보수적인 설정입니다.

### 신호 2 — 피봇 기반 다이버전스 (`useDivergence=true` 시)

다이버전스(Divergence)란 가격 방향과 RSI 방향이 반대로 움직이는 현상으로, 추세 전환 신호입니다.

**강세 다이버전스 (BUY)**:
```
현재 종가 < 최근 스윙 저점(Lower Low)
AND 현재 RSI > 해당 스윙 저점 RSI(Higher Low)
AND 현재 RSI < oversoldLevel + 10   (과매도 근방에서만 유효)
```

**약세 다이버전스 (SELL)**:
```
현재 종가 > 최근 스윙 고점(Higher High)
AND 현재 RSI < 해당 스윙 고점 RSI(Lower High)
AND 현재 RSI > overboughtLevel - 10 (과매수 근방에서만 유효)
```

> 스윙 고/저점은 최근 `pivotWindow`(기본 10)개 캔들 내에서 탐색합니다.
> 다이버전스 신호가 기본 과매도/과매수 신호보다 **우선 평가**됩니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `period` | `14` | RSI 계산 기간 |
| `oversoldLevel` | `25.0` | 과매도 기준선 |
| `overboughtLevel` | `60.0` | 과매수 기준선 |
| `useDivergence` | `true` | 피봇 다이버전스 감지 활성화 |
| `pivotWindow` | `10` | 스윙 고/저점 탐색 범위 |

### 신호 강도

```
BUY  강도 = (oversoldLevel - RSI) / oversoldLevel × 100
SELL 강도 = (RSI - overboughtLevel) / (100 - overboughtLevel) × 100
다이버전스 = (100 - RSI) / 100 × 100  (강세) / RSI / 100 × 100 (약세)
```

---

## 4. MACD — 이동평균 수렴·발산 크로스

**추세 추종 전략 — MACD선과 Signal선의 교차로 추세 전환 감지**

### 동작 원리

```
MACD선   = EMA(fast) - EMA(slow)
Signal선 = MACD선의 EMA(signalPeriod)
히스토그램 = MACD선 - Signal선
```

### 코인별 기본 파라미터

| coinPair 파라미터 | fast | slow | signal |
|------------------|------|------|--------|
| BTC 포함 | 14 | 22 | 9 |
| ETH 포함 | 10 | 26 | 9 |
| 그 외 | 12 | 24 | 9 |

> 2024~2025 상반기 그리드 서치 결과 기반으로 설정된 코인별 최적 파라미터입니다.

### 매수 조건 (골든크로스)

```
현재: MACD선 > Signal선
이전: MACD선 ≤ Signal선 (크로스 발생)
AND MACD선 > 0            (제로라인 필터 — 약세 구간 매수 방지)
AND 히스토그램 확대 중     (가짜 크로스 방지)
```

### 매도 조건 (데드크로스)

```
현재: MACD선 < Signal선
이전: MACD선 ≥ Signal선 (크로스 발생)
AND MACD선 < 0            (제로라인 필터 — 강세 구간 매도 방지)
AND 히스토그램 음수 방향 확대 중 (가짜 크로스 방지)
```

### ADX 최소선 필터

```
ADX(14) < 25 → HOLD (추세가 약하면 크로스 신호 억제 — 횡보장 오신호 방지)
```

### 필터 요약

| 필터 | BUY 조건 | SELL 조건 |
|------|---------|---------|
| 제로라인 | MACD > 0 | MACD < 0 |
| 히스토그램 | 현재 Hist > 이전 Hist | 현재 Hist < 이전 Hist |
| ADX | ADX ≥ 25 | ADX ≥ 25 |

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `fastPeriod` | 코인별 | EMA fast 기간 |
| `slowPeriod` | 코인별 | EMA slow 기간 |
| `signalPeriod` | `9` | Signal EMA 기간 |
| `adxPeriod` | `14` | ADX 계산 기간 |
| `adxThreshold` | `25.0` | ADX 최소선 (미달 시 HOLD) |
| `coinPair` | - | 코인 페어 (BTC/ETH 기본값 선택용) |

### 신호 강도

```
strength = min(|히스토그램| / |Signal선| × 1000, 100)
```

히스토그램이 Signal선 대비 클수록 강한 신호입니다.

---

## 5. SUPERTREND — ATR 기반 동적 추세선

**추세 추종 전략 — ATR로 계산된 동적 지지/저항선을 기준으로 추세 방향 판단**

### 동작 원리

ATR(Average True Range)을 활용하여 동적인 상·하단 밴드를 계산하고,
종가가 어떤 밴드 위에 있는지로 추세 방향을 결정합니다.

```
HL2 = (고가 + 저가) / 2
상단 밴드(기본) = HL2 + multiplier × ATR(atrPeriod)
하단 밴드(기본) = HL2 - multiplier × ATR(atrPeriod)
```

### 밴드 연속성 조정 (Supertrend 핵심)

단순 밴드 계산과 달리 이전 밴드를 기준으로 연속성을 유지합니다:

```
하단 밴드 최종 = max(현재 기본 하단, 이전 최종 하단)  ← 이전에 상승 추세였던 경우
상단 밴드 최종 = min(현재 기본 상단, 이전 최종 상단)  ← 이전에 하락 추세였던 경우
```

밴드가 역방향으로 후퇴하지 않도록 하여 신호 안정성을 높입니다.

### 추세 판단 및 신호

```
상승 추세: 종가 ≥ 하단 밴드 (최종) → BUY
하락 추세: 종가 < 상단 밴드 (최종) → SELL
```

### 추세 전환 신호 (강한 신호)

```
이전: 하락 추세 → 현재: 상승 추세  →  강한 BUY  (strength ≥ 70)
이전: 상승 추세 → 현재: 하락 추세  →  강한 SELL (strength ≥ 70)
```

추세 전환이 없고 추세 **유지** 중인 경우는 약한 신호(strength ÷ 2)를 반환합니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `atrPeriod` | `10` | ATR 계산 기간 |
| `multiplier` | `3.0` | 밴드 폭 배수 (클수록 신호 감소, 추세 안정) |

### 신호 강도

```
distance = |종가 - 활성 밴드선| / 종가 × 10000
strength = min(distance, 100)

추세 전환 시: strength = max(계산값, 70)
추세 유지 시: strength = 계산값 / 2
```

### 매수 예시

```
이전 추세: SELL (하락)
현재 종가: 50,200 > 상단 밴드 50,100  →  상승 추세로 전환

→ SUPERTREND 상승 전환: 종가=50200, 밴드=50100 (period=10, mult=3.0)
→ distance = |50200 - 50100| / 50200 × 10000 = 19.9
→ strength = max(19.9, 70) = 70  (전환 시 최소 70 보장)
```

---

## 6. ATR_BREAKOUT — 변동성 돌파 진입

**변동성 돌파 전략 — ATR로 계산된 돌파 기준선 상방/하방 이탈 시 진입**

### 동작 원리

래리 윌리엄스 변동성 돌파 전략을 ATR 기반으로 구현합니다.
당일 시가를 기준으로 ATR 배수를 더하거나 빼 돌파 레벨을 계산합니다.

```
ATR = 최근 atrPeriod캔들의 True Range 평균

매수 기준선 = 현재 캔들 시가 + ATR × multiplier
매도 기준선 = 현재 캔들 시가 − ATR × multiplier
```

### 매수 조건

```
현재 종가 > 매수 기준선 (상방 돌파)
AND 현재 거래량 > 최근 atrPeriod캔들 평균 거래량 × volumeMultiplier
```

### 매도 조건

```
현재 종가 < 매도 기준선 (하방 돌파 — 손절/공매도)
AND 현재 거래량 > 최근 atrPeriod캔들 평균 거래량 × volumeMultiplier
```

> **거래량 필터**: 저유동성 가짜 돌파를 걸러냅니다. 거래량 기준 미달 시 HOLD.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `atrPeriod` | `14` | ATR 계산 기간 |
| `multiplier` | `1.5` | 돌파 기준선 ATR 배수 |
| `volumeMultiplier` | `1.5` | 거래량 필터 배수 |

### 신호 강도

```
돌파 폭% = (종가 - 기준선) / ATR × 100
strength = min(50 + 돌파 폭% / 2, 100)
```

ATR 대비 돌파 폭이 클수록 강한 신호입니다.

---

## 7. EMA_CROSS — EMA 골든/데드크로스

**추세 추종 전략 — 단기/장기 EMA 교차로 추세 전환 포착**

### 동작 원리

단기 EMA(20)와 장기 EMA(50)의 교차를 감지합니다.

```
이전: EMA(20) ≤ EMA(50) → 현재: EMA(20) > EMA(50) → BUY  (골든크로스)
이전: EMA(20) ≥ EMA(50) → 현재: EMA(20) < EMA(50) → SELL (데드크로스)
교차 없음 → HOLD
```

### ADX 최소선 필터

```
ADX(14) < 25 → HOLD (추세 약함 — Whipsaw 방지)
```

추세가 충분히 형성된 경우에만 크로스 신호를 인정합니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `shortPeriod` | `20` | 단기 EMA 기간 |
| `longPeriod` | `50` | 장기 EMA 기간 |
| `adxPeriod` | `14` | ADX 계산 기간 |
| `adxThreshold` | `25.0` | ADX 최소선 (미달 시 HOLD) |

### 신호 강도

```
EMA 갭 비율 = |EMA(20) - EMA(50)| / EMA(50) × 1000
strength = min(갭 비율, 100)
```

크로스 직후(갭 작음) 강도가 낮고, 추세가 진행될수록 강해집니다.

---

## 8. BOLLINGER — 볼린저 밴드 평균 회귀

**역추세 전략 — 볼린저 밴드 이탈 후 평균 가격으로 회귀 예상**

### 동작 원리

20기간 SMA ± 2σ로 밴드를 계산하고 %B 값으로 신호를 결정합니다.

```
중심선  = SMA(20)
상단 밴드 = SMA(20) + 2 × 표준편차
하단 밴드 = SMA(20) - 2 × 표준편차

%B = (현재가 - 하단 밴드) / (상단 밴드 - 하단 밴드)

%B < 0  → BUY  (하단 이탈 — 평균 회귀 기대)
%B > 1  → SELL (상단 이탈 — 평균 회귀 기대)
0 ≤ %B ≤ 1 → HOLD
```

### 필터 1 — ADX 상한선

```
ADX(14) ≥ 25 → HOLD (강한 추세장에서 역추세 매매 억제)
```

### 필터 2 — Squeeze 감지

```
현재 밴드 폭(Bandwidth) ≤ 최근 30캔들 밴드 폭의 최솟값 → HOLD
```

밴드가 압축(Squeeze) 중이면 브레이크아웃이 임박했을 수 있으므로 평균 회귀 신호를 억제합니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `period` | `20` | 볼린저 밴드 SMA 기간 |
| `stdDevMultiplier` | `2.0` | 표준편차 배수 |
| `adxPeriod` | `14` | ADX 계산 기간 |
| `adxThreshold` | `25.0` | ADX 상한선 (초과 시 HOLD) |
| `squeezeLookback` | `30` | Squeeze 감지 비교 기간 |

### 신호 강도

```
BUY  강도: |%B| × 100     (0 아래로 내려갈수록 강함)
SELL 강도: (%B - 1) × 100 (1 위로 올라갈수록 강함)
```

---

## 9. ORDERBOOK_IMBALANCE — 호가 불균형 압력 감지

**호가 분석 전략 — 매수/매도 주문량 불균형으로 단기 방향 예측**

### 동작 원리

매수 주문량(bid)과 매도 주문량(ask)의 비율로 시장의 단기 압력을 측정합니다.

```
불균형 비율 = 매수량 / (매수량 + 매도량)

비율 > 0.70 → BUY  (매수 압력 우세)
비율 < 0.30 → SELL (매도 압력 우세)
0.30 ~ 0.70 → HOLD
```

### 실시간 모드 vs 캔들 근사 모드

| 모드 | 사용 조건 | 설명 |
|------|---------|------|
| **실시간 모드** | `bidVolume`, `askVolume` 파라미터 제공 시 | WebSocket 실시간 호가창 데이터 사용 |
| **캔들 근사 모드** | 파라미터 없을 때 (백테스팅 등) | OHLCV 캔들로 매수/매도량 추정 |

**캔들 근사 공식 (Tick Rule)**:
```
상승비율 = (종가 - 저가) / (고가 - 저가 + ε)
추정 매수량 = 거래량 × 상승비율
추정 매도량 = 거래량 × (1 - 상승비율)
```
최근 15캔들을 누적하여 비율을 계산합니다.

### Delta 일관성 필터

```
마지막 캔들의 압력 방향 ≠ 이전 누적 방향 → 신호 강도 50% 할인
```

직전 캔들에서 갑자기 방향이 바뀐 경우 스푸핑 가능성을 고려하여 강도를 낮춥니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `buyThreshold` | `0.70` | 매수 신호 불균형 임계값 |
| `sellThreshold` | `0.30` | 매도 신호 불균형 임계값 |
| `lookback` | `15` | 캔들 근사 모드 누적 기간 |
| `bidVolume` | - | 실시간 매수 주문량 (제공 시 실시간 모드) |
| `askVolume` | - | 실시간 매도 주문량 (제공 시 실시간 모드) |

---

## 10. VOLUME_DELTA — 누적 볼륨 델타

**볼륨 분석 전략 — 매수/매도 주도 거래량 차이를 누적하여 시장 압력 방향 측정**

### 동작 원리

각 캔들의 거래량을 매수 주도/매도 주도로 분해하고,
그 차이(Delta)를 누적하여 시장 압력의 방향과 강도를 측정합니다.

**볼륨 분해 (Tick Rule 근사)**:
```
상승비율 = (종가 - 저가) / (고가 - 저가 + ε)
매수 볼륨 = 거래량 × 상승비율
매도 볼륨 = 거래량 × (1 - 상승비율)
캔들 Delta = 매수 볼륨 - 매도 볼륨
```

**누적 Delta 정규화**:
```
누적Delta비율 = sum(Delta) / sum(거래량)   (범위: -1.0 ~ +1.0)

누적Delta비율 > +signalThreshold → BUY  (매수 압력 우세)
누적Delta비율 < -signalThreshold → SELL (매도 압력 우세)
```

### 필터 1 — Delta 추세 확인

lookback 구간을 전반부/후반부로 나눠 추세 방향을 확인합니다:

```
BUY  발동: 후반부 평균 Delta > 전반부 평균 Delta (매수 압력이 강화되는 추세)
SELL 발동: 후반부 평균 Delta < 전반부 평균 Delta (매도 압력이 강화되는 추세)
미충족 시 HOLD로 격하
```

### 필터 2 — 다이버전스 감지 (`divergenceMode=true` 시)

```
가격 상승 + 누적Delta 음수 → 약세 다이버전스 → BUY 신호를 HOLD로 격하
가격 하락 + 누적Delta 양수 → 강세 다이버전스 → SELL 신호를 HOLD로 격하
```

> 다이버전스 모드는 반대 신호를 **발생시키지 않고 억제만** 합니다.

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `lookback` | `20` | 누적 Delta 계산 기간 |
| `signalThreshold` | `0.10` | 신호 발동 임계값 (±10%) |
| `divergenceMode` | `true` | 다이버전스 필터 활성화 |

### 신호 강도

```
BUY  강도: (누적Delta비율 - threshold) / (1 - threshold) × 100
SELL 강도: (-threshold - 누적Delta비율) / (1 - threshold) × 100
```

---

## 11. STOCHASTIC_RSI — 스토캐스틱 RSI 크로스

**오실레이터 전략 — RSI 값에 Stochastic 공식을 적용하여 과매도/과매수 구간 이탈 시 진입**

### 동작 원리

일반 RSI보다 민감하게 반응하는 Stochastic RSI를 계산합니다.

```
1단계: RSI(rsiPeriod) 시계열 계산  (Wilder's Smoothing)

2단계: %K = (RSI - RSI_최저) / (RSI_최고 - RSI_최저) × 100
       - RSI_최고/최저: 최근 stochPeriod개의 RSI 값 중 최고·최저
       - 분모 = 0인 경우 %K = 50 (중립)

3단계: %D = %K의 SMA(signalPeriod)   ← 시그널 선
```

### 매수 조건

```
이전 %K ≤ oversoldLevel (20)   → 과매도 구간에 있었음
AND 현재 %K > oversoldLevel     → 과매도 구간 이탈
AND 현재 %K > 현재 %D           → %K가 %D 위 (현재)
AND 이전 %K > 이전 %D           → %K가 %D 위 (이전, 2캔들 연속 확인)
AND 거래량 ≥ 최근 평균           → 거래량 확인
```

### 매도 조건

```
이전 %K ≥ overboughtLevel (80)  → 과매수 구간에 있었음
AND 현재 %K < overboughtLevel    → 과매수 구간 이탈
AND 현재 %K < 현재 %D            → %K가 %D 아래 (현재)
AND 이전 %K < 이전 %D            → %K가 %D 아래 (이전, 2캔들 연속 확인)
AND 거래량 ≥ 최근 평균           → 거래량 확인
```

> 2캔들 연속 %K/%D 위치를 확인하여 단발성 크로스로 인한 오신호를 줄입니다.

### ADX 상한선 필터

```
ADX(14) ≥ 30 → HOLD (강한 추세장 회피 — 레인지/변동성 구간에 적합)
```

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `rsiPeriod` | `14` | RSI 계산 기간 |
| `stochPeriod` | `14` | Stochastic 계산 기간 |
| `signalPeriod` | `3` | %D(Signal) SMA 기간 |
| `oversoldLevel` | `20.0` | 과매도 기준선 |
| `overboughtLevel` | `80.0` | 과매수 기준선 |
| `adxPeriod` | `14` | ADX 계산 기간 |
| `adxMaxThreshold` | `30.0` | ADX 상한선 (초과 시 HOLD) |
| `volumePeriod` | `20` | 거래량 이동평균 기간 |

### 신호 강도

```
BUY  강도: (현재 %K - oversoldLevel) / (100 - oversoldLevel) × 100
SELL 강도: (overboughtLevel - 현재 %K) / overboughtLevel × 100
```

---

## 12. GRID — 격자 기반 분할 매매 (Stateful)

**횡보장 특화 전략 — 가격 격자(Grid) 레벨에서 분할 매수/매도 반복**

> **Stateful 전략**: 진입한 그리드 레벨 상태를 유지합니다. 세션마다 새 인스턴스를 생성합니다.

### 동작 원리

최근 캔들의 가격 범위를 N등분하여 격자(Grid)를 만들고,
현재가가 격자 하단에 접근하면 매수, 상단에 접근하면 매도합니다.

```
그리드 범위 = 최근 lookback개 캔들의 최고가 ~ 최저가
그리드 간격 = 범위 / gridLevels
그리드 레벨 = [최저가, 최저가+간격, 최저가+간격×2, ... , 최고가]

현재가 위치비율 = (현재가 - 최저가) / 범위
```

### 매수 조건

```
현재가 위치비율 ≤ 0.30 (그리드 하단 30%)
AND 가장 가까운 그리드 레벨과의 거리 ≤ 0.5%
AND 해당 레벨에 아직 진입하지 않은 경우 (activeLevels에 없음)
```

### 매도 조건

```
현재가 위치비율 ≥ 0.70 (그리드 상단 30%)
AND 가장 가까운 그리드 레벨과의 거리 ≤ 0.5%
```

### 상태 관리

| 상태 | 설명 |
|------|------|
| `activeLevels` | 이미 매수 진입한 그리드 레벨 집합 (중복 매수 방지) |
| 그리드 리셋 | 그리드 범위가 1% 이상 변경되면 상태 초기화, 새 그리드 시작 |

### 파라미터

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `gridLevels` | `10` | 그리드 분할 수 |
| `lookback` | `100` | 가격 범위 계산 캔들 수 |
| `entryThreshold` | `0.005` | 그리드 레벨 근접 임계값 (0.5%) |
| `lowerZone` | `0.30` | 매수 구간 상단 위치비율 |
| `upperZone` | `0.70` | 매도 구간 하단 위치비율 |

### 신호 강도

```
BUY  강도: (1 - 위치비율) × 100   (하단에 가까울수록 강함)
SELL 강도: 위치비율 × 100          (상단에 가까울수록 강함)
```

### 그리드 매수 예시

```
가격 범위: 48,000 ~ 52,000 (범위 4,000)
그리드 간격: 400 (10등분)
그리드 레벨: [48000, 48400, 48800, 49200, ..., 52000]

현재가: 48,350
위치비율: (48350 - 48000) / 4000 = 0.0875 → 하단 30% 이내
가장 가까운 레벨: 48,400 / 거리: |48400 - 48350| / 48350 = 0.10% ≤ 0.5%
48,400 레벨 미진입 → BUY

→ strength = (1 - 0.0875) × 100 = 91.25
```

---

## 구현 파일 위치

| 전략 | 파일 |
|------|------|
| VWAP | [VwapStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/vwap/VwapStrategy.java) |
| RSI | [RsiStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java) |
| MACD | [MacdStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java) |
| SUPERTREND | [SupertrendStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/supertrend/SupertrendStrategy.java) |
| ATR_BREAKOUT | [AtrBreakoutStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java) |
| EMA_CROSS | [EmaCrossStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java) |
| BOLLINGER | [BollingerStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerStrategy.java) |
| ORDERBOOK_IMBALANCE | [OrderbookImbalanceStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/orderbook/OrderbookImbalanceStrategy.java) |
| VOLUME_DELTA | [VolumeDeltaStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java) |
| STOCHASTIC_RSI | [StochasticRsiStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/stochasticrsi/StochasticRsiStrategy.java) |
| GRID | [GridStrategy.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java) |
| 전략 레지스트리 | [StrategyRegistry.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategyRegistry.java) |
| 공통 지표 유틸 | [IndicatorUtils.java](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/IndicatorUtils.java) |
