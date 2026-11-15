# v1.166 §3 — 원문 보존·외부 연결 격리·N15 시각 의존 (2026-11-13~15 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.166 / 412차](../review-6f27b35d/README.md) §3-1~6을 진행했다. 운영 서버·운영 DB는 조회하지 않았다. 실제 PG는 로컬 격리 컨테이너 `v152-pg`(127.0.0.1:15437)의 실행별 새 DB다. **§3-5 전체 회귀 중 제품 경합 후보를 찾아 §4에 따라 재실행 전에 멈췄다(§6).**

## 결론

| 항목 | 결과 | 근거 |
|---|---|---|
| §3-1 원문 보존 | main **82e6b313**(푸시)에서 G1 근거 커밋 블롭 **236/236** 바이트 일치. 기존 SHA256SUMS·과거 근거 불변, 재생성 0 | [G1 보완 목록](../g1-submit-boundary-20261113/PRESERVATION-v1166.md) |
| §3-2 6클래스 격리 | WS 대역만 추가. 대역 전 음성 대조: WS 시도14·차단26·6클래스 실패 → 대역 뒤 **42/42·차단0** | `runs/guard-neg-6cls`, `runs/iso-6cls` |
| §3-3 감시 | SecurityManager로 모든 소켓 연결·이름 해석 검사, 루프백·자기 인터페이스만 허용, 외부는 차단+클래스 실패, 끝에 복원(`restored_security_manager=null`) | §2 |
| §3-4 N15 PG | 원인 확정·결정적 재현. 게이트 없음·500 각 **13/13**, NPE0, 잔여 행0 | §3 |
| 관련 19클래스(b2976905) | 게이트 없음 **235=성공230·건너뜀5**, 게이트50 **235=성공218·건너뜀17**, 실패0, 차단0 | `runs/rel19-gate*-b2976905` |
| §3-5 전체 회귀(b2976905, 1회) | **종료값1.** core·exchange·strategy 실패0, web-api 실패47 = 감시 실패46 + `ExecutorBarrierTest` 1. 외부 연결 차단 **104건** — 6클래스 밖에서 실제 외부 연결이 광범위했다 | `runs/int-full-b2976905` |
| 후속 격리(b987d7a7) | 입증 출처만 격리 후 해당 46클래스 **376 중 실패1, 차단0** | §4, `runs/affected46-b987d7a7` |
| **제품 경합 후보** | 5초 폴러가 제출 중 SUBMITTING을 낡은 엔티티로 UNKNOWN 덮어씀 → 커밋된 SUBMITTED·거래소 주문번호 소실 | **§6** |
| §3-6 제품 입력 | 88f06345 대비 시험 밖 경로0. 최상위23항목 중 `web-api/src/test`만 다름(프런트 트리 `ba34ee0f` 동일) | `change-paths.txt` |

## 1. 커밋·가지

`test/v166-isolation-3f9cc926`(3f9cc926 후속), **푸시 b987d7a79f4ff9220420d5377356507bec9464d6**. 모두 `web-api/src/test/`(28경로). 패치 원문 `patches/0001~0007`.

| 커밋 | 내용 |
|---|---|
| bdc92b64 | 감시 `NetworkIsolationGuard`·`NetworkIsolationExtension`(자동 등록), `OutboundNetworkGuard.uninstall`, 진단 `@AfterAll` 전역 복원 |
| 8d5e41db | 6클래스 WS 대역 |
| 25abb9c4 | N15 PG 격자 입력·⑤ 재현·주문 응답 대역 |
| b2976905 | 감시: 자기 인터페이스 주소 허용·별도 집계 |
| eed5b396 | 전체 회귀에서 입증된 WS 첫 연결 출처 11클래스 대역 |
| 6d8fdcd0 | scheduler 상태 확인 루프백, DataController 수집 서비스 대역 |
| b987d7a7 | H2 N15 두 클래스: WS 대역 + DYNAMIC 시각 보완 |

## 2. 외부 연결 감시 (§3-3)

