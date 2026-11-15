# v1.167 — 미관측 N03·장부 읽기, 주문 확인 경합 재현과 최소 제품 후보 (2026-11-15 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.167 / 414차](../review-40bf70b9/README.md) §3·§4를 진행했다. 운영 서버는 기존 `read.sh` 읽기만 했고 운영 DB·설정·배포는 바꾸지 않았다. 로컬 실제 PG는 격리 컨테이너 `v152-pg`(127.0.0.1:15437)의 실행별 새 DB다.

## 1. 운영 읽기 (§3-1·2)

실제 읽기 **11-15 15:36:16~15:36:31 KST**(서버 시각). 11-13 14:30:18 KST 이후 이 읽기 전까지는 미관측이다. 각 회차의 원문·서버 해시·설치 상태는 회차 폴더에 있다.

| 회차 | 폴더 | 보고 | 서버 해시 대조 |
|---|---|---|---|
| 11-13 22:00 | [n03-1113-2200](../n03-1113-2200/README.md) | 🟢 정상, ERROR 0 | 3/3 |
| 11-14 10:00 + 09:30 장부 | [n03-1114-1000](../n03-1114-1000/README.md) | 🔴 미분류7(뉴스 수집 Connection reset·timeout, 보고 본문은 5줄) | 3/3 |
| 11-14 22:00 | [n03-1114-2200](../n03-1114-2200/README.md) | 🔴 미분류1(CryptoPanic Connection reset) | 3/3 |
| 11-15 10:00 + 09:30 장부 | [n03-1115-1000](../n03-1115-1000/README.md) | 🟢 정상(🟡 통신6·BLAST) | 3/3 |

- 4회차 모두 HEAD **b5cd1d0d**, backend 이미지 **52e12428**, 재시작0, 기동 11-10 13:48 KST, INSTALLED_SHA c669ccdc, daily_check.sh·daily_checks.sql·crontab 해시 직전과 같음. frontend `.Image` d825a9f5(FEIMGOLD). Flyway 94. Hikari 누수·연결 획득 실패·N17/N19/N20 0.
- **실자금 진행 주문(§3-2):** 각 보고의 `real_unresolved_orders`(LIVE·DYNAMIC REAL의 PENDING·INTENT_READY·SUBMITTING·UNKNOWN·SUBMITTED·PARTIAL_FILLED, SID 203 밖) **0**, SID 203 미결0·UNKNOWN 0, 정리 대기0·격리 대상0·미해결 정산 불일치0, 구간 실자금 매수0. 질의 원문은 c669ccdc `scripts/ops/daily_checks.sql`(해시 653b22ba가 서버와 같음) 26~32·68~75행. 진행 주문이 없으므로 §3-2의 추가 읽기 전용 SQL은 **실행하지 않았다.** 과거 종결 주문에 이 경합이 실제로 있었는지는 이 읽기의 범위가 아니며 확인하지 않았다.
- SID 203: CREATED·20,200.00·보유0 유지. 장부 11-14·11-15는 기록 시각과 BTC 표시가만 달라졌고 과거 장부 파일은 그대로다. 66세션 RUNNING63·지정 STOPPED3/3·적재 뒤처짐12 전부 KRW-BLAST(기존 모양).
- `last_send`는 서버가 매 회차 덮어써서 네 폴더의 사본 모두 11-15 10:00 값이다. 회차별 전송 성공은 보고 `.txt` 끝 줄로 확인했다.

## 2. 커밋·가지

`candidate/order-poll-race-b987d7a7`(작업 사본 `D:/tmp/race-b987d7a7`, 원격 푸시) = 시험 가지 **b987d7a7** 위 6커밋. **제품 변경은 19d1cc61 한 커밋·2파일(+76/−12)** 이고 나머지는 시험만이다.

| 커밋 | 내용 |
|---|---|
| e29559d0 | `SubmitPollRaceTest` 사례 A~E(시험만) |
| 89290e5c | 같은 시험의 거래로그 기대에 의도 생성 기록(`null→INTENT_READY`) 포함 — 제 기대값 누락 정정(시험만) |
| **19d1cc61** | **제품: `OrderRepository.touchUnknownProbeIfUnresolved` 추가, `OrderExecutionEngine`의 해소·제출 결과·제출 오류 3경로** |
| 109611f4 | `SubmitPollRacePgTest`(A~E 상속 + 행 잠금 대기 F·G, 시험만) |
| 42d2cd3a | LIVE 매도·DYNAMIC 매수/매도의 순서 A·B 6사례(시험만) |
| **99e6ab10** | `ExecutorBarrierTest` 전제 보완 + `ExecutorBarrierPremiseProbeTest`(시험만). **최종 후보 머리** |

