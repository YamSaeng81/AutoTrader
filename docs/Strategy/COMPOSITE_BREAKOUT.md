# COMPOSITE_BREAKOUT 전략 분석

> ATR 변동성 돌파 + Volume Delta 볼륨 압력 + RSI 브레이크 + EMA 추세 확인으로 구성된
> **추세장·변동성 장세 특화 전략**. ADX 횡보장 차단 + EMA 방향 필터 활성화.

---

## 1. 전략 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **ATR_BREAKOUT** | **0.4** | 변동성 돌파 진입 (핵심 트리거) |
| **VOLUME_DELTA** | **0.3** | 누적 볼륨 압력 방향 확인 |
| **RSI** | **0.2** | 과매수 구간 진입 차단 (브레이크) |
| **EMA_CROSS** | **0.1** | EMA 골든/데드크로스 추세 보조 확인 |
| **EMA 방향 필터** | — | 추세 역행 신호 억제 (활성화) |
| **ADX 횡보장 필터** | — | ADX < 20이면 즉시 HOLD (활성화) |

### 설계 원칙

```
ADX 횡보장 필터 = ADX < 20이면 하위 전략 평가 없이 즉시 차단 (최우선)
ATR 돌파       = 진입 트리거
Volume Delta   = 볼륨 압력으로 신호 신뢰도 보강
RSI            = 과매수 구간 진입 차단 (브레이크)
EMA Cross      = 중기 추세 방향 보조 확인
EMA 방향 필터   = 하락 추세에서 BUY / 상승 추세에서 SELL 억제
```

> **Stateless 전략**: 구성 전략이 모두 상태를 보유하지 않으므로 세션 간 인스턴스를 공유합니다.

---

## 2. 하위 전략 상세

### 2-1. ATR_BREAKOUT (가중치 0.4 — 핵심 트리거)

래리 윌리엄스 변동성 돌파 전략을 ATR 기반으로 구현.

```
매수 기준선 = 현재 캔들 시가 + ATR(14) x 1.5
매도 기준선 = 현재 캔들 시가 - ATR(14) x 1.5
```

- **BUY**: 종가 > 매수 기준선 AND 거래량 > 평균 x 1.5
- **SELL**: 종가 < 매도 기준선 AND 거래량 > 평균 x 1.5
- 거래량 필터로 저유동성 가짜 돌파 차단

### 2-2. VOLUME_DELTA (가중치 0.3 — 신호 신뢰도 보강)

캔들 OHLCV에서 Tick Rule로 매수/매도 볼륨을 분해하고 20캔들 누적 Delta를 계산.

```
누적Delta비율 = sum(Delta) / sum(volume)

> +0.10 -> BUY  (매수 압력 우세)
< -0.10 -> SELL (매도 압력 우세)
```

- Delta 추세 확인 필터: 후반부 평균 Delta > 전반부 -> 압력 강화 추세만 인정
- 다이버전스 필터: 가격-Delta 방향 불일치 시 억제

### 2-3. RSI (가중치 0.2 — 브레이크 역할)

RSI는 이 전략에서 **진입을 막는 역할**이 주목적.

```
RSI(14) < 30 -> BUY  (과매도)
RSI(14) > 70 -> SELL (과매수)
```

- ATR BUY + RSI > 70 -> 상충 -> 과매수 구간 돌파 진입 차단
- RSI 단독 BUY (< 30) -> buyScore = 0.2 -> 0.4 미달 -> 단독 진입 불가
- 피봇 기반 다이버전스 감지 기능 포함

### 2-4. EMA_CROSS (가중치 0.1 — 추세 보조)

EMA(20) / EMA(50) 교차로 추세 전환 감지.

- 골든크로스 -> BUY / 데드크로스 -> SELL
- ADX(14) < 25이면 HOLD (Whipsaw 방지)
- 가중치 0.1로 단독 신호 결정 불가, 보조 역할

---

## 3. 이중 필터 시스템

### ADX 횡보장 필터 (최우선)

```
ADX(14) < 20 -> 하위 전략 평가 없이 즉시 HOLD
```

횡보장에서 가짜 돌파 신호를 원천 차단.

### EMA 방향 필터

```
EMA(20) > EMA(50) -> 상승 추세 -> SELL 억제
EMA(20) < EMA(50) -> 하락 추세 -> BUY 억제
```

---

## 4. 백테스트 결과 (2023~2025년, H1)

### KRW-BTC

| 항목 | 수치 |
|------|------|
| **총 수익률** | **+256.82%** |
| **승률** | **60.0%** |
| **MDD (최대 낙폭)** | -26.41% |
| **테스트 완료** | 2026.04.10 15:26 |

### KRW-ETH

| 항목 | 수치 |
|------|------|
| **총 수익률** | **+165.87%** |
| **승률** | 40.7% |
| **MDD (최대 낙폭)** | **-49.41%** |
| **테스트 완료** | 2026.04.10 15:48 |

### 백테스트 분석

