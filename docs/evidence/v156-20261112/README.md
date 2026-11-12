# 총괄 v1.156 실행 근거 — N15 정시 격자·회차 격리, O1·O3 완료 대기 경계, 최종 SHA 84fb03d6 관련·PG·전체·jar (2026-11-12, 개발 세션, 총괄 판정 전)

운영 접속·배포·DDL·세션 생성·재기동 없음. 모든 후보는 미승인이다. 로컬 합성 결과는 운영 실측이 아니다. 두 커밋 모두 **시험 코드만** 바꿨다(제품 코드 변경 0).

| 단위 | 커밋 (`candidate/n15-grid-rounds`, ee02b346 위 → 통합 빨리감기) | 결과 | 폴더 |
|---|---|---|---|
| N15 세 시험 정시 격자·회차 격리 | **da8a33a0** | H2 1회차 7실패(준비 코드 결함) → 2회차 19/19, 실제 PG 6/6 | `n15-*` |
| O1·O3 매도 완료 대기 | **84fb03d6** | 고정 경합 진단으로 원인 확인 → 이웃 5클래스 100/100 | `o1o3-*` |
| **최종 SHA 84fb03d6** (`integration/v155-aligned2`) | | 관련 64클래스 586/586 · 실제 PG 8클래스 45/45 + 추가 3클래스 11/11 · **전체 1,720 중 실패 0·건너뜀 56** · jar 9bce 대비 891→891 변경 10 | `final-*` |

## 1. N15 세 시험 (v1.156 §124-1~3)

**원인 두 가지(확인).**
- 비정시 봉: 종전 준비는 마지막 봉을 "실제 현재 − 61분 − 초 오프셋 + 371/613/829ms" 로 두었다. B01 뒤 LIVE 는 캔들 조회 상한·최신성을 공통 시계로 보므로, 승인 시각 E−1ms 의 정시가 봉 시작 + 2시간으로 넘어가면(실행 시각의 분이 늦을 때) "필요 다음 정시 이후" 로 최신성에서 막혀 만료 방어에 닿기 전에 거절됐다. 13:15(분이 이름) 같은 ee02b346 에서 12/12(`n15-now-ee02/`) — 시각 의존 확인.
- **② DYNAMIC "접수 0" 은 회차 키 충돌이었다**: "순번 × 5초" 오프셋이 앞 시험(⑤, 12:54:15)이 걸린 5초와 상쇄돼 ②(12:54:20)의 base 가 ⑤와 같은 초가 됐고, ⑤가 닫은 키로 접수가 "회차 마감 뒤 도착"이 됐다. ⑤가 최신성 거절로 빨리 끝난 것이 계기라 B01 SHA 에서만 보였다.

**보완(시험만).** 공용 준비 `N15GridRounds`:
- LIVE 입력: 거래 코인 연속 H1 자료 L = 실제 현재 정시 − 1시간까지, 회차 키 (H1, L). 접수 시각을 시험 시계로 **두 위치**(C+1s · D−1ms)에 고정하고 LIVE 사례를 그 두 위치로 매개변수화했다. 벽시계의 분을 기다리지 않는다.
- BTC 자료는 진행 중 봉 C 까지 — **제품의 LIVE BTC 급락 관문 최신성은 실제 시각**이다(B01 접점 아님). 시험 중 실제 정시가 바뀌어도 BTC 가 최신이다.
- 회차 격리: 독립 입력마다 새 `EntryPriorityRounds` 인스턴스를 LIVE·DYNAMIC·PAPER 엔진 필드에 꽂고 다음 입력 전·정리 때 원래 빈으로 되돌린다(꽂을 때 원래 빈인지·되돌린 뒤 같은지 확인). 닫힌 회차를 다시 열거나 지우지 않는다. 초·밀리초·`minusSeconds` 오프셋은 모두 없앴다.
- 스케줄러: 시험 프로필은 `app.scheduling.enabled=false`(2026-10-13~)라 `processDueRounds` 가 돌지 않는다. 매 시험 `@Scheduled` 처리기 빈이 없음을 단언한다. `MutableClock` 주석의 "운영 스케줄러가 같은 시계를 읽는다" 는 옛 설명을 바로잡았다(등록 스레드 한정은 유지 — 승인은 시험 스레드나 등록한 대기 스레드에서만).
- 단언 그대로: D+1ms/E−1ms 진입, E/E+1ms 거절, 앞 후보·자기 관문·계좌/세션 잠금 대기, 차감 직전·의도 직전 재검사, 자금 복원·슬롯/의도/거래소 0·원래 신호 사유, 확정 주문 비철회·회차 닫힘·재대기 없음, 대조군 진입. 출력에서 LIVE 거절은 모두 만료 사유다 — 콜백 전 "승인 실행 만료"(①의 E·E+1ms, ③) 또는 잠금 안 `ENTRY_EXPIRED`(잠금 뒤/차감 직전/의도 직전). "최신 캔들을 못 읽었다"·"회차 마감 뒤 도착" 은 두 클래스 출력에 0건이다.

