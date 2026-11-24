# r1-diag-2 — SQL 출력 계약 보완 · 격리 로컬 PG 1묶음 · 진단 전용 실행기 (총괄 v1.234 §3)

2026-11-24 Claude Code 로컬 작업(12:47 KST 기준으로 PG를 실행함). **운영 SSH·DB·계좌 호출 0회, 가짜 SSH 0회.** 격리 로컬 PG 1묶음을 1회 실행해 통과했다. 실행기는 `bash -n`과 원본 대조까지만 했다. **이 문서는 운영 실행 승인이 아니다.** 실행기의 승인 값 3칸은 빈칸이다. 원본 r1-diag-1·model·체크섬은 바꾸지 않았다. U0 재시도·U1·U2는 보류를 유지한다.

## 1. SQL 보완 — [sql/r1-diag-2.sql](sql/r1-diag-2.sql) `4134c631`

원본 r1-diag-1 `7c26631d`의 사본이다. 차이는 [diff/sql-1-vs-2.diff](diff/sql-1-vs-2.diff)에 있다.

- **F1 해소:** 분해 행(`res`)의 모든 UNION 분기와 최종 SELECT를 `tag + c1~c8`(9열)로 넓혔다. UQ의 c8에 `union_items = union_pos + a_null_pos`를 실제로 출력한다. 쓰지 않는 칸은 `\N`이다.
- META 식별자를 `r1-diag-2`로 바꾸고, 머리 주석에 열 수 고정(META 10 · REF 4 · ORIG 3 · END 2 · 분해 9)을 적었다.
- PE `exit_reason` 그룹: 코드 형식(`^[A-Z][A-Z0-9_]{0,39}$`)에 맞으면 그대로, 맞지 않으면 `(nonconforming)`으로 묶는다(NULL은 `(null)`). 수동 편집으로 들어간 자유 문자열이 서버 밖으로 나가지 않게 하기 위해서다(§3-3 반출 범위). 최소 보완 범위를 넘는다고 보면 이 한 줄만 원래 식으로 되돌릴 수 있다.
- **바꾸지 않은 것:** ORIG 문장은 원본과 글자 그대로 같다(기계 대조). `sa`/`pc`의 WHERE(R1 선택 조건), 분류식, 보호값 DO 블록, 격리 수준, ROLLBACK도 그대로다.

## 2. 검사기 — [check/shape_check2.py](check/shape_check2.py) `7f56f640`

종료값은 0 `SHAPE_OK` 또는 7 `SHAPE_MISMATCH` 둘뿐이다. 예외가 나도 traceback 없이 7을 낸다.

- **열 수를 먼저 확인한다.** 인덱스 접근·언패킹 전에 태그별 열 수를 정확히 대조한다. 쓰지 않는 칸이 `\N`이 아니어도 거절한다. 열 수가 하나라도 틀리면 관계 검사로 넘어가지 않는다. 옛 검사기는 뒤쪽 `\N`을 지운 뒤 인덱스로 바로 접근했다. 그래서 9열 합성 입력은 통과했고 실제 8열 출력에서는 예외가 났다. 누락을 0으로 메우지 않는다.
- **필수 행 집합:** 종류(LIVE·DYNAMIC)마다 모드 4·AX 3·AF 7·PX 3·PF 12·AGE 2×5·LEG 2·ORIG/SPLIT/UQ 1이 정확히 한 번씩 있어야 한다. 옛 검사기는 합만 봤기 때문에 0건인 종류의 행이 통째로 빠져도 통과했다.
- **META:** `r1-diag-2`, **Flyway 96**, `repeatable read`, 15s/2s, 시각 형식, `since = db_now − 30일`, `db_now ≤ db_clock ≤ END`를 확인한다. REF는 81·87·94가 각 1행이어야 한다.
- **관계:** ORIG = A+P · AM/PM/AX/PX/PE 합 · AGE 분기 합 · PX BOTH = 0 · AF/PF 각 ≤ 분기 건수 · Q_NO_SETTLED + Q_PARTIAL = ② · LEG ≤ 분기 · UQ 산식(`union = dist + p − both`, `items = union + null`, `both ≤ min`)을 확인한다.
- **계약 밖 값:** 이유 문구에는 태그·종류·열 위치만 쓰고 값 원문은 내지 않는다. PE 그룹도 위 형식만 허용한다.

