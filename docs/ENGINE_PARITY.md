# 4엔진 정합성 매트릭스

> **최초 작성 2026-08-19.** 기계 검증본은
> [`EngineParityTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/EngineParityTest.java).
> 이 문서는 그 테스트가 왜 그렇게 선언돼 있는지를 설명한다 — 값을 바꾸려면 양쪽을 함께 고칠 것.

---

## 1. 왜 만들었나

2026-08-17~19 사흘간 나온 결함이 거의 전부 같은 모양이었다: **한 엔진에 규칙을 적용하고
나머지를 잊는다.**

| 결함 | 있던 곳 | 없던 곳 | 결과 |
|---|---|---|---|
| `LOSS_ESCAPE_THRESHOLD` 4중복 | 4곳 | 테스트는 LIVE↔PAPER 만 감시 | DYNAMIC 값 드리프트 |
| `strategy_type_enabled` 검사 | DYNAMIC | LIVE · PAPER | 폐기 판정 우회로 2개 |
| `markClosingIfOpen` 원자적 CLOSING | DYNAMIC | LIVE | LIVE 중복 매도 |
| `tickCandleCache` | PAPER | LIVE · DYNAMIC | DYNAMIC 이 API 예산 89% 소비 |
| kill criteria 세션 순회 | live·dynamic 테이블 | `paper_trading` 스키마 | 페이퍼 112세션이 판정 대상 밖 |

구조적 원인은 하나다 — **청산 규칙을 쓰는 곳이 넷인데 정합성을 강제하는 장치가 없었다.**

| 엔진 | 줄 수 | 세션 테이블 | 포지션 테이블 |
|---|---|---|---|
| `LiveTradingService` | 2,819 | `live_trading_session` | `public.position` |
| `DynamicTradingService` | 2,884 | `dynamic_session` | `public.position` |
| `PaperTradingService` | 1,076 | `paper_trading.virtual_balance` | `paper_trading.position` |
| **`BacktestEngine`** | 426 | — (`backtest_run`) | — |

> **2026-09-08: `BacktestEngine` 을 축에 추가했다.** 이 문서는 08-19 작성 이래 "매매 엔진이 셋"
> 으로 시작했는데, `BacktestEngine` 도 같은 `ExitRuleChecker` 를 쓰고 **그 결과가 WF 게이트를
> 통해 실자본 배정을 결정한다.** 09-08 에 손실구간 SL 조임을 고치다 컴파일 에러로 이 네 번째
> 호출자가 드러났고, 그 누락 때문에 WF 350건이 통째로 무효가 됐다. 매매를 실행하지 않는다고
> 축에서 빼면, 그 엔진이 만든 근거로 매매를 허가하는 경로가 감시 밖에 남는다.

---

## 2. 매트릭스

`O` 적용 · `—` 의도적 제외(사유 명시) · `✗` **누락(결함)**

### 세 엔진 모두 적용되어야 하는 것

| 규칙 | LIVE | DYNAMIC | PAPER | BACKTEST |
|---|---|---|---|---|
| SL/TP 산정 (`ExitRuleFormula`) | O | O | O | **O** ✅ 09-08 |
| `shouldTimeStop` (시간 초과 청산) | O | O | O | **O** ✅ 09-08 |
| `BlackSwanGuard` | O | O | O | — (진입 차단은 실시간 신호 기반) |
| `BtcMarketGuard` | O | O | O | O |
| `MarketRegimeDetector` | O | O | O | O |
| 닫힌 캔들 게이팅 | O | O | O | — (캔들 단위 시뮬이라 구조적으로 성립) |
| `strategyEnablementGate` | O | O | O | — (운영 게이트, 검증 도구엔 부적용) |
| `updateTrailingStops` (TP 래칫) | O | O | O | O |
| 전략 SELL 게이트 (`SignalExitGate`) | O | O | O | O ✅ 09-08 |
| 틱 캔들 캐시 (`TickCandleCache`) | O | O | O | — (캔들 리스트를 인자로 받는다) ✅ 09-08 |

### ✅ BACKTEST 의 청산 규칙을 실전과 통일했다 (2026-09-08 해소)

`BacktestEngine` 은 `ExitRuleChecker.calculateStopLevels` 를, 세 매매 엔진은
`ExitRuleCalculator.resolveStopLossPct` 를 썼다 — **이름이 비슷한 다른 함수였다.**

| | BACKTEST (수정 전) | 네 경로 (수정 후) |
|---|---|---|
| SL 폭 | **항상 5.0% 고정** | `clamp(ATR(14)/가격 × 1.5, floor, 8%)` → 5~8% |
| ATR 반영 | **안 함** (`atrStopLossEnabled=false`, DB 로 켤 수단도 없었다) | 함 |
| TP 폭 | **SL × 2 = 항상 10%** | `min(SL × 2, 8%)` → ≤8% |
| 전략 제안 SL | 그대로 채택 | `.min()` — 더 넓은 쪽 |
| 시간 초과 청산 | **없음** | `maxHoldHours` (기본 24h) |

**원인은 "잊었다" 가 아니라 패키지 배치였다.** 공식이 `web-api` 에 있고 백테스트는
`core-engine` 이라 **모듈 의존 방향상 호출 자체가 불가능**했다. 검사를 추가해도 못 고친다 —
배치를 바꿔야 사라진다. 그래서 공식을 `core-engine` 의 `ExitRuleFormula` 로 올렸고,
`ExitRuleCalculator` 는 세션 오버라이드만 해석하는 얇은 위임층이 됐다.
**실전 세 엔진의 계산 결과는 바뀌지 않는다**(상수·로직을 그대로 옮겼다). 달라지는 것은 백테스트뿐이다.

#### 구버전 결과가 실자본을 승인하는 것을 막는다 (V78)

게이트는 조합별 **최신 실행 하나**만 본다. 그래서 재실행된 조합은 옛 결과가 밀려나지만
**재실행되지 않은 조합은 수정 전 판정을 영구히 유지한다** — 조용히 낡은 근거로 승인이 난다.

`ExitRuleFormula.EXIT_RULES_VERSION`(현재 **2**)을 `backtest_run.exit_rules_version` 에 기록하고,
`WalkForwardValidationGate` 가 그보다 낮은 실행을 **근거로 인정하지 않는다**(= 이력 없음과 동일).
09-08 이전 실행은 전부 NULL 이므로 자동으로 걸러진다. **공식·상수를 바꾸면 이 상수를 함께 올릴 것.**

### 실거래 경로 전용 — PAPER 에 없는 것이 정상

| 규칙 | LIVE | DYNAMIC | PAPER | 사유 |
|---|---|---|---|---|
| `markClosingIfOpen` | O | O | — | 페이퍼는 체결이 동기 시뮬레이션이라 CLOSING 중간 상태가 없다 |
| `checkCircuitBreaker` | O | O | — | 실자본 보호 장치. 다만 kill criteria `CB_REPEAT` 는 페이퍼에서 영영 0 |
| `notifyTimeStop` / `notifyStopLoss` | O | O | — | 페이퍼는 `bufferTradeEvent` → 일일 다이제스트(12:00·24:00 KST). 112세션에 즉시 알림을 붙이면 알림 폭탄 |

### 자본 배정 게이트 — PAPER 제외 (2026-08-06 판단)

| 규칙 | LIVE | DYNAMIC | PAPER | 사유 |
|---|---|---|---|---|
| `walkForwardValidationGate` | O | O | — | "실자본을 쓸 자격이 있는가"를 묻는 게이트. 페이퍼는 **그 자격을 얻기 전에 검증하는 도구**라 걸면 검증 경로가 사라진다 |
| `strategyLiveStatusRegistry` | O | O | — | 위와 동일 |

### 엔진 고유

| 규칙 | LIVE | DYNAMIC | PAPER | 사유 |
|---|---|---|---|---|
| 교차 세션 노출 한도 | — | O | — | 동적 세션만 워치리스트에서 종목을 골라 서로 겹칠 수 있다. LIVE 는 코인 고정, PAPER 는 실자본 아님 |

---

## 3. 미해소 결함 (테스트가 현 상태로 고정 중)

해소되면 `EngineParityTest` 가 깨지도록 해 뒀다 — 고친 뒤 테스트를 함께 갱신할 것.

| # | 결함 | 영향 | 왜 지금 안 고치는가 |
|---|---|---|---|
| ~~1~~ | ~~**DYNAMIC 에 TP 트레일링 없음**~~ | **2026-09-08 해소** — 세 엔진이 `updateTrailingStops` 공용. 번호는 다른 문서·주석의 참조가 깨지지 않도록 비워 둔다. 아래 §4 트레일링 절 참조 | — |
| ~~2~~ | ~~**`tickCandleCache` 가 PAPER 에만**~~ | **2026-09-08 해소** — `TickCandleCache`(스레드 스코프) 로 세 엔진 공용. 근거였던 "API 요청의 89%" 는 08-26 `fetchWithCache` 도입으로 이미 낡은 수치였고, 남아 있던 낭비는 **틱 안에서 같은 (코인,타임프레임) 을 세션 수만큼 다시 조회**하는 쪽이었다. `processTick` 이 `@Transactional` 프록시 경유라 인자로 넘길 수 없어 ThreadLocal 스코프를 쓴다 | — |
| ~~3~~ | ~~**닫힌 캔들 게이트 이름 불일치**~~ | **2026-09-08 해소** — DYNAMIC 을 `lastEvaluatedClosedCandle` 로 통일. "순수 리네이밍이라 우선순위 낮음" 으로 미뤄 왔지만, 이 저장소의 반복 결함이 **"규칙이 한 엔진에만 적용됐는지" 를 사람이 확인하다 놓치는 것**이라는 점에서 우선순위가 낮지 않았다 — 감사 도구가 못 믿을 이름을 남겨 두면 감사 자체가 헛돈다. `EngineParityTest.closedCandleGateNamingIsConsistent` 로 고정 | — |

### 트레일링 — TP 와 SL 을 구분할 것 (2026-09-08 해소)

트레일링은 방향이 반대인 두 동작이다. 하나로 뭉쳐 부르면 **제거된 버그를 새로 심게 된다.**

| 동작 | LIVE | DYNAMIC | PAPER | 상태 |
|---|---|---|---|---|
| **TP 래칫** (고점 추적, 이익 잠금) | O | O | O | **2026-09-08 통합 — 세 엔진이 `updateTrailingStops` 호출** |
| **손실 구간 SL 조임** (저점 × (1−margin) 로 상향) | 08-06 제거 | 없음 | 09-08 제거 | **되살리지 말 것** |

#### TP 래칫 — 통합 전 세 엔진이 세 가지로 달랐다

| 엔진 | 통합 전 발동 조건 | 기준가 |
|---|---|---|
| LIVE | `spikeUp && pnl > 0` — 30초 +2.0% 급등한 틱에서만 | 현재가 × (1−margin) |
| PAPER | `candleHigh > entryPrice` — 수익이면 매 틱 | 고가 × (1−margin) |
| DYNAMIC | 없음 — 진입 시 TP 한 번 정하고 끝 | — |

문서가 이걸 `LIVE O / PAPER O / DYNAMIC ✗` 로 적어 "DYNAMIC 만 없다"고 읽히게 해 뒀지만,
**LIVE 와 PAPER 도 서로 다른 규칙이었다.** 완만하게 오르는 포지션은 LIVE 에서만 TP 가
고정된 채 남았다. 이제 셋 다 `ExitRuleChecker.updateTrailingStops` 를 호출한다.
LIVE 의 `spikeUp` 은 throttle(급등락 1초 / 평상시 5초) 판정에만 계속 쓰인다.

`candleLow` 파라미터도 함께 제거했다 — 세 호출부가 의미 없는 인자를 넘기게 되고,
그 자리가 조임 로직이 되살아나는 자리가 되기 때문이다.

#### 손실 구간 SL 조임 — 같은 결함이 두 번 나왔다

손실 중 SL 을 끌어올리는 것은 "다음 틱에 강제청산을 예약"하는 동작이다.
하락 방어는 **진입 시점에 확정된 ATR 기반 SL** 이 담당한다.

- **2026-08-06 LIVE** — 급락 감지 시 `trailingSlMargin`(0.3%)으로 SL 조임 → 제거
- **2026-09-08 PAPER** — `ExitRuleChecker.updateTrailingStops` 의 손실 분기 → 제거

08-06 에 LIVE·DYNAMIC 만 고치고 **PAPER 전용 경로(`ExitRuleChecker`)를 빠뜨렸다.**
그 한 달 동안 `stop_loss_pct=5.00` 이 실제로는 **−0.43% 손절**로 걸려, 운영 페이퍼 청산
534건 중 **85.4%(456건)가 평균 0.9시간 만에 휩쏘 손절**됐다(승률 13%, 누적 −1,270만원).

⚠️ **`BacktestEngine` 도 같은 함수를 쓴다.** 백테스트가 운영 엔진과 규칙을 공유하는 것은
설계 의도지만, 그래서 **2026-09-08 이전의 백테스트·Walk Forward 결과는 0.3% 손절 기준**이다.
그 결과로 통과/탈락시킨 조합은 재검증 대상이다.

고정 장치:
- `ExitRuleCheckerTest` §15 — 진입가 1틱 아래에서도 SL 이 유지되는지 **값으로** 검증
- `EngineParityTest.noEngineTightensStopLossOnLoss` — 세 엔진 어디도 조임 마진을 쓰지 않는지 검증
- `EngineParityTest.trailingUsesSharedCalculatorInAllEngines` — 셋 다 공용 함수를 쓰는지 검증
- `RulesetFingerprint` 의 `exit.slTightenOnLoss` — 제거 전후 표본이 한 지문에 섞이지 않도록 분리

---

## 4. 이 매트릭스 밖의 정합성 문제

### 타임프레임 축 — 전수 점검 (2026-09-08)

같은 결함이 두 번 나와서 축 전체를 훑었다. 08-24 에 WF 게이트를 전략 → 전략×코인 으로
좁히면서 타임프레임을 놓쳤고(09-08 수정), 09-08 에 신호 로그 집계에서 같은 것이 또 나왔다.

**점검 방법**: 전략 컬럼이 있는 테이블 11개 중 `timeframe` 이 없는 것을 찾고,
그 테이블을 집계하는 코드가 실제로 타임프레임을 무시하는지 확인했다.

| 테이블 | `timeframe` | 집계 코드 | 상태 |
|---|---|---|---|
| `backtest_run` | O | `WalkForwardValidationGate` | ✅ 09-08 수정 |
| `dynamic_session` · `live_trading_session` · `virtual_balance` | O | — | ✅ |
| `kill_criteria_judgment` | O | `groupByStrategyTimeframe` | ✅ 원래 올바름 |
| **`strategy_log`** | **✗ → O (V76)** | `LogController` · `StrategyDegradationWatchdog` | ✅ 09-08 수정 |
| `strategy_type_enabled` | ✗ | `StrategyKillCriteriaService` | ⚠️ 의도된 한계 (아래) |
| **`strategy_timeframe_enabled`** | **O (V80 신설)** | `StrategyEnablementGate` | ✅ 09-08 신설 |
| **`weight_optimizer_snapshot`** | **✗ → O (V79)** | `StrategyWeightOptimizer` | ✅ 09-08 수정 |
| `execution_drift_log` | ✗ | — (주문 단위 기록) | — 해당 없음 |

#### 왜 `strategy_log` 가 가장 컸나

컬럼이 없으니 집계가 구조적으로 (전략, 코인) 으로만 묶였고, 운영은 두 타임프레임을 동시에
돌리며 M15 가 3~5배 많아 **사실상 M15 통계에 H1 이 잡음으로 섞였다.**
합치면 결론이 뒤집힌다 (2026-08-01~ BUY 신호 사후 4h):

| 전략 | H1 | M15 | 합산(수정 전) |
|---|---|---|---|
| `COMPOSITE_MTF_BTC` | **−1.428** | **+0.046** | −0.319 |
| `COMPOSITE_MTF_BTC_STRICT` | −0.509 | +0.518 | +0.266 |
| `COMPOSITE_MOMENTUM_ICHIMOKU_V2` | −0.367 | +0.142 | +0.052 |

09-04 "전략 검토" · 09-07 "전략 순위가 통제하면 무너진다" 분석 모두 코인·시각은 통제했지만
**타임프레임은 통제한 적이 없다.** 두 분석의 전략 순위는 이 축에서 다시 봐야 한다.

#### `strategy_type_enabled` — 의도된 한계 (고치지 않음)

판정 단위는 세션(= 전략 × 타임프레임)인데 이 테이블은 전략명만 키로 쓴다.
그래서 `StrategyKillCriteriaService.disableFullyKilledStrategies` 는 **그 전략의 모든 변형이
폐기 판정일 때만** 비활성화한다 — `MEANREV_BB@M15` 하나가 죽었다고 끄면 멀쩡한
`MEANREV_BB@H1` 까지 막히기 때문이다. 세분화가 거친 것을 알고 보수적으로 우회한 설계이며,
그 사유가 코드 javadoc 에 남아 있다.

#### ✅ `weight_optimizer_snapshot` — 해소 (V79)

`StrategyWeightOptimizer` 는 청산 포지션을 `regime` / `regime:coin` 으로 묶어 가중치를 냈다 —
**타임프레임이 키에 없었다.** 같은 전략·코인·레짐이면 H1 과 M15 성적이 한 가중치로 합쳐졌는데,
위 표대로 **두 타임프레임의 부호가 반대인 경우가 실제로 있어 서로 상쇄된다.**

미룬 사유는 *"집계 원천인 `position` 에 timeframe 이 없어 session_id 조인이 필요하다"* 였는데,
확인해 보니 **가중치 쿼리 두 개가 이미 `live_trading_session` 을 조인하고 있었다** —
`s.timeframe` 을 한 컬럼 더 고르면 되는 일이었다. 실제 비용이 추정보다 훨씬 작았다.

**두 층으로 누적한다**: 같은 거래를 `regime` 과 `regime@tf` 양쪽에 넣고,
`WeightOverrideStore` 가 `regime:coin@tf → regime:coin → regime@tf → regime → 코드 기본값`
순으로 폴백한다. 타임프레임별 표본이 최소치에 못 미치는 동안에는 종전 동작이 그대로 유지되고,
표본이 쌓이면 세분화된 값이 자동으로 이긴다 — 이 변경이 가중치를 **개선하는 대신 지워 버리는**
것을 막는 장치다.

세 엔진이 전략 평가 파라미터에 `timeframe` 을 주입하고, `RegimeAdaptiveStrategy` →
`StrategySelector` 가 그 값으로 키를 좁힌다. 하나라도 빠지면 그 엔진만 무관 가중치를 쓴다.

#### ✅ 폐기 판정이 재생성을 막지 못하던 구멍 — 해소 (V80)

`strategy_type_enabled` 가 전략명만 키로 쓰는 것은 **의도된 한계가 맞다** —
`MEANREV_BB@M15` 하나가 죽었다고 끄면 멀쩡한 `@H1` 까지 막히므로, "전부 죽었을 때만" 끄는
보수적 우회가 옳다(`KillCriteriaStrategyDisableTest` 가 그 경계를 고정한다).

**문제는 그 우회의 결과였다.** `KILL_CRITERIA.md` §5 가 전략 비활성화를 두는 이유가
*"세션만 정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수 있다"* 인데,
타임프레임 단위 폐기에서는 그 목적이 전혀 달성되지 않았다:

```
MEANREV_BB@M15 KILL  →  세션 정지                          O
                     →  MEANREV_BB@M15 새 세션 생성 차단?   X   (아무도 안 막았다)
```

`kill-criteria.auto-stop` 이 OFF 라 아직 실제 동작은 아니었다 — **켜는 순간 구멍이 된다.**
그래서 켜기 전에 `strategy_timeframe_enabled`(V80) 를 신설했다. 판정 단위(세션 = 전략 ×
타임프레임)와 차단 단위가 처음으로 일치하며, 다른 타임프레임은 영향받지 않으므로 위 우회의
취지도 그대로 지켜진다. 두 층은 독립이다:

| 테이블 | 의미 | 쓰는 시점 |
|---|---|---|
| `strategy_type_enabled` | 전략 전체 차단 | 모든 변형이 폐기일 때 |
| `strategy_timeframe_enabled` | 그 전략의 특정 타임프레임만 차단 | 그 조합이 폐기일 때 |

### 타임스탬프 규약 혼재

| 타입 | 컬럼 | 테이블 |
|---|---|---|
| `timestamptz` | 56 | 22 |
| **`timestamp` (naive UTC)** | **18** | **10** |

naive UTC 테이블: `discord_send_log` · `flyway_schema_history` · `regime_change_log` ·
`news_item_cache` · `news_source_config` · `notion_report_log` · `llm_provider_config` ·
`llm_task_config` · `discord_channel_config` · `notion_report_config`

**조용히 틀린 답을 준다.** 08-19 판정 확인 중 `created_at > now() - interval '90 minutes'` 가
0건을 반환했는데, 알림은 정상 발송된 상태였다(`discord_send_log` 가 naive UTC).
08-18 에도 `flyway_schema_history.installed_on` 에서 같은 함정에 걸렸다.

**규칙: 신규 테이블은 예외 없이 `TIMESTAMPTZ`.**

**2026-09-08 해소 (V77).** 미뤄 온 사유가 "운영 조회 코드를 동시에 고쳐야 해서" 였는데
실제로 확인해 보니 그렇지 않았다 — 해당 엔티티가 **전부 `Instant`** 를 쓴다
(`LocalDateTime` 이었다면 변환이 값을 이동시켰겠지만 `Instant` 는 절대시각이라
`timestamptz` 가 오히려 자연스러운 매핑이다). 저장값이 UTC 임도 운영 DB 로 확인했다:
`now()` 06:13:37+00 vs `news_item_cache.fetched_at` 최댓값 06:08:41 — 5분 전, UTC 로 일치.
따라서 `AT TIME ZONE 'UTC'` 변환이 무손실이다.

덤으로 `hibernate.jdbc.time_zone` 미설정 상태에서 naive 컬럼 해석이 **JVM 기본 타임존에
의존**하던 것도 사라진다. `flyway_schema_history` 는 Flyway 소유라 제외했다.

규칙 자체도 문장에서 **테스트로 옮겼다** — `TimestampConventionTest` 가 V78 이후
마이그레이션에 naive `TIMESTAMP` 가 들어오면 깨진다. 문장으로 적힌 규칙은 이 저장소의
반복 결함(= 사람이 확인하다 놓친다)에 그대로 노출되기 때문이다.

### 판정 이력 보존 (해소됨, V70)

`discord_send_log.message_preview` 는 **102자에서 잘린다**. 08-19 첫 폐기 판정의 근거가
Discord 메시지에만 남아, "언제 어떤 수치로 걸렸는가" 를 조회할 수 없었다.
→ `kill_criteria_judgment` 테이블 신설(V70). KILL/WARN 만 저장한다.
