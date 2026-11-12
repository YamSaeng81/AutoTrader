# 총괄 v1.157 §125 실행 근거 — throttle 반례 보완·A/B v4 실제 연결·원인 미확인 3건·phantom·전역 REAL 손실 실패 재현 (2026-11-12, 개발 세션 395차, 총괄 판정 전)

운영 서버·운영 DB·SSH·배포·DDL·세션 생성·재기동 없음. DB 는 모두 로컬 격리 컨테이너 `v152-pg`(127.0.0.1:15437)의 새 DB 또는 H2. 거래소·알림·웹소켓은 대역. 모든 후보는 **미승인**이며 84fb03d6 에 합치지 않았다. 84fb03d6·기존 jar·전체 회귀 폴더는 건드리지 않았고, 변경 없는 그 SHA 의 전체 회귀·PG·빌드는 반복하지 않았다.

| §125 | 가지 (기점) | 커밋 | 결과 | 근거 |
|---|---|---|---|---|
| 2 throttle | `candidate/rt-throttle-exact` (b5f8a8fd) | 시험 **9c0b16cf** → 보완 **49d485f0** | 반례 시험 21건 중 **6실패**(LIVE·DYNAMIC 모두 매도 0) → 21/21 + 단위 6/6, 관련 15클래스 **167/167** | `runs/thr-*`, `patches/throttle` |
| 3 A/B v4 | `candidate/ab-v4-precheck` (84fb03d6) | 실행 전 필수 환경 확인 **5f195542**(시험만) | 누락 시 서버 기동 전 실패 확인 → seed-other 1/1 → **본 실행 1/1**(다른 DB 같은 ID 8개·1µs·음성 4종 거절, 집계 rc 0) | `runs/ab4-*`, `patches/ab-v4-precheck` |
| 4 원인 미확인 3건 | `candidate/v157-test-barriers` (84fb03d6) | 대기 경계 ① **ee5beb18**, 포화 **0a807cbb**(시험만) | 2건 원인 입증·결정적 재현·보완(3/3, 4/4, R*+S* 233/233·건너뜀 9). 정리 시험 1건은 제품 경합까지만 입증 — **시험 미수정** | `runs/diag-*`·`draingap-fix`·`saturation-fix`·`tb-RS`, `diag-src/` |
| 6 전역 REAL 손실 | `candidate/real-loss-global` (84fb03d6) | 실패 재현 **92aa8712**(시험만) | 실제 PG 3건 중 1실패(DYNAMIC REAL 손실 승인) — **후보 수정은 아직 없음** | `runs/realloss-before-pg`, `patches/real-loss` |
| 7 phantom | `candidate/phantom-keep-realized` (84fb03d6) | 시험 **5ddc5cbe** → 누적 **0c980619** → 같은 트랜잭션 입금 **76ea3050** | 실제 PG 4건 중 3실패 → 3/4 → **4/4**, 관련 PG 7/7·7/7, H2 30/30 | `runs/phantom-*`, `patches/phantom` |
| 5 DYNAMIC 시계 · 8 D4 | — | — | **미착수**(아래 남은 일) | — |

## 1. throttle 반례 (§125-2)

**원인.** b5f8a8fd 는 백분율 손절선을 `평균 × (1 − %) × 1.000001` 근사값으로 기억했다. 실제 판정은 손익률을 6자리 HALF_UP 으로 반올림한 뒤 `≤ −%` 로 본다. 근사선과 실제 판정이 어긋나 285.00025(반올림 −4.9999%, 미발동)에서 조기 점검했다. 그때 직전 유효 시세가 선 아래로 옮겨져 실제 첫 발동 284 가 "첫 통과 아님"으로 간격에 막혔다.

**실패 시험(9c0b16cf, 시험만).** 기존 11건에 10건을 더했다. 모든 시세는 직전 점검 뒤 0.5초 경과로 두었다. LIVE 는 평상시·급등락 두 간격을 모두 0.5초로 두어 급락 경로가 결과를 가리지 않게 했다.

