# 12월 준비 23 — 등록 세션 2단계 읽기 검토안 (총괄 v1.203 §3-3) — 실행 금지

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). [등록표 v3 §5](../dec-registry-v3-p7v6-20261120/README.md)의 허용 열 목록을 실행 가능한 형태로 구체화한 **검토안**이다. [read.sql](read.sql)·[parse.py](parse.py)는 어떤 DB에도 실행하지 않았다. 운영 SQL·계좌 조회·수집기도 실행하거나 설치하지 않았다. [tests.py](tests.py)는 가상 출력 파일만 쓴다. **8/8 통과**([run1](run1/tests.out.txt)).

## 1. 두 단계 한 스냅숏

1. psql `-X -v ON_ERROR_STOP=1`로 실행한다. 변수 `live_ids`, `dyn_ids`는 해시 고정 등록표 v3에서 만들고, `window_from`은 기준 관측의 거래소 앞 읽기 시작이다.
2. `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` 한 트랜잭션에서 META(`now()` 트랜잭션 시작, `clock_timestamp()`, `pg_current_snapshot()`, flyway 버전, 격리 수준)를 먼저 기록한다. 이후 모든 행은 같은 스냅숏에서 읽는다.
3. **1단계에서는 금액 없는 식별 목록만 읽는다.** 대상은 실자금 세션(LIVE 전체, DYNAMIC 중 `trading_mode`가 정확히 'PAPER'가 아닌 것), 진행 중이거나 창 안에서 생성된 주문, CLOSED가 아닌 포지션이다. 주문에는 소유 상태 `OK / NO_SESSION_ID / NO_SESSION_ROW / UNKNOWN_KIND`를 붙여 **소유 세션 없는 주문도 빠뜨리지 않는다.**
4. **게이트:** 등록 밖 RUNNING 세션, 등록 밖 주문, 등록 밖 포지션이 하나라도 있으면 `GATE STOP`을 출력하고 2단계 없이 ROLLBACK한다. 이때 금액 열은 추가로 열람하지 않는다.
5. 2단계에서는 등록 (kind,id)의 금액 열만 읽는다(§2). 마지막에 END(`clock_timestamp()`)를 출력하고 ROLLBACK한다.

PAPER 세션(사전 등록 522~587 포함)은 `paper_trading` 스키마에 있으며 읽지 않는다. 키는 항상 kind+id로 구분하고 id 숫자만으로 제외하지 않는다. DYNAMIC PAPER 행은 조건으로 제외하며 금액·성과 열은 열람하지 않는다.

## 2. 스키마 대응(d81c3f2f = 운영 0e1d2a02, 관련 엔티티 열 이름 동일 — 차이는 `ExitReason.java`뿐)

| 초안 표기 | 실제 |
|---|---|
| `mode/trading_mode` | `live_trading_session`에는 모드 열이 없다(LIVE = REAL로 출력). `dynamic_session.trading_mode`를 쓴다. |
| 주문 테이블 | `"order"`(따옴표 필요). `session_id`는 null 가능, `session_kind`는 not null. |
| `reported_*`/`applied_*` | `reported_executed_funds, reported_paid_fee, applied_executed_funds, applied_paid_fee`. `reported_observed_at`도 읽지만 정정 때 갱신되지 않으므로 불변 근거로 쓰지 않는다(성적표 원천 v3 S2). |
| 포지션 | `position`: `size, status, invested_krw, position_fee, ruleset_hash, opened_at, closing_at, closed_at` |
| 미해결 정산 차이 | `order_settlement_discrepancy` 중 `resolved_at IS NULL`, 등록 세션 주문만 |

읽지 않는 열은 `response_json, signal_reason, failed_reason, strategy_params, watchlist_json`과 API 키다.

## 3. 출력 형식·종료값·보존

- **출력:** 탭 구분, null은 `\N`. 레코드 순서는 META → ID_S → ID_O → ID_P → GATE → S → O → P → D → END이며, 종류별 열 수는 [parse.py](parse.py) `COLS`에 있다. 숫자는 문자열 그대로, 불리언은 `t/f`, 시각은 시간대를 포함한다.
- **parse.py 종료값:** 0은 2단계 조각 생성, 10은 게이트 STOP(등록 밖 ID 목록만 남김), 20은 psql rc≠0(부분 출력을 값으로 쓰지 않음), 30은 형식 결함이다. 형식 결함에는 열 수, 순서, 숫자·시각 형식, META/GATE/END 누락, GO인데 식별 목록에 등록 밖 ID가 있는 경우, STOP인데 2단계 행이 있는 경우, 2단계 행이 등록 밖인 경우가 포함된다.
- **보존:** 회차 폴더에 stdout·stderr 원문, psql rc, 변수 파일, read.sql·parse.py sha256, 원문 sha256(조각에 기록)을 남긴다. 실패 원문도 지우지 않는다.

## 4. 축 관계 자료(P7 v5 `p7-clock-1`)

DB 단계 앞뒤로 수집기 벽시계와 단조 시계를 기록한다. 표본은 `{pair:['db','collector'], c0, d=clock_timestamp(), c1, mono_rtt_ms}`이다. 거래소 단계는 별도 단계(exchange 축)이며 DB 스냅숏과 동시라고 쓰지 않는다. 거래소 시계 표본이 없으면 P7이 그 비교를 보류한다. 앱 축(`created_at`)과 DB 축은 같은 시계라는 근거가 확인되기 전까지 같은 것으로 선언하지 않는다.

DB 지문(`db_fp_pre/post`)은 이 트랜잭션 바로 앞뒤의 별도 autocommit 읽기에서 같은 2단계 행 md5로 구한다. 스냅숏 안의 md5는 구성상 항상 같으므로 안정 근거로 쓰지 않는다.

## 5. 남은 결정

실제 실행 승인, 수집기 위치, 거래소 시계 표본의 원천, 값별 불변 근거로 쓸 읽기(성적표 원천 v3)는 정해지지 않았다. 체크섬은 [SHA256SUMS](SHA256SUMS)에 있다.
