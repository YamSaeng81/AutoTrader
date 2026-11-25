# 격리 PG 새 1묶음 — B 실제 LIVE 경로 + 복구 게이트 v3 (총괄 v1.243 §3-2, 제출)

2026-11-25 09:48 KST, 로컬 작업이다. 운영·거래소 접속은 0회다. 거래소 응답만 대역(MockBean)으로 바꿨다. 격리 PG는 `one-entry-pg`(PostgreSQL **15.13**, 127.0.0.1:15441)이고 새 DB `oe2_r1`를 썼다. Flyway 최고 버전은 **96**이다. 시험이 끝난 뒤 컨테이너를 정지했다.

| 항목 | 값 |
|---|---|
| 시험 SHA | **7fd723b4f087bf6030cc61adddfab60d46e66d84** (`candidate/live-one-entry-d81c3f2f`, 원격 보존) |
| 제품 | c43e1f1a와 같다. `git diff --name-only c43e1f1a 7fd723b4` = 시험 1파일 `LiveOneEntrySmokePathPgTest.java`. 실행기가 실행 전에 확인했다 |
| 게이트 | [recovery-gate-v3.sql](../one-entry-gate-v3-20261125/recovery-gate-v3.sql) `80f508bd…` · [gate_check.py](../one-entry-gate-v3-20261125/gate_check.py) `95d404e7…` ([head.txt](runs/r1/head.txt)) |
| 실행기 | [run.sh](run.sh) 회차 `r1`, 1회 |

## 결과 — 판정: 일부 통과, 실제 경로 미완료

| 부분 | 결과 |
|---|---|
| ② 게이트 v3 합성 | **29/29 기대대로**([gate-summary](runs/r1/gate-summary.txt)). 합성 세션 22개, 그리고 대상 없음·sid `abc`·빈 sid·등록값 불일치·등록값 빈칸·등록 파일 없음·부분 출력 7가지 |
| ① 실제 LIVE 경로 | **실패(시험기 기대값 결함)**. gradle rc=1, 2건 중 1건 실패([summary](runs/r1/summary.txt) · [gradle.log](runs/r1/gradle.log) · [marks](runs/r1/marks.txt)) |

**① 실패 위치:** 매수 체결 뒤 단언 `가용액 = 10000 − 체결대금 − 매수 수수료`. 기대 45.025, 실제 **45.02**. 제품 규칙 `BalanceAmounts.buyRefund`는 미사용액(요청 9,950 − 대금 9,950 − 수수료 4.975 = −4.975)을 소수 둘째 자리 HALF_UP으로 반영한다. 그래서 −4.98이 반영되고 가용액은 45.02가 된다. 주문 원시 기록은 `applied 9950.00000000/4.97500000`으로 8자리 그대로다. 제품 결함이 아니고, 시험기가 두 자리 잔액 규칙을 기대값에 넣지 않은 결함이다. v1 실행안의 "9,954.98원 이하"와도 맞는다. **기대값을 바꿔 다시 돌리지 않았다.**

**실패 앞까지 관측한 것(marks 원문):**
- HTTP 생성 200이다. `ApiResponse{success,data,error}` 형태이고, `sessionType REAL`, `status CREATED`, `strategyParams {"maxEntries":1}`(정수)이다.
- 새 트랜잭션 재조회에서 `number`, `1`, 10000.00, 0.9950, 9950.00, `started_at` NULL을 확인했다.
- 지문 `e3a51ef15cdf`는 재조회해도 같다. canonical은 옵션이 있으면 `{maxEntries=1}`, 없으면 `{}`이다.
- HTTP 시작 200, RUNNING이다.
- 제품 `executeSessionBuy`를 부르자 의도 1건, 외부 bid 제출 1회가 생겼다. 체결(`done`) 뒤 FILLED, 포지션 0.000199, `fill_accounting_pending` false였다.

**실행되지 않은 단계(성공으로 세지 않는다):** 매도(`executeSessionSell`), 청산 확정, 실제 경로 재진입 `ENTRY_LIMIT_REACHED`, HTTP 정지, 정지 뒤 재시작 거절, 그리고 실제 경로 세션의 게이트 PASS. 실제 경로 세션은 목록 파일에 기록되지 않았다.

## 게이트 v3 합성에서 확인한 것

- **PASS:** 정상 왕복과 완료 정리 이력(`cleanup_done=1`), 허용 코드 `WITHDRAWN_STOPPED` FAILED와 확인된 취소, 해결된 정산 불일치.
- 정상 세션도 `settlement_recheck=2`이다. 제품은 `settlement_final`을 TRUE로 세우지 않는다(OrderSettlementService 223행). 그래서 v2의 '미정산 차단' 열을 쓰면 정상 종료가 막힌다. v3는 이 열을 참고로만 둔다.
- **FAIL:** 미완료 정리, 활성 주문, 회계 반영 대기, 반영 안 된 체결 매도, 잔량, OPEN 포지션, RUNNING, maxEntries 없음/문자열 `"1"`/`1.0`, PAPER, BUY 2행, 알 수 없는 주문·포지션 상태, 대상 없음, 조회 실패(rc 3), 등록값 불일치·빈칸·파일 없음, 부분 출력.
- **HOLD:** 확인 없는 취소, FAILED 코드 NULL, 목록 밖 코드(`SUBMIT_TIMEOUT_X`), 미해결 정산 불일치, EMERGENCY_STOPPED.

## 추가 실행이 필요한 범위(승인 전 실행하지 않음)

- **시험기 기대값만 고친 새 회차 `r2` 1회.** 가용액 기대를 제품 규칙으로 바꾼다: `10000 − 9950 + buyRefund(9950, 9950, 4.975)` = 45.02. 매도 뒤 기대는 `sellCredit`(두 자리 HALF_UP)이다. 바뀌는 것은 시험 파일 1개다(새 시험 SHA). 제품은 c43e1f1a 그대로다. 게이트 합성 부분은 그 회차에서 함께 다시 나오고, 같은 결과가 기대된다.
- §3-3 전체 회귀와 LF jar는 "앞 단계 모두 통과" 조건을 충족하지 못해 **시작하지 않았다.**

체크섬: [SHA256SUMS](SHA256SUMS)(자신 제외).
