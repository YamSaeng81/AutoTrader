# 상한 null 보완 · LIVE/PAPER 같은 입력 비교 · LIVE_ALIGNED 선택 모의 후보 (야간 A·B·C)

2026-11-10 · 개발 제출 · 총괄 v1.143 검토와 v1.144 야간 지시의 A·B·C · **운영 반영·서버 빌드·세션 생성 없음**

## 후보와 기점

| 커밋 | 내용 | 부모 |
|---|---|---|
| `2aa2a498` | SMA20 전향 모의 최소 지원(1회 매수 상한), 앞 제출 | `b5cd1d0d`(운영 소스) |
| `192d4459` | A — 상한 명시적 null 거절 | `2aa2a498` |
| `0913daf3` | B — 같은 입력 LIVE↔PAPER 비교 실행기(시험만) | `192d4459` |
| `581312ff` | C — `paperExecutionMode=LIVE_ALIGNED` 선택 모드, 실행기 공통 대역 분리 | `0913daf3` |

- 브랜치는 `candidate/sma20-paper-fwd-b5cd1d0`이고 `581312ff`까지 push했다. main의 V95와 슬리피지 열은 섞지 않았다.
- 전체 차이는 `candidate/diff-b5cd1d0-581312ff.patch`, 커밋 목록은 `candidate/commits.txt`에 있다.

## A — 상한 명시적 null

| 확인 | 결과 |
|---|---|
| 수정 | `PaperMaxInvestment.from`이 `containsKey`로 키 유무를 가른다. 값이 `null`·불리언·객체·숫자 아님·5,000 미만이면 생성 전에 거절한다. 오류 문구에 입력 원문을 붙이지 않는다. |
| 시험 표(서비스·DTO 같은 13행) | 키 없음(100,000 통과 / 20,200 거절), 20000, "20000", 0, −20000, "4999", "abc", true, 객체, 명시적 null(100,000·20,200), 원금 4,999 |
| HTTP | JSON `"paperMaxInvestment": null`이면 400, 세션 0 |
| 통과 | `DailySma20PaperForwardPathTest` 8/8 (`a-null/pass/`) |
| 실패 재현 | 수정본에서 키 판단 한 줄만 옛 `get(KEY) == null`로 되돌리면 8건 중 4건 실패 (`a-null/mutation/`) |

- 실패 재현은 수정한 뒤에 변이로 했다. 지시의 "실패 먼저" 순서와 다르다.
- 이 변이는 2aa2a498 원본과 완전히 같지 않다. 원본은 오류 문구에 입력 원문을 붙였다.
- 키가 없는 경로는 바뀌지 않았다. 최소 원금 100,000원, 매수금 = 가용 × 비율 그대로다.

## B — 같은 입력·같은 시각 비교(종전 PAPER)

실행기는 `AbstractSma20SameInputFixture`와 `Sma20LivePaperSameInputTest`다. 두 엔진의 운영 호출부를 그대로 부른다.

- LIVE 경로:
  - 평가 `evaluateAndExecuteSession`
  - 회차 승인 `EntryPriorityRounds.processDue`
  - `approveQueuedEntry`
  - 예약과 의도 `reserveAndPrepareIntent`
  - 실행기
  - 실시간 처리기 본문 `onRealtimePriceEvent`
- PAPER 경로: `runSessionStrategy`

조건과 대역:
- 세션 조건은 SID 203과 같은 숫자다. 20,200 · 0.995 · 상한 20,000 · 손절 하한 5% · 시간 청산 0.
- 거래소 주문과 텔레그램은 목으로 막았다. 외부 주문은 0이다.
- 기대(같음/다름)는 실행 전에 소스를 읽고 적었다. 관측이 기대와 다르면 시험이 실패한다.

**1회차 11/12 실패**(`b-legacy/run1-fail/`, 당시 시험 원문 포함):
- 실패한 시나리오는 S06이다. LIVE 실시간 처리기는 `@Async(marketDataExecutor)`다. 시험이 처리 결과보다 먼저 DB를 읽었다.
- 손절은 실제로 일어났다. 로그 `실시간 손절 발동 … −6%`가 `market-data-1` 스레드에 남아 있다.
- 하네스를 고쳤다. 프록시 없이 같은 메서드 본문을 시험 스레드에서 부른다. 대기나 sleep은 넣지 않았다.
- **2회차는 12/12다**(`b-legacy/run2-pass/report.tsv`). C 커밋 뒤에도 같은 표가 바이트까지 같았다.

