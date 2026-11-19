# 445차 C 후속 — BUY 수량 NULL·예약 거절 성과 제외·구판 복구 관문 4판 초안·배포 전 읽기 v3 (총괄 v1.193 §2·§3)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 기점은 고정 비용 후보 **32d5dafd**이고, 후속 고정 SHA는 **b408a223**이다(가지 `candidate/cost-basis-boundary-b861bdf9`, 로컬 사본 `D:/tmp/costbasis-0e1d2a02`). 운영 접속·운영 DB 읽기·배포·생성은 하지 않았다. 시험은 로컬 H2와 격리 PG(`cat-rt-pg` 15432, `v152-pg` 15437)에서 클래스마다 새 DB로 돌렸다.

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| a533c0f4 | 시험 `CostBasisFollowupPgTest`(새 클래스, 47단언). 기존 59·48단언 클래스는 바꾸지 않았다 |
| b408a223 | 제품 5파일 +20/−3 + `ExitReasonRecordingTest` 기대값 1줄. [product.diff](product.diff) · [test.diff](test.diff) |

- **N1**: `CONFIRMED_NO_TRADE`에서 `COALESCE(nb.filled_quantity, 0) = 0`을 `nb.filled_quantity IS NOT NULL AND nb.filled_quantity = 0`으로 바꿨다. 정상 경로는 저장 직전에 0을 넣는다(`OrderEntity.prePersist`, V4 열 기본값 0). 따라서 NULL은 미보고 행이나 수동 행에만 생기며, 이번 변경으로 그런 행은 판정 불가(④)가 된다. 정상 취소·실패 매수의 무거래 판정은 그대로다(F1 기점 단언). Java 쪽은 SQL 결과만 읽는다(`RiskManagementService` r[9]).
- **N2**: `ExitReason.countsTowardStrategyPerformance()`에서 `ENTRY_REJECTED`를 뺐고 `isTrade()`를 추가했다. 예약 거절 행은 다음 집계에서 빠진다. 행과 사유 자체는 남는다.
  - 킬 기준 세션 집계 `aggregateClosedTradesPerSession`
  - REAL 가중치 조회 4개
  - 보고서 `LogAnalyzerService`의 청산 수·승패·코인별 통계
  - LIVE 성과 요약 `getPerformanceSummary`(세션 id로만 모으므로 같은 id의 DYNAMIC 행이 섞일 수 있다)
  - 이 조회들이 쓰던 `investedKrw > 0` 필터는 예약 거절 슬롯(투자액 = 배정액 > 0)을 걸러내지 못했다.
- **바꾸지 않은 것**: 연속 손실 계산(확정 무거래가 연속을 끊는 종전 계약), 기존 사유의 성과 포함 여부(FORCED_STOP·UNKNOWN 제외 그대로), DYNAMIC 화면 통계(이미 size 0 제외), PAPER 집계.

## 2. 결과 (저장 XML/JSON 기준)

| 실행 | 입력 | 결과 |
|---|---|---|
| [base-fail](base-fail/) | 시험 a533c0f4 + 제품 32d5dafd | **35/47**. 실패 12 = F1 LIVE·DYNAMIC 각 4(CANCELLED·취소 확정·수량 NULL과 FAILED·수량 NULL을 승인), F3 4(성과 포함 true, 킬 기준 거래 1, 보고서 청산 2/기대 1, LIVE 성과 거래 5→6). F2(매도 대금/수량 NULL)는 기점에서도 통과해 수수료 NULL과 같은 계약임을 확인 |
| [fix1](fix1/) | 제품 b408a223 | **47/47** |
| [affected-h2](affected-h2/) | b408a223, 종전 영향 29클래스 + 바뀐 소비자 6클래스 | 39 XML **257시험 중 실패 1 → 기대값 갱신 뒤 9/9**. 실패는 `ExitReasonRecordingTest`의 "UNKNOWN·FORCED_STOP만 빠진다" 단언으로, 지시된 계약 변경이다. 갱신 전 XML은 `.before-expect-update.xml.txt`로 보존 |
| [affected-pg](affected-pg/) | b408a223 | CostBasisBoundary **59/59**, CostBasisRisk **48/48**, SettlementRounding 16, Phantom 4, RealLossThreeFormula 8, PartialSell 7 = XML 6개 37/37 |

- affected-pg의 Phantom·RealLoss·PartialSell은 처음에 환경 변수 이름(SR 대신 PH/RL/PP)을 잘못 줘서 건너뜀으로 끝났다. 그 기록은 `*.skipped-wrong-env.log`로 남겼고, `v152-pg`에서 다시 돌린 결과가 위 숫자다.
- F3의 LIVE 성과 사례는 같은 id의 LIVE 세션을 둔 실제 경로다. id가 겹칠 때 DYNAMIC 행 전체가 LIVE 성과에 섞이는 것은 기존 동작이며, 이번에는 예약 거절 행만 뺐다.

