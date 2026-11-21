# 병행 감시 SQL 격리 PG 검증·P0 입력 후보·실제 읽기 승인안 (총괄 v1.215 §3)

2026-11-21 KST, 개발 세션 제출(총괄 판정 전). 지시는 [v1.215](../review-a569939b/README.md) §3, 기준 커밋은 3b78ade0이다.

이번 제출에서 실제로 한 일은 두 가지뿐이다.

- 운영과 분리된 일회용 로컬 PG 1개에서 SQL을 실행했다.
- 로컬 파일만으로 P0 후보를 시험했다.

운영 SSH·DB·로그 접근, 감시 설치·cron, 배포, 제품 회귀·빌드·jar는 없었다. 기존 13사례·W 묶음·R1/R2도 다시 돌리지 않았다. 기존 원문([감시 초안](../parallel-monitor-draft-20261121/README.md)의 SQL·변환기 포함)은 고치지 않았다.

## 1. 새 SQL 격리 PG 1묶음 (§3-1)

- **환경:** 로컬 이미지 `timescale/timescaledb:latest-pg15`(PostgreSQL 15.13, psql 15.13)를 썼다. 컨테이너는 `--network none`·데이터 tmpfs로 띄웠다. d81c3f2f V1~V96(95개)을 고정 [setup_pg.py](../dec-read-gate-v3-20261120/setup_pg.py)(98b3eab0)로 적용했다([setup](runs/setup/setup.txt)). 끝난 뒤 컨테이너를 삭제했다.
- **실행기:** [pg_check.py](candidate/pg_check.py)를 썼다. 경계마다 템플릿에서 새 DB를 만들고, 제출 호출 형식 `psql -X -q -v ON_ERROR_STOP=1 -f`로 실행한다. 그 결과를 변환기 → 고정 W1/W2(registry_f1 bcdcada6·parallel_report 37978bfc, 합성 registry 14599ba6)로 넘긴다.
- **픽스처:** 제품 형식을 따랐다. 금액 열에는 합성 표지값을 넣고, 출력 어디에도 나오지 않는지 검사했다.

### 1회차 [pg-run1](runs/pg-run1/pg_check.out.txt) — 제출 원본 pm-state-1(3c0b656f)·pm-conv-1(8d8806b8): **0/4**

- **실행 자체는 문제없었다.** 4경계 모두 psql rc0·stderr 0B였고, 출력 형식(META~END, 열 수, `\N`, 탭)은 변환기 형식 검사를 통과했다.
- **실패 원인은 실제 결함이었다.** 제품 d81c3f2f는 DYNAMIC PAPER 세션의 주문·포지션을 `session_kind='DYN_PAPER'`로 저장한다([V67](../../../web-api/src/main/resources/db/migration/V67__add_trading_mode_to_dynamic_session.sql) 주석, `DynamicTradingService.sessionKind`). 초안은 `DYNAMIC`+PAPER만 모의로 본다. 그래서 실제 데이터 형식에서는 다음 세 가지가 생겼다([B1 원문](runs/pg-run1/B1_normal_identity_settlement/in/state.tsv)).
  - ① DYN_PAPER 행이 `UNKNOWN_KIND`가 되어 모든 경계가 수집 불통과(20)였다.
  - ② CNT의 모의 개수가 0이었다.
  - ③ U에 모의 소유 표지가 섞였다. 기대는 `1 1`, 실제는 `2 2`였다.
- 운영에는 과거 DYN_PAPER 청산 기록이 있다(코드 주석의 "335건"). 이 원본으로 운영을 읽었다면 매번 수집 불통과가 났을 것으로 본다.

### 2회차 [pg-run2](runs/pg-run2/pg_check.out.txt) — 최소 수정 사본 pm-state-2·pm-conv-2: **4/4**

**SQL 사본 [sql/state-2.sql](sql/state-2.sql)(4c04efff)에서 바꾼 것은 DYN_PAPER 소유 분류 하나다.**

- `DYN_PAPER`를 dynamic_session과 조인한다.
  - 세션이 PAPER이면 모의로 보고, 행을 내지 않고 개수만 센다.
  - 세션 행이 없으면 `NO_SESSION_ROW`다.
  - 세션이 PAPER가 아니면 `KIND_MODE_MISMATCH`이고, 변환기에서 UNCLASSIFIED 수집 불통과가 된다.