**한계 — 분리 제출(제품 시계 접점 필요).** DYNAMIC 은 제품이 후보 접수(`submit(..., Instant.now())`)·코인 최신성·BTC 관문·닫힌 봉 판정을 모두 실제 시각으로 본다. 정시 격자 회차는 실제 시각이 C~D(매시 0~5분)일 때만 접수되므로, DYNAMIC 입력을 정시로 옮기려면 DYNAMIC 판단 시각을 공통 시계로 잇는 제품 접점이 필요하다. 지시(DYNAMIC 제품 시계 확대 제외)에 따라 만들지 않았다. DYNAMIC 사례(N15E ②④-DYN, N15F ⑦b⑧⑨b⑩-DYN, PG ②④)는 **실제 시각 기준 비정시 자료**(현재 −61분)에 새 회차 상태만 쓴다. 이 자료는 실제 시각 판정과 일관돼 실행 시각의 분과 무관하게 통과한다.

| 실행 | SHA | 결과 |
|---|---|---|
| `n15-now-ee02/` | ee02b346 (13:15) | 12/12 — 분이 이른 때 종전 시험 통과(시각 의존) |
| `n15-h2-run1/` | 작업본 | 19 중 7실패 — **내 준비 코드 결함**: 한 메서드에서 두 번째 회차 상태를 만들 때 앞 것이 꽂힌 채라 "원래 빈" 확인에 걸림 |
| `n15-h2-run2/` | 작업본 | 19/19 (앞 것을 먼저 되돌리고, 되돌림은 한 번만) |
| `n15-pg-run1/` | 작업본, 새 DB | 6/6 — LIVE ①③ 두 접수 위치 모두 DB `pg_stat_activity` Lock 대기·쥔 쪽 pid 확인 |

## 2. O1·O3 LIVE REST_TRADE 매도 0 (v1.156 §124-4)

- 경로: `onRealtimePriceEvent` → (시험 스레드) "실시간 손절 발동" → `executeSessionSell` → `markClosingIfOpen` → `OrderExecutionEngine.submitOrder` 는 **`@Async("orderExecutor")`** — 의도 저장·claim·거래소 호출이 모두 실행기 작업이다.
- 시험 대기 `drain` = 큐 비었음 · active 0 · taskCount == completedTaskCount. **이미 놀고 있는 작업자가 큐에서 작업을 꺼낸 뒤 실행 표시(lock) 전**이면 셋 다 참이다(기존 `OrderExecutorDrainPredicateGapTest` ① 이 일반 실행기로 보인 경계). 새 작업자 경로는 생성 상태가 active 로 세어져 공백이 없다 — 단독 실행이 통과한 이유. 전체 실행에서는 같은 문맥을 쓰는 앞 클래스(N17·N19·N20)가 작업자를 미리 만들어 둔다(`int155b-order.txt`: O1O3 바로 앞 N15·N16·N17·N19·N20).
- **고정 경합 진단**(`o1o3-diag-run1/`, 같은 SHA, 커밋 안 함 — 소스 `O1O3DrainGapDiagTest.java.txt`): orderExecutor 빈만 운영 설정(core 2·max 4·queue 50) 그대로에 "꺼낸 뒤 붙잡기"를 더한 실행기로 바꾸고, 작업자 2개를 미리 띄운 뒤 같은 LIVE REST_TRADE 280 이벤트를 넣었다.
  - 붙잡은 동안: 옛 조건 **비워짐=참**, pool=2 active=0 queue=0, **거래소 매도 0·주문 행 0** — 전체 실행 실패 값과 같다.
  - 풀어 주면 매도 1·주문 행 1. 제품 경로는 정상 — **제품 결함 아님**.
  - 거래소 호출을 기다리는 대기로는 같은 붙잡기(300ms 뒤 해제)에서 매도 1.
