# D81B-EXP1 U0→U1 서버 배치 준비·검증 (총괄 v1.250 §1·§2)

2026-11-25 KST, Claude Code 단일 실행 세션. **서버 준비와 합성 검증만 했다. 배치를 등록하거나 시작하지 않았다**(`runs/` 없음). 실제 배포 블록·운영 체크아웃·compose·cron·DB·계좌·`~/maint-d81b-20261127-exp1`은 건드리지 않았다. 로컬 예약 `f05e856c`·`cffcfad9`는 그대로 있다(11-25 13:2x KST CronList에서 확인). 새 배치가 수용되기 전에는 취소하지 않는다.

## 1. 실제 서버 호출 (4회, 모두 `ssh -o BatchMode=yes cat-prod`, 실제 `/usr/bin/ssh`)

| # | 시각(UTC, PC 기준) | 범위 | rc | 원문 |
|---|---|---|---|---|
| 1 | 04:26:38~04:26:40 | 최소 환경 읽기 1회(읽기만) | 0 | [env-read.sh](env/env-read.sh) b0b5cf03 → [env-read.txt](env/env-read.txt), stderr 0B |
| 2 | 04:41:07~04:42:49 | 전용 폴더 생성(없음 확인 후) → tar 전송 → 해시 → 합성 검증 → 분리 실행 사례 시작 | 0 | [call2](server/call2-transfer-verify.sh) f2f5bc11 → [out](server/call2-out.txt) |
| 3 | 04:43:03~04:43:04 | 분리 실행 사례 확인 — **제가 너무 일찍 보냈다**(진행 중이라 FAIL 2) | 0 | [call3](server/call3-detach-check.sh) 75d5c039 → [out](server/call3-out.txt) |
| 4 | 04:44:24~04:44:25 | 완료 뒤 같은 확인 1회 더 | 0 | [call4](server/call4-detach-check.sh) 20818e4b → [out](server/call4-out.txt), stderr 0B |

서버에 쓴 파일은 `~/work-batches/d81b-20261127-exp1/` 안에만 있다(src·batch·selftest·selftest.sha256(호출 3, 진행 중 시점)·selftest-final.sha256(호출 4) a7e5c080, 832파일). sudo·설치·재기동·DB·외부 API 호출은 0회다.

## 2. 서버 환경 (호출 1)

- 시각: 서버 UTC 04:26:40 = PC 04:26:38~40. `date -u +32400`과 Python 고정 +09:00 결과가 같다(13:26:40). 시스템 TZ는 Asia/Seoul이지만 배치는 이 값을 쓰지 않는다.
- Ubuntu 22.04.5 · 32코어 · 메모리 31.9G(가용 25.4G) · 홈 가용 541G(39%) · 부하 0.26(호출 2 직후 1.06).
- 도구: bash 5.1.16 · python3 3.10.12 · tar · sha256sum · nohup · setsid · flock(util-linux 2.37.2) · timeout 있음. **`at` 없음.** `Linger=no`이고 logind `KillUserProcesses` 설정 줄이 없다(Ubuntu 기본값은 no). 그래서 setsid/nohup 사용자 프로세스를 쓴다. 호출 2→4에서 접속이 끊긴 뒤에도 프로세스가 끝까지 실행됐다.
- 운영 고정값 일치: HEAD 0e1d2a02(작업 트리 변경 0) · backend 2c5d8427 running 재시작0 · frontend 9d7f123a running 재시작0 · INSTALLED_SHA c669ccdc · crontab f2dce280. `~/work-batches`·`~/maint-d81b-20261127-exp1`는 없었다.

## 3. 전송한 것 = 고정 출처와 새 배치 원문 분리

`src/`(출처, [MANIFEST](src-MANIFEST.sha256) 67e8ed1f): 452ac384 11블록 + blocks.json 68d6c894(로컬 실행 폴더 사본, v1.249 §1에서 총괄이 대조 일치) + 참고용 extract.py c5d7a388·run.sh 06b913a8(`git show 452ac384`). 블록 해시는 run.sh 고정값과 같다. 서버 `sha256sum -c`에서 15/15 OK였다.

`batch/`(새 원문, [BATCH.sha256](batch/BATCH.sha256) 0ccec8d4, 서버 12/12 OK). 이 폴더의 파일은 서버 사본과 바이트가 같다.

