# N03 11-20 10:00 보고·09:30 장부 읽기 (총괄 v1.200 §3-1)

같은 고정 [read.sh](read.sh)(41e4f337…)를 인자 `20261120-1000 n03-1120-1000 20261120`으로 1회 실행했다. 읽기 전용이며 재실행·재전송·재생성·추가 DB 조회는 하지 않았다.

- **실제 시각:** 개발 PC 01:31:29~01:31:35Z, 서버 **11-20 10:31:32 KST**, `read rc=0`, 표준오류 0바이트([pc-time](pc-time.txt)·[run-time](run-time.txt)).
- **원문 해시:** 보고 bb1c553e…·msg 4881020c…·raw 4f1737cb…·last_send 1cea34c2… 로컬 = 서버. 보고 생성 10:00:04 KST, last_send 10:00:04. 토큰 형식 검사 모두 0.
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 · frontend 9d7f123a 재시작0 · INSTALLED_SHA c669ccdc · crontab f2dce280 · 점검 스크립트 해시는 11-19 회차와 같음([install_state](install_state.txt)).

## 보고 판정 (🔴 이상 1건)

- **🔴 미분류 ERROR 3건**: CryptoPanic·UpbitNotice Connection reset(15:25:41Z = 11-20 00:25 KST), tokenpost_rss 시간 초과(16:25:56Z = 01:25 KST). 보조 뉴스 수집이며 매매·정산 경로가 아니다. 로거별로 ClaudeProvider 1·Telegram 1 ERROR도 있다([observations-supp](observations-supp.txt), [error_lines](error_lines.txt) 23줄). 원인은 확정하지 않는다.
- 🟡 외부 통신 ERROR 12(Connection reset 6줄), KRW-BLAST 404·보충 수집 실패 각4, 66세션 적재 뒤처짐 12회(전부 KRW-BLAST) — 11-19 10:00 회차와 같은 패턴.
- 텔레그램 구간 성공2 · 마지막 성공 11-20 04:37. 휴대폰 수신은 확인하지 않았다.
- DB(보고값): Flyway 96 · SID203 밖 실자금 0 · 66세션 RUNNING 63 + STOPPED 3/3. SID203 CREATED·잔액 20200.00·주문0·정산 이상0. B1 true · B3 20/20.

## 09:30 장부

[ledger-20261120](ledger-20261120.txt)(38841e8a…, 09:30:01 생성) L0 `203 CREATED 20200.00`, L1~L3 0행, L4 balance_diff 0.00·raw_diff 0.00·순체결량0=열린 포지션0. 과거 장부 파일은 maint-n17 기준과 같다([ledger-files](ledger-files.txt)).

**판단:** 매매·정산에 새 P0 없음. 마지막 운영 읽기는 **11-20 10:31:32 KST**이며 DB는 보고 값이다. 11-19 10:13 ~ 11-20 10:31 사이는 실시간 관측하지 않았고 두 정기 보고로만 덮인다.
