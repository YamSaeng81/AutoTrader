# Sharpe / Sortino / Calmar 계산 정밀 감사 — 2026-04-15

> **결론 먼저**: 버그 **확정**. 세 지표 모두 체계적으로 과대계산되고 있으며, `PROGRESS.md` 의 Sharpe·Calmar 수치는 **전략 선택 근거로 사용해서는 안 되는 상태**다. 오차 배율은 전략의 거래 빈도에 반비례하며, 현재 H1 Composite 전략들에서는 **Sharpe 약 4~5배, Calmar 약 18~23배 과대계산** 된다.

---

## 1. 감사 대상 코드

파일: [core-engine/src/main/java/com/cryptoautotrader/core/metrics/MetricsCalculator.java](../core-engine/src/main/java/com/cryptoautotrader/core/metrics/MetricsCalculator.java)

### 1.1. 핵심 상수와 리턴 리스트 구성

```java
// line 19
private static final BigDecimal ANNUALIZATION_FACTOR = BigDecimal.valueOf(Math.sqrt(365));

// line 143~149 — 메서드 이름과 내용이 불일치
private static List<BigDecimal> calculateDailyReturns(List<TradeRecord> sellTrades, BigDecimal initialCapital) {
    List<BigDecimal> returns = new ArrayList<>();
    for (TradeRecord t : sellTrades) {
        returns.add(t.getPnl().divide(initialCapital, SCALE, RoundingMode.HALF_UP));
    }
    return returns;
}
```

- 이름은 `calculateDailyReturns` 이지만 실제로는 **"매도 거래 1건당 수익률(= pnl / 초기자본)"** 목록이다.
- 리스트 길이 = 매도 거래 수. 3년 H1 Composite 전략이면 전형적으로 40~70 entries.
- **"일별"이라는 명칭이 붙은 유일한 근거는 메서드 이름뿐**이며, 내용에는 시간·날짜·일자 개념이 전혀 들어가지 않는다.

### 1.2. Sharpe 계산

```java
// line 151~164
private static BigDecimal calculateSharpe(List<BigDecimal> returns) {
    if (returns.size() < 2) return BigDecimal.ZERO;
    BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
    BigDecimal variance = returns.stream()
            .map(r -> r.subtract(mean).pow(2))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
    double stdDev = Math.sqrt(variance.doubleValue());
    if (stdDev == 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf(mean.doubleValue() / stdDev)
            .multiply(ANNUALIZATION_FACTOR)   // × sqrt(365)
            .setScale(SCALE, RoundingMode.HALF_UP);
}
```

- `Sharpe_code = (mean_per_trade / std_per_trade) × sqrt(365)`

### 1.3. Sortino — 같은 버그 + 추가 문제

```java
// line 166~180
BigDecimal downsideVariance = returns.stream()
        .filter(r -> r.compareTo(BigDecimal.ZERO) < 0)
        .map(r -> r.pow(2))     // ← 주의: (r - mean)² 가 아니라 r²
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP);
double downsideDev = Math.sqrt(downsideVariance.doubleValue());
// ...
return BigDecimal.valueOf(mean.doubleValue() / downsideDev)
        .multiply(ANNUALIZATION_FACTOR)
        .setScale(SCALE, RoundingMode.HALF_UP);
```

- Sharpe 와 같은 annualization 오류.
- **추가 문제**: `r.pow(2)` — 편차(`r - MAR`) 의 제곱이 아니라 **수익률 값 자체의 제곱**. Sortino 표준 정의는 `downside_risk = sqrt(E[min(r - MAR, 0)²])`. MAR=0 가정 하에서는 손실 구간만 필터링하므로 결과가 같아야 하지만, **분모가 `size()` 이고 `downsideVariance` 는 음수만 합산**한다 → 손실 표본의 분산을 전체 표본 크기로 나눈 형태로 **Sortino 가 정의상 "부분 평균"이 된다**. 이 방식은 Sortino 원본 정의와도, 실무 컨벤션과도 일치하지 않는다.

### 1.4. Calmar — **또 다른 별개의 버그**

```java
// line 89~95
BigDecimal meanTradeReturn = dailyReturns.isEmpty() ? BigDecimal.ZERO
        : dailyReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), SCALE, RoundingMode.HALF_UP);
BigDecimal annualizedReturnPct = meanTradeReturn.multiply(BigDecimal.valueOf(365)).multiply(HUNDRED);
BigDecimal calmarRatio = mddPct.compareTo(BigDecimal.ZERO) == 0
        ? BigDecimal.ZERO
        : annualizedReturnPct.divide(mddPct.abs(), SCALE, RoundingMode.HALF_UP);
```

