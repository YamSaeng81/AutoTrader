# N03 11-21 10:00 보고·09:30 장부 읽기 (총괄 v1.210 §4)

같은 고정 [read.sh](read.sh)(41e4f337…, n03-1120-2200 사본과 해시 일치)를 인자 `20261121-1000 n03-1121-1000 20261121`으로 1회 실행했다. 읽기 전용이며 재실행·재전송·재생성·추가 DB 조회는 하지 않았다. 실행 전 `~/.claude/usage-observed.json` 없음 → 사용률 미확인(0 아님).

- **실제 시각:** 개발 PC 01:14:19~01:14:24Z, 서버 **11-21 10:14:20 KST**, `read rc=0`, 표준오류 0바이트([pc-time](pc-time.txt)·[run-time](run-time.txt)).
- **원문 해시:** 보고 7c5d7a34…·msg bb83ce42…·raw 6362ec82…·last_send 91a5db7c… 로컬 = 서버([server-files](server-files.txt)). 보고 생성 10:00:04 KST, last_send 10:00:04 "보고 전송 성공 · 🔴 이상 1건". 토큰 형식 검사 모두 0.
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 · frontend 9d7f123a 재시작0 · INSTALLED_SHA c669ccdc · crontab f2dce280 · 점검 스크립트 해시는 앞 회차와 같음([install_state](install_state.txt)).

## 보고 판정 (🔴 이상 1건)

- **🔴 미분류 ERROR 2건**: CryptoPanic·UpbitNotice `Connection reset`(15:27:18Z = 11-21 00:27 KST). 보조 뉴스 수집이며 매매·정산 경로가 아니다. 11-20 10:00 회차(00:25 KST, 같은 두 소스)와 같은 패턴이다. 원인은 확정하지 않는다.
- 🟡 외부 통신 ERROR 12: 15:00:40~15:01:10Z(00:00 KST) KRW-ADA·AVAX·BTC M15 캔들 `request timed out` 각 1(수집기+동기화 2줄씩), Telegram 전송 중 Connection reset 1(19:25:46Z = 04:25 KST), ClaudeProvider 요청 실패 1(20:00:02Z) 등([error_lines](error_lines.txt) 22줄, [observations-supp](observations-supp.txt)).
- 🟡 KRW-BLAST 404·보충 수집 실패 각4, 66세션 적재 뒤처짐 12회(전부 KRW-BLAST) — 기존 패턴. cryptopanic HTTP_403 수집 미완료 23건(ERROR 아님).
- 텔레그램 구간 성공2 · 마지막 성공 11-21 04:25. 휴대폰 수신은 확인하지 않았다.
- DB(보고값): Flyway 96 · SID203 밖 실자금 0 · 66세션 RUNNING 63 + STOPPED 3/3. SID203 CREATED·잔액 20200.00·주문0·정산 이상0. B1 true · B3 20/20.

## 09:30 장부

[ledger-20261121](ledger-20261121.txt)(4bcad885…, 09:30:02 생성) L0 `203 CREATED 20200.00`, L1~L3 0행, L4 balance_diff 0.00·raw_diff 0.00·순체결량0=열린 포지션0, L5 중단 기록 0. 과거 장부 파일은 maint-n17 기준과 같다([ledger-files](ledger-files.txt)).

**판단:** 매매·정산에 새 P0 없음. 🔴는 보조 뉴스 수집 통신 오류 2건이다. 마지막 운영 읽기는 **11-21 10:14:20 KST**이며 DB는 보고·장부 값이다. 11-20 22:14 ~ 11-21 10:14 사이는 실시간 관측하지 않았고 정기 보고로만 덮인다.
