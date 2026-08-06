# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 이 파일은 **최신 작업 이력(최근 세션 몇 개) + 보류/결정 대기 항목 + 프로젝트 참조 정보**만 담는다. 오래된 상세 이력은 [`docs/old_progress.md`](old_progress.md)(2026-08-06 이전 전체 백업)와 [`docs/CHANGELOG.md`](CHANGELOG.md)를 참조.
> **2026-08-06**: 파일이 2,000줄+ 로 비대해져 `old_progress.md`로 전체 백업 후 이 파일을 정리했다. 상세 근거·재현 과정이 필요하면 `old_progress.md`에서 날짜로 검색할 것.

---

## 🔴 보류·결정 대기 항목

> **최우선 (2026-08-06 벤치마크 측정 결과 — 아래 별도 섹션 참조)**
>
> 1. **실자본 매매 중단 → 페이퍼 전환 검토** — 시스템이 시장 대비 알파 음수(동적 −3.30% vs 알트 −1.63%), 누적 실현손실 −21,854원, 승률 8.9~11.1%. 지금 속도(6일 8거래)로는 통계적 유의성에 도달하기 전에 계속 잃는다. 페이퍼로 전환하면 **잃지 않으면서 같은 데이터를 얻는다**. ✅ **2026-08-06 페이퍼를 LIVE와 동일 로직으로 정렬 완료**(위 별도 섹션) — 이제 페이퍼 결과가 실전 예측에 유효하다. `PaperTradingService`는 07-01 이후 미가동 상태이므로 재가동만 하면 된다.
> 2. **자본 증액 금지** — 기대값이 음수인 상태에서 금액을 늘리면 손실 속도만 빨라진다. 증액 조건은 "벤치마크 대비 알파가 양수임이 통계적으로 증명될 때" 하나뿐.
> 3. **벤치마크 비교를 시스템에 내장** — 이번엔 수동 SQL로 계산했다. 모닝 브리핑/성과 대시보드에 "같은 기간 BTC·알트 홀딩 대비" 고정 표시 필요. 이게 없어서 몇 달간 "잘하고 있는지" 판단 자체가 불가능했다.
> 4. **신호 반전 가설 검증** — BUY 후 24h −4.81%, SELL 후 +1.06%. 신호가 체계적으로 반대일 가능성. 백테스트에서 진입 신호를 반전시켜 돌려보면 즉시 판정 가능(수수료·슬리피지 포함해서). 양수가 나오면 중대한 발견, 안 나오면 신호는 그냥 무작위 + 마찰비용.
> 5. **중단 기준(kill criteria)을 미리 문서화** — "N거래 후 벤치마크 대비 알파가 음수면 이 전략은 폐기" 를 사전에 정해두지 않아, 나쁜 전략을 계속 고쳐 쓰는 루프에 빠져 있다. 현재 22개 전략 중 실전 검증 통과한 것은 **0개**.

> 아래는 코드가 준비돼 있고 실행 여부·시점만 운영 판단이 필요한 것들.

- **Walk Forward 게이트 활성화** (`REQUIRE_WALK_FORWARD_GATE`) — `WalkForwardValidationGate` 구현 완료, 기본값 비활성. 대부분 전략이 Walk Forward 이력이 없어 켜는 즉시 신규 세션 생성이 전면 중단된다. 켜기 전에 `GET /api/v1/strategies/walk-forward-gate-status`로 어떤 전략이 막히는지 먼저 확인하고, 운영 중인 전략들(COMPOSITE_MOMENTUM_ICHIMOKU_V2 등)에 Walk Forward부터 돌려 통과시킬지 판단할 것.
- **LIVE time stop 활성화** (`maxHoldHours`) — 컬럼·로직(V64) 완료, 기본값 0(비활성). DYNAMIC도 처음엔 세션 하나만 테스트 삼아 켰던 전례를 따를 것. LIVE는 유령 포지션 자동 정산이 아직 없어(헬스체크는 감지·알림만) DYNAMIC보다 보수적으로 접근해야 한다. 필요한 세션에 개별로 켜는 것은 기존 API로 이미 가능.
- **신호 기대값 자체가 음수인 문제** — 최근 7일 동적 세션 BUY 신호 사후수익률 4h −2.17%/24h −4.47%(n=50). Walk Forward 게이트는 "검증 안 된 전략을 막는" 것이지 "전략 자체를 고치는" 게 아니라서, 게이트를 켜도 이 문제는 해결되지 않는다. 전략/신호 모델 자체를 봐야 하는 별도 과제.
- **시간 초과 청산(time stop) 텔레그램 알림 부재** — `STOP_LOSS` 알림 유형에 안 잡혀 자본 회수 이벤트가 사용자에게 통지되지 않는다. LIVE time stop을 켤 때 함께 처리하는 게 자연스러움.
- **LIVE 유령 포지션 자동 정산** — 헬스체크(`OperationalHealthCheckService`)는 감지·알림까지만 하고 자동 정산은 안 한다. DYNAMIC의 `reconcileDynamicGhostPositions`에 대응하는 LIVE용 자동 정산을 추가할지는 실거래 자금에 직접 손대는 범위라 별도 검토 필요.
- **e2e 스위트(`@playwright/test`) 미설치** — `navigation.spec.ts`·`global-setup.ts`·`auth-fixtures.ts`는 작성돼 있으나 의존성이 없어 실행 불가. `npm i -D @playwright/test && npx playwright install chromium` 필요.
- **StrategyDegradationWatchdog을 DYNAMIC까지 확장할지** — 현재 LIVE(`sessionType=REAL`)만 감시. 저하 발견 시 Discord 알림에 그치지 않고 Walk Forward 게이트와 연동해 자동 재차단할지도 별건.
- **Walk Forward 미리보기 API를 `/strategies` 페이지에 노출** — 현재 API만 있고 프런트 표시 없음.

