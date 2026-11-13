# v1.165 §3 — G1 보호 매도 미제출 조사·음성 단언 완료 경계·외부 연결 격리 (2026-11-13 15:36~16:25 KST, 개발 세션, 총괄 판정 전)

총괄 [v1.165 / 410차](../review-9fd6c856/README.md) §3-1~8을 진행했다. 운영 서버·운영 DB는 조회하지 않았다. 실제 PG는 로컬 격리 컨테이너 `v152-pg`(127.0.0.1:15437, 운영과 무관)의 클래스별 새 DB다.

## 결론

| 항목 | 판정 | 근거 |
|---|---|---|
| **G1 원인** | **시험 읽기 결함. 제품의 커밋 뒤 제출 공백 아님.** 정지 목 응답 안의 시험 스레드가 정지 트랜잭션의 영속성 컨텍스트로 매도를 읽었다. 의도 커밋 뒤·claim 전에 한 번 읽은 `INTENT_READY/null`이 1차 캐시에 남아, 실제 접수 뒤에도 같은 값을 돌려줬다 | §2 ①⑤ |
| 같은 주문의 제출 경계 | 시세 작업자 트랜잭션: CLOSING·의도 저장 → 커밋 → 커밋 뒤 실행기 접수(등록부 true) → claim 커밋(등록부 false) → 거래소 대역 호출 → 결과 커밋. 끊긴 경계 없음 | §2 표 |
| 정지 트랜잭션 영향 | 원 시험의 20초 단언 실패가 정지 목 응답 밖으로 나가 **정지를 롤백**: 세션 `RUNNING`·정리 대기 없음. 두 번째 매도는 이미 `SUBMITTED` | §2 ⑤ |
| 정상 커밋 뒤 확인 | 두 스레드·명시적 신호로 같은 끼어들기를 만들고 정지를 정상 커밋 → 독립 트랜잭션 확인. STOPPED·CLOSING·정리 대기·거래소 2회, 거절 뒤 상한 메모까지 원 기대 그대로(시작 문 유무 각 LIVE·DYNAMIC) | §2 ③ |
| 폴러 회복(§3-4) | G1 상태에서 `pollActiveOrders()` → `FAILED`·`WITHDRAWN_DISPATCH_LOST`(외부 호출 0) → 청산 확인 OPEN → 정리 작업 재매도 1회·접수, 3회 반복 중복 0. LIVE·DYNAMIC 모두 | §3 |
| 제품 변경 | **없음.** 88f06345 대비 시험 외 경로 변경 0 | `change-paths.txt` |
| 음성 단언 보완(§3-6) | LateOrder·N16 ③·N15Pg 정리 대기의 공백을 게이트500으로 각각 재현하고 장벽으로 보완. O1O3는 공백 아님 | §4 |

## 1. 커밋·가지

- 가지 `investigate/g1-submit-boundary`(7d1d00ae 기점), **푸시 `3f9cc926d5bcd08d67a9d8541e859d91110a3677`**. 원격 참조와 일치.
  - `43964b30` **계측**(시험 전용): `OrderFlowObservation`의 추가 스레드 접두·주문 실행기 시작 문(opt-in, 기본 불변), `OutboundNetworkGuard`(`G1_OUTBOUND_GUARD=1`일 때만), `G1SubmitBoundaryDiagTest` 12건.
  - `3f9cc926` **시험 보완**: StopVersus (다)·LateOrder·N16 ③·N15Pg + WS·텔레그램 대역. 패치 원문 `patches/0001`·`0002`.
- 제품 트리: `git diff --name-only 88f06345 3f9cc926`에서 `web-api/src/test/` 밖 0경로. 후보 88f06345의 jar·D4·N03 연결은 바뀌지 않는다.

## 2. 같은 주문의 경계 계측 (`G1SubmitBoundaryDiagTest`)

시간 지연 대신 명시적 신호를 쓴다: 시세 실행기 장벽(WS 작업 완료), 주문 실행기 **시작 문**(접수 뒤·실행 전 대기), 독립 트랜잭션 조건 대기. 같은 순간을 정지 트랜잭션 저장소·독립 JDBC(REQUIRES_NEW)·거래소 대역 호출 수·등록부 표본(`isDispatching`, 제품 불변)으로 읽는다.