- 보완(시험만, 84fb03d6): 매도를 세기 전에 `verify(timeout 20초).createOrder(그 코인, ask)` 로 실제 작업 결과를 기다린다. 오지 않으면 20초 뒤 실패. "매도 1회" 기대·나머지 단언 그대로, 긴 sleep·순서 고정·실패 제외 없음. 이웃 5클래스(`o1o3-fix-run1/`) 100/100.
- 같은 옛 조건을 쓰는 시험 파일이 O1O3 밖에 20개 있다(정리 단계만 쓰는 곳 포함). 원인이 입증되지 않아 고치지 않았다 — 잠재 경계 목록: CancelConfirmAndLateFillCleanup, ClaimAndUnknownTracking, DailySma20LiveOrderPath, ExchangeRecoveryRestart, ExecutorBarrier, IntentPrecommitAndOnceOnly, LateOrderSubmissionAfterStop, LimitedTestExchangeRecovery, LiveEmergencyStopUnderConcurrentWrite, LiveStopUnderConcurrentWrite, N15PgLockWaitExpiry, N16InvalidPriceRepro, N17RestInvalidPrice, N19RestFallbackItemDefense, OrderExecutorDrainPredicateGap(경계 자체 시험), SlWatchdogFreshness, StaleRealtimeExit, StopVersusInFlightExitRace, SubmitOrderBoundaryProbe, SubmitResponseUnknown.

## 3. 남은 원인 미확인 3건 (v1.156 §124-5)

| 시험 | 관측 | 최종 전체 회귀(84fb03d6) | 상태 |
|---|---|---|---|
| `SharedMarketDataExecutorSaturationTest` 대기열 가득 expected 0 but 100 | 9bce `R*`+`S*` 두 번 재현, 절반씩 나누면 재현 안 됨 | 통과 | 원인 미확인 — 고정 탐침 미작성 |
| `SchedulerProfileCleanupTest` 60초 안 정리 | 다른 Gradle 2개와 동시 실행 때 1회 | 통과 | 부하 가능성, 미확인 |
| `OrderExecutorDrainPredicateGapTest` ① | c6b14e18 전체에서 `active=1` 로 1회 | 통과 | 자체 실행기 시간 창 시험 — 미확인 |

통과로 해결 처리하지 않는다.

## 4. 최종 SHA 84fb03d6 검증

- **관련**(`final-related/`, 15:25~15:28): v155 관련 57클래스 + N15 3·O1O3·N17·N19·N20·대기 경계 시험 → 64클래스(중첩 포함) **586/586**.
- **실제 PG**(`final-pg/`, 클래스마다 새 DB, 실행 전 결과 폴더 비움, 15:28~15:31): AlignedV96 5/5 · BalanceList 2/2 · S5 1/1 · StartedAt 1/1 · LiveLossWindow 7/7 · **N15PgLockWait 6/6** · SettlementRounding 16/16 · PartialSellRealizedPnl 7/7 = **45/45**.
- **추가 PG**(`final-pg-extra/`): `V96MigrationPgTest` 1/1(V94→V96 이행·validate·기존 행 불변) · N13 HoldBaselineLoop 4/4 · HoldBaselineSqlEquivalence 6/6. **AbChain·AbChainV4 는 실행하지 못했다** — 1회차 둘 다 내 실행 설정 누락(`AB_CHAIN_DRIVER` 미지정 → NPE), v4 2회차도 `AB_CHAIN_CODE_SHA`·`AB_CHAIN_OTHER_DB`(같은 ID 를 가진 다른 실제 DB) 미준비로 NPE. A/B 도구 연결 시험이라 이번 변경과 무관하지만 미실행으로 남긴다(원문 보존). v1 구동기는 없어진 컨테이너 `v150-pg` 를 쓴다.
- **전체 회귀**(`final-full/`, 15:32:16~15:53:18, rc=0): core-engine 298(성공 291·건너뜀 7) · exchange-adapter 11/11 · strategy-lib 113/113 · **web-api 1,298 중 성공 1,249·실패 0·건너뜀 49**. 합계 **1,720 중 실패 0·건너뜀 56**. web-api 가 1,291 → 1,298 인 것은 N15 LIVE 사례를 접수 두 위치로 매개변수화해서다(H2 12 → 19). 시각·종료값·요약을 모두 쓴 뒤 마지막에 해시 269개를 만들었고 그 뒤 폴더에 쓰지 않았다(`sha256sum -c` 269/269).
- 건너뜀 56(XML 사유 문구 없음 — 실행 조건은 주석):

