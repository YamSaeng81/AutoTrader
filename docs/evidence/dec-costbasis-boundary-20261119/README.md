# 12월 준비 19 — 비용 기준 판정 불가 두 경계 보완 (총괄 v1.192 §2)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 기점 b861bdf9 위 로컬 가지 `candidate/cost-basis-boundary-b861bdf9`, 작업 사본 `D:/tmp/costbasis-0e1d2a02`, HEAD **32d5dafd**. **원격 보존은 아직 못 했다** — 가지 올리기가 이 세션의 권한 검사에서 거절됐다(사용자 확인 필요). 운영 변경·DB 쓰기·배포 없음. 설정·DDL·Flyway·Gradle 변경 0.

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| `d578c95b` | 실패 먼저: 새 PG 시험 `CostBasisBoundaryPgTest`(기존 48단언 시험과 별도 클래스) |
| `1cadf471` | 시험 측정 보정: N2 매수는 사전 등록 한도에서(측정 한도 0.00이면 공유 풀 예약이 RISK_CONFIG_MISMATCH로 거절 — [attempt1-n2-risk](attempt1-n2-risk/)) |
| `a2c532fe` | 제품: 두 경계 보완(아래 §2) |
| `45cf9798` | 실패 먼저: N2b DYNAMIC 진입 예약 거절 빈 슬롯 |
| `ab9899c6` | 제품: 예약 거절 빈 슬롯에 `ENTRY_REJECTED` 표시 → 무거래 확정 |
| `a243efbf`·`32d5dafd` | 시험 고정물: 직접 닫은 REAL 행 4클래스에 같은 손익의 확정 주문 원천(`CostSourceFixture`, 수수료 0, 세션 id 없음) |

제품 4파일 +57/−16([product.diff](product.diff)), 시험 6파일 +590/−6([test.diff](test.diff)).

## 2. 제품 변경

- **매도 필수값 NULL:** 두 손실 합에서 `COALESCE(applied_paid_fee, 0)`을 뺐다. 확정 매도는 대금·수수료·체결 수량이 모두 있어야 합에 든다(`SELL_FIELDS_KNOWN`). 하나라도 NULL이면 판정 불가 계수 ③이 센다. **확정 0은 값, NULL은 미보고**로 구분한다. 세션 연속 손실 재료도 같다(열 [8]).
- **주문 원천 없는 청산:** 체결 BUY·확정 SELL이 하나도 없는 CLOSED REAL 청산은 저장 손익 대신 판정 불가다(계수 ④, 연속 손실 재료). 예외는 **무거래 확정**(`CONFIRMED_NO_TRADE`) 두 가지뿐이다.
  - BUY가 하나 이상이고 모두 체결 0이 확정된 매수다(`isConfirmedUnfilledBuy`와 같은 조건). 확정 SELL은 없다.
  - DYNAMIC 진입 예약 거절로 같은 트랜잭션에서 닫은 빈 슬롯이다: `exit_reason = ENTRY_REJECTED`, 수량 0, 주문 행 없음.
  - 무거래 확정의 연속 손실 값은 0이다. 손실이 아니며, 종전과 같이 연속을 끊는다.
- **`ENTRY_REJECTED`가 필요했던 이유:** `DynamicTradingService.executeBuy`는 예약 거절 때 size 0 포지션을 주문 없이 CLOSED로 남긴다. LIVE는 롤백한다. 표시가 없으면 예약 거절 한 번이 그 세션과 전역 DYNAMIC 신규 진입을 막는다([fix1-n2b-fail](fix1-n2b-fail/) 3건 실패로 재현). 표시는 새 행에만 쓴다. 과거 행은 보충하지 않는다(§4).
- **유지한 것:** 가격 평균가·SL/TP·전략 SELL·체결/정산 쓰기·과거 저장 손익·0.01% 손실률 해상도·한도·분모·조회 기간·재시작 계약. 판정 불가만으로 정지하거나 보유를 청산하지 않는다.

## 3. 결과 (저장 XML/JSON 기준)

