# 최종 로컬 통합 d81c3f2f — 시험 원천 보정·PG 선별·전체 회귀1회·LF jar (총괄 v1.196 §2·§3-1~4)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 최종 고정 SHA **d81c3f2f91c0239aacb0e1176dde9b898f4d7466**(가지 `integ/final-abc-60df3437`, 사본 `D:/tmp/final-abc`, LF 사본 `D:/tmp/final-lf-d81c3f2f`). 운영 접속·운영 DB·배포·실제 생성은 하지 않았다. 60df3437·09fa2d1c·596ed4b0과 그 실패/후보 기록은 그대로다.

## 1. 포함 범위 [integration-commits.txt](integration-commits.txt)

| 묶음 | 원 커밋 → 이식 | 내용 |
|---|---|---|
| 기점 | 60df3437 | A+B+C 통합(불변) |
| 재진입 시험 누적 | 7e0143e1·09fa2d1c → 1b9110cb·d4ce64a2 | 60df3437→09fa2d1c 두 커밋 전체(마지막 하나만이 아님) |
| REAL 매수 요약 | 9a297069·c8cb48a0·44b5bf4b·69c81c3d·596ed4b0 → 871e4aa4…786978cd | 제품 `OrderSettlementService` 1파일 + 시험/도우미 |
| LiveLossWindow 보정(새) | 0401d0e9 | 시험 1파일 +36/−7 |
| RealLossGlobal 보정(새) | d81c3f2f | 시험 1파일 +19 |

- 이식 7커밋 patch-id **7/7 같음**. 원 가지에서 온 최종 블롭 4개가 원 가지 끝(09fa2d1c·596ed4b0)과 같다.
- 60df3437 대비 제품 변경은 `OrderSettlementService.java` 하나(69c81c3d)다. 정책·수수료 정의·손절/익절·위험 분모/한도는 추가하지 않았다.

## 2. 시험 원천 보정 [fixture-lw-rl.diff](fixture-lw-rl.diff)

**LiveLossWindowPgTest (0401d0e9, §2-1·2).** LIVE 세션 포지션마다 확정 BUY를 같은 세션 종류/ID·포지션 ID로 둔다.
- 값: 체결 수량 = 포지션 수량, 반영 대금 = 수량×평균가(0.0016×12,495,000 = 19,992), 확정 매수 수수료 **0 명시**(NULL 아님). 시각은 포지션 진입 시각.
- 매도는 기존 폴러 `checkOrderStatus`·`reconcileClosingPositions`·`settle` 경로를 그대로 지난다.
- ⑥의 비세션 FILL 대조군과 DYNAMIC REAL 범위 밖 관측에는 원천을 붙이지 않았다.
- 기존 단언은 하나도 바꾸지 않았다.

| 실행 | 결과 |
|---|---|
| [lw-0401d0e9](lw-0401d0e9/) 새 DB `lwfin_0401` | **7/7 rc=0** |

[LW154] 추적값:
- ① 한도 거절 사유 "일일 손실 한도 초과: 2.00% > 1.50%"
- ② 체결만 0 → 확정 2,001 → 재확정 2,001 → 정정 2,002
- ③ 1,000.50 → 3,000.50
- ④ 롤백 0
- ⑤ 2,001
- ⑦ T 포함·T+1µs 제외 차이 2,001

**RealLossGlobalPgTest (d81c3f2f, §2-3 선별에서 발견).** 보정 전 [direct-0401d0e9](direct-0401d0e9/pg-RealLossGlobalPgTest/)은 3/3 rc=0이었다. 그러나 ①② 거절 사유가 **"판정 불가 — 확정 비용을 구하지 못했다"**였고 분자는 0이었다. 한도 판정이 아니라 원가 미상으로 통과한 것이라 v1.196 §2-2(판정 불가로 통과시키지 않음)에 어긋난다. 그래서 다음과 같이 보정했다.
- `lossSell`에 같은 방식으로 확정 BUY를 둔다.
- ①② 거절 사유에 "판정 불가"가 없음을 단언에 더한다. 기존 승인/거절 단언은 그대로 두었다.
- 결과 [direct2-d81c3f2f](direct2-d81c3f2f/pg-RealLossGlobalPgTest/) **3/3**. ① "[DYNAMIC REAL 보호식] 일일 손실 한도 초과: 4.00% > 1.50%", ② "일일 손실 한도 초과: 2.00% > 1.50%"(분자 2,001)이다.

