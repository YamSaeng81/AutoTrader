package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.service.StrategyKillCriteriaService.EdgeStats;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.EdgeVerdict;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Judgment;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.SessionStats;
import com.cryptoautotrader.api.service.StrategyKillCriteriaService.Verdict;
import com.cryptoautotrader.core.risk.KillCriteriaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전략 폐기 기준 판정 테스트 — 정책 문서 {@code docs/KILL_CRITERIA.md} §4 의 실행 가능한 사본.
 *
 * <p>이 테스트의 목적은 커버리지가 아니라 <b>임계값이 조용히 완화되는 것을 막는 것</b>이다.
 * 문서 §7 은 "발동 이력이 있는 전략을 살리려는 목적의 완화"를 금지하는데, 코드에서 임계값만
 * 슬쩍 바꾸면 그 규칙은 강제되지 않는다. 여기서 경계값을 못박아 두면 완화 시 테스트가 깨져
 * 근거를 남기도록 강제된다.</p>
 *
 * <p><b>판정 단위가 둘</b>이라 테스트도 둘로 나뉜다 — A(자본 보호)는 {@code decide(SessionStats)},
 * B(엣지)는 {@code decideEdge(EdgeStats)}. 2026-08-18 실측(세션당 0.07거래/일)으로 B를 세션
 * 단위로 두면 표본 20건에 280일이 걸려 기준이 발동하지 않는다는 것이 확인돼 그룹 단위로 올렸다.</p>
 */
class StrategyKillCriteriaDecisionTest {

    private static final KillCriteriaConfig CFG = KillCriteriaConfig.defaults();

    /** 기준을 하나도 건드리지 않는 건강한 세션 — 각 테스트는 여기서 한 항목만 바꾼다. */
    private static SessionStats healthy() {
        return new SessionStats("DYNAMIC", 48L, "COMPOSITE_MEANREV_BB", "H1", "DYNAMIC#48 MEANREV_BB@H1",
                new BigDecimal("10000"),   // initialCapital
                new BigDecimal("10200"),   // totalAsset  (+2.00%)
                new BigDecimal("10300"),   // mddPeak      (−0.97% 낙폭)
                0,                          // cbTripCount
                0, 0,                       // tradeCount, winCount
                BigDecimal.ZERO,            // sumRealizedPnl
                Instant.now(),              // startedAt
                11);                        // runningDays
    }

    private static SessionStats withAsset(SessionStats s, BigDecimal totalAsset) {
        return new SessionStats(s.sessionKind(), s.sessionId(), s.strategyType(), s.timeframe(), s.label(),
                s.initialCapital(), totalAsset, s.mddPeakCapital(), s.circuitBreakerTripCount(),
                s.tradeCount(), s.winCount(), s.sumRealizedPnl(), s.startedAt(), s.runningDays());
    }

    @Test
    @DisplayName("기준을 건드리지 않으면 KEEP")
    void healthySessionIsKept() {
        Judgment j = StrategyKillCriteriaService.decide(healthy(), CFG);
        assertThat(j.verdict()).isEqualTo(Verdict.KEEP);
        assertThat(j.code()).isEqualTo("OK");
    }

    // ── A. 자본 보호 — 세션 단위, 표본 무관 ───────────────────────────────────

    @Nested
    @DisplayName("A. 자본 보호는 세션 단위이고 거래 0건이어도 발동한다")
    class CapitalProtection {

        @Test
        @DisplayName("CAPITAL_LOSS: −15% 도달 시 폐기, −14.99%는 유지")
        void capitalLossBoundary() {
            // 초기자본 10,000 → 8,500 = 정확히 −15.00%
            assertThat(StrategyKillCriteriaService.decide(
                    withAsset(healthy(), new BigDecimal("8500")), CFG).code())
                    .isEqualTo("CAPITAL_LOSS");

            // 8,501 = −14.99% — 한도 미달
            assertThat(StrategyKillCriteriaService.decide(
                    withAsset(healthy(), new BigDecimal("8501")), CFG).verdict())
                    .as("경계 바로 위에서 발동하면 정상 변동성에도 전략이 죽는다")
                    .isEqualTo(Verdict.KEEP);
        }

