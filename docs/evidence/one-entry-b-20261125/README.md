# LIVE 1회 제한 B안 — 스키마 없는 별도 후보 구현·직접 시험 (총괄 v1.241 §3, 제출)

2026-11-25 KST, Claude Code 로컬 작업이다. **main 제품·D81 배포 후보에 합치지 않았다.** 운영 SSH·DB·계좌·서버 배포·실제 생성/start·주문은 0회다. 전체 회귀·jar·다른 PG 묶음도 돌리지 않았다.

## 1. 후보

| 항목 | 값 |
|---|---|
| 가지 | `candidate/live-one-entry-d81c3f2f`(원격 보존, 일반 push). 작업 사본 `/d/tmp/one-entry-d81c3f2f` |
| 기점 | d81c3f2f(D81 배포 후보 제품) |
| 커밋 | 6d690f5d 시험 → 98d9e9c1 시험 import 수정 → 90a9a674 시험기 보정 → **c43e1f1a 구현(최종)** |
| 제품 차이 | [product-vs-d81c3f2f.diff](product-vs-d81c3f2f.diff) — 4파일 +110줄, 삭제 0. Flyway·엔티티·지문(RulesetRegistry)·DYNAMIC·PAPER·매도/청산/정지 경로 변경 없음 |
| 시험 차이 | [tests-vs-d81c3f2f.diff](tests-vs-d81c3f2f.diff) — 새 시험 2개만 |

**구현(c43e1f1a):**
- `LiveEntryLimit`(새 클래스): `strategy_params.maxEntries`는 키가 없으면 제한 없음이다. 키가 있으면 **정수형 1만** 받는다(Integer·Long·Short·Byte·BigInteger).
- `LiveTradingService.createSession` **첫 줄**에서 잘못된 값을 `IllegalArgumentException`으로 거절한다. 계좌 동기화·자본 배정·전략 검사·저장보다 앞이다. 일괄 생성은 strategyParams를 넘기지 않아 영향이 없다.
- `CapitalReservationService.reserveForBuy` LIVE 분기 ①-3: **계좌 잠금·만료 확인 뒤, 등록 한도·차감·의도 전**에 판정한다.
  - 옵션이 있는 세션만 `count(*) FROM "order" WHERE session_kind='LIVE' AND session_id=? AND UPPER(side)='BUY'`(상태·기간 필터 없음)를 센다. 1 이상이면 `ENTRY_LIMIT_REACHED`다.
  - 저장 값이 1이 아니면(수동 편집 등) `ENTRY_LIMIT_INVALID`로 거절한다.
  - 조회 실패는 잡지 않고 그대로 올린다. 그러면 트랜잭션이 롤백되어 예약·의도가 생기지 않는다.
- 옵션 없는 LIVE 예약에는 세션 `findById`가 1회 늘 뿐이다(같은 트랜잭션의 ④도 같은 엔티티를 읽는다). 판정 결과는 바뀌지 않는다.

## 2. 같은 시험으로 기점과 후보 비교

시험: [LiveOneEntryLimitPgTest](tests-vs-d81c3f2f.diff)(격리 PG 9사례), [LiveOneEntryParamsTest](tests-vs-d81c3f2f.diff)(순수 3사례). 후보 전용 기호를 참조하지 않아 기점에서도 컴파일된다.

PG 시험은 제품 매수 경로와 같은 순서로 직접 부른다. 지문은 트랜잭션 밖에서 계산하고, 한 트랜잭션 안에서 슬롯 저장 → `reserveAndPrepareIntent` 순으로 간다. 틱·전략·시세·거래소 호출은 없다.

격리 PG는 timescale/timescaledb pg15(PostgreSQL **15.13**) 새 컨테이너 `one-entry-pg`다. 127.0.0.1:15441에만 열었고, 회차마다 새 DB를 만들었다. Flyway는 **v96**(95개 적용)이고 운영 데이터는 없다. 실행 틀은 [run.sh](run.sh)다.