| 실행 | 입력 | 결과 |
|---|---|---|
| [base-fail](base-fail/) | 시험 1cadf471 + 제품 b861bdf9 | 단언 **39/54**. 실패 15건 = N1 원천 없는 청산(LIVE 5·DYNAMIC 4)·N3 매도 수수료 NULL(각 3). N2 무거래 취소·N4 원가 복구 해제·N5 미세 손실은 기점에서도 통과 |
| [run1](run1/) | a2c532fe | 54/54 |
| [fix1-n2b-fail](fix1-n2b-fail/) | 시험 45cf9798 + 제품 a2c532fe | 56/59 — N2b 3건 실패(예약 거절 빈 슬롯 판정 불가) |
| [attempt-run2-null-logic](attempt-run2-null-logic/) | 표시 첫 시도 | 54/59 — `exit_reason` NULL의 3값 논리로 계수 ④가 빠짐. `COALESCE`로 고침 |
| [run3](run3/) | 제품 ab9899c6(=HEAD 제품) | **59/59**, XML 1/1 |
| [affected-h2](affected-h2/) | HEAD 32d5dafd, 앞 후보와 같은 H2 29클래스 | 31 XML **215/215**. 앞 시도: [attempt1](attempt1-affected-h2/) 208/215(원천 없는 고정물 5클래스), [attempt2](attempt2-affected-h2/) 211/215(고정물 주문이 세션 주문 수 단언에 섞임), 5클래스 재실행 로그 rerun5.log |
| [affected-pg](affected-pg/) | HEAD | **36/36**, 5 XML: CostBasisRiskPg 48/48 단언·SettlementRounding·Phantom·RealLossThreeFormula·PartialSell. [attempt1](attempt1-affected-pg/)은 v152-pg 계정명 오류로 DB를 못 만든 실행이라 결과로 쓰지 않는다 |

N3 기대값은 이렇다. 매도 수수료 NULL이면 판정 불가이고 손실 합 0이다. 확정 0이면 다음 조회에서 해제되고, 손익 +1이라 승인된다. 확정 3.75면 손실 합 −2.75로 거절되고 연속 손실 한도 1이 발동한다. N5에서 −0.50 한 건은 합에서 0으로 버리지 않는다. 다만 비율 0.00%라 승인이다(기존 해상도). 두 건 합 −1.00은 0.01%로 거절된다.

## 4. 판정 불가 해제 범위 (조회 기간·재시작 계약은 바꾸지 않음)

| 경로 | 읽는 범위 | 확정 원천 복구 시 | 원천이 끝내 없을 때 |
|---|---|---|---|
| 전역 관문(LIVE `checkRisk`, DYNAMIC `checkRealLossGuard`·전체 REAL) | 30일 창(매도 `sell_applied_at`, 청산 `closed_at`) | 다음 조회에서 해제(N3·N4) | 30일 창에서 빠질 때까지 그 종류의 신규 REAL 거절 |
| LIVE 세션 연속 손실(진입 차단만) | 그 세션 CLOSED 전체(최근부터 한도 수까지) | 다음 조회에서 해제 | **시간으로 풀리지 않는다** — 그 세션에서는 계속 진입 차단. 새 세션은 영향 없음 |
| DYNAMIC 세션 연속 손실 | 이번 가동 `started_at` 뒤 CLOSED | 다음 조회에서 해제 | 재가동하면 새 `started_at` 앞이라 빠진다(기존 재시작 계약) |

수동 면제·원가 추정·손익 0 대입·과거 행 보충·관문 우회는 없다. 과거의 DYNAMIC 예약 거절 빈 슬롯에는 `ENTRY_REJECTED`가 없어 판정 불가로 읽힌다. 운영에 30일 안의 그런 행이 있는지는 배포 전 읽기 R1·R3로 확인해야 한다.

## 5. 배포 전 읽기안 v2

[predeploy_read.v2.sql](predeploy_read.v2.sql)은 [gen_predeploy.py](gen_predeploy.py)가 후보 `OrderRepository`의 상수·`@Query` 원문을 기계적으로 풀어 만든다. 손으로 옮기지 않는다.

- R1은 제품 판정 불가 계수, R2는 두 손실 합이다.
- R3은 세션 연속 손실 재료 행이다. 조회 범위는 제품 그대로다: LIVE는 RUNNING REAL CLOSED 전체, DYNAMIC은 `started_at` 뒤. `newest_rank`·`exit_reason`도 함께 나온다.
- 로컬 시험 DB(costbb_x)에서 문법만 확인했다([predeploy_local_syntax.txt](predeploy_local_syntax.txt), rc0). 운영에서는 실행하지 않았다.
- v1의 R3 설명은 SQL과 맞지 않았다. 원본 v1은 보존하고 v2로 대체를 제안한다.

## 6. 남은 것

- 가지 원격 보존(권한)
- A aff357e2 + B dfe3b9f7 + C(이 HEAD) 로컬 통합과 교차 경계 시험(v1.192 §3-3)
- 판정이 필요한 것: `ENTRY_REJECTED` 표시 추가(새 열거값 — 성과 집계에서 이 값을 거래로 세지 않는지 확인 필요)

체크섬 [SHA256SUMS](SHA256SUMS).