- **설치 시점:** JUnit 확장 자동 감지(`junit-platform.properties`)로 엔진이 확장을 등록할 때 = 어떤 스프링 컨텍스트·HttpClient 생성보다 먼저. ProxySelector를 거치지 않는 NIO·원시 소켓도 `SocketPermission` 검사에서 잡힌다.
- **허용:** 127.0.0.0/8·::1·localhost·미지정 주소, 이 기계 인터페이스 주소(Tomcat 종료 `unlockAccept`의 자기 연결, `allowed_own_interface_connections`로 따로 셈), JDK `DnsContextFactory.filterNameServers`의 권한 확인(관리자가 있을 때만 생기며 패킷 없음 — JDK 원본으로 확인, 막지 않아야 감시가 없을 때와 같은 동작).
- **차단:** 그 밖은 기록 후 `SecurityException`, 클래스 끝에 새 차단이 있으면 그 클래스 실패. 캐시된 컨텍스트의 비동기 재연결은 뒤 클래스에 귀속되므로 귀속은 근사다. 그래서 출처는 시험 스레드의 첫 연결 로그로 따로 입증했다(`ws-origin.txt`).
- **복원:** 루트 저장소 종료 때 요약(`NET-GUARD-summary.txt`) 기록·원래 관리자(null) 복원. 설치 전 ForkJoinPool 공용 풀 초기화(Innocuous 작업자 회피). `TEST_NET_GUARD=off`로만 끈다.
- **감시가 시험 동작을 바꾸는가:** 음성 대조에서 감시 실패 외 단언42건 모두 통과. `ExecutorBarrierTest`(스프링 없음)를 감시 켬·끔 각 3회 단독 실행 → 6/6 통과(콘솔 확인, 실행 폴더 미보존).
- **범위 한계:** 감시는 `web-api` 시험에만 있다. core-engine·exchange-adapter·strategy-lib 시험의 외부 연결은 감시하지 않았다.

## 3. N15 PG 시각 의존 (§3-4)

후보의 DYNAMIC은 v1.158 뒤로 닫힌 봉·최신성·조회 상한·BTC 관문·접수 시각을 모두 판단 시계(`EntryClock`)로 본다. 옛 입력(봉=실제 현재−61분, 접수=실제 현재)은 H1 창 W=5분이라 E=실제 현재+9분이고, 승인 E−1ms에서 GRACE 2분을 뺀 기준이 다음 정시를 넘으면 BTC 관문이 "최신 완료 봉 없음"으로 **잠금 전에** 거절했다. 시험 주석의 "DYNAMIC은 실제 시각" 전제가 낡았다.

- 보완: DYNAMIC도 LIVE와 같은 정시 격자 봉(BTC 포함)·접수 C+1s·승인 E−1ms·E를 시험 시계 하나로. 제품 시계 접점 추가 없음. ②④ 기대·만료 경계·실제 잠금 대기 단언 불변.
- ⑤ 재현(시험 시계로 W 고정, sleep·건너뜀·실행 시각 선택 없음): 옛 입력 **:52:00·:53:00(경계)·:07 진입, :53:37·:55·:59·정각 거절** — 앞 차수 실측(15:53:37·15:54·15:55 실패, 15:52:29·16:07 통과)과 같다. 같은 W의 격자 입력은 모두 진입·차감 10000→2000.
- 대조군 `createOrder`에 고유 주문번호 접수 응답(wait) 대역 → NPE 0. 실행 뒤 6개 표 잔여 행 0(`residual-rows.txt`).
- 같은 원인이 H2 `N15EntryExpiryTest`·`N15FinalApprovalBoundaryTest`에도 있었다(`affected46-wip`, 00:56 UTC 실행에서 8건 실패). 같은 방식으로 보완했다(b987d7a7, 커밋 메시지의 "6건"은 오기이며 원문은 EntryExpiry 3·FinalApproval 5 = 8건).
- **남은 것:** `실제 현재−61분` 자료를 쓰는 다른 9개 시험(`AbstractSma20SameInputFixture`·`BtcGateUnknownEntry`·`DailySma20LiveOrderPath`·`DailySma20PaperForwardPath`·`DynamicBlackSwanCooldownCrossKind`·`EntryPriorityEndToEnd`·`ExitAbArmContract`·`SharedPaperLoopIsolation` 등)은 정시 직전 실패가 관측되지 않아 바꾸지 않았다. 정시 직전 탐침은 실행하지 않았다.

## 4. 전체 회귀에서 드러난 외부 연결과 후속 격리

b2976905 전체 회귀(09:29:24~09:49:26 KST, 기본 게이트·진단 감시 끔, 명령은 88f06345 회귀와 같은 `./gradlew cleanTest test --continue`): **core-engine 298=291+건너뜀7, exchange-adapter 11, strategy-lib 113, web-api 1,413 = 성공1,294·실패47·건너뜀72.** 외부 차단 104건.

| 출처(입증) | 근거 | 격리 |
|---|---|---|
| 실제 WS 첫 연결 13클래스 | 13클래스 모두 `[Test worker]` 스레드의 "Upbit WebSocket 연결 시도"(17줄). `[market-data]` 2줄은 그중 `RealtimeProtectionSaturationTest`. 재연결(`upbit-ws-scheduler`) 82줄은 이 컨텍스트들의 재연결이 뒤 클래스 중에 난 것 | WS 대역(eed5b396·b987d7a7) |
| `SchedulerProfileCleanupTest` 상태 확인 | scheduler 프로필의 30초 `checkHealth`가 실제 api.upbit.com 호출(차단 2회, 연속 실패 2/3 — 3회면 DOWN·비상 정지로 시험 동작이 바뀜) | 기존 접점 `healthCheckUriForTest`로 루프백 200 응답(정상 거래소와 같은 UP), 빈 초기화 전 설정·컨텍스트 종료 때 서버 종료 |
| `DataControllerIntegrationTest` 수집 | 202 요청의 비동기 수집이 실제 봉 조회. 제품이 `new UpbitRestClient()`를 직접 만들어 빈 대역이 닿지 않음(wip 실행에서 확인) | `DataCollectionService` 대역(요청 검증·응답 코드만 보는 시험) |