---

## 🔴 2026-08-06 모의투자(PAPER)가 실전(LIVE)과 완전히 다른 시스템이었음 — 정렬 완료

> 사용자 질문("같은 전략·같은 코인으로 실전과 페이퍼를 동시에 돌리면 결과가 같다고 볼 수 있나?")에서 출발한 검증. **답은 아니오였다.** 벤치마크 측정 직후 "페이퍼로 전환해 안전하게 학습하자"는 권고를 냈었는데, 그 권고의 전제가 성립하지 않았다.

### 발견된 차이 (정렬 전)

| 항목 | 실전(LIVE/DYNAMIC) | 페이퍼(정렬 전) |
|---|---|---|
| **진입 게이트** | EMA200 · BlackSwan · Range · BTC가드 | **전부 없음(0개)** |
| SL/TP 산정 | `ExitRuleCalculator` ATR 기반 clamp | `ExitRuleChecker` 고정 % |
| Time stop | 있음 | 없음 |
| 전략 SELL 게이트 | 최소보유 180분 + 본전청산 차단(+0.30%) | **없음 — 신호 즉시 청산** |
| `evaluate()` 파라미터 | coinPair·sessionStartedAt 등 주입 | **`emptyMap()`** |
| 캔들당 1회 평가 게이팅 | 있음 | **없음 — 60초마다 재평가** |
| 미마감 캔들 | `closedCandleSlice()`로 제외 | **그대로 사용** |
| 슬리피지 | 실측 −0.03~+0.42% | **0 (종가 정확 체결)** |
| 최소주문 5,000원 | 있음 | 없음 |

**가장 치명적인 건 진입 게이트 부재다.** 실전 로그에는 *"BUY 신호 86건 전량 진입 게이트 차단"*, *"54건 전량 차단 → 실행 0건"* 이 반복된다. 즉 실전은 BUY 신호 대부분을 걸러내는데 페이퍼는 그걸 전부 체결했다 — **거래 모집단 자체가 달랐다.** 이 상태의 페이퍼 성적은 실전 예측에 쓸 수 없다.

여기에 미마감 캔들 + 60초 재평가가 겹쳐, 실전에 없는 미세한 정보 이점까지 있었다.

### 정렬 작업 — [PaperTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java)

`runSessionStrategy`를 `LiveTradingService.processSessionTick`과 동일한 순서·게이트로 재작성했다.

- **진입 게이트 4종 이식** — EMA200(면제 전략 포함) → RANGE → BLACK_SWAN → BTC_MARKET_GUARD, LIVE와 같은 순서.
- **닫힌 캔들 게이팅** — `lastEvaluatedClosedCandle` 도입, 미마감 캔들은 직전 닫힌 캔들로 평가.
- **SL/TP를 `ExitRuleCalculator`로 교체** — 세 서비스(LIVE·DYNAMIC·PAPER)가 같은 ATR 공식을 공유.
- **time stop 추가** — `ExitRuleCalculator.shouldTimeStop`, 기본 비활성.
- **전략 SELL 게이트 3종** — 최소보유 180분 / 본전청산 차단 / 손실탈출 예외.
- **슬리피지 0.1%** — 매수는 높게, 매도는 낮게 체결. 백테스트와 같은 값으로 세 엔진 체결 가정 통일.
- **최소주문 5,000원** — 실거래에서 못 넣는 주문은 페이퍼도 넣지 않는다.
- **신호품질 기록**(`wasExecuted`/`blockedReason`) — LIVE와 동일하게 남겨 기대값 분석이 가능해졌다.
- **[V66](../web-api/src/main/resources/db/migration/V66__align_paper_trading_with_live.sql)** — `virtual_balance`에 `stop_loss_pct`·`invest_ratio`·`max_hold_hours` 추가. NULL이면 `risk_config` 기본값 폴백(LIVE와 동일 경로)이라 **LIVE 세션 설정을 그대로 복제해 돌릴 수 있다.**

