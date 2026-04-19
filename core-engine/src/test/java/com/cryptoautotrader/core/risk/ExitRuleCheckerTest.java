package com.cryptoautotrader.core.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20260415_analy.md Tier 4 §15 — ExitRuleChecker 전용 단위 테스트.
 *
 * <p>SL/TP 초기 계산, 캔들 기반 청산, 현재가 기반 청산, 트레일링 스탑, 포지션 사이징을 검증한다.</p>
 */
class ExitRuleCheckerTest {

    private ExitRuleChecker checker;
    private ExitRuleConfig defaultConfig;

    @BeforeEach
    void setUp() {
        defaultConfig = ExitRuleConfig.defaults();
        checker = new ExitRuleChecker(defaultConfig);
    }

    // ── calculateStopLevels ──────────────────────────────────────────────

    @Nested
    @DisplayName("§15 calculateStopLevels — SL/TP 초기 계산")
    class CalculateStopLevels {

        @Test
        @DisplayName("기본 설정: SL=진입가-5%, TP=진입가+10%")
        void defaults_slAndTp() {
            BigDecimal entry = new BigDecimal("100000000");
            ExitRuleChecker.StopLevels levels = checker.calculateStopLevels(entry, null);

            // SL: 100_000_000 × 0.95 = 95_000_000
            assertThat(levels.getStopLossPrice())
                    .isEqualByComparingTo(new BigDecimal("95000000"));
            // TP: 100_000_000 × 1.10 = 110_000_000
            assertThat(levels.getTakeProfitPrice())
                    .isEqualByComparingTo(new BigDecimal("110000000"));
        }

        @Test
        @DisplayName("signal이 suggestedStopLoss/TakeProfit를 제공하면 그 값 사용")
        void usesSignalSuggestedLevels() {
            BigDecimal entry = new BigDecimal("100000000");
            com.cryptoautotrader.strategy.StrategySignal signal =
                    com.cryptoautotrader.strategy.StrategySignal.builder()
                            .suggestedStopLoss(new BigDecimal("92000000"))
                            .suggestedTakeProfit(new BigDecimal("115000000"))
                            .build();

            ExitRuleChecker.StopLevels levels = checker.calculateStopLevels(entry, signal);

            assertThat(levels.getStopLossPrice()).isEqualByComparingTo(new BigDecimal("92000000"));
            assertThat(levels.getTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("115000000"));
        }

        @Test
        @DisplayName("커스텀 SL 비율 적용 — SL 3%, TP 9%")
        void customSlPct() {
            ExitRuleConfig cfg = ExitRuleConfig.builder()
                    .stopLossPct(new BigDecimal("3.0"))
                    .takeProfitMultiplier(new BigDecimal("3.0"))
                    .build();
            ExitRuleChecker custom = new ExitRuleChecker(cfg);
            BigDecimal entry = new BigDecimal("100000000");

            ExitRuleChecker.StopLevels levels = custom.calculateStopLevels(entry, null);

            // SL: 100_000_000 × 0.97 = 97_000_000
            assertThat(levels.getStopLossPrice()).isEqualByComparingTo(new BigDecimal("97000000"));
            // TP: 100_000_000 × 1.09 = 109_000_000
            assertThat(levels.getTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("109000000"));
        }
    }

    // ── checkCandleExit ─────────────────────────────────────────────────

    @Nested
    @DisplayName("§15 checkCandleExit — 캔들 기반 청산 체크")
    class CheckCandleExit {

        @Test
        @DisplayName("SL 만 터치 — STOP_LOSS 반환, ambiguous=false")
        void slOnly() {
            BigDecimal sl = new BigDecimal("95000000");
            BigDecimal tp = new BigDecimal("110000000");

            ExitRuleChecker.ExitCheck result = checker.checkCandleExit(
                    new BigDecimal("94000000"),  // low ≤ sl
                    new BigDecimal("98000000"),  // high < tp
                    sl, tp);

            assertThat(result.isShouldExit()).isTrue();
            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.STOP_LOSS);
            assertThat(result.isAmbiguous()).isFalse();
            assertThat(result.getExitPrice()).isEqualByComparingTo(sl);
        }

