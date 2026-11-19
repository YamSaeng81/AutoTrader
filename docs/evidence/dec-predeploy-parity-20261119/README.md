# 사전 SQL v3 ↔ 제품 판정값 로컬 대조 (총괄 v1.194 §2-4)

2026-11-19 KST, 개발 세션 제출. 대상은 [predeploy_read.v3.sql](../dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql) 원문(b408a223에서 생성)과 통합 60df3437 제품이다. 로컬 격리 PG(`cat-rt-pg` 15432)에서만 실행했다. 운영 DB와 과거 이력 보충은 쓰지 않았다.

## 방법

- 미추적 시험 [PredeploySqlParityPgTest.java](PredeploySqlParityPgTest.java) 1개를 사본 `D:/tmp/sqlpar-60df3437`(60df3437)에 넣었다. 헬퍼는 `AbcCrossPgTest`(= CostBasisBoundaryPgTest 이식)에서 옮겼다.
- SQL 파일에서 주석 줄을 빼고 SELECT 3개(R1·R2·R3)를 그대로 실행한다. R3는 열 위치로 읽는다.
- 같은 시점의 제품 값을 나란히 남긴다:
  - `countRealSessionCostUndeterminedSince`
  - `sumLive/DynamicRealSession…LossSince`
  - `checkCircuitBreaker`
  - 연속 손실 입력 조회(DYNAMIC은 started_at에서 중단)
- R3 행의 세션 판정은 SQL 주석에 적힌 읽기 규칙으로 계산한다: newest_rank 1부터 읽고, 한도 안에서 판정 불가가 나오면 그 posId를 쓴다.
- 입력은 실제 매수·매도 경로(왕복 25@300)로 만들고 한도는 2로 둔다. 행을 직접 쓴 곳은 라벨에 적었다: 수수료 NULL/0/복구, 시각 이동.
- 입력 사이에는 `ageAll`로 앞 단계 행을 40일 전으로 옮기고 세션을 STOPPED로 둔다.

## 결과 [sqlpar3-60df3437](sqlpar3-60df3437/) rc=0 — **96/96 일치** ([parity_table.tsv](parity_table.tsv))

| 입력 (LIVE·DYNAMIC 각각) | 전역 30일 판정 불가 / 손실 합 | 세션 입력 범위·최신순 / 판정 |
|---|---|---|
| P1 확정 손실 1건 | 0 / −107.45 | [a] / PASS |
| P2 확정 손실 2건 = 한도 | 0 / −314.85 | [b, a] 최신순 / TRIGGERED |
| P3 매도 수수료 NULL | **1** / 0 (합에서 빠짐) | [p] / UNDETERMINED p |
| P4 같은 행 수수료 확정 0 | 0 / −103.75 | [p] / PASS (NULL과 0이 구분됨) |
| P5 확정 수수료 3.70 복구 | 0 / −107.45 | [p] / PASS (해제) |
| P6 표시 없는 빈 청산 | **1** / 0 | [e] / UNDETERMINED e |
| P7 확정 손실 뒤 더 이른 빈 청산 | 1 / −107.45 | [손실, e] / UNDETERMINED e (손실 1회 다음) |
| P8 가동 전·45일 전 빈 청산 | 0 | LIVE [e] / **UNDETERMINED e**, DYNAMIC [] / PASS |

- P8에서 LIVE 세션 연속 손실 범위는 시간 제한이 없다. 그래서 30일 창 밖의 빈 청산도 진입을 막는다. SQL 주석의 🔴 문장과 제품이 같다. DYNAMIC은 started_at 이전 청산을 보지 않는다.
- 전역 R1은 30일 안의 판정 불가 매도와 표시 없는 빈 청산을 함께 센다(P6·P7 = 1).

## 앞선 두 실행 (보존, 대조 결과 아님)

- [sqlpar-60df3437](sqlpar-60df3437/) rc=1: 시험 결함이다. 이름 없는 하위 조회 열(`?column?`)이 Map으로 읽을 때 하나로 합쳐져 R3 열이 10개 미만이 됐다. 열 위치 읽기로 고쳤다.
- [sqlpar2-60df3437](sqlpar2-60df3437/) rc=1: 입력 설계 오류다. 빈 청산을 최신으로 둔 뒤 매수하려 했고, 제품이 판정 불가로 매수를 막았다(정상). P7을 "손실 왕복 먼저, 빈 청산은 더 이른 시각"으로 바꿨다.

체크섬 [SHA256SUMS](SHA256SUMS).
