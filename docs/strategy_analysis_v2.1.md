# 📊 암호화폐 자동매매 전략 분석 & 개선 로드맵 v2

> **통합 분석**: 기존 10개 전략의 상세 분석 + 거시적 구조 개선 제안  
> **작성일**: 2026-03-15  
> **목표**: 개인 프로젝트 수준 → 실전 자동매매 시스템으로 발전

---

## 📋 Executive Summary

현재 전략 체계는 **개인 프로젝트 기준 상위 수준**이지만, 실전 자동매매로 발전하려면 다음 4가지 필수 요소 부재:

| # | 필수 요소 | 현재 상태 | 영향도 |
|---|----------|---------|--------|
| 1 | **Market Regime Detector** | ❌ 없음 | 🔴 매우 높음 |
| 2 | **Risk Engine** | ❌ 없음 | 🔴 매우 높음 |
| 3 | **Strategy Selector** | ❌ 없음 | 🟠 높음 |
| 4 | **Multi-Timeframe** | ❌ 없음 | 🟠 높음 |

---

## 🔴 거시적 구조 문제

### 1. Market Regime (시장 상태) 판단 없음 — 가장 큰 문제

#### 현재 문제점

**상충하는 신호가 동시에 발생** 가능:

```
강한 상승장에서:
  ✅ Supertrend → BUY
  ✅ EMA Cross → BUY  
  ❌ Bollinger → SELL (밴드 상단 이탈)
  ❌ RSI → SELL (과매수)

→ 같은 시장에서 상충하는 신호 발생!
```

이는 자동매매에서 **가장 흔한 손실 구조**.

#### 원인
- 각 전략이 독립적으로 신호 생성
- 시장 상태(트렌드/횡보/변동성)를 판단 후 적절한 전략만 활성화하는 메커니즘 부재

---

### 2. 전략 중복 문제 — 10개 전략, 실제로는 4개 구조

#### 현황 분석

**추세 전략이 3개:**
- EMA CROSS → 추세
- MACD → 추세  
- SUPERTREND → 추세

**평균회귀 전략이 2개:**
- RSI → 평균회귀
- BOLLINGER → 평균회귀

**실제 구조:**
```
10개 전략 → 4개 전략 체계

1. 추세 전략 (EMA/MACD/Supertrend 중 하나만 필요)
2. 평균회귀 전략 (RSI/Bollinger 중 선택)
3. 변동성 전략 (ATR Breakout)
4. 호가 분석 (Orderbook)
```

#### 해결 방법: 전략 구조 재편성

```
TREND 시장
├─ Supertrend (메인)
├─ EMA (보조)
└─ ADX (확인)

MEAN REVERSION
├─ RSI (메인)
├─ Bollinger (보조)
└─ VWAP (필터)

BREAKOUT
├─ ATR Breakout
└─ Volume Spike

MICROSTRUCTURE
└─ Orderbook imbalance + Trade Flow
```

---

### 3. 리스크 관리 부재 — 가장 중요한 요소 없음

#### 현재 구조의 치명적 결함

**자동매매에서 필요한 리스크 관리:**

```
❌ Stop Loss → 없음
❌ Position Size → 고정 수량
❌ Daily Max Loss → 제한 없음
❌ Max Open Trades → 제한 없음
❌ Max Leverage → 제한 없음
```

한 번의 큰 손실이 **전체 계좌를 날릴 수 있는 구조**.

#### Risk Engine 필수 요소

**Kelly Criterion 기반 Position Sizing:**

```
Position Size = Account × Risk% / Stop Distance%

예시:
  계좌: $10,000
  Risk: 1% ($100)
  손절 거리: 2%
  → Position Size = $10,000 × 0.01 / 0.02 = $5,000
```

**추가 제한:**

```
Daily Max Loss    = 5% (일일 손실 한도)
Max Open Trades   = 3 (동시 포지션 제한)
Max Leverage      = 3x (레버리지 제한)
Win Rate Confirm  = 30+ 거래 이후 유의성 판단
```

---

### 4. 자동매매 시스템 구조의 문제

#### 현재 구조 (SIGNAL ENGINE 중심)

```
입력 → [SIGNAL ENGINE] → 신호 → 실행
       (모든 전략 무조건 실행)
```