| 시험 | b5f8a8fd | 49d485f0 |
|---|---|---|
| P1 DYNAMIC·P2 LIVE 백분율 286→285.00025→284→283 | 중간 점검·284 미점검·**OPEN 매도 0** | 중간 미점검·284 점검·매도 1·283 중복 0·CLOSING |
| P3 DYNAMIC·P4 LIVE 절대 285 대조 | 매도 1 | 매도 1 |
| P5 DYNAMIC·P6 LIVE 같은 코인 두 보호선(백분율 5%·절대 280) →279 | 285.00025 두 세션 점검·284/279 미점검·**매도 0** | 285.00025 점검 없음·284 백분율 손절·279 절대 손절·**매도 2** |
| P7 DYNAMIC·P8 LIVE 0·낡은 280·285.00025·284 | 285.00025 점검·284 미점검·매도 0 | 앞 셋 점검 없음·284 매도 1 |
| P9 DYNAMIC·P10 LIVE 반올림 경계 285.0001(−5.0000%) | 점검·매도 1 | 점검·매도 1 |

LIVE 의 같은 공백도 실제 처리기 경로에서 재현됐다(P2·P6·P8).

**보완(49d485f0).** 새 클래스 `RealtimeProtectLines` 가 마지막 전체 점검 뒤 OPEN 으로 남은 포지션별 판정 입력(평균가·절대 손절가·손절%·익절가)을 기억한다. "직전 유효 시세에서 미발동, 이번 시세에서 발동"인 보호선이 하나라도 있으면 점검한다. 두 엔진의 실제 손절·익절 판정과 손익률 계산도 같은 정적 메서드(`stopHit`·`takeProfitHit`·`pnlPct`)를 부른다. 식과 절대가 우선 규칙은 그대로다.

바꾸지 않은 것: 간격 5초/1초, 실행기, 매 틱 DB 조회 없음, 낡은·비가격 시세 선제 거절, LIVE 매도 실패 시 보유선 유지, DYNAMIC 매도 뒤 제외, 전역 손실 정책. 여유값 숫자는 없다.

**한계.** 마지막 전체 점검 뒤 새로 열린 포지션과 바뀐 평균가·손절가·익절가는 다음 정기 점검(최대 간격 1회)부터 반영된다. DYNAMIC 매도 호출이 실패해 OPEN 이 남는 경우는 b5f8a8fd 와 같다(그 포지션은 다음 정기 점검부터 다시 본다). F 전달(5f570e87) 위 통합 실행은 하지 않았다 — 기대값을 고치지 않고 통합 단계에서 F 시험과 함께 돌린다.

**관련 시험.** b5f8a8fd 당시 관련 14클래스와 새 단위 시험을 합쳐 167/167 이다(종전 151 + 새 통합 10 + 단위 6). `DynamicPaperLiveRuleParityTest` 의 처리기 2600자 창 검사도 통과했다.

## 2. A/B v4 실제 연결 (§125-3)

- 도구: `docs/evidence/ab-create-v4-20261111/chain_driver_v4.py` (sha256 `31709f5c…2080`), 컨테이너 `v152-pg`. 코드 SHA = 실행 가지 HEAD `5f195542`(84fb03d6 + 시험 확인만).
- **실행 전 확인(5f195542).** 다음을 서버 기동 전(`@BeforeAll`)에 확인하고, 빠지면 이유를 적고 실패한다.
  - 구동기 파일·이름
  - 40자리 SHA
  - `AB_CHAIN_ROLE`(main 은 `AB_CHAIN_OTHER_DB` 필수, seed-other 는 받지 않음)
  - 컨테이너 공개 포트 = URL 포트, 구동기 psql 이 시험 DB 를 여는지, 다른 DB 가 같은 컨테이너에 있는지

  음성 실행 직전에는 다른 DB 에 같은 ID 8개가 실제로 있는지도 확인한다. v1.156 의 3회 NPE 실패를 바꾸는 보완이다. 제품·구동기·기대값은 바꾸지 않았다.
- 실행(16:36~16:37, 생성 창 C+6~C+11 대기 포함). 결과는 다음 표와 같다.

