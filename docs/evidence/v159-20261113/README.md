# v1.159 최종 검증 실행 근거 — 399차 개발 제출 (2026-11-13 KST, 총괄 판정 전)

**지시:** [총괄 v1.159 / 398차](../review-b0859306/README.md) §3~§6. **운영 서버·운영 DB에는 접속하지 않았다.** 배포·DDL·세션 생성·T0·F/S 핀 변경은 하지 않았고 승인되지도 않았다. 모든 실행은 로컬(작업트리·로컬 PG `v152-pg`·로컬 Docker)이다.

**최종 후보가 바뀌었다: `4a4a04a3` → `802b691a`(`integration/v158`).** 4a4a04a3 전체 회귀와 운영 방식 프런트 빌드에서 결함 둘을 찾았고, 원인을 확인한 뒤 시험 1파일과 프런트 `.dockerignore` 한 줄만 고쳤다. 변경 경계는 커밋 전에 [change-boundary.md](change-boundary.md)에 적었다. 제품 Java·V96·백엔드 빌드 입력은 그대로라 jar `0834382a…`를 재빌드 없이 연결한다.

**802b691a 전체 회귀에서 새 실패 1건(SchedulerProfileCleanupTest LIVE)이 나왔다. 원인은 §4에 적은 범위까지만 확인했다. 이 SHA는 아직 전체 통과가 아니다.**

## 1. 요약

| 항목 | 결과 | 근거 |
|---|---|---|
| jar 보존 | `0834382a…`를 build 밖 `/d/tmp/v159/jar/4a4a04a3.jar`에 두고 해시 대조 | §2 |
| 4a4a04a3 전체 회귀(1회) | 1,776 중 실패 2(N16 ② LIVE·DYNAMIC)·건너뜀 78. 원문 체크섬 선고정(281줄) | `runs/int-full-4a4a04a3` |
| N16 실패 | 원인 확인: 시험 보조 `drain`의 판정 경쟁(제품은 1회 제출). 시험만 보완 `02d14061` | §3-1 |
| 프런트 운영 빌드 | 4a4a04a3 Docker 빌드 **실패**(e2e 설정 파일이 빌드 문맥 밖 모듈을 가져옴). `.dockerignore` 1줄 `802b691a` → 성공 | §3-2, §7 |
| 802b691a 전체 회귀(1회) | 1,776 중 실패 1(SchedulerProfileCleanupTest LIVE)·건너뜀 78. N16 통과. 원문 체크섬 선고정(280줄) | §4, `runs/int-full-802b691a` |
| 건너뜀 10건 차이 | **원인 확인**: 클래스 단위로 건너뛴 매개변수 메서드 10개(정산 반올림 8·N15 2)를 콘솔은 세지 않는다. 실험 2회로 재현 | §5 |
| 필수 PG | 연결 5묶음 29 + 새 실행 10클래스 49 + AbChainV4 seed/main 1/1·1/1, 실패 0 | §6 |
| 손실 조회 v2 ↔ 제품 | 10/10(손실·분모·손실률·창별 판정·결론·판정 불가·risk_config 없음·한도 NULL) | §6-2 |
| 구판 실제 기동(V96) | 18/18 — V94 실제 DB 복제 → 후보 jar 기동으로 V96 하나 → 보존 구판 jar `java -jar` 기동·읽기·쓰기·새 열 보존 | §8 |
| 상태 기반 복구 관문 3판 | 로컬 PG 예행 19/19, 관문 전후 표 내용 해시 같음 | §9 |
| 구 프런트 + 새 API | 키 없는 세션 표시·정지 이동 같음(A·B 통과). 새 모드 CLOSING 은 구 화면이 0원·보유 0 으로 보임(C 관측) | §7 |
| D4(V159D4) | 블록 10개 생성·`bash -n`·F+G 연결 문법·값 대조. 대역 r3 **88/88**, 금지 명령 0. r2 에서 U4 결함 1건 발견·수정 | §10 |
| 문서 | 문서 정리 `b12f19f8`, F5-3 새 출처 `729cdbe2` | §11 |

## 2. 실제 jar 보존

`/d/tmp/int158/web-api/build/libs/web-api-0.1.0.jar`를 회귀 전에 `/d/tmp/v159/jar/4a4a04a3.jar`로 복사했다. 두 파일 모두 `0834382ab008e1b2…`다. 4a4a04a3 ↔ 802b691a 차이는 시험 1·프런트 `.dockerignore` 1이라 백엔드 빌드 입력(제품 Java·리소스·build.gradle·모듈)이 같다. 그래서 jar를 재빌드하지 않고 v158 §7의 비교 결과(보존 b5cd1d0 대비 881→895, 변경 48·추가 14)를 802b691a에 연결한다.