### 의도적으로 적용하지 않은 LIVE 로직

자본 배정 게이트라 페이퍼의 목적과 상충하므로 제외했다 — `StrategyLiveStatusRegistry.isBlocked`, `WalkForwardValidationGate`, `§8 cross-session 잔고 가드`. **페이퍼는 미검증 전략을 검증하는 도구인데 "검증되지 않아 차단" 규칙을 적용하면 존재 이유가 사라진다.**

### 검증

- [PaperLiveAlignmentTest](../web-api/src/test/java/com/cryptoautotrader/api/service/PaperLiveAlignmentTest.java) 9건 — 청산 게이트 상수 3종 + `CANDLE_LOOKBACK`이 **LIVE와 같은 값인지 리플렉션으로 대조**, 데이터 공급 불변식 3건(`SYNC_CANDLE_COUNT ≥ lookback`, `> 201`, 세션 한도 ≥ 100), 세션 설정 배선 2건.
- **무력화 검증 2회 완료** — ① 페이퍼 `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT`를 180 → 60으로 바꾸면 1건 실패 ② `SYNC_CANDLE_COUNT`를 520 → 120(구 값)으로 되돌리면 2건 실패. 둘 다 확인 후 복원했다. **한쪽만 튜닝하거나 동기화 수를 낮추는 순간 CI가 잡는다** — 이번 사태의 근본 원인이 "조용히 벌어진 것"이었으므로 이 가드가 핵심이다.

### 격자 실험(코인 N × 전략 M) 대응 — 세션 한도 + 데이터 공급

사용자 요청("10코인 × 10전략 = 100세션은 돌려야 한다")을 검토하니, **세션 한도만 올리면 조용히 실패하는 상태**였다.

- **🔴 진짜 블로커는 캔들 데이터였다.** 페이퍼는 `market_data_cache`만 읽는데 H1 실측 현황이 BTC·ETH만 2,305건 최신이고, XRP 1,700건(44일 전), DOGE 523건(100일 전), SOL 407건(98일 전), 나머지는 **0건**이었다. 즉 지금 H1로 돌릴 수 있는 코인은 **2개뿐**.
- **더 나쁜 건 `SYNC_CANDLE_COUNT = 120`** — 소비 측 `CANDLE_LOOKBACK`은 500이고 EMA200 계열 전략은 닫힌 캔들 201개 이상을 요구한다. 이력 없는 신규 코인으로 세션을 만들면 캔들이 120개만 쌓여 **EMA200 전략이 구조적으로 신호를 낼 수 없다.** 그대로 100세션을 띄웠으면 "표본 0"이 또 반복됐을 것이다.

조치 3건:

- **`SYNC_CANDLE_COUNT` 120 → 520** — 소비 측은 항상 "최근 500개 구간"만 조회하므로, 이 값이 500 이상이면 과거 데이터에 갭이 있어도 평가 구간은 연속으로 채워진다. `UpbitCandleCollector`가 200개 단위로 페이지네이션하므로 pair당 3회 호출(10코인 = 30회/분, 업비트 공개 API 한도에 여유).
- **`MAX_CONCURRENT_SESSIONS` 20 → 120** — 10×10 격자에 여유분.
- **틱 단위 캔들 캐시 신설** — 같은 `(코인, 타임프레임)`을 세션마다 다시 조회하던 낭비 제거. 100세션이 10코인을 쓰면 500행 쿼리가 **200회 → 11회**(코인 10 + BTC 가드 1)로 줄어든다. 틱마다 새로 만들므로 stale 위험 없음.

**동기화 대상은 자동 확장된다** — `MarketDataSyncService`가 RUNNING 세션(페이퍼+실전)에서 `(coinPair, timeframe)`를 추출해 중복 제거하므로, 원하는 코인으로 세션을 만들기만 하면 다음 틱부터 수집이 시작된다. 다만 **500개가 쌓이기까지 시간이 걸리므로**(H1 기준 첫 sync에 520개 일괄 수집 → 즉시 충족) 세션 생성 직후 1~2틱은 "캔들 부족" 경고가 날 수 있다.

### ⚠️ 남은 차이 (문서화)

- 페이퍼는 서킷 브레이커(`riskManagementService.checkCircuitBreaker`)를 적용하지 않는다 — LIVE/DYNAMIC 전용 엔티티 오버로드라 `VirtualBalanceEntity`에 바로 쓸 수 없다. 연속 손실 중단이 필요하면 별도 작업.
- 페이퍼에는 WS 실시간 SL 감시가 없다(60초 폴링만). 실전은 WS로 초 단위 감지하므로 **급락 시 페이퍼가 실전보다 늦게 손절**될 수 있다.

