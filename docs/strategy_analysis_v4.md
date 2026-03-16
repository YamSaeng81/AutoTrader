# 📊 암호화폐 자동매매 전략 분석 & 개선 로드맵 v4

> **통합 분석**: v3 개선 로드맵 + 2025년 백테스트 실증 결과 반영
> **작성일**: 2026-03-16
> **이전 버전**: strategy_analysis_v3.md
> **핵심 변화**: 이론에서 데이터로 — 백테스트로 검증된 전략만 신뢰

---

## 📋 v4 핵심 변경사항 요약

v3 대비 가장 큰 변화는 **실증 데이터 기반 전략 재평가**다.

| 항목 | v3 (이론 기반) | v4 (백테스트 반영) |
|------|--------------|-----------------|
| 전략 우선순위 | 지표 이론 기반 ⭐ 평점 | 2025년 KRW-BTC/ETH 실적 기반 |
| STOCHASTIC_RSI | ⭐⭐⭐ 활용 권장 | ❌ **폐기 검토** (BTC -70%, ETH -68%) |
| MACD | ⭐⭐ 추세 보조 | ❌ **구조 재설계 필요** (BTC -59%, ETH -58%) |
| GRID | ⭐⭐ 범위 매매 | ✅ **핵심 전략 승격** (BTC+ETH 양코인 안정) |
| ORDERBOOK | ⭐⭐⭐ 단기 스캘핑 | ✅ **핵심 전략 승격** (BTC+ETH 양코인 플러스) |
| 전략 배분 | 시장 Regime 기반 | Regime + **코인별 특성** 이중 필터 |

---

## 🔬 백테스트 인사이트 (2025년 KRW-BTC/ETH, H1)

### 2025년 시장 특성 맥락

> 2025년은 하락/횡보 구간이 지배적이었던 해로, 추세 추종 전략이 불리했다.
> 이 결과를 전천후 진리로 해석하면 안 되며, **시장 Regime과의 정합성**으로 해석해야 한다.

### 전략 성과 매트릭스

| 전략 | KRW-BTC | KRW-ETH | 일관성 | 결론 |
|------|---------|---------|--------|------|
| **GRID** | ✅ +8.42% | ✅ +1.38% | ✅ 안정 | 핵심 유지 |
| **ORDERBOOK_IMBALANCE** | ✅ +0.79% | ✅ +30.55% | ✅ 안정 | 핵심 유지 |
| **BOLLINGER** | ✅ +3.16% | ❌ -36.97% | 🔶 BTC 전용 | 코인 조건부 |
| **ATR_BREAKOUT** | ❌ -29.75% | ✅ +39.01% | 🔶 ETH 전용 | 코인 조건부 |
| **EMA_CROSS** | ❌ -51.15% | ✅ +23.73% | 🔶 ETH 전용 | 코인 조건부 |
| **RSI** | ❌ -8.57% | ❌ -30.97% | ❌ 손실 | 파라미터 재조정 |
| **SUPERTREND** | ❌ -39.45% | ❌ -7.57% | ❌ 손실 | 횡보장 비활성 필요 |
| **VWAP** | ❌ 거래없음 | ❌ -27.05% | ❌ | 임계값 재조정 |
| **MACD** | ❌ -58.81% | ❌ -57.63% | ❌❌ | 구조 재설계 |
| **STOCHASTIC_RSI** | ❌ -70.36% | ❌ -67.60% | ❌❌ | **폐기 검토** |

### 핵심 발견

1. **코인-전략 불일치**: 동일 전략이 BTC/ETH에서 완전히 반대 결과
   → Market Regime 외에 **코인별 특성 레이어** 필요

2. **횡보장 생존자**: GRID, BOLLINGER(BTC), ORDERBOOK만 양전
   → 2025년 하락/횡보장 = Regime 판단 없는 추세 전략은 모두 손실

3. **STOCHASTIC_RSI 구조적 결함**: ADX 필터 추가 후에도 -70%
   → 파라미터 문제가 아닌 **전략 알고리즘 자체의 문제**

4. **VWAP 임계값 과도**: BTC 승률 0% (거래 미발생)
   → 2.5% 임계값이 지나치게 높아 신호 자체가 없음

---

## 🔴 거시적 구조 문제 (v3 계승 + 업데이트)

### 1. Market Regime 판단 없음 — v3와 동일, 백테스트로 확인됨

2025년 백테스트가 이를 실증했다:

```
2025년 KRW-BTC (하락/횡보 지배):
  → SUPERTREND, EMA_CROSS, MACD 모두 큰 손실
  → GRID, BOLLINGER만 생존

2025년 KRW-ETH (횡보 + 단기 급등 패턴):
  → ATR_BREAKOUT, EMA_CROSS가 오히려 수익
  → 같은 횡보장이어도 변동 패턴이 다름
```

