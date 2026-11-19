# 445차 REAL 매수 요약 누락 보완 후보 (총괄 v1.194 §3) — 별도 가지, 통합 SHA 미포함

2026-11-19 KST, 개발 세션 제출(최종 포함 판정 전). 가지 `candidate/real-buy-summary-60df3437`(사본 `D:/tmp/rbs-60df3437`), 기점 60df3437. 60df3437에는 합치지 않았다. 실제 텔레그램 요청은 없다(시험 설정 알림 꺼짐, SpyBean 호출 인자만 본다).

## 1. 커밋 [commits.txt](commits.txt)

| 커밋 | 내용 |
|---|---|
| 9a297069 | 실패 시험 먼저: `RealBuySummaryPgTest`(새 클래스, 23 확인 항목) |
| c8cb48a0 | 시험 R3: 늦은 정정 재조회 백오프 경과를 재현(`settlement_next_check_at`만 당김) |
| 44b5bf4b | 시험 도우미: 매수 이벤트의 null 손익 인자 허용(`List.of` → `Arrays.asList`) |
| **69c81c3d** | 제품 `OrderSettlementService` 1파일 +43/−10. [product.diff](product.diff) |
| 596ed4b0 | 통합 영향: `AbcCrossPgTest` 같은 도우미 null 허용(1줄) |

**제품 변경.**
- `observeAndApplyBuyFill`은 원자 회계 트랜잭션(`REQUIRES_NEW`)이 커밋돼 돌아온 뒤에만 그 커밋의 증가분을 기존 요약 버퍼(`bufferTradeEvent`)에 1건 싣는다.
  - 증가분: 수량 Δ, 대금 Δ÷수량 Δ 단가, 수수료 Δ.
  - 표시는 `종류#ID`, 사유는 주문의 신호 사유다.
- 수량 Δ가 0 이하이면 싣지 않는다. 회계가 롤백되면 예외가 나서 적재 코드에 닿지 않는다.
- 버퍼 예외는 경고 로그만 남기며 정산은 이미 커밋돼 있다.
- 즉시 알림·외부 전송·표/아웃박스·정산/손익 변경은 없다. 원자 매수는 `processFilledOrder`를 거치지 않으므로 기존 BUY 적재와 겹치지 않는다.

## 2. 결과

| 실행 | 입력 | 결과 |
|---|---|---|
| [rbs-base-9a297069](rbs-base-9a297069/) | 시험 1판 + 제품 60df3437 | 기점 실패 rc=1(이벤트 0). R3 입력이 백오프에 막혀 증가분이 반영되지 않는 것을 발견 → c8cb48a0 |
| [rbs-base-c8cb48a0](rbs-base-c8cb48a0/) | 시험 보정 + 제품 60df3437 | **기점 실패 18/23**: 실패는 모두 "매수 이벤트 없음"이다. 반영 수량(25·19.99)은 맞다 |
| [rbs-fix1-c8cb48a0-dirty](rbs-fix1-c8cb48a0-dirty/) | + 제품 변경(미커밋) | 시험 도우미 NPE(null 인자) → 44b5bf4b |
| [rbs-fix2-44b5bf4b-dirty](rbs-fix2-44b5bf4b-dirty/) | + 제품 변경(미커밋, diff sha256 906eed87… = 69c81c3d의 커밋 diff) | **23/23** rc=0 |

확인 항목 (LIVE·DYNAMIC 각각):

- 완전 체결 25@300·수수료 3.75 → BUY 1건(`종류#ID`, 손익 null). 적재 수량은 확정 체결 수량과 같다.
- 같은 응답을 반복하면 추가 0이다. 수수료만 3.80으로 정정되면 회계에는 반영되고 이벤트 추가는 0이다.
- 매도 요약은 SELL 1건이고 BUY 0건이다.
- 부분 체결 10 다음 취소 확정 19.99 → 두 번째 이벤트는 9.99(수수료 1.4985)뿐이고 합은 19.99다. 같은 취소 응답을 반복하면 추가 0이다.
- 미체결 취소 → 0건.
- 회계 롤백(포지션 갱신 트리거 예외) → 수량 미반영, 0건. 트리거를 제거한 뒤 재처리하면 1건(25)이다.
- 버퍼 예외 → 주문 FILLED·수량 25·대기 해제·포지션 25가 유지되고, 버퍼 호출은 1회다.

## 3. 통합 영향 (전체 회귀 아님)

변경은 `observeAndApplyBuyFill`과 `bufferTradeEvent`에만 영향이 있다. 이를 직접 부르는 H2 클래스와, 매수 체결 경로를 지나는 PG 클래스를 각각 새 DB에서 돌렸다.

| 실행 | 범위 | 결과 |
|---|---|---|
| [rbs-impact-69c81c3d/h2-8](rbs-impact-69c81c3d/h2-8/) | AlignedV147/V148Fixes·BuySettlementEdge·EngineParity·PositionLimit·TelegramMinimalAlert·TelegramTestIsolation·SubmitPollRace (XML 9) | **93/93** |
| rbs-impact-69c81c3d/pg-* | SettlementRounding 16·CostBasisBoundary·CostBasisFollowup·CostBasisRisk (`cat-rt-pg`) | 모두 통과 |
| rbs-impact-69c81c3d/pg-AbcCrossPgTest | 교차 | **실패**: 시험 도우미 `List.of`가 이제 생기는 BUY 이벤트의 null 손익에서 NPE를 낸다 → 596ed4b0(도우미 1줄) |
| rbs-impact-69c81c3d/pg-{ClosingIntentAtomicity, LiveLossWindow, PartialSellRealizedPnl, RealLossThreeFormula} | — | 실행 실수: 비밀번호 기본값이 없는 클래스를 15432에 붙여 컨텍스트 적재에 실패했다. 아래에서 다시 돌렸다 |
| [rbs-impact2-596ed4b0](rbs-impact2-596ed4b0/) | AbcCross(`cat-rt-pg`), CR·LW·PP·RL(`v152-pg` 15437, 종전 실행과 같은 곳) | AbcCross **19/19**, ClosingIntentAtomicity **5/5**, PartialSell **7/7**, RealLossThreeFormula **8/8**, LiveLossWindow **1/7** |

- LiveLossWindow 1/7은 이 후보 때문이 아니다. 60df3437에서도 실패 문구까지 같고, C 단독 b408a223에서도 1/7, 운영 기점 0e1d2a02에서는 7/7이다. 기록은 [통합 README §3-1](../dec-integ-abc-20261119/README.md)에 있다.
- 교차 시험 X2의 청산 알림 표시(`DYNAMIC#id`)·거절 문구 없음 단언은 그대로 통과한다.

**포함 영향**: 후보를 넣으면 제품 1파일과 시험 도우미 2곳(새 시험, AbcCross 1줄)이 바뀐다. 60df3437 위에 그대로 얹을 수 있다. 최종 포함 여부는 따로 판정한다.

체크섬 [SHA256SUMS](SHA256SUMS).