---

## 2026-08-07 DYNAMIC(멀티코인) PAPER 모드 추가

> 배경: 페이퍼 정렬(위 섹션) 직후 "실전과 페이퍼를 동시에 돌리면 같다고 볼 수 있나?"는 질문에서, 벤치마크 열세를 검증하려면 결국 **동적 멀티코인 엔진**(운영 중인 세션 대부분이 여기 있음)에서 코인×전략 조합을 대량으로 실험할 수 있어야 한다는 결론에 도달. `PaperTradingService`(단일 코인) 확장이 아니라 `DynamicTradingService` 자체에 PAPER 모드를 추가하는 쪽으로 재설계.

### 설계 원칙

전략·게이트·SL/TP·time stop·워치리스트 로직은 REAL과 **100% 공유**하고, 체결 한 곳만 분기한다.

- `dynamic_session.trading_mode` (`REAL`|`PAPER`, [V67](../web-api/src/main/resources/db/migration/V67__add_trading_mode_to_dynamic_session.sql)) 로 세션 단위 구분.
- `position`/`order.session_kind`를 REAL="DYNAMIC", PAPER="DYN_PAPER"로 분리 — 기존 REAL 전용 정산 스케줄러 4종 중 3종(`reconcileDynamicClosingPositions`/`GhostPositions`/`OrphanBuyPositions`)은 이미 `SESSION_KIND`로 먼저 필터링하고 있어 **자동으로 격리**된다.
- PAPER 체결은 `OrderExecutionEngine`(실거래소 연동)을 아예 거치지 않고, 같은 `@Transactional` 메서드 안에서 슬리피지(0.1%)·수수료(0.05%)를 반영해 동기 시뮬레이션한다 — REAL의 비동기 체결(주문 제출 → 콜백으로 사이즈 확정) 구조에서 오는 부분 롤백 위험(과거 P0 패턴)이 PAPER에는 애초에 존재하지 않는다.
- 매도 후처리(`finalizeDynamicSell`)는 REAL 정산 경로와 완전히 동일한 함수를 그대로 재사용 — 손익·수수료·부분체결·세션잔고 복원 로직이 두 모드에서 갈라지지 않는다.

### 발견 및 수정한 버그 — `reconcileDynamicSessionBalance`가 PAPER를 감지하지 못함

나머지 안전망 3종과 달리 이 스케줄러는 **세션을 먼저 순회한 뒤** 포지션/주문을 `SESSION_KIND`(REAL 고정)로 조회한다. PAPER 세션은 자기 포지션이 `DYN_PAPER`로만 존재하므로, 이 스케줄러 입장에서는 "포지션 없음"으로 보여 정상적인 보유 잔고 차이가 고아 잔고로 오인되어 강제 롤백될 뻔했다. `if (session.isPaper()) continue;` 가드 추가로 수정.

이 버그는 **테스트를 sabotage-then-restore로 검증하다가** 발견했다 — 처음 작성한 `balanceReconcile_ignoresPaperSessions` 테스트는 grace-period(3분) 가드에 막혀 통과했지만, 실제로는 kind 체크 로직에 도달하지도 못한 "우연히 통과"였다. `backdateUpdatedAt` 헬퍼로 grace period를 우회하도록 테스트를 다시 짜자 진짜 버그가 드러났다.

### 검증

- [DynamicPaperTradingTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPaperTradingTest.java) 8건 — 세션 생성 기본값/PAPER 지정, PAPER 매수가 `OrderExecutionEngine`을 호출하지 않고 즉시 체결되는지, REAL 매수는 여전히 호출하는지, PAPER 매도 즉시 정산, 고아매수/잔고 정산 스케줄러가 PAPER를 무시하는지, REAL·PAPER 간 교차세션 노출 격리.
- **무력화 검증 2회** — ① `SESSION_KIND_PAPER`를 `"DYN_PAPER"` → `"DYNAMIC"`(REAL과 충돌)로 바꾸면 격리 의존 테스트 3건 실패 ② `reconcileDynamicSessionBalance`의 `isPaper()` 가드 제거 시 정확히 1건 실패. 둘 다 복원 후 8/8 통과 재확인.
- `session_kind` 컬럼이 `VARCHAR(10)`이라 `"DYNAMIC_PAPER"`(13자)는 잘림 위험 — `"DYN_PAPER"`(9자)로 명명, 코드 작성 전에 컬럼 정의를 먼저 확인해 사전에 회피.

### 의도적으로 REAL 전용으로 남긴 것

