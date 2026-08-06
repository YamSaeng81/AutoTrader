package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-05 회귀 테스트 — <b>익절가 절대 상한(TP_PCT_MAX 8%)</b>.
 *
 * <p><b>사고 재현</b>: TP를 SL 폭의 2배로 따라 키우다 보니 KRW-META2는 진입가 9,150에
 * TP가 <b>10,423(+14.10%)</b>로 잡혔다. 넓은 SL은 반드시 맞고 넓은 TP는 사실상 안 맞는다 —
 * 07-31 ATR 개편 이후 5일간 동적 세션 <b>익절 0건 / 손절 3건</b>이 그 결과다.</p>
 *
 * <p>이 테스트가 잠그는 것은 "손익비보다 도달 가능성이 우선"이라는 결정이다. 상한 구간에서
 * 명목 손익비는 2:1 아래로 내려가지만, 도달하지 않는 TP의 손익비는 의미가 없다.</p>
 */
class DynamicTakeProfitCapTest {

    /** 진입가와 SL 폭(%)으로 TP가 진입가 대비 몇 %인지 계산한다. */
    private double tpPctFor(String price, double slPct, BigDecimal suggested) {
        BigDecimal entry = new BigDecimal(price);
        BigDecimal sl = entry.multiply(BigDecimal.valueOf(1 - slPct / 100.0));
        BigDecimal tp = ExitRuleCalculator.resolveTakeProfitPrice(entry, sl, suggested);
        return tp.divide(entry, 8, java.math.RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    @Test
    @DisplayName("★ META2 재현 — SL 6.96%여도 TP는 14.10%가 아니라 8%로 잘린다")
    void meta2Regression_tpCapped() {
        double tpPct = tpPctFor("9150", 6.96, null);

        assertThat(tpPct)
                .as("구 동작이라면 6.96 × 2 = 13.9%대가 나온다")
                .isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("SL이 좁으면 손익비 2:1이 그대로 유지된다 — 상한은 넓은 구간에서만 개입한다")
    void narrowStopLoss_keepsRiskReward() {
        // SL 3% → TP 6% (상한 8% 미만이라 그대로)
        assertThat(tpPctFor("1000", 3.0, null))
                .isCloseTo(6.0, org.assertj.core.data.Offset.offset(0.01));

        // SL 5%(세션 하한) → TP 10% 였으나 상한 8%로 잘린다
        assertThat(tpPctFor("1000", 5.0, null))
                .isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("전략 제안 TP가 더 멀면 존중하되 상한을 넘지 못한다")
    void suggestedTakeProfit_respectedButCapped() {
        BigDecimal entry = new BigDecimal("1000");

        // 제안 TP +20% → 상한 8%로 잘림
        assertThat(tpPctFor("1000", 3.0, new BigDecimal("1200")))
                .as("제안값이라도 상한을 넘을 수는 없다")
                .isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.01));

        // 제안 TP +7% (기본 6%보다 멀고 상한 이내) → 제안값 채택
        assertThat(tpPctFor("1000", 3.0, new BigDecimal("1070")))
                .isCloseTo(7.0, org.assertj.core.data.Offset.offset(0.01));

        // 제안 TP +2% (기본 6%보다 가까움) → 기본값(더 먼 쪽) 채택 — 기존 동작 유지
        assertThat(tpPctFor("1000", 3.0, new BigDecimal("1020")))
                .isCloseTo(6.0, org.assertj.core.data.Offset.offset(0.01));

        assertThat(entry).isNotNull();
    }

    @Test
    @DisplayName("TP는 항상 진입가보다 높다 — 즉시 익절되는 값이 나오지 않는다")
    void takeProfitAlwaysAboveEntry() {
        BigDecimal entry = new BigDecimal("101");
        for (double slPct : new double[]{0.5, 3.0, 5.0, 8.0}) {
            BigDecimal sl = entry.multiply(BigDecimal.valueOf(1 - slPct / 100.0));
            assertThat(ExitRuleCalculator.resolveTakeProfitPrice(entry, sl, null))
                    .as("SL %s%% 일 때", slPct)
                    .isGreaterThan(entry);
        }
    }
}
