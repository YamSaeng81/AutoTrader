# DAILY_SMA20 BTC 전향 모의 — 최소 지원안(총괄 v1.142 §110 판단 2)

2026-11-10 · 개발 제출 · **운영 반영·세션 생성 없음. 실행 승인 아님.**

## 목표

SID 203과 같은 조건의 독립 모의 세션을 만든다.

| 조건 | 값 |
|---|---|
| 전략 | DAILY_SMA20 |
| 코인·주기 | KRW-BTC·H1 |
| 초기 자본 | 20,200원 |
| 투자 비율 | 0.995 |
| 1회 매수 상한 | 20,000원 |
| 손절 하한 | 5% |
| 시간 청산 | maxHoldHours 0(끔) |

관문·청산·비용도 가능한 범위에서 같게 고정한다. 같게 할 수 없는 것은 아래 차이표에 남긴다.

## 현행 b5cd1d0의 막힘

- `PaperTradingStartRequest.initialCapital`에 `@DecimalMin("100000")`이 걸려 있다.
- PAPER 세션에는 `maxInvestment`가 없다. 매수금 = 가용 × 투자 비율이다.
- 10만 원에 투자 비율만 낮추면 매수금은 맞출 수 있다. 하지만 남는 현금, 다음 매수금, 손익률의 분모가 달라진다. 그래서 "같은 2만 원 조건"이 아니다.

## 최소 후보 `2aa2a498`

브랜치 `candidate/sma20-paper-fwd-b5cd1d0`, 운영 `b5cd1d0` 기점이다. 고친 곳은 세 곳이고 시험 1개 클래스를 더했다. 미배포 V95와 기존 main 변경은 넣지 않았다.

| 파일 | 변경 |
|---|---|
| `PaperMaxInvestment.java`(새 파일) | `strategy_params.paperMaxInvestment`를 읽는다. 키가 없으면 null. 숫자가 아니거나 5,000 미만이면 거절. 최소 원금 규칙은 키가 없으면 100,000, 있으면 5,000이다. |
| `PaperTradingStartRequest.java` | `@DecimalMin("100000")` → `@DecimalMin("5000")`으로 바꾸고, 위 규칙의 `@AssertTrue`를 더했다. 키 없는 10만 원 미만 요청은 종전처럼 400으로 거절된다(메시지만 다름). |
| `PaperTradingService.java` | `createSession`에서 같은 규칙을 다시 확인한다(DTO를 거치지 않는 호출 대비, `IllegalStateException`). `executeBuy`의 매수금 = `min(가용 × 비율, 상한)` 뒤 둘째 자리 버림. LIVE `executeSessionBuy`와 같은 식이다. |

설계상 고른 점:
- **DDL이 없다.** 새 열 대신 기존 `virtual_balance.strategy_params`(jsonb, V74)를 쓴다. 세션 생성은 이미 이 값을 그대로 옮긴다(`PaperSessionStrategyParamsTest`가 고정).
- **규칙 지문에 실린다.** 상한 키는 `strategy.params`에 들어가므로 이 세션은 다른 지문이 된다. 기존 PAPER 표본과 섞이지 않는다.
- **청산 해석은 바뀌지 않는다.** `ExitRuleOverrides`는 모르는 키를 무시한다. 그래서 `isPresent()`가 거짓으로 남고, 전략 제안 SL과의 `min()`도 LIVE와 같다.
- **잘못된 상한은 세션을 만들지 않는다.** 조용히 무시하면 08-24 파라미터 유실 사고처럼 실험군이 대조군과 같아진 채 아무도 모르게 된다.
- 다른 길도 있다. `virtual_balance.max_investment` 열을 더하면 의미는 더 분명하다. 하지만 DDL과 마이그레이션 번호가 필요하고, 그 번호는 미배포 V95와 충돌한다. 그래서 고르지 않았다. 총괄이 열 방식을 원하면 바꾼다.

## 국소 시험(로컬 H2, 서버 실행 없음)

