# N3 구판 실제 조회 — 새 ExitReason 값 1행에서 구판 바이너리 엔티티 조회와 관문 3·4판 (총괄 v1.194 §2-3)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 로컬 격리 PG(`cat-rt-pg` 15432)만 사용했다. 운영 DB, 호환 바이너리 설계, 행 수정, 운영 관문 교체는 하지 않았다.

## 방법

- 구판 사본 두 개를 새로 만들었다(분리 HEAD): `D:/tmp/n3old-0e1d2a02`(운영 0e1d2a02), `D:/tmp/n3old-b5cd1d0d`(복구판 b5cd1d0d). 두 사본 모두 제품 코드는 그대로다. 미추적 시험 파일 [OldBinaryNewEnumPgTest.java](OldBinaryNewEnumPgTest.java) 하나만 넣었다.
- 시험마다 새 DB에서 Flyway를 실행한다. 이어서 후보가 저장하는 형태의 행을 둔다(CLOSED·size 0·투자액 3000). `exit_reason='ENTRY_REJECTED'`는 구판 열거형에 없는 값이라 JDBC로 그 열에만 쓴다.
- 단계 1: 새 값 행이 있는 LIVE·DYNAMIC 세션에서 구판의 실제 조회 경로 P1~P7을 부른다. 이 행은 DB에 그대로 남긴다.
- 단계 2: 다른 세션에 같은 행을 두되 사유만 NULL로 한 대조군이다.
- 두 구판의 ExitReason 8값은 같다: STOP_LOSS, TAKE_PROFIT, TRAILING_STOP, TIME_STOP, STRATEGY_SIGNAL, BLACK_SWAN, FORCED_STOP, UNKNOWN.

## 결과 (2회차 `n3old2-*` 기준, 두 구판 동일)

| 경로 | 새 값 행 있음 | 사유 NULL 대조 |
|---|---|---|
| P1 `positionRepository.findBySessionKindAndSessionId`(DYNAMIC) | **실패** `IllegalArgumentException: No enum constant …ExitReason.ENTRY_REJECTED` | 정상(1) |
| P2 `checkCircuitBreaker`(DYNAMIC, 회차 시작 차단 판단) | **실패** 같은 예외 | 정상(false/null) |
| P3 `isInLossCooldown`(DYNAMIC 매수 경로) | **실패** 같은 예외 | 정상(false) |
| P4 `checkCircuitBreaker`(LIVE) | **실패** 같은 예외 | 정상 |
| P5 `getSessionPositions`(LIVE 화면) | **실패** 같은 예외 | 정상(1) |
| P6 `getAllPositions` 첫 쪽(전체 목록) | **실패** 같은 예외 | — (단계 1 행이 남아 대조 제외) |
| P7 원시 SQL 집계 | 정상 `ENTRY_REJECTED=2` | 정상 `(null)=2` |

- 구판은 이 값 1행만 있어도 그 세션의 서킷 브레이커, DYNAMIC 매수 전 손실 냉각, LIVE 화면, 전체 목록에서 엔티티 조회가 예외로 끝난다. 원인은 값 하나다(NULL 대조에서는 정상).
- 1회차(`n3old-*`)의 P3는 관측 도구 결함이었다: Spring 프록시에 리플렉션으로 호출해 필드가 null이었고, 결과가 NPE로 나왔다. 2회차는 실제 빈에 호출했다(`AopTestUtils.getUltimateTargetObject`). 1회차 원문은 지우지 않고 보존했다. 나머지 경로는 1·2회차 결과가 같다.

## 관문 대조 (같은 DB, 새 값 행 2개가 남은 상태)

| DB | 3판 `v159-20261113/rollback-gate-v3` | 4판 초안 `dec-costbasis-followup-20261119/rollback-gate-v4-draft` | 새 값 행 전후 |
|---|---|---|---|
| n3old2_0e1d2a02 | `ROLLBACK_GATE_OK` rc=0 (구판 복구 허용 → 위 예외 경로로 들어간다) | **`STOP_INCOMPATIBLE`** rc=3, 장부 없음 | 2행, id·사유·행 md5 동일 |
| n3old2_b5cd1d0d | `ROLLBACK_GATE_OK` rc=0 | **`STOP_INCOMPATIBLE`** rc=3, 장부 없음 | 2행 동일 |

- 4판 초안은 구판이 실제로 실패하는 입력에서 복구를 멈춘다. 원래 값은 바뀌지 않았다([rows_before_gate](n3old2-0e1d2a02/rows_before_gate.txt) = [rows_after_gate](n3old2-0e1d2a02/rows_after_gate.txt)).
- 3판은 같은 입력을 통과시킨다. 따라서 새 값을 저장하는 후보를 배포한 뒤에 3판으로 구판 복구를 판정하면 안 된다.
- 관문 교체와 복구 명령 실행 승인은 이 제출 범위가 아니다.

## 파일

`n3old*-*/`에는 실행마다 head.txt, gradle.log, rc.txt, xml/, old_binary_new_enum.json이 있다. 2회차에는 gate-v3.txt, gate-v4.txt, ledger-v3/, rows_before/after_gate.txt도 있다. 실행 스크립트는 [seq1.sh](seq1.sh)·[seq2.sh](seq2.sh)다. 체크섬 [SHA256SUMS](SHA256SUMS).
