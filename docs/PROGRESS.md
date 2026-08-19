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
> 3. ~~**벤치마크 비교를 시스템에 내장**~~ ✅ **2026-08-07 완료** — `BenchmarkAlphaService` + 대시보드 상단 고정 노출(위 별도 섹션).
> 4. ~~**신호 반전 가설 검증**~~ ✅ **2026-08-07 기각** — 반전이 오히려 더 나쁘다(평균 −4.85% → −6.01%). 신호는 거꾸로인 게 아니라 **방향성이 없다**. 손실의 정체는 무작위 진입 + 마찰비용(위 별도 섹션).
> 5. ~~**중단 기준(kill criteria)을 미리 문서화**~~ ✅ **2026-08-18 완료** — [`docs/KILL_CRITERIA.md`](KILL_CRITERIA.md) 제정 + `StrategyKillCriteriaService`로 집행(매일 09:00 KST). 자본 보호 3종은 표본 무관 즉시 발동, 엣지 2종은 n≥20. 아래 08-18 섹션 참조. ⚠️ **자동 정지는 아직 꺼져 있다**(`kill-criteria.auto-stop=false`) — 판정이 며칠간 옳게 나오는지 관찰 후 켤 것.
> 6. **LIVE 세션을 H1로 재생성 필요** — 실전(M5)과 페이퍼(H1)의 타임프레임이 달라 A/B 비교가 성립하지 않는다. 세션 생성 후 타임프레임 변경 API가 없으므로 **정지 후 H1로 재생성**해야 한다(현재 포지션 0건이라 안전). 동적 멀티코인은 REAL/PAPER 쌍이 이미 H1로 맞춰져 있어 문제없다.

> 아래는 코드가 준비돼 있고 실행 여부·시점만 운영 판단이 필요한 것들.

- **Walk Forward 게이트 활성화** (`REQUIRE_WALK_FORWARD_GATE`) — `WalkForwardValidationGate` 구현 완료, 기본값 비활성. 대부분 전략이 Walk Forward 이력이 없어 켜는 즉시 신규 세션 생성이 전면 중단된다. 켜기 전에 `GET /api/v1/strategies/walk-forward-gate-status`로 어떤 전략이 막히는지 먼저 확인하고, 운영 중인 전략들(COMPOSITE_MOMENTUM_ICHIMOKU_V2 등)에 Walk Forward부터 돌려 통과시킬지 판단할 것.
- ~~**LIVE time stop 활성화** (`maxHoldHours`)~~ ✅ **2026-08-18 완료** — 기본값 0 → 24 (V68, LIVE·DYNAMIC·PAPER 3종 동시). 운영 RUNNING 10세션에도 소급 적용. 아래 08-18 섹션 참조. ⚠️ 켜자마자 LIVE 경로에서 중복 SELL 1건이 재현됐다(같은 섹션 "새로 드러난 것" 참조) — 미해결.
- **신호 기대값 자체가 음수인 문제** — 최근 7일 동적 세션 BUY 신호 사후수익률 4h −2.17%/24h −4.47%(n=50). Walk Forward 게이트는 "검증 안 된 전략을 막는" 것이지 "전략 자체를 고치는" 게 아니라서, 게이트를 켜도 이 문제는 해결되지 않는다. 전략/신호 모델 자체를 봐야 하는 별도 과제.
- ~~**시간 초과 청산(time stop) 텔레그램 알림 부재**~~ ✅ **2026-08-18 완료** — `notifyTimeStop` 신설, LIVE·DYNAMIC 배선. 08-18 오전 XRP 4건 청산에 알림이 안 간 것이 실측 사례였다.
- **LIVE 유령 포지션 자동 정산** — 헬스체크(`OperationalHealthCheckService`)는 감지·알림까지만 하고 자동 정산은 안 한다. DYNAMIC의 `reconcileDynamicGhostPositions`에 대응하는 LIVE용 자동 정산을 추가할지는 실거래 자금에 직접 손대는 범위라 별도 검토 필요.
- **e2e 스위트(`@playwright/test`) 미설치** — `navigation.spec.ts`·`global-setup.ts`·`auth-fixtures.ts`는 작성돼 있으나 의존성이 없어 실행 불가. `npm i -D @playwright/test && npx playwright install chromium` 필요.
- **StrategyDegradationWatchdog을 DYNAMIC까지 확장할지** — 현재 LIVE(`sessionType=REAL`)만 감시. 저하 발견 시 Discord 알림에 그치지 않고 Walk Forward 게이트와 연동해 자동 재차단할지도 별건.
- **Walk Forward 미리보기 API를 `/strategies` 페이지에 노출** — 현재 API만 있고 프런트 표시 없음.

---

## 🟢 2026-08-19 지문 구멍 마감 — `CompositeStrategy` 상수 12개 (`composite.*`)

V74 배포 확인 중 **내가 방금 만든 구멍**을 발견했다. `DEFAULT_EMA_DEADBAND_PCT = 0.05` 를
추가하면서 지문에 넣지 않았다 — 지문 체계를 만든 이유가 정확히 그건데 같은 실수를 했다.

### 이론이 아니라 실제 구멍이었다

같은 클래스에 동종 상수가 **12개** 있고 전부 지문 밖이었다. 그리고 이건 가정이 아니다:

- 지문에는 `gate.scanWeakThreshold` 가 있지만 운영 `risk_config` 값이 **NULL** 이다
- 즉 지문에 적히는 값은 `null` 이고, **실제로 쓰이는 건 코드 상수 `WEAK_THRESHOLD = 0.3`**
- 이 상수를 바꾸면 해시가 그대로라 변경 전후 거래가 한 표본에 합산된다

V71 검토 때 `ExitRuleCalculator` 에서 똑같은 걸 찾아 `exitcalc.*` 로 해결했는데
이 클래스는 놓쳤다. 같은 패턴을 그대로 적용했다.

### 조치

- `CompositeStrategy.behaviorParams()` — 상수 12개 노출
  (임계 2 + EMA 4 + ADX 6). `RulesetRegistry.base()` 에서 `composite.*` 로 싣는다.
- 리플렉션 가드 — `static final` 수치 상수가 `behaviorParams()` 에 없으면 테스트가 깨진다.
  **뮤테이션 검증**: 더미 상수 `MUTATION_PROBE_FACTOR` 를 추가하니 정확히 그 이름으로 실패했다.
- `RulesetRegistryCompositionTest` 필수 키에 `composite.*` 5종 추가.
- `:web-api:test` **356건**, `:core-engine:test` **209건** 전부 통과.

### 배포 전까지 감쇠 상수를 건드리지 말 것

지금 코드는 배포 전이라 운영 지문에 `composite.*` 가 없다. 이 상태에서 상수를 바꾸면
해시가 안 변해 오늘 쌓이는 데이터가 조용히 섞인다. 배포 후에는 안전하다.

---

## 🟢 2026-08-19 V74 — 감쇠 A/B 기반 + EMA 데드밴드 + 지문 구멍 1건

원인 분해에서 나온 조치를 전부 반영했다. **감쇠값 자체는 바꾸지 않았다** — 이유는 아래.

### 1. 지문 구멍: `gate.scanEmaDampenFactor` 누락 (V71 검토에서 놓친 것)

`risk_config.scan_ema_dampen_factor` 는 **틱마다 읽혀 전략에 넘어가는** 값인데 지문에 없었다.
0.0(역추세 BUY 전량 차단)과 1.0(무필터)은 신호 수를 통째로 바꾸는데, 지문이 같으면
바꾸기 전후 거래가 한 표본에 섞인다. V71 이 막으려던 바로 그 상황이다.

> `scan_min_atr_pct`·`scan_max_spread_pct`·`scan_max_candidate_size` 도 지문에 없지만
> 이쪽은 **구멍이 아니다** — 세션 생성 시 세션 컬럼으로 복사되고 그 세션값이 `scan.*` 로 실린다.

### 2. EMA 추세 판정 데드밴드 (`CompositeStrategy`)

방향 판정이 `emaShort.compareTo(emaLong) > 0` 단순 대소 비교라 격차가 0에 가까워도 추세로 쳤다.
`DEFAULT_EMA_DEADBAND_PCT = 0.05` 를 도입해 격차가 밴드 안이면 **방향 없음**으로 보고
어느 쪽도 감쇠하지 않는다.

**`band == 0` 이면 flat 이 절대 성립하지 않아 수정 전 동작과 정확히 같다** — A/B 대조군을
파라미터만으로 재현할 수 있어야 해서 이 성질을 테스트로 잠갔다.

### 3. 세션별 전략 파라미터 (V74) — A/B 의 전제

`emaFilterDampenFactor` 같은 값은 `risk_config` **전역**이라 바꾸면 14세션이 한꺼번에 움직인다.
대조군이 없어 A/B 가 불가능하고, 시점을 나눠 비교하면 시장 국면이 교란한다.

- `dynamic_session.strategy_params` / `paper_trading.virtual_balance.strategy_params` (jsonb)
- 전략 평가 시 전역값을 깔고 세션값으로 덮는다
- `DynamicSessionRequest.strategyParams` 로 세션 생성 시 지정
- **지문에 `strategy.params` 로 실린다** — 안 실으면 A/B 가 오히려 데이터를 망친다

### 4. 지표 스냅샷 기록 (`IndicatorSnapshot`)

DYNAMIC·PAPER 가 `indicators_json` 을 안 남겨 이번 분석을 `reason` 정규식 파싱으로 해야 했다.
세 엔진이 같은 스냅샷(adx14/atr14/ema20/ema50/close/candleCount)을 남기게 했다.
DYNAMIC 은 `market_regime` 도 로그에 없었다 — 함께 채운다.

### 검증

- 신규 `CompositeStrategyEmaDeadbandTest` 4건 (기본값 양수 가드, 평탄장 무감쇠,
  진짜 하락장 필터 유지, **band=0 이 옛 동작 재현**)
- `RulesetRegistryCompositionTest` +2건 (파라미터 다르면 지문 갈림 / 0.5 와 0.50 은 안 갈림)
  및 필수 키에 `gate.scanEmaDampenFactor=` 추가
- `:web-api:test` **356건**, `:core-engine:test` **208건** 전부 통과

### 값은 바꾸지 않았다 — 의도적이다

감쇠 기본값을 전역으로 바꾸면 **모든 세션이 한쪽 팔이 되어 A/B 가 불가능해진다.**
지금까지 알아낸 것은 필터가 죽이는 신호의 **양**(2일 66건, 통과분 51건보다 많음)이지
그 신호의 **질**이 아니다. 함대 승률이 22% 인 걸 보면 필터가 손실을 막고 있었을 수도 있다.

**다음 단계**: 같은 전략·타임프레임으로 세션 2개를 만들되 한쪽에만
`{"emaFilterDampenFactor": 0.5}` 를 주고 지문이 갈리는지 확인한 뒤 며칠 돌린다.

---

## 🔍 2026-08-19 페이퍼 함대 83%가 거래하지 않는 이유 — 원인 분해

> 112세션 중 **19개만** 거래한 적이 있고 93개는 12일간 포지션을 한 번도 안 열었다.
> 구성은 8코인 × 7전략 × 2타임프레임.

### 게이트가 아니라 신호 생성이 병목이다

7일간 PAPER 신호: **HOLD 12,202 / SELL 1,309 / BUY 80.** BUY 비율 0.65%.
그 80건 중 50건(63%)은 실제 체결됐다 — **진입 게이트는 병목이 아니다.**

전략별 BUY 비율: `PULLBACK_MTF` 3.14%, 나머지 6개는 **0.05~0.26%**.
전체 52거래 중 41건(79%)이 `PULLBACK_MTF` 하나에서 나왔다.

### 원인 분해 (2일치 HOLD 7,033건, `buy=` 점수 추출)

| buy 점수 | 건수 | 비율 |
|---|---|---|
| **0.00 — 지표가 하나도 안 켜짐** | **6,193** | **88.1%** |
| 0.01–0.09 | 622 | 8.8% |
| 0.10–0.19 | 180 | 2.6% |
| 0.20–0.29 (임계 근접) | 37 | 0.5% |

임계값은 `WEAK_THRESHOLD = 0.3`. **88%는 임계값을 낮춰도 안 바뀐다** — 아무 지표도 안 켜졌기 때문이다.
`SUPERTREND:BUY(50)` 단독으로 0.08밖에 안 되므로 임계 통과에는 지표 여러 개가 동시에 켜져야 한다.

### 두 감쇠 장치가 통과분보다 많이 죽이고 있다

| 장치 | 적용 | 그중 감쇠 전 ≥0.30 (= 죽은 BUY) |
|---|---|---|
| TRANSITIONAL 감쇠 | 1,228 | **45** |
| EMA 하락추세 필터 | 424 | **21** |
| 합계 | | **66** |

같은 2일간 실제 발생한 BUY 신호는 **51건**이다. **통과분보다 감쇠로 죽은 쪽이 많다.**

### EMA 필터의 구조적 결함 — 데드밴드가 없다

```java
} else if (!uptrend && buyScore > 0) {
    buyScore *= dampenFactor;   // DEFAULT_EMA_DAMPEN_FACTOR = 0.0 → 전량 0
}
```

`uptrend = emaShort > emaLong` 단순 비교라 **차이가 아무리 작아도 하락추세**로 친다.
0으로 죽은 424건 중 **192건(45%)이 EMA20/EMA50 격차 0.1% 미만**, 91건은 로그 정밀도에서 아예 동일하다
(`EMA20=244<EMA50=244`). 다만 그중 점수 ≥0.30 이었던 건 6건이라 **실손실은 2일에 6건** 규모다.

`emaFilterDampenFactor` 는 주석에 *"완전 차단 대신 비례 감쇠로 완화해 백테스트로 효과를 검증할 수 있다"* 고
적혀 있는데 **한 번도 켠 적이 없다.** 기본값 0.0 은 이 파라미터가 없던 시절과 동작이 같다.

### 결론과 우선순위

1. **주원인(88%)은 설정이 아니다.** 고정 8코인에 복합 전략 7개를 걸어두니 셋업이 거의 안 잡힌다.
   동적 멀티코인이 코인을 고정하지 않는 방식으로 이 문제를 구조적으로 피한다 — 그래서 새 14세션이 유리하다.
2. **감쇠 2종은 A/B 대상이다.** 지문이 갈라주므로 표본 오염 없이 실험 가능하다.
   `emaFilterDampenFactor` 0.0 → 0.5 는 파라미터 하나짜리 첫 실험으로 적합하다.
3. **EMA 추세 판정에 데드밴드 추가** — 코드 변경 필요. 효과는 작다(2일 6건).

> **주의**: "감쇠로 66건이 죽었다" 는 **비용**이지 **손실**이 아니다. 이 필터들이 손실 거래를 막고
> 있었을 수도 있다 — 함대 승률이 22% 인 걸 감안하면 그럴 가능성도 충분하다.
> 지금 알아낸 것은 **비용의 크기**이고, 편익은 A/B 로만 알 수 있다. 값을 먼저 바꾸지 말 것.