| 실행 | DB | 결과 |
|---|---|---|
| `ab4-preflight-missing-other` | `p157_ab4_preflight_missing_other_16996` | main·다른 DB 없음 → `initializationError` "AB_CHAIN_OTHER_DB 필요", 15초, 생성 POST 없음 |
| `ab4-seed-other` | `p157_ab4_seed_other_6014` | 1/1 — 다른 DB 를 만드는 앞 회차(정상 연결 전체) |
| **`ab4-main`** | `p157_ab4_main_13439`, 다른 DB = seed | **1/1**. 실제 HTTP 생성 8 → 첫 평가 지문 8(각 1개) → psql 확인 → v4 완성 → ab_arms → psql 추출 → 집계 rc 0. 다른 DB(같은 ID 8개 확인): **8팔 모두 started_at 불일치로 거절**(종료 3·완성본 없음). 1µs: **P3B 한 팔만 거절**. 지문 없음·복수·T0 전 거래·저장 비율: 종료 3. 세션 행 9 = 마이그레이션 기본 행 1 + 생성 8(COMPOSITE 8 단언 통과, 추가 POST 0) |

구판 AbChain(v1.150 구동기)은 다시 실행하지 않았다. 그 구동기는 없어진 컨테이너 `v150-pg` 를 고정으로 쓴다. 재실행하려면 같은 이름의 로컬 컨테이너와 `AB_CHAIN_DRIVER`·`AB_CHAIN_CODE_SHA` 가 필요하다. 이번 결과는 운영 세션 생성 허가가 아니다.

## 3. 원인 미확인 3건 (§125-4)

| 시험 | 입증한 원인 | 결정적 재현(진단, 커밋 안 함) | 조치 |
|---|---|---|---|
| `OrderExecutorDrainPredicateGapTest` ① (int155-full: queue=0 active=1 tasks=1 completed=0) | `prestartAllCoreThreads` 는 작업자를 만들기만 한다. 아직 출발하지 않은 작업자는 생성 상태(−1)라 active 로 세어진다(같은 시험 ②의 성질). 부하로 한 작업자가 늦게 출발했다 | `DrainGapLateWorkerDiagTest`: 두 번째 작업자 출발만 붙잡기 → **같은 수치**. 두 작업자 take 진입 확인 뒤 → drained=true | ee5beb18: take 진입 래치(2) 대기. 3/3 |
| `SharedMarketDataExecutorSaturationTest` "대기열 가득 expected 0 but 100" | 장벽이 코어 작업자를 미리 띄워 코어 차단 작업이 대기열을 거친다. 코어 작업자가 늦게 꺼내면 빈 작업 100개가 대기열을 넘쳐 비코어 작업자 2개가 처리한다. 차단 작업 3·4 는 대기열을 거쳐 실행된다 → 4 막힘·대기열 0·남은 용량 100 | `SaturationLateCoreDiagTest`(같은 설정 실행기, 코어 take 만 붙잡기): **remaining=100**, 코어 실행 확인 뒤 채우면 0. 같은 SHA 계측(`diag-RS-1`, R*+S* 242건 통과·재현 안 됨): 이 문맥 실행기는 시작 시 작업자 0개 → **"잔존 작업자" 가설 기각** | 0a807cbb: 코어 차단 작업 실행 래치 대기. 4/4(대기 경계 포함), R*+S* 233/233·건너뜀 9 |
| `SchedulerProfileCleanupTest` LIVE 60초 (diag11-Rhalf1-S) | 원문: 12:26:19.391 정리 제출(CLOSING, 커밋 뒤 비동기 매도) → **4ms 뒤 같은 스케줄러의 `reconcileClosingPositions` 가 "SELL 주문 없음 → OPEN 롤백"**. 60초 뒤 메모 "매도 진행 중", 포지션 OPEN, 주문번호 없음 | `CleanupReconcileRaceDiagTest`: 주문 실행기를 붙잡고 정리 → 청산 확인 → 해제 → **OPEN 포지션에 SUBMITTED 매도 1(거래소 호출 1)**, 다음 정리 "매도 진행 중"(중복 매도 0) | **시험 미수정.** 원문 순서와 같은 제품 경합(청산 확인 롤백에 커밋 뒤 비동기 제출 유예 없음)은 입증했다. 실패 회차에서 60초 동안 주문번호가 붙지 않은 지연은 그 패키지 INFO 로그가 없어 **미입증**이다. 이 경합은 제품 보고 사항으로 분리한다(아래) |

- 계측 실행 `diag-RS-1` 은 84fb03d6 + 포화 시험 출력 4줄 추가(`diag-src/…instrument.diff.txt`)다. 통과를 해결로 쓰지 않았다.
- 다른 옛 대기 20개의 분류(실제 완료에 기대는 단언 vs 정리만)는 **아직 하지 않았다**.

