package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Judgment;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.SessionStats;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Verdict;
import com.cryptoautotrader.core.risk.KillCriteriaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 폐기 기준 판정 테스트 — 정책 문서 {@code docs/KILL_CRITERIA.md} §4 의 실행 가능한 사본.
 *
 * <p>이 테스트의 목적은 커버리지가 아니라 <b>임계값이 조용히 완화되는 것을 막는 것</b>이다.
 * 문서 §7 은 "발동 이력이 있는 전략을 살리려는 목적의 완화"를 금지하는데, 코드에서 임계값만
 * 슬쩍 바꾸면 그 규칙은 강제되지 않는다. 여기서 경계값을 못박아 두면 완화 시 테스트가 깨져
 * 근거를 남기도록 강제된다.</p>
 */
class StrategyKillCriteriaDecisionTest {

    private static final KillCriteriaConfig CFG = KillCriteriaConfig.defaults();

    /** 기준을 하나도 건드리지 않는 건강한 세션 — 각 테스트는 여기서 한 항목만 바꾼다. */
    private static SessionStats healthy() {
        return new SessionStats("DYNAMIC", 48L, "COMPOSITE_MEANREV_BB", "DYNAMIC#48 MEANREV_BB",
                new BigDecimal("10000"),   // initialCapital
                new BigDecimal("10200"),   // totalAsset  (+2.00%)
                new BigDecimal("10300"),   // mddPeak      (−0.97% 낙폭)
                0,                          // cbTripCount
                0, 0,                       // tradeCount, winCount
                BigDecimal.ZERO,            // sumRealizedPnl
                null,                       // benchmark (표본 미달이라 미조회)
                11);                        // runningDays
    }

    private static SessionStats with(SessionStats s, BigDecimal totalAsset) {
        return new SessionStats(s.sessionKind(), s.sessionId(), s.strategyType(), s.label(),
                s.initialCapital(), totalAsset, s.mddPeakCapital(), s.circuitBreakerTripCount(),
                s.tradeCount(), s.winCount(), s.sumRealizedPnl(), s.benchmarkReturnPct(), s.runningDays());
    }

    @Test
    @DisplayName("기준을 건드리지 않으면 KEEP")
    void healthySessionIsKept() {
        Judgment j = StrategyKillCriteriaService.decide(healthy(), CFG);
        assertThat(j.verdict()).isEqualTo(Verdict.KEEP);
        assertThat(j.code()).isEqualTo("OK");
    }

    // ── A. 자본 보호 — 표본 무관 ──────────────────────────────────────────────

    @Nested
    @DisplayName("A. 자본 보호는 거래 0건이어도 발동한다")
    class CapitalProtection {

        @Test
        @DisplayName("CAPITAL_LOSS: −15% 도달 시 폐기, −14.99%는 유지")
        void capitalLossBoundary() {
            // 초기자본 10,000 → 8,500 = 정확히 −15.00%
            assertThat(StrategyKillCriteriaService.decide(
                    with(healthy(), new BigDecimal("8500")), CFG).code())
                    .isEqualTo("CAPITAL_LOSS");

            // 8,501 = −14.99% — 한도 미달
            assertThat(StrategyKillCriteriaService.decide(
                    with(healthy(), new BigDecimal("8501")), CFG).verdict())
                    .as("경계 바로 위에서 발동하면 정상 변동성에도 전략이 죽는다")
                    .isEqualTo(Verdict.KEEP);
        }

        @Test
        @DisplayName("CAPITAL_LOSS 는 거래 0건 · 표본 0 에서도 발동한다 (문서 §2)")
        void capitalLossIgnoresSampleSize() {
            SessionStats s = with(healthy(), new BigDecimal("8000"));
            assertThat(s.tradeCount()).isZero();

            Judgment j = StrategyKillCriteriaService.decide(s, CFG);
            assertThat(j.verdict())
                    .as("\"표본이 부족하다\"를 자본 한도에 적용하면 표본을 모으는 동안 자본이 소진된다")
                    .isEqualTo(Verdict.KILL);
            assertThat(j.code()).isEqualTo("CAPITAL_LOSS");
        }