## 3. 4a4a04a3 전체 회귀와 실패 조사

`runs/int-full-4a4a04a3` — 시작 2026-11-13 08:21:21, 끝 08:41:04, 종료값 1, 추적 변경 0, 수집 폴더 새로 만들고 `build/test-results/test` 비운 뒤 실행.

| 모듈 | 시험 | 실패 | 건너뜀 | 통과 |
|---|---|---|---|---|
| core-engine | 298 | 0 | 7 | 291 |
| exchange-adapter | 11 | 0 | 0 | 11 |
| strategy-lib | 113 | 0 | 0 | 113 |
| web-api | 1,354 | **2** | 71 | 1,281 |

콘솔: `1344 tests completed, 2 failed, 61 skipped`. 새 탐침 SubmitOrderBoundaryProbeTest 는 통과했다. 원문 체크섬은 후속 시험 전에 고정했다(281줄, 목록 sha `c019c9cd…`).

### 3-1. N16InvalidPriceReproTest ② — 시험 판정 경쟁(시험만 보완)

- 실패: ② LIVE·DYNAMIC "null 다음 신선한 280 → 보호 매도 1회" 기대 1, 실제 0. 두 사례 모두 포지션은 CLOSING 이었다. 1eed27d1 전체 회귀에서는 같은 순서로 통과했다.
- 단독 재실행(`runs/n16-alone-1`): 13/13 통과.
- 판정식 진단(`runs/diag-drainrace`, 임시 시험 `tools/DrainRaceDiagTest.java`, 실행 뒤 제거): 시험의 `drain` 판정식을 같은 설정 실행기에 적용. 작업자가 있는 실행기에서 20,000회 중 **8회**는 막 넘긴 작업이 실행되기 전에 "비었다"고 답했다. 새 작업자 경로는 2,000회 중 0회.
- 실제 회귀 안 관측(`runs/diag-full-n16`, 4a4a04a3 별도 작업트리 + N16 관측 사본, diff `worktree.diff`): ② DYNAMIC 에서 `drain` 반환 직후 매도 **0**, 실행기 `active=1 task=59 done=58`, 주문 `INTENT_READY` → 21ms 뒤 매도 **1**, `SUBMITTED`. 시험 스레드 트랜잭션 없음(sync=false·tx=false). 제품은 1회 제출했다.
- 보완 `02d14061`(시험만): 매도 1회를 단언하는 두 곳(①·②)에서 거래소 호출을 최대 15초 기다린 뒤 실행기를 다시 비우고 **정확히 1회**를 단언한다. 기대값·0회 단언·제품 코드는 그대로다. 수정본 단독 13/13(`runs/n16-fix-alone`). 정리 경합 후보 가지에도 체리픽(`candidate/closing-race-guard` `0bd4cea9`).
- 한계: 0회 단언 4곳은 이벤트가 동기적으로 버려지는 경로라 이 경쟁으로 실패하지 않지만, 늦게 나간 매도를 놓칠 수는 있다(거짓 통과 쪽). 같은 `drain` 판정식을 쓰는 다른 시험 파일은 바꾸지 않았다.

### 3-2. 프런트 운영 Docker 빌드 실패(빌드 입력 1줄 보완)

- 4a4a04a3 프런트를 운영과 같은 `Dockerfile`·`.dockerignore`로 로컬 `docker build`(node:20-alpine): **실패**. `playwright.paper-render.config.ts`의 `./e2e/auth-fixtures` 모듈 없음(타입 검사). `.dockerignore`가 `e2e`·`playwright.config.ts`는 빼지만, v1.153 G(5f9ef2ad)가 추가한 이 설정 파일은 빼지 않는다. 로컬 `next build`는 e2e 폴더가 있어 통과한다(`runs/fe-new-4a4a04a3`).
- 대조: b5cd1d0 프런트 같은 방식 빌드 성공(`runs/fe-docker-b5cd1d0`).
- 보완 `802b691a`: `.dockerignore`에 `playwright.paper-render.config.ts` 한 줄(CRLF 유지). 작업 사본(`runs/fe-docker-fix1`)과 커밋본 `git archive` 사본(`runs/fe-docker-802b691a`) 모두 빌드 성공. 이미지는 로컬 태그만, 어디에도 올리지 않았다. 빌드는 재현 가능하지 않아 이미지 ID는 매번 다르다.

