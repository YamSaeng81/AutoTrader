# 12월 준비 34 — 실제 읽기 승인 묶음 인계표 (총괄 v1.210 §2 순서2) — 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). 기존에 검토·수용된 출처를 한 표로 연결하고, 실제 읽기 전에 아직 비어 있는 입력과 담당을 적는다. **새 SQL·수집 기능·실제 값은 만들지 않았다. DB·PG·운영 접속·시험·빌드는 하지 않았다.** 이 표는 읽기 승인이 아니다.

## 1. 고정된 실행 출처 (모두 검토된 작업 사본 해시)

| 구성 | sha256 | 수용 근거 |
|---|---|---|
| 읽기 SQL v3 | 9818db5452f66f058c97322ac9aaf73097036997777d10a0af9f6dde05bdae03 | [출처 표](../dec-read-source-20261120/README.md) §1, [v1.208](../review-73c23938/README.md) §1 |
| 파서 v3 | 0f6fe827c9da513445ce9c52300b8688f8f4f162a99026b90d05943c10341908 | 같음 |
| 조립기(기록 확정 보완) | ff8549346014095c5cd33ba297f187966fee39f449a6b953755b8ae905447472 | [459차](../dec-collect-assembler-commit-20261120/README.md), [v1.210](../review-2ff6982c/README.md) §1 |
| P7 v8 `reconcile.py` / F3 v8 `f3.py` | 156c3828…f9effa / 0b1a55b6…be011e | [P7 v8](../dec-registry-p7v8-20261120/README.md) |
| F3 v9 오류 처리 | f825ca5e80ef1542abf98962d1aeb91f5fe279555ae6acfc1b1421df73e55f15 | [v1.208](../review-73c23938/README.md) §1 |
| N03 read.sh | 41e4f337… | [N03 11-20 10:00](../n03-1120-1000/README.md) |
| 수집 계약·보충 | 계약 초안 + 보충 3항(P7 5단계, `db_extract` 표본 키, 외삽 금지) | [계약](../dec-collect-contract-20261120/README.md), [보충](../dec-collect-contract-supp-20261120/README.md) |

소비 조건: 다음 단계는 조립기 `--verify` 0을 통과한 `bundle.json`만 읽는다. 이 묶음은 **DB 추출 부분 묶음**이며 P7 전체 관측·F3 통과·계좌/월말 확정이 아니다. PG 결과는 **3PASS/1FAIL + 별도 정상 원천 보충 비교**를 유지한다.

## 2. 실제 읽기 전에 비어 있는 것과 담당

| 항목 | 현재 상태 | 채워야 할 곳 | 담당·선행 조건 |
|---|---|---|---|
| 등록 ID(LIVE·DYNAMIC kind+id) | 미확정. 실제 세션 미생성 | 등록표 `sessions[]` → SQL `live_ids`/`dyn_ids`·등록표 해시 | 운영자: 실제 생성 승인 후 생성 결과로 기록. 비면 SQL이 STOP |
| 등록 자본(`initial_capital`)·총원금 | 미확정 | 등록표 v2, P7 `account_sum` | 운영자 결정(총원금·월 손실 상한). 추정값 금지 |
| 미배정 현금·기존 보유(`unallocated_krw_base`·`non_session_holdings`) | 미확정 | 등록표 v2 | 별도 승인된 계좌 읽기 필요(현재 승인 없음) |
| 앱↔DB 시계 관계 | 미확정(같은 시계 근거 없음) | P7 `meta.clock` | 운영 근거(같은 호스트·시계 출처) 제출 시에만 `same_source`. 없으면 보류 |
| 거래소↔수집기 시계, 거래소 계좌·주문 | 미확정(이 읽기에 없음) | P7 `exchange_before/after`·`done_query` | 별도 승인된 거래소 조회 단계. 없으면 F3 불통과(의도된 동작) |
| P7 단계 4개(`db_fp_pre`·`exchange_before`·`exchange_after`·`db_fp_post`) | 수집 근거 없음 | P7 `meta.steps` | 별도 수집 근거. 같은 시각·지문으로 채우지 않음 |
| 앱 코드 SHA 원천 | 미확정(이 읽기에 없음) | P7 `meta.app_code_sha`·`_ref` → F3 `code` | N03/F1의 운영 SHA 관측(현재 0e1d2a02)을 출처로 연결하는 방식 결정 필요 |
| 규칙 지문(`ruleset_hash`/`confirmed`) | 미확정 | 등록표 v2·P7 | 생성 직후 `ruleset_snapshot` 확인(생성 승인 뒤) |
| psql 실행 위치·접속 수단 | 미정 | 실행 절차 | 운영자 승인. 새 SQL/계좌 읽기는 별도 승인 전 |

## 3. 하지 않은 것

새 SQL·수집 기능·실제 ID/금액 작성, PG·회귀·빌드 재실행, 운영·DB·거래소 접속. 기존 계약·SQL·파서·조립기·결과·체크섬은 바꾸지 않았다.
