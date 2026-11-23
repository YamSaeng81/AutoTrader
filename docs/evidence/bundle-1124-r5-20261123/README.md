# 11-24 묶음 r5 — 관측 수집안의 범위·실패 처리·원가 원천 보정 (총괄 v1.229 §3 R1~R3·§4)

2026-11-23 KST 개발. 기준 지시 [v1.229](../review-825d7efd/README.md)(ae57fbf3). r4 폴더와 체크섬은 바꾸지 않았고 이 폴더에 새 원문·r4 대비 차이·시험 원문을 둔다. **운영 호출 0회** — SSH·서버 별칭·가짜 SSH·운영 DB·계좌·Docker 운영 스택·예약 조회 없음. 실행한 것은 격리 로컬 PG 1묶음 4사례와 파일 경계 7개뿐이다.

이 읽기는 **허용된 관측값 수집안**이다. A3 미배정 현금 확정이나 '충분' 판정 경로가 아니며(v1.229 §2), 실행 승인을 요청하지 않는다.

## 1. 새 원문과 r4 대비 차이

| 파일 | 고정 해시(sha256 앞 8자) | r4 | 차이 |
|---|---|---|---|
| [read/a3-pre.sql](read/a3-pre.sql) a3-pre-2 | **69e1f277** | d505d08f | [diff](diff/a3-pre.sql.diff) |
| [read/a3-proj.py](read/a3-proj.py) a3-proj-2(파일 전용 반출 변환) | **24802e60** | r4 원격 heredoc | [diff](diff/a3-proj.py.diff) (r4 heredoc 추출본 [r4-projection-heredoc.py](diff/r4-projection-heredoc.py)) |
| [read/a3-remote.sh](read/a3-remote.sh) a3-remote-2 | **2cb80742** | 94db4ca9 | [diff](diff/a3-remote.sh.diff) |
| [read/a3-finish.sh](read/a3-finish.sh) a3-finish-2(PC 마무리, 파일 전용) | **fb2aac5e** | r4 PC 뒷부분 | 새 파일. r4의 ssh 뒤 처리 부분을 대신한다 |
| [read/a3-preread.sh](read/a3-preread.sh) a3-preread-2 | 이 폴더 SHA256SUMS | 324d05d9 | [diff](diff/a3-preread.sh.diff). 위 네 해시를 고정해 하나라도 다르면 원격 0회로 거절 |

### R1. LIVE#203 REAL 범위와 서버 승인 창

- **대상 고정**: PC는 `--kind LIVE --sid 203` 이외를 시작 전에 거절한다. 대상을 묶지 못하던 `--reg-id`는 뺐다. 원격도 폴더를 만들기 전에 같은 대상을 다시 확인한다(`TARGET_NOT_APPROVED`).
- **SQL 관문(금액보다 먼저)**: 같은 REPEATABLE READ READ ONLY 트랜잭션에서 META 다음 바로 `SCOPE`를 정한다. 순서는 `SID_NOT_APPROVED`(203이 아님) → `SESSION_NOT_FOUND` → `SESSION_NOT_REAL` → `OK`. psql `\gset`/`\if`로 OK일 때만 TSES·OBUY·CNT·XCNT·POS·MAXTS·MORD를 실행한다. 불통과면 META·SCOPE·END만 나온다. 인자는 숫자로 캐스팅하지 않고 문자열로 비교한다.
- **범위 절은 모두 LIVE#203 소유 행만** 읽는다. r4의 전 REAL 세션 SESS·OBUY·POS·MAXTS는 없앴다. MORD도 같은 관문 안에 있어 PAPER 주문 금액은 선택되지 않는다. 별도 MSES는 TSES로 합쳤다.
- **대상 밖은 XCNT 개수만**: 멈추지 않은 다른 REAL 세션, 다른 REAL 열린 주문, 다른 REAL 열린 포지션, 대상 밖 정리 대기, 소유 없는 열린 주문. 행과 금액은 내지 않는다. 소유 없는 행 면제·가짜 등록·전체 자본 합산은 없다. 이 SQL로 S 전부를 안다고 표시하지 않는다. 배정 전체는 계속 보류한다.
- **서버 KST 승인 창**: 승인 날짜·창·대상을 원격 인자로 넘긴다. 원격 `gate()`가 **SQL 직전과 계좌 호출 직전**에 `TZ=Asia/Seoul date +%F_%H:%M`를 한 번에 읽어 순수 함수 `win_ok`로 판정한다. 불통과면 `STOP 2 WINDOW_OUT_SQL|ACCOUNT`로 끝나고 그 호출은 0회다. 자정을 넘는 창은 받지 않는다. 운영에서 실행하지 않았으므로 이 흐름은 [원문 차이](diff/a3-remote.sh.diff)와 제어 흐름 검토로만 확인했다.
- **SCOPE가 OK가 아니면** 원격은 `STOP 8 SCOPE_DENIED`로 끝난다. 계좌와 앱 시계 호출은 0회다.
- **앱 시계**: 종료값 0이고 `YYYY-MM-DDTHH:MM:SS.ffffffZ` 형식일 때만 표본으로 쓴다. 첫 표본이 실패하면 계좌 호출 전에 `STOP 7`로 끝난다. 둘째 표본이 실패하면 `APP_CLOCK_2 invalid`이며 최종 종료값은 7이다.
- 실제 스키마·PGOPTIONS 보호값·READ ONLY·ROLLBACK·재시도 없음은 유지했다.

