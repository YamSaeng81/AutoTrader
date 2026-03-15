# 📊 암호화폐 매매 전략 분석 & 복합 전략 추천

> **분석 대상**: `strategy-lib/src/main/java/com/cryptoautotrader/strategy/` 내 10개 전략
> **분석일**: 2026-03-15

---

## 📋 전략 목록 & 분류

| # | 전략명 | 유형 | 주요 지표 | 시장 적합도 |
|---|--------|------|-----------|-------------|
| 1 | **VWAP** | 역추세(평균 회귀) | 거래량 가중 평균가 | 횡보장 |
| 2 | **EMA_CROSS** | 추세 추종 | EMA(9), EMA(21) | 트렌드장 |
| 3 | **BOLLINGER** | 역추세(평균 회귀) | SMA + 표준편차 밴드 | 횡보/변동장 |
| 4 | **GRID** | 범위 매매 | 가격 격자 분할 | 횡보장 |
| 5 | **RSI** | 모멘텀/타이밍 | RSI + 다이버전스 | 모든 시장 |
| 6 | **MACD** | 추세/모멘텀 | MACD/Signal 크로스 | 트렌드장 |
| 7 | **SUPERTREND** | 추세 추종 | ATR 기반 동적 밴드 | 트렌드장 |
| 8 | **ATR_BREAKOUT** | 변동성 돌파 | ATR × 배수 | 모멘텀장 |
| 9 | **ORDERBOOK_IMBALANCE** | 호가 분석 | 매수/매도 볼륨 비율 | 단기 스캘핑 |
| 10 | **STOCHASTIC_RSI** | 모멘텀/타이밍 | StochRSI %K/%D | 변동장/횡보장 |

---

## 🔍 개별 전략 상세 분석

---

### 1. VWAP (거래량 가중 평균가 역추세)

**📁 파일**: [VwapStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/vwap/VwapStrategy.java)

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

**📁 파일**: [EmaCrossStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java)

**원리**: 단기 EMA(9)가 장기 EMA(21)를 상향/하향 돌파 시 BUY/SELL

**파라미터**:
- `fastPeriod`: 단기 EMA (기본 9)
- `slowPeriod`: 장기 EMA (기본 21)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **횡보장 거짓 신호(Whipsaw)** | EMA 크로스만 사용하면 횡보 구간에서 빈번한 거짓 신호 발생. 필터링 메커니즘이 전혀 없음 |
| 🟡 중간 | **크로스 이외 HOLD만 반환** | 추세 유지 중에도 약한 BUY/SELL 유지 신호를 줄 수 있으나, 현재는 오직 크로스 시점에만 신호 |
| 🟡 중간 | **신호 강도 계산 방식** | `gap / slowEma × 1000`이 의미 있는 범위(0~100)를 보장하지 않음. 극단적 값에서 clamp됨 |

#### 💡 개선 제안

1. **ADX 필터 추가**: ADX > 25일 때만 크로스 신호 유효화 (추세 강도 확인)
2. **EMA 3선 구조**: EMA(5), EMA(13), EMA(34) 같은 3중 크로스로 정확도 향상
3. **지연 확인(Confirmation)**: 크로스 후 N개 캔들 유지 확인 옵션
4. **각도(기울기) 필터**: EMA 기울기가 일정 이상일 때만 신호 유효화

---

### 3. Bollinger Bands (볼린저 밴드)

**📁 파일**: [BollingerStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerStrategy.java)

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

**📁 파일**: [GridStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java)

**원리**: lookback 기간의 고저가를 N등분하고, 하위 30% 근접 시 BUY, 상위 30% 근접 시 SELL

**파라미터**:
- `lookbackPeriod`: 범위 산정 기간 (기본 100)
- `gridCount`: 그리드 수 (기본 10)
- `triggerPct`: 레벨 근접 트리거 (기본 0.5%)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **가격 범위 이탈 대응 없음** | 가격이 lookback 고점/저점을 돌파하면 그리드 자체가 무효화됨. 범위 재설정 로직 없음 |
| 🔴 높음 | **하드코딩된 초기값** | `lowest = 999999999999` — 실제로는 `BigDecimal.valueOf(Long.MAX_VALUE)` 또는 첫 데이터로 초기화해야 안전 |
| 🟡 중간 | **상태 미보존** | 어떤 그리드에서 이미 매수했는지 추적하지 않음. 동일 레벨에서 중복 매매 발생 가능 |
| 🟡 중간 | **중간 구간 활용 없음** | 30%~70% 범위에서는 항상 HOLD → 신호 발생 빈도가 매우 낮음 |

