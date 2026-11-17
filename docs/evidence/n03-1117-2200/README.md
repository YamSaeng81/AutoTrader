# N03 11-17 22:00 보고 — 예약 미실행, 11-18 08:18 늦은 읽기

## 예약 실행 결과: **실행되지 않음**

- 11-17 10:51 KST에 22:12:30 KST 실행을 예약했다([sched/scheduled.txt](sched/scheduled.txt), 개발 PC 대기 프로세스). 13:23에는 대기 중인 것을 확인했다.
- 개발 PC가 **11-17 17:18:06 KST에 절전**됐다. 시작 메뉴에서 사용자 계정의 전원 요청(이벤트 1074·42)이 있었다. **11-18 08:05:33 KST**에 복귀했다. 08:17:46 확인 때 대기 프로세스는 없었고 22:12 실행 흔적(run-time.txt)도 없었다([sched/not-run.txt](sched/not-run.txt)).
- 따라서 11-17 17:18~11-18 08:18 사이는 **미관측**이다. 세션 대기나 예약 프로세스 생존을 감시로 쓰지 않는다.

## 늦은 읽기 — 같은 고정 명령 [read.sh](read.sh)·[sched/n03run.sh](sched/n03run.sh)

| 항목 | 값 |
|---|---|
| 시각 | 개발 PC 23:18:19~23:18:25Z, 서버 **11-18 08:18:23 KST**([late-run-time.txt](late-run-time.txt), [run-time.txt](run-time.txt): Git Bash의 `TZ=Asia/Seoul`은 UTC로 찍힘). read rc=0 |
| 보고 파일 | 22:00:02~03 KST 생성. [last_send](last_send) **22:00:03 보고 전송 성공 · 🟢 점검 항목 정상**. 해시 [server-files.txt](server-files.txt)·[local-sha256.txt](local-sha256.txt) |
| 보고 값 | 운영 0e1d2a02 · 앱 running 재시작 0 · Flyway 96 · SID203 밖 실자금 보유/미결/정리 대기 0 · 66세션 RUNNING 63+지정 STOPPED 3 · SID203 CREATED 잔액 20200.00 주문 0 · B1 true · ERROR 전체 0 · 66세션 적재 뒤처짐 12회(전부 KRW-BLAST 거래지원 종료) |
| 설치 상태 | HEAD 0e1d2a02 · backend 2c5d8427 · frontend 9d7f123a(재시작 0) · INSTALLED_SHA c669ccdc · [install_state.txt](install_state.txt)·[frontend-image.txt](frontend-image.txt) |
| 로그 관측 | ERROR 줄 0 · CryptoPanic HTTP 403 24(수집 미완료 24, 완료 84) · Connection reset 0 · Hikari 경고 0 · N17/N19/N20 0 · [observations.txt](observations.txt)·[supp](observations-supp.txt) |
| 토큰 형식 검사 | 7개 파일 모두 0 [token-check.txt](token-check.txt) |

**판단**: 새 P0는 없다. KRW-BLAST 뒤처짐과 CryptoPanic 403은 기존 패턴이다. DB 값은 22:00 보고 값이며 직접 조회하지 않았다. 22:00 이후 상태는 08:18 설치 상태 읽기 외에는 미관측이다.