LIVE STALE, 주문 20(`runs/diag-r1`, 같은 판정이 고정 SHA `final-rel19-nogate`에서 주문 168·170으로 재현):

| +초 | 스레드 | 사건 | 트랜잭션 자원 |
|---|---|---|---|
| 0.125 | Test worker | WS 손절 이벤트(정지 목 응답 안) | `stopSession` CH@43d01057 |
| 0.127 | market-data-1 | `update position set status='CLOSING'` | `onRealtimePriceEvent` CH@61d16637 |
| 0.129 | market-data-1 | `insert into "order"`(INTENT_READY) | 같음 |
| 0.130 | market-data-1 | **커밋** → 커밋 뒤 실행기 접수 task=100 | 같음(완료 직후 묶인 자원) |
| 0.130 | order-exec-1 / 표본 | 시작 문 대기 / 등록부 **true** | - |
| (문 열기 전) | Test worker | 독립 읽기 `INTENT_READY`, **정지 tx 저장소 읽기 `INTENT_READY/null`** | 정지 tx |
| 0.131~0.132 | order-exec-1 | 작업 시작 → `update "order" set state='SUBMITTING' … where state='INTENT_READY'` → 커밋 | CH@7f5cfb82 |
| 0.132 | 표본 | 등록부 **false**(claim 종료의 정상 해제) | - |
| 0.212 | order-exec-1 | **거래소 대역 호출** `KRW-G1L9` | - |
| 0.214 | order-exec-1 | 결과 기록 커밋 → 작업 끝 | CH@5cad1b14 |
| 0.221 | Test worker | 정지 커밋 반환 | `stopSession` |

판독(LIVE·DYNAMIC 같음): 접수 뒤 정지 tx 저장소 = `INTENT_READY/null`(엔티티가 영속성 컨텍스트에 포함), 독립 읽기 = `SUBMITTED/uuid`, 거래소 호출 2, `em.refresh` 뒤 `SUBMITTED`. 대조 ②(claim 전에 읽지 않음)는 정지 tx 안에서도 `SUBMITTED`. 409차의 장벽·실행기 50ms 지연은 "claim 전 첫 읽기"를 만드는 조건이었다.

- ⑤ 원 시험식: 같은 낡은 읽기로 목 응답 안에서 `AssertionError` → 정지 밖으로 전파 → 독립 확인 세션 `RUNNING`·정리 대기 false·매도 `SUBMITTED`·거래소 2.
- ③ 두 스레드: 정지 스레드 `g1-stop`이 마지막 등록 조회 결과를 읽은 직후 신호로 멈춤 → 시험 스레드가 WS 매도·접수 확인 → 재개·정상 커밋.
- H2·PG 차이: 낡은 읽기는 Hibernate 1차 캐시 동작이라 DB 종류와 무관하다. N15 실제 PG는 §4에서 돌렸다. 진단·StopVersus의 PG 실행은 하지 않았다.

## 3. 회복 경로 직접 호출 (④)

G1 상태 = 정지 뒤 CLOSING + 이 프로세스 소유 `INTENT_READY` 매도(직접 커밋, 등록부 없음). LIVE·DYNAMIC 각 1회(`final-rel19-nogate`):

1. 폴러 전 청산 확인: 활성 의도가 있으므로 **CLOSING 유지**.
2. `pollActiveOrders()`: `FAILED`·`WITHDRAWN_DISPATCH_LOST`, 거래소 호출 증가 0.
3. 청산 확인: OPEN.
4. 정리 작업 3회 + 장벽: 새 매도 1건 `SUBMITTED`·거래소 접수, 총 거래소 2회(앞의 거절 1 + 재매도 1), 중복 0, 메모 "청산 매도 체결 대기", 사유 FORCED_STOP, 완료 아님.

철회(`WITHDRAWN_DISPATCH_LOST`)는 거래소 거절 상한(2회)에 세지 않았다. 직접 호출은 회복 기능의 근거이며 5초·10초 주기의 실제 회복 시간은 G3에 남는다.

## 4. 음성 단언·정리 대기의 완료 경계 (§3-6)

