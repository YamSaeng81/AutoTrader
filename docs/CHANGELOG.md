# CryptoAutoTrader — CHANGELOG.md

> **목적**: 완료된 작업의 상세 변경 이력. `PROGRESS.md`에서 할 일이 완료되면 이 파일에 추가한다.

---

### ✅ 완료 (2026-03-25) — COMPOSITE_BTC V2 재구성 (Grid+Bollinger → MACD+VWAP+Grid)

| 파일 | 변경 내용 |
|------|-----------|
| `CompositePresetRegistrar.java` | COMPOSITE_BTC 구성 변경: `Grid×0.6 + Bollinger×0.4` → `MACD×0.5 + VWAP×0.3 + Grid×0.2`. import BollingerStrategy 제거, MacdStrategy·VwapStrategy 추가 |
| `StrategyController.java` | COMPOSITE_BTC 설명 업데이트 |

**재구성 근거**: KRW-BTC H1 백테스트 결과 분석
- VWAP / SUPERTREND / RSI_DIVERGENCE 신규 BTC 백테스트 실시
- VWAP: 2024 +53.38% / 2025 -6.97% → 평균 +23.2%, 승률 64.8%, MDD -15~-19% (채택)
- SUPERTREND: 2024 +68.97% / 2025 -39.45% → 불안정, MDD -45% (기각)
- RSI: 일관 마이너스 (기각)
- 기존 Grid(+8.4%), Bollinger(+3.2%) 대비 MACD 최적화(+151.9%) 압도적

**백테스트 검증 결과** (KRW-BTC H1, 24~25년): **+58.83%**, 승률 37.5%, MDD -25.62%
- 구 Grid+Bollinger 추정치 ~+6% 대비 압도적 개선
- 승률 낮음은 MACD 추세 전략 특성 (진입 빈도↓, 홀딩 기간↑)

---

### ✅ 완료 (2026-03-25) — COMPOSITE_ETH_VD 추가 및 백테스트 채택

| 파일 | 변경 내용 |
|------|-----------|
| `CompositePresetRegistrar.java` | `COMPOSITE_ETH_VD` 등록: `ATR×0.4 + OB×0.3 + VD×0.2 + EMA×0.1` |
| `BacktestService.java` | `compositeEthVdBt()` 헬퍼 추가 — 백테스트 전용 가중치 분리 |
| `StrategyController.java` | COMPOSITE_ETH_VD 설명·구현 여부 추가 |

**백테스트 비교 결과** (KRW-ETH H1, 2회):

| 전략 | 결과 1 | 결과 2 | 평균 | MDD |
|------|--------|--------|------|-----|
| COMPOSITE_ETH_VD | +92.54% | +48.10% | **+70.3%** | -12~-17% |
| COMPOSITE_ETH | +47.05% | +50.37% | +48.7% | -26~-28% |
| VOLUME_DELTA 단독 | +94.04% | -22.11% | +36% | -32~-54% |

**결론**: VD 편입 시 MDD 대폭 개선. VOLUME_DELTA 단독은 불안정하여 복합 편입 전략으로 채택.

---

### ✅ 완료 (2026-03-24) — MACD coinDefaults 코인별 분리

| 파일 | 변경 내용 |
|------|-----------|
| `MacdStrategy.java` | `coinDefaults(String coinPair)` 정적 메서드 추가. BTC → (14,22,9), ETH → (10,26,9), 그외 → (12,24,9) 자동 선택 |
| `BacktestEngine.java` | `coinPair` 파라미터 주입 → MacdStrategy 초기화 시 coinDefaults 적용 |
| `LiveTradingService.java` | 세션 시작 시 coinPair 전달 → MACD 코인별 최적 파라미터 자동 적용 |

---

### ✅ 완료 (2026-03-24) — MACD 파라미터 그리드 서치 백테스트

| 파일 | 변경 내용 |
|------|-----------|
| `MacdGridSearchRequest.java` | 그리드 서치 전용 DTO 신규 (fastMin/Max, slowMin/Max, signalPeriod) |
| `BacktestService.java` | `runMacdGridSearch()` 추가 — O(n) 고속 버전 (primitive double, MACD 시리즈 1회 계산) |
| `BacktestController.java` | `POST /api/v1/backtest/macd-grid-search` 엔드포인트 추가 |
| `MacdConfig.java` | 기본값 변경: (12,26,9) → (14,22,9) (BTC 그리드 서치 최적값) |

**그리드 서치 결과 (2024~2025 H1, 176조합)**:

| 코인 | 최적 파라미터 | 수익률 | Sharpe | MDD | 기본값 대비 |
|------|--------------|--------|--------|-----|------------|
| KRW-BTC | fast=14, slow=22 | +151.9% | 1.68 | 18.5% | +63.8%p |
| KRW-ETH | fast=10, slow=26 | +216.0% | 1.61 | 49.5% | +41.4%p |

> 성능 이슈: BacktestEngine이 O(n²)이라 그리드 서치에 부적합 → 경량 O(n) 루프로 직접 구현

---

### ✅ 완료 (2026-03-24) — 급등/급락 실시간 감지 (손절 가속 + 트레일링 스탑)

| 파일 | 변경 내용 |
|------|-----------|
| `LiveTradingService.java` | `PriceSnapshot` 내부 클래스 추가. `priceHistory` / `spikeCheckLastMs` 필드 추가. 상수 6개 추가 (SPIKE_WINDOW_MS, SPIKE_DOWN/UP_THRESHOLD, SL_TIGHTEN/TRAILING_STOP_MARGIN 등). `onRealtimePriceEvent()` 재작성. `updatePriceHistory()` / `calcSpikeRate()` 헬퍼 추가. import `ArrayDeque`, `Deque` 추가 |

