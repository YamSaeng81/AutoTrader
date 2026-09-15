# 전략 라이브러리 상세 분석 및 개선 검토

> 최초 작성·재검토: 2026-09-15  
> 범위: strategy-lib의 시장 전략 14개, 공통 지표, 복합 전략, 전략 생성·상태 관리, 백테스트·실거래 연결  
> 이 문서는 코드 검토와 별도 경계 입력 재현 결과를 기록한다. 애플리케이션 코드는 변경하지 않았다.  
> 코드 근거와 테스트 결과는 검토 당시 작업 트리 기준이다. 운영 DB·실제 배포 버전·시장 데이터 성과를 이번 검토에서 재검증하지 않았다.

## 1. 핵심 결론

**전략 개수나 조건의 복잡성보다, 측정한 전략과 실행하는 전략이 같은지 먼저 보장해야 한다.**

초기 문서는 전략 소개로는 유효했지만, 구현 결함과 성과 검증의 신뢰성을 판단하기에는 부족했다. 재검토에서 다음을 확인했다.

1. **동일 전략명으로 백테스트와 실거래가 서로 다른 구성을 실행한다.** 특히 COMPOSITE_BREAKOUT의 성분·가중치·RSI 처리 방식이 다르다.
2. **ADX의 마지막 평활 단계가 일반적인 Wilder 방식과 다르다.** 이 차이는 단일 전략의 필터와 시장 국면 분류, 복합 전략까지 영향을 준다.
3. **GRID의 매도 후 레벨 해제, MACD_STOCH_BB의 cooldown, 백테스트의 상태 격리에 문제가 있다.**
4. **ATR=0에서 예외가 발생하고, 완전 무변동 가격에서 RSI·Volume Delta가 SELL을 낼 수 있다.**
5. **MTF 상위봉 집계가 실제 시각 경계가 아닌 배열 시작점에 의존한다.**
6. 기존 전략 테스트 82개는 재실행하여 모두 통과했다. 그러나 별도 입력으로 위 경계 문제 일부를 재현했다. 테스트 성공은 이러한 결함이 없다는 증거가 아니다.

기존의 “검증된 COMPOSITE 전략”, “HEIKIN_ASHI가 구조적으로 가장 완성도 높다”는 표현은 철회한다. 운영상 허용된 전략이라는 사실과 현재 구현의 동등성·수익성이 입증되었다는 판단을 구분해야 한다.

### 1.1 판정 기준

| 표시 | 의미 |
|---|---|
| 재현 확인 | 재컴파일한 전략 클래스에 별도 입력을 전달하여 결과 확인 |
| 코드 확인 | 구현과 호출 경로에서 확인. 실거래 손실 규모나 발생 빈도까지 측정한 것은 아님 |
| 설계 선택 | 의도할 수 있는 규칙이나, 전략 설명·운영 정책과 일치하는지 확인 필요 |
| 보강 제안 | 향후 수정·실험 방향. 성과 개선을 보장하지 않음 |

수정 우선순위는 다음 의미로 사용한다.

- **최우선:** 성과 검증의 전제 또는 다수 전략의 계산·상태를 흔드는 문제.
- **높음:** 기본 입력에서 예외·잘못된 방향·전략 동작 왜곡을 일으킬 수 있는 문제.
- **보강:** 파라미터·점수·설명·검증 체계를 개선할 사항.

개별 테스트 파일이 없다는 이유만으로 모든 항목을 P0로 분류하지 않는다.

## 2. 구조와 검토 범위

```text
strategy-lib
  Strategy.evaluate(candles, params)
    → StrategySignal(BUY / SELL / HOLD, strength, optional SL/TP)
  Stateless 시장 전략 12개
  Stateful 시장 전략 2개: GRID, MACD_STOCH_BB
  TEST_TIMED: 실행 검증용, 시장 전략 14개에서 제외

core-engine
  공통 지표 소비·시장 국면 감지
  CompositeStrategy: 가중 투표
  RegimeAdaptiveStrategy / CompositeRegimeRouter
  Ichimoku / MTF / RSI Veto 등의 래퍼
  BacktestEngine

web-api
  CompositePresetRegistrar: 운영용 프리셋 등록
  BacktestService: 백테스트 실행용 전략 선택·별도 조합
  LIVE / DYNAMIC / PAPER: 세션별 평가·체결·청산
```

[StrategyRegistry](strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategyRegistry.java)는 공유 인스턴스와 상태 전략의 생성 팩토리를 함께 관리한다. 따라서 팩토리가 존재한다는 사실만으로 모든 호출 경로의 상태 격리가 보장되지는 않는다.

또한 stateful 등록 여부는 단순히 StatefulStrategy 인터페이스 구현 여부와 같지 않다. COMPOSITE 계열은 내부 GRID나 국면 감지기의 상태 때문에 팩토리로 등록된다. 바깥 래퍼뿐 아니라 내부 성분까지 새 인스턴스인지 확인해야 한다.

운영 정책의 근거는 [StrategyLiveStatusRegistry](web-api/src/main/java/com/cryptoautotrader/api/service/StrategyLiveStatusRegistry.java)다.

- 시장 전략 14개 중 MACD, STOCHASTIC_RSI, MACD_STOCH_BB는 BLOCKED다.
- 나머지 단독 시장 전략은 EXPERIMENTAL이다.
- TEST_TIMED는 DEPRECATED다.
- 일부 복합 전략은 ENABLED다. 이 값과 코드 주석에 적힌 과거 수익률은 운영 정책·기록이며, 이번 검토에서 해당 성과를 재계산한 것은 아니다.

## 3. 최우선: 백테스트와 실행 전략의 동등성

### 3.1 COMPOSITE_BREAKOUT의 구성 불일치

**판정: 코드 확인 / 최우선**

| 구성 요소 | BacktestService의 백테스트 | 운영 레지스트리 등록 |
|---|---|---|
| ATR_BREAKOUT | 0.4 | 0.5 |
| VOLUME_DELTA | 0.3 | 0.3 |
| RSI | 0.2, 투표 참여 | 별도 RsiVetoStrategy |
| EMA_CROSS | 0.1 | 없음 |
| MACD | 없음 | 0.2 |
| EMA·ADX 필터 | 적용 | 적용 |

근거:

- [BacktestService.java](web-api/src/main/java/com/cryptoautotrader/api/service/BacktestService.java): compositeBreakoutBt(), 검토 당시 770행.
- [CompositePresetRegistrar.java](web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java): COMPOSITE_BREAKOUT 등록, 검토 당시 88행.

RSI 투표와 RSI Veto는 같은 규칙이 아니다. 전자는 반대 점수를 내더라도 최종 BUY를 막지 못할 수 있고, 후자는 조건에 따라 BUY를 직접 차단한다.

**영향:** 해당 백테스트 경로의 성과를 현재 운영 등록 전략의 검증 결과로 사용할 수 없다. 과거 저장 성과 전체가 틀렸다고 단정할 수는 없지만, 어느 코드·생성 경로로 산출했는지 추적해야 한다.

**개선 방향**

