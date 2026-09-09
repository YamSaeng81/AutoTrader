package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.BacktestRunEntity;
import com.cryptoautotrader.api.repository.BacktestRunRepository;
import com.cryptoautotrader.core.risk.ExitRuleFormula;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner;

/**
 * 신호 기대값 검증 게이트 — "Walk Forward 로 out-of-sample 기대값 &gt; 0 이 확인된 전략만
 * 실자본(LIVE·DYNAMIC) 세션 생성을 허용한다"는 정책을 강제한다.
 *
 * <h3>배경</h3>
 * <p>2026-08-06 운영 DB 분석: 최근 7일 동적 세션 BUY 신호(n=50)의 사후수익률이
 * 4h -2.17% / 24h -4.47% — 기대값이 음수인 신호가 그대로 실자본을 쓰고 있었다.
 * 반면 {@link StrategyLiveStatusRegistry}의 ENABLED/BLOCKED 매트릭스는 특정 시점
 * 백테스트 근거를 사람이 손으로 기록한 것이라 최신 검증과 무관하게 고정돼 있고,
 * Walk Forward 페이지({@code /backtest/walk-forward})는 실행은 되지만 그 결과가
 * 세션 생성과 전혀 연결되지 않았다 — 검증과 자본 배정이 분리된 상태였다.</p>
 *
 * <h3>판정 기준</h3>
 * <p>전략명 기준(코인 무관 — {@link StrategyLiveStatusRegistry}와 동일한 세분화 수준)으로
 * 가장 최근 Walk Forward 실행 결과를 본다:</p>
 * <ul>
 *   <li>실행 이력이 없으면 → 차단 (증명되지 않음)</li>
 *   <li>verdict = OVERFITTING → 차단 (In-Sample 대비 Out-of-Sample 하락폭이 커서
 *       기대값 자체를 신뢰할 수 없음 — CAUTION 이하만 통과)</li>
 *   <li>OOS 병합 거래 수 &lt; {@value #MIN_TRADES} → 차단 (표본 부족)</li>
 *   <li>OOS 기대값(expectancyPct) &le; 0 → 차단</li>
 *   <li>그 외 → 통과</li>
 * </ul>
 *
 * <h3>기본 비활성</h3>
 * <p>{@code strategy-validation.require-walk-forward-gate=true} 로 명시적으로 켜기 전엔
 * 아무 세션 생성도 막지 않는다(판정 로직·API는 항상 살아 있어 사전 확인 가능).
 * 대부분의 기존 전략은 Walk Forward 실행 이력이 아예 없어, 플래그를 기본 on으로 배포하면
 * 신규 세션 생성이 전면 중단된다 — 사용자가 {@code /api/v1/strategies/walk-forward-gate-status}
 * 로 영향 범위를 먼저 확인한 뒤 켜는 것을 전제로 한다.</p>
 */
@Component
@Slf4j
public class WalkForwardValidationGate {

    /** OOS 병합 거래 수 최소 표본 — 이보다 적으면 기대값 부호 자체를 신뢰하지 않는다. */
    static final int MIN_TRADES = 5;

    private final BacktestRunRepository backtestRunRepository;
    private final boolean gateEnabled;

    public WalkForwardValidationGate(
            BacktestRunRepository backtestRunRepository,
            @Value("${strategy-validation.require-walk-forward-gate:false}") boolean gateEnabled) {
        this.backtestRunRepository = backtestRunRepository;
        this.gateEnabled = gateEnabled;
        if (gateEnabled) {
            log.info("[WalkForwardGate] 활성화 — Walk Forward 미검증 전략은 신규 세션 생성이 차단됩니다.");
        }
    }

    /** 게이트가 실제로 세션 생성을 차단하는 상태인지. false면 판정만 하고 강제하지 않는다. */
    public boolean isEnabled() {
        return gateEnabled;
    }

    /** 전략 단위 판정, 타임프레임 무관 — {@link #evaluateStrategy(String, String)} 참조. */
    public GateDecision evaluate(String strategyName) {
        return evaluateStrategy(strategyName, null);
    }