- `meanTradeReturn × 365` — 거래당 수익률을 **365배** 해서 "연환산" 이라 부른다.
- 하루에 1거래, 1년에 365거래를 가정한 것인데, 실제로는 BTC BREAKOUT 20거래/년, SOL 15거래/년 수준이다.
- 비율 오차: `365 / trades_per_year` → BTC 의 경우 **약 18배 과대**.
- 또한 "mean × N" 은 단순합산이지 복리연환산이 아니다. 진짜 연환산은 `(1 + total_return)^(1/years) - 1`.

---

## 2. 수학적 재유도

### 2.1. 올바른 Sharpe annualization

주기 수익률 `r_t` 가 IID 라고 가정할 때, 기간 `P` 내 N 개 주기가 있으면:
- `E[r_P] = N · E[r_t]`
- `Var[r_P] = N · Var[r_t]`
- `SD[r_P] = √N · SD[r_t]`
- `Sharpe_P = E[r_P] / SD[r_P] = √N · E[r_t] / SD[r_t] = √N · Sharpe_t`

**따라서 per-period Sharpe → annual 변환은 "연간 period 수" 의 제곱근을 곱한다.**

- 일일 수익률 → `√365` (암호화폐는 24/7 이므로 252 아님)
- 주간 수익률 → `√52`
- 거래당 수익률 → `√(trades_per_year)`

현재 코드는 **거래당 수익률에 `√365` 를 곱한다** = 잘못된 period 가정.

### 2.2. 오차 배율 공식

```
오차 배율 = sqrt(365 / trades_per_year)
```

| 전략 (3년) | 거래수 | 연간 거래수 | 오차 배율 √(365/N) |
|---|---|---|---|
| BTC BREAKOUT | 61 | 20.3 | **4.24×** |
| ETH BREAKOUT | 61 | 20.3 | 4.24× |
| SOL BREAKOUT | 46 | 15.3 | **4.88×** |
| XRP BREAKOUT | 47 | 15.7 | 4.83× |
| DOGE BREAKOUT | 49 | 16.3 | 4.73× |

### 2.3. Calmar 오차 배율

```
오차 배율(Calmar) = (365 × mean_per_trade) / (true_annualized_return)
           ≈ 365 / trades_per_year (단순 선형 근사)
```

- BTC BREAKOUT: `365 / 20.3 ≈ 17.98×`
- SOL BREAKOUT: `365 / 15.3 ≈ 23.86×`

---

## 3. 실제 PROGRESS.md 수치 재계산

### 3.1. 거래당 통계 역산

`Sharpe_code = (mean/std) × √365 = 6.41` 이고 `mean = total_return / trades` 가정 시:

**BTC COMPOSITE_BREAKOUT** (PROGRESS.md 기준: +104.24%, 61 거래, Sharpe 6.41)
- `mean_per_trade = 1.0424 / 61 = 0.01709` (= 1.709% / trade)
- `mean/std = 6.41 / √365 = 6.41 / 19.105 = 0.3355`
- `std_per_trade = 0.01709 / 0.3355 = 0.05094` (= 5.094% / trade)

검산: `(0.01709 / 0.05094) × √365 = 0.3355 × 19.105 = 6.409` ✓

### 3.2. 올바른 Sharpe 재계산

**방식 A — per-trade 기반 `√(trades/year)` 사용**
- BTC: `0.3355 × √20.3 = 0.3355 × 4.506 = 1.511`
- ETH: PROGRESS 3.16 → `3.16 / 4.24 = 0.745`
- SOL: PROGRESS 3.81 → `3.81 / 4.88 = 0.781`
- XRP: PROGRESS 0.85 → `0.85 / 4.83 = 0.176`
- DOGE: PROGRESS 1.84 → `1.84 / 4.73 = 0.389`