### 부수 발견

**PAPER** 로그 7,238건의 `strategy_log.indicators_json` 이 전부 NULL 이다.
LIVE 는 `buildIndicatorsJson` 으로 기록하지만 **DYNAMIC·PAPER 는 기록 경로 자체가 없다.**
(처음에 "전부 NULL" 이라고만 썼는데, 그 쿼리는 `session_type='PAPER'` 로 한정한 것이었다.)
지표 원값을 사후 검증할 수단이 없어 이번 분석도 `reason` 문자열 파싱으로 했다.

---

## 🟢 2026-08-19 V73 — 분석 파이프라인 보강 구현 (검토 8건 중 6건 조치, 2건 오진)

세션을 새로 시작하기 **전에** 스키마를 확정했다. 데이터가 쌓인 뒤 넣으면 그 기간이 분석에서 빠진다.

### 조치한 것

| 항목 | 내용 |
|---|---|
| **P1** | `ExitReason` enum 신설 + `position`·`paper_trading.position` 에 `exit_reason` 컬럼. 세 엔진(LIVE·DYNAMIC·PAPER)의 **모든 청산 경로**에 사유를 실었다 |
| **P2** | `DynamicTradingService.executeBuy` 에서 진입 레짐 판정 → 포지션에 기록 (그동안 0/36) |
| **P3** | `paper_trading.position` 에 `market_regime`·`invested_krw`·`session_kind` 추가 후 매수 경로에서 채운다 |
| **P5** | `kill_criteria_judgment.ruleset_hash` + `persist()` 에서 기록 |
| **P7** | 고아 테이블 2개 DROP (`paper_trading.strategy_log`, `paper_trading.trade_log` — 매핑 엔티티 없음, 0행) |
| **P8** | `execution_drift_log.session_kind` + 호출부 2곳 |

### 설계 판단 두 가지

**`ExitType` 은 이미 있었다.** `ExitRuleChecker.ExitType` 이 SL/TP 를 구분하고 있었는데
호출부가 `getReason()` 만 쓰고 **타입을 버리고 있었다.** 새로 만든 게 아니라 흐르던 정보를
보존한 것이다. `ExitReason.from(ExitType)` 이 그 다리다.

**사유는 CLOSING 전환과 같은 UPDATE 에 담았다.** 실거래는 매도가 비동기라 사유를 아는 시점
(`executeSell`)과 CLOSED 확정 시점(`reconcile`)이 다르다. 엔티티에 세팅해 `save()` 하면
네이티브 UPDATE 가 이미 바꾼 `status` 를 낡은 값(OPEN)으로 덮어써 **이중 매도 가드가 무력해진다.**
그래서 `markClosingIfOpen(id, now, reason)` 한 문장에 담았다.

**`closing_at` 은 페이퍼에 넣지 않았다** — `PaperTradingService.closePosition` 은 동기라
CLOSING 중간 상태가 없다. 넣어봐야 항상 NULL 인 컬럼이 된다.

### 가드 검증 (뮤테이션)

처음 시도한 뮤테이션이 **안 잡혔다.** PAPER 경로에서 덮어써지는 인자를 골랐기 때문인데,
그게 곧 **REAL 경로(비동기 매도)가 테스트에 안 걸려 있다**는 뜻이었다. 두 테스트를 추가해 메웠다.

| 뮤테이션 | 결과 |
|---|---|
| PAPER `FORCED_STOP` → `UNKNOWN` | 1건 실패 ✅ |
| CLOSING 전환에서 사유 기록 제거 (REAL 경로) | 1건 실패 ✅ |

- 신규 `ExitReasonRecordingTest` 9건 (SL/TP/TIME_STOP/FORCED_STOP, ExitType 매핑, 성과 집계 제외,
  레짐 저장, **REAL 경로 사유 보존**, **경합 시 먼저 발동한 사유 유지**)
- `:web-api:test` **354건 통과** (실패 0, 스킵 3). 338 → 341 → 345 → 354.

### 배포 필요

V72(매도 정산 멱등화) + V73 이 함께 올라가야 한다. `exit_reason` 은 배포 이후 청산부터
채워지므로 **세션 재시작보다 배포가 먼저**다.

---

## 🟡 2026-08-19 데이터 수집·분석 파이프라인 전면 검토 — 결함 8건

> **맥락**: 세션을 전부 지우고 새로 기록을 시작하기 직전. 데이터가 쌓인 뒤 결함을 발견하면
> **그 기간 데이터를 못 쓰게 되므로** 지금 스키마를 확정해야 한다.

### 잘 되고 있는 것 (건드리지 말 것)

| 항목 | 근거 |
|---|---|
| 신호품질 백필 | 미처리 4h **16건**, 24h **0건**. 적격 신호 7,693건 중 7,522건(97.8%) 평가 완료 |
| 진입 차단 사유 | BUY 미체결 60건 중 57건(95%)에 `blocked_reason` 기록 — 게이트별 집계 가능 |
| 일일 헬스 스냅샷 | 매일 08:30, 14행 정상 |
| 규칙 지문 스탬핑 | V71 이후 미스탬핑 0건 |

> `price_after_4h` 가 전체 로그의 8.8% 인 것은 결함이 아니다 — HOLD 는 평가 대상이 아니고,
> 분모를 BUY/SELL 로 잡으면 97.8% 다.

### P1 🔴 청산 사유가 구조화돼 있지 않다 — 가장 큰 구멍

`position` 에 `exit_reason` 컬럼이 없다. 사유는 `order.signal_reason` 자유 텍스트에만 있고,
**손익률이 문자열 안에 박혀 있어** 값이 전부 유일하다:

```
시간 초과 청산 — 보유 259시간 ≥ 24시간 (pnl -1.87%)
시간 초과 청산 — 보유 259시간 ≥ 24시간 (pnl -1.95%)
```

`GROUP BY` 가 불가능하다. 대시 문자도 `—` 와 `--` 두 종류가 섞여 있어 정규식도 취약하다.
**"손절 대 익절 대 시간초과 비율" 이라는 가장 기본적인 질문에 답할 수 없다.**

### P1-b 🔴 익절이 사실상 작동하지 않는다 (P1 로 문자열 분류해 보니 드러남)

전체 SELL 주문 **6,944건**을 사유로 분류한 결과:

| 분류 | 건수 |
|---|---|
| STOP_LOSS | **6,702** |
| 전략 신호 청산 | 186 |
| FORCED_STOP (정지/비상정지) | 45 |
| TIME_STOP | 10 |
| **TAKE_PROFIT** | **1** |

`take_profit_price` 는 종료 포지션 313건 중 260건에 **설정돼 있는데 발동은 1건**이다.
청산이 완전히 비대칭이다 — 손절은 계속 걸리고 목표가는 닿지 않는다.
60일 실적도 부합한다: **24승 85패(22%), 평균 −153.91원.**

> 주의: 이 수치는 지문 도입 이전이라 여러 규칙 세대가 섞여 있다. **방향성 근거이지 결론이 아니다.**
> 다만 "TP 1건" 은 규칙 세대와 무관한 구조적 신호다. `TP_RR_MULTIPLIER=2.0` 이
> 이 변동성에서 너무 먼 것인지 확인이 필요하다.

### P2 🔴 `position.market_regime` 이 LIVE 에서만 채워진다

`.marketRegime(...)` 을 포지션에 세팅하는 곳은 `LiveTradingService:1268` 하나뿐이다.
`DynamicTradingService` 는 세팅하지 않고, `PaperPositionEntity` 에는 **컬럼 자체가 없다.**

| session_kind | 종료 포지션 | 레짐 기록 |
|---|---|---|
| LIVE | 277 | 174 |
| DYNAMIC | 30 | **0** |
| DYN_PAPER | 6 | **0** |

**지금 돌리는 엔진에는 레짐 귀속이 전혀 없다.** "이 전략은 횡보장에서만 잘 되는가" 를 물을 수 없다.

### P3 🟠 `paper_trading.position` 이 `public.position` 보다 4컬럼 부족

`market_regime`, `invested_krw`, `session_kind`, `closing_at` 이 없다.
페이퍼 함대와 동적 세션 성과를 **같은 쿼리로 볼 수 없다.**
`closing_at` 부재는 분석이 아니라 정합성 문제다 — 페이퍼에는 매도 중복 제출을 막는
CLOSING 가드가 없다 (`PaperTradingService` 에 `"CLOSING"` 문자열이 없음).

### P4 ~~🟠 `trade_log` 가 실주문 경로에서만 쓰인다~~ → **오진, 조치 불필요**

`trade_log` 는 체결 기록이 아니라 **주문 상태전이 감사 로그**다
(`order_id`, `event_type`, `old_state` → `new_state`). 페이퍼 엔진은 주문을 처음부터
`FILLED` 로 만들고 거래소 왕복이 없어 전이 자체가 존재하지 않는다 — 기록할 것이 없다.
합성 행을 넣으면 데이터가 아니라 잡음이 된다.

모의 체결 데이터는 이미 `paper_trading.order` / `public.order` 에 `state='FILLED'` 로
전부 남아 있다. **분석 기반이 없다는 진단은 틀렸다.**

### P5 🟠 `kill_criteria_judgment` 에 `ruleset_hash` 가 없다

판정은 `engine/strategy@timeframe#rulesetHash` 그룹 단위로 내리는데 기록에는 지문이 없다.
나중에 "어느 규칙이 폐기됐나" 를 역참조할 수 없다.
**아직 0행이라 지금 추가하면 비용이 0이다.**

### P6 ~~🟡 SELL 신호에 차단 사유가 없다~~ → **오진, 조치 불필요**

처음에 "`blocked_reason` 이 없다" 고 썼는데, 그건 `blocked_reason` 을 **BUY 에 대해서만
조회하고 SELL 은 확인하지 않은 채** 내린 판단이었다. 실제로는 **2,489/2,491 = 99.9%** 기록돼 있다:
`청산할 포지션 없음`(1,850), `SCANNING — 보유 포지션 없음`(569).

남는 것은 데이터 결함이 아니라 **집계 규약** 문제다. 14일간 미체결 SELL 2,486건이 의미 있는
BUY 의사결정 60건의 41배라, BUY 124건과 SELL 2,336건을 한 테이블에서 단순 평균하면 오독한다.
데이터에 판별자가 이미 있으므로 **쿼리에서 나누면 된다 — 코드 변경 대상이 아니다.**

### P7 🟡 죽은 테이블 3개

`paper_trading.strategy_log`(0행), `paper_trading.trade_log`(0행), `regime_change_log`(0행).
페이퍼 함대는 로그를 `public.strategy_log` 에 `session_type='PAPER'` 로 쓰고 포지션만
`paper_trading` 에 쓴다 — **스키마가 갈라져 있다.** 빈 테이블은 나중에 오해를 부른다.

### P8 🟡 `execution_drift_log` 에 `session_kind` 가 없다

`dynamic_session` 과 `live_trading_session` 은 별도 시퀀스라 `session_id` 만으로는 모호하다.
현재 55행, LIVE 만.

### 이미 데이터가 말하는 것 (파이프라인 없이도 보이는 것)

- **BUY 신호 4h 평균 +0.076%, 24h 평균 −0.499%, 적중 51/124(41%).**
  4시간까지는 미세하게 맞고 24시간에는 잃는다. `max_hold_hours=24` 는 **감쇠 구간을 통과해
  보유하도록** 설정돼 있다. 보유시간 상한을 4~8시간으로 낮춘 A/B 가 유력한 첫 실험이다.
- **LIVE 는 14일간 BUY 신호 1건.** 사실상 정지 상태다.

---

## 🔴 2026-08-19 P0 — 동적 세션 정지 실패 루프로 매도대금 21회 중복 지급

> 세션 재시작 중 "세션 49 가 정지도 삭제도 안 된다" 는 신고에서 출발했다.
> 정지가 **실패하는** 게 문제가 아니라, **실패할 때마다 돈을 찍고 있었다.**
> `available_krw` 10,000 → **174,752.22** (초기자본의 17배).

### 메커니즘

`stopSession()` 이 세션을 version N 으로 읽은 뒤:

1. `closeOpenPositions()` → `executePaperSell()` → `finalizeDynamicSell()`
   → `balanceUpdater.apply()` 로 매도대금 **선커밋** (N→N+1)
2. 이어서 `transitionToScanning()` → 또 `balanceUpdater.apply()` **선커밋** (N+1→N+2)
3. `dynamicSessionRepo.save(session)` — 1번 이전에 읽은 **낡은 엔티티**
   → `ObjectOptimisticLockingFailureException` → **바깥 트랜잭션만** 롤백

`DynamicSessionBalanceUpdater` 는 `PROPAGATION_REQUIRES_NEW` + `saveAndFlush` 라
롤백에 딸려가지 않는다. **돈은 남고 포지션 청산은 사라진다.** 포지션이 OPEN 으로
되돌아오니 다음 정지 시도에서 또 지급된다 — 결정론적 무한 증식 루프다.

삭제 불가는 2차 효과다: `deleteSession()` 은 RUNNING 을 거부하는데 정지가 100% 실패하니
RUNNING 을 벗어날 수 없다. 비상정지도 같은 구조라 탈출구가 되지 못한다.

### 데이터가 그대로 증언한다

| 증거 | 값 | 의미 |
|---|---|---|
| 포지션 2458 | `status=OPEN`, `closing_at=NULL` | 청산 처리가 전부 롤백 |
| `PAPER-DYNAMIC-SELL-2458` 주문 | **없음** | 같은 롤백에 휩쓸림 |
| `PAPER-DYNAMIC-SELL-2394` (08-18 정상 청산) | 있음 | 정상 경로 대조군 |
| `available_krw` | 1,966 → 174,752 (14:03→14:10) | +172,786 = 8,038 × **약 21회** |
| `scan_state`/`current_position_id` | `SCANNING`/`NULL` | `transitionToScanning` 선커밋만 생존 |

### 수정 (B) — `DynamicTradingService`

`stopSession()`·`emergencyStop()` 의 상태 변경을 `dynamicSessionRepo.save(낡은 엔티티)` 에서
**`balanceUpdater.apply()`(재조회 + 낙관적 락 재시도)** 로 바꿨다.

**가드 검증(뮤테이션)**: 수정을 되돌리고 신규 테스트를 돌려 3건 전부
`ObjectOptimisticLockingFailureException` 으로 깨지는 것을 확인했다 — 운영에서 난 것과 같은 예외다.
수정 복구 후 전부 통과.

- 신규 `DynamicStopOptimisticLockTest` 3건 — 정지 성공+1회만 지급 / 재정지 불가 / 비상정지 동일 경로
- `:web-api:test` **341건 통과** (실패 0, 스킵 3). 338 → 341.
- 매수측 대칭 사고는 이미 `DynamicBalanceLeakTest`(2026-08-03)가 잠그고 있다. 이번은 **매도측**.