        @Test
        @DisplayName("MAX_DRAWDOWN: 고점 기준이라 초기자본 대비 이익 중이어도 발동한다")
        void drawdownIsMeasuredFromPeakNotInitial() {
            // 초기 10,000 → 고점 15,000 → 현재 11,000 : 초기 대비 +10% 지만 고점 대비 −26.67%
            SessionStats s = new SessionStats("LIVE", 198L, "MTF", "LIVE#198",
                    new BigDecimal("10000"), new BigDecimal("11000"), new BigDecimal("15000"),
                    0, 0, 0, BigDecimal.ZERO, null, 11);

            Judgment j = StrategyKillCriteriaService.decide(s, CFG);
            assertThat(j.code())
                    .as("한 번 벌었다가 토해내는 패턴은 CAPITAL_LOSS 로는 절대 안 잡힌다")
                    .isEqualTo("MAX_DRAWDOWN");
        }

        @Test
        @DisplayName("CB_REPEAT: 누적 3회에서 폐기, 2회는 유지")
        void circuitBreakerRepeatBoundary() {
            SessionStats twice = new SessionStats("LIVE", 199L, "MTF", "LIVE#199",
                    new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                    2, 0, 0, BigDecimal.ZERO, null, 11);
            assertThat(StrategyKillCriteriaService.decide(twice, CFG).verdict()).isEqualTo(Verdict.KEEP);

            SessionStats thrice = new SessionStats("LIVE", 199L, "MTF", "LIVE#199",
                    new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                    3, 0, 0, BigDecimal.ZERO, null, 11);
            Judgment j = StrategyKillCriteriaService.decide(thrice, CFG);
            assertThat(j.code()).isEqualTo("CB_REPEAT");
            assertThat(j.reason()).contains("누적 3회");
        }
    }

    // ── B. 엣지 안전망 — 표본 필요 ────────────────────────────────────────────

    @Nested
    @DisplayName("B. 엣지 판정은 표본이 찼을 때만 본다")
    class EdgeSafetyNet {

        private SessionStats traded(int tradeCount, int winCount, String sumPnl, String benchmarkPct) {
            return new SessionStats("DYN_PAPER", 53L, "COMPOSITE_PULLBACK_MTF", "DYN_PAPER#53",
                    new BigDecimal("10000"), new BigDecimal("9800"), new BigDecimal("10000"),
                    0, tradeCount, winCount, new BigDecimal(sumPnl),
                    benchmarkPct == null ? null : new BigDecimal(benchmarkPct), 40);
        }

        @Test
        @DisplayName("NEGATIVE_EV: n≥20 이고 누적 실현손익 ≤ 0 이면 폐기")
        void negativeExpectancyKills() {
            Judgment j = StrategyKillCriteriaService.decide(traded(20, 2, "-200", null), CFG);
            assertThat(j.code()).isEqualTo("NEGATIVE_EV");
            assertThat(j.reason()).contains("평균 -10.00원");
        }

        @Test
        @DisplayName("표본 미달(n=19)이면 손실이 나도 엣지 판정을 하지 않는다")
        void belowMinSampleSkipsEdgeTest() {
            Judgment j = StrategyKillCriteriaService.decide(traded(19, 0, "-200", null), CFG);
            assertThat(j.verdict())
                    .as("n<20 에서 EV 부호를 믿으면 운 나쁜 연속 손실로 멀쩡한 전략을 죽인다")
                    .isNotEqualTo(Verdict.KILL);
        }

        @Test
        @DisplayName("승률이 낮아도 기대값이 양수면 살린다 — 승률 단독 폐기 기준은 없다")
        void lowWinRateWithPositiveExpectancySurvives() {
            // 25거래 중 6승(24%)이지만 손익비가 좋아 누적 +500원, 알파도 양수
            SessionStats s = new SessionStats("DYNAMIC", 50L, "CMI_V2", "DYNAMIC#50",
                    new BigDecimal("10000"), new BigDecimal("10500"), new BigDecimal("10500"),
                    0, 25, 6, new BigDecimal("500"), new BigDecimal("1.00"), 40);

            Judgment j = StrategyKillCriteriaService.decide(s, CFG);
            assertThat(j.verdict())
                    .as("추세추종은 승률 30%대가 정상 — 승률로 죽이면 옳은 전략이 먼저 죽는다")
                    .isEqualTo(Verdict.KEEP);
        }

        @Test
        @DisplayName("NEGATIVE_ALPHA: 절대 수익이 나도 시장에 뒤지면 폐기")
        void positiveReturnButLosingToMarketKills() {
            // 세션 +3% 인데 같은 기간 알트 그냥 보유가 +8%
            SessionStats s = new SessionStats("DYNAMIC", 52L, "PULLBACK_MTF", "DYNAMIC#52",
                    new BigDecimal("10000"), new BigDecimal("10300"), new BigDecimal("10300"),
                    0, 22, 12, new BigDecimal("300"), new BigDecimal("8.00"), 40);

            Judgment j = StrategyKillCriteriaService.decide(s, CFG);
            assertThat(j.code()).isEqualTo("NEGATIVE_ALPHA");
            assertThat(j.reason()).contains("-5.00%p");
        }

