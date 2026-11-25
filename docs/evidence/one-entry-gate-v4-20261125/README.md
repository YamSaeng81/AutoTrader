# 1회 제한 시험 세션 종료·복구 게이트 v4 (총괄 v1.245 §2, 제출)

2026-11-25 KST. v3([one-entry-gate-v3-20261125](../one-entry-gate-v3-20261125/README.md))와 그 체크섬, r1·r2 원문은 바꾸지 않았다. 이 폴더는 별도 사본이다. 운영에서는 실행하지 않았다. 검증은 격리 PG [r3](../one-entry-pg2-20261125/R3.md)에서 했다.

| 파일 | 역할 |
|---|---|
| [recovery-gate-v4.sql](recovery-gate-v4.sql) | 읽기 전용. v3에서 보유 판정 1줄과 머리 주석만 바뀌었다 |
| [gate_check.py](gate_check.py) | 판정 논리는 v3와 같다(머리 설명 3줄만 다름). PASS 0 · HOLD 2 · FAIL 1 |

## v3 → v4 변경

| 항목 | v3 | v4 |
|---|---|---|
| 보유 차단 `open_or_residual_positions` | `status IN ('OPEN','CLOSING') OR size <> 0` | **`status IN ('OPEN','CLOSING')`**. size가 0이어도 이 두 상태는 차단한다. CLOSED의 양수 size만으로는 세지 않는다 |

- **열 이름은 호환 목적으로 유지한다.** 뜻은 "제품이 보유/청산 중으로 관리하는 포지션 수"다. 실제 거래소 잔량을 확정하는 필드가 아니다.
- **바꾼 이유:** c43e1f1a `LiveTradingService.finalizeSellPosition`은 전량 매도를 CLOSED로 닫지만 size를 0으로 만들지 않는다(체결 수량 유지, 기존 규칙·B 무관). v3는 정상 왕복(r2 sid 23)을 FAIL로 판정했다. r2 합성 정상 사례는 `position(…, "CLOSED", "0")`으로 size 0을 넣었기 때문에 이 차이를 잡지 못했다.
- **그대로 둔 것:** 알 수 없는·NULL 포지션 상태 FAIL(`unknown_position_status`), 활성 주문, `fill_accounting_pending`, 반영 표시 없는 FILLED 매도, 미완료 정리, 미입증 종결 HOLD, 미해결 정산 불일치 HOLD, 대상·REAL·등록값·정수 maxEntries=1·BUY 행 >1 FAIL, STOPPED만 자동 PASS·EMERGENCY_STOPPED HOLD. CLOSED라는 이유로 이 검사 중 어느 것도 풀리지 않는다.
- **r2 합성 `g_residual_size`의 뜻이 바뀐다.** 그 사례(CLOSED·size 0.00001·나머지 정상)는 v3에서 FAIL이었고 v4에서는 PASS가 된다. 이는 §2-1이 정한 의도다. r2 기록은 고치지 않았고, r3에서 다시 돌리지도 않았다. 같은 모양을 r3의 `c_closed_size_sold`가 PASS로 확인했다.
- **한계:** CLOSED는 거래소 보유 0을 입증하지 않는다. 장부 밖 체결이나 잘못 닫힌 포지션의 잔량은 A3·계좌 대조의 별도 범위다. 이 수정은 계좌 읽기나 운영 복구를 승인하지 않는다. 그 밖의 한계(구판 maxEntries 무시, 정지 경합 미해결)는 v3 README와 같다.

## 참조 연결

- 시험 원문 초안 [live-smoke-draft-20261125](../live-smoke-draft-20261125/README.md)의 gate 단계는 v4 경로로 바꿨다. 변경 전 체크섬은 그 폴더의 `SHA256SUMS-v3ref`에 보존했다.
- 해시: `recovery-gate-v4.sql`·`gate_check.py`는 [SHA256SUMS](SHA256SUMS)에 있다. 실행 전 고정본은 `SHA256SUMS-pre`(커밋 a5f08222)이며 두 파일 해시가 같다.

체크섬: [SHA256SUMS](SHA256SUMS)(자신·SHA256SUMS-pre 제외).