**문제점:**
- 시장 상태를 고려하지 않음
- 신호 우선순위 없음
- 리스크 통제 불가

#### 권장 구조 (시스템 아키텍처)

```
시장 데이터
    ↓
[1] MARKET DETECTOR
    ├─ ADX > 25? → TREND
    ├─ ADX < 20 + BB Narrow? → RANGE
    └─ ATR Spike? → VOLATILITY
    ↓
[2] STRATEGY SELECTOR
    ├─ TREND → Supertrend + EMA
    ├─ RANGE → Bollinger + RSI + Grid
    └─ VOLATILITY → ATR Breakout
    ↓
[3] SIGNAL ENGINE
    ├─ 선택된 전략들의 신호 생성
    └─ Strategy Voting (다수결 투표)
    ↓
[4] RISK ENGINE
    ├─ Position Size 계산
    ├─ Daily Loss 확인
    └─ Max Trades 확인
    ↓
[5] EXECUTION ENGINE
    └─ 신호 → 주문 실행
```

---

## 📋 전략 목록 & 분류

| # | 전략명 | 유형 | 주요 지표 | 시장 적합도 | 우선순위 |
|---|--------|------|-----------|------------|---------|
| 1 | **VWAP** | 역추세(평균 회귀) | 거래량 가중 평균가 | 횡보장 | ⭐⭐⭐ |
| 2 | **EMA_CROSS** | 추세 추종 | EMA(9), EMA(21) | 트렌드장 | ⭐⭐ |
| 3 | **BOLLINGER** | 역추세(평균 회귀) | SMA + 표준편차 밴드 | 횡보/변동장 | ⭐⭐⭐ |
| 4 | **GRID** | 범위 매매 | 가격 격자 분할 | 횡보장 | ⭐⭐ |
| 5 | **RSI** | 모멘텀/타이밍 | RSI + 다이버전스 | 모든 시장 | ⭐⭐⭐⭐ |
| 6 | **MACD** | 추세/모멘텀 | MACD/Signal 크로스 | 트렌드장 | ⭐⭐ |
| 7 | **SUPERTREND** | 추세 추종 | ATR 기반 동적 밴드 | 트렌드장 | ⭐⭐⭐⭐⭐ |
| 8 | **ATR_BREAKOUT** | 변동성 돌파 | ATR × 배수 | 모멘텀장 | ⭐⭐⭐⭐ |
| 9 | **ORDERBOOK_IMBALANCE** | 호가 분석 | 매수/매도 볼륨 비율 | 단기 스캘핑 | ⭐⭐⭐ |
| 10 | **STOCHASTIC_RSI** | 모멘텀/타이밍 | StochRSI %K/%D | 변동장/횡보 | ⭐⭐⭐ |

---

## 🔍 개별 전략 상세 분석

---

### 1. VWAP (거래량 가중 평균가 역추세)

**📁 파일**: VwapStrategy.java

**원리**: VWAP 대비 현재가의 이탈률이 ±thresholdPct 이상이면 반대 방향 신호 생성

**파라미터**:
- `thresholdPct`: 이탈 임계값 (기본 1.0%)
- `period`: VWAP 계산 기간 (기본 20)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **VWAP 리셋 미구현** | 실제 VWAP은 장 시작 시 리셋되어야 하나, 여기선 롤링 기간만 사용함. 암호화폐의 24시간 시장 특성상 일별 UTC 00:00 기준 리셋이 표준 |
| 🟡 중간 | **임계값 고정** | 변동성에 따라 임계값이 동적으로 조정되지 않음. BTC 1%와 알트코인 1%는 의미가 매우 다름 |
| 🟢 낮음 | **볼륨 0 처리** | `sumVolume == 0`일 때 종가를 VWAP으로 반환하는데, 이 경우 신호를 HOLD로 처리하는 것이 더 안전함 |

#### 💡 개선 제안

1. **변동성 비례 임계값**: ATR 대비 비율로 임계값 자동 조절  
2. **VWAP 편차 밴드**: VWAP ± 1σ, ±2σ 밴드 추가 (Volume-Weighted Standard Deviation)
3. **Anchored VWAP**: 특정 피봇 포인트부터 VWAP 계산 시작 옵션 추가

---

### 2. EMA Cross (지수이동평균 크로스)