## 3. N3 구판 복구 — 관문 4판 초안 [rollback-gate-v4-draft/](rollback-gate-v4-draft/)

3판(`v159-20261113/rollback-gate-v3`)의 원문·해시는 그대로 두고 사본에 두 가지만 더했다(`gate.sql` b24239f8…, `rollback_gate.sh` 0b391e58…).

- 집계 `exit_reason_unknown_to_old`: `position`과 `paper_trading.position`에서 구판 ExitReason 8값(b5cd1d0·0e1d2a02 동일) 밖의 값을 센다.
- 판정 `STOP_INCOMPATIBLE`(종료값 3): 모든 판정보다 먼저 확인하며, 장부를 만들지 않는다. 값 치환·행 삭제·추정은 하지 않는다. 결과는 "호환 바이너리 또는 운영자 별도 복구 판단 필요"로 보고한다.

| 로컬 예행 [rehearsal/](rollback-gate-v4-draft/rehearsal/) | 3판 | 4판 |
|---|---|---|
| ENTRY_REJECTED 1행 DB(costbf_fix1 사본) | **ROLLBACK_GATE_OK**(구판 복구 허용 = 위험 재현) | **STOP_INCOMPATIBLE**, 장부 없음 |
| 새 값 없는 DB(SettlementRounding) | HOLD_PENDING | HOLD_PENDING, GATE 줄은 새 키(=0) 말고 동일 |
| 위 1행만 NULL로 둔 사본(예행 준비, [prep](rollback-gate-v4-draft/rehearsal/gate4_ok-prep.txt)) | — | ROLLBACK_GATE_OK |

이것은 정적 위험을 막는 관문 초안이다. 복구 명령 실행 승인이 아니며, D4 블록의 GS 고정도 바꾸지 않았다.

- **v1.194 추가 (실제 조회)**: 구판 0e1d2a02·b5cd1d0d 바이너리로 새 값 1행에 대한 엔티티 조회를 실제로 실행했다. 서킷 브레이커·DYNAMIC 손실 냉각·LIVE 화면·전체 목록 경로가 `No enum constant …ENTRY_REJECTED`로 실패한다. NULL 대조에서는 정상이다.
- 같은 DB에서 3판은 `ROLLBACK_GATE_OK`, 4판은 `STOP_INCOMPATIBLE`이며, 새 값 행은 전후 동일하다. 근거: [dec-n3-oldbinary-20261119](../dec-n3-oldbinary-20261119/README.md).

## 4. 배포 전 읽기 v3 [predeploy-v3/](predeploy-v3/)

같은 생성기(`dec-costbasis-boundary-20261119/gen_predeploy.py`)로 b408a223 원문에서 다시 추출했다. v2와 다른 곳은 R1·R3의 `CONFIRMED_NO_TRADE` 조각과 머리 SHA뿐이다.

- 로컬 사본 `pre3_x` 전체 실행 rc0([local_syntax_v3.txt](predeploy-v3/local_syntax_v3.txt)).
- LIVE 무거래 매수 1건의 수량을 NULL로 둔 사본에서 R1(창만 3650일로 넓힘)은 **v2 LIVE 0 / v3 LIVE 1**이다([local_null_buy_diff.txt](predeploy-v3/local_null_buy_diff.txt)). 제품 PG 시험 F1의 판정 불가와 같은 방향이다.
- 운영에서는 실행하지 않았다. 전역 30일과 세션 연속 손실 범위(R3 newest_rank), 무거래·비용 미상, 과거 표시 없는 빈 청산(ENTRY_REJECTED 없음 → 판정 불가)의 구분은 v2와 같다.

## 5. 남은 것

- 통합(A+B+C)·교차·최종 회귀는 [dec-integ-abc-20261119](../dec-integ-abc-20261119/README.md)에 있다.
- 구판 조회 실제 대조(§3)와 사전 SQL v3의 로컬 제품 판정값 대조는 v1.194에서 로컬 승인 범위가 됐다. 결과는 각각 [dec-n3-oldbinary-20261119](../dec-n3-oldbinary-20261119/README.md)와 [dec-predeploy-parity-20261119](../dec-predeploy-parity-20261119/README.md)에 있다.
- 운영 배포 전 읽기(§4)의 운영 실행은 여전히 별도 승인이 필요하다.

체크섬 [SHA256SUMS](SHA256SUMS).