| 축 | LIVE | 종전 PAPER |
|---|---|---|
| 정상 진입 시점 | 회차 마감 D 뒤 승인에서 체결 | 평가 즉시 체결 |
| 정상 진입 매수금·손절/익절 거리 | 20,000 · 5% · 8% | 같음 |
| 낡은 입력(완료 봉 3시간 전) | 판정 불가, 진입 없음 | 낡은 봉으로 진입 |
| BTC 판정 불가(대조 코인 KRW-ETH) | HOLD "BTC_MARKET_GUARD 판정 불가" | 진입 |
| 승인 E 직전 / E 정각 | 진입 / "승인 실행 만료" | 회차 없음, 평가 시각에 이미 진입 |
| 신호 뒤 +2% | 승인 시각 가격 12,744,900 | 평가 시각 가격 × 1.001 |
| 장중 SL(저가 −6%, 종가 −1%) | 실시간 시세로 손절(CLOSING) | 유지(1분 주기, 캐시 마지막 종가) |
| 장중 TP 근처(고가 +4%) | 실시간 고가로 TP 상향 12,929,826 | 상향 없음 12,869,850 |
| 낡은 캔들 + 종가 SL 아래 | 유효한 현재가가 없으면 판정 안 함. 회복 뒤 손절 | 3시간 전 종가로 즉시 손절 |
| 전역 위험 한도 / 공유 풀 한도 | 거절 | 진입 |
| 전략 SELL 청산 | STRATEGY_SIGNAL, 매도 접수 | STRATEGY_SIGNAL, 즉시 CLOSED |

- KRW-BTC 세션의 BTC 판정 불가는 KRW-BTC 세션에서는 일어나지 않는다. BTC 관문이 최신성을 통과한 자기 캔들을 쓰기 때문이다. 캔들이 낡으면 낡은 입력 축이 된다.
- S01의 "신호 로그" 행은 실행 전 "다름"으로 적었지만 문구가 같았다. 단언하지 않은 표 행이고, 기대를 고치지 않고 그대로 둔다.

## C — `paperExecutionMode=LIVE_ALIGNED` 선택 모드

키가 없으면 종전 PAPER와 같다. 근거는 두 가지다.
- 종전 실행기 표가 C 뒤에도 바이트까지 같았다.
- 관련 회귀가 모두 통과했다.

값은 `LIVE_ALIGNED`만 받는다. null, 다른 문자열, 소문자, 숫자, 불리언은 DTO와 서비스 모두에서 거절한다(C00).

**LIVE와 같은 함수와 빈을 재사용한다**
- 캔들 최신성:
  - `CandleFreshnessGuard.problem`에 공식 점검 공백 면제를 함께 쓴다.
  - LIVE의 공유 stale 지도와 경보는 건드리지 않는다.
- BTC 관문: `btcGate`와 `blocksEntry`를 쓴다.
- 진입 회차: 같은 `EntryPriorityRounds`에 kind `PAPER`로 접수한다. 승인 차례에 다음을 다시 확인한다.
  - 실행 중인지, 이미 보유했는지
  - 최신 캔들
  - 블랙스완, BTC
  - 만료 E
  
  체결가는 승인 시각의 최신 종가다.
- 청산 순서: 익절 → 시간 → 손절. 트레일링은 실시간 처리기에서만 한다.
- 실시간 시세 손절과 트레일링: 낡은 시세와 가격이 아닌 값은 무시하고, 코인별 5초 간격이다.
- 캔들을 못 쓰는 회차: 유효한 실시간 현재가로만 가격 청산을 판정한다.

**REAL에 닿는 변경 두 가지(동작 불변 범위)**
1. `EntryPriorityRounds.ORDER` 첫 키를 "PAPER면 1"로 바꿨다. PAPER 후보는 실자금 후보를 모두 승인한 뒤에 차례가 온다.
   - PAPER 후보가 없는 회차는 첫 키가 모두 0이라 종전 순서와 같다.
   - `EntryPriorityRoundsTest` 10/10, `EntryPriorityEndToEndTest` 2/2.
2. `WsSubscriptionManager`에 출처 `PAPER_ALIGNED`를 추가했다.
   - LIVE_ALIGNED RUNNING 세션의 코인만 구독에 더한다. 그런 세션이 없으면 출처가 없고 재연결도 없다.
   - 구독에 더해진 코인은 LIVE REST 폴백의 조회 대상에도 들어간다. 운영 생성 전 부하 검토 항목이다.

**결과**: LIVE_ALIGNED 실행기 14/14(`c-aligned/run1/aligned.tsv`).

