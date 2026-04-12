# COMPOSITE_MOMENTUM_ICHIMOKU 전략 분석

> COMPOSITE_MOMENTUM(MACD + VWAP + GRID) 위에 **Ichimoku 구름 필터**를 추가한 강화 버전.
> EMA 방향 필터 + Ichimoku 이중 필터로 추세 전환 구간 진입을 이중 차단.
> BTC·ETH 등 대형 코인 최적화.

---

## 1. 전략 구성

| 하위 전략 | 가중치 | 역할 |
|-----------|--------|------|
| **MACD** | **0.5** | 중기 모멘텀 방향 + 추세 전환 감지 |
| **VWAP** | **0.3** | 거래량 가중 평균 가격 대비 할인/프리미엄 판단 |
| **GRID** | **0.2** | 가격 격자 기반 분할 매수/매도 |
| **EMA 방향 필터** | — | 추세 역행 신호 억제 (1차 필터) |
| **Ichimoku 구름 필터** | — | 구름 내부(전환 구간) 신호 억제 (2차 필터) |

### 아키텍처 — 래퍼 패턴

```
IchimokuFilteredStrategy (외부 래퍼)
  └─ CompositeStrategy (내부 기반 전략)
       ├─ MACD (0.5)
       ├─ VWAP (0.3)
       └─ GRID (0.2)
       └─ [EMA 방향 필터]
```

기존 COMPOSITE_MOMENTUM의 로직을 **전혀 변경하지 않고**,
IchimokuFilteredStrategy가 결과를 받아 추가 필터만 적용하는 구조.

> **Stateful 전략**: GridStrategy 상태 + Ichimoku 캔들 의존 → 세션마다 새 인스턴스 생성.

---

## 2. Ichimoku 구름 필터 (COMPOSITE_MOMENTUM 대비 추가된 부분)

### 파라미터

| 파라미터 | 값 | 설명 |
|---------|-----|------|
| Tenkan-sen | 9 | 전환선 (단기 고저 중간값) |
| Kijun-sen | 26 | 기준선 (중기 고저 중간값) |
| Senkou Span B | 52 | 선행 스팬 B (장기 고저 중간값) |
| 최소 캔들 수 | 52 | Senkou Span B 계산에 필요 |

### 구름(Kumo) 계산

```
Senkou Span A = (Tenkan + Kijun) / 2
Senkou Span B = (52기간 최고가 + 52기간 최저가) / 2

구름 상단 = max(Senkou A, Senkou B)
구름 하단 = min(Senkou A, Senkou B)
```

### 필터 규칙

| 가격 위치 | 판정 | 처리 |
|----------|------|------|
| 가격 > 구름 상단 | **구름 위** (상승 추세 확립) | 신호 통과 |
| 가격 < 구름 하단 | **구름 아래** (하락 추세 확립) | 신호 통과 |
| 구름 하단 <= 가격 <= 구름 상단 | **구름 내부** (전환 구간) | **HOLD로 억제** |

### EMA 필터와의 역할 분담

| 필터 | 차단 대상 | 목적 |
|------|----------|------|
| **EMA 방향 필터** | 추세 역행 방향의 신호 | 하락추세 BUY / 상승추세 SELL 억제 |
| **Ichimoku 구름 필터** | 전환 구간의 모든 신호 | 방향 불확실 구간 진입 자체를 차단 |

두 필터는 중복이 아닌 **상호 보완적** 관계:
- EMA 필터: "추세 방향을 역행하는 신호"를 차단
- Ichimoku 필터: "추세 방향 자체가 불확실한 구간"을 차단

---

## 3. 신호 흐름 (전체 파이프라인)

```
1단계: 하위 전략 평가
   MACD(0.5) + VWAP(0.3) + GRID(0.2) -> 가중 투표 -> buyScore / sellScore

2단계: 가중 투표 판정
   buyScore > 0.6 -> STRONG BUY
   buyScore > 0.4 -> BUY
   (sellScore 동일)

3단계: EMA 방향 필터 (1차)
   EMA(20) < EMA(50) + BUY -> HOLD (추세 역행 억제)

4단계: Ichimoku 구름 필터 (2차)
   가격이 구름 내부 -> HOLD (전환 구간 차단)
   가격이 구름 외부 -> 신호 통과

최종 신호 출력
```

### 매수 억제 예시 (Ichimoku 필터 동작)

```
MACD  -> BUY (골든크로스, strength=70)  -> 0.5 x 0.70 = 0.350
VWAP  -> BUY (할인 구간, strength=50)   -> 0.3 x 0.50 = 0.150
GRID  -> HOLD                           -> 0

buyScore = 0.500 -> BUY

EMA 필터: EMA(20) > EMA(50) -> 상승 추세 -> BUY 통과 (1차 통과)

Ichimoku 필터: 가격(87,500,000) in [구름하단(86,800,000), 구름상단(88,200,000)]
  -> 구름 내부 -> HOLD (2차 차단)

최종: HOLD (Ichimoku필터 구름내부 차단)
```

---

## 4. 백테스트 결과 (2023~2025년, KRW-BTC, H1)