### R2. 계좌 실패·반출 검사·필수 기록

- **a3-proj.py(파일 전용)**: 장부 형식과 SCOPE OK를 먼저 확인한 뒤 계좌를 검사한다. 순서는 `JSON_INVALID` → `NOT_SUCCESS` → `KEY_NOT_CONFIGURED` → `SERVICE_ERROR` → `FIELD_INVALID`이다. FIELD_INVALID는 KRW 세 값이 정수가 아니거나, fetchedAt이 ISO UTC 형식이 아니거나, 보유 필드가 숫자가 아니거나 통화 코드 형식이 틀린 경우다.
  - 불통과면 고정 사유 한 줄과 비영 종료값(계좌 6·범위 8·형식 4)만 남긴다. JSON 오류 문구와 API 오류 문자열은 내지 않는다.
  - 시세가 없거나 0인 보유분은 `ACCT_VALUATION UNKNOWN price_unknown_n=N`로 남기고 BTC 가격은 `UNKNOWN`으로 둔다. 계좌 수집 성공(종료 0)과 자산 평가 확정을 구분한다.
- **원격**: curl 실패나 HTTP≠200이면 변환하지 않고 `STOP 6`으로 끝난다. 변환이 불통과면 첫 고정 사유 줄과 `STOP <rc> PROJECTION_FAILED`만 낸다. 서버 해시 목록 실패는 `STOP 4`이며, 앞 비영 값이 있으면 그 값을 보존한다. STOP 사유는 모두 ASCII 고정 코드다.
- **a3-finish.sh(PC, 파일 전용) 반출 검사**: 토큰 형태 grep은 발견(0)이면 **종료 9**, 0건(1)이면 통과, 읽기/검사 실패(2 이상)면 종료 4 `EXPORT_CHECK_FAILED`로 구분한다. 이어 허용 표지 밖 줄과 허용 문자 밖 줄도 불통과로 본다. 불통과면 본문을 출력·확정하지 않고 고정 사유만 낸다. remote.out은 지우거나 고치지 않는다.
- **원격 실패(비영)**: 데이터 줄은 내지 않는다. 검사를 통과한 고정 사유 줄(STOP·ACCT_FAIL·LEDGER_FAIL)만 내고 원격 종료값을 그대로 쓴다.
- **확정 순서**: 반출 검사 → PC-SHA256SUMS → **RESULT.txt(마지막 필수 기록)** → 본문 출력 → 완료 문구 → INCOMPLETE 제거. 각 단계의 성공을 확인한다. 실패하면 종료 4이고 미완료 표지를 남긴다. 앞 비영 값은 보존한다. INCOMPLETE 제거 뒤에는 반드시 성공해야 할 기록이 없다.
- **후속 소비 조건**: `RESULT.txt` 첫 줄이 `OK a3-finish-2`이고 `INCOMPLETE.txt`가 없을 때만 a3calc3.py 입력으로 쓴다.
- curl `-H @파일` 지원은 미확인이다. 운영 탐색은 하지 않았고 승인 본문의 사전 조건으로 남긴다.

### R3. 매수 총원가와 남은 보유 원가

