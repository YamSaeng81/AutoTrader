# A/B 생성 도구 v4.1 — 전체 목록 GET 을 열 제한 DB 메타 조회로 (총괄 v1.177 D1·§3-1·§3-2, 2026-11-16~17)

**실제 생성 승인 전 도구다.** 운영 서버·운영 DB·실제 토큰에 연결하지 않았다. v4 원문(`ab-create-v4-20261111/`)은 바꾸지 않았다.

## 1. 바꾼 것

| 도구 | v4 | v4.1 |
|---|---|---|
| `create/ab_create.py` (`eb8cc5ab…`) | 사전·사후 `GET /api/v1/paper-trading/sessions` 전체 목록(522~587 성과 필드 포함 응답) | 목록 요청 **없음**. HTTP 클라이언트는 생성 POST 외 요청을 보내면 예외(구조적 금지). 사전 `meta_preflight.sql`·사후 `meta_after.sql`를 `--db-cmd` psql 로 실행 |
| `create/meta_preflight.sql` (`8009407d…`) | — | 읽기 전용·ROLLBACK. 열: id·상태·전략·코인·주기·초기자본·strategy_params·started_at. 522~587 행 제외, 전체 RUNNING 수·max_id 는 집계만 |
| `create/meta_after.sql` (`1f118109…`) | — | `id > 사전 max_id` 새 행만(동시 다른 생성 포함), 522~587 조건 제외 |
| 생성기 판정 | 새 ID = 목록에 없던 ID | + 사전 max_id 초과·522~587 아님. RUNNING + 8 > `--session-cap`(120)이면 보내지 않음. 비밀번호 문자열이 든 `--db-cmd` 거절 |
| 초안 | `api_stored_started_at`(API 목록 값), `api_reconcile_ok` | `db_meta_started_at`(DB 메타 조회 값), `reconcile_ok`, `reconcile_source`·`db_source`·`preflight_max_id`·`meta_after_sha256`, `checks.other_new_ids` |
| `create/ab_complete.py` (`929ab28b…`) | v4 초안 | v4.1 초안만. 판 확인을 맨 앞에. 초안에 API 출처 키가 섞이면 거절. psql started_at == db_meta_started_at 나노초 정확 대조. 완성본에 생성기 조회 출처·확인 SQL 출처 둘 다 |
| `tool/ab_arms.py` (`1d278d57…`) | 완성본 v4 | 완성본 v4.1(시각 근거 키만 바뀜), 규칙 같음 |
| `chain_driver_v41.py` (`789e14ac…`) | — | v4 구동기에서 도구 경로·`--db-cmd`·`--db-source`만. `javaharness/chain_driver_v4.py`는 0e1d2a02 의 `AbChainV4PgTest`가 파일 이름을 고정해 둔 데 맞춘 실행 래퍼(동작 없음) |

확인 SQL(`post_creation_check.sql`)·추출 SQL·프로토콜(`8d787fe9…`)·집계기 v3 는 바이트가 같다.

## 2. 시험

| 시험 | 결과 | 원문 |
|---|---|---|
| 생성기 `create/test_ab_create_v41.py` — v3/v4 생성기 시험 22건 이식 + v4.1(상한·사전 조회 실패 4종·설정 거절 5종·max_id 이하 ID·522~587 ID·다른 DB 메타·동시 다른 생성·GET 구조 금지) | **31/31** (2회차) | `runs/test_ab_create_v41.run2.txt` |
| 1회차 | 30/31 — **시험 결함**: 950 대역 행을 P2 서명과 같게 만들어 사전 중복으로 먼저 멈춤(rc2). 서명 다른 행으로 고침. 1회차 출력은 파일로 남기지 않았다 | — |
| 완성기·arms `tool/test_ab_v41.py` — v4 21건 이식 + 출처 혼합 5건 | **26/26** (2회차) | `runs/test_ab_v41.run1.txt`(25/26), `runs/test_ab_v41.run2.txt` |
| 1회차 | v4 초안 거절은 됐으나(종료 3·파일 0) 사유가 판 확인이 아닌 대조 키 누락이었다 → 판 확인을 맨 앞으로(도구 수정) | `runs/test_ab_v41.run1.txt` |
| 실제 SQL — 격리 v152-pg `v41meta`(V96 시험 DB 사본, 530·587 같은 서명 RUNNING 섞음) | 사전: 행 588만·RUNNING 4·max_id 601·중복 P1A/P1B. 사후(max 586): 588·600·601. 변수 없음 rc3. 다른 DB·psql 없음 고정 실패. 1µs 정밀도 거절 | `runs/meta-sql-pg/` |
| **실제 HTTP·PG 연결** — 0e1d2a02 작업 사본의 `AbChainV4PgTest`(시험 코드 불변) + v4.1 구동기 | seed-other(`ab41a`) 1/1, main(`ab41b`, 다른 DB `ab41a`) **1/1**: 생성 8 → 지문 → psql 확인 → 완성 → arms → 추출 → 집계 rc0. 음성 1µs·다른 DB·지문 없음·복수·T0 전 거래·저장 비율 **모두 3**, 추가 POST 0 | `runs/pgchain/run-a-seed`, `runs/pgchain/run-b-main` |
| 연결 실패 회차(보존) | run0: 구동기 파일 이름 검사로 서버 기동 전 실패 → 래퍼 추가. run-a-seed-early-window: 실제 시각 C+5 라 생성기 rc2(보낸 것 없음) | `runs/pgchain/run0-driver-name-rejected`, `runs/pgchain/run-a-seed-early-window` |

- 실제 초안(run-b): POST `23:07:05.586068100Z` → DB 메타 `23:07:05.586068+00`, 8개 정밀도 계약 통과, `reconcile_source`=DB 메타 조회, `preflight_max_id`=1, 기록부 preflight·preflight_meta·intent 8·result 8·reconcile 1.
- 실제 서버 쪽 요청 수는 세지 않았다. 목록 GET 0 의 근거는 클라이언트 구조 금지(시험 `Structural`)와 대역 `list_gets==0` 이다.
- `local-test-token.txt` 는 시험 프로필 로컬 값(`test-api-token`)이며 운영 비밀값이 아니다.

## 3. 남은 것(미완료)

- SMA20 단건 도구(D3), 사전 봉 질의 보완(B1·결측·B3·최소 봉), SMA20 생성→첫 09:00 고정 시계 경로, 차트 대비 후보, 최종 고정 묶음은 **아직 하지 않았다**(세션이 11-16 17:36경~11-17 07:56 멈춤). [현재 인계](../../CURRENT_HANDOFF.md) 참조.
- 확인된 설계 근거(코드 읽기, 시험 전): SMA20 은 마지막 닫힌 H1 봉이 23:00 UTC 봉일 때만 신호를 내므로(`DailySmaTrendStrategy`), 생성이 09:00~09:59 KST 에 걸리면 첫 평가에서 T0 전 진입이 가능하다. 그 밖 시각의 생성은 첫 09:00 이후 첫 평가 전까지 HOLD 다. 판단 시계는 `EntryClock`(봉 조회·신선도·닫힌 봉)으로 고정 시계 시험이 가능하다.
