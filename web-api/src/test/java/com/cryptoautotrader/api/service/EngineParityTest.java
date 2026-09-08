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
 * 3엔진 정합성 감사 — 2026-08-19 신설.
 *
 * <h3>왜 이 테스트가 존재하는가</h3>
 * <p>매매 엔진이 셋이다: {@link LiveTradingService}(2,787줄) · {@link DynamicTradingService}(2,246줄) ·
 * {@link PaperTradingService}(1,032줄). 각자 세션 테이블·포지션 테이블·틱 루프·reconciler 를
 * 따로 갖고, <b>교차 규칙을 세 곳에 적용했는지 강제하는 장치가 없었다.</b>
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
 * <h3>이 테스트가 하는 일</h3>
 * <p>교차 규칙별로 <b>어느 엔진에 있어야 하는가</b>를 선언하고 소스에서 검증한다.
 * 의도적으로 없는 칸은 사유와 함께 고정한다 — 나중에 "왜 없지?" 를 다시 조사하지 않도록.
 * 새 규칙을 한 엔진에만 넣으면 여기서 깨진다.</p>
 *
 * <p><b>한계</b>: 소스 문자열 검사라 "호출된다"까지만 보고 "올바르게 호출된다"는 못 본다.
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
    @DisplayName("알려진 결함: 같은 개념을 엔진마다 다르게 부른다 (닫힌 캔들 게이트)")
    void knownGap_inconsistentNaming() {
        // LIVE·PAPER 는 lastEvaluatedClosedCandle, DYNAMIC 만 lastEvaluatedCandle 이다.
        // 기능은 같지만 이름이 달라 grep 기반 감사가 오탐을 낸다 — 이 테스트를 처음 돌렸을 때
        // 실제로 "LIVE 에 없다" 는 오탐이 났고, 고친 뒤에는 "PAPER 에 없다" 로 또 틀렸다.
        // 이름이 통일되면 세 번째 단언이 깨지므로 그때 이 테스트를 제거할 것.
        assertThat(has("Live", "lastEvaluatedClosedCandle")).isTrue();
        assertThat(has("Paper", "lastEvaluatedClosedCandle")).isTrue();
        assertThat(has("Dynamic", "lastEvaluatedClosedCandle"))
                .as("DYNAMIC 이 표준 이름으로 통일됐다면 이 테스트를 제거할 것")
                .isFalse();
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

    @Test
    @DisplayName("알려진 결함: 틱 캔들 공유 캐시가 PAPER 에만 있다")
    void knownGap_onlyPaperSharesCandleCache() {
        // PAPER 는 틱당 (코인,타임프레임) 캔들을 한 번만 조회해 세션 수와 무관하게 비용이 고정된다.
        // LIVE·DYNAMIC 은 세션마다 독립 조회라, 2026-08-18 실측에서 DYNAMIC 8세션이
        // 전체 API 요청의 89%(264/297 req/분)를 썼다. 세션 확장의 1순위 병목이다.
        assertThat(has("Paper", "tickCandleCache")).isTrue();
        assertThat(has("Dynamic", "tickCandleCache"))
                .as("DYNAMIC 에 공유 캐시가 생겼다면 결함 해소 — 이 테스트를 갱신할 것")
                .isFalse();
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
}