## 3. 경합 재현 (§4-1)

같은 주문 ID에서 거래소 대역 `createOrder`·`getOrderByIdentifier`가 래치에서 멈추고 시험이 커밋 순서를 정한다. 시간 대기로 순서를 만들지 않고 래치 시간 초과는 실패다. 모든 사례에서 거래소 제출 1회를 단언한다.

| 사례 | 순서 | 제품 변경 전 결과(원문) |
|---|---|---|
| A | 폴러 SUBMITTING 읽기 → 조회 대기 → 제출 결과 커밋 → 조회 "없음" | **UNKNOWN**·주문번호·제출 시각 소실 — 413차 주문359와 같은 모양 |
| B | 폴러 UNKNOWN 먼저 커밋 → 제출 결과 | SUBMITTED·주문번호는 붙지만 사유 "조회되지 않음 (시도 **0회**)" 잔존 — 조회 횟수 증가까지 낡은 엔티티 병합에 덮임 |
| C | A와 같은 순서, 조회 "찾음" | 제출 응답 원문 null로 덮음·SUBMITTED 전이 2회 |
| D | 제출 결과 → 다른 회차의 체결·정산 반영 → 폴러 재개 | **FILLED→UNKNOWN, 체결량0·정산 버전0·수수료 출처 PENDING으로 되돌림**(포지션 9.8·잔액 7,058.53은 반영된 채) — 재정산 위험 |
| E | 제출 호출 대기 중 폴러가 찾아 체결·정산 → 제출이 통신 오류로 끝남 | **FILLED→UNKNOWN**(사유 "제출 결과 불명") |
| F (PG) | 제출 결과 기록 중 별도 연결이 같은 행을 잠그고 FILLED 반영 | 잠금 대기 뒤 SUBMITTED로 되돌림 |
| G (PG) | 폴러 "없음" 재개 때 별도 연결이 SUBMITTED·주문번호로 잠금·커밋 전 | 잠금 대기 뒤 UNKNOWN으로 덮음 |
| 6사례 | LIVE 매도, DYNAMIC 매수, DYNAMIC 매도 × A·B | 6/6 실패 |

F·G는 시험이 별도 JDBC 연결로 행을 잠근 채 제품 스레드가 `pg_stat_activity`에서 Lock 대기에 들어간 것을 확인한 뒤 커밋한다. PG 연결 초기화는 운영과 같은 `lock_timeout = '10s'`다.

| 실행 | 머리(작업 사본) | 결과 |
|---|---|---|
| r01 | e29559d0 | H2 5 = 실패5(A~E) |
| r03 | 89290e5c | H2 5 = 실패5 |
| r10 | 42d2cd3a + 제품만 89290e5c로 되돌림 | H2 11 = **실패11** |
| r07 / r11 | 109611f4 / 42d2cd3a + 제품 되돌림 | 실제 PG 8 = 실패7 / **14 = 실패13**(통과는 PG 전제 확인 1) |

## 4. 최소 제품 후보 (§4-2·3) — 19d1cc61