### 정리 (A) — 세션 49 강제 종료

API 경로(정지/비상정지/삭제)가 전부 같은 버그로 막혀 SQL 이 유일한 수단이었다.
`scratchpad/fix49.sql`: 세션을 먼저 `DELETED` 로 내려 틱을 끊고, 그다음 포지션 2458 을
`CLOSED` 로 정리한다(앱의 orphan 정리 정책과 동일하게 `status`+`closed_at` 만).

> `available_krw = 174,752.22` 는 **일부러 보존한다** — 덮어쓰면 버그의 증거가 사라진다.
> 세션은 폐기 대상이라 수치를 살릴 이유도 없다.

### 근본 수정 (C) — 매도 정산 멱등화 (V72)

B 는 결정론적 트리거 하나를 없앴을 뿐, 틱 경로(`executeSell`)에서 다른 이유로 롤백이 나면
같은 중복 지급이 재발할 수 있었다. 그래서 구조를 고쳤다.

**핵심**: "이미 정산했다" 는 표식을 **대금 반영과 같은 트랜잭션**에 쓴다. 둘이 함께 커밋되므로
표식은 대금과 **정확히 같은 수명**을 갖는다 — 대금이 남으면 표식도 남고, 표식이 롤백되면
대금도 롤백된다. 이 동일 수명이 멱등성의 근거다. (기존 `pos.getStatus() == CLOSED` 가드는
포지션 저장이 바깥 트랜잭션에 있어 롤백과 함께 사라지므로 무력했다.)

- **V72** `dynamic_sell_settlement` — 매도 1건당 1행. 감사 로그를 겸한다.
- `DynamicSessionBalanceUpdater.applySettlementOnce()` — 표식 INSERT + 잔고 변경을 한
  `REQUIRES_NEW` 트랜잭션에 묶는다. 반영했으면 `true`, 이미 정산돼 있으면 `false`.
  동시 진입은 PK 충돌(`DataIntegrityViolationException`)로 걸리고, 그 트랜잭션만 롤백되므로
  대금은 반영되지 않는다.
- `finalizeDynamicSell` — 대금은 한 번만, **포지션 CLOSED 확정은 성공할 때까지** 재시도한다.
  이전 시도에서 청산이 롤백됐을 수 있기 때문이다. 텔레그램 알림도 실제 반영 시에만 나간다.
- 키는 **`exchange_order_id`** 다. 페이퍼는 `"PAPER-DYNAMIC-SELL-{positionId}"` 로 포지션에서
  결정되므로 롤백 후 재시도해도 같은 값이 나온다. `order.id` 를 쓰면 주문 행이 롤백에 휩쓸려
  사라진 뒤 재시도마다 새 시퀀스 값이 생겨 **막지 못한다** — 이 선택이 수정의 급소다.
- 멱등 키가 없으면 정산을 **거부**한다. 보장 못 하는 채로 돈을 움직이지 않는다.
- 지표 `dynamic.session.sell.settlement.duplicate` — 0이 아니면 롤백 재시도가 일어나고 있다는 뜻.

**가드 검증(뮤테이션)**: `applySettlementOnce` 를 수정 전 동작(무조건 반영)으로 되돌리니
7건 중 **4건이 깨졌다**. 나머지 3건은 B 가 지키는 항목이라 통과한 것이 맞다.

- `DynamicStopOptimisticLockTest` 7건 — B 3건 + C 4건
  (롤백 후 1회만 / **21회 재시도해도 1회분만** / 키 없으면 거부 / 페이퍼 키가 포지션에서 결정)
- `:web-api:test` **345건 통과** (실패 0, 스킵 3). 338 → 341 → 345.

---

## 🟡 2026-08-19 동적 멀티코인 PAPER 세션 진단 — 재시작 결정

> **질문**: "수정하면서 수익률이나 데이터가 꼬인 것 같다."
> **답**: 산술은 안 꼬였다. **의미가 오염됐다.**

### 자본 정합성은 정상

RUNNING 4개 세션 전부 검산이 맞는다 — 재계산 오류나 유실은 없다.

| 세션 | 전략 | 가용 | 총자산 | 수익률 | 검산 |
|---|---|---|---|---|---|
| 47 | MTF_CONFIRMED | 10,000 | 10,000 | 0.00% | 거래 0건 |
| 49 | MEANREV_BB | 1,966 | 10,012 | +0.12% | 1,966 + 7,866(투입) = 10,000 − 167.88 |
| 51 | ICHIMOKU_V2 | 9,894 | 9,894 | −1.06% | 10,000 − 106.13 |
| 53 | PULLBACK_MTF | 9,444 | 9,444 | −5.56% | 10,000 − 555.60 |

### 오염 근거 1 — `max_hold_hours` 가 안 걸리고 있었다

설정은 24시간인데 실제 보유시간이 이를 크게 넘긴다:

| 포지션 | 세션 | 보유 | 청산 시각 | 손익 |
|---|---|---|---|---|
| 2394 KRW-XRP | 49 | **259.9h** (10.8일) | 08-18 08:55:20 | −167.88 |
| 2425 KRW-LINK | 51 | **54.9h** | 08-18 08:55:20 | −106.13 |
| 2412 KRW-SOL | 53 | **66.0h** | 08-15 00:00 | −96.52 |

2394 와 2425 는 **같은 초에** 청산됐다 — 전략 판단이 아니라 08-18 일괄 정리다.
청산가가 시장이 아니라 배치 실행 시각으로 정해졌으므로 **이 손익은 전략 성과가 아니라 버그 산물**이다.
종료 6건 중 3건이 여기 해당한다.

### 오염 근거 2 — 규칙이 4번 바뀌는 동안 쌓였고 지문이 없다

08-07 진입 게이트 회귀 → 08-18 `maxHoldHours` 0→24 + 매도 후처리 롤백 수정 → 08-19 V71.
그 사이 거래 7건은 전부 `ruleset_hash IS NULL` 이다. **사후에 규칙별로 나눌 수단이 없다** —
지문 체계를 만든 이유가 정확히 이건데, 이 데이터는 그 이전 것이다.

### 오염 근거 3 — 표본이 판단 불가

12일간 4세션 합계 **거래 7건**, 세션 47 은 **0건**(주문 이력도 0). 종료 6건 전부 손실(합계 −829.60).

### 결정: 재시작 (사용자가 UI 에서 직접 수행)

V71 이 오늘 12:38 에 배포됐으므로 지금 재시작하면 **이후 모든 거래에 지문이 붙는다.**

- 정지 대상: 47 / 49 / 51 / 53. 세션 49 는 KRW-CAP 포지션(2458, 평가익 +180)이 열려 있어 정지 시 청산된다.
- 신규 세션은 **설정을 동일하게** 둔다 (전부 기본값). 바꾸면 새 표본이 "지문 도입" 때문에 갈린 건지
  "설정 변경" 때문인지 구분이 안 된다.
- 기존 세션은 **삭제하지 않고 STOPPED 로만** 둔다 — `DELETED` 는 목록에서 빠져 지문 이전 구간을 되짚을 수 없다.

> **API 로 자동화하지 않은 이유**: 운영 서버는 DB 포트 8432 만 외부 개방돼 있고 백엔드 8080 은 닫혀 있다
> (8080 은 공유기 관리 페이지가 응답). SQL 직접 변경은 하지 않았다 — `stopSession()` 은
> `closeOpenPositions()` 로 거래 서비스를 통해 청산하므로, UPDATE 로 상태만 바꾸면 세션 49 의
> 열린 포지션이 SL/TP 평가를 못 받는 orphan 이 된다.

### 남는 문제 — 재시작이 고쳐주지 않는 것

**세션 47(`COMPOSITE_MTF_CONFIRMED@H1`)은 12일간 거래 0건.** 같은 설정으로 재시작하면 같은 결과가
나올 가능성이 높다. 이건 표본 오염이 아니라 **신호를 안 내는 문제**다. 내일 09:00 판정에서
`NO_SIGNAL` WARN 으로 잡히면 그때 존속 여부를 판단한다.

---

## 🟢 2026-08-19 14:00 V71 배포 후속 확인 — 4개 항목 중 3개 실측 통과, 1개는 내일 09:00

배포(12:38 KST) 90분 뒤 운영 DB를 조회해 스탬핑이 실제로 붙는지 확인했다. **정오 확인 때 미검증으로 남겼던 포지션 스탬핑이 통과했다.**

| 확인 항목 | 결과 |
|---|---|
| `paper_trading.position.ruleset_hash` | ✅ 배포 후 생성 2건 **전부 스탬핑** (2466 13:30, 2467 13:45 → `ffa830313372`) |
| `public.position.ruleset_hash` | ⏸ 0/314 — 배포 후 생성된 포지션이 **없어서** 실측 불가 (마지막 생성 10:10) |
| `ruleset_snapshot` | ✅ 3행 유지, 예상 밖 지문 없음 |
| `strategy_log` 미스탬핑 | ✅ 12:39 이후 `ruleset_hash IS NULL` **0행** (DYN_PAPER 12 + PAPER 168 전부 스탬핑) |
| `kill_criteria_judgment` | ⏸ 누적 0행 — 결함 아님, 아래 참조 |

### 포지션 스탬핑 컷오버

정오 확인 때 `0/58` 이었던 것은 실패가 아니라 **표본이 없었던 것**이 맞았다. 12:30 행이 마지막 미스탬핑,
13:30 부터 전부 스탬핑 — 배포 시각을 경계로 깨끗하게 갈린다.

`public.position` 은 LIVE/DYNAMIC 이 실매매 포지션을 열지 않는 상태라 아직 표본이 없다. 코드 경로는
`paper_trading.position` 과 동일하지만 **실측은 미완**으로 남긴다.

### `kill_criteria_judgment` 이 비어 있는 이유

판정은 `@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")` 로 도는데
(`StrategyKillCriteriaService.check()`), 테이블을 만드는 **V70 이 오늘 12:37 에 배포**됐다.
오늘 09:00 판정은 테이블이 없는 DB 에서 돌았다. 게다가 `persist()` 는 KEEP 이 아닌 판정만 저장하므로
전부 KEEP 이면 아무 행도 안 남는다. **첫 실측은 2026-08-20 09:00.**

> **내일 아침 확인할 것 — NO_SIGNAL 수정의 실증**
> 지문 도입으로 지문별 표본(`tradeCount`)이 전 세션 0 으로 리셋됐다. `tradeCountAllRulesets` 를
> 추가하지 않았다면 내일 09:00 에 다수 세션이 `NO_SIGNAL` WARN 으로 떴을 것이다.
> **안 뜨면 그 수정이 검증된 것이고, 뜨면 수정이 안 먹은 것이다.** 이 판정은 한 번뿐이라 놓치면 안 된다.

---

## 🟢 2026-08-19 V71 배포 전 전면 검토 — 지문 체계의 결함 6건 수정

> **맥락**: 규칙 지문(V71) 구현을 "다 됐다" 고 두 번 보고했다가 두 번 다 빠진 게 나왔다.
> 세 번째로 같은 실수를 하지 않으려고 배포 전에 코드를 다시 훑었고, **6건이 더 나왔다.**
> 그중 하나는 배포 블로커, 둘은 "동작하는 것처럼 보이지만 드리프트를 못 잡는" 유형이었다.

### 1. 🔴 `@Transactional(REQUIRES_NEW)` 가 애초에 걸리지 않고 있었다 (배포 블로커)

`RulesetRegistry.register()` 의 유일한 호출자가 같은 인스턴스의 `hashFor()` 였다.
**자기호출은 Spring 프록시를 타지 않아 애노테이션이 조용히 무시된다.** 주석이 약속한 두 가지가
정확히 반대로 동작하고 있었다:

| 주석의 약속 | 실제 |
|---|---|
| 호출부가 롤백돼도 스냅샷은 남는다 | 같이 롤백된다. 그런데 `known` 은 메모리라 롤백되지 않아 재기동 전까지 재시도하지 않는다 → **원문 없는 지문**이 남는다 (이 클래스가 막으려던 바로 그 상태) |
| 등록 실패가 매매를 막지 않는다 | 반대. PostgreSQL 은 트랜잭션 내 INSERT 실패 시 트랜잭션을 abort 로 만든다. `catch` 로 삼켜도 **바깥 매수 트랜잭션이 커밋에서 터진다** |

→ `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` 로 교체. 프록시를 거치지 않으므로 자기호출에서도
확실히 분리된다. 실패한 지문은 `known` 에 넣지 **않아** 다음 거래에서 재시도한다.

### 2. 🟠 지문이 엔진이 **실제로 쓰는** 청산 규칙을 담고 있지 않았다

`exit.*` 16키는 `ExitRuleConfig`(DB 설정)에서 나오는데:

- `getExitRuleConfig()` 가 DB에서 채우는 필드는 **6개뿐**, 나머지 10개는 코드 기본값
- 세 엔진이 SL/TP 를 실제로 계산하는 곳은 **`ExitRuleCalculator` 의 static 상수 5개**
  (`SL_ATR_PERIOD` 14 / `SL_ATR_MULTIPLIER` 1.5 / `SL_PCT_MAX` 8.0 / `TP_RR_MULTIPLIER` 2.0 / `TP_PCT_MAX` 8.0)
- **`DynamicTradingService` 는 `ExitRuleConfig` 를 참조조차 하지 않는다** — 이 상수들만 쓴다

즉 `SL_ATR_MULTIPLIER` 를 1.5 → 2.0 으로 바꿔 손절폭이 33% 넓어져도 지문이 그대로였다.
08-07 워치리스트 회귀와 정확히 같은 유형이다.

→ `ExitRuleCalculator.behaviorParams()` 신설, 지문에 `exitcalc.*` 5키 추가.

### 3. 🟠 가드가 프로덕션 지문을 지키지 않았다

앞서 "눈 대조를 그만뒀다" 며 추가한 `requiredKeysArePresent` 는 **테스트가 직접 만든 빌더**를
검사하고 있었다. `RulesetRegistry.base()` 를 부르지 않으므로 거기서 `gate.*` 블록 10줄을 통째로
지워도 깨지지 않았다. 가드를 만든 목적 자체를 달성하지 못하고 있었다.

→ `RulesetRegistryCompositionTest` 신설 — 진짜 `RulesetRegistry` 빈이 남긴
`ruleset_snapshot.params_text` 를 읽어 필수 키 26종을 검증한다. 값을 바꿨을 때 해시가 실제로
갈리는지도 확인한다(`gate.cooldownMinutes`, `strategy.params`, 엔진).

**변이 테스트로 가드가 진짜 깨지는지 확인했다** — `gate.cooldownMinutes` 한 줄과
`behaviorParams()` 의 `slAtrMultiplier` 한 줄을 지우니 4개 테스트가 실패했다. 되돌린 뒤 전부 그린.

