# N03 정기 점검 — 2026-11-03 10:00 KST 자동 보고

설치판 `8435935` 의 두 번째 cron 자동 보고다. 2026-11-03 10:05 KST 에 기존 권한(`ssh cat-prod`)으로 **읽기만** 했다. 서버·cron·backend·DB·장부에는 쓰지 않았고, 다시 실행하거나 설정을 바꾸지 않았다.

파일:
- `20261103-1000.txt`: 서버 `~/daily-check/20261103-1000.txt` 원본. 마지막 줄이 `2026-11-03 10:00:04 KST 보고 전송 성공 · 🔴 이상 1건` 이다.
- `20261103-1000.txt.msg`: 텔레그램으로 보낸 본문.
- `20261103-1000.txt.raw`: 파서 입력 원본(DB 결과·66세션 판정).
- `last_send`: 서버 전송 기록 원본.
- `crontab_installed.txt`: `crontab -l | sha256sum` 과 `~/ops-scripts/INSTALLED_SHA`.
- `error_lines.txt`: 서버 `20261103-1000.txt.log.gz` 에서 ` ERROR ` 줄만 뽑은 13줄. 로그 시각은 UTC 다(구간 시작 13:00 = 22:00 KST).
- `sid203-ledger-20261103.txt`: 09:30 KST 장부(`~/sid203-ledger/20261103.txt`) 원본.

확인 결과:
- 머리글 `점검 스크립트 8435935e975c`, `INSTALLED_SHA` `8435935e975ceb6c2e16a09ef5b82530574a7da2`.
- **15:07 기동 🔴 사라짐.** 구간이 11-02 22:00 부터라 11-02 15:07 계획 기동이 빠졌다. 앱 `재시작 0`, 기동 시각 `2026-11-02T06:07:04Z` 그대로 — 새 재기동 없음.
- BTC: `관측 11-03 10:00:02 KST · B1 true · 최신 완료 봉 11-03 09:00 · 필요 11-03 08:00(2분 유예) · B3 20/20`. BTC ⚪ 없음.
- **새 🔴 1건: `미분류 ERROR 3건`.** 세 줄 모두 보조 뉴스 수집의 외부 연결 끊김이다.
  - `14:25:51 UTC`(23:25 KST) `RssNewsSource` `sourceId=tokenpost_rss: Connection reset`
  - `15:25:56 UTC`(00:25 KST) `UpbitNoticeSource` `수집 실패: Connection reset`
  - `15:25:57 UTC`(00:25 KST) `CryptoPanicSource` `수집 실패: Connection reset`
  - 8435935 점검의 뉴스 패턴은 지정 출처의 `: null` 메시지만 🟡 로 받고, 그 밖은 일부러 미분류 🔴 로 올린다(`scripts/ops/daily_check.sh` 274~283행). 그래서 설계대로 🔴 가 됐다. 매매 경로 오류로 단정하지 않으며, 분류 변경은 하지 않았다.
- 🟡: 외부 통신 실패 ERROR 6건(점검 분류 그대로: KRW-ADA M15 캔들 수집 시간 초과·동기화 실패 2줄 00:01 KST, KRW-BLAST `UpbitCandleCollector` 404 2줄 04:10 KST, 텔레그램 응답 미수신 Connection reset 04:35 KST, ClaudeProvider 요청 실패 05:00 KST — 줄 단위는 `error_lines.txt`), BLAST 보충 수집 실패 2건·캔들 404 2건·66세션 적재 뒤처짐 12회(전부 KRW-BLAST). BLAST 줄은 기존 반복과 같다.
- ⚪ 없음.
- SID 203 CREATED·잔액 20200.00·보유/미결 0, 지정 종료 576 577 578 STOPPED 3/3, SID 203 밖 실자금 보유·미결·매수 0, Flyway 94.
- 09:30 장부: CREATED·20200.00·주문 0·balance_diff 0·중단 기능 기록 0.
- crontab sha256 `f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b` — 이전과 같다.
- 전송: **서버 전송 성공**(`last_send` 10:00:04). **휴대폰 실제 수신은 확인하지 않았다.**
- 텔레그램 토큰 등 비밀값은 파일에 없다.