| 수 | 클래스 | 실행 조건 | 최종 SHA 에서 |
|---|---|---|---|
| 7 | core-engine 백테스트 러너 7개(HeikinAshiStoch·SignalExitGateAb·StrategyReviewAb·SupertrendFilterGate·SupertrendFilterQ2·SupertrendValidation·WeakThresholdAb) | 시스템 속성(`ha.backtest.csv`·`review.backtest.dir`·`stfilter*.dir`·`stval.dir`) | 해당 없음(수동 연구 러너) |
| 1 | PullbackMtfBacktestRunner | `@Disabled`(Upbit 네트워크 수동 러너) | 해당 없음 |
| 5·2·1·1 | AlignedV96PgBehavior·BalanceListSnapshotPg·PerformanceSnapshotS5Pg·StartedAtPrecisionPg | `V96_PG_URL` 로컬 PG | 따로 실행 9/9 |
| 7 | LiveLossWindowPg | `LW_PG_URL` | 7/7 |
| 4 | N15PgLockWaitExpiry | `N15_PG_URL` | 6/6(매개변수 펼침 뒤) |
| 8 | SettlementRoundingPg | `SR_PG_URL` | 16/16(매개변수 펼침 뒤) |
| 7 | PartialSellRealizedPnlPg | `PP_PG_URL` | 7/7 |
| 1 | V96MigrationPg | `V96_PG_MIG_URL` 빈 DB | 1/1 |
| 4·6 | HoldBaselineLoopPg·HoldBaselineSqlEquivalencePg | `N13_PG_URL` | 4/4·6/6 |
| 1·1 | AbChainPg·AbChainV4Pg | `AB_CHAIN_PG_URL` + 구동기·코드 SHA·다른 DB | **미실행**(위) |

- **jar**(`final-jar/`, 84fb03d6 bootJar — 파일은 커밋하지 않고 해시만 `jar-sha256.txt`): 비교기 `v1135-h2-d2-1ec9104/comparator/jarcmp_v2.py` 그대로.
  - 기준 b5cd1d0(운영) → 84fb03d6: 881 → 891, 추가 10(PaperExecutionMode·PaperMaxInvestment·Paper 내부 3·RealtimeProtectionDispatcher 4·V96 이행 SQL), 변경 38, 삭제 0.
  - 9bce7dc1(조사용) → 84fb03d6: 891 → 891, **변경 10, 추가·삭제 0** — 제품 소스 변경 6파일(CandleFreshnessGuard·Dynamic·Live·Paper·RealtimeProtectionDispatcher·RiskManagementService)과 일치. N15·O1O3 커밋은 시험만이라 jar 에 없다.
- 프런트: 5f9ef2ad 와 84fb03d6 사이 `frontend/` 차분 0줄 — G 근거(렌더 7/7·tsc 0) 그대로 적용(재실행 아님).

## 5. 미승인 D4·복구안 메모 (실행안 아님 — 빈칸 목록)

운영 반영은 승인되지 않았다. 84fb03d6 은 **전체 회귀 실패 0 인 최종 확인 SHA** 이지만 F·부분 손익·손실 필수화·B01 은 각각 별도 검토 상태이고, 이 시험 수정 허용이 그 코드나 배포의 수용이 아니다.
- 반영 단위: 84fb03d6 jar 1개(해시 `final-jar/jar-sha256.txt`) + V96 이행(DDL, 승인 대상). 포함: F 유계 묶음, LIVE 손실 합산 필수 의존성, 부분 매도 실현손익(LIVE·DYNAMIC), B01 LIVE 3접점 + 접수 + PAPER 접점, fixture·N15·O1O3 시험. 제외: throttle 후보 b5f8a8fd, DYNAMIC 81b0e7a7, main V95, DYNAMIC 시계 접점(없음).
- V94 → V96: 이행 실제 PG 1/1(최종 SHA). 되돌림은 세션 생성 전이면 종전 D4 되돌림, 생성 뒤면 `../v153-20261112/h-rollback/v2/rollback_gate.sh` 판정을 먼저 지난다(`LEDGER_REFUSED`·`HOLD_PENDING`·`STOP_*` 는 구판 복구 불가). 관문 예행은 v153 의 로컬 PG 10/10 이고 이번에 다시 돌리지 않았다.
- 반영 뒤 관측 칸(숫자 기준은 미정): 보호 디스패처 대기열 초과·처리기 실패·실행기 거절·재접수 지연, `ENTRY_EXPIRED`·"회차 마감 뒤 도착" 건수, 캔들 최신성 판정 불가, 실시간 손절 발동 대비 매도 주문 수, 부분 매도 뒤 손익 합.
- 반영 전에 닫을 것: DYNAMIC REAL 손실의 전역 한도 포함 정책(v155 §9), DYNAMIC 판단 시계 접점 필요 여부(§1 한계), throttle 첫 통과 후보 수용 여부, phantom 정리 경로의 손익 덮어쓰기(추정 청산, 미변경), 원인 미확인 3건(§3).
