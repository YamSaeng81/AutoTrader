# O1·O3 로컬 최소 후보 · O2 연결 · N15 실제 PG 잠금 경계 · W6 실제 jar·배포 초안 (총괄 v1.134 §2·§3-6·§5, 2026-11-07, 로컬)

**개발 결과이며 총괄 판정 전이다.** 서버·운영 DB·cron·세션은 건드리지 않았다. 배포·복구 초안은 **미승인이며 서버 실행 금지**다. 로컬 통과를 운영 설치 승인으로 보지 않는다.

## 1. 후보

| 항목 | 값 |
|---|---|
| 기점 | `1899b49f633f8aefdc66b15c42debdaba8b3852c` (운영 #26) |
| 후보 | **`0d142aa213bb7049fa942e73aa4b652a35e2ed90`** = 원격 `candidate/o1o3-log-1899b49` (`code/remote.txt`) |
| 커밋 | `b9f16a6` O1·O3 제품 변경과 시험, `0d142aa` N15 PG 시험(시험 파일만) — `code/candidate-commits.txt` |
| 변경 경로 | 제품 2개(LiveTradingService·DynamicTradingService, 각 +8/−5), 시험 3개(O1O3LogContractTest 신규, PoolLossLimitTest +1 시험, N15PgLockWaitExpiryTest 신규) — `code/1899b49-0d142aa.diff` |

### O1 — 강제 갱신 로그 고정 문구 (두 엔진의 `forceRefreshPrice` 안 두 줄만)

- INVALID_PRICE WARN
  - 이전: `… 발행하지 않음 (market={}, trade_price={})`, 인자는 REST market과 가격 원문.
  - 이후: `… 발행하지 않음 (가격 분류={})`, 인자는 `0` 또는 `음수`뿐.
- catch ERROR
  - 이전: `… 강제 복구 실패 (coin={}): {}`, 인자는 `e.getMessage()`.
  - 이후: `… 강제 복구 실패 — 조회·변환 예외(원문 생략)`, 인자·Throwable 없음.
  - 수준은 ERROR 그대로다(WARN으로 낮추지 않음).
- 결과(INVALID_PRICE·FAILED·STALE·PUBLISHED), 이벤트 발행, 관측 시각, 감시기 주기·120초·재경보·연속 횟수·주문 경로, 로그 수준과 건수는 바꾸지 않았다. 새 알림도 없다.
- 메서드 밖의 다른 ERROR 원문은 손대지 않았다. 감시기 경보 본문(`trade_price 0` 등)도 이번 범위 밖이라 그대로다.
- 승인 관측 정규식 `\[(Dynamic\]\[)?§9\] REST 강제 갱신 시세가 가격이 아님 . 발행하지 않음 \(`의 앞부분과 `발행하지 않음 (` 경계를 유지했다.
  - `obs-bench/`: #26 블록의 `n17obs` 원문에 후보 문구 2줄과 옛 문구 1줄을 넣었다. LIVE 2·DYNAMIC 1로 셌고, ERROR·손절 줄은 세지 않았다(종료 0).
  - 그래서 관측 초안의 차분은 없다. F/배포 블록은 바꾸지 않았다.
- **coin 인자도 뺐다.** 총괄 지시 "값은 엔진 구분·0/음수 같은 유한한 분류만"을 따랐다. coin은 세션 설정값이라 REST 원문은 아니지만 유한한 분류도 아니다. 이 줄만으로는 어느 코인인지 알 수 없고, 같은 주기의 감시기 경보·로그가 세션·코인을 알려 준다. 넣을지는 판단 사항이다.

### O3 — 실시간 손절 문구 중립화

- LIVE
  - 로그: `실시간 손절 발동 (WS): …` → `실시간 손절 발동: …`
  - 새 매도 사유: `실시간 손절(WS) — 손익률 …` → `실시간 손절 — 손익률 …`
- DYNAMIC
  - 로그: `[Dynamic] 실시간 손절(WS): …` → `[Dynamic] 실시간 손절: …`
  - 새 매도 사유: `실시간 손절(WS) — pnl …` → `실시간 손절 — pnl …`
- 바꾸지 않은 것
  - 조건·가격·손익·ExitReason.STOP_LOSS·매도 수.
  - 텔레그램 순서: LIVE는 성공 뒤 전송, DYNAMIC은 기존 순서.
  - `실시간 익절(WS)`은 이번 범위 밖이다.
- 과거 저장 사유 `실시간 손절(WS)`
  - DB·문서 이력은 고치지 않는다.
  - 분류(`startsWith("실시간 손절")`)는 그대로 STOP_LOSS다.
  - API 대조군 `DynamicSessionPaperVisibilityTest`·`ExitReasonRecordingTest`는 수정 없이 통과했다.

### O2 — 동작·ERROR 유지, 시험 연결만

- 기존 `PoolLossLimitTest.unappliedFillBlocks`는 거절만 단언한다. `firstBuyOfStageIsInScope`는 반영 뒤 계산만 단언한다. 둘을 한 흐름으로 잇는 시험은 없었다.
- 그 연결만 보완했다: `unappliedFillThenAppliedValuatesNormally`.
  - 반영 전: `valuate`가 null이고, `POOL_VALUATION_UNKNOWN`으로 진입을 막는다.
  - 같은 주문의 반영값이 들어온 뒤: 같은 신선한 시세로 순자산 −608.7을 계산하고 승인한다.
- 제품 변경은 없다. 유예·건너뛰기·0 채우기도 없다.

## 2. 시험 실행 (`runs/`, 실행마다 HEAD·작업 사본 상태·소스 해시·gradle.log·XML·종료값)

| 실행 | 작업 사본 | 대상 | 결과 |
|---|---|---|---|
| r1 | 1899b49 + 새 시험 | O1O3 | 시험 코드 컴파일 오류(검사 예외 선언 누락). `NOTE.txt` |
| **r2 실패 먼저** | 1899b49 + 새 시험 | O1O3 17 | **12 실패.** O1 WARN 4건(REST market의 줄바꿈·가짜 표지·숫자 원문이 그대로 출력), ERROR 4건(예외 메시지·URL·`token=`·coin), O3 4건(`실시간 손절(WS)`). 대조군 5 통과(정상·낡은 양수, 분류 호환) |
| r3 | 1899b49 + O1·O3 제품 변경 + O1O3 시험(커밋 전, O2 연결 시험 추가 전 — `runs/r3-*/worktree.diff`) | 관련 14 클래스(`related-tests.txt`) | **149/149**(PoolLossLimitTest 21) |
| r4 | 후보 + O2 연결 | PoolLossLimitTest | **22/22** |
| **r5** | **b9f16a6 깨끗한 사본** | web-api 전체 | 1136건: 통과 1116·건너뜀 19·**실패 1** |
| **r6** | **1899b49 깨끗한 사본** | web-api 전체 | 1118건: 통과 1098·건너뜀 19·**실패 1(같은 시험)** |
| r7·r8·r9 | 1899b49 + N15 PG 시험 | N15 PG | 시험 코드 결함 3개, 각 `NOTE.txt`. ① record 구성요소 이름 `wait` ② `invokeMethod` 오버로드 추론 ③ 정리 단계 외래키(비동기 주문 처리가 정리보다 늦음). r8·r9에서도 실제 잠금 대기 관측과 만료·진입 판정은 기대대로였다 |
| **r10** | **1899b49 제품 코드** + N15 PG 시험 | N15 PG 4 | **4/4**, 실행 뒤 격리 DB 행 0 |
| **r11** | 후보 0d142aa | N15 PG 4 | **4/4** |
| r12 | 후보 0d142aa, 환경 변수 없음 | N15 PG 4 | **4 건너뜀**(기본 H2 시험 영향 없음) |

- `full-compare.txt`: r5·r6 비교.
  - 공통 시험 중 결과가 다른 것은 **0**이다.
  - 후보에만 있는 시험은 18건(O1O3 17 + O2 연결 1)이다.
  - 유일한 실패 `ExecutionDriftTrackerTest#§14 record() — 음수 slippage`는 **기점에서도 같은 실패**다. 이번 변경과 무관하다.
  - 이 시험은 `driftRepo.findAll().get(0)`으로 첫 행을 읽어 다른 시험이 남긴 행에 영향받을 수 있다. 원인은 확인하지 않았고 고치지 않았다(범위 밖).
- 기존 N15 시험 재실행(의도 직전 환원·늦은 체결)은 하지 않았다. 해당 제품 코드(CapitalReservationService·EntryPriorityRounds·OrderExecutionEngine)는 1899b49와 같다. 다만 r5·r6 전체 시험에는 N15EntryExpiryTest·N15FinalApprovalBoundaryTest가 포함돼 통과했다.

## 3. N15 실제 PostgreSQL 잠금 경계 (`N15PgLockWaitExpiryTest`)

- **격리 DB**: 로컬 컨테이너 `n15-pg`(:15434, Flyway V1~V94, 운영과 무관)다. 환경 변수 `N15_PG_URL`이 있을 때만 돈다.
- **잠금 걸기**: 다른 트랜잭션이 행을 `FOR UPDATE`로 쥔다. 승인 스레드는 E−1ms 가짜 시계로 회차를 처리한다.
- **실제 대기 확인**: 세 가지를 모두 확인한 뒤에 시계를 움직이고 잠금을 푼다.
  - `pg_stat_activity.wait_event_type = 'Lock'`. 관측값은 `wait_event=transactionid`였다.
  - `pg_blocking_pids`에 잠금을 쥔 쪽의 pid가 있다.
  - 승인 스레드 스택이 `reserveForBuy` 안의 대기 지점에 있다.
- 기존 EntryClock을 썼다. 제품에 새 시계·트랜잭션·잠금 정책은 만들지 않았다.

| 경우 | 대기 지점 | 시계 | 결과 |
|---|---|---|---|
| ① LIVE | `account_capital_lock id=1`(예약 첫 단계 `lockAccount`) | 대기 중 E | `ENTRY_EXPIRED` "잠금 뒤". 가용 20000·슬롯 0·매수 의도 0(모든 상태)·거래소 0·원래 신호에 사유 |
| ② DYNAMIC | `dynamic_session`(차감 전 `em.refresh(PESSIMISTIC_WRITE)`) | 대기 중 E | `ENTRY_EXPIRED` "차감 직전". 가용 10000·슬롯 0·의도 0·거래소 0·신호에 사유 |
| ③ LIVE 대조 | 같은 계좌 잠금 실제 대기 | E−1ms 유지 | 정상 진입(20000→15000·슬롯 1·의도 1) |
| ④ DYNAMIC 대조 | 같은 세션 행 실제 대기 | E−1ms 유지 | 정상 진입(10000→2000·슬롯 1·의도 1) |

- 기존 H2 시험 ⑧은 DYNAMIC만, 그리고 순서만 확인했다. 이번에는 LIVE의 실제 대기 지점이 세션 행이 아니라 계좌 잠금 행임을 확인했다. LIVE 예약은 세션 행을 잠그지 않는다.
- 한계
  - 로컬 PostgreSQL 15(timescale 이미지)이며 운영 DB 버전·부하와 같다고 주장하지 않는다.
  - 접수·부분 체결·늦은 체결 조합 전체와 DYNAMIC 전체 경로를 검증한 것이 아니다.

## 4. W6 — 실제 로컬 bootJar 대조 (`jars/`)

- **빌드**: 깨끗한 작업 사본에서 `:web-api:clean :web-api:bootJar -x test --offline`로 했다. 1899b49(11:05:02~22)와 0d142aa(11:05:22~30) 모두 종료 0이고, `build-status-*.txt`는 비어 있다.
- **비교기**: 수용된 `k23-prep-51dcee5/jars/jarcmp.py`를 썼다. 항목 해시 비교다.
  - 다시 빌드한 1899b49와 보존 1899b49 jar(`74fd7c83…`, #26 기준)는 **차이 0, 881 881**이다. 파일 해시가 다른 것은 빌드 시각 때문이다.
  - **1899b49 → 0d142aa**: `변경 DynamicTradingService.class`, `변경 LiveTradingService$PriceSnapshot.class`, `변경 LiveTradingService.class`. **3항목, 추가·삭제 0, `(끝) 881 881`**이다. 보존 jar 기준으로도 같다.
- **메서드 단위**(`method-diff-1899b49-0d142aa.txt`)
  - 두 서비스 모두 164/164·134/134 메서드 중 `forceRefreshPrice`·`doOnRealtimePriceEvent` 2개만 다르다.
  - `$PriceSnapshot`은 `javap -c -p`가 같고 줄 번호표만 다르다(3547→3550, 주석 줄 추가).
- 후보 jar 파일은 커밋하지 않는다(로컬 `D:/tmp/o1o3/jars/`). 해시는 `jar-sha.txt`에 있다.

## 5. W6 — 다음 배포·복구 초안 D1 (`w6/`, **미승인·서버 실행 금지**)

- **입력**: 커밋된 #26 최종 블록 `n20-k26-final-1899b49/blocks-k26` 여덟 개(원본은 바꾸지 않았다).
- **생성기 `mkdraft.py`**: 바꿀 자리마다 맞는 횟수를 강제한다. 바꾼 것만 적는다(`k26-to-d1.diff`).
  - 판 표지 `UB=98` → `UB=O13D1`(U0~U되돌림 전부). 기록 폴더 `~/maint-n20` → `~/maint-o13`.
  - `OLD=1899b49`, `NEW=0d142aa`.
  - `IMGOLD`는 #26 이미지 `sha256:426a09a2…53a1`이고, U0는 `~/maint-n20/image-new.txt`와 같은지 확인한다.
  - 보존 태그
    - U1은 `$REPO:1899b49`(= #26 이미지)를 새로 만든다.
    - #26 U1이 만든 `$REPO:ee509cf`(= #25 `200f2f57…`)는 `IMGPREV7`로 U0가 확인만 한다.
  - `DIFFN`은 위 5개 경로, `RARX`는 위 jar 3줄이다. 실제 `git diff --name-only`·실제 jar 차이와 대조해 같다(`diffn-rarx-check.txt`).
  - 안내 문구: ee509cf/#25 → 1899b49/#26.
- **확인**
  - `bash -n` 8/8.
  - 판 표지 대역(`marker-check.txt`): U1~U되돌림 6블록 × {O13D1 통과, 98·N20D2·빈 값 멈춤}, U0 앞부분 4입력(바른 값만 통과).
- **하지 않은 것**
  - 블록 전체 대역 실행(서버 명령 흉내), 서버 실행, 계획서 반영(§ 번호 부여), F 변수·F1 핀 변경.
  - 실제 실행 전에는 총괄 출처 검토가 필요하다. 운영 핀이 바뀌면 기점(OLD)·IMGOLD부터 다시 정한다.

## 6. 남은 판단 (총괄)

1. O1 ERROR·WARN에서 coin까지 뺀 것이 맞는지 판단이 필요하다. 넣으면 세션 설정값 1개가 인자로 남는다.
2. 이 후보를 운영 반영 대상으로 삼을지, 그리고 그 시기(12-01 전 또는 뒤)를 정해야 한다. 운영 배포·재기동 권한은 이번 주말 지시에 없다.
3. `ExecutionDriftTrackerTest` 기존 실패 1건을 별도로 조사할지 정해야 한다(1899b49에서도 실패).