## 3. 격리 로컬 PG 1묶음 — [runs/pg-r1](runs/pg-r1/summary.json) 통과

- **환경:** 고정 [setup_pg.py](../dec-read-gate-v3-20261120/setup_pg.py)(`98b3eab0`)를 그대로 썼다. `timescale/timescaledb:latest-pg15` = PostgreSQL 15.13, `--network none`, 데이터 tmpfs, d81c3f2f V1~V96 95개([setup](runs/pg-setup/setup.txt)). 생산 별칭·SSH·운영 인증·실제 DB 파일·제품 시험·회귀·jar는 쓰지 않았다. 끝난 뒤 컨테이너 `r1d2-pg`를 삭제했고, 남은 컨테이너 0을 확인했다.
- **Flyway 표:** setup의 최소 표(version, success)는 복제 DB 안에서만 실제 Flyway 열 구성(installed_rank·installed_on timestamp…)으로 바꿨다. V81은 45일 전, V87은 38일 전, V94는 20일 전에 설치된 것으로 넣었다. 실패한 97 1행과 버전 없는 반복 이관 1행도 넣었다(→ META 96이 나와야 함).
- **시험기:** [test/pg_r1diag2.py](test/pg_r1diag2.py)(`d98f3f85`). 합성 입력은 [fixture.sql](runs/pg-r1/fixture.sql), 손계산 기대 103행은 [expected.tsv](runs/pg-r1/expected.tsv)다. 결과는 파일 모델(model.py)이 아니라 SQL만 만든다.

합성 사례(같은 DB 한 묶음):

| 사례 | 들어가는 곳 |
|---|---|
| 같은 포지션 매도 2건 대금 NULL + 미확정 청산 | A ③×2 + P ② **겹침**, V81·V87 전 개시 |
| 매수 수수료 NULL | A ① C_BUY_COST_NULL (P 아님) |
| `position_id` NULL 매도 2건(1건은 now+1h) | A ① C_NO_POSITION, AGE D30P(시계 차이 모사) |
| 세션 PAPER·행 없음·모드 WEIRD | PM PAPER / NO_ROW / OTHER |
| 주문 없는 청산 · ENTRY_REJECTED size≠0 · 취소 미확정 · 체결 수량 NULL+미반영 매도 | P ④ 각 표지 |
| 코드 형식 밖 exit_reason | PE `(nonconforming)`, 원문 미반출 |
| **제외:** FAILED 무거래 · ENTRY_REJECTED size 0 · 30일 창 밖 · DYN_PAPER · OPEN · session_id NULL · LIVE 정상 | 0 |
| LIVE PAPER 세션 매도 수량 0 | LIVE A ③ + P ② 겹침 |

결과(원문은 [normal/out.tsv](runs/pg-r1/normal/out.tsv)):

| 회차 | 결과 |
|---|---|
| **normal** (PGOPTIONS 15s/2s) | psql rc0, stderr 0바이트. META `r1-diag-2`·96·repeatable read·UTC·15s·2s. REF 81·87·94. 본문 103행이 손계산과 **정확히 일치**(누락 0·초과 0). ORIG DYNAMIC 11 = A5 + P6, UQ `5 2 2 6 1 7 9` / LIVE 2 = 1+1, UQ `1 1 0 1 1 1 1`. 자유 문자열 미반출. 실행 전후 DB 행 수 같음(21/16/97). **shape_check2 실제 rc0 `SHAPE_OK`** |
| **guard_14s** (statement_timeout=14s) | psql **rc3**, stdout 빈, stderr `R1D_GUARD 보호값 불일치 statement_timeout=14s lock_timeout=2s` → 출력 전에 중단 |
| **uq_short** (normal 실제 출력에서 DYNAMIC UQ 마지막 칸 제거) | shape_check2 **rc7** `82행 UQ: 열 수 8 ≠ 9`, stderr 0바이트(traceback 없음) |

수집 시각·SQL 입력 해시·PGOPTIONS·rc·stdout/stderr는 회차 폴더마다 보존했다. 기존 14/13 파일 시험과 다른 PG·회귀는 반복하지 않았다.

**한계:** 접속 계정이 `trader`가 아니라 `postgres`다(권한 차이 미검증). 실제 데이터 양에서 15s 안에 끝나는지는 확인하지 않았다. 운영 TimeZone과 Flyway installed_on의 실제 시간대도 미확인이다(REF가 원값과 해석값을 함께 내고, META가 TimeZone을 낸다). 앱 시계와 DB 시계의 차이는 이 시험 범위 밖이다.