### 4. 🟡 배포 직후 NO_SIGNAL 오경보 (코드로 차단)

기존 거래는 전부 `ruleset_hash = NULL`, 세션은 새 해시를 받으므로 집계 키가 전부 미스 →
**지문별 거래 수가 0으로 떨어진다.** 이건 의도된 동작("규칙 미상은 표본에서 제외")이지만,
`NO_SIGNAL`(30일+거래 5건 미만)이 이 값을 보면 **멀쩡히 거래 중인 세션을 "자본이 놀고 있다"**
고 경보한다.

→ `SessionStats.tradeCountAllRulesets` 추가. **"우위가 있는가" 는 같은 규칙끼리만 물어야 하지만,
"거래를 하기는 하는가" 는 규칙과 무관한 질문**이라 판정 입력을 나눴다.
회귀 테스트 2건(`rulesetSplitDoesNotFakeNoSignal`, `genuinelyIdleSessionStillWarns`).

### 5. 🟡 `strategy.params` 만 수치 정규화가 빠져 있었다

빌더의 `put(String, BigDecimal)` 은 `stripTrailingZeros()` 로 `0.30 == 0.3` 을 보장하는데,
전략 파라미터는 `TreeMap.toString()` 원본이라 **`14` 와 `14.0` 이 다른 지문**이 됐다.
JSONB 역직렬화가 Integer/Double 중 무엇을 주느냐에 따라 규칙이 안 바뀌었는데 표본이 쪼개진다.

→ `normalize()` 재귀 함수로 수치·중첩 맵·컬렉션 정규화. 리스트는 순서가 의미를 가지므로 정렬하지 않는다.

### 6. 🟡 전략 로그 1건당 `risk_config` SELECT 2회

`hashFor()` → `base()` 에서 `getExitRiskConfig()` 와 `getRiskConfig()` 를 각각 불러 같은 행을 두 번 읽었다.
`known` 캐시는 해시 계산이 **끝난 뒤에** 확인하므로 SELECT 를 막지 못한다.
전략 로그는 틱마다 코인마다 쓰이므로 페이퍼 112세션 기준 틱당 224회였다.

→ `RiskManagementService.toExitRuleConfig(RiskConfigEntity)` 오버로드 분리, 설정을 1회만 읽는다.
`known` 캐시 확인도 트랜잭션을 열기 **전으로** 옮겼다.

### 정정

앞선 보고에서 "`gate.*` 11키" 라고 했으나 **10개**다.

### 지문 최종 구성 (38키)

| 그룹 | 키 수 | 출처 |
|---|---|---|
| `engine` + `engine.*` | 3 | 엔진명 · `CANDLE_LOOKBACK` · `WATCHLIST_ALLOWED_SPREAD_TICKS` |
| `exitcalc.*` | 5 | **`ExitRuleCalculator` 상수 — 실제 SL/TP 계산** (신규) |
| `exit.*` | 16 | `ExitRuleConfig` (DB 6 + 코드 기본값 10) |
| `gate.*` | 10 | `risk_config` 진입 게이트 |
| `session.*` | 4 | 타임프레임 · 보유시간 · 손절 · 투자비율 |
| `scan.*` | 4 | 워치리스트 필터 (DYNAMIC) |
| `strategy.params` | 1 | 전략 튜닝값 (LIVE) |
| `paper.slippagePct` | 1 | 체결 가정 (PAPER) |

### 검증

`:web-api:test` **338건 그린**(실패 0, 스킵 3 — 330 → +8), `:core-engine:test` 그린.

### 배포 후 확인할 것

1. `ruleset_snapshot` 에 행이 생기는가 (첫 거래·첫 전략로그 시점)
2. 신규 `position.ruleset_hash` / `paper_trading.position.ruleset_hash` / `strategy_log.ruleset_hash` 가 채워지는가
3. `[Ruleset] 지문 원문 저장 실패` WARN 이 로그에 없는가
4. 09:00 판정에서 NO_SIGNAL 오경보가 없는가

⚠️ **`kill-criteria.auto-stop` 은 계속 `false` 로 둔다.** 지문별 표본이 다시 쌓이기 전까지
엣지 판정(B)은 표본 미달로 발동하지 않는 것이 정상이다.

---

## 🔴 2026-08-18 운영 DB 세션 분석 (08-07 재기동 후 11일차)

> 운영 DB(`yhpapa.iptime.org:8432`) 직접 조회. 기준시각 2026-08-18 08:30 KST.
> 대상: LIVE 2세션(198·199) + 동적 8세션(46~53, REAL/PAPER 4쌍). 전부 H1, 세션당 초기자본 10,000원.

### 성적 요약 (11일)

| 세션 | 전략 | 모드 | 총자산 | 손익 | 거래 | 승 |
|---|---|---|---|---|---|---|
| 46 / 47 | MTF_CONFIRMED | REAL / PAPER | 10,000 / 10,000 | 0.00% / 0.00% | 0 / 0 | — |
| 48 / 49 | MEANREV_BB | REAL / PAPER | 9,850 / 9,844 | −1.50% / −1.56% | 0(보유1) | — |
| 50 / 51 | CMI_V2 | REAL / PAPER | 10,000 / 9,906 | 0.00% / −0.94% | 0 / 0(보유1) | — |
| 52 / 53 | PULLBACK_MTF | REAL / PAPER | 9,623 / 9,709 | −3.77% / −2.91% | 4 / 3 | **0 / 0** |
| 198 | MEANREV_BB (XRP) | LIVE | 9,850 | −1.50% | 0(보유1) | — |
| 199 | MTF_BTC_STRICT (BTC) | LIVE | 10,000 | 0.00% | 0 | — |

동적 REAL 합계 39,467.73원(−1.33%), 동적 PAPER 합계 39,452.60원(−1.37%).
**11일간 청산된 8거래 전부 손실**(−1.07 ~ −1.47%). 큰 손실은 없고 −1% 내외 손실만 반복 — 마찰비용형 출혈.

### 발견 (심각도순)

1. **XRP 3중 포지션 259시간(10.8일) 고착** — 2026-08-07 13:00 같은 분에 LIVE 198(pos 2396)·DYNAMIC 48(pos 2395)·DYN_PAPER 49(pos 2394)가 각각 8,000원씩 KRW-XRP 매수. 실자금만 16,000원. SL 1,368.95 / TP 1,556.28 어느 쪽도 미도달, 현재 −1.87~−2.02%. 세 세션 모두 `max_hold_hours=0`(비활성)이라 **V62가 막으려던 저변동 고착이 그대로 재현**. 헬스체크는 08-09부터 매일 `stuck_position_count=2`로 감지만 하고 조치 없음. 세션당 가용 KRW가 10,000 → 2,000으로 11일째 묶여 있다.
2. **동일코인 노출 상한이 LIVE↔DYNAMIC 사이에 적용되지 않음** — 상한 1건이라는 차단 로그가 존재하는데도 LIVE 198과 DYNAMIC 48이 같은 코인을 같은 시각에 잡았다. 실자금 기준 XRP 편중 16,000원 = 두 실거래 세션 합산 자본의 80%.
3. **BLACK_SWAN_GUARD 오차단** — 세션 46의 11일간 유일한 BUY(KRW-PIEVERSE, 08-08 20:00, confidence 0.44)가 "1시간 내 −11.17%" 가드로 차단. 사후수익률 **4h +4.81% / 24h +2.13%** — 11일 중 유일하게 돈이 됐을 신호였다. 이후 진입가 가드가 720분 추가 차단. 저유동 신규코인에 −8%/1h 임계가 과도한지 재검토 필요.
4. **"본전 근처" SELL 차단이 손실을 확정시킨다** — 52/53에 `본전 근처 pnl=−0.09~−0.93%` 차단이 40건+ 누적. 그 상태로 버티다 최종 −1.07~−1.47%에 청산. 청산된 8거래 전부 이 밴드에 있었다. 수수료 왕복 + 슬리피지를 감안하면 이 게이트가 순손실 요인일 가능성이 높다 — A/B 검증 대상.
5. **자본 절반이 11일간 유휴** — 46·47·50·199는 거래 0건(199는 263회 평가에 BUY/SELL 신호 0). 배치된 59,850원 중 40,000원이 아무 일도 하지 않았다.
6. **REAL/PAPER 정렬은 대체로 성공, 단 50↔51 이탈** — 52↔53(−3.77% vs −2.91%), 48↔49(−1.50% vs −1.56%)는 근접. 반면 50(REAL)은 BUY 0건인데 51(PAPER)은 08-16 02:00 LINK 진입. 신호 자체가 갈렸다 — 워치리스트/평가시점 차이 의심, 로그 확인 필요.
7. **신호 기대값 여전히 음수** — 52/53 신호 161·152건, 평균 confidence 0.658인데 사후수익률 4h −0.18% / 24h −0.40%. confidence가 높다고 수익이 나지 않는다(가장 좋았던 신호는 confidence 0.44).
8. **`order_sequence_gap` 증가** — 08-16 0 → 08-17 12 → 08-18 16. 잔고 불일치·유령 포지션은 0이라 즉시 위험은 아니지만 원인 확인 필요.

### 정상 확인된 것

`balance_mismatch_count=0`, `ghost_position_count=0` 11일 연속. 서킷브레이커 46~53 전부 미발동. LIVE 세션은 H1로 재생성 완료(보류 항목 6번 해소).

---

## 🟢 2026-08-18 조치 — time stop 기본 활성(V68) + 고착 4건 청산 + 본전 게이트 판정

### 1. `max_hold_hours` 기본값 0 → 24 (V63 되돌리기 완료)

V63이 문서로 남겨둔 되돌리기 절차를 그대로 수행했다. 껐던 사유(매도 후처리 롤백 P0)는 08-03에
해소됐고, `ghost_position_count`가 11일 연속 0으로 재발 징후도 없었다.

| 파일 | 변경 |
|---|---|
| [`DynamicSessionEntity`](../web-api/src/main/java/com/cryptoautotrader/api/entity/DynamicSessionEntity.java) | `DEFAULT_MAX_HOLD_HOURS` 0 → **24**, 스테일 주석(07-31) 현행화 |
| [`LiveTradingSessionEntity`](../web-api/src/main/java/com/cryptoautotrader/api/entity/LiveTradingSessionEntity.java) | `DEFAULT_MAX_HOLD_HOURS` 신설(동적 값 참조) + `prePersist` 폴백 |
| [`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) | `createSession` 하드코딩 0 → 상수 |
| [`PaperTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java) / [`VirtualBalanceEntity`](../web-api/src/main/java/com/cryptoautotrader/api/entity/paper/VirtualBalanceEntity.java) | NULL 폴백 제거 — 페이퍼만 time stop 없이 도는 비대칭 차단 |
| [`V68`](../web-api/src/main/resources/db/migration/V68__default_max_hold_hours_on.sql) | 3개 테이블 `ALTER COLUMN ... SET DEFAULT 24` + COMMENT. **기존 행은 건드리지 않음**(V63 정책 준수) |
| `schema-h2.sql` | 동일 기본값 동기화 |
| 테스트 3종 | "기본값 0" 을 못박던 assertion 갱신 + `maxHoldHours=0` 명시 옵트아웃 경로 테스트 신설. `:web-api:test` 255건 그린 |

**24시간 근거**: 08-07~08-18 실측 청산 8건의 보유시간 중앙값 16시간(초과 2건은 66h/70h, 둘 다 손실 마감).
V62가 처음 의도했던 값과도 같다.

### 2. 운영 RUNNING 10세션 소급 적용 → 고착 4건 자동 청산

마이그레이션은 기존 행을 건드리지 않으므로 운영자 UPDATE로 별도 적용(46~53, 198, 199 = `max_hold_hours` 0 → 24).
`tick()`이 매 60초 세션을 DB에서 다시 읽으므로 재배포 없이 다음 틱에 발동했고, **정규 매도 경로**로
청산됐다(DB에서 포지션을 직접 CLOSED로 바꾸는 방식은 거래소와 어긋나므로 쓰지 않았다).

| 포지션 | 세션 | 코인 | 보유 | 실현손익 | 청산시각(KST) |
|---|---|---|---|---|---|
| 2396 | LIVE 198 | XRP | 259.9h | −154원 (−1.92%) | 08:56:54 |
| 2395 | DYNAMIC 48 | XRP | 259.9h | −154원 (−1.92%) | 08:55:29 |
| 2394 | DYN_PAPER 49 | XRP | 259.9h | −168원 (−2.10%) | 08:55:20 |
| 2425 | DYN_PAPER 51 | LINK | 54.9h | −106원 (−1.33%) | 08:55:20 |

11일간 잠겨 있던 자본이 풀렸다 — 세션 48·198의 가용 KRW가 각각 2,000 → 9,846원. 전체 OPEN 포지션 0건.

### 3. 🔴 새로 드러난 것 — LIVE 중복 SELL (미해결)

LIVE 198이 **같은 포지션에 SELL을 두 번 제출**했다. 두 번째(주문 8725)는 거래소가
`insufficient_funds_ask`로 거절했다.

```
08:55:54  주문 8724 생성·제출  → 08:55:59 FILLED
08:56:54  주문 8725 생성(정확히 다음 틱) → HTTP 400 거절
08:56:54  position 2396 closing_at 기록 → CLOSED
```

[`executeSessionSell`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L1316)은
주문 제출 **전에** 포지션을 CLOSING으로 표시하는데, `closing_at`이 첫 시도(08:55:54)가 아니라
두 번째 시도 시각으로 찍혔다 — **첫 틱의 CLOSING 쓰기가 커밋되지 않았다**는 뜻이다. 주문은 이미
비동기로 나간 뒤라 포지션만 OPEN으로 남았고, 60초 뒤 같은 time stop이 다시 발동했다.
07-31 세션 38 RLUSD 사고(V63이 기본값을 0으로 내린 바로 그 사유)와 동일한 시그니처다.

**다만 그때만큼 나쁘지는 않다** — 07-31은 86분간 4회 실패 루프였고, 오늘은 1회 중복 후
`reconcileClosingPositions`가 정상 종료시켰다. 08-03 수정이 "폭주 루프 → 1틱 중복"까지는 줄인 셈이다.
DYNAMIC 48·PAPER 2종은 재현되지 않았다(LIVE 경로 한정).

**결과 자체는 정합** — 포지션 CLOSED, 실현손익 정확, KRW 복원 정상, `ghost_position_count=0`, OPEN 0건.

**⚠️ 남은 위험**: LIVE 198과 DYNAMIC 48이 같은 업비트 계정에서 동시에 XRP를 들고 있었다. 오늘은
둘 다 이미 팔린 뒤라 거절됐지만, 타이밍이 어긋났다면 **LIVE의 중복 매도가 DYNAMIC 포지션의 코인을
팔았을 수 있다.** LIVE 매도의 틱 간 멱등성 확보가 필요하다(다음 우선 과제).