#### 💡 개선 제안

1. **동적 그리드 범위**: ATR 또는 Bollinger Band 기반으로 그리드 범위 자동 조절
2. **주문 상태 추적**: 각 그리드 레벨에서의 진입/청산 이력 관리 (Strategy 인터페이스 확장 필요)
3. **기하급수 그리드**: 등간격 대신 로그 스케일 그리드로 저가 영역에서 더 촘촘하게 배치
4. **범위 돌파 감지**: 그리드 범위 이탈 시 전략 일시 중단 또는 범위 재설정

---

### 5. RSI (상대강도지수)

**📁 파일**: [RsiStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java)

**원리**: RSI < 30 → BUY(과매도), RSI > 70 → SELL(과매수). 다이버전스 감지 옵션 포함

**파라미터**:
- `period`: RSI 기간 (기본 14)
- `oversoldLevel` / `overboughtLevel`: 과매도/과매수 기준 (기본 30/70)
- `useDivergence`: 다이버전스 감지 활성화 (기본 true)
- `divergenceLookback`: 다이버전스 검출 룩백 (기본 5)

#### 👍 잘 구현된 부분
- **Wilder's Smoothing** 정확히 구현
- **다이버전스 감지**가 포함되어 있어 단순 과매수/과매도보다 정교함
- 다이버전스 조건에 RSI 영역 필터(`oversold + 10`, `overbought - 10`)가 있어 정확도 향상

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

**📁 파일**: [MacdStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java)

**원리**: MACD선이 Signal선을 상향/하향 돌파 시 BUY/SELL (골든/데드 크로스)

**파라미터**:
- `fastPeriod`: 단기 EMA (기본 12)
- `slowPeriod`: 장기 EMA (기본 26)
- `signalPeriod`: 시그널 EMA (기본 9)

#### 👍 잘 구현된 부분
- **전체 시계열 순차 스캔**으로 정확한 EMA 계산 (마지막 점만 계산하지 않음)
- **이전 시점 MACD**도 계산하여 크로스 정확히 감지
- 히스토그램 방향 정보 제공

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **성능 이슈** | `calculateMacd`를 현재/이전 시점에 대해 2번 호출, 이전 시점은 subList로 재계산. 비효율적 |
| 🟡 중간 | **제로라인 크로스 미활용** | MACD선이 0선을 상향/하향 돌파하는 것도 중요한 신호이나 미구현 |
| 🟡 중간 | **히스토그램 방향 전환 미감지** | 히스토그램이 축소에서 확대로 전환되는 시점 = 초기 모멘텀 전환 → 미감지 |
| 🟢 낮음 | **신호 강도 계산** | `histogram / signal × 1000`은 Signal이 0에 가까울 때 과도한 값 산출 |

#### 💡 개선 제안

1. **히스토그램 무빙 에버리지(Histogram MA)**: 히스토그램 기울기 변화 감지
2. **제로라인 크로스**: MACD 0선 돌파를 추세 확인 신호로 추가
3. **계산 최적화**: 이전/현재 MACD를 한 번의 순회로 계산
4. **MACD 다이버전스**: 가격과 MACD 다이버전스 감지 (RSI 다이버전스와 유사)

---

### 7. Supertrend (슈퍼트렌드)

**📁 파일**: [SupertrendStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/supertrend/SupertrendStrategy.java)

**원리**: ATR 기반 동적 지지/저항 밴드를 계산하고, 가격 위치로 추세 판단

**파라미터**:
- `atrPeriod`: ATR 기간 (기본 10)
- `multiplier`: ATR 배수 (기본 3.0)