**제품 관찰(보고, 미수정).** LIVE `reconcileClosingPositions`(5초)는 CLOSING 포지션에 SELL 주문이 없으면 즉시 OPEN 으로 되돌린다. 정리·실시간 손절은 CLOSING 표시 뒤 커밋 후 비동기로 의도를 만든다. 그래서 그 사이에 확인이 돌면 OPEN 포지션에 매도가 나간다.
- 중복 매도는 의도 단계의 미반영 매도 검사가 막는다(진단 ⑤).
- 그 매도의 체결 반영은 CLOSING 경로를 타지 않는다. 정리 작업의 "체결 미반영" 분기나 phantom 이 이어받는 구조다.
- `cleanupOne` 주석이 이미 이 순서를 설명한다. 실시간 손절 경로의 같은 창은 이번에 실행하지 않았다.

## 4. phantom (§125-7)

- 경로: 첫 다리는 실제 폴러(`checkOrderStatus`) → `reconcileClosingPositions`. 남은 반은 거래소 잔고 대역 0 → `reconcilePhantomPositions` 3회. 가상 체결·주문·FILL·현금 보정은 없다.
- 수정 전(84fb03d6 + 시험).
  - 1회차(`phantom-before-pg`)는 ② 가 **내 시험 결함**(`invokeMethod` 제네릭 추론으로 Class 오버로드 선택)으로 ClassCastException 이었다. 원문을 보존하고 고쳤다.
  - 2회차(`phantom-before-pg-run2`, 5ddc5cbe 와 같은 내용)는 3실패다.
    - ① 최종 실현 −1,000.50 = 추정분만이다. 이 자료에서는 추정가가 첫 다리 체결가와 같아 두 값이 같다. 기대는 합 −2,001.00 이다.
    - ② 반복·재기동은 다시 더하지 않는다. 확정분은 소실된다.
    - ③ **phantom 확정 트랜잭션 롤백 시 포지션은 OPEN 으로 돌아오는데 세션 입금 +8,995.50 은 남는다.** `SessionBalanceUpdater.apply` 가 REQUIRES_NEW 라 독립 커밋되어, 재확정에서 두 번 들어간다.
    - ④ 부분 매도 없는 전량 phantom 대조는 통과한다.
- 0c980619: `setRealizedPnl(추정분)` → `앞선 확정분 + 추정분`. 로그에 앞선 확정분을 적는다. 결과 3/4(③만 남음).
- 76ea3050: phantom 입금 `apply` → `applyWithin`(정산과 같은 규칙). **4/4.** 롤백 뒤 잔액 차 0, 재확정 입금 1회(8,995.50).
- 유지한 것:
  - 추정가 규칙·수수료 누적·수량·총자산 규칙
  - 추정 표시
  - 주문 원천 확정 손실 합(전역 판정 원천)은 phantom 전후 같다(−1,000.50 → −1,000.50) — 추정분은 넣지 않는다
- 확정/추정 구분은 별도 열 없이 이렇게 남는다: 확정분은 주문 행(`sell_applied_at`)에 있고, 포지션 `realized_pnl` 은 확정+추정 합계다. 포지션 단위로 추정분만 따로 읽으려면 열 추가(DDL)가 필요하다 — 자료 구조안으로만 남긴다.
- 관련: PartialSellRealizedPnlPgTest 7/7, LiveLossWindowPgTest 7/7, H2 7클래스 30/30.

## 5. 전역 REAL 확정 손실 실패 재현 (§125-6, 후보 수정 없음)

현재 구조는 다음과 같다.
- 분자: 비세션 FILL + LIVE 세션 매도 확정(주문 원천). 주문 원천 SQL 은 `session_kind='LIVE'` 만 센다(주석 "DYNAMIC REAL·DYN_PAPER 는 범위 밖·판단 대기").
- 분모: RUNNING LIVE 초기자본 → 설정 → 1,000만.
- 관문: DYNAMIC 진입은 세션 서킷 브레이커만 부른다. 주문 엔진의 `checkRisk` 는 비세션 BUY(positionId null)만 탄다. 그래서 DYNAMIC REAL 세션 BUY 는 전역 판정을 지나지 않는다.

