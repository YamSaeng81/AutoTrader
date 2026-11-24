# R1 DYNAMIC 9 — 주문 없는 종료 경로 조사와 배포 해제용 최소 증거안 (총괄 v1.237 §3)

2026-11-24 KST, Claude Code 로컬 작업이다. **운영 SSH·DB·계좌 호출은 0회다.** PG·전체 회귀·jar 빌드·가짜 SSH도 실행하지 않았다. 제품, 고정 SQL, pf3ok, d81 후보는 바꾸지 않았다. 아래 r1-diag-3 SQL은 **초안**이다. PG 문법과 실행을 검증하지 않았고, 운영 실행 승인도 아니다.

읽은 원천은 모두 Git 블롭이다. 작업 사본은 쓰지 않았다.
- 운영 `0e1d2a02` — DynamicTradingService `972555bd`
- 후보 `d81c3f2f` — DynamicTradingService `1cb4a824`
- 같은 두 SHA의 OrderExecutionEngine, PositionRepository, PositionStateTransition, StrategyLogEntity, PositionEntity, LiveTradingService

## 1. 요약

- **0e1d2a02에서 DYNAMIC 포지션을 주문 없이 CLOSED로 바꾸는 경로는 넷이다.** 넷 다 exit_reason을 쓰지 않는다(§2). 운영의 9건(REAL·CLOSED·주문 0·exit_reason NULL·V87 뒤 개설)은 넷 중 어느 것으로도 만들어질 수 있다.
- **가장 유력한 경로는 E(예약 거절)다.** 단, 실제 행과 연결해 보지는 않았다. 근거는 셋이다.
  1. 9건의 모양(주문 0·size 0 템플릿·사유 NULL)과 정확히 맞는 정상 경로는 E 하나뿐이다. D·O·S는 비정상 상태(주문 없는 OPEN size=0 포지션)가 먼저 있어야 생긴다.
  2. 보존 기록상 10-16 배포 뒤 실자금 신규 진입은 공유 풀 미등록으로 일부러 거절됐다. 10-30에도 공유 풀 미등록 로그가 있었다(PROGRESS 보존본 384·199행). 그 기간에 REAL DYNAMIC 세션이 매수 신호를 내면 E가 생긴다.
  3. E는 **같은 틱 트랜잭션에서(코드 판독) strategy_log 행에 `was_executed=false`, `blocked_reason='공유 풀 예약 거절(<코드>): …'`을 남긴다.** 과거 9건을 확실한 사실과 이어 줄 원천이 DB 안에 있다는 뜻이다. strategy_log를 자동으로 지우는 코드는 없다. 수동 초기화는 LIVE 행과 paper 스키마만 지운다.
- **d81c3f2f는 E에만 ENTRY_REJECTED를 붙인다.** D·O·S는 0e1d2a02와 글자 단위로 같다. 그래서 배포 뒤에는 E가 새 R1 행을 만들지 않는다. D·O·S는 계속 NULL로 닫히고 R1에 걸린다. D·O·S가 전제하는 상태는 원천 결측의 증상이므로, 걸리는 것이 맞다(§2-2).
- **새 PG나 단위 시험은 추가하지 않았다.** d81의 E 동작은 보존된 PG 결과 F3·X3이 이미 확인했다(§3). 0e1d2a02와의 차이가 한 줄(`setExitReason`)뿐이라는 것은 Git diff로 확인했다.
- **배포를 풀 방안 권고:** r1-diag-3 읽기를 1회 해서 경로와 정확한 만료 시각을 확인한다. 그 결과로 자연 만료를 기다릴지 정한다(§4). 과거 행 수정이나 예외 확대는 권하지 않는다.

## 2. 주문 없이 DYNAMIC 포지션을 닫는 경로 (§3-1)

### 2-1. 경로 표 (0e1d2a02 / d81c3f2f 행 번호)

