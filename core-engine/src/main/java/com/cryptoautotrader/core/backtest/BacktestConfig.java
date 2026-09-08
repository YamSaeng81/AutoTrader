package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.strategy.Candle;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class BacktestConfig {
    private final String strategyName;
    private final String coinPair;
    private final String timeframe;
    private final Instant startDate;
    private final Instant endDate;

    @Builder.Default
    private final BigDecimal initialCapital = new BigDecimal("10000000");
    @Builder.Default
    private final BigDecimal slippagePct = new BigDecimal("0.1");
    @Builder.Default
    private final BigDecimal feePct = new BigDecimal("0.05");

    private final Map<String, Object> strategyParams;

    // Fill Simulation
    @Builder.Default
    private final boolean fillSimulationEnabled = false;
    @Builder.Default
    private final BigDecimal impactFactor = new BigDecimal("0.1");
    @Builder.Default
    private final BigDecimal fillRatio = new BigDecimal("0.3");

    // ── 통합 리스크/청산 규칙 (실전매매 기본값과 동일) ─────────
    /** null이면 ExitRuleConfig.defaults() 사용 */
    private final ExitRuleConfig exitRuleConfig;

    /**
     * 시간 초과 청산(time stop) 기준 시간 — 운영 세션 기본값은 <b>24시간</b>.
     *
     * <p><b>2026-09-08 추가.</b> 그 전까지 백테스트에는 time stop 경로가 <b>아예 없었다.</b>
     * 그런데 운영 동적 세션 청산 83건 중 <b>TIME_STOP 이 39건(47%)</b> 이다 — 즉 실전 거래의
     * 절반 가까이가 백테스트에서는 발생할 수 없는 경로로 끝났고, <b>거래 모집단 자체가 달랐다.</b>
     * 저변동 종목은 SL/TP 어느 쪽에도 도달하지 않아 백테스트에서는 포지션이 무기한 유지되며,
     * 그동안 자본이 묶여 다른 진입을 막는 비용도 재현되지 않았다.</p>
     *
     * <p>null 또는 0 이하이면 비활성 — <b>2026-09-08 이전 백테스트와 같은 동작</b>이다.
     * 실전과 비교할 목적이라면 세션의 {@code maxHoldHours} 를 그대로 넣을 것.</p>
     */
    private final Integer maxHoldHours;

    /**
     * BTC_MARKET_GUARD 판정용 BTC 캔들 (coinPair와 동일 timeframe, 시간 오름차순).
     * null이면 게이트를 적용하지 않는다 (실전매매 LiveTradingService/DynamicTradingService와
     * 동일하게 맞추려면 호출측에서 반드시 주입해야 한다 — 2026-07-02 codex 분석 §6).
     */
    private final List<Candle> btcCandles;

    /**
     * 진입/청산 신호를 반전시킨다 (BUY↔SELL). 연구 전용 — 2026-08-07.
     *
     * <p>실전 신호 기대값이 체계적으로 음수(BUY 후 24h −4.81%, SELL 후 +1.06%)로 관측되어,
     * "신호가 방향만 반대일 뿐 예측력은 있는가"를 판정하기 위한 가설 검증용 플래그다.
     * 반전 후에도 수수료·슬리피지는 그대로 부과되므로, 결과가 양수라면 마찰비용을 넘는
     * 실제 예측력이 반대 방향에 존재한다는 뜻이고, 여전히 음수라면 신호는 무작위 + 마찰비용이다.
     *
     * <p><b>주의</b>: 반전은 전략이 낸 신호에만 적용되며 손절·익절·time stop 등 리스크 규칙은
     * 그대로 유지된다(그쪽까지 뒤집으면 손절이 익절이 되어 실험 자체가 무의미해진다).
     */
    @Builder.Default
    private final boolean invertSignals = false;

    public ExitRuleConfig getExitRuleConfig() {
        return exitRuleConfig != null ? exitRuleConfig : ExitRuleConfig.defaults();
    }
}
