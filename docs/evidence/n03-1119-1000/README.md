# N03 11-19 10:00 보고·09:30 장부 예약 읽기

같은 고정 [read.sh](read.sh)(41e4f337…)를 인자 `20261119-1000 n03-1119-1000 20261119`로 예약 실행했다([sched](sched/)). 읽기 전용이며 재실행·재전송·수집 호출은 하지 않았다.

- **실제 시각:** 개발 PC 01:13:08~01:13:14Z, 서버 **11-19 10:13:09 KST**, `read rc=0`, 표준오류 없음. 보고·msg·raw·last_send 로컬 해시 = 서버 해시. 토큰 형식 검사 모두 0.
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 · INSTALLED_SHA c669ccdc([install_state](install_state.txt)).

## 보고 판정 (10:00:04 KST 전송 성공 · 🔴 이상 1건)

- **🔴 미분류 ERROR 3건**: 모두 보조 외부 통신이다 — 뉴스 수집(CryptoPanic Connection reset·시간 초과, UpbitNotice Connection reset)과 ClaudeProvider 요청 실패(로그 20:00:02Z = 11-19 05:00 KST). 매매·정산 경로가 아니다. 스크립트 규칙대로 🔴로 두며 원인은 확정하지 않는다([error_lines](error_lines.txt) 18줄).
- **🟡 텔레그램 TRADE_SUMMARY 전송 실패 1건**: 15:00:10Z(= 11-19 00:00 KST 자정 요약) 응답 미수신 시간 초과. 19:26:31Z(04:26 KST)에도 Connection reset 1건이 있었고, 마지막 성공은 04:26. 휴대폰 수신은 확인하지 않았다.
- 🟡 외부 통신 ERROR 7(Connection reset 6줄), KRW-BLAST 404·보충 수집 실패 각 4, 66세션 적재 뒤처짐 12회(전부 KRW-BLAST) — 기존 패턴.
- DB(보고값): Flyway 96 · SID203 밖 실자금 보유/미결/정리 대기/격리 0 · 66세션 RUNNING 63 + 지정 STOPPED 3/3. SID203 CREATED·잔액 20200.00·주문 0·정산 이상 0. B1 true · B3 20/20.

## 09:30 장부

[ledger-20261119](ledger-20261119.txt) L0 `203 CREATED 20200.00`, L4 balance_diff 0.00·raw_diff 0.00·순체결량0=열린 포지션0. 과거 장부 파일은 maint-n17 기준과 같다([ledger-files](ledger-files.txt)).

**판단:** 매매·정산에 새 P0 없음. 밤사이(11-19 00:00~05:00 KST) 외부 통신 끊김이 몰렸고, 자정 거래 요약 텔레그램 1건이 실패했다. 마지막 운영 읽기는 **11-19 10:13:09 KST**이며 DB는 보고 값이다.