- 새 시험 `DailySma20PaperForwardPathTest` 6개. 운영 생성 경로 `PaperTradingService.start`(전략 활성 관문 포함)와 운영 스케줄러가 부르는 `runStrategy()`를 그대로 부른다. 캔들은 `market_data_cache` H1 500봉이다.
  1. SID 203 조건: 매수금 20,000(20,099 아님), 남는 현금 200, 손절가 ≤ 진입가 × 0.95, 청산 오버라이드 없음, BUY 신호 실행 기록, 키 없는 세션과 지문이 다름
  2. 상한 50,000: 걸리지 않음 → 20,099
  3. 키 없음, 100,000: 종전과 같음 → 99,500
  4. 서비스 경로: 키 없는 20,200, 상한 4,999, 상한 "abc"는 모두 거절되고 세션 수가 그대로
  5. 요청 검증 7가지
  6. HTTP `POST /api/v1/paper-trading/sessions`: 키 없는 20,200 요청은 400이고 세션이 생기지 않음
- 결과: 새 6/6, 기존 `PaperSessionStrategyParamsTest` 4/4, `PaperLiveAlignmentTest` 12/12. 합계 22/22, 1분 11초. 근거는 `candidate/pass/`다.
- 변이 확인: 매수금 상한 한 줄만 지우면 새 시험 1/6이 실패한다(기대 20,000, 실제 20,099.00000000). 근거는 `candidate/mutation-*`이고, 그 줄은 되돌렸다.
- 이미 통과한 전체 회귀는 다시 돌리지 않았다.

## LIVE SID 203과 PAPER 후보의 차이표(b5cd1d0 코드 기준)

| # | 항목 | LIVE `evaluateAndExecuteSession` | PAPER `runSessionStrategy` | 처리 |
|---|---|---|---|---|
| 1 | 전략·주기 | `StrategyRegistry` DAILY_SMA20, `timeframe` 전달 | 같음 | 같음 |
| 2 | 캔들 조회 | `CANDLE_LOOKBACK`, `market_data_cache` | 같음 | 같음 |
| 3 | 캔들 신선도 | `freshCandles`(CandleFreshnessGuard)가 낡은 캐시를 버림 | 없음 | **차이**: 수집이 멈추면 PAPER는 낡은 종가로 판정할 수 있음. 기록부에 수집 공백을 남긴다 |
| 4 | 닫힌 봉·요구 캔들 수 | 닫힌 봉 판정, 457봉 미만이면 평가 생략 | 같음 | 같음 |
| 5 | EMA200·RANGE·BLACK_SWAN 관문 | 같은 함수, 같은 순서 | 같음 | 같음 |
| 6 | BTC 관문 | `blocksEntry()`: 발동 **또는 판정 불가**면 차단 | `triggered()`만 차단 | **차이**: BTC 자료가 판정 불가일 때 PAPER만 진입할 수 있음 |
| 7 | 서킷 브레이커·MDD·전역 위험 `checkRisk` | 있음 | 없음 | **차이**: 2만 원 단일 세션이라 발동 가능성은 낮지만 같지 않다 |
| 8 | 진입 시점 | 회차 접수 → 수집 마감 D=C+W → 승인 때 서킷·위험·최신 캔들·BLACK_SWAN·BTC 재확인 → 실행 만료 E=D+W(H1은 W=5분) | 평가한 그 틱에 바로 체결 | **차이**: 가격이 최대 약 10분 어긋나고, 만료·재확인 거절이 PAPER에는 없다 |
| 9 | 매수금 | `min(가용 × 0.995, 20,000)` 버림 | 후보로 같은 식 | **같아짐**(후보) |
| 10 | 체결·비용 | 시장가 실제 체결, 수수료 0.05% | 종가 × (1 ± 0.1%), 수수료 0.05% | **차이**: 슬리피지는 가정값이다. 쌍 비교에서 실제 체결가와 대조한다 |
| 11 | 손절·익절 설정 | `ExitRuleCalculator` ATR식, 하한 5%, 전략 제안과 `min()` | 같음 | 같음 |
| 12 | 손절 감시 | 실시간 시세 1~5초 + 틱 | 60초 틱, 최신 H1 행의 종가 | **차이**: 손절 체결가와 시각이 어긋날 수 있다 |
| 13 | TP 트레일링 | `updateTrailingStops`, 실시간 시세 | 같은 함수, 60초 틱 | 빈도가 다르다 |
| 14 | 시간 청산·전략 SELL 관문 | `shouldTimeStop`, `SignalExitGate` 같은 상수 | 같음 | 같음 |
| 15 | §8 자금 예약·풀 한도·거래소 상태 비상 정지 | 있음 | 없음 | **차이**(PAPER는 의도적으로 제외) |
| 16 | 세션 수명 | CREATED → 시작 → RUNNING | `start()`가 바로 RUNNING | 시작 시각이 다르다. 쌍 비교 구간을 따로 고정 |
| 17 | 지문 | engine LIVE | engine PAPER, 상한 키 포함 | 표본을 합치지 않는다 |