**결론**: Regime 판단 없이 전략을 실행하면 시장 상황에 따라 결과가 완전히 갈린다.

---

### 2. 코인별 특성 레이어 — v4 신규 추가

v3에서 인식하지 못했던 구조적 문제:

```
BTC와 ETH는 같은 하락장이어도 패턴이 다르다:

BTC 2025:
  - 평균회귀 패턴 강함 (GRID +8.4%, BOLLINGER +3.2%)
  - 급등 변동성 낮음 → ATR Breakout 손실

ETH 2025:
  - 단기 급등/급락 패턴 강함 (ATR_BREAKOUT +39%, EMA_CROSS +24%)
  - 횡보 중 급등 구간 → 모멘텀 전략 유리
```

**권장 아키텍처 추가 레이어:**

```
시장 데이터
    ↓
[0] COIN PROFILE FILTER (신규)
    ├─ BTC: 평균회귀 전략 가중치 ↑ (GRID, BOLLINGER)
    ├─ ETH: 모멘텀 전략 가중치 ↑ (ATR_BREAKOUT, EMA)
    └─ 기타 알트: 변동성 높음 → ORDERBOOK 가중치 ↑
    ↓
[1] MARKET REGIME DETECTOR (기존)
    ...
```

---

### 3. 리스크 관리 부재 — v3와 동일

백테스트 MDD가 이를 실증:

```
STOCHASTIC_RSI: MDD -71.34% (BTC), -71.00% (ETH)
MACD:           MDD -58.81% (BTC), -60.69% (ETH)
SUPERTREND:     MDD -45.36% (BTC), -51.80% (ETH)
```

**일일 최대 손실 5% 제한만 있었어도 위 MDD는 불가능했다.**

---

## 📋 전략 목록 & 재분류 (v4 업데이트)

| # | 전략명 | 유형 | 2025 BTC | 2025 ETH | 우선순위 (v4) | 상태 |
|---|--------|------|----------|----------|--------------|------|
| 1 | **GRID** | 범위 매매 | ✅ +8.4% | ✅ +1.4% | ⭐⭐⭐⭐⭐ | **핵심** |
| 2 | **ORDERBOOK_IMBALANCE** | 호가 분석 | ✅ +0.8% | ✅ +30.6% | ⭐⭐⭐⭐⭐ | **핵심** |
| 3 | **BOLLINGER** | 평균회귀 | ✅ +3.2% | ❌ -37% | ⭐⭐⭐⭐ | BTC 전용 |
| 4 | **ATR_BREAKOUT** | 변동성 돌파 | ❌ -29.8% | ✅ +39% | ⭐⭐⭐⭐ | ETH 전용 |
| 5 | **EMA_CROSS** | 추세 추종 | ❌ -51.2% | ✅ +23.7% | ⭐⭐⭐ | ETH + TREND만 |
| 6 | **RSI** | 모멘텀 타이밍 | ❌ -8.6% | ❌ -31% | ⭐⭐⭐ | 보조 필터 |
| 7 | **SUPERTREND** | 추세 추종 | ❌ -39.5% | ❌ -7.6% | ⭐⭐ | TREND Regime만 |
| 8 | **VWAP** | 평균회귀 | ❌ 거래없음 | ❌ -27% | ⭐⭐ | 임계값 재조정 후 |
| 9 | **MACD** | 추세/모멘텀 | ❌ -58.8% | ❌ -57.6% | ⭐ | 구조 재설계 필요 |
| 10 | **STOCHASTIC_RSI** | 모멘텀 | ❌ -70.4% | ❌ -67.6% | ❌ | **폐기 검토** |

---

## 🔍 개별 전략 상세 분석 (v4: 백테스트 실적 반영)

---

### 1. VWAP

**파일**: VwapStrategy.java

**2025 백테스트**: BTC 거래 미발생(승률 0%), ETH -27.05%

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **임계값 2.5% 과도** | BTC 백테스트에서 거래 자체가 발생하지 않음. 원래 1.0% → 2.5%로 높인 것이 과도했음 |
| 🟡 중간 | **VWAP 리셋 미구현** | UTC 00:00 기준 일별 리셋 필요 |
| 🟡 중간 | **임계값 고정** | 변동성 대비 동적 임계값 필요 |

#### 개선 방향

1. **임계값 1.5% 테스트**: 2.5% → 1.5% 재조정으로 거래 발생 확인
2. **ATR 비례 임계값**: 고정 % 대신 `ATR / currentPrice × 100 × factor`
3. **볼륨 0 시 HOLD 처리**: 안전장치 추가

---

### 2. EMA Cross

**파일**: EmaCrossStrategy.java