| 경로 | 위치 | 선행 조건 | 주문·자금·트랜잭션 | exit_reason | 9건 모양과 맞나 |
|---|---|---|---|---|---|
| **E 예약 거절** | executeBuy 1712 템플릿(size 0) → 1746 save → 1764~1774 catch / d81 1827 save · 1845~1855 | REAL 세션 매수 신호. 즉시 경로(1272) 또는 회차 승인(893) | `reserveAndPrepareIntent`(OrderExecutionEngine 494~549)가 거절하면 차감이 없었거나 같은 트랜잭션에서 되돌린다(`releaseWithinTransaction`). 주문 의도 행은 승인 뒤에만 만든다. **틱 트랜잭션은 롤백하지 않는다.** 슬롯은 CLOSED로 커밋된다. 같은 트랜잭션에서 `updateSignalQuality`(2329~2337)가 blocked_reason을 쓴다 | 0e1d: NULL / d81: ENTRY_REJECTED | **예**: 주문 0, size 0, opened≈closed(같은 틱) |
| **D 세션 삭제** | deleteSession 705~728 (setStatus 716) / d81 756 | RUNNING이 아닌 세션 삭제 요청 + 그 세션에 OPEN 포지션이 남아 있음 | 주문 조회 없이 OPEN을 모두 CLOSED로 바꾼다. 자금 복원 없음. 세션은 DELETED | 둘 다 NULL | 주문이 없는 OPEN이 먼저 있어야 함 |
| **O 고아 정리(주문 없음)** | reconcileDynamicOrphanBuyPositions 3254~3272 / d81 3341~3359 | OPEN·size≤0·BUY 주문 0·opened_at 뒤 5분 이상 | `closeIfOpen` 뒤 `investedKrw`를 세션 가용액에 복원한다. 소스 주석은 "async 스레드 DB 오류 등"을 예로 든다 | 둘 다 NULL(`closeIfOpen`은 status·closed_at만 쓴다, PositionRepository 197) | 같음. closed−opened ≥ 5분 |
| **S 정지 시 빈 슬롯** | closeOpenPositions 2519~2534 → settleEmptyPosition 2608~2627 / d81 2695~2713 | 정지·비상 정지 때 size=0 포지션에 BUY 주문이 없음 | `closeIfOpen`만 한다. 복원 없음 | 둘 다 NULL | 같음. closed≈세션 stopped_at |
| (참고) 체결 없는 매수 정리 | 3230 · 2812 / d81 3317 · 2899 | BUY 주문이 있고 체결 0 확정 | 예약액 복원 + `closeIfOpen` | NULL | 아님: 주문이 있다. R1 무거래 예외 둘째 갈래(체결 0 확정)에 해당 |
| (참고) LIVE 예약 거절 | LiveTradingService 매수 경로 1656 | — | 거절을 **트랜잭션 롤백**으로 처리한다. 슬롯이 남지 않는다 | — | LIVE R1 0과 맞다 |

직접 UPDATE도 검색했다. 제품의 `UPDATE position`은 V51(session_kind 채우기), V90(배정액), PositionLimitGuard(peak 값)뿐이고 status를 바꾸지 않는다. 그 밖의 CLOSED 쓰기는 모두 매도 체결 정산(3130·LiveTradingService·OrderExecutionEngine 2364)이다. 이 경로들은 SELL 주문이 있으므로 주문 0인 9건과 맞지 않는다.

### 2-2. 경로 사이의 차이와 d81에서 남는 것

- **E는 증거가 남는 무거래다.** 거절된 승인이라 거래소 호출이 0이다. 차감은 없거나 같은 트랜잭션에서 되돌린다. d81의 X3 시험이 잔액 불변과 예약 0을 확인했다.
- **D·O·S는 무거래를 보장하지 않는다.** 주문 없는 OPEN size=0 포지션은 V85 뒤 정상 경로로는 생기지 않는다. 슬롯과 의도를 같은 트랜잭션에서 커밋하고, E는 그 자리에서 닫기 때문이다. 그래서 D·O·S가 실제로 일어났다면 주문 행이 사라진 것이다. 비동기 오류, 수동 편집, V85 이전 행 같은 원천 결측이다. 여기에 ENTRY_REJECTED를 붙이거나 예외를 넓히면 그 결측을 숨긴다. v1.237 §2의 판단과 같다.
- **d81에서 새로 생기는 R1 행:** E는 ENTRY_REJECTED → `CONFIRMED_NO_TRADE` 첫 갈래로 빠진다. D·O·S는 NULL → ④로 계속 잡힌다. "d81 표지가 신규 경로를 어디까지 막는가"에 대한 답은 **E만 막는다**이다. 이 구분은 보수적이고 맞다.

### 2-3. 운영 9건: 실제 경로와 코드상 가능한 경로

| 구분 | 내용 |
|---|---|
| 코드상 가능 | E·D·O·S 넷. 모두 REAL·CLOSED·주문 0·NULL을 만든다 |
| 정황상 유력 | E. 공유 풀 미등록 기간(10-16 이후 의도적 거절, 10-30 로그)과 9건의 closed_at 구간 [10-25, 11-01)이 겹친다. STAGE_1 기준 행 시작은 10-30 04:16:57Z다(RUNBOOK BASEX). 그 전에는 POOL_NOT_REGISTERED 거절이 가능했다. 다만 공유 풀 미등록이 그 구간 내내 이어졌는지, 그때 RUNNING이던 REAL DYNAMIC 세션이 무엇인지는 이번에 확인하지 않았다 |
| 확인되지 않음 | 9건의 size 값, opened/closed 간격, 세션 상태, strategy_log 연결. r1-diag-2는 이 값을 읽지 않았다. **그래서 9건을 E로 확정하지 않는다** |