- 전략 생성 로직을 공통 팩토리로 통합한다.
- 동일 전략명·파라미터·캔들에서 백테스트와 운영 경로가 동일한 전략 트리와 신호를 만드는지 검증한다.
- 결과에 성분·가중치·필터·기본값·지표 구현 버전을 저장한다.
- 청산 규칙 버전이 같다는 사실만으로 진입 전략의 동등성을 판단하지 않는다.

### 3.2 COMPOSITE_ETH의 의도적인 가중치 차이

**판정: 코드 확인 / 비교 조건 명시 필요**

- 백테스트: ATR 0.7 / ORDERBOOK 0.1 / EMA 0.2
- 운영 등록: ATR 0.5 / ORDERBOOK 0.3 / EMA 0.2

BacktestService의 compositeEthBt()에는 OHLCV 근사의 불확실성을 이유로 호가 성분의 비중을 줄였다는 주석이 있다. 의도적이라도 동일 전략의 실거래 성과 예측으로 해석하면 안 된다.

또한 LiveTradingService의 실호가 주입 조건은 단독 전략명이 ORDERBOOK_IMBALANCE인 경우다. COMPOSITE_ETH 내부의 호가 성분에 자동으로 같은 주입이 이루어지는 것은 아니다. “LIVE는 실호가, BT는 근사이므로 비중만 보정하면 된다”는 전제부터 호출 경로별 확인이 필요하다.

### 3.3 COMPOSITE 백테스트가 적응형 동작을 재현하지 않음

**판정: 코드 확인 / 최우선**

[BacktestService.java](web-api/src/main/java/com/cryptoautotrader/api/service/BacktestService.java)의 runStrategy()는 COMPOSITE에 대해 다음 순서로 처리한다.

1. 새 MarketRegimeDetector 생성.
2. 전체 캔들로 detect()를 한 번 호출.
3. 그 결과로 StrategySelector에서 조합 선택.
4. 선택된 고정 CompositeStrategy로 전체 백테스트 실행.

[MarketRegimeDetector.java](core-engine/src/main/java/com/cryptoautotrader/core/regime/MarketRegimeDetector.java)의 초기 국면은 RANGE이며, 다른 국면으로 바뀌려면 3회 연속 감지가 필요하다. 정상 입력에서 첫 한 번의 detect()는 RANGE를 반환하므로 이 경로는 RANGE 조합으로 고정된다.

반면 운영의 [RegimeAdaptiveStrategy.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/RegimeAdaptiveStrategy.java)는 평가마다 국면을 갱신하고 TRANSITIONAL·ADX 관련 BUY 차단도 수행한다.

**개선:** 시점별 과거 캔들만 전달하여 운영과 같은 적응형 인스턴스를 실행한다. detect()를 detectRaw()로 바꾸는 것만으로 해결하지 않는다. 전체 기간으로 한 번 국면을 선택하면 미래 정보를 초기 전략 선택에 사용하는 문제가 생긴다.

### 3.4 백테스트와 중첩 전략의 상태 격리 누락

**판정: 코드 확인 / 최우선**

[BacktestEngine.java](core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java)의 이름 기반 run()은 StrategyRegistry.get()으로 공유 인스턴스를 가져온다. 해당 경로에는 새 상태 인스턴스 생성이나 실행 시작 시 초기화가 없다.

영향 가능한 상태:

- GRID의 activeLevels와 이전 가격 범위.
- MACD_STOCH_BB의 마지막 BUY 길이.
- 국면 감지기와 GRID를 포함하는 복합 전략의 내부 상태.

그 결과 실행 순서에 따라 결과가 달라지거나, 병렬 백테스트가 서로 간섭할 수 있다. 이번 검토에서 병렬 오염 발생 빈도를 측정한 것은 아니다.

또한 RegimeAdaptiveStrategy가 사용하는 [StrategySelector.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/StrategySelector.java)의 ws()도 하위 전략을 공유 레지스트리에서 가져온다. 바깥 세션 인스턴스만 새로 만들어도 내부 상태가 공유될 수 있다.

**개선:** 실행·세션 단위로 전체 전략 트리를 새로 생성한다. 공유 객체에 resetState()만 호출하는 방식은 병렬 실행 격리를 해결하지 못한다.

## 4. 공통 지표와 데이터 경계의 결함

### 4.1 ADX 최종 평활 방식 불일치

**판정: 코드 확인 / 최우선**

[IndicatorUtils.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/IndicatorUtils.java)의 adx()는 DM·TR을 평활한 뒤 산출한 DX를 입력 이력 전체에 걸쳐 단순 평균한다.

```text
현재 구현:
ADX = 계산된 DX 전체 합 / DX 개수

일반적인 Wilder 방식:
초기 ADX = 초기 DX 평균
이후 ADX = (이전 ADX × (period − 1) + 현재 DX) / period
```

