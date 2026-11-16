# 11-17 아침 상태 읽기 (08:01:34 KST, 읽기만)

[read-morning.sh](read-morning.sh) 한 번(접속 단계 실패만 재시도하는 래퍼 사용, [n03-1116-2200](../n03-1116-2200/README.md) 참조). 서버 쓰기 없음. 운영 DB SQL 은 이번에 실행하지 않았다(비밀번호 요청 전) — **SID203·실자금 진행/불명 주문의 DB 직접 확인은 미확인**이다. 마지막 DB 값은 22:00 보고 값이다.

| 항목 | 결과 ([state.txt](state.txt)) |
|---|---|
| 설치 | crontab f2dce280…, INSTALLED_SHA c669ccdc…, HEAD 0e1d2a02 |
| backend | 2c5d8427, 기동 11-16 14:16:14 KST, 재시작 0, running |
| frontend | 9d7f123a, 기동 11-16 16:15:16 KST, 재시작 0, running |
| db | b2618032, 기동 10-21, 재시작 0 |
| 22:00 이후 backend 로그 ([logs-since-2200.txt](logs-since-2200.txt)) | 13:00:22Z~23:01:46Z 18,410줄. ERROR 21. 연결 누수·획득 실패 0, OOM 0, 재기동 0, 캔들 판정 불가 0, N17/N19/N20 0. 뉴스 미완료 20(CryptoPanic 403 ×19·IOException 1) |

ERROR 21 중 기존 형태: KRW-BLAST 404 계열 12. **원인 미확인 통신 오류 9줄**([network-lines.txt](network-lines.txt)):

- 00:00:49~00:01:19 KST Upbit 캔들 `request timed out` 3 → KRW-IOTA·WAVES·CRO H1 동기화 실패 3
- 00:50:47 CryptoPanic `Connection reset`
- 04:26:31 텔레그램 `메시지 전송 중 오류(응답 미수신): Connection reset` — 그 메시지의 실제 수신 여부는 모른다
- 05:00:02 ClaudeProvider 요청 실패(REPORT_NARRATION, Connection reset)

같은 아침 07:56~08:05 개발 PC → 서버 ssh 접속도 약 절반이 인증 전 끊겼다. 서버 바깥 연결 불안정일 수 있으나 **원인은 확인하지 않았다.** 기존 P0 조건(연결 획득 실패·누수·재기동·OOM)은 0 이다. 운영 변경은 하지 않았고 이 읽기 뒤 예정된 운영 단계도 없다.