        @Test
        @DisplayName("CAPITAL_LOSS 는 거래 0건 · 표본 0 에서도 발동한다 (문서 §2)")
        void capitalLossIgnoresSampleSize() {
            SessionStats s = withAsset(healthy(), new BigDecimal("8000"));
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
            SessionStats s = new SessionStats("LIVE", 198L, "MTF", "H1", "LIVE#198",
                    new BigDecimal("10000"), new BigDecimal("11000"), new BigDecimal("15000"),
                    0, 0, 0, BigDecimal.ZERO, Instant.now(), 11);

            assertThat(StrategyKillCriteriaService.decide(s, CFG).code())
                    .as("한 번 벌었다가 토해내는 패턴은 CAPITAL_LOSS 로는 절대 안 잡힌다")
                    .isEqualTo("MAX_DRAWDOWN");
        }

        @Test
        @DisplayName("CB_REPEAT: 누적 3회에서 폐기, 2회는 유지")
        void circuitBreakerRepeatBoundary() {
            SessionStats twice = new SessionStats("LIVE", 199L, "MTF", "H1", "LIVE#199",
                    new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                    2, 0, 0, BigDecimal.ZERO, Instant.now(), 11);
            assertThat(StrategyKillCriteriaService.decide(twice, CFG).verdict()).isEqualTo(Verdict.KEEP);

            SessionStats thrice = new SessionStats("LIVE", 199L, "MTF", "H1", "LIVE#199",
                    new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                    3, 0, 0, BigDecimal.ZERO, Instant.now(), 11);
            Judgment j = StrategyKillCriteriaService.decide(thrice, CFG);
            assertThat(j.code()).isEqualTo("CB_REPEAT");
            assertThat(j.reason()).contains("누적 3회");
        }

        @Test
        @DisplayName("세션 판정은 엣지를 보지 않는다 — 손실 20거래여도 자본이 멀쩡하면 KEEP")
        void sessionLevelIgnoresEdge() {
            SessionStats s = new SessionStats("DYN_PAPER", 60L, "COMPOSITE_MTF_BTC", "H1", "DYN_PAPER#60",
                    new BigDecimal("10000"), new BigDecimal("9900"), new BigDecimal("10000"),
                    0, 20, 2, new BigDecimal("-100"), Instant.now(), 11);

            assertThat(StrategyKillCriteriaService.decide(s, CFG).verdict())
                    .as("엣지는 전략×타임프레임 그룹이 판정한다 — 세션 단위로 보면 280일이 걸려 발동하지 않는다")
                    .isEqualTo(Verdict.KEEP);
        }
    }

    // ── B. 엣지 안전망 — 전략×타임프레임 그룹 단위 ────────────────────────────

    @Nested
    @DisplayName("B. 엣지 판정은 코인을 가로질러 합산한 그룹에서만 본다")
    class EdgeSafetyNet {

        private EdgeStats group(int sessions, int trades, int wins, String sumPnl,
                                String init, String asset, String benchmarkPct) {
            return new EdgeStats("COMPOSITE_PULLBACK_MTF", "H1", sessions, trades, wins,
                    new BigDecimal(sumPnl), new BigDecimal(init), new BigDecimal(asset),
                    benchmarkPct == null ? null : new BigDecimal(benchmarkPct));
        }

        @Test
        @DisplayName("NEGATIVE_EV: n≥20 이고 누적 실현손익 ≤ 0 이면 폐기")
        void negativeExpectancyKills() {
            EdgeVerdict v = StrategyKillCriteriaService.decideEdge(
                    group(16, 20, 2, "-200", "160000", "159800", null), CFG);

            assertThat(v).isNotNull();
            assertThat(v.code()).isEqualTo("NEGATIVE_EV");
            assertThat(v.reason()).contains("평균 -10.00원").contains("16세션 20거래");
        }

        @Test
        @DisplayName("표본 미달(n=19)이면 손실이 나도 판정하지 않는다")
        void belowMinSampleSkipsEdgeTest() {
            assertThat(StrategyKillCriteriaService.decideEdge(
                    group(16, 19, 0, "-200", "160000", "159800", null), CFG))
                    .as("n<20 에서 EV 부호를 믿으면 운 나쁜 연속 손실로 멀쩡한 전략을 죽인다")
                    .isNull();
        }

        @Test
        @DisplayName("세션은 적어도 코인을 합쳐 표본이 차면 판정한다 — 그룹 집계의 목적")
        void fewSessionsButEnoughAggregatedTrades() {
            // 세션 4개가 각 5거래씩 = 20. 세션 단위였다면 어느 것도 판정 대상이 아니다.
            assertThat(StrategyKillCriteriaService.decideEdge(
                    group(4, 20, 1, "-500", "40000", "39500", null), CFG))
                    .isNotNull();
        }

