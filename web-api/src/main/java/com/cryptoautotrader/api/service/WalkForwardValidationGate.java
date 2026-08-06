package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.BacktestRunEntity;
import com.cryptoautotrader.api.repository.BacktestRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /** 전략에 대한 최신 Walk Forward 실행 결과 기반 판정. */
    public GateDecision evaluate(String strategyName) {
        List<BacktestRunEntity> runs =
                backtestRunRepository.findByStrategyNameAndIsWalkForwardTrueOrderByCreatedAtDesc(strategyName);
        if (runs.isEmpty()) {
            return decide(strategyName, null, null, null, null);
        }
        BacktestRunEntity latest = runs.get(0);
        Map<String, Object> wf = latest.getWfResultJson();
        String verdict = wf != null ? asString(wf.get("verdict")) : null;

        Map<String, Object> aggregated = wf != null ? asMap(wf.get("aggregatedOutSample")) : null;
        BigDecimal expectancyPct = aggregated != null ? asBigDecimal(aggregated.get("expectancyPct")) : null;
        Integer totalTrades = aggregated != null ? asInteger(aggregated.get("totalTrades")) : null;

        return decide(strategyName, verdict, expectancyPct, totalTrades, latest.getCreatedAt() != null
                ? latest.getCreatedAt().toString() : null);
    }

    /**
     * 게이트가 켜져 있고 판정이 FAIL이면 예외를 던진다. 꺼져 있으면 판정과 무관하게 통과시킨다
     * (판정 자체는 항상 계산해 호출부 로그에 남길 수 있게 한다).
     */
    public void throwIfBlocked(String strategyName) {
        GateDecision decision = evaluate(strategyName);
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
