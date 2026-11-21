# 병행 감시 실제 읽기 1회 승인안 pm-read-1 (총괄 v1.215 §3-3) — 미실행

2026-11-21 KST, 개발 세션 제출. **이 문서와 [pm-read.sh](pm-read.sh)는 승인 요청안이다. 실행하지 않았다.** 운영 SSH·DB·로그 접근, 감시 설치·cron, d81 배포, 생성·T0, 실자금 시작은 모두 별도 승인 전이다.

## 1. 고정 파일

| 역할 | 파일 | sha256 |
|---|---|---|
| 실행 블록(read → post) | [approval/pm-read.sh](pm-read.sh) | `7f07c470b5483f438952fa702d89e8166623a7f52f63b379031e1b2bfb977d74` |
| 상태 SQL pm-state-2(격리 PG 4/4) | [sql/state-2.sql](../sql/state-2.sql) | `4c04efff32b096b420341b5fe10cc84475b0dc421147ae5a27eccd0f086aab94` |
| 변환기 pm-conv-3(pm-aux-2) | [candidate/convert_state_3.py](../candidate/convert_state_3.py) | `a131c531c54bcab354249edcfa1fe862de78ee801dacc0cb03da18083483b2d3` |
| P0 입력 pm-p0-1 | [candidate/p0_input.py](../candidate/p0_input.py) | `3fdd580a2008af692e9448ed0766f1a1a058ae3aaf17128352911f8d403d1f66` |
| P0 기준(holdobs 원문) | [U변수.sh](../../d81d4r-20261120/d4/blocks-d81/U변수.sh) | `efb96108113142132da3685669c1a034e7a3050bc891bbacbecd2891137b19fd` |
| `$DC`·psql 접속 형식 출처 | [F.sh](../../d81d4r-20261120/runner/extract-cd8bd76f/F.sh) 2·6행 | `26d392695c47fecb2321e850b07418679c1326c2a7eec13c02002056f96f4c24` |
| SSH 읽기 형식 출처 | [read.sh](../../n03-1121-1000/read.sh) (`ssh -o BatchMode=yes cat-prod`) | `41e4f33747f47ed4089ffe8ca192964529cfea24b8d150a7b1a8300c572f73ad` |

pm-read.sh는 시작할 때 SQL·변환기·P0 도구·U변수 해시 4개를 확인하고, 다르면 아무것도 읽지 않고 멈춘다.

## 2. 요청 범위 — 읽기 1회, 서버 쓰기 0

`bash pm-read.sh read pm-read-<YYYYMMDD-HHMM> <SINCE>` 한 번으로 아래를 읽는다. 출력은 모두 SSH 표준 출력으로 개발 PC 보존 폴더에 받는다. 서버에는 파일을 만들지 않는다.

1. 서버 시각과 설치 상태: `git rev-parse HEAD`, backend 컨테이너 `Image`·`StartedAt`·`RestartCount`·`Status`.
2. 상태 SQL 1회: `docker compose … exec -T db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -`에 state-2.sql을 표준 입력으로 넣는다. 격리 PG와 같은 옵션이고, 입력 방식만 `-f 파일`에서 `-f -`로 바뀐다. 트랜잭션은 REPEATABLE READ READ ONLY이고 끝에서 ROLLBACK한다.
3. P0 원천: `docker compose … logs --no-color --since <SINCE> --until <실행 시각> backend`. U3와 같은 수집 명령에 끝 시각만 더했다.

그 뒤 `post` 단계는 로컬에서만 돈다. read 원문 해시를 다시 확인하고, 구간 근거를 만든 뒤 p0_input → pm-aux-2 → convert_state_3 순으로 실행한다. **W1/W2는 실행하지 않는다.** 승인된 실제 등록표(registry-3) 파일과 해시가 아직 없기 때문이다. 합성 fixture 등록표에 실제 관측을 대조하지도 않는다.

## 3. 금액 없는 허용 열(SQL이 내는 것 전부)

- **S(세션, LIVE·DYNAMIC 전체):** 종류, id, 모드(`session_type`/`trading_mode`), status, strategy_type, timeframe, coin_pair(LIVE만), created_at(UTC).
- **O(주문, 모의 소유 제외):** id, session_kind, session_id, side, coin_pair, state, 소유 분류.
- **P(포지션, 모의 소유 제외):** id, session_kind, session_id, coin_pair, status, 소유 분류.
- **U:** 반영 대기 주문 수, 미해결 정산 불일치가 있는 주문 수.
- **CNT:** 행 수 대조와 모의 소유 개수. **META/END:** SQL 판, db 시각, flyway 최대 판, 격리 수준.

**읽지 않는 것:** 금액·수량·가격·잔액·대금·수수료·성과 열, `strategy_params`·`invest_ratio` 같은 전략 파라미터, 인증 정보, `paper_trading` 스키마(522~587 포함). 금액 열이 섞이면 열 수 검사에서 INVALID가 된다. 격리 PG에서는 금액 표지 노출이 0건이었다.

## 4. 보존 경로

로컬 `docs/evidence/pm-read-<YYYYMMDD-HHMM>/`이며, 폴더가 이미 있으면 멈춘다. 담기는 파일은 다음과 같다.

