# v1.168 — 외부 연결 격리·수동조정 뒤 늦은 응답 PG·비정시 자료 탐침·최종 SHA 검증 (2026-11-15~16 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.168 / 416차](../review-7e77e34a/README.md) §3·§5를 진행했다. 가지는 `candidate/order-poll-race-b987d7a7`(작업 사본 `D:/tmp/race-b987d7a7`)이고, **이번 커밋은 모두 시험만**이다(제품·빌드·프런트 블롭 목록이 99e6ab10과 같음, §5). 로컬 실제 PG는 격리 컨테이너 `v152-pg`(127.0.0.1:15437)의 실행별 새 DB다. 실행 원문은 `runs/`(근거 원문 `D:/tmp/v168-ev/runs`의 사본)에 있다.

| 커밋(99e6ab10 위, 전부 시험만) | 내용 |
|---|---|
| be7e8e4f | 외부 연결 출처 추적(`TEST_NET_TRACE`, 기본 꺼짐) + WS 대역 3클래스 (§1) |
| 57867533 | `SubmitPollRaceTest` 사례 H (§2) |
| ac1ef49d | 공용 준비 코드 `useAlignedRound` + S04a 정시 회차 (§3) |
| 3f2cce80 | `AlignedRoundBoundaryProbeTest` 결정적 경계 탐침 (§3) |
| **0e1d2a02** | `TestRoundKeys` 회차 키 등록부 — 공용 준비 코드·BtcGate (§3). **최종 후보 머리, 원격 푸시** |

탐침 전용 변경(커밋 안 함)은 [probe/probe-wt.diff](probe/probe-wt.diff)·[probe/probe-fix.diff](probe/probe-fix.diff)와 [probe/ProbeTimeExtension.java](probe/ProbeTimeExtension.java)에 있다. 탐침 원문은 실행마다 `probe/runs/*/meta.txt`·`probe-times.tsv`, 인용한 6회의 XML, 전체 XML·gradle 로그 압축본(`probe/probe-*-all.tar.gz`), 묶음별 집계 `probe/agg-*.txt`.

## 1. 외부 연결 출처 격리 (§3-1)

| 실행 | 머리 | 결과 |
|---|---|---|
| n01 추적 12클래스 | be7e8e4f 이전(추적 기능만 추가) | 89 = 실패7, **차단14 재현**, 로그의 WS 연결 시도 줄14 |
| n02 추적 SettlementRoundingPg(PG) | 같음 | 17 = 실패1, 차단2 |
| n03 보완 12클래스 | be7e8e4f 내용 | **82 = 성공82, 차단0**, WS 줄0 |
| n04 보완 SettlementRoundingPg(PG) | 같음 | **16 = 성공16, 차단0** |
| full-diag(추적 켬, 진단용) | **be7e8e4f 깨끗** | **종료값0.** web-api 1,388 = 성공1,307·건너뜀81·실패0, 차단0·추적 기록0. 20:27:37~20:47:30 |

