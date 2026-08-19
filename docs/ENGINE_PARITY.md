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
| 1 | **DYNAMIC 에 트레일링 없음** | `ExitRuleConfig.trailingEnabled=true` 이고 LIVE·PAPER 는 구현돼 있는데 DYNAMIC 만 진입 시 TP 를 한 번 정하고 끝. 같은 전략이라도 이익 구간 거동이 다르다 | **매매 거동 변경**이라 백테스트 검증 없이 이식하면 "고치다 새 문제" 를 반복한다 |
| 2 | **`tickCandleCache` 가 PAPER 에만** | DYNAMIC 8세션이 전체 API 요청의 89%(264/297 req/분). 세션 확장의 1순위 병목 | 현재 부하 11% 라 시급하지 않음. 세션을 늘릴 때가 착수 시점 |
| 3 | **닫힌 캔들 게이트 이름 불일치** | LIVE·PAPER 는 `lastEvaluatedClosedCandle`, DYNAMIC 만 `lastEvaluatedCandle`. grep 기반 감사가 오탐을 낸다 (이 문서를 쓰는 중 실제로 두 번 틀렸다) | 순수 리네이밍이라 언제든 가능. 우선순위 낮음 |

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
