# 야간 v1.152 체크포인트 (세션 중단 시 재개용, 진행하며 갱신)

| 단계 | 상태 | 후보 SHA / 출처 | 근거 경로 |
|---|---|---|---|
| B S5 성과 스냅샷 | 후보 커밋·push | 560d13c5 (기점 19dbad3a) | s5/ |

- 격리 PG 컨테이너: `v152-pg` (timescale/timescaledb:latest-pg15, PostgreSQL 15.13, 127.0.0.1:15437, 신뢰 인증). 작업 끝에 이 컨테이너만 지운다.
- 1회차 PG 실패: postgres:15 / 15-alpine 이미지로 띄워 V1 의 create_hypertable 없음(s5/pg-run1-failed-no-timescale). postgres:15 이미지는 이 작업에서 받았고 바로 지웠다(15-alpine 은 원래 있던 이미지).
