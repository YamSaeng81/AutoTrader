# N03 11-22 22:00 보고 읽기 (총괄 v1.210 §4, 장부 없음)

같은 고정 [read.sh](read.sh)(41e4f337…, n03-1122-1000 사본과 해시 일치)를 인자 `20261122-2200 n03-1122-2200`으로 1회 실행했다. 읽기 전용이며 재실행·재전송·재생성·추가 DB 조회는 하지 않았다. 실행 전 `~/.claude/usage-observed.json` 없음 → 사용률 미확인(0 아님).

- **실제 시각:** 개발 PC 13:14:16~13:14:20Z, 서버 **11-22 22:14:17 KST**, `read rc=0`, 표준오류 0바이트([pc-time](pc-time.txt)·[run-time](run-time.txt)·[read-stderr](read-stderr.txt)).
- **원문 해시:** 보고 c6720d68…·msg 0ca627ee…·raw de9f1121…·last_send d37ab083… 로컬 = 서버([server-files](server-files.txt)). 보고 생성 22:00:04 KST, last_send 22:00:04 "보고 전송 성공 · 🟢 점검 항목 정상". 토큰 형식 검사 모두 0.
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 · frontend 9d7f123a 재시작0 · INSTALLED_SHA c669ccdc · crontab f2dce280 · 점검 스크립트 해시는 지난 회차와 같음([install_state](install_state.txt)·[frontend-image](frontend-image.txt)). 10:00 회차에서 미확인이던 frontend 값은 이번 회차에 읽혔고 지난 값과 같다.
- 폴더 `.gitattributes`(`* -text`)로 원문 바이트를 그대로 커밋한다.

## 보고 판정 (머리 🟢, 새 🔴 없음)

- 🟢 점검 항목 정상. ERROR(구간) 전체 0 · 미분류 0 · 통신 실패 0([error_lines](error_lines.txt) 0바이트).
- 🟡/본문 🔴 표기: 66세션 적재 뒤처짐 12회 — 전부 KRW-BLAST(거래지원 종료 마켓), 기존 패턴. cryptopanic HTTP_403 수집 미완료 23건(ERROR 아님, [observations](observations.txt)).
- Hikari 누수 경고 0 · 연결 획득 실패 0 · Connection reset 0.
- 텔레그램 구간 성공1 · 마지막 성공 11-22 12:00 · last_send 22:00:04 성공 줄. 휴대폰 수신은 확인하지 않았다.
- DB(보고값): Flyway 96 · SID203 밖 실자금 0 · 66세션 RUNNING 63 + STOPPED 3/3. SID203 CREATED·잔액 20200.00·보유0·주문0·정산 이상0. B1 true · B3 20/20.

**판단:** 매매·정산에 새 P0 없음. 마지막 운영 읽기는 **11-22 22:14:17 KST**이며 DB는 보고 값이다. 10:14 ~ 22:14 사이는 실시간 관측하지 않았고 정기 보고로만 덮인다.