- **폴러(`resolveUnresolvedSubmissionInTx`)**: 식별자 조회 응답을 받은 **뒤** `UPDATE … SET unknown_probe_count+1, unknown_probed_at WHERE id AND state IN ('SUBMITTING','UNKNOWN') AND exchange_order_id IS NULL`. **0행이면 다른 경로가 먼저 해소한 것이므로 아무것도 쓰지 않는다.** 1행이면 이 갱신이 행 잠금을 잡았고 컨텍스트가 비워졌으므로 최신 행을 다시 읽어 그 값으로만 UNKNOWN 전이·사유 또는 주문번호 부착·SUBMITTED 전이를 한다. 조회 예외 경로도 같은 조건부 갱신을 쓴다. 잠금은 거래소 응답 뒤 갱신 구간에서만 잡는다.
- **제출 결과(`recordSubmitOutcome`)**: `findByIdForUpdate`로 읽고 **SUBMITTING·UNKNOWN이며 주문번호가 없거나 같은 행에만** 반영한다. UNKNOWN이었으면 사유를 해제하고 조회 메타는 둔다(거래로그는 실제 `UNKNOWN→SUBMITTED`). 사람이 수동 조정한 불명 주문(V91)은 폴러와 같게 관측만 남긴다. 이미 다른 경로가 진행시켰으면 상태·주문번호·체결·정산값을 건드리지 않고, 같은 주문의 응답 원문이 비어 있을 때만 남긴다.
- **제출 오류(`callExchangeAndRecord` 예외)**: 잠금 재읽기 뒤 **SUBMITTING일 때만** UNKNOWN. 폴러가 먼저 UNKNOWN으로 올렸으면 그 사유·메타를 둔다.
- 기존 `attachFoundSubmission`·명시적 거절 경로(`findByIdForUpdate` + SUBMITTING 확인)는 이미 조건부라 바꾸지 않았다. 새 진행 중 표식은 추가하지 않았다(`DISPATCHING` 수명 그대로).
- **남는 동작(판단 대상):** 같은 프로세스에서 제출 호출이 진행 중일 때 폴러가 조회 "없음"을 먼저 받으면 B처럼 **일시 UNKNOWN**이 커밋된다(제출 결과가 오면 SUBMITTED로 회복). 정기 점검이 그 순간을 읽으면 UNKNOWN 1건으로 보일 수 있다. 제출 중 SUBMITTING을 폴러가 건너뛰게 하려면 claim~외부 호출 수명의 진행 표식이 필요해 이번 최소 범위에서 뺐다.

## 5. 관련·실제 PG 검증 (§4-4)

| 실행 | 머리 | 결과 |
|---|---|---|
| r04 / r08 | 89290e5c·109611f4 + 제품 작업분 | H2 5/5, 11/11 |
| r05 관련69 | 89290e5c + 제품 작업분(19d1cc61과 같은 내용) | **538 = 성공533·건너뜀5·실패0**, 외부 차단0 |
| **r14 관련71** | **99e6ab10 깨끗** | **546 = 성공541·건너뜀5·실패0**, 외부 차단0 |
| r09 / pg99 경합 PG | 42d2cd3a / 99e6ab10 | **14/14** ×2 |
| pg99 기존 PG 9클래스 | 99e6ab10, 클래스별 새 DB | ClosingIntentAtomicity 5·ClosingIntentRollback 2·LiveLossWindow 7·N15PgLockWaitExpiry 13·PartialSellRealizedPnl 7·PhantomKeepsConfirmedPnl 4·RealLossGlobal 3·RealLossThreeFormula 8 전부 통과. **SettlementRoundingPg 17 중 실패1 = 감시가 api.upbit.com 이름 조회 2건 차단**(단언 16 통과) |
| pgbase | 제품 되돌림 | SettlementRoundingPg 같은 2건 차단·같은 실패1 → **이번 변경과 무관한 기존 격리 공백**(시험 보완은 아직) |

관련71 = 413차 후속46·관련19 합집합 57 + 변경 경로를 직접 부르는 12(`resolveUnresolvedSubmission`·`submitOrder`·`getOrderByIdentifier` 등 검색) + ExecutorBarrier 2. 건너뜀5는 환경 변수 없는 PG 클래스다. 재기동 복구(`ExchangeRecoveryRestartTest`·`LimitedTestExchangeRecoveryTest`), 응답 유실 추적(`ClaimAndUnknownTrackingTest`·`SubmitResponseUnknownTest`), 정산1회(`ConcurrentFillSettlementTest`), 수동조정(`UnknownOrderAdjustmentTest`), 정지·취소(`CancelConfirmAndLateFillCleanupTest`·`LateOrderSubmissionAfterStopTest`·`DynamicStopRaceTest`)가 포함된다. r12·r13의 탐침 파일은 미추적 상태로 돌았고 99e6ab10에 같은 내용으로 커밋했다.

## 6. ExecutorBarrierTest (§4-5)