Wilder 방식의 후속 평활 공식은 [TA-Lib ADX 구현](https://github.com/TA-Lib/ta-lib/blob/main/src/ta_func/ta_ADX.c)과 대조했다. 이번 검토에서 TA-Lib과 전체 수치 시계열을 실행 비교한 것은 아니다.

**영향**

- EMA/MACD의 추세 진입 필터.
- VWAP/Bollinger/StochRSI의 강한 추세 회피 필터.
- MarketRegimeDetector의 국면 분류.
- CompositeStrategy와 눌림목 전략의 ADX 게이트.

현재 방식은 오래된 구간의 DX에 같은 비중을 주므로 최근 추세 변화를 나타내는 의미가 달라진다. “입력 이력이 충분하면 Wilder 지표의 초기값 영향이 줄어든다”는 설명을 이 구현에 그대로 적용할 수 없다.

**개선:** 기준 지표와의 시계열 비교 테스트를 먼저 작성하고, 계산 수정 후 기존 임계값 20·25·35 등을 재검증한다. 지표 변경 전후의 성과를 같은 전략 버전으로 합산하지 않는다.

### 4.2 ADX 동적 임계값의 계산 창 불일치와 1개 초과 반환

**판정: 코드 확인 및 개수 재현 / 높음**

CompositeStrategy는 현재 ADX를 전체 전달 이력으로 계산하지만, 동적 임계값용 adxList()의 각 ADX는 최대 2 × (2 × period + 1)개로 잘라 계산한다. period=14이면 최대 58개다.

ADX 자체가 DX 전체 평균이므로, 현재값과 분포가 서로 다른 길이의 평균에 근거한다. 동일 통계량의 현재값과 과거 분포를 비교하는 구조가 아니다.

추가로 adxList()는 시작 인덱스부터 마지막까지 포함하여 순회해, 충분한 데이터에서 windowSize=60일 때 61개를 반환한다. 별도 입력으로 61을 확인했다.

**개선:** 같은 계산 정의와 초기화 정책으로 하나의 ADX 시계열을 만들고, 마지막 값과 필요한 과거 표본을 추출한다. 현재값을 분포에 포함할지도 명시한다.

### 4.3 ATR=0에서 기본 설정으로 예외 발생

**판정: 재현 확인 / 높음**

[AtrBreakoutStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java)의 비돌파 HOLD 분기에서 다음 계산을 한다.

```text
위치 = (현재 종가 − 매도 기준선) / (매수 기준선 − 매도 기준선)
```

OHLC가 모두 100인 캔들 100개를 기본 파라미터로 전달하면 ATR=0이고 두 기준선이 같아 ArithmeticException: / by zero가 발생한다.

잘못된 사용자 파라미터가 없어도 발생하는 문제다.

**개선:** ATR 또는 기준선 폭이 0 이하이면 이유를 포함한 HOLD를 반환한다. 가격 정밀도 반올림으로 0이 되는 경우도 검사한다.

### 4.4 완전 무변동 가격에서 RSI=100, SELL

**판정: 재현 확인 / 높음**

다음 두 구현은 평균 손실이 0이면 평균 이익과 무관하게 RSI=100을 반환한다.

- IndicatorUtils.rsiFromAvg().
- [RsiStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java)의 rsiFromAvgs().

가격이 완전히 일정한 기본 입력에서 RsiStrategy는 SELL을 반환했다.

분모가 0인 RSI의 경계 처리는 구현마다 관례가 다를 수 있다. 다만 상승·하락이 모두 없는 상태를 강한 과매수로 해석할지는 별도 정책이어야 한다.

**개선:** 양쪽 평균이 모두 0인 경우를 구분하고 중립값 또는 방향 판정 보류 정책을 적용한다. 단독 RSI와 공통 RSI가 서로 다른 결과를 내지 않도록 중복 계산도 정리한다.

### 4.5 무변동 봉을 전량 매도로 분류하는 OHLCV 근사

**판정: 수식 확인 및 Volume Delta SELL 재현 / 높음**

[VolumeDeltaStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java)와 [OrderbookImbalanceStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/orderbook/OrderbookImbalanceStrategy.java)의 근사는 다음 수식을 쓴다.

```text
buyRatio = (close − low) / (high − low + epsilon)
buyVolume = volume × buyRatio
sellVolume = volume × (1 − buyRatio)
```

high=low=close이면 buyRatio=0이 되어 거래량 전체를 매도로 간주한다. epsilon은 예외를 피하지만 데이터에 없는 방향을 만든다.

가격이 전혀 움직이지 않는 20개 봉에서 앞 10봉 거래량=100, 뒤 10봉=200으로 주면 VolumeDeltaStrategy가 기본 설정에서 SELL을 반환한다. 증가한 거래량을 매도 압력 강화로 해석하기 때문이다.

**개선:** 무변동 봉의 delta를 0으로 처리하거나 방향 판단에서 제외하는 정책을 정하고 두 구현에 공통 적용한다. 단순히 분모에 더 큰 epsilon을 넣는 것으로 해결하지 않는다.

## 5. 상태·시간·주문 계약의 문제

### 5.1 GRID 매도 시 잘못된 레벨 해제

**판정: 재현 확인 / 높음**

[GridStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java)는 하단 BUY에서 activeLevels.add(levelIndex)를 실행하고 상단 SELL에서 현재 상단 번호에 대해 remove(levelIndex)를 실행한다.

기본 10분할에서 BUY 영역은 하위 0~3, SELL 영역은 상위 7~10이다. 같은 가격 범위라면 매수한 번호와 제거하는 번호가 겹치지 않는다.

```text
가격 범위 100~110 고정:
102 → BUY  : 레벨 2 추가
108 → SELL : 레벨 8 제거 시도
102 → HOLD : 레벨 2가 남아 재진입 차단
```

이 결과를 별도 입력으로 재현했다. 미체결뿐 아니라 정상적인 BUY→SELL 신호 순서에서도 발생한다.

추가 문제:

- 실제 체결 전에 BUY 신호만으로 레벨을 점유한다.
- 복합 투표나 상위 필터에서 BUY가 탈락해도 내부 상태는 바뀐다.
- 작은 가격 범위 이동에서는 같은 레벨 번호의 실제 가격이 바뀌어도 상태가 유지된다.
- 범위 변경 비교는 직전 평가 기준이므로 작은 변화가 누적된 이동도 별도로 검토해야 한다.

**개선 방향**

1. 실제 격자 매매라면 레벨별 주문·재고·청산의 대응 관계를 모델링한다.
2. 복합 전략의 위치 투표라면 체결 상태를 갖지 않는 성분으로 분리하는 방안을 검증한다.
3. 이미 존재하는 levelDedupEnabled=false는 비교 실험에 사용할 수 있지만, 재고 관리 문제의 완전한 해결책은 아니다.

### 5.2 MACD_STOCH_BB cooldown의 시간 경과 오류

**판정: 코드 확인 / 높음**

[MacdStochBbStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/macdstochbb/MacdStochBbStrategy.java)는 다음 값을 경과 봉 수로 사용한다.

```java
candles.size() - lastBuyCandleCount
```

BacktestEngine의 창은 최대 500개다. 길이 500에서 BUY가 발생하면 이후 창도 500이므로 차이는 계속 0이다. 기본 cooldownCandles=3에서는 상태 초기화나 창 길이 변화가 없을 때 이후 BUY 조건을 계속 차단한다.

**개선:** 마지막 BUY의 닫힌 봉 시각 또는 새 봉 평가 순번을 사용한다. 결측봉이 있을 때 3개 관측봉과 3개 시간 구간 중 무엇을 의미하는지 정한다. 전략 인스턴스 재생성·재시작 시 cooldown 복원 정책도 필요하다.

### 5.3 MTF 상위봉 집계의 시간 정렬 문제

**판정: 코드 확인 / 높음**

[CandleDownsampler.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/CandleDownsampler.java)는 배열 처음부터 factor개씩 묶는다.

```text
H1→H4, factor=4:
조회 시작 00시: 00·01·02·03 / 04·05·06·07
조회 시작 01시: 01·02·03·04 / 05·06·07·08
```

고정 길이 창이 한 봉 이동하면 과거 상위봉의 묶음도 바뀐다. 결측봉이 있으면 4개 관측봉이 4시간을 넘을 수 있다.

영향 대상:

- [MtfConfirmedStrategy.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/MtfConfirmedStrategy.java)를 사용하는 복합 프리셋.
- [CompositePullbackMtfStrategy.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositePullbackMtfStrategy.java).

미완성 마지막 그룹도 포함한다. 이것 자체는 미래 정보를 쓰는 행위가 아니지만, 완성된 H4로 확인하는 전략과는 다르다. strictHtf=true도 봉 완성 여부를 검사하지 않는다.

**개선:** 시각 경계 기반 집계, 첫 부분 그룹·마지막 미완성 그룹·결측봉 정책을 명시한다. 동일한 닫힌 H4 구간이 조회 시작점과 무관하게 같은 OHLCV를 가지는지 검증한다.

### 5.4 BUY·SELL·HOLD의 의미 혼용

**판정: 설계 선택 및 보강**

현재 StrategySignal.Action은 다음 의미를 모두 담는다.

- 신규 롱 진입.
- 기존 롱 청산.
- 하락 방향 의견 또는 숏 진입 설명.
- 지속 상승·하락 추세의 편향.

Supertrend는 추세 유지 중에도 BUY/SELL을 반환한다. HA의 SELL에는 숏 기준 제안 SL/TP가 포함되지만, 이 설명만으로 현물 엔진이 숏을 실행한다고 해석하면 안 된다.

진입 필터도 SELL을 차단할 수 있다.

- StochRSI: SELL에 거래량 필터 적용.
- EMA/MACD 등: 공통 ADX 선행 필터가 양방향 신호에 영향.
- MTF/Ichimoku: 방향 불일치나 구름 조건이 SELL도 HOLD로 바꿀 수 있음.

SELL이 방향 의견이면 가능한 설계지만, 기존 롱 청산이면 탈출 지연 효과를 평가해야 한다. DYNAMIC의 전략 신호 청산은 기본 비활성화되어 있으므로 신호 생성과 실제 청산을 별도로 기록해야 한다.

**개선:** intent를 추가하거나 진입 신호·청산 신호·방향 편향을 분리한다. 신호 계약 변경 시 LIVE/DYNAMIC/PAPER/백테스트의 관련 소비 경로와 의도적인 차이를 함께 검증한다.

### 5.5 제안 SL/TP와 실제 손익비의 차이

**판정: 코드 확인 및 정책 검토**

LIVE/DYNAMIC/PAPER와 백테스트에는 제안 손절가와 ATR 손절가 중 더 낮은 값을 선택하는 경로가 있다. 명시적 청산 override 유무 등 적용 조건을 함께 봐야 한다.

예시:

```text
진입가 100
전략 제안 손절가 98.5
ATR 손절가 95
min 선택 결과 95
```

근거: [LiveTradingService.java](web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)의 손절가 결정, 검토 당시 1264행. 익절은 [ExitRuleFormula.java](core-engine/src/main/java/com/cryptoautotrader/core/risk/ExitRuleFormula.java)의 resolveTakeProfitPrice()에서 추가 조정된다.

제안값은 신호 시점 가격으로 계산되며 실제 체결가·트레일링·청산 규칙에 따라 실현 손익비가 바뀐다. 따라서 “고정 1:2 전략”은 제안값의 기본 비율로 한정하여 설명해야 한다.

## 6. 시장 전략 14개 상세 평가

아래 장점은 코드 구조와 전략 가설에 대한 평가다. 수익성을 재검증했다는 의미는 아니다.

### 6.1 VWAP — 세션/rolling 평균회귀

근거: [VwapStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/vwap/VwapStrategy.java)

- **규칙:** 기본 thresholdPct=1.5, period=20, anchorSession=true. VWAP 대비 할인 BUY, 프리미엄 SELL. ADX 상한 기본 35.
- **장점:** 거래량을 반영한 기준가격과의 이탈을 설명하기 쉽다.
- **한계:** 강한 추세에서는 더 싸진 가격이 계속 하락할 수 있다. 평균회귀 가설은 손절·최대 보유시간과 함께 평가해야 한다.
- **구현 유의점:** UTC 당일 봉이 3개 미만이면 rolling으로 대체한다. 세 번째 봉에서 기준이 바뀐다. 입력 창이 당일 전체를 포함하지 못하면 완전한 세션 VWAP가 아니다. ADX 이력이 부족하면 필터를 건너뛴다.
- **보강:** 세션 시작·종료·전환 시나리오, session/rolling A/B, 실제 평균회귀 폭 대비 왕복 비용을 검증한다.
- **운영 정책:** EXPERIMENTAL.

### 6.2 BOLLINGER — 밴드 내부 경계의 평균회귀

근거: [BollingerStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerStrategy.java)

- **규칙:** period=20, multiplier=2, BUY %B<0.2, SELL %B>0.8. ADX 상한 25, squeeze 필터 기본 활성.
- **장점:** 고정 퍼센트 대신 변동성에 따라 진입 경계를 바꾼다.
- **한계:** 추세장에서 밴드를 따라가는 가격에 역행할 수 있다. squeeze 후 방향 돌파를 추종하는 전략은 아니다.
- **정정:** 주석의 %B<0 / >1과 기본 구현 0.2 / 0.8은 다르다. “밴드 밖 이탈만 매매”로 설명하면 틀린다.
- **보강:** 밴드 이탈 즉시 진입과 밴드 복귀 확인 후 진입을 비교한다. squeeze 필터의 거래 감소와 손실 감소를 각각 측정한다.
- **운영 정책:** EXPERIMENTAL.

### 6.3 RSI — 극단값과 조기 다이버전스

근거: [RsiStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/rsi/RsiStrategy.java)

- **규칙:** RSI 14, 기본 30 미만 BUY / 70 초과 SELL. useDivergence=true, pivotWindow=10.
- **장점:** 과매수·과매도와 가격/모멘텀 불일치를 단순한 규칙으로 표현한다.
- **한계:** 추세에서는 극단값이 오래 유지된다. 최근 종가 pivot과 현재값을 비교하며 현재 저점·고점의 반전 확정을 기다리지 않는다.
- **판정:** 현재 pivot 미확정은 미래 참조 결함이 아니라 조기 진입 설계다.
- **보강:** 무변동 RSI 처리, 공통 RSI와 계산 통합, 다이버전스 신호와 일반 신호의 강도 차이, 극단 구간 탈출 확인을 검증한다.
- **운영 정책:** EXPERIMENTAL.

### 6.4 GRID — 범위 하단·상단 위치 신호

근거: [GridStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/grid/GridStrategy.java)

- **규칙:** 최근 100봉 고저 범위를 10등분. 하위 30%·상위 30%에서 가까운 레벨과의 거리가 격자 간격의 기본 5% 이내일 때 신호.
- **장점:** 범위 내 가격 위치를 직접 활용한다.
- **한계:** 현재 구현에는 레벨별 자본 배분·격자 주문·재고 대응이 없다. 완전한 그리드 주문 전략으로 설명하면 과장이다.
- **확인 오류:** 매도 시 매수 레벨이 남음. 신호 발생만으로 상태 변경. 공유 인스턴스 경로의 오염 가능성.
- **보강:** 5.1절의 상태·체결 대응을 우선 처리하고, 실제 격자 매매와 복합 투표용 위치 신호를 구분한다.
- **운영 정책:** EXPERIMENTAL.

### 6.5 STOCHASTIC_RSI — 극단 구간 탈출 확인

근거: [StochasticRsiStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/stochasticrsi/StochasticRsiStrategy.java)

- **규칙:** RSI14→Stoch14→D SMA3. K가 20 위로 탈출하며 현재·직전 모두 K>D이면 BUY. 80 아래 탈출과 현재·직전 K<D이면 SELL. ADX 상한 30과 거래량 확인.
- **장점:** 단순 과매도 진입보다 탈출을 확인한다.
- **한계:** 직전 K>D까지 요구하므로 한 봉에 급반전하는 탈출은 놓칠 수 있다. 이 조건은 일반적인 “현재 봉의 K/D 골든크로스”와 다르다.
- **보강:** 즉시 반전과 점진적 탈출의 신호 수를 비교하고, SELL 거래량 필터의 청산 지연 효과를 측정한다.
- **운영 정책:** BLOCKED. 과거 손실 기록이 있지만 현재 수정안의 성과를 이번 검토에서 재계산하지 않았다.

### 6.6 EMA_CROSS — 교차 이벤트형 추세 전략

근거: [EmaCrossStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/ema/EmaCrossStrategy.java)

- **규칙:** EMA20/50 교차, ADX 하한 25.
- **장점:** 진입 사건이 명확하고 해석하기 쉽다.
- **한계:** 후행성과 횡보장 가짜 교차. ADX 필터 역시 같은 공통 계산 문제를 상속한다.
- **핵심 통찰:** 강도는 EMA 간격/slow EMA ×1000이다. 교차 순간 간격이 작으므로 유효한 교차도 가중 투표에서는 매우 약한 표가 될 수 있다.
- **보강:** fast<slow 검증, 교차 시 점수 하한이나 변동성 정규화 비교, 교차 이벤트와 지속 추세 의견의 분리.
- **운영 정책:** EXPERIMENTAL.

### 6.7 MACD — 교차·0선·ADX 조건

근거: [MacdStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java)

- **규칙:** coinPair에 따른 기본 fast/slow가 존재한다. BTC 14/22, ETH 10/26, 그 외 12/24, signal=9. 교차와 0선 방향, ADX 하한을 확인한다.
- **장점:** 추세 반대쪽의 교차를 제한하는 의도가 명확하다.
- **한계:** 0선 필터는 초기 추세 전환 신호를 포기한다. 0선 부근 signal 값으로 나누는 강도는 쉽게 포화될 수 있다.
- **확인 사항:** 히스토그램 확대 필터는 현재 교차 조건에서 자동 성립한다.
  - BUY 교차: 이전 histogram≤0, 현재 histogram>0이므로 현재>이전.
  - SELL 교차: 이전 histogram>0, 현재 histogram≤0이므로 현재<이전.
- **정정:** 이 비교가 독립적인 가짜 교차 제거 필터로 작동한다는 기존 평가는 틀렸다.
- **보강:** 확대 지속을 확인하려면 별도 여러 봉 조건을 설계하고, 지연·거래 감소까지 A/B 검증한다.
- **운영 정책:** BLOCKED, 복합 성분으로 사용됨.

### 6.8 SUPERTREND — 추세 상태와 전환

근거: [SupertrendStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/supertrend/SupertrendStrategy.java)

- **규칙:** ATR10×3의 조정 밴드로 추세 판단. 전환뿐 아니라 유지 상태에서도 BUY/SELL.
- **장점:** 방향 확인용 성분으로 사용할 수 있고 ATR 시계열 사전 계산으로 O(n) 평가한다.
- **한계:** 횡보장의 반복 전환과 초기 상승 편향. 일정 길이 이력이 있으면 초기 영향이 항상 사라진다고 보장할 수는 없다.
- **강도:** 지속 신호 상한 50, 전환 신호 하한 70. 가중치 0.3이면 지속 신호의 점수 기여 상한은 0.15다.
- **보강:** 짧은/긴 입력 이력의 신호 비교, 상태 유지와 신규 진입 구분, MTF 집계 오류 수정 후 재평가.
- **운영 정책:** EXPERIMENTAL.

### 6.9 ATR_BREAKOUT — 현재봉 변동성 돌파

근거: [AtrBreakoutStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/atrbreakout/AtrBreakoutStrategy.java)

- **규칙:** 현재 종가가 현재 시가±ATR14×1.5를 넘는지 판단. BUY에는 직전 14봉 평균 대비 거래량 필터.
- **장점:** 방향성 큰 봉과 거래량을 함께 본다.
- **한계:** 현재봉이 ATR에도 포함되므로 큰 돌파봉 자체가 기준선을 높인다. 확정봉 평가라면 미래 참조는 아니며, 사전에 정해진 돌파 주문선과 다른 규칙이다.
- **확인 오류:** ATR=0의 HOLD 분기 예외.
- **의미 주의:** useStopLoss는 실제 진입가 기준 손절이 아니라 하방 돌파 SELL 허용 여부다.
- **보강:** 이전봉 ATR로 기준을 고정한 버전과 비교하되 동일 전략으로 성과를 섞지 않는다.
- **운영 정책:** EXPERIMENTAL.

### 6.10 ORDERBOOK_IMBALANCE — 실호가와 OHLCV 근사의 혼합

근거: [OrderbookImbalanceStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/orderbook/OrderbookImbalanceStrategy.java)

- **규칙:** 기본 lookback=15, imbalanceThreshold=0.70. Number형 bidVolume/askVolume이 있으면 실호가, 없으면 종가 위치·거래량 근사.
- **장점:** 공급된 잔량에 대해 명확한 불균형 계산을 한다.
- **한계:** 근사 모드의 delta 가속·반전 할인은 실호가 모드에 없다. 두 모드는 데이터뿐 아니라 필터도 다르다.
- **운영 확인:** LiveTradingService는 단독 ORDERBOOK_IMBALANCE에 REST 호가를 주입하며 실패하면 근사로 전환한다. 실거래라고 항상 실호가 모드는 아니다.
- **보강:** 데이터 모드·snapshot 시각·깊이·유효기간·fallback 이유를 기록한다. threshold는 비중 우세 의미상 0.5보다 크고 1보다 작도록 검토한다. 음수 잔량도 거부한다.
- **운영 정책:** EXPERIMENTAL. 레지스트리의 “WebSocket 연동 필요” 문구와 현재 REST 연결 사실은 구분한다.

### 6.11 VOLUME_DELTA — 종가 위치 기반 압력

근거: [VolumeDeltaStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java)

- **규칙:** 최근 20봉 누적 delta/거래량이 기본 ±0.10을 넘고, 전·후반 평균 delta 방향이 강화될 때 신호.
- **장점:** 거래량과 봉 내 종가 위치를 결합하고 강도 포화점을 조절할 수 있다.
- **한계:** 실제 aggressor volume이 아니다. 같은 delta 비율에서도 거래량 증가만으로 원시 delta가 커질 수 있으므로 “가속”의 의미에 주의한다.
- **다이버전스 실제 동작:** 가격 하락+양의 delta이면 BUY 보류, 가격 상승+음의 delta이면 SELL 보류. 반전 진입보다 가격과 delta 방향의 일치를 요구하는 성격이다.
- **확인 오류:** 무변동 봉의 전량 매도 분류.
- **보강:** 분해 공식 공통화, 원시 delta와 거래량 정규화 delta 비교, 가격 확인 필터의 기여도 분리.
- **운영 정책:** EXPERIMENTAL.

### 6.12 FAIR_VALUE_GAP — 3봉 비중첩 모멘텀

근거: [FairValueGapStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/fvg/FairValueGapStrategy.java)

- **규칙:** c0.high<c2.low면 상승 FVG, 반대면 하락 FVG. 최소 gap 0.1%, 기본 EMA20 필터.
- **장점:** 명확한 가격 구조를 사용하고 미세 gap을 제한한다.
- **한계:** 중간봉의 방향·강한 몸통이 필수 조건은 아니다. 가격 비중첩만으로 실제 유동성 공백을 관측했다고 볼 수 없다.
- **강도 문제:** gap/body이므로 작은 몸통에서 쉽게 100으로 포화되고, 도지에서는 60으로 분기한다. 임펄스가 강할수록 높은 점수라는 해석과 반드시 일치하지 않는다.
- **보강:** 몸통·ATR로 정규화한 displacement 조건을 실험한다. gap 메움·재방문·무효화 관리는 현재 구현의 버그가 아니라 별도 전략 확장이다.
- **운영 정책:** EXPERIMENTAL.

### 6.13 HEIKIN_ASHI_STOCH — 추세·교차·캔들 구조

근거: [HeikinAshiStochStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/heikinashi/HeikinAshiStochStrategy.java)

- **규칙:** 원본 종가의 EMA200, StochRSI K/D 교차, HA 봉 방향·꼬리·몸통, BUY 거래량 확인. 기본 제안 SL −1.5%, TP +3%.
- **장점:** 원본 가격 지표와 HA 모양 검사를 구분하며 진입 조건과 제안 SL/TP가 명시적이다.
- **정정:** 기본 requireBodyGrowth=true이므로 몸통 증가는 필수다. 주석의 “가산점으로 완화”만 읽으면 실제 기본 동작을 오해한다.
- **한계:** 필터 수가 많다고 우수성이 입증되는 것은 아니다. K/D 교차와 HA 조건의 동시 충족으로 기회를 놓칠 수 있다.
- **보강:** 각 조건 제거 실험, 거래량 필터 효과, 실제 체결 후 SL/TP·실현 손익비 검증. 기존 주석의 소표본 성과를 일반화하지 않는다.
- **운영 정책:** EXPERIMENTAL.

### 6.14 MACD_STOCH_BB — 추세 속 과매도 되돌림

근거: [MacdStochBbStrategy.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/macdstochbb/MacdStochBbStrategy.java)

- **규칙:** MACD>0, histogram 증가, K<20, K>D, 거래량 확인 후 BUY. histogram 감소 또는 K>80이면 SELL 후보.
- **장점:** 추세와 되돌림 타이밍을 결합하려는 가설이 명확하다.
- **정정:** 현재 볼린저 조건은 제거되어 이름과 구현이 다르다. K>D는 현재의 상대 위치이며 직전 K≤D를 확인하는 골든크로스 사건이 아니다.
- **확인 오류:** 고정 길이 창에서 cooldown 정지.
- **추가 한계:** sidewaysThreshold=0.0005는 MACD 가격 절대 단위다. 종목·호가 통화 간 의미가 다르다. 횡보 선행 필터는 SELL 평가도 막는다.
- **보강:** MACD/가격 또는 MACD/ATR 정규화 비교, 이름·조건 설명 정리, 체결과 신호 cooldown의 구분.
- **운영 정책:** BLOCKED.

## 7. 복합·필터 전략에 대한 추가 통찰

### 7.1 강도는 승률이 아니며, 명목 가중치는 실제 영향력과 다르다

[StrategySignal.java](strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategySignal.java)의 getConfidence()는 strength/100이다. 확률로 보정된 승률이 아니다.

| 신호 예시 | 현재 강도 특성 |
|---|---|
| RSI=29, 과매도 기준 30의 일반 BUY | 약 3.33 |
| FVG 발화 | 최소 50 |
| Supertrend 전환 | 최소 70 |
| EMA 교차 직후 | EMA 간격이 작으면 매우 낮음 |

[CompositeStrategy.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java)는 weight×confidence를 합산하고, 가중치 합이 1보다 클 때만 합계로 나눈다. HOLD 성분을 제외하여 활성 성분끼리 다시 정규화하는 방식은 아니다.

실효 영향력은 다음에 의해 결정된다.

- 명목 가중치.
- 신호 빈도와 강도 분포.
- 다른 성분과 동시에 발화하는 정도.
- EMA·ADX·시간대·국면 감쇠.
- 최종 weak/strong 임계값.

강도가 낮아진 결과를 “그 전략은 수익성이 없다”고 해석해서는 안 된다. 먼저 최종 결정에 참여할 수 있는 점수 구조인지 확인해야 한다.

### 7.2 유사한 원천 신호를 독립 정보로 오해하지 않기

- Orderbook의 OHLCV 근사와 Volume Delta는 같은 종가 위치 기반 공식을 사용한다.
- EMA·MACD·Supertrend는 서로 다르지만 가격 추세 정보가 겹칠 수 있다.
- 같은 원천 신호를 여러 표로 세면 합의 점수가 높아져도 독립적인 증거가 늘어난 것은 아닐 수 있다.

성분별 신호 상관관계, 동시 발화율, 제거 전후 성과를 측정해야 한다. 복잡한 전략보다 단순 기준 전략이 더 나은지 함께 비교한다.

### 7.3 필터가 많아지는 비용을 수치로 확인하기

각 필터에 대해 다음을 기록한다.

1. 원시 후보 수.
2. 해당 필터가 제거한 후보 수.
3. 최종 체결 수.
4. 제거된 후보의 이후 수익·손실 분포.
5. 다른 필터와 중복 차단하는 비율.

조건 추가로 승률이 올라도 거래 수가 급감하거나 큰 추세 진입을 놓쳐 전체 기대값이 낮아질 수 있다. MACD histogram처럼 논리적으로 중복인 조건은 먼저 제거 가능한지 확인한다.

### 7.4 Ichimoku와 눌림목 전략의 설명 정밀화

[IchimokuFilteredStrategy.java](core-engine/src/main/java/com/cryptoautotrader/core/selector/IchimokuFilteredStrategy.java)는 기본적으로 현재값의 비변위 구름을 사용하고 ichimokuDisplaced=true일 때 과거 기준 구름을 사용하는 비교 옵션이 있다. 코드가 이를 의도적으로 구분하므로 무조건 오류라고 단정하지 않는다. 전략 결과에는 어떤 모드를 사용했는지 기록해야 한다.

COMPOSITE_PULLBACK_MTF는 H4 Supertrend, EMA200, RSI40~55, EMA20/VWAP 부근 눌림·회복, ADX 하한을 결합한다. 코드상 다음을 추가 검토할 가치가 있다.

- “직전봉도 EMA20 아래” 확인에서 직전 종가를 현재 EMA20과 비교한다. 각 봉 시점의 EMA를 비교하는 규칙과 다르다.
- 최근 저가가 지지선 허용 상단 이하인지 확인하지만 근접 범위의 하한은 없다. 지지선보다 훨씬 깊은 하락도 다른 조건을 충족하면 눌림 후보가 될 수 있다.
- 과거 저가를 현재 EMA/VWAP와 비교하므로 “당시 지지선 접촉”의 엄밀한 판정은 아니다.
- MTF 시각 정렬과 ADX 계산 문제를 상속한다.

이 항목은 기존 의도와 대조할 설계 검토 사항이다. 각각을 수정하면 반드시 성과가 개선된다는 뜻은 아니다.

## 8. 파라미터와 데이터 계약 보강

### 8.1 범위·관계 검증

StrategyParamUtils는 파싱을 제공하지만 공통 범위 검증을 하지 않는다. 일부 전략은 별도 Number 전용 파서를 사용하여 문자열 숫자가 기본값으로 대체되는 차이도 있다.

[StrategyConfigCreateRequest.java](web-api/src/main/java/com/cryptoautotrader/api/dto/StrategyConfigCreateRequest.java)의 최상위 Bean Validation만으로 configJson 내부의 전략별 범위·관계가 검증되지는 않는다.

검증할 예:

- 기간·lookback·gridCount의 양수 조건.
- EMA/MACD의 fastPeriod<slowPeriod.
- RSI/Stoch의 0≤oversold<overbought≤100.
- 호가 우세 임계값의 0.5<threshold<1.
- VWAP thresholdPct>0.
- 유효한 multiplier, 손절·익절, 강도 포화 분모.
- NaN/Infinity, 음수 잔량·거래량, 잘못된 OHLC.
- 강도 0~100과 신호·제안 가격의 일관성.

설정 오류는 세션 생성 때 설명 가능한 오류로 거부하고, 실시간 데이터 부족·무변동은 이유가 있는 HOLD로 처리하는 식으로 구분하는 것이 좋다. 잘못된 설정을 영구 HOLD로 숨기지 않는다.

### 8.2 동일 이름의 퍼센트 단위 불일치

| 전략 | stopLossPct 값 | 의미 |
|---|---|---|
| HEIKIN_ASHI_STOCH | 1.5 | 1.5% |
| MACD_STOCH_BB | 0.02 | 2% |

동일 키를 복사하면 의도와 크게 다른 손절가가 만들어질 수 있다. 단위를 공통화하거나 Ratio/Pct를 이름에서 구분하고, 저장된 기존 설정의 변환 정책을 마련해야 한다.

### 8.3 최소 데이터와 워밍업

getMinimumCandleCount()는 기본값 기준의 고정 수다. 변경 파라미터의 필요 수와 다를 수 있다.

- 일부 경로는 데이터 부족 HOLD를 반환한다.
- MACD_STOCH_BB의 minRequired에는 volumePeriod가 포함되지 않아 큰 volumePeriod에서 sma() 예외가 가능하다.
- 백테스트 최대 창 500을 넘는 기간 설정은 단순히 시작점만 늦춰 해결되지 않는다.
- ADX 이력 부족 시 필터가 생략되는 전략은 “필터 활성 설정”과 실제 적용 상태가 다를 수 있다.

requiredCandleCount(params), 안정화를 위한 warmup, 상위봉 필요 데이터, 엔진 조회 상한을 함께 설계한다.

### 8.4 복합 파라미터의 이름 공간

WeightedStrategy는 같은 params를 하위 전략에 전달한다. adxPeriod, fastPeriod, multiplier 등의 같은 키가 여러 성분에 동시에 영향을 줄 수 있다.

전체 공통 설정과 성분별 설정을 구분하고, 최종 해석된 파라미터를 결과에 저장한다. 파라미터 하나의 변경이 어떤 성분에 적용되었는지 설명 가능해야 한다.

## 9. 시장 국면과 운영 정책

초기 문서의 국면 표는 정책 분류이지 실제 성과 증명이 아니다.

| 국면 | 기존 정책에서 적합으로 분류한 단일 전략 | 비적합 분류 |
|---|---|---|
| TREND | EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT | GRID, VWAP, BOLLINGER |
| RANGE | VWAP, BOLLINGER, GRID, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI | EMA_CROSS, MACD, SUPERTREND, ATR_BREAKOUT |
| VOLATILITY | ATR_BREAKOUT, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI | GRID, SUPERTREND |

TRANSITIONAL의 처리 방식은 스케줄러·적응형 전략·라우터마다 구분해야 한다. 모든 계층이 “이전 국면 유지”라는 하나의 규칙을 쓰는 것으로 설명하면 안 된다.

FVG·HA·Volume Delta 등의 미분류 전략이 기존 활성 상태를 유지하는 정책도 적합성 검증과 같지 않다. 특히 공통 ADX 수정 후에는 국면별 표본 자체가 달라지므로 과거 국면별 성과표를 다시 계산해야 한다.

## 10. 검증 기록과 재현 입력

### 10.1 기존 테스트 재실행

2026-09-15 재검토에서 실행:

```powershell
.\gradlew.bat :strategy-lib:test --rerun-tasks --console=plain
```

결과: BUILD SUCCESSFUL, 관련 3개 태스크 실제 실행. 테스트 XML 기준 11개 suite, 82개 테스트, 실패 0, 오류 0.

| suite | 테스트 수 |
|---|---:|
| ATR_BREAKOUT | 11 |
| BOLLINGER | 5 |
| ConflictingSignal | 2 |
| EMA_CROSS | 4 |
| HEIKIN_ASHI_STOCH | 7 |
| MACD | 8 |
| ORDERBOOK_IMBALANCE | 12 |
| RSI | 7 |
| SUPERTREND | 8 |
| VOLUME_DELTA | 14 |
| VWAP | 4 |
| 합계 | 82 |

초기 검토의 UP-TO-DATE 확인과 달리 이번에는 재컴파일·재실행했다. 이 문서 편집 단계에서 같은 테스트를 또 실행한 것은 아니다.

검토 당시 전용 테스트 파일이 없었던 시장 전략은 FVG, GRID, STOCHASTIC_RSI, MACD_STOCH_BB다. 전용 파일의 부재와 다른 모듈에서 간접 검증되는지 여부는 구분한다.

### 10.2 별도 입력 재현 결과

재컴파일된 strategy-lib 클래스에 JShell로 입력했다. 별도 테스트 소스 파일은 추가하지 않았다.

| 항목 | 입력 | 실제 출력 |
|---|---|---|
| ATR 무변동 | OHLC=100, 거래량=100, 시간 오름차순 100봉, 기본 params | ArithmeticException: / by zero |
| RSI 무변동 | 동일 입력 | SELL |
| ADX 개수 | 동일 100봉, adxList(candles,14,60) | 61 |
| GRID 왕복 | 100봉 모두 high=110, low=100. 마지막 종가 102→108→102, 같은 전략 인스턴스 | BUY→SELL→HOLD |
| Volume Delta 무변동 | OHLC=100인 20봉, 거래량 앞 10봉=100/뒤 10봉=200 | SELL |

다음은 동일 경계 입력을 재현할 핵심 Java 코드다. JShell에서 strategy-lib/build/classes/java/main을 classpath로 지정하여 실행할 수 있다.

```java
import com.cryptoautotrader.strategy.*;
import com.cryptoautotrader.strategy.grid.*;
import com.cryptoautotrader.strategy.atrbreakout.*;
import com.cryptoautotrader.strategy.rsi.*;
import com.cryptoautotrader.strategy.volumedelta.*;
import java.util.*;
import java.math.*;
import java.time.*;

Candle c(int i, double o, double h, double l, double close, double v) {
    return Candle.builder().time(Instant.EPOCH.plusSeconds(i * 3600L))
        .open(BigDecimal.valueOf(o)).high(BigDecimal.valueOf(h))
        .low(BigDecimal.valueOf(l)).close(BigDecimal.valueOf(close))
        .volume(BigDecimal.valueOf(v)).build();
}

List<Candle> flat = new ArrayList<>();
for (int i = 0; i < 100; i++) flat.add(c(i,100,100,100,100,100));
try {
    System.out.println(new AtrBreakoutStrategy().evaluate(flat, Map.of()).getAction());
} catch (Exception e) {
    System.out.println(e);
}
System.out.println(new RsiStrategy().evaluate(flat, Map.of()).getAction());
System.out.println(IndicatorUtils.adxList(flat,14,60).size());

List<Candle> gridWindow = new ArrayList<>();
for (int i = 0; i < 100; i++) gridWindow.add(c(i,102,110,100,102,100));
GridStrategy grid = new GridStrategy();
System.out.println(grid.evaluate(gridWindow, Map.of()).getAction());
gridWindow.set(99,c(100,108,110,100,108,100));
System.out.println(grid.evaluate(gridWindow, Map.of()).getAction());
gridWindow.set(99,c(101,102,110,100,102,100));
System.out.println(grid.evaluate(gridWindow, Map.of()).getAction());

List<Candle> zeroRange = new ArrayList<>();
for (int i = 0; i < 20; i++)
    zeroRange.add(c(i,100,100,100,100,i < 10 ? 100 : 200));
System.out.println(new VolumeDeltaStrategy().evaluate(zeroRange, Map.of()).getAction());
```

### 10.3 이번에 수행하지 않은 검증

- 실제 시장 데이터의 신규 성과 백테스트·walk-forward.
- 전체 백엔드 통합 테스트와 엔진 전체 동작 재실행.
- 실계좌·PAPER 운영 상태 확인.
- 상태 오염의 병렬 실행 재현 및 실제 손실 규모 측정.
- 수정안 구현과 그 성과 비교.

따라서 코드상 결함과 재현 결과는 보고할 수 있지만, 특정 수정이 수익률을 몇 % 개선한다고 주장하지 않는다.

## 11. 수정 및 재검증 계획

### 11.1 구현 수정의 권장 순서

| 순서 | 작업 | 완료 판정 기준 |
|---|---|---|
| 1 | 백테스트·운영 전략 생성 통합 | 같은 전략명·설정으로 동일 성분·가중치·필터 및 신호 |
| 2 | 실행별 전체 전략 트리 상태 격리 | A→A 반복, A→B→A, 병렬 실행에서 동일 입력 결과 일치 |
| 3 | ADX 정의·시계열·개수 수정 | 기준값·전환 구간·창 길이 테스트 통과 |
| 4 | GRID 해제·cooldown·ATR=0 처리 | 왕복 후 재진입, 고정 창 cooldown 만료, 무변동 HOLD |
| 5 | MTF 시각 정렬·완성봉 정책 | 조회 시작점·결측·시간 경계에 대한 일관된 집계 |
| 6 | RSI/delta 경계·파라미터·단위 | 무방향 입력 정책 준수, 잘못된 설정의 명시적 거부 |
| 7 | 강도 보정·필터 기여도 | 후보→투표→체결 전 과정과 A/B 영향 설명 가능 |
| 8 | 새 버전의 성과 검증 | 기간 외 검증·거래 수·비용·PAPER 실행 검증 충족 |

### 11.2 우선 추가할 회귀 테스트

- COMPOSITE_BREAKOUT의 서비스 경로별 실제 구성·Veto 일치.
- COMPOSITE의 시점별 국면 전환과 신규 BUY 차단 재현.
- 같은 백테스트 반복·순서 변경·동시 실행의 재현성.
- ADX의 추세→횡보, 횡보→추세 전환과 기준 구현 비교.
- GRID 매수→매도→동일 가격대 재매수 및 거부·미체결 처리.
- MACD_STOCH_BB의 고정 길이 500봉 창과 결측봉·재시작.
- ATR=0, RSI 무변동, delta 무변동·거래량 증가.
- H1→H4 집계 시작 오프셋, 첫/마지막 부분 그룹, 결측봉.
- FVG 임계 gap·EMA 반대 방향·중간봉 도지/작은 몸통.
- StochRSI 급반전·점진적 탈출·SELL 거래량 부족.
- 모든 전략의 유효 입력에서 유한한 강도 0~100 및 제안 가격 계약.

단순히 BUY 또는 HOLD 둘 다 허용하는 테스트만으로 방향 계산의 정확성을 보장하기 어렵다. 핵심 분기는 의도한 신호가 확정되는 입력과 예상값으로 검증한다.

### 11.3 성과 검증 설계

```text
구현 동등성·재현성 확보
→ 전략 버전 고정
→ 종목 × 시간프레임 × 국면 × 기간 분할
→ 학습 구간에서 설정 선택
→ 독립 평가 구간 / walk-forward
→ 수수료·스프레드·슬리피지·미체결 민감도
→ PAPER 데이터·실행 검증
→ 사전에 정한 승격 기준으로 판단
```

측정 항목은 총수익률 외에 거래 수, 기대값, Profit Factor, MDD, 보유시간, 최대 연속 손실, 종목별 편차, 신뢰구간을 포함한다. 겹치는 보유기간·연속 신호의 의존성을 고려하고, 같은 평가 구간을 반복 튜닝에 사용하지 않는다.

단순 기준 전략, 각 성분 제거 버전, 필터 제거 버전을 함께 비교한다. “좋은 결과가 나온 조합”보다 “여러 구간에서 왜 동작하는지 설명 가능한 조합”을 찾는 것이 목적이다.

## 12. 초기 문서의 정정 이력

| 초기 문서 | 재검토 후 정정 |
|---|---|
| 검증된 COMPOSITE 전략이 실전 중심 | 운영상 허용된 복합 전략이 있으나 현재 백테스트 구성·상태 동등성에 결함 |
| HEIKIN_ASHI가 구조적으로 가장 완성도 높음 | 조건과 제안 SL/TP가 명시적이나 상대적 우수성·수익성 미확정 |
| MACD histogram 확대가 추가로 신호를 보수적으로 만듦 | 현재 교차 조건에서 자동 성립하는 중복 비교 |
| cooldown은 고정 창이면 문제가 될 수 있음 | 실제 최대 500봉 백테스트 경로에서 시간 경과를 표현하지 못함 |
| GRID는 미체결 때만 상태가 오염될 수 있음 | 정상 BUY→SELL 신호 후에도 매수 레벨 미해제 재현 |
| FVG gap 무효화 테스트 누락을 결함처럼 제시 | 현 모멘텀 전략과 별도 gap 추적 기능 확장을 구분 |
| 파라미터 검증·전용 테스트 부재를 모두 P0 | 성과 전제와 공통 계산·상태 결함부터 수정하도록 우선순위 변경 |
| 전략 테스트 UP-TO-DATE 성공 | 2026-09-15 --rerun-tasks로 82개 실제 재실행 성공, 경계 문제는 별도 재현 |
| 최소 캔들 수 부족은 대체로 HOLD | 일부 파라미터는 예외·필터 생략·조회 상한 문제까지 있으므로 경로별 구분 |

**최종 판단:** 전략 아이디어는 다양하지만, 지금은 전략을 추가하거나 가중치를 미세 조정하기 전에 백테스트·운영 구성, 공통 지표, 상태 격리, 시간봉 집계의 신뢰성을 확보해야 한다. 이 전제가 충족되어야 기존 성과표와 전략 간 비교가 개발 의사결정의 근거가 될 수 있다.