| 파일 | sha256 앞 8 | 역할 |
|---|---|---|
| [batch.sh](batch/batch.sh) | d905a7c8 | 실제 배치. 상수(시각 epoch·11블록 해시·출처)·실제 함수(date/sleep/`bash -c`·읽기 전용 pins)를 정하고 lib.sh를 불러온다. 테스트 시각/경로 옵션은 없다 |
| [lib.sh](batch/lib.sh) | da3964a3 | 흐름·기록 계약(잠금·1회·대기·N03·run_block·final) |
| [n03check.py](batch/n03check.py) | 0d64cbed | N03 보고·장부·핀 판정(43항목) |
| [kst.py](batch/kst.py) | 175ab414 | 고정 UTC+09:00 변환(v1.249 §3-1 규칙, TZ/zoneinfo 미사용) |
| [launch.sh](batch/launch.sh) | 71ab3248 | 수용 뒤 1회 시작기(`setsid nohup`, stdin 닫음, 출력 `logs/<작업>.txt`) |
| [selftest.sh](batch/selftest.sh) | 4718f573 | 합성 검증 |
| [fixture-n03-20261124/](batch/fixture-n03-20261124/SOURCE.txt) | — | 11-24 보존 원문 사본(보고 73e94385·장부 b69761ca) + 같은 회차 관측값으로 만든 pins |

전송 묶음 tar sha256 = 70678796…(PC 임시 폴더, 보존 안 함 — 내용은 위 두 해시 목록으로 고정).

## 4. 배치 동작 (batch.sh → lib.sh `batch_main`)

1. `flock -n` 전역 잠금(`batch.lock`, fd 9는 블록 자식에게 넘기지 않음)을 잡는다. `runs/`가 비어 있어야 하고 `runs/<작업ID>`는 mkdir로 원자적으로 만든다. 하나라도 어긋나면 아무것도 만들지 않고 3으로 끝난다. **1회 작업**이라 기록이 남으면 재실행을 거절한다.
2. job.txt(작업ID·pid·sid·시작·상수·출처)와 state.txt(heartbeat, ≤300초마다)·steps.txt를 남긴다. 배치 파일 해시(BATCH.sha256)와 블록 11개 해시(run.sh와 같은 조건 + blocks.json source 전체=452ac384)를 확인한다.
3. **11-27 10:14 KST(1795742040)까지 유한 대기**(300초 단위, 1000회 상한, 기한 19:31:00 미만). 서버 `date +%s`와 고정 epoch만 비교한다. 상수 4개는 selftest에서 kst.py로 다시 계산해 일치를 확인했다.
4. **N03**: `~/daily-check/20261127-1000.txt`(+msg·raw·log.gz·last_send)와 `~/sid203-ledger/20261127.txt`를 `runs/<작업>/n03/`에 복사한다. 원본·사본 sha256이 같아야 한다. 같은 시점의 설치 핀과 로그 집계를 읽고 n03check.py로 판정한다. 보고를 만들거나 다시 보내지 않으며 cron도 변경하지 않는다. PASS가 아니면 `STOP-N03`이고 블록은 실행하지 않는다.
5. 17:36(1795768560)까지 대기한 뒤, 직전에 창 17:35~19:30(끝 분 포함)과 배치 해시를 다시 검사한다. 이어 **U0 1회**: `bash -c "$(cat F G U변수 U0)"`. run.sh의 원격 문자열과 같고 ssh는 없다. 기록 계약도 run.sh와 같다(INCOMPLETE → 시작 줄 → 출력 → 끝 줄 → exit-codes → 표지 삭제).
6. U0가 rc0이고 `ok-U0`가 있으면 U1 조건을 본다. ok-U0 뒤 **1740초 이하**(블록 한도 1800초보다 60초 앞), 19:31 전이어야 하며, :14~:24이면 :25까지 기다린다(최대 3회). 조건을 만족하면 **U1 1회**. rc0이고 `ok-U1`이 있을 때만 `COMPLETE-U1-READY`로 끝난다. **U2·되돌림·재시도는 배치에 없다.**
7. `final.txt`는 마지막 필수 기록이고 덮어쓰지 않는다(`mv -n`). 블록 뒤에 기록이 실패하면 `RECORD-FAIL-U0/U1`이다. INCOMPLETE를 남기고, 블록이 비영이면 그 값을 유지한다. 다음 블록은 실행하지 않는다.

### N03 판정 항목 (기존 N03 판정 계약 → 원문 필드)