**BTC 성과 (우수)**
- 3년간 약 3.57배 수익 (+256.82%)으로 매우 높은 수익률
- 승률 60%로 과반 이상 성공 — 돌파 전략이 BTC의 추세 특성에 잘 맞음
- MDD -26.41%는 BTC 시장 특성상 수용 가능한 수준

**ETH 성과 (주의 필요)**
- 수익률 +165.87%로 양호하나 BTC 대비 약 91%p 낮음
- 승률 40.7%로 BTC(60%)보다 크게 낮음
- **MDD -49.41%가 심각** — 자산의 절반 가까이 잃는 구간 존재
- ETH는 BTC 대비 변동성이 크고 추세 전환이 빈번하여, ATR 돌파 후 되돌림(가짜 돌파)에 더 취약

---

## 5. Walk Forward 검증 (2023~2025년, H1)

### KRW-BTC

| 항목 | 수치 |
|------|------|
| **수익률** | **168.1%** |
| **결과** | **과적합 경고** |
| **검증 완료** | 2026.04.10 15:11 |

### KRW-ETH

| 항목 | 수치 |
|------|------|
| **수익률** | **80.2%** |
| **결과** | **과적합 경고** |
| **검증 완료** | 2026.04.10 15:47 |

### Walk Forward 분석 — 과적합 경고 문제

**BTC Walk Forward 168.1%**: 일반적인 Walk Forward 통과 수준(양수~10% 내외)을 크게 초과하는 비정상적으로 높은 수치. 과적합 경고가 발생한 이유로 추정되는 원인:

1. **인샘플-아웃오브샘플 수익률 비율 이상**
   - 백테스트(인샘플) +256.82% vs Walk Forward(아웃오브샘플) 168.1%
   - WF 수익률이 백테스트의 65%로 비정상적으로 높음
   - 일반적으로 WF는 백테스트의 10~30% 수준이 정상

2. **과적합 가능성**
   - ATR multiplier(1.5), 거래량 필터(1.5배), ADX 임계값(20) 등의 파라미터가 2023~2025 BTC 시장 특성에 과도하게 맞춰져 있을 가능성
   - BTC의 2023~2025 상승 추세(비트코인 반감기 사이클)에 편승한 결과일 수 있음

3. **ETH도 동일 패턴**
   - ETH WF 80.2%도 비정상적으로 높음 (백테스트 165.87%의 48%)
   - 두 코인 모두 과적합 경고 → 전략 자체의 구조적 문제일 가능성

**과적합 경고의 의미**
- 전략이 "미래에도 통한다"가 아니라 "과거 데이터에 과도하게 최적화되었을 수 있다"는 경고
- 실전 배포 시 백테스트·WF 수준의 수익률을 기대하면 안 됨
- 파라미터 튜닝이 필요하거나, 더 긴 기간/다양한 시장 조건에서 재검증 필요

---

## 6. 종합 평가

| 평가 항목 | BTC | ETH |
|----------|-----|-----|
| 수익성 | **A** (백테스트 최고) | **B+** |
| 승률 | **A** (60%) | **C+** (40.7%) |
| 안정성 (MDD) | **B** (-26.41%) | **D** (-49.41%) |
| 과적합 위험 | **높음** (WF 과적합 경고) | **높음** (WF 과적합 경고) |
| Walk Forward | **과적합 경고** | **과적합 경고** |

### 핵심 문제점

1. **BTC·ETH 모두 Walk Forward 과적합 경고** — 실전 배포 전 파라미터 재검증 필수
2. **ETH MDD -49.41%** — 리스크 관리 없이 운용 시 자산 절반 손실 가능
3. **BTC·ETH 성과 격차 큼** — 코인별 파라미터 분리 튜닝 필요 가능성

### 권장 사항

1. **실전 배포 보류** — 과적합 경고 해소 전까지 페이퍼 트레이딩으로 검증
2. ETH 운용 시 **포지션 사이징 축소 + 손절 강화** (MDD -49.41% 대응)
3. ATR multiplier, 거래량 필터 배수, ADX 임계값 등 **파라미터 민감도 분석** 수행 권장
4. 2023~2025 외 기간(2021~2022 하락장 포함)으로 **추가 백테스트** 권장
5. COMPOSITE_MOMENTUM / COMPOSITE_MOMENTUM_ICHIMOKU와 비교 시:
   - BREAKOUT이 백테스트 수익률은 압도적이나, 과적합 경고로 신뢰도 낮음
   - MOMENTUM 계열이 WF 통과로 실전 안정성 높음

---

## 구현 파일 위치

| 항목 | 파일 |
|------|------|
| 전략 등록 | [CompositePresetRegistrar.java](../../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) |
| 가중 투표 엔진 + EMA/ADX 필터 | [CompositeStrategy.java](../../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
| ATR_BREAKOUT 전략 | [AtrBreakoutStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java) |
| VOLUME_DELTA 전략 | [VolumeDeltaStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java) |
| RSI 전략 | [RsiStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java) |
| EMA_CROSS 전략 | [EmaCrossStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java) |
| 상세 전략 가이드 | [COMPOSITE_BREAKOUT.md](../COMPOSITE_BREAKOUT.md) |