**2025 백테스트**: BTC -51.15%, ETH +23.73% → **코인별 완전히 다른 결과**

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **코인-전략 불일치** | BTC에서 -51%인데 ETH에서 +24%. Coin Profile 없이는 사용 불가 |
| 🔴 높음 | **횡보장 Whipsaw** | 2025 BTC 횡보장에서 빈번한 거짓 신호로 누적 손실 |
| 🟡 중간 | **ADX 필터 미구현** | 추세 강도 확인 없이 크로스 신호 발생 |

#### v4 적용 조건 (좁혀진 사용 범위)

```
EMA_CROSS 활성화 조건 (AND):
  1. Coin = ETH (또는 ETH 계열 알트)
  2. Regime = TREND (ADX > 25)
  3. ADX 필터: ADX > 25 유지 N캔들

→ BTC에서는 기본 비활성화
```

---

### 3. Bollinger Bands

**파일**: BollingerStrategy.java

**2025 백테스트**: BTC +3.16%(73.91% 승률), ETH -36.97%

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **ETH에서 역전** | BTC에서 통하는 평균회귀가 ETH에서는 손실. Coin Profile 적용 필요 |
| 🟡 중간 | **트렌드장 역매매 위험** | ADX 필터로 완화됐으나 미완전 |
| 🟡 중간 | **밴드 스퀴즈 미감지** | 스퀴즈 후 브레이크아웃 오인 가능 |

#### v4 적용 조건

```
BOLLINGER 활성화 조건:
  1. Coin = BTC (우선)
  2. Regime = RANGE (ADX < 20)
  3. Squeeze 감지 시 방향성 전환 대기

→ ETH에서는 조건부 (RANGE + 충분한 확인 캔들)
```

---

### 4. Grid

**파일**: GridStrategy.java

**2025 백테스트**: BTC +8.42%(57.14%), ETH +1.38%(75.00%) → **양코인 유일한 안정적 수익**

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **상태 미보존** | 그리드 레벨별 진입 이력 미추적 → 중복 매매 발생 (여전히 미수정) |
| 🔴 높음 | **가격 범위 이탈 대응 없음** | Lookback 고점/저점 돌파 시 그리드 무효화 |
| 🟡 중간 | **추세장 손실 위험** | ADX > 25 시 비활성화 필요 (v3에서 이미 지적) |

> **v4 추가**: 백테스트에서도 확인됨 — ETH 승률 75%인데도 수익은 +1.4%에 불과. 상태 미보존으로 인한 중복 진입이 실질 수익을 깎고 있을 가능성 높음. **Grid 상태 추적 구현이 Phase 1 최우선 과제.**

---

### 5. RSI

**파일**: RsiStrategy.java

**2025 백테스트**: BTC -8.57%(41.03%), ETH -30.97%(58.33%)

> ETH는 승률 58%인데 수익은 -31% → **페이오프 비율 문제** (손실이 이익보다 큼)

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **페이오프 비율 낮음** | 승리 시 작게 이기고, 패배 시 크게 지는 구조 |
| 🟡 중간 | **강한 추세 역매매** | ETH 하락 추세에서 RSI<30 → 매수 → 추가 하락 반복 |
| 🟡 중간 | **다이버전스 감지 단순화** | 피봇 기반 다이버전스로 정밀도 향상 필요 |

#### v4 대응

```
RSI 단독 전략 비활성화.
보조 지표로 역할 재정의:
  - Weighted Voting의 타이밍 필터로만 사용
  - CompositeStrategy 내에서 0.2 가중치 부여
  - 단독 신호 생성 금지
```

---

### 6. MACD

**파일**: MacdStrategy.java

**2025 백테스트**: BTC -58.81%, ETH -57.63% → **ADX 필터 추가 후에도 동일 수준 손실**

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **ADX 필터 효과 미미** | ADX > 25 조건 추가 후에도 성과 개선 없음. 구조적 문제 |
| 🔴 높음 | **히스토그램 기울기 미감지** | 크로스 시점이 이미 늦은 시점일 수 있음 |
| 🟡 중간 | **제로라인 크로스 미활용** | MACD가 0선 상향을 추세 확인 신호로 추가 필요 |

#### v4 전략

```
Phase 1: MACD 단독 전략 비활성화 (손실이 너무 큼)
Phase 3: 히스토그램 기울기 + 제로라인 크로스 구현 후 재평가

구조 재설계:
  - 크로스 신호 → 히스토그램 방향 전환으로 대체
  - 제로라인 크로스를 추세 필터로 활용
  - MACD 다이버전스만 선택적 유지
```

---

### 7. Supertrend

**파일**: SupertrendStrategy.java

**2025 백테스트**: BTC -39.45%(MDD -45.36%), ETH -7.57%(MDD -51.80%)