| 대상 | 재현(게이트500) | 보완 | 보완 뒤 |
|---|---|---|---|
| `LateOrderSubmissionAfterStopTest` | 제출 06:49:06.997 → 옛 대기 반환 07.059(`completed=0`) → 단언 통과 07.075 → 실제 작업의 정지 거절 07.515 (`late-orig-gate500-probe`) | 대기 → 실행기 장벽 | 정지 거절 38.450 → 대기 반환 38.962(`completed=3`) → 단언 (`late-fix-gate500-probe`). 기대(매수0·STOPPED·활성 없음·임시 포지션 없음) 불변 |
| `N16InvalidPriceReproTest` ③ | 단언 통과 시점 디스패처 `pendingEvents=1`·`processed=1`(처리기 미실행), 장벽 뒤 `processed=2` (`n16-orig-gate500-probe`) | ③만 시세→주문 장벽. ①②는 처리기 직접 호출(동기)·OPEN 단언이 증인, ④e는 양성 | 13/13 |
| `N15PgLockWaitExpiryTest` 정리 | 실제 PG: 대조군 매수 claim이 세션 삭제 **뒤** "status=없음"으로 철회 시도 2회 (`n15pg-gate500`) | 정리 전 대기 → 장벽 + WS 대역 | PG 게이트 없음·500 각 6/6, "status=없음" 0, 실행 뒤 5개 표 행 0 |
| O1O3 | - | 변경 없음: `timeout(20s).atLeastOnce()` 양성 대기 뒤 정확히 1회를 센다 | - |

보완 뒤 N15 PG 정리 전에 대조군 claim이 돌면서 `recordSubmitOutcome` NPE 3줄이 나온다. 이 클래스가 `createOrder` 대역을 설정하지 않아 null을 받은 시험 현상이다. 실제 `UpbitOrderClient.createOrder`는 결과를 돌려주거나 예외를 던진다. 단언 결과는 바뀌지 않는다.

**기존 시험의 시계 의존(새 관측, 변경 없음).** N15 PG ④ DYNAMIC 대조군은 원본에서도 15:53:37·15:54·15:55 KST에 실패했고 15:52:29와 16:07~16:08(보완판, `final-n15pg-*`)에는 통과했다(`n15pg-orig-nogate-1555` 등). DYNAMIC 캔들은 벽시계 −61분, BTC 관문 판정은 시험 시계 E 기준이라 정시 직전 몇 분 동안 "최신 완료 봉 없음"이 된다. 시험 자료 문제이며 제품 동작 변경이 아니다. 실행 시각을 정시 직전으로 잡지 않거나 자료를 E 기준으로 맞추는 보완은 별도 판단으로 남긴다.

## 5. 고정 SHA 실행 (3f9cc926, 작업 사본 차이 0)

| 실행(`runs/`) | 범위 | 결과 |
|---|---|---|
| `final-rel19-nogate` | 409차 18클래스 + G1 진단 | **234 = 성공230·건너뜀4(H2의 N15Pg)·실패0** |
| `final-rel19-gate50` | 같음, 게이트50 | **234 = 성공218·건너뜀16(N15Pg 4 + 진단 12: 게이트 실행기는 시작 문이 없어 건너뜀)·실패0** |
| `final-n15pg-off` / `-500` | N15 실제 PG | 6/6 · 6/6, 남은 행 0 |
| `focus-gate500` / `gate500-stopversus` | 커밋 전 작업 사본(7d1d00ae + 변경, 메타에 차이 해시) — 변경 4클래스+진단 / StopVersus | 33/33 · 7/7 (409차에서 (다) 2건 실패하던 조건) |
| `focus-nogate-guard` | 커밋 전 작업 사본, 외부 연결 감시 켬 | 33/33, 차단 기록 0 |

전체 회귀·jar·D4 갱신은 하지 않았다(§3-7: G1의 제품 변경 여부·포함 범위 판정 뒤). 이번 판정은 제품 변경 없음이다.

## 6. 외부 연결 (§3-1)