- read 원문: `run-time.txt`·`install_state.*`·`state-2.sql`·`state.tsv/.stderr/.rc`·`backend.log/.stderr`·`collect.rc`·`range-args.txt`·`read-SHA256SUMS`
- post 결과: `post/range.json`(또는 `range-not-provided.txt`)·`post/p0/`·`post/n03-p0-aux.json`·`post/conv/`·`post-SHA256SUMS`

backend 로그 원문은 보존 폴더에 남는다. 커밋할 때는 별도 검토로 범위를 정한다. 로그 본문에 금액이 있을 수 있기 때문이다. **이 항목은 판단 요청이다.**

## 5. 멈춤 조건

| 시점 | 조건 | 처리 |
|---|---|---|
| 시작 전 | 실제 사용률 80% 확인 | 보존·대기 원칙대로. 미관측이면 0%로 쓰지 않는다 |
| 시작 전 | 고정 해시 4개 불일치, SINCE 형식 위반, 보존 폴더가 이미 있음 | 읽지 않고 멈춤 |
| read | SSH 실패(BatchMode) | 멈춤. 다른 방식으로 우회하지 않는다 |
| read | `state.rc`≠0 또는 `state.stderr`가 비어 있지 않음(compose 경고 포함) | 원문 보존, 변환기는 INVALID. **같은 승인으로 다시 읽지 않는다** |
| post | read 원문 해시가 바뀜, post를 이미 실행함 | 멈춤 |
| post | META flyway_max≠96, 또는 HEAD·backend 이미지가 운영 핀(0e1d2a02 / 2c5d8427)과 다름 | 보고하고 판정은 총괄에게 넘긴다(변환 결과를 소비하지 않는다) |
| post | 변환 INVALID 30 / ERROR 40 | 보고. 소비 가능한 관측이 없다 |
| post | 변환 COLLECTION_NOT_PASSED 20 | 수집 불통과 목록(종류·세션ID·주문/포지션ID·상태)을 보고. 병행 시작 근거로 쓰지 않는다 |
| post | P0_PRESENT | 🔴 즉시 보고(DB 연결 획득 timeout) |
| post | P0_CHECK_INCOMPLETE | 🔴 P0 검사 미완료로 보고. 0건·정상으로 바꾸지 않는다 |

## 6. 결과 해석 기준(v1.215 그대로)

- **과거 종결 행:** 경보를 유지한다. 시간 필터나 예외 목록은 없다. 이번 읽기의 목적 중 하나는 실제 과거 행을 종류+세션ID+주문/포지션ID의 금액 없는 목록으로 식별하는 것이다. 행별 인수안은 결과를 본 뒤 따로 제출한다.
- **P0:** `Connection is not available, request timed out` 기준만 쓴다. 뉴스 Connection reset·캔들 HTTP·외부 timeout은 P0가 아니다. 이 원천과 N03 원천은 aux에서 따로 표시한다. 장기 대여 경고는 별도 필드로 남긴다.
- **등록표:** registry-3이며 실제 파일·해시는 미정이다. **계좌 일치(P7)·월말 성적표·F3는 이번 범위 밖**이다.
- **N03 값(미분류 ERROR·66):** 자동으로 해석하지 않는다. 승인 전에 정한 N03 회차의 보고 원문을 `n03-values.json`으로 전사해 넘긴 경우에만 채운다. 없으면 null이 되고 수집 불통과로 남는다.

**코드로 본 예상(확인 아님).** 운영 `"order"`에는 V52 주석이 말하는 비세션 주문(`session_id` NULL)이 남아 있을 수 있다. 과거 LIVE·DYNAMIC 종결 주문도 남아 있을 수 있다. 그러면 실제 읽기 결과는 `ORDER_OWNER_NO_SESSION_ID` 수집 불통과가 되거나, W2를 연결했을 때 `UNREGISTERED_REAL_ORDER`가 된다. 이것은 실패한 읽기가 아니다. 개별 인수에 쓸 식별 목록이다.

## 7. 승인자가 정할 값(채우지 않음)

1. 실행 시각: N03 22:00/10:00 회차와 겹치지 않는 창.
2. `SINCE`: P0 대상 구간의 시작. backend `StartedAt`이 SINCE보다 뒤이면 구간 근거 미제공이 되고 P0는 미완료로 처리된다.
3. `n03-values.json`의 원천 회차와 전사 여부.
4. 운영 쿼리 보호값(예: `PGOPTIONS=-c statement_timeout=…`)을 추가할지. 격리 검증에 없던 옵션이라 기본값에는 넣지 않았다.
5. backend 로그 원문의 커밋 범위.

## 8. 다음 통합 범위(제출만, 실행 안 함)

변환기 출력을 R2(`run_offline_r2.py` e203ea6c)에 연결하는 통합은 다음 조건이 갖춰진 뒤에 한다.

- pm-state-2·pm-conv-3·pm-p0-1이 고정되어 있을 것.
- 실제 registry-3 파일·해시가 승인되어 있을 것.

필요한 직접 경계는 세 가지다.

- (a) pm-conv-3 CONVERTED 출력 → R2가 W1/W2를 같은 파일로 소비하는 정상 1개
- (b) AUX_P0_CHECK_INCOMPLETE·COLLECTION_NOT_PASSED가 R2에 들어가지 않는 거절 1개
- (c) P0_PRESENT가 R2 결과에서 위험으로 남는 1개

기존 R2 6/6을 반복하지 않는다.