**동작 흐름**:
1. WebSocket ticker 수신마다 `priceHistory`에 (timestamp, price) 저장 (throttle 전, 항상 기록)
2. 30초 윈도우 내 변동률 계산 → 급락(-1.5%) / 급등(+2.0%) 여부 판단
3. **급등락 감지 시**: 1초 throttle (평상시 5초) + 세션 루프 진입
4. **기존 손절 체크 강화**: `stopLossPrice` 절대가 우선 → 없으면 `stopLossPct %` (기존 로직이 `stopLossPrice` 를 무시하던 버그 함께 수정)
5. **급락 + 손실 중 포지션**: SL을 `현재가 × 0.997`로 조임 (단방향 ratchet — 한번 조이면 완화 안 됨)
6. **급등 + 수익 중 포지션**: TP를 `현재가 × 0.995`로 트레일링 업데이트 (단방향 ratchet — 고점 추적)

**설계 결정**:
- spike threshold는 감지 트리거일 뿐, 실제 손절/익절 기준은 세션 설정(`stopLossPct`, `takeProfitPrice`) 유지
- SL 조임은 손실 중 포지션에만 적용 (수익 중엔 불필요)
- TP 트레일링은 수익 중 포지션에만 적용 (손실 중엔 의미 없음)
- `priceHistory` 는 전략과 무관하게 모든 RUNNING 세션에 공통 적용

---

### ✅ 완료 (2026-03-24) — WebSocket 연결 상태 표시 버그 수정

| 파일 | 변경 내용 |
|------|-----------|
| `UpbitWebSocketClient.java` | `connectionStateListener` (`Consumer<Boolean>`) 필드 추가. `setConnectionStateListener()` 공개 메서드 추가. `notifyConnectionState(boolean)` 내부 헬퍼 추가. `onOpen()` → `notifyConnectionState(true)` 호출. `onClose()` / `onError()` / `exceptionally()` / `disconnectInternal()` → `notifyConnectionState(false)` 호출 |
| `LiveTradingService.java` | `reconcileOnStartup()` 내 WebSocket 리스너 등록 블록에 `wsClient.setConnectionStateListener(exchangeHealthMonitor::setWebSocketConnected)` 한 줄 추가 |

**수정 전 동작**: `ExchangeHealthMonitor.webSocketConnected` 필드가 항상 `false` → Upbit 연동 상태 화면에서 WebSocket이 항상 "미연결" 표시

**수정 후 동작**: WebSocket `onOpen` 시 `true`, 연결 종료/오류/명시적 disconnect 시 `false`로 실시간 갱신 → 화면에 실제 연결 상태 반영

**설계 결정**: `UpbitWebSocketClient`는 `exchange-adapter` 모듈, `ExchangeHealthMonitor`는 `web-api` 모듈이므로 직접 의존성 주입 대신 콜백(`Consumer<Boolean>`) 패턴 사용. 기존 `tickerListeners` / `tradeListeners` 리스너 패턴과 동일한 방식.

---

### 📋 참고 (2026-03-23) — AI 복합 전략 리뷰 분석 및 개선 우선순위 도출

> 제미나이 · OpenAI · 퍼플렉시티 3개 AI에게 `COMPOSITE_STRATEGIES_GUIDE.md`를 보여주고 받은 피드백 요약.

#### 3개 AI 모두 동의 — 완료된 개선 항목

| 순위 | 항목 | 상태 |
|------|------|------|
| 1 | **글로벌 RiskManager 분리** — 전략 외부 SL/TP/최대노출 통합 관리 | ✅ 완료 (V26, PaperTradingService, LiveTradingService) |
| 2 | **`COMPOSITE_BTC` EMA 방향 필터** — 추세 감지 시 역추세 신호 억제 | ✅ 완료 (CompositeStrategy.java) |
| 3 | **`COMPOSITE` TRANSITIONAL → 신규 진입 금지** | ✅ 완료 (PaperTradingService, LiveTradingService) |
| 4 | **`COMPOSITE_ETH` 모드별 가중치 분리** — 백테스트/실시간 프리셋 구분 | ✅ 완료 (BacktestService.java) |

#### 잔여 개선 항목 (PROGRESS.md P1 추적 중)

| 순위 | 항목 | 비고 |
|------|------|------|
| 5 | `MACD_STOCH_BB` → COMPOSITE TREND 서브필터 편입 | 복잡도: 중 |
| 6 | 멀티 타임프레임 (1H 방향 + 15M 진입) | 복잡도: 고 |
| 7 | 동적 가중치 (100거래 이상 샘플, 오버피팅 주의) | 복잡도: 고 |

#### 주요 판단 메모

- **OpenAI 충돌 판단 로직 변경 제안 반박**: `abs(buyScore-sellScore)<0.1→HOLD` 방식은 두 신호 모두 강할 때(예: buy=0.80, sell=0.75) 상충을 방치함. 현재 "양쪽 모두 0.4 이상이면 HOLD" 로직이 우월.
- **동적 가중치 오버피팅 위험**: 최근 10~20거래 승률로 가중치 조정 시 "방금 잘 됐던 전략에 몰빵" 역효과. 최소 100거래 + 변화 ≤ 10%/회 + 최저 하한선 0.1 조건 필수.
- **하이퍼 파라미터 자산별 최적화(제미나이 제안)**: BTC/ETH 이외 알트코인 지원 시점에 검토. 현재는 파라미터 폭발 위험.

---

### ✅ 완료 (2026-03-24) — VolumeDeltaStrategy 신규 구현 및 StrategyRegistry 등록

