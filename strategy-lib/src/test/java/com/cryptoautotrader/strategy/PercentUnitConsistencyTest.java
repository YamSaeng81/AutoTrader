package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.macdstochbb.MacdStochBbStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Wave 4-M — <b>같은 이름의 파라미터는 모든 전략에서 같은 단위로 읽혀야 한다.</b>
 *
 * <p><b>무엇을 잡는 테스트인가</b><br>
 * 2026-09-22 이전에 {@code stopLossPct} 가 두 전략에서 다른 뜻이었다:
 * <ul>
 *   <li>{@code HeikinAshiStochStrategy} — 1.5 를 퍼센트로 읽고 {@code /100}</li>
 *   <li>{@code MacdStochBbStrategy} — 0.02 를 비율로 읽고 그대로 {@code 1 - x}</li>
 * </ul>
 * 같은 params 맵을 두 전략에 넘기면 <b>손절 폭이 100배 달라졌다.</b>
 * {@code LiveTradingService} 가 세션의 {@code strategy_params} 로 전략 params 를
 * 시드하므로, {@code stopLossPct: 5.0}(5% 의도)은 MacdStochBb 에서 <b>500%</b> 가 됐다 —
 * 손절이 사실상 사라지는데 예외도 경고도 나지 않는다.
 *
 * <p><b>고친 방식</b>: 단위 변환({@code /100})을
 * {@link StrategyParamUtils#getPercentAsRatio} 한 곳으로 모았다. 두 전략 모두 이 경로를 쓴다.
 * <b>나눗셈이 호출자마다 흩어져 있던 것이 결함의 발생 경로였다</b> — 규약을 문서로만 적어 두면
 * 다음 전략이 또 갈라진다.
 *
 * <p><b>커버리지의 한계를 적어 둔다</b><br>
 * 거동 검증은 {@code MacdStochBb} 에만 걸었다. {@code HeikinAshiStoch} 는
 * BUY 조건 네 개(EMA200 상회 · StochRSI 골든크로스 · 하이키나시 롱캔들 · 거래량)가
 * <b>같은 봉에서</b> 동시에 성립해야 하는데, 하이키나시는 구조적으로 스토캐스틱 교차보다
 * 2~3봉 늦어 합성 시나리오로 재현하기 어렵다(실측: 골든크로스 5회 · 롱캔들 111회 · 교집합 0회).
 * 억지로 맞춘 시나리오는 조건이 조금만 바뀌어도 <b>조용히 무력화</b>되므로 두지 않았다.
 * 대신 HeikinAshi 도 같은 {@code getPercentAsRatio} 를 거치게 바꿨고,
 * 그 변환 자체를 {@link #getPercentAsRatio_convertsUnits_inBothPrecisions} 로 덮는다.
 *
 * <p><b>뮤테이션 확인</b>: {@code MacdStochBbStrategy} 의 {@code getPercentAsRatio} 를
 * {@code getDouble} 로 되돌리면 손절 거리가 2% → 0.02% 가 되어
 * {@link #macdStochBb_readsStopLossPct_asPercent} 가 깨진다.
 */
class PercentUnitConsistencyTest {

    /** 전략에 넘길 손절·익절 (퍼센트). */
    private static final double SL_PCT = 2.0;
    private static final double TP_PCT = 4.0;

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final long H1 = 3600L;

    @Test
    @DisplayName("MACD_STOCH_BB 는 stopLossPct 를 퍼센트로 읽는다 — 2.0 이면 손절 −2%")
    void macdStochBb_readsStopLossPct_asPercent() {
        Map<String, Object> params = new HashMap<>();
        params.put("stopLossPct", SL_PCT);
        params.put("takeProfitPct", TP_PCT);

        List<Candle> series = upTrendWithOscillation();
        Strategy strategy = new MacdStochBbStrategy();

        boolean checked = false;
        for (int end = 210; end <= series.size(); end++) {
            List<Candle> window = series.subList(0, end);
            StrategySignal s = strategy.evaluate(window, params);
            if (s.getAction() != StrategySignal.Action.BUY || s.getSuggestedStopLoss() == null) {
                continue;
            }
            BigDecimal entry = window.get(window.size() - 1).getClose();
            BigDecimal slDist = entry.subtract(s.getSuggestedStopLoss())
                    .divide(entry, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            BigDecimal tpDist = s.getSuggestedTakeProfit().subtract(entry)
                    .divide(entry, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            assertThat(slDist.doubleValue())
                    .as("손절은 진입가 아래 %.1f%% 여야 한다 (비율로 읽으면 0.02%%)", SL_PCT)
                    .isCloseTo(SL_PCT, within(0.01));
            assertThat(tpDist.doubleValue())
                    .as("익절은 진입가 위 %.1f%% 여야 한다", TP_PCT)
                    .isCloseTo(TP_PCT, within(0.01));
            checked = true;
            break;
        }
        assertThat(checked)
                .as("BUY 가 한 번도 나오지 않아 단위를 검증하지 못했다 — "
                        + "시나리오가 진입 조건을 더는 만족하지 않는다면 시나리오를 고칠 것 "
                        + "(테스트를 지우면 검증 공백이 된다)")
                .isTrue();
    }

    @Test
    @DisplayName("기본값 거동은 이전과 같다 — 0.02 비율 ≡ 2.0 퍼센트")
    void defaultBehaviour_isUnchangedByTheUnitMigration() {
        List<Candle> series = upTrendWithOscillation();
        Strategy strategy = new MacdStochBbStrategy();

        for (int end = 210; end <= series.size(); end++) {
            List<Candle> window = series.subList(0, end);
            StrategySignal s = strategy.evaluate(window, Map.of());   // 파라미터 없음 = 기본값
            if (s.getAction() != StrategySignal.Action.BUY || s.getSuggestedStopLoss() == null) {
                continue;
            }
            BigDecimal entry = window.get(window.size() - 1).getClose();
            BigDecimal dist = entry.subtract(s.getSuggestedStopLoss())
                    .divide(entry, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            // 이전 코드의 기본값은 비율 0.02 = 2%. 지금은 퍼센트 2.0 = 2%. 같아야 한다.
            assertThat(dist.doubleValue()).isCloseTo(2.0, within(0.01));
            return;
        }
        throw new AssertionError("기본값 경로에서 BUY 가 나오지 않았다");
    }

    @Test
    @DisplayName("퍼센트 파라미터는 전부 스키마에 PERCENT 로 등록돼 있다")
    void schema_declaresEveryPctParamAsPercent() {
        assertThat(StrategyParamSchema.all()).isNotEmpty();
        for (StrategyParamSchema.Param p : StrategyParamSchema.all()) {
            if (p.name().endsWith("Pct")) {
                assertThat(p.unit())
                        .as("%s — 이름이 Pct 로 끝나면 퍼센트여야 한다", p.name())
                        .isEqualTo(StrategyParamSchema.Unit.PERCENT);
            }
        }
        // 결함이 났던 두 이름은 반드시 등록돼 있어야 한다
        assertThat(StrategyParamSchema.isPercent("stopLossPct")).isTrue();
        assertThat(StrategyParamSchema.isPercent("takeProfitPct")).isTrue();
    }

    @Test
    @DisplayName("getPercentAsRatio 는 퍼센트를 비율로 바꾼다 — double·BigDecimal 양쪽")
    void getPercentAsRatio_convertsUnits_inBothPrecisions() {
        assertThat(StrategyParamUtils.getPercentAsRatio(Map.of("x", 2.0), "x", 999))
                .isCloseTo(0.02, within(1e-12));
        // 값이 없으면 기본값도 퍼센트로 해석한다
        assertThat(StrategyParamUtils.getPercentAsRatio(Map.of(), "x", 1.5))
                .isCloseTo(0.015, within(1e-12));

        // BigDecimal 판 — HeikinAshiStoch 가 쓰는 경로. 구 pct() 와 스케일·반올림이 같아야 한다.
        assertThat(StrategyParamUtils.getPercentAsRatio(Map.of("x", 1.5), "x", 999, 8))
                .isEqualByComparingTo(new BigDecimal("0.015"));
        assertThat(StrategyParamUtils.getPercentAsRatio(Map.of(), "x", 3.0, 8))
                .isEqualByComparingTo(new BigDecimal("0.03"));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /**
     * 상승 추세 + 주기적 진동.
     *
     * <p>추세가 MACD 를 양수로 유지하고, 진동의 골이 StochRSI 를 과매도(K&lt;20)로 밀어넣는다.
     * 반등 첫 봉에서 히스토그램이 상승 전환하며 BUY 조건이 성립한다.
     */
    private static List<Candle> upTrendWithOscillation() {
        List<Candle> out = new ArrayList<>();
        double prev = 100.0;
        for (int i = 0; i < 400; i++) {
            double next = 100.0 + 0.5 * i + 9.0 * Math.sin(i * 2 * Math.PI / 40.0);
            double open = prev;
            double close = next;
            double high = Math.max(open, close) + 0.05;
            double low = Math.min(open, close);
            out.add(Candle.builder()
                    .time(BASE.plusSeconds((long) i * H1))
                    .open(BigDecimal.valueOf(open))
                    .high(BigDecimal.valueOf(high))
                    .low(BigDecimal.valueOf(low))
                    .close(BigDecimal.valueOf(close))
                    .volume(BigDecimal.valueOf(1000))   // 일정 — 거래량 필터를 통과시킨다
                    .build());
            prev = next;
        }
        return out;
    }
}
