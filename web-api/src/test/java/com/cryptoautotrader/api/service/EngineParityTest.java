package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4엔진 정합성 감사 — 2026-08-19 신설, 2026-09-08 BACKTEST 축 추가.
 *
 * <h3>왜 이 테스트가 존재하는가</h3>
 * <p>청산·리스크 규칙을 구현한 곳이 넷이다: {@link LiveTradingService} · {@link DynamicTradingService} ·
 * {@link PaperTradingService} · {@code BacktestEngine}(core-engine 모듈). 앞의 셋은 각자 세션 테이블·
 * 포지션 테이블·틱 루프·reconciler 를 따로 갖고, <b>교차 규칙을 모든 곳에 적용했는지 강제하는
 * 장치가 없었다.</b>
 * 그 결과 08-17~08-19 사흘간 나온 결함이 거의 전부 같은 모양이었다 —
 * "한 엔진에 적용하고 나머지를 잊는다":</p>
 * <ul>
 *   <li>{@code LOSS_ESCAPE_THRESHOLD} 가 4곳에 복제, LIVE↔PAPER 만 테스트로 보호 → DYNAMIC 드리프트</li>
 *   <li>{@code strategy_type_enabled} 검사가 3개 생성 경로 중 DYNAMIC 에만 → 폐기 우회로 2개</li>
 *   <li>{@code markClosingIfOpen}(원자적 CLOSING)이 DYNAMIC 에만 → LIVE 중복 매도</li>
 *   <li>{@code tickCandleCache} 가 PAPER 에만 → DYNAMIC 이 API 예산의 89% 소비</li>
 *   <li>kill criteria 가 {@code paper_trading} 스키마를 몰라 페이퍼 112세션이 판정 대상 밖</li>
 * </ul>
 *
 * <p>※ 엔진별 줄 수는 여기 적지 않는다. 과거 이 자리에 박아둔 수치(2,787 · 2,246 · 1,032)는
 * 갱신되지 않은 채 남았고, 2026-09-15 구조 리뷰에서 서로 다른 숫자를 근거로 논쟁이 벌어졌다.
 * 규모가 필요하면 그때 세고, 주석에 고정하지 말 것.</p>
 *
 * <h3>이 테스트가 하는 일</h3>
 * <p>교차 규칙별로 <b>어느 엔진에 있어야 하는가</b>를 선언하고 소스에서 검증한다.
 * BACKTEST 축은 {@code ../core-engine/...} 상대 경로로 읽으므로 web-api 모듈 밖의 파일도 대상이다.
 * 의도적으로 없는 칸은 사유와 함께 고정한다 — 나중에 "왜 없지?" 를 다시 조사하지 않도록.
 * 새 규칙을 한 엔진에만 넣으면 여기서 깨진다.</p>
 *
 * <p><b>한계</b>: 소스 문자열 검사라 "호출된다"까지만 보고 "올바르게 호출된다"는 못 본다.
 * 즉 <b>네 엔진의 동작 결과가 같다는 것을 증명하지 않는다</b> — 규칙이 한 엔진에만 들어가는
 * 회귀를 잡는 정적 감사다.
 * 파라미터 값의 동등성은 {@link PaperLiveAlignmentTest} 가 따로 담당한다.
 * 둘은 보완 관계이며 어느 쪽도 다른 쪽을 대체하지 않는다.</p>
 */
class EngineParityTest {

    private static final Path SERVICE_DIR =
            Path.of("src/main/java/com/cryptoautotrader/api/service");

    private static final Map<String, String> SOURCES = new LinkedHashMap<>();

    private static String source(String engine) {
        return SOURCES.computeIfAbsent(engine, e -> {
            try {
                return Files.readString(SERVICE_DIR.resolve(e + "TradingService.java"));
            } catch (IOException ex) {
                throw new IllegalStateException("엔진 소스를 읽을 수 없습니다: " + e, ex);
            }
        });
    }