- `StrategyLiveStatusRegistry.isBlocked`/`WalkForwardValidationGate` — 자본 배정 게이트라 페이퍼(미검증 전략 검증용)의 목적과 상충, PAPER 세션 생성 시 건너뜀.
- WS 실시간 SL/TP 감시(`doOnRealtimePriceEvent`, `refreshWsSubscription`) — PAPER는 60초 폴링만 사용, 앞서 정렬한 단일코인 페이퍼와 동일한 제약.
- `restoreBlackSwanCooldown` 등 REAL 전용 쿨다운 복원 — 변경 없음.

### 미완료

- 프런트에서 DYNAMIC 세션 생성 시 `tradingMode` 선택 UI 없음(API 응답 필드는 노출됨) — 아직 요청 없음.
- 전체 회귀 테스트(`./gradlew :web-api:test`) 최종 확인 진행 중.

---

## 🔴 2026-08-06 벤치마크 대비 성과 측정 — **시장보다 못하고 있음(알파 음수)**

> 사용자 질문("이 시스템이 잘하고 있는가")에 답하기 위해 운영 DB에서 처음으로 **벤치마크 대비 측정**을 수행. 그동안 이 비교가 없어서 판정 자체가 불가능했다.

### 시스템 vs 매수후보유 (07-31 세션 재구성 ~ 08-06, 약 6일)

| 구분 | 시작 | 현재 | 수익률 |
|---|---|---|---|
| **동적 7세션** | 70,000 | 67,687 | **−3.30%** |
| **LIVE 2세션** | 20,000 | 20,068 | **+0.34%** |
| **전체** | 90,000 | 87,756 | **−2.49%** |

| 벤치마크(매수후보유) | 시작가 | 현재가 | 수익률 |
|---|---|---|---|
| KRW-DOGE | 101 | 99 | −1.98% |
| KRW-ETH | 2,721,000 | 2,708,000 | −0.48% |
| KRW-SOL | 105,800 | 104,800 | −0.95% |
| KRW-XRP | 1,537 | 1,489 | −3.12% |
| **알트 평균** | | | **−1.63%** |
| KRW-BTC ⚠️ | 90,890,000 | 91,900,000 | +1.11% (08-03 기준, 기간 짧음) |

- **동적 −3.30% vs 알트 평균 −1.63% → 알파 약 −1.67%p (6일)**. 그냥 들고 있는 것보다 못하다.
- **LIVE +0.34%는 전략 성과가 아니다** — 194가 BTC를 들고 있었고 그 기간 BTC가 +1.11%였을 뿐. 투자비율 0.8을 감안하면 오히려 홀딩보다 못 따라갔다. 195는 6일간 거래 0건.

### 누적 실현손익 — **−21,854원** (전 기간)

| 세션종류 | 청산건수 | 승 | 승률 | 실현손익 | 수수료 |
|---|---|---|---|---|---|
| LIVE | 237 | 21 | **8.9%** | **−16,901** | 1,204 |
| DYNAMIC | 18 | 2 | **11.1%** | **−4,953** | 68 |

**LIVE 월별 분해가 결정적이다:**

| 월 | 건수 | 승 | 실현손익 | 수수료 |
|---|---|---|---|---|
| 2026-03 | 43 | **0** | −138.46 | 138.46 |
| 2026-04 | 89 | **0** | −347.83 | 347.92 |
| 2026-05 | 21 | **0** | −83.95 | 83.98 |
| 2026-06 | 45 | 14 | **−13,883.47** | 456.40 |
| 2026-07 | 39 | 7 | −2,447.46 | 177.69 |

- 3~5월은 **손실 = 수수료 전액**(0승) — 이건 성과가 아니라 05-31에 규명한 "가짜 본전" 버그(매도 체결가 미산출 → `realizedPnl`이 −수수료로만 기록)의 흔적이다. **그 기간은 측정 자체가 안 되고 있었다.**
- 06-23 P0 수정으로 **제대로 측정되기 시작하자마자 6월 −13,883원**. 즉 "2개월 본전권"은 착시였고, 실제 실력이 드러난 6~7월에 −16,331원이 나왔다.

### 신호 기대값 — BUY·SELL 양쪽 다 역예측

| 구간 | 신호 | n | 4h | 24h | 4h 승률 |
|---|---|---|---|---|---|
| DYNAMIC 최근 14일 | BUY | 89 | **−1.585%** | **−4.811%** | 29.2% |
| DYNAMIC 최근 14일 | SELL | 3,672 | +0.161% | +1.055% | 48.4% |
| 실제 체결분 전기간 | DYNAMIC BUY | 25 | −0.614% | **−4.350%** | |
| 실제 체결분 전기간 | LIVE BUY | 173 | −0.080% | −0.149% | |

