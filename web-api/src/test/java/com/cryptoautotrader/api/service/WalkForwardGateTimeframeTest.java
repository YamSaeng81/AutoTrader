package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.BacktestRunEntity;
import com.cryptoautotrader.api.repository.BacktestRunRepository;
import com.cryptoautotrader.core.risk.ExitRuleFormula;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 2026-09-08 신규 — 게이트의 <b>타임프레임 축</b> 분리.
 *
 * <h3>왜 필요한가</h3>
 * <p>08-24 에 게이트를 전략 단위에서 전략×코인 단위로 좁히면서, 같은 이유가 적용되는
 * <b>타임프레임 축은 놓쳤다.</b> {@code latestPerCoin} 의 키가 코인뿐이라 같은 (전략, 코인) 의
 * H1 과 M15 가 한 자리를 다퉜고, {@code putIfAbsent} + createdAt desc 정렬 탓에
 * <b>나중에 실행된 쪽이 다른 쪽 판정을 덮어썼다</b> — 실행 순서가 판정을 좌우한다는 점에서
 * {@code evaluate(String)} javadoc 이 "우연에 가까웠다"고 적은 것과 정확히 같은 결함이다.</p>
 *
 * <p>운영 실태가 이 구분을 요구한다: 2026-09-08 기준 고정코인 PAPER 40세션은 전부 M15,
 * 동적 12세션은 H1 6 / M15 6 인데 그때까지 WF 실행 350건은 <b>전부 H1</b> 이었다.
 * 구분이 없으면 M15 세션이 H1 근거로 통과한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalkForwardGateTimeframeTest {

    @Mock
    private BacktestRunRepository repo;

    private WalkForwardValidationGate gate;

    @BeforeEach
    void setUp() {
        gate = new WalkForwardValidationGate(repo, false);
    }

    /** OOS 기대값 · 거래수를 담은 WF 실행 1건 — 현재 청산 규칙으로 돈 것으로 표시한다. */
    private static BacktestRunEntity run(String coin, String tf, String verdict,
                                         double expectancyPct, int trades, Instant createdAt) {
        BacktestRunEntity e = runWithRuleset(coin, tf, verdict, expectancyPct, trades, createdAt,
                ExitRuleFormula.EXIT_RULES_VERSION);
        return e;
    }

    /** 규칙 버전을 명시하는 형태 — 구버전 거부를 검증할 때 쓴다. */
    private static BacktestRunEntity runWithRuleset(String coin, String tf, String verdict,
                                                     double expectancyPct, int trades,
                                                     Instant createdAt, Integer exitRulesVersion) {
        BacktestRunEntity e = new BacktestRunEntity();
        e.setExitRulesVersion(exitRulesVersion);
        e.setStrategyName("COMPOSITE_MTF_BTC");
        e.setCoinPair(coin);
        e.setTimeframe(tf);
        e.setIsWalkForward(true);
        e.setCreatedAt(createdAt);
        e.setWfResultJson(Map.of(
                "verdict", verdict,
                "aggregatedOutSample", Map.of(
                        "expectancyPct", expectancyPct,
                        "totalTrades", trades)));
        return e;
    }

    @Test
    @DisplayName("전략×코인×TF — M15 판정이 H1 결과에 오염되지 않는다")
    void 코인_타임프레임_조합별로_분리된다() {
        // H1 은 통과할 성적, M15 는 기대값이 음수라 차단돼야 한다.
        when(repo.findByStrategyNameAndCoinPairAndTimeframeAndIsWalkForwardTrueOrderByCreatedAtDesc(
                anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    String tf = inv.getArgument(2);
                    return "H1".equals(tf)
                            ? List.of(run("KRW-BTC", "H1", "ACCEPTABLE", 1.8, 30,
                                    Instant.parse("2026-09-02T00:00:00Z")))
                            : List.of(run("KRW-BTC", "M15", "ACCEPTABLE", -0.9, 30,
                                    Instant.parse("2026-09-08T00:00:00Z")));
                });

        assertThat(gate.evaluate("COMPOSITE_MTF_BTC", "KRW-BTC", "H1").passed())
                .as("H1 은 기대값 +1.8 이라 통과")
                .isTrue();
        assertThat(gate.evaluate("COMPOSITE_MTF_BTC", "KRW-BTC", "M15").passed())
                .as("M15 는 기대값 −0.9 라 차단 — H1 결과가 넘어오면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("전략 단위 판정 — 같은 코인의 H1/M15 가 서로를 덮어쓰지 않는다 (핵심 회귀)")
    void 같은_코인의_두_타임프레임이_서로를_덮지_않는다() {
        // createdAt 은 M15 가 더 최신이다. 키가 코인뿐이던 예전 코드는 M15(FAIL)만 남기고
        // H1(PASS)을 버려서 전략 전체가 FAIL 이 됐다 — 실행 순서가 판정을 뒤집는다.
        when(repo.findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(
                        run("KRW-BTC", "M15", "ACCEPTABLE", -0.9, 30, Instant.parse("2026-09-08T00:00:00Z")),
                        run("KRW-BTC", "H1", "ACCEPTABLE", 1.8, 30, Instant.parse("2026-09-02T00:00:00Z"))));

        assertThat(gate.evaluate("COMPOSITE_MTF_BTC").passed())
                .as("H1 조합이 PASS 이므로 전략 단위로는 통과해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("전략 단위 + 타임프레임 지정 — 그 타임프레임 실행만 본다")
    void 타임프레임을_주면_해당_실행만_본다() {
        when(repo.findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(
                        run("KRW-BTC", "M15", "ACCEPTABLE", -0.9, 30, Instant.parse("2026-09-08T00:00:00Z")),
                        run("KRW-BTC", "H1", "ACCEPTABLE", 1.8, 30, Instant.parse("2026-09-02T00:00:00Z"))));

        assertThat(gate.evaluateStrategy("COMPOSITE_MTF_BTC", "H1").passed()).isTrue();
        assertThat(gate.evaluateStrategy("COMPOSITE_MTF_BTC", "M15").passed())
                .as("M15 세션이 H1 근거로 통과하면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("해당 타임프레임 실행 이력이 없으면 차단 — 증명되지 않았으므로")
    void 타임프레임_이력이_없으면_차단() {
        when(repo.findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(
                        run("KRW-BTC", "H1", "ACCEPTABLE", 1.8, 30, Instant.parse("2026-09-02T00:00:00Z"))));

        var d = gate.evaluateStrategy("COMPOSITE_MTF_BTC", "M15");
        assertThat(d.passed()).isFalse();
        assertThat(d.reason()).contains("이력 없음");
    }

    @Test
    @DisplayName("타임프레임 null — 종전대로 타임프레임 무관 판정 (하위 호환)")
    void 타임프레임_null이면_종전_동작() {
        when(repo.findByStrategyNameAndCoinPairAndIsWalkForwardTrueOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(List.of(run("KRW-BTC", "H1", "ACCEPTABLE", 1.8, 30,
                        Instant.parse("2026-09-02T00:00:00Z"))));

        assertThat(gate.evaluate("COMPOSITE_MTF_BTC", "KRW-BTC").passed()).isTrue();
    }

    // ── 청산 규칙 버전 (2026-09-08) ────────────────────────────────

    @Test
    @DisplayName("구버전 규칙으로 돈 실행은 근거로 인정하지 않는다 — 성적이 아무리 좋아도")
    void 구버전_규칙_실행은_거부된다() {
        // 기대값 +3.5%, 거래 40건 — 성적만 보면 통과할 실행이다.
        when(repo.findByStrategyNameAndCoinPairAndTimeframeAndIsWalkForwardTrueOrderByCreatedAtDesc(
                anyString(), anyString(), any()))
                .thenReturn(List.of(runWithRuleset("KRW-BTC", "H1", "ACCEPTABLE", 3.5, 40,
                        Instant.parse("2026-09-02T00:00:00Z"),
                        ExitRuleFormula.EXIT_RULES_VERSION - 1)));

        var d = gate.evaluate("COMPOSITE_MTF_BTC", "KRW-BTC", "H1");

        assertThat(d.passed())
                .as("SL 5%% 고정 · TP 10%% · time stop 없음으로 나온 성적이다 — 실전 거동을 반영하지 못한다")
                .isFalse();
        assertThat(d.reason()).contains("청산 규칙");
    }

    @Test
    @DisplayName("버전 미상(NULL)도 거부한다 — 09-08 이전 350건이 전부 여기 해당한다")
    void 버전_미상은_거부된다() {
        when(repo.findByStrategyNameAndCoinPairAndTimeframeAndIsWalkForwardTrueOrderByCreatedAtDesc(
                anyString(), anyString(), any()))
                .thenReturn(List.of(runWithRuleset("KRW-BTC", "H1", "ACCEPTABLE", 3.5, 40,
                        Instant.parse("2026-09-02T00:00:00Z"), null)));

        assertThat(gate.evaluate("COMPOSITE_MTF_BTC", "KRW-BTC", "H1").passed())
                .as("어떤 규칙으로 돌았는지 모르는 결과를 통과시키면, 안전한 기본값이 '통과'가 된다")
                .isFalse();
    }

    @Test
    @DisplayName("전략 단위 판정도 구버전을 걸러낸다 — 한쪽만 고치면 DYNAMIC 경로로 샌다")
    void 전략_단위_판정도_버전을_본다() {
        // 코인을 모르는 DYNAMIC 세션 생성 경로. 구버전 실행만 있으면 통과해선 안 된다.
        when(repo.findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(runWithRuleset("KRW-BTC", "H1", "ACCEPTABLE", 3.5, 40,
                        Instant.parse("2026-09-02T00:00:00Z"),
                        ExitRuleFormula.EXIT_RULES_VERSION - 1)));

        assertThat(gate.evaluateStrategy("COMPOSITE_MTF_BTC", "H1").passed()).isFalse();
    }

    // ── 판정 불가(표본 부족) 차단 (2026-09-09) ────────────────────────

    @Test
    @DisplayName("INSUFFICIENT_DATA 는 검증 이력 없음과 같게 차단한다 — 과적합과 다른 상태다")
    void 판정불가는_차단된다() {
        // 표본이 없어 판정 자체가 불가능한 실행. 기대값·거래수는 게이트의 자체 하한으로도
        // 걸리지만, 두 하한이 갈라져도 새지 않도록 verdict 단계에서 먼저 막는지 본다.
        when(repo.findByStrategyNameAndCoinPairAndTimeframeAndIsWalkForwardTrueOrderByCreatedAtDesc(
                anyString(), anyString(), any()))
                .thenReturn(List.of(run("KRW-EUL", "M15",
                        WalkForwardTestRunner.VERDICT_INSUFFICIENT_DATA, 3.5, 40,
                        Instant.parse("2026-09-09T00:00:00Z"))));

        var d = gate.evaluate("COMPOSITE_MTF_BTC", "KRW-EUL", "M15");

        assertThat(d.passed())
                .as("성적이 좋아 보여도 표본이 없으면 아무 말도 할 수 없는 상태다")
                .isFalse();
        assertThat(d.reason()).contains("판정 불가");
    }
}