직접 넣은 BUY 행은 시험 준비 데이터이며 엔진 왕복 체결의 증거가 아니다. C의 원천 없는 청산·NULL 비용 거절 음성 시험(`CostBasisBoundaryPgTest` 등)은 바꾸지 않았다.

## 3. PG 선별 조사 (§2-3) — 건너뜀 목록 중 손실 합·연속 손실·신규 진입·C/N2 소비자 연결 클래스

| 클래스 | 실행 조건 | 비용 원천을 직접 만드는 위치 | 기존 고정 결과 | 이번 |
|---|---|---|---|---|
| LiveLossWindowPg | LW_PG_URL | `closingPosition`(LIVE 세션, BUY 없음) | 0e1d2a02 7/7, C·60df 1/7 | **보정 → 7/7** |
| RealLossGlobalPg | RL_PG_URL | `lossSell` 포지션 직접(BUY 없음) | C 이후 기록 없음 | 보정 전 3/3(①② 판정 불가로 통과) → **보정 3/3** |
| RealLossThreeFormulaPg | RL_PG_URL | C 후보에서 확정 BUY 포함 | 596ed4b0 8/8. 판정 불가 문구는 ③⑧ "DYNAMIC 자본 0" 음성 사례뿐 | 연결(시험 블롭·제품 동일) |
| PartialSellRealizedPnlPg | PP_PG_URL | C 후보에서 보정 | 596ed4b0 7/7 | 연결 |
| ClosingIntentAtomicityPg | CR_PG_URL | — | 596ed4b0 5/5 | 연결 |
| ClosingIntentRollbackPg | CR_PG_URL | CLOSING 전환만(손실 합 없음) | C 이후 없음 | **2/2** |
| PhantomKeepsConfirmedPnlPg | PH_PG_URL | 확정 BUY 행 있음 | b408a223 4/4(제품 다름) | **4/4** |
| N15PgLockWaitExpiry | N15_PG_URL | 포지션 없음, `checkRisk` 대역 승인 | C 이후 없음 | **13/13** |
| SettlementRoundingPg | SR_PG_URL | b861bdf9에서 BUY 보정 | 69c81c3d 16/16 | 연결 |
| CostBasisRisk/Boundary/FollowupPg | SR_PG_URL | 의도적 원천 미상 음성 포함 | 69c81c3d 통과 | 연결 |
| AbcCrossPg | SR_PG_URL | — | 596ed4b0 19/19 | 연결 |
| RealBuySummaryPg | SR_PG_URL | — | 44b5bf4b 미커밋 차분 23/23 | **고정 SHA 23/23** |

- 연결 근거:
  - 596ed4b0→d81c3f2f의 `src/main` 차이는 0이다. 시험 차이는 DailySma20·LW·RL 세 파일뿐이며, 연결한 클래스의 시험 블롭은 596ed4b0·69c81c3d와 같다.
  - 69c81c3d와 596ed4b0의 차이는 AbcCross 시험 1파일뿐이다. 연결 실행은 [rbs-impact-69c81c3d](../dec-real-buy-summary-20261119/rbs-impact-69c81c3d/)·[rbs-impact2-596ed4b0](../dec-real-buy-summary-20261119/rbs-impact2-596ed4b0/)에 있다.
- 0401d0e9의 실행(DailySma20·Phantom·CR Rollback·N15·LW)도 연결한다. d81c3f2f와의 차이가 RealLossGlobal 시험 1파일뿐이기 때문이다.
- 제외: AbChain(V4)·AlignedV96PgBehavior·BalanceListSnapshot·HoldBaseline 2종·PerformanceSnapshotS5·StartedAtPrecision·SubmitPollRace·V96Migration. 손실 합·진입 판정 호출과 세션 포지션 생성이 없다.

## 4. 고정 SHA 직접 영향 (§3-2) [direct-0401d0e9](direct-0401d0e9/) · [direct2-d81c3f2f](direct2-d81c3f2f/)

| 클래스 | 결과 |
|---|---|
| DailySma20LiveOrderPathTest (H2) | **24/24** |
| LiveLossWindowPg | **7/7** (§2) |
| RealBuySummaryPg (`cat-rt-pg` 새 DB) | **23/23** — [real_buy_summary_checks.json](direct2-d81c3f2f/pg-RealBuySummaryPgTest/real_buy_summary_checks.json) pass 23. 첫 실행(direct-0401d0e9)은 `COSTB_OUT`을 빠뜨려 rc=0만 남았다. 확인 항목 저장을 위해 한 번 더 돌렸다 |
| RealLossGlobal 3/3 · Phantom 4/4 · ClosingIntentRollback 2/2 · N15 13/13 | §3 |