### 4. 본전 청산 게이트 — 운영 데이터로 판정: **손해**

[`SignalExitGateAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/SignalExitGateAbBacktestRunner.java)를
BTC/ETH/SOL/XRP H1로 돌렸으나 **게이트 ON/OFF가 8개 조합 전부 완전히 동일**했다 — 이 하네스에서는
전략 SELL 경로가 아예 안 타고 전부 SL/TP로 청산된다. 즉 07-02 L-2가 "영향 없음"으로 판정했던 근거는
게이트가 무해해서가 아니라 **측정되지 않았기 때문**이다. 이 러너는 게이트 검증에 쓸 수 없다.

대신 운영 데이터로 반사실 비교를 했다 — 청산된 포지션마다 "게이트가 막은 첫 SELL 신호 시점의 pnl"과
"실제 청산 pnl"을 대조:

| 포지션 | 코인 | 게이트가 막은 첫 SELL | 실제 청산 | 게이트 비용 |
|---|---|---|---|---|
| 2404 | SOL | −0.428% | −1.225% | 0.797%p |
| 2405 | SOL | −0.371% | −1.070% | 0.699%p |
| 2412 | SOL | −0.430% | −1.230% | 0.800%p |
| 2413 | SOL | −0.280% | −1.170% | 0.890%p |
| **평균** | | **−0.377%** | **−1.174%** | **0.797%p** |

**4건 전부 게이트가 손해였다.** 기전도 구조적으로 설명된다 — `allowsSignalExit`는
`minPnlPctForSignalExit=+0.30%` 미만이면 SELL을 막고, `lossEscapeThresholdPct=−1.00%` 아래로
떨어져야 다시 풀어준다. 즉 **−1.00% ~ +0.30% 구간에 갇힌 포지션은 −1%를 뚫어야만 나갈 수 있다** —
게이트가 작은 손실을 1% 이상 손실로 확정시키는 구조다. 실제로 11일간 청산된 8건 전부가 이 밴드였다.

n=4로 표본은 작지만 방향이 4/4이고 기전이 결정론적이라, 재현이 아니라 설계 문제로 본다.

→ **2026-08-18 조치 완료**. 아래 섹션 참조.

---

## 🟢 2026-08-18 후속 조치 — LIVE 매도 멱등성 + 본전 게이트 대칭화

> ⚠️ **둘 다 코드 변경이라 배포해야 반영된다.** 오늘 오전의 time stop 소급 적용(DB UPDATE)과 달리
> 운영에 자동으로 적용되지 않는다.

### 1. LIVE 중복 SELL — DYNAMIC 방어 2종 이식

원인을 DYNAMIC과 대조해 특정했다. DYNAMIC이 07-31/08-03 P0에서 받은 방어가 **LIVE에는 이식되지 않은
상태**였다.

| 방어 | DYNAMIC | LIVE (수정 전) |
|---|---|---|
| 원자적 CLOSING 전환 | `markClosingIfOpen` (07-02 감사 #2) | `setStatus + save` — 중복 감지 불가 |
| CLOSING reconcile 주문 선택 | **FILLED 우선** (08-03) | `sellOrders.get(0)` 최신순 — FAILED에 가려 OPEN 롤백 |
| 롤백된 OPEN 포지션 정산 | `reconcileDynamicGhostPositions` | 없음(거래소 대조형 `reconcilePhantomPositions`만) |

- [`executeSessionSell`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) / `closeSessionPositions` → `markClosingIfOpen` 사용, 0이면 주문 제출 스킵.
- [`reconcileClosingPositions`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) → FILLED 우선 선택 이식. 체결은 되돌릴 수 없는 사실이므로 FILLED가 있으면 그것이 진실이다.
- 신규 [`LiveSellIdempotencyTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/LiveSellIdempotencyTest.java) 3종 — 원자적 가드 / FILLED 우선 / 체결 없을 때는 기존대로 OPEN 롤백.

**남은 갭(미조치)**: 부모 tx가 롤백돼 포지션이 OPEN으로 되돌아간 경우는 위 두 수정으로도 안 잡힌다
(CLOSING만 순회하므로). LIVE는 `reconcilePhantomPositions`(거래소 실잔고 대조, 3회 연속 ≈3분, API 키 필요)가
느리게 잡아준다. DYNAMIC처럼 주문 기반 즉시 정산(`reconcileLiveGhostPositions`)을 추가할지는 실거래 자금에
직접 손대는 범위라 여전히 별도 판단 — 기존 보류 항목 그대로다.

**정확한 트리거 분기는 미확정** — 운영 서버 로그가 있어야 `sellOrders.isEmpty()` 롤백(@Async 주문 INSERT
경합)인지 부모 tx 롤백인지 갈린다. 로컬 `logs/`는 3월치라 쓸 수 없었다. 다만 위 수정은 두 경로 모두에
유효하다.

### 2. 본전 게이트 대칭화 — `lossEscapeThresholdPct` −1.00 → −0.30

데드밴드가 **−1.00% ~ +0.30%** 로 비대칭이라, 전략이 SELL을 내도 손실이 1%를 넘기 전에는 못 나갔다.
−0.30 은 `minPnlPctForSignalExit`(+0.30%)와 대칭이고 왕복 수수료(0.1%)+슬리피지를 덮는다 — churn 방지라는
원래 목적은 유지하면서 손실 구간에 갇히는 비대칭만 제거한다.

**부수 발견 — 이 상수가 네 군데에 복제돼 있었다.** `LiveTradingService`·`PaperTradingService`·
`DynamicTradingService`·`ExitRuleConfig` 각각 `new BigDecimal("-1.00")`. `PaperLiveAlignmentTest`가
리플렉션으로 LIVE↔PAPER만 비교하고 있어 **DYNAMIC과 백테스트는 감시망 밖**이었다.
→ 세 서비스가 [`ExitRuleConfig`](../core-engine/src/main/java/com/cryptoautotrader/core/risk/ExitRuleConfig.java)를
단일 출처로 참조하도록 통합하고, 정합성 테스트를 DYNAMIC·백테스트까지 확장했다.

신규 테스트: 데드밴드 대칭성, 운영에서 막혔던 실제 pnl 값(−0.371/−0.428/−1.174)이 이제 빠져나오는지,
본전 근처(±0.10%) churn 차단은 유지되는지, 최소 보유시간 미달은 여전히 무시되는지.

### 검증

`:web-api:test` **262건 그린**(실패 0, 스킵 3), `:core-engine:test` 그린.

기존 [`SignalExitGateAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/SignalExitGateAbBacktestRunner.java)는
**게이트 검증에 쓸 수 없다** — ON/OFF 결과가 8개 조합 전부 동일하다(전략 SELL 경로를 안 타고 전부 SL/TP 청산).
07-02 L-2의 "영향 없음" 판정이 이 러너 근거였다면 재검토 대상이다.

### 배포 확인 (2026-08-18 10:15)

두 작업 모두 반영됐다. 작업 1은 V68이 09:19:55 KST에 적용됐고(`column_default=24` × 3테이블,
RUNNING 10세션 소급 완료), 작업 2는 마이그레이션이 없어 Flyway로 확인되지 않지만 **10:09 재기동**으로 들어갔다.

재기동 판정 근거 — 시간당 `strategy_log` 건수가 평소 ~49건인데 두 시간대만 두 배다:

| 시간대 | 로그 | 사유 |
|---|---|---|
| 03~08시 | 48~54 | 정상 (H1 캔들당 1스윕) |
| **09시** | **97** | 09:19:55 재기동 + 정기 스윕 |
| **10시** | **98** | 10:00 캔들 스윕 + **10:09 추가 스윕** |

10:09 스윕은 10:00에 이미 평가한 47개 세션 전부를 **같은 H1 캔들로** 재평가했다(세션 50 KRW-PROM이
`signal_price=2710`으로 두 번 동일 기록). 같은 닫힌 캔들을 다시 평가하는 건 인메모리 `lastEvaluatedCandle`
맵이 비었을 때뿐 — JVM 재기동 신호이고, 09:19:55 재기동과 같은 패턴이다. 커밋 89b5a4c(10:07:32) 이후다.

⚠️ **두 변경 모두 아직 실제 경로를 타지 않았다** — 전 세션 SCANNING, OPEN 포지션 0건이라
멱등성 가드와 −0.30 게이트는 다음 진입이 있어야 검증된다. 확인 지점: ① SELL 1건당 주문 1건
② 본전 차단이 −0.30% 위에서만 발생.

---

## 🟢 2026-08-18 전략 폐기 기준(kill criteria) 제정 — [`docs/KILL_CRITERIA.md`](KILL_CRITERIA.md)

> 보류 항목 최우선 5번("중단 기준을 미리 문서화") 해소. **문서가 본체이고 코드는 집행부다.**

### 왜 지금인가

08-18 오전까지의 작업(time stop, 멱등성, 청산 게이트)은 전부 **"잘 지는 시스템을 더 안전하게"** 만드는
것이었다. 정작 실전 검증 통과 전략은 22개 중 0개, 11일 승률 0/7, 알파 음수인데 **폐기 조건이 없어서**
나쁜 전략을 무한히 고쳐 쓰는 루프에 있었다. 기준이 없으면 손실은 항상 "표본이 부족해서"로 설명되고,
표본은 영원히 부족하다.

### 핵심 구분 — 두 종류를 섞지 않는다

| | A. 자본 보호 | B. 엣지 검증 |
|---|---|---|
| 묻는 것 | "더 잃어도 되는가?" | "우위가 있는가?" |
| 통계적 유의성 | **불필요** | 필요 |
| 오판 비용 | 좋은 전략 조기 종료(회복 가능) | 나쁜 전략에 계속 자본 투입(회복 불가) |

"n이 적으니 판단 불가"는 B의 올바른 태도인데 이걸 A에 적용하면 **표본을 모으는 동안 자본이 소진된다** —
지금이 정확히 그 상태였다.

### 정직한 인정

현재 속도는 10세션 11일에 실현 거래 7건(세션당 연 23건). EV 부호를 신뢰할 표본은 실전에서 수년이 걸린다.
→ **실자본 운영으로는 엣지를 통계적으로 검증할 수 없다.** 엣지 검증은 Walk Forward가 맡고, 실전 kill
criteria는 자본 보호가 주력이며 B 기준은 "백테스트를 명백히 배신했을 때 잡는 안전망"으로만 쓴다.

### 발동 기준 (판정 단위 = 세션, PAPER 포함)

| 코드 | 조건 | 표본 |
|---|---|---|
| `CAPITAL_LOSS` | 초기자본 대비 ≤ −15% | 무관 |
| `MAX_DRAWDOWN` | **고점** 대비 ≤ −20% | 무관 |
| `CB_REPEAT` | 서킷브레이커 누적 ≥ 3회 | 무관 |
| `NEGATIVE_EV` | 누적 실현손익 ≤ 0 | n ≥ 20 |
| `NEGATIVE_ALPHA` | 세션 수익률 − 동일기간 알트 보유 < 0 | n ≥ 20 |
| `NO_SIGNAL` | 30일 운영 & 종료거래 < 5 → **경보만** | — |

승률 단독 폐기 기준은 두지 않는다 — 추세추종은 승률 30%대가 정상이라 승률로 죽이면 옳은 전략이 먼저 죽는다.

### 발동 시

세션 정지(정상 매도 경로 청산) → **전략 타입 비활성화** → Discord 경보. 두 번째가 핵심이다 — 세션만
정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수 있다. 부활은 Walk Forward 재검증 → PAPER
재투입 → n≥20 누적을 거쳐야 한다. "파라미터를 고쳤으니 다시 켜자"는 부활이 아니라 **새 전략**이다.

**자동 정지는 기본 꺼져 있다** (`kill-criteria.auto-stop=false`) — 판정·경보는 항상 동작.
`WalkForwardValidationGate`와 같은 방식으로, 판정이 며칠간 옳게 나오는지 본 뒤 켤 것.

### 구현

- [`KillCriteriaConfig`](../core-engine/src/main/java/com/cryptoautotrader/core/risk/KillCriteriaConfig.java) — 임계값 단일 출처
- [`StrategyKillCriteriaService`](../web-api/src/main/java/com/cryptoautotrader/api/service/StrategyKillCriteriaService.java) — 매일 09:00 KST, 순수 판정 함수 `decide()` 분리
- [`BenchmarkAlphaService.altAvgHoldReturnPct`](../web-api/src/main/java/com/cryptoautotrader/api/service/BenchmarkAlphaService.java) — 세션 **자기 기간**의 벤치마크(기존 `getAlphaSummary()`는 전체 세션 중 가장 이른 시작일 하나로 집계해 개별 세션 알파를 못 낸다)
- `PositionRepository.aggregateClosedTradesPerSession()` — 판정 1회당 쿼리 1회
- **V69** `circuit_breaker_trip_count` — 기존엔 `circuit_breaker_triggered_at` 한 칸뿐이라 발동할 때마다 덮어써져 반복 횟수가 남지 않았다
- [`StrategyKillCriteriaDecisionTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/StrategyKillCriteriaDecisionTest.java) 15종 — 경계값을 못박아 **임계값이 조용히 완화되는 것을 막는다**(문서 §7 강제)

`StrategyDegradationWatchdog`와의 차이: 워치독은 신호 품질(사후 4h 수익률)을 6시간마다 보고 경보만 —
조기 경보. 이쪽은 실현 손익·자본을 하루 한 번 보고 정지까지 — 최종 판정.

### 같이 처리 — time stop 텔레그램 알림 (보류 항목 해소)

time stop은 손절도 익절도 아니라 `STOP_LOSS` 유형에 안 잡혔고, **자본 회수 이벤트가 통지되지 않았다.**
08-18 오전 259시간 고착 XRP 4건 청산에 알림이 한 건도 안 간 것이 실측 사례다.
`notifyTimeStop` 신설 → LIVE·DYNAMIC 두 경로에 배선.

### 검증

`:web-api:test` **277건 그린**(실패 0, 스킵 3 — 이전 262건에서 +15).

### 실자본 중단 + 페이퍼 현황 재파악 (2026-08-18 11:30)

실행 스크립트: [`scripts/rebuild_paper_fleet.sh`](../scripts/rebuild_paper_fleet.sh)

> **1차 시도는 전 요청이 `UNAUTHORIZED` 로 실패했다.** `ApiTokenAuthFilter` 가 로그인 없이
> `API_AUTH_TOKEN` 환경변수와 일치하는 고정 Bearer 토큰을 요구하는데 스크립트에 헤더가 없었다.
> (`docker-compose.prod.yml` 이 `${API_AUTH_TOKEN}` 를 읽으므로 같은 디렉터리 `.env` 에 있다.)
> 스크립트에 토큰 로딩 + 사전 인증 확인을 추가했다 — 토큰이 거부되면 아무것도 건드리지 않고 멈춘다.

#### 정정 1 — PaperTradingService 는 가동 중이었다 (문서가 틀렸다)

