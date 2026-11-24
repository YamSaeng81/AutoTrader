# R1 판정 불가 원인 분리 — 원천·호출 경로 연결과 금액 없는 진단 읽기안 (총괄 v1.233 §3)

2026-11-24 Claude Code 로컬 작업. **운영 SSH·DB·계좌 호출 0회. 새 SQL은 PG에서 실행하지 않았다(문법·실행 미검증).** 이 문서는 운영 실행 승인이 아니다. 실제 읽기 1회 범위는 총괄이 따로 확정한다. U0 재시도·U1·U2 보류 유지. 원래 SQL·제품·관문·과거 체크섬은 바꾸지 않았다.

읽은 원천(바꾸지 않음): 고정 SQL `dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql`(sha256 ca6e7c8d…, 이 작업에서 다시 계산해 일치) · 제품 d81c3f2f(`d81c3f2f91c0…`, 작업 사본 `/d/tmp/final-lf-d81c3f2f` = 커밋, web-api 차이 0). 블롭: OrderRepository b72bda24 · RiskManagementService a127e0bd · DynamicTradingService 1cb4a824 · LiveTradingService 7f045519 · OrderExecutionEngine 063f74dd.

## 1. 요약

- **R1은 두 COUNT의 합이다.** 같은 포지션이 양쪽에 들어갈 수 있다(예: 대금 NULL 매도 → A ③ + P ② 동시). 그래서 9는 고유 포지션 9건으로 단정할 수 없다.
- **R1에는 모드 조인이 없다.** 다만 제품은 PAPER 동적 세션의 행을 `session_kind='DYN_PAPER'`로 따로 쓴다(V67, `DynamicTradingService.sessionKind`). `trading_mode`는 생성 때만 정해지고 바꾸는 호출이 없다(`setTradingMode` 호출 0). V67 전의 행은 기본값이 REAL이다. 따라서 **정상 경로로는 `DYNAMIC`에 PAPER가 섞이지 않는다.** 섞인다면 수동 DB 편집이나 세션 행 삭제(NO_ROW)뿐이다. 이것도 가설이므로 읽기로 확인한다.
- **영향 범위:** DYNAMIC R1>0이면 **LIVE 신규 실자금 진입도 거절된다**(§3). 운영 중인 0e1d2a02에는 이 관문이 없다(`git grep` 0건). d81c3f2f를 배포하는 즉시 생긴다.
- **원천 결측의 유력 가설(미확인):** V81(10-07 작성, 반영 대금·수수료 열)과 V87(10-13 작성, `sell_applied_at`)은 기존 행을 채우지 않는다(UPDATE 없음). 그래서 반영 열이 생기기 전에 매수하고 그 뒤에 확정 매도된 포지션은 ①로 세어질 수 있다. 진단안의 LEG 행이 이것을 보조 지표로 낸다.

## 2. R1 두 분기 대조표 (§3-1)

| 항목 | (A) 미확정 매도 주문 | (P) 미확정 CLOSED 포지션 |
|---|---|---|
| 세는 단위 | 매도 주문 행(`"order"`) | 포지션 행(`position`) |
| 종류·세션 | `session_kind = :kind`, `session_id IS NOT NULL` | 같음 |
| 시간 기준 | `sell_applied_at >= since` (확정 매도 시각) | `closed_at >= since` (청산 시각) |
| since | 제품: 앱 `Instant.now() − 30일` · 고정 SQL·진단: DB `now() − 30일` | 같음 |
| 사유 | ③ 매도 대금·수수료·수량 NULL 또는 수량≤0 (`SELL_FIELDS_KNOWN` 부정, OrderRepository:496) · ① 그 포지션의 `BUY_COST_BY_POSITION`(:516) 행 없음: 체결 BUY 없음 / 체결 BUY 대금·수수료 NULL / `position_id` NULL | ② 체결 매수 수량 합 > 확정 매도 수량 합(대금 NULL인 확정 매도는 합에서 빠짐) · ④ 체결 BUY·확정 SELL이 모두 없고 무거래 확정도 아님 |
| 무거래 예외 | 해당 없음 | `CONFIRMED_NO_TRADE`(:507): `ENTRY_REJECTED`·size 0·주문 0 **또는** BUY가 모두 체결 0 확정(FAILED, 또는 CANCELLED이면서 fee_source NULL이거나 취소 확정)이고 확정 SELL 없음 |
| REAL/PAPER 필터 | **없음** (`session_kind`로만 구분) | **없음** |
| 서로 겹침 | 같은 포지션이 A·P에 함께 들어갈 수 있다. 한 포지션의 매도 여러 건은 A에서 여러 번 센다 | 포지션당 1회 |
| 제품 위치 | `countRealSessionCostUndeterminedSince` OrderRepository:552 (첫 COUNT) | 같은 식의 둘째 COUNT |

