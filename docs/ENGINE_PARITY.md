# 3엔진 정합성 매트릭스

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

구조적 원인은 하나다 — **매매 엔진이 셋인데 정합성을 강제하는 장치가 없었다.**

| 엔진 | 줄 수 | `@Scheduled` | 세션 테이블 | 포지션 테이블 |
|---|---|---|---|---|
| `LiveTradingService` | 2,787 | 6 | `live_trading_session` | `public.position` |
| `DynamicTradingService` | 2,246 | 7 | `dynamic_session` | `public.position` |
| `PaperTradingService` | 1,032 | 1 | `paper_trading.virtual_balance` | `paper_trading.position` |

---

## 2. 매트릭스

`O` 적용 · `—` 의도적 제외(사유 명시) · `✗` **누락(결함)**

### 세 엔진 모두 적용되어야 하는 것

| 규칙 | LIVE | DYNAMIC | PAPER |
|---|---|---|---|
| `ExitRuleCalculator` (SL/TP 산정) | O | O | O |
| `shouldTimeStop` (시간 초과 청산) | O | O | O |
| `BlackSwanGuard` | O | O | O |
| `BtcMarketGuard` | O | O | O |
| `MarketRegimeDetector` | O | O | O |
| 닫힌 캔들 게이팅 | O | O | O |
| `strategyEnablementGate` | O | O | O |
| `updateTrailingStops` (TP 래칫) | O | O | O |

> 하나라도 빠지면 그 엔진만 다른 규칙으로 매매한다. 그러면 **페이퍼 결과로 실전을 예측한다**는
> 08-06 정렬 작업의 전제가 깨진다.

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
| 2 | **`tickCandleCache` 가 PAPER 에만** | DYNAMIC 8세션이 전체 API 요청의 89%(264/297 req/분). 세션 확장의 1순위 병목 | 현재 부하 11% 라 시급하지 않음. 세션을 늘릴 때가 착수 시점 |
| 3 | **닫힌 캔들 게이트 이름 불일치** | LIVE·PAPER 는 `lastEvaluatedClosedCandle`, DYNAMIC 만 `lastEvaluatedCandle`. grep 기반 감사가 오탐을 낸다 (이 문서를 쓰는 중 실제로 두 번 틀렸다) | 순수 리네이밍이라 언제든 가능. 우선순위 낮음 |

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

**규칙: 신규 테이블은 예외 없이 `TIMESTAMPTZ`.** 기존 10개 테이블의 마이그레이션은
운영 조회 코드를 동시에 고쳐야 해서 별건으로 남긴다. 그때까지 이 목록이 경고문이다.

### 판정 이력 보존 (해소됨, V70)

`discord_send_log.message_preview` 는 **102자에서 잘린다**. 08-19 첫 폐기 판정의 근거가
Discord 메시지에만 남아, "언제 어떤 수치로 걸렸는가" 를 조회할 수 없었다.
→ `kill_criteria_judgment` 테이블 신설(V70). KILL/WARN 만 저장한다.