> 3년 백테스트에서는 BTC +81.21%였으나 2025년 횡보장에서 -39%. **Regime 없이는 사용 불가**임이 실증됨.

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **횡보장 사용 불가** | 2025 결과가 증명. TREND Regime에서만 활성화 필수 |
| 🔴 높음 | **성능 O(n²)** | 매 캔들마다 전체 ATR 재계산 |
| 🟡 중간 | **신호 강도 코드 버그** | 삼항연산 동일 값 문제 (v3에서 지적, 여전히 미수정) |

#### v4 적용 조건 (v3보다 훨씬 제한적)

```
SUPERTREND 활성화 조건 (AND):
  1. Regime = TREND (ADX > 25, N캔들 연속)
  2. Hysteresis 통과
  3. 코인 무관 (BTC/ETH 모두 TREND에서는 활용 가능)

→ RANGE, VOLATILITY, TRANSITIONAL에서는 완전 비활성화
```

---

### 8. ATR Breakout

**파일**: AtrBreakoutStrategy.java

**2025 백테스트**: BTC -29.75%, ETH +39.01% → **EMA_CROSS와 동일한 코인 분리 패턴**

#### v4 핵심 업데이트

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **코인-전략 불일치** | BTC -30%인데 ETH +39%. Coin Profile 없이 사용 불가 |
| 🟡 중간 | **거짓 돌파 필터 없음** | 일시적 급등 후 복귀 패턴 미감지 |
| 🟡 중간 | **거래량 확인 없음** | 변동성 돌파의 신뢰도는 거래량 동반 여부에 좌우 |

#### v4 적용 조건

```
ATR_BREAKOUT 활성화 조건:
  1. Coin = ETH (또는 변동성 높은 알트)
  2. Regime = VOLATILITY 또는 TREND 초기
  3. 거래량 필터: 평균 거래량 1.5배 이상 동반

→ BTC에서는 기본 비활성화 (VOLATILITY 극단 구간만 예외)
```

---

### 9. Orderbook Imbalance

**파일**: OrderbookImbalanceStrategy.java

**2025 백테스트**: BTC +0.79%, ETH +30.55% → **양코인 플러스, 특히 ETH에서 강세**

> 낮은 승률(BTC 40%, ETH 27%)에도 플러스 수익 → **페이오프 비율이 우수한 전략**

#### v4 핵심 업데이트

ORDERBOOK은 v4에서 **핵심 전략으로 승격**.

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **호가 스푸핑 미감지** | 호가 Delta 추적으로 해결 필요 |
| 🟡 중간 | **실제 거래량 미반영** | Trade Flow 결합으로 신뢰도 향상 가능 |

---

### 10. Stochastic RSI

**파일**: StochasticRsiStrategy.java

**2025 백테스트**: BTC -70.36%(MDD -71.34%), ETH -67.60%(MDD -71.00%) → **완전 폐기 검토**

> ADX 필터 + 임계값 강화(과매도 15, 과매수 85) 후에도 -70% 수준 유지.
> **파라미터 문제가 아닌 전략 알고리즘 자체의 구조적 결함**으로 판단.

#### v4 결정

```
STOCHASTIC_RSI → Phase 1에서 비활성화

재설계 방향 (Phase 4에서 검토):
  - Raw %K → Smooth %K (SMA(3) 적용)
  - 단순 크로스 → 추세 방향과 일치하는 방향만 신호
  - 재설계 후 A/B 테스트로 재평가

현재: 비활성화 상태로 코드 유지 (삭제 X)
```

---

## 🏗️ 공통 인프라 분석 (v3 계승)

### IndicatorUtils 최적화 필요

| 심각도 | 문제 | 해결 방안 |
|--------|------|---------|
| 🟡 중간 | **표준편차 — 모표준편차 사용** | 의도적 선택이면 주석 추가 |
| 🟡 중간 | **double 변환 정밀도 손실** | BigDecimal 자체 sqrt 구현 검토 |
| 🟡 중간 | **파라미터 헬퍼 중복** | 공통 유틸리티로 통합 |

### Strategy 인터페이스 제약

| 심각도 | 문제 | 해결 방안 |
|--------|------|---------|
| 🟡 중간 | **상태 관리 불가** | `StatefulStrategy` 인터페이스 추가 (Grid 즉시 필요) |
| 🟡 중간 | **복합 전략 지원 없음** | `CompositeStrategy` 패턴 구현 |

---

## 🎯 통합 개선 로드맵 v4

### Phase 0: 전략 비활성화 (즉시, 1-2일)

**백테스트 결과 기반 손실 전략 즉시 차단**

| # | 항목 | 조치 |
|---|------|------|
| 1 | **STOCHASTIC_RSI** | 설정에서 `enabled: false` (코드 삭제 X) |
| 2 | **MACD** | 설정에서 `enabled: false` |
| 3 | **EMA_CROSS** | ETH + TREND 조건 충족 시만 활성화 (나머지 비활성) |
| 4 | **ATR_BREAKOUT** | ETH + VOLATILITY/TREND 조건 충족 시만 활성화 |
| 5 | **VWAP** | 임계값 2.5% → 1.5%로 재조정 후 재테스트 |

