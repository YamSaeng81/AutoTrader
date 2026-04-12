# COMPOSITE_BREAKOUT_ICHIMOKU 전략 분석

> COMPOSITE_BREAKOUT(ATR + VD + RSI + EMA) 위에 **Ichimoku 구름 필터**를 추가한 강화 버전.
> EMA 방향 필터 + ADX 횡보장 필터 + Ichimoku 구름 필터의 삼중 필터 구조.

---

## 1. 전략 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **ATR_BREAKOUT** | **0.4** | 변동성 돌파 진입 (핵심 트리거) |
| **VOLUME_DELTA** | **0.3** | 누적 볼륨 압력 방향 확인 |
| **RSI** | **0.2** | 과매수 구간 진입 차단 (브레이크) |
| **EMA_CROSS** | **0.1** | EMA 골든/데드크로스 추세 보조 확인 |
| **EMA 방향 필터** | — | 추세 역행 신호 억제 (1차 필터) |
| **ADX 횡보장 필터** | — | ADX < 20이면 즉시 HOLD (2차 필터) |
| **Ichimoku 구름 필터** | — | 구름 내부(전환 구간) 신호 억제 (3차 필터) |

### 아키텍처 — 래퍼 패턴

```
IchimokuFilteredStrategy (외부 래퍼 — 3차 필터)
  └─ CompositeStrategy (내부 기반 전략)
       ├─ ATR_BREAKOUT (0.4)
       ├─ VOLUME_DELTA (0.3)
       ├─ RSI (0.2)
       └─ EMA_CROSS (0.1)
       └─ [ADX 횡보장 필터 — 2차]
       └─ [EMA 방향 필터 — 1차]
```

> **Stateless 전략**: 구성 전략이 모두 상태를 보유하지 않으므로 세션 간 인스턴스를 공유합니다.

---

## 2. Ichimoku 구름 필터 (COMPOSITE_BREAKOUT 대비 추가된 부분)

### 파라미터

| 파라미터 | 값 | 설명 |
|---------|-----|------|
| Tenkan-sen | 9 | 전환선 (단기 고저 중간값) |
| Kijun-sen | 26 | 기준선 (중기 고저 중간값) |
| Senkou Span B | 52 | 선행 스팬 B (장기 고저 중간값) |

### 필터 규칙

| 가격 위치 | 판정 | 처리 |
|----------|------|------|
| 가격 > 구름 상단 | **구름 위** (상승 추세 확립) | 신호 통과 |
| 가격 < 구름 하단 | **구름 아래** (하락 추세 확립) | 신호 통과 |
| 구름 하단 <= 가격 <= 구름 상단 | **구름 내부** (전환 구간) | **HOLD로 억제** |

### 삼중 필터 체계

| 순서 | 필터 | 차단 대상 |
|------|------|----------|
| 1차 | ADX 횡보장 필터 | ADX < 20 → 즉시 HOLD (모든 신호 차단) |
| 2차 | EMA 방향 필터 | 추세 역행 방향의 신호 억제 |
| 3차 | Ichimoku 구름 필터 | 전환 구간(구름 내부)의 모든 신호 억제 |

---

## 3. 백테스트 결과 (2023~2025년, H1)

> COMPOSITE_BREAKOUT_ICHIMOKU 전용 백테스트 데이터가 제공되지 않았으므로,
> COMPOSITE_BREAKOUT 백테스트 결과를 기준으로 분석합니다.

### COMPOSITE_BREAKOUT 백테스트

| 코인 | 수익률 | 승률 | MDD | 완료 |
|------|--------|------|-----|------|
| **KRW-BTC** | **+256.82%** | **60.0%** | -26.41% | 2026.04.10 15:26 |
| **KRW-ETH** | **+165.87%** | 40.7% | **-49.41%** | 2026.04.10 15:48 |

---

## 4. Walk Forward 검증 (2023~2025년, H1)

### KRW-BTC

