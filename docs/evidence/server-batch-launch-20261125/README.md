# 서버 유한 배치 batch-r2 시작 1회 (총괄 v1.252 §2)

2026-11-25 KST, Claude Code 단일 실행 세션. **서버 배치를 1회 시작했고, 지금은 11-27 10:14 KST N03 단계를 기다리고 있다.** U0/U1은 아직 실행되지 않았다. 운영 컨테이너도 교체되지 않았다.

## 1. 로컬 예약 정리 (시작 전)

| 확인 | 결과 |
|---|---|
| CronList(시작 전) | `f05e856c` 11-27 10:14 N03 회수, `cffcfad9` 11-27 17:36 U0→U1 |
| `CronDelete cffcfad9` | "Cancelled job cffcfad9." |
| CronList(취소 뒤) | `f05e856c`만 남음. N03 기존 원문 회수용이며 U0/U1을 부르지 않는다. 바꾸지 않았다 |
| 다른 로컬 세션 | ListAgents 결과 crypto-auto-trader-09 1개. 문의에 "CronList 비어 있음, U0/U1/U2·run.sh 예약 없음, SSH·운영 호출 없음"이라고 회신했다 |
| Windows 작업 스케줄러 | d81·u0·u1·crypto·claude·batch 이름의 관련 작업 0 (OS 유지보수 작업만 있음) |

## 2. 시작용 접속 (실제 ssh 1회, 이 작업 누적 서버 호출 6회째)

- 스크립트: [call6-launch.sh](call6-launch.sh) (sha256 3c35e021…). 실행 직전 `command -v ssh` = `/usr/bin/ssh`, 별칭 `cat-prod`. 실행 결과: [출력](call6-out.txt), [stderr](call6-stderr.txt), [PC 시각](call6-pc-time.txt). ssh rc0.
- 서버 시각: 05:14:48Z(14:14:48 KST)이며 T_N03 1795742040보다 앞이다.
- 고정 해시 7개가 서버에서 모두 일치했다. BATCH.sha256 f05d47a3, launch 5f1ad35d, batch 4dd355d9, lib 3255301b, pins 7f0117aa, n03check d0047ac9, src MANIFEST 67e8ed1f. 목록 검사도 BATCH 15/15, src 15/15 통과했다.
- 충돌 확인: runs/ 없음, batch.lock 없음, `~/maint-d81b-20261127-exp1` 없음, batch.sh 프로세스 0개였다.
- **`launch.sh` 1회 실행, rc0.** 재호출하지 않았다.
- 배치 원문 커밋: 72901be37f1d2e4cf0d601c4924824b923026a1c. 실제 U 블록 출처: 452ac38430dff30300048026e9478e555e015b8c.

## 3. 등록 상태 (시작 8초 뒤 관측, 05:14:56Z)

| 항목 | 값 |
|---|---|
| 작업 ID | **d81b-u0u1-20261125T051448Z-2283028** |
| PID | **2283036** (pgrep으로 같은 args 확인) |
| 세션 분리 | PPID 1, PGID=SID=2283036, TTY 없음(`?`), STAT Ss. SigIgn 0x7로 SIGHUP을 무시한다. 자식은 `sleep 300` |
| job.txt | pid/ppid/sid, start 14:14:48 KST, T_N03 11-27 10:14, T_START 17:35, T_U0 17:36, END_EXCL 19:31, U1AGE 1740, source 452ac384, M 경로 |
| state.txt | `대기 N03 목표=1795742040(2026-11-27T10:14:00+09:00) 기한=1795775460` |
| steps.txt | 시작 → 검증 통과 — N03 대기 → 대기 N03 |
| 로그 | `~/work-batches/d81b-20261127-exp1/logs/d81b-u0u1-20261125T051448Z-2283028.txt` (`[배치 시작]` 1줄) |
| final/INCOMPLETE | 없음 |
| runs/ | 항목 1개 |
| 운영 | HEAD 0e1d2a02, 변경 0, crontab f2dce280. 운영 Git, compose, cron은 변경하지 않았다 |

시작기의 rc0만 보고 판단하지 않았다. PID 생존, 작업 정체성, 세션 분리, state를 함께 확인했다.

**stderr 241B는 배치와 무관하다.** 시작 뒤 확인 줄의 `docker compose ps -q backend`에서 서비스 이름이 틀려 `no such service: backend`와 compose `version` 경고가 나왔다. 이 때문에 backend 이미지 줄은 빈 값이다. 이 값을 다시 읽으려고 추가로 접속하지 않았다. 컨테이너 상태는 배치의 N03 핀 수집이 27일에 기록한다.

## 4. 남은 범위

- 서버 배치는 PC나 SSH 연결과 상관없이 계속 돈다. **서버가 재부팅되거나 프로세스가 끝나면 자동으로 다시 시작하지 않는다.**
- 27일 순서는 10:14 N03 보존·판정 → 17:36 U0(창 17:35~19:30) → 조건을 충족하면 U1이다. U2와 그 뒤 단계는 배치에 들어 있지 않고 사용자의 '재기동 진행' 확인 뒤에만 한다.
- 회수: 세션이 살아 있으면 final.txt를 확인하는 즉시 회수한다. 꺼져 있었으면 다음 접속 때 바로 회수한다. 이틀 대기 중에 짧은 간격으로 읽지 않는다.

## 체크섬

[SHA256SUMS](SHA256SUMS) (이 폴더 전체, 자신 제외).
