# r1-diag-4 — 만료·전체 R1·실자금 실행 상태 최소 진단 · 격리 PG 1묶음 · 실행기 사본 (총괄 v1.239 §3-1)

2026-11-24 KST, Claude Code 로컬 작업이다. 격리 로컬 PG 1묶음은 14:30 KST(05:30Z)에 1회 실행했다. **운영 SSH·DB·계좌 호출은 0회다. 가짜 SSH도 0회다.** 실행기는 `bash -n`과 원본 대조만 했다. 승인 값 3칸은 빈칸이다. r1-diag-3 초안([r1-closure-audit](../r1-closure-audit-20261124/sql/r1-diag-3.sql))과 r1-diag-2·v1.236 원문은 바꾸지 않았다. **이 문서는 운영 실행 승인이 아니다.**

## 1. SQL — [sql/r1-diag-4.sql](sql/r1-diag-4.sql) `276a14fe`

[생성기](sql/build_sql.py)가 고정 r1-diag-2(`4134c631`)의 줄을 그대로 잘라 붙였다. 복사 구간은 ORIG 69~70 · bc 78~82 · sa 96~100 · q_any 106~108 · n_any 109~118 · pc 133~148이다. 그래서 R1 식, A/P 선택 조건, ④ 판정식이 글자 그대로 같다. 보호값 DO 블록, REPEATABLE READ·READ ONLY, ROLLBACK, META 10열도 그대로이고 식별자만 `r1-diag-4`다. strategy_log, E/D/O/S 연결, 원인 태그, 세부 분해(AM~LEG)는 뺐다.

| 필수 출력(v1.239 §3-1) | 행 | 내용 |
|---|---|---|
| 1. LIVE/DYNAMIC A·P·합계 | ORIG · SPLIT | ORIG = 고정 R1 식. SPLIT = a, p, a+p. 검사기가 ORIG = a+p를 확인한다 |
| 2. 최초/최종 만료 경계 | EXP `kind class rows first last next_minute` | 경계 = t + 30일(UTC, 마이크로초). next_minute = 분 내림(last) + 1분이다. 경계가 정각 분이어도 그보다 **엄격히 뒤**다. 대상이 0이면 `0 \N \N \N`. class: **KNOWN**(직전 9건의 부류) / **OTHER**(그 밖 전부) / ALL |
| 3. 직전 진단 뒤 행 수 | NEW `kind branch rows` | t > `2026-11-24T04:49:14.512077Z`(1339 META db_now 원문). sell_applied_at/closed_at 값만 본다. 그 뒤의 삽입·수정·삭제 이력을 증명하지 않는다(값을 과거로 고친 행·지운 행은 보이지 않는다) |
| 4. REAL 세션·실행 상태 | RS · RM · RX · OW · TOT · UST · ORPH | RS = REAL 세션 상태 7칸. RM = 세션 모드 REAL/PAPER/OTHER. RX = REAL 소유 열린/정리 중 포지션·미해결 제출(INTENT_READY·SUBMITTING·UNKNOWN)·그 밖 활성 주문(PENDING·SUBMITTED·PARTIAL_FILLED)·정리 대기. OW = REAL 밖 소유(PAPER·DYN_PAPER·**불명 4종** NO_SESSION_ID·KIND_OTHER·NO_ROW·MODE_OTHER). TOT = 전체 합. UST = 알려진 상태값 밖. ORPH = session_id NULL 옛 고아(ACTIVE/TERMINAL·CLOSED/기타)를 금액 없이 따로 센다 |
| 5. 계약 유지 | META · PRIOR · END | Flyway 96, 15s/2s, READ ONLY, REPEATABLE READ, ROLLBACK. PRIOR 행은 고정 상수 세 개를 그대로 낸다 |

