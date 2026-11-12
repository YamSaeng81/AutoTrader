# v1.158 §126 실행 근거 — 시험 알림 차단·바이트 보존 복구·정리 경합·손실 세 보호식·DYNAMIC 판단 시계·통합판·경계 탐침 보완·jar 대조·D4 초안 (397차, 총괄 판정 전)

2026-11-12 · 개발 제출 · 총괄 v1.158 [review-60777286](../review-60777286/README.md) 지시 실행 · [review-1eed27d1-boundary](../review-1eed27d1-boundary/README.md) 후속(탐침 시험만 보완)

**모든 후보와 통합판은 미승인이다.** 운영 서버 빌드·설치·재기동·DDL·세션 생성은 하지 않았다. 84fb03d6 은 그대로이며, 84fb03d6 의 이전 전체 통과를 새 통합판의 결과로 쓰지 않는다.
로컬 PostgreSQL 은 기존 컨테이너 `v152-pg`(127.0.0.1:15437)에서 실행마다 새 DB 를 만들어 썼다. 외부로 나간 요청은 없다(아래 §1 확인 포함).

## 0. 정정 메모 (이전 제출)

- **v157 README 첫 머리의 "알림은 대역" 표현은 틀렸다.** 같은 문서 §7 에 적은 대로, 알림 대역이 없는 H2 시험 문맥에서 실제 Telegram HTTPS 요청(빈 토큰, 404 거절)이 나갔다. 원문과 집계는 지우지 않고 그대로 둔다. 이번 §1 이 그 원인을 막는다.
- **R*+S* 표기 정정:** "233/233" 이 아니라 **전체 242 중 성공 233·건너뜀 9·실패 0** 이다.
- **v157 근거의 커밋 블롭 불일치:** 60777286 의 블롭은 302 중 102 만 원 SHA256SUMS 와 같았다(200 은 CRLF→LF 변환). 원본·원목록·60777286 은 고치지 않았다. 아래 §2 의 새 커밋으로 복구했다.
- 시험 분류는 기술 원인과 제품 발생 여부를 나눈다: 대기 경계①·포화는 **시험 준비 원인**(제품 발생 아님), 정리 시험은 **제품 경합**(이번 §3 후보).

## 1. 시험 외부 알림 차단 — `candidate/test-telegram-off` d32244e5 (84fb03d6 기점, 시험 설정만)

- 원인: `application-test.yml` 에 telegram 값이 없어 운영 기본값(`enabled=true`, 운영 기본 chat-id)이 그대로 쓰였다. 환경 변수 `TELEGRAM_*` 는 프로필 설정보다 우선한다.
- 보완:
  - 시험 프로필 `telegram.enabled=false`, 빈 토큰, 비운영 chat-id(`test-isolated-no-chat`).
  - Gradle `test` 작업이 시험 JVM 에 `TELEGRAM_*` 환경을 넘기지 않는다.
  - 운영 설정(application.yml)·알림 사유/재시도/큐 계약은 바꾸지 않았다.
- 새 시험 `TelegramTestIsolationTest`(test / test+scheduler 프로필): 유효 설정을 읽고, 목적지를 127.0.0.1 계수기로 돌린 뒤 실제 알림 경로 3종을 부른다.
  - **수정 전 2/2 실패**: `enabled=true`·운영 chat-id·로컬 계수 3회(`runs/tg-before`).
  - **수정 후 2/2**: `enabled=false`·계수 0. `TELEGRAM_ENABLED=true TELEGRAM_CHAT_ID=999` 를 걸고 실행해도 시험 JVM 환경에 `TELEGRAM_*` 0개(`runs/tg-after-envset`).
- 대표 시험(Telegram* 전체·Candle*·S* 대표 10클래스) **155/155**(`runs/tg-related`). 전송 줄은 명시적 전송 계약 3클래스(TelegramMarkdownFallback·TelegramRiskLimitRetryHttp·CandleRefreshReportRetry, 모두 `enabled=true`+127.0.0.1)에만 있고, `api.telegram.org` 문자열은 0건이다.
- 알림 비활성화의 성공 반환을 실제 전송 성공 근거로 쓰는 단언은 새로 만들지 않았다. 생산자 호출·롤백/큐 거절·재시도·서식 폴백 시험은 그대로 통과했다.

## 2. 근거 바이트 보존 복구 — main 43506729

1. v157 원본 302개와 원 SHA256SUMS 를 `D:/tmp/v153-runs/v158-preserve/` 에 바이트 그대로 복사했다(302/302 일치 확인).
2. `docs/evidence/v157-20261112/.gitattributes` 에 `* -text` 를 두고 `git add --renormalize` 로 CRLF 원본 200개를 다시 넣었다.
3. **새 커밋 43506729 의 블롭 302/302 가 원 SHA256SUMS 와 일치.** 60777286 은 같은 대조에서 102/302(그대로 보존, amend/rebase 없음).
4. 총괄 v1.158 검토 근거 `review-60777286/` 에도 같은 `.gitattributes` 를 두고 커밋했다(로컬 40/40 일치). 이번 근거 폴더도 같다.

## 3. 정리 경합 보완 — `candidate/closing-race-guard` 78324572 (84fb03d6 기점)

**계약**: CLOSING 전환과 식별 가능한 SELL 의도(`INTENT_READY` 주문 행)를 **한 트랜잭션으로** 확정한다. 그러면 "CLOSING 인데 주문 행이 없다" 는 "의도가 만들어지지 않았다"(중복·미반영 매도·형식 거절)는 뜻이 되어, 청산 확인의 기존 롤백이 근거 있는 회복이 된다. 시간 유예·자동 재매도·영구 CLOSING 은 쓰지 않았다.

