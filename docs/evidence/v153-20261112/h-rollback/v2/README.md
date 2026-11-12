# H 관문 2판 — 장부 기록 실패 시 성공 금지 (총괄 v1.154 §4, 2026-11-12)

**초안이다. 운영 실행·DDL·세션 생성 승인 아님. 자동 되돌림 없음.** 1판(`../rollback_gate.sh`)은 원문 그대로 두었다.

## 바꾼 것

판정 조건(생성 전/후, RUNNING·OPEN·CLOSING·이상 상태)은 1판과 같다. 바뀐 것은 `POST_CREATION_OK` 직전의 장부 기록뿐이다.

- 장부 파일 3개(세션·포지션·주문 CSV): psql 종료 코드 ≠ 0, 오류 출력 있음, **빈 파일(머리 줄도 없음)** 이면 거절.
- `gate-line.txt` 쓰기·비어 있음 검사.
- 체크섬: 생성 실패, **4줄이 아님**, 목록에 파일 빠짐, `sha256sum -c` 재검증 불일치(`CHECK.txt` 보존) 중 하나면 거절.
- 완료 표지 `LEDGER_COMPLETE` 쓰기 실패면 거절.
- 거절은 모두 `LEDGER_REFUSED`·종료 4, "구판 복구 불가" 문구, **"이후 종전 U되돌림" 문구 없음**. 불완전 사본은 지우지 않고 `LEDGER_FAILED`(사유) 표지와 함께 남긴다.
- 기존 경로가 있으면 1판처럼 덮어쓰지 않고 거절하며, 그 폴더에는 아무것도 쓰지 않는다. 상위 폴더가 없거나 폴더 생성이 실패해도 거절(`mkdir -p` 대신 `mkdir`).

## 가짜 명령 검사 (`probe.sh`, DB·Docker·네트워크 없음)

가짜 psql(집계·COPY 결과를 모드로 정함)과 가짜 sha256sum(실패·빈 성공·틀린 값)만 쓴다. 같은 검사기로 1판과 2판을 돌렸다.

| 사례 | 기대 | 1판(`runs/probe-v1-original`) | 2판(`runs/probe-v2`) |
|---|---|---|---|
| normal | POST_CREATION_OK 0·장부 완결 | 판정 같음(완료 표지 개념 없음) | 통과 |
| agg_fail(집계 rc 2) | STOP_UNKNOWN 3 | 통과 | 통과 |
| csv_fail(COPY rc 1) | LEDGER_REFUSED 4 | 판정 같음 | 통과 |
| csv_stderr | LEDGER_REFUSED 4 | 판정 같음 | 통과 |
| **csv_empty** | LEDGER_REFUSED 4 | **POST_CREATION_OK 0** | 통과 |
| write_fail(상위가 파일) | LEDGER_REFUSED 4 | 통과 | 통과 |
| **sha_fail(rc 17)** | LEDGER_REFUSED 4 | **POST_CREATION_OK 0**(총괄 재현과 같음) | 통과 |
| **sha_empty_ok(빈 목록, rc 0)** | LEDGER_REFUSED 4 | **POST_CREATION_OK 0** | 통과 |
| **sha_mismatch(틀린 값)** | LEDGER_REFUSED 4 | **POST_CREATION_OK 0** | 통과 |
| existing_path | LEDGER_REFUSED 4·기존 폴더 그대로 | 통과 | 통과 |

- 1판 3/10(판정이 틀린 4건은 굵게, 나머지 3건은 판정은 같고 실패 표지 개념이 없어 검사기 기준 불일치), 2판 **10/10**.
- 콘솔 출력 원문은 이 README 를 쓰기 전 터미널에서만 보았고 파일로 남기지 않았다(각 사례 `*.txt` 에 판정·종료 코드·장부 목록은 남아 있다). 1판 검사는 두 번 돌렸다 — 첫 실행은 출력을 버렸고(`/d/tmp/v153-runs/h/v2probe/v1`, 근거에 넣지 않음) 두 번째(`v1b`)를 `probe-v1-original` 로 넣었다.

## 실제 로컬 PG 예행 (`rehearse.sh` 2판, `runs/pg-rehearse-v2`)

1판 예행과 같은 10사례·같은 기대를 2판 관문으로 한 번 돌렸다: **10/10**, 관문 전후 표 내용 해시 같음(쓰기 없음). s2 장부에 `SHA256SUMS`(4줄)·`CHECK.txt`·`LEDGER_COMPLETE` 가 생겼다. s10(기존 폴더)은 그 폴더에 아무것도 쓰지 않았다.

## 대역으로 주장하지 않는 것

실제 서버 psql 경로·권한, 실제 세션 정지 API, 첫 유효 시세 정산 시간, 운영 데이터의 상태 분포, 실제 디스크 가득 참.