| 사례 | 기점 [base-r3](runs/base-r3/summary.txt) (제품 = d81c3f2f) | 후보 [cand-r1](runs/cand-r1/summary.txt) (c43e1f1a) |
|---|---|---|
| ① 옵션 미지정: 매수→정산→재매수 | 승인(통과) | **승인(통과)**. 옵션 없는 세션 지문 `17f61c29763c` = 기점과 같음 |
| ② 잘못된 값 13종(null·""·"1"·true·1.0·1.5·-1·0·2·2L·BigDecimal 1/1.0·"abc") | **실패** — 검증 없이 `syncBalance` 호출 | **통과** — 13종 모두 동기화·배정 호출 0, 저장 0. 1·1L·BigInteger 1은 생성, 옵션 없음은 키 없음 |
| ③ 옵션 1: 매수→매도 정산 뒤 재진입 | **실패** — 재진입 승인(orderId 21) | **통과** — `ENTRY_LIMIT_REACHED`, 매수 행 1, 가용액 불변, 거절 슬롯 롤백 |
| ④ 동시 예약 2(둘째가 실제 `account_capital_lock` 대기 확인 = 1) | 의도 1이지만 기존 **활성 중복 거절**(`INTENT_REJECTED`) 때문 → 단언 실패 | **통과** — 둘째가 커밋된 의도를 보고 `ENTRY_LIMIT_REACHED`, 의도 1, 차감 1회 |
| ⑤ 만료 거절·승인 뒤 롤백 → 다음 예약 | 통과 | **통과** — 이력 0이 유지되고 다음 예약 승인 |
| ⑥ UNKNOWN / FAILED(제품 철회 경로) / CANCELLED 뒤 | UNKNOWN은 활성 중복 거절, **FAILED·CANCELLED는 재승인** | **통과** — 셋 다 `ENTRY_LIMIT_REACHED`, 행 1, 가용액 불변 |
| ⑦ DYNAMIC 같은 세션 ID BUY 행이 있을 때 | 정산 뒤 재승인(실패) | **통과** — DYNAMIC 행을 세지 않아 첫 LIVE 승인, 정산 뒤 LIVE 자기 이력으로 거절 |
| ⑧ 새 트랜잭션(REQUIRES_NEW, 새 backend) 재조회 | 재승인(실패) | **통과** — `ENTRY_LIMIT_REACHED`. 실제 `transaction_isolation = read committed`, `default_transaction_isolation = read committed`(PG 출력) |
| ⑨ 이력 조회 실패(다른 트랜잭션이 order 표 ACCESS EXCLUSIVE, `lock_timeout` 1.5s) | 승인 없음. 다만 실패 위치는 등록 한도 조회 | **통과** — `PessimisticLockingFailureException`이 `liveEntryLimit`에서 올라감, 승인·의도·차감 0 |
| 순수 3: canonicalParams 불변/분리, 청산 오버라이드가 키 무시 | 3/3 | 3/3 |

**합계: 후보 12/12(gradle rc0). 기점 5/12**(①⑤와 순수 3. ⑨는 승인 없음은 같지만 실패 위치 단언에서 실패). 두 회차의 시험 파일은 같다(`git diff 90a9a674 c43e1f1a -- web-api/src/test` 비어 있음). 원문 표시는 [cand-r1 marks](runs/cand-r1/marks.txt)·[base-r3 marks](runs/base-r3/marks.txt)에 있다.

**실제 잠금 구간(코드+PG):** `lockAccount`는 `SELECT … FROM account_capital_lock WHERE id=1 FOR UPDATE` 행 잠금이다. 예약 트랜잭션이 커밋·롤백될 때까지 유지된다(④에서 둘째가 `wait_event_type=Lock`으로 대기한 것을 관측했다). 판정 조회는 잠금 획득 **뒤**의 새 문장이다. READ COMMITTED에서는 문장마다 새 스냅숏을 쓰므로, 먼저 커밋된 의도를 본다(④⑧ 관측). 주석만으로 단정하지 않고 PG 출력으로 확인했다.

**보존한 실패 회차(성공 수에 합치지 않음):**
- [base-r1](runs/base-r1/gradle.log): 시험 파일의 `OrderRequest` import 누락으로 컴파일이 실패했다(시험기 결함).
- [base-r2](runs/base-r2/summary.txt): 시험기 결함 둘. ① 예약 트랜잭션 안에서 잠금 **전에** 세션을 읽어, 낡은 엔티티로 차감하다 ④에서 낙관적 잠금 충돌이 났다. 제품은 지문을 트랜잭션 밖에서 계산하므로 제품 경로가 아니다. ② ⑦⑧이 첫 의도를 정산하지 않아 기존 활성 중복 거절에 먼저 걸렸다(구분력 없음). 두 가지를 고친 시험기로 base-r3를 새로 돌렸다.