**방식 B — 진짜 일별 equity curve 기반 (더 보수적·표준적)**
- 1095일 중 거래 발생일 비율 `p = 61/1095 ≈ 5.57%`
- 비거래일 수익률 = 0 이므로 일별 평균은 낮고, 비거래일이 변동성 분모에 포함된다
- BTC 근사:
  - `E[r_daily] = p × 0.01709 = 0.000952` (= 0.0952% / day)
  - `Var[r_daily] ≈ p × (mean² + std²) - E[r_daily]² ≈ 0.0557 × (0.000292 + 0.002595) - 0.00000091 ≈ 0.0001609 - 0.00000091 ≈ 0.000160`
  - `SD[r_daily] ≈ 0.01266`
  - `Sharpe_daily = 0.000952 / 0.01266 = 0.0752`
  - `Sharpe_annual = 0.0752 × √365 = 0.0752 × 19.105 = 1.437`

두 방식 모두 **BTC Sharpe ≈ 1.44~1.51** 로 수렴. 코드가 보고한 **6.41 대비 약 4.3배 과대**.

### 3.3. 재계산 결과 테이블 (추정)

| 코인 | 코드 Sharpe | 배율 | 재계산 Sharpe | 의미 |
|---|---|---|---|---|
| BTC BREAKOUT | 6.41 | ÷4.24 | **~1.51** | 여전히 우수하지만 "unprecedented" → "양호" |
| ETH BREAKOUT | 3.16 | ÷4.24 | ~0.75 | **보통 수준**. 시장 평균 미만 가능성 |
| SOL BREAKOUT | 3.81 | ÷4.88 | ~0.78 | 보통 수준 |
| XRP BREAKOUT | 0.85 | ÷4.83 | **~0.18** | **경계값 이하**. 사실상 수익 대비 리스크 과도 |
| DOGE BREAKOUT | 1.84 | ÷4.73 | ~0.39 | 낮음 |

> ⚠ **주의**: 위 계산은 PROGRESS.md 의 수치를 역산한 것이다. 실제 재계산은 `MetricsCalculator` 를 올바르게 고친 후 다시 백테스트를 돌려야 정확한 값을 얻는다. 여기 숫자는 "오차 배율 순서" 의 크기 감각을 주는 용도.

### 3.4. Sharpe 6.41 이 애초에 불가능한 숫자였다는 sanity check

실세계 기관 벤치마크:
- Renaissance Medallion (업계 최고): 역사적 Sharpe ≈ 2.5
- Two Sigma: ~1.5
- Bridgewater Pure Alpha: ~0.9
- S&P500 buy-and-hold: ~0.4~0.6

**Sharpe 6.41 인 암호화폐 소매 H1 전략이 존재한다는 것은 물리적으로 무리** — 이 자체가 "숫자가 틀렸다"는 가장 강한 신호였다. 전략이 아무리 좋아도 리스크 조정 수익률이 세계 최고 헤지펀드의 2.5배일 수는 없다.

### 3.5. Calmar 예시 — 거의 의미 없는 값

- BTC: `meanTrade × 365 × 100 = 0.01709 × 365 × 100 = 623.7%` (코드가 보고하는 "연환산 수익률")
- 실제 연환산: `1.0424^(1/3) - 1 = 0.1395 = 13.95%`
- 코드 vs 실제 배율: `623.7 / 13.95 = 44.7×` (정확히 18× 는 아님 — 시간가치 복리 + `total_return ≠ n × mean_trade` 비선형성 때문)
- 코드 Calmar: `623.7 / 5.98 = 104.3`
- 올바른 Calmar: `13.95 / 5.98 = 2.33`
- **오차 배율 ≈ 45×**

Calmar 는 웹 UI 에 노출된다면 거의 전적으로 쓸모없는 숫자를 보여주고 있는 것.

---

## 4. 테스트가 이를 잡지 못하는 이유

[MetricsCalculatorTest.java](../core-engine/src/test/java/com/cryptoautotrader/core/metrics/MetricsCalculatorTest.java) 검토 결과:

- 테스트 4개 전체: `빈 리포트`, `승률 100`, `MDD 음수`, `연속 손실 카운트`
- **Sharpe / Sortino / Calmar 에 대한 assertion 이 단 하나도 없음**
- 즉 `ANNUALIZATION_FACTOR = √365` 를 실수로 `√1` 또는 `√100` 로 바꾸어도 현재 테스트는 전부 통과한다.

---

## 5. 올바른 구현 제안

### 5.1. 핵심 원칙

