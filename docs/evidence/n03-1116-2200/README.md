# N03 11-16 22:00 보고 읽기 — 늦은 읽기 (11-17 07:56~08:01 KST, 읽기만)

**예정 22:12 이후 읽기를 하지 못했다.** 개발 세션이 11-16 17:36경 멈췄고 11-17 07:56 재개됐다. 그 사이는 관측하지 않았다. 실제 읽은 시각: 서버 07:56:57(1차)~08:00:53 KST(6차).

[read.sh](read.sh)는 [n03-1116-1000/read.sh](../n03-1116-1000/read.sh)와 같은 파일이다. 재전송·서버 쓰기 없음.
- 서버 ssh 접속이 약 절반 확률로 인증 전 끊겼다(`Connection closed by … port 1822`, 07:56~08:05 관측). 1~5차는 일부만 받았다(`attempt1`~`attempt5` 보존: 연결 재사용 시도는 이 환경에서 동작 안 함, 4초 간격도 실패). 6차는 접속 단계 실패(종료 255)만 재시도하는 래퍼로 받았다. 원격 명령은 같다. 6차의 `observations.txt` 는 10회 재시도 모두 실패해 비어 있고, 같은 명령의 4차 결과(`attempt4/observations.txt`)를 쓴다.
- 로컬 `run-time.txt` 의 `TZ=Asia/Seoul date` 는 이 셸에서 UTC 로 찍혔다(22:56 = 07:56 KST). 서버 시각 줄이 기준이다.

## 결과

- 보고 [20261116-2200.txt](20261116-2200.txt): 22:00:03 전송 성공. 4파일 sha256 = 서버 값([server-files.txt](server-files.txt)·[local-sha256.txt](local-sha256.txt)). 토큰 패턴 0.
- 보고 머리: ⚪ 판정 불가 — 수집 실패 1건. 원문 분류:
  - ⚪ ERROR 로그 범위 미확보 / 🔴 구간 안 backend 기동 11-16 14:16 → **계획된 U3 backend 기동**(427차 v172run). 로그 범위가 14:16:13 이후뿐이라 10:00~14:16 이 집계 밖이다. log.gz 첫/끝 05:16:15Z~12:59:22Z.
  - frontend 기동 16:15 KST(07:15:16Z, 9d7f123a)는 UF2 로 **따로 분류**한다(이 보고의 backend 표시와 무관).
  - 🟡 KRW-BLAST 404·66세션 적재 뒤처짐 12회 = 거래지원 종료 마켓, 기존 형태.
- 설치 [install_state.txt](install_state.txt): crontab f2dce280…, INSTALLED_SHA c669ccdc…, HEAD **0e1d2a02**, backend **2c5d8427** 기동 11-16 14:16:14 KST·재시작 0. frontend [frontend-image.txt](frontend-image.txt) **9d7f123a** 기동 16:15:16·재시작 0.
- 보고 값: Flyway 96, SID203 밖 실자금 보유·미결·정리 대기 0, SID203 CREATED·20,200·주문 0, 66세션 RUNNING 63·지정 종료 3, BTC B1 true·B3 20/20.
- ERROR 3([error_lines.txt](error_lines.txt)) = 기동 직후 상장 폐지 마켓 404(BLAST·BONK·STORJ). CryptoPanic 403 ×15. 연결 누수·획득 실패 0, N17/N19/N20 0, DOCTYPE 0.
- 판정: 보고의 🔴·⚪ 는 계획 기동과 그 결과의 로그 범위로 설명된다. **새 P0 없음.**
