# 1회 제한 시험 세션 종료·복구 게이트 v3 (총괄 v1.243 §3-1, 제출)

2026-11-25 KST. v2([one-entry-recovery-sql-20261125](../one-entry-recovery-sql-20261125/README.md))와 그 체크섬은 바꾸지 않았다. 이 폴더는 별도 사본이다. 운영에서는 실행하지 않았다. 검증은 격리 PG 1묶음 [one-entry-pg2 r1](../one-entry-pg2-20261125/README.md)에서 했다(게이트 29/29). 판독 기준은 c43e1f1a(= d81c3f2f와 같은 경로)다.

| 파일 | 역할 |
|---|---|
| [recovery-gate-v3.sql](recovery-gate-v3.sql) | 읽기 전용. 대상이 없어도 1행이 나온다(`found=f`). 마지막 열은 `end_mark=END`다 |
| [gate_check.py](gate_check.py) | 출력, psql 종료코드, 사전 등록 파일로 판정한다. PASS 0 · HOLD 2 · FAIL 1 |

## v2 → v3 변경

| 항목 | v2 | v3 | 근거 |
|---|---|---|---|
| 미입증 종결 | 참고 열. FAILED는 code NULL만 셌다 | **보류(HOLD).** FAILED는 code가 `NOT_EXECUTED_CODES` 6개 밖이면(NULL 포함) 센다. CANCELLED는 `cancel_confirmed_at` NULL이면 센다 | `OrderExecutionEngine.isTerminalWithoutNoFillProof` 그대로. 비어 있지 않은 임의 문자열을 무거래 근거로 보지 않는다 |
| 미정산 | `settlement_final=false…`를 차단으로 썼다 | **참고로 내림**(`settlement_recheck`) | 제품은 `settlement_final`을 TRUE로 세우지 않는다(OrderSettlementService 223행). 정상 세션도 2로 나온다(r1 실측). 그래서 v2 식은 정상 종료를 막는다 |
| 회계·반영 대기 | 없음 | **차단**: `fill_accounting_pending` TRUE, 반영 표시 없는 FILLED 매도(`sell_applied_at` NULL) | V83 · V87·V94 |
| 정산 불일치 | 없음 | **보류**: `order_settlement_discrepancy.resolved_at` NULL | V82 — 사람 확인 전까지 미해결 |
| 상태 | 활성 6만 셌다 | 알려진 주문 상태 9 밖, 포지션 상태 OPEN/CLOSING/CLOSED 밖, 세션 상태 5 밖이면 **FAIL** | 알 수 없는 상태는 통과하지 않는다 |
| 대상·등록 | 없음 | 대상 ID, REAL, 전략·종목·주기, 10000/0.995/9950, `maxEntries` jsonb `number`이면서 텍스트 `1`이 사전 등록 파일과 같아야 한다. BUY 행 >1이면 FAIL | 등록값이 없거나 빈칸·불일치면 FAIL |
| 실패 형태 | 없음 | 0행, 부분 출력(END 없음·열 수 다름), psql rc≠0, 숫자 아닌 sid는 FAIL | — |
| 세션 상태 | STOPPED | STOPPED만 자동 PASS. **EMERGENCY_STOPPED는 HOLD**(v1.243 §2) | — |

**이미 증거로 해결된 경우:** 제품이 남기는 근거 필드만 인정한다. 확인된 취소(`cancel_confirmed_at`), 허용 코드, 재조회로 바뀐 상태(FILLED 등), 해결된 불일치(`resolved_at`)가 그렇다. 이런 행은 현재 값으로 다시 판정되므로 자동으로 빠진다. 사람의 판단(V91 수동 조정, V94 격리 해제, 메모)은 이 SQL에서 해제 근거가 되지 않는다. V91은 주문을 UNKNOWN으로 두므로 활성 차단(FAIL)이 그대로 남는다. 미입증 종결은 HOLD로 남는다. 어느 쪽이든 총괄이 별도 기록으로 판정한다. 과거 종결을 지우거나 무거래로 재분류하지 않는다.

**한계:** PASS는 DB 판정이다. 거래소 잔량(장부 밖 체결)은 A3·장부 대조로 따로 확인한다. 구판(0e1d2a02·d81c3f2f)은 `maxEntries`를 무시한다. 그래서 복구 뒤 이 세션을 다시 start하면 안 된다. 두 구판은 DAILY_SMA20 세션의 정지 뒤 시작을 거절한다(`LIMITED_TEST_STRATEGIES`, 거래소 복구 자동 재시작도 거절, 0e1d2a02·d81c3f2f 192·523·560행 판독, B는 +2행). 이 거절은 DB 직접 수정까지 막지는 못한다. 정지 경합은 해결되지 않았다.

체크섬: [SHA256SUMS](SHA256SUMS)(자신 제외).