- **BUY 신호 24h −4.81%(n=89)** — 표본이 50 → 89로 늘었는데 오히려 더 나빠졌다. 같은 기간 시장이 −1.6%였으니 **시장 대비로도 −3.2%p 언더퍼폼**.
- **SELL 후에는 오른다(+1.055%)** — 팔지 말았어야 했다는 뜻. BUY도 SELL도 방향이 반대다. ⚠️ 단 SELL 3,672건은 대부분 SCANNING 중 포지션 없을 때의 노이즈라 해석에 주의 필요.
- LIVE BUY(n=173)는 −0.08%로 사실상 **무작위** — 예측력이 0에 가깝다.

### 07-31 이후 청산 8건 — 1승 7패

| 사유 | 건수 | 손익 |
|---|---|---|
| 실시간 손절(WS) −7.006% | 2 | −1,092.68 |
| 손절 −7.911% | 1 | −666.22 |
| 시간 초과 청산 | 2 | −250.96 |
| 전략 SELL (손실) | 2 | −403.84 |
| 전략 SELL (이익) | 1 | **+87.78** |

### 판정

**배관은 고쳤지만 엣지가 없다.** 지난 한 달간 잡은 버그(FK·롤백·유령포지션·잔고누수·SL이탈)는 전부 실재했고 잘 고쳤지만, 그걸 다 고친 지금도 시장보다 못하다. 문제는 코드가 아니라 **신호 자체에 예측력이 없다**는 것이고, 이건 백테스트(+106~127%) → Walk Forward(+3~4%) → 실전(마이너스)의 3단계 붕괴가 이미 예고한 결과다.

**→ 조치 권고는 아래 "보류·결정 대기 항목" 최상단 참조.**

---

## ✅ 최근 작업 이력 (요약)

### 2026-08-06 [P2 후속] DYNAMIC SL 워치독 + 헬스체크 이력 화면 + MSW 미들웨어 버그

- **DYNAMIC SL 워치독 신설**(`DynamicTradingService.warnStaleSlCheck`) — LIVE에만 있던 §9 워치독(3분 미점검 시 경보 + 그 코인만 REST 강제 갱신)을 DYNAMIC에도 동일 패턴으로 추가. 이제껏 DYNAMIC엔 이런 안전망 자체가 없었다.
- **헬스체크 이력 화면**(`/admin/health-check`) — `GET /api/v1/admin/health-check/history` + Next.js 페이지 신설, 브라우저 실검증(Playwright, mock 모드) 완료.
- **부수 발견·수정**: `proxy.ts` 인증 미들웨어가 `/mockServiceWorker.js`를 제외 목록에서 빠뜨려 로그인 전 MSW 등록 자체가 리다이렉트로 막히던 버그 1줄 수정.
- 신규 테스트 6건 + 전체 스위트 **233건(스킵 1) 전부 통과**. 미배포·미커밋.

### 2026-08-06 [P2] 운영 헬스체크 자동화 + SL 워치독 대응 조치

- **`OperationalHealthCheckService`** 신설 — 세션 잔고 정합성·주문 시퀀스 갭·유령 포지션·무출구 고착 포지션(24h+) 4대 점검을 매일 08:30 KST 자동 실행, `daily_health_snapshot`(V65)에 기록, 이상 시 Discord 알림. **감지·알림만, 자동 정산은 안 함**(LIVE 유령 포지션 자동 정산은 실거래 자금에 직접 손대는 범위 확장이라 스코프 밖).
- **LIVE SL 워치독에 대응 조치 추가** — 그동안 `warnStaleSlCheck`가 경보만 보내던 것을, 그 코인 하나만 REST로 즉시 강제 갱신해 SL 감시를 스스로 복구 시도하도록 확장. 전역 WS 폴백(`isWsUnhealthy`)이 놓치는 "다른 코인은 정상인데 이 코인만 조용히 끊긴" 사각지대를 메운다.
- 신규 테스트 18건 + 전체 스위트 227건(스킵 1) 통과.

### 2026-08-06 [P1] LIVE/DYNAMIC 청산 엔진 통합 (2/2) — SL/TP 공식 + time stop 공유

- **`ExitRuleCalculator`** 신설 — DYNAMIC의 ATR 기반 SL/TP 공식(`resolveStopLossPct`/`resolveTakeProfitPrice`)과 time stop 판정(`shouldTimeStop`)을 공유 클래스로 추출. **LIVE는 이제껏 고정 `stopLossPct`만 썼는데**(세션 194 BTC 136시간 고착의 원인), 신규 진입부터 DYNAMIC과 동일한 ATR 기반 SL/TP를 쓴다.
- V64 마이그레이션 — `live_trading_session.max_hold_hours` 컬럼 신설, 기본값 0(비활성) — time stop은 기본값이 꺼져 있어 배포해도 트리거되지 않지만, **SL/TP 공식 자체는 배포 즉시 바뀐다**(플래그 없음 — 07-31 개편을 뒤늦게 이식하는 것뿐이라 DYNAMIC에서 이미 검증된 변경으로 간주).
- 기존 회귀 테스트 10건 이름만 바꿔 통과(계산 결과 불변 확인) + 신규 9건 + 전체 스위트 무회귀.