    /**
     * 전략×코인×타임프레임 조합의 최신 Walk Forward 실행 결과 기반 판정 — 코인이 정해진 세션(LIVE)용.
     *
     * <p>같은 전략도 코인마다 성적이 크게 갈린다(Tier1/Tier2 표 참조). 코인을 아는 상황에서
     * 코인 무관 판정을 쓰면, 엉뚱한 코인의 결과가 섞여 실제로는 좋은 조합을 차단하거나
     * 나쁜 조합을 통과시킬 수 있다(2026-08-24 실측: 5전략을 ADA 기준으로만 판정했더니
     * BTC에서는 통과할 만한 조합 3개가 전부 가려짐).</p>
     *
     * <p><b>타임프레임도 같은 이유로 구분한다 (2026-09-08 추가).</b> 08-24 에 코인 축만 분리하고
     * 타임프레임은 놓쳤다 — 같은 (전략, 코인) 의 H1 과 M15 가 한 키를 다투면서
     * <b>나중에 실행된 쪽이 다른 쪽 판정을 덮어썼다.</b> 실행 순서가 판정을 좌우한다는 점에서
     * 아래 {@link #evaluate(String, String, String)} javadoc 이 "우연에 가까웠다"고 적은 것과
     * 정확히 같은 결함이다. {@code timeframe} 이 null 이면 종전대로 타임프레임 무관 판정을 한다.</p>
     */
    public GateDecision evaluate(String strategyName, String coinPair, String timeframe) {
        if (coinPair == null) {
            return evaluateStrategy(strategyName, timeframe);
        }
        List<BacktestRunEntity> runs = (timeframe == null)
                ? backtestRunRepository
                    .findByStrategyNameAndCoinPairAndIsWalkForwardTrueOrderByCreatedAtDesc(strategyName, coinPair)
                : backtestRunRepository
                    .findByStrategyNameAndCoinPairAndTimeframeAndIsWalkForwardTrueOrderByCreatedAtDesc(
                            strategyName, coinPair, timeframe);
        String label = strategyName + "/" + coinPair + (timeframe != null ? "@" + timeframe : "");
        if (runs.isEmpty()) {
            return decide(label, null, null, null, null);
        }
        return decideFromRun(label, runs.get(0));
    }

    /** 타임프레임을 모를 때 — {@link #evaluate(String, String, String)} 참조. */
    public GateDecision evaluate(String strategyName, String coinPair) {
        return evaluate(strategyName, coinPair, null);
    }

    /**
     * 전략에 대한 판정 — 코인을 아직 모르는 상황(DYNAMIC 세션 생성, 실제 매수 코인은 감시목록
     * 스캔 후 정해짐)에서만 쓴다. "이 전략이 검증을 통과한 조합이 하나라도 있는가"로 판단한다 —
     * 조합별 최신 실행 중 하나라도 PASS 면 전략 전체를 PASS 로 본다.
     *
     * <p>완벽한 판정은 아니다(통과 코인이 아닌 다른 코인을 매수할 수도 있다) — 하지만 예전처럼
     * "가장 최근에 실행된 아무 코인 하나"로 전략 전체를 판정하는 것보다는 낫다. 그쪽은 실행 순서에
     * 따라 결과가 좌우되는 우연에 가까웠다.</p>
     *
     * <p>{@code timeframe} 을 주면 그 타임프레임의 실행만 본다. 동적 세션은 코인은 몰라도
     * 타임프레임은 생성 시점에 정해져 있으므로 넘길 수 있다 — 2026-09-08 이전 WF 350건이
     * 전부 H1 인데 운영 동적 세션 절반이 M15 였다는 점에서, 이 구분이 없으면 M15 세션이
     * H1 근거로 통과한다.</p>
     */
    public GateDecision evaluateStrategy(String strategyName, String timeframe) {
        List<BacktestRunEntity> runs =
                backtestRunRepository.findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(strategyName);
        if (timeframe != null) {
            runs = runs.stream().filter(r -> timeframe.equals(r.getTimeframe())).toList();
        }
        if (runs.isEmpty()) {
            return decide(strategyName, null, null, null, null);
        }

        // 조합(코인 × 타임프레임)별 최신 실행만 남긴다 — runs 는 이미 createdAt desc 이므로 첫 등장이 최신.
        // 2026-09-08 이전에는 키가 코인뿐이라 같은 코인의 H1/M15 가 서로를 덮어썼다.
        Map<String, BacktestRunEntity> latestPerCombo = new LinkedHashMap<>();
        for (BacktestRunEntity run : runs) {
            latestPerCombo.putIfAbsent(run.getCoinPair() + "@" + run.getTimeframe(), run);
        }

        GateDecision best = null;
        for (Map.Entry<String, BacktestRunEntity> e : latestPerCombo.entrySet()) {
            GateDecision d = decideFromRun(strategyName + "/" + e.getKey(), e.getValue());
            if (d.passed()) {
                return GateDecision.pass(strategyName,
                        String.format("%s 기준 PASS (%s)", e.getKey(), d.reason()), d.lastValidatedAt());
            }
            if (best == null) best = d; // 전부 FAIL이면 가장 최근 것의 사유를 대표로 보여준다
        }
        return new GateDecision(strategyName, false,
                String.format("검증된 %d개 조합 전부 FAIL — 예: %s", latestPerCombo.size(), best.reason()),
                null);
    }