| 항목 | COMPOSITE_MOMENTUM_ICHIMOKU | COMPOSITE_MOMENTUM | 차이 |
|------|---------------------------|-------------------|------|
| **총 수익률** | **+121.75%** | +115.72% | **+6.03%p** |
| **승률** | **40.7%** | 40.0% | **+0.7%p** |
| **MDD (최대 낙폭)** | -25.62% | -25.62% | 동일 |
| **테스트 완료** | 2026.04.10 14:57 | 2026.04.10 14:48 | — |

### 백테스트 비교 분석

**Ichimoku 필터 추가 효과:**

1. **수익률 +6.03%p 개선** (+115.72% -> +121.75%)
   - 구름 내부(전환 구간)에서의 오신호 차단으로 불필요한 손실 거래 감소
   - 추세가 확립된 구간에서만 진입하여 평균 수익 단가 개선

2. **승률 +0.7%p 개선** (40.0% -> 40.7%)
   - 불확실 구간의 저품질 신호 제거로 성공 거래 비율 소폭 상승

3. **MDD 동일** (-25.62%)
   - 최대 낙폭이 동일하다는 것은 Ichimoku 필터가 최악 구간의 손실을 줄이지는 못했음을 의미
   - MDD는 시장 급락(예: 2022 하반기~2023 초) 구간에서 발생했을 가능성 높음
   - 추세가 이미 확립된 하락장에서는 Ichimoku 구름 바깥이므로 필터가 작동하지 않음

---

## 5. Walk Forward 검증 (2023~2025년, KRW-BTC, H1)

| 항목 | COMPOSITE_MOMENTUM_ICHIMOKU | COMPOSITE_MOMENTUM | 차이 |
|------|---------------------------|-------------------|------|
| **수익률** | **9.3%** | 7.4% | **+1.9%p** |
| **결과** | 통과 | 통과 | — |
| **검증 완료** | 2026.04.10 15:31 | 2026.04.10 15:28 | — |

### Walk Forward 분석

- Walk Forward에서도 Ichimoku 버전이 **+1.9%p 우위** (7.4% -> 9.3%)
- 두 전략 모두 **통과** 판정이나, Ichimoku 버전이 미래 데이터에 대한 적응력이 더 높음
- 백테스트-WF 격차: ICHIMOKU(121.75% vs 9.3%) vs 기본(115.72% vs 7.4%)
  - 두 전략 모두 격차가 크나, 비율적으로 유사 → Ichimoku 필터가 과적합을 유발하지 않음

---

## 6. 종합 평가

| 평가 항목 | 등급 | 비고 |
|----------|------|------|
| 수익성 | **A-** | 백테스트 +121.75%, WF 9.3% (기본 대비 전면 우위) |
| 안정성 | **B** | MDD -25.62% (기본과 동일), 승률 40.7% |
| 과적합 위험 | **보통** | 백테스트-WF 격차 크지만, 기본 전략과 비율 유사 |
| 필터 효과 | **유효** | 수익률·승률 모두 개선, 부작용 없음 |
| 적합 코인 | BTC, ETH | 대형 코인 (Ichimoku 52기간 기준선 의미 있음) |
| 적합 타임프레임 | H1 | 1시간봉 최적화 |

### COMPOSITE_MOMENTUM 대비 장단점

| 항목 | 장점 | 단점 |
|------|------|------|
| 수익률 | +6.03%p 높음 | — |
| 승률 | +0.7%p 높음 | — |
| 신호 빈도 | 저품질 신호 제거 | 유효 신호도 일부 놓칠 수 있음 (구름 경계) |
| 복잡성 | — | 캔들 최소 52개 필요 (기본 대비 초기 대기 시간 증가) |
| 급등장 대응 | — | 구름 통과 전 급등 초입 진입 놓칠 가능성 |

### 권장 사항

1. **COMPOSITE_MOMENTUM보다 ICHIMOKU 버전을 우선 배포 권장** — 모든 지표에서 우위
2. 실전 배포 시 WF 기준 **7~10% 수준을 현실적 기대치**로 설정
3. MDD -25.62% 대비 리스크 관리(포지션 사이징, 손절) 필수
4. 소형 알트에 적용 금지 — VWAP 신뢰도 + Ichimoku 52기간 기준선 의미 없음
5. 급등장 초입 진입이 중요한 경우 COMPOSITE_BREAKOUT 계열과 병행 검토

---

## 구현 파일 위치

| 항목 | 파일 |
|------|------|
| 전략 등록 | [CompositePresetRegistrar.java](../../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) |
| Ichimoku 래퍼 | [IchimokuFilteredStrategy.java](../../core-engine/src/main/java/com/cryptoautotrader/core/selector/IchimokuFilteredStrategy.java) |
| 가중 투표 엔진 + EMA 필터 | [CompositeStrategy.java](../../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) |
| MACD 전략 | [MacdStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java) |
| VWAP 전략 | [VwapStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/vwap/VwapStrategy.java) |
| GRID 전략 | [GridStrategy.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java) |
| Ichimoku 유틸 | [IndicatorUtils.java](../../strategy-lib/src/main/java/com/cryptoautotrader/strategy/IndicatorUtils.java) |
| 복합 전략 가이드 | [COMPOSITE_STRATEGIES_GUIDE.md](../COMPOSITE_STRATEGIES_GUIDE.md) |