## 4. 진단 전용 실행기 — [approval/r1-diag-read.sh](approval/r1-diag-read.sh) (미실행)

바탕은 a1-read.sh v2 [`b1e9e1f0`](../a1-fake-boundary-20261123/prod/approval/a1-read.sh)이다. 전체 차이는 [diff/a1-read-v2-vs-r1-diag-read.diff](diff/a1-read-v2-vs-r1-diag-read.diff)에 있다.

| 항목 | a1-read v2 | r1-diag-read |
|---|---|---|
| 고정 해시 | SQL a1-time-1 `b84a89e7` | SQL r1-diag-2 `4134c631` + 검사기 `7f56f640`. 시작 때·보존 사본·SQL 직전·검사 직전에 다시 확인 |
| 인자·승인 값 | `<폴더> <NOT_AFTER>` | `<폴더>` 1개. **APPROVED_DATE·WIN_START·WIN_END를 파일 안 빈칸으로 둔다.** 빈칸·형식 오류면 원격 0회로 거절(2). 폴더 날짜는 APPROVED_DATE와 같아야 함 |
| 보존 폴더 | `a1-read-YYYYMMDD-HHMM`, 다른 `a1-read-*`가 있으면 거절 | `r1-diag-read-YYYYMMDD-HHMM`, 다른 `r1-diag-read-*`가 있으면 거절 |
| 창 확인 | PC 시각 ≤ NOT_AFTER | ① 원격 전 PC 시각이 창 안(2) ② (1)의 server_utc와 PC 차이 ≤ 120초 ③ server_utc가 창 안이고, SQL 직전 추정 서버 시각 ≤ 끝 ④ 결과 META db_now가 창 안. ②~④를 어기면 6. 원격 호출은 늘리지 않음 |
| 형태 검사 | awk로 META/END/CNT·OT/PT 합 확인 | META awk로 Flyway를 먼저 확인(불일치 3). 그다음 해시를 확인한 검사기 사본을 `python -I`로 실행하고 **그 프로세스 종료값**을 기록·사용. rc0·출력 `SHAPE_OK` 한 줄·stderr 없음이 아니면 7 |
| 오류 반출 | stderr 파일 보존 | 같음. 화면에는 내지 않고 `sql-stderr-class.txt`에 분류만 기록(GUARD·STATEMENT_TIMEOUT·LOCK_TIMEOUT·OTHER). OTHER는 총괄 확인 전에 인용·해석하지 않음 |
| 그대로 유지 | — | 원격 2회와 ssh 줄(원격 도구는 이 1줄뿐), 설치 핀(HEAD·이미지·running·재시작 0), 설치 실패·stderr·핀 불일치 시 SQL 0회, PGOPTIONS 15s/2s·READ ONLY·ROLLBACK, 미완료 표지→기록→해시 목록→상태(마지막), 기록 실패 4, 자동 재시도 없음 |

READ_OK가 되려면 SQL rc0, SQL stderr 없음, META 형태·Flyway 96, 검사기 실제 rc0, db_now가 창 안, 모든 기록 성공이 함께 충족돼야 한다. 종료값: 0 · 2 · 3 · 4 · 5 · **6(창·시계, 새로 추가)** · 7 · 원격 rc 그대로(255 등).

확인한 것: `bash -n` rc0. 실행기 밖 단독 명령으로 GNU date가 META 시각(`…+00:00`)과 `…Z`를 해석하는지, Git Bash 절대 경로를 받은 윈도 `python -I shape_check2.py`가 rc0/rc7을 내는지만 확인했다. **실행기 자체는 가짜/실제 SSH로 돌리지 않았다.**

## 5. 총괄 판단이 필요한 것

- 승인 날짜·창(UTC)·보존 폴더 이름과 1회 범위. 이 사본에서는 빈칸 3칸을 채우지 않았다.
- PE 정규화(§1)를 받아들일지.
- 창 확인 ②~④의 종료값 6과 허용 시계 차이 120초가 적절한지.
- 실제 데이터 양에서의 15s 한도(시간 초과면 STATEMENT_TIMEOUT으로 분류되고 rc3).

## 6. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더, 자신 제외).