### 2026-08-06 [P1] 신호 기대값 검증 게이트 신설

- **`WalkForwardValidationGate`** 신설 — 전략별 최근 Walk Forward 결과(verdict·OOS 기대값)를 판정해 세션 생성에 배선. 기본값 비활성(`REQUIRE_WALK_FORWARD_GATE=false`) — 위 보류 항목 참조.
- 미리보기 API `GET /api/v1/strategies/walk-forward-gate-status` 신설.
- 신규 테스트 12건 + 전체 스위트 200건 통과.

### 2026-08-06 세션 카드 모바일 레이아웃 깨짐 수정 + MSW 목이 통째로 죽어 있던 문제

- **근본 원인**: MSW 핸들러 22개가 `/api/v1/...`에 등록됐는데 클라이언트는 `axios baseURL='/api/proxy'`를 거쳐 `/api/proxy/api/v1/...`로 호출 → 프록시 도입 이후 목이 전부 매칭 실패, 앞선 "28라우트 이상 없음" 검증이 사실 빈 화면만 훑은 것이었음.
- 경로 접두사 수정 + 동적 세션 픽스처 추가 후 재검증(28라우트 × 6폭 전부 통과). 세션 카드 `flex-wrap`/`shrink-0` 누락으로 좁은 화면에서 글자 단위 세로 배열되던 버그도 함께 수정.

### 2026-08-06 [P0 보안] DB 초기화 비밀번호 소스 하드코딩 제거

- `DbResetService`의 하드코딩된 비밀번호를 `DB_RESET_PASSWORD` 환경변수로 이관(fail-closed + 상수시간 비교 + 503 분리 응답). **사용자가 운영 `.env`에 새 값 적용 확인 완료.**

### 2026-08-06 FE 네비게이션 5개 대분류 재편 + 모바일 대응

- 사이드바 브레이크포인트 부재로 모바일에서 화면 65%를 먹던 구조를 `navConfig.ts` 단일 소스 + 데스크톱 사이드바/모바일 상단앱바·하단탭바·바텀시트·드로어로 교체. 그룹: 백테스트·모의투자 / 실전매매 / 전략관리 / 분석 / 설정.

### 2026-08-06 전 세션(동적 7 + 실전 2) 운영 DB 점검

- 기반 건전성 전부 정상(9/9 RUNNING·36h 무결손 틱·잔고 정합성 0원·주문 갭 0). 이 점검에서 나온 문제들(무출구 고착, 동적 세션 시가평가, LIVE time stop 부재, SL 워치독 알림 유실 등)은 이후 세션들(위 P1/P2)에서 대부분 해소 — 남은 것은 위 "보류·결정 대기 항목" 참조.

### 2026-08-05 이전 (요약만 — 상세는 `old_progress.md`)

- 08-05: "손절 5%인데 7~8%" 원인 규명(설계대로의 동작, 하한이었음) — SL 배수·상한 축소, TP 상한 신설, REST 폴백 DYNAMIC 코인 누락 수정, BLACK_SWAN 진입가 가드 추가. 배포 완료.
- 08-04: 멀티코인 24h 운영 분석, BLACK_SWAN 쿨다운 재기동 소실 버그 발견·수정.
- 08-03: 매도 롤백 P0(유령 포지션) 해소, 블랙스완 쿨다운, 미실현손익 복구.
- 07-31 이전: 세션 격리 버그, 청산 규칙 전면 개편(ATR 기반 SL 최초 도입), FK 위반 수정, 워치리스트 큐레이션 등 — DYNAMIC 시스템의 근간을 잡은 다수의 P0/P1 수정. 상세는 `old_progress.md` 07-02~07-31 구간 참조.

---

## 프로젝트 개요

- **서비스**: 업비트 기반 가상화폐 자동매매 시스템
- **운영 환경**: Ubuntu 서버, Docker Compose (`docker-compose.prod.yml`)
- **기술 스택**: Spring Boot 3.2 (Java 17) + Next.js 16.1.6 / React 19.2.3 (TypeScript) + TimescaleDB + Redis

### 모듈 구조

```
crypto-auto-trader/
├── web-api/          # Spring Boot 백엔드 (Gradle 멀티모듈)
│   ├── core-engine/      # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── strategy-lib/     # 전략 22종 (단일 11 + 복합 11)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis + db-backup)
```

### 구현된 전략 22종