**Phase 0 체크리스트:**
- [ ] 전략별 `enabled` 플래그 설정 구조 추가
- [ ] STOCHASTIC_RSI 비활성화
- [ ] MACD 비활성화
- [ ] VWAP 임계값 1.5% 재조정
- [ ] 비활성화 후 단순 회귀 테스트 실행

---

### Phase 1: 버그 수정 & 즉시 개선 (1주일)

| # | 항목 | 설명 | 우선도 |
|---|------|------|--------|
| 1 | **Grid 상태 추적 구현** | 레벨별 진입/청산 이력 → 중복 매매 방지 | 🔴 긴급 |
| 2 | **Supertrend 코드 오류** | 삼항연산 동일 값 수정 (v3에서 지적, 미수정) | 🔴 긴급 |
| 3 | **Grid 하드코딩 제거** | `999999999999` → 안전한 초기화 | 🔴 |
| 4 | **StatefulStrategy 인터페이스** | Grid 상태 추적을 위한 인터페이스 추가 | 🟠 |
| 5 | **상충 신호 테스트** | Bollinger SELL + Supertrend BUY 동시 발생 케이스 | 🟠 |

**Phase 1 체크리스트:**
- [ ] `StatefulStrategy` 인터페이스 설계 및 구현
- [ ] Grid 상태 추적 구현 (중복 매매 방지)
- [ ] Grid 초기화 안전화 (하드코딩 제거)
- [ ] Supertrend 신호 강도 코드 오류 수정
- [ ] 상충 신호 테스트 케이스 작성

---

### Phase 2: Market Regime + Risk Engine (2-3주)

> v3와 동일하되, 백테스트 결과를 Regime 분류 정확도 검증 기준으로 활용

#### 2-1. Market Regime Detector 구현

```java
class MarketRegimeDetector {
  enum Regime { TREND, RANGE, VOLATILITY, TRANSITIONAL }

  private Regime previousRegime = Regime.RANGE;
  private int regimeHoldCount = 0;
  private static final int HYSTERESIS_CANDLES = 3;

  Regime detect(List<Candle> candles) {
    double adx = calculateADX(candles);
    double bbBandwidth = calculateBandwidth(candles);
    double bbLow20 = calculateBandwidthPercentile(candles, 20);
    double atr = calculateATR(candles);
    double atrMA = calculateATRMovingAverage(candles, 20);

    Regime detected;

    if (adx > 25) {
      detected = Regime.TREND;
    } else if (adx < 20 && bbBandwidth < bbLow20) {
      detected = Regime.RANGE;
    } else if (atr > atrMA * 1.5 && adx < 25) {
      detected = Regime.VOLATILITY;
    } else {
      detected = Regime.TRANSITIONAL;
    }

    // Hysteresis: 새 Regime이 N캔들 연속 유지되어야 전환
    if (detected != previousRegime) {
      regimeHoldCount++;
      if (regimeHoldCount < HYSTERESIS_CANDLES) {
        return previousRegime;
      }
    }

    regimeHoldCount = 0;
    previousRegime = detected;
    return detected;
  }
}
```

#### 2-2. Coin Profile (v4 신규)

```java
enum CoinProfile {
  BTC_LIKE,   // 평균회귀 패턴 강함 → GRID, BOLLINGER 가중치 ↑
  ETH_LIKE,   // 단기 모멘텀 패턴 강함 → ATR_BREAKOUT, EMA 가중치 ↑
  ALT_LIKE    // 고변동성 → ORDERBOOK 가중치 ↑
}

class CoinProfileMapper {
  static CoinProfile of(String symbol) {
    if (symbol.contains("BTC")) return BTC_LIKE;
    if (symbol.contains("ETH")) return ETH_LIKE;
    return ALT_LIKE; // 기본값
  }
}
```

> **주의**: 현재 BTC/ETH 결과는 2025년 단년 기반. CoinProfile은 다년도 데이터 수집 후 재검증 필요.
> 초기에는 보수적으로 적용하고, 백테스트 데이터가 쌓이면 프로파일 동적 조정.

#### 2-3. Risk Engine 구현 (v3와 동일)