R3(세션 연속 손실 입력)은 `dynamic_session.trading_mode='REAL'`·RUNNING·`started_at` 뒤로 범위를 좁힌다. R1은 그렇지 않다. 30일 안이면 STOPPED 세션이나 행이 없는 세션도 포함된다.

## 3. 신규 진입에서 판정 불가 검사까지의 호출 경로 (§3-1)

`RiskManagementService`(d81c3f2f):

- `checkRisk()` :163 → ① `costUndeterminedReason("기존 보호식","LIVE")` :171 → 기존 RiskEngine 한도 → 통과하면 `checkAddedRealLossFormulas` :205 → ② `costUndeterminedReason("DYNAMIC REAL 보호식","DYNAMIC")` :255
- `checkRealLossGuard()` :216 → ① LIVE :223 → 기존 손실 한도 → `checkAddedRealLossFormulas` :235 → ② DYNAMIC :255

| 진입 경로 | 호출 | LIVE R1>0 | DYNAMIC R1>0 |
|---|---|---|---|
| LIVE 세션 즉시 매수 (`evaluateAndExecuteSession`) | LiveTradingService:1388 `checkRisk()` | 거절 | **거절**(기존 한도 통과 시 ②에서) |
| LIVE 회차 승인 시점 관문 (`approvalTimeGate`) | LiveTradingService:1518 `checkRisk()` | 거절 | **거절** |
| 비세션 BUY (`positionId == null`) | OrderExecutionEngine:811 `checkRisk()` | 거절 | **거절** |
| DYNAMIC REAL 매수 (`executeBuy`, 즉시·회차 공통) | DynamicTradingService:1716 `checkRealLossGuard()` (`!isPaper()` :1715) | 거절 | 거절 |
| DYNAMIC PAPER 매수 | 호출 없음(:1715 분기 밖) | 영향 없음 | 영향 없음 |
| 매도·청산 | 관문 없음(OrderExecutionEngine:805 주석: SELL은 항상 허용) | 영향 없음 | 영향 없음 |

→ **"LIVE 0"만으로 고정 LIVE 시작에 영향이 없다고 볼 수 없다.** 현재 DYNAMIC 9가 남은 채 d81c3f2f를 배포하면 LIVE·DYNAMIC 신규 실자금 매수가 모두 거절된다(보유·청산·정지에는 영향 없음, OrderRepository:533 주석). 다만 기존 한도에서 먼저 거절되면 ② 문구는 나오지 않는다.

## 4. 금액 없는 최소 진단 읽기안 r1-diag-1 (§3-2)

파일: [r1-diag-1.sql](r1-diag-1.sql). 해시는 [SHA256SUMS](SHA256SUMS)에 있다. 출력 행의 정의는 SQL 머리 주석에 있다.