- U·CNT의 모의 제외 조건도 같은 기준으로 맞췄다.
- 그 밖의 열·필터·출력 형식은 그대로다.

**변환기 사본 [convert_state_2.py](candidate/convert_state_2.py)(06bd8640)는 판 표지 두 상수만 바꿨다.** 판정식은 같다.

| 경계 | psql | 변환 | W1 / W2 |
|---|---|---|---|
| B1 정상: LIVE203 CREATED·DYN900 REAL·DYN901 PAPER(DYN_PAPER 주문·포지션), 정산 표지(반영 대기1·미해결 불일치1, 해결된 것과 모의 소유는 제외) | rc0·0B | CONVERTED, U `1 1`, 모의 1/1 | SET_MATCH / RISK_PRESENT [SETTLEMENT_UNRESOLVED] |
| B2 소유자 없음(session_id NULL)·알 수 없는 종류(`PAPER`)·세션 행 없음(DYNAMIC 777)·DYN_PAPER→REAL 900 | rc0·0B | COLLECTION_NOT_PASSED: 주문4 NO_SESSION_ID·주문5 UNKNOWN_KIND·주문6 UNCLASSIFIED·포지션14 NO_SESSION_ROW | 호출 안 함 |
| B3 등록 DYNAMIC 900의 trading_mode를 PAPER로 변경 | rc0·0B | CONVERTED, 표시 주문·포지션 0, 모의 2/2 | MISMATCH(누락 DYN900) / NO_RISK_ITEMS. W2 단독으로는 못 잡고 W1이 잡는다 |
| B4 과거 미등록 LIVE157 STOPPED의 FILLED 매수·매도 + CLOSED 포지션 | rc0·0B | CONVERTED | SET_MATCH / RISK_PRESENT [UNREGISTERED_REAL_ORDER 21·22]. **기존 경보 유지** |

- **시간대 대조:** B1을 세션 시간대 `UTC`와 `Asia/Seoul`(PGTZ, `SHOW timezone`으로 확인)로 두 번 실행했다. S/O/P/U/CNT 8줄이 바이트 단위로 같았다. 생성 시각은 두 경우 모두 `2026-10-30T04:16:58.044684+00:00`이다.
- **금액 노출:** 표지값 5개가 SQL 출력·변환·W 출력에 나온 경우는 4경계 모두 0건이다.
- **볼 점(판단하지 않음):** pm-state-2도 원래 설계처럼 `DYNAMIC` 종류의 주문·포지션이라도 세션이 PAPER이면 개수로만 남긴다. 등록 세션이면 W1이 잡는다. **미등록 세션이 REAL→PAPER로 바뀌면 그 세션의 실자금 행은 목록에서 빠진다.** 제품에는 `setTradingMode` setter가 있지만, 생성 밖의 변경 경로는 확인하지 못했다.

## 2. P0 입력 후보 — 로컬 파일 전용 (§3-2)

- **[p0_input.py](candidate/p0_input.py) pm-p0-1(3fdd580a):** 고정 U변수.sh(efb96108)의 해시를 확인한 뒤 holdobs 함수의 python 본문만 꺼내 실행한다. U변수 전체나 운영 명령은 실행하지 않는다. 패턴은 원문 그대로이며 넓히지 않았다.
- **입력:** backend 로그·수집 rc·구간 근거(`{since, until, axis, basis}`).
- **결과는 세 가지다.**
  - `P0_PRESENT`(10)
  - `P0_ABSENT_IN_PROVIDED_LOG`(0): 제공 로그 안에서 그 패턴이 없다는 뜻일 뿐이다.
  - `P0_CHECK_INCOMPLETE`(20): `p0_events=null`.
- **발생 줄은 숨기지 않는다.** 수집이 불완전해도 비어 있지 않은 로그에서 발생 줄이 나오면 `P0_PRESENT`로 낸다.
- **장기 대여 경고(holdobs 3)는 별도 필드다.** 뉴스·캔들·외부 timeout은 이 도구가 분류하지 않는다.
- **aux 연결:**
  - 기존 pm-aux-1은 원천이 하나라 N03과 backend 로그를 구분하지 못한다. 그래서 새 사본 [convert_state_3.py](candidate/convert_state_3.py) pm-conv-3(a131c531)이 **pm-aux-2**를 받는다. pm-aux-2에서 `n03`은 미분류 ERROR·66의 원천과 rc를, `p0`은 p0_input 결과 파일의 해시를 담는다.
  - P0 미완료는 `AUX_P0_CHECK_INCOMPLETE` 수집 불통과이며 관측 파일을 내지 않는다.
  - 결과에는 state(db 구간)·n03·p0(로그 구간·해시)를 서로 다른 원천으로 적는다.
  - 기존 pm-aux-1·pm-conv-1·pm-conv-2는 그대로다.