    /**
     * 실행이 <b>현재 청산 규칙</b>으로 돌았는지 — 아니면 근거로 인정하지 않는다 (2026-09-08).
     *
     * <h3>왜 필요한가</h3>
     * <p>이 게이트는 조합별 <b>가장 최근 실행 하나</b>만 본다. 그래서 재실행된 조합은 옛 결과가
     * 자연히 밀려나지만, <b>재실행되지 않은 조합은 수정 전 판정을 영원히 유지한다.</b>
     * 조용히 낡은 근거로 실자본이 승인된다 — 아무도 에러를 보지 못한다.</p>
     *
     * <p>09-08 에 백테스트 청산 규칙이 실제로 바뀌었다(SL 5% 고정 → ATR 기반 5~8%,
     * TP 10% → ≤8%, time stop 없음 → 24h, 손실 구간 SL 조임 제거). v1 결과는 실전 거동의
     * 근거가 되지 못하므로 "검증 이력 없음"과 같게 취급한다.</p>
     */
    private static boolean isCurrentRuleset(BacktestRunEntity run) {
        Integer v = run.getExitRulesVersion();
        return v != null && v >= ExitRuleFormula.EXIT_RULES_VERSION;
    }

    private GateDecision decideFromRun(String label, BacktestRunEntity run) {
        if (!isCurrentRuleset(run)) {
            return GateDecision.fail(label, String.format(
                    "청산 규칙이 바뀐 뒤 재검증되지 않았습니다 (실행 규칙 v%s < 현재 v%d) — "
                            + "이 결과는 SL 5%% 고정·TP 10%%·time stop 없음 기준이라 실전 거동을 반영하지 "
                            + "못합니다. /backtest/walk-forward 에서 재실행하세요.",
                    run.getExitRulesVersion() == null ? "미상" : run.getExitRulesVersion(),
                    ExitRuleFormula.EXIT_RULES_VERSION));
        }
        Map<String, Object> wf = run.getWfResultJson();
        String verdict = wf != null ? asString(wf.get("verdict")) : null;
        Map<String, Object> aggregated = wf != null ? asMap(wf.get("aggregatedOutSample")) : null;
        BigDecimal expectancyPct = aggregated != null ? asBigDecimal(aggregated.get("expectancyPct")) : null;
        Integer totalTrades = aggregated != null ? asInteger(aggregated.get("totalTrades")) : null;
        return decide(label, verdict, expectancyPct, totalTrades,
                run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
    }

    /**
     * 게이트가 켜져 있고 판정이 FAIL이면 예외를 던진다. 꺼져 있으면 판정과 무관하게 통과시킨다
     * (판정 자체는 항상 계산해 호출부 로그에 남길 수 있게 한다).
     */
    public void throwIfBlocked(String strategyName) {
        throwIfBlocked(strategyName, null, null);
    }

    /**
     * 코인은 모르지만 타임프레임은 아는 세션(DYNAMIC 생성)용 — 2026-09-08 추가.
     * 그전까지 M15 동적 세션이 H1 근거로 통과했다(당시 WF 350건이 전부 H1).
     */
    public void throwIfBlockedByTimeframe(String strategyName, String timeframe) {
        throwIfBlocked(strategyName, null, timeframe);
    }

    /** 코인을 아는 세션(LIVE)은 이쪽을 쓴다 — {@link #evaluate(String, String, String)} 참조. */
    public void throwIfBlocked(String strategyName, String coinPair, String timeframe) {
        GateDecision decision = (coinPair == null)
                ? evaluateStrategy(strategyName, timeframe)
                : evaluate(strategyName, coinPair, timeframe);
        if (!decision.passed()) {
            log.info("[WalkForwardGate] 판정: {} → {} ({}) — 게이트 {}",
                    strategyName, decision.passed() ? "PASS" : "FAIL", decision.reason(),
                    gateEnabled ? "강제" : "비활성(통과 허용)");
        }
        if (gateEnabled && !decision.passed()) {
            throw new IllegalArgumentException(String.format(
                    "전략 '%s'은(는) Walk Forward 기대값 검증을 통과하지 못해 신규 세션을 생성할 수 없습니다: %s "
                            + "(/backtest/walk-forward 에서 재검증하세요)",
                    strategyName, decision.reason()));
        }
    }

    /**
     * 순수 판정 함수 — DB 접근 없이 테스트 가능. verdict/expectancyPct/totalTrades 는
     * 실행 이력이 없으면 전부 null 로 전달된다.
     */
    static GateDecision decide(String strategyName, String verdict, BigDecimal expectancyPct,
                                Integer totalTrades, String lastValidatedAt) {
        if (verdict == null) {
            return GateDecision.fail(strategyName,
                    "Walk Forward 검증 이력 없음 — 아직 out-of-sample 기대값이 증명되지 않았습니다.");
        }
        if (WalkForwardTestRunner.VERDICT_INSUFFICIENT_DATA.equals(verdict)) {
            // 2026-09-09: "과적합이 아니다" 와 구분한다. 표본이 없어 **판정 자체가 불가능**한
            // 상태이므로 검증 이력이 없는 것과 같게 취급한다. 아래 표본 검사로도 대부분 걸리지만,
            // 두 하한이 갈라져도 판정이 새지 않도록 여기서 먼저 막는다.
            return GateDecision.fail(strategyName, String.format(
                    "Walk Forward 판정 불가 — Out-of-Sample 거래가 %d건 미만이라 과적합 여부를 "
                            + "잴 수 없습니다. 상장이 최근이거나 신호가 거의 없는 조합입니다.",
                    WalkForwardTestRunner.MIN_OOS_TRADES_FOR_VERDICT));
        }
        if ("OVERFITTING".equals(verdict)) {
            return GateDecision.fail(strategyName,
                    "최근 Walk Forward 판정이 OVERFITTING — In-Sample 대비 Out-of-Sample 성과 하락폭이 커서 "
                            + "기대값을 신뢰할 수 없습니다.");
        }
        if (totalTrades == null || totalTrades < MIN_TRADES) {
            return GateDecision.fail(strategyName, String.format(
                    "Out-of-Sample 거래 표본 부족(n=%s < %d).", totalTrades == null ? "0" : totalTrades, MIN_TRADES));
        }
        if (expectancyPct == null || expectancyPct.signum() <= 0) {
            return GateDecision.fail(strategyName, String.format(
                    "Out-of-Sample 기대값이 0 이하(expectancyPct=%s%%).",
                    expectancyPct == null ? "N/A" : expectancyPct.toPlainString()));
        }
        return GateDecision.pass(strategyName, String.format(
                "verdict=%s, OOS expectancyPct=%s%%, n=%d", verdict, expectancyPct.toPlainString(), totalTrades),
                lastValidatedAt);
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer asInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    public record GateDecision(String strategyName, boolean passed, String reason, String lastValidatedAt) {
        static GateDecision fail(String strategyName, String reason) {
            return new GateDecision(strategyName, false, reason, null);
        }
        static GateDecision pass(String strategyName, String reason, String lastValidatedAt) {
            return new GateDecision(strategyName, true, reason, lastValidatedAt);
        }
    }
}
