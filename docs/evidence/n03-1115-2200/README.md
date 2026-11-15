# N03 11-15 22:00 보고 읽기 (2026-11-15 22:08 KST, 개발 세션, 총괄 판정 전)

- 실제 읽기: 서버 시각 **22:08:26 KST**(`run-time.txt` 2행; Git Bash 표기 1·3행은 9시간 어긋남, 기존과 같은 표기 오류). 재시도 22:09(4행). 보고 생성(22:00:04) 뒤 읽기 전까지는 미관측이다.
- 명령: [n03-1115-1000/read.sh](../n03-1115-1000/read.sh) 복사본(`read.sh`), 인자 `20261115-2200 .`(장부 날짜 없음 — 장부는 09:30 회차). 재실행·재전송·수집 호출·운영 변경 없음. 토큰 형태 0(`token-check.txt`, 재시도 파일 포함).
- **연결 종료 2건:** 첫 실행에서 `server-files.txt`(서버 ls·sha256)·`error_lines.txt`가 `Connection closed by … port 1822`로 비었다. 원래 파일은 그대로 두고 **같은 두 읽기 명령만** 다시 실행해 `server-files-retry.txt`·`error_lines-retry.txt`에 받았다.
- 서버 해시 대조: 보고 `.txt`·`.msg`·`.raw`·`last_send` **4/4 일치**(`server-files-retry.txt` 대 `local-sha256.txt`). `error_lines-retry.txt` sha256 `3d10e35b…`가 서버가 계산한 값(`install_state.txt` 1행)과 같다.

| 항목 | 값 |
|---|---|
| 보고 | 🔴 이상 1건 = **미분류 ERROR 1**(CryptoPanicSource 수집 실패 `HTTP connect timed out`, 로그 08:33:56 UTC = 17:33 KST). 🟡 66세션 적재 뒤처짐 12회 전부 KRW-BLAST. 22:00:04 전송 성공 |
| 실자금 | SID 203 밖 실자금 보유0·미결0·정리 대기0·격리 대상0·구간 실자금 매수0 |
| SID 203 | CREATED · 20,200.00 · 보유0 · 미결0 · 정산 확인 불가0 · 미해결 정산 불일치0 |
| BTC H1 | B1 true · 최신 완료 봉 21:00 · B3 20/20 |
| 뉴스 수집 | 완료84 · 미완료24(cryptopanic HTTP 403 23 · 연결 시간 초과1). Connection reset 0 |
| Hikari·N17/N19/N20 | 0 |

설치 상태(`install_state.txt`): HEAD b5cd1d0d · backend 이미지 52e12428 · 재시작 0 · 기동 11-10 13:48 KST · INSTALLED_SHA c669ccdc · daily_check.sh ff0a5d7d · daily_checks.sql 653b22ba — 직전 회차와 같다. frontend `.Image` d825a9f5(FEIMGOLD와 같음), 재시작 0.

판단: ERROR 1건은 11-14 두 보고의 뉴스 미분류와 같은 수집 계열이며 원문 분류대로 둔다(실자금·주문 경로 아님). 휴대폰 수신은 운영자 확인 대상이다.
