# N03 11-23 22:00 보고 읽기 (총괄 v1.226 §2, 예약 4bc9e12a, 장부 없음)

같은 고정 [read.sh](read.sh)(41e4f337…, n03-1123-1000 사본과 해시 일치)를 인자 `20261123-2200 docs/evidence/n03-1123-2200`(장부 인자 없음)으로 **1회** 실행했다. 읽기 전용이며 재실행·재전송·재생성·추가 SQL/계좌 조회는 하지 않았다. 실행 전 폴더·같은 회차 기록 없음 확인, `~/.claude/usage-observed.json` 없음 → 사용률 미확인(0 아님), `command -v ssh`·`type ssh` = `/usr/bin/ssh`(가짜 PATH 없음).

- **실제 시각:** 개발 PC 22:14:13 KST 시작, 서버 **11-23 22:14:16 KST**, `read rc=0`(22:14:18, [pc-time](pc-time.txt)·[run-time](run-time.txt)·[rc-local](rc-local.txt)). 정기 회차(22:00) 대비 약 14분 뒤 읽음. 표준오류 [read-stderr](read-stderr.txt) 0바이트.
- **원문 해시:** 보고 814636f5…·msg d402eb62…·raw a5b4bbe6…·last_send f01434bf… 로컬 = 서버([server-files](server-files.txt)·[local-sha256](local-sha256.txt)). 보고 생성 22:00:04 KST, last_send 22:00:04 "보고 전송 성공 · 🟢 점검 항목 정상". 토큰 형식 검사 모두 0([token-check](token-check.txt)).
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 running(기동 11-16 05:16:14Z) · INSTALLED_SHA c669ccdc · crontab f2dce280 · 점검 스크립트·장부 SQL 해시는 지난 회차와 같다([install_state](install_state.txt)).
- **frontend:** 9d7f123a · 재시작0 · running(22:14:19 KST 조회, [frontend-image](frontend-image.txt)).

## 보고 판정 (🟢, 새 🔴 없음)

- 보고 머리 🟢. 본문 "66세션: 🔴 적재 뒤처짐 12회"는 전부 KRW-BLAST(거래지원 종료 마켓)이며 기존 패턴이다.
- ERROR(구간) 1 = 통신 실패 1 · 미분류 0. [error_lines](error_lines.txt) 1줄: `01:33:46.908Z`(= 10:33:46 KST) `UpbitOrderClient - 계좌 조회 실패: null`. 단발이며 Connection reset 0([observations-supp](observations-supp.txt)). 직전 회차의 KRW-BLAST 404 계열은 이번 구간에 없다. 원인은 이 읽기로 확인하지 않았다(추가 조회 금지) — 🟡로 기록하고 다음 회차에 반복 여부를 본다.
- 수집 미완료 24 = cryptopanic HTTP_403(ERROR 아님, [observations](observations.txt)). 보조 뉴스 수집 ERROR 0.
- Hikari 누수 경고 0 · 획득 실패 0 · N17/N19/N20 줄 0 · 시세 처리 실패 0 · DOCTYPE/html 0.
- 텔레그램 구간 성공 1 · 마지막 성공 11-23 12:00. 휴대폰 수신은 확인하지 않았다.
- DB(보고값): Flyway 96 · SID203 밖 실자금 0 · 미결0 · 정리 대기0 · 66세션 RUNNING 63 + STOPPED 3/3. SID203 CREATED·잔액 20200.00·보유0·구간 주문0·정산 이상0. B1 true · B3 20/20.

**판단:** 매매·정산에 새 P0 없음, 새 🔴 없음. 마지막 운영 읽기는 **11-23 22:14:16 KST**이며 DB는 보고값이다. 11-23 10:14 ~ 22:14 사이는 실시간 관측하지 않았고 정기 보고로만 덮인다. 다음 회차 11-24 10:00 보고·09:30 장부는 기존 예약 `08a9c4d7`(10:14 KST)로 읽는다 — 사용자 지시(11-23 22:02)에 따라 새 예약을 추가하지 않았다.