| 항목 | COMPOSITE_BREAKOUT_ICHIMOKU | COMPOSITE_BREAKOUT | 차이 |
|------|---------------------------|-------------------|------|
| **수익률** | 168.1% | 168.1% | **0.0%p (동일)** |
| **결과** | **과적합 경고** | **과적합 경고** | 동일 |
| **검증 완료** | 2026.04.10 15:23 | 2026.04.10 15:11 | — |

### KRW-ETH

| 항목 | COMPOSITE_BREAKOUT_ICHIMOKU | COMPOSITE_BREAKOUT | 차이 |
|------|---------------------------|-------------------|------|
| **수익률** | 80.2% | 80.2% | **0.0%p (동일)** |
| **결과** | **과적합 경고** | **과적합 경고** | 동일 |
| **검증 완료** | 2026.04.10 15:51 | 2026.04.10 15:47 | — |

---

## 5. 핵심 문제 분석

### 문제 1: Ichimoku 필터 무효화 — 결과 완전 동일

**BTC·ETH 모두 ICHIMOKU 버전과 기본 버전의 Walk Forward 수익률이 소수점까지 완전히 동일합니다.**

이는 Ichimoku 구름 필터가 **단 한 번도 신호를 차단하지 않았음**을 의미합니다.

#### 원인 분석

1. **ADX 횡보장 필터가 먼저 차단**
   - ADX < 20인 횡보 구간에서는 CompositeStrategy가 즉시 HOLD 반환
   - HOLD 신호는 IchimokuFilteredStrategy에서 필터 없이 그대로 통과
   - 구름 내부(전환 구간)이지만 ADX가 이미 낮아 HOLD가 먼저 나오는 상황

2. **EMA 방향 필터와 Ichimoku 필터의 중복**
   - EMA(20) < EMA(50)인 하락 추세 → EMA 필터가 BUY를 먼저 HOLD로 변환
   - 구름 내부에 진입하는 전환 구간은 대부분 EMA 크로스 직전/직후
   - EMA 필터가 이미 처리한 신호를 Ichimoku가 다시 처리할 기회 없음

3. **ATR 돌파 전략 특성상 구름 내부에서 신호 발생 희소**
   - ATR 돌파는 강한 변동성(시가 ± ATR x 1.5) 후에만 발생
   - 강한 돌파가 발생하는 시점은 대부분 가격이 이미 구름 외부(추세 확립 구간)
   - 구름 내부의 좁은 범위에서는 ATR 돌파 기준선을 넘기 어려움

#### 결론

COMPOSITE_BREAKOUT 전략 자체의 **ADX 필터 + EMA 필터 + ATR 돌파 조건**이
이미 Ichimoku 구름 필터가 차단할 신호를 사전에 걸러내고 있어,
Ichimoku 래퍼가 추가 가치를 제공하지 못하고 있습니다.

> COMPOSITE_MOMENTUM_ICHIMOKU에서는 Ichimoku 필터가 효과를 보였지만(+6.03%p),
> 이는 COMPOSITE_MOMENTUM에 ADX 횡보장 필터가 없고,
> GRID·VWAP 같은 역추세 전략이 구름 내부에서도 신호를 낼 수 있기 때문입니다.

---

### 문제 2: Walk Forward 과적합 경고 (BTC·ETH 공통)

| 코인 | 백테스트 | Walk Forward | WF/BT 비율 |
|------|---------|-------------|-----------|
| BTC | +256.82% | 168.1% | 65.4% |
| ETH | +165.87% | 80.2% | 48.3% |

**일반적으로 WF는 백테스트의 10~30% 수준이 정상**입니다.
WF/BT 비율이 48~65%로 비정상적으로 높다는 것은:

1. **파라미터가 테스트 기간 전체에 과적합** — 인샘플·아웃오브샘플 구분 없이 동일하게 작동
2. **2023~2025 BTC 강세장 편향** — 반감기 사이클의 상승 추세가 전 기간을 관통
3. **다른 시장 조건(하락장, 장기 횡보)에서의 성과 불확실**