## 4. 802b691a 전체 회귀 — 새 실패 1건

`runs/int-full-802b691a` — 시작 09:13:22, 끝 09:40:31, 종료값 1, 추적 변경 0. 같은 시간에 다른 로컬 작업(대역·Docker 빌드·PG)이 겹쳐 27분 걸렸다.

| 모듈 | 시험 | 실패 | 건너뜀 | 통과 |
|---|---|---|---|---|
| core-engine | 298 | 0 | 7 | 291 |
| exchange-adapter | 11 | 0 | 0 | 11 |
| strategy-lib | 113 | 0 | 0 | 113 |
| web-api | 1,354 | **1** | 71 | 1,282 |

콘솔 `1344 tests completed, 1 failed, 61 skipped`. 원문 체크섬 선고정(280줄, 목록 sha `6375eef4…`). N16 ①·② 모두 통과.

- 실패: `SchedulerProfileCleanupTest` LIVE — "scheduler 프로필에서 @Scheduled 정리 작업이 60초 안에 대기 행을 가져가야 한다". 4a4a04a3·1eed27d1 전체 회귀에서는 22초 안에 통과했다. 같은 실행의 DYNAMIC 은 통과.
- 원문: 09:38:27 `정지 후 정리 — 청산 매도 제출 (posId=658 …)` → 포지션 CLOSING, 시도 1, 60초 시점 메모 `청산 매도 체결 대기`. 정리 작업은 행을 가져가 매도 의도를 만들었는데, **60초 동안 거래소 주문 ID 가 붙은 매도가 생기지 않았다**(제출이 끝나지 않음).
- 정리 경로는 행마다 새 트랜잭션에서 `markClosingWithSellIntent`(CLOSING+의도) → 커밋 뒤 `orderExecutor` 제출이다. 정적 읽기로는 제출이 빠지는 경로를 특정하지 못했다.
- 진단: 4a4a04a3 별도 작업트리에서 이 시험에 60초 시점 매도 행 상태·주문 ID·실패 코드·`createOrder` 호출 수를 출력하는 관측만 더해 web-api 전체를 한 번 더 돌렸다(`runs/diag-full-sched`). 결과는 §4-1.

### 4-1. 진단 결과 — 재현되지 않음, 원인 미확정

- `runs/diag-full-sched`(09:46:09–10:09:57, 종료값 0): web-api 전체 **실패 0**. LIVE 22.2초 — 60초 시점 관측 `563:SELL:SUBMITTED:ex=uuid-sched-sell-…`, `createOrder` 1회. DYNAMIC 19.9초도 같은 모양.
- 실패 실행과 통과 실행 모두 주문 실행기 스레드 로그가 남지 않아(기본 로그 수준) 실패 때 매도 행이 INTENT_READY·SUBMITTING·FAILED 중 어디였는지는 **알 수 없다**. 차이로 남은 것은 실패 실행에서 LIVE 포지션 #658 에 `PositionLimitGuard` 줄이 10초마다 4번 찍힌 것(통과 실행 0번)뿐이며, 매도가 제출되지 않은 상태와 맞을 뿐 이유를 말해 주지 않는다.
- 판단: **시험 시간 문제라고도, 제품의 제출 유실이라고도 단정하지 않는다.** 근거 없이 시험의 60초 기준을 늘리거나 단언을 바꾸지 않았다. 다음 실패에서 상태를 남기려면 이 시험에 "기준 시각 초과 시 매도 행 상태·주문 ID·실패 코드·createOrder 수 출력"을 더하는 시험 변경이 필요하다(SHA 변경 — 판정 요청).
- 정리 경합 후보 가지 `0bd4cea9`(N16 체리픽)의 N16 단독 13/13(`runs/cr-n16-0bd4cea9`).

## 5. 콘솔 1,344/61 대 XML 1,354/71 — 원인 확인

- 보존 원문 재집계: 차이 10은 n13 두 클래스(4+6)와 수가 같아 처음에는 그쪽을 의심했다. 단정하지 않고 실험했다.
- 실험(`runs/skipcount-a`, `-b`, 임시 실패 시험 `tools/SkipCountProbeFailTest.java` — Gradle 은 실패가 있을 때만 집계 줄을 찍는다):
  - a: n13 둘 + V96 이행 + 정산 반올림 + 임시 실패 → 콘솔 12/1/11, XML 20/1/19. **n13 은 콘솔에 세어졌고** 정산 반올림 8 이 빠졌다.
  - b: n13 없이 V96 이행 + 정산 반올림 + 임시 실패 → 콘솔 2/1/1, XML 10/1/9. 같은 8 이 빠졌다.