```java
class RiskEngine {

  // Fixed Fractional Position Sizing
  PositionSize calculate(
    BigDecimal accountBalance,
    BigDecimal riskPercentage,    // 1% = 0.01
    BigDecimal stopLossDistance   // 2% = 0.02
  ) {
    BigDecimal position = accountBalance
      .multiply(riskPercentage)
      .divide(stopLossDistance, SCALE, HALF_UP);
    return new PositionSize(position);
  }

  // Correlation-Adjusted Open Trades
  int effectiveSlots(List<OpenPosition> positions) {
    int slots = 0;
    for (OpenPosition pos : positions) {
      slots += 1;
      for (OpenPosition other : positions) {
        if (pos != other && correlation(pos.symbol, other.symbol) > 0.7) {
          slots += 1;
          break;
        }
      }
    }
    return slots;
  }

  boolean isDailyLimitExceeded(BigDecimal dailyLoss, BigDecimal dailyMax) {
    return dailyLoss.compareTo(dailyMax) > 0;
  }
}
```

**Phase 2 체크리스트:**
- [ ] Market Regime Detector 구현
- [ ] TRANSITIONAL + Hysteresis 로직
- [ ] ADX, Bollinger Bandwidth 퍼센타일 함수
- [ ] CoinProfile 매퍼 구현 (BTC_LIKE / ETH_LIKE)
- [ ] Risk Engine (Fixed Fractional)
- [ ] Correlation Risk 매트릭스
- [ ] 단위 테스트: Regime 전환 시나리오 (2025년 BTC 데이터로 검증)
- [ ] 2025년 백테스트 데이터로 Regime 분류 수동 라벨링 검증

---

### Phase 3: Strategy Selector & Composite Strategy (2주)

v4에서 CoinProfile이 추가되어 Selector 로직 업데이트:

```java
class StrategySelector {
  List<WeightedStrategy> selectStrategies(
    MarketRegime regime,
    CoinProfile coinProfile  // v4 신규 파라미터
  ) {
    switch(regime) {
      case TREND:
        if (coinProfile == ETH_LIKE) {
          return List.of(
            new WeightedStrategy(new SupertrendStrategy(...), 0.4),
            new WeightedStrategy(new EmaCrossStrategy(...), 0.4),  // ETH에서 강세
            new WeightedStrategy(new AtrBreakoutStrategy(...), 0.2)
          );
        } else { // BTC_LIKE
          return List.of(
            new WeightedStrategy(new SupertrendStrategy(...), 0.6),
            new WeightedStrategy(new EmaCrossStrategy(...), 0.4)
          );
        }

      case RANGE:
        if (coinProfile == BTC_LIKE) {
          return List.of(
            new WeightedStrategy(new BollingerStrategy(...), 0.4),  // BTC에서 강세
            new WeightedStrategy(new RsiStrategy(...), 0.3),
            new WeightedStrategy(new GridStrategy(...), 0.3)
          );
        } else { // ETH_LIKE
          return List.of(
            new WeightedStrategy(new GridStrategy(...), 0.5),       // ETH RANGE는 Grid
            new WeightedStrategy(new OrderbookImbalanceStrategy(...), 0.3),
            new WeightedStrategy(new RsiStrategy(...), 0.2)
          );
        }

      case VOLATILITY:
        if (coinProfile == ETH_LIKE) {
          return List.of(
            new WeightedStrategy(new AtrBreakoutStrategy(...), 0.6), // ETH에서 강세
            new WeightedStrategy(new OrderbookImbalanceStrategy(...), 0.4)
          );
        } else { // BTC_LIKE
          return List.of(
            new WeightedStrategy(new OrderbookImbalanceStrategy(...), 0.6),
            new WeightedStrategy(new GridStrategy(...), 0.4)
          );
        }

      case TRANSITIONAL:
        return previousStrategies.stream()
          .map(ws -> ws.withReducedWeight(0.5))
          .collect(toList());
    }
  }
}
```

**Phase 3 체크리스트:**
- [ ] WeightedStrategy 래퍼 클래스 구현
- [ ] CoinProfile을 반영한 StrategySelector 구현
- [ ] Weighted Voting 알고리즘
- [ ] CompositeStrategy 인터페이스
- [ ] BTC_LIKE/ETH_LIKE 각 조합 백테스트 검증
- [ ] TRANSITIONAL Regime 포지션 축소 로직

---

### Phase 4: 개별 전략 고도화 (3주)

v4 우선순위 재조정 (백테스트 결과 반영):

| # | 전략 | 개선 사항 | 우선순위 |
|---|------|---------|---------|
| 1 | **Grid** | 동적 범위 + 추세 감지 종료 | ⭐⭐⭐⭐⭐ |
| 2 | **Orderbook** | Trade Flow + 호가 Delta 추적 | ⭐⭐⭐⭐⭐ |
| 3 | **Supertrend** | ATR 캐싱 최적화 (O(n²)→O(n)) | ⭐⭐⭐⭐ |
| 4 | **Bollinger** | Squeeze 감지 + BTC 전용 조건 | ⭐⭐⭐⭐ |
| 5 | **ATR Breakout** | 거래량 필터 + ETH 전용 조건 | ⭐⭐⭐ |
| 6 | **RSI** | 피봇 기반 다이버전스 | ⭐⭐⭐ |
| 7 | **VWAP** | ATR 비례 임계값 | ⭐⭐ |
| 8 | **MACD** | 히스토그램 기울기 필터 (재설계 후) | ⭐ |
| 9 | **Stochastic RSI** | 구조 재설계 (Smooth %K) | ⭐ |