매수 요약 계약은 23항목 안에서 다음을 확인한다: 커밋된 증가분만, 반복·수수료만 정정·취소·롤백 시 중복0, 버퍼 실패 뒤 정산 유지. 버퍼는 메모리다. 커밋 뒤 프로세스 중단·버퍼 예외 때 알림이 빠질 수 있으며, 영속 알림·휴대폰 수신 보장이 아니다.

## 5. 전체 회귀 1회 [full-d81c3f2f/](full-d81c3f2f/)

15:47:45~16:08:03, **rc=0**. HEAD d81c3f2f, dirty 0, 환경 관문 변수 비움. 스크립트 [full.sh](full.sh)는 종전 full.sh의 출력 경로·사본만 바꿨다.

| 모듈 | 시험 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|---|
| core-engine | 298 | 291 | 0 | 7 |
| exchange-adapter | 11 | 11 | 0 | 0 |
| strategy-lib | 113 | 113 | 0 | 0 |
| web-api | 1445 | 1358 | **0** | 87 |
| 합 | 1,867 | 1,773 | 0 | 94 |

- 건너뜀 94 = 종전 93 + 새 `RealBuySummaryPgTest` 1. 모두 환경 변수 없는 PG 클래스와 수동 실행기다([summary.txt](full-d81c3f2f/summary.txt)).
- **필수 PG는 이 전체 통과에 넣지 않는다.** §3·§4의 개별 결과로만 연결한다.
- XML 보존 [xml-copy-check.txt](full-d81c3f2f/xml-copy-check.txt): 47/2/17/223, 해시 불일치 0.
- NET-GUARD: 194클래스, 외부 차단 0.

## 6. LF jar 1회 빌드 [jar-d81c3f2f/](jar-d81c3f2f/)

`git -c core.autocrlf=false worktree add`로 만든 사본에서 빌드했다. 리소스 97개 CRLF 0, 블롭 일치. 명령은 `./gradlew :web-api:clean :web-api:bootJar -x test --offline`이고 rc=0이다.

| 항목 | 값 |
|---|---|
| jar sha256 | `eae7e1d2…`(빌드 시각 포함, 파일은 근거에 넣지 않음) |
| 항목 목록 | 898줄. 계산 코드 [jarm_u1.py](jar-d81c3f2f/jarm_u1.py)는 V171D4 `U1.sh`(sha256 d9f03855…) 97~143행 원문이다 |
| **JARM** | **`33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509`** |
| 운영 0e1d2a02(Git LF 기대 895, JARM b6587953) 대비 | 같음 860 · 변경 35 · 추가 3 · 삭제 0 ([jar-diff-vs-0e1d2a02.txt](jar-d81c3f2f/jar-diff-vs-0e1d2a02.txt)) |

- 변경/추가 항목은 모두 `BOOT-INF/classes` 클래스다. 묶음 14개가 0e1d2a02→d81c3f2f 비시험 원천 14파일([product-paths](jar-d81c3f2f/product-paths-0e1d2a02-d81c3f2f.txt))과 일대일로 대응한다.
- 리소스·SQL·라이브러리·모듈 jar 내부 항목의 변화는 0이다.
- ABC 기준(60df3437) jar는 따로 빌드하지 않았다(빌드 1회 원칙). 60df3437→d81c3f2f의 제품 차이는 원천 1파일이다. 이 jar에서 그 묶음은 `OrderSettlementService`·`$Outcome`·`$SellAmounts` 변경과 `$BuyIncrement` 추가다. 다만 A/B/C 변경 클래스와 같은 jar에 섞여 있어, ABC jar와 바이트로 대조한 것은 아니다.
- 프런트 차분은 없어 재빌드하지 않았다.

## 7. 남은 것

- §3-5 배포/복구안 초안(최종 제품 d81c3f2f + 관문 4판 + SQL v3, 새 열거값 구판 복구 정지 대역)은 이 제출 이후 작업이다.
- 운영 배포·관문 교체·구판 복구 실행·실제 생성은 승인 전이다.

체크섬 [SHA256SUMS](SHA256SUMS).