- **계측:** 이름 해석은 `HttpClient-N-Worker-0`에서 JDK 프레임만으로 일어나 호출자를 알 수 없었다. JDK HttpClient는 생성 시점의 기본 `ProxySelector`를 잡고 요청·WebSocket 열기 때 **호출 스레드**에서 `select(uri)`를 부른다. 감시 설치 때(어떤 HttpClient 생성보다 먼저) 기록만 하고 원래 선택기에 위임하는 추적 선택기를 `TEST_NET_TRACE=1`일 때만 넣었다(기본 꺼짐, 동작 불변). 차단 기록에도 시각·당시 클래스를 붙였다.
- **출처(입증):** 18건 모두 `DynamicTradingService.refreshWsSubscription → WsSubscriptionManager.recompute → UpbitWebSocketClient.connect`(wss://api.upbit.com/websocket/v1). 호출 스레드가 `Test worker`인 첫 연결은 **DynamicPaperBuySameTickWatchlistTest(executeBuy 뒤)·DynamicTradingSlWatchdogRecoveryTest(warnStaleSlCheck 뒤)·SettlementRoundingPgTest(executeBuy 뒤)** 세 곳이고, 나머지는 실패 뒤 `upbit-ws-scheduler`의 재연결 예약이 캐시된 컨텍스트에서 이어져 **뒤 클래스에 귀속**된 것이다(Dynamic*·EntryPriority·ExchangeRecovery·KrwRestore·N15·O1O3·SchedulerProfile·Sma20·SqlTrace는 출처가 아님).
- **보완(be7e8e4f, 시험만):** 세 클래스에 `@MockBean UpbitWebSocketClient`. 세 시험 모두 WS 연결을 단언하지 않는다 — 구독 목록 계산은 `WsSubscriptionManager`가 클라이언트와 무관하게 그대로 한다. 거래소 DOWN/복구(ExchangeRecoveryRestartTest)·정산 단언은 그대로이며 위 실행에서 통과했다. 감시는 기본 활성 그대로이고 끝에 원래 보안 관리자·선택기로 되돌린다(`restored_security_manager=null`).

## 2. 수동조정 뒤 늦은 제출 응답 — 실제 PG (§3-2)

`SubmitPollRaceTest` 사례 **H**(57867533, LIVE·DYNAMIC 매수). PG 하위 클래스 `SubmitPollRacePgTest`가 그대로 상속한다.

순서(신호로 고정): 제출 스레드를 거래소 호출 안에 세움 → 폴러 조회 "없음"으로 **UNKNOWN 커밋** → `UnknownOrderAdjustmentService.record`(3,000 반환, `now`=31분 뒤로 MIN_AGE 전제 충족) → 제출 응답 도착 → `recordSubmitOutcome`의 수동조정 분기 → 같은 응답을 같은 트랜잭션 경계로 한 번 더 전달.

| 실행 | 결과 |
|---|---|
| h01 H2 `SubmitPollRaceTest` | **13 = 성공13**(A~E·H×2·종류6), 차단0 |
| h02 실제 PG `SubmitPollRacePgTest` | **16 = 성공16**(상속 13 + PG 전제·F·G), 차단0 |
| h03 음성 대조: 제품의 수동조정 분기만 끔(`if (false && …)`), H만 | **2 = 실패2**(관측 호출 0회). 실행 뒤 제품 파일 원복·차분0 확인 |

- 단언: 상태 **UNKNOWN 유지**·주문번호 미부착(폴러의 같은 분기와 같음)·체결량/정산 버전/수수료 출처 보존·`applied_executed_funds` null, 세션 잔액은 **조정 뒤 값 그대로**(이중 반환 없음)·포지션 0, 거래로그 `…→SUBMITTING→UNKNOWN`에서 추가 전이 없음, `later_observation`에 "제출 응답 늦게 도착·uuid", 불일치 이력 `AFTER_MANUAL_ADJ` **1건**, 경보 1회. 재전달 뒤 불일치 1건·경보 1회·`later_observed_at` 불변.
- **REQUIRES_NEW 관측:** `recordLaterObservation`은 제출 스레드 `order-exec-1`에서 안쪽 트랜잭션(`…UnknownOrderAdjustmentService.recordLaterObservation`)으로 **예외 없이 2~6ms(PG)** 에 끝났다. 그 커밋 직후(제출 결과 트랜잭션은 아직 열림) 별도 자동 커밋 연결에서 관측 값이 보였다 — 따로 커밋된다. 두 표(`order_manual_adjustment`·`order_settlement_discrepancy`)에는 `orders` 외래 키가 없어 바깥의 `FOR UPDATE`와 충돌하는 잠금이 없다(V82·V91 정의). lock_timeout 10s 대기 없음.
- 발견한 제품 결함 없음. 제품 변경 없음.

## 3. 비정시 자료 9개 시험 탐침 (§3-3)

**방법.** 제품이 판단 시계(`EntryClock`)와 벽시계(`Instant.now()`)를 섞어 쓰므로(DYNAMIC 판단 시계 9곳·직접 46곳 등) 시계만 옮기는 방식은 실제로 없는 불일치를 만든다. 그래서 ① **실제 벽시계**로 매시 :50~:02 창에 대상 클래스를 반복 실행하고 사례마다 실제 시작·종료를 남겼고(별도 작업 사본 `D:/tmp/probe-wt`, be7e8e4f + 시각 기록 확장 `ProbeTimeExtension` — 미커밋, 실행마다 `meta.txt`에 상태·차분 해시), ② 제품 판정 함수를 직접 부르는 결정적 탐침을 더했다.

대상 9개: 공용 준비 코드 대표 `Sma20LivePaperSameInputTest`, `BtcGateUnknownEntry`·`DailySma20LiveOrderPath`·`DailySma20PaperForwardPath`·`DynamicBlackSwanCooldownCrossKind`·`EntryPriorityEndToEnd`·`ExitAbArmContract`·`SharedPaperLoopIsolation`, N15 DYNAMIC 자료 `N15EntryExpiry`. 21:05부터 공용 준비 코드 하위 클래스 11개를 더해 20클래스.

| 탐침 | 범위 | 결과 |
|---|---|---|
| v1 p-1115-2050~2102 (9클래스, 20회, 사례 1,340) | 20:50~21:03 | **S04a 12회 연속 실패 20:53:35~21:00:39**, 그 밖 분 통과. N15EntryExpiry "LIVE 접수 LATE" 1회(20:50:51 냉시동 실행, 거래소 호출 1) — 뒤 19회 재현 없음. 나머지 8클래스 실패0 |
| v2 p-1115-2150~2202 (보완 전, 20클래스, 10회, 사례 1,360) | 21:50~22:03 | S04a 21:54~21:59 5회 실패(재현). **A04a(정시 변형) 창 안 통과** — 결함 아님. BtcGateUnknownEntry "승인 시점" 3회 실패(21:52:09·22:00:21·22:03:02, 회차 접수 0) — 아래 별도 |
| 보완 사본 f-1115-2150~2203 (ac1ef49d, S04a·A04a) | 21:50~22:03 | **26회·사례702 실패0** |
| v2 p-1115-2250~2302 (보완 전, 20클래스, 11회) | 22:50~23:04 | S04a 6회·BtcGate 5회 재현, N15 LATE 1회(22:51:00, 창 첫 실행). 23:06 종료 |
| 보완 사본 f-1115-2250~2301 (0e1d2a02, 20클래스) | 22:50~23:03 | **10회·사례1,360 실패0** |
| t03 결정적 탐침 `AlignedRoundBoundaryProbeTest`(3f2cce80) | 대표 분 :52·:53·:55·:59·:00·:01 | 옛 자료 E−1ms 거절 창 [:53, :01)이 벽시계와 일치. 옛 자료를 D에서 판정하면 :59·:00도 거절. 정시 회차는 접수 C+1초·D·E−1ms 모두 통과 |

**입증된 결함과 보완(ac1ef49d, 시험만).** 공용 준비 코드의 마지막 봉은 실제 현재 W−61분(정시 아님)이고 회차 E = 봉 시작+70분 = W+9분이다. S04a는 판단 시계를 E−1ms에 둔다. 최신성 기준은 floor시(판단−2분)−1시간이므로 실제 분이 :53~:59면 다음 정시로 넘어가고, :00이면 마지막 봉(전 시 :59)이 기준보다 앞서 "최신 완료 봉 없음"(원문: `마지막 완료 봉 10:55:48Z, 필요 11:00:00Z 이후(4분 늦음)`)으로 거절된다. 운영 봉은 정시다. 공용 준비 코드에 `useAlignedRound()`(마지막 봉 = 직전 완료 정시 봉, 판단 시계 = 접수 C+1초 고정, JVM 안 사용 키 집합으로 유일성)를 넣고 **S04a만** 쓴다. 기대(E−1ms LIVE 진입·PAPER 진입)는 그대로다. 공용 `approveRound()`는 D를 처리 인자로만 넘기고 판단 시계는 실제 시각이라 D 경계에는 닿지 않는다. 판단 시계를 옮기지 않는 8개 시험은 자료·판단이 같은 벽시계이고 2분 유예로 어느 분에서도 최신성에 걸리지 않으며 창에서도 실패0이라 바꾸지 않았다.

| 관련 확인(ac1ef49d) | 결과 |
|---|---|
| t01 Sma20LivePaperSameInput | 12/12 |
| t02 공용 준비 하위 12클래스(H2) | 81/81, 차단0 |
| t04 PG 하위 3클래스(V96_PG_URL, 클래스별 새 DB) | AlignedV96PgBehavior 5 · BalanceListSnapshotPg 2 · PerformanceSnapshotS5Pg 1, 실패0·차단0 |

**두 번째 입증 결함 — 클래스 사이 회차 키 충돌(0e1d2a02, 시험만).** v2의 BtcGateUnknownEntry "승인 시점"(회차 접수 기대1·실제0) 실패는 시각 창과 무관했다(22:03:02도 실패). 탐침 사본에서만 `EntryPriorityRounds` 로그를 INFO로 올리고(미커밋) 20클래스를 창 밖에서 6회 돌려 **3회 재현**했다(p-1115-2213~2219). 실패 실행에만 "회차 마감 뒤 도착" 2줄(LIVE#3 KRW-CRO·DYNAMIC#1 KRW-IOTA)이 있다. 원문(p-1115-221620): 같은 컨텍스트(같은 회차 빈)의 앞 클래스 `B01RealNextSellBarTest`가 **22:16:54.114**에 회차 `12:15:54Z`를 접수·확정했고, 같은 초 **22:16:54.512**에 BtcGate가 같은 키(실제 현재−61분, 초 절삭)로 접수해 처리된 키로 거절됐다. 첫 창(9클래스)에서는 BtcGate가 첫 클래스라 앞 클래스가 없었다. 두 클래스만 돌린 12회(k-1115-2208~2212)에서는 B01의 마지막 처리 키가 다른 초여서 재현되지 않았다 — 같은 초에 앞 클래스가 바로 그 키를 처리했을 때만 겹친다. 전체 회귀의 클래스 순서(B01 → 환경 변수 없어 건너뛰는 PG 클래스 → BtcGate)에서도 같은 위험이 있다.

- 보완: 시험 JVM 회차 키 등록부 `support/TestRoundKeys.claim`(이미 나간 키면 1ms 씩). 공용 준비 코드(기존 키·정시 회차)와 BtcGate 승인 시점이 여기서 키를 받는다. 봉·관문·승인 기대는 그대로.
- 검증: t05(B01·BtcGate·Sma20 두 클래스) **32/32**. 보완 사본(0e1d2a02 + 같은 INFO 로그) 20클래스 창 밖 6회 **실패0**(f-1115-2221~2227). BtcGate 접수 키가 6회 중 5회 `.001Z`·`.002Z`로 옮겨졌다 — 충돌이 실제로 생겼고 등록부가 피했다.
- 남은 위험(관측 없음, 변경 안 함): 같은 컨텍스트를 쓸 수 있는 다른 회차 사용 시험(EntryPriorityEndToEnd·DailySma20LiveOrderPath·SharedPaperLoopIsolation·BtcGateSupplyEndToEnd·EntryRoundsCrossRoundPaperDelay)도 현재−61분 키를 쓴다. DailySma20LiveOrderPath는 같은 키 12:15:54Z를 2초 뒤 접수·확정했지만 다른 컨텍스트라 충돌이 아니었다.

**미해결 관측.** N15EntryExpiry "LIVE 접수 LATE"(④ 자기 관문 대기)의 거래소 호출 1이 **보완 전 사본에서 2회**(20:50:51·22:51:00) 나왔다. 둘 다 약 47분 쉰 뒤 창의 **첫 실행**이다. 21:50 창의 첫 실행과 그 밖 보완 전 실행 45회, 보완 사본 실행 42회(첫 실행 포함)에서는 나오지 않았다. 만료 거절·예약·의도 0은 맞고 거래소 호출 수만 1이다. 같은 클래스의 `createOrder` 대역은 응답을 정하지 않아 null 을 돌려주고 비동기 제출(`submitPreparedOrder`)이 `recordSubmitOutcome`에서 NPE로 끝난다(19d1cc61 이전 코드도 같은 곳에서 역참조 — 실제 클라이언트는 null 을 돌려주지 않는다). 앞 사례의 비동기 제출이 대역 초기화 뒤 도착했을 가능성이 있으나 입증하지 못했다. 원문은 runs/p-1115-205003.

## 4. 최종 SHA 전체 회귀 (§3-4) — **0e1d2a02**

[`full.sh`](full.sh)(415차와 같은 명령 `./gradlew cleanTest test --continue`, 기본 게이트·진단 감시·추적·PG 환경 변수 끔). 작업 사본 깨끗(차분 해시 e3b0c442…). **23:05:53~23:25:47 KST, 종료값0.** 탐침 창(:50~:02)과 겹치지 않게 보완 전 탐침을 멈춘 뒤 실행했다. 원문 `runs/full-0e1d2a02`.

| 모듈 | 결과 |
|---|---|
| core-engine | 298 = 성공291·건너뜀7 |
| exchange-adapter | 11 = 성공11 |
| strategy-lib | 113 = 성공113 |
| web-api | **1,392 = 성공1,310·건너뜀82·실패0** |
| 합계 | **1,814 = 성공1,725·건너뜀89·실패0** |

- 외부 연결 감시: 191클래스, **차단0**, JNDI 이름 서버 권한 확인3(패킷 없음)·자기 인터페이스72, 끝에 원래 보안 관리자 복원.
- 415차 1,822와의 차이: 그때 실패12는 클래스 종료 뒤 `executionError` 항목으로 따로 집계됐다(1,400 = 사례1,388 + 12). 이번 web-api = 1,388 + 사례 H 2 + 경계 탐침 1 + PG 건너뜀 1 = 1,392. 건너뜀은 `SubmitPollRacePgTest` 9→10(사례 H 매개변수 시험이 클래스 조건으로 1건 집계) 외 같다(클래스별 목록 `summary.txt`).
- ExecutorBarrierTest 통과. 전제 탐침이 의도한 풀 상태 `pool=2 active=1 queue=0 task=1 completed=0`·"옛 조건=true 장벽 성립=false"를 기록했다. 실패가 없어 새 전제 문구의 실패 시점 상태는 남지 않았다(b2976905 1회 실패의 원인 확정은 여전히 범위 밖 — 기전 재현까지).
- 진단 회귀(be7e8e4f, 추적 켬): 20:27:37~20:47:30 종료값0, 차단0·추적 기록0 — 외부 연결 격리 직후 상태.

## 5. 산출물 연결 (§3-5)

- 99e6ab10 → **0e1d2a02** 차분은 시험 11경로뿐이다(비시험 0). 시험 밖 블롭 736개의 (블롭·경로) 목록 sha256이 두 커밋에서 같다(`acb84484…`). 제품·빌드·프런트 입력이 같으므로 99e6ab10의 jar `f7450f13…`·JARM·프런트 8경로·경합 PG 결과를 연결한다.
- 추가로 0e1d2a02에서 `./gradlew :web-api:clean :web-api:bootJar -x test --offline`(22:30, 종료값0)을 실제로 다시 빌드했다. jar 파일 sha256 `be723125…`(빌드 시각이 들어가 달라짐), **항목 내용 목록 895줄이 99e6ab10 jar 와 바이트 동일, JARM `7df5758f…` 재현**([jars/](jars/)). 처음 로컬 계산값 `749cb01d…`는 Windows 파이썬 표준 출력이 줄 끝을 CRLF로 쓴 탓이다(같은 방식으로 99e6ab10 jar 도 `749cb01d…`). LF로 맞추면 둘 다 기대값이다. 서버 U1은 리눅스 `python3`라 LF다.
- 기록할 SHA: **최종 시험 결과 = 0e1d2a02**(§4), **연결한 제품 검증 = 99e6ab10**(415차 경합 PG·관련71·jar) + 0e1d2a02 bootJar JARM 재현.

## 6. 새 고정 실행 출처 (§3-6) — V168D4 초안 (🔴 실행 승인 아님)

- [`d4/blocks-d4`](d4/blocks-d4/) — 생성기 [`d4/mkdraft9.py`](d4/mkdraft9.py)가 **V167D4 원본**(v167-race-20261115/d4/blocks-d4)에서 값만 바꾼다(바꾸는 곳마다 횟수 확인, 원본 표기 잔존 시 멈춤). 바꾼 값: 판 표지 V168D4·`~/maint-v168`·판 문구 v1.168, **NEW 0e1d2a02**, **DIFFN 133경로**(시험103·제품 Java 20·V96 1·build.gradle 1·프런트 8, 실제 `git diff --name-only b5cd1d0 0e1d2a02`; 비시험 30경로는 99e6ab10 목록과 같음). 그대로: OLD·GS·RARX 62줄·JARN·**JARM 7df5758f…**(생성기가 기대값과 다르면 거부)·IMGOLD·FEIMGOLD·관문/조회문 해시·블록 순서·JARM 대조 위치. 10블록 `bash -n` 통과, 차분 [`d4/blocks-d4.v167-to-v168.diff`](d4/blocks-d4.v167-to-v168.diff)(39줄), 해시 [`d4/blocks-d4.sha256`](d4/blocks-d4.sha256).
- **대역 r1: 12사례 49/49, 금지 명령0줄**([`d4/standin/scenarios-v168-r1.txt`](d4/standin/scenarios-v168-r1.txt), 22:31~22:35). 장치는 V167 대역 사본에서 NEW·jar·DIFFN만 교체([`harness-v167-to-v168.diff`](d4/standin/harness-v167-to-v168.diff)), 시나리오는 판 값만 교체([`scenarios-v168.sh`](d4/standin/scenarios-v168.sh)). x01 실제 0e1d2a02 bootJar 로 전 구간·JARM 기록, x04 옛 후보 jar(88f06345 입력) RARX·JARN 통과 뒤 JARM 에서 재기동 전 멈춤 → U1-되돌림, x05 b5cd1d0 jar RARX 에서 멈춤, x06/x12 JARM 없음/변조, **x07 옛 V167D4 U변수 + 새 블록·x08 새 U변수 + V167D4 블록·x09 기록 폴더 maint-v167·x10 UB=V167D4 거부**, x11 FEIMGOLD. JARM 코드·기대가 같아 옛 jar·중복 항목 거부의 판정 논리는 V167D4 근거와 같다.
- 서버 빌드의 항목 내용이 로컬 bootJar 와 다르면 U1 이 **재기동 전에** `새 jar 항목 내용이 후보 0e1d2a02(제품 입력 99e6ab10) 목록(JARM)과 다름`으로 멈춘다. 보고할 파일: `~/maint-v168/jar-manifest-new.txt`·`jar-manifest-sha.txt`·`jar-diff.txt`, 그 뒤 U1-되돌림. 전제(서버 빌드 = 로컬 bootJar 항목 내용)는 b5cd1d0에서 로컬·보존 jar 항목 차이0(v158 근거)으로만 확인됐다.
- **복구안 잔여 위험:** 되돌림 기준 b5cd1d0 은 주문 확인 경합(415차 사례 A·D·E — 폴러가 제출 결과를 낡은 엔티티로 덮어 UNKNOWN·체결/정산값 되돌림)을 그대로 가진 판이다. U되돌림·U1-되돌림 뒤에는 그 경합이 다시 열린다.
- **대역 원문 보존:** V167 대역 원문 1,421개(로컬 `D:/tmp/v167d4s/sim/runs`, 1.2GB)와 이번 V168 1,421개를 [`standin-archive/`](standin-archive/)에 jar 제외 압축본(각 2.7MB)·jar 사본 참조 목록·기존 1,421줄 목록으로 넣었다. jar 사본 14개는 알려진 jar 3개(99e6ab10 bootJar `f7450f13…`/0e1d2a02 bootJar `be723125…`·보존 b5cd1d0 `a7ef759f…`·88f06345 입력 `0834382a…`)와 바이트 동일이다. [`restore.sh`](standin-archive/restore.sh)로 복원해 **둘 다 1,421/1,421 일치**를 확인했다.

## 7. 운영 읽기

- [11-15 22:00 N03](../n03-1115-2200/README.md): 실제 읽기 **22:08:26 KST**(재시도 22:09). 🔴 이상1 = 미분류 ERROR1(CryptoPanic 연결 시간 초과, 17:33 KST), 실자금 보유·미결0, SID 203 CREATED·20,200.00, HEAD b5cd1d0d·이미지52e12428·재시작0. SSH 연결 종료 2건은 같은 읽기만 재시도해 서버 해시 4/4.
- 11-15 15:36 이전·22:00 보고 생성(22:00:04)~22:08 사이·22:09 이후는 미관측이다.

## 8. 남은 판단

1. V168D4(NEW 0e1d2a02) 새 고정 실행안 — 총괄 판정 대상. U1 이후 재개 여부. 11-16 10:25 U0 상태 확인은 기존 V161D4(c8520c55) 원문으로 하는 기존 허용 그대로이며 이 초안과 섞지 않는다.
2. 다른 회차 사용 시험의 키 충돌 위험(§3, 관측 없음)을 같은 등록부로 넓힐지.
3. N15EntryExpiry ④ LATE 창 첫 실행 2회(보완 전 사본) — 원인 미확정.
4. 과거 G2와 경합의 관계 미확정 유지.

운영 변경 없음. 운영 배포·DDL·세션 생성·운영 DB 조회 없음. 운영 서버는 기존 `read.sh` 읽기만.