**Phase 4 체크리스트:**
- [ ] Grid 동적 범위 + 범위 돌파 감지
- [ ] Orderbook 호가 Delta 추적
- [ ] Supertrend ATR 사전 계산 리팩토링
- [ ] Bollinger Squeeze 감지 + BTC 조건 태그
- [ ] ATR Breakout 거래량 필터 + ETH 조건 태그
- [ ] RSI 피봇 기반 다이버전스
- [ ] VWAP ATR 비례 임계값 (1.5% 임시 조정 → 동적으로 발전)
- [ ] MACD 히스토그램 기울기 재설계
- [ ] Stochastic RSI Smooth %K 재설계 후 A/B 테스트

---

### Phase 5: 신호 확장 & Multi-Timeframe + 백테스트 (2주)

| # | 기능 | 설명 |
|---|------|------|
| 1 | **StrategySignal 확장** | `suggestedStopLoss`, `suggestedTakeProfit`, `confidence` 추가 |
| 2 | **Multi-Timeframe** | 상위 TF 트렌드 + 하위 TF 진입 |
| 3 | **타임프레임별 파라미터** | 1m/5m/1h/1d 자동 조절 |
| 4 | **백테스트 프레임워크** | Phase 0-4 통합 검증 (2023-2025 BTC/ETH) |
| 5 | **CoinProfile 재검증** | 다년도 데이터로 BTC_LIKE/ETH_LIKE 패턴 검증 |

**Phase 5 체크리스트:**
- [ ] StrategySignal 확장
- [ ] Multi-Timeframe 데이터 파이프라인
- [ ] 백테스트 엔진 (Phase 0-4 전체 통합)
- [ ] 2023-2025 BTC/ETH 통합 백테스트
- [ ] CoinProfile 2023-2024 데이터 재검증
- [ ] 결과 기반 파라미터 최적화

---

## 🚀 실전 구현 전략 (v4 업데이트)

### 코인별 권장 전략 조합

#### KRW-BTC (평균회귀 중심)

```
RANGE Regime (2025년 지배적):
  Primary:   GRID (weight: 0.4)
  Secondary: BOLLINGER (weight: 0.4)
  Filter:    RSI 타이밍 보조 (weight: 0.2)

TREND Regime (ADX > 25):
  Primary:   SUPERTREND (weight: 0.6)
  Secondary: EMA_CROSS (weight: 0.4)

VOLATILITY:
  Primary:   ORDERBOOK_IMBALANCE (weight: 0.6)
  Secondary: GRID (weight: 0.4)
```

#### KRW-ETH (모멘텀 중심)

```
RANGE Regime:
  Primary:   GRID (weight: 0.4)
  Secondary: ORDERBOOK_IMBALANCE (weight: 0.4)
  Filter:    RSI 타이밍 보조 (weight: 0.2)

TREND Regime:
  Primary:   SUPERTREND (weight: 0.3)
             EMA_CROSS (weight: 0.4)       ← ETH에서 강세
             ATR_BREAKOUT (weight: 0.3)

VOLATILITY:
  Primary:   ATR_BREAKOUT (weight: 0.6)   ← ETH에서 강세
  Secondary: ORDERBOOK_IMBALANCE (weight: 0.4)
```

### 제거/비활성화 전략

```
즉시 비활성화:
  - STOCHASTIC_RSI: 구조적 결함 (-70%)
  - MACD: 구조 재설계 전 비활성 (-59%)

코인 조건부 활성화 (Coin Profile 구현 전 비활성):
  - EMA_CROSS: ETH + TREND만
  - ATR_BREAKOUT: ETH + VOLATILITY/TREND만
  - BOLLINGER: BTC + RANGE만

단독 신호 금지 (보조 필터로만):
  - RSI: CompositeStrategy 내 타이밍 필터
```

### 신호 의사결정 구조 (v4)