## 3. 기존 근거 연결 (§3-1 ②) — 다시 실행하지 않음

| 확인 내용 | 보존 결과 | 결과 |
|---|---|---|
| d81 DYNAMIC 예약 거절 → CLOSED·size 0·ENTRY_REJECTED·주문 없음, 성과·킬 기준·보고서 집계에서 빠짐 | [CostBasisFollowupPgTest checks](../dec-real-buy-summary-20261119/rbs-impact-69c81c3d/pg-CostBasisFollowupPgTest/cost_basis_followup_checks.json) sha256 `749e455d…`, F3 9개 | 47/47 통과. 실제 값 `CLOSED/0E-8/ENTRY_REJECTED/invested=8000.29`. 거절 반환 문자열은 `공유 풀 예약 거절(RISK_CONFIG_MISMATCH): …` |
| 예약 거절 슬롯: 주문 0·예약 0·잔액 그대로, 관문·연속 손실·진입 판정 불가 아님, 알림 0 | [AbcCrossPgTest checks](../dec-real-buy-summary-20261119/rbs-impact2-596ed4b0/pg-AbcCrossPgTest/abc_cross_checks.json) sha256 `5fb8326e…`, X3 8개 | 19/19 통과. `pos=0 ord=0 reserved=0 avail=10000.37 → pos=1 ord=0 reserved=0 avail=10000.37` |
| 표지 없는 과거 빈 청산은 판정 불가 | [사전 SQL v3 대조](../dec-predeploy-parity-20261119/README.md) P6~P8, [v3 README](../dec-costbasis-followup-20261119/README.md) 57행 | 96/96 일치. "과거 표시 없는 빈 청산(ENTRY_REJECTED 없음 → 판정 불가)"은 **설계 때 정한 동작**이다 |
| 0e1d2a02와 d81의 E 차이 | `git diff` 블록 대조(이 작업) | 한 줄 추가(`setExitReason(ENTRY_REJECTED)`)뿐이다. 삭제(D)·고아 정리(O) 블록은 같다 |

**부족한 경계:** strategy_log의 blocked_reason이 E 슬롯과 같은 트랜잭션으로 커밋된다는 점은 코드로만 읽었다. 시험 출력에는 없다. r1-diag-3은 이 연결에 기대므로 §4-2에서 PG 1묶음을 제안한다. 단위 시험으로는 트랜잭션 경계를 증명할 수 없어 추가하지 않았다.

## 4. 배포를 풀기 위한 최소 증거안 (§3-2)

### 4-1. 두 경로 비교

| | 역사적 무거래 입증 | 자연 만료 |
|---|---|---|
| 필요한 증거 | 9건 각각이 E임을 보이는 독립 원천: 같은 세션·코인, opened_at 직전 6분 안의 strategy_log 거절 행, size 0, opened≈closed. 주문 0·size 0만으로는 부족하다(D·O·S도 같다) | 9건의 정확한 만료 시각, 그리고 그 뒤 새 대상이 없다는 것 |
| 이것만으로 풀리나 | **아니다.** 입증해도 R1은 그대로 9다. 풀려면 과거 exit_reason 보정(DB 쓰기)이나 제품 예외 변경이 필요하다. 둘 다 v1.237에서 승인되지 않았다 | 예. 제품·DB를 바꾸지 않는다 |
| 일정 | 읽기 1회와 별도 쓰기 승인·검토가 필요하다. 11-27 전에 끝내기 어렵다 | 상한은 DB 기준 **12-01 04:49:14Z(13:49 KST) 이전**이다. 실제 시각은 읽기 전에는 모른다 |
| 11-27 고정1 GO/HOLD | 쓰기 승인 전이면 HOLD 사유로 남는다 | EXP가 11-27 전이면 영향 없음. 그 뒤면 "만료 대기 HOLD" |
| 11-30 최종 S | 같음 | EXP ≤ 11-30이면 영향 없음 |
| 12-01 시작 | 확약할 수 없다 | EXP가 시작 예정 시각 뒤면 그 시각까지 LIVE·DYNAMIC 신규 실자금 진입이 거절된다. 최악은 12-01 13:49 KST까지 |
| 위험 | 보정이 D·O·S 행까지 덮으면 원천 결측을 숨긴다 | 새 E는 0e1d2a02가 계속 만든다. **d81 배포 전에 REAL DYNAMIC 세션이 매수 신호를 내면 9가 늘고 만료가 뒤로 밀린다** |

**권고:** r1-diag-3을 1회 읽는다. 결과에 따라 이렇게 정한다.
- PATH가 전부 E_ONLY이고 NEW가 0이다 → 자연 만료를 택한다. EXP 시각으로 일정을 확정한다.
- D/S/O가 하나라도 있다 → 그 행은 원천 결측 후보다. 만료와 별도로 원인을 조사한다.