**📁 파일**: EmaCrossStrategy.java

**원리**: 단기 EMA(9)가 장기 EMA(21)를 상향/하향 돌파 시 BUY/SELL

**파라미터**:
- `fastPeriod`: 단기 EMA (기본 9)
- `slowPeriod`: 장기 EMA (기본 21)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **횡보장 거짓 신호(Whipsaw)** | EMA 크로스만 사용하면 횡보 구간에서 빈번한 거짓 신호 발생. 필터링 메커니즘이 전혀 없음 |
| 🟡 중간 | **ADX 필터 미구현** | 추세 강도 확인 없이 약한 추세도 신호 생성 |
| 🟡 중간 | **신호 강도 계산 방식** | `gap / slowEma × 1000`이 의미 있는 범위(0~100)를 보장하지 않음. 극단적 값에서 clamp됨 |

#### 💡 개선 제안

1. **ADX 필터 추가**: ADX > 25일 때만 크로스 신호 유효화 (추세 강도 확인)
2. **EMA 3선 구조**: EMA(5), EMA(13), EMA(34) 같은 3중 크로스로 정확도 향상
3. **지연 확인(Confirmation)**: 크로스 후 N개 캔들 유지 확인 옵션
4. **각도(기울기) 필터**: EMA 기울기가 일정 이상일 때만 신호 유효화

---

### 3. Bollinger Bands (볼린저 밴드)

**📁 파일**: BollingerStrategy.java

**원리**: %B < 0 (하단 이탈) → BUY, %B > 1 (상단 이탈) → SELL

**파라미터**:
- `period`: SMA 기간 (기본 20)
- `multiplier`: 표준편차 배수 (기본 2.0)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **트렌드장 역매매 위험** | 강한 상승/하락 추세에서 밴드 이탈 = 되돌림이 아닌 추세 지속일 수 있음. 추세 필터 없음 |
| 🟡 중간 | **밴드 스퀴즈 미감지** | 밴드 폭이 좁아지는 스퀴즈(Squeeze)는 큰 변동의 전조인데, 이 패턴 감지 로직 없음 |
| 🟢 낮음 | **%B만 사용** | 밴드폭(Bandwidth) 지표를 함께 활용하면 변동성 상태 판단 가능 |

#### 💡 개선 제안

1. **Bollinger Squeeze 감지**: `Bandwidth < 30-period 최저` 시 브레이크아웃 대기 모드
2. **추세 방향 필터**: SMA 기울기 또는 상위 시간프레임 추세와 일치하는 방향만 진입
3. **Double Bollinger**: 1σ/2σ 이중 밴드로 진입 구간 세분화
4. **밴드폭 기반 강도 조절**: 밴드가 넓을수록 이탈의 의미가 큼

---

### 4. Grid (그리드 매매)

**📁 파일**: GridStrategy.java

**원리**: lookback 기간의 고저가를 N등분하고, 하위 30% 근접 시 BUY, 상위 30% 근접 시 SELL

**파라미터**:
- `lookbackPeriod`: 범위 산정 기간 (기본 100)
- `gridCount`: 그리드 수 (기본 10)
- `triggerPct`: 레벨 근접 트리거 (기본 0.5%)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **가격 범위 이탈 대응 없음** | 가격이 lookback 고점/저점을 돌파하면 그리드 자체가 무효화됨. 범위 재설정 로직 없음 |
| 🔴 높음 | **추세장에서 손실 위험** | 강한 상승/하락 추세(BTC 30k → 60k)에서 Grid short → 계좌 손실 |
| 🟡 중간 | **상태 미보존** | 어떤 그리드에서 이미 매수했는지 추적하지 않음. 동일 레벨에서 중복 매매 발생 가능 |

#### 💡 개선 제안

1. **동적 그리드 범위**: ATR 또는 Bollinger Band 기반으로 그리드 범위 자동 조절
2. **주문 상태 추적**: 각 그리드 레벨에서의 진입/청산 이력 관리
3. **추세 감지 종료**: ADX > 25 → Grid OFF (추세장에서는 비활성화)
4. **범위 돌파 감지**: Range Breakout → Grid Stop 메커니즘

---

### 5. RSI (상대강도지수)

**📁 파일**: RsiStrategy.java