- 원인: 클래스 단위 조건(`@EnabledIfEnvironmentVariable`)으로 건너뛴 클래스의 **`@ParameterizedTest` 메서드**를 XML 은 메서드마다 건너뜀 1건으로 적고, 콘솔 집계는 세지 않는다. 전체 회귀의 건너뜀 클래스 중 매개변수 메서드는 SettlementRoundingPgTest 8 + N15PgLockWaitExpiryTest 2 = **10**. 낡은 XML 때문이 아니다(실행 전 결과 폴더를 비웠다). XML·원문은 삭제·편집하지 않았다.
- 같은 이유로 PG 로 실제 실행하면 사례가 펼쳐져 수가 다르다(정산 반올림 8→16, N15 4→6).

## 6. 필수 PG 실행·연결표 (최종 후보 802b691a)

802b691a 의 시험 외·프런트 외 블롭은 4a4a04a3 와 같고, 바뀐 시험(N16)은 PG 시험이 아니다. 그래서 4a4a04a3 에서 실행한 결과를 802b691a 에 연결한다. 모든 실행은 클래스마다 새 DB(`v152-pg`, 로컬)다.

| 묶음 | 클래스 | 결과 | 출처 SHA·DB |
|---|---|---|---|
| 정리 경합 | ClosingIntentAtomicityPgTest·RollbackPgTest | 7/7 | 4a4a04a3, v158 `runs/new-pg-closing`(연결) |
| 손실 세 보호식 | RealLossThreeFormulaPgTest | 8/8 | 4a4a04a3, v158 `new-pg-loss3`(연결) |
| 기존 전역 손실 | RealLossGlobalPgTest | 3/3 | 4a4a04a3, v158 `new-pg-lossold`(연결) |
| LIVE 창 | LiveLossWindowPgTest | 7/7 | 4a4a04a3, v158 `new-pg-lw`(연결) |
| phantom | PhantomKeepsConfirmedPnlPgTest | 4/4 | 4a4a04a3, v158 `new-pg-phantom`(연결) |
| N15 잠금 | N15PgLockWaitExpiryTest | 6/6 | 4a4a04a3 `pg-n15` `p159_pg_n15_13307` |
| 부분 매도 | PartialSellRealizedPnlPgTest | 7/7 | `pg-partialsell` |
| V96 이행 | V96MigrationPgTest | 1/1 | `pg-v96mig` |
| V96 동작 | AlignedV96PgBehaviorTest | 5/5 | `pg-v96behavior` |
| 잔고·목록 스냅샷 | BalanceListSnapshotPgTest | 2/2 | `pg-balancelist` |
| 성과 스냅샷 | PerformanceSnapshotS5PgTest | 1/1 | `pg-perfs5` |
| 정산 반올림 | SettlementRoundingPgTest | 16/16 | `pg-settleround` |
| started_at 정밀도 | StartedAtPrecisionPgTest | 1/1 | `pg-startedat` |
| N13 | HoldBaselineLoopPgTest·SqlEquivalencePgTest | 4/4·6/6 | `pg-n13loop`·`pg-n13sqleq` |
| A/B v4 | AbChainV4PgTest seed-other → main | 1/1 → 1/1 | `ab4-seed-other`·`ab4-main`, 구동기 `chain_driver_v4.py` 31709f5c… |
| 손실 조회 v2 대조 | (도구) RealLossPreflightSqlParityPgTest | 10/10 | `pg-preflight-parity` |

- 제외(필수 PG 아님): `AbChainPgTest`(폐기된 구판 구동기, 없어진 `v150-pg` 컨테이너 고정 — v157 근거와 같은 사유), `PullbackMtfBacktestRunner`(수동 연구 실행기, `@Disabled`, 업비트 네트워크 의존).
- 새로 돈 10클래스 합 49건, 연결 5묶음 29건, AbChainV4 2회. 실패 0.

### 6-1. (참고) AbChainV4

v157 절차 그대로 seed-other 가 다른 DB 를 만들고 main 이 그 DB 를 받는다. 각 회차는 15분 칸의 6~11분 생성 창을 기다린다. 4a4a04a3 는 v157 의 사전 확인 커밋(cdf9be99)을 포함한다.

