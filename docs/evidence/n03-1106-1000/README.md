# N03 2026-11-06 10:00 정기 보고·09:30 SID 203 장부 — 읽기 전용 확인 (#25 U0 전, 총괄 v1.127 지시)

**읽기만 했다.** 재실행·재전송·수집 호출 없음. 서버에 쓰지 않았다. 실제 읽은 시각: 2026-11-06 10:05:17 KST(`read.sh`).

## 확인 결과 — 새 미해명 이상·⚪ 없음

| 항목 | 값 | 판단 |
|---|---|---|
| 보고 생성·전송 | `.txt` 10:00:04, `last_send` "10:00:04 KST 보고 전송 성공 · 🔴 이상 1건". `.msg`는 `.txt`에서 전송 결과 한 줄만 빠짐. 내려받은 파일 sha256이 서버 값과 같음 | 정상 생성·전송(휴대폰 수신은 미확인) |
| 앱·이미지 | running·재시작 0·기동 11-05T10:41:45Z·이미지 e51b512a72cc, HEAD 7a944723 | #24 그대로 |
| 19:41 기동·생성 공백 표지 | "현재 컨테이너 생성 11-05 19:41:44(구간 시작 전·같음 — 재생성 공백 없음)". ERROR 집계가 구간 전체(앞부분 미포함 표지 없음) | 어제 표지 사라짐(기대대로) |
| DB | Flyway 94·SID 203 밖 실자금 보유/미결/정리/격리 0·66세션 RUNNING 63·576/577/578 STOPPED·구간 실자금 매수 0 | 정상 |
| SID 203 | CREATED·20200.00·보유 0·미결 0·정산 불일치 0 | 정상 |
| 09:30 장부 | 09:30:01 생성, L4 `balance_diff 0.00`·`raw_diff 0.00`·unk 0, L5 중단 기록 0·CREATED. 과거 장부 파일 그대로(maint-n17 기준) | 정상 |
| BTC H1 | B1 true·최신 완료 봉 11-06 09:00·B3 20/20 | 충족 |
| 설치본·cron | INSTALLED_SHA c669ccd·daily_check.sh ff0a5d7d…·daily_checks.sql 653b22ba…·SID_LEDGER_SHA 8bfe8a9·sid_ledger.sql 02a85850…·crontab f2dce280… | 그대로 |
| ERROR 7 | 미분류 1 = 13:00:29.675Z(22:00:29 KST) CryptoPanic `Connection reset` — 07:50 보존 원문과 같은 줄, 기존 분류대로 미분류 🔴·복구 미확인. 나머지 6 = KRW-BLAST 404 계열(상장 폐지 404 2·통신 실패 2·보충 수집 실패 2) — 11-03·11-04 10:00 보고와 같은 나눔 | 기존 분류와 일치 |
| 66세션 경보 | 적재 뒤처짐 12회 전부 KRW-BLAST 1종, 현재 🟢 21/🔴 1 | 기존 분류(거래지원 종료 마켓) |
| 관측 | CryptoPanic HTTP 403 23건·IOException 1건, DOCTYPE/`<html` 0, Hikari 누수·획득 실패 0, N17·N19 줄 0 | P0 없음 |
| 토큰 형태 | 모든 보존 파일 0 | 비밀 없음 |

결론: 보고 전체를 정상으로 바꾸지 않는다(🔴 1건은 기존 미분류 그대로). #25 U0로 넘어갈 수 있다. 이는 개발 에이전트 확인이며 총괄 판정 전이다.

## 파일
`read.sh`(실행한 읽기 스크립트 원문), 보고 `.txt/.msg/.raw`·`last_send`, `server-files.txt`, `local-sha256.txt`, `install_state.txt`, `error_lines.txt`, `observations*.txt`, `ledger-20261106.txt`·`ledger-files.txt`, `token-check.txt`. 서버의 `.log.gz`는 내려받지 않았다(sha256만 기록).