        @Test
        @DisplayName("TP 만 터치 — TAKE_PROFIT 반환")
        void tpOnly() {
            BigDecimal sl = new BigDecimal("95000000");
            BigDecimal tp = new BigDecimal("110000000");

            ExitRuleChecker.ExitCheck result = checker.checkCandleExit(
                    new BigDecimal("102000000"),  // low > sl
                    new BigDecimal("112000000"),  // high ≥ tp
                    sl, tp);

            assertThat(result.isShouldExit()).isTrue();
            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.TAKE_PROFIT);
            assertThat(result.getExitPrice()).isEqualByComparingTo(tp);
        }

        @Test
        @DisplayName("SL·TP 동시 터치 — STOP_LOSS 반환, ambiguous=true")
        void slAndTpBothHit_returnsSLWithAmbiguousFlag() {
            BigDecimal sl = new BigDecimal("95000000");
            BigDecimal tp = new BigDecimal("110000000");

            ExitRuleChecker.ExitCheck result = checker.checkCandleExit(
                    new BigDecimal("90000000"),  // low ≤ sl
                    new BigDecimal("115000000"),  // high ≥ tp
                    sl, tp);

            assertThat(result.isShouldExit()).isTrue();
            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.STOP_LOSS);
            assertThat(result.isAmbiguous()).isTrue();  // 경로 불확정 플래그
        }

        @Test
        @DisplayName("SL·TP 모두 미터치 — NONE 반환")
        void noHit() {
            ExitRuleChecker.ExitCheck result = checker.checkCandleExit(
                    new BigDecimal("96000000"),
                    new BigDecimal("108000000"),
                    new BigDecimal("95000000"),
                    new BigDecimal("110000000"));

            assertThat(result.isShouldExit()).isFalse();
            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.NONE);
        }

        @Test
        @DisplayName("SL 정확히 접촉 — 손절 발동")
        void slExact() {
            BigDecimal sl = new BigDecimal("95000000");
            ExitRuleChecker.ExitCheck result = checker.checkCandleExit(
                    sl, new BigDecimal("105000000"), sl, new BigDecimal("110000000"));

            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.STOP_LOSS);
        }
    }

    // ── checkPriceExit ──────────────────────────────────────────────────

    @Nested
    @DisplayName("§15 checkPriceExit — 현재가 기반 청산 체크")
    class CheckPriceExit {

        @Test
        @DisplayName("현재가 ≤ SL — 손절 발동")
        void slHit() {
            BigDecimal sl = new BigDecimal("95000000");
            BigDecimal tp = new BigDecimal("110000000");

            ExitRuleChecker.ExitCheck result =
                    checker.checkPriceExit(new BigDecimal("94500000"), sl, tp);

            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.STOP_LOSS);
            assertThat(result.getExitPrice()).isEqualByComparingTo(new BigDecimal("94500000"));
        }

        @Test
        @DisplayName("현재가 ≥ TP — 익절 발동")
        void tpHit() {
            BigDecimal sl = new BigDecimal("95000000");
            BigDecimal tp = new BigDecimal("110000000");

            ExitRuleChecker.ExitCheck result =
                    checker.checkPriceExit(new BigDecimal("111000000"), sl, tp);

            assertThat(result.getType()).isEqualTo(ExitRuleChecker.ExitType.TAKE_PROFIT);
        }

        @Test
        @DisplayName("현재가 SL~TP 사이 — NONE")
        void noHit() {
            ExitRuleChecker.ExitCheck result = checker.checkPriceExit(
                    new BigDecimal("100000000"),
                    new BigDecimal("95000000"),
                    new BigDecimal("110000000"));

            assertThat(result.isShouldExit()).isFalse();
        }
    }

    // ── updateTrailingStops ─────────────────────────────────────────────

    @Nested
    @DisplayName("§15 updateTrailingStops — 트레일링 스탑 갱신")
    class UpdateTrailingStops {

        /** 기본 설정: trailingTpMargin=0.5%, trailingSlMargin=0.3% */

        @Test
        @DisplayName("수익 구간 — 고점 갱신 시 TP 래칫 상향")
        void tpRatchetsUp() {
            BigDecimal entry = new BigDecimal("100000000");
            BigDecimal sl    = new BigDecimal("95000000");
            BigDecimal tp    = new BigDecimal("110000000");

            // 고가 120_000_000 → trailed TP = 120_000_000 × 0.995 = 119_400_000
            ExitRuleChecker.StopLevels updated = checker.updateTrailingStops(
                    new BigDecimal("120000000"),
                    new BigDecimal("105000000"),
                    entry, sl, tp);

            assertThat(updated.getTakeProfitPrice())
                    .isGreaterThan(tp);  // 기존 110_000_000 초과
        }

        @Test
        @DisplayName("TP 래칫은 단방향 — 고점이 낮아지면 TP 불변")
        void tpDoesNotDecrease() {
            BigDecimal entry = new BigDecimal("100000000");
            BigDecimal sl    = new BigDecimal("95000000");
            BigDecimal tp    = new BigDecimal("119000000");  // 이미 높은 TP

            // 고가 115_000_000 → trailed = 114_425_000 < 기존 119_000_000 → 불변
            ExitRuleChecker.StopLevels updated = checker.updateTrailingStops(
                    new BigDecimal("115000000"),
                    new BigDecimal("102000000"),
                    entry, sl, tp);

            assertThat(updated.getTakeProfitPrice()).isEqualByComparingTo(tp);
        }

        @Test
        @DisplayName("손실 구간 — 저점 갱신 시 SL 조임(상향)")
        void slTightensOnLoss() {
            BigDecimal entry = new BigDecimal("100000000");
            BigDecimal sl    = new BigDecimal("80000000");  // 느슨한 SL
            BigDecimal tp    = new BigDecimal("110000000");

            // 저가 90_000_000 (< entry) → trailed SL = 90_000_000 × 0.997 = 89_730_000 > 80_000_000
            ExitRuleChecker.StopLevels updated = checker.updateTrailingStops(
                    new BigDecimal("95000000"),
                    new BigDecimal("90000000"),
                    entry, sl, tp);

            assertThat(updated.getStopLossPrice()).isGreaterThan(sl);
        }

        @Test
        @DisplayName("트레일링 비활성화 — SL/TP 불변")
        void trailingDisabled_noChange() {
            ExitRuleConfig cfg = ExitRuleConfig.builder()
                    .trailingEnabled(false)
                    .build();
            ExitRuleChecker noTrail = new ExitRuleChecker(cfg);

            BigDecimal sl = new BigDecimal("95000000");
            BigDecimal tp = new BigDecimal("110000000");
            ExitRuleChecker.StopLevels updated = noTrail.updateTrailingStops(
                    new BigDecimal("130000000"),
                    new BigDecimal("88000000"),
                    new BigDecimal("100000000"), sl, tp);

            assertThat(updated.getStopLossPrice()).isEqualByComparingTo(sl);
            assertThat(updated.getTakeProfitPrice()).isEqualByComparingTo(tp);
        }
    }

    // ── calculateInvestAmount ───────────────────────────────────────────

    @Nested
    @DisplayName("§15 calculateInvestAmount — 포지션 사이징")
    class CalculateInvestAmount {

        @Test
        @DisplayName("가용 자금 × 80% 투자")
        void investsEightyPercent() {
            BigDecimal available = new BigDecimal("1000000");
            BigDecimal invest = checker.calculateInvestAmount(available);

            assertThat(invest).isEqualByComparingTo(new BigDecimal("800000"));
        }

        @Test
        @DisplayName("최소 투자 금액(5000) 미만이면 0 반환")
        void belowMinimumReturnsZero() {
            BigDecimal available = new BigDecimal("5000");  // 5000 × 80% = 4000 < 5000
            BigDecimal invest = checker.calculateInvestAmount(available);

            assertThat(invest).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("정확히 최소 금액 충족 — 투자 진행")
        void exactlyMinimumThreshold() {
            // min=5000, ratio=0.8 → available=6250 → 6250×0.8=5000
            BigDecimal available = new BigDecimal("6250");
            BigDecimal invest = checker.calculateInvestAmount(available);

            assertThat(invest).isGreaterThanOrEqualTo(new BigDecimal("5000"));
        }
    }
}
