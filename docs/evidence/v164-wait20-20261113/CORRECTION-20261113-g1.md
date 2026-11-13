# 정정 메모 — 409차 대기 조건 근거 (2026-11-13, 총괄 v1.165 §1·§3-1 반영)

원문 [README](README.md)·[SHA256SUMS](SHA256SUMS)·`runs/`는 수정하지 않았다. 이 메모는 그 뒤 확인한 사실만 덧붙인다. 근거는 [G1 조사](../g1-submit-boundary-20261113/README.md)에 있다.

1. **게이트500 실패 42건의 분류.** 대기 경합 **40건·13클래스**와 `StopVersusInFlightExitRaceTest` (다) LIVE·DYNAMIC **2건**(G1)이다. §3의 "14클래스 보완"에서 StopVersus 변경은 그 클래스의 대기 보완이며, (다) 2건의 원인은 아래 2다.
2. **§4 "보호 매도 미제출" 관측의 원인.** 제품의 커밋 뒤 제출 공백이 아니라 **시험 읽기**였다. 시험 스레드가 정지 목 응답 안(정지 트랜잭션)에서 저장소로 매도를 읽어, 의도 커밋 뒤·claim 전에 한 번 읽은 `INTENT_READY/null` 엔티티가 영속성 컨텍스트에 남았다. 같은 주문은 실제로 claim·거래소 대역 호출·`SUBMITTED` 기록까지 끝났다. `dispatching=false`는 claim 종료 뒤의 정상 해제다. 20초 단언 실패가 정지 트랜잭션을 롤백시켜 세션은 `RUNNING`, 정리 대기는 없었다.
3. **§3 "미변경(4)" 정정.** `LateOrderSubmissionAfterStopTest`, `N16InvalidPriceReproTest` ③, `N15PgLockWaitExpiryTest` 정리 대기는 게이트500에서 작업 전 통과(또는 정리 뒤 작업 실행)가 재현돼 보완했다. N16의 "이미 보완"은 ①②④에만 맞고 ③에는 맞지 않았다. O1O3는 양성 대기 뒤 횟수를 세므로 같은 공백이 아니다.
4. **외부 연결 사실.** 409차 진단 실행(`n05-links-88f06345/runs/diag-stopversus-dispatching`)의 XML에 실제 Upbit WebSocket 연결 성공 1줄과 `KRW-SVRD2` 구독 전송 1줄이 있다. 같은 18클래스 실행은 대역이 없는 클래스에서 실제 WS 연결·구독이 있었다(410차 재실행에서 6클래스 확인, G1 근거 §6). 공개 시세 구독이며 인증·주문 호출은 없다.