### 6-2. 배포 전 손실 조회 v2 와 제품 대조

- 조회문 [`d4-draft/preflight-real-loss-readonly.v2.sql`](d4-draft/preflight-real-loss-readonly.v2.sql)(sha `af4105c1…`): 한 기준 시각(트랜잭션 `now()` 또는 시험용 `preflight.asof`), 창은 Java `Instant.minus(DAYS)`와 같게 24·168·720시간. 식별 출력 = 식·창마다 손실·분모·분모 출처(RUNNING LIVE / 설정 portfolio_limit_krw / 최종 폴백 10,000,000 / RUNNING DYNAMIC REAL / 합)·손실률·한도·판정(허용/거절/판정 불가) + 마지막 `판정` 줄(제품과 같은 검사 순서의 첫 거절 사유). 반올림은 `div()`·나머지로 소수 4자리 HALF_UP 을 정확히 계산(numeric 나눗셈 뒤 round 의 이중 반올림 회피), 비교는 `>` 거절·동률 허용.
- 대조 도구 [`tools/RealLossPreflightSqlParityPgTest.java`](tools/RealLossPreflightSqlParityPgTest.java)(제품 트리 밖, 실행 때만 복사·실행 뒤 제거): 같은 DB 행을 조회문과 제품 메서드(legacyLossKrw·calculateLossPct·resolvePortfolioLimit·runningLiveCapital·runningDynamicRealCapital·configuredOrFallbackLimit·pct 를 같은 기준 시각으로 반사 호출, 비교는 RiskEngine.check)가 읽고 칸별로 대조, 결론은 공개 `checkRealLossGuard()`와 대조. 손실 행은 확정 매도 주문 행·FILL 기록을 직접 넣었다 — **체결 경로 시험이 아니다**(그건 RealLossThreeFormulaPgTest 8/8).
- 사례 10: 손실 0·자본 0(설정 한도·최종 폴백) / LIVE 단독 2% 거절은 DYNAMIC 자본 900,000 을 더해도 거절 / DYNAMIC 손실·자본 0 판정 불가 / PAPER 제외 / 이익 비상쇄 / 반올림·동률(1,500=1.50 허용, 1,504.99→1.50 허용, 1,505→1.51 거절) / 전체 REAL(희석·DYNAMIC 1.71% 거절·RUNNING 없음 설정 분모) / 창 경계(since 정각 포함·1µs 앞 제외) / risk_config 없음 / 한도 NULL(제품 예외 ⇔ 조회문 판정 불가).
- r1(`runs/pg-preflight-parity-r1-toolbug`) 9/10: **도구 결함**(판정 불가 기대를 창 전체로 묶음 — 제품·조회문은 창마다). 도구만 창별로 고쳐 r2 10/10.
- 정책(398차 §4): 마지막 줄이 `진행 가능`이 아니면(거절·판정 불가·필수값 누락·조회 실패) 배포 전 중단·보고. D4 U0 에 그대로 넣었다(§10).

## 7. 프런트

- 런타임 경로: `src/app/paper-trading/[sessionId]/page.tsx`·`history/page.tsx`·`page.tsx`, `src/lib/paperValuation.ts`·`types.ts`. 시험/설정: `e2e/paper-render.spec.ts`, `playwright.paper-render.config.ts`, (802b691a) `.dockerignore`.
- 802b691a 프런트 = 5f9ef2ad(v1.153 G) + `.dockerignore` 1줄 → v1.153 렌더링 7/7·tsc 0 을 연결한다. 오늘 4a4a04a3(런타임 경로 같음) 로컬: tsc 0, eslint 오류 0(경고 21, `src` 전체), `next build` 성공(node 22, `runs/fe-new-4a4a04a3`). 운영 방식 Docker 빌드는 §3-2.
- **구 프런트(b5cd1d0) + 새 API**(`runs/fe-compat-old`, 임시 시험 `tools/frontend/paper-compat-old.spec.ts`, b5cd1d0 작업트리에서 실행 뒤 제거): A 키 없는 세션(사전 등록 66 형태) 목록·상세가 같은 숫자 101,234.5, 화면 오류 0 — 통과. B 정지(대기 0) 응답 뒤 목록 이동 — 통과. C(관측) 새 모드 CLOSING·평가 null: 화면 오류는 없으나 `총 평가 자산 0KRW +0.00%`·`보유 포지션 0개`로 보이고 정지 뒤 대기 안내 없이 목록으로 간다 — **잘못 읽힌다.** 그래서 backend 먼저·frontend 나중의 사이 구간에는 새 모드 세션이 없어야 안전하다(D4 UF2 머리말·U2 문구).