- `OrderExecutionEngine.markClosingWithSellIntent`:
  - 전환은 기존 관문 `PositionStateTransition.markClosingIfOpen` 을 그대로 거친다(REQUIRED 로 같은 트랜잭션).
  - 의도 저장은 기존 `prepareIntent`(포지션 잠금·미반영 매도 차단·중복 검사 그대로).
  - 제출은 커밋 뒤(호출자 트랜잭션이 있으면 그 커밋 뒤). 실행기가 받지 못하면 의도를 철회한다(기존 `WITHDRAWN_DISPATCH_LOST`, 외부 호출 0).
- 적용 경로 6곳: LIVE `executeSessionSell`(손절·익절·시간·신호·실시간)·정지 청산·정리 작업, DYNAMIC REAL `executeSell`·정지 청산·정리 작업. PAPER 는 종전 그대로다 — CLOSING 전환+체결 시뮬레이션을 `executePaperSellIfOpen` 으로 묶었을 뿐 동작은 같다(e1e76d6c; 통합 관련 실행에서 `DynamicPaperLiveRuleParityTest` 가 PAPER 분기와 `executePaperSell` 사이 거리가 벌어진 것을 잡아 고쳤다, 시험은 그대로).
- 정리 작업의 "직전 제출이 주문으로 나타났나" 대조가 이번 시도의 의도를 알아보도록, 시도 시각을 의도 저장 **전에** 잡아 기록한다(첫 관련 실행에서 이 대조 때문에 재매도가 유예에 걸린 것을 확인하고 고쳤다).
- 실제 PG `ClosingIntentAtomicityPgTest`(주문 실행기 작업자를 래치로 모두 붙잡아 커밋 뒤 제출을 대기열에 둔 상태에서 청산 확인을 부른다):

| 사례 | 수정 전(84fb03d6 제품 코드) | 수정 후 |
|---|---|---|
| ① LIVE 정리 작업 창 | CLOSING→**OPEN**, 주문 0 | CLOSING·의도 1 → 해제 뒤 호출 1 → 폴러 FILLED → CLOSED, 가용 7,000→9,742.63, 재확인 입금·매도 중복 0 |
| ② LIVE 실시간 손절 창 | OPEN, 주문 0 | 같은 흐름 CLOSED·입금 1회 |
| ③ DYNAMIC REAL 매도 창 | OPEN, 주문 0 | 같은 흐름 CLOSED·입금 1회 |
| ④ 실행기 거절(최대 작업자·대기열 가득) | 의도 행 없음 | 의도 FAILED(`WITHDRAWN_DISPATCH_LOST`)·외부 호출 0 → 청산 확인이 그 근거로 OPEN |
| ⑤ 의도 확정 뒤 응답 불명 | OPEN | CLOSING 유지·UNKNOWN·재매도 0 |

  - 수정 전 최종 시험으로 **5/5 실패**(`runs/cr-before3-finaltest`), 수정 후 **5/5**(`runs/cr-pg-final`).
  - 1회차(`cr-before`)는 시험 준비 결함으로 ② 가 창을 만들지 못했다(앞 시험이 남긴 비코어 작업자가 제출을 가져감, pool=4 active=2). 살아 있는 작업자 전부를 붙잡도록 시험만 고쳤다(`cr-before2` 5/5 실패). ④ 의 첫 수정 후 실패(`cr-after`)는 시험 자신의 장벽 작업이 가득 찬 대기열에 거절된 것이라, 붙잡은 작업이 모두 끝난 뒤 장벽을 넣도록 시험만 고쳤다. 원문은 모두 보존했다.
- 롤백 계약 `ClosingIntentRollbackPgTest` 2/2: ⑥ 호출자 트랜잭션 롤백 → CLOSING·의도 함께 사라짐·제출 0, ⑦ 이미 CLOSING → 전환 0·의도 0.
- 관련 H2(재기동·정지/비상정지·취소/늦은 체결·정산·중복 매도·실시간 등) **234 중 성공 233·건너뜀 1·실패 0**(`runs/cr-related-final2`).
  - 첫 관련 실행은 7실패였다(`runs/cr-related`): 4건은 시험이 `PositionStateTransition` 스파이에 경합을 심는데 새 경로가 그 관문을 우회해서였고(관문을 다시 거치게 고침), 3건은 위 시도 시각 대조였다. 시험 기대는 바꾸지 않았다.
  - 그 실행과 `cr-related-final`(205/205)은 패턴 목록 일부가 셸 글롭으로 파일 이름이 되어 대상이 빠졌다. 글롭을 끈 `cr-related-final2` 가 판정 실행이다.
