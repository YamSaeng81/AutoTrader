# 서버 배치 batch-r2 — 수집 실패 전달 보완·4경계·실제 형식 확인 (총괄 v1.251)

2026-11-25 KST, Claude Code 단일 실행 세션. **배치는 등록하거나 시작하지 않았다**(서버 `runs/` 없음). 로컬 `cffcfad9`·`f05e856c`는 그대로다. 기존 83건 합성 검증과 제품 회귀·PG·jar는 다시 돌리지 않았다.

## 1. 보완 (batch-r2 새 사본. 기존 `batch/`·`src/`와 그 해시는 보존한다)

| 파일 | 변경 |
|---|---|
| [pins.sh](batch-r2/pins.sh) **신규** | collect_pins를 batch.sh에서 떼어 낸 파일이다. 필수 명령(checkout 이동·git rev-parse/status·compose ps·docker inspect·INSTALLED_SHA·crontab -l·해시 대상 읽기·SID_LEDGER_SHA·과거 장부 검사 실행)마다 **종료값을 직접 확인한다.** 실패하면 단계별 고유값 11~20을 돌려주고 echo로 덮지 않는다. 로그는 `gzip -t`(22) → 한 번 압축 해제(23) → 고정 키 9개를 `grep -c`로 센다. 일치가 없어 grep이 1을 돌려주면 0건으로 본다. grep이 2 이상이거나 숫자가 아니면 24로 실패한다. 압축 해제한 로그는 임시 파일로만 쓰고 지운다. 과거 장부 파일의 '불일치'는 수집 실패가 아니라 값 `past_ledger 1`로 남기고, 판정기가 FAIL로 본다 |
| [lib.sh](batch-r2/lib.sh) ([diff](diff/lib.sh-vs-r1.diff)) | n03_stage는 `collect-rc.txt`(`collect_pins rc=N stderr=SB`)를 남긴다. **rc가 0이 아니거나 stderr가 0바이트가 아니면 판정기를 실행하지 않는다.** 이때 `N03 BLOCK 수집 실패` 사유와 pins.txt·.err 원문·체크섬을 보존하고 1을 돌려주며, 결과는 STOP-N03·U0 호출 0이다. 다른 흐름과 기록 계약은 바뀌지 않았다 |
| [n03check.py](batch-r2/n03check.py) ([diff](diff/n03check.py-vs-r1.diff)) | 판정기도 직접 거절한다. `collect-rc.txt`가 정확히 `collect_pins rc=0 stderr=0B`이고 `pins.txt.err`가 0바이트여야 하며, pins 키 중복도 FAIL이다. 항목은 43개에서 45개로 늘었다. 경고 허용 유형은 바꾸지 않았다 |
| [batch.sh](batch-r2/batch.sh) ([diff](diff/batch.sh-vs-r1.diff)) | `BDIR=$BASE/batch-r2`로 바꾸고, 내장 collect_pins를 지운 뒤 `. pins.sh`를 불러온다. 시각 상수·11블록 해시·출처는 그대로다 |
| [launch.sh](batch-r2/launch.sh) ([diff](diff/launch.sh-vs-r1.diff)) | 경로만 batch-r2로 바꿨다(BATCH.sha256 검사, 실행 파일) |
| [edge4.sh](batch-r2/edge4.sh)·[pins-read.sh](batch-r2/pins-read.sh)·[pinscheck.py](batch-r2/pinscheck.py) **신규** | 4경계 검증, 실제 형식 읽기 1회, 형식 검사 |
| selftest.sh **제외** | r1의 83건용이다. fixture에 collect-rc가 없어 r2와 맞지 않고, 다시 돌리지도 않으므로 r2에서 뺐다. r1 원문은 [이전 폴더](../server-batch-prep-20261125/batch/selftest.sh)에 보존돼 있다 |

새 [BATCH.sha256](batch-r2/BATCH.sha256)은 f05d47a3, 15파일이다. 서버 `~/work-batches/d81b-20261127-exp1/batch-r2/`와 바이트가 같다. 공통 BASE·runs·전역 잠금·`src/`(MANIFEST 67e8ed1f, 452ac384 11블록)는 그대로 쓴다. 27일 시각, U0/U1 조건, 경고 허용 4유형, 기록 계약은 바꾸지 않았다.

## 2. 직접 영향 4경계 (서버 10/10)

실제 lib.sh·pins.sh(collect_pins·pins_logs)·n03check.py를 쓰고, 합성 함수로 바꾼 것은 `pins_core`(git/docker/해시 읽기)뿐이다. 이 함수는 11-24 fixture 값을 낸다. 블록 대역은 호출만 기록한다. 서버 결과는 [call5-out](server/call5-out.txt), WSL 사전 실행 결과는 [pre-wsl](pre-wsl/edge4-result.txt)(10/10)에 있다.

