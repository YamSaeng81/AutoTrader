# v1.168 야간 작업 체크포인트 (개발 세션, 11-15~16 KST)

후보 가지 `candidate/order-poll-race-b987d7a7`(작업 사본 /d/tmp/race-b987d7a7). 99e6ab10 이후 커밋은 전부 시험만.

| 커밋 | 내용 | 검증 |
|---|---|---|
| be7e8e4f | 외부 연결 추적(TEST_NET_TRACE) + WS 대역 3클래스 | n03 82/82·n04 16/16 차단0, full-diag 종료값0·차단0 |
| 57867533 | 사례 H(수동조정 뒤 늦은 응답) | h01 H2 13/13, h02 PG 16/16, h03 음성 2/2 실패 |
| ac1ef49d | useAlignedRound + S04a 정시 회차 | t01 12/12, t02 하위12클래스 81/81(창 밖). 창 안 검증: probe/loop-fix.sh(runs/f-*) |
| 3f2cce80 | AlignedRoundBoundaryProbeTest(결정적 경계) | t03 1/1 |

| 진행 | 상태 | 경로 |
|---|---|---|
| 벽시계 탐침 v1(9클래스) 20:50~21:03 | 완료: S04a [:53,:01) 12회 실패, N15 1회(20:50:51, 비재현) | probe/runs/p-1115-2050~2102, index.txt |
| 벽시계 탐침 v2(보완 전 be7e8e4f, 20클래스) | 21:50~ 매시 창, 08:05까지 | probe/loop.sh, classes.txt |
| 보완 사본 탐침(ac1ef49d, S04a·A04a 2클래스) | 21:50~ 매시 창 | probe/loop-fix.sh, index-fix.txt |
| 남음 | A04a 등 창 판정 → 필요 시 보완 → 창 안 검증 → 최종 SHA 전체 회귀 1회 → jar 재빌드 JARM → V168D4(d4/mkdraft9.py) | |
| 운영 읽기 | 22:00 N03(read.sh), 08:00~08:15 1회 | |
