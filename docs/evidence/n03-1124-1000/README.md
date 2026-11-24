# N03 11-24 10:00 보고·09:30 장부 읽기 (총괄 v1.232 §3-3, 예약 08a9c4d7)

같은 고정 [read.sh](read.sh)(41e4f337…, n03-1123-1000 사본과 해시 일치)를 인자 `20261124-1000 docs/evidence/n03-1124-1000 20261124`로 1회 실행했다. 읽기 전용이며 재실행·재전송·재생성·추가 DB 조회는 하지 않았다. 실행 전 `command -v ssh` = `/usr/bin/ssh`(실제), `~/.claude/usage-observed.json` 없음 → 사용률 미확인(0 아님).

- **실제 시각:** 개발 PC 01:14:15~01:14:20Z, 서버 **11-24 10:14:18 KST**, `read rc=0`([pc-time](pc-time.txt)·[run-time](run-time.txt)). 표준오류는 [read-stderr](read-stderr.txt)로 받았고 0바이트다.
- **원문 해시:** 보고 73e94385…·msg 54d98025…·raw 852f8145…·last_send 402793a6… 로컬 = 서버([server-files](server-files.txt)). 보고 생성 10:00:05 KST, last_send 10:00:05 "보고 전송 성공 · 🟢 점검 항목 정상". 토큰 형식 검사 모두 0([token-check](token-check.txt)).
- **설치 상태:** HEAD 0e1d2a02 · backend 2c5d8427 재시작0 running(기동 11-16 05:16:14Z) · INSTALLED_SHA c669ccdc · crontab f2dce280 · 점검 스크립트 해시는 지난 회차와 같다([install_state](install_state.txt)).
- **frontend:** 9d7f123a · 재시작0 · running(10:14:22 KST 조회, [frontend-image](frontend-image.txt)).

## 보고 판정 (🟢, 새 🔴 없음)

- 보고 머리 🟢. 본문 "66세션: 🔴 적재 뒤처짐 12회"는 전부 KRW-BLAST(거래지원 종료 마켓)이며 기존 패턴이다.
- ERROR(구간) 7 = 통신 실패 7 · 미분류 0 · 상장 폐지 404 0 · 공유 풀 미등록 0 · 캔들 판정 불가 0. [error_lines](error_lines.txt) 7줄(UTC):
  - 15:00:46·15:01:01 캔들 수집 request timed out 2건 + 그에 따른 시장 데이터 동기화 실패 2건(KRW-INJ H1·KRW-BTC H1) — 00:00~00:01 KST
  - 15:03:58 UpbitOrderClient 계좌 조회 실패: Connection reset 1건 — 00:03 KST. **정정(11-24 10:2x, 사용자 지시): 단발이 아니라 반복이다.** 직전 구간 [11-23 22:00 회차](../n03-1123-2200/README.md)의 11-23 10:33 KST "계좌 조회 실패: null"에 이어 두 구간 연속 발생했다. 회복 여부(이후 계좌 조회 성공)는 이 읽기로 확인하지 않았다 → **반복·회복 미확인 🟡**
  - 19:33:16 텔레그램 메시지 전송 중 오류(응답 미수신): Connection reset 1건 — 04:33 KST. 같은 구간 텔레그램 성공 2·큐 진입 실패 0·마지막 성공 04:33
  - 20:00:02 ClaudeProvider 요청 실패 1건 — 05:00 KST(보조 LLM 호출)
  - 계좌 조회 외 항목은 외부 통신 단발로 🟡이며 원인은 확인하지 않았다. 매매·정산 경로 이상 신호는 없다(SID203 주문 0, B1 true).
- 수집 미완료 24 = cryptopanic HTTP_403(ERROR 아님). Connection reset 줄 7([observations](observations.txt)·[observations-supp](observations-supp.txt)).
- Hikari 누수 경고 0 · 획득 실패 0 · N17/N19/N20 줄 0 · 시세 처리 실패 0 · DOCTYPE/html 0.
- 휴대폰 수신은 확인하지 않았다.
- DB(보고값): Flyway 96 · SID203 밖 실자금 0 · 미결0 · 66세션 RUNNING 63 + STOPPED 3/3. SID203 CREATED·잔액 20200.00·보유0·주문0·정산 이상0. B1 true(최신 완료 봉 09:00) · B3 20/20.

## 09:30 장부

[ledger-20261124](ledger-20261124.txt)(b69761ca…, 09:30:01 생성)
- L0 `203 CREATED 20200.00`, L1~L3 0행
- L4 balance_diff 0.00·raw_diff 0.00·순체결량 0 = 열린 포지션 0
- L5 중단 기록 0

11-23 장부와 비교하면 조회 시각과 평가 봉(83,726,000 @11-24 09:00)만 다르다. 과거 장부 파일은 maint-n17 기준과 같다([ledger-files](ledger-files.txt)).

## 판정: U0 진입 조건 충족

회수 완전(rc0·원문4 로컬=서버·표준오류0). 새 매매·정산·P0 안전 이상 없음. 운영 기대값 HEAD 0e1d2a02·backend 2c5d8427·frontend 9d7f123a·재시작0·SID203 CREATED 20,200 모두 일치. U0-BLOCK 표지는 만들지 않았다.

마지막 운영 읽기는 **11-24 10:14:18 KST**이며 DB는 보고·장부 값이다. 11-23 22:14 ~ 11-24 10:14 사이는 실시간 관측하지 않았고 정기 보고로만 덮인다. 커밋은 10:32 U0 작업 뒤에 함께 한다.

## 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더, 자신 제외).