기존 서술 "07-01 이후 미가동" 은 **사실이 아니다.** `paper_trading.virtual_balance` 에
**42세션이 08-07 09:00 부터 가동 중**이다.

| | |
|---|---|
| 구성 | 7전략 × 6코인(BTC·ETH·XRP·SOL·DOGE·USDT), 전부 H1 |
| 자본 | 세션당 1,000만원, 총 4.2억 |
| 11일 실현 거래 | **34건** (동적·LIVE 합계 7건의 5배) |

전략 목록이 08-18 에 고른 7종과 정확히 일치한다 — **계획했던 DYN_PAPER 9세션은 이것과 완전히
중복이고 표본도 더 좁다. 생성을 취소한다.** 08-18 오전 "47세션 재평가 스윕" 으로 재기동을 판정했을 때
그 47은 42 PAPER + 3 dynamic + 2 LIVE 였다.

전략별 11일 수익률 — **7종 전부 음수, 양수 0개**:
MEANREV_BB / MTF_BTC / MTF_BTC_STRICT −0.05% · MOMENTUM_ICHIMOKU / _V2 / MTF_CONFIRMED −0.19% ·
PULLBACK_MTF −1.62%

#### 정정 2 — 공유 캔들 캐시는 이미 있다 (페이퍼에만)

앞 절의 API 예산 계산은 42세션을 빠뜨렸다. 다만 결론은 반대 방향이었다 —
`PaperTradingService.runStrategy` 에는 **틱당 공유 캔들 캐시(`tickCandleCache`)가 이미 구현돼 있다.**

| 구성 | 세션 | 요청/분 | 비고 |
|---|---|---|---|
| DYNAMIC | 8 | **264** | 캐시 없음 — 세션마다 워치리스트 10코인 각자 조회 |
| PAPER (virtual_balance) | 42 | ~21 | 공유 캐시 → 6코인+BTC 만 |
| LIVE | 2 | ~12 | |
| 합계 | 52 | ~297 (한도 420의 71%) | |

**세션의 15%인 DYNAMIC 이 요청의 89%를 쓴다.** "만들어야 한다" 고 적었던 공유 캐시는 이미
코드베이스에 있고 `DynamicTradingService` 에만 없다 — 이식하면 264 → 약 45로 떨어진다.
세션 확장을 원하면 이게 첫 번째 레버다(`CANDLE_LOOKBACK` 축소보다 우선).

#### 조치 — 42 페이퍼 세션 time stop 복구 (완료)

`max_hold_hours` 가 42행 전부 **NULL** 이었다. V68 은 컬럼 DEFAULT 만 24로 바꾸고 기존 행은
건드리지 않는데(V63 정책), 이 세션들은 08-07 생성이라 해당됐다. 결과적으로
**페이퍼가 실전과 다르게 동작 중**이었고 08-06 정렬 작업의 목적이 깨져 있었다.

→ OPEN 포지션 0건을 확인하고 `UPDATE ... SET max_hold_hours = 24 WHERE status='RUNNING' AND
max_hold_hours IS NULL` 적용, 42/42 확인. 청산 유발 없음(24시간 초과 보유 0건).
포지션 상태가 아니라 **config 컬럼**이고 틱마다 새로 읽으므로 08-18 오전 V68 소급 적용과 같은 부류다.

#### 실자본 6세션 정지 — ✅ 완료 (14:13:37 KST)

| 조치 | 대상 | 사유 |
|---|---|---|
| **정지** | DYN 46·48·50·52 (REAL) + LIVE 198·199 | 실자본 노출 제거 |
| 유지 | DYN_PAPER 47·49·51·53 | DYNAMIC 엔진(워치리스트 스캔) 경로의 **유일한** 페이퍼 관측. 42세션은 단일코인 고정이라 스캔 로직을 검증하지 못한다 |
| 유지 | virtual_balance 42세션 | 위 참조 |
| 취소 | DYN_PAPER 9세션 신규 생성 | 42세션과 중복 |

정지 후 API 부하는 297 → 약 165 요청/분(39%)으로 떨어진다.

**DB 직접 UPDATE 로 세션 status 를 바꾸지 않는다.** 포지션이 0건이라 위 `max_hold_hours` 수정처럼
보이지만 위험이 다르다 — 틱이 도는 중에 status 를 STOPPED 로 내리면 그 틱이 방금 연 실포지션이
**SL/TP 평가를 받지 못하는 고아 상태**로 남는다(틱 루프는 RUNNING 만 순회하고 reconciler 4종은
CLOSING/유령만 다룬다). `stopSession` 은 활성 주문 취소 → 포지션 청산 → 상태 전환을 한 트랜잭션에서
처리한다.

> ⚠️ 재시작은 kill criteria 시계를 0으로 되돌린다(n, `mddPeak`, `startedAt`, NO_SIGNAL 30일 카운터).
> "성적이 나빠지면 재시작" 이 습관이 되면 폐기 기준은 영구히 무력화된다.

#### 검증 결과 (14:14 KST)

| 확인 | 결과 |
|---|---|
| DYN 46·48·50·52 / LIVE 198·199 | 전부 `STOPPED`, `stopped_at` 14:13:37 |
| REAL 노출 세션 · LIVE RUNNING | **0 / 0** |
| OPEN·CLOSING 포지션 | 0 |
| 최근 2시간 주문 | 0건 — 포지션이 없어 청산 없이 종료 |
| 정지 후 로그 | 없음. 마지막 로그가 14:00~14:01(정지 이전) — 틱 루프에서 정상 제외 |
| RUNNING | DYN_PAPER 4 + virtual_balance 42, `max_hold_hours` 42/42 = 24 |

**2026-08-18 14:13 부로 실자본 매매가 전면 중단됐다.** 08-06 벤치마크 측정에서 알파 음수가 확인된 뒤
12일 만이다. 관측은 페이퍼 46세션(단일코인 42 + 동적 4)으로 이어진다.

---

## 🟢 2026-08-18 페이퍼 데이터 생성 설계 — 격자 확장 + 엣지 판정 단위 교정

> 목표: 실자본을 멈춘 상태에서 "어느 전략에 우위가 있는가"를 **몇 달이 아니라 몇 주에** 판정할
> 표본을 만든다. 실행 스크립트: [`scripts/build_paper_grid.sh`](../scripts/build_paper_grid.sh)

### 11일 실측 — 병목은 세션 수가 아니라 신호 희소성

| 코인 | BUY 신호 | 종료 거래 |
|---|---|---|
| SOL | 25 | 16 |
| USDT | 8 | 8 |
| BTC / DOGE | 6 / 6 | 4 / 5 |
| **ETH / XRP** | **0 / 0** | **0 / 1** |

코인당 평가 1,869회에 BUY 0~25건 — **발생률 0.3%**. ETH·XRP는 7전략 × 11일 = 1,869회 평가에
진입 신호가 **한 건도** 없었다. 전략별로는 **PULLBACK_MTF가 전체 거래의 62%(21/34)**, 나머지
6종은 1~3건. MTF_BTC_STRICT는 11일에 1건 — 이 속도면 n=20까지 220일이다.

### 비용 구조의 비대칭 — 페이퍼는 세션이 사실상 공짜

| | 비용 |
|---|---|
| `PaperTradingService` | (코인 × 타임프레임) × 3 요청 — **세션 수와 무관** (`tickCandleCache` 공유) |
| `DynamicTradingService` | 세션 × (워치리스트+1) × 3 — 세션마다 중복 조회 |

42세션이 6코인 × H1 = 18 요청/분으로 돌고 있었다. 진짜 제약은 API가 아니라
`PaperTradingService.MAX_CONCURRENT_SESSIONS = 120`.

### 설계 — 7전략 × 8코인 × 2타임프레임 = 112세션

| | |
|---|---|
| 유지 | SOL · BTC · DOGE (신호 발생 확인) |
| 제외 | **USDT**(스테이블코인 — 8거래 전부 손실, 마찰비용만 태움), **ETH · XRP**(BUY 0건) |
| 추가 | LINK · ADA · AVAX + PROM · EUL (동적 워치리스트가 ATR·스프레드 필터를 통과시킨 실적 종목) |
| 타임프레임 | H1 + M15 — H1 하루 24회 평가, M15 96회 → 진입 기회 4배 |

| | 현재 | 설계 후 |
|---|---|---|
| 세션 | 42 | 112 |
| API | 18 req/분 | 48 req/분 (11%) |
| 거래 생성 | 3.1건/일 | **~20건/일** |
| 전략당 n=20 | ~10개월 | **~7일** |

### 🔴 전제 조건이었던 결함 — 엣지 판정 단위

**이대로면 kill criteria가 영원히 발동하지 않는다.** 엣지 기준이 세션당 n≥20인데 세션당
0.07거래/일이면 **280일**이 걸린다. 판정기를 만들어놓고 안 쓰는 셈이었다.

→ 판정 단위를 분리했다:

| | 단위 | 이유 |
|---|---|---|
| A 자본 보호 | **세션** | 자본이 세션마다 따로 잡힌다 |
| B 엣지 | **전략 × 타임프레임** (코인 합산) | 그룹당 16세션이면 같은 표본이 18일에 모인다 |

- `decide(SessionStats)` — A + NO_SIGNAL 만. B 블록 제거.
- `decideEdge(EdgeStats)` — 신설. 코인을 가로질러 합산한 그룹을 판정한다. 그룹 수익률은
  **자본 가중**(초기자본 합 대비 총자산 합)이라 세션 크기 차이에 왜곡되지 않는다.
- 그룹이 죽으면 그 그룹의 세션 전부를 정지한다. 자본 기준으로 이미 KILL 인 세션은 사유가
  더 구체적이라 덮어쓰지 않는다.
- 벤치마크 조회가 세션 수가 아니라 **그룹 수**에 비례하게 바뀌었다(부수 효과: 캔들 조회 감소).

코인이 아니라 타임프레임으로 그룹을 나누는 이유 — 같은 전략이라도 H1과 M15는 진입·청산
시점이 완전히 달라 사실상 다른 전략이다. 한쪽 결과로 다른 쪽을 판정할 수 없다.

**검증**: `:web-api:test` **288건 그린**(실패 0, 스킵 3).

### 실행 결과 — ✅ 완료 (2026-08-18 15:08, 검증 15:10)

| 확인 | 결과 |
|---|---|
| 페이퍼 세션 | **RUNNING 112 / STOPPED 21** |
| 격자 완전성 | 코인 8종 × (H1 7 + M15 7) = 14 — **8종 전부 일치** |
| | 전략 7종 × (H1 8 + M15 8) = 16 — **7종 전부 일치** |
| | 세션 수가 7이 아닌 (코인, TF) 조합 **0건** — 누락·중복 없음 |
| `max_hold_hours=24` | 112/112 |
| 정지 대상 | ETH 7 · XRP 7 · USDT 7 = 21 |
| 이력 보존 | 최초 시작 08-07 09:00 (SOL·BTC·DOGE @H1 21세션 유지됨) |
| API 조합 | 16 (8코인 × 2TF) → 48 요청/분, 한도의 11% |
| 실자본 | DYN REAL 0 · LIVE 0 · 실포지션 0 · 최근 1시간 실주문 0 |

**정지 경합 1건 관찰(무해).** 정지된 21세션 중 11개가 정지 직후 1초에 로그를 1건씩 남겼다
(15:08:52 정지 → 15:08:53 로그). `runStrategy()`가 틱 시작 시 RUNNING 목록을 한 번 읽고
순회하므로, 순회 도중 정지된 세션은 그 패스를 마저 처리한다. 신호는 전부 HOLD·SELL 이었고
**BUY 는 없어 고아 포지션이 생기지 않았다**(정지 세션의 OPEN 포지션 0건 확인).

> ⚠️ 다만 구조적으로는 **정지 직후 1틱 동안 신규 진입이 가능하다.** 페이퍼라 무해했지만
> LIVE·DYNAMIC 도 같은 패턴이므로, 실자본 재개 시에는 틱 진입부에서 세션 상태를 재확인하는
> 가드가 필요하다. 지금은 실자본이 0이라 후순위.

현재 OPEN 포지션 1건은 세션 122(PULLBACK_MTF · KRW-BTC · H1)가 14:00:09 에 연 것으로,
격자 재구성 이전부터 있던 정상 포지션이다.

---

## 🔴 2026-08-19 kill criteria 가 페이퍼 112세션을 못 보고 있었다 — 수정

> 08-19 07:46 KST, 첫 판정(09:00) 전 점검에서 발견. **판정기와 데이터가 분리돼 있었다.**

### 무엇이 문제였나

전날 실자본을 전면 중단하고 데이터 생성을 페이퍼 112세션으로 옮겼는데, kill criteria 는
그 112세션을 **하나도 보지 못하는** 상태였다. 원인은 스키마 분리다:

| | 실전·동적 | 모의투자 |
|---|---|---|
| 세션 | `live_trading_session` · `dynamic_session` | **`paper_trading.virtual_balance`** |
| 포지션 | `public.position` | **`paper_trading.position`** |

`evaluateAll()` 은 앞의 두 세션 테이블만 순회하고, 거래 집계
(`PositionRepository.aggregateClosedTradesPerSession`)도 `public.position` 만 읽는다.
결과적으로 **평가 대상은 DYN_PAPER 4세션뿐**이었고, 표본이 아무리 쌓여도 기준이 발동할 수 없었다.

발견 당시 실측 — 이미 폐기 조건을 충족했는데 판정에 잡히지 않던 그룹:

| 그룹 | 세션 | 거래 | 승 | 누적 손익 |
|---|---|---|---|---|
| **`COMPOSITE_PULLBACK_MTF@H1`** | 8 | **23** | **0** | **−1,054,645원** |
| `COMPOSITE_PULLBACK_MTF@M15` | 8 | 7 | 2 | −75,289 |
| 나머지 12그룹 | 8씩 | 0~1 | — | — |

`n≥20` · 합계 ≤ 0 → `NEGATIVE_EV` 발동 조건이다.

### 수정

- `PaperPositionRepository.aggregateClosedTradesPerSession()` 신설 —
  `paper_trading.position` 집계. `size > 0` 으로 고아 포지션 제외
  (`public.position` 의 `invested_krw > 0` 에 대응. 페이퍼 테이블에는 그 컬럼이 없다).
- `evaluateAll()` 이 `virtualBalanceRepo.findByStatusOrderByStartedAtAsc("RUNNING")` 까지
  순회하도록 확장. `sessionKind = "PAPER"`.
- 정지 경로에 `paperTradingService.stop()` 분기 추가.
- **페이퍼는 `MAX_DRAWDOWN` 판정을 생략한다** — `virtual_balance` 에 고점 컬럼이 없다.
  `mddPeakCapital=null` 을 넘겨 낙폭 판정만 빠지고 `CAPITAL_LOSS` 는 정상 동작한다.
  고점 정보가 없는데 낙폭을 판정하면 근거 없는 폐기가 된다.

신규 [`KillCriteriaPaperVisibilityTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/KillCriteriaPaperVisibilityTest.java) 5종 —
페이퍼 세션 포함 여부 / 코인 가로지른 합산(세션당 4거래 × 5세션 = 20 으로 발동) /
H1·M15 분리 판정 / 페이퍼 자본 보호 / 낙폭 생략.

**검증**: `:web-api:test` **293건 그린**(실패 0, 스킵 3).

### 배포 후 예상 판정

`COMPOSITE_PULLBACK_MTF@H1` → **`NEGATIVE_EV` KILL** (8세션 정지).
같은 전략의 `@M15` 는 표본 7건이라 살아남으므로 **전략 자체는 비활성화되지 않는다** —
08-18 에 넣은 "운영 세션이 전부 폐기일 때만 끈다" 규칙이 실제로 적용되는 첫 사례다.

⚠️ `kill-criteria.auto-stop=false` 라 실제 정지는 일어나지 않고 Discord 경보만 나간다.
경보 내용이 위 예상과 맞는지 확인한 뒤 자동정지를 켤 것.

---

---

## 🟢 2026-08-19 3엔진 정합성 감사 — [`docs/ENGINE_PARITY.md`](ENGINE_PARITY.md)

> 사용자 지적("확인할 때마다 계속 수정할 게 나온다")에서 출발. **맞았고, 원인은 하나였다.**

### 진단

사흘간 나온 결함이 거의 전부 같은 모양이었다 — **한 엔진에 규칙을 적용하고 나머지를 잊는다.**
구조적 원인은 매매 엔진이 셋(LIVE 2,787줄 · DYNAMIC 2,246줄 · PAPER 1,032줄)인데
각자 세션·포지션 테이블과 틱 루프를 따로 갖고, **정합성을 강제하는 장치가 없었던 것**이다.

### 산출물

- **[`docs/ENGINE_PARITY.md`](ENGINE_PARITY.md)** — 교차 규칙 × 3엔진 매트릭스.
  각 칸을 `적용 / 의도적 제외(사유) / 누락(결함)` 으로 판정.
- **[`EngineParityTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/EngineParityTest.java)** —
  그 매트릭스의 기계 검증본. 새 규칙을 한 엔진에만 넣으면 빌드가 깨진다.
  기존 `PaperLiveAlignmentTest`(파라미터 **값** 일치)와 보완 관계 — 이쪽은 규칙의 **존재** 여부를 본다.

> 테스트를 처음 돌리자마자 자기 몫을 했다: 닫힌 캔들 게이트가 LIVE·PAPER 는
> `lastEvaluatedClosedCandle`, DYNAMIC 만 `lastEvaluatedCandle` 이라는 이름 불일치를 잡았다.
> 기능 차이는 아니지만 **grep 기반 감사를 두 번이나 오답으로 이끈** 원인이다.

### 이번에 함께 처리한 것

| # | 항목 | 조치 |
|---|---|---|
| 1 | **판정 이력이 어디에도 없음** | `kill_criteria_judgment` 테이블 신설(**V70**). `discord_send_log.message_preview` 가 102자 컷이라 첫 폐기 판정의 근거가 Discord 에만 남아 있었다. KILL/WARN 만 저장 |
| 2 | **kill criteria 그룹 키가 엔진 미구분** | 그룹 키를 `엔진/전략@타임프레임` 으로. PAPER(코인 고정)와 DYN_PAPER(워치리스트 스캔)는 종목 선정 방식도 자본 규모(1,000만 vs 1만)도 달라 한 그룹으로 묶으면 안 된다 — 08-19 첫 판정에서 실제로 섞였다 |
| 3 | **페이퍼 거래 리포트 전무** | 112세션 전부 `telegram_enabled=false` → `true`. 일일 다이제스트(12:00·24:00 KST)로 나간다 |
| 4 | **타임스탬프 규약 혼재** | 문서화 + 신규 테이블 `TIMESTAMPTZ` 강제. `timestamptz` 56컬럼 vs **naive UTC 18컬럼(10테이블)** |

### 정정 — 어제 진단이 틀렸다

"페이퍼 time stop 이 무음" 의 원인을 `notifyTimeStop` 누락으로 짚었는데 **틀렸다.**
페이퍼는 애초에 다른 메커니즘(`bufferTradeEvent` → 일일 다이제스트)을 쓰고 청산 사유도 거기 실린다.
진짜 원인은 `telegram_enabled=false` 였다. 결과(알림 없음)는 같지만 고칠 대상이 달랐다 —
`notifyTimeStop` 을 페이퍼에 붙였다면 112세션 알림 폭탄이 됐을 것이다.
이 판정을 `EngineParityTest.realOnlyRules` 에 사유와 함께 고정했다.

### 미해소 (테스트가 현 상태로 고정 중)

| 결함 | 왜 지금 안 고치는가 |
|---|---|
| DYNAMIC 에 트레일링 없음 (LIVE·PAPER 는 있음) | **매매 거동 변경.** 백테스트 검증 없이 이식하면 "고치다 새 문제" 를 반복한다 |
| `tickCandleCache` 가 PAPER 에만 | 현재 API 부하 11% 라 시급하지 않음. 세션 확장 시 1순위 |
| 닫힌 캔들 게이트 이름 불일치 | 순수 리네이밍. 우선순위 낮음 |

**검증**: `:web-api:test` **309건 그린**(실패 0, 스킵 3 — 293 → +16).

---

## 🔴 2026-08-19 데이터 신뢰성 — 규칙 지문(ruleset fingerprint) 도입

> 사용자 지적: "수개월 프로젝트인데 계속 수정할 게 나오고, 데이터를 쌓아도 폐기하고 다시 만들고
> 폐기하고를 반복한다." **맞았고, 원인은 새 버그가 아니라 기록의 부재였다.**

### 진단 — 같은 문제를 반복해서 다시 발견하고 있었다

동적 세션 필터 파라미터를 세션 생성일별로 보면:

| 생성일 | `min_atr_pct` | `max_spread_pct` | 후보 | 세션 |
|---|---|---|---|---|
| 07-09 | **0.30** | **0.15** | **50** | 32~37 |
| 07-31 | **0.30** | **0.15** | 30 | 39~45 |
| **08-07** | **0.50** | **0.10** | 30 | 46~53 |

**7월에 이미 이 문제를 발견하고 필터를 완화했었다.** 그런데 08-07 "PAPER↔LIVE 정렬" 작업에서
세션을 재생성하면서 **코드 하드코딩 기본값으로 조용히 되돌아갔다.** 감시 코인이 주당
62종 → 10종으로 붕괴했지만 알림도 기록도 없었다.

**실패 모드: 수정이 세션 행(row)에 저장돼 있어서, 세션을 다시 만들면 사라진다.**

### 왜 데이터를 계속 버리게 되는가

`position` 314행 전부 `strategy_config_id = NULL`. `strategy_log` 에는 설정 컬럼이 아예 없었다.
→ **어떤 규칙 아래 만들어진 데이터인지 기록이 없다.** 규칙 변경을 나중에 알면 어디까지가
옛 규칙인지 구분할 수 없어 전부 버리는 수밖에 없었다.

데이터가 틀린 게 아니라 **라벨이 없었다.** 그런데 라벨이 없으면 틀린 것과 구분되지 않는다.

### 조치 (V71)

| # | 항목 | 내용 |
|---|---|---|
| 1 | [`RulesetFingerprint`](../core-engine/src/main/java/com/cryptoautotrader/core/risk/RulesetFingerprint.java) | 거동에 영향 주는 파라미터 → 12자 해시. 청산 규칙 16개 + 진입 게이트 11개 + 세션 설정 + 워치리스트 필터 |
| 2 | 데이터 스탬프 | `position` · `paper_trading.position` · `strategy_log` 에 `ruleset_hash`. 3엔진 6개 생성 지점 전부 배선 |
| 3 | [`ruleset_snapshot`](../web-api/src/main/resources/db/migration/V71__add_ruleset_fingerprint.sql) | 지문 → 파라미터 원문 역참조. 지문당 1행 |
| 4 | kill criteria | 집계 키에 지문 추가. **세션의 현재 규칙과 같은 지문의 거래만** 표본에 넣는다 |
| 5 | `risk_config` 승격 | `scan_min_atr_pct` · `scan_max_spread_pct` · `scan_max_candidate_size`. 요청 > risk_config > 코드 기본값 |

**5번이 08-07 회귀를 직접 막는다** — 튜닝이 전역 설정에 있으면 세션 재생성에도 살아남는다.

지문 계산은 [`RulesetRegistry`](../web-api/src/main/java/com/cryptoautotrader/api/service/RulesetRegistry.java)
**한 곳에만** 둔다. 처음엔 세 엔진에 각각 넣었다가 되돌렸다 — 그러면 한 엔진에만 파라미터를
추가하는 순간 서로 다른 규칙이 같은 지문을 갖게 되고, 지문 체계 자체가 거짓말이 된다.
kill criteria 도 같은 메서드를 쓴다.

### 무엇이 달라지는가

```
V71 이전:  규칙 변경 → 어디까지가 옛 규칙인지 모름 → 전부 폐기
V71 이후:  규칙 변경 → 지문이 갈림 → 표본 분할, 데이터는 남음
```

| 검증 (테스트로 고정) | |
|---|---|
| 08-07 회귀 재현 | ATR 0.30/스프레드 0.15 ≠ 0.50/0.10 → 다른 지문 |
| 08-18 청산 게이트 변경 | −1.00 ≠ −0.30 → 다른 지문 |
| 엔진 구분 | 파라미터가 같아도 LIVE ≠ PAPER (체결 가정이 다르다) |
| 스케일 무시 | 0.30 == 0.3 (무의미한 표본 분할 방지) |
| 옛 규칙 손실 20건 | 현재 판정에 **불참** → KEEP. 데이터 20건은 그대로 남음 |
| 지문 없는 거래 | "규칙 미상" → 표본 제외. **소급 추정하지 않는다** |

### 한계 — 정직하게

- **08-19 이전 데이터는 `ruleset_hash = NULL` 로 남는다.** 소급 추정하지 않는다 — 근거가 없고,
  "미상" 을 "특정 규칙" 으로 위장하는 것이 더 나쁘다. 즉 **어제까지의 34거래는 실질적으로 못 쓴다.**
  다만 그건 오늘 만든 것 때문이 아니라 원래 그랬던 것이고, 이제 그 사실을 알 수 있다는 게 차이다.
- 지문에 담기지 않은 파라미터를 바꾸면 지문이 그대로라 서로 다른 규칙이 한 표본에 섞인다.
  **새 파라미터를 도입하면 `RulesetRegistry.base()` 에 반드시 추가할 것.**

### 1차 적용 후 재점검에서 나온 누락 (사용자 확인 요청으로 발견)

"1~5 다 했나" 확인 요청에 대조해 보니 **2·3·4만 완성이고 1·5는 절반이었다.**
같은 착오가 이번 대화에서 두 번 반복됐다 — 사람이 체크리스트를 눈으로 대조하는 방식이 실패하고 있다.

| 누락 | 영향 |
|---|---|
| 진입 게이트 11개 파라미터 | 08-07 회귀가 정확히 이 유형인데 지문에 안 잡혔다 |
| `strategy_params` (LIVE 세션별 전략 튜닝) | **같은 전략을 다르게 튜닝해도 같은 지문** — 지문 체계의 목적이 반쯤 무너진다 |
| `CANDLE_LOOKBACK` · `ALLOWED_SPREAD_TICKS` | 지표 계산 구간과 감시 종목 선정을 바꾸는데 미포함 |
| `risk_config` 승격 (5번) | 컬럼만 만들고 **읽는 코드가 없어 효과 0** — 회귀 방지가 작동하지 않았다 |
| `strategy_log` 스탬프 | 컬럼만 있고 아무도 안 씀 |

### 그리고 이번 작업이 만든 새 중복

```
PaperTradingService.SLIPPAGE_PCT         = 0.001
DynamicTradingService.PAPER_SLIPPAGE_PCT = 0.001
RulesetRegistry.PAPER_SLIPPAGE_PCT       = 0.001   ← 지문을 만들면서 새로 추가
```

**하루 종일 고친 게 이 패턴인데 하나를 더 만들었다.** 한 곳만 바뀌면 지문이 실제 체결 가정과
어긋나고, 그건 지문이 거짓말을 하는 것이라 가장 나쁜 종류다.
`CANDLE_LOOKBACK` 도 4곳(LIVE·DYNAMIC·PAPER·BacktestEngine)에 500 으로 복제돼 있었고
PAPER 주석에는 "동일하게 맞춰야 한다" 는 **수동 동기화 지시**만 있었다.

→ [`TradingConstants`](../web-api/src/main/java/com/cryptoautotrader/api/util/TradingConstants.java) 로
`CANDLE_LOOKBACK` · `PAPER_SLIPPAGE_PCT` · `WATCHLIST_ALLOWED_SPREAD_TICKS` 단일 출처화.
`BacktestConfig.slippagePct` 는 같은 0.1% 를 <b>퍼센트 단위</b>(0.1)로 써서 그대로 합치면
100배 오차가 난다 — 통합하지 않고 양쪽 javadoc 에 경고를 남겼다.

### 기계 가드 — 눈 대조를 그만둔다

같은 누락이 세 번째 나지 않도록 검증을 자동화했다:

| 가드 | 무엇을 잡나 |
|---|---|
| `RulesetFingerprintTest.everyExitRuleFieldIsFingerprinted` | **리플렉션**으로 `ExitRuleConfig` 전 필드가 지문에 있는지 검사. 새 필드를 추가하고 지문에 안 넣으면 빌드가 깨진다 |
| `RulesetFingerprintTest.requiredKeysArePresent` | 리플렉션으로 못 잡는 키(engine·scan·candleLookback 등)를 명시 목록으로 고정 |
| `EngineParityTest.sharedConstantsAreNotRehardcoded` | 세 엔진 소스에 상수 리터럴이 다시 박히면 실패 |
| `EngineParityTest.candleLookbackIsIdenticalAcrossEngines` | 공통 상수 참조 여부 + 백테스트와 값 일치 |

### 지문에 담지 않기로 한 것 (사유 고정)

- **비세션 포지션** (`OrderExecutionEngine` 의 `positionId` 없는 수동/전역 주문) —
  세션이 없으므로 "적용된 매매 규칙" 이라는 것이 존재하지 않는다. `sessionId` 가 null 이라
  kill criteria 집계에서도 제외된다. 소스에 주석으로 못박아 다음 감사에서 오탐이 되지 않게 했다.
- **DYNAMIC·PAPER 의 `strategy_params`** — 그 두 세션 테이블에는 해당 컬럼이 없다.
  전략 파라미터가 세션별로 달라질 수 없으므로 담을 것이 없다. LIVE 만 오버라이드를 갖는다.