| 파일 | 변경 내용 |
|------|-----------|
| `volumedelta/VolumeDeltaStrategy.java` (신규) | `Strategy` 인터페이스 구현. Tick Rule 근사로 캔들별 Delta(매수볼륨 − 매도볼륨) 계산. `누적Delta비율 = sum(Delta) / sum(volume)` 로 정규화. Delta 추세 확인 필터(전반부 vs 후반부 평균 비교), 다이버전스 필터(가격 방향 vs Delta 방향 역전 시 신호 억제) 포함 |
| `volumedelta/VolumeDeltaConfig.java` (신규) | `StrategyConfig` 구현. 파라미터: `lookback`(기본 20), `signalThreshold`(기본 0.10), `divergenceMode`(기본 true). `fromParams()` 정적 팩토리 메서드 포함 |
| `volumedelta/VolumeDeltaStrategyTest.java` (신규) | 8개 테스트: 이름/최소캔들수 확인, 데이터 부족 HOLD, 매수 압력 강화 BUY, 매도 압력 강화 SELL, 임계값 미만 HOLD, Delta 강화 없을 시 HOLD 격하, 약세 다이버전스 HOLD, 신호 강도 범위 검증 |
| `StrategyRegistry.java` | `VolumeDeltaStrategy` import 추가. `register(new VolumeDeltaStrategy())` 등록 (`OrderbookImbalanceStrategy` 바로 다음) |

**신호 로직 요약**:
- BUY: `누적Delta비율 > signalThreshold` AND `후반부 평균Delta > 전반부 평균Delta` AND (divergenceMode OFF 또는 가격↑ 아닐 것)
- SELL: `누적Delta비율 < -signalThreshold` AND `후반부 평균Delta < 전반부 평균Delta` AND (divergenceMode OFF 또는 가격↓ 아닐 것)

**설계 근거**: ORDERBOOK_IMBALANCE의 캔들 근사 볼륨 분해 방식을 재사용하되, 호가 불균형 비율 대신 "누적 Delta 방향 + 가속 추세"를 신호 기준으로 삼는다. 다이버전스 필터는 가격과 매수/매도 압력이 반대 방향으로 움직이는 국면(압력이 실제로 소진됐을 가능성)에서의 허위 신호를 억제한다.

---

### ✅ 완료 (2026-03-24) — ORDERBOOK_IMBALANCE Delta 가속도 필터 추가 (Option A)

| 파일 | 변경 내용 |
|------|-----------|
| `OrderbookImbalanceStrategy.java` | `evaluateWithCandleApproximation()` 내 per-candle delta 배열 저장 추가. lookback 구간을 전반부/후반부로 분할하여 캔들당 평균 Delta 비교. BUY: `후반부Avg > 전반부Avg` 필수, SELL: `후반부Avg < 전반부Avg` 필수. 조건 미충족 시 HOLD 격하 + reason에 전반부/후반부 Δavg 값 표기 |
| `OrderbookImbalanceStrategyTest.java` | 기존 평탄 상승/하락 테스트 → 가속 패턴 테스트(전반부 1% / 후반부 5%)로 교체. `캔들_매수_우세이나_Delta_감속시_HOLD` 신규 테스트 추가 (동일 3% 반복 → HOLD 격하 검증) |

**적용 범위**: 캔들 근사 모드(백테스팅)에만 적용. 실시간 모드(`bidVolume`/`askVolume` 파라미터 제공 시)는 호가창 데이터 자체가 정확하므로 필터 미적용.

**설계 근거**: 호가 불균형 비율이 임계값을 넘더라도 Delta가 평탄하거나 감소 중이면 이미 압력이 소진된 국면일 수 있다. 후반부 평균이 전반부 평균을 상회(BUY)/하회(SELL)하는 경우만 통과시켜 허위 신호를 억제.

**기존 S4-6 Delta 일관성 필터와의 관계**: 일관성 필터는 "마지막 캔들 vs 이전 누적"의 방향 역전 시 강도 50% 할인. 가속도 필터는 "전반부 vs 후반부 추세"가 진행 방향인지 확인. 두 필터는 독립적으로 동작하며 중복 없음.

---

### ✅ 완료 (2026-03-23) — COMPOSITE_ETH 모드별 가중치 분리 (백테스트 vs 실시간)

| 파일 | 변경 내용 |
|------|-----------|
| `BacktestService.java` | 3곳의 전략 분기(`runBacktest`, `runMultiStrategyBacktest`, `runBulkBacktest`)에 `else if ("COMPOSITE_ETH".equals(...))` 블록 추가. `compositeEthBt()` 헬퍼 메서드 신설 |
| 동일 파일 | 임포트 추가: `AtrBreakoutStrategy`, `EmaCrossStrategy`, `OrderbookImbalanceStrategy` |

**가중치 변화**:

| 구분 | ATR_BREAKOUT | ORDERBOOK_IMBALANCE | EMA_CROSS |
|------|-------------|---------------------|-----------|
| **Live** (기존) | 0.5 | 0.3 | 0.2 |
| **Backtest** (신규) | 0.7 | 0.1 | 0.2 |

**설계 근거**: 백테스트에서 `ORDERBOOK_IMBALANCE`는 실시간 호가창 대신 캔들 근사값(시가·종가 스프레드)을 사용하므로 실효 신뢰도가 낮다. 가중치를 0.3→0.1로 축소하고 그 0.2를 검증된 `ATR_BREAKOUT`으로 이동. 사용자는 전략명 `COMPOSITE_ETH`만 선택하면 되며, 백테스트/라이브 모드 자동 전환으로 UI 변경 없음.

---


### ✅ 완료 (2026-03-23) — COMPOSITE TRANSITIONAL 신규 진입 금지

| 파일 | 변경 내용 |
|------|-----------|
| `PaperTradingService.java` | COMPOSITE 전략 신호 평가 후 `regime == TRANSITIONAL && action == BUY` → `StrategySignal.hold(...)` 로 억제. 원신호 이유를 메시지에 포함하여 로그 추적 용이. `finalSignal` final 변수로 람다 캡처 문제 해결 |
| `LiveTradingService.java` | 동일 패턴 적용 |

**동작**: COMPOSITE(시장 국면 자동 선택) 전략 세션에서 TRANSITIONAL 국면 감지 시 BUY 신호 차단 → 기존 포지션은 SELL 신호로 정상 청산 가능. COMPOSITE_BTC/ETH 프리셋은 regime을 사용하지 않으므로 영향 없음.

---

