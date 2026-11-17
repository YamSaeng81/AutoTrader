# 아침 통신 오류 9줄·ssh 끊김 — 읽기 전용 조사 (총괄 v1.178 §3, 예약 실행 11-17 10:17 KST)

기존 backend 로그(사건 구간으로 제한)와 서버 SSH 서비스 로그만 읽었다. 능동 부하·API 시험·설정 변경·재시작·서버 쓰기 없음. 출력의 IPv4 는 앞 두 자리만 남겼다(전체 IPv4 0건 확인). 토큰 패턴 0([token-check.txt](token-check.txt)). 시각: app 로그는 UTC, 사건 표기는 KST.

## 실행 여부와 실제 시각

| 읽기 | 예약/방식 | 실제 시각 | 종료값 |
|---|---|---|---|
| 본 조사 [inv.sh](inv.sh) `2f8c233b…` | 백그라운드 예약 `wait_then.sh 01:17:00`([sched/](sched/): [wait_then.sh](sched/wait_then.sh), [ssh-shim2.sh](sched/ssh-shim2.sh), [건 명령](sched/scheduled-command.txt)) | 01:17:03Z~01:17:14Z = **10:17:03~10:17:14 KST**([run-time.txt](run-time.txt); `local` 줄은 이 셸에서 UTC 로 찍힘) | **rc=1**([inv-stderr.txt](inv-stderr.txt)) — 아래 |
| 보충 [supp.sh](supp.sh) `6b2c4fce…` | 결과를 본 뒤 직접 실행(같은 SSH 로그·같은 구간, 성공 로그인 종류·MaxStartups 줄) | 01:17:56Z~01:18:03Z, **서버 10:17:58 KST**([supp-run-time.txt](supp-run-time.txt)) | rc=0 |

- 본 조사 rc=1 의 출처: `inv.sh` 의 마지막 명령이 토큰 검사 `grep -c` 이고, 모든 파일이 일치 0 이라 grep 이 1 을 돌려준 것이다(토큰 0 = 정상). 앞선 ssh 읽기 6개는 모두 출력 파일이 채워졌고 stderr 에 재시도·오류 줄 0. 이 rc=1 을 0 으로 고쳐 적지 않는다 — 스크립트의 종료값 설계 결함이며 다음에는 마지막 줄을 rc 판정과 분리한다.
- 한계: `w1`·`w2` 는 패턴 일치 줄을 앞 80 줄까지만 남겼다(캔들 완료 줄이 많아 `w2` 에는 15:50:47Z CryptoPanic 줄이 잘렸다 — 그 줄은 [N03 error_lines](../n03-1117-1000/error_lines.txt) 에 있다). `ssh-journal.txt` 는 집계 + 앞 60 줄만 남겼다.

## 결과

**backend 통신 오류 9줄** (구간별 줄 수·ERROR·WARN 은 각 파일 첫 줄):

| 시각(KST) | 원문 [파일] | 경과 | 영향 |
|---|---|---|---|
| 00:00:49~00:01:19 | Upbit 캔들 `HttpTimeoutException: request timed out` 3 → IOTA·WAVES·CRO H1 동기화 실패 3, `ExchangeHealthMonitor 거래소 연결 실패 (1/3회) 10001ms` 1 [w1](w1-candle-0000kst.txt) | 바로 앞 14:59Z·다음 15:02:34Z 회차는 같은 3코인 수집 완료 — 한 회차만 실패, 헬스 1/3 에서 멈춤 | BTC 아님, 모의 세션 신호는 HOLD. 주문 영향 0 |
| 00:50:47 | CryptoPanic `Connection reset` 1 ([N03 error_lines](../n03-1117-1000/error_lines.txt)) | 그 밤 CryptoPanic 은 403 이 상시(×23) | 보조 뉴스 |
| 04:26:31 | 텔레그램 `응답 미수신: Connection reset` [w3](w3-telegram-0426kst.txt) | **2초 뒤 재시도 2번째에 `전송 성공`**(04:26:34, CANDLE_REFRESH_REPORT) | 앱 기준 재전송 성공. 첫 시도의 실제 수신 여부(중복 가능성)는 모른다 |
| 05:00:02 | ClaudeProvider `IOException: Connection reset` → `LlmTaskRouter REPORT_NARRATION 응답 실패` [w4](w4-claude-0500kst.txt) | 재시도 줄 없음 | 보고 서술 1회 누락 가능, 매매 경로 아님 |

- 분포 [net-minutes.txt](net-minutes.txt)(22:00~08:30 KST 전체 연결 계열): 00:00 1·00:01 3·00:50 1·04:26 1·05:00 1 — **묶여 있지 않고 서로 다른 외부 상대(Upbit·CryptoPanic·Telegram·Anthropic)에 흩어진 단발**이다. 05:00 뒤 10:00 까지 재발 0([N03 10:00](../n03-1117-1000/README.md)).
- 원인: 서버 바깥 경로의 일시 끊김일 수 있으나 **확정하지 않는다**(같은 시각 서버 네트워크 장치·공유기 기록은 읽지 않았다).

**ssh 접속 끊김(07:56~08:05)** — [ssh-journal.txt](ssh-journal.txt)·[ssh-supp.txt](ssh-supp.txt), SSH 서비스 로그 07:50~08:10 KST:

- 이 20분 동안 sshd 로그 **120,444줄**, `Failed` 20,754 — 거의 모두 한 출발지 `143.198.x.x` 의 무차별 대입(20,708, 분당 약 1,000, 없는 사용자 이름·root 시도).
- sshd 가 **MaxStartups 제한을 걸어 인증 전 연결을 버리고 있었다**: `exited MaxStartups throttling after 00:48:31, 149646 connections dropped`(08:06:58), 4초 뒤 08:07:02 다시 시작·`drop connection … past MaxStartups`.
  - 즉 약 07:18:27~08:06:58 KST 제한 구간이 개발 PC 접속 끊김 07:56~08:05 를 덮는다. **ssh 끊김의 유력한 원인**이다. 다만 끊긴 개발 PC 연결 하나하나의 drop 줄을 대조한 것은 아니다(앞 60줄만 보존·출발지 가림).
- 성공 로그인은 `Accepted publickey` 21 뿐(이 구간). `Accepted password`·다른 방식 0.
- 오늘 10:13·10:17 접속은 재시도 0 — 지금 공격·제한이 계속되는지는 이 시각 기준으로 보지 않았다.

## 판단 요청 (운영 변경 안 함)

- 🔴 **보안 노출**: 로그에 `Failed password for invalid user …` 가 남는 것은 서버 sshd 가 **비밀번호 인증 시도를 받고 있다**는 뜻일 가능성이 높다. `sshd -T` 는 sudo 권한이 없어 설정값은 **미확인**이다. 이 구간에서 비밀번호 로그인 성공은 0 이다. 대응(비밀번호 인증 끄기·fail2ban·공유기 포트 제한 등)은 운영 변경이라 하지 않았다 — 총괄·사용자 판단 사항.
- backend 통신 오류는 단발·자동 회복·주문 영향 0 으로 분류한다. P0 조건(연결 획득 실패·누수·재기동·OOM) 0.