| 묶음 | 통과 조건 |
|---|---|
| 생성 시각 | 보고 mtime 11-27 10:00~10:10, 장부 09:30~09:35, last_send ≥ 10:00 |
| 보고 머리 | 1행 `[정기 점검] 🟢 점검 항목 정상`. 2행 실행 11-27 10:00·구간 11-26 22:00·운영 0e1d2a02·점검 c669ccdc6e24. 그 뒤 경고 줄은 과거 회차에서 매매·정산 이상이 아니라고 본 4유형(외부 통신 실패·66세션 KRW-BLAST 적재 뒤처짐·KRW-BLAST 보충 수집·검증 코인 KRW-BLAST 캔들)만 허용한다. 다른 🟡/🔴/⚪이 있으면 BLOCK(사람 확인) |
| 본문 | 앱 running·재시작0·기동 11-16T05:16:14·이미지 2c5d8427 · 재생성 공백 없음 · DB 줄 전문(Flyway 96·203 밖 실자금 0·미결 0·RUNNING 63·STOPPED 3/3·203 밖 매수 0) · SID 203 줄 전문(CREATED·20200.00·보유0·주문0·정산 이상0) · B1 true·B3 20/20 · ERROR 전체=통신 실패·미분류0 · 본문 🔴는 KRW-BLAST 적재 뒤처짐(머리와 같은 횟수)만 · 전송 성공 줄 = last_send |
| 장부 | L0 203 CREATED 20200(init_ok t) · L1/L2/L3 0행 · L4 balance_diff 0.00·raw_diff 0.00·미확인 0·순체결 0·보유 0 · L5 중단 기록 0·CREATED · ROLLBACK·끝 |
| 설치 핀(같은 시점 읽기) | HEAD 0e1d2a02·변경0, backend/frontend Image·StartedAt·재시작0·running 전문, INSTALLED_SHA, crontab, daily_check.sh/.sql, SID_LEDGER_SHA, sid_ledger.sql, 과거 장부 파일(maint-n17 기준) 그대로 |
| 로그 집계(그 회차 log.gz) | Hikari 누수·획득 실패, N17·N19·N20 3종, DOCTYPE, `<html` 모두 0 |

11-24 실제 원문으로는 43/43 PASS가 나온다. 변조 14건(머리 🔴·잔액·새 🔴 머리·장부 L2·HEAD·last_send·묵은 보고·pins 없음·미분류·재시작·Hikari·balance_diff·BLAST 횟수·과거 장부)은 전부 거절했다.

## 5. 합성 검증 결과 (서버 83/83, 분리 2/2)

부작용 없는 함수·파일만 썼다. 블록 대역은 받은 문자열의 `#BLOCK=` 표지만 기록하고, 가짜 시계는 파일이다. ssh·docker·git·PG·PATH 치환은 쓰지 않았다. 서버 결과는 [call3-out.txt](server/call3-out.txt)의 result r1 부분, 분리 실행은 [call4-out.txt](server/call4-out.txt)에 있다.

- **시각 27건:** TZ=UTC/Asia/Seoul/America/New_York/Etc/GMT-14/없음에서 같은 표본은 같은 결과였다(날짜 넘김 23:59:59→00:00). 창 시작 직전·정각, 끝 분 마지막 초·직후를 확인했다. 잘못된 날짜(02-30)·24:00·23:60·형식 결함 7건은 rc2로 거절했고 출력이 없었다. now가 서버 epoch와 ±2초 안이고, batch.sh 상수 4개가 KST 의도와 일치한다.
- **N03 16건:** 위 §4 표와 같다.
- **흐름 33건:** 정상 완료(블록 문자열 F·G·U변수·블록 순서, exit-codes 2줄, final이 마지막, N03 사본=원본, 체크섬, 대기 기록). 중복 잠금·1회 작업 거절(작업 폴더 0). 기한 만료(N03 전·N03 뒤 U0 전·U0 뒤 U1 전). N03 BLOCK·보고 없음. 블록 변조·배치 해시 불일치. U0 실패·ok-U0 없음. :14 → 18:25:00 U1. ok-U0 1741초. U0 뒤 기록 실패(INCOMPLETE 남김·블록 rc 원문 보존)·비영+기록 실패(7 유지)·U1 뒤 기록 실패. 기록 실패 뒤 재실행 거절. U1 실패. 시각 읽기 실패. 시작 상태 기록 실패. 모든 사례에서 U0·U1 밖 블록 호출 0.
- **실제 출처 7건:** 실제 11블록이 batch.sh 고정값과 일치, 1바이트 변조 거절, 다른 출처 blocks.json 거절, BATCH/MANIFEST 일치, 쉘·Python 구문.
- **접속과 독립된 보존:** 호출 2에서 `setsid nohup`으로 띄운 사례(pid 2263663, ppid 1, 자체 sid)는 호출 2 접속이 끝난(04:42:49Z) 뒤 04:43:29Z에 `COMPLETE-U1-READY`로 끝났다. 결과는 서버에 남았고, 호출 4에서 회수했으며 남은 프로세스는 0이다.

