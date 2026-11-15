# N03 11-14 10:00 보고·09:30 장부 읽기 (2026-11-15 15:36 KST, 개발 세션, 총괄 판정 전)

- 실제 읽기: `run-time.txt` 2행 서버 시각(Git Bash 표기 1·3행은 9시간 어긋남, 기존과 같은 표기 오류). 보고 생성 뒤 읽지 않은 시간은 미관측이며 감시 시간이 아니다.
- 명령: [n03-1113-1000/read.sh](../n03-1113-1000/read.sh) 복사본(`read.sh`)을 인자만 바꿔 실행. 재실행·재전송·수집 호출·66 중간 성과 열람·운영 변경 없음. 토큰 형태 0(`token-check.txt`).
- `last_send`는 서버에서 매 회차 덮어써지는 파일이라 이 폴더의 사본은 읽은 시점 최신(11-15 10:00) 값이다. 회차별 전송 결과는 보고 `.txt` 끝 줄.

| 항목 | 값 |
|---|---|
| 보고 | 🔴 이상 1건(미분류 ERROR 7), 10:00:04 전송 성공 |
| 실자금·SID 203 | 직전과 같음(보유0·미결0·구간 주문0·정산 불일치0) |
| ERROR 27 | 404 3·통신14·미분류7·BLAST 보충 3. 미분류7 = 15:29~16:00 UTC 뉴스 수집 7줄(CryptoPanic·UpbitNotice·tokenpost·CoinGecko·bloomberg·blockmedia Connection reset, coindesk timed out). 보고 본문은 `head -5`로 5줄만 표시(기존 c669ccdc 426행 동작) |
| 장부 | `20261114.txt` 3,732B 09:30:01, 11-13 대비 기록 시각·BTC 표시가(93,226,000 @11-14 09:00)만 다름. 과거 장부 파일 그대로 |

설치 상태(`install_state.txt`): HEAD b5cd1d0d · backend 이미지 52e12428 · 재시작 0 · 기동 11-10 13:48 KST · INSTALLED_SHA c669ccdc · daily_check.sh ff0a5d7d · daily_checks.sql 653b22ba · crontab 해시는 파일 참조. frontend `.Image` d825a9f5(FEIMGOLD와 같음). Hikari 누수·획득 실패·N17/N19/N20 0.