        @Test
        @DisplayName("시장이 더 나쁘면 절대 손실이어도 살린다")
        void losingLessThanMarketSurvives() {
            // 세션 −2% 인데 알트는 −10% → 알파 +8%p
            SessionStats s = new SessionStats("DYNAMIC", 52L, "PULLBACK_MTF", "DYNAMIC#52",
                    new BigDecimal("10000"), new BigDecimal("9800"), new BigDecimal("10000"),
                    0, 22, 10, new BigDecimal("100"), new BigDecimal("-10.00"), 40);

            assertThat(StrategyKillCriteriaService.decide(s, CFG).verdict())
                    .as("−2%가 나쁜 성적인지는 같은 기간 시장을 봐야 판정된다")
                    .isEqualTo(Verdict.KEEP);
        }

        @Test
        @DisplayName("벤치마크를 못 구하면(캔들 부족) 알파 판정을 생략한다")
        void nullBenchmarkSkipsAlphaTest() {
            SessionStats s = new SessionStats("DYNAMIC", 52L, "PULLBACK_MTF", "DYNAMIC#52",
                    new BigDecimal("10000"), new BigDecimal("9900"), new BigDecimal("10000"),
                    0, 22, 10, new BigDecimal("100"), null, 40);

            assertThat(StrategyKillCriteriaService.decide(s, CFG).verdict()).isEqualTo(Verdict.KEEP);
        }
    }

    // ── C. 판정 불가 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("NO_SIGNAL: 30일 0거래는 경보만 — 성과가 나쁜 게 아니므로 정지하지 않는다")
    void noSignalWarnsButDoesNotKill() {
        // 운영 세션 46/47(MTF_CONFIRMED) 이 접근 중인 상태를 재현
        SessionStats s = new SessionStats("DYNAMIC", 46L, "COMPOSITE_MTF_CONFIRMED", "DYNAMIC#46",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                0, 0, 0, BigDecimal.ZERO, null, 30);

        Judgment j = StrategyKillCriteriaService.decide(s, CFG);
        assertThat(j.verdict()).isEqualTo(Verdict.WARN);
        assertThat(j.code()).isEqualTo("NO_SIGNAL");
    }

    @Test
    @DisplayName("29일차에는 NO_SIGNAL 경보를 내지 않는다")
    void noSignalRespectsDayThreshold() {
        SessionStats s = new SessionStats("DYNAMIC", 46L, "COMPOSITE_MTF_CONFIRMED", "DYNAMIC#46",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                0, 0, 0, BigDecimal.ZERO, null, 29);

        assertThat(StrategyKillCriteriaService.decide(s, CFG).verdict()).isEqualTo(Verdict.KEEP);
    }

    // ── 순서 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("자본 한도 초과가 표본 미달보다 우선한다 (문서 §2 — 순서를 뒤집으면 안 되는 이유)")
    void capitalProtectionOutranksSampleShortage() {
        // −20% 손실 + 거래 3건(표본 미달) + 30일 무신호 조건까지 동시 충족
        SessionStats s = new SessionStats("LIVE", 198L, "MEANREV_BB", "LIVE#198",
                new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("10000"),
                0, 3, 0, new BigDecimal("-2000"), null, 40);

        Judgment j = StrategyKillCriteriaService.decide(s, CFG);
        assertThat(j.verdict()).isEqualTo(Verdict.KILL);
        assertThat(j.code())
                .as("NO_SIGNAL 경보로 끝나면 −20% 손실 세션이 계속 돌아간다")
                .isEqualTo("CAPITAL_LOSS");
    }

    // ── 임계값 고정 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("기본 임계값은 문서 §4 와 일치한다 — 바꾸려면 문서 §7 절차를 따를 것")
    void defaultsMatchPolicyDocument() {
        assertThat(CFG.getCapitalLossPct()).isEqualByComparingTo("-15.0");
        assertThat(CFG.getMaxDrawdownPct()).isEqualByComparingTo("-20.0");
        assertThat(CFG.getCircuitBreakerRepeatLimit()).isEqualTo(3);
        assertThat(CFG.getMinTradesForEdgeTest()).isEqualTo(20);
        assertThat(CFG.getNoSignalDays()).isEqualTo(30);
        assertThat(CFG.getNoSignalMinTrades()).isEqualTo(5);
    }
}