```
입력: 시장 데이터
  ↓
[0] COIN PROFILE 결정 (v4 신규)
  ├─ BTC → BTC_LIKE (평균회귀 선호)
  └─ ETH → ETH_LIKE (모멘텀 선호)
  ↓
[1] Market Regime 판단 (Hysteresis 적용)
  ├─ TREND / RANGE / VOLATILITY / TRANSITIONAL
  ↓
[2] Strategy Selector (Regime + CoinProfile)
  ├─ BTC + RANGE → GRID + BOLLINGER + RSI
  ├─ ETH + RANGE → GRID + ORDERBOOK + RSI
  ├─ BTC + TREND → SUPERTREND + EMA
  ├─ ETH + TREND → SUPERTREND + EMA + ATR
  ├─ BTC + VOLATILITY → ORDERBOOK + GRID
  ├─ ETH + VOLATILITY → ATR_BREAKOUT + ORDERBOOK
  └─ TRANSITIONAL → 직전 전략 유지 + 포지션 축소
  ↓
[3] Weighted Voting
  ├─ 가중 합산 > 0.6 → 강한 신호
  ├─ 가중 합산 > 0.4 → 약한 신호
  ├─ BUY/SELL 상충 → HOLD
  └─ 모두 HOLD → HOLD
  ↓
[4] Risk Engine
  ├─ Position Size (Fixed Fractional)
  ├─ Correlation Risk
  ├─ Daily Loss 체크
  └─ Max Open Trades
  ↓
[5] 주문 실행
  └─ Entry + Stop Loss + Take Profit
```

---

## 📊 전략 비교 요약 (v4 업데이트)

| 조합 | 코인 | 적합 시장 | 2025 실적 기반 기대 | 복잡도 |
|------|------|----------|-------------------|--------|
| **GRID + ORDERBOOK** | BTC+ETH | 횡보/변동 | ✅ 양코인 검증됨 | ⭐⭐ |
| **GRID + BOLLINGER** | BTC | RANGE | ✅ BTC 검증됨 | ⭐⭐ |
| **ATR + ORDERBOOK** | ETH | VOLATILITY | ✅ ETH 검증됨 | ⭐⭐ |
| **SUPERTREND + EMA** | ETH | TREND | ✅ ETH 검증 (BTC 미검증) | ⭐⭐ |
| **SUPERTREND + RSI** | BTC | TREND | ⚠️ 2025 미검증 (3년 기준 유망) | ⭐⭐ |
| **CompositeStrategy** | BTC+ETH | All | 🔬 Phase 3 구현 후 검증 필요 | ⭐⭐⭐⭐ |

---

## 🎓 최종 평가 & 기대 효과

### 현재 상태 (v4 기준)

- **지표 계산**: 상위 수준 ✅
- **백테스트 결과**: 2025년 기준 10개 중 4개만 양전
- **핵심 부재**: Regime 판단, CoinProfile, Risk Engine, Strategy Selector

### v4 개선 로드맵 적용 시 기대 효과

#### 즉각 효과 (Phase 0 완료 후)

```
STOCHASTIC_RSI(-70%) + MACD(-59%) 비활성화만으로도
→ 평균 포트폴리오 손실 -30% 이상 개선 기대
```

#### 구조적 효과 (Phase 2-3 완료 후)

- Regime 판단으로 추세 전략이 횡보장에 진입하지 않음
- CoinProfile로 코인-전략 불일치 제거
- Risk Engine으로 MDD -70% 수준 방지

### 성과 검증 계획

> ⚠️ **수치 목표는 백테스트 완료 후 설정**
> 2025년 단년 결과만으로 전천후 성과를 예측할 수 없다.

**Phase별 검증 기준:**

| Phase | 검증 항목 | 기준 |
|-------|----------|------|
| Phase 0 완료 | 비활성화 전략 제외 후 수익률 | Phase 0 전 대비 개선 확인 |
| Phase 2 완료 | Regime 분류 정확도 | 2025년 수동 라벨 대비 70%+ |
| Phase 2 완료 | CoinProfile 유효성 | BTC/ETH 전략 선택 정확도 |
| Phase 3 완료 | 신호 상충 비율 | 기존 대비 80%↓ |
| Phase 4 완료 | 개별 전략 백테스트 | 각 전략 단독 성과 |
| Phase 5 완료 | 통합 시스템 성과 | 2023-2025 BTC/ETH 전체 기간 |

---

## 결론

**v3 → v4 핵심 변화**: 이론에서 실증으로.

2025년 백테스트가 v3의 이론적 제안을 실증했다:

1. **Regime 없으면 추세 전략은 횡보장에서 대규모 손실** → Market Regime Detector 우선 구현
2. **BTC/ETH는 같은 시장에서도 다른 전략이 먹힌다** → CoinProfile 레이어 신규 추가
3. **STOCHASTIC_RSI, MACD는 필터 추가로도 개선 불가** → 즉시 비활성화 후 재설계
4. **GRID, ORDERBOOK은 시장과 무관하게 안정적** → 핵심 전략으로 승격

**추천 실행 순서**: Phase 0(비활성화) → Phase 1(버그 수정) → Phase 2(Regime+CoinProfile+Risk) → Phase 3(CompositeStrategy) → 백테스트 검증 반복