- 기전: JDK17 `ThreadPoolExecutor.Worker`는 생성 때 상태 −1이고 `runWorker` 첫 `unlock()`에서 0이 된다. `isLocked()`는 상태≠0이다. **시작됐지만 아직 `runWorker` 전인 작업자는 `getActiveCount()`·`getTaskCount()`에 1로 세진다.** `ExecutorBarrierPremiseProbeTest`가 둘째 작업자를 스레드 공장에서 신호로 붙잡아 `pool=2 active=1 queue=0 task=1 completed=0` → "옛 조건" 거짓을 결정적으로 재현했다(r12). 둘째가 시작하면 active0·task0.
- 보완(시험만): 실행 전 코어 작업자 2개가 대기열 `take()`에 들어간 신호를 기다리고, 전제 단언 문구에 풀 상태를 남긴다. 기대값은 그대로다(r13·r14 통과).
- 판단 범위: b2976905 전체 회귀의 실패 시점 풀 상태는 기록되지 않았다. 같은 전제 단언을 거짓으로 만드는 기전을 입증한 것이며 그 1회가 이 기전이었다고 확정하지는 않는다. 다음 전체 회귀에서 실패하면 새 문구에 상태가 남는다.

## 7. 실제 jar와 새 D4 초안 (§4-6)

- 99e6ab10에서 `./gradlew :web-api:clean :web-api:bootJar -x test --offline` → `f7450f13…`(사본 `/d/tmp/v167-ev/jars/99e6ab10.jar`). 비교기 v2(V161D4 U1 원문에서 그대로 꺼낸 [`jarcmp_v2.py`](jarcmp_v2.py))로:
  - 보존 b5cd1d0.jar(`a7ef759f…`) 대비 **881→895, 변경48·추가14·삭제0** — 이름 목록이 **V161D4 RARX 62줄과 완전히 같다**(`jars/rarx-v161.txt`=`rarx-99e6.txt`).
  - 88f06345 입력 jar(`0834382a…`) 대비 **895→895, 변경5**: `OrderRepository.class`, `OrderExecutionEngine.class`·`$5`·`$CancelOutcome`·`$SubmitRejection`(바깥 클래스 재컴파일). 모두 19d1cc61의 두 소스 파일에 대응한다. 프런트·다른 모듈·리소스는 입력 동일(DIFFN의 프런트 8경로는 88f06345와 같음).
- **기존 D4의 한계:** U1의 jar 확인은 이름 목록(RARX)·항목 수(JARN)만 본다. 옛 후보 jar와 새 jar가 같은 62줄이라 **V161D4는 두 jar를 구별하지 못한다.**
- **V167D4 초안** [`d4/blocks-d4`](d4/blocks-d4/) — 생성기 [`d4/mkdraft8.py`](d4/mkdraft8.py)(원본 V161D4는 그대로, 바꾸는 곳마다 횟수 확인). 바꾼 값: 판 표지 V167D4·`~/maint-v167`·판 문구 v1.167, NEW **99e6ab10**, DIFFN **130경로**(시험100·제품 Java20·V96 1·build.gradle 1·프런트8, 실제 `git diff --name-only`), RARX 주석. 추가: **JARM**(새 jar 항목 이름·sha256 목록 [`jarmanifest.py`](jarmanifest.py) 895줄의 sha256 = `7df5758f…`; 88 입력 jar는 `a91ffaaf…`, b5cd1d0은 `a66d2ccd…`), U0 존재 확인, U1 JARN 확인 뒤 목록 대조(다르면 재기동 전 멈춤·U1-되돌림). 그대로: OLD·GS·RARX 62줄·JARN·IMGOLD·FEIMGOLD·관문/조회문 해시·블록 순서. 10블록 `bash -n` 통과, 차분 [`d4/blocks-d4.v161-to-v167.diff`](d4/blocks-d4.v161-to-v167.diff), 해시 [`d4/blocks-d4.sha256`](d4/blocks-d4.sha256).
- **대역 r1: 12사례 49/49, 금지 명령0줄**([`d4/standin/scenarios-v167-r1.txt`](d4/standin/scenarios-v167-r1.txt), 장치 차분 `harness-v161-to-v167.diff`, 실행 파일 해시 `runs-r1.sha256` 1,421개). x01 정상 전 구간·JARM 기록, x02 UF2 뒤 U되돌림, x03 U1-되돌림, **x04 옛 후보 jar → RARX·JARN 통과 후 JARM에서 재기동 전 멈춤 → U1-되돌림 원복**, x05 b5cd1d0 jar → RARX에서 먼저 멈춤, x06/x12 JARM 없음/변조, x07~x10 옛 판 혼합·기록 폴더·판 표지, x11 FEIMGOLD 불일치.
- 판단 범위: 서버 빌드 jar의 항목 내용이 로컬 bootJar와 같다는 전제는 b5cd1d0에서 로컬·보존 jar 항목 차이0(v158 근거)으로만 확인됐다. 다르면 U1이 재기동 전에 멈추는 쪽이다. V161D4의 V96·관문·손실 조회 경계 22사례(96/96)는 이번에 바뀌지 않은 입력이라 다시 돌리지 않았다. **실행 승인 범위는 아님** — 새 고정 실행안은 총괄 판정 대상이다.

