# 12월 준비 21 — 등록표 v3와 P7 v6 파일 입력 후보 (총괄 v1.202 §2-4·§3-2·§3-3)

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). P7 v5 `reconcile.py`(2ca03476…)를 [p7v6/reconcile.py](p7v6/reconcile.py)로 복사한 뒤 등록표 v3의 계좌 합산만 더했다. v5 원문, 451차 [registry_sum.py](../dec-parallel-registry-20261120/registry_sum.py), 고정 RUNBOOK, 설치본, read.sh는 바꾸지 않았다. 운영·DB·거래소 접속은 없었다.

## 1. 등록표 v3 (`registry_version: "registry-3"`)

| 필드 | 내용 |
|---|---|
| `cash_base` | `{amount_krw, base_at, axis, source, includes_event_refs}`. 미배정 KRW 기준과 그 시각·축·출처, **기준값에 이미 반영된 이벤트 ref** |
| `holdings_base` | `{coins, base_at, axis, source, includes_event_refs}`. 세션 밖 코인 보유 기준 |
| `capital_events[]` | `{ref, type, amount, currency, applied_at, recorded_at, axis, session}`. ALLOCATE/RECOVER는 KRW만 받고 등록 세션 `[kind,id]` 연결이 필수다. DEPOSIT/WITHDRAW는 세션 연결이 없다(`null`) |
| 항목 `ruleset` | `{expected, confirmed, confirmed_source}`. 등록 기대 지문과 실제 확인 지문·출처를 구분한다. 확인 출처가 없으면 확인 지문도 받지 않는다 |

검사 항목은 다음과 같다. 필드 누락·초과, ref 중복, 금액 0 이하·NaN, 알 수 없는 종류, 기록 < 적용, 축 없음, 미등록 세션 배정, 입출금에 세션 연결, 배정 통화 KRW 외, 포함 선언 ref 미존재, 버전 없이 v3 필드만 있음. 이 중 하나라도 해당하면 `REGISTRY_INVALID`다. 기본 시각이나 0을 넣지 않는다.

**기준 경계:** 포함 선언된 이벤트는 다시 적용하지 않는다(P3). 포함으로 선언했는데 적용이 기준 뒤로 확정되면 `ACCOUNT_SUM_BASE_EVENT_CONTRADICTION` 이상이다. 선언이 없는데 기준 이전이거나 같은 시각이거나 선후가 불명이면 그 통화의 합산만 `ACCOUNT_SUM_BASE_BOUNDARY`로 보류한다. 현재 관측의 db 추출 뒤에 적용된 이벤트는 넣지 않는다. 선후가 불명이면 보류한다.

**합산(현재 관측 한 시점):**
- KRW: 거래소(가용+잠김) = Σ등록 세션 `available_krw` + Σ체결0 미체결 매수 `reserved_krw` + 미배정. 오차는 `--krw-quantum` 범위 안이어야 한다.
- 코인: 코인별로 거래소 보유 = Σ등록 세션 **OPEN** 수량(LIVE+DYNAMIC, BTC 포함) + 비세션 보유.
- CLOSING 포지션이 있으면 그 코인을 보류한다. 부분 체결 예약이 있으면 KRW와 코인을 모두 보류한다. 중복 계산을 막기 위해서다.
- 미등록 실자금(v5의 `UNREGISTERED_REAL_*`·행 누락·계약 불일치)이 있으면 합산을 맞추지 않는다.
- `ruleset.confirmed`가 없으면 `RULESET_UNCONFIRMED` 보류다. 기대 지문과 다르면 `RULESET_MISMATCH`다. `ruleset_snapshot`에는 세션 열이 없으므로 그 테이블만으로 확인 지문을 채우지 않는다.
- 옛 등록표에는 `ACCOUNT_SUM_UNAVAILABLE`을 보류로 붙인다. 통과로 보지 않는다.

## 2. 결과

| 검사 | 결과 |
|---|---|
| 실패 먼저 고정 — 같은 반례를 v5 원문에 ([run-v5-red](p7v6/run-v5-red/cases.out.txt)) | **16/29 실패**. v5는 registry-3 필드를 모르는 필드로 보고 등록표 전체를 무효 처리한다. 형식 거절 13건(R1~R13)만 결과적으로 맞는다 |
| v6 직접 반례 [cases_v6.py](p7v6/cases_v6.py) ([run-v6](p7v6/run-v6/cases.out.txt)) | **29/29 통과**(`reconcile.py` 08563a18…). V0 일치, P3a~c 기준 경계, P1 주문 없는 미등록 가동 세션, P2 포지션 없는 미등록 주문, A1 DYNAMIC BTC 합산·A1b BTC 누락 검출, B1 CLOSING 보류, B2 예약 합산, B3 부분 체결 보류, E1 관측 뒤 입금 제외, E2 기록됐지만 없는 입금 검출, R1~R13 형식 거절, F1·F2 지문, L1 옛 등록표 보류 |
| 영향 범위 [impact.py](p7v6/impact.py) ([run-impact](p7v6/run-impact/impact.out.txt)) | **260/260 통과**. v5 시험 생성기의 K32·P30·R99·L99 입력을 v5와 v6에 함께 넣었다. 이상(전체 내용)·기존 보류·계좌 계산·그 밖 출력이 같다. 차이는 유효 등록표에서 보류 1개 추가뿐이다(OK→HOLD 19, 나머지 전이 없음). 등록표가 무효이면 합산하지 않고 이상만 유지한다 |