        @Test
        @DisplayName("승률이 낮아도 기대값이 양수면 살린다 — 승률 단독 폐기 기준은 없다")
        void lowWinRateWithPositiveExpectancySurvives() {
            // 25거래 중 6승(24%)이지만 손익비가 좋아 누적 +500원, 알파도 양수
            assertThat(StrategyKillCriteriaService.decideEdge(
                    group(16, 25, 6, "500", "160000", "168000", "1.00"), CFG))
                    .as("추세추종은 승률 30%대가 정상 — 승률로 죽이면 옳은 전략이 먼저 죽는다")
                    .isNull();
        }

        @Test
        @DisplayName("NEGATIVE_ALPHA: 절대 수익이 나도 시장에 뒤지면 폐기")
        void positiveReturnButLosingToMarketKills() {
            // 그룹 +3% 인데 같은 기간 알트 그냥 보유가 +8%
            EdgeVerdict v = StrategyKillCriteriaService.decideEdge(
                    group(16, 22, 12, "4800", "160000", "164800", "8.00"), CFG);

            assertThat(v).isNotNull();
            assertThat(v.code()).isEqualTo("NEGATIVE_ALPHA");
            assertThat(v.reason()).contains("-5.00%p");
        }

        @Test
        @DisplayName("시장이 더 나쁘면 절대 손실이어도 살린다")
        void losingLessThanMarketSurvives() {
            // 그룹 −2% 인데 알트는 −10% → 알파 +8%p (실현손익은 양수라 EV 기준은 통과)
            assertThat(StrategyKillCriteriaService.decideEdge(
                    group(16, 22, 10, "100", "160000", "156800", "-10.00"), CFG))
                    .as("−2%가 나쁜 성적인지는 같은 기간 시장을 봐야 판정된다")
                    .isNull();
        }

        @Test
        @DisplayName("벤치마크를 못 구하면(캔들 부족) 알파 판정을 생략한다")
        void nullBenchmarkSkipsAlphaTest() {
            assertThat(StrategyKillCriteriaService.decideEdge(
                    group(16, 22, 10, "100", "160000", "158400", null), CFG))
                    .isNull();
        }

        @Test
        @DisplayName("수익률은 자본 가중 — 세션 크기가 달라도 큰 세션이 결과를 지배하지 않는다")
        void returnIsCapitalWeighted() {
            // 초기 160,000 → 164,800 = +3.00%. 세션 수와 무관하게 합산 자본으로 계산된다.
            EdgeVerdict v = StrategyKillCriteriaService.decideEdge(
                    group(2, 22, 12, "4800", "160000", "164800", "8.00"), CFG);
            assertThat(v).isNotNull();
            assertThat(v.reason()).contains("수익률 3.00%");
        }
    }

    // ── C. 판정 불가 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("NO_SIGNAL: 30일 0거래는 경보만 — 성과가 나쁜 게 아니므로 정지하지 않는다")
    void noSignalWarnsButDoesNotKill() {
        SessionStats s = new SessionStats("DYNAMIC", 46L, "COMPOSITE_MTF_CONFIRMED", "H1", "DYNAMIC#46",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                0, 0, 0, BigDecimal.ZERO, Instant.now(), 30);

        Judgment j = StrategyKillCriteriaService.decide(s, CFG);
        assertThat(j.verdict()).isEqualTo(Verdict.WARN);
        assertThat(j.code()).isEqualTo("NO_SIGNAL");
    }

    @Test
    @DisplayName("29일차에는 NO_SIGNAL 경보를 내지 않는다")
    void noSignalRespectsDayThreshold() {
        SessionStats s = new SessionStats("DYNAMIC", 46L, "COMPOSITE_MTF_CONFIRMED", "H1", "DYNAMIC#46",
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                0, 0, 0, BigDecimal.ZERO, Instant.now(), 29);

        assertThat(StrategyKillCriteriaService.decide(s, CFG).verdict()).isEqualTo(Verdict.KEEP);
    }

    // ── 순서 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("자본 한도 초과가 NO_SIGNAL 경보보다 우선한다 (문서 §2)")
    void capitalProtectionOutranksNoSignal() {
        // −20% 손실 + 40일 운영 + 거래 3건 → NO_SIGNAL 조건도 동시 충족
        SessionStats s = new SessionStats("LIVE", 198L, "MEANREV_BB", "H1", "LIVE#198",
                new BigDecimal("10000"), new BigDecimal("8000"), new BigDecimal("10000"),
                0, 3, 0, new BigDecimal("-2000"), Instant.now(), 40);

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
