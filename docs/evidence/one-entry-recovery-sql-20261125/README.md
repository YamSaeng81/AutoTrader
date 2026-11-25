# 1회 제한 B → 구판 복구 전 읽기 확인 SQL v2 — 정리 대기 조건 정정과 계약 대조 (v1.242 §3-3)

2026-11-25 KST. 로컬 코드·스키마 판독만 했다. **SQL 실행·PG 추가 시험·운영 호출은 0회다.** 원본 초안([one-entry-b §4](../one-entry-b-20261125/README.md))은 고치지 않았다. 별도 사본: [recovery-check-v2.sql](recovery-check-v2.sql). 판독 기준은 후보 c43e1f1a 블롭이다. 대조한 부분은 d81c3f2f와 같다(B의 제품 차이 4파일에 아래 경로는 없다).

## 1. 정정

| 열 | 초안 | v2 | 근거 |
|---|---|---|---|
| 정리 대기 | `position_cleanup` 세션 행 전체 | **`done_at IS NULL`만** | V87: `done_at`은 CLOSED 확인 때만 쓰고 행은 지우지 않는다. 부분 색인 `idx_position_cleanup_open … WHERE done_at IS NULL`. `PositionCleanupRepository.countPending`도 같은 식이다. 초안은 정상 완료한 과거 행까지 세어 복구를 계속 막는다 |
| 미정산 | 없음 | **추가**: `settlement_final = false AND fee_source IS NOT NULL AND exchange_order_id IS NOT NULL` | V82 `settlement_final`의 기본값은 FALSE다. 그래서 거래소에 나가지 않은 주문도 false로 남는다. 대상 조건은 `OrderRepository.findSettlementDue`와 같다(시각 조건만 뺐다) |

## 2. 기존 정지 계약과 대조

| 확인 | 제품 코드의 기준 | v2 |
|---|---|---|
| 활성 주문 | `TradingConstants.ACTIVE_ORDER_STATES` = PENDING·INTENT_READY·SUBMITTING·UNKNOWN·SUBMITTED·PARTIAL_FILLED | 같은 6개(초안과 같다) |
| 정지 경고 | `stopSession`과 `completeCleanup`의 `countUnresolvedSubmissions` = INTENT_READY·SUBMITTING·UNKNOWN | `_info` 열로 보인다. 활성 6개의 부분집합이라 차단 조건이 넓어지지는 않는다 |
| 열린 포지션 | 정지는 OPEN 포지션을 청산한다. 확인 없는 취소 매수(`isUnconfirmedCancelledBuy`)와 FILLED 매수 대기는 size=0이어도 포지션을 열어 둔다(`settleEmptyPosition`). 반영 대기 매도는 CLOSING으로 남는다 | `status IN ('OPEN','CLOSING') OR size <> 0`. `size`는 NOT NULL이라 COALESCE는 뺐다 |
| 근거 없는 종결 | `isTerminalWithoutNoFillProof`: 근거 코드 없는 FAILED·확인 없는 CANCELLED. 정리 작업은 이를 사람 확인으로 둔다 | `_info` 열이다. 근거 코드 목록(`NOT_EXECUTED_CODES` 6개)은 코드 상수라서 여기서는 `failure_code IS NULL`만 센다. 0이 아니면 해당 행을 따로 읽는다 |
| 세션 상태 | `stopSession`은 STOPPED로 바꾼다. 비상 정지 경로는 `EMERGENCY_STOPPED`를 쓴다 | 통과 조건은 STOPPED만으로 유지한다. EMERGENCY_STOPPED를 통과로 볼지는 총괄이 정한다 |

**통과 = status STOPPED이고 `_block` 4열이 모두 0.** STOPPED 하나만으로는 완료로 보지 않는다. 거래소 잔량(장부 밖 체결)은 이 SQL로 확정하지 않는다. 기존 계좌 대조(A3·장부) 절차가 맡는다. 열 이름은 Flyway V4·V12·V52·V81·V82·V86·V87에 있다. 컬럼 존재는 마이그레이션 판독으로만 확인했고 PG 실행으로는 확인하지 않았다.

## 3. 남은 것

- PG 실행 검증과 운영 사용은 별도 검토·승인 전이다.
- 근거 없는 종결 행이 있을 때 사람이 어떻게 처리할지는 기존 정리 작업의 '사람 확인' 범위다. 이 SQL은 판정하지 않는다.

체크섬: [SHA256SUMS](SHA256SUMS)(자신 제외).