- POS 열을 `buy_n·buy_qty·buy_cost_total(과거 매수 총원가)·buy_cost_unknown_n·buy_unsettled_n·sell_confirmed_n·sell_confirmed_qty·sell_unknown_n·sell_inflight_n·discrepancy_open_n·size(현재 잔량)·cost_state·remaining_cost_d81`로 나눴다.
- `remaining_cost_d81`은 `cost_state=ALLOCATED`일 때만 값을 낸다. 식은 d81 `RiskManagementService.costBasisClosedPnl`의 매도분 원가 식(`sellQty × buyCost ÷ buyQty`, 8자리 반올림)과 같은 수량 배분으로 `buy_cost_total − 매도분 원가`다. 그 밖의 상태는 `-`(미확정)다.
- `cost_state` 우선순위: NO_BUY_SOURCE → BUY_COST_UNKNOWN → BUY_SETTLEMENT_PENDING → SELL_UNKNOWN → SELL_INFLIGHT → DISCREPANCY_OPEN → CLOSING → QTY_MISMATCH(`buy_qty − sell_qty ≠ size`) → ALLOCATED.
- BUY가 0행이면 `NO_BUY_SOURCE`이고 원가는 `-`다. r4의 `0=0 → complete=true`는 없앴다.
- 평가·실현 손익 계산은 넣지 않았다. 제품·변환기 v4·성적표 v5·W4 R1은 바꾸지 않았다.

## 2. 검증 — 신규 격리 PG 1묶음 4사례 + 파일 7경계

**격리 PG**([runs/pg-scope-r1](runs/pg-scope-r1/summary.json), 시험 [test/pg_scope.py](test/pg_scope.py)): 고정 [setup_pg.py](../dec-read-gate-v3-20261120/setup_pg.py)(98b3eab0)를 썼다. 컨테이너는 `--network none`·tmpfs, 이미지는 기존 `timescale/timescaledb:latest-pg15`, d81c3f2f V1~V96 95개를 적용했다([setup](runs/pg-setup/setup.txt)). 사례마다 base를 복제하고, 읽기안 형식(표준 입력·PGOPTIONS·`-At -F TAB`·`with_orders=true`)으로 SQL을 1회 실행했다. SHOW는 15s/2s였다. 끝난 뒤 컨테이너를 삭제했다. **4/4 통과**:

| 사례 | 결과 |
|---|---|
| A REAL#203(+범위 밖 REAL 204·PAPER 205·DYNAMIC REAL 900·소유 없는 열린 주문) | SCOPE OK. 범위 밖 금액 9종 0건. TSES/OBUY/POS/MORD는 203 소유 행만. CNT `1 0 2`, XCNT `2 1 2 1 1`. **포지션 21**: 20개 10,005원 매수 → 10개 매도, 잔량 10, `ALLOCATED`, remaining **5002.5**(총원가 10005를 넘기지 않음). **포지션 22**: BUY 없음 → `NO_BUY_SOURCE`, 원가 `-` |
| B 203이 PAPER | `SESSION_NOT_REAL`, META·SCOPE·END만 |
| C 203 세션 행 없음(203을 가리키는 주문/포지션 행은 남김) | `SESSION_NOT_FOUND`, META·SCOPE·END만 |
| D sid=204(REAL) 지정 | `SID_NOT_APPROVED`, META·SCOPE·END만 |

**파일 7경계**([runs/file7-r1](runs/file7-r1/summary.json), 시험 [test/file7.py](test/file7.py)): a3-proj.py와 a3-finish.sh만 실행했다. remote.out은 a3-remote.sh 3)절 출력 규칙을 시험 파일이 흉내 내어 만들었고, a3-remote.sh·SSH·가짜 SSH·docker·curl은 실행하지 않았다. 작업 폴더는 저장소 밖 임시 폴더다. **7/7 통과**:

| 경계 | 변환 | PC 마무리 | 확정/본문/소비 |
|---|---|---|---|
| b1 정상(ETH 시세 0 포함) | 0, `ACCT_VALUATION UNKNOWN price_unknown_n=1` | 0 | 확정·본문 출력·소비 가능 |
| b2 손상 JSON | 6 `JSON_INVALID` | 6 `REMOTE_FAILED` | 없음, 고정 사유 줄만 |
| b3 서비스 error | 6 `SERVICE_ERROR`(오류 표지 문자열 미반출) | 6 | 없음 |
| b4 키 미설정 | 6 `KEY_NOT_CONFIGURED`(안내 문구 미반출) | 6 | 없음 |
| b5 합성 토큰 표지 | (가) 계좌 필드 → 6 `FIELD_INVALID` | (나) 변환 우회를 가정해 remote.out에 표지를 넣고 원격 0 → **9** `EXPORT_TOKEN_SHAPE_FOUND` | 없음. 표지가 든 입력은 저장소에 넣지 않고 해시만 기록 |
| b6 반출 검사 읽기 실패(remote.out이 폴더) | 0 | 4 `EXPORT_CHECK_FAILED`(발견 0건과 구분) | 없음 |
| b7 마지막 필수 기록 실패(RESULT.txt 쓰기 불가) | 0 | 4 `RESULT_RECORD_FAILED` | 없음, INCOMPLETE 유지 |

