# N03 11-13 22:00 보고 읽기 (2026-11-15 15:36 KST, 개발 세션, 총괄 판정 전)

- 실제 읽기: `run-time.txt` 2행 서버 시각(Git Bash 표기 1·3행은 9시간 어긋남, 기존과 같은 표기 오류). 보고 생성 뒤 읽지 않은 시간은 미관측이며 감시 시간이 아니다.
- 명령: [n03-1113-1000/read.sh](../n03-1113-1000/read.sh) 복사본(`read.sh`)을 인자만 바꿔 실행. 재실행·재전송·수집 호출·66 중간 성과 열람·운영 변경 없음. 토큰 형태 0(`token-check.txt`).
- `last_send`는 서버에서 매 회차 덮어써지는 파일이라 이 폴더의 사본은 읽은 시점 최신(11-15 10:00) 값이다. 회차별 전송 결과는 보고 `.txt` 끝 줄.

| 항목 | 값 |
|---|---|
| 보고 | 🟢 점검 항목 정상, 22:00:03 전송 성공 |
| 실자금 | SID 203 밖 보유0·미결0(LIVE·DYNAMIC REAL의 PENDING~PARTIAL_FILLED·UNKNOWN)·정리 대기0·격리0·구간 매수0 |
| SID 203 | CREATED · 20,200.00 · 보유0 · 미결0 · 구간 주문0 · 정산 불일치0 |
| ERROR | 0 |

설치 상태(`install_state.txt`): HEAD b5cd1d0d · backend 이미지 52e12428 · 재시작 0 · 기동 11-10 13:48 KST · INSTALLED_SHA c669ccdc · daily_check.sh ff0a5d7d · daily_checks.sql 653b22ba · crontab 해시는 파일 참조. frontend `.Image` d825a9f5(FEIMGOLD와 같음). Hikari 누수·획득 실패·N17/N19/N20 0.