PAPER가 의도적으로 빼는 LIVE 관문(`StrategyLiveStatusRegistry`, `WalkForwardValidationGate`, §8)은 `PaperTradingService` 주석에 있는 기존 설계다. 이번에 바꾸지 않았다.

## 운용안(검토용, 실행 금지)

**0. 배포**
- 이 후보는 앱을 다시 빌드하고 기동해야 한다.
- 운영 기점이 그때도 `b5cd1d0`이면 단독 후보로, 바뀌었으면 그 기점에 다시 얹는다.
- 배포 시점(12-01 전 점검 창 또는 다음 정비)과 절차는 총괄이 정한다. 203 CREATED·S·F 핀에 주는 영향도 그때 따로 판정한다.

**1. 생성 전 읽기 전용 확인**
- DAILY_SMA20 H1이 활성인지(`strategyEnablementGate`). 203이 생성돼 있으므로 생성 당시에는 활성이었다.
- RUNNING PAPER 수가 120 미만인지(사전 등록 66 포함).
- KRW-BTC H1 봉이 457개 이상 연속인지.

**2. 생성 요청(1회, 사람 실행)**

`POST /api/v1/paper-trading/sessions`

```json
{"strategyType":"DAILY_SMA20","coinPair":"KRW-BTC","timeframe":"H1","initialCapital":20200,
 "investRatio":0.995,"stopLossPct":5,"maxHoldHours":0,
 "strategyParams":{"paperMaxInvestment":20000,"forwardTag":"SMA20-FWD-1"},"enableTelegram":false}
```

- `forwardTag`는 전략이 무시하는 표지다. 지문에 실려 이 세션을 다른 세션과 구별한다.
- 코드 SHA는 파라미터에 넣지 않는다. 세션이 도는 동안 배포가 바뀌면 파라미터 속 SHA는 낡은 값이 되기 때문이다.

**3. 버전 기록부**

`docs/`에 SMA20 전향 기록부를 둔다. 남길 항목:
- 세션 ID와 생성 시각, 생성 때의 운영 HEAD·이미지
- 이후 모든 배포의 시각·SHA·이미지(F1·N06 핀 기록과 같은 출처)
- 수집 공백, 종료 시각

포지션의 진입 시각을 이 기록부의 구간에 대응시켜 거래별 코드 버전을 정한다. 스키마는 바꾸지 않는다.

**4. 종료·중단 조건(결과 전에 고정)**
- 종료: 12-01 제한시험 종료와 같은 날, 또는 총괄이 정한 날.
- 중단: DAILY_SMA20이나 PAPER 청산 경로를 바꾸는 배포가 있으면 그 시각을 경계로 구간을 나눈다. 세션을 지우지 않고, 튜닝하지 않는다.
- 세션 수동 정지는 장애·결함 때만 하고, 경위를 남긴다.

**5. 쌍 비교 구간**
- LIVE 203 시작(12-01 08:50 KST 목표)부터 LIVE 종료까지로 따로 고정한다.
- PAPER가 먼저 시작해 쌓인 자료는 "선행 구간"으로 따로 표시하고, 쌍 비교에 넣지 않는다.

**6. 비교 지표(성과 판정 아님)**
- 거래별 진입·청산 시각 차이, 가격 차이(슬리피지 실측 대 0.1% 가정), 청산 사유 일치
- 관문·만료로 한쪽만 진입한 건수
- 실현손익(비용 뒤)

DAILY_SMA20은 일봉 마감에 하루 최대 한 번 신호를 낸다. 12-01 전후 몇 건의 거래로 수익성 검증이 끝났다고 보지 않는다.

**7. 사전 등록 계약**
- 새 세션은 522~587 밖의 새 ID이고 사전 등록 표본·분모에 넣지 않는다.
- 다만 RUNNING 세션 수와 평가 루프 부하가 사전 등록 세션과 같은 루프를 공유한다. 영향이 없다고 보는지는 총괄 판단으로 남긴다.