- **경계 탐침 `SubmitOrderBoundaryProbeTest` — 1eed27d1 에서 실패, 총괄 승인([review-1eed27d1-boundary](../review-1eed27d1-boundary/README.md)) 뒤 시험만 보완(4a4a04a3, 정리 후보 99830dd5)**
  - 경위: 1eed27d1 전체 회귀에서 처음 드러났다(관련 실행 패턴에 이 클래스가 없었다 — 범위 누락). 단독으로 84fb03d6+d32244e5 1/1 통과(`runs/probe-before`), 정리 후보 e1e76d6c 1/1 실패(`runs/probe-closing`). 옛 시험은 "최초 거래 기록 저장" 하나를 제출 경계로 보고 정지 트랜잭션 비합류를 단언했는데, 결정 3 으로 그 최초 기록이 `null→INTENT_READY`(정지 트랜잭션 안)가 되어 충돌했다.
  - 진단 사본(커밋하지 않음, `runs/probe-diag-int/v158-probe-diag.txt`): `INTENT_READY` 만 `Test worker`·정지 트랜잭션, claim·결과 저장은 `order-exec-1`·각자 트랜잭션, 주문 행 1. 🔴 정정: 앞선 보고의 "거래소 호출도 자기 트랜잭션" 은 틀린 표현이다 — `callExchangeAndRecord()` 는 조회·결과 저장만 `submitTx` 로 감싸고 **거래소 호출은 DB 트랜잭션 밖**이다(아래 새 탐침이 실측).
  - 보완(제품 코드 변경 없음): 같은 주문 ID 와 `oldState→newState` 로 관측을 연결하고, 트랜잭션 동일/분리는 트랜잭션에 묶인 실제 자원(엔티티 관리자 보유자)의 객체 동일성으로 판정한다. 네 경계를 각각 단언한다 — ① CLOSING·`null→INTENT_READY` 호출자 스레드·같은 자원, 정지 트랜잭션 커밋 직전 `createOrder` 0 ② claim 은 호출자 커밋 뒤·다른 작업 스레드·활성 트랜잭션·호출자와 다른 자원 ③ `createOrder` 는 작업 스레드·실제 트랜잭션 비활성·묶인 자원 없음·claim 뒤·1회 ④ `SUBMITTING→SUBMITTED` 는 호출자·claim 과 다른 활성 트랜잭션·반영 1회·최종 주문 행 1개(SUBMITTED). 완료 대기는 실행기 큐가 아니라 결과 저장 트랜잭션의 afterCommit 이며 **15초 안에 오지 않으면 실패**한다. 통과해도 관측값을 `V158PROBE` 줄로 XML system-out 에 남긴다.
  - 결과: 1eed27d1+새 탐침 **1/1**(`runs/probe-new`, 관측 — 의도·CLOSING 자원 2eff2960 `Test worker` → 커밋 #3 → claim #4 `order-exec-1` 자원 7f735d44 → createOrder #5 `txActive=false resource=null` → 결과 #6 자원 6038c02e, 주문 1, createOrder 1, 커밋 직전 0). **판별력 대조**: 정리 경합 수정 전(d32244e5)+새 탐침 **1/1 실패**(`runs/probe-new-before`, 의도가 `order-exec-1` 에서 생기고 claim #3 이 호출자 커밋 #6 보다 앞섬 — 경합 창 그 자체). 대조 작업 사본은 원복했다.
- **미입증 유지**: 과거 실패 회차의 60초 지연 전체 원인은 확인하지 않았다. H2 결과로 PG 잠금 동작을 일반화하지 않는다. 재기동 중 의도만 남은 경우는 기존 복구(소유권 없는 `INTENT_READY` 철회 → 정리 재제출, `CancelConfirmAndLateFillCleanupTest` D3)가 그대로 통과한 것으로만 확인했다.

## 4. 전역 실자금 손실 세 보호식 — `candidate/real-loss-guard-3way` 12b94ce3·d825f28a (92aa8712 실패 재현 위)

결정 2 그대로다. 새 숫자·완화 설정 없음. 각 일/주/월 창에서 아래 세 식을 **기존 `RiskEngine` 비교(손실률 > 한도면 거절, 동률 승인)·기존 한도**로 보고 하나라도 넘으면 신규 실자금 매수를 거절한다.

| 보호식 | 손실 원천 | 자본 기준 |
|---|---|---|
| 기존 | 비세션 FILL + 확정 LIVE 세션 매도(종전 그대로, 사유 문구도 종전 그대로) | 종전 LIVE RUNNING 초기자본·설정·1,000만 폴백 |
| DYNAMIC REAL | 확정 DYNAMIC REAL 세션 매도(새 조회 `sumDynamicRealSessionRealizedLossSince`, LIVE 조회와 같은 식) | RUNNING DYNAMIC REAL 초기자본 |
| 전체 REAL | 위 두 원천의 합 | RUNNING LIVE + DYNAMIC REAL 초기자본, 둘 다 0 이면 설정/폴백 |

- DYNAMIC REAL 손실이 있는데 RUNNING DYNAMIC REAL 자본이 없으면 **판정 불가로 거절**. 손실 0 이면 자본 없는 엔진 때문에 막지 않는다.
- 조회 실패는 예외로 그 진입을 중단한다(fail-closed). 잡아서 거절을 돌려주지 않는 이유: 참여 중인 트랜잭션이 이미 롤백 전용이 되어 호출자 커밋에서 다시 실패한다.
- 연결: LIVE 즉시·회차 승인·비세션 BUY 는 `checkRisk()` 가 세 식을 본다. DYNAMIC REAL 즉시·회차 승인은 공통 `executeBuy` 첫머리에서 `checkRealLossGuard()`(세 식만, 포지션 수·자본 사용률·낙폭은 넣지 않음 — DYNAMIC 에 새 비손실 한도를 얹지 않기 위해). PAPER 는 막지 않는다(모의 계약 그대로).
- 실제 PG `RealLossThreeFormulaPgTest`(손실은 실제 폴러 체결→엔진 확정으로만 만듦): **수정 전 6/8 실패 → 8/8**(`runs/rl3-before`, `runs/rl3-after3`).

| 사례 | 결과 |
|---|---|
| ① DYNAMIC REAL −4,002 (LIVE 10만·DYN 10만) | DYNAMIC 4.00% > 1.50% 거절 — checkRisk·DYNAMIC 진입 모두 |
| ② LIVE −2,001 (LIVE 10만·DYN 10만) | **기존 2.00% 거절 유지**(전체 1.00% 로 완화되지 않음), DYNAMIC 진입도 거절 |
| ③ 판정 불가 | 손실 0·DYN 자본 0 → 승인 / DYN 손실 있고 DYN 정지 → 판정 불가 거절 |
| ④ 동률 | 손실률 = 한도 2.00% 승인, 한도 1.99% 거절(기존·DYNAMIC 각각) |
| ⑤ 창 경계 | 2일 전 DYNAMIC 손실은 일일 밖·주간 안(주간 거절) |
| ⑥ 반복·정정·롤백 | 롤백 0·반영 표시 null → 재확정 −2,001 한 번 → 재확정·재폴링 −2,001 → 정정 수수료 9→10 −2,002 |
| ⑦ DYN_PAPER −5,000 | 합산 0·승인 |
| ⑧ 자본 추가·정지 표 | 아래 |

| 상황 | 판정 |
|---|---|
| LIVE 손실 −2,001, LIVE 10만만 | 거절(기존 2.00%) |
| + DYNAMIC REAL 10만 RUNNING 추가 | **거절 유지**(기존 식) |
| DYNAMIC 정지 | 거절 유지 |
| DYNAMIC 손실 −2,001, DYN 10만·LIVE 10만 | 거절(DYNAMIC 2.00%) |
| + DYNAMIC REAL 10만 추가(DYN 20만) | 승인(DYNAMIC 1.00%·전체 0.67%) — 자체 자본 추가는 자체 식을 낮춘다 |
| DYNAMIC 전부 정지(손실은 기간 안에 남음) | **판정 불가 거절** — 정지 후 자체 분모가 없어 오히려 더 엄격 |

- 기존 PG: `LiveLossWindowPgTest` 7/7, `RealLossGlobalPgTest` 3/3(92aa8712 의 ① 은 이제 통과).
  - 첫 실행의 `LiveLossWindowPgTest` 1실패는 내가 기존 거절 사유 앞에 "[기존 보호식]" 을 붙인 탓이었다. 시험을 고치지 않고 기존 사유 문구를 종전대로 되돌렸다(`runs/rl-pg-lw` → `rl-pg-lw2`).
  - 첫 PG 실행(`rl-pg-*` 대문자 이름)은 내 실행 도구가 대문자 DB 이름을 만들어 접속 실패했다(도구 결함, 시험 결과 아님).
- **H2 시험 14클래스 변경(공개)**: 기존 손실 원천(trade_log FILL)은 PostgreSQL 전용 구문(`->>`)이라 H2 에서 실행할 수 없다. DYNAMIC REAL 매수를 H2 로 돌리는 시험들이 처음으로 그 조회에 닿아 15실패했다(`runs/rl-related`). LIVE 쪽 H2 시험이 `checkRisk()` 를 스파이에서 승인으로 두는 기존 관례를 `checkRealLossGuard()` 에만 같게 적용했다: `BtcGateSupplyEndToEnd`·`BtcGateUnknownEntry`·`EntryPriorityEndToEnd`·`N15FinalApprovalBoundary`(기존 checkRisk 대역 옆 한 줄), `DynamicBuyCompletesConsistently`·`DynamicBuyExternalSubmissionBoundary`·`DynamicBuyOuterWriteOrdering`·`DynamicPaperBuySameTickWatchlist`(스파이+대역), `PoolLossLimit`(실제 빈 → 스파이), 통합 관련 실행에서 같은 원인으로 드러난 `DynamicBlackSwanCooldownCrossKind`·`DynamicPaperTrading`·`DynamicPoolReservation`(스파이+대역)·`N15EntryExpiry`(기존 대역 옆 한 줄)(c95b74e6), 통합판 전체 회귀에서 드러난 `PositionLimit`(스파이+대역, 1eed27d1·후보 3f0dc138, 단독 13/13 `runs/poslimit-stub`). 각 시험의 다른 단언은 바꾸지 않았다. 총괄은 `PositionLimit` 대역을 **손실 합산 관문에 한정해** 허용했다(review-1eed27d1-boundary §1) — 포지션 한도·신선도·정지 단언과 제품 코드는 그대로이며, 이 H2 결과는 손실 보호식의 근거가 아니다(근거는 PG 시험, §7).
- **`DynamicPaperLiveRuleParityTest` 허용 목록에 `checkRealLossGuard` 추가(사람 판단 예외, 공개)**: 이 시험은 PAPER/REAL 이 매수 여부를 다르게 가르는 새 분기를 사람이 판단하게 막는다. 결정 2 가 "PAPER 모의 한도는 기존 계약을 보존하고 REAL 자료로 오염시키지 않는다" 로 PAPER 제외를 명시했으므로 예외로 넣었다. **한계: PAPER 는 이 관문의 차단을 예측하지 못한다.** 총괄이 다르게 판단하면 이 한 줄과 `executeBuy` 의 `!session.isPaper()` 조건을 함께 바꾼다. 관문 자체는 실제 PG 시험으로만 판정한다. 관련 H2 **157/157**(`runs/rl-related4`).
- 운영 정책 적용 승인이 아니다.

## 5. DYNAMIC 판단 시계 — `candidate/dynamic-judge-clock` c4b09e5d (84fb03d6 기점, §125-5)

- DYNAMIC 에 `EntryClock`(LIVE B01·회차와 같은 시계, 기본 시스템 UTC)을 연결했다. 회차 C/D/E 계약은 바꾸지 않았다.

| 판단 지점 | LIVE (84fb03d6) | PAPER(선택 모드) | DYNAMIC 수정 전 | DYNAMIC 수정 후 |
|---|---|---|---|---|
| 닫힌 봉 판정 | 판단 시계 | 판단 시계(v1.152 D) | 시스템 시각 | 판단 시계 |
| 캔들 최신성 | 판단 시계 | 판단 시계 | 시스템 시각 | 판단 시계 |
| 캔들 조회 상한 | 판단 시계(DB 조회 to) | — | 상한 없음(캐시 조회 내부 시스템 시각) | 판단 시각 뒤 봉 제외(시스템 시계에서는 미래 봉이 없어 같다) |
| BTC 관문 | 가드 내부 시스템 시각 | — | 가드 내부 시스템 시각 | 판단 시계(새 5인자 진입점, 4인자 불변) |
| 회차 접수 시각 | 판단 시계(v1.155 §3) | 판단 시계 | 시스템 시각 | 판단 시계 |
| 전략 SELL 최소 보유 | 판단 시계(B01 ③) | — | 시스템 시각 | 판단 시계 |
| 시간 초과 청산·블랙스완 쿨다운 | 시스템 시각 | — | 시스템 시각 | 시스템 시각(범위 밖, 그대로) |

  - **남은 차이(판정 요청)**: LIVE 의 BTC 관문은 여전히 가드 내부 시스템 시각이다. DYNAMIC 만 판단 시계로 바뀌어, 시험 시계에서는 두 엔진의 BTC 판정 시각이 다를 수 있다(운영 기본 시계에서는 같다). LIVE 쪽 연결은 이번 범위에 넣지 않았다.
- `DynamicJudgeClockTest`(H2, 시험 시계 2020-01-01): **수정 전 4/5 실패 → 5/5**(`runs/dc-before`, `runs/dc-after`). 기본 시계 사례는 수정 전후 같다. 매 시험 뒤 시계를 시스템 UTC 로 되돌린다.
- 관련(Dynamic*·EntryPriority*·N15*·BtcGate*·CandleFreshness*·Shared*·Sma20*·B01*) **279 중 성공 275·건너뜀 4·실패 0**(`runs/dc-related`).
- 회차 접수 시각과 최소 보유 시간은 별도 시험 없이 코드 대조와 관련 시험 통과로만 확인했다.

## 6. 옛 대기 20개 분류 (§125-4, 코드 변경 없음)

`awaitDrained/drain`(실행기 `taskCount == completedTaskCount` 와 대기열·활성 0) 을 쓰는 20개 클래스를 호출 위치로 나눴다(`wait20-classify.tsv`, 정적 분류 — 호출 뒤 8줄 안의 단언 여부 기준이라 근사다).

- **정리만(@AfterEach/@BeforeEach)**: N15PgLockWaitExpiry(2/2 호출 전부).
- **본문에서 대기 뒤 곧바로 단언(실제 완료에 기댐, 우선 조사)**: SubmitResponseUnknown 4, IntentPrecommitAndOnceOnly 2, LateOrderSubmissionAfterStop 1, ClaimAndUnknownTracking 2, ExchangeRecoveryRestart 3, LiveStopUnderConcurrentWrite 2, N16 2, O1O3LogContract 2(84fb03d6 에서 거래소 호출 대기로 이미 보완), DailySma20LiveOrderPath 1.
- **상태 기반 대기와 함께 씀(상대적으로 안전)**: CancelConfirmAndLateFillCleanup 11·StopVersusInFlightExitRace 6 — 단언 전에 `awaitSells/awaitSubmittedSells`(주문 상태·건수 조건)로 다시 기다린다.
- **본문 호출 뒤 단언이 바로 없음(관측·다음 단계 준비)**: LimitedTestExchangeRecovery 4, N17 2, N19 2, SlWatchdogFreshness 2, StaleRealtimeExit 2, SubmitOrderBoundaryProbe 1, LiveEmergencyStopUnderConcurrentWrite 1.
- 이번 회차에서 이 대기 때문에 재현된 실패는 없었다. 같은 원인이 재현된 곳만 바꾼다는 지시대로 고치지 않았다.

## 7. 통합판 `integration/v158` 4a4a04a3 (84fb03d6 위 커밋 20개 — cherry-pick 16개 충돌 0 + 통합 실행에서 드러난 보완 3개 + 승인된 탐침 보완 1개)

순서: 시험 알림(d32244e5) → 시험 래치(ee5beb18·0a807cbb) → A/B v4 사전 확인(5f195542·5ddc5cbe·0c980619) → phantom(76ea3050) → 정리 경합(78324572) → 손실(92aa8712·12b94ce3·d825f28a) → DYNAMIC 시계(c4b09e5d) → throttle(d6930856·b5f8a8fd·9c0b16cf·49d485f0, b5 기점 커밋만 옮김) → 보완 7ac9f25b(PAPER 매도 도우미)·c95b74e6(H2 대역 4클래스·허용 목록)·1eed27d1(H2 대역 PositionLimit) → 4a4a04a3(탐침 네 경계, 시험만). 보완은 각 후보 가지에도 같은 내용으로 넣었다(정리 후보 e1e76d6c·99830dd5, 손실 후보 86fdf3b7·3f0dc138). 목록 `patches/integration-v158-commits.txt`, 패치 `patches/integration-v158/`, 가지 HEAD `patches/candidate-heads.txt`.

- F 디스패처(단일 이벤트 진입·처리기 @Transactional·비동기 경계)는 84fb03d6 에 이미 있다. 통합 뒤 `LiveTradingService` 의 `@EventListener/@Async` 는 84fb03d6 과 같은 세 곳(거래소 DOWN/복구·기동)뿐이고 실시간 처리기에 옛 주석이 되살아나지 않았다.
- 84fb03d6 대비 제품 변경: Java 7개(`OrderRepository`·`CandleFreshnessGuard`·`DynamicTradingService`·`LiveTradingService`·`OrderExecutionEngine`·`RealtimeProtectLines`(새)·`RiskManagementService`) + `build.gradle`(시험 JVM 환경). 마이그레이션 변경 0(V96 까지 그대로, V95 없음).

🔴 **결과는 SHA 별로 나눈다. 서로 다른 SHA 의 결과를 합쳐 "새 SHA 전체 회귀 통과" 라고 쓰지 않는다.** 4a4a04a3 의 전체 회귀는 아직 돌리지 않았다(지시 §3-4: 최종 고정 뒤 한 번 모아서).

**(1) 666619ce — 관련 실행** (`runs/int-related`, 패턴 `runs/int-related-patterns.txt`): 615 중 실패 9·건너뜀 31. 8건은 PG 전용 손실 조회가 H2 에 닿은 것(→ c95b74e6 대역), 1건은 `DynamicPaperLiveRuleParityTest` 허용 목록·거리(→ 7ac9f25b·c95b74e6). 같은 SHA 의 PG: 정리 7/7·손실 세 보호식 8/8·기존 전역 3/3·LIVE 손실 창 7/7·phantom 4/4(`runs/int-pg-*`). 이 PG 결과는 뒤 제품 변경(7ac9f25b) 전이라 4a4a04a3 근거로 쓰지 않고 (4)에서 다시 돌렸다.

**(2) c95b74e6 — 전체 회귀 1** (`runs/int-full`, 종료 1, 19분 26초): core-engine 298(건너뜀 7)·exchange-adapter 11·strategy-lib 113 실패 0, web-api 1,354 중 **실패 3**·건너뜀 71 — `PositionLimit` 2(H2 손실 조회 → 1eed27d1), 탐침 1.

**(3) 1eed27d1 — 전체 회귀 2** (`runs/int-full2-1eed27d1`, 2026-11-12 21:07:47~21:27:20, 종료 1, 작업 사본 변경 0): core-engine 298 중 실패 0·건너뜀 7, exchange-adapter 11/11, strategy-lib 113/113, web-api 1,354 중 **실패 1·건너뜀 71**·성공 1,282. 실패는 예상한 탐침 1건(`SubmitOrderBoundaryProbeTest`)뿐이며 실패로 기록한다. 원문(Gradle 로그·XML 276개·요약·시각)은 후속 시험 전에 옮기고 체크섬을 고정했다(그 폴더 `SHA256SUMS` 279줄, 파일 자체 sha256 `b0777fc4…`). web-api 건너뜀 71 은 19개 클래스에서 나온다 — 환경 변수가 없을 때 건너뛰는 실제 PG 시험(손실·정리·phantom·V96·N15 잠금·정산 반올림 등)·A/B 실제 연결 2·수동 실행기 `PullbackMtfBacktestRunner` 등. 참고: Gradle 콘솔 집계는 "1344 completed, 61 skipped" 로 XML 집계와 건너뜀 쪽에서 10건 다르다(전체 회귀 1 도 같은 차이, 원인 미확인 — 수치는 XML 기준).

**(4) 4a4a04a3 — 새 SHA 추가 검증** (1eed27d1 대비 바뀐 것은 시험 1파일, 아래 블롭 대조)
- 시험 외 블롭 동일: `git ls-tree -r` 에서 `/src/test/` 를 뺀 736줄이 1eed27d1·4a4a04a3 같음(`blob-1eed27d1-vs-4a4a04a3/`). 제품·리소스·빌드 입력·마이그레이션 변경 0.
- 탐침 단독 1/1(`runs/probe-new`), 판별력 대조 수정 전 1/1 실패(`runs/probe-new-before`) — §3.
- 관련 H2(정리 경합 판정 실행과 같은 패턴 + 탐침·PositionLimit·`*Submit*`·`*Intent*`·`*Claim*`·`*OrderExecution*`·`*Closing*`, `runs/new-related-4a4a04a3`): 52클래스 **312 중 실패 0·건너뜀 11**·성공 301. 건너뜀 11 = PG 전용 `ClosingIntentAtomicityPgTest` 5·`ClosingIntentRollbackPgTest` 2·`PhantomKeepsConfirmedPnlPgTest` 4(이 실행에 PG 변수 없음) → 바로 아래 PG 로 실행.
- 실제 PG(실행마다 새 DB, `runs/new-pg-*`): 정리 `ClosingIntent*PgTest` **7/7**, 손실 세 보호식 **8/8**, 기존 전역 손실 **3/3**, LIVE 손실 창 **7/7**, phantom **4/4** — 건너뜀 0.
- 실제 jar(아래 jar 대조): 4a4a04a3 깨끗한 작업 사본에서 bootJar 1회.

**jar 출처 대조** (`jar-4a4a04a3/`): v158 통합판의 기존 실제 jar 는 없었다(기존 jar 는 b5cd1d0·9bce7dc1·84fb03d6 뿐) — 그래서 연결이 아니라 4a4a04a3 에서 1회 만들었다(`./gradlew clean :web-api:bootJar`, 작업 사본 변경 0, sha256 `0834382a…`, 795항목 압축 793파일). 시험만 바뀐 뒤라 이후 시험 보완으로 다시 만들 이유는 없다. jar 비교기 v2(`56f5d3ba…`, 고치지 않음):

- 보존 b5cd1d0.jar(`a7ef759f…`, D3·sma20p D4 와 같은 기준) 대비 **881→895, 변경 48·추가 14·삭제 0**. 클래스 밖 차이는 `db/migration/V96__paper_aligned_runtime_state.sql` 추가 하나. (로컬 b5cd1d0-base.jar `c704528f…` 와 보존 jar 는 항목 차이 0 — 같은 목록.)
- 84fb03d6.jar(`71f105a2…`) 대비 **891→895, 변경 24·추가 4·삭제 0** — 변경 클래스는 모두 위 제품 Java 7개에서 나온다(`OrderRepository`·`CandleFreshnessGuard`·`DynamicTradingService`·`LiveTradingService`·`OrderExecutionEngine`(+`ClosingSellIntent` 추가)·`RealtimeProtectLines`(새, 3클래스 추가)·`RiskManagementService`). 라이브러리 차이 0.
- 9bce7dc1.jar 대비 891→895, 변경 29·추가 4·삭제 0(참고).
- jar 바이너리는 관례대로 커밋하지 않았다(해시 `jar-4a4a04a3/jar-hashes.txt`). 서버 빌드 jar 는 해시가 다를 수 있고 판정은 항목 비교로만 한다.

## 8. 포함/제외 표 (초안 — 후보 SHA 4a4a04a3, 포함 범위 판정 전이라 최종 고정 아님)

| 항목 | 출처 | 통합판 포함 | 상태 |
|---|---|---|---|
| F 디스패처·B01·V96·N15·O1O3 등 84fb03d6 내용 | 84fb03d6 | 포함(기점) | v1.157 수용 범위 |
| 시험 알림 차단 | d32244e5 | 포함 | 시험 설정만, 판정 대기 |
| 시험 래치 2건 | ee5beb18·0a807cbb | 포함 | v1.158 수용 |
| A/B v4 사전 확인 | 5f195542 계열 | 포함 | v1.158 수용(시험·도구만) |
| phantom 확정분 보존·입금 같은 트랜잭션 | 76ea3050 | 포함 | v1.158 로컬 수용 |
| throttle 실제 판정 규칙 | 49d485f0 계열 4커밋 | 포함 | v1.158 로컬 수용, F 위 실행 확인은 §7 |
| 정리 경합(CLOSING+의도 원자) | 78324572·7ac9f25b | 포함 | **새 후보, 판정 대기** |
| 경계 탐침 네 경계 보완 | 4a4a04a3(정리 후보 99830dd5) | 포함 | 시험만, 총괄 승인 범위(review-1eed27d1-boundary) — 탐침 코드 검토 전 |
| H2 손실 관문 대역(14클래스+허용 목록) | c95b74e6·1eed27d1 등 | 포함 | 시험만, PositionLimit 은 손실 합산 관문 한정으로 허용 |
| 손실 세 보호식 | 92aa8712·12b94ce3·d825f28a | 포함 | **새 후보, 운영 정책 적용 미승인** |
| DYNAMIC 판단 시계 | c4b09e5d | 포함 | **새 후보, 판정 대기** |
| DYNAMIC 쿨다운 종류별 격리 81b0e7a7 | candidate/dynamic-cooldown-per-kind | **제외** | 이번 범위 밖, 포함 여부 판정 요청 |
| V95 | — | 제외 | 유지 |

- 총괄이 새 후보 셋 중 하나라도 빼라고 하면 해당 커밋을 빼고 통합판을 다시 만든 뒤 관련→PG→전체를 다시 돈다(커밋이 독립이라 cherry-pick 순서만 바뀐다).

## 9. D4 초안 — **실행 금지·미정 값 있음**

이 절은 실행 절차가 아니다. 서버에서 아무것도 실행하지 않았다. 값이 정해지지 않은 칸은 `미정` 으로 둔다.

| 변수 | 값 |
|---|---|
| OLD(현재 운영) | `b5cd1d0d…` / 이미지 `52e12428…`(#27, `~/maint-o13n21/image-new.txt`) — 수용 기록에서 옮김, 추정 아님 |
| NEW(후보 SHA) | `4a4a04a304fe22555fbeacd904175a84598af015`(`integration/v158`) — **후보**. 총괄의 포함 범위 판정(§8)으로 커밋이 빠지면 바뀐다 |
| 로컬 jar 기대(`JARN`·`RARX`) | 4a4a04a3 실제 대조값: `JARN="(끝) 881 895"`, `RARX` 62줄(변경 48·추가 14 — V96 SQL 1 포함), 보존 b5cd1d0.jar `a7ef759f…` 기준(§7) |
| DIFFN | `git diff --name-only b5cd1d0 4a4a04a3` 정확한 목록 **98경로**(시험 69·제품 Java 20·마이그레이션 1·`build.gradle` 1·프런트 7), 목록 sha256 `867849d3…` |
| DDL | V94→V96(V96 `paper_aligned_runtime_state` 한 개 — `paper_trading.position.exit_decided_at`·`virtual_balance.mdd_peak_krw` 두 열, NULL 허용·기본값 없음·기존 행 불변), **V95 제외**(4a4a04a3 에 V95 없음 확인). 새 후보 셋은 DDL 없음 |
| 이미지·장부·점검 설치본·crontab | **미정** — sma20p D4 `U변수.sh` 구조를 따르되 U0 직전 운영 현재값 재확인 뒤 기입(지금 옮겨 적지 않음) |
| 프런트 | **미정** — DIFFN 에 프런트 7경로(PAPER 표시·e2e)가 들어 있으나 U1 은 backend 이미지만 다시 만든다. 프런트 재빌드를 이번 배포에 넣을지, 구 프런트와 새 API 의 호환을 확인할지 판정 필요(미확인) |

순서 초안(D3/D4 기존 블록 구조를 그대로 쓰고 값만 바꾼다): `F 변수 → G 변수 → D4 U변수 → U0(현재 이미지·기록·HEAD 대조) → U1(:14~:24 밖, checkout·서버 빌드·jar 항목 비교) → ⏸ 운영자 확인 → U2(DDL V96 적용 → backend 재기동) → 5~10분 U3 → 30분 U4 → 뒤 관측`.

| 단계 | 멈춤 조건 | 복구 | 관측 |
|---|---|---|---|
| U0 | 실행 이미지 ≠ #27, HEAD ≠ OLD, 기록 파일 불일치 | 없음(아무것도 안 바꿈) | 대조 출력 |
| U1 | jar 항목 차이가 기대와 다름, 경로 수 다름, V95 존재 | U1되돌림(재기동 없음, HEAD·latest 원복) | jar 비교 출력 |
| U2 | V96 적용 실패, 기동 실패 | U되돌림: 이미지 #27·b5cd1d0 복귀. **V96 은 되돌리지 않는다** — 정적 근거: 구판 Flyway 9.22.3 기본 `ignoreMigrationPatterns=*:future` 라 모르는 V96 을 검증에서 무시, `ddl-auto: validate` 는 남는 열을 막지 않음, 두 열은 NULL 허용·기본값 없음이라 구판 INSERT 영향 없음. **실제 구판 기동은 미검증**(대역 리허설에서 V96 적용 DB 로 b5cd1d0 jar 기동 확인 예정) | 기동 로그·마이그레이션 이력 |
| U3/U4 | ERROR 증가, 손실 관문 판정 불가 로그, CLOSING 장기 잔존, 정리 대기 증가 | U되돌림 | 아래 관측 항목 |

- **구판 복구 제한(유지)**: 새 모드(LIVE_ALIGNED 등) 세션을 만든 뒤 구판(b5cd1d0)으로 되돌리면 구판은 그 세션의 CLOSING·새 열을 처리하지 못한다. 그래서 **새 세션 생성 전까지만** 구판 복구를 허용하고, 생성 뒤에는 되돌림 대신 정지·정리로 대응한다. 장부 기록 실패 시 진행 거절도 유지한다.
- **새 후보로 생기는 관측 항목**:
  - 손실 관문: `[DYNAMIC REAL 보호식]`·`[전체 REAL 보호식]`·`판정 불가` 거절 로그 건수. 현재 DYNAMIC REAL 손실 이력이 있고 RUNNING DYNAMIC REAL 이 없으면 **배포 직후부터 실자금 신규 매수가 판정 불가로 막힌다** — 배포 전 읽기 전용 SQL 로 확인한다 — 조회문 `d4-draft/preflight-real-loss-readonly.sql`(제품과 같은 원천·조건: 비세션 FILL·확정 LIVE 세션 매도·DYNAMIC 세션 매도 손실 1/7/30일, RUNNING LIVE·DYNAMIC REAL 초기자본, `dynamic_judgement_impossible` 열, `risk_config` 한도; `BEGIN READ ONLY … ROLLBACK`). 로컬 PG(V96 적용 시험 DB)에서 구문·실행만 확인했고 제품 판정과의 값 대조는 하지 않았다. 운영 실행은 승인 뒤 U0 앞에서만. 결과가 "판정 불가" 면 배포 전에 총괄 판단이 필요하다(정책 결정, 개발이 정하지 않음).
  - 정리 경합: `INTENT_READY` 매도 행이 CLOSING 과 함께 생기는지, `WITHDRAWN_DISPATCH_LOST` 건수, "CLOSING 포지션에 SELL 주문 없음" 경고가 줄었는지.
  - DYNAMIC 시계: 운영 기본 시계라 동작 같음 — 관측 항목 없음.
- 변수 초안 `d4-draft/U변수.draft.sh`(맨 위 ⛔ 실행 금지): OLD·NEW·DDLV·DIFFN 98·RARX 62·JARN 을 실제 대조 파일에서 생성했고, `bash -n` 통과 + 불러온 값이 원본 목록과 같음을 확인했다. 이미지·장부 값은 `미정`.
- U0~U4·U되돌림 블록 생성·대역 리허설(D3/D4 대역 장치 사본)은 **아직 하지 않았다**(포함 범위 판정·최종 SHA 고정 뒤).
- 운영 실행은 승인되지 않았다.

## 10. 남은 일

- 포함 범위 판정(특히 §8 새 후보 셋·81b0e7a7) → 최종 SHA 고정(빠지는 커밋이 없으면 4a4a04a3) → 그 SHA 의 전체 회귀 1회(지시 §3-4) → D4 블록/대역 리허설(구판 V96 기동 확인 포함)·프런트 판정. 제품 변경이 없으면 jar 는 다시 만들지 않고 §7 jar 를 연결한다.
- 새 탐침 코드 총괄 검토.
- LIVE BTC 관문의 판단 시계 연결 여부(§5).
- 옛 대기 20개 중 "곧바로 단언" 묶음의 실제 경합 재현 조사(§6).
- 과거 60초 지연 원인(§3, 미입증).

## 파일

- `runs/` 실행 원문(시각·HEAD·작업 사본 차분·Gradle 로그·XML·요약). PG 실행은 `time.txt` 에 DB 이름.
- `patches/` 통합판 format-patch 20개·커밋 목록·가지 HEAD.
- `runs/int-full2-1eed27d1/` 1eed27d1 전체 회귀 원문(자체 `SHA256SUMS`, 후속 시험 전에 고정).
- `blob-1eed27d1-vs-4a4a04a3/` 시험 외 트리 대조. `jar-4a4a04a3/` bootJar 로그·jar 비교 출력·해시·DIFFN 목록. `d4-draft/` 실행 금지 변수 초안·배포 전 읽기 전용 조회문.
- `wait20-classify.tsv` §6 분류 원자료.
- `SHA256SUMS` — 이 폴더 전부(자신 제외). `.gitattributes` `* -text`.
