package com.cryptoautotrader.core.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RiskEngine — 포지션 사이징 + 상관관계 슬롯")
class RiskEngineTest {

    private final RiskConfig defaultConfig = RiskConfig.builder().build();
    private final RiskEngine engine = new RiskEngine(defaultConfig);

    // ── 기존 한도 검사 ────────────────────────────────────────────────────

    @Test
    @DisplayName("모든 조건 충족 → approve")
    void check_approve() {
        RiskCheckResult result = engine.check(
                new BigDecimal("1.0"),
                new BigDecimal("3.0"),
                new BigDecimal("7.0"),
                1
        );
        assertThat(result.isApproved()).isTrue();
    }

    @Test
    @DisplayName("일일 손실 한도 초과 → reject")
    void check_dailyLoss_reject() {
        RiskCheckResult result = engine.check(
                new BigDecimal("5.0"),  // maxDailyLossPct = 3.0
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0
        );
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getReason()).contains("일일 손실 한도 초과");
    }

    @Test
    @DisplayName("최대 포지션 수 초과 → reject")
    void check_maxPositions_reject() {
        // maxPositions 기본값은 20; 명시적으로 3으로 설정해 안전망 검증
        RiskEngine smallEngine = new RiskEngine(RiskConfig.builder().maxPositions(3).build());
        RiskCheckResult result = smallEngine.check(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                3   // currentPositions >= maxPositions(3) → reject
        );
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getReason()).contains("최대 포지션 수 초과");
    }

    // ── Fixed Fractional 포지션 사이징 ───────────────────────────────────

    @Test
    @DisplayName("잔고 10,000,000 × 1% ÷ 2% 스탑 = 5,000,000")
    void calculatePositionSize_기본() {
        BigDecimal result = engine.calculatePositionSize(
                new BigDecimal("10000000"),
                new BigDecimal("0.02")
        );
        assertThat(result).isEqualByComparingTo(new BigDecimal("5000000"));
    }

    @Test
    @DisplayName("잔고 5,000,000 × 1% ÷ 5% 스탑 = 1,000,000")
    void calculatePositionSize_5pct_stop() {
        BigDecimal result = engine.calculatePositionSize(
                new BigDecimal("5000000"),
                new BigDecimal("0.05")
        );
        assertThat(result).isEqualByComparingTo(new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("스탑 거리 0 → IllegalArgumentException")
    void calculatePositionSize_zeroStop_throws() {
        assertThatThrownBy(() ->
                engine.calculatePositionSize(new BigDecimal("10000000"), BigDecimal.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("riskPercentage 커스텀(2%) 적용 시 결과 2배")
    void calculatePositionSize_customRisk() {
        RiskConfig config2pct = RiskConfig.builder()
                .defaultRiskPercentage(new BigDecimal("0.02"))
                .build();
        RiskEngine engine2 = new RiskEngine(config2pct);

        BigDecimal result = engine2.calculatePositionSize(
                new BigDecimal("10000000"),
                new BigDecimal("0.02")
        );
        // 10,000,000 × 0.02 / 0.02 = 10,000,000
        assertThat(result).isEqualByComparingTo(new BigDecimal("10000000"));
    }

    // ── 상관관계 기반 유효 슬롯 ───────────────────────────────────────────

    @Test
    @DisplayName("자산 없음 → 슬롯 0")
    void effectiveSlots_empty() {
        assertThat(engine.effectiveSlots(List.of())).isEqualTo(0);
    }

    @Test
    @DisplayName("단일 자산 → 슬롯 1")
    void effectiveSlots_single() {
        assertThat(engine.effectiveSlots(List.of("KRW-BTC"))).isEqualTo(1);
    }

    @Test
    @DisplayName("BTC + ETH (corr=0.85 > 0.7) → 슬롯 3 (2 + 패널티 1)")
    void effectiveSlots_btcEth_penalty() {
        int slots = engine.effectiveSlots(List.of("KRW-BTC", "KRW-ETH"));
        assertThat(slots).isEqualTo(3);
    }

    @Test
    @DisplayName("BTC + ETH + BNB (3쌍 모두 고상관) → 슬롯 6 (3 + 패널티 3)")
    void effectiveSlots_btcEthBnb_triplePenalty() {
        int slots = engine.effectiveSlots(List.of("KRW-BTC", "KRW-ETH", "KRW-BNB"));
        assertThat(slots).isEqualTo(6);
    }

    @Test
    @DisplayName("상관 없는 자산 쌍 → 패널티 없음")
    void effectiveSlots_uncorrelated_nopenalty() {
        // XRP는 상관 맵에 없음
        int slots = engine.effectiveSlots(List.of("KRW-BTC", "KRW-XRP"));
        assertThat(slots).isEqualTo(2);
    }

    @Test
    @DisplayName("correlationThreshold 상향(0.9) 시 BTC+ETH 패널티 미적용")
    void effectiveSlots_highThreshold_noPenalty() {
        RiskConfig highThreshold = RiskConfig.builder()
                .correlationThreshold(0.9)
                .build();
        RiskEngine engineHT = new RiskEngine(highThreshold);

        // BTC+ETH corr=0.85 < threshold=0.9 → 패널티 없음
        int slots = engineHT.effectiveSlots(List.of("KRW-BTC", "KRW-ETH"));
        assertThat(slots).isEqualTo(2);
    }
}