### ✅ 완료 (2026-03-23) — COMPOSITE_BTC EMA 방향 필터 추가 (추세 역행 신호 억제)

| 파일 | 변경 내용 |
|------|-----------|
| `CompositeStrategy.java` | `emaFilterEnabled` 생성자 플래그 추가. `getMinimumCandleCount()` — EMA 필터 활성화 시 최소 50 캔들 보장. `evaluate()` → `finalSignal()` + `applyEmaFilter()` 로 분리. EMA20 > EMA50 상승추세 → SELL 억제 HOLD, EMA20 < EMA50 하락추세 → BUY 억제 HOLD |
| `CompositePresetRegistrar.java` | `COMPOSITE_BTC` 등록 시 `emaFilterEnabled=true` 전달 (COMPOSITE_ETH는 미적용 — 추세추종 전략 포함으로 억제 불필요) |

**설계 결정**: EMA20/50 파라미터 하드코딩 (일봉 기준 합리적 기본값). `emaFilterEnabled=false` 기본값 유지로 기존 전략 영향 없음.

---

### ✅ 완료 (2026-03-23) — 글로벌 리스크 매니저 분리 (포지션별 SL/TP 절대가 저장 및 매 틱 체크)

| 파일 | 변경 내용 |
|------|-----------|
| `V26__add_sl_tp_to_positions.sql` (신규) | `position` 및 `paper_trading.position` 테이블에 `stop_loss_price`, `take_profit_price` 컬럼 추가 (NUMERIC 20,8, nullable — 기존 포지션 하위 호환) |
| `PositionEntity.java` | `stopLossPrice`, `takeProfitPrice` 필드 추가 |
| `PaperPositionEntity.java` | `stopLossPrice`, `takeProfitPrice` 필드 추가 |
| `PaperTradingService.java` | `executeBuy()` — 매수 시 전략 제시 SL/TP 우선, 없으면 기본 3%/6% 적용하여 포지션에 저장. `runSessionStrategy()` — 전략 평가 전 SL/TP 가격 비교 체크 추가 (손절: currentPrice ≤ stopLossPrice, 익절: currentPrice ≥ takeProfitPrice) |
| `LiveTradingService.java` | `executeSessionBuy()` — 매수 시 전략 제시 SL/TP 우선, 없으면 세션 `stopLossPct` 기반(SL×2 = TP) 기본값 적용. SL/TP 체크 블록 리팩터링: 기존 SL(%-비교)에 TP 체크 추가, `pos.stopLossPrice != null`이면 절대가 비교, null이면 pnlPct 비교(하위 호환) |

**설계 결정**:
- 전략이 `StrategySignal.suggestedStopLoss/TakeProfit`을 제공하면 그 값 우선 사용
- 없으면 세션/기본 비율로 진입가 기준 절대가를 계산하여 저장 → 매 틱 O(1) 비교
- 기존 포지션(`stopLossPrice == null`)은 기존 %-비교 방식 유지 (하위 호환)

---

### ✅ 완료 (2026-03-23) — 전략 코드 리팩토링 (기능 변경 없음)

| 파일 | 변경 내용 |
|------|-----------|
| `StrategyParamUtils.java` (신규) | `getInt / getDouble / getBoolean` 공용 파라미터 파싱 헬퍼. `Number` 외에 `String` → 숫자 변환 fallback 포함 (JSON 역직렬화 타입 불일치 방어). 기존 4개 전략에 각각 중복 정의되어 있던 코드 제거 |
| `IndicatorUtils.java` | `rsiSeries()` — Wilder's Smoothing RSI 시계열 / `rsiFromAvg()` — RS → RSI 변환 헬퍼 / `stochasticKSeries()` — RSI 시계열 → Stochastic %K 시계열 / `smaList()` — 롤링 SMA 시계열 (기존 `sma()`는 단일 BigDecimal 반환) 추가 |
| `MacdStrategy.java` | `calculateEmaFromList()` 제거 → `IndicatorUtils.ema()` 직접 호출 / `getInt / getDouble` 제거 → `StrategyParamUtils` 사용 / `calculateMacd()` 이중 호출 제거: 단일 패스에서 MACD 라인 시계열 빌드 후 `prev`·`current` signal을 한 번에 산출 (이전: O(2n), 이후: O(n) + 1 EMA step) |
| `MacdStochBbStrategy.java` | `calcEmaFromList / calcRsiSeries / rsiFromAvg / calcStochK / calcSma` 제거 → `IndicatorUtils` 위임 / `getInt / getDouble` 제거 → `StrategyParamUtils` 사용 / `calcMacd()` 이중 호출 → 단일 패스로 개선 |
| `StochasticRsiStrategy.java` | `computeRsiSeries / rsiFromAvg / computeStochasticK / computeSma` 제거 → `IndicatorUtils` 위임 / `parseIntParam / parseDoubleParam` 제거 → `StrategyParamUtils` 사용 |
| `VwapStrategy.java` | `getInt / getDouble / getBool` 제거 → `StrategyParamUtils` 사용 |
| `CompositeStrategy.java` | `totalWeight` 필드 추가, 생성자에서 1회 계산 → `evaluate()` 호출마다 `stream().sum()` 재산정하던 낭비 제거 |
| `types.ts` | `StrategyType` union에 `COMPOSITE_BTC`, `COMPOSITE_ETH`, `MACD_STOCH_BB` 추가 (등록된 전략인데 타입 미포함으로 컴파일 타임 오류 불가 상태였음) |
| `page.tsx` (strategies) | 탭 버튼 `className` 계산 로직 → `tabClass(active: boolean)` 헬퍼 추출, 중복 제거 |

> **제거된 중복 코드 규모**: 파라미터 파싱 ~40줄 (4개 파일), 지표 계산(RSI 시계열·StochK·SMA 시계열·EMA 리스트) ~210줄 (2개 파일) — 총 약 250줄

---

### ✅ 완료 (2026-03-23) — P4 전략 고도화

