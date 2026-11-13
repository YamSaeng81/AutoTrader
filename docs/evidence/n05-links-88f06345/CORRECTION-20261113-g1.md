# 정정 메모 — N05 후보판 연결표 G1 (2026-11-13, 총괄 v1.165 §2·§3 반영)

원문 [README](README.md)·[SHA256SUMS](SHA256SUMS)·`runs/`는 수정하지 않았다. 근거는 [G1 조사](../g1-submit-boundary-20261113/README.md)에 있다.

- **G1 원인.** §3의 `INTENT_READY`·`dispatching=false`는 정지 트랜잭션 안 시험 스레드의 낡은 읽기(영속성 컨텍스트)였다. 같은 주문은 claim 커밋 → 등록부 해제 → 거래소 대역 호출 → `SUBMITTED` 기록까지 끝났다. "등록이 빠진 경계"는 없었다.
- **폴러 회복은 추정이 아니라 직접 호출로 확인했다.** G1 상태(CLOSING + 이 프로세스 소유 `INTENT_READY` + 등록부 없음)를 만든 뒤 `pollActiveOrders()` → `FAILED`·`WITHDRAWN_DISPATCH_LOST`(외부 호출 0) → 청산 확인 OPEN → 정리 작업 재매도 1회·거래소 접수·3회 반복에도 중복 없음(LIVE·DYNAMIC). 회복 전 청산 확인은 활성 의도가 있는 동안 CLOSING을 유지했다. 실제 5초·10초 주기의 회복 시간은 G3에 남는다.
- **외부 연결.** `runs/diag-stopversus-dispatching` XML에 실제 Upbit WebSocket 연결 성공·`KRW-SVRD2` 구독 전송이 각 1줄 있다. 이후 G1 시험은 WS·텔레그램을 대역으로 둔다.
- G2(과거 60초 지연)와 G1의 관계를 보여 주는 근거는 이번 조사에서 나오지 않았다. G2는 미확정 그대로다.