[p0-run1](runs/p0-run1/cases_p0.out.txt) **5/5**([cases_p0.py](candidate/cases_p0.py)). 상태 입력은 pg-run2 B1의 실제 psql 출력(d7728145)을 읽기만 했다.

| 경계 | P0 결과 | 변환 → W2 |
|---|---|---|
| Q1 유효한 비발생: 장기 대여 경고(HOLD 1·반환)·뉴스 Connection reset·캔들 HTTP 502·외부 Read timed out | ABSENT(0), holdobs 3, events [] | CONVERTED → W2에 P0 없음 |
| Q2 DB 획득 timeout(머리줄 + Caused by) | PRESENT(10), events 2 | CONVERTED → W2 [P0, SETTLEMENT_UNRESOLVED] |
| Q3 로그 수집 rc1(부분 로그, 발생 없음) | INCOMPLETE [LOG_COLLECTION_FAILED]. holdobs는 0이어도 미완료 | COLLECTION_NOT_PASSED, 출력 없음 |
| Q4 빈 로그 | INCOMPLETE [LOG_EMPTY] | COLLECTION_NOT_PASSED |
| Q5 구간 근거 미제공 | INCOMPLETE [RANGE_NOT_PROVIDED] | COLLECTION_NOT_PASSED |

## 3. 실제 읽기 승인안 1개 (§3-3) — 미실행

[approval/README.md](approval/README.md)와 [pm-read.sh](approval/pm-read.sh)(7f07c470)에 다음을 고정했다.

- 고정 파일 해시
- 요청 범위: 서버 시각·설치 상태, 상태 SQL 1회, 같은 구간 backend 로그 1회. 서버 쓰기는 0이다.
- 금액 없는 허용 열
- 보존 경로 `pm-read-<YYYYMMDD-HHMM>/`
- 멈춤 조건
- 결과 해석: 과거 종결 행은 경보 유지, P0는 기존 기준, registry-3이며 실제 파일은 미정이라 W1/W2를 실행하지 않음, P7·월말·F3는 별도

운영값(SINCE·실행 창·N03 전사 회차·쿼리 보호값·로그 커밋 범위)은 채우지 않았다.

**read 단계는 실행하지 않았다.** post 단계만 합성 사본으로 로컬 사전 점검했다. 이 점검에서 결함 2개를 발견해 고쳤다.

- 해시 쌍을 나누는 부분이 `D:` 경로의 콜론 때문에 깨졌다.
- aux 작성이 실패해도 멈추지 않았다.

고친 뒤 확인한 결과는 다음과 같다.

- 정상: CONVERTED
- backend가 SINCE 뒤에 재기동한 경우: 구간 근거 미제공 → P0 미완료 → 수집 불통과
- N03 값 미제공: AUX_NOT_PROVIDED

임시 폴더는 삭제했으며 결과로 보존하지 않는다.

**코드로 본 예상(확인 아님):** 운영에는 비세션 주문(`session_id` NULL)과 과거 종결 주문이 남아 있을 수 있다. 그러면 첫 실제 읽기는 수집 불통과이거나 경보 상태가 된다. 이것은 실패가 아니며 과거 행별 인수에 쓸 식별 목록이 된다.

## 4. 판단 요청

1. pm-state-2의 DYN_PAPER 분류와 `KIND_MODE_MISMATCH` 처리를 수용할지.
2. 미등록 세션이 REAL→PAPER로 바뀐 경우의 실자금 행 처리. 현재는 개수로만 남는다.
3. pm-aux-2(원천 분리)와 P0 우선 규칙(수집이 불완전해도 발생은 PRESENT)을 수용할지.
4. 승인안 §7의 값 다섯 가지. backend 로그 원문의 커밋 범위가 여기에 포함된다.

## 5. 원문·체크섬

- `.gitattributes`(`* -text`)를 처음에 두었다.
- 1회차 실패 원문은 그대로 보존했다. 2회차는 새 폴더에 저장했다.
- 체크섬 목록은 [SHA256SUMS](SHA256SUMS)다(이 폴더 전체, 자기 자신 제외).