- [x] **MACD 히스토그램 연속 확대 조건 추가** — 크로스 시점에 `현재 histogram > 이전 histogram` 방향 확인 조건 추가. `MacdStrategy.evaluate()` 적용. 가짜 크로스 약 30% 필터링
- [x] **MACD 제로라인 필터** — BUY: MACD 선이 0선 위에서 크로스할 때만 진입 (`currentMacd > 0`). SELL: 0선 아래 크로스만. 횡보장 노이즈 감소
- [x] **StochRSI oversold/overbought 임계값 조정** — `oversoldLevel 15→20`, `overboughtLevel 85→80`. 신호 발생 빈도 줄여 노이즈 감소. `StochasticRsiStrategy.java` 기본값 변경
- [x] **StochRSI %K-%D 크로스 연속 확인 조건** — 1캔들 즉시 진입 대신 2캔들 연속 %K > %D 유지 시만 매수. `kSeries.size() >= 3` 조건으로 구현
- [x] **StochRSI 거래량 확인 조건 추가** — 진입 시 현재 캔들 거래량 ≥ 최근 20캔들 평균일 때만 신호 발동. `IndicatorUtils.sma()` 활용
- [x] **VWAP 임계값 완화 + ADX 상한 상향** — `thresholdPct 2.5→1.5%`, `adxMaxThreshold 25→35`. BTC 거래 0건 문제 해소
- [x] **VWAP 앵커 방식 개선** — UTC 00:00 기점 캔들부터 당일 VWAP 누적 (`anchorSession=true`). 당일 캔들 3개 미만이면 rolling VWAP fallback. `period` 파라미터로 rolling 기간 조정 가능
- [x] **코인별 복합 전략 프리셋 추가** — `COMPOSITE_BTC` (GRID×0.6 + BOLLINGER×0.4), `COMPOSITE_ETH` (ATR_BREAKOUT×0.5 + ORDERBOOK_IMBALANCE×0.3 + EMA_CROSS×0.2). `CompositePresetRegistrar` @PostConstruct 등록. 전략 관리 탭에서 단일/복합 분리 표시
- [x] **MACD_STOCH_BB 신규 복합 추세 전략** — MACD(추세) + StochRSI(타이밍) + 볼린저밴드(지지선) 결합. 1시간봉 최적화. 매수 6조건 AND, 매도: Hist↓ OR K>80. 손절-2%/익절+4% 자동 첨부, 쿨다운 3캔들. `StatefulStrategy` 구현 (세션별 쿨다운 상태 격리)

### ✅ 완료 (2026-03-23)

- [x] **비상정지 텔레그램 사유 전송** — `ExchangeDownEvent`에 `reason` 필드 추가. `ExchangeHealthMonitor`가 연속 3회 실패 시 사유 포함 이벤트 발행. `LiveTradingService.onExchangeDown()`에서 `notifyExchangeDown(reason)` 전달. `emergencyStopAll()`에서 세션별 `notifySessionStopped(..., isEmergency=true)` 호출
- [x] **12/24시 요약 텔레그램 "매매 없음" 버그 수정** — `LiveTradingService`에서 `bufferTradeEvent()` 미호출이 원인. `finalizeSellPosition()` SELL 체결 확정 시 + `OrderExecutionEngine.handleBuyFill()` BUY 체결 시 각각 `telegramService.bufferTradeEvent()` 추가
- [x] **`ExchangeHealthMonitor` 연속 실패 카운터 추가** — `consecutiveFailCount` 필드 + `DOWN_THRESHOLD=3`. 1회 실패 즉시 DOWN → 3회 연속 실패 시 DOWN으로 완화. 중간은 DEGRADED
- [x] **`getPerformanceSummary()` N+1 쿼리 제거** — `PositionRepository.findBySessionIdIn()` 추가, 세션 수와 무관하게 단 1회 쿼리로 전체 포지션 로드 후 메모리 그루핑. `LiveTradingService:884`
- [x] **수수료율 하드코딩 제거** — `TradingController`의 고정 `FEE_RATE = 0.0005` → `UpbitOrderClient.getOrderChance()` 1회 호출 후 `ask_fee` 사용. API 실패 시 0.0005 폴백
- [x] **`RsiStrategy` RSI 중복 계산 제거** — `calculateRsi()` 3회 호출 → `calculateRsiSeries()` 1-pass 계산 후 인덱스 룩업으로 교체. `rsiFromAvgs()` 헬퍼 분리

### ✅ 완료 (2026-03-23) — P3 인프라·안정성

