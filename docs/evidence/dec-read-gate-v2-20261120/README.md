# 12월 준비 24 — 읽기 게이트 v2(R1~R3) 격리 로컬 PG 검증 (총괄 v1.204 §3-1) — 운영 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [v1.204 지시](../review-43d729db/README.md) §2 R1~R3을 [앞 검토안](../dec-read-plan-20261120/README.md)의 새 사본에서 고쳤다. 앞 판 read.sql·parse.py는 보존한다. **운영 DB·제품 PG·애플리케이션·빌드·회귀는 실행하지 않았다.** 허용된 격리 로컬 PostgreSQL 검증만 했다: **11/11 통과**([run2](run2/tests_pg.out.txt)).

## 1. 바뀐 계약

| 공백 | v2 처리 |
|---|---|
| R1 NULL 소유자 | 주문·포지션 소유를 CASE로 먼저 `NO_SESSION_ID / UNKNOWN_KIND / NO_SESSION_ROW / DYN_PAPER / REG / UNREG / UNCLASSIFIED`로 확정한다. 비교식에 NULL이 남지 않으며 ELSE는 이상이다. 소유 이상은 **시각·상태와 관계없이** SQL 게이트 STOP이다. 게이트는 `count(*)=0`만 쓴다. |
| R2 모드 누출 | 등록 (kind,id)마다 같은 스냅숏에서 존재·모드를 대조한다(`ID_R`: `REG_OK / REG_MISSING / REG_MODE`). DYNAMIC은 `trading_mode='REAL'`과 정확히 같아야 한다. 하나라도 아니거나 등록표가 비면 2단계 전에 STOP한다. 2단계 세션 조회에도 `trading_mode='REAL'`을 다시 건다. |
| R3 조회 범위 | `window_from`을 없앴다. 2단계는 등록 세션의 주문·포지션(CLOSED 포함)과 미해결 정산 차이를 **시간 필터 없이** 허용 열로 모두 읽는다. 1단계 등록 밖 판정도 생성 시각을 쓰지 않는다. 등록 밖 주문은 진행 중·정산 미확정(`fill_accounting_pending` 또는 `fee_source` 있는 `settlement_final=false`)·미해결 차이·비CLOSED 포지션 연결 중 하나면 STOP이고, 그 밖은 금액 없이 목록에만 남긴다. |
| 파서 | 추가 방어다. 등록표 sha256·id 배열을 META와 대조하고, ID 목록의 STOP 표시를 다시 세어 SQL `GATE_N`과 맞춘다. STOP인데 2단계 행이 있거나, GO인데 등록 밖 행·모드 불일치·`CNT` 행 수 불일치가 있으면 30이다. 등록 주문이 등록 밖 포지션을 가리키면 `cross_links`에 보존한다. |

종료값은 앞 판과 같다(0 GO, 10 STOP, 20 psql 실패, 30 형식 결함·불일치). 레코드 순서는 `META → ID_R → ID_S → ID_O → ID_P → GATE_N → GATE → S → O → P → D → CNT → END`이다. 열 수는 [parse.py](parse.py) `COLS`에 있다.

**운영 영향 주의:** 소유 이상을 시각과 관계없이 거절하므로, 운영에 과거 비세션 주문(V52 주석의 `position_id` 없는 주문 등)이 남아 있으면 게이트는 계속 STOP한다. 이것은 의도한 보수 처리다. 그때는 STOP 출력의 금액 없는 목록으로 예외 등록을 따로 판단한다. 운영 실제 분포는 읽지 않았으므로 모른다.

## 2. 격리 로컬 PG 검증

- **환경:** 일회용 도커 컨테이너 `timescale/timescaledb:latest-pg15`(운영 compose와 같은 태그, 서버 15.13), `--network none`, 데이터는 tmpfs다. d81c3f2f 마이그레이션 V1~V96(95개)을 `base`에 적용했다. `flyway_schema_history`는 `(version, success)` 최소 표로 대신했다. 사례마다 `base`를 템플릿으로 새 DB를 만들었다. 검증 뒤 컨테이너를 삭제했다. 기존 제품 PG(ks-postgres 등)는 건드리지 않았다.
- **사례:** A 정상 GO(과거 생성 매수12·창 밖 생성/창 안 정산 매도13·CLOSED 포지션8 포함, PAPER950·등록 밖 STOPPED100 금액 없음), B NULL 소유자, C 소유 행 없음, D 알 수 없는 kind, E 미존재 등록 ID, F 등록된 DYNAMIC PAPER, G 등록 밖 RUNNING 세션, H 등록 밖 진행 주문, I 소유 없는 포지션, J 빈 등록표, K psql 실패다. **B~J는 모두 psql rc0·게이트 STOP·파서10·원문의 2단계 행0이다.** K는 psql rc3·파서20·2단계 행0이다.
- **v1 대조(결함 재현):** 앞 판 read.sql을 같은 픽스처에 돌렸다. B는 GO였다. 과거 NULL 소유 주문50은 창 필터 때문에 목록에도 나오지 않았고, 과거 매수12·매도13도 2단계에서 빠졌다. F는 GO였고 PAPER950 금액 `777777`이 출력됐다. 원문은 `run2/V1B`, `run2/V1F`에 있다.
- **시도 기록:** [run1](run1/setup-error.txt)은 사례 DB를 만들 때 TimescaleDB 백그라운드 작업자가 `base`에 접속해 있어 멈췄다. read.sql은 한 번도 실행되지 않았다. `base`를 `ALLOW_CONNECTIONS false`로 바꾸고 드라이버의 마이그레이션 수 조회 위치만 옮겨 run2로 마쳤다. read.sql·parse.py는 run1과 run2 사이에 바뀌지 않았다.
- **보존:** 사례별 폴더에 `fixture.sql`, `registry.json`, `vars.txt`, `stdout.tsv`, `stderr.txt`, `psql_rc.txt`, `parsed.json`, `parse_rc.txt`가 있다. 해시는 [SHA256SUMS](SHA256SUMS)에 있다.

## 3. 한계·남은 것

- 이 검증은 SQL·파서의 분기가 실제 PostgreSQL 3값 논리에서 맞는지 확인한다. 운영 데이터 분포, 운영 계정 권한, `statement_timeout`, 행 수 규모는 확인하지 않았다.
- `now()`는 트랜잭션 시작 시각이다. 스냅숏의 월말 정확 시각을 보증하지 않는다. 수집 구간과 DB clock 표본은 앞 판 §4대로 별도로 보존한다. 앱 축(`created_at`)과 DB 축은 같은 시계로 선언하지 않는다. v2는 시간 필터를 없애 이 축 비교를 게이트에서 쓰지 않는다.
- 실행 출처 고정 표(v1.204 §3-3: 등록표 해시·스키마 대응·행 범위·종료값·보존 경로)는 이 README §1~2와 [앞 판 §2](../dec-read-plan-20261120/README.md)에 나뉘어 있다. 성적표 S1·S2와 P7 P1·F3(v1.204 §3-2)은 이번 제출에 들어 있지 않다. 다음 작업이다.
- 실제 읽기 실행, 수집기 위치, 운영 DB·계좌 조회는 승인 전이다.