#### 👍 잘 구현된 부분
- **밴드 조정 로직** 정확히 구현 (이전 밴드보다 후퇴하지 않도록)
- **추세 전환 감지** (크로스) 구현으로 강한 신호 별도 처리
- 전체 시계열 순차 스캔

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🔴 높음 | **성능 — 매 캔들마다 전체 ATR 재계산** | `calculateSupertrend` 내 루프에서 매 인덱스마다 `IndicatorUtils.atr(subCandles)` 호출 → O(n²) 복잡도 |
| 🟡 중간 | **신호 강도 변수 무용** | Line 62: `band = currentUptrend ? result.supertrendLine : result.supertrendLine` — 삼항연산의 양쪽이 동일하여 의미 없음 (코드 오류) |
| 🟡 중간 | **추세 유지 시 신호** | 추세 유지 중 BUY/SELL을 반환하면 포지션 관리에 혼선. HOLD 또는 별도 Action 필요 |

#### 💡 개선 제안

1. **ATR 캐싱 최적화**: 각 캔들의 ATR을 사전 계산하여 O(n) 복잡도로 개선
2. **Line 62 버그 수정**: `band = currentUptrend ? result.supertrendLine(lower) : result.supertrendLine(upper)` 분리
3. **ADX 결합**: Supertrend + ADX로 추세 강도까지 확인
4. **다중 타임프레임**: 상위 타임프레임 Supertrend로 방향 확인, 하위에서 진입 타이밍

---

### 8. ATR Breakout (변동성 돌파)

**📁 파일**: [AtrBreakoutStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java)

**원리**: 현재 캔들 시가 ± ATR×multiplier 돌파 시 BUY/SELL (래리 윌리엄스 변동성 돌파 변형)

**파라미터**:
- `atrPeriod`: ATR 기간 (기본 14)
- `multiplier`: 돌파 배수 (기본 1.5)
- `useStopLoss`: 하방 돌파 시 매도 여부 (기본 true)

#### 👍 잘 구현된 부분
- 손절 옵션(`useStopLoss`) 별도 설정 가능
- 비돌파 구간에서도 위치 정보 제공 (`rangePosition`)

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **시가 기준 돌파** | 현재 캔들의 시가 기준이므로, 일봉이 아닌 분봉에서는 시가가 자주 바뀌어 신호 불안정 |
| 🟡 중간 | **거짓 돌파(False Breakout) 필터 없음** | 일시적 급등·낙으로 돌파 후 복귀하는 패턴 미감지 |
| 🟡 중간 | **거래량 확인 없음** | 변동성 돌파의 신뢰도는 거래량 동반 여부에 크게 좌우됨 |

#### 💡 개선 제안

1. **이전 캔들 시가 + 전일 ATR**: 래리 윌리엄스 원본처럼 이전일 ATR × K값 사용
2. **거래량 확인 필터**: 돌파 시 평균 거래량 대비 N배 이상일 때만 유효
3. **돌파 유지 확인**: 캔들 종가 기준 돌파 확인 (고가/저가 일시적 터치 제외)
4. **시간대 필터**: 특정 시간대(예: 아시아 세션 시작) 돌파만 유효화

---

### 9. Orderbook Imbalance (호가 불균형)

**📁 파일**: [OrderbookImbalanceStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/orderbook/OrderbookImbalanceStrategy.java)

**원리**: 매수/매도 호가 볼륨 비율이 임계값 이상이면 우세 방향으로 신호 생성
- **실시간 모드**: WebSocket 호가 데이터 사용
- **백테스트 모드**: 캔들 데이터로 매수/매도 압력 근사

**파라미터**:
- `imbalanceThreshold`: 불균형 임계값 (기본 0.65)
- `lookback`: 캔들 근사 기간 (기본 5)
- `depthLevels`: 호가 단계 수 (기본 10, 설정에만 존재)

#### 👍 잘 구현된 부분
- 실시간/백테스트 이중 모드 설계
- 캔들 기반 볼륨 분해 공식이 합리적 (`close - low / high - low + ε`)
- EPSILON 처리로 0 나눗셈 방지

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **호가 스푸핑 미감지** | 대형 호가가 실행 직전 취소되는 스푸핑/레이어링을 구분할 수 없음 |
| 🟡 중간 | **`depthLevels` 미사용** | Config에 정의되어 있으나 Strategy에서 활용하지 않음 |
| 🟢 낮음 | **캔들 근사치 신뢰도** | 캔들 기반 근사는 실제 호가 불균형과 상관관계가 낮을 수 있음 (주석에도 언급) |