1. 수익률은 **시간 단위 equity curve** 에서 추출한다 (거래가 없는 날도 포함).
2. Annualization factor 는 period 에 맞춰 결정한다.
3. Calmar 는 CAGR (compound annual growth rate) / |MDD| 로 계산한다.
4. Sortino 는 표준 정의 `mean / √(E[min(r - MAR, 0)²])` 를 따른다.

### 5.2. 의사 코드

```java
// 1. 일별 equity 시계열 생성
// 입력: 매도 거래 리스트 + 백테스트 기간 (from, to)
List<BigDecimal> dailyEquity = buildDailyEquityCurve(trades, initialCapital, from, to);
// → length == 전체 일수 (예: 1095)

// 2. 일별 로그 수익률
List<BigDecimal> dailyLogReturns = new ArrayList<>();
for (int i = 1; i < dailyEquity.size(); i++) {
    double r = Math.log(dailyEquity.get(i).doubleValue() / dailyEquity.get(i - 1).doubleValue());
    dailyLogReturns.add(BigDecimal.valueOf(r));
}

// 3. Sharpe
double mean = mean(dailyLogReturns);
double std  = stdDevSample(dailyLogReturns);   // N-1 자유도
double sharpeAnnual = (mean / std) * Math.sqrt(365);

// 4. Sortino (MAR = 0)
double downsideSq = dailyLogReturns.stream()
        .mapToDouble(r -> Math.min(r.doubleValue(), 0))
        .map(x -> x * x).sum() / dailyLogReturns.size();
double downsideDev = Math.sqrt(downsideSq);
double sortinoAnnual = (mean / downsideDev) * Math.sqrt(365);

// 5. Calmar — 진짜 CAGR
double years = Duration.between(from, to).toDays() / 365.0;
double totalReturn = dailyEquity.get(dailyEquity.size() - 1).doubleValue() / initialCapital.doubleValue();
double cagr = Math.pow(totalReturn, 1.0 / years) - 1;
double calmar = cagr / (Math.abs(mddPct.doubleValue()) / 100);
```

### 5.3. 주의사항

- **일별 리샘플링 방식**: 매도 거래일까지의 equity 를 step function 으로 유지 (거래 직후부터 새 equity). 미실현 손익 을 포함하려면 일별 close 가격으로 open position 을 mark-to-market 해야 하는데, 백테스트에서는 복잡. 우선은 realized equity 만으로도 충분히 기존 방식보다 **정확**하다.
- **표본 분산 (N-1)**: 현재 코드는 모집단 분산(N) 사용. 거래 수가 적은 전략에서 무시할 수 없는 차이. `√(N/(N-1))` 정도 보정.
- **`√252` vs `√365`**: 암호화폐는 24/7 거래 → 365 가 맞다. 주식이었다면 252.
- **로그 수익률 vs 단순 수익률**: Sharpe 계산 목적으로는 소수 차이이므로 둘 다 허용되지만, CAGR 계산에는 로그/복리 방식이 필수.

---

## 6. 추가해야 할 테스트

```java
@Test
void sharpe_연환산_일별_equity_기반() {
    // 1년간 매일 +0.1% 수익 (std=0 → 무한대 회피용 노이즈 추가)
    // 기대: Sharpe 는 시장 현실적 범위 (< 5)
}

@Test
void sharpe_거래_빈도_변화_불변() {
    // 동일 일별 수익률 시리즈를 주간/월간으로 리샘플링해도
    // 연환산 Sharpe 가 거의 동일해야 함 (IID 가정)
    // → 현재 코드는 주기 변경 시 Sharpe 가 √N 배 변한다 → 테스트 실패
}

@Test
void calmar_CAGR_계산_정확() {
    // +100% 누적 수익 + 3년 기간 → CAGR = 25.99%
    // MDD -10% → Calmar = 2.599
    // 현재 코드: mean × 365 × 100 방식으로 수십배 오차
}

@Test
void sortino_편차_제곱_정의_검증() {
    // 표본: [+0.01, -0.02, -0.01, +0.03]
    // mean = 0.0025
    // downside (r < 0): -0.02, -0.01
    // 표준 Sortino: downside_dev = √((0.0004 + 0.0001) / 4) = 0.01118
    // 현재 코드가 이 식을 정확히 따르는지 명시적 assertion
}

@Test
void annualization_factor_거래빈도에_따라_달라야_함() {
    // 20 거래/년 strategy 와 100 거래/년 strategy 에 동일 per-trade
    // return 시리즈를 넣으면, 정상적 Sharpe 는 √20 vs √100 배 차이나야 함
    // (즉 거래 빈도가 높은 전략이 같은 per-trade 효율로도 더 높은 연환산 Sharpe)
}
```