시험 소스 해시는 각 summary.json에 있고, 고정 원문 해시와 같다(시험 뒤 원문 수정 없음). 각 경계에서 기록 폴더의 토큰 형태 0건을 확인했다.

**과정 기록**: 첫 setup_pg.py 호출은 작업 폴더를 `docs/evidence`로 둔 탓에 마이그레이션 목록이 비어 flyway 표 생성에서 멈췄다. 컨테이너를 지우고 저장소 루트에서 다시 준비했다. SQL 실행 전 준비 실패다. 스키마 열 확인용 빈 DB `probe`(데이터·SQL 실행 없음)를 만들었다가 사례 실행 전에 지웠다. 같은 A3 2/3/8건·D81 시험·제품 PG·회귀·jar 빌드는 반복하지 않았다.

## 3. 승인표 — 관측 수집 / 자금 충분 판정 / 월중 손실 / 월말 한계 (r4 §5를 대신한다)

각 행은 별도 승인이다. 원문 수용은 실행 승인이 아니다.

| # | 구분 | 원문 | 운영 호출 | 상태 |
|---|---|---|---|---|
| 1 | D81 배포 1회 | D81A2 65a384e5 기존 블록(U0 안 A2 1회 → U1 → U2 직전 확인 → U3 → U4 → 뒤 관측) | 블록 원문 그대로 | 승인 전. **A3 조회 통과를 조건으로 붙이지 않는다**(v1.229 §2) |
| 2 | 관측 수집(배포 전 1회) | a3-preread-2 `--with-orders false` | ssh 1(SQL 1·앱 시계 ≤2·계좌 요약 ≤1, 창/범위 불통과면 그 뒤 0) | **원문 제출(미실행). 실행 승인을 요청하지 않는다.** 배정 원천 미기입이면 원문이 거절 |
| 3 | 자금 충분 판정(A3 '충분') | 해당 원천 없음 | — | **보류.** 변화 근거와 배정 전부 포함 근거가 없다. 위 수집으로 대신하지 않는다 |
| 4 | 월중 손실 원천 | 같은 원문 `--with-orders true`, F3-2 회차 직후 | 회차당 #2와 같은 1회 | 승인 전. 자금 이벤트 담당 미기입. R3 원가는 ALLOCATED일 때만 잔여 원가 |
| 5 | 월말 한계 | 같은 원문, 12-31 경계 전·01-01 경계 뒤 각 1회 | 각 1회 | 승인 전. T 포착 아님 → **달력월 미확정(종결된 한계)** |
| 6 | 당일 F1(12-01 08:30) | RUNBOOK F1 원문 | F1 그대로 | 시작 승인 안 됨 |

**운영자 회신(비어 있으면 미완료):** 배포 실행자·창 ____ · U2 직전 확인자 ____ · 휴대폰 실제 수신 확인 시각 ____ · 대응 가능 시간대 ____ · 대응 불가 시간의 대체 경로 ____ · 활성 주문/정리 대기/실보유 확인 담당 ____ · 자금 이벤트 기록 담당·파일 경로 ____ · 인증 헤더 파일 준비자·서버 경로(+curl `-H @파일` 지원 확인) ____ · 관리자 읽기·보안 회신 ____.

11-25 조건부 배포 목표·11-27 중간 판정·11-30 최종 S, 예산 100,000원/12월 손실 40,000원, 기존의 더 엄격한 보호는 그대로다.

## 4. 남은 한계

- 서버 창 관문·앱 시계 관문·원격 STOP 흐름은 운영과 가짜 SSH에서 돌리지 않았다. 원문 차이와 제어 흐름으로만 확인했다. 파일 7경계의 remote.out은 흉내 낸 것이다.
- curl `-H @파일` 지원, 서버 `python3` 존재, `docker compose exec`의 PGOPTIONS 전달은 미확인이다. 격리 검증은 `docker exec -e` 형식이었다.
- XCNT는 개수뿐이다. 소유 없는 열린 주문이 0이 아니면 보류이며, 배정 전부 포함은 이 SQL로 증명하지 않는다.

## 5. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더 전체, 자신 제외). 예약 4bc9e12a(22:14)는 조회·변경하지 않았다.