#### 💡 개선 제안

1. **호가 깊이별 가중치**: 가까운 호가에 높은 가중치 부여 (exp decay)
2. **불균형 변화율**: 절대 불균형보다 불균형이 급변하는 시점이 더 유의미
3. **depthLevels 활용**: 호가 N단계까지만 불균형 계산하도록 구현
4. **호가 벽(Wall) 감지**: 특정 레벨에 대형 주문 존재 시 지지/저항으로 활용

---

### 10. Stochastic RSI

**📁 파일**: [StochasticRsiStrategy.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/stochasticrsi/StochasticRsiStrategy.java)

**원리**: RSI에 Stochastic 공식 적용 → %K가 과매도 탈출 + %K > %D → BUY, 반대 → SELL

**파라미터**:
- `rsiPeriod`: RSI 기간 (기본 14)
- `stochPeriod`: Stochastic 기간 (기본 14)
- `signalPeriod`: %D 이동평균 기간 (기본 3)
- `oversoldLevel` / `overboughtLevel`: 과매도/과매수 (기본 20/80)

#### 👍 잘 구현된 부분
- **RSI 시계열 전체 계산**으로 정확한 Stochastic 적용
- **%K/%D 크로스 + 영역 탈출** 이중 조건으로 거짓 신호 감소
- Javadoc이 매우 상세하고 명확

#### ⚠️ 발견된 문제점

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **%K Smoothing 미적용** | 일반적으로 Raw %K에 SMA(3)을 적용한 Smooth %K를 사용하나, 현재는 Raw %K 사용 |
| 🟢 낮음 | **Config에 Lombok 미사용** | 다른 Config 클래스는 `@Getter @Setter` 사용, `StochasticRsiConfig`만 수동 getter/setter |
| 🟢 낮음 | **중립 영역 신호** | 20~80 사이에서는 항상 HOLD → 신호 발생 빈도 낮음 |

#### 💡 개선 제안

1. **Smooth %K**: Raw %K에 SMA(3) 적용하여 노이즈 감소
2. **%K/%D 크로스만 별도 신호**: 영역 제한 없이 %K > %D 크로스만으로도 약한 신호 생성
3. **Lombok 일관성**: `@Getter @Setter` 적용으로 코드 일관성 확보

---

## 🏗️ 공통 인프라 분석

### IndicatorUtils