---

## 7. 영향 받은 의사결정 재검토 필요 목록

1. **BTC COMPOSITE_BREAKOUT 채택 근거** — 여전히 유효할 가능성 높음 (재계산 Sharpe 1.5 수준이면 양호). 단 MACD 단일전략 (+151.9%, Sharpe 1.68 ← 같은 버그 하에 계산됨) 대비 비교는 다시.
2. **ETH COMPOSITE_MOMENTUM vs BREAKOUT** — Walk-Forward score 비교가 Sharpe-like 계산에 부분적으로 의존했다면 재검토. 순수 총수익률 비교는 영향 없음.
3. **SOL V2 전환 권고** — 근거가 주로 "3년 총수익 2배 + MDD 낮음" 이라 Sharpe 오차의 영향 작음. 그러나 "BREAKOUT Walk-Forward OOS 합계" 계산에서 이 수치를 재확인 필요.
4. **XRP 전략 보류 결정** — XRP 는 재계산 시 Sharpe 가 0.18 수준이므로 "안 좋다"는 결론은 **더 강화**된다. 결정 자체는 그대로 유효.
5. **DOGE V2 투입 권고** — Sharpe 근거가 아니라 OOS 수익률 근거였으므로 영향 작음.
6. **동적 가중치 최적화** — `StrategyWeightOptimizer` 는 Sharpe 를 직접 쓰지 않고 "4h 신호 적중률" 을 쓰므로 이 버그의 직접 영향 없음. 단 `WeightOverrideStore` 기본값 근거가 "Sharpe 우위" 라면 간접 영향 있음.
7. **웹 UI Calmar 표시** — 사용자가 이 수치를 보고 있다면 즉시 경고 배너 또는 숨김 처리. 현재 표시되고 있는지 확인 필요.

---

## 8. 수정 우선순위

| 우선순위 | 작업 | 난이도 |
|---|---|---|
| 🔴 P0 | `MetricsCalculator` 의 annualization 로직 전면 수정 | 중 |
| 🔴 P0 | 일별 equity curve 생성 유틸 추가 | 중 |
| 🔴 P0 | Sharpe/Sortino/Calmar 단위 테스트 작성 (위 §6) | 하 |
| 🟡 P1 | 기존 백테스트 결과 일괄 재계산 (배치 스크립트) | 중 |
| 🟡 P1 | `PROGRESS.md` Sharpe 수치 전부 업데이트 또는 "재계산 대기" 표시 | 하 |
| 🟡 P1 | 웹 UI 에 Calmar 표시가 있다면 숨기거나 경고 | 하 |
| 🟢 P2 | Sortino `r.pow(2)` → `(r - MAR).pow(2)` 명확화 | 하 |
| 🟢 P2 | 표본 분산 (N-1) vs 모집단 분산 (N) 결정 및 주석 | 하 |

---

## 9. 최종 판정

| 지표 | 버그 확정 | 오차 배율 (H1 Composite) | 즉시 조치 필요 |
|---|---|---|---|
| **Sharpe** | ✅ 확정 | ×4~5 | ✅ |
| **Sortino** | ✅ 확정 (annualization) + 정의 모호성 | ×4~5 | ✅ |
| **Calmar** | ✅ 확정 (CAGR 오류 + 365× 오류) | ×18~45 | ✅ |
| **Total Return %** | ❌ 문제 없음 | 1× | — |
| **Win Rate** | ❌ 문제 없음 | 1× | — |
| **MDD** | ⚠ 장중 drawdown 미포함 별도 이슈 있음 | — | 별도 분석 |
| **Win/Loss Ratio** | ❌ 문제 없음 | 1× | — |

**결론**: `MetricsCalculator` 의 Sharpe / Sortino / Calmar 세 지표는 전부 체계적 오차를 갖고 있으며, 현재 프로젝트의 모든 전략 선택·Walk-Forward 해석·웹 UI 표기에서 이 수치를 근거로 사용하는 것은 중단해야 한다. 수정은 기술적으로 어렵지 않으나 (하루 이내 작업 추정), **수정 후 모든 과거 백테스트를 재실행**해야 비교 가능한 수치가 확보된다.