    /**
     * BACKTEST 축 (2026-09-08 추가) — `BacktestEngine` 은 core-engine 모듈에 있어 위 SERVICE_DIR
     * 규칙에 맞지 않으므로 경로를 따로 둔다. 매매를 실행하지 않지만 그 결과가 WF 게이트를 통해
     * 실자본 배정을 결정하므로 정합성 축에 포함한다.
     */
    private static final Path BACKTEST_ENGINE = Path.of(
            "../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java");

    private static String backtestSource() {
        try {
            return Files.readString(BACKTEST_ENGINE);
        } catch (IOException ex) {
            throw new IllegalStateException("BacktestEngine 소스를 읽을 수 없습니다: " + BACKTEST_ENGINE, ex);
        }
    }

    /**
     * 주석(블록 {@code /* *}/ · 라인 {@code //})을 지운다 — <b>설명문이 가드를 통과시키는 것을 막는다.</b>
     *
     * <p>이 저장소에서 두 번 실제로 일어났다: 08-06 SL 조임 제거를 <b>설명한 주석</b>에
     * {@code getTrailingSlMargin} 이 걸려 LIVE 가 조임을 유지하는 것처럼 판정됐고,
     * 09-08 에는 {@code calculateStopLevels} 호출을 없앴는데 그 사실을 적은 주석 때문에
     * {@code contains("calculateStopLevels")} 가 그대로 통과했다.</p>
     *
     * <p>문자열 리터럴 안의 {@code //} 까지 구분하지는 않는다 — 이 테스트가 찾는 토큰은
     * 전부 코드 식별자라 그 정도로 충분하다.</p>
     */
    private static String stripComments(String source) {
        String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlocks.lines()
                .map(l -> {
                    int i = l.indexOf("//");
                    return i >= 0 ? l.substring(0, i) : l;
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 세미콜론으로 구분된 대안 이름 중 하나라도 있으면 true (엔진마다 이름이 다른 경우 대응). */
    private static boolean has(String engine, String tokens) {
        for (String t : tokens.split(";")) {
            if (source(engine).contains(t.trim())) return true;
        }
        return false;
    }

    // ── 세 엔진 모두에 있어야 하는 규칙 ───────────────────────────────────────

    /**
     * 매매 판단의 핵심 규칙들. 하나라도 빠지면 그 엔진만 다른 규칙으로 매매한다 —
     * 페이퍼 결과로 실전을 예측한다는 전제가 깨진다(08-06 PAPER↔LIVE 정렬 작업의 목적).
     */
    @ParameterizedTest(name = "[{0}] {1} 은 LIVE·DYNAMIC·PAPER 모두에 있어야 한다")
    @CsvSource({
            "청산 규칙 산정,   ExitRuleCalculator",
            "시간 초과 청산,   shouldTimeStop",
            "블랙스완 가드,    BlackSwanGuard",
            "BTC 시장 가드,    BtcMarketGuard",
            "시장 레짐 감지,   MarketRegimeDetector",
            "닫힌 캔들 게이팅, lastEvaluatedCandle;lastEvaluatedClosedCandle",
            "비활성 전략 차단, strategyEnablementGate",
    })
    @DisplayName("교차 규칙 3엔진 적용")
    void ruleAppliedToAllEngines(String label, String token) {
        assertThat(has("Live", token)).as("%s — LIVE 누락", label).isTrue();
        assertThat(has("Dynamic", token)).as("%s — DYNAMIC 누락", label).isTrue();
        assertThat(has("Paper", token)).as("%s — PAPER 누락", label).isTrue();
    }

    // ── 실거래 경로에만 있어야 하는 규칙 ──────────────────────────────────────

    /**
     * 거래소 비동기 체결·실자본을 전제하는 장치. PAPER 는 체결이 동기 시뮬레이션이고
     * 거래 리포트를 {@code bufferTradeEvent}(일일 다이제스트)로 내보내므로 해당 사항이 없다.
     */
    @ParameterizedTest(name = "[{0}] {1} 은 LIVE·DYNAMIC 에만")
    @CsvSource({
            "원자적 CLOSING 전환, markClosingIfOpen",
            "서킷 브레이커,       checkCircuitBreaker",
            "시간초과 즉시 알림,  notifyTimeStop",
            "손절 즉시 알림,      notifyStopLoss",
    })
    @DisplayName("실거래 전용 규칙 — PAPER 에는 없는 것이 정상")
    void realOnlyRules(String label, String token) {
        assertThat(has("Live", token)).as("%s — LIVE 누락", label).isTrue();
        assertThat(has("Dynamic", token)).as("%s — DYNAMIC 누락", label).isTrue();
        assertThat(has("Paper", token))
                .as("%s — PAPER 에 생겼다. 페이퍼는 일일 다이제스트로 보고하므로 즉시 알림을 "
                        + "붙이면 112세션이 알림 폭탄이 된다. 의도한 변경이면 이 테스트도 고칠 것.", label)
                .isFalse();
    }

    // ── 의도적 비대칭 — 사유와 함께 고정 ──────────────────────────────────────

    @Test
    @DisplayName("N/A 고정: 자본 배정 게이트 2종은 PAPER 에 걸지 않는다")
    void capitalAllocationGatesSkipPaper() {
        // 이 두 게이트는 "실자본을 쓸 자격이 있는가" 를 묻는다. 페이퍼는 정확히 그 자격을
        // 얻기 전에 검증하는 도구이므로, 적용하면 검증 경로 자체가 사라진다 (2026-08-06 판단).
        for (String token : new String[]{"walkForwardValidationGate", "strategyLiveStatusRegistry"}) {
            assertThat(has("Live", token)).as("%s — LIVE 에는 있어야 한다", token).isTrue();
            assertThat(has("Dynamic", token)).as("%s — DYNAMIC 에는 있어야 한다", token).isTrue();
            assertThat(has("Paper", token))
                    .as("%s — PAPER 에 생겼다. 페이퍼가 막히면 검증 경로가 사라진다", token)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("N/A 고정: 교차 세션 노출 한도는 DYNAMIC 전용")
    void crossSessionExposureIsDynamicOnly() {
        // 동적 세션만 워치리스트에서 코인을 골라 서로 같은 종목에 겹칠 수 있다.
        // LIVE 는 세션당 코인이 고정이고, PAPER 는 실자본이 아니라 노출 한도 개념이 없다.
        assertThat(has("Dynamic", "crossSession;CrossSession")).isTrue();
        assertThat(has("Live", "crossSession;CrossSession")).isFalse();
        assertThat(has("Paper", "crossSession;CrossSession")).isFalse();
    }

    // ── 알려진 결함 — 해소되면 이 테스트가 깨져서 알려준다 ──────────────────────

    @Test
    @DisplayName("닫힌 캔들 게이트를 세 엔진이 같은 이름으로 부른다 (2026-09-08 결함 #3 해소)")
    void closedCandleGateNamingIsConsistent() {
        // 해소 전: LIVE·PAPER 는 lastEvaluatedClosedCandle, DYNAMIC 만 lastEvaluatedCandle.
        // 기능은 같았지만 이름이 달라 **grep 기반 감사가 오탐을 냈다** — 이 테스트를 처음 쓸 때
        // "LIVE 에 없다" 로 한 번, 고친 뒤 "PAPER 에 없다" 로 또 한 번 틀렸다.
        //
        // 순수 리네이밍이라 우선순위가 낮아 보였지만, 이 저장소의 반복 결함이 "규칙이 한 엔진에만
        // 적용됐는지" 를 사람이 확인하다 놓치는 것이라는 점에서 우선순위가 낮지 않다 —
        // 감사 도구가 못 믿을 이름을 남겨 두면 감사 자체가 헛돈다.
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(has(engine, "lastEvaluatedClosedCandle"))
                    .as("%s 가 닫힌 캔들 게이트를 다른 이름으로 부른다 — 이름이 갈리면 감사가 오탐을 낸다",
                            engine)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("TP 트레일링은 세 엔진이 같은 함수를 쓴다 (2026-09-08 결함 #1 해소)")
    void trailingUsesSharedCalculatorInAllEngines() {
        // 해소 전 상태 — 세 엔진이 세 가지로 달랐다:
        //   LIVE     spikeUp(30초 +2.0%) 에 걸린 틱에서만 자체 공식으로 TP 상향
        //   PAPER    수익이면 매 틱 ExitRuleChecker.updateTrailingStops
        //   DYNAMIC  없음 — 진입 시 TP 를 한 번 정하고 끝
        // 완만하게 오르는 포지션은 LIVE 에서만 TP 가 고정됐고, DYNAMIC 은 아예 안 움직였다.
        // 같은 전략이 엔진마다 다르게 청산된다는 뜻이라 "페이퍼로 실전을 예측한다"는
        // 전제가 깨진다. 이제 셋 다 같은 함수를 호출한다.
        //
        // ⚠️ 이 함수는 TP 만 올린다. 손실 구간 SL 조임은 되살리지 말 것 —
        //    아래 noEngineTightensStopLossOnLoss 와 ExitRuleChecker javadoc 참조.
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(has(engine, "updateTrailingStops"))
                    .as("%s 가 공용 TP 트레일링을 쓰지 않는다 — 엔진별 자체 공식이 되살아났는지 확인", engine)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("어느 엔진도 손실 구간에서 SL 을 조이지 않는다 (2026-09-08 회귀 방지)")
    void noEngineTightensStopLossOnLoss() {
        // 손실 중 SL 을 현재가/저점 쪽으로 끌어올리는 것은 "다음 틱에 강제청산을 예약"하는
        // 동작이다. 하락 방어는 진입 시점에 확정된 ATR 기반 SL 이 담당한다.
        //
        // 같은 결함이 두 번 나왔다:
        //   2026-08-06  LIVE   급락 감지 시 trailingSlMargin(0.3%) 로 SL 조임 → 제거
        //   2026-09-08  PAPER  ExitRuleChecker.updateTrailingStops 손실 분기 → 제거
        // 08-06 에 LIVE·DYNAMIC 만 고치고 PAPER 전용 경로를 빠뜨린 것이 한 달간 살아남아
        // 운영 손실 −1,270만원을 만들었다. 세 번째가 없도록 여기서 고정한다.
        //
        // 검사 대상은 엔진이 조임 마진을 **실제로 읽는지**(getter 호출)다. LIVE 소스에는
        // 제거를 기록한 주석에 `trailingSlMargin(0.3%)` 이라는 문구가 남아 있어, 느슨한
        // 토큰으로 검사하면 그 주석이 오탐을 낸다 — 접근자 이름만 본다.
        // ExitRuleChecker 자체의 거동은 ExitRuleCheckerTest §15 가 값으로 검증한다
        // (문자열 검사보다 강하다).
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(has(engine, "getTrailingSlMargin"))
                    .as("%s 가 손실 구간 SL 조임을 되살렸다 — 위 주석의 두 사고를 다시 읽을 것", engine)
                    .isFalse();
        }
    }

    /**
     * 틱 캔들 캐시를 세 엔진이 공유한다 — 2026-09-08 해소.
     *
     * <p>PAPER 만 틱당 {@code (코인, 타임프레임)} 캔들을 한 번 조회해 세션 수와 무관하게 비용이
     * 고정됐고, LIVE·DYNAMIC 은 세션마다 독립 조회였다. 08-19 감사가 "한 엔진에만 적용하고
     * 나머지를 잊는다" 목록에 올려 둔 마지막 항목이다.</p>
     *
     * <p>당시 근거였던 "DYNAMIC 이 전체 API 요청의 89%" 는 08-26 에 {@code fetchWithCache}
     * (market_data_cache 경유, 갭만 REST)가 들어가면서 이미 낡은 수치다. 남아 있던 낭비는
     * <b>틱 안에서 같은 조합을 세션 수만큼 다시 조회</b>하는 쪽이었고, 동적 세션은 워치리스트를
     * 세션마다 통째로 훑으므로 그 배수가 컸다.</p>
     *
     * <p>구현은 {@code TickCandleCache} 하나다 — 동작은 {@code TickCandleCacheTest} 가 검증한다.</p>
     */
    @Test
    @DisplayName("틱 캔들 캐시를 세 엔진이 공유한다 (2026-09-08 해소)")
    void tickCandleCacheIsShared() {
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(stripComments(source(engine)))
                    .as("%s 가 틱 스코프를 열지 않는다 — 그 엔진만 세션 수만큼 중복 조회한다", engine)
                    .contains("TickCandleCache.begin()");
            assertThat(stripComments(source(engine)))
                    .as("%s 가 틱 스코프를 닫지 않는다 — 다음 틱이 옛 캔들로 매매한다", engine)
                    .contains("TickCandleCache.end()");
        }
    }

    // ── 상수 단일 출처 가드 ────────────────────────────────────────────────────

    /**
     * 세 엔진이 같은 값을 써야 하는 상수를 <b>각자 하드코딩하지 못하게</b> 막는다.
     *
     * <p>{@code CANDLE_LOOKBACK} 은 LIVE·DYNAMIC·PAPER·BacktestEngine 네 곳에 각각 500 으로
     * 박혀 있었고, PAPER 주석에는 "백테스트·실거래와 동일하게 맞춰야 한다" 는 <b>수동 동기화
     * 지시</b>만 있었다. {@code SLIPPAGE_PCT} 도 세 곳에 0.001 로 복제돼 있었다 —
     * 그중 하나는 2026-08-19 에 규칙 지문을 만들면서 내가 새로 추가한 것이었다.</p>
     *
     * <p>지문이 실제 거동과 어긋나면 지문이 거짓말을 하는 것이라 가장 나쁜 종류의 결함이 된다.</p>
     */
    @ParameterizedTest(name = "[{0}] 리터럴 하드코딩 금지 — TradingConstants 참조")
    @CsvSource({
            "캔들 조회 개수,   CANDLE_LOOKBACK = 500",
            "모의 슬리피지,    SLIPPAGE_PCT = new BigDecimal(\"0.001\")",
    })
    @DisplayName("공통 상수는 TradingConstants 단일 출처를 쓴다")
    void sharedConstantsAreNotRehardcoded(String label, String literal) {
        for (String engine : new String[]{"Live", "Dynamic", "Paper"}) {
            assertThat(source(engine))
                    .as("%s — %s 에 리터럴이 다시 박혔다. TradingConstants 를 참조할 것", label, engine)
                    .doesNotContain(literal);
        }
    }

    @Test
    @DisplayName("세 엔진의 CANDLE_LOOKBACK 이 실제로 같은 값이다")
    void candleLookbackIsIdenticalAcrossEngines() {
        assertThat(com.cryptoautotrader.api.util.TradingConstants.CANDLE_LOOKBACK)
                .as("백테스트 BacktestEngine.MAX_LOOKBACK 과도 같아야 한다 — 다르면 같은 전략이 "
                        + "백테스트와 실거래에서 다른 지표값을 본다")
                .isEqualTo(500);
        for (String engine : new String[]{"Live", "Dynamic", "Paper"}) {
            assertThat(has(engine, "TradingConstants.CANDLE_LOOKBACK"))
                    .as("%s 가 공통 상수를 참조하지 않는다", engine).isTrue();
        }
    }

    // ── BACKTEST 축 (2026-09-08 추가) ───────────────────────────────────────

    @Test
    @DisplayName("BACKTEST 도 매매 엔진과 같은 청산 규칙 모듈을 쓴다")
    void backtestSharesExitRuleModule() {
        // BacktestEngine 은 매매를 실행하지 않지만, 그 결과가 WalkForwardValidationGate 를 통해
        // **실자본 배정을 결정한다**. 축에서 빼면 "그 엔진이 만든 근거로 매매를 허가하는 경로"가
        // 감시 밖에 남는다 — 09-08 에 WF 350건이 통째로 무효가 된 원인이 이 누락이었다.
        assertThat(backtestSource()).contains("ExitRuleChecker");
    }

    /**
     * 네 경로가 <b>같은 SL/TP 공식</b>을 쓴다 — 2026-09-08 해소.
     *
     * <p>그전까지 백테스트만 {@code ExitRuleChecker.calculateStopLevels}(SL 5% 고정 · TP 10%)를,
     * 세 매매 엔진은 {@code ExitRuleCalculator.resolveStopLossPct}(ATR 기반 5~8% · TP ≤8%)를 썼다.
     * 원인은 <b>패키지 배치</b>였다: 공식이 web-api 에 있고 백테스트는 core-engine 이라
     * 모듈 의존 방향상 호출 자체가 불가능했다. 공식을 core-engine 의 {@code ExitRuleFormula} 로
     * 올려 해소했다.</p>
     *
     * <p><b>주석이 아니라 호출을 본다.</b> 이전 버전은 {@code contains("calculateStopLevels")} 로
     * 검사했는데, 수정 뒤 그 단어가 <b>설명 주석에만</b> 남았는데도 통과했다 — 08-06 제거 이력을
     * 적어 둔 주석에 {@code getTrailingSlMargin} 이 걸렸던 것과 같은 오탐이다.</p>
     */
    @Test
    @DisplayName("BACKTEST 가 세 엔진과 같은 SL/TP 공식을 쓴다 (주석 제외, 실제 호출 검사)")
    void backtestUsesSharedExitFormula() {
        String code = stripComments(backtestSource());

        assertThat(code)
                .as("백테스트가 공용 공식을 호출하지 않는다 — ExitRuleChecker.calculateStopLevels 는 "
                        + "이름만 비슷한 다른 함수이고 atrStopLossEnabled 기본값 false 때문에 항상 5%% 고정이다")
                .contains("ExitRuleFormula.resolveStopLossPct")
                .contains("ExitRuleFormula.resolveTakeProfitPrice");
        assertThat(code)
                .as("자체 SL/TP 산정으로 되돌아갔다 — 되돌리려면 ENGINE_PARITY.md 매트릭스도 함께 고칠 것")
                .doesNotContain("calculateStopLevels");
    }

    @Test
    @DisplayName("BACKTEST 에 시간 초과 청산이 있다 — 운영 청산의 47%를 차지하는 경로")
    void backtestHasTimeStop() {
        // 운영 동적 세션 청산 83건 중 TIME_STOP 이 39건(47%)이다. 백테스트에 그 경로가 없으면
        // 거래 모집단 자체가 달라져, "백테스트로 검증하고 실전에 올린다"는 절차가 성립하지 않는다.
        // 2026-09-08 해소 — BacktestConfig.maxHoldHours 신설(BacktestExitRuleParityTest 가 동작 검증).
        assertThat(stripComments(backtestSource()))
                .as("BACKTEST 의 time stop 이 사라졌다 — 실전 거래의 절반 가까이가 재현되지 않는다")
                .contains("ExitRuleFormula.shouldTimeStop");
    }

    /**
     * 전략 SELL 게이트가 <b>네 경로 모두 한 함수</b>다 — 2026-09-08 해소.
     *
     * <p>그전까지 BACKTEST 만 {@code ExitRuleChecker.allowsSignalExit} 을 쓰고 세 매매 엔진은
     * 같은 판정을 인라인으로 복제했다. 상수는 09-08 에 {@code ExitRuleConfig} 위임으로 통일했지만
     * ({@code EngineConstantParityTest}) <b>조건식 자체는 여전히 네 벌</b>이었다 —
     * 한쪽 부등호만 바뀌어도 아무도 모르는 상태였다.</p>
     *
     * <p>이제 넷 다 {@code SignalExitGate} 를 호출한다. 세 엔진은 임계값을 직접 넘기므로
     * DYNAMIC 의 세션별 A/B 오버라이드({@code ExitRuleOverrides})도 그대로 살아 있다.</p>
     */
    @Test
    @DisplayName("전략 SELL 게이트를 네 경로가 공유한다 (주석 제외, 실제 호출 검사)")
    void signalExitGateIsShared() {
        assertThat(stripComments(backtestSource()))
                .as("BACKTEST 가 공용 게이트를 쓰지 않는다")
                .contains("allowsSignalExit");

        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(stripComments(source(engine)))
                    .as("%s 가 SELL 게이트를 다시 인라인으로 구현했다 — 조건식이 갈리면 "
                            + "같은 전략이 엔진마다 다르게 청산된다", engine)
                    .contains("SignalExitGate.decide");
        }
    }

    /**
     * 판정 <b>구현</b>이 한 곳뿐이다 — 호출만 공유하고 조건식을 남겨 두면 의미가 없다.
     *
     * <p>{@code SignalExitGate} 로 옮긴 뒤 각 엔진에 남아 있던 인라인 비교
     * ({@code compareTo(LOSS_ESCAPE_THRESHOLD) >= 0} 형태)가 지워졌는지 본다. 남아 있으면
     * 두 판정이 공존하게 되고, 나중에 한쪽만 고쳐지는 원래 상태로 돌아간다.</p>
     */
    @Test
    @DisplayName("SELL 판정 조건식이 엔진에 남아 있지 않다")
    void signalExitConditionIsNotDuplicated() {
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(stripComments(source(engine)))
                    .as("%s 에 본전가드 조건식이 남아 있다 — SignalExitGate 와 두 벌이 된다", engine)
                    .doesNotContain("compareTo(LOSS_ESCAPE_THRESHOLD) >= 0");
        }
    }

    /**
     * 워밍업 계약 (Wave 4-N, 2026-09-22).
     *
     * <p>해소 전 상태 — 엔진마다 기준이 달랐다:
     * <ul>
     *   <li>{@code BacktestEngine} — {@code strategy.getMinimumCandleCount()} ✓</li>
     *   <li>{@code DynamicTradingService} — 같음 ✓ (2026-08-31 에 하드코딩 15 제거)</li>
     *   <li>{@code LiveTradingService} — <b>하드코딩 10</b> ✗</li>
     *   <li>{@code PaperTradingService} — <b>하드코딩 10</b> ✗</li>
     * </ul>
     * 10 은 어떤 전략의 요구량도 아니다 — HEIKIN_ASHI_STOCH 205 ·
     * COMPOSITE_PULLBACK_MTF 201 · GRID 100. 미달이어도 평가에 들어가
     * 전략 내부 가드가 HOLD 를 돌려주므로 잘못된 신호는 안 나오지만,
     * <b>장기 지표가 조용히 비활성된 채로 도는 것을 아무도 모른다.</b>
     * PAPER 는 함대 표본을 만드는 엔진이라 특히 문제였다.
     */
    @Test
    @DisplayName("세 엔진이 전략 선언 최소 캔들 수를 같은 출처로 묻는다 (Wave 4-N)")
    void minimumCandleContractIsAskedByAllEngines() {
        for (String engine : new String[] {"Live", "Dynamic", "Paper"}) {
            assertThat(stripComments(source(engine)))
                    .as("%s 가 전략 선언 최소 캔들 수를 묻지 않는다 — "
                            + "하드코딩 문턴은 어느 전략의 요구량도 아니다", engine)
                    .satisfiesAnyOf(
                            src -> assertThat(src).contains("getMinimumCandleCount"),
                            src -> assertThat(src).contains("minimumCandlesFor"));
        }
        assertThat(stripComments(backtestSource()))
                .as("BACKTEST 가 전략 선언 최소 캔들 수를 묻지 않는다")
                .contains("getMinimumCandleCount");
    }

    /**
     * 캔들 미달이라고 <b>사이클을 통째로 건너뛰면 안 된다</b> (Wave 4-N).
     *
     * <p>LIVE·PAPER 는 캔들 판정 <b>뒤에서</b> 손절·익절·타임스톱을 처리한다.
     * 문턴만 올리고 {@code return} 하면 캔들이 모자란 동안
     * <b>열린 포지션이 방치된다</b> — 지금까지 정상 처리되던 구간이
     * 통째로 사각지대가 된다. 그래서 <b>평가만</b> 건너뛴다.
     *
     * <p>DYNAMIC 은 진입 후보를 훑는 루프라 그 구간에 포지션이 없어
     * {@code continue} 로 막아도 된다. <b>같은 수정을 그대로 옮기면 안 되는 이유다.</b>
     */
    @Test
    @DisplayName("LIVE·PAPER 는 캔들 미달 시 평가만 건너뛰고 청산은 계속한다 (Wave 4-N)")
    void shortCandlesSkipEvaluationNotTheWholeCycle() {
        for (String engine : new String[] {"Live", "Paper"}) {
            String code = stripComments(source(engine));
            assertThat(code)
                    .as("%s 에 캔들 미달 플래그가 없다", engine)
                    .contains("candlesShortOfStrategy");
            // 공백을 뭉개고 본다 — 포매팅이 바뀌어도, 플래그가 **HOLD 대입으로 이어지는지**만 본다.
            // (단순 contains 로는 플래그가 가드에만 있고 신호 분기에서 빠져도 통과한다 —
            //  실제로 이 테스트의 첫 판이 그 뮤테이션을 놓쳤다.)
            // ⚠️ "\\s+" 여야 한다. Java 15+ 에서 "\s" 는 **공백 한 칸 이스케이프**라
            //    "\s+" 는 정규식 \s+ 가 아니라 문자열 " +" 가 된다 — 컴파일은 되는데
            //    줄바꿈이 안 뭉개져 아래 단정이 항상 실패한다. 실제로 한 번 그렇게 썼다.
            String flat = code.replaceAll("\\s+", " ");
            // 플래그가 신호 분기에서 쓰여야 한다 — 그래야 평가만 건너뛰고
            // 아래 청산 로직이 그대로 돌아간다.
            assertThat(code)
                    .as("%s 가 캔들 미달을 신호 분기에서 다루지 않는다 — "
                            + "return 으로 막으면 열린 포지션의 손절·익절이 멈춘다", engine)
                    .contains("if (candlesShortOfStrategy) {");
            assertThat(flat)
                    .as("%s 가 캔들 미달을 **신호 분기**에서 HOLD 로 처리하지 않는다 — "
                            + "가드에만 두고 끝내면 아무 효과가 없고, return 으로 막으면 "
                            + "열린 포지션의 손절·익절이 멈춘다", engine)
                    .contains("if (candlesShortOfStrategy) { signal = StrategySignal.hold(");
            // 플래그가 **HOLD 신호**로 이어져야 한다. return 이면 청산 로직까지 끊긴다.
            assertThat(code)
                    .as("%s 가 캔들 미달을 HOLD 신호로 처리하지 않는다 — "
                            + "return 으로 막으면 열린 포지션의 손절·익절이 멈춘다", engine)
                    .contains("전략 요구 캔들 미달");
        }
    }
}