**📁 파일**: [IndicatorUtils.java](file:///D:/Claude%20Code/projects/crypto-auto-trader/strategy-lib/src/main/java/com/cryptoautotrader/strategy/IndicatorUtils.java)

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **표준편차 — 모표준편차 사용** | `variance = sumSq / period` → 모분산. 표본분산을 쓰려면 `/ (period - 1)`. TradingView 등은 모분산 사용하므로 의도적일 수 있음 |
| 🟡 중간 | **표준편차 — double 변환** | `Math.sqrt(variance.doubleValue())` 사용 → BigDecimal 정밀도 손실. Newton's method 등으로 BigDecimal 자체 sqrt 구현 가능 |
| 🟡 중간 | **파라미터 헬퍼 중복** | `getInt`, `getDouble`, `getBoolean`이 거의 모든 전략에서 중복 정의됨 → IndicatorUtils 또는 별도 유틸리티로 통합 필요 |

### Strategy 인터페이스

| 심각도 | 문제 | 설명 |
|--------|------|------|
| 🟡 중간 | **상태 관리 불가** | 순수 함수형 인터페이스라 이전 신호 이력, 포지션 상태 등 관리 불가 |
| 🟡 중간 | **복합 전략 지원 없음** | 여러 전략의 결과를 조합하는 `CompositeStrategy` 패턴이 인터페이스 레벨에서 지원되지 않음 |

---

## 🔧 전체 공통 개선 사항

### 코드 품질

| # | 개선 사항 | 영향 범위 |
|---|----------|-----------|
| 1 | **파라미터 헬퍼 통합** — `getInt/getDouble/getBoolean`이 7개 전략에서 중복 정의 → 공통 유틸리티로 추출 | 전 전략 |
| 2 | **Config `fromParams` 일관성** — VWAP, EMA, Bollinger, Grid만 `fromParams` 메서드가 있고 나머지는 없음 | 6개 전략 |
| 3 | **StochasticRsiConfig Lombok** — 유일하게 수동 getter/setter 사용 | StochasticRSI |
| 4 | **예외 메시지 표준화** — 일부는 한국어, 일부는 "데이터 부족"만 사용 → 통일 | 전 전략 |

### 기능 확장

| # | 개선 사항 | 설명 |
|---|----------|------|
| 1 | **CompositeStrategy 클래스** | 여러 Strategy를 조합하여 신호를 합산/투표하는 복합 전략 지원 |
| 2 | **Strategy 상태 관리** | 이전 신호 이력, 포지션 보유 상태를 참조할 수 있는 `StatefulStrategy` 인터페이스 |
| 3 | **타임프레임 인식** | 선택된 캔들 주기(1m, 5m, 1h, 1d)에 따라 파라미터 자동 조절 |
| 4 | **Risk/Reward 정보** | 신호에 추천 손절/익절 가격 포함 (현재는 BUY/SELL/HOLD만) |
| 5 | **신호 신뢰도(Confidence)** | `strength` 외에 시장 상황 대비 신호의 신뢰도 수치 별도 제공 |

---

## 🎯 복합 매매 전략 추천

### 전략 조합 1: 🏆 **추세 확인 + 타이밍 + 손절** (추천 1순위)

```
SUPERTREND  → 추세 방향 (큰 그림)
RSI         → 진입 타이밍 (과매도/과매수)
ATR_BREAKOUT → 리스크 관리 (동적 손절선)
```

| 역할 | 전략 | 설명 |
|------|------|------|
| **추세 필터** | SUPERTREND | 상승 추세일 때만 매수 신호 허용, 하락일 때만 매도 허용 |
| **진입 타이밍** | RSI | 과매도(RSI<30)에서 매수, 과매수(RSI>70)에서 매도 |
| **손절/확인** | ATR_BREAKOUT | ATR 기반 동적 손절선 제공, 변동성 돌파로 추가 확인 |

**동작 시나리오**:
1. Supertrend = 상승 추세 ✅
2. RSI < 30 (과매도 매수 신호) ✅  
3. → **강한 BUY** (추세 방향 + 과매도 반등)
4. ATR Breakout 하방 돌파 시 → **강제 SELL** (손절)

**적합 시장**: 트렌드장, 중장기 투자

---

### 전략 조합 2: 📈 **이중 추세 확인 + 모멘텀**

```
EMA_CROSS   → 추세 방향 (단기)
MACD        → 추세 모멘텀 확인
VWAP        → 가치 평가 필터
```

| 역할 | 전략 | 설명 |
|------|------|------|
| **추세 방향** | EMA_CROSS | 골든크로스/데드크로스로 추세 전환 포착 |
| **모멘텀 확인** | MACD | EMA 크로스와 MACD 크로스 동시 발생 = 강한 확인 |
| **가치 필터** | VWAP | VWAP 아래에서만 매수 (합리적 가격 확인) |

**동작 시나리오**:
1. EMA 골든크로스 발생 ✅
2. MACD 골든크로스 동시/근접 발생 ✅
3. 현재가 < VWAP ✅  
4. → **강한 BUY** (추세 전환 + 모멘텀 확인 + 할인된 가격)

**적합 시장**: 추세 전환 초기, 스윙 트레이딩

---

### 전략 조합 3: ⚡ **변동성 + 타이밍 + 호가** (단기 스캘핑)

```
BOLLINGER           → 변동성 밴드 (범위 확인)
STOCHASTIC_RSI      → 민감한 타이밍 (빠른 진입/청산)
ORDERBOOK_IMBALANCE → 수급 확인 (실시간 매수/매도 압력)
```

| 역할 | 전략 | 설명 |
|------|------|------|
| **범위 확인** | BOLLINGER | 하단 밴드 근처에서만 매수, 상단에서만 매도 |
| **빠른 타이밍** | STOCHASTIC_RSI | 과매도 탈출 + %K>%D로 정확한 진입점 |
| **수급 확인** | ORDERBOOK | 매수 압력 우세일 때만 매수 신호 유효화 |

**동작 시나리오**:
1. 가격이 Bollinger 하단 밴드 근처 ✅
2. Stochastic RSI 과매도 탈출 ✅
3. Orderbook 매수 압력 우세 (≥65%) ✅
4. → **강한 BUY** (밴드 하단 반등 + 타이밍 + 수급 지지)

**적합 시장**: 횡보/변동장, 단기 스캘핑 (1m~15m)

---

### 전략 조합 4: 🏗️ **레인지 매매 + 안전장치** (횡보장 전용)

```
GRID        → 분할 매수/매도 (격자 레벨)
BOLLINGER   → 범위 확인 & 스퀴즈 감지
RSI         → 과매수/과매도 필터
```

| 역할 | 전략 | 설명 |
|------|------|------|
| **분할 매매** | GRID | 설정된 격자 레벨에서 자동 매수/매도 |
| **범위 확인** | BOLLINGER | 밴드 내에서만 그리드 활성, 밴드 돌파 시 전략 중단 |
| **안전장치** | RSI | RSI<30 매수 확인, RSI>70 매도 확인으로 타이밍 보완 |

**적합 시장**: 명확한 횡보/레인지장

---

### 전략 조합 5: 💎 **다이버전스 스나이퍼** (추세 반전 포착)

```
RSI (다이버전스)    → 추세 반전 신호
SUPERTREND         → 추세 전환 확인
MACD (히스토그램)   → 모멘텀 방향 확인
```

| 역할 | 전략 | 설명 |
|------|------|------|
| **선행 신호** | RSI 다이버전스 | 가격 신저점 + RSI 상승 = 반전 조기 감지 |
| **확인 신호** | SUPERTREND | 추세 전환(상승←하락) 크로스가 발생하면 확인 |
| **모멘텀** | MACD | 히스토그램 방향 전환으로 모멘텀 변화 확인 |

**동작 시나리오**:
1. RSI 강세 다이버전스 감지 (가격↓ RSI↑) ✅
2. MACD 히스토그램 축소 → 확대 전환 ✅
3. Supertrend 상승 전환 크로스 ✅
4. → **최강 BUY** (다이버전스 + 모멘텀 전환 + 추세 전환 3중 확인)

**적합 시장**: 하락 추세 바닥권, 중장기 투자

---

## 📊 복합 전략 비교 요약

| 조합 | 유형 | 적합 시장 | 신호 빈도 | 승률 기대 | 복잡도 |
|------|------|-----------|-----------|-----------|--------|
| **1. 추세+타이밍+손절** | 트렌드 팔로잉 | 트렌드장 | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ |
| **2. 이중 추세+모멘텀** | 스윙 트레이딩 | 추세 전환기 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| **3. 변동성+타이밍+호가** | 스캘핑 | 횡보/변동 | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| **4. 레인지+안전장치** | 그리드 트레이딩 | 횡보장 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| **5. 다이버전스 스나이퍼** | 반전 매매 | 바닥/천장권 | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |

---

## 🚀 구현 우선순위 제안

### Phase 1: 즉시 수정 (버그)
1. `SupertrendStrategy` Line 62 — 삼항연산 양쪽 동일 값 수정
2. `GridStrategy` — 하드코딩된 `999999999999` 제거

### Phase 2: 공통 리팩토링
1. 파라미터 헬퍼(`getInt/getDouble/getBoolean`) 공통 유틸리티 추출
2. Config 클래스 `fromParams` 일관성 확보
3. `StochasticRsiConfig`에 Lombok 적용

### Phase 3: CompositeStrategy 구현
1. `CompositeStrategy` 클래스 — 다중 전략 신호 조합 엔진
2. 가중치 기반 투표(Weighted Voting) 시스템
3. 시장 상태(트렌드/횡보/변동) 감지 → 자동 조합 선택

### Phase 4: 개별 전략 고도화
1. EMA Cross에 ADX 필터 추가
2. Bollinger에 스퀴즈 감지 추가
3. ATR Breakout에 거래량 확인 추가
4. Supertrend ATR 캐싱 최적화

### Phase 5: 신호 확장
1. StrategySignal에 `suggestedStopLoss`, `suggestedTakeProfit` 추가
2. 신호 신뢰도(Confidence) 레벨 추가
3. 타임프레임별 파라미터 자동 조절
