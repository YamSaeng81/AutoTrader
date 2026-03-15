# 코인 자동매매 전략 분석 및 개선 제안

작성일: 2026-03-15

------------------------------------------------------------------------

# 1. 가장 큰 구조 문제

## Market Regime (시장 상태) 판단 없음

현재 전략은 다음과 같은 문제를 가질 수 있다.

예시

-   강한 상승장 → Bollinger SELL 발생
-   강한 하락장 → RSI BUY 발생

즉 서로 반대 전략이 동시에 작동할 수 있다.

이것은 자동매매에서 가장 흔한 손실 구조이다.

------------------------------------------------------------------------

# 해결 방법: Market Regime Detector

시장 상태를 먼저 판단해야 한다.

## 시장 상태

1.  TREND
2.  RANGE
3.  VOLATILITY

## 판단 지표

-   ADX
-   Bollinger Bandwidth
-   ATR

### 예시 로직

ADX \> 25\
→ TREND

ADX \< 20 AND BB width narrow\
→ RANGE

ATR spike\
→ VOLATILITY

------------------------------------------------------------------------

# 전략 매칭 구조

  시장         전략
  ------------ -------------------------
  TREND        Supertrend + EMA + MACD
  RANGE        Bollinger + RSI + Grid
  VOLATILITY   ATR Breakout

------------------------------------------------------------------------

# 2. 전략 중복 문제

현재 전략은 실제로 같은 전략이 여러 개 있다.

  전략         의미
  ------------ ------
  EMA CROSS    추세
  MACD         추세
  SUPERTREND   추세

즉 추세 전략이 3개다.

또한

  전략        의미
  ----------- ----------
  RSI         평균회귀
  Bollinger   평균회귀

따라서 실제로는

10 전략 → 4 전략

정도 구조이다.

------------------------------------------------------------------------

# 추천 전략 구조

## TREND

-   Supertrend
-   EMA
-   ADX

## MEAN REVERSION

-   RSI
-   Bollinger
-   VWAP

## BREAKOUT

-   ATR Breakout
-   Volume Spike

## MICROSTRUCTURE

-   Orderbook imbalance

------------------------------------------------------------------------

# 3. 가장 중요한 요소: 리스크 관리

현재 구조에는 다음이 없다.

-   Stop Loss
-   Position Size
-   Max Loss

자동매매에서 가장 중요한 요소이다.

------------------------------------------------------------------------

# Risk Engine 예시

Max risk per trade = 1%

Position size 계산

position = account × risk / stop distance

예시

계좌 = 10000\$ risk = 1% 손절 거리 = 2%

포지션 ≈ 500\$

------------------------------------------------------------------------

# 추가 리스크 제한

-   daily max loss = 5%
-   max open trades = 3
-   max leverage = 3x

------------------------------------------------------------------------

# 4. Orderbook 전략 문제

Orderbook imbalance 전략은 다음 문제 존재

-   스푸핑
-   레이어링

그래서 다음 구조가 필요

orderbook + trade flow

즉

-   orderbook
-   real trade volume

조합 사용

------------------------------------------------------------------------

# 5. Grid 전략 위험

Grid 전략은 추세장에서 위험하다.

예

BTC 30k → 60k 상승

Grid short → 계좌 손실

### 해결

range breakout → grid stop

또는

ADX \> 25 → grid off

------------------------------------------------------------------------

# 6. 자동매매 시스템 구조

추천 구조

MARKET DETECTOR\
↓\
STRATEGY SELECTOR\
↓\
SIGNAL ENGINE\
↓\
RISK ENGINE\
↓\
EXECUTION ENGINE

현재 시스템은 SIGNAL ENGINE 중심 구조이다.

------------------------------------------------------------------------

# 7. 추천 실전 전략 구조

TREND\
Supertrend + EMA

ENTRY\
RSI pullback

EXIT\
ATR trailing stop

즉

trend + pullback 전략

자동매매에서 안정적이다.

------------------------------------------------------------------------

# 8. 전략 추천 순위

1.  Supertrend
2.  RSI
3.  Bollinger
4.  ATR Breakout
5.  Orderbook

------------------------------------------------------------------------

# 9. 제거 추천 전략

다음 중 하나만 사용

-   EMA CROSS
-   MACD

둘은 거의 동일 전략이다.

------------------------------------------------------------------------

# 10. 가장 큰 개선 포인트 TOP5

1.  Market Regime Detector 추가
2.  Risk Engine 추가
3.  Strategy Voting Engine
4.  Volume Filter
5.  Multi Timeframe

------------------------------------------------------------------------

# Multi Timeframe 예시

1H trend 확인

5M entry

이 구조는 성능을 크게 향상시킨다.

------------------------------------------------------------------------

# 최종 평가

현재 전략 구조는

개인 프로젝트 기준 상위 수준이다.

하지만 실전 자동매매 수준으로 발전하려면

필수 요소

1.  Market Regime
2.  Risk Engine
3.  Strategy Selector
4.  Multi Timeframe

이 4가지가 필요하다.