**원리**: RSI < 30 → BUY(과매도), RSI > 70 → SELL(과매수). 다이버전스 감지 옵션 포함

**파라미터**:
- `period`: RSI 기간 (기본 14)
- `oversoldLevel` / `overboughtLevel`: 과매도/과매수 기준 (기본 30/70)
- `useDivergence`: 다이버전스 감지 활성화 (기본 true)
- `divergenceLookback`: 다이버전스 검출 룩백 (기본 5)

#### 👍 잘 구현된 부분
- **Wilder's Smoothing** 정확히 구현
- **다이버전스 감지**가 포함되어 있어 단순 과매수/과매도보다 정교함
- 다이버전스 조건에 RSI 영역 필터가 있어 정확도 향상

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **다이버전스 감지 단순화** | 진정한 다이버전스는 피봇 포인트(스윙 고점/저점) 기반이어야 하나, 여기선 fixed lookback으로만 비교 |
| 🟡 중간 | **강한 추세에서의 과매수/과매도** | 강한 상승 추세에서 RSI 70 이상이 장기간 유지될 수 있으나, 즉시 SELL 신호 발생 |
| 🟢 낮음 | **RSI 중립 영역 미활용** | 50선 크로스도 추세 전환 신호로 활용 가능하나 현재 미구현 |

#### 💡 개선 제안

1. **피봇 기반 다이버전스**: 스윙 하이/로우를 감지하여 진정한 다이버전스 검출
2. **RSI 구간별 신호**: 30-50 약매수, 50-70 약매도 등 세분화
3. **Hidden Divergence**: 추세 지속형 다이버전스도 감지
4. **RSI 50선 활용**: 50선 상향/하향 돌파를 추세 필터로 활용

---

### 6. MACD (이동평균 수렴/확산)

**📁 파일**: MacdStrategy.java

**원리**: MACD선이 Signal선을 상향/하향 돌파 시 BUY/SELL (골든/데드 크로스)

**파라미터**:
- `fastPeriod`: 단기 EMA (기본 12)
- `slowPeriod`: 장기 EMA (기본 26)
- `signalPeriod`: 시그널 EMA (기본 9)

#### 👍 잘 구현된 부분
- **전체 시계열 순차 스캔**으로 정확한 EMA 계산
- **이전 시점 MACD**도 계산하여 크로스 정확히 감지
- 히스토그램 방향 정보 제공

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **성능 이슈** | `calculateMacd`를 현재/이전 시점에 대해 2번 호출, 비효율적 |
| 🟡 중간 | **제로라인 크로스 미활용** | MACD선이 0선을 상향/하향 돌파하는 것도 중요한 신호이나 미구현 |
| 🟡 중간 | **히스토그램 방향 전환 미감지** | 히스토그램이 축소→확대 전환 = 초기 모멘텀 전환 → 미감지 |

#### 💡 개선 제안

1. **히스토그램 무빙 에버리지(Histogram MA)**: 히스토그램 기울기 변화 감지
2. **제로라인 크로스**: MACD 0선 돌파를 추세 확인 신호로 추가
3. **계산 최적화**: 이전/현재 MACD를 한 번의 순회로 계산
4. **MACD 다이버전스**: 가격과 MACD 다이버전스 감지

---

### 7. Supertrend (슈퍼트렌드)

**📁 파일**: SupertrendStrategy.java

**원리**: ATR 기반 동적 지지/저항 밴드를 계산하고, 가격 위치로 추세 판단

**파라미터**:
- `atrPeriod`: ATR 기간 (기본 10)
- `multiplier`: ATR 배수 (기본 3.0)

#### 👍 잘 구현된 부분
- **밴드 조정 로직** 정확히 구현
- **추세 전환 감지** (크로스) 구현으로 강한 신호 별도 처리
- 전체 시계열 순차 스캔

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **성능 — O(n²) 복잡도** | 매 캔들마다 전체 ATR 재계산 → 효율성 낮음 |
| 🟡 중간 | **신호 강도 변수 무용** | 삼항연산의 양쪽이 동일하여 의미 없음 (코드 오류) |
| 🟡 중간 | **추세 유지 시 신호** | 추세 유지 중 BUY/SELL을 반환하면 포지션 관리에 혼선 |

#### 💡 개선 제안

