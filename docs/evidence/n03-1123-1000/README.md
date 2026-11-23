# N03 11-23 10:00 보고·09:30 장부 읽기 (총괄 v1.210 §4, 예약 8d0db7de)

같은 고정 [read.sh](read.sh)(41e4f337…, n03-1122-2200 사본과 해시 일치)를 인자 `20261123-1000 n03-1123-1000 20261123`으로 1회 실행했다. 읽기 전용이며 재실행·재전송·재생성·추가 DB 조회는 하지 않았다. 실행 전 `~/.claude/usage-observed.json` 없음 → 사용률 미확인(0 아님).

- **실제 시각:** 개발 PC 01:14:32~01:14:37Z, 서버 **11-23 10:14:34 KST**, `read rc=0`([pc-time](pc-time.txt)·[run-time](run-time.txt)). 표준오류는 [read-stderr](read-stderr.txt)로 받았고 0바이트다.
- **원문 해시:** 보고 cacf8054…·msg a02e21d2…·raw 4bd9c924…·last_send 7e3cc6db… 로컬 = 서버([server-files](server-files.txt)). 보고 생성 10:00:04 KST, last_send 10:00:04 "보고 전송 성공 · 🟢 점검 항목 정상". 토큰 형식 검사 모두 0([token-check](token-check.txt)).
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 running(기동 11-16 05:16:14Z) · INSTALLED_SHA c669ccdc · crontab f2dce280 · 점검 스크립트 해시는 지난 회차와 같다([install_state](install_state.txt)).
- **frontend:** 9d7f123a · 재시작0 · running(10:14:38 KST 조회, [frontend-image](frontend-image.txt)). 11-22 10:00 회차의 미확인은 이번 회차 값으로 대체하지 않고 그 회차 기록으로 유지한다.

## 보고 판정 (🟢, 새 🔴 없음)

- 보고 머리 🟢. 본문 "66세션: 🔴 적재 뒤처짐 12회"는 전부 KRW-BLAST(거래지원 종료 마켓)이며 기존 패턴이다.
- ERROR(구간) 15 = 상장 폐지 404 5 · 통신 실패 5 · KRW-BLAST 보충 수집 실패 5 · 미분류 0. [error_lines](error_lines.txt) 15줄은 모두 KRW-BLAST H1 404 계열(19:10~19:13Z = 04:10~04:13 KST)이다. Connection reset 0([observations-supp](observations-supp.txt)).
- 보조 뉴스 수집 ERROR 0(직전 세 회차의 00시대 reset 2건 패턴은 이번 구간에 없음). 수집 미완료 24 = cryptopanic HTTP_403(ERROR 아님, [observations](observations.txt)).
- Hikari 누수 경고 0 · 획득 실패 0 · N17/N19/N20 줄 0 · 시세 처리 실패 0.
- 텔레그램 구간 성공 2 · 마지막 성공 11-23 04:25. 휴대폰 수신은 확인하지 않았다.
- DB(보고값): Flyway 96 · SID203 밖 실자금 0 · 66세션 RUNNING 63 + STOPPED 3/3. SID203 CREATED·잔액 20200.00·보유0·주문0·정산 이상0. B1 true · B3 20/20.

## 09:30 장부

[ledger-20261123](ledger-20261123.txt)(1196bcfd…, 09:30:01 생성)
- L0 `203 CREATED 20200.00`, L1~L3 0행
- L4 balance_diff 0.00·raw_diff 0.00·순체결량 0 = 열린 포지션 0
- L5 중단 기록 0

과거 장부 파일은 maint-n17 기준과 같다([ledger-files](ledger-files.txt)).

**판단:** 매매·정산에 새 P0 없음, 새 🔴 없음. 마지막 운영 읽기는 **11-23 10:14:34 KST**이며 DB는 보고·장부 값이다. 11-22 22:14 ~ 11-23 10:14 사이는 실시간 관측하지 않았고 정기 보고로만 덮인다. 이 읽기는 v1.217 §2 상태 읽기(10:34~10:54 창, 예약 934ceda9)와 별개다.