- **한 스냅숏:** 첫 DO 블록이 보호값 15s/2s를 확인한다(다르면 예외, psql rc 3) → `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` → META(now·clock·since·Flyway·격리·보호값) → REF(V81·V87·V94 설치 시각, 원값과 해석값) → ORIG → 분해 1문장 → END → `ROLLBACK`. 임시 테이블과 쓰기 문장은 없다.
- **ORIG = 고정 SQL R1 식 그대로.** 기계 대조 결과 2,179자가 일치한다(식 sha256 앞 16자 ba068e88dc8c8362). **SPLIT**은 같은 조건으로 A·P를 따로 센다. ORIG ≠ A+P이면 재현 실패로 보고 멈춘다.
- **원래 건수와 사유별 건수를 구분한다:** AX·PX는 배타 분류라 합이 원래 건수와 같다. AF·PF는 중복 가능한 표지라 합을 내지 않는다.
- **모드:** AM·PM으로 REAL/PAPER/NO_ROW/OTHER를 낸다. DYNAMIC은 `dynamic_session.trading_mode`, LIVE는 `live_trading_session.session_type` 기준이다.
- **중복:** UQ에 매도 행 수, 고유 포지션 수, `position_id` NULL 매도 수, P 수, 양쪽 겹침 수, 고유 포지션 합, 겹침을 뺀 대상 수를 낸다.
- **자연 만료:** AGE는 30일 창에서 빠지기까지 남은 일수를 구간별로 센다(D30P = 시각이 now() 이후). 이 읽기의 합이 9와 달라도 옛 10:30 실행을 재현했다고 주장하지 않는다. 시간이 지나 창에서 빠진 것인지 새로 생긴 것인지는 AGE와 META 시각으로 구분한다.
- **출력 제한:** 수량·대금·수수료·가격·저장손익·전략 파라미터·인증정보는 값을 출력하지 않는다(NULL 여부와 비교 결과만). ID와 행 단위 출력도 없다. `exit_reason`은 코드 앞 40자 그룹별 건수만 낸다. paper_trading 스키마와 522~587 모의 테이블은 조회하지 않는다.

### 호출안 (기존 a1-read.sh 검토 계약을 그대로 따르며, 새 실행 틀은 만들지 않는다)

a1-read.sh(`a1-fake-boundary-20261123/prod/approval/`)의 검토된 계약을 그대로 쓰고 아래만 바꾼다. 승인 전에는 실행기 사본도 만들지 않는다. 승인 범위가 정해지면 그 문서에 차이와 해시를 고정한다.

| 항목 | 값 |
|---|---|
| 원격 호출 | 정확히 2회: (1) 서버 UTC·HEAD·backend 이미지/상태/재시작 읽기 (2) r1-diag-1.sql 1회 |
| 핀 | HEAD `0e1d2a021ab2…` · backend Image `sha256:2c5d8427…` · running · RestartCount 0 · Flyway 96(META). 하나라도 다르면 SQL 호출 전 STOP_PIN 3 |
| SQL 호출 | `ssh -o BatchMode=yes cat-prod "cd ~/crypto_trader/AutoTrader && docker compose -f docker-compose.prod.yml exec -T -e PGOPTIONS='-c statement_timeout=15s -c lock_timeout=2s' db psql -X -q -v ON_ERROR_STOP=1 -U trader -d crypto_auto_trader -f -" < r1-diag-1.sql` (해시를 확인한 로컬 사본을 표준 입력으로 보낸다. 서버에 파일을 쓰지 않는다) |
| 1회 범위 | 보존 폴더 `docs/evidence/r1-diag-read-YYYYMMDD-HHMM`이 없어야 하고, 다른 `r1-diag-read-*`가 있으면 거절(원격 0회). NOT_AFTER(승인 창 끝 UTC) 이후면 거절 |
| 기록 | 미완료 표지(맨 처음) → 각 출력·표준오류·rc → run-time → 해시 목록 → read-status(맨 마지막). 모든 기록이 성공한 뒤에만 표지를 지운다. 기록 실패 시 종료 4, 자동 재시도 없음 |
| 형태 확인 | 로컬 [check/shape_check.py](check/shape_check.py): META 첫 줄(r1-diag-1·repeatable read·15s·2s)과 END 끝 줄, 허용 태그만, ORIG=A+P, 배타 분류 합, 모드 합, AGE 합, UQ 산식. 어긋나면 STOP_SQL_SHAPE 7. rc0인데 표준오류가 있으면 STOP_SQL_STDERR 5 |
| 반출 | 개수·시각·코드 그룹뿐이라 출력 전체를 로컬에 보존한다. 실패 시 psql 오류 문구도 보존한다(금액 열을 출력하는 문장이 없다) |
| 종료값 | 0 READ_OK · 2 시작 전 거절 · 3 STOP_PIN / psql ON_ERROR_STOP(R1D_GUARD 포함) · 5 STDERR · 7 SHAPE · 255 SSH · 4 기록 실패 |
| 해석 | 보고만 한다. 행 삭제, session_id·exit_reason·모드 수정, 원천 추가, 관문 완화의 근거로 자동 사용하지 않는다(v1.233 §4) |