## 8. 구판 b5cd1d0 실제 기동 — V94 복제 + V96

[`tools/oldbin_v96.py`](tools/oldbin_v96.py), `runs/oldbin-v96-r2` **18/18**. 보존 구판 jar `a7ef759f…`, 후보 jar `0834382a…`, 로컬 `v152-pg`·`cat-rt-redis`, 외부 접속 차단(프록시 127.0.0.1:9).

1. 빈 DB 에 구판 `java -jar` → Flyway V94(115행). V94 에 새 열 없음.
2. 대표 행(LIVE·DYNAMIC REAL·종전 PAPER·LIVE_ALIGNED 와 포지션) → V94 복제본(`CREATE DATABASE … TEMPLATE`).
3. 복제본에 후보 `java -jar` → **V96 하나만**(체크섬 `-424836241`), V95 없음. V94 열의 표 내용 해시 불변, 두 열 NULL 허용·기본값 없음·기존 행 NULL.
4. 새 열 값 기록 + 종전 PAPER RUNNING 1행.
5. 같은 복제본에 **구판 `java -jar` 재기동**: 기동 16초, 60초 생존, Flyway 이력 불변(96 그대로·95 없음) = validate 통과, 스키마 관련 ERROR 0(ERROR 5줄은 로컬 공유 풀 환경 변수 없음·거래소 차단). 읽기 GET 4종 200·success, 쓰기 = 구판 API 정지 POST 200·success·RUNNING→STOPPED, 새 열 값 보존(구판 UPDATE 뒤에도), 다른 PAPER 행 V94 열은 V94 원본과 같음.
6. 후보 재기동 — 추가 마이그레이션 없음.

r1(`runs/oldbin-v96-r1`) 16/18 은 **장치 결함**: V9 마이그레이션이 기본 PAPER 행(id 1)을 넣는 것을 몰라 id 를 가정했고, 정지 POST 의 `success:false`(실행 중 아님)를 통과로 셌다. RETURNING 으로 id 를 받고 success 를 확인하게 고쳐 r2. 구판 기동 성공은 복구 승인이 아니다(§9).

## 9. 상태 기반 복구 관문 3판

[`rollback-gate-v3/`](rollback-gate-v3/) `gate.sql`(sha `c9dc2056…`)·`rollback_gate.sh`(sha `fe19aab4…`)·`rehearse.sh`. 2판(v153 `h-rollback/v2`)은 원문 그대로 둔다.

- 2판과 다른 점: "새 모드 세션 0(생성 전)"만으로 통과하지 않는다. 생성 전·후 모두 ① 새 모드 RUNNING·OPEN·CLOSING·이상 상태 0 ② 실계좌 미종결 주문(PENDING·INTENT_READY·SUBMITTING·UNKNOWN·SUBMITTED·PARTIAL_FILLED)·회계 반영 대기·정리 대기·미해결 정산 불일치·실계좌 CLOSING 0(F3 개수식과 같은 정의) ③ 반영값/보고값 없음·보고≠반영 0 ④ RUNNING 실계좌 세션의 주문 순체결량 = 열린 포지션 수량, RUNNING LIVE 잔액 = F3 잔액 기대식(v1.33)을 초기자본으로 일반화한 값 ⑤ 실계좌 RUNNING·보유가 있으면 거래소 대조 근거 파일(`EXCH_RECON`, 승인된 읽기 블록의 출력)이 있어야 하며 해시를 장부에 남긴다 ⑥ 장부(새 모드·실계좌 상태 CSV + 관문 줄 + 대조 근거 사본) 덮어쓰기 없이·체크섬 재검증·완료 표지까지 성공해야 `ROLLBACK_GATE_OK`.
- 판정: 0 OK / 2 HOLD_PENDING(현재 바이너리로 정지·유효 가격 대기·정산/정리 계속, 보고) / 3 STOP_RUNNING·STOP_MISMATCH·STOP_UNKNOWN / 4 LEDGER_REFUSED. 0원 정산·행 삭제·상태 강제 변경·마이그레이션 삭제로 조건을 만들지 않는다.
- 로컬 PG 예행 `runs/gate-v3-r2` **19/19**(템플릿 h153 = 4a4a04a3 와 마이그레이션 트리 `7bca8a02…` 같음), 관문 전후 관문이 읽는 9개 표 내용 해시 같음. r1 18/19 는 예행 준비 결함(정리 대기 행의 외래 키) — 준비문만 고쳐 r2.
- 한계: `EXCH_RECON` 의 내용(거래소 잔액·수량이 맞는지)은 관문이 판정하지 않는다 — 운영자가 승인된 읽기 블록의 출력으로 판단하고 관문은 존재·비어 있지 않음·해시만 확인한다. 잔액 대조는 RUNNING LIVE 만(DYNAMIC REAL 잔액 식은 범위 밖, 수량 대조와 거래소 근거로 대신). 실제 서버 psql 경로·권한·운영 자료 분포는 대역으로 주장하지 않는다.

