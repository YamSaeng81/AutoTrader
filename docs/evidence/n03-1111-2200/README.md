# N03 2026-11-11 22:00 정기 보고(늦게 읽음) + 11-12 아침 상태 읽기 — 읽기 전용

**읽기만 했다.** 재실행·재전송·수집 호출·서버 쓰기·DB 조회는 없다.

- **22:00 회차를 예정(22:12 이후)에 읽지 못했다.** 개발 세션이 11-11 약 17:30(D 착수 중)부터 11-12 08:05 사용자 재개 요청까지 진행하지 않았다. 실제로 읽은 시각은 **2026-11-12 08:06:10 KST**(`read-at.txt`)다. 보고 원본은 22:00:02~03 에 생성·전송됐고 이후 바뀌지 않았다(서버 sha256 = 내려받은 파일 sha256, `server-files.txt`·`local-sha256.txt`).
- `read.sh` 는 11-11 10:00 회차와 같은 명령에서 회차 이름·폴더만 바꾸고 09:30 장부 단계를 뺐다. 종료 코드 1은 토큰 형태 검사 `grep -c` 0건 때문이다(이전 회차와 같음).
- 아침 읽기(`morning-read-at.txt` 08:06:31 KST)는 현재 컨테이너의 **22:00 KST 이후 로그**에서 ERROR·연결 획득 실패·Hikari·시세 보호 줄 수와 ERROR 원문을 한 번 읽은 것이다(`morning-observations.txt`, `morning-error-lines.txt`). 설치 상태는 22:00 회차 읽기 명령의 `install_state.txt`(08:06 시점 값)다.

## 22:00 보고 — 🟢, 새 미해명 🔴·⚪ 없음, P0 없음

| 항목 | 값 |
|---|---|
| 보고·전송 | `last_send`: "2026-11-11 22:00:03 KST 보고 전송 성공 · 🟢 점검 항목 정상". **휴대폰 수신 미확인** |
| 앱·이미지·HEAD (08:06 읽기) | running · 재시작 0 · StartedAt 2026-11-10T04:48:44Z · 이미지 `52e12428…9055` · HEAD b5cd1d0d |
| 설치본·cron | INSTALLED_SHA c669ccd · daily_check.sh `ff0a5d7d…` · daily_checks.sql `653b22ba…` · SID_LEDGER 8bfe8a9 / `02a85850…` · crontab `f2dce280…` — 10:13 읽기와 같음 |
| DB(보고 원문) | Flyway 94 · SID 203 밖 실자금 보유/미결/정리/격리 0 · 66세션 RUNNING 63 · 576/577/578 STOPPED 3/3 · 구간 실자금 매수 0 |
| SID 203 (보고 원문) | CREATED · 20200.00 · 보유 0 · 미결 0 · 구간 주문 0 · 정산 불일치 0 |
| BTC H1 B1/B3 | 관측 22:00:01: **B1 true** · 최신 완료 봉 21:00(필요 20:00) · **B3 20/20** |
| ERROR(구간 10:00~22:00) | 0 |
| 🟡 | 66세션 적재 뒤처짐 12회 — 전부 KRW-BLAST(기존 모양) |
| P0 관측 | Hikari 누수 0 · unleaked 0 · **연결 획득 실패 0** |
| 시세 보호 | N17 0 · N19 0 · N20 0 · 회차 오류 0 · 처리 실패 0 |
| 뉴스 | 완료 84 · 미완료 24(전부 cryptopanic HTTP_403) · DOCTYPE/`<html` 0 · Connection reset 0 |
| 텔레그램(앱) | 구간 성공 1 · 큐 진입 실패 0 · 마지막 성공 12:00 |

## 아침 읽기 — 22:00 KST 이후 로그 (보고서 판정 아님, 10:00 보고 전)

- **연결 획득 실패 0 · Hikari 누수 0 · N17/N19/N20 0 → P0 없음.**
- ERROR 23줄(원문 전체 `morning-error-lines.txt`, 토큰 형태 0). KST 로 묶으면:
  - 00:00:56~00:01:26 Upbit 캔들 `request timed out` 3 + 시장 데이터 동기화 실패 3 (ADA·AVAX·BTC M15)
  - 00:04:28 `UpbitOrderClient` 계좌 조회 실패: Connection reset 1
  - 00:25:14 UpbitNotice·**CryptoPanic** 수집 실패: Connection reset 2, 00:55:15~16 Rss(tokenpost·bloomberg)·CoinGecko Connection reset 3
  - 04:10~04:11 KRW-BLAST 404 ×3 묶음 9 (11-06 이후 반복 문구)
  - 04:38:31 텔레그램 메시지 전송 중 오류(응답 미수신): Connection reset 1
  - 05:00:02 ClaudeProvider 요청 실패 1
- 판단 보류: 00:00~00:55 의 외부 통신 실패 묶음과 04:38 텔레그램 응답 미수신은 **이 구간의 새 관측**이다. 원인·영향(어떤 메시지였는지, 실제 전달 여부)은 확인하지 않았다. 분류는 11-12 10:00 보고 원문을 따른다. **CryptoPanic Connection reset 이 이 구간에 다시 1줄 나왔다** — 기존 미분류 🔴은 해결로 쓰지 않는다.
- SID 203·BTC 는 아침에 DB 를 읽지 않았다. 아침 값은 **미확인**이며 위 표의 값은 22:00 보고 원문이다. 형성 중 봉으로 B1/B3 를 판정하지 않았다.
- 11-12 09:30 장부·10:00 보고는 이 읽기 뒤의 일정이다.

운영 변경 없음.