## 5. 로컬 확인 (§3-3) — 파일 입력 대조만

[check/](check/): 실데이터가 아닌 합성 입력 [fixture.json](check/fixture.json)을 [model.py](check/model.py)에 넣었다. model.py는 제품 원식과 분해 정의를 파이썬으로 옮긴 것이다.

- 입력 사례: 같은 포지션 양쪽 중복(대금 NULL 매도) · 매수 수수료 NULL(①) · `position_id` NULL 매도 · `DYNAMIC`인데 세션이 PAPER(혼입 모사) · 세션 행 없음 · 주문 없는 청산 · FAILED 무거래(제외) · ENTRY_REJECTED size 0(제외) / size≠0(포함) · 취소 미확정 · 체결 수량 NULL · 창 밖 30일 초과(제외) · DYN_PAPER(제외) · OPEN(제외) · LIVE 정상(0).
- 결과: [model-out.tsv](check/model-out.tsv) ORIG DYNAMIC 9 = A3 + P6, 고유 포지션 7·겹침 1·대상 8. 손계산 기대 [expected.tsv](check/expected.tsv) 14줄이 모두 일치한다. `shape_check` SHAPE_OK.
- 변형 검출: [mutation-results.txt](check/mutation-results.txt). 정상 2건(SQL 출력 모양 모사 + 모델 출력)은 rc0이다. 변형 13건(보호값 14s·read committed·END 누락·ORIG≠SPLIT·모드 합·PAPER 숨김·BOTH≠0·UQ·AGE·PE·허용 밖 태그·빈 출력·정수 아님)은 모두 rc7로 검출됐다.
- **한계:** 이것은 SQL 자체가 아니라 같은 정의를 옮긴 모델의 대조다. **r1-diag-1.sql의 문법과 실제 PG 결과는 검증하지 않았다**(v1.233 §3-3에 따라 PG·제품 회귀·jar·가짜 SSH는 실행하지 않음). 실제 읽기 전에 로컬 PG 문법·스냅숏 확인 1회를 허용할지 총괄이 판단해야 한다. 기계 확인은 ORIG 식 글자 일치와 쓰기 문장 0뿐이다.

## 6. 진단 뒤 판정 연결 (v1.233 §4, 미판정)

| 읽기 결과 | 뜻 | 다음 판정 |
|---|---|---|
| PM/AM 대부분 REAL, AX COST_ONLY·AF C_BUY_COST_NULL, LEG before_v81 많음 | 반영 열이 생기기 전 매수의 원천 결측 | 제품 보완(예외 정의) 대 배포 전 기준 변경(자연 만료 대기)을 각각 판정 |
| PX QTY_SHORT·PF Q_NO_SELL_ORDER / exit_reason 추정 청산 | 팬텀·추정 청산 | 기존 정의상 판정 불가가 맞다. 해제 경로와 만료 시각을 AGE로 판단 |
| PX NO_SOURCE·PF N_* | 무거래 예외 누락 또는 주문 원천 없는 청산 | 무거래 확정 정의의 누락인지 실제 원천 결측인지 판정 |
| PM/AM PAPER·NO_ROW > 0 | 비정상 혼입 또는 세션 행 삭제 | 제품 SQL에 모드 조인이 필요한지는 원인을 확정한 뒤 판정. SQL만 고쳐 해결로 보지 않는다 |

어떤 경우에도 10:30 rc1을 통과로 고쳐 쓰지 않는다. ok-U0를 만들지 않고, 과거 행도 고치지 않는다.

## 7. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더, 자신 제외).