그리고 만료 전까지 REAL DYNAMIC 세션이 새 E를 만들지 않는지 확인해야 한다. 마지막 상태 읽기는 RUNNING 0이었지만 이는 전 기간의 보장이 아니다. 이 확인은 r1-diag-3의 NEW 행으로 한다.

**총괄 판단 사항(제안만 함):** U0의 보수적 사전 기준은 "R1 = 0"이다. R1>0으로 배포하면 만료 전까지 신규 실자금 진입이 막힌다. 지금 RUNNING 실자금 세션이 없다면, 이 효과는 아무도 진입하지 않는 기간과 겹친다. 그런 조건에서 배포를 먼저 하고 진입은 만료 뒤에 허용하는 순서도 가능하다. 다만 이는 U0 기준을 바꾸는 일이라 이 문서에서 정하지 않는다.

### 4-2. 최소 집계 읽기안 r1-diag-3 (실행 전 제출)

| 항목 | 내용 |
|---|---|
| 원문 | [sql/r1-diag-3.sql](sql/r1-diag-3.sql)(175행). 생성기 [build_sql.py](sql/build_sql.py). pc CTE는 r1-diag-2 102~148행을 글자 그대로 옮겼다([pc-vs-r1-diag-2.diff](sql/pc-vs-r1-diag-2.diff) 0바이트). 보호값 블록과 META는 r1-diag-2와 같고 식별자만 `r1-diag-3`이다 |
| 출력 | NS(대상 수·고유 세션 수) · SZ(size 0/NULL/그 밖) · GAP(closed−opened 4구간) · SS(세션 상태) · STOPNEAR · LINK(REJECT_LINKED / OTHER_LOG_ONLY / NO_LOG) · CODE(거절 코드 그룹) · PATH(E_ONLY / D_OR_S_ONLY / O_ONLY / MULTI / NONE) · **EXP(마지막·첫 만료 시각, 분 단위)** · NEW(11-01 04:49:14Z 뒤 닫힌 대상 수) |
| 반출 범위 | 개수, 코드, 분 단위 시각뿐이다. 금액·수량·가격·손익·잔액·행 ID는 없다. blocked_reason은 괄호 안 코드만 내고, 코드 형식이 아니면 `(nonconforming)`으로 묶는다 |
| 보호값 | statement_timeout 15s, lock_timeout 2s(R1D_GUARD). READ ONLY, REPEATABLE READ, ROLLBACK |
| 횟수·실행기 | 승인되면 1회. 기존 [r1-diag-read.sh](../r1-diag2-20261124/approval/) v1.236 사본의 SQL 경로·META 식별자·검사기만 바꾼 새 사본을 쓴다. 새 결과 폴더는 `r1-diag3-read-<날짜>-<HHMM>`. 자동 재시도는 없다 |
| 기존 진단과 차이 | ORIG/SPLIT 분해는 다시 내지 않는다. DYNAMIC ④ 대상만 position·dynamic_session·strategy_log와 잇는다. 새로 읽는 표는 strategy_log 하나다 |
| 남은 일 | ① 격리 PG 1묶음: E·D·O·S 합성 행과 strategy_log 행으로 PATH·LINK·EXP가 기대대로 나오는지, 문법은 맞는지 확인. ② 검사기(필수 태그·열 수·합 = NS) 작성. ③ 실행기 사본 작성. **셋 다 총괄 수용 뒤 진행한다.** PG를 따로 요청하는 이유: 이 SQL은 새 표와 시각 계산을 쓰므로 r1-diag-2 PG 결과로 검증되지 않는다 |
| 한계 | strategy_log 저장이 실패한 경우(`saveStrategyLog`가 null을 반환) E인데도 NO_LOG가 된다. 6분 창 안의 다른 거절 행과 잘못 이어질 수 있다. 같은 세션·코인·직전 6분이라 가능성은 낮다. LINK는 E의 충분조건이 아니라 강한 정황이다 |

## 5. N03 예약 6fe39570 (§3-3)

이 세션의 예약 목록은 0건이다. 6fe39570을 소유한 세션 ebcf4168은 직접 확인할 수 없다. 그래서 존속도, 22:14 회수가 실제로 실행될지도 미확인이다. 중복을 막으려고 예약을 새로 만들지 않았다. 생산 cron도 바꾸지 않았다. 사용률은 관측하지 못했다(미확인이며, 0으로 두지 않음).

## 6. 하지 않은 것

운영 호출, PG, 회귀, 빌드, 가짜/실제 SSH, 제품·고정 SQL·과거 행 수정, U0 재시도, U1, U2.