## 10. D4 — V159D4 (실행 금지 초안)

- 생성기 [`d4/mkdraft5.py`](d4/mkdraft5.py): 원본 = SMA20PD4(`sma20p-d4-af7ffa58/d4/blocks-d4`, 덮어쓰지 않음). 바꾸는 곳마다 횟수 확인, 원본 표기 잔존 시 중단.
- 블록 [`d4/blocks-d4/`](d4/blocks-d4/) 10개(U변수·U0·U1·U1되돌림·U2·U3·**UF2**·U4·U되돌림·U뒤관측), 해시 [`d4/blocks-d4.sha256`](d4/blocks-d4.sha256). 각 블록 `bash -n` 통과, F+G+U변수+블록 연결 `bash -n` 통과, U변수를 불러온 DIFFN(100)·RARX(62)가 원본 목록과 같음.
- 값: OLD b5cd1d0, NEW **802b691a**, IMGOLD 52e12428…(#27, 마지막 실제 운영 읽기 기준), DIFFN 100경로(시험 70·제품 Java 20·V96 1·build.gradle 1·프런트 8), RARX 62줄·JARN `(끝) 881 895`, V96 블롭 `5b92eef0…`·Flyway 체크섬 `-424836241`.
- 순서: U0(기존 확인 + V95 없음·V96 원문·frontend 실행 이미지 = FEIMGOLD·**배포 전 손실 조회 v2: `진행 가능`이 아니면 중단**) → U1(:14~:24 밖, checkout·backend 빌드·jar 비교 + frontend 기존 이미지 보존 태그 `…:b5cd1d0`·새 frontend 이미지 빌드, 재기동 없음) → ⏸ 운영자 확인 → U2(V94 확인 → backend 재기동, Flyway V96 한 줄·체크섬 확인, frontend 는 구판 그대로) → 5~10분 U3(배포는 V96 적용 줄 기록, 되돌림은 적용 없음, Flyway 96) → **UF2**(U3 deploy 뒤 frontend 만 교체, 다른 서비스 불변, `/` 응답 200·30x, frontend 로그 오류 없음) → U4(30분, UF2 필수, frontend 는 UF2 기록과 대조) → 뒤 관측.
- 되돌림: U1되돌림은 backend·frontend latest 를 기존 이미지로(재기동 없음). **U되돌림은 관문 3판을 고정 커밋 GS 에서 꺼내 해시 확인 후 실행하고, `ROLLBACK_GATE_OK`가 아니면 구판으로 가지 않고 멈춘다.** 통과하면 frontend(교체했으면) → backend 순서로 기존 이미지 복귀(구 프런트 + 새 API 조합 A·B 가 확인된 쪽을 먼저 만든다). V96 은 되돌리지 않는다(§8).
- **미정 값과 채우는 규칙**(추정하지 않는다):
  - `FEIMGOLD` — 실행 중 frontend 이미지 ID. U0 직전 읽기 전용 `docker inspect` 값을 총괄 승인 판에 기입. 미정이면 U0 가 멈추고, 값이 실행 이미지와 다르면 멈춘다.
  - `GS` — 관문 3판·조회문 v2 를 담은 main 고정 커밋(이 근거 커밋 뒤 기입). 미정·없음이면 U0·U되돌림이 멈춘다. 꺼낸 원문 해시가 `GATESQL`·`GATESH`·`PFSUM`과 다르면 멈춘다.
  - 새 backend·frontend 이미지 ID — U1 이 빌드해 `image-new.txt`·`image-fe-new.txt`에 적는다(사전 값 없음). 이후 블록은 실행 이미지가 이 값과 다르면 멈춘다.
  - 장부·점검 설치본·crontab — SMA20PD4 값(LS·CK·CRON)을 그대로 두었다. U0 가 운영 현재값과 다르면 멈춘다(바꾸지 않는다).
- 대역([`d4/standin/`](d4/standin/)): SMA20PD4 대역 장치 사본에 frontend 이미지·`compose build/up frontend`·Flyway 96 이력·표준 입력 SQL(손실 조회 v2·관문 3판·COPY)·GS `git show/ls-tree`·frontend `curl`을 더했다. 손실 조회 응답은 로컬 PG 에서 실제 psql 로 뽑은 출력(`fixbase159/preflight-ok·impossible.txt`), 관문 줄은 관문 예행의 실제 출력이다. 관문 스크립트는 대역 안에서 실제로 돈다.
  - r1 67/88: 장치 결함(가짜 docker 가 conf 값을 읽지 않아 V96 체크섬 기본값 공백) — 모든 U2 가 멈춤.
  - r2 85/88: **블록 결함 1** — U4 가 UF2 뒤 "backend 외 서비스가 바뀜"으로 항상 멈춘다. 생성기에서 U4 를 고쳤다(UF2 필수, frontend 는 UF2 기록과 대조). 장치 결함 1(되돌림 뒤 두 번째 U3 가 같은 출력 파일 이름). 고치기 전 블록은 `d4/blocks-d4-gen1-before-U4fix`.
  - **r3 88/88, 19사례 금지 명령 기록 0줄**: 정상(U0→U1→U2→U3→UF2→U4→뒤 관측), U0 경계 8(FEIMGOLD 미정·손실 판정 불가·조회 실패·V95·101번째 경로·frontend 이미지 다름·GS 미정·판정 줄 2개), U1 frontend 빌드 실패 → U1되돌림, U1 뒤 운영자 취소 → U1되돌림, U2 V96 체크섬 다름, UF2 순서·응답 502, U되돌림(UF2 전·후 복귀, 관문 HOLD 로 멈춤·HEAD/backend 그대로, GS 미정, 장부 폴더 있음 → 관문 종료 4).
  - 대역으로 주장하지 않는 것: 실제 docker 재기동·헬스·서버 빌드 시간, 실제 Flyway 로그 문구, frontend 실제 화면, 운영 자료 분포, 경쟁 상태.

## 11. 문서

- `b12f19f8`: 문서 정리 보존(START_HERE·CURRENT_HANDOFF·RUNBOOK 분리·관리 문서·*_ARCHIVE_20261112·점검 근거·추출 도구 참조·총괄 v1.159 근거) — 제품 통합과 별도. 점검 근거 체크섬 11/11·9/9·2/2·2/2 와 스테이징 블롭 일치. `.claude/settings.local.json` 제외.
- `729cdbe2`: RUNBOOK **F5-3v** — 같은 커밋의 RUNBOOK 에서 F3 추출, fetch 실패·RUNBOOK 없음·0바이트·기대 해시(`bb86fb39…`) 불일치면 F3·완료 조회를 실행하지 않고 멈춤. 기존 15블록은 b12f19f8 바이트·순서 그대로. `scripts/ops/test_f53v_source.py` 15/15. 영향 시험 test_start_gate 7/7·test_cron_switch 44/44·test_sid_ledger 92/101 — **변경 전 RUNBOOK 으로도 같은 9건 실패**(로컬 `cat-rt-pg`의 SID 203 자료가 보존 원본 20,182.36 이 아닌 19,142.78. 컨테이너 자료는 바꾸지 않았다). README 의 "네 곳을 함께 고친다"는 실계좌 전용 관문·거래소 주문 상태·선택 모드의 의도된 차이를 함께 적도록 고쳤다.

## 12. 하지 않은 것·남은 것

- 운영 서버·운영 DB 접속, 배포·DDL·세션 생성, F/S 핀·T0 변경, 이미지 푸시: 하지 않았다.
- 802b691a 의 SchedulerProfileCleanupTest LIVE 실패 — §4-1 진단 결과에 따라 원인 확정·보완 여부를 정한다. 확정 전에는 802b691a 를 전체 통과로 쓰지 않는다.
- D4 미정 값(FEIMGOLD·GS)과 장부·점검 설치본·crontab 의 운영 현재값 재확인, 실제 서버 빌드 시간·frontend 실제 화면은 승인된 운영 단계에서만.
- 마지막 실제 운영 읽기는 v158 기준 그대로다(이 작업은 새 운영 확인이 아니다).

모든 파일의 체크섬은 [SHA256SUMS](SHA256SUMS)에 있다. 폴더 전용 `.gitattributes`(`* -text`)로 바이트를 보존한다.
