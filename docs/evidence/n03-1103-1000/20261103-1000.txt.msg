[정기 점검] 🔴 이상 1건
실행 2026-11-03 10:00 KST · 구간 11-02 22:00 ~ (12시간) · 운영 ea44900 · 점검 스크립트 8435935e975c
🔴 미분류 ERROR 3건
🟡 외부 통신 실패 ERROR 6건
🟡 KRW-BLAST 보충 수집 실패 — 상세 원인 미확인 2건
🟡 검증 코인 KRW-BLAST 캔들 404 2건 — 거래지원 종료 확인된 마켓
🟡 66세션 경보: 적재 뒤처짐 12회 — 전부 거래지원 종료 확인 마켓(KRW-BLAST), 다른 코인 이상 없음
—
앱: running · 재시작 0 · 기동 2026-11-02T06:07:04.247782578Z · 이미지 618046540764
DB: Flyway 94 · SID 203 밖 실자금 보유 0 · 미결 0 · 정리 대기 0 · 격리 대상 0 · 66세션: 원래 66 중 RUNNING 63 · 지정 종료 STOPPED 3/3(576 577 578) · 구간 SID 203 밖 실자금 매수 0
SID 203: CREATED · 잔액 20200.00 · 보유 0 · 미결 0 · 구간 주문 매수 0 매도 0 · 정산 확인 불가 0 · 보고값≠반영값 0 · 미해결 정산 불일치(전체) 0
BTC H1 입력(관측 11-03 10:00:02 KST): B1 true · 최신 완료 봉 11-03 09:00 · 필요 11-03 08:00(2분 유예) · B3 최근 20일 마감 봉 20/20
텔레그램(구간): 성공 2 · 큐 진입 실패 0 · 마지막 성공 2026-11-03 04:35
ERROR(구간): 전체 13 · 공유 풀 미등록 0 · 캔들 판정 불가 0 · 상장 폐지 404 2 · 통신 실패 6 · 미분류 3
ERROR(원인 미확인 🟡): 보조 뉴스 수집 실패 0 · KRW-BLAST 보충 수집 실패 2
  · 14:25:51.216 [scheduler-4] ERROR c.c.api.news.source.RssNewsSource - [RssNewsSource] 수집 실패 — sourceId=tokenpost_rss: Connection reset
  · 15:25:56.841 [scheduler-1] ERROR c.c.a.news.source.UpbitNoticeSource - [UpbitNoticeSource] 수집 실패: Connection reset
  · 15:25:57.042 [scheduler-1] ERROR c.c.a.news.source.CryptoPanicSource - [CryptoPanicSource] 수집 실패: Connection reset
66세션: 🔴 적재 뒤처짐 12회