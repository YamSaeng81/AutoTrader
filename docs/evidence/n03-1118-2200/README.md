# N03 11-18 22:00 보고 — 11-19 09:15 늦은 회수 (총괄 v1.191 §4)

같은 고정 명령 [read.sh](read.sh)(해시 41e4f337…, [n03-1118-1000](../n03-1118-1000/README.md)과 같음)를 인자 `20261118-2200 .`(장부 없음)으로 1회 실행했다. 읽기 전용이며 재실행·재전송·강제 평가·추가 DB 조회는 하지 않았다.

- **실제 시각:** 개발 PC 00:15:57~00:16:01Z, 서버 **11-19 09:15:58 KST**, `read rc=0`, 표준오류 없음([late-run-time](late-run-time.txt)·[run-time](run-time.txt). Git Bash `TZ=Asia/Seoul`은 UTC로 찍힘).
- **파일 대조:** 보고·msg·raw·last_send 4개의 로컬 해시 = 서버 해시([server-files](server-files.txt)·[local-sha256](local-sha256.txt)). 서버 파일 시각 22:00:03~04 KST. 토큰 형식 검사 모두 0([token-check](token-check.txt)).
- **설치 상태(09:15~09:16 읽기):** HEAD 0e1d2a02 · backend 2c5d8427 running 재시작0(기동 11-16 05:16:14Z) · frontend 9d7f123a 재시작0 · INSTALLED_SHA c669ccdc · daily_check.sh ff0a5d7d · daily_checks.sql 653b22ba · sid_ledger.sql 02a85850([install_state](install_state.txt)·[frontend-image](frontend-image.txt)).

## 보고 판정 (22:00:04 KST 전송 성공 · 🟢)

- DB(보고값): Flyway 96 · SID203 밖 실자금 보유/미결/정리 대기/격리 0 · 구간 SID203 밖 실자금 매수 0 · 66세션 RUNNING 63 + 지정 STOPPED 3/3.
- SID203: CREATED · 잔액 20200.00 · 보유0 · 미결0 · 구간 주문 0 · 정산 확인 불가0 · 불일치0. B1 true(최신 완료 봉 21:00) · B3 20/20.
- ERROR 전체 0([error_lines](error_lines.txt) 0바이트). 🟡 66세션 적재 뒤처짐 12회 전부 KRW-BLAST(기존 패턴). CryptoPanic HTTP 403 24(수집 완료 84)·Connection reset 0·Hikari 경고 0·N17/N19/N20 0([observations](observations.txt)·[supp](observations-supp.txt)).

**판단:** 새 P0 없음. DB 값은 22:00 보고 값이며 직접 조회하지 않았다. 마지막 운영 읽기는 이제 **11-19 09:15:58 KST**(설치 상태·파일만)이며, 11-18 22:00 보고 이후 DB·매매 상태는 미관측이다. 11-19 10:00 보고·09:30 장부는 10:12 이후 같은 명령으로 읽는다.