## 3. 해결하지 않는 것·한계

- 제한은 **"새 매수 의도 최대 1회"**다. 정지와 동시 승인의 경합(stopSession은 계좌 잠금을 쓰지 않는다)은 해결하지 않았다. 사용자 시험 총액·수수료·실제 계좌 여유(A3)도 별도 조건이다.
- DB CHECK 같은 마지막 방어는 없다. 의도 생성 경로가 `reserveAndPrepareIntent` 하나라는 판독에 기댄다(d81c3f2f 기준 LIVE BUY 생성 1곳, restartHarness는 시험 전용 소스셋). 주문 행을 손으로 지우면 무력화된다.
- 기존 H2 예약 시험(CapitalPoolLimitTest·ConcurrentEntryCapTest 등)은 돌리지 않았다. 옵션 없는 경로의 차이가 같은 트랜잭션 안의 `findById` 1회라 직접 영향이 넓어지지 않았다고 판단했다. 합치기 전 검사가 필요하면 이 두 클래스만 제안한다.

## 4. 복구 제한(구판으로 되돌릴 때)

옵션을 모르는 구판(0e1d2a02·d81c3f2f)은 `maxEntries`를 무시하고 **제한 없이** 돈다. 그래서 시험 세션이 있으면 다음 순서를 지킨다.
1. 후보판에서 시험 세션을 `stopSession`으로 정지한다(기존 API).
2. 아래 읽기 확인이 모두 0이고 status=STOPPED여야 한다. **STOPPED 하나만으로 완료 처리하지 않는다.**
3. 그다음에만 구판으로 되돌린다. 구판에서 그 세션을 `start`/RUNNING 재개하지 않는다.

구판의 자동 재개는 거래소 장애 복구 경로 하나뿐이다. 이 경로는 장애 때 RUNNING이던 세션만 메모리 목록으로 재시작하고, 앱을 재기동하면 목록이 사라진다. 그래서 STOPPED 세션은 사람이 start를 호출해야만 재개된다(d81c3f2f LiveTradingService 304·330~350 판독).

확인용 읽기 SQL 초안이다. **PG 미검증, 운영 미실행**이며, 실제 사용 전에 별도 검토·승인이 필요하다.

```sql
-- :sid = 1회 제한 시험 LIVE 세션 id. 모두 0 이고 status = STOPPED 여야 구판 복구로 넘어간다.
SELECT s.id, s.status, s.strategy_params->>'maxEntries' AS max_entries,
  (SELECT count(*) FROM "order" o WHERE o.session_kind = 'LIVE' AND o.session_id = s.id
     AND o.state IN ('PENDING','INTENT_READY','SUBMITTING','UNKNOWN','SUBMITTED','PARTIAL_FILLED')) AS unresolved_orders,
  (SELECT count(*) FROM position p WHERE p.session_kind = 'LIVE' AND p.session_id = s.id
     AND (p.status IN ('OPEN','CLOSING') OR COALESCE(p.size, 0) <> 0)) AS open_or_residual_positions,
  (SELECT count(*) FROM position_cleanup c WHERE c.session_kind = 'LIVE' AND c.session_id = s.id) AS cleanup_pending
FROM live_trading_session s WHERE s.id = :sid;
```

거래소 쪽 잔량(장부 밖 체결)은 이 SQL로 알 수 없다. 기존 계좌 대조(A3·장부) 절차가 맡는다. 확인 경로는 존재하므로 실행 보류 사유로 보지 않는다. 다만 **본운영 포함·생성·start는 승인 전**이다.

## 5. 다시 실행

`bash run.sh <새 회차>` — 작업 사본 HEAD로 돈다. 결과 폴더가 이미 있으면 거절한다. 컨테이너 `one-entry-pg`는 시험 뒤 정지했다. 같은 회차를 시간 채우기로 반복하지 않는다.

## 6. 체크섬

[SHA256SUMS](SHA256SUMS)(이 폴더, 자신 제외).