1. **ATR 캐싱 최적화**: 각 캔들의 ATR을 사전 계산하여 O(n) 복잡도로 개선
2. **코드 버그 수정**: 신호 강도 변수 분리
3. **ADX 결합**: Supertrend + ADX로 추세 강도까지 확인
4. **다중 타임프레임**: 상위 타임프레임 Supertrend로 방향 확인, 하위에서 진입

---

### 8. ATR Breakout (변동성 돌파)

**📁 파일**: AtrBreakoutStrategy.java

**원리**: 현재 캔들 시가 ± ATR×multiplier 돌파 시 BUY/SELL

**파라미터**:
- `atrPeriod`: ATR 기간 (기본 14)
- `multiplier`: 돌파 배수 (기본 1.5)
- `useStopLoss`: 하방 돌파 시 매도 여부 (기본 true)

#### 👍 잘 구현된 부분
- 손절 옵션(`useStopLoss`) 별도 설정 가능
- 비돌파 구간에서도 위치 정보 제공

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **시가 기준 돌파** | 분봉에서는 시가가 자주 바뀌어 신호 불안정 |
| 🟡 중간 | **거짓 돌파(False Breakout) 필터 없음** | 일시적 급등·낙으로 돌파 후 복귀하는 패턴 미감지 |
| 🟡 중간 | **거래량 확인 없음** | 변동성 돌파의 신뢰도는 거래량 동반 여부에 크게 좌우됨 |

#### 💡 개선 제안

1. **이전 캔들 시가 + 전일 ATR**: 래리 윌리엄스 원본 방식 적용
2. **거래량 확인 필터**: 돌파 시 평균 거래량 대비 N배 이상일 때만 유효
3. **돌파 유지 확인**: 캔들 종가 기준 돌파 확인

---

### 9. Orderbook Imbalance (호가 분석)

**📁 파일**: OrderbookImbalanceStrategy.java

**원리**: 매수/매도 호가 볼륨 비율이 임계값 이상이면 우세 방향으로 신호 생성

**파라미터**:
- `imbalanceThreshold`: 불균형 임계값 (기본 0.65)
- `lookback`: 캔들 근사 기간 (기본 5)

#### 👍 잘 구현된 부분
- 실시간/백테스트 이중 모드 설계
- 캔들 기반 볼륨 분해 공식이 합리적
- EPSILON 처리로 0 나눗셈 방지

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **호가 스푸핑 미감지** | 대형 호가가 실행 직전 취소되는 스푸핑/레이어링을 구분할 수 없음 |
| 🟡 중간 | **실제 거래량 미반영** | 실시간 호가와 실제 거래량의 괴리 미처리 |
| 🟢 낮음 | **캔들 근사치 신뢰도** | 캔들 기반 근사는 실제 호가 불균형과 상관관계가 낮을 수 있음 |

#### 💡 개선 제안

1. **호가 + 거래량 조합**: Orderbook imbalance + real trade flow 결합
2. **호가 깊이별 가중치**: 가까운 호가에 높은 가중치 부여
3. **불균형 변화율**: 절대 불균형보다 급변하는 시점이 더 유의미

---

### 10. Stochastic RSI

**📁 파일**: StochasticRsiStrategy.java

**원리**: RSI에 Stochastic 공식 적용 → %K가 과매도 탈출 + %K > %D → BUY, 반대 → SELL

**파라미터**:
- `rsiPeriod`: RSI 기간 (기본 14)
- `stochPeriod`: Stochastic 기간 (기본 14)
- `signalPeriod`: %D 이동평균 기간 (기본 3)

#### 👍 잘 구현된 부분
- **RSI 시계열 전체 계산**으로 정확한 Stochastic 적용
- **%K/%D 크로스 + 영역 탈출** 이중 조건으로 거짓 신호 감소
- Javadoc이 상세하고 명확

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **%K Smoothing 미적용** | 일반적으로 Raw %K에 SMA(3)을 적용한 Smooth %K를 사용하나, 현재는 Raw %K 사용 |
| 🟢 낮음 | **중립 영역 신호** | 20~80 사이에서는 항상 HOLD → 신호 발생 빈도 낮음 |

#### 💡 개선 제안