| 축 | 같은가 |
|---|---|
| 정상 진입: 평가 직후 대기, 승인 결과, 매수금, 기준가(슬리피지 제외), 손절 5% / 익절 8% 거리 | 같음 |
| 낡은 입력, BTC 판정 불가(사유 문구까지), E 직전 진입, E 정각 만료 | 같음 |
| 신호 뒤 +2%: 승인 시각 가격 | 같음 |
| 장중 SL: 실시간 저가 뒤 STOP_LOSS / 장중 TP: 같은 익절가 상향 | 같음 |
| 시세 무효 → 회복: 판정 안 함 → STOP_LOSS | 같음 |
| 전략 SELL 청산 사유 | 같음 |
| 승인 순서 | LIVE가 먼저, PAPER가 다음(설계) |
| 전역 위험 한도, 공유 풀 한도 | **다름**: LIVE만 거절(REAL 계좌 관문, 모의 계좌 대응물 없음) |
| 체결 방식, 상태 | 어댑터 차이: LIVE 접수 뒤 정산(CLOSING) / PAPER 즉시 체결(CLOSED), 슬리피지 0.1% |

**A11 — 같은 체결가로 정산한 대조**
- LIVE 매수 주문을 운영 폴러 경로 `checkOrderStatus`로 정산했다. 체결가는 PAPER와 같고, 업비트식 수수료(체결 금액의 0.05%)를 넣었다.
- 일치한 것:
  - 체결 뒤 가용 잔액: 200 = 200
  - 수량: 0.00159824 = 0.00159824
  
  두 행은 실행 전에 "수량 다름"으로 적었다. 수량 행은 단언하지 않았고 관측대로 남긴다.
- 남은 차이: **평균가에 수수료를 넣는 방식**. LIVE 12,507,511.39(체결가), PAPER 12,513,765.14(수수료 포함). 매수 수수료는 9.995 대 10.
  - PAPER의 손익률은 같은 시세에서 약 0.05%p 낮게 계산된다.
  - 그래서 전략 SELL 관문의 +0.3%·−0.3% 경계와, 손절가가 없을 때의 손절률 판정이 0.05%p만큼 늦거나 빨라질 수 있다.
  - **공통 판단에 남은 차이로 기록한다.** 어댑터 탓으로 덮지 않는다. 이번 후보에서는 고치지 않았다. 고치려면 종전 PAPER 손익 회계와 갈라지는 결정이 필요하다.
- 매도 쪽 정산 대조는 하지 않았다.

**남은 차이(후보 범위 밖)**
- REAL 계좌 관문: 전역 위험 한도 `checkRisk`, 세션 서킷 브레이커, 공유 풀·슬롯·손실 한도, §8 예약.
- LIVE 실시간 처리기는 급변(30초 −1.5%/+2%)이면 1초 간격으로 다시 확인한다. 이 예외는 옮기지 않았고 PAPER는 5초 고정이다.
- 캔들 불가 회차의 시간 청산: LIVE는 가격 없이 시장가로 매도한다. 모의 체결에는 가격이 필요해서 유효한 현재가가 없으면 다음 회차로 미룬다.
- 손절 점검 기록 `recordSlCheck`와 미점검 경보 `warnStaleSlCheck`, 낙폭 경고 알림은 PAPER에 없다.
- 체결 실패, 부분 체결, 미확인(UNKNOWN) 주문은 어댑터 가정상 없다.

## 관련 회귀(`c-aligned/regress/`)

20클래스가 모두 통과했다.
- 목록: BtcGateSupplyEndToEnd 3, BtcGateUnknownEntry 3, CandleFreshnessGuard 8, DailySma20LiveOrderPath 24, DailySma20PaperForwardPath 8, DynamicPaperBuySameTickWatchlist 7, DynamicPaperLiveRuleParity 6, DynamicPaperTrading 9, EntryPriorityEndToEnd 2, EntryPriorityRounds 10, KillCriteriaPaperVisibility 7, N15EntryExpiry 6, N15FinalApprovalBoundary 6, PaperLiveAlignment 12, PaperSessionStrategyParams 4, PaperStopUnderConcurrentWrite 4, WsSubscriptionUnion 3, 같은 입력 실행기 12와 14.
- **N15PgLockWaitExpiryTest 4건은 건너뛰었다.** PostgreSQL이 필요한 시험이고, 이번 실행은 H2다.
- 전체 회귀는 다시 돌리지 않았다.

**변이**(`c-aligned/mutation/`): LIVE_ALIGNED의 BTC 관문을 `blocksEntry()`에서 `triggered()`로 바꾸면 A03 1건이 실패한다.

## 하지 않은 것

- 운영 배포, 서버 빌드, 재기동, 세션 생성, DB 쓰기, 실제 API 주문, 알림.
- 외부 WebSocket 연결. LIVE_ALIGNED 시험은 `UpbitWebSocketClient`를 목으로 막았다.
- 운영 계약(전략 조건, SL/TP 배수, 한도, 만료, 주기) 변경.