- 409차 진단 XML: 실제 Upbit WebSocket 연결 성공 1줄·`KRW-SVRD2` 구독 1줄. 410차 원본 N15Pg 게이트500 실행(`n15pg-gate500`)에서도 연결 시도·성공·`KRW-IOTA` 구독 각 1줄. 공개 시세 구독이며 인증·주문 호출은 없다.
- 이번에 바꾼 StopVersus·N15Pg와 진단 클래스는 `UpbitWebSocketClient`(+StopVersus·진단은 텔레그램) 대역. 단독·묶음 실행에서 해당 클래스 WS 줄 0.
- **남은 것:** 같은 묶음에서 대역이 없는 6클래스(ExchangeRecoveryRestart·LimitedTestExchangeRecovery·LiveEmergencyStop·N17·SlWatchdog·StaleRealtimeExit)는 실제 WS 연결·구독을 했다(`final-rel19-nogate`, XML 로그는 시간 구간으로 붙으므로 귀속은 근사). 재연결 의미를 시험하는 클래스가 섞여 있어 이번 범위에서 바꾸지 않았다. 대역 전환 여부는 판단 대상이다.
- `OutboundNetworkGuard`는 JVM 전역이라 기본은 끈다. 켜면 루프백 외 연결을 기록·차단만 한다(단언 없음).

## 7. N05 — LIVE_ALIGNED 대기 정산 경로 연결 (§3-8, 후보판, 88f06345 줄 번호)

K14~K16은 REAL 주문 경로다. 새 모의 모드는 주문 실행기를 거치지 않고 포지션 상태(CLOSING)로 청산 대기를 표현한다. `PTS`=PaperTradingService.

| 번호 | 경로 | 코드 위치 | 기존 시험·검증 |
|---|---|---|---|
| K20 | **정지 → 청산 대기** | `PTS:344` `stopOnce`: 시세 장부 유효 가격이 있으면 그 가격으로 FORCED_STOP 정산, 없으면 `markClosingInTx`(CLOSING, 0원·옛 봉 체결 없음). 대기 수는 정지 커밋 트랜잭션에서 센다(`PTS:331`, 응답 `PaperTradingController:133`) | `AlignedV147FixesTest` R2, `AlignedV150FixesTest` S1·S1b·S1c·S2, `AlignedV148FixesTest` W4b |
| K21 | **재기동 복원** | `PTS:1406` `restoreAlignedClosing`(ApplicationReadyEvent): DB의 CLOSING(선택 모드)을 코인별 대기에 다시 올림. 판단 시각은 저장 열 | `AlignedV147FixesTest` 재기동 복원, `AlignedRestartContextTest`, **`AlignedV96PgBehaviorTest` 실제 PG 5/5(88f06345, `runs/aligned-v96pg-88f06345`)** |
| K22 | **첫 유효 가격 정산** | 실시간 `PTS:1666` → `alignedPriceExits`의 CLOSING 분기 `PTS:1249` `settleClosingFresh`(넘어온 봉 종가 미사용), 주기 `runStrategy` 끝·세션 없음 분기 `PTS:763`·`798` `settleClosingFromMarkBook`(`PTS:1418`), 격리 정산 `PTS:1368` | `AlignedV147FixesTest` R4·주기 정산, `AlignedV148FixesTest` W1b·§3-1.5, `AlignedV96PgBehaviorTest` 첫 정산 1회·두 번째 0회 |
| K23 | **삭제 거절** | `PTS:479` `deleteSession`: 같은 트랜잭션에서 커밋된 CLOSING이 있으면 거절(`PTS:487`). 일괄 `PTS:502`는 대기 세션을 건너뜀 | `AlignedV148FixesTest` W2·W2c·W3 |

H2 4클래스는 88f06345 전체 회귀(`v160-20261113/runs/int-full-88f06345`)에서 V147 12/12·V148 14/14·V150 8/8·RestartContext 1/1, 실패 0이다. 그 실행에서 PG 전용 V96은 건너뜀이라 이번에 88f06345에서 직접 돌렸다. 코드 대조에서 새 공백은 찾지 않았다. 최종 운영판 대조는 배포 뒤 N05(G4)에서 한다.

## 8. 남은 판단

1. G1을 "시험 읽기 결함, 제품 변경 없음"으로 닫을지, 그리고 U1부터의 배포 재개 여부(총괄 판정).
2. 대역이 없는 6클래스의 실제 WS 연결을 대역으로 바꿀지.
3. N15 PG ④의 정시 직전 시계 의존을 보완할지.
4. 독립 시험 가지 3f9cc926을 통합 후보에 넣을 때의 전체 회귀 시점.

원문 체크섬은 [`SHA256SUMS`](SHA256SUMS). 실행 틀은 [`run.sh`](run.sh)(기존 출력 덮어쓰기 금지, 메타에 HEAD·작업 상태·차이 해시).