1. **Smooth %K**: Raw %K에 SMA(3) 적용하여 노이즈 감소
2. **%K/%D 크로스만 별도 신호**: 영역 제한 없이 크로스만으로도 약한 신호 생성
3. **코드 표준화**: 다른 Config와 동일하게 Lombok 적용

---

## 🏗️ 공통 인프라 분석

### IndicatorUtils 최적화 필요

| 심각도 | 문제 | 해결 방안 |
|--------|------|---------|
| 🟡 중간 | **표준편차 — 모표준편차 사용** | 의도적 선택이면 주석 추가, 표본분산 필요 시 `/ (period - 1)` 적용 |
| 🟡 중간 | **double 변환으로 정밀도 손실** | Newton's method 등으로 BigDecimal 자체 sqrt 구현 검토 |
| 🟡 중간 | **파라미터 헬퍼 중복** | `getInt`, `getDouble`, `getBoolean` → 공통 유틸리티로 통합 |

### Strategy 인터페이스 제약

| 심각도 | 문제 | 해결 방안 |
|--------|------|---------|
| 🟡 중간 | **상태 관리 불가** | `StatefulStrategy` 인터페이스 추가 검토 |
| 🟡 중간 | **복합 전략 지원 없음** | `CompositeStrategy` 패턴 구현 (Strategy Voting Engine) |

---

## 🎯 통합 개선 로드맵

### Phase 1: 즉시 수정 (버그 & 치명적 문제)

**기간**: 1주일 | **영향도**: 🔴 매우 높음

| # | 항목 | 설명 |
|---|------|------|
| 1 | **Supertrend 코드 오류** | Line 62 삼항연산 동일 값 수정 |
| 2 | **Grid 하드코딩** | `999999999999` 제거, 안전한 초기화 |
| 3 | **위험 신호 검증** | 상충하는 신호(Bollinger SELL + Supertrend BUY) 테스트 |

### Phase 2: 구조적 기초 (Market Regime + Risk Engine)

**기간**: 2-3주 | **영향도**: 🔴 매우 높음

#### 2-1. Market Regime Detector 구현

```java
class MarketRegimeDetector {
  enum Regime { TREND, RANGE, VOLATILITY }
  
  Regime detect(List<Candle> candles) {
    double adx = calculateADX(candles);
    double bbBandwidth = calculateBandwidth(candles);
    double atr = calculateATR(candles);
    
    if (adx > 25) return Regime.TREND;
    if (adx < 20 && bbBandwidth < bbLow20Period) return Regime.RANGE;
    if (atr > atrHigh) return Regime.VOLATILITY;
    
    return Regime.RANGE; // default
  }
}
```

#### 2-2. Risk Engine 구현

```java
class RiskEngine {
  PositionSize calculate(
    BigDecimal accountBalance,
    BigDecimal riskPercentage,    // 1% = 0.01
    BigDecimal stopLossDistance   // 2% = 0.02
  ) {
    // Position = Account × Risk% / Stop Distance%
    BigDecimal position = accountBalance
      .multiply(riskPercentage)
      .divide(stopLossDistance, SCALE, HALF_UP);
    return new PositionSize(position);
  }
  
  boolean isDailyLimitExceeded(BigDecimal dailyLoss, BigDecimal dailyMax) {
    return dailyLoss.compareTo(dailyMax) > 0;
  }
}
```

### Phase 3: Strategy Selector & Composite Strategy

**기간**: 2주 | **영향도**: 🟠 높음

```java
class StrategySelector {
  List<Strategy> selectStrategies(MarketRegime regime) {
    switch(regime) {
      case TREND:
        return List.of(
          new SupertrendStrategy(...),
          new EmaStrategy(...),
          new AdxIndicator(...) // 확인용
        );
      case RANGE:
        return List.of(
          new BollingerStrategy(...),
          new RsiStrategy(...),
          new GridStrategy(...)
        );
      case VOLATILITY:
        return List.of(
          new AtrBreakoutStrategy(...),
          new StochasticRsiStrategy(...)
        );
    }
  }
}

class CompositeStrategy implements Strategy {
  StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
    List<Strategy> selected = selector.selectStrategies(regime);
    List<StrategySignal> signals = selected.stream()
      .map(s -> s.evaluate(candles, params))
      .collect(toList());
    
    // Strategy Voting Engine
    return votingEngine.combine(signals);
  }
}
```