| 사례(실제 PG, 일일 1.5%) | 현재 | 초안(LIVE+DYNAMIC REAL+비세션 확정 손실 / RUNNING LIVE+DYNAMIC REAL 초기자본) |
|---|---|---|
| ① LIVE 10만·DYNAMIC REAL 10만, DYNAMIC REAL 확정 손실 −4,002 | 분자 0 → **승인** (시험 실패) | 4,002/200,000 = 2.0% → 거절 |
| ② 같은 세션, LIVE 손실 −2,001 만 | 2.00% → 거절 | 2,001/200,000 = 1.00% → **승인 — 초안이 LIVE 판정을 완화** |
| ③ DYN_PAPER 손실 −5,000 | 제외 → 승인 | 제외 → 승인 |
| REAL 없음·비세션만 | 분모 = 설정/폴백 | 같음(관측 미실행) |
| DYNAMIC REAL 만 RUNNING | 분모 = 설정/폴백(LIVE 없음) | DYNAMIC REAL 초기자본 |

②는 숨기지 않는다. 분모를 넓히면 LIVE 단독 손실의 비율이 내려간다. 후보는 분모 정의 선택(합산 또는 엔진별 판정의 최댓값 등)을 먼저 결정해야 한다. 관문 연결(DYNAMIC 즉시 진입·회차 승인)도 아직 만들지 않았다. 반복 정산·늦은 정정·롤백·기간 경계는 주문 원천 SQL 을 그대로 넓히면 LIVE 와 같은 성질을 갖는다(LiveLossWindowPgTest ②④⑦). 이번에 DYNAMIC 으로는 실행하지 않았다. **새 전역 정책 운영 적용 승인이 아니다.**

## 6. 남은 일 (이번 회차 미완)

- **§125-5 DYNAMIC 판단 시계**: 미착수. 확인된 접점은 다음과 같다.
  - 회차 접수 `entryPriorityRounds.submit(..., Instant.now())`(DynamicTradingService:1255 부근)
  - 코인 최신성·BTC 관문·닫힌 봉 판정의 실제 시각(LIVE 는 `judgeNow()` 세 접점만 EntryClock)
- **§125-6 후보 수정**: 분모 선택·관문 연결·DYNAMIC 반복/정정/롤백/경계 실행.
- **§125-4 옛 대기 20개 분류**와 정리 시험 지연 원인(주문 실행기 INFO 로그를 켠 재현 필요).
- **§125-8 포함표·통합 SHA·D4**: 미착수. 통합 후보 묶음은 84fb03d6 + test-barriers(시험만) + ab-v4-precheck(시험만) 이다. throttle(49d485f0, b5f8a8fd 기점)·phantom(76ea3050) 은 총괄 판정 뒤 포함 여부를 정한다.

## 7. 실패·주의 이력

- phantom 1회차 ②는 내 시험 코드 결함(위)이다. 고친 뒤 2회차를 수정 전 근거로 쓴다. 1회차 원문은 `runs/phantom-before-pg/` 에 보존했다.
- 진단 실행(`diag-race-gap`·`diag-saturation-latecore`·`diag-RS-1`)은 84fb03d6 위 **커밋하지 않은 파일**로 돌았다. 소스는 `diag-src/` 에, 추적 파일 차이는 각 `worktree.diff` 에 있다.
- **이번 실행에도 Telegram 외부 전송 시도가 있었다.** 알림 대역이 없는 H2 시험 문맥(내가 넓힌 `RealtimeThrottleCrossingTest` 포함)에서 `TelegramNotificationService` 가 실제 HTTPS 요청을 보냈다. 토큰 환경 값이 없어 빈 토큰 주소로 나갔고 모두 HTTP 404 거절이었다. 전달 근거는 없다. 요청에는 application.yml 기본 chat-id 와 시험 알림 문구가 담겼다.
  - 집계(`[Telegram] 메시지 전송` 줄): thr-before/after-fix 각 1, thr-related 15, phantom-rel-h2 11, diag-RS-1 36, tb-RS 37.
  - 기존 전체 회귀에도 있던 성질이다(이전 원문 `diag11-Rhalf1-S` 등).
  - 시험 프로필에서 알림을 끄는 보완(예: 시험 설정 `telegram.enabled=false` 또는 공통 대역)은 하지 않았다. 범위 결정이 필요하다. 외부 호출 0 이라고 쓰지 않는다.
- 다른 개발용 Gradle 과 동시 실행하지 않았다(A/B 묶음 뒤 진단 순차 실행).
