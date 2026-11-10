# 청산 A/B 기록부 — 틀(생성 전 채움, 결과 전 고정)

**아직 생성하지 않았다. 아래 칸은 실제 값을 읽은 뒤에만 채운다.**

| 항목 | 값 | 확인 방법 |
|---|---|---|
| 승인 근거(총괄 판정·시각) | | |
| 실행 코드 SHA / 이미지 ID | | 서버 HEAD·`docker inspect` 읽기 |
| 실행 모드(두 팔 같음) | 종전 / LIVE_ALIGNED | 요청 본문 |
| 생성 직전 전략 활성 | COMPOSITE_MEANREV_BB M15 / COMPOSITE_MTF_CONFIRMED M15 | `strategy_type_enabled`·`strategy_timeframe_enabled` 읽기 |
| 생성 직전 PAPER RUNNING 수 | | 읽기(성과 열 없음) |
| 세션 ID 8개(쌍·팔) | | 생성 응답 + DB 읽기 |
| 저장값 대조(자본·비율·상한·시간 청산·손절 하한·파라미터) | | DB 읽기, 쌍 안에서 하한만 달라야 함 |
| 규칙 지문(A·B) | | `ruleset_snapshot` |
| 공동 시작 시각 T0(첫 공통 평가 틱) | | 전략 로그 |
| 끝 시각 = T0 + 42일 | | 계산 |
| 일별 평가 시각 | T0 + n일, 닫힌 M15 종가 | 집계기 규칙 |
| 구간 표시(CODE_CHANGE / OUTAGE / COLLECTION_GAP) | | 배포 기록·장애 기록·수집 공백 |
| 종료 처리 | 끝 시각 순자산 평가, 끝 가격 없으면 판정 불가 | 집계기 규칙 |