- [x] **`.env.example` 파일 추가** — 백엔드 `.env.example`에 `API_AUTH_TOKEN`, `REDIS_PASSWORD` 항목 추가. 프론트엔드 `crypto-trader-frontend/.env.example` 신규 생성 (`API_TOKEN`, `AUTH_PASSWORD`, `AUTH_SECRET`, `INTERNAL_BACKEND_URL`)
- [x] **API proxy 토큰 노출 제거** — `src/app/api/proxy/[...path]/route.ts` 신규 생성. `NEXT_PUBLIC_API_TOKEN` → 서버사이드 전용 `API_TOKEN`으로 전환. `api.ts` baseURL을 `/api/proxy`로 변경. `docker-compose.prod.yml` frontend 서비스 환경변수 반영
- [x] **`createSession()` 동시성 보호** — `synchronized` 추가 (UI 버튼 중복 클릭 수준 방어)
- [x] **`AsyncConfig` graceful shutdown** — `orderExecutor`(30s), `marketDataExecutor`(10s), `taskExecutor`(15s) 각각 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds()` 적용
- [x] **CI/CD GitHub Actions** — `.github/workflows/ci.yml` 추가. backend(Gradle + TimescaleDB + Redis 서비스 컨테이너) / frontend(npm lint + build) / Docker 이미지 빌드(main push 시)
- [x] **Config `fromParams()` 타입 안전성** — `RsiConfig`, `MacdConfig`, `AtrBreakoutConfig`, `SupertrendConfig`, `OrderbookImbalanceConfig`, `StochasticRsiConfig`에 `fromParams(Map<String,Object>)` 추가

### ✅ 완료 (2026-03-22)

- [x] **Circuit Breaker (Auto Kill-Switch)** — MDD 임계값(기본 20%) 또는 연속 손실 한도(기본 5회) 초과 시 세션 강제 비상 정지. `RiskManagementService.checkCircuitBreaker()` + Flyway V24
- [x] **Prometheus/Grafana 모니터링** — Spring Actuator + Micrometer 추가, `docker-compose.prod.yml`에 prometheus(9090) + grafana(3001) 서비스 추가. `monitoring/` 디렉터리
- [x] **텔레그램 알림 비동기화** — `telegramExecutor` 전용 스레드풀 분리, 텔레그램 HTTP 호출이 매매 루프를 블로킹하지 않도록 수정


## 2026-03-22 — PortfolioManager.totalCapital 거래소 잔고 동기화

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`PortfolioManager.syncTotalCapital()` 추가** — 거래소 잔고를 받아 `totalCapital` 갱신. `allocatedCapital`이 새 `totalCapital`을 초과하면 안전하게 조정 | `PortfolioManager.java` |
| 2 | **`EngineConfig` — `PortfolioManager` Spring Bean 등록** — 초기값 `ZERO`, 기동 직후 `PortfolioSyncService`가 동기화 | `EngineConfig.java` |
| 3 | **`PortfolioSyncService` 신규 추가** — `ApplicationReadyEvent` 시 1회 즉시 동기화 + `@Scheduled(fixedDelay=300_000)` 5분 주기 반복. KRW 가용+잠금 합계를 `totalCapital`로 갱신. API Key 미설정 시 건너뜀 | `PortfolioSyncService.java` |
| 4 | **`SchedulerConfig` 주석 업데이트** — `PortfolioSyncService.scheduledSync()` 목록 추가 | `SchedulerConfig.java` |

## 2026-03-22 — WebSocket 실시간 손절 통합

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`UpbitWebSocketClient` `@Component` + `@PreDestroy`** — Spring Bean으로 전환, `destroy()` 소멸 훅 추가 | `UpbitWebSocketClient.java` |
| 2 | **`RealtimePriceEvent` 추가** — WS 콜백 → Spring 이벤트 디커플링용 단순 POJO | `RealtimePriceEvent.java` |
| 3 | **`LiveTradingService` WS 통합** — `ApplicationEventPublisher` 주입, `UpbitWebSocketClient` optional 주입, `reconcileOnStartup()`에서 ticker 리스너 등록 + 초기 구독, `onRealtimePriceEvent()` (`@Async("marketDataExecutor")`, 5초 throttle 손절 체크), `refreshWsSubscription()` (세션 lifecycle 전체 호출) | `LiveTradingService.java` |

## 2026-03-22 — GridStrategy 세션별 격리 + CompositeStrategy 정규화

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`StrategyRegistry` 팩토리 패턴 추가** — `FACTORIES` 맵, `registerStateful()` / `isStateful()` / `createNew()` 메서드 추가. `GridStrategy`를 `registerStateful("GRID", GridStrategy::new)`로 등록 | `StrategyRegistry.java` |
| 2 | **`LiveTradingService` 세션별 GridStrategy 인스턴스 관리** — `sessionStatefulStrategies: Map<Long, Strategy>` 필드 추가. `statefulStrategy`면 세션별 인스턴스, 아니면 공유 인스턴스 사용. `stopSession` / `emergencyStop` / `deleteSession` 정리 코드 추가 | `LiveTradingService.java` |
| 3 | **`CompositeStrategy` 가중치 정규화** — 루프 후 `totalWeight`로 `buyScore` / `sellScore` 나눔. 임계값(STRONG=0.6, WEAK=0.4) 신뢰도 복원 | `CompositeStrategy.java` |

## 2026-03-22 — PortfolioManager race condition 수정

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`canAllocate()` synchronized 추가** — `allocatedCapital` 메모리 가시성 보장. 멀티 전략 동시 실행 시 두 스레드가 동시에 `true`를 받아 잔고 초과 할당하는 TOCTOU 버그 수정 | `PortfolioManager.java` |
| 2 | **`getAvailableCapital()` synchronized 추가** — 동일하게 `allocatedCapital` 비동기 읽기 가능성 차단 | `PortfolioManager.java` |

## 2026-03-22 — 제미나이 비판적 분석 반영 (태스크 추가)

| 구분 | 추가된 태스크 |
|------|-------------|
| 🔴 CRITICAL | `PortfolioManager.canAllocate()` synchronized 누락 (자본 초과 할당 race condition) |
| 🔴 CRITICAL | `docker-compose.prod.yml` DB 포트 5432 외부 노출 차단 |
| 🟡 안정성 | `GridStrategy` 세션별 인스턴스 격리 (다중 세션 상태 오염) |
| 🟡 안정성 | `CompositeStrategy` 가중치 정규화 (임계값 신뢰도 복원) |
| 🟡 안정성 | `PortfolioManager.totalCapital` 거래소 잔고 주기 동기화 |
| 🟡 보안 | Redis `requirepass` 미설정 |
| 🟡 코드 품질 | 수수료율 0.0005 하드코딩 → `getOrderChance()` API 활용 |
| 🟡 코드 품질 | `RsiStrategy` RSI 3중 중복 계산 제거 |
| 🟢 코드 품질 | `Map<String, Object>` 파라미터 타입 안전성 (P3 수준) |

> 분석 원본: `docs/crypto_autotrader_critical_analysis.md`

## 2026-03-22 — 실전매매 안정화 HIGH 2종

| # | 항목 | 파일 |
|---|------|------|
| 1 | **SchedulerConfig 스레드 풀 3→8 확장** — `@Scheduled` 작업 최소 5개(executeStrategies / reconcileClosingPositions / syncMarketData / runStrategy(Paper) / checkExchangeHealth)인데 풀이 3이어서 손절·reconcile 지연 위험. 풀 크기 8로 증가, 주석 현행화 | `SchedulerConfig.java` |
| 2 | **CLOSING 포지션 5분 타임아웃 롤백** — 매도 주문 미체결로 CLOSING 상태가 고착되면 세션 BUY 영구 차단. `closing_at` 기록 후 5분 초과 시 OPEN 롤백. V23 마이그레이션(`position.closing_at TIMESTAMPTZ`) + `PositionEntity.closingAt` 필드 + `executeSessionSell`·`closeSessionPositions`에서 closingAt 설정 + `reconcileClosingPositions()` 타임아웃 분기 추가 | `V23__add_closing_at_to_position.sql`, `PositionEntity`, `LiveTradingService` |

## 2026-03-21 — HIGH 버그 4종 수정

| # | 항목 | 파일 |
|---|------|------|
| 1 | **`API_AUTH_TOKEN` 백엔드 환경변수 추가** — `docker-compose.prod.yml` backend 서비스에 `API_AUTH_TOKEN: ${API_AUTH_TOKEN}` 주입. 미설정 시 기본값으로 운영되던 보안 취약점 해소 | `docker-compose.prod.yml` |
| 2 | **세션 평가 race condition 수정** — `evaluateAndExecuteSession()` 진입 시 DB에서 세션 상태 재확인. `stopSession()` 동시 호출 시 STOPPED 세션에 매수/매도 실행되던 문제 차단 | `LiveTradingService` |
| 3 | **세션 종료 시 size=0 포지션 KRW 복원** — `closeSessionPositions()`에서 미체결(size=0) 포지션 감지 시 FAILED BUY 주문 확인 후 KRW 복원 + CLOSED 처리. 기존엔 수량=0 SELL 주문 실패 후 KRW 영구 손실 | `LiveTradingService` |
| 4 | **`finalizeSellPosition()` 멱등성 보장** — CLOSED 상태 사전 체크 추가. `reconcileOnStartup()` + `reconcileClosingPositions()` 동시 실행 시 fee/KRW 이중 계상 방지 | `LiveTradingService` |

## 2026-03-21 — 긴급 안정화 5종

| # | 항목 | 파일 |
|---|------|------|
| 1 | **Telegram 토큰 하드코딩 제거** — `application.yml` 기본값 `${TELEGRAM_BOT_TOKEN:}` (빈 문자열). git history 노출 이력 있음 — 봇 토큰 재발급 필요 | `application.yml` |
| 2 | **CLOSING 중간 상태 + 롤백 로직** — `executeSessionSell()`·`closeSessionPositions()`에서 포지션을 CLOSING으로 표시 후 주문 제출. `reconcileClosingPositions()` (@Scheduled 5s) + `reconcileOnStartup()`에서 FILLED→CLOSED / FAILED→OPEN 롤백 처리 | `LiveTradingService` |
| 3 | **실제 체결가 기반 손익 계산** — `finalizeSellPosition()`에서 `filledOrder.getPrice()` 사용. `OrderExecutionEngine.handleSellFill()` 세션 주문 skip (세션 주문 처리는 reconcile에 위임) | `LiveTradingService`, `OrderExecutionEngine` |
| 4 | **손실 전략 차단** — `BLOCKED_LIVE_STRATEGIES = [STOCHASTIC_RSI, MACD]` 상수 추가, `createSession()`에서 검증 후 `IllegalArgumentException` 발생 | `LiveTradingService` |
| 5 | **V22 마이그레이션 커밋** — `position_fee NUMERIC(20,2) NOT NULL DEFAULT 0` 컬럼 추가 SQL 파일 커밋 | `V22__add_position_fee_to_position.sql` |

## 2026-03-21 — 리팩토링 (코드 품질 개선, 기능 변경 없음)

| 파일 | 변경 내용 |
|------|-----------|
| `PositionEntity` | `@Getter` `@Setter` 추가 → 명시적 getter/setter 40줄 제거 |
| `UpbitRestClient` | `buildGetRequest(url)` 헬퍼 추출 → GET 요청 빌드 3곳 중복 제거, `getTickerIndividually()` 루프 변수 재할당 제거 |
| `LiveTradingService` | `java.util.ArrayList<>()` → `ArrayList<>()`, `java.time.Instant` 로컬 변수 → `Instant` (FQN 제거) |
| `TelegramNotificationService` | `@Autowired` 필드 주입 → `final` + `@RequiredArgsConstructor` 생성자 주입, 파라미터 FQN(`java.math.BigDecimal`) → `BigDecimal` |

## 2026-03-21 — 전체 시스템 비판적 분석

| 구분 | 발견 내용 |
|------|-----------|
| 🔴 보안 | `application.yml`에 Telegram 봇 토큰 평문 하드코딩 (git 노출) |
| 🔴 버그 | `executeSessionSell()` — @Async 주문 실패해도 포지션 CLOSED + KRW 즉시 복원 (이중 계상) |
| 🔴 버그 | 손익 계산이 실제 체결가 아닌 캔들 종가 추정값 기반 |
| 🔴 배포 | `V22__add_position_fee_to_position.sql` untracked (미커밋) |
| 🔴 운영 | 백테스트 손실 전략(STOCHASTIC_RSI, MACD) 실전매매에서 선택 가능 |
| 🟡 보안 | Swagger 인증 없이 공개 / CORS 전체 허용 / API 토큰 기본값 취약 |
| 🟡 안정성 | 손절 체크 60초 지연 / 세션 생성 race condition / 매도 reconcile 없음 |
| 🟡 안정성 | MarketRegimeDetector 재시작 시 상태 초기화 |
| 🟢 인프라 | CI/CD 없음 / DB 백업 없음 / SchedulerConfig 주석 오래됨 |
| 🟢 테스트 | web-api 테스트가 H2 기반 + Happy Path 없음. LiveTradingService 테스트 전무 |

## 2026-03-21 — 실전매매 안정화 4종

| # | 항목 | 파일 |
|---|------|------|
| 1 | **장애 복구** — `reconcileOnStartup()` (`@EventListener(ApplicationReadyEvent)`) 추가: PENDING+exchangeOrderId=null → FAILED / OPEN+size=0 → 포지션 강제 종료+KRW 복원 | `LiveTradingService` |
| 2 | **실전매매 수수료 추적** — V22 migration (`position_fee NUMERIC(20,2)`) + `PositionEntity.positionFee` + `executeSessionSell()`에서 fee 저장 + `getPerformanceSummary()`에서 정상 집계 | `PositionEntity`, `V22__*.sql`, `LiveTradingService` |
| 3 | **텔레그램 낙폭 경고** — 손절 한도 50% 이상 손실 시 `DRAWDOWN_WARNING` 알림 (30분 쿨다운, `lastDrawdownWarning` ConcurrentHashMap). stopSession/emergencyStop/deleteSession 시 cleanup | `LiveTradingService`, `TelegramNotificationService` |
| 4 | **ORDERBOOK REST 호가창 연동** — `UpbitRestClient.getOrderbook()` 추가 (`GET /v1/orderbook`). `LiveTradingService`에 `@Autowired(required=false) UpbitRestClient` 주입 → ORDERBOOK_IMBALANCE 전략 평가 전 `bidVolume`/`askVolume` 실값 주입 (실패 시 캔들 근사 폴백) | `UpbitRestClient`, `LiveTradingService` |

## 2026-03-21 — Upbit API 테스트 페이지 보강

| 항목 | 내용 |
|------|------|
| `GET /api/v1/settings/upbit/ticker` | 현재가 조회 엔드포인트 추가 (공개 API, 인증 불필요) |
| `settingsApi.upbitTicker()` | 프론트엔드 API 함수 추가 |
| `upbit-status/page.tsx` 전면 재작성 | slate- 계열로 디자인 통일, 상태 카드 4개(API키·잔고·WebSocket·캔들), 잔고 상세(보유코인 테이블), 현재가 조회 섹션 신규 추가 |

## 2026-03-21 — 성과 대시보드 코드 검증

| 항목 | 결과 |
|------|------|
| API 연결 (`tradingApi.getPerformance` / `paperTradingApi.getPerformance`) | ✅ 정상 |
| 타입 매핑 (`PerformanceSummary` ↔ `PerformanceSummaryResponse`) | ✅ 정상 |
| 세션 0건 처리 | ✅ 빈 리스트 → "데이터가 없습니다" |
| 실전매매 수수료 집계 | ❌ `BigDecimal.ZERO` 하드코딩 (V22 TODO) |

## 2026-03-21 — 손익 대시보드 및 성과 통계 구현

| 항목 | 내용 |
|------|------|
| `PerformanceSummaryResponse` DTO | 전체 집계 + 세션별 성과 내역 (세션 수 / 승률 / 수익률 / 수수료 등) |
| `LiveTradingService.getPerformanceSummary()` | 실전매매 전 세션 PositionEntity 기반 집계 |
| `PaperTradingService.getOverallPerformance()` | 모의투자 전 세션 VirtualBalanceEntity 기반 집계 |
| `GET /api/v1/trading/performance` | 실전매매 성과 API |
| `GET /api/v1/paper-trading/performance` | 모의투자 성과 API |
| `/performance` 페이지 | 실전/모의 탭 전환, 요약 카드 7개, 세션별 테이블 |
| 사이드바 | 실전매매 그룹에 "손익 대시보드" 항목 추가 (PieChart 아이콘) |

## 2026-03-20 — 코드 보완 전체 완료

| 항목 | 내용 |
|------|------|
| null 안전 | `evaluateAndExecuteSession()` — `stopLossPct` null 시 기본값 5.0 (NPE 방어) |
| 실전매매 다중전략 | `MultiStrategyLiveTradingRequest` DTO + `POST /api/v1/trading/sessions/multi` + 프론트 체크박스 UI (2개 이상 선택 시 일괄 생성) |
| position_fee | V20 migration + `PaperPositionEntity.positionFee` + `executeBuy()`·`closePosition()` 누적 |
| version 낙관적 락 | V21 migration + `VirtualBalanceEntity.@Version` JPA 낙관적 락 |

## 2026-03-20 — 실전매매 세션별 투자비율(investRatio) 설정

- `live_trading_session.invest_ratio` 컬럼 추가 (V19, 기본 0.8000)
- `executeSessionBuy()` — 하드코딩 80% → `session.getInvestRatio()` + `maxInvestment` 절대 상한
- 세션 생성 폼에 투자비율 입력 추가 (1~100%, 기본 80%, TEST_TIMED 숨김)

**동작**: `availableKrw × investRatio`가 매수금액. `maxInvestment` 설정 시 그 값이 절대 상한.

## 2026-03-20 — 모의투자 실현손익·누적수수료 추적

- `virtual_balance`에 `realized_pnl`, `total_fee` 컬럼 추가 (V18)
- 매수/매도 체결 시마다 누적 저장 → 세션 종료 후에도 조회 가능
- 프론트엔드 SessionCard + 잔고 카드에 표시

## 2026-03-20 — 프론트엔드 로그인 인증

- Next.js middleware → `auth_session` 쿠키 미존재 시 `/login` 리다이렉트
- `AUTH_PASSWORD` / `AUTH_SECRET` 환경변수로 비밀번호 관리
- **운영서버 배포 시**: `docker-compose.prod.yml` frontend 서비스에 두 환경변수 추가 필요

## 2026-03-19~20 — 실전매매 소액 테스트 검증 완료

- TEST_TIMED / ORDERBOOK_IMBALANCE / COMPOSITE 각 1만원 매수·매도 사이클 ✅ 확인
- 2026-03-19 07:00 ~ 지속 운영 중
- 리스크 한도(`riskManagementService.checkRisk()`) BUY 진입 전 연동 완료