**단일 전략 (11종)**: VWAP / EMA Cross / Bollinger Band / Grid / RSI / MACD / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

**복합 전략 (11종)**:

| 전략 | 구성 | 실적합 코인 | 요약 |
|------|------|------------|------|
| COMPOSITE | Regime 자동 선택 | 범용 | — |
| COMPOSITE_MOMENTUM | MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 필터 | ETH·SOL | ETH +53.6%, SOL +59.8% |
| COMPOSITE_ETH | ATR×0.5 + OB×0.3 + EMA×0.2 | ETH | 구버전 평균 +48.7% (재검증 필요) |
| COMPOSITE_BREAKOUT (CB) | ATR×0.5 + VD×0.3 + MACD×0.2, EMA+ADX+RSI Veto 필터 | **BTC·ADA** | BTC **+106.71%**, ADA **+86.98%** |
| COMPOSITE_MOMENTUM_ICHIMOKU (CMI_V1) | CB_MOMENTUM + Ichimoku 필터 | XRP | XRP +1.04% (유일 양수) |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 (CMI_V2) | MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터 | **DOGE** | DOGE **+124.77%** |
| COMPOSITE_BREAKOUT_ICHIMOKU | CB + Ichimoku 필터 | — | ⚠ CB와 동일 (ADX 중복) |
| COMPOSITE_REGIME_ROUTER (CRR) | ADX/ATR 레짐 → CB/V1/V2 자동 위임 | **SOL·ETH** | SOL **+65.38%**, ETH +65.09% |
| COMPOSITE_MTF_BTC | CB(H1) + Supertrend(H4) | **ETH·AAVE·CHZ** | ETH **+127.70%**, AAVE +28.15% |
| COMPOSITE_MTF_MOMENTUM | CMI_V2(H1) + Supertrend(H4) | **BLUR·DOGE** | BLUR **+48.06%**, DOGE +83.40% |
| COMPOSITE_MTF_CONFIRMED | CRR(H1) + Supertrend(H4) | **XRP** 범용 | XRP **+3.37%** (유일 흑자) |
| MACD_STOCH_BB | MACD + StochRSI + 볼린저 6조건 AND | ❌ 비활성화 | BTC -2.32%, 거래 극희소 |

전체 코인별 백테스트 수치(2026-04-30 H1 FULL, 17코인 × 7전략)와 Walk-Forward 결과는 `old_progress.md` 참조.

---

## 🟢 배포 권고 / 🚨 배포 금지 (2026-04-30 MTF 백테스트 기준, 마지막 갱신)

> H1 FULL 2022~2026-04-30 백테스트 결과 기반. 이후 재검증 없음 — 오래된 기준이니 신규 배포 판단 시 최신 Walk Forward로 재확인 권장.

### Tier 1 — 백테스트 검증 통과

| 코인 | 권장 전략 | FULL 수익률 | MDD | Sharpe |
|------|-----------|------------|-----|--------|
| **BTC** | COMPOSITE_BREAKOUT | +106.71% | -8.88% | 1.24 |
| **ETH** | COMPOSITE_MTF_BTC | +127.70% | -7.24% | 1.35 |
| **SOL** | COMPOSITE_REGIME_ROUTER | +65.38% | -14.93% | 0.76 |
| **DOGE** | CMI_V2 | +124.77% | -30.75% | 0.87 |
| **ADA** | COMPOSITE_BREAKOUT | +86.98% | -14.14% | 0.96 |

### Tier 2 — 흑자 전환·신규 발굴, 관찰 후 투입

| 코인 | 권장 전략 | FULL 수익률 | MDD |
|------|-----------|------------|-----|
| **XRP** | COMPOSITE_MTF_CONFIRMED | +3.37% | -15.67% |
| **AAVE** | COMPOSITE_MTF_BTC | +28.15% | -31.01% |
| **BLUR** | COMPOSITE_MTF_MOMENTUM | +48.06% | -11.91% |
| **CHZ** | COMPOSITE_MTF_BTC | +14.09% | -14.88% |

### 🚨 배포 금지

- 전 코인 × M15 타임프레임(오버트레이딩 -99%), 전 코인 × FAIR_VALUE_GAP(구조 결함)
- ETH/XRP/ADA/CHZ/AAVE × 특정 전략 조합 다수 — 상세는 `old_progress.md` 2026-04-30 섹션 참조
- MOVE/SUPER/FLOCK/AXL/BIO/KERNEL — 거래수 부족으로 통계 신뢰성 없음

---

## Dev Workflow Orchestrator

`.claude/` 6개 서브에이전트(SparkAI → PLAN → Design → Do → Check → Report) 파이프라인 — 상세는 `.claude/ORCHESTRATOR.md` 및 프로젝트 루트 `CLAUDE.md` 참조.