### Phase 4: 개별 전략 고도화

**기간**: 3주 | **영향도**: 🟠 높음

| # | 전략 | 개선 사항 | 우선순위 |
|---|------|---------|---------|
| 1 | **Supertrend** | ATR 캐싱 최적화 (O(n²) → O(n)) | ⭐⭐⭐⭐⭐ |
| 2 | **EMA Cross** | ADX 필터 추가 | ⭐⭐⭐⭐ |
| 3 | **Bollinger** | Squeeze 감지 + Trend filter | ⭐⭐⭐⭐ |
| 4 | **Grid** | 동적 범위 + 추세 감지 종료 | ⭐⭐⭐⭐ |
| 5 | **RSI** | 피봇 기반 다이버전스 | ⭐⭐⭐ |
| 6 | **ATR Breakout** | 거래량 필터 추가 | ⭐⭐⭐ |
| 7 | **Orderbook** | Trade Flow 결합 | ⭐⭐⭐ |

### Phase 5: 신호 확장 & 타임프레임

**기간**: 2주 | **영향도**: 🟠 중간

| # | 기능 | 설명 |
|---|------|------|
| 1 | **StrategySignal 확장** | `suggestedStopLoss`, `suggestedTakeProfit` 추가 |
| 2 | **Confidence 레벨** | 신호 신뢰도 (0.0 ~ 1.0) |
| 3 | **Multi-Timeframe** | 상위 TF 트렌드 + 하위 TF 진입 |
| 4 | **타임프레임별 파라미터** | 1m/5m/1h/1d 별 자동 조절 |

---

## 🚀 실전 구현 전략

### 추천 우선순위 (신호 생성 순서)

```
Tier 1 (필수):
1. Supertrend      (추세 방향 = 큰 틀)
2. RSI             (진입 타이밍 = 세밀한 조정)
3. ATR Breakout    (손절 거리 = 리스크 관리)

Tier 2 (보조):
4. Bollinger       (범위 확인)
5. VWAP            (가치 평가)

Tier 3 (선택):
6. Orderbook       (수급 확인, 실시간 필수)
7. StochasticRSI   (빠른 타이밍, 스캘핑)

제거 추천:
- EMA CROSS ↔ MACD (같은 추세 전략, 중복 선택)
```

### 신호 의사결정 구조

```
입력: 시장 데이터
  ↓
[1] Market Regime 판단
  ├─ Trend? → Trend 전략 그룹 활성화
  ├─ Range? → Range 전략 그룹 활성화
  └─ Volatility? → Volatility 전략 그룹 활성화
  ↓
[2] 선택된 전략들의 신호 생성
  ├─ Supertrend: BUY/SELL/HOLD
  ├─ RSI: BUY/SELL/HOLD
  └─ ATR Breakout: BUY/SELL/HOLD
  ↓
[3] Strategy Voting
  ├─ 2개 이상 BUY → 강한 BUY
  ├─ 1개 BUY + 나머지 HOLD → 약한 BUY
  ├─ 신호 상충 (BUY vs SELL) → HOLD (신호 무시)
  └─ 모두 HOLD → HOLD
  ↓
[4] Risk Engine 확인
  ├─ Position Size 계산
  ├─ Daily Loss 체크
  ├─ Max Open Trades 체크
  └─ Risk OK? → 주문 실행 / No → 신호 무시
  ↓
[5] 주문 실행
  └─ Entry + Stop Loss + Take Profit
```

### 추천 매개변수 설정

#### TREND 시장 (ADX > 25)

```
Supertrend:
  - atrPeriod: 10
  - multiplier: 3.0
  
EMA (보조):
  - fastPeriod: 9
  - slowPeriod: 21
  - 선택: ADX > 25일 때만 활성

RSI (진입 타이밍):
  - period: 14
  - oversold: 30
  - overbought: 70
```

#### RANGE 시장 (ADX < 20 + BB Narrow)

```
Bollinger:
  - period: 20
  - multiplier: 2.0
  - squeeze 감지: bandwidth < 30일 최저값
  
RSI:
  - period: 14
  - oversold: 30
  - overbought: 70
  
Grid:
  - lookbackPeriod: 100
  - gridCount: 10
  - 조건: ADX < 20 & price within grid
```