**KNOWN 부류의 정의:** DYNAMIC · P 분기 · ④ NO_SOURCE · 세션 trading_mode REAL · `2026-10-25T04:49:14.512077Z ≤ closed_at < 2026-11-01T04:49:14.512077Z` · 같은 포지션이 A 분기에 없음. 이는 직전 1339 진단의 SPLIT(0/9)·PX NO_SOURCE 9·PM REAL 9·AGE D00_06 9에서 정했다. **부류 판정일 뿐 같은 행이라는 증거가 아니다.** 행 ID를 읽지 않는다. 그래서 9건이 빠지고 같은 모양의 다른 행이 생겨도 구분하지 못한다(한계).

반출 범위: 개수, 고정 코드 이름, UTC 시각뿐이다. 금액·수량·가격·손익·행 ID·원시 사유 문자열은 없다. 읽는 표는 order, position, position_cleanup, dynamic_session, live_trading_session, flyway_schema_history다.

## 2. 검사기 — [check/shape_check4.py](check/shape_check4.py) `27902178`

종료값은 0 `SHAPE_OK` 또는 7 `SHAPE_MISMATCH` 둘뿐이다(예외도 7). shape_check2의 원칙을 따른다: 열 수를 먼저 확인하고, 필수 행 집합이 정확히 한 번씩 있어야 하며, 이유 문구에 값 원문을 쓰지 않는다. 관계 검사는 다음과 같다.

- ORIG = a+p = EXP ALL. KNOWN + OTHER = ALL. LIVE KNOWN = 0
- EXP: 0 ⇔ 세 칸 `\N`. first ≤ last. first ≥ db_now. next_minute 산식. ALL 경계 = 두 부류의 min/max. KNOWN 경계는 KNOWN 구간 + 30일 안
- NEW ≤ 분기 수. NEW 합 ≤ OTHER
- RS 합 = RM REAL. TOT = RX + OW. ORPH ACTIVE = OW NO_SESSION_ID 합. PRIOR 상수 일치

## 3. 격리 로컬 PG 1묶음 — [runs/pg-r1d4/summary.json](runs/pg-r1d4/summary.json)

환경: 고정 [setup_pg.py](../dec-read-gate-v3-20261120/setup_pg.py)(`98b3eab0`), PostgreSQL 15.13, `--network none`, tmpfs, d81c3f2f V1~V96 95개([setup](runs/pg-setup/setup.txt)). 컨테이너 `r1d4-pg`는 끝난 뒤 삭제했고 남은 것은 0이다. 시험기는 [test/pg_r1diag4.py](test/pg_r1diag4.py)(`ae8d989e`)다. 사례마다 base를 복제한 DB를 쓴다. 기대값은 사례 표에 손으로 적었다. EXP 시각만 파이썬으로 t + 30일을 더했다.

| 사례 | 확인 | 결과 |
|---|---|---|
| normal | KNOWN 3(상한 1µs 전 포함) · OTHER 5(상한 정각·PAPER 세션·QTY_SHORT·11-10 종료·pid 없는 A) · LIVE A1·P2 · 무거래 확정·창 밖 제외 · 옛 고아 3행 · 세션 상태/모드 혼합 | psql rc0, stderr 0, 본문 = 기대, 검사기 rc0, DB 행 수 불변 |
| empty | 무거래 확정 행만 → EXP 6행 모두 `0 \N \N \N` | 위와 같음 |
| minute_edge | 세션 TimeZone **Asia/Seoul**. KNOWN 마지막 경계 `11-30T23:59:00.000000` → 다음 분 `12-01T00:00`. OTHER 경계 `12-05T12:34:59.999999` → `12-05T12:35:00` | 위와 같음 |
| new_ap | 직전 db_now와 **같은** 시각 A행은 NEW 아님. 30초 뒤 A, 1분 뒤 P, 1µs 뒤 LIVE P는 NEW. DYNAMIC A1·P1, LIVE P1 | 위와 같음 |
| running_real | REAL RUNNING/PAUSED, RX 8칸 1, 소유 불명 4종·PAPER·DYN_PAPER, 상태 불명 주문 2·포지션 1, 옛 고아 ACTIVE·TERMINAL·STATE_OTHER, 완료된 정리 대기는 제외. TOT 손계산 7/2/3/4/3 | 위와 같음 |
| guard_14s | statement_timeout=14s | psql rc3, stdout 빈, stderr `R1D_GUARD` |
| query_fail | position_cleanup 없는 DB | psql rc3, 부분 stdout(END 없음), 검사기 **rc7** |
| mutations | normal 실제 출력 변형 7종 | 6종 rc7 성공. **bad_next_minute는 시험기 결함으로 실패**(아래) |