**검증**: `:web-api:test` **330건 그린**(실패 0, 스킵 3 — 316 → +14).
신규 [`RulesetFingerprintTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/RulesetFingerprintTest.java) 9종 +
`KillCriteriaPaperVisibilityTest` 표본 분할 2종 + `EngineParityTest` 상수 가드 2종.

---

## 🟢 2026-08-19 동적 워치리스트 붕괴 — 원인 규명 및 수정

> 실측으로 확정. **가설(ATR 하한)은 틀렸고 범인은 스프레드 필터였다.**

### 깔때기 계측 도입

탈락 사유가 DEBUG 로만 찍혀 운영에서 원인을 볼 수 없었다. actuator 는 `prometheus, health` 만
노출돼 런타임 로그 레벨 변경도 불가. → `WatchlistFilterService` 에 게이트별 탈락 수를
**INFO 한 줄**로 남기게 했다(목표 미달 시 WARN 동반). 한 번 보고 끝나는 DEBUG 대신 상시 계측이다.

강제 갱신(`watchlist_refreshed_at = NULL` → 다음 틱) 후 4세션 전부 동일한 결과:

```
후보 30 → 스프레드탈락 23~24 · 캔들부족 0 · ATR하한탈락 4 · 품질탈락 1 → 통과 1~2/10
```

**스프레드가 80% 를 잡아먹는다.** ATR 은 4개뿐 — 가설이 빗나갔다.

### 진짜 원인: 퍼센트 임계가 유동성이 아니라 가격대를 재고 있었다

업비트 공개 API 로 상위 30 직접 실측(운영 로그와 일치, 7 통과/23 탈락):

| 코인 | 가격 | 호가 폭 | 스프레드 | 거래대금 | 기존 판정 |
|---|---|---|---|---|---|
| KRW-BTC | 90,361,000 | **6틱** | 0.0066% | 411억 | 통과 |
| KRW-RED | 129 | **1.4틱** (물리적 최소) | 0.7752% | 152억 | **탈락** |
| KRW-XLM | 217 | **10틱** (진짜 넓음) | 0.4608% | 107억 | 탈락 |
| KRW-DOGE | 98 | 5틱 | 0.2035% | 46억 | 탈락 |

**저가 코인은 1틱만으로 0.1% 를 넘는다.** BTC 는 6틱이나 벌어졌는데 통과하고,
호가가 물리적으로 가장 좁은 RED 는 탈락했다. 유동성 필터가 아니라 **가격대 필터**였다.

### 수정 — 틱 상대 하한

```
통과 = spread% ≤ max(maxSpreadPct, 2 × 1틱%)
```

퍼센트 기준은 고가 코인에 그대로 유효하고(BTC 6틱도 0.0066% 라 통과), 저가 코인은
입자도로 불이익을 받지 않으며, 호가가 **진짜로** 넓으면(XLM 10틱) 가격대와 무관하게 탈락한다.

**단순히 임계값을 0.5% 로 올리는 것과 통과 수는 같지만(23/30) 고르는 코인이 정반대다:**

| | 0.5% 단순 상향 | 틱 상대(2틱) |
|---|---|---|
| RED (1.4틱, 유동성 152억) | ❌ 탈락 | ✅ 통과 |
| XLM (10틱, 호가 넓음) | ✅ 통과 | ❌ 탈락 |

틱은 **호가창에서 직접 추론**한다(인접 호가 간 최소 양수 간격). 업비트 틱 테이블을
하드코딩하면 규칙 변경 시 조용히 틀린다 — 실측에서도 EUL(1,643원) 틱이 1원,
DOGE(98원)가 0.04원으로 공개 표와 달랐다.

신규 [`WatchlistSpreadFilterTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/WatchlistSpreadFilterTest.java) 7종 —
틱 추론 / 하드코딩 금지 / RED 구제 / XLM 탈락 / BTC 퍼센트 유지 / 틱 미상 시 폴백.

**검증**: `:web-api:test` **316건 그린**(실패 0, 스킵 3 — 309 → +7).

### 남은 조정 후보 (배포 후 깔때기 재측정하고 판단)

- **거래대금 하한 50억** (`risk_config.scan_min_trade_value_krw` NULL → 코드 기본값).
  PROM 이 45.2억으로 아깝게 탈락했다. 상위 30 안에 들면서 50억 미달인 종목이 다수다.
- **ATR 하한 0.5%**(H1, `BASELINE_TIMEFRAME_MIN=60` 이라 스케일링 없음) — 4개 탈락.
  스프레드가 풀리면 후보가 늘어 이 게이트의 실제 영향도 다시 봐야 한다.

둘 다 세션 컬럼/`risk_config` 라 **재배포 없이 조정 가능**하다. 스프레드 수정 배포 후
깔때기 수치를 다시 보고 정한다 — 한 번에 여러 임계값을 움직이면 무엇이 효과였는지 알 수 없다.

---

### ~~미해결~~ — 동적 워치리스트 붕괴 (✅ 위 절에서 규명·수정)

`targetWatchSize=10` 인데 실제 워치리스트는 **0~1개**다. 8세션 중 5개가 동시에 `["KRW-EUL"]`,
2개는 `[]`. 11일 누적으로도 평가된 코인이 14종(하루 2~6종)뿐이다.

→ **동적 세션을 늘려도 전부 같은 코인 하나를 본다.** 상관관계가 1에 가까워 표본이 늘지 않는다.
`WatchlistFilterService` 게이트 3종(ATR 하한 0.5% / 스프레드 상한 0.1% / 품질 게이트) 중
무엇이 막는지는 탈락 사유가 DEBUG 로만 찍혀 아직 미확인. **추측으로 값을 바꾸지 않는다.**
DYN_PAPER 4세션은 스캔 엔진 관측용으로 유지하되, 필터를 고치기 전에는 확장하지 않는다.

---

## 🟢 2026-08-18 kill criteria 후속 — 폐기 우회 경로 차단 + 비활성화 범위 교정

> 위 세션 계획(7전략 × 2타임프레임)을 짜다 드러난 결함 두 가지. 단일 타임프레임에서는 노출되지 않는다.

### 1. 폐기 판정 우회 경로 2개 — `StrategyEnablementGate` 신설

`strategy_type_enabled` 검사가 `DynamicTradingService.createSession`에만 인라인으로 있었고
**LIVE와 `PaperTradingService`는 무검사**였다. kill criteria가 폐기 시 전략을 비활성화하는 목적은
"세션만 정지하면 같은 전략으로 새 세션을 만들어 재개할 수 있다"를 막는 것인데, 세 진입점 중 하나만
막혀 있으면 달성되지 않는다 — 우회로가 둘 열려 있었다.

→ [`StrategyEnablementGate`](../web-api/src/main/java/com/cryptoautotrader/api/service/StrategyEnablementGate.java)로
규칙을 추출해 LIVE·DYNAMIC·PAPER 세 경로에 동일 적용. "행이 없으면 허용"(차단 목록) 규칙은 유지.

운영 DB 실측: `strategy_type_enabled` 21행이 **전부 `is_active=false`**인데, 가동 중인 composite 전략
대부분은 아예 등재돼 있지 않다. 즉 이 테이블은 차단 목록으로 동작 중이며 기본값을 false로 바꾸면
미등재 전략 전부가 즉시 막힌다.

### 2. 비활성화 범위 과잉 — "전부 죽었을 때만" 으로 교정

판정 단위는 세션(= 전략 × 타임프레임)인데 `strategy_type_enabled`는 **전략명만** 키로 쓴다.
비활성화가 판정보다 한 단계 거칠어서, MEANREV_BB@M15 하나가 죽으면 **멀쩡한 MEANREV_BB@H1까지 막혔다.**

→ `applyKill`을 `stopKilledSession`(세션 정지) + `disableFullyKilledStrategies`(전략 비활성화)로 분리하고,
후자는 **그 전략의 운영 세션이 전부 KILL일 때만** 실행한다. 살아 있는 변형이 하나라도 있으면
세션 정지에서 멈춘다. WARN(NO_SIGNAL)도 "살아 있음"으로 친다 — 경보는 폐기가 아니다.

### 검증

`:web-api:test` **285건 그린**(실패 0, 스킵 3). 신규
[`StrategyEnablementGateTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/StrategyEnablementGateTest.java) 4종 ·
[`KillCriteriaStrategyDisableTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/KillCriteriaStrategyDisableTest.java) 4종.

> `RateLimiterEmergencyStopTest`의 동시 acquire 테스트가 1회 실패했다(7 기대 → 8 획득). 리필 데몬 타이밍에
> 민감한 기존 테스트로, 재실행 시 통과하며 이번 변경과 무관하다. 간헐 실패가 반복되면 별도 처리 대상.

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

## 🔴 2026-08-07 신호 반전 가설 — **기각**. 신호는 "거꾸로"가 아니라 그냥 예측력이 없다

> 08-06 측정에서 BUY 후 24h −4.81%, SELL 후 +1.06%가 나와 "신호 방향만 반대인 것 아닌가"라는 가설이 섰다. 맞다면 진입만 뒤집어 즉시 수익 전환이 가능하므로 **가장 먼저 확인해야 할 가설**이었다. 결론은 아니오다.

### 검증 방법 — [BacktestConfig.invertSignals](../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestConfig.java)

전략이 낸 신호의 방향만 뒤집고(BUY↔SELL), **손절·익절·time stop 등 리스크 규칙과 수수료·슬리피지는 그대로** 둔다(청산 규칙까지 뒤집으면 손절이 익절이 되어 실험이 무의미해진다). 운영 중인 7개 전략 × 4개 코인(BTC·XRP·ETH·SOL) H1 8,000캔들(2025-04-30 ~ 2026-03-30, 약 11개월)로 정방향/반전을 각각 돌려 비교했다.

### 결과 — 28개 조합

| | 평균 수익률 | 총 거래 |
|---|---|---|
| 정방향 | **−4.85%** | 815 |
| 반전 | **−6.01%** | 965 |

- **반전이 오히려 더 나쁘다.** "반전이 더 나은 조합 16/28(57%)"은 동전던지기 수준이고, 그나마도 대부분 **반전 시 거래가 거의 안 일어나서**(0~4건) "안 잃은 것"에 불과하다. 진입은 무포지션일 때만, 청산은 보유 중일 때만 가능하므로 반전은 대칭이 아니다 — BUY를 SELL로 바꾸면 진입 자체가 사라진다.
- **표본이 충분한 조합에서는 반전이 명백히 더 나쁘다.** `COMPOSITE_PULLBACK_MTF`는 정방향 95~123거래 / 반전 174~241거래로 양쪽 다 표본이 큰데, 4개 코인 전부 반전이 −8.7 ~ **−55.3%p** 악화됐다.
- 유일하게 눈에 띈 건 `COMPOSITE_MEANREV_BB` XRP(정방향 −3.52% → 반전 +14.83%, 31거래)뿐인데 단일 조합이라 노이즈로 봐야 한다.

**판정: 신호는 체계적으로 반대인 게 아니라 방향성 자체가 없다.** 손실의 정체는 예측 실패가 아니라 **무작위 진입 + 마찰비용(수수료 0.05% + 슬리피지 0.1%)**이다. PROGRESS에 미리 적어둔 두 갈래("양수면 중대한 발견, 아니면 무작위 + 마찰비용") 중 후자로 확정됐다.

### 더 중요한 부수 발견 — 백테스트도 이제 마이너스다

같은 기간 매수후보유는 BTC −24.43%, XRP −35.56%, SOL −39.16%, ETH +21.63%였다. 즉 **하락장이었고**, 이 구간에서 전략들의 정방향 평균이 −4.85%인 건 "시장보다는 덜 잃었다"로 읽을 여지가 있다. 다만 승률이 대부분 **0~23%**로 극단적으로 낮아, 소수의 큰 이익으로 다수의 손실을 메우는 구조인데 그 이익이 마찰비용을 못 넘고 있다.

과거 기록된 "백테스트 +106~127%"와 지금 측정이 크게 다른 건 전략·구간·게이트 구성이 달라졌기 때문이다. **지금은 백테스트와 실전이 같은 방향(마이너스)을 가리킨다** — 측정 정합성 자체는 오히려 건강해졌다는 뜻이다.

### 남은 것

반전이 답이 아니므로 **엣지는 신호 모델 자체에서 찾아야 한다.** 현재 22개 전략 중 실전 검증 통과 0개라는 사실은 그대로다. 다음 후보는 ① 마찰비용을 줄이는 방향(거래 빈도 축소·지정가 진입) ② 승률이 아니라 손익비를 키우는 방향 ③ 전략 폐기 기준(kill criteria) 확정 후 대량 폐기.

---

## 2026-08-07 벤치마크 대비 알파를 대시보드에 내장

> "이 시스템이 잘하고 있는가"를 수개월간 판정하지 못한 근본 원인 — 수익률을 **절대값**으로만 봤다. 시장이 −10%일 때의 −3%는 좋은 성적이고, 시장이 +10%일 때의 −3%는 나쁜 성적이다. 08-06엔 수동 SQL로 계산했는데, 이걸 매번 손으로 하지 않도록 시스템에 넣었다.

- **[BenchmarkAlphaService](../web-api/src/main/java/com/cryptoautotrader/api/service/BenchmarkAlphaService.java)** — 운영 중 실자본 세션의 수익률을 같은 기간 BTC·ETH·XRP·SOL·DOGE 매수후보유와 비교. `GET /api/v1/trading/benchmark-alpha`.
- **PAPER 세션은 집계에서 제외** — 실자본 성과가 아니므로 섞이면 알파가 왜곡된다.
- **시세는 `market_data_cache`에서 읽는다** — `candle_data`는 백테스트용 과거 저장소라 최신 구간이 비어 있다(실측: H1 최신이 2026-03-30에서 멈춰 있었다). 이걸 모르고 `candle_data`를 썼다면 알파가 조용히 엉뚱하게 계산됐을 것이다.
- **데이터 없는 코인은 0%가 아니라 `null`(판정 불가)** — 0%로 세면 알파가 희석돼 실제보다 좋아 보인다.
- **대시보드 상단 고정 노출** — 시장을 이기는 중이면 초록, 못하면 빨강 테두리.

**검증**: [BenchmarkAlphaServiceTest](../web-api/src/test/java/com/cryptoautotrader/api/service/BenchmarkAlphaServiceTest.java) 5건. 무력화 2회 — ① PAPER 제외 가드 제거 → 1건 실패 ② 알파 뺄셈 제거 → 처음엔 1건만 실패했는데, 확인해보니 첫 테스트가 **알트 평균을 0%로 잡아 뺄셈을 빼먹어도 통과하는 약한 테스트**였다. 평균을 +2%로 바꿔 네 값이 모두 다르게 만든 뒤 2건 실패로 정상화. 무력화 검증이 없었으면 그냥 통과하는 테스트로 남았을 것이다.

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