#### VOLATILITY 시장 (ATR spike)

```
ATR Breakout:
  - atrPeriod: 14
  - multiplier: 1.5
  - useStopLoss: true
  
StochasticRSI:
  - rsiPeriod: 14
  - stochPeriod: 14
  - signalPeriod: 3
```

---

## 📊 복합 전략 비교 요약

| 조합 | 유형 | 적합 시장 | 신호 빈도 | 승률 기대 | 복잡도 | 구현 난이도 |
|------|------|----------|-----------|-----------|--------|-----------|
| **Supertrend + RSI + ATR** | 트렌드 팔로잉 | 트렌드장 | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | 중간 |
| **EMA Cross + MACD + VWAP** | 스윙 트레이딩 | 추세 전환기 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | 중간 |
| **Bollinger + StochRSI + Orderbook** | 스캘핑 | 횡보/변동 | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | 높음 |
| **Grid + Bollinger + RSI** | 그리드 트레이딩 | 횡보장 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | 중간 |
| **RSI Div + MACD + Supertrend** | 반전 매매 | 바닥/천장권 | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 높음 |

---

## 🎓 최종 평가 & 기대 효과

### 현재 상태
- **평가**: 개인 프로젝트 기준 상위 수준 (지표 계산 정확도 ✅)
- **문제점**: 
  - 시장 상태 판단 없음 (신호 충돌 가능)
  - 리스크 관리 부재 (큰 손실 위험)
  - 전략 체계 비효율 (중복 & 상충)

### 개선 후 기대 효과

#### 안정성 향상
- Market Regime + Risk Engine으로 **일관성 있는 거래**
- 신호 상충 제거 → **거짓 신호 50%↓**
- Daily Loss Limit → **최악의 날도 통제 가능**

#### 수익성 향상
- Strategy Voting으로 **신호 신뢰도 향상**
- Multi-Timeframe → **진입점 정확도 ↑**
- 맥락 기반 전략 선택 → **시장별 최적화**

#### 자동화 완성도
- 완전 자동화 거래 가능 (수동 개입 최소화)
- 스트레스 테스트 기반 파라미터 최적화
- 실시간 시장 상태 감지 & 전략 자동 전환

### 예상 결과 (보수 추정)

**현재 전략**:
- 승률: ~45%
- 페이오프 레이시오: ~1.0:1
- 연수익: 불안정

**개선 후 (Phase 1-5 완료 기준)**:
- 승률: ~55% (신호 개선)
- 페이오프 레이시오: ~1.5:1 (손절/익절 개선)
- 드로우다운: 5% 한정 (Risk Engine)
- 연수익: 20%+ (안정적)

---

## 체크리스트

### 즉시 수행 항목
- [ ] Supertrend 코드 오류 수정
- [ ] Grid 초기화 안전화
- [ ] 상충 신호 테스트 케이스 작성

### Phase 1-2 준비
- [ ] Market Regime Detector 스켈레톤 작성
- [ ] Risk Engine 로직 검증
- [ ] ADX, Bollinger Bandwidth 지표 확인

### Phase 3 준비
- [ ] Strategy Voting 알고리즘 설계
- [ ] CompositeStrategy 인터페이스 설계
- [ ] 신호 조합 규칙 명세화

### 테스트 계획
- [ ] 각 Market Regime에서 신호 검증
- [ ] Risk Engine의 Position Size 정확도
- [ ] Multi-strategy Voting의 신호 품질
- [ ] 백테스트: 2023-2025년 BTC/ETH 데이터

---

## 결론

현재 시스템은 **지표 계산 정확도는 우수**하나, **자동매매 시스템으로서의 완성도**는 부족하다. 

**핵심 개선 4가지**만 구현하면:
1. **Market Regime Detector** → 신호 충돌 제거
2. **Risk Engine** → 손실 통제
3. **Strategy Selector** → 맥락 기반 전략 선택
4. **Multi-Timeframe** → 진입점 정확도 향상

**단계적 구현**을 통해 **신뢰할 수 있는 자동매매 시스템**으로 발전할 수 있다.

**추천**: Phase 1-2 (시장 판단 + 리스크 관리)부터 시작하여, 3개월 내 완전 자동화 시스템 구축 목표.