기준 입력은 v4 저장 사례 `G3_exact_two_sessions`(체크섬 확인)에 v5 합성 같은 시계 선언을 붙인 것이다. 값은 가상이며 측정이 아니다. 451차 `registry_sum.py`는 운영 대조기로 쓰지 않는다.

## 3. F1/N03/F3 문안 (로컬 사본, 설치 전)

- **F1:** 기대 집합은 등록표 v3 파일(해시 고정)에서 만든다. 활성 LIVE REAL id 집합 = 등록 LIVE, 활성 DYNAMIC REAL id 집합 = 등록 DYNAMIC으로 **집합 일치**를 본다. 출력 문안은 `F1 기대 {LIVE:[203], DYNAMIC:[…]} / 관측 {…} / 일치|불일치(초과 …, 누락 …)`다. 기존 `EGS` 상수(DYNAMIC 0)는 등록표 v3를 승인한 뒤에 바꾼다. 비상 정지 목록과 `capital_stage_baseline` 검사는 유지한다.
- **F3:** 등록 세션마다 KEEP 행 검사와 `ruleset.confirmed` 대조를 하고, P7 v6 `account_sum`을 확인한다. 통과 조건은 `account_sum.status == "일치"`다. 보류는 통과가 아니다.
- **N03:** 보고 회수는 그대로 둔다. 장부 회수에 등록 세션 행, 열린 포지션, 미체결 매수 예약을 더한다. 월 경계 포착은 [성적표 원천 v2](../dec-scorecard-source-v2-20261120/README.md) C1 근거와 함께 남긴다. 1월 1일 10:00 회차의 값을 자동으로 12월 말 값으로 쓰지 않는다.
- 고정 RUNBOOK·read.sh·설치본에는 반영하지 않았다.

## 4. 남은 결정

총원금, 월 손실 상한, DYNAMIC 실제 구성·배정액, 미배정 현금과 기존 보유의 실제 값, 실제 확인 지문은 정해지지 않았다. 이번 작업은 모두 가상 입력으로 했다.

## 5. 읽기 명령 초안 — 검토용, 실행 금지

실제 월 경계 포착과 미배정 현금 읽기는 이 초안을 검토하고 별도로 승인한 뒤에 한다.

- **범위:** 등록표 v3의 (kind,id) 세션만 읽는다. **세션 522~587은 조건으로 제외하고 열람하지 않는다.**
- **일관성:** `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;` 한 트랜잭션 안에서 `now()`와 `clock_timestamp()`를 앞뒤로 기록한다(db 축). 포착 ID는 트랜잭션 시작 시각과 txid로 만든다.
- **허용 열:**
  - 세션: `id, mode/trading_mode, strategy_type, timeframe, coin_pair, initial_capital, available_krw, status, created_at, stopped_at`
  - 주문: `id, session, position_id, coin_pair, side, order_type, quantity, reserved_krw, state, filled_quantity, filled_at, sell_applied_at, reported_observed_at, reported_*/applied_* 대금·수수료, settlement_final, fill_accounting_pending, exchange_order_id, created_at`
  - 포지션: `id, session, coin_pair, status, size, invested_krw, position_fee, ruleset_hash, opened_at`
  - 기타: `order_settlement_discrepancy` 미해결 건
  - 응답 원문, API 키, 다른 세션의 행은 읽지 않는다.
- **계좌:** 거래소 잔고 조회는 별도 단계(exchange 축)에서 하고, 앞뒤로 수집기 시각을 기록한다. DB 트랜잭션과 동시라고 쓰지 않는다.
- **보존:** 원문 출력과 SHA256을 회차 폴더에 남긴다. 실패 원문도 남긴다. 월 경계 회차는 T 이후 M 이전에 한 번 읽고, 공백 근거(같은 축, 공백 동안 그 세션 주문·이벤트 0건)를 같은 트랜잭션에서 함께 읽는다.

체크섬은 [SHA256SUMS](SHA256SUMS)에 있다.