**검증 중 결함(서버 전송 전 WSL 선행 실행에서 발견, 수정 후 전송):** 시험 스크립트 결함 3건(항목 수 기대 41→실제 43, 장부 변조 sed 식, 같은 `local` 줄에서 `$name`을 먼저 참조)이 있었다. **배치 결함 1건**도 있었다. 블록이 비영으로 끝나고 기록도 실패하면 최종 표지가 `STOP-U0`로 나와 기록 실패를 드러내지 않았다. `RB_WR`로 구분해 `RECORD-FAIL-*`·원래 값 유지로 고쳤고 사례 2건을 추가했다. WSL은 `wsl` 호출이 끝날 때 분리 프로세스를 정리해 접속 독립성의 대표가 아니다. 그래서 그 확인은 서버 결과만 쓴다.

## 6. 실행 주체 전환안 (총괄 수용 뒤에만 진행)

1. 총괄이 이 원문(BATCH 0ccec8d4·MANIFEST 67e8ed1f)을 수용한다.
2. **먼저 로컬 `cffcfad9`(U0→U1)를 CronDelete로 취소하고 CronList로 없음을 확인한다.** `f05e856c`(N03 로컬 회수)는 읽기 전용 보존 회수로 남길 수 있다. 서버 보고를 다시 만들지 않고, 그 결과가 배치 진입 근거도 아니다. 두 예약 모두 이 세션이 끝나면 사라진다.
3. ssh 1회로 `bash ~/work-batches/d81b-20261127-exp1/batch/launch.sh`를 실행하고 작업 ID·pid·state.txt를 기록한다. 이후 11-27 10:14 전까지는 state.txt heartbeat(≤300초)로만 생존을 확인한다.
4. 11-27 19:31 이후(또는 다음 접속 때) `runs/<작업>/final.txt`·steps·out-U0/U1·n03/verdict를 회수해 제출한다. `COMPLETE-U1-READY`이면 사용자 '재기동 진행' 확인을 요청한다(U2는 배치 밖).

## 7. 남은 위험 — 총괄 판단 필요

- **collect_pins 실제 명령은 서버에서 아직 실행하지 않았다.** 필드 형식은 env-read·N03 read.sh와 같은 명령이다. daily_check.sh/.sql·SID_LEDGER·과거 장부 검사·log.gz 집계는 오늘 읽지 않았다. 형식 오류가 있으면 11-27에 `STOP-N03`으로 멈춘다(안전 쪽이지만 창을 잃는다). 승인되면 launch 직전에 읽기 전용 1회(현재 회차 20261125-2200 대상, n03check는 돌리지 않고 pins 필드만 대조)를 제안한다.
- **약 2일 대기 중 서버 재부팅·프로세스 종료**가 생기면 자동 재시작하지 않는다(설계상 무제한 반복·재시도 없음). 로컬 `cffcfad9`를 취소한 뒤라 대체 실행 주체가 없다. 11-27 오전 접속 때 state.txt가 300초보다 오래됐으면 사람이 판단한다.
- N03 판정은 기존 사람 판단보다 엄격하다. 허용 4유형 밖의 🟡(예: 텔레그램 전송 실패)도 BLOCK이다. 이것은 기대를 약화하지 않으려고 고른 것이다.
- U1 블록은 19:30 전에 **시작**하지만 끝나는 시각은 블록 소요에 따른다. 30분 한도와 :14~:24 제외는 블록이 스스로도 다시 검사한다.

v1.249 §3(실주문 초안 v2 시각·gate·보유 표시)은 **아직 하지 않았다.** kst.py가 그 공통 시각 규칙의 첫 구현이다.

## 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더 전체, 자신 제외). `.gitattributes` `* -text`로 바이트를 보존한다.