**실패 1건과 보충:** 시험기의 변형값 `12-01T04:50:00`이 실제 정상값과 같았다(KNOWN 마지막 경계 `12-01T04:49:14.512076`의 다음 분). 그래서 입력이 바뀌지 않았고(`changed=false`) 검사기는 rc0을 냈다. 이는 검사기의 올바른 동작이다. 원 회차는 그대로 보존했다(summary `ok=false`). PG를 다시 돌리지 않고, 보존된 normal 출력의 next_minute만 1분 뒤로 바꾼 파일로 보충했다([test/mut_next_minute.py](test/mut_next_minute.py), [runs/mut-next-minute](runs/mut-next-minute/summary.json)). 결과는 rc7 `next_minute ≠ 분 내림(last) + 1분`, stderr 0이다.

**한계:** 접속 계정이 `trader`가 아니라 `postgres`다. 실제 데이터 양에서 15s 안에 끝나는지는 보지 않았다. 앱 시계(Instant.now)와 DB 시계 차이는 범위 밖이다. 재확인 시각을 정할 때는 next_minute에 시계 여유를 더해야 한다.

## 4. 실행기 사본 — [approval/r1-diag4-read.sh](approval/r1-diag4-read.sh) `13e72a5d` (미실행)

[생성기](approval/make_runner.py)가 고정 v1.236 실행기(`6af7ee65`)에 25개 치환을 정확히 1회씩 적용했다. 전체 차이는 [diff/v1236-vs-r1-diag4-read.diff](diff/v1236-vs-r1-diag4-read.diff)(변경 61줄)에 있다.

| 바꾼 것 | 내용 |
|---|---|
| SQL·검사기 | r1-diag-4 `276a14fe` · shape_check4 `27902178`. 시작 때·사본·SQL 직전·검사 직전 해시 확인은 그대로다 |
| META 식별자 | awk 확인 `$2=="r1-diag-4"` |
| 결과 폴더 | `r1-diag4-read-YYYYMMDD-HHMM`. 다른 `r1-diag4-read-*`가 있으면 거절한다 |
| 직전 결과 확인 | v1.236 전용 1300/1339 고정 줄을 지웠다. 대신 PRIOR 상수 출처인 1339가 완료·`READ_OK exit=0`·r1.tsv `78580b93`일 때만 진행한다 |
| 승인 값 | `APPROVED_DATE=""` `WIN_START=""` `WIN_END=""` — 빈칸이면 원격 0회로 거절(2) |

바꾸지 않은 것: 원격 2회와 ssh 줄 1개, 설치 핀(0e1d2a02·2c5d8427·running·재시작 0), 창·시계 확인(②~④ 종료 6, 120초), PGOPTIONS 15s/2s, 표준오류 분류, 미완료 표지 → 기록 → 해시 → 상태, 기록 실패 4, 자동 재시도 없음. [bash -n](runs/bash-n.txt) rc0. 실제 창·폴더·호출 횟수는 총괄이 정한다.

## 5. 총괄 판단이 필요한 것

- 운영 읽기 1회의 창·폴더(실행기 빈칸 3칸)
- KNOWN 부류 정의를 9건의 대리 기준으로 받아들일지(동일 행 증거 아님)
- 재확인 시각의 시계 여유(next_minute + 몇 초)

## 6. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더, 자신 제외).
