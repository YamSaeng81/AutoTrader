# 12월 준비 22 — P7 v7 합산 경계·F3 파일 판정기 후보 (총괄 v1.203 §2-4·§2-5·§3-2)

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [p7v7/reconcile.py](p7v7/reconcile.py)는 [P7 v6](../dec-registry-v3-p7v6-20261120/README.md) 사본이며 원문은 수정하지 않았다. `account_sum`만 보완했다. 새 [f3.py](p7v7/f3.py)는 P7 출력 JSON 하나만 읽는다. 운영·DB·설치본·RUNBOOK은 바꾸지 않았다.

## 1. 보완

- **미래 기준(P3):** `cash_base`, `holdings_base`의 `base_at`이 현재 관측(db) 이전인지 그 축으로 범위 판정한다. 확정적으로 뒤면 `ACCOUNT_SUM_BASE_IN_FUTURE` 이상과 해당 통화 불일치로 처리한다. 선후가 불명이면 `ACCOUNT_SUM_BASE_TIME_UNCONFIRMED`로 해당 통화만 보류한다.
- **기준 포함 이벤트:** 현재 관측 뒤로 확정되면 `ACCOUNT_SUM_INCLUDED_EVENT_AFTER_OBSERVATION` 이상으로 처리하고, 불명이면 보류한다.
- **불가능한 잔액:** 미배정 현금이나 비세션 보유가 음수이면 `ACCOUNT_SUM_NEGATIVE_UNALLOCATED` 이상으로 처리한다.
- **F3:** 452차 초안의 `account_sum.status == 일치` 단독 조건을 철회한다. 새 조건은 **전체 판정 OK·등록표 valid·두 관측 ok·관측 안정·코드 출처 확인·거래소 완료 조회 구간 일치·계좌 대조 일치·합산 일치**를 모두 요구한다. 보류는 불통과다. 종료값은 0 통과, 1 불통과, 2 읽기 실패다.

## 2. 결과 ([run-v7](p7v7/run-v7/cases.out.txt))

[cases_v7.py](p7v7/cases_v7.py) **12/12 통과**(reconcile e1f9f27b…, f3 08f01d7b…). 입력은 v1.203 검토 입력 P0~P4이며, 같은 시계 선언은 그 파일의 시험용 선언이다.

- **RED:** v6 출력에 초안 F3를 적용하면 P1·P2·P3·P4가 모두 통과한다.
- **유지:** P0 정상 대조군은 OK이고 F3 통과다. P1(HOLD)·P2(ANOMALY)·P4(HOLD)는 합산 숫자가 일치로 남지만 F3 불통과다. 합산 숫자 계산은 바꾸지 않았다.
- **P3 계열:** 두 기준 2030은 이상이다. 현금 기준만 미래이면 KRW만 불일치다. 시계 관계가 없으면 보류다. 포함 이벤트가 관측 뒤이면 이상이다. 미배정 현금이 음수이면 이상이다.
- **회귀:** v6의 통화별 보류를 보존한다(CLOSING은 그 코인만 보류). f3 CLI는 P0 rc0, P1 rc1이다.

옛 형식 260건은 반복하지 않았다. 변경은 `registry-3`의 `account_sum` 안에만 있어 옛 형식 경로에는 닿지 않는다.

## 3. F3 문안(설치 전)

등록 세션별 KEEP 행 검사와 `ruleset.confirmed` 대조는 기존 초안대로 둔다. 판정은 P7 v7 출력에 `f3.py`를 적용한 결과만 쓴다. 고정 RUNBOOK과 설치본에는 반영하지 않았다. 체크섬은 [SHA256SUMS](SHA256SUMS)에 있다.