### 문제 3: ETH MDD -49.41% 과다

ETH 백테스트 MDD -49.41%는 자산의 절반 가까이 손실하는 구간이 있었음을 의미.
ATR 돌파 전략이 ETH의 빈번한 가짜 돌파(whipsaw)에 취약한 것으로 보입니다.

---

## 6. 종합 평가

| 평가 항목 | 등급 | 비고 |
|----------|------|------|
| 수익성 (백테스트) | **A** | BTC +256.82%, ETH +165.87% |
| Ichimoku 필터 효과 | **F (무효)** | BTC·ETH 모두 결과 변화 없음 |
| 과적합 위험 | **높음** | BTC·ETH 모두 과적합 경고 |
| ETH 안정성 | **D** | MDD -49.41% 과다 |
| BTC 안정성 | **B** | MDD -26.41%, 승률 60% |
| 실전 신뢰도 | **낮음** | 과적합 경고 + Ichimoku 무효 |

### COMPOSITE_MOMENTUM 계열과의 비교

| 항목 | BREAKOUT 계열 | MOMENTUM 계열 |
|------|-------------|--------------|
| BT 수익률 (BTC) | +256.82% | +115~121% |
| Walk Forward | **과적합 경고** | **통과** (7.4~9.3%) |
| Ichimoku 효과 | **무효** (0.0%p) | **유효** (+6.03%p) |
| MDD | -26~49% | -25.62% |
| 실전 신뢰도 | 낮음 | **높음** |

---

## 7. 권장 사항

### 즉시 조치

1. **COMPOSITE_BREAKOUT_ICHIMOKU 폐기 또는 통합 권장**
   - Ichimoku 필터가 완전 무효 → 기본 COMPOSITE_BREAKOUT과 동일한 전략
   - 불필요한 복잡성만 추가하므로 전략 목록에서 제거하거나,
   - Ichimoku 필터가 실제로 작동하도록 필터 적용 순서를 변경해야 함

2. **실전 배포 보류** — 과적합 경고 해소 전까지 페이퍼 트레이딩으로 검증

### 과적합 해소를 위한 검토 사항

3. **파라미터 민감도 분석** — ATR multiplier, 거래량 필터 배수, ADX 임계값 변동에 따른 성과 변화 확인
4. **테스트 기간 확장** — 2021~2022 하락장 포함하여 다양한 시장 조건 검증
5. **코인별 파라미터 분리** — BTC(승률 60%, MDD -26%) vs ETH(승률 40%, MDD -49%) 성과 격차가 크므로 코인별 최적화 검토

### Ichimoku 필터 개선 방안 (선택적)

6. Ichimoku 필터를 CompositeStrategy **내부**에 적용하여 ADX/EMA 필터보다 **먼저** 평가
7. 또는 Ichimoku를 하위 전략(가중치 부여)으로 편입하여 투표에 참여시키는 방식 검토

---

## 구현 파일 위치

| 항목 | 파일 |
|------|------|
| 전략 등록 | [CompositePresetRegistrar.java](../../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) |
| Ichimoku 래퍼 | [IchimokuFilteredStrategy.java](../../core-engine/src/main/java/com/cryptoautotrader/core/selector/IchimokuFilteredStrategy.java) |
| 가중 투표 엔진 + EMA/ADX 필터 | [CompositeStrategy.java](../../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
| ATR_BREAKOUT 전략 | [AtrBreakoutStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java) |
| VOLUME_DELTA 전략 | [VolumeDeltaStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java) |
| RSI 전략 | [RsiStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java) |
| EMA_CROSS 전략 | [EmaCrossStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java) |
| 상세 전략 가이드 | [COMPOSITE_BREAKOUT.md](../COMPOSITE_BREAKOUT.md) |