`ExecutorBarrierTest` 1건("옛 조건은 이 창에서 참" 거짓)은 단독 6/6 통과로 재현하지 못했다. 원인 미확정, 시험 자체의 전제 확인 단언이다.

## 5. 후속 확인

| 실행 | SHA | 결과 |
|---|---|---|
| `affected46-wip` | 작업 사본(메타에 차이 해시) | 376, 실패9: DataController 감시1 + N15 H2 시각 8 |
| `affected46-b987d7a7` | b987d7a7 | **376, 실패1, 외부 차단0**, WS 줄0. 실패는 §6 |

## 6. 제품 경합 후보 — 제출 중 SUBMITTING을 폴러가 UNKNOWN으로 덮어씀

`affected46-b987d7a7`의 `SchedulerProfileCleanupTest` DYNAMIC(관측 원문 XML):

| 시각(UTC 01:03:02) | 스레드 | 사건 |
|---|---|---|
| .905~.906 | scheduler-7 | 정리 작업: CLOSING·매도 의도 저장·커밋 → 실행기 접수 |
| .907 | order-exec-2 | claim `INTENT_READY→SUBMITTING` 커밋 |
| .908 | order-exec-2 | 거래소 대역 호출·반환 `uuid-sched-sell-…` |
| **.909** | order-exec-2 | **`SUBMITTING→SUBMITTED`·거래소 주문번호 커밋** (거래로그 579) |
| .910 | scheduler-7 | `unknown_probe_count+1` |
| **.911** | scheduler-7 | **거래로그 580 `SUBMITTING→UNKNOWN`, 주문 행 전체 열 갱신·커밋** → 최종 `UNKNOWN`·주문번호 없음·"식별자로 조회되지 않음(시도 10회)" |

88f06345 코드 대조(V84, 0170bc7a 2026-10-12 도입):
- `pollActiveOrders`(5초, `@Transactional`)는 활성 주문을 읽고 SUBMITTING이면 **경과 시간·이 프로세스의 제출 중(DISPATCHING) 여부를 보지 않고** `resolveUnresolvedSubmission` → 식별자 조회(`OrderExecutionEngine:2006~2087`). INTENT_READY는 DISPATCHING을 확인하지만 SUBMITTING은 확인하지 않는다.
- 조회 결과가 없으면 `transitionState(order,"UNKNOWN")` — **조건 없는 엔티티 저장**(`:2593~2602`, `orderRepository.save`)이라, SUBMITTING 시점 엔티티로 커밋된 SUBMITTED를 덮는다. 거래로그 580의 old_state=SUBMITTING이 그 낡은 엔티티의 증거다.

판단 범위: 이 시험 대역의 `getOrderByIdentifier`는 항상 null이라 UNKNOWN이 고정됐다. 실제 거래소에서는 다음 5초 조회에서 찾으면 주문번호가 복구될 수 있다. **운영 영향(일시 UNKNOWN·주문번호 소실 구간·경보·정지 판단에 주는 영향)과 G2(과거 60초 제출 지연)와의 관계는 확인하지 않았다.** 창은 실행기 claim 커밋부터 결과 커밋까지(실거래 호출 시간만큼)라, 대역이 1ms인 시험보다 운영에서 넓다. 현재 운영판에 같은 코드가 있는지는 운영 SHA로 확인하지 않았다(도입 시점상 포함 가능성이 높다). 제품은 바꾸지 않았다.

## 7. 남은 판단

1. §6 경합 후보를 제품 결함으로 보고 11-16 U0/U1 진행에 영향이 있는지, 제품 보완(SUBMITTING의 제출 중 확인·조건부 전환 등)을 할지(총괄).
2. 전체 회귀 재실행은 §6 판단 뒤로 미뤘다. 이 경합이 있는 한 scheduler 시험은 비결정적으로 실패할 수 있다.
3. `ExecutorBarrierTest` 1회 실패의 원인, 비정시 자료를 쓰는 나머지 9개 시험의 정시 직전 탐침.

원문 체크섬은 [`SHA256SUMS`](SHA256SUMS). 실행 틀 `run.sh`(관련 실행)·`full.sh`(전체 회귀), 기존 출력 덮어쓰기 금지.