## 8. 최종 전체 회귀 1회 (§4-6) — 99e6ab10

[`full.sh`](full.sh)(413차 `full.sh`와 같은 명령 `./gradlew cleanTest test --continue`, 기본 게이트·진단 감시·PG 환경 변수 끔). 작업 사본 깨끗(차분 해시 e3b0c442…). **16:06:32~16:27:07 KST, 종료값1.** 원문 `runs/full-99e6ab10`.

| 모듈 | 결과 |
|---|---|
| core-engine | 298 = 성공291·건너뜀7 |
| exchange-adapter | 11 = 성공11 |
| strategy-lib | 113 = 성공113 |
| web-api | **1,400 = 성공1,307·실패12·건너뜀81** — 실제 시험 사례 1,388건 실패0. 실패12는 전부 클래스 종료 뒤 외부 연결 감시 판정(`executionError`) |

- 외부 연결 감시: 190클래스 확인, **차단16**(전부 `resolve api.upbit.com [HttpClient-N-Worker-0]`, JDK 프레임만이라 클래스 귀속은 근사). b2976905 전체 회귀의 차단104에서 줄었지만 0은 아니다. 대상 12클래스(DynamicPaperBuySameTickWatchlist·DynamicPaperTrading·DynamicScanTransitionGuard·DynamicTradingSlWatchdogRecovery·EntryPriority 회차 순서·거래소 DOWN 복구·정지 롤백 재시도·N15FinalApprovalBoundary·O1O3LogContract·SchedulerProfileCleanup·Sma20LiveAlignedGaps·SqlTraceSelfTest)와 PG의 SettlementRoundingPg는 이번 제품 변경과 무관한 기존 격리 공백이며 시험 보완은 하지 않았다.
- 이번 변경 관련: SubmitPollRaceTest 11/11, ExecutorBarrierTest·탐침 통과(실행 원문 "옛 조건=true 장벽 성립=false"), SchedulerProfileCleanupTest의 실제 사례 2건 통과(413차 경합 실패 사례 포함). SubmitPollRacePgTest는 환경 변수가 없어 건너뜀(9로 집계).
- 건너뜀: web-api 81 = PG 환경 변수 없는 클래스 등(클래스별 목록은 `summary.txt`). 413차 72 대비 증가분은 새 PG 하위 클래스 9다.

## 9. 비정시 자료 9개 시험 (§4-5 후반)

**이번 세션에서 진행하지 않았다.** 경합 재현·후보·PG·전체 회귀·jar·D4를 우선했다. 판단 시계와 봉 생성 기준의 대응과 공용 준비 코드 경계 탐침은 다음 작업으로 남긴다. 이번 전체 회귀에서 이 9개 시험의 실제 사례 실패는 없었다(실행 16:06~16:27로 정시 직전 창이 아니어서 탐침 근거는 아니다).

## 10. 남은 판단

1. V167D4 초안의 JARM 대조 추가와 새 고정 실행안(총괄). U1 이후 재개 여부.
2. 제출 진행 중 폴러의 일시 UNKNOWN(§4 끝)을 진행 표식으로 막을지.
3. 전체 회귀 차단16·SettlementRoundingPg 차단2의 시험 격리 보완 범위.
4. 과거 G2(60초 제출 지연)와 이번 경합의 관계는 추가 근거 없음 — 미확정 유지. 운영에서 실제 발생 여부는 진행 주문0까지만 확인했다.
5. 11-16 10:25 U0 상태 확인은 V161D4(c8520c55) 원문으로 하는 기존 허용 그대로다. 이 초안과 섞지 않는다.

운영 변경 없음. 운영 배포·DDL·세션 생성·운영 DB 조회 없음.