| 경계 | 결과 |
|---|---|
| 정상 수집·빈 stderr·정상 N03 | `COMPLETE-U1-READY`, 호출 U0 U1, collect rc0 0B, 판정기 45/45 |
| pins 내용은 정상(fixture와 같은 내용 확인), 수집 rc 24 | `STOP-N03`, 호출 0, 판정기 미실행, 사유 보존 |
| 수집 rc0, stderr 38B | `STOP-N03`, 호출 0, 판정기 미실행 |
| log.gz가 gzip이 아님 | rc22, `STOP-N03`, 호출 0, logcount 줄 0(0건으로 바꾸지 않음) |

r1의 총괄 반례(pins.txt에 실패 줄을 넣고 .err가 비어 있지 않은 상태에서 rc0 PASS)는 r2에서 두 군데서 막힌다. 호출자 단계(경계 2·3)와 판정기의 '수집 rc0·stderr 0B' 항목이다.

## 3. 실제 서버 형식 확인 1회 (20261125-1000 고정)

[pins-read.sh](batch-r2/pins-read.sh)는 batch.sh를 불러오지 않고 pins.sh만 불러온다. DCK는 `~/daily-check`, TAG는 `20261125-1000`이다. 대상 log.gz는 214,976B이고 11-25 10:00:04 KST에 생성됐다. 결과는 서버 `pins-format-20261125-1000/`(SHA256SUMS d4d9e81a)에 있다.

- **collect_pins rc=0, stderr 0B.** 임시 로그와 cron 사본을 지운 것을 확인했다.
- 형식 **34/34**: 필수 필드 20개가 각 1회씩 있고, 알 수 없는 필드는 0개이며, 타입이 모두 맞다(40/64 hex·정수·docker 4필드).
- 설치 핀 11개가 전부 기대값과 같다: HEAD 0e1d2a02·변경 0, backend 2c5d8427 재시작0 running, frontend 9d7f123a 재시작0 running, INSTALLED_SHA c669ccdc, crontab f2dce280, daily_check.sh ff0a5d7d, .sql 653b22ba, SID_LEDGER 8bfe8a9f, sid_ledger.sql 02a85850, 과거 장부 0.
- 로그 집계(보고값): 9개 키 모두 0이다.
- 11-27용 n03check 전체는 오늘 자료로 돌리지 않았다.

## 4. 실제 서버 호출 (이번 1회)

| # | 시각(UTC) | 범위 | rc |
|---|---|---|---|
| 5 | 04:59:09~04:59:21 | 새 경로 없음 확인 → batch-r2 tar 전송 → BATCH 15/15 → 기존 batch/·src/ 보존 해시 → edge4 → pins-read 1회 | 0 (stderr 0B) |

스크립트는 [call5](server/call5-r2-transfer-edge4-pins.sh)(ffa0e932…)이고, 전송 tar는 476813ce…다. 서버에 쓴 것은 `batch-r2/`, `edge4-r2/`, `pins-format-20261125-1000/`뿐이다. runs, `~/maint-d81b-20261127-exp1`, 운영 Git, compose, cron은 변경이 없다. 호출 뒤 HEAD 0e1d2a02 변경 0, crontab f2dce280, 부하 0.29다. v1.250 때의 호출 1~4와 합치면 이 작업의 누적 서버 호출은 5회다.

## 5. 회수·전환 (v1.251 §4·§5 반영)

- **19:31까지 기다리는 회수 계획은 철회한다.** 세션이 살아 있으면 `final.txt`가 생긴 것을 확인하는 즉시 out-U0/U1·steps·n03을 회수해 제출하고, U2 사용자 확인을 요청한다. 세션이 꺼져 있었다면 다음 접속 때 바로 회수한다.
- 상태를 볼 때는 heartbeat와 작업 PID의 생존·정체성(job.txt의 pid·args)을 함께 본다. U0/U1 실행 중에는 heartbeat 간격이 길어질 수 있으므로 heartbeat가 오래됐다는 것만으로 죽었다고 단정하지 않는다. 프로세스가 없으면 자동으로 다시 시작하지 않고, 실행된 단계·INCOMPLETE·기록을 먼저 확인한다.
- 시작 승인을 받으면 순서는 이렇다. 로컬 `cffcfad9` 취소 → CronList에서 없음 확인 → `bash ~/work-batches/d81b-20261127-exp1/batch-r2/launch.sh` 1회. 시작이 실패하면 로컬 예약을 임의로 복원하지 않고 보고한다. `f05e856c`는 기존 원문 회수로 남긴다.

다음 작업은 v1.249 §3 실주문 초안 v2이며 아직 미착수다. 서버 배치 manifest와는 섞지 않는다.

## 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더 전체, 자신 제외). `* -text`로 바이트를 보존한다.